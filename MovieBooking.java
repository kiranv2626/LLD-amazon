import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Amazon SDE2-style single-file LLD:
 * - Multi-city/cinema/hall model
 * - Search catalog (title/language/genre) + showtime directory
 * - Concurrency-safe seat hold (CAS) to prevent double booking
 * - Payment authorize/capture/refund
 * - Coupon pricing via Decorator
 * - Admin add movie/show
 * - Booking cancel + modify with notifications
 *
 * Java 8 compatible.
 */
public class MovieBookingSystemDemo {

    // ===================== ENUMS =====================
    enum SeatType { SILVER, GOLD, PLATINUM }
    enum SeatStatus { AVAILABLE, HELD, BOOKED }
    enum BookingStatus { PENDING, CONFIRMED, CANCELLED, PAYMENT_FAILED, EXPIRED }
    enum PaymentStatus { INITIATED, AUTHORIZED, CAPTURED, FAILED, REFUNDED }
    enum BookingChannel { ONLINE, IN_PERSON }
    enum UserRole { CUSTOMER, TICKET_AGENT, ADMIN }

    // ===================== VALUE OBJECTS =====================
    static final class Money {
        final long cents;

        Money(long cents) { this.cents = cents; }

        static Money ofDollars(double dollars) {
            long c = Math.round(dollars * 100.0);
            return new Money(c);
        }

        Money plus(Money other) { return new Money(this.cents + other.cents); }
        Money minus(Money other) { return new Money(this.cents - other.cents); }
        Money max(Money other) { return new Money(Math.max(this.cents, other.cents)); }
        Money min(Money other) { return new Money(Math.min(this.cents, other.cents)); }

        @Override public String toString() {
            return String.format("$%.2f", cents / 100.0);
        }
    }

    // ===================== USERS =====================
    static final class User {
        final String userId;
        final UserRole role;
        final boolean optedInNewMovieAlerts;

        User(String userId, UserRole role, boolean optedInNewMovieAlerts) {
            this.userId = userId;
            this.role = role;
            this.optedInNewMovieAlerts = optedInNewMovieAlerts;
        }
    }

    // ===================== ENTITIES =====================
    static final class City {
        final String name;
        final String state; // optional but useful (disambiguation + search/filter)
        final List<Cinema> cinemas = new ArrayList<>();
        City(String name, String state) { this.name = name; this.state = state; }
    }

    static final class Cinema {
        final String cinemaId;
        final String name;
        final City city;
        final List<Hall> halls = new ArrayList<>();
        Cinema(String cinemaId, String name, City city) {
            this.cinemaId = cinemaId; this.name = name; this.city = city;
        }
    }

    static final class Hall {
        final String hallId;
        final String name;
        final Map<String, Seat> seatsById = new HashMap<>();

        // R2: enforce one show at a time (no overlaps)
        final List<Show> scheduledShows = new ArrayList<>();

        Hall(String hallId, String name) { this.hallId = hallId; this.name = name; }

        boolean canSchedule(Instant start, int durationMinutes) {
            Instant end = start.plus(Duration.ofMinutes(durationMinutes));
            for (Show s : scheduledShows) {
                Instant sEnd = s.startTime.plus(Duration.ofMinutes(s.movie.durationMinutes));
                if (start.isBefore(sEnd) && end.isAfter(s.startTime)) return false;
            }
            return true;
        }

        void addShow(Show show) {
            if (!canSchedule(show.startTime, show.movie.durationMinutes)) {
                throw new IllegalStateException("Overlapping show not allowed for hall=" + hallId);
            }
            scheduledShows.add(show);
        }
    }

    static final class Seat {
        final String seatId;
        final SeatType type;
        Seat(String seatId, SeatType type) { this.seatId = seatId; this.type = type; }
    }

    static final class Movie {
        final String movieId;
        final String title;
        final String language;
        final String genre;
        final int durationMinutes;

        Movie(String movieId, String title, String language, String genre, int durationMinutes) {
            this.movieId = movieId; this.title = title; this.language = language; this.genre = genre;
            this.durationMinutes = durationMinutes;
        }

        @Override public String toString() {
            return "Movie{id=" + movieId + ", title=" + title + ", lang=" + language + ", genre=" + genre + "}";
        }
    }

    static final class Show {
        final String showId;
        final Movie movie;
        final Hall hall;
        final Instant startTime;
        final SeatInventory inventory;

        Show(String showId, Movie movie, Hall hall, Instant startTime) {
            this.showId = showId;
            this.movie = movie;
            this.hall = hall;
            this.startTime = startTime;
            this.inventory = new SeatInventory(showId, hall.seatsById);
        }

        @Override public String toString() {
            return "Show{id=" + showId + ", movie=" + movie.title + ", hall=" + hall.hallId + ", at=" + startTime + "}";
        }
    }

    static final class Ticket {
        final String ticketId;
        final String bookingId;
        final String showId;
        final String seatId;

        Ticket(String ticketId, String bookingId, String showId, String seatId) {
            this.ticketId = ticketId;
            this.bookingId = bookingId;
            this.showId = showId;
            this.seatId = seatId;
        }
    }

    static final class Booking {
        final String bookingId;
        final String userId;
        final String showId;
        final Instant createdAt;

        final List<String> seatIds = new ArrayList<>();
        final List<Ticket> tickets = new ArrayList<>();

        volatile BookingStatus status;
        volatile PaymentStatus paymentStatus;
        volatile Money totalPrice;

        Booking(String bookingId, String userId, String showId, List<String> seatIds) {
            this.bookingId = bookingId;
            this.userId = userId;
            this.showId = showId;
            this.createdAt = Instant.now();
            this.seatIds.addAll(seatIds);
            this.status = BookingStatus.PENDING;
            this.paymentStatus = PaymentStatus.INITIATED;
            this.totalPrice = new Money(0);
        }
    }

    static final class SeatHold {
        final String holdId;
        final String showId;
        final String userId;
        final List<String> seatIds;
        final Instant expiresAt;

        SeatHold(String holdId, String showId, String userId, List<String> seatIds, Instant expiresAt) {
            this.holdId = holdId;
            this.showId = showId;
            this.userId = userId;
            this.seatIds = new ArrayList<>(seatIds);
            this.expiresAt = expiresAt;
        }
    }

    // ===================== PRICING: DECORATOR =====================
    interface FareCalculator {
        Money calculate(Show show, List<String> seatIds);
    }

    static final class BaseFareCalculator implements FareCalculator {
        private final Map<SeatType, Money> fixedRates;
        BaseFareCalculator(Map<SeatType, Money> fixedRates) { this.fixedRates = new HashMap<>(fixedRates); }

        @Override
        public Money calculate(Show show, List<String> seatIds) {
            Money total = new Money(0);
            for (String seatId : seatIds) {
                Seat seat = show.hall.seatsById.get(seatId);
                if (seat == null) throw new IllegalArgumentException("Invalid seat: " + seatId);
                Money rate = fixedRates.get(seat.type);
                if (rate == null) throw new IllegalStateException("Missing rate for " + seat.type);
                total = total.plus(rate);
            }
            return total;
        }
    }

    static abstract class FareDecorator implements FareCalculator {
        protected final FareCalculator inner;
        FareDecorator(FareCalculator inner) { this.inner = inner; }
    }

    static final class CouponPercentageDecorator extends FareDecorator {
        private final String couponCode;
        private final int percentOff;
        private final Money maxDiscount;
        private final Instant expiresAt;

        CouponPercentageDecorator(FareCalculator inner, String couponCode, int percentOff, Money maxDiscount, Instant expiresAt) {
            super(inner);
            this.couponCode = couponCode;
            this.percentOff = percentOff;
            this.maxDiscount = maxDiscount;
            this.expiresAt = expiresAt;
        }

        @Override
        public Money calculate(Show show, List<String> seatIds) {
            Money base = inner.calculate(show, seatIds);
            if (Instant.now().isAfter(expiresAt) || percentOff <= 0) return base;

            long discountCents = (base.cents * percentOff) / 100L;
            Money appliedDiscount = new Money(discountCents).min(maxDiscount);
            return base.minus(appliedDiscount).max(new Money(0));
        }

        @Override public String toString() {
            return "Coupon(" + couponCode + ", " + percentOff + "%, cap=" + maxDiscount + ")";
        }
    }

    static final class CouponFlatDecorator extends FareDecorator {
        private final String couponCode;
        private final Money flatOff;
        private final Instant expiresAt;

        CouponFlatDecorator(FareCalculator inner, String couponCode, Money flatOff, Instant expiresAt) {
            super(inner);
            this.couponCode = couponCode;
            this.flatOff = flatOff;
            this.expiresAt = expiresAt;
        }

        @Override
        public Money calculate(Show show, List<String> seatIds) {
            Money base = inner.calculate(show, seatIds);
            if (Instant.now().isAfter(expiresAt)) return base;
            return base.minus(flatOff).max(new Money(0));
        }

        @Override public String toString() {
            return "Coupon(" + couponCode + ", flatOff=" + flatOff + ")";
        }
    }

    // ===================== CONCURRENCY: SEAT INVENTORY =====================
    static final class SeatInventory {

        // Seat can be:
        // AVAILABLE
        // HELD(holdId,userId,expiresAt)
        // BOOKED(bookingId,userId)
        static final class SeatState {
            final SeatStatus status;
            final String holdId;     // only HELD
            final String bookingId;  // only BOOKED
            final String userId;     // HELD or BOOKED
            final Instant expiresAt; // only HELD

            private SeatState(SeatStatus status, String holdId, String bookingId, String userId, Instant expiresAt) {
                this.status = status;
                this.holdId = holdId;
                this.bookingId = bookingId;
                this.userId = userId;
                this.expiresAt = expiresAt;
            }

            static SeatState available() { return new SeatState(SeatStatus.AVAILABLE, null, null, null, null); }
            static SeatState held(String holdId, String userId, Instant expiresAt) {
                return new SeatState(SeatStatus.HELD, holdId, null, userId, expiresAt);
            }
            static SeatState booked(String bookingId, String userId) {
                return new SeatState(SeatStatus.BOOKED, null, bookingId, userId, null);
            }
        }

        static final class HoldResult {
            final boolean success;
            final String message;
            final SeatHold hold;
            HoldResult(boolean success, String message, SeatHold hold) {
                this.success = success; this.message = message; this.hold = hold;
            }
            static HoldResult ok(SeatHold hold) { return new HoldResult(true, "OK", hold); }
            static HoldResult fail(String msg)  { return new HoldResult(false, msg, null); }
        }

        private final String showId;
        private final Map<String, Seat> seatsById;

        private final ConcurrentHashMap<String, AtomicReference<SeatState>> seatStates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, SeatHold> holds = new ConcurrentHashMap<>();

        SeatInventory(String showId, Map<String, Seat> seatsById) {
            this.showId = showId;
            this.seatsById = seatsById;
            for (String seatId : seatsById.keySet()) {
                seatStates.put(seatId, new AtomicReference<>(SeatState.available()));
            }
        }

        HoldResult holdSeats(String userId, List<String> seatIds, Duration ttl) {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(seatIds, "seatIds");
            if (seatIds.isEmpty()) return HoldResult.fail("No seats selected");

            for (String seatId : seatIds) {
                if (!seatsById.containsKey(seatId)) return HoldResult.fail("Invalid seat: " + seatId);
            }

            String holdId = UUID.randomUUID().toString();
            Instant expiresAt = Instant.now().plus(ttl);

            List<String> acquired = new ArrayList<>();

            for (String seatId : seatIds) {
                AtomicReference<SeatState> ref = seatStates.get(seatId);

                while (true) {
                    SeatState cur = ref.get();

                    // Lazy expire HELD -> AVAILABLE
                    if (cur.status == SeatStatus.HELD && cur.expiresAt != null && Instant.now().isAfter(cur.expiresAt)) {
                        ref.compareAndSet(cur, SeatState.available());
                        continue;
                    }

                    if (cur.status != SeatStatus.AVAILABLE) {
                        rollbackHeld(acquired, holdId);
                        return HoldResult.fail("Seat not available: " + seatId);
                    }

                    SeatState next = SeatState.held(holdId, userId, expiresAt);
                    if (ref.compareAndSet(cur, next)) {
                        acquired.add(seatId);
                        break;
                    }
                }
            }

            SeatHold hold = new SeatHold(holdId, showId, userId, seatIds, expiresAt);
            holds.put(holdId, hold);
            return HoldResult.ok(hold);
        }

        boolean confirmBooking(String holdId, String userId, String bookingId) {
            SeatHold hold = holds.get(holdId);
            if (hold == null) return false;
            if (!hold.userId.equals(userId)) return false;

            if (Instant.now().isAfter(hold.expiresAt)) {
                releaseHold(holdId);
                return false;
            }

            for (String seatId : hold.seatIds) {
                AtomicReference<SeatState> ref = seatStates.get(seatId);
                while (true) {
                    SeatState cur = ref.get();
                    if (cur.status == SeatStatus.HELD && holdId.equals(cur.holdId)) {
                        if (ref.compareAndSet(cur, SeatState.booked(bookingId, userId))) break;
                        continue;
                    }
                    // something changed -> fail safe
                    releaseHold(holdId);
                    return false;
                }
            }

            holds.remove(holdId);
            return true;
        }

        void releaseHold(String holdId) {
            SeatHold hold = holds.remove(holdId);
            if (hold == null) return;
            rollbackHeld(hold.seatIds, holdId);
        }

        void releaseExpiredHolds() {
            Instant now = Instant.now();
            for (Map.Entry<String, SeatHold> e : holds.entrySet()) {
                SeatHold hold = e.getValue();
                if (now.isAfter(hold.expiresAt)) {
                    if (holds.remove(e.getKey()) != null) {
                        rollbackHeld(hold.seatIds, hold.holdId);
                    }
                }
            }
        }

        // Release BOOKED -> AVAILABLE only if it belongs to the bookingId (used for cancel/modify)
        void releaseBookedSeats(String bookingId, Collection<String> seatIdsToRelease) {
            for (String seatId : seatIdsToRelease) {
                AtomicReference<SeatState> ref = seatStates.get(seatId);
                if (ref == null) continue;
                while (true) {
                    SeatState cur = ref.get();
                    if (cur.status == SeatStatus.BOOKED && bookingId.equals(cur.bookingId)) {
                        if (ref.compareAndSet(cur, SeatState.available())) break;
                        continue;
                    }
                    break;
                }
            }
        }

        Map<String, SeatStatus> snapshotSeatStatuses() {
            Map<String, SeatStatus> out = new HashMap<>();
            Instant now = Instant.now();
            for (Map.Entry<String, AtomicReference<SeatState>> e : seatStates.entrySet()) {
                SeatState st = e.getValue().get();
                if (st.status == SeatStatus.HELD && st.expiresAt != null && now.isAfter(st.expiresAt)) {
                    out.put(e.getKey(), SeatStatus.AVAILABLE);
                } else {
                    out.put(e.getKey(), st.status);
                }
            }
            return out;
        }

        private void rollbackHeld(List<String> seatIds, String holdId) {
            for (String seatId : seatIds) {
                AtomicReference<SeatState> ref = seatStates.get(seatId);
                if (ref == null) continue;

                while (true) {
                    SeatState cur = ref.get();
                    if (cur.status == SeatStatus.HELD && holdId.equals(cur.holdId)) {
                        if (ref.compareAndSet(cur, SeatState.available())) break;
                        continue;
                    }
                    break;
                }
            }
        }
    }

    // ===================== CATALOG + SHOW DIRECTORY (R3/R4) =====================
    static final class Catalog {
        private final Map<String, List<Movie>> byTitle = new HashMap<>();
        private final Map<String, List<Movie>> byLanguage = new HashMap<>();
        private final Map<String, List<Movie>> byGenre = new HashMap<>();

        void addMovie(Movie m) {
            byTitle.computeIfAbsent(norm(m.title), k -> new ArrayList<>()).add(m);
            byLanguage.computeIfAbsent(norm(m.language), k -> new ArrayList<>()).add(m);
            byGenre.computeIfAbsent(norm(m.genre), k -> new ArrayList<>()).add(m);
        }

        List<Movie> searchByTitle(String title) { return byTitle.getOrDefault(norm(title), Collections.emptyList()); }
        List<Movie> searchByLanguage(String language) { return byLanguage.getOrDefault(norm(language), Collections.emptyList()); }
        List<Movie> searchByGenre(String genre) { return byGenre.getOrDefault(norm(genre), Collections.emptyList()); }

        private String norm(String s) { return s == null ? "" : s.trim().toLowerCase(); }
    }

    static final class ShowDirectory {
        // movieId -> all shows across all cinemas/halls
        private final Map<String, List<Show>> byMovieId = new HashMap<>();

        void addShow(Show show) {
            byMovieId.computeIfAbsent(show.movie.movieId, k -> new ArrayList<>()).add(show);
        }

        List<Show> getShowtimes(String movieId) {
            return byMovieId.getOrDefault(movieId, Collections.emptyList());
        }
    }

    // ===================== PAYMENT =====================
    static final class PaymentRequest {
        final String method; // "CARD" / "CASH"
        final Money amount;
        PaymentRequest(String method, Money amount) { this.method = method; this.amount = amount; }
    }

    static final class PaymentResult {
        final boolean success;
        final String txnId;
        final String message;
        PaymentResult(boolean success, String txnId, String message) {
            this.success = success; this.txnId = txnId; this.message = message;
        }
        static PaymentResult ok(String txnId) { return new PaymentResult(true, txnId, "OK"); }
        static PaymentResult fail(String msg) { return new PaymentResult(false, null, msg); }
    }

    interface PaymentService {
        PaymentResult authorize(PaymentRequest req);
        PaymentResult capture(String txnId);
        PaymentResult refund(String txnId);
    }

    static final class DummyPaymentService implements PaymentService {
        @Override public PaymentResult authorize(PaymentRequest req) {
            if (req.amount.cents <= 0) return PaymentResult.fail("Invalid amount");
            return PaymentResult.ok(UUID.randomUUID().toString());
        }
        @Override public PaymentResult capture(String txnId) { return PaymentResult.ok(txnId); }
        @Override public PaymentResult refund(String txnId) { return PaymentResult.ok(txnId); }
    }

    // ===================== NOTIFICATION (R14/R15) =====================
    interface NotificationService {
        void newMovieAdded(User user, Movie movie);
        void bookingConfirmed(String userId, Booking booking);
        void bookingCancelled(String userId, String bookingId);
        void bookingModified(String userId, Booking booking);
        void bookingFailed(String userId, String reason);
    }

    static final class ConsoleNotificationService implements NotificationService {
        @Override public void newMovieAdded(User user, Movie movie) {
            System.out.println("[NOTIFY] user=" + user.userId + " NEW_MOVIE: " + movie.title);
        }
        @Override public void bookingConfirmed(String userId, Booking booking) {
            System.out.println("[NOTIFY] user=" + userId + " CONFIRMED bookingId=" + booking.bookingId
                    + " seats=" + booking.seatIds + " total=" + booking.totalPrice);
        }
        @Override public void bookingCancelled(String userId, String bookingId) {
            System.out.println("[NOTIFY] user=" + userId + " CANCELLED bookingId=" + bookingId);
        }
        @Override public void bookingModified(String userId, Booking booking) {
            System.out.println("[NOTIFY] user=" + userId + " MODIFIED bookingId=" + booking.bookingId
                    + " seats=" + booking.seatIds + " total=" + booking.totalPrice);
        }
        @Override public void bookingFailed(String userId, String reason) {
            System.out.println("[NOTIFY] user=" + userId + " FAILED reason=" + reason);
        }
    }

    // ===================== ADMIN SERVICE (R12 + new movie notif R14) =====================
    static final class AdminService {
        private final Catalog catalog;
        private final ShowDirectory showDirectory;
        private final NotificationService notificationService;
        private final List<User> users; // in real life: DB query

        AdminService(Catalog catalog, ShowDirectory showDirectory, NotificationService notificationService, List<User> users) {
            this.catalog = catalog;
            this.showDirectory = showDirectory;
            this.notificationService = notificationService;
            this.users = users;
        }

        void addMovie(Movie movie) {
            catalog.addMovie(movie);
            for (User u : users) {
                if (u.optedInNewMovieAlerts) {
                    notificationService.newMovieAdded(u, movie);
                }
            }
        }

        void addShow(Hall hall, Show show) {
            hall.addShow(show);            // enforces "one show at a time" per hall (R2)
            showDirectory.addShow(show);   // enables global showtime listing (R3)
        }
    }

    // ===================== BOOKING SERVICE =====================
    static final class BookingService {
        private final Map<String, Show> shows; // showId -> Show
        private final PaymentService paymentService;
        private final NotificationService notificationService;

        // pretend DB tables
        private final ConcurrentHashMap<String, Booking> bookings = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> bookingTxn = new ConcurrentHashMap<>(); // bookingId -> txnId

        BookingService(Map<String, Show> shows, PaymentService paymentService, NotificationService notificationService) {
            this.shows = shows;
            this.paymentService = paymentService;
            this.notificationService = notificationService;
        }

        SeatHold createSeatHold(String userId, String showId, List<String> seatIds, Duration ttl) {
            Show show = mustGetShow(showId);
            show.inventory.releaseExpiredHolds(); // in real: background job
            SeatInventory.HoldResult res = show.inventory.holdSeats(userId, seatIds, ttl);
            if (!res.success) throw new RuntimeException("Hold failed: " + res.message);
            return res.hold;
        }

        Booking payAndConfirm(User actor,
                              BookingChannel channel,
                              String holdId,
                              String showId,
                              List<String> seatIds,
                              FareCalculator calculator,
                              PaymentRequest paymentRequest) {

            Show show = mustGetShow(showId);

            // R7 enforcement
            if (channel == BookingChannel.ONLINE) {
                if (actor.role != UserRole.CUSTOMER) throw new IllegalArgumentException("ONLINE requires CUSTOMER");
                if (!"CARD".equalsIgnoreCase(paymentRequest.method)) {
                    show.inventory.releaseHold(holdId);
                    notificationService.bookingFailed(actor.userId, "Online booking supports CARD only");
                    throw new IllegalArgumentException("Online booking supports CARD only");
                }
            } else { // IN_PERSON
                if (!(actor.role == UserRole.TICKET_AGENT || actor.role == UserRole.CUSTOMER)) {
                    throw new IllegalArgumentException("IN_PERSON requires CUSTOMER or TICKET_AGENT");
                }
                if (!("CARD".equalsIgnoreCase(paymentRequest.method) || "CASH".equalsIgnoreCase(paymentRequest.method))) {
                    throw new IllegalArgumentException("In-person supports CASH or CARD");
                }
            }

            Money finalAmount = calculator.calculate(show, seatIds);

            Booking booking = new Booking(UUID.randomUUID().toString(), actor.userId, showId, seatIds);
            booking.totalPrice = finalAmount;

            PaymentResult auth = paymentService.authorize(new PaymentRequest(paymentRequest.method, finalAmount));
            if (!auth.success) {
                booking.status = BookingStatus.PAYMENT_FAILED;
                booking.paymentStatus = PaymentStatus.FAILED;
                show.inventory.releaseHold(holdId);
                notificationService.bookingFailed(actor.userId, "Payment authorize failed");
                return booking;
            }
            booking.paymentStatus = PaymentStatus.AUTHORIZED;

            PaymentResult cap = paymentService.capture(auth.txnId);
            if (!cap.success) {
                booking.status = BookingStatus.PAYMENT_FAILED;
                booking.paymentStatus = PaymentStatus.FAILED;
                show.inventory.releaseHold(holdId);
                notificationService.bookingFailed(actor.userId, "Payment capture failed");
                return booking;
            }
            booking.paymentStatus = PaymentStatus.CAPTURED;
            bookingTxn.put(booking.bookingId, auth.txnId);

            boolean confirmed = show.inventory.confirmBooking(holdId, actor.userId, booking.bookingId);
            if (!confirmed) {
                booking.status = BookingStatus.EXPIRED;
                notificationService.bookingFailed(actor.userId, "Hold expired or seats changed");
                return booking;
            }

            booking.status = BookingStatus.CONFIRMED;

            // R10: one ticket per seat
            booking.tickets.clear();
            for (String seatId : seatIds) {
                booking.tickets.add(new Ticket(UUID.randomUUID().toString(), booking.bookingId, showId, seatId));
            }

            bookings.put(booking.bookingId, booking);
            notificationService.bookingConfirmed(actor.userId, booking);
            return booking;
        }

        boolean cancelBooking(String userId, String bookingId) {
            Booking b = bookings.get(bookingId);
            if (b == null) return false;
            if (!b.userId.equals(userId)) return false;
            if (b.status != BookingStatus.CONFIRMED) return false;

            Show show = mustGetShow(b.showId);

            // Release booked seats for this booking
            show.inventory.releaseBookedSeats(b.bookingId, b.seatIds);

            // Refund if we have txn
            String txnId = bookingTxn.get(bookingId);
            if (txnId != null) {
                paymentService.refund(txnId);
                b.paymentStatus = PaymentStatus.REFUNDED;
            }

            b.status = BookingStatus.CANCELLED;
            notificationService.bookingCancelled(userId, bookingId);
            return true;
        }

        Booking modifyBooking(User user,
                              String bookingId,
                              List<String> newSeatIds,
                              Duration ttl,
                              FareCalculator calculator) {

            Booking b = bookings.get(bookingId);
            if (b == null) throw new IllegalArgumentException("Invalid bookingId");
            if (!b.userId.equals(user.userId)) throw new IllegalArgumentException("Not owner");
            if (b.status != BookingStatus.CONFIRMED) throw new IllegalStateException("Only CONFIRMED can be modified");

            Show show = mustGetShow(b.showId);

            // 1) hold new seats first (so we don't lose old seats if new not available)
            SeatHold newHold = createSeatHold(user.userId, b.showId, newSeatIds, ttl);

            // 2) confirm new seats -> BOOKED under same bookingId
            boolean ok = show.inventory.confirmBooking(newHold.holdId, user.userId, b.bookingId);
            if (!ok) {
                notificationService.bookingFailed(user.userId, "Modify failed (hold expired/seats changed)");
                throw new IllegalStateException("Modify failed");
            }

            // 3) release old seats that are not in newSeatIds
            Set<String> newSet = new HashSet<>(newSeatIds);
            List<String> toRelease = new ArrayList<>();
            for (String oldSeat : b.seatIds) {
                if (!newSet.contains(oldSeat)) toRelease.add(oldSeat);
            }
            show.inventory.releaseBookedSeats(b.bookingId, toRelease);

            // 4) update booking seats + tickets + price
            b.seatIds.clear();
            b.seatIds.addAll(newSeatIds);

            b.tickets.clear();
            for (String seatId : newSeatIds) {
                b.tickets.add(new Ticket(UUID.randomUUID().toString(), b.bookingId, b.showId, seatId));
            }

            b.totalPrice = calculator.calculate(show, newSeatIds);

            notificationService.bookingModified(user.userId, b);
            return b;
        }

        private Show mustGetShow(String showId) {
            Show show = shows.get(showId);
            if (show == null) throw new IllegalArgumentException("Invalid showId: " + showId);
            return show;
        }
    }

    // ===================== DEMO =====================
    public static void main(String[] args) throws Exception {

        // ===== Setup (R1) =====
        City seattle = new City("Seattle", "WA");
        City chicago = new City("Chicago", "IL");

        Cinema c1 = new Cinema("C1", "Downtown Cinema", seattle);
        Cinema c2 = new Cinema("C2", "Lakeside Cinema", chicago);
        seattle.cinemas.add(c1);
        chicago.cinemas.add(c2);

        Hall h1 = new Hall("H1", "Hall-1");
        Hall h2 = new Hall("H2", "Hall-2");
        c1.halls.add(h1);
        c2.halls.add(h2);

        // seats
        h1.seatsById.put("A1", new Seat("A1", SeatType.PLATINUM));
        h1.seatsById.put("A2", new Seat("A2", SeatType.GOLD));
        h1.seatsById.put("A3", new Seat("A3", SeatType.SILVER));

        h2.seatsById.put("B1", new Seat("B1", SeatType.PLATINUM));
        h2.seatsById.put("B2", new Seat("B2", SeatType.GOLD));

        // movies
        Movie inception = new Movie("M1", "Inception", "English", "Sci-Fi", 148);
        Movie interstellar = new Movie("M2", "Interstellar", "English", "Sci-Fi", 169);

        // users
        List<User> users = Arrays.asList(
                new User("user-1", UserRole.CUSTOMER, true),
                new User("user-2", UserRole.CUSTOMER, false),
                new User("agent-1", UserRole.TICKET_AGENT, false),
                new User("admin-1", UserRole.ADMIN, false)
        );

        NotificationService notif = new ConsoleNotificationService();
        Catalog catalog = new Catalog();
        ShowDirectory showDirectory = new ShowDirectory();
        AdminService adminService = new AdminService(catalog, showDirectory, notif, users);

        // Admin adds movies (R12 + new movie notify R14)
        adminService.addMovie(inception);
        adminService.addMovie(interstellar);

        // shows (R2 + R3)
        Show s1 = new Show("S1", inception, h1, Instant.now().plus(Duration.ofHours(2)));
        Show s2 = new Show("S2", inception, h2, Instant.now().plus(Duration.ofHours(3)));
        adminService.addShow(h1, s1);
        adminService.addShow(h2, s2);

        // Search (R4)
        System.out.println("Search title=Inception -> " + catalog.searchByTitle("Inception"));
        System.out.println("Search language=English -> count=" + catalog.searchByLanguage("English").size());
        System.out.println("Showtimes for movie M1 -> " + showDirectory.getShowtimes("M1"));

        // Pricing base + coupon decorator
        Map<SeatType, Money> rates = new HashMap<>();
        rates.put(SeatType.SILVER, Money.ofDollars(10));
        rates.put(SeatType.GOLD, Money.ofDollars(12));
        rates.put(SeatType.PLATINUM, Money.ofDollars(15));

        FareCalculator base = new BaseFareCalculator(rates);
        FareCalculator withCoupon = new CouponPercentageDecorator(
                base, "SAVE10", 10, Money.ofDollars(5), Instant.now().plus(Duration.ofDays(1))
        );

        // Booking service
        Map<String, Show> shows = new HashMap<>();
        shows.put(s1.showId, s1);
        shows.put(s2.showId, s2);

        BookingService bookingService = new BookingService(shows, new DummyPaymentService(), notif);

        // ===== Concurrency Demo (R11) =====
        System.out.println("\n=== CONCURRENCY DEMO: two users try same seat A1 on show S1 ===");
        String showId = "S1";
        List<String> targetSeats = Arrays.asList("A1");
        Duration holdTtl = Duration.ofSeconds(8);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGun = new CountDownLatch(1);

        Callable<String> u1 = () -> runUserFlow(
                new User("user-1", UserRole.CUSTOMER, false),
                BookingChannel.ONLINE,
                bookingService,
                showId, targetSeats, holdTtl,
                withCoupon,
                startGun
        );

        Callable<String> u2 = () -> runUserFlow(
                new User("user-2", UserRole.CUSTOMER, false),
                BookingChannel.ONLINE,
                bookingService,
                showId, targetSeats, holdTtl,
                withCoupon,
                startGun
        );

        Future<String> f1 = pool.submit(u1);
        Future<String> f2 = pool.submit(u2);

        System.out.println("Both users ready... start!");
        startGun.countDown();

        String r1 = f1.get();
        String r2 = f2.get();
        System.out.println("Result-1: " + r1);
        System.out.println("Result-2: " + r2);

        System.out.println("Seat snapshot S1: " + s1.inventory.snapshotSeatStatuses());

        // ===== Cancel + Modify demo (R14/R15) =====
        // Pick the CONFIRMED bookingId from whichever succeeded
        String bookingId = parseBookingIdFromResult(r1);
        if (bookingId == null) bookingId = parseBookingIdFromResult(r2);

        if (bookingId != null) {
            System.out.println("\n=== MODIFY booking: switch to seat A2 (if available) ===");
            try {
                bookingService.modifyBooking(
                        new User("user-1", UserRole.CUSTOMER, false),
                        bookingId,
                        Arrays.asList("A2"),
                        Duration.ofSeconds(8),
                        withCoupon
                );
            } catch (Exception e) {
                System.out.println("Modify failed: " + e.getMessage());
            }
            System.out.println("Seat snapshot S1 after modify: " + s1.inventory.snapshotSeatStatuses());

            System.out.println("\n=== CANCEL booking ===");
            boolean cancelled = bookingService.cancelBooking("user-1", bookingId);
            System.out.println("Cancelled=" + cancelled);
            System.out.println("Seat snapshot S1 after cancel: " + s1.inventory.snapshotSeatStatuses());
        }

        pool.shutdownNow();
    }

    private static String runUserFlow(User actor,
                                      BookingChannel channel,
                                      BookingService bookingService,
                                      String showId,
                                      List<String> seatIds,
                                      Duration holdTtl,
                                      FareCalculator calculator,
                                      CountDownLatch startGun) {
        try {
            startGun.await();

            // 1) hold
            SeatHold hold = bookingService.createSeatHold(actor.userId, showId, seatIds, holdTtl);

            // simulate checkout time
            Thread.sleep(150);

            // 2) pay + confirm
            Booking booking = bookingService.payAndConfirm(
                    actor,
                    channel,
                    hold.holdId,
                    hold.showId,
                    hold.seatIds,
                    calculator,
                    new PaymentRequest("CARD", new Money(0)) // amount ignored, computed by calculator
            );

            return booking.status + " bookingId=" + booking.bookingId + " total=" + booking.totalPrice;

        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private static String parseBookingIdFromResult(String s) {
        if (s == null) return null;
        int idx = s.indexOf("bookingId=");
        if (idx < 0) return null;
        int start = idx + "bookingId=".length();
        int end = s.indexOf(' ', start);
        if (end < 0) end = s.length();
        String id = s.substring(start, end).trim();
        return id.isEmpty() ? null : id;
    }
}
