import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/* =========================
   ENUMS
   ========================= */
enum LockerSize {
    EXTRA_SMALL(1),
    SMALL(2),
    MEDIUM(3),
    LARGE(4),
    EXTRA_LARGE(5),
    DOUBLE_EXTRA_LARGE(6);

    private final int rank;
    LockerSize(int rank) { this.rank = rank; }
    public int rank() { return rank; }
    public boolean canFit(LockerSize need) { return this.rank >= need.rank; }
}

enum LockerState { CLOSED, BOOKED, AVAILABLE }
enum LockerEventType { DELIVERED, PICKED_UP, RETURN_CREATED, RETURN_DROPPED, EXPIRED }

/* =========================
   OBSERVER
   ========================= */
interface LockerEventListener {
    void onEvent(LockerEventType type, Notification n);
}

/**
 * ✅ Simple Observer:
 * LockerService emits events; listener is the single place that "sends" notifications.
 * No customer.receive() or n.send() inside LockerService.
 */
class ConsoleListener implements LockerEventListener {
    @Override
    public void onEvent(LockerEventType type, Notification n) {
        n.send(); // single place to "send"
        System.out.println("🔔 EVENT [" + type + "] -> " + n);
    }
}

/* =========================
   STRATEGY
   ========================= */
interface LockerAssignmentStrategy {
    Locker pick(List<Locker> lockers, LockerSize required);
}

class BestFitStrategy implements LockerAssignmentStrategy {
    @Override
    public Locker pick(List<Locker> lockers, LockerSize required) {
        Locker best = null;
        for (Locker l : lockers) {
            if (l.getState() != LockerState.AVAILABLE) continue;
            if (!l.getSize().canFit(required)) continue;
            if (best == null || l.getSize().rank() < best.getSize().rank()) best = l;
        }
        return best;
    }
}

/* =========================
   FACTORY
   ========================= */
class LockerFactory {
    public Locker create(String id, LockerSize size, String locationName) {
        return new Locker(id, size, locationName);
    }
}

/* =========================
   DOMAIN CLASSES
   ========================= */
class Item {
    private final String itemId;
    private final int quantity;
    private final LockerSize requiredSize;

    public Item(String itemId, int quantity, LockerSize requiredSize) {
        this.itemId = itemId;
        this.quantity = quantity;
        this.requiredSize = requiredSize;
    }

    public String getItemId() { return itemId; }
    public int getQuantity() { return quantity; }
    public LockerSize getRequiredSize() { return requiredSize; }
}

class Order {
    private final String orderId;
    private final List<Item> items = new ArrayList<>();
    private final String deliveryLocation;
    private final String customerId;

    public Order(String orderId, String deliveryLocation, String customerId) {
        this.orderId = orderId;
        this.deliveryLocation = deliveryLocation;
        this.customerId = customerId;
    }

    public void addItem(Item item) { if (item != null) items.add(item); }
    public List<Item> getItems() { return Collections.unmodifiableList(items); }
    public String getOrderId() { return orderId; }
    public String getDeliveryLocation() { return deliveryLocation; }
    public String getCustomerId() { return customerId; }
}

class Package {
    private final String packageId;
    private final Order order;

    public Package(String packageId, Order order) {
        this.packageId = packageId;
        this.order = order;
    }

    public String getPackageId() { return packageId; }
    public Order getOrder() { return order; }
}

class LockerPackage extends Package {
    private final String otp;
    private final Date createdAt;
    private final int validDays;

    public LockerPackage(String packageId, Order order, String otp, int validDays) {
        super(packageId, order);
        this.otp = otp;
        this.validDays = validDays;
        this.createdAt = new Date();
    }

    public String getOtp() { return otp; }

    public boolean isOtpValid() {
        long now = System.currentTimeMillis();
        long start = createdAt.getTime();
        long validMillis = validDays * 24L * 60L * 60L * 1000L;
        return now <= start + validMillis;
    }

    public boolean verify(String input) {
        return isOtpValid() && otp.equals(input);
    }
}

class Notification {
    private final String customerId;
    private final String orderId;
    private final String lockerId;
    private final String code;

    public Notification(String customerId, String orderId, String lockerId, String code) {
        this.customerId = customerId;
        this.orderId = orderId;
        this.lockerId = lockerId;
        this.code = code;
    }

    public String getCustomerId() { return customerId; }
    public String getOrderId() { return orderId; }
    public String getLockerId() { return lockerId; }
    public String getCode() { return code; }

    public void send() {
        System.out.println("📤 Notify customer=" + customerId +
                " order=" + orderId + " locker=" + lockerId + " code=" + code);
    }

    @Override
    public String toString() {
        return "{order=" + orderId + ", locker=" + lockerId + ", code=" + code + "}";
    }
}

class Customer {
    private final String customerId;
    private final String name;

    public Customer(String customerId, String name) {
        this.customerId = customerId;
        this.name = name;
    }

    public String getCustomerId() { return customerId; }

    // kept for demo, but NOT used by LockerService (observer sends)
    public void receive(Notification n) {
        System.out.println("    📩 Customer " + name + " received: " + n);
    }
}

class DeliveryPerson {
    private final String deliveryPersonId;

    public DeliveryPerson(String deliveryPersonId) {
        this.deliveryPersonId = deliveryPersonId;
    }

    public String getDeliveryPersonId() { return deliveryPersonId; }
}

/* =========================
   LOCKER
   - minimal reservation token to avoid mismatch
   ========================= */
class Locker {
    private final String lockerId;
    private final LockerSize size;
    private final String locationName;

    private LockerState state = LockerState.AVAILABLE;
    private LockerPackage current;

    // minimal mismatch protection
    private String reservationToken; // set when booked

    public Locker(String lockerId, LockerSize size, String locationName) {
        this.lockerId = lockerId;
        this.size = size;
        this.locationName = locationName;
    }

    public String getLockerId() { return lockerId; }
    public LockerSize getSize() { return size; }
    public String getLocationName() { return locationName; }
    public LockerState getState() { return state; }
    public LockerPackage getCurrent() { return current; }

    public boolean book(String token) {
        if (state != LockerState.AVAILABLE) return false;
        state = LockerState.BOOKED;
        reservationToken = token;
        return true;
    }

    // only allow occupying if token matches booking
    public boolean addPackage(LockerPackage pkg, String token) {
        if (state != LockerState.BOOKED) return false;
        if (!Objects.equals(reservationToken, token)) return false;

        current = pkg;
        state = LockerState.CLOSED;
        reservationToken = null; // consumed
        return true;
    }

    public boolean removePackage(String otp) {
        if (current == null) return false;
        if (!current.verify(otp)) return false;

        current = null;
        state = LockerState.AVAILABLE;
        return true;
    }

    public void forceClearExpired() {
        current = null;
        reservationToken = null;
        state = LockerState.AVAILABLE;
    }

    @Override
    public String toString() {
        return "Locker{id=" + lockerId + ", size=" + size + ", state=" + state + "}";
    }
}

class LockerLocation {
    private final String name;
    private final List<Locker> lockers = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock(true);

    public LockerLocation(String name) { this.name = name; }
    public String getName() { return name; }
    public List<Locker> getLockers() { return lockers; }
    public ReentrantLock getLock() { return lock; }
    public void addLocker(Locker l) { lockers.add(l); }
}

/* =========================
   SINGLETON SERVICE
   - Fixes:
     1) O(1) OTP lookup via map
     2) All state transitions under location lock
     3) Minimal reservation token used for book->occupy
   - Also:
     ✅ OTP is generated inside service
     ✅ Observer is the single place that "sends" notifications
   ========================= */
class LockerService {
    private static LockerService INSTANCE;

    private final List<LockerLocation> locations = new ArrayList<>();
    private final LockerAssignmentStrategy strategy = new BestFitStrategy();
    private final List<LockerEventListener> listeners = new CopyOnWriteArrayList<>();

    // O(1) lookup (OTP -> lockerId)
    private final Map<String, String> otpToLockerId = new ConcurrentHashMap<>();

    // store booking token for lockerId until package is placed
    private final Map<String, String> lockerIdToReservationToken = new ConcurrentHashMap<>();

    // ✅ OTP generator
    private final Random random = new Random();

    private LockerService() {}

    public static LockerService getInstance() {
        if (INSTANCE == null) INSTANCE = new LockerService();
        return INSTANCE;
    }

    public void addListener(LockerEventListener l) { if (l != null) listeners.add(l); }
    private void emit(LockerEventType type, Notification n) {
        for (LockerEventListener l : listeners) l.onEvent(type, n);
    }

    public void addLocation(LockerLocation loc) { if (loc != null) locations.add(loc); }

    private LockerLocation findLocationByName(String name) {
        for (LockerLocation l : locations) if (l.getName().equals(name)) return l;
        return null;
    }

    private Locker findLockerById(String lockerId) {
        for (LockerLocation loc : locations) {
            for (Locker l : loc.getLockers()) {
                if (l.getLockerId().equals(lockerId)) return l;
            }
        }
        return null;
    }

    // "Pack together if possible" => required locker size = max item required size
    public LockerSize computeRequiredSize(Order order) {
        LockerSize need = LockerSize.EXTRA_SMALL;
        for (Item it : order.getItems()) {
            if (it.getRequiredSize().rank() > need.rank()) need = it.getRequiredSize();
        }
        return need;
    }

    // ✅ simple 6-digit OTP
    private String generateOtp() {
        int v = 100000 + random.nextInt(900000);
        return String.valueOf(v);
    }

    // book locker atomically under location lock
    private Locker requestLockerInternal(LockerSize required, String preferredLocation) {
        LockerLocation loc = findLocationByName(preferredLocation);
        if (loc == null) return null;

        loc.getLock().lock();
        try {
            Locker chosen = strategy.pick(loc.getLockers(), required);
            if (chosen == null) return null;

            String token = UUID.randomUUID().toString();
            if (!chosen.book(token)) return null;

            lockerIdToReservationToken.put(chosen.getLockerId(), token);
            return chosen;
        } finally {
            loc.getLock().unlock();
        }
    }

    // DELIVERY: book -> occupy -> store otp mapping -> emit notification
    public Notification deliver(Order order) {
        LockerSize need = computeRequiredSize(order);
        Locker locker = requestLockerInternal(need, order.getDeliveryLocation());
        if (locker == null) throw new IllegalStateException("No locker available");

        String pickupOtp = generateOtp();

        LockerLocation loc = findLocationByName(order.getDeliveryLocation());
        loc.getLock().lock();
        try {
            String token = lockerIdToReservationToken.get(locker.getLockerId());
            LockerPackage lp = new LockerPackage("PKG-" + order.getOrderId(), order, pickupOtp, 3);

            boolean placed = locker.addPackage(lp, token);
            if (!placed) throw new IllegalStateException("Failed to occupy locker (token mismatch)");

            lockerIdToReservationToken.remove(locker.getLockerId());
            otpToLockerId.put(pickupOtp, locker.getLockerId()); // O(1) lookup
        } finally {
            loc.getLock().unlock();
        }

        Notification n = new Notification(order.getCustomerId(), order.getOrderId(), locker.getLockerId(), pickupOtp);
        emit(LockerEventType.DELIVERED, n);
        return n;
    }

    // pickup via map (O(1) locker lookup)
    public boolean pickup(String otp) {
        String lockerId = otpToLockerId.get(otp);
        if (lockerId == null) return false;

        Locker locker = findLockerById(lockerId);
        if (locker == null) return false;

        LockerLocation loc = findLocationByName(locker.getLocationName());
        loc.getLock().lock();
        try {
            LockerPackage cur = locker.getCurrent();
            if (cur == null) return false;

            boolean ok = locker.removePackage(otp);
            if (ok) {
                otpToLockerId.remove(otp); // invalidate mapping
                Notification n = new Notification(cur.getOrder().getCustomerId(), cur.getOrder().getOrderId(), lockerId, "INVALIDATED");
                emit(LockerEventType.PICKED_UP, n);
            }
            return ok;
        } finally {
            loc.getLock().unlock();
        }
    }

    // RETURN: reserve locker, generate OTP, store mapping, emit notification
    public Notification createReturn(Order order) {
        LockerSize need = computeRequiredSize(order);
        Locker locker = requestLockerInternal(need, order.getDeliveryLocation());
        if (locker == null) throw new IllegalStateException("No locker available for return");

        String returnOtp = generateOtp();
        otpToLockerId.put(returnOtp, locker.getLockerId());

        Notification n = new Notification(order.getCustomerId(), order.getOrderId(), locker.getLockerId(), returnOtp);
        emit(LockerEventType.RETURN_CREATED, n);
        return n;
    }

    // dropReturn goes through service lock + token check
    public boolean dropReturn(Order order, String returnOtp) {
        String lockerId = otpToLockerId.get(returnOtp);
        if (lockerId == null) return false;

        Locker locker = findLockerById(lockerId);
        if (locker == null) return false;

        LockerLocation loc = findLocationByName(locker.getLocationName());
        loc.getLock().lock();
        try {
            String token = lockerIdToReservationToken.get(lockerId);
            LockerPackage lp = new LockerPackage("RET-" + order.getOrderId(), order, returnOtp, 3);

            boolean ok = locker.addPackage(lp, token);
            if (ok) {
                lockerIdToReservationToken.remove(lockerId);
                emit(LockerEventType.RETURN_DROPPED, new Notification(order.getCustomerId(), order.getOrderId(), lockerId, returnOtp));
            }
            return ok;
        } finally {
            loc.getLock().unlock();
        }
    }

    // Expiry scan: clear expired packages and emit EXPIRED
    public void expireOldPackages() {
        for (LockerLocation loc : locations) {
            loc.getLock().lock();
            try {
                for (Locker l : loc.getLockers()) {
                    LockerPackage cur = l.getCurrent();
                    if (cur != null && !cur.isOtpValid()) {
                        otpToLockerId.remove(cur.getOtp());
                        Notification n = new Notification(cur.getOrder().getCustomerId(), cur.getOrder().getOrderId(), l.getLockerId(), "EXPIRED");
                        l.forceClearExpired();
                        emit(LockerEventType.EXPIRED, n);
                    }
                }
            } finally {
                loc.getLock().unlock();
            }
        }
    }
}

/* =========================
   MAIN
   ========================= */
public class Main {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("   AMAZON LOCKER (SDE2 30-min LLD)     ");
        System.out.println("========================================");

        LockerService service = LockerService.getInstance();
        service.addListener(new ConsoleListener());

        LockerLocation downtown = new LockerLocation("Downtown");
        LockerFactory factory = new LockerFactory();

        downtown.addLocker(factory.create("L1", LockerSize.SMALL, "Downtown"));
        downtown.addLocker(factory.create("L2", LockerSize.MEDIUM, "Downtown"));
        downtown.addLocker(factory.create("L3", LockerSize.LARGE, "Downtown"));
        service.addLocation(downtown);

        Customer customer = new Customer("CUST1", "Alice");
        DeliveryPerson dp = new DeliveryPerson("DEL1");

        Order order = new Order("ORD1", "Downtown", customer.getCustomerId());
        order.addItem(new Item("ITEM1", 1, LockerSize.SMALL));
        order.addItem(new Item("ITEM2", 2, LockerSize.MEDIUM)); // max => MEDIUM

        System.out.println("\n1) DELIVERY");
        Notification deliveryNotif = service.deliver(order);
        String pickupOtp = deliveryNotif.getCode();
        System.out.println("    Delivered (OTP shown for demo): " + pickupOtp);

        System.out.println("\n2) PICKUP");
        boolean picked = service.pickup(pickupOtp);
        System.out.println("    Pickup success? " + picked);

        System.out.println("\n3) RETURN (create + drop)");
        Notification returnNotif = service.createReturn(order);
        String returnOtp = returnNotif.getCode();
        System.out.println("    Return OTP (shown for demo): " + returnOtp);

        boolean dropped = service.dropReturn(order, returnOtp);
        System.out.println("    Return dropped? " + dropped);

        Timer t = new Timer(true); // daemon thread
t.scheduleAtFixedRate(new TimerTask() {
    @Override public void run() {
        service.expireOldPackages();
    }
}, 0, 5 * 60 * 1000); // every 5 minutes

        System.out.println("\nDONE ✅");

    }
}
