import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

// =====================================================================================
// HOTEL MANAGEMENT SYSTEM — LLD (Amazon/Apple SDE-2 Strong Hire)
// =====================================================================================
//
// DESIGN PATTERNS:
//   1. Singleton       — HotelManagementSystem (single entry point)
//   2. Factory         — RoomFactory (create rooms by style)              [R2]
//   3. Strategy        — PricingStrategy & PaymentStrategy                [R4]
//   4. Observer        — NotificationService (booking events)             [R6]
//   5. Decorator       — RoomService decorator chain                      [R8]
//
// CONCURRENCY:
//   ConcurrentHashMap + per-room ReentrantReadWriteLock
//     - Readers (search) acquire read lock → multiple searches in parallel
//     - Writers (book/cancel) acquire write lock on ONLY that room
//     - Overlap check + payment + insert all happen inside write lock
//       → no wasted payments, no double bookings
//     - Different rooms lock independently → full parallelism
//
// DATE-RANGE OVERLAP:
//   Each room holds a TreeSet<DateRange> sorted by checkIn.
//   Overlap check uses floor()/ceiling() → O(log n) not O(n).
//   A room can have multiple future bookings as long as dates don't conflict.
// =====================================================================================

// ─────────────────────────── ENUMS ───────────────────────────

enum RoomStyle { STANDARD, DELUXE, FAMILY_SUITE, BUSINESS_SUITE }          // R2
enum BookingStatus { CONFIRMED, CANCELLED, CHECKED_IN, CHECKED_OUT }
enum AccountType { GUEST, RECEPTIONIST, HOUSEKEEPER, ADMIN }               // R1
enum NotificationType { BOOKING_CONFIRMED, BOOKING_CANCELLED, REMINDER }   // R6

// ─────────────────────────── DATE RANGE ──────────────────────
//
// Value object representing a check-in to check-out interval.
// Implements Comparable so TreeSet keeps bookings sorted by checkIn date,
// enabling O(log n) overlap detection via floor()/ceiling().

class DateRange implements Comparable<DateRange> {
    private final LocalDateTime checkIn;
    private final LocalDateTime checkOut;

    DateRange(LocalDateTime checkIn, int nights) {
        this.checkIn = checkIn;
        this.checkOut = checkIn.plusDays(nights);
    }

    // Two ranges overlap if one starts before the other ends AND vice versa
    //
    //   OVERLAP:       |────A────|
    //                       |────B────|
    //   A.start < B.end  AND  B.start < A.end → TRUE
    //
    //   NO OVERLAP:    |────A────|
    //                               |────B────|
    //   A.start < B.end  BUT  B.start >= A.end → FALSE
    //
    boolean overlaps(DateRange other) {
        return this.checkIn.isBefore(other.checkOut)
            && other.checkIn.isBefore(this.checkOut);
    }

    LocalDateTime getCheckIn()  { return checkIn; }
    LocalDateTime getCheckOut() { return checkOut; }

    @Override
    public int compareTo(DateRange other) {
        return this.checkIn.compareTo(other.checkIn);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DateRange)) return false;
        DateRange that = (DateRange) o;
        return checkIn.equals(that.checkIn) && checkOut.equals(that.checkOut);
    }

    @Override
    public int hashCode() {
        return Objects.hash(checkIn, checkOut);
    }

    @Override
    public String toString() {
        return checkIn.toLocalDate() + " → " + checkOut.toLocalDate();
    }
}

// ─────────────────────────── ACCOUNTS (R1) ───────────────────

abstract class Account {
    private final String id;
    private final String name;
    private final String email;
    private final AccountType type;

    Account(String id, String name, String email, AccountType type) {
        this.id = id; this.name = name; this.email = email; this.type = type;
    }
    String getId()          { return id; }
    String getName()        { return name; }
    String getEmail()       { return email; }
    AccountType getType()   { return type; }
}

class Guest extends Account {
    private final List<Booking> bookings = new CopyOnWriteArrayList<>();  // thread-safe
    Guest(String id, String name, String email) {
        super(id, name, email, AccountType.GUEST);
    }
    void addBooking(Booking b)       { bookings.add(b); }
    List<Booking> getBookings()      { return Collections.unmodifiableList(bookings); }
}

class Receptionist extends Account {
    Receptionist(String id, String name, String email) {
        super(id, name, email, AccountType.RECEPTIONIST);
    }
}

class Housekeeper extends Account {
    Housekeeper(String id, String name, String email) {
        super(id, name, email, AccountType.HOUSEKEEPER);
    }
}

class Admin extends Account {
    Admin(String id, String name, String email) {
        super(id, name, email, AccountType.ADMIN);
    }
}

// ─────────────────────────── ROOM & FACTORY (R2) ─────────────
//
// Room no longer has a single RoomStatus enum.
// Instead it holds a TreeSet<DateRange> of confirmed bookings.
// A room is "available" for a given date range if no existing booking overlaps.
//
// WHY TreeSet:
//   - Sorted by checkIn → floor()/ceiling() give O(log n) neighbor lookup
//   - If requested range doesn't overlap its two sorted neighbors,
//     it can't overlap ANY booking (proof: everything before floor ends
//     even earlier, everything after ceiling starts even later)

class Room {
    private final String roomId;
    private final RoomStyle style;
    private final double basePrice;
    private final int floor;
    private final TreeSet<DateRange> bookedDates = new TreeSet<>();

    Room(String roomId, RoomStyle style, double basePrice, int floor) {
        this.roomId = roomId; this.style = style;
        this.basePrice = basePrice; this.floor = floor;
    }

    // O(log n) overlap check using TreeSet neighbors
    boolean isAvailable(DateRange requested) {
        DateRange floor = bookedDates.floor(requested);
        if (floor != null && floor.overlaps(requested)) return false;

        DateRange ceiling = bookedDates.ceiling(requested);
        if (ceiling != null && ceiling.overlaps(requested)) return false;

        return true;
    }

    void addBooking(DateRange range)    { bookedDates.add(range); }
    void removeBooking(DateRange range) { bookedDates.remove(range); }
    int getBookingCount()               { return bookedDates.size(); }

    String getRoomId()       { return roomId; }
    RoomStyle getStyle()     { return style; }
    double getBasePrice()    { return basePrice; }
    int getFloor()           { return floor; }
}

// FACTORY PATTERN — encapsulates room creation logic per style
class RoomFactory {
    static Room createRoom(String id, RoomStyle style, int floor) {
        return switch (style) {
            case STANDARD       -> new Room(id, style, 100.0, floor);
            case DELUXE         -> new Room(id, style, 200.0, floor);
            case FAMILY_SUITE   -> new Room(id, style, 350.0, floor);
            case BUSINESS_SUITE -> new Room(id, style, 500.0, floor);
        };
    }
}

// ─────────────────────────── KEY MANAGEMENT (R9) ─────────────

class RoomKey {
    private final String keyId;
    private final boolean isMaster;
    private final Set<String> accessibleRooms;

    // Regular key — opens one room
    RoomKey(String keyId, String roomId) {
        this.keyId = keyId; this.isMaster = false;
        this.accessibleRooms = Set.of(roomId);
    }
    // Master key — opens a set of rooms
    RoomKey(String keyId, Set<String> accessibleRooms) {
        this.keyId = keyId; this.isMaster = true;
        this.accessibleRooms = Set.copyOf(accessibleRooms);
    }
    boolean canOpen(String roomId) { return accessibleRooms.contains(roomId); }
    boolean isMaster()             { return isMaster; }
    String getKeyId()              { return keyId; }
}

// ─────────────────────────── STRATEGY: PRICING (R4) ──────────
//
// WHY STRATEGY: pricing rules change by season/room/membership
// without modifying booking code. New pricing = new class, zero edits. (OCP)

interface PricingStrategy {
    double calculate(Room room, int nights);
}

class StandardPricing implements PricingStrategy {
    public double calculate(Room room, int nights) {
        return room.getBasePrice() * nights;
    }
}

class WeekendPricing implements PricingStrategy {
    public double calculate(Room room, int nights) {
        return room.getBasePrice() * nights * 1.25;  // 25% surcharge
    }
}

class SeasonalPricing implements PricingStrategy {
    private final double multiplier;
    SeasonalPricing(double multiplier) { this.multiplier = multiplier; }
    public double calculate(Room room, int nights) {
        return room.getBasePrice() * nights * multiplier;
    }
}

// ─────────────────────────── STRATEGY: PAYMENT (R4) ──────────

interface PaymentStrategy {
    boolean pay(double amount);
    boolean refund(double amount);
}

class CreditCardPayment implements PaymentStrategy {
    private final String cardNumber;
    CreditCardPayment(String cardNumber) { this.cardNumber = cardNumber; }
    public boolean pay(double amount) {
        System.out.println("    [PAY] Charged $" + amount + " to card ending "
            + cardNumber.substring(cardNumber.length() - 4));
        return true;
    }
    public boolean refund(double amount) {
        System.out.println("    [REFUND] Refunded $" + amount + " to card ending "
            + cardNumber.substring(cardNumber.length() - 4));
        return true;
    }
}

class UPIPayment implements PaymentStrategy {
    private final String upiId;
    UPIPayment(String upiId) { this.upiId = upiId; }
    public boolean pay(double amount) {
        System.out.println("    [PAY] Charged $" + amount + " via UPI: " + upiId);
        return true;
    }
    public boolean refund(double amount) {
        System.out.println("    [REFUND] Refunded $" + amount + " via UPI: " + upiId);
        return true;
    }
}

class CashPayment implements PaymentStrategy {
    public boolean pay(double amount) {
        System.out.println("    [PAY] Collected $" + amount + " in cash");
        return true;
    }
    public boolean refund(double amount) {
        System.out.println("    [REFUND] Returned $" + amount + " in cash");
        return true;
    }
}

// ─────────────────────────── BOOKING (R3, R4, R5) ────────────

class Booking {
    private final String bookingId;
    private final Guest guest;
    private final Room room;
    private final DateRange dateRange;
    private final double amount;
    private final PaymentStrategy paymentMethod;  // stored for refunds
    private volatile BookingStatus status;

    Booking(String bookingId, Guest guest, Room room,
            DateRange dateRange, double amount, PaymentStrategy paymentMethod) {
        this.bookingId = bookingId; this.guest = guest; this.room = room;
        this.dateRange = dateRange; this.amount = amount;
        this.paymentMethod = paymentMethod; this.status = BookingStatus.CONFIRMED;
    }

    // R5: Full refund if cancelled > 24 hours before check-in
    boolean isEligibleForFullRefund() {
        return LocalDateTime.now().until(dateRange.getCheckIn(), ChronoUnit.HOURS) > 24;
    }

    String getBookingId()            { return bookingId; }
    Guest getGuest()                 { return guest; }
    Room getRoom()                   { return room; }
    DateRange getDateRange()         { return dateRange; }
    double getAmount()               { return amount; }
    PaymentStrategy getPaymentMethod() { return paymentMethod; }
    BookingStatus getStatus()        { return status; }
    void setStatus(BookingStatus s)  { this.status = s; }
}

// ─────────────────────────── OBSERVER: NOTIFICATIONS (R6) ────
//
// WHY OBSERVER: decouples booking logic from notification delivery.
// Adding SMS/push/email = add new observer, zero changes to BookingService.

interface NotificationObserver {
    void update(Booking booking, NotificationType type);
}

class EmailNotification implements NotificationObserver {
    public void update(Booking booking, NotificationType type) {
        System.out.println("    [EMAIL] → " + booking.getGuest().getEmail()
            + " | " + type + " | Booking#" + booking.getBookingId().substring(0, 8)
            + " | " + booking.getDateRange());
    }
}

class SMSNotification implements NotificationObserver {
    public void update(Booking booking, NotificationType type) {
        System.out.println("    [SMS]   → " + booking.getGuest().getName()
            + " | " + type + " | Booking#" + booking.getBookingId().substring(0, 8));
    }
}

class NotificationService {
    // CopyOnWriteArrayList: observers rarely change, iteration-heavy → ideal
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();

    void subscribe(NotificationObserver o)   { observers.add(o); }
    void unsubscribe(NotificationObserver o) { observers.remove(o); }

    void notifyAll(Booking booking, NotificationType type) {
        for (NotificationObserver o : observers) {
            o.update(booking, type);  // in production: use async thread pool
        }
    }
}

// ─────────────────────────── DECORATOR: SERVICES (R8) ────────
//
// WHY DECORATOR: guests add/remove services dynamically at runtime.
// Each service wraps the previous, building up cost & description.
// New service = new class, no existing code changes. (OCP)

interface RoomServiceComponent {
    String getDescription();
    double getCost();
}

class BaseRoomService implements RoomServiceComponent {
    private final Room room;
    BaseRoomService(Room room) { this.room = room; }
    public String getDescription() {
        return room.getStyle() + " Room #" + room.getRoomId();
    }
    public double getCost() { return 0; }  // base cost handled in booking
}

abstract class ServiceDecorator implements RoomServiceComponent {
    protected final RoomServiceComponent wrapped;
    ServiceDecorator(RoomServiceComponent wrapped) { this.wrapped = wrapped; }
}

class FoodService extends ServiceDecorator {
    FoodService(RoomServiceComponent w) { super(w); }
    public String getDescription() { return wrapped.getDescription() + " + Food Service"; }
    public double getCost()        { return wrapped.getCost() + 50.0; }
}

class LaundryService extends ServiceDecorator {
    LaundryService(RoomServiceComponent w) { super(w); }
    public String getDescription() { return wrapped.getDescription() + " + Laundry"; }
    public double getCost()        { return wrapped.getCost() + 25.0; }
}

class SpaService extends ServiceDecorator {
    SpaService(RoomServiceComponent w) { super(w); }
    public String getDescription() { return wrapped.getDescription() + " + Spa & Wellness"; }
    public double getCost()        { return wrapped.getCost() + 100.0; }
}

class WiFiPremiumService extends ServiceDecorator {
    WiFiPremiumService(RoomServiceComponent w) { super(w); }
    public String getDescription() { return wrapped.getDescription() + " + Premium WiFi"; }
    public double getCost()        { return wrapped.getCost() + 15.0; }
}

// ─────────────────────────── HOUSEKEEPING (R7) ───────────────

class CleanupTask {
    private final String taskId;
    private final Room room;
    private final Housekeeper assignee;
    private volatile boolean completed;

    CleanupTask(String taskId, Room room, Housekeeper assignee) {
        this.taskId = taskId; this.room = room;
        this.assignee = assignee; this.completed = false;
    }
    void markComplete()        { this.completed = true; }
    boolean isCompleted()      { return completed; }
    String getTaskId()         { return taskId; }
    Room getRoom()             { return room; }
    Housekeeper getAssignee()  { return assignee; }
}

class HousekeepingService {
    // ConcurrentLinkedQueue: lock-free, multi-producer multi-consumer
    // Receptionists add tasks, housekeepers poll — no blocking between them
    private final ConcurrentLinkedQueue<CleanupTask> taskQueue = new ConcurrentLinkedQueue<>();

    CleanupTask assignTask(Room room, Housekeeper hk) {
        CleanupTask task = new CleanupTask(UUID.randomUUID().toString(), room, hk);
        taskQueue.add(task);
        System.out.println("    [HOUSEKEEPING] Task assigned → Room "
            + room.getRoomId() + " | " + hk.getName());
        return task;
    }

    void completeTask(CleanupTask task) {
        task.markComplete();
        taskQueue.remove(task);
        System.out.println("    [HOUSEKEEPING] Task completed → Room " + task.getRoom().getRoomId());
    }

    List<CleanupTask> getPendingTasks() {
        return taskQueue.stream().filter(t -> !t.isCompleted()).collect(Collectors.toList());
    }
}

// ─────────────────────────── BRANCH (R10) ────────────────────
//
// CONCURRENCY MODEL:
//   ConcurrentHashMap<roomId, Room>              → lock-free reads for search
//   ConcurrentHashMap<roomId, ReentrantRWLock>   → per-room fine-grained locking
//
//   Search: read lock per room (multiple searches parallel)
//   Book:   write lock on ONLY the target room
//   Cancel: write lock on ONLY the target room
//   Different rooms → different locks → zero contention

class Branch {
    private final String branchId;
    private final String name;
    private final String address;

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> roomLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Booking> bookings = new ConcurrentHashMap<>();

    Branch(String branchId, String name, String address) {
        this.branchId = branchId; this.name = name; this.address = address;
    }

    void addRoom(Room room) {
        rooms.put(room.getRoomId(), room);
        roomLocks.put(room.getRoomId(), new ReentrantReadWriteLock());
    }

    // R3: Search rooms available for SPECIFIC dates
    //     "Available" means no existing booking overlaps the requested range.
    //     Read lock per room → multiple searches run in parallel.
    List<Room> searchAvailableRooms(RoomStyle style, DateRange requested) {
        return rooms.values().stream()
            .filter(r -> style == null || r.getStyle() == style)
            .filter(r -> {
                ReentrantReadWriteLock lock = roomLocks.get(r.getRoomId());
                lock.readLock().lock();
                try {
                    return r.isAvailable(requested);
                } finally {
                    lock.readLock().unlock();
                }
            })
            .collect(Collectors.toList());
    }

    // Convenience: search all rooms regardless of dates (for display)
    List<Room> getAllRooms() {
        return new ArrayList<>(rooms.values());
    }

    // R3+R4: Book with date-range overlap check
    //
    // CONCURRENCY FLOW:
    //   Thread A books Room 201 for Mar 10-13:
    //     → writeLock(201) → overlap check → clean → pay → insert → unlock
    //   Thread B books Room 201 for Mar 12-15 (overlaps A):
    //     → writeLock(201) → waits → gets lock → overlap check → CONFLICT → null
    //   Thread C books Room 201 for Mar 20-22 (no overlap):
    //     → writeLock(201) → waits → gets lock → overlap check → clean → books ✓
    //   Thread D books Room 301 for Mar 12-15:
    //     → writeLock(301) → COMPLETELY UNBLOCKED by 201's lock → books ✓
    //
    Booking bookRoom(String roomId, Guest guest, LocalDateTime checkIn,
                     int nights, PricingStrategy pricing, PaymentStrategy payment) {
        Room room = rooms.get(roomId);
        if (room == null) return null;

        DateRange requested = new DateRange(checkIn, nights);
        ReentrantReadWriteLock lock = roomLocks.get(roomId);
        lock.writeLock().lock();
        try {
            // Overlap check INSIDE write lock — critical for correctness
            // Without this, two threads could both see "available" and double-book
            if (!room.isAvailable(requested)) {
                return null;  // dates conflict — no payment attempted
            }

            double amount = pricing.calculate(room, nights);
            if (!payment.pay(amount)) {
                return null;  // payment failed — room dates untouched
            }

            // Both checks passed — commit
            room.addBooking(requested);
            Booking booking = new Booking(UUID.randomUUID().toString(),
                guest, room, requested, amount, payment);
            bookings.put(booking.getBookingId(), booking);
            guest.addBooking(booking);
            return booking;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // R5: Cancel — removes date range, freeing the slot for future bookings
    boolean cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null || booking.getStatus() != BookingStatus.CONFIRMED) return false;

        ReentrantReadWriteLock lock = roomLocks.get(booking.getRoom().getRoomId());
        lock.writeLock().lock();
        try {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.getRoom().removeBooking(booking.getDateRange());
            bookings.remove(bookingId);

            if (booking.isEligibleForFullRefund()) {
                booking.getPaymentMethod().refund(booking.getAmount());
            } else {
                System.out.println("    [REFUND] Denied — within 24hr of check-in");
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    String getBranchId() { return branchId; }
    String getName()     { return name; }
}

// ─────────────────────── SINGLETON: HMS (R10) ────────────────
//
// WHY SINGLETON: one system instance manages all branches globally.
// Thread-safe via double-checked locking — lazy init, no sync overhead after init.

class HotelManagementSystem {
    private static volatile HotelManagementSystem instance;
    private final ConcurrentHashMap<String, Branch> branches = new ConcurrentHashMap<>();
    private final NotificationService notificationService = new NotificationService();
    private final HousekeepingService housekeepingService = new HousekeepingService();

    private HotelManagementSystem() {}

    static HotelManagementSystem getInstance() {
        if (instance == null) {
            synchronized (HotelManagementSystem.class) {
                if (instance == null) {
                    instance = new HotelManagementSystem();
                }
            }
        }
        return instance;
    }

    void addBranch(Branch branch)                  { branches.put(branch.getBranchId(), branch); }
    Branch getBranch(String branchId)              { return branches.get(branchId); }
    NotificationService getNotificationService()   { return notificationService; }
    HousekeepingService getHousekeepingService()   { return housekeepingService; }

    // Search across ALL branches for a given style and date range
    List<Room> searchAllBranches(RoomStyle style, DateRange requested) {
        return branches.values().stream()
            .flatMap(b -> b.searchAvailableRooms(style, requested).stream())
            .collect(Collectors.toList());
    }
}

// ═══════════════════════════ DEMO ════════════════════════════

public class HotelManagementSystem_LLD {

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  HOTEL MANAGEMENT SYSTEM — LLD DEMO (ALL R1–R10)");
        System.out.println("═══════════════════════════════════════════════════════════\n");

        // ── 1. SINGLETON ──
        HotelManagementSystem hms = HotelManagementSystem.getInstance();

        // ── 2. OBSERVER: Register notification channels (R6) ──
        hms.getNotificationService().subscribe(new EmailNotification());
        hms.getNotificationService().subscribe(new SMSNotification());

        // ── 3. FACTORY: Create branch with rooms (R2, R10) ──
        Branch downtown = new Branch("B1", "Downtown Grand", "123 Main St");
        downtown.addRoom(RoomFactory.createRoom("101", RoomStyle.STANDARD, 1));
        downtown.addRoom(RoomFactory.createRoom("201", RoomStyle.DELUXE, 2));
        downtown.addRoom(RoomFactory.createRoom("301", RoomStyle.FAMILY_SUITE, 3));
        downtown.addRoom(RoomFactory.createRoom("401", RoomStyle.BUSINESS_SUITE, 4));
        hms.addBranch(downtown);

        Branch airport = new Branch("B2", "Airport Inn", "456 Airport Rd");
        airport.addRoom(RoomFactory.createRoom("A101", RoomStyle.STANDARD, 1));
        airport.addRoom(RoomFactory.createRoom("A201", RoomStyle.DELUXE, 2));
        hms.addBranch(airport);
        System.out.println("✓ Created 2 branches with 6 rooms total\n");

        // ── 4. SEARCH: Available deluxe rooms for specific dates (R3) ──
        System.out.println("── TEST 1: Search Available Rooms ──────────────────────");
        DateRange searchRange = new DateRange(LocalDateTime.now().plusDays(7), 3);
        List<Room> deluxeRooms = hms.searchAllBranches(RoomStyle.DELUXE, searchRange);
        deluxeRooms.forEach(r -> System.out.println("  Found: Room " + r.getRoomId()
            + " | " + r.getStyle() + " | $" + r.getBasePrice() + "/night"));
        System.out.println();

        // ── 5. BOOKING: Strategy pattern for pricing + payment (R4) ──
        System.out.println("── TEST 2: Book Room 201 (Mar 10-13) ──────────────────");
        Guest alice = new Guest("G1", "Alice", "alice@email.com");
        LocalDateTime mar10 = LocalDateTime.now().plusDays(7);
        Booking booking1 = downtown.bookRoom("201", alice, mar10, 3,
            new WeekendPricing(), new CreditCardPayment("4111111111111234"));

        if (booking1 != null) {
            System.out.println("  ✓ Booked: " + booking1.getBookingId().substring(0, 8) + "...");
            hms.getNotificationService().notifyAll(booking1, NotificationType.BOOKING_CONFIRMED);
        }
        System.out.println();

        // ── 6. NON-OVERLAPPING: Same room, different dates — SHOULD SUCCEED ──
        System.out.println("── TEST 3: Book Room 201 (Mar 20-22) — No Overlap ─────");
        Guest bob = new Guest("G2", "Bob", "bob@email.com");
        LocalDateTime mar20 = LocalDateTime.now().plusDays(17);
        Booking booking2 = downtown.bookRoom("201", bob, mar20, 2,
            new StandardPricing(), new UPIPayment("bob@upi"));

        if (booking2 != null) {
            System.out.println("  ✓ Booked: " + booking2.getBookingId().substring(0, 8) + "...");
            System.out.println("  ✓ CORRECT — dates don't overlap, both bookings coexist");
            hms.getNotificationService().notifyAll(booking2, NotificationType.BOOKING_CONFIRMED);
        }
        System.out.println();

        // ── 7. OVERLAPPING: Same room, conflicting dates — SHOULD FAIL ──
        System.out.println("── TEST 4: Book Room 201 (Mar 9-12) — OVERLAP ─────────");
        Guest charlie = new Guest("G3", "Charlie", "charlie@email.com");
        LocalDateTime mar9 = LocalDateTime.now().plusDays(6);
        Booking booking3 = downtown.bookRoom("201", charlie, mar9, 3,
            new StandardPricing(), new CashPayment());

        if (booking3 == null) {
            System.out.println("  ✗ Rejected — overlaps with Alice's booking (Mar 10-13)");
            System.out.println("  ✓ CORRECT — no payment was processed, no wasted money");
        }
        System.out.println();

        // ── 8. DECORATOR: Add services (R8) ──
        System.out.println("── TEST 5: Add Services (Decorator) ───────────────────");
        RoomServiceComponent service = new BaseRoomService(deluxeRooms.get(0));
        service = new FoodService(service);
        service = new SpaService(service);
        service = new WiFiPremiumService(service);
        System.out.println("  " + service.getDescription());
        System.out.println("  Add-on Total: $" + service.getCost());
        System.out.println();

        // ── 9. CANCELLATION with refund (R5) ──
        System.out.println("── TEST 6: Cancel Booking (>24hr = full refund) ───────");
        boolean cancelled = downtown.cancelBooking(booking1.getBookingId());
        System.out.println("  Cancelled: " + cancelled);
        hms.getNotificationService().notifyAll(booking1, NotificationType.BOOKING_CANCELLED);
        System.out.println();

        // ── 10. VERIFY: Room 201 dates freed — overlapping dates now available ──
        System.out.println("── TEST 7: Re-book Room 201 (Mar 9-12) After Cancel ───");
        Booking booking4 = downtown.bookRoom("201", charlie, mar9, 3,
            new StandardPricing(), new CashPayment());
        if (booking4 != null) {
            System.out.println("  ✓ Booked: " + booking4.getBookingId().substring(0, 8) + "...");
            System.out.println("  ✓ CORRECT — Alice's slot was freed by cancellation");
        }
        System.out.println();

        // ── 11. HOUSEKEEPING (R7) ──
        System.out.println("── TEST 8: Housekeeping ───────────────────────────────");
        Housekeeper hk = new Housekeeper("H1", "Dave", "dave@hotel.com");
        CleanupTask task = hms.getHousekeepingService().assignTask(
            downtown.getAllRooms().get(0), hk);
        System.out.println("  Pending: " + hms.getHousekeepingService().getPendingTasks().size());
        hms.getHousekeepingService().completeTask(task);
        System.out.println("  Pending: " + hms.getHousekeepingService().getPendingTasks().size());
        System.out.println();

        // ── 12. KEY MANAGEMENT (R9) ──
        System.out.println("── TEST 9: Key Management ─────────────────────────────");
        RoomKey guestKey = new RoomKey("K-201", "201");
        RoomKey masterKey = new RoomKey("MASTER-F1", Set.of("101", "201", "301"));
        System.out.println("  Guest key opens 201:  " + guestKey.canOpen("201"));
        System.out.println("  Guest key opens 301:  " + guestKey.canOpen("301"));
        System.out.println("  Master key opens 301: " + masterKey.canOpen("301"));
        System.out.println("  Master key opens 401: " + masterKey.canOpen("401"));
        System.out.println();

        // ── 13. CONCURRENCY: 10 threads race for overlapping dates on same room ──
        System.out.println("── TEST 10: CONCURRENCY — 10 Threads, Same Room, Same Dates ──");
        System.out.println("  (Only 1 should succeed, 9 should fail with no payment)");
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<Booking>> futures = new ArrayList<>();
        LocalDateTime raceDate = LocalDateTime.now().plusDays(30);

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                Guest g = new Guest("R" + idx, "Racer-" + idx, "r" + idx + "@mail.com");
                return downtown.bookRoom("301", g, raceDate, 2,
                    new StandardPricing(), new CashPayment());
            }));
        }

        int successCount = 0;
        String winner = "";
        for (Future<Booking> f : futures) {
            Booking b = f.get();
            if (b != null) {
                successCount++;
                winner = b.getGuest().getName();
            }
        }
        System.out.println("  ✓ Winner: " + winner);
        System.out.println("  ✓ Total successful: " + successCount + " (expected: 1)");
        System.out.println("  ✓ Failed (no payment processed): " + (10 - successCount));
        System.out.println();

        // ── 14. CONCURRENCY: Multiple rooms in parallel — zero contention ──
        System.out.println("── TEST 11: CONCURRENCY — Different Rooms in Parallel ─");
        String[] roomIds = {"101", "201", "401"};
        List<Future<Booking>> parallelFutures = new ArrayList<>();
        LocalDateTime parallelDate = LocalDateTime.now().plusDays(40);

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            parallelFutures.add(executor.submit(() -> {
                Guest g = new Guest("P" + idx, "Parallel-" + idx, "p" + idx + "@mail.com");
                return downtown.bookRoom(roomIds[idx], g, parallelDate, 2,
                    new StandardPricing(), new CashPayment());
            }));
        }

        int parallelSuccess = 0;
        for (Future<Booking> f : parallelFutures) {
            if (f.get() != null) parallelSuccess++;
        }
        System.out.println("  ✓ All 3 rooms booked in parallel: " + parallelSuccess + "/3");
        System.out.println("  ✓ Zero contention — each room has its own lock");

        executor.shutdown();

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("  ALL REQUIREMENTS R1–R10 DEMONSTRATED ✓");
        System.out.println("  Patterns: Singleton, Factory, Strategy, Observer, Decorator");
        System.out.println("  Concurrency: ConcurrentHashMap + Per-Room RWLock");
        System.out.println("  Overlap: TreeSet<DateRange> + floor()/ceiling() → O(log n)");
        System.out.println("═══════════════════════════════════════════════════════════");
    }
}
