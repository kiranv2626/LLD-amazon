import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * One-file, 30-min interview LLD: Car Rental System
 * Focus: prevent double booking + patterns (Singleton/Strategy/Decorator/Factory)
 * Java 8 compatible.
 */
public class CarRentalSystemOneFile {

    // ============================= ENUMS =============================
    enum Role { CUSTOMER, RECEPTIONIST }
    enum AccountStatus { ACTIVE, CLOSED, CANCELED, BLACKLISTED, BLOCKED }
    enum ReservationStatus { ACTIVE, PENDING, CONFIRMED, COMPLETED, CANCELED }
    enum PaymentStatus { UNPAID, PENDING, COMPLETED, CANCELED, REFUNDED }
    enum PaymentMethod { CASH, CARD, ONLINE }

    enum VehicleStatus { AVAILABLE, RESERVED, RENTED, IN_SERVICE, LOST }

    enum VehicleType { CAR, VAN, TRUCK, MOTORCYCLE }
    enum CarType { ECONOMY, COMPACT, INTERMEDIATE, STANDARD, FULL_SIZE, PREMIUM, LUXURY }
    enum VanType { PASSENGER, CARGO }
    enum TruckType { LIGHT_DUTY, MEDIUM_DUTY, HEAVY_DUTY }
    enum MotorcycleType { STANDARD, CRUISER, TOURING, SPORTS, OFF_ROAD, DUAL_PURPOSE }

    enum VehicleLogType { ACCIDENT, FUELING, CLEANING_SERVICE, OIL_CHANGE, REPAIR, OTHER }

    // ============================= MONEY =============================
    static final class Money {
        final long cents;
        Money(long cents) { this.cents = cents; }
        static Money ofDollars(double dollars) { return new Money(Math.round(dollars * 100.0)); }
        Money plus(Money o) { return new Money(this.cents + o.cents); }
        Money minus(Money o) { return new Money(this.cents - o.cents); }
        Money mul(double k) { return new Money(Math.round(this.cents * k)); }
        @Override public String toString() { return String.format("$%.2f", cents / 100.0); }
    }

    // ============================= VALUE OBJECTS =============================
    static final class Address {
        final String street, city, state, zip, country;
        Address(String street, String city, String state, String zip, String country) {
            this.street = street; this.city = city; this.state = state; this.zip = zip; this.country = country;
        }
        @Override public String toString() { return street + ", " + city + ", " + state + " " + zip + ", " + country; }
    }

    static final class Interval {
        final Instant start, end; // [start, end)
        Interval(Instant start, Instant end) {
            if (start == null || end == null || !start.isBefore(end)) throw new IllegalArgumentException("Invalid interval");
            this.start = start; this.end = end;
        }
        boolean overlaps(Interval other) {
            return this.start.isBefore(other.end) && other.start.isBefore(this.end);
        }
        @Override public String toString() { return "[" + start + " -> " + end + "]"; }
    }

    // ============================= PEOPLE / ACCOUNTS =============================
    static abstract class Person {
        String name;
        Address address;
        String email;
        String phoneNumber;
        public String getName() { return name; }
    }

    static abstract class Account extends Person {
        String accountId;
        String password;
        AccountStatus status;
        Role role;
        public String getAccountId() { return accountId; }
        public void setPassword(String p) { this.password = p; }
        public abstract boolean resetPassword();
    }

    static final class Customer extends Account {
        String licenseNumber;
        Date licenseExpiry;
        Customer(String id, String name, String email) {
            this.accountId = id; this.name = name; this.email = email;
            this.status = AccountStatus.ACTIVE; this.role = Role.CUSTOMER;
        }
        @Override public boolean resetPassword() {
            setPassword(UUID.randomUUID().toString()); return true;
        }
    }

    static final class Receptionist extends Account {
        Date dateJoined = new Date();
        Receptionist(String id, String name, String email) {
            this.accountId = id; this.name = name; this.email = email;
            this.status = AccountStatus.ACTIVE; this.role = Role.RECEPTIONIST;
        }
        @Override public boolean resetPassword() {
            setPassword(UUID.randomUUID().toString()); return true;
        }
    }

    static final class Driver extends Person {
        int driverId;
    }

    // ============================= VEHICLES =============================
    static final class VehicleLog {
        final int logId;
        final VehicleLogType logType;
        final String description;
        final Date creationDate;
        VehicleLog(int id, VehicleLogType type, String desc) {
            this.logId = id; this.logType = type; this.description = desc; this.creationDate = new Date();
        }
    }

    static abstract class Vehicle {
        String vehicleId;
        String licensePlateNumber;
        int passengerCapacity;
        VehicleStatus status = VehicleStatus.AVAILABLE;
        String model;
        int manufacturingYear;
        String branchId;                 // current location
        Set<String> features = new HashSet<>(); // "AWD", "GPS", "AUTO"
        List<VehicleLog> log = new ArrayList<>();
        public String getVehicleId() { return vehicleId; }
        public VehicleStatus getStatus() { return status; }
        public String getModel() { return model; }
        public String getBranchId() { return branchId; }

        void addLog(VehicleLogType type, String desc) {
            log.add(new VehicleLog(log.size() + 1, type, desc));
        }
        void reserveVehicle() { status = VehicleStatus.RESERVED; addLog(VehicleLogType.OTHER, "Reserved"); }
        void pickupVehicle() { status = VehicleStatus.RENTED; addLog(VehicleLogType.OTHER, "Picked up"); }
        void returnVehicle(String newBranchId) { status = VehicleStatus.AVAILABLE; this.branchId = newBranchId; addLog(VehicleLogType.OTHER, "Returned"); }
    }

    static final class Car extends Vehicle { CarType carType; }
    static final class Van extends Vehicle { VanType vanType; }
    static final class Truck extends Vehicle { TruckType truckType; }
    static final class Motorcycle extends Vehicle { MotorcycleType motorcycleType; }

    // ============================= ADD-ONS =============================
    static abstract class Equipment {
        int equipmentId;
        Money price;
        Money getPrice() { return price; }
    }
    static final class Navigation extends Equipment {}
    static final class ChildSeat extends Equipment {}
    static final class SkiRack extends Equipment {}

    static abstract class Service {
        int serviceId;
        Money price;
        Money getPrice() { return price; }
    }
    static final class DriverService extends Service { int driverId; }
    static final class RoadsideAssistance extends Service {}
    static final class WiFi extends Service {}

    // ============================= RESERVATION =============================
    static final class VehicleReservation {
        String reservationId;
        String customerId;
        String vehicleId;
        Date creationDate;
        ReservationStatus status;
        Interval interval;                 // core for overlap + double-booking prevention
        String pickupLocationBranchId;
        String returnLocationBranchId;
        Instant actualReturnTime;          // for fines
        List<Equipment> equipments = new ArrayList<>();
        List<Service> services = new ArrayList<>();
        Money quotedPrice = Money.ofDollars(0);
    }

    // ============================= PAYMENT =============================
    static abstract class Payment {
        Money amount;
        Date timestamp;
        PaymentStatus status;
        PaymentMethod method;
        abstract boolean makePayment();
    }
    static final class Cash extends Payment {
        @Override public boolean makePayment() { status = PaymentStatus.COMPLETED; return true; }
    }
    static final class CreditCard extends Payment {
        String nameOnCard; String cardNumber; String billingAddress; int code;
        @Override public boolean makePayment() { status = PaymentStatus.COMPLETED; return true; }
    }
    static final class Online extends Payment {
        @Override public boolean makePayment() { status = PaymentStatus.COMPLETED; return true; }
    }

    // Factory
    static final class PaymentFactory {
        static Payment create(PaymentMethod method, Money amount) {
            Payment p;
            switch (method) {
                case CASH: p = new Cash(); break;
                case CARD: p = new CreditCard(); break;
                case ONLINE: p = new Online(); break;
                default: throw new IllegalArgumentException("Unsupported method");
            }
            p.method = method; p.amount = amount; p.timestamp = new Date(); p.status = PaymentStatus.PENDING;
            return p;
        }
    }

    // ============================= NOTIFICATION =============================
    static abstract class Notification {
        int notificationId;
        Date createdOn = new Date();
        String content;
        void setContent(String c) { content = c; }
        String getContent() { return content; }
        abstract void sendNotification(Account account);
    }
    static final class SmsNotification extends Notification {
        @Override public void sendNotification(Account account) {
            System.out.println("[SMS] to " + account.getName() + ": " + getContent());
        }
    }
    static final class EmailNotification extends Notification {
        @Override public void sendNotification(Account account) {
            System.out.println("[EMAIL] to " + account.getName() + ": " + getContent());
        }
    }

    // Factory
    static final class NotificationFactory {
        static Notification create(String channel) {
            return "SMS".equalsIgnoreCase(channel) ? new SmsNotification() : new EmailNotification();
        }
    }

    // ============================= STRATEGIES =============================
    interface PricingStrategy {
        Money basePrice(Vehicle v, Interval interval);
    }

    // Simple but solid for interview: daily pricing by type (can be extended)
    static final class DefaultPricingStrategy implements PricingStrategy {
        @Override public Money basePrice(Vehicle v, Interval interval) {
            long hours = Duration.between(interval.start, interval.end).toHours();
            long days = Math.max(1, (hours + 23) / 24);

            Money daily;
            if (v instanceof Truck) daily = Money.ofDollars(95);
            else if (v instanceof Van) daily = Money.ofDollars(85);
            else if (v instanceof Motorcycle) daily = Money.ofDollars(45);
            else daily = Money.ofDollars(55);

            return new Money(daily.cents * days);
        }
    }

    interface FinePolicy {
        Money fine(VehicleReservation r, Instant actualReturn, boolean fuelLow, boolean damaged);
    }

    static final class DefaultFinePolicy implements FinePolicy {
        @Override public Money fine(VehicleReservation r, Instant actualReturn, boolean fuelLow, boolean damaged) {
            Money total = Money.ofDollars(0);
            if (actualReturn.isAfter(r.interval.end)) {
                long lateHours = Duration.between(r.interval.end, actualReturn).toHours();
                total = total.plus(Money.ofDollars(20 * lateHours)); // $20/hour late
            }
            if (fuelLow) total = total.plus(Money.ofDollars(50));
            if (damaged) total = total.plus(Money.ofDollars(200));
            return total;
        }
    }

    interface CancellationPolicy {
        Money refund(Money paid, VehicleReservation r, Instant cancelTime);
    }

    static final class DefaultCancellationPolicy implements CancellationPolicy {
        @Override public Money refund(Money paid, VehicleReservation r, Instant cancelTime) {
            // cancel must be before pickup start
            if (!cancelTime.isBefore(r.interval.start)) return Money.ofDollars(0);
            long hrs = Duration.between(cancelTime, r.interval.start).toHours();
            return (hrs >= 24) ? paid : paid.mul(0.8); // 80% refund if < 24h
        }
    }

    // ============================= DECORATOR PRICING =============================
    interface PriceComponent { Money price(); }

    static final class BasePriceComponent implements PriceComponent {
        final Money base;
        BasePriceComponent(Money base) { this.base = base; }
        @Override public Money price() { return base; }
    }

    static abstract class PriceDecorator implements PriceComponent {
        final PriceComponent inner;
        PriceDecorator(PriceComponent inner) { this.inner = inner; }
    }

    static final class EquipmentDecorator extends PriceDecorator {
        final List<Equipment> eq;
        EquipmentDecorator(PriceComponent inner, List<Equipment> eq) { super(inner); this.eq = eq; }
        @Override public Money price() {
            Money t = inner.price();
            for (Equipment e : eq) t = t.plus(e.getPrice());
            return t;
        }
    }

    static final class ServiceDecorator extends PriceDecorator {
        final List<Service> sv;
        ServiceDecorator(PriceComponent inner, List<Service> sv) { super(inner); this.sv = sv; }
        @Override public Money price() {
            Money t = inner.price();
            for (Service s : sv) t = t.plus(s.getPrice());
            return t;
        }
    }

    static final class DiscountDecorator extends PriceDecorator {
        final double pct;
        DiscountDecorator(PriceComponent inner, double pct) { super(inner); this.pct = pct; }
        @Override public Money price() { return inner.price().mul(1.0 - pct); }
    }

    static final class PeakSeasonDecorator extends PriceDecorator {
        final double pct;
        PeakSeasonDecorator(PriceComponent inner, double pct) { super(inner); this.pct = pct; }
        @Override public Money price() { return inner.price().mul(1.0 + pct); }
    }

    // ============================= BRANCH / PARKING =============================
    static final class ParkingStall {
        int stallId;
        String locationIdentifier; // branchId
        String vehicleId;          // null if empty
    }

    static final class CarRentalBranch {
        String name;
        Address address;
        String branchId;
        List<ParkingStall> stalls = new ArrayList<>();

        CarRentalBranch(String branchId, String name, Address address) {
            this.branchId = branchId; this.name = name; this.address = address;
        }
    }

    // ============================= SEARCH =============================
    interface Search {
        List<Vehicle> searchByType(String type);
        List<Vehicle> searchByModel(String model);
    }

    static final class VehicleCatalog implements Search {
        private final Map<String, List<Vehicle>> vehicleTypes = new HashMap<>();
        private final Map<String, List<Vehicle>> vehicleModels = new HashMap<>();

        void addVehicle(Vehicle v) {
            String t = v.getClass().getSimpleName().toUpperCase(); // CAR/VAN...
            vehicleTypes.computeIfAbsent(t, k -> new ArrayList<>()).add(v);
            vehicleModels.computeIfAbsent(v.model.toLowerCase(), k -> new ArrayList<>()).add(v);
        }

        @Override public List<Vehicle> searchByType(String type) {
            return new ArrayList<>(vehicleTypes.getOrDefault(type.toUpperCase(), Collections.emptyList()));
        }

        @Override public List<Vehicle> searchByModel(String model) {
            return new ArrayList<>(vehicleModels.getOrDefault(model.toLowerCase(), Collections.emptyList()));
        }
    }

    // ============================= SINGLETON SYSTEM (Facade) =============================
    static final class CarRentalSystem {
        private static volatile CarRentalSystem INSTANCE;

        // in-memory stores (good for interview)
        final Map<String, CarRentalBranch> branches = new ConcurrentHashMap<>();
        final Map<String, Vehicle> vehicles = new ConcurrentHashMap<>();
        final Map<String, VehicleReservation> reservations = new ConcurrentHashMap<>();
        final Map<String, Payment> payments = new ConcurrentHashMap<>();

        // for search
        final VehicleCatalog catalog = new VehicleCatalog();

        // strategies
        final PricingStrategy pricingStrategy = new DefaultPricingStrategy();
        final FinePolicy finePolicy = new DefaultFinePolicy();
        final CancellationPolicy cancellationPolicy = new DefaultCancellationPolicy();

        // concurrency: per-vehicle lock registry (KEY for double booking)
        final ConcurrentHashMap<String, ReentrantLock> vehicleLocks = new ConcurrentHashMap<>();

        private CarRentalSystem() {}

        static CarRentalSystem getInstance() {
            if (INSTANCE == null) {
                synchronized (CarRentalSystem.class) {
                    if (INSTANCE == null) INSTANCE = new CarRentalSystem();
                }
            }
            return INSTANCE;
        }

        // ---- Helpers ----
        private Lock lockForVehicle(String vehicleId) {
            return vehicleLocks.computeIfAbsent(vehicleId, k -> new ReentrantLock());
        }

        public void addBranch(CarRentalBranch b) { branches.put(b.branchId, b); }

        public void addVehicle(Vehicle v, String branchId) {
            v.branchId = branchId;
            vehicles.put(v.vehicleId, v);
            catalog.addVehicle(v);
        }

        // ---- Search: by location + date + type/model/features ----
        public List<Vehicle> search(String branchId, Interval interval, String typeOrNull, String modelOrNull, Set<String> requiredFeatures) {
            List<Vehicle> pool;
            if (modelOrNull != null) pool = catalog.searchByModel(modelOrNull);
            else if (typeOrNull != null) pool = catalog.searchByType(typeOrNull);
            else pool = new ArrayList<>(vehicles.values());

            List<Vehicle> out = new ArrayList<>();
            for (Vehicle v : pool) {
                if (branchId != null && !branchId.equals(v.branchId)) continue;
                if (requiredFeatures != null && !v.features.containsAll(requiredFeatures)) continue;

                // availability = no overlapping non-canceled reservation for this vehicle
                if (isVehicleAvailable(v.vehicleId, interval)) out.add(v);
            }
            return out;
        }

        private boolean isVehicleAvailable(String vehicleId, Interval interval) {
            for (VehicleReservation r : reservations.values()) {
                if (!vehicleId.equals(r.vehicleId)) continue;
                if (r.status == ReservationStatus.CANCELED) continue;
                if (r.interval.overlaps(interval) && r.status != ReservationStatus.COMPLETED) return false;
            }
            return true;
        }

        // ---- Create reservation (prevents double booking) ----
        public VehicleReservation reserve(
                Account actor,
                String customerId,
                String vehicleId,
                String pickupBranchId,
                String returnBranchId,
                Interval interval,
                List<Equipment> equipments,
                List<Service> services,
                boolean peakSeason,
                double discountPct,
                PaymentMethod method
        ) {
            if (actor == null) throw new IllegalArgumentException("actor required");
            if (!branches.containsKey(pickupBranchId) || !branches.containsKey(returnBranchId))
                throw new IllegalArgumentException("invalid branch");

            Vehicle v = vehicles.get(vehicleId);
            if (v == null) throw new IllegalArgumentException("vehicle not found");

            Lock lock = lockForVehicle(vehicleId);
            lock.lock();
            try {
                // Double booking guard (under lock)
                if (!isVehicleAvailable(vehicleId, interval)) {
                    throw new IllegalStateException("Vehicle already reserved for that time window");
                }

                VehicleReservation r = new VehicleReservation();
                r.reservationId = UUID.randomUUID().toString();
                r.customerId = customerId;
                r.vehicleId = vehicleId;
                r.creationDate = new Date();
                r.status = ReservationStatus.PENDING;
                r.interval = interval;
                r.pickupLocationBranchId = pickupBranchId;
                r.returnLocationBranchId = returnBranchId;
                if (equipments != null) r.equipments.addAll(equipments);
                if (services != null) r.services.addAll(services);

                // Pricing: Strategy + Decorators
                Money base = pricingStrategy.basePrice(v, interval);
                PriceComponent pc = new BasePriceComponent(base);
                pc = new EquipmentDecorator(pc, r.equipments);
                pc = new ServiceDecorator(pc, r.services);
                if (peakSeason) pc = new PeakSeasonDecorator(pc, 0.20);
                if (discountPct > 0) pc = new DiscountDecorator(pc, discountPct);
                r.quotedPrice = pc.price();

                // Payment (Factory)
                Payment payment = PaymentFactory.create(method, r.quotedPrice);
                boolean ok = payment.makePayment();
                payments.put(r.reservationId, payment);

                if (!ok) {
                    r.status = ReservationStatus.CANCELED;
                    reservations.put(r.reservationId, r);
                    notifyUser(actor, "EMAIL", "Payment failed. Reservation canceled: " + r.reservationId);
                    return r;
                }

                r.status = ReservationStatus.CONFIRMED;
                reservations.put(r.reservationId, r);

                // Mark vehicle reserved (status is NOT the true guard; interval+lock is)
                v.reserveVehicle();

                notifyUser(actor, "EMAIL", "Reservation confirmed: " + r.reservationId + ", price=" + r.quotedPrice);
                return r;

            } finally {
                lock.unlock();
            }
        }

        // ---- Cancel reservation (policy + refund) ----
        public VehicleReservation cancel(Account actor, String reservationId, Instant cancelTime) {
            VehicleReservation r = reservations.get(reservationId);
            if (r == null) throw new IllegalArgumentException("reservation not found");

            Lock lock = lockForVehicle(r.vehicleId);
            lock.lock();
            try {
                if (r.status == ReservationStatus.CANCELED || r.status == ReservationStatus.COMPLETED)
                    return r;

                // only before pickup start
                if (!cancelTime.isBefore(r.interval.start)) {
                    throw new IllegalStateException("Cannot cancel after pickup time");
                }

                Payment p = payments.get(reservationId);
                Money paid = (p == null || p.status != PaymentStatus.COMPLETED) ? Money.ofDollars(0) : p.amount;
                Money refund = cancellationPolicy.refund(paid, r, cancelTime);

                if (p != null && refund.cents > 0) p.status = PaymentStatus.REFUNDED;

                r.status = ReservationStatus.CANCELED;

                // If no other future reservation overlaps, we can set AVAILABLE
                Vehicle v = vehicles.get(r.vehicleId);
                if (v != null && isVehicleAvailable(v.vehicleId, new Interval(Instant.now(), Instant.now().plusSeconds(1)))) {
                    v.status = VehicleStatus.AVAILABLE;
                }

                notifyUser(actor, "SMS", "Reservation canceled: " + r.reservationId + ", refund=" + refund);
                return r;
            } finally {
                lock.unlock();
            }
        }

        // ---- Pickup vehicle ----
        public VehicleReservation pickup(Account receptionist, String reservationId) {
            if (receptionist.role != Role.RECEPTIONIST) throw new IllegalArgumentException("Only receptionist can pickup");
            VehicleReservation r = reservations.get(reservationId);
            if (r == null) throw new IllegalArgumentException("reservation not found");

            Lock lock = lockForVehicle(r.vehicleId);
            lock.lock();
            try {
                if (r.status != ReservationStatus.CONFIRMED) throw new IllegalStateException("Reservation not confirmed");
                Vehicle v = vehicles.get(r.vehicleId);
                if (v == null) throw new IllegalArgumentException("vehicle not found");
                v.pickupVehicle();
                r.status = ReservationStatus.ACTIVE;
                notifyUser(receptionist, "EMAIL", "Vehicle picked up for reservation: " + r.reservationId);
                return r;
            } finally {
                lock.unlock();
            }
        }

        // ---- Return vehicle (fine + notification) ----
        public Money returnVehicle(Account receptionist, String reservationId, Instant actualReturn, boolean fuelLow, boolean damaged) {
            if (receptionist.role != Role.RECEPTIONIST) throw new IllegalArgumentException("Only receptionist can return");
            VehicleReservation r = reservations.get(reservationId);
            if (r == null) throw new IllegalArgumentException("reservation not found");

            Lock lock = lockForVehicle(r.vehicleId);
            lock.lock();
            try {
                if (r.status != ReservationStatus.ACTIVE) throw new IllegalStateException("Not active reservation");
                r.actualReturnTime = actualReturn;

                Money fine = finePolicy.fine(r, actualReturn, fuelLow, damaged);
                Vehicle v = vehicles.get(r.vehicleId);
                if (v != null) v.returnVehicle(r.returnLocationBranchId);

                r.status = ReservationStatus.COMPLETED;

                if (fine.cents > 0) notifyUser(receptionist, "SMS", "Fine generated for res " + r.reservationId + ": " + fine);
                return fine;
            } finally {
                lock.unlock();
            }
        }

        private void notifyUser(Account who, String channel, String msg) {
            Notification n = NotificationFactory.create(channel);
            n.setContent(msg);
            n.sendNotification(who);
        }

        // tracking rental history count (R5)
        public int totalReservationsForCustomer(String customerId) {
            int c = 0;
            for (VehicleReservation r : reservations.values()) if (customerId.equals(r.customerId)) c++;
            return c;
        }
    }

    // ============================= DEMO MAIN =============================
    public static void main(String[] args) {
        CarRentalSystem sys = CarRentalSystem.getInstance();

        // branches
        CarRentalBranch airport = new CarRentalBranch("B1", "Airport Branch",
                new Address("123 Main", "Seattle", "WA", "98101", "USA"));
        CarRentalBranch downtown = new CarRentalBranch("B2", "Downtown Branch",
                new Address("9 Pike", "Seattle", "WA", "98102", "USA"));
        sys.addBranch(airport);
        sys.addBranch(downtown);

        // users
        Customer customer = new Customer("CUST1", "Alice", "alice@email.com");
        Receptionist receptionist = new Receptionist("REC1", "Bob", "bob@branch.com");

        // vehicles
        Car car = new Car();
        car.vehicleId = "CAR1";
        car.model = "Toyota Corolla";
        car.manufacturingYear = 2022;
        car.carType = CarType.ECONOMY;
        car.features.add("AUTO");
        car.features.add("GPS");
        sys.addVehicle(car, "B1");

        // search
        Interval interval = new Interval(Instant.now().plus(Duration.ofHours(2)), Instant.now().plus(Duration.ofDays(3)));
        System.out.println("Search results: " + sys.search("B1", interval, "CAR", null, new HashSet<>(Arrays.asList("GPS"))).size());

        // add-ons
        ChildSeat seat = new ChildSeat(); seat.equipmentId = 1; seat.price = Money.ofDollars(15);
        WiFi wifi = new WiFi(); wifi.serviceId = 1; wifi.price = Money.ofDollars(10);

        // reserve (safe against double booking)
        VehicleReservation r = sys.reserve(
                customer,
                customer.getAccountId(),
                "CAR1",
                "B1",
                "B2",
                interval,
                Arrays.asList(seat),
                Arrays.asList(wifi),
                true,      // peak season
                0.10,      // discount
                PaymentMethod.CARD
        );
        System.out.println("Reservation: " + r.reservationId + " status=" + r.status + " price=" + r.quotedPrice);

        // receptionist pickup + return
        sys.pickup(receptionist, r.reservationId);
        Money fine = sys.returnVehicle(receptionist, r.reservationId, interval.end.plus(Duration.ofHours(2)), true, false);
        System.out.println("Fine: " + fine);

        // rental history count
        System.out.println("Total reservations for customer: " + sys.totalReservationsForCustomer(customer.getAccountId()));
    }
}
