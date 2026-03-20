/*
 * RESTAURANT MANAGEMENT SYSTEM — Strong Hire LLD (30-min interview target)
 *
 * DESIGN DECISIONS
 * ─────────────────────────────────────────────────────────────────────────────
 * DD1  [Composite – Menu Hierarchy]
 *      MenuComponent interface unifies MenuItem and MenuSection.
 *      Alt: flat list + category field — can't nest sub-sections (e.g. "Vegan Mains"
 *      inside "Mains"). Composite lets both leaf and container respond to the same
 *      getName/getPrice/print contract; Manager.updateMenu swaps a subtree, not a list.
 *      TALK ABOUT: "Menu is a tree; Composite lets me treat leaf and node uniformly,
 *                  and swap any subtree without touching callers."
 *
 * DD2  [Strategy – Payment]
 *      PaymentStrategy interface → CashPayment, CreditCardPayment.
 *      Alt: if/else in BillProcessor — OCP violation; every new method (UPI, wallet)
 *      needs a diff into core logic. Strategy: new payment = new class only.
 *      TALK ABOUT: "Open/Closed — adding Venmo is a new class, not a diff to core billing."
 *
 * DD3  [Template Method – Bill Processing]
 *      BillProcessor.process() skeleton: validate → calculate → charge (hook) → receipt.
 *      Alt: duplicate flow in each Strategy — invariant steps (tax, idempotency) diverge.
 *      Template fixes the skeleton; subclasses/strategies swap only the charge step.
 *      TALK ABOUT: "Template locks the invariant flow; strategy swaps only the charge hook."
 *
 * DD4  [Observer – Reservation Notifications]
 *      Reservation notifies registered observers (Email, SMS) on confirm/cancel/remind.
 *      Alt: inline notification calls in domain model — couples domain to infra.
 *      Observer: adding push notification is a new class, zero domain changes.
 *      TALK ABOUT: "Observer decouples when-to-notify from how; push channel = new observer."
 *
 * DD5  [Per-Table ReentrantReadWriteLock for Reservation Concurrency]
 *      ConcurrentHashMap<tableId, RWLock> in ReservationManager.
 *      Alt 1: single branch-level lock — serializes all table reservations; throughput hit.
 *      Alt 2: DB-level OCC — valid in prod, out of scope in 30-min LLD.
 *      Only conflicting tables contend; unrelated tables reserve in parallel.
 *      TALK ABOUT: "Table-level RWLock maximizes concurrency; only contending tables lock."
 *
 * DD6  [Check-Then-Act Atomicity for Reservation Overlap]
 *      Overlap check + insert both inside writeLock → prevents TOCTOU race.
 *      Alt: check outside lock — two threads both see "free" and double-book the same slot.
 *      TreeSet<Reservation> (ordered by startTime) for O(log n) navigation.
 *      TALK ABOUT: "If I check outside the lock, two threads both pass and double-book.
 *                  The entire CAS — check + insert — must be inside writeLock."
 *
 * DD7  [Order as Aggregate Root + CopyOnWriteArrayList for OrderItems]
 *      All mutations (addItem, confirm, serve) route through Order.
 *      COAL for items: read-heavy (kitchen display polling), rare writes (items added once).
 *      Alt: synchronized list — every kitchen-display read contends with writes.
 *      TALK ABOUT: "Order is the consistency boundary; COAL because reads dominate after add."
 *
 * DD8  [Enum State Machines for Order and Reservation]
 *      OrderStatus: CREATED→ITEMS_ADDED→CONFIRMED→SERVED→BILLED→COMPLETED
 *      ReservationStatus: REQUESTED→CONFIRMED→CANCELLED→COMPLETED
 *      Alt: boolean flags (isPaid, isServed) — 2^n state space, illegal combos possible.
 *      State machine makes illegal transitions explicit and testable.
 *      TALK ABOUT: "Boolean flags give 2^n states; enum machine makes illegal transitions
 *                  a compile-time / guarded-method concern."
 *
 * DD9  [Singleton ReservationManager]
 *      Double-checked locking singleton holds all per-table lock maps.
 *      Alt: per-Branch managers — cross-branch availability queries need coordination layer.
 *      Singleton centralizes the lock registry; Branch delegates to it.
 *      TALK ABOUT: "Singleton owns the lock registry; otherwise cross-branch queries
 *                  need an extra coordination layer."
 *
 * DD10 [Builder – Order Construction]
 *      OrderBuilder accumulates items before committing; prevents half-built Orders.
 *      Alt: telescoping constructors — N items = N constructor variants.
 *      Builder also makes the Server.createOrder() call site readable.
 *      TALK ABOUT: "Builder separates accumulation from commitment; no half-baked Order."
 *
 * DD11 [AtomicReference<BillStatus> for Payment Idempotency]
 *      bill.tryStartPayment() does CAS(PENDING → PROCESSING); blocks double-charge.
 *      Alt: synchronized block — heavier; CAS is lock-free for a single-field transition.
 *      Alt: DB unique constraint — correct in prod, OOscope here.
 *      TALK ABOUT: "CAS on BillStatus ensures exactly-once payment even with concurrent retries."
 *
 * DD12 [Inheritance for Person Hierarchy]
 *      Person → Employee → {Server, Receptionist, Manager}; Person → Customer.
 *      Alt: flat class + role enum — loses compile-time enforcement (Manager.updateMenu
 *      callable by Server). Role-based ACL is future work; IS-A holds structurally.
 *      TALK ABOUT: "IS-A holds — Server is-an Employee; role-specific methods are
 *                  unreachable from wrong types at compile time."
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

// ══════════════════════════════════════════════════════════════════════════════
// ENUMS — DD8
// ══════════════════════════════════════════════════════════════════════════════

enum OrderStatus       { CREATED, ITEMS_ADDED, CONFIRMED, SERVED, BILLED, COMPLETED }
enum ReservationStatus { REQUESTED, CONFIRMED, CANCELLED, COMPLETED }
enum BillStatus        { PENDING, PROCESSING, PAID, FAILED }
enum TableStatus       { AVAILABLE, OCCUPIED, RESERVED, OUT_OF_SERVICE }
enum SeatType          { REGULAR, BAR, OUTDOOR }

// ══════════════════════════════════════════════════════════════════════════════
// DD1: COMPOSITE — MENU HIERARCHY
// ══════════════════════════════════════════════════════════════════════════════

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

// ================= INTERFACE =================
interface MenuComponent {
    String getName();
    String getDescription();
    double getPrice();          // leaf: item price; composite: aggregate sum
    boolean isAvailable();
    void print(String indent);

    // ✅ polymorphic search
    Optional<MenuItem> findItem(String itemId);
}

// ================= LEAF =================
class MenuItem implements MenuComponent {
    private final String id;
    private final String name;
    private final String description;
    private final double price;
    private volatile boolean available = true;

    public MenuItem(String id, String name, String description, double price) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public double getPrice() { return price; }
    @Override public boolean isAvailable() { return available; }

    @Override
    public void print(String indent) {
        System.out.printf("%s[Item] %-25s $%.2f%n", indent, name, price);
    }

    // ✅ leaf handles itself
    @Override
    public Optional<MenuItem> findItem(String itemId) {
        return this.id.equals(itemId) ? Optional.of(this) : Optional.empty();
    }

    public String getId() { return id; }

    public void setAvailable(boolean v) {
        this.available = v;
    }
}

// ================= COMPOSITE =================
class MenuSection implements MenuComponent {
    private final String name;
    private final String description;

    // Read-heavy → good choice
    private final List<MenuComponent> components = new CopyOnWriteArrayList<>();

    public MenuSection(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void add(MenuComponent c) {
        components.add(c);
    }

    public void remove(MenuComponent c) {
        components.remove(c);
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }

    @Override
    public double getPrice() {
        return components.stream()
                .mapToDouble(MenuComponent::getPrice)
                .sum();
    }

    @Override
    public boolean isAvailable() {
        return components.stream()
                .anyMatch(MenuComponent::isAvailable);
    }

    @Override
    public void print(String indent) {
        System.out.printf("%s[Section] %s%n", indent, name);
        components.forEach(c -> c.print(indent + "  "));
    }

    // ✅ composite delegates to children
    @Override
    public Optional<MenuItem> findItem(String itemId) {
        for (MenuComponent child : components) {
            Optional<MenuItem> found = child.findItem(itemId);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    public List<MenuComponent> getComponents() {
        return components;
    }
}

// ================= ROOT =================
class Menu {
    private final String id;
    private final MenuSection root;

    public Menu(String id, String name) {
        this.id = id;
        this.root = new MenuSection(name, "root");
    }

    public void addSection(MenuSection section) {
        root.add(section);
    }

    public String getId() {
        return id;
    }

    public void print() {
        root.print("");
    }

    public Optional<MenuItem> findItem(String itemId) {
        return root.findItem(itemId); // ✅ clean
    }
}


// ══════════════════════════════════════════════════════════════════════════════
// TABLE
// ══════════════════════════════════════════════════════════════════════════════

class Table {
    private final String id;
    private final int capacity;
    private final SeatType seatType;
    private volatile TableStatus status = TableStatus.AVAILABLE;

    public Table(String id, int capacity, SeatType seatType) {
        this.id = id; this.capacity = capacity; this.seatType = seatType;
    }

    public String getId()         { return id; }
    public int getCapacity()      { return capacity; }
    public SeatType getSeatType() { return seatType; }
    public TableStatus getStatus() { return status; }
    public void setStatus(TableStatus s) { this.status = s; }
    public boolean isAvailable()  { return status == TableStatus.AVAILABLE; }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD12: PERSON HIERARCHY (Inheritance)
// ══════════════════════════════════════════════════════════════════════════════

abstract class Person {
    protected final String id;
    protected String name, email, phone;

    public Person(String id, String name, String email, String phone) {
        this.id = id; this.name = name; this.email = email; this.phone = phone;
    }
    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
}

abstract class Employee extends Person {
    protected final String employeeId;
    protected final String branchId;

    public Employee(String id, String empId, String branchId, String name, String email, String phone) {
        super(id, name, email, phone);
        this.employeeId = empId; this.branchId = branchId;
    }
    public String getBranchId() { return branchId; }
}

class Server extends Employee {
    public Server(String id, String empId, String branchId, String name, String email, String phone) {
        super(id, empId, branchId, name, email, phone);
    }
    // Server-specific: can create an order (compile-time role enforcement)
    public Order createOrder(String orderId, Table table) {
        return new Order.OrderBuilder(orderId, table.getId(), this.id).build();
    }
}

class Receptionist extends Employee {
    public Receptionist(String id, String empId, String branchId, String name, String email, String phone) {
        super(id, empId, branchId, name, email, phone);
    }
    // Receptionist-specific: can make reservations on behalf of customer
}

class Manager extends Employee {
    public Manager(String id, String empId, String branchId, String name, String email, String phone) {
        super(id, empId, branchId, name, email, phone);
    }
    // Manager-specific: updateMenu and table configuration available only here
    public void updateBranchMenu(Branch branch, Menu newMenu) { branch.updateMenu(newMenu); }
    public void addTableToBranch(Branch branch, Table table)  { branch.addTable(table); }
}

class Customer extends Person {
    private final List<Reservation> history = new ArrayList<>();

    public Customer(String id, String name, String email, String phone) {
        super(id, name, email, phone);
    }
    public void addReservation(Reservation r)       { history.add(r); }
    public List<Reservation> getReservationHistory() { return Collections.unmodifiableList(history); }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD7 + DD8 + DD10: ORDER (Aggregate Root) + OrderItem + Builder
// ══════════════════════════════════════════════════════════════════════════════

class OrderItem {
    private final String menuItemId;
    private final String menuItemName;
    private final double unitPrice;
    private int quantity;
    private final String specialInstructions;

    public OrderItem(String menuItemId, String menuItemName,
                     double unitPrice, int quantity, String specialInstructions) {
        this.menuItemId = menuItemId; this.menuItemName = menuItemName;
        this.unitPrice = unitPrice; this.quantity = quantity;
        this.specialInstructions = specialInstructions;
    }
    public double getSubtotal()             { return unitPrice * quantity; }
    public String getMenuItemId()           { return menuItemId; }
    public String getMenuItemName()         { return menuItemName; }
    public int getQuantity()                { return quantity; }
    public String getSpecialInstructions()  { return specialInstructions; }
}

// DD7: Order as aggregate root; DD8: enum state machine; per-order ReentrantLock
class Order {
    private final String id;
    private final String tableId;
    private final String serverId;
    private final LocalDateTime createdAt;
    // DD7: COAL — kitchen displays read constantly; items added infrequently
    private final CopyOnWriteArrayList<OrderItem> items = new CopyOnWriteArrayList<>();
    private volatile OrderStatus status;
    private final ReentrantLock lock = new ReentrantLock(); // per-order state transitions

    private Order(OrderBuilder b) {
        this.id = b.id; this.tableId = b.tableId; this.serverId = b.serverId;
        this.createdAt = LocalDateTime.now(); this.status = OrderStatus.CREATED;
        items.addAll(b.items);
        if (!items.isEmpty()) status = OrderStatus.ITEMS_ADDED;
    }

    // DD8: guarded transitions — illegal moves return false, not throw
    public boolean addItem(OrderItem item) {
        lock.lock();
        try {
            if (status != OrderStatus.CREATED && status != OrderStatus.ITEMS_ADDED) return false;
            items.add(item);
            status = OrderStatus.ITEMS_ADDED;
            return true;
        } finally { lock.unlock(); }
    }

    public boolean confirm() {
        lock.lock();
        try {
            if (status != OrderStatus.ITEMS_ADDED) return false;
            status = OrderStatus.CONFIRMED; return true;
        } finally { lock.unlock(); }
    }

    public boolean markServed() {
        lock.lock();
        try {
            if (status != OrderStatus.CONFIRMED) return false;
            status = OrderStatus.SERVED; return true;
        } finally { lock.unlock(); }
    }

    public boolean markBilled() {
        lock.lock();
        try {
            if (status != OrderStatus.SERVED) return false;
            status = OrderStatus.BILLED; return true;
        } finally { lock.unlock(); }
    }

    public boolean markCompleted() {
        lock.lock();
        try {
            if (status != OrderStatus.BILLED) return false;
            status = OrderStatus.COMPLETED; return true;
        } finally { lock.unlock(); }
    }

    public double calculateTotal() {
        return items.stream().mapToDouble(OrderItem::getSubtotal).sum();
    }

    public String getId()                        { return id; }
    public String getTableId()                   { return tableId; }
    public OrderStatus getStatus()               { return status; }
    public List<OrderItem> getItems()            { return Collections.unmodifiableList(items); }

    // DD10: Builder — separates accumulation from commitment
    public static class OrderBuilder {
        private final String id, tableId, serverId;
        private final List<OrderItem> items = new ArrayList<>();

        public OrderBuilder(String id, String tableId, String serverId) {
            this.id = id; this.tableId = tableId; this.serverId = serverId;
        }
        public OrderBuilder addItem(OrderItem item) { items.add(item); return this; }
        public Order build() { return new Order(this); }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD4: OBSERVER — RESERVATION NOTIFICATIONS
// ══════════════════════════════════════════════════════════════════════════════

interface NotificationObserver {
    void onReservationConfirmed(Reservation r);
    void onReservationReminder(Reservation r);
    void onReservationCancelled(Reservation r);
}

class EmailNotificationObserver implements NotificationObserver {
    @Override public void onReservationConfirmed(Reservation r) {
        System.out.printf("[EMAIL] ✓ Reservation %s confirmed — table %s at %s%n",
            r.getId(), r.getTableId(), r.getStartTime().toLocalTime());
    }
    @Override public void onReservationReminder(Reservation r) {
        System.out.printf("[EMAIL] ⏰ Reminder: your reservation %s is at %s%n",
            r.getId(), r.getStartTime().toLocalTime());
    }
    @Override public void onReservationCancelled(Reservation r) {
        System.out.printf("[EMAIL] ✗ Reservation %s cancelled%n", r.getId());
    }
}

class SMSNotificationObserver implements NotificationObserver {
    @Override public void onReservationConfirmed(Reservation r) {
        System.out.printf("[SMS]   ✓ Table reserved for %s at %s%n",
            r.getCustomerId(), r.getStartTime().toLocalTime());
    }
    @Override public void onReservationReminder(Reservation r) {
        System.out.printf("[SMS]   ⏰ Your table is ready at %s%n", r.getStartTime().toLocalTime());
    }
    @Override public void onReservationCancelled(Reservation r) {
        System.out.printf("[SMS]   ✗ Reservation %s was cancelled%n", r.getId());
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// RESERVATION — DD4 (Observer) + DD8 (State Machine) + DD6 (overlap)
// ══════════════════════════════════════════════════════════════════════════════

class Reservation implements Comparable<Reservation> {
    private final String id;
    private final String tableId;
    private final String customerId;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final int partySize;
    private volatile ReservationStatus status = ReservationStatus.REQUESTED;
    private final List<NotificationObserver> observers;

    public Reservation(String id, String tableId, String customerId,
                       LocalDateTime start, LocalDateTime end,
                       int partySize, List<NotificationObserver> observers) {
        this.id = id; this.tableId = tableId; this.customerId = customerId;
        this.startTime = start; this.endTime = end;
        this.partySize = partySize;
        this.observers = new ArrayList<>(observers);
    }

    // Used inside writeLock by ReservationManager (DD6)
    public boolean overlapsWith(LocalDateTime start, LocalDateTime end) {
        return this.startTime.isBefore(end) && this.endTime.isAfter(start);
    }

    public boolean confirm() {
        if (status != ReservationStatus.REQUESTED) return false;
        status = ReservationStatus.CONFIRMED;
        observers.forEach(o -> o.onReservationConfirmed(this));
        return true;
    }

    public boolean cancel() {
        if (status == ReservationStatus.COMPLETED || status == ReservationStatus.CANCELLED) return false;
        status = ReservationStatus.CANCELLED;
        observers.forEach(o -> o.onReservationCancelled(this));
        return true;
    }

    public boolean complete() {
        if (status != ReservationStatus.CONFIRMED) return false;
        status = ReservationStatus.COMPLETED; return true;
    }

    public void sendReminder() {
        if (status == ReservationStatus.CONFIRMED)
            observers.forEach(o -> o.onReservationReminder(this));
    }

    @Override public int compareTo(Reservation o) { return this.startTime.compareTo(o.startTime); }

    public String getId()               { return id; }
    public String getTableId()          { return tableId; }
    public String getCustomerId()       { return customerId; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime()   { return endTime; }
    public int getPartySize()           { return partySize; }
    public ReservationStatus getStatus(){ return status; }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD2: STRATEGY — PAYMENT
// ══════════════════════════════════════════════════════════════════════════════

interface PaymentStrategy {
    boolean charge(double amount, String billId);
    String getMethodName();
}

class CashPayment implements PaymentStrategy {
    @Override public boolean charge(double amount, String billId) {
        System.out.printf("[CASH]  Collected $%.2f for bill %s%n", amount, billId);
        return true;
    }
    @Override public String getMethodName() { return "CASH"; }
}

class CreditCardPayment implements PaymentStrategy {
    private final String maskedCard;
    public CreditCardPayment(String maskedCard) { this.maskedCard = maskedCard; }

    @Override public boolean charge(double amount, String billId) {
        System.out.printf("[CARD]  Charged $%.2f on %s for bill %s%n", amount, maskedCard, billId);
        return true;
    }
    @Override public String getMethodName() { return "CREDIT_CARD:" + maskedCard; }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD11: BILL — AtomicReference for payment idempotency
// ══════════════════════════════════════════════════════════════════════════════

class Bill {
    private final String id;
    private final String orderId;
    private final double subtotal;
    private final double taxRate;
    private final double total;
    // DD11: CAS on status prevents double-charge
    private final AtomicReference<BillStatus> status = new AtomicReference<>(BillStatus.PENDING);
    private volatile String paymentMethod;

    public Bill(String id, String orderId, double subtotal, double taxRate) {
        this.id = id; this.orderId = orderId; this.subtotal = subtotal;
        this.taxRate = taxRate; this.total = subtotal * (1 + taxRate);
    }

    // DD11: atomic PENDING → PROCESSING; only one thread wins
    public boolean tryStartPayment() {
        return status.compareAndSet(BillStatus.PENDING, BillStatus.PROCESSING);
    }
    public void markPaid(String method)  { status.set(BillStatus.PAID); this.paymentMethod = method; }
    public void markFailed()             { status.set(BillStatus.FAILED); }

    public String getId()         { return id; }
    public String getOrderId()    { return orderId; }
    public double getTotal()      { return total; }
    public BillStatus getStatus() { return status.get(); }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD3: TEMPLATE METHOD — BILL PROCESSOR
//   Skeleton: validate → calculate → charge (hook) → receipt
// ══════════════════════════════════════════════════════════════════════════════

abstract class BillProcessor {
    // Template method — final: skeleton is invariant
    public final boolean process(Bill bill, PaymentStrategy strategy) {
        if (!validate(bill))     return false;          // DD11: CAS idempotency check
        double amount = calculate(bill);
        boolean charged = charge(bill, strategy, amount); // hook — varies by subclass
        if (charged) {
            finalizeReceipt(bill, strategy.getMethodName(), amount);
            return true;
        }
        bill.markFailed();
        return false;
    }

    protected boolean validate(Bill bill) {
        if (!bill.tryStartPayment()) {                  // DD11: CAS gate
            System.out.printf("[BILL]  Already processed (status=%s): %s%n",
                bill.getStatus(), bill.getId());
            return false;
        }
        return true;
    }

    protected double calculate(Bill bill) {
        System.out.printf("[BILL]  Total due: $%.2f%n", bill.getTotal());
        return bill.getTotal();
    }

    // Hook — subclasses override this step only
    protected abstract boolean charge(Bill bill, PaymentStrategy strategy, double amount);

    protected void finalizeReceipt(Bill bill, String method, double amount) {
        bill.markPaid(method);
        System.out.printf("[RECEIPT] Bill %s paid via %s: $%.2f%n",
            bill.getId(), method, amount);
    }
}

class StandardBillProcessor extends BillProcessor {
    @Override
    protected boolean charge(Bill bill, PaymentStrategy strategy, double amount) {
        return strategy.charge(amount, bill.getId());   // DD2: Strategy delegates here
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// DD5 + DD6 + DD9: RESERVATION MANAGER (Singleton)
//   Per-table RWLock map; check-then-act inside writeLock
// ══════════════════════════════════════════════════════════════════════════════

class ReservationManager {
    // DD9: Double-checked locking singleton
    private static volatile ReservationManager instance;

    // DD5: per-table locks — unrelated tables don't contend
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> tableLocks  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TreeSet<Reservation>>   tableSlots  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Reservation>            byId        = new ConcurrentHashMap<>();
    // DD4: global observers injected into every Reservation
    private final List<NotificationObserver> globalObservers = new CopyOnWriteArrayList<>();

    private ReservationManager() {}

    public static ReservationManager getInstance() {
        if (instance == null) {
            synchronized (ReservationManager.class) {
                if (instance == null) instance = new ReservationManager();
            }
        }
        return instance;
    }

    public void registerObserver(NotificationObserver o) { globalObservers.add(o); }

    public void registerTable(String tableId) {
        tableLocks.putIfAbsent(tableId, new ReentrantReadWriteLock());
        tableSlots.putIfAbsent(tableId, new TreeSet<>());
    }

    // DD6: overlap check + insert are BOTH inside writeLock — no TOCTOU gap
    public Optional<Reservation> makeReservation(String resId, String tableId, String customerId,
                                                  LocalDateTime start, LocalDateTime end, int partySize) {
        ReentrantReadWriteLock lock = tableLocks.get(tableId);
        if (lock == null) throw new IllegalArgumentException("Table not registered: " + tableId);

        lock.writeLock().lock();
        try {
            TreeSet<Reservation> existing = tableSlots.get(tableId);
            // DD6: check INSIDE the write lock — atomicity guaranteed
            boolean conflict = existing.stream()
                .filter(r -> r.getStatus() != ReservationStatus.CANCELLED)
                .anyMatch(r -> r.overlapsWith(start, end));
            if (conflict) return Optional.empty();

            Reservation res = new Reservation(resId, tableId, customerId,
                start, end, partySize, globalObservers);
            res.confirm();          // fires DD4 observers
            existing.add(res);
            byId.put(resId, res);
            return Optional.of(res);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean cancelReservation(String resId) {
        Reservation r = byId.get(resId);
        if (r == null) return false;
        ReentrantReadWriteLock lock = tableLocks.get(r.getTableId());
        lock.writeLock().lock();
        try { return r.cancel(); }
        finally { lock.writeLock().unlock(); }
    }

    // R5/R7: availability search — reads all matching tables concurrently
    public List<String> findAvailableTables(List<String> tableIds, LocalDateTime start, LocalDateTime end) {
        List<String> available = new ArrayList<>();
        for (String tableId : tableIds) {
            ReentrantReadWriteLock lock = tableLocks.get(tableId);
            if (lock == null) continue;
            lock.readLock().lock();
            try {
                boolean conflict = tableSlots.get(tableId).stream()
                    .filter(r -> r.getStatus() != ReservationStatus.CANCELLED)
                    .anyMatch(r -> r.overlapsWith(start, end));
                if (!conflict) available.add(tableId);
            } finally {
                lock.readLock().unlock();
            }
        }
        return available;
    }

    public Optional<Reservation> getReservation(String id) { return Optional.ofNullable(byId.get(id)); }
}

// ══════════════════════════════════════════════════════════════════════════════
// ORDER MANAGER — owns Order and Bill lifecycle
// ══════════════════════════════════════════════════════════════════════════════

class OrderManager {
    private final ConcurrentHashMap<String, Order>  orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bill>   bills  = new ConcurrentHashMap<>();
    private final BillProcessor billProcessor = new StandardBillProcessor(); // DD3

    public Order createOrder(String orderId, String tableId, String serverId) {
        Order order = new Order.OrderBuilder(orderId, tableId, serverId).build();
        orders.put(orderId, order);
        return order;
    }

    public boolean addItemToOrder(String orderId, MenuItem menuItem, int qty, String note) {
        Order order = getOrderOrThrow(orderId);
        return order.addItem(new OrderItem(menuItem.getId(), menuItem.getName(),
            menuItem.getPrice(), qty, note));
    }

    public Bill generateBill(String billId, String orderId, double taxRate) {
        Order order = getOrderOrThrow(orderId);
        if (!order.markBilled()) throw new IllegalStateException("Order not in SERVED state: " + orderId);
        Bill bill = new Bill(billId, orderId, order.calculateTotal(), taxRate);
        bills.put(billId, bill);
        return bill;
    }

    public boolean processPayment(String billId, PaymentStrategy strategy) {
        Bill bill = getBillOrThrow(billId);
        boolean success = billProcessor.process(bill, strategy);  // DD3 template
        if (success) {
            Optional.ofNullable(orders.get(bill.getOrderId())).ifPresent(Order::markCompleted);
        }
        return success;
    }

    public Optional<Order> getOrder(String id) { return Optional.ofNullable(orders.get(id)); }
    public Optional<Bill>  getBill(String id)  { return Optional.ofNullable(bills.get(id)); }

    private Order getOrderOrThrow(String id) {
        Order o = orders.get(id);
        if (o == null) throw new IllegalArgumentException("Order not found: " + id);
        return o;
    }
    private Bill getBillOrThrow(String id) {
        Bill b = bills.get(id);
        if (b == null) throw new IllegalArgumentException("Bill not found: " + id);
        return b;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// BRANCH — independent unit (R1, R11); delegates reservation to singleton
// ══════════════════════════════════════════════════════════════════════════════

class Branch {
    private final String id;
    private final String name;
    private final String address;
    private volatile Menu menu;                            // R11: manager can swap
    private final ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<>();
    private final ReservationManager resMgr = ReservationManager.getInstance(); // DD9
    private final OrderManager orderMgr = new OrderManager();

    public Branch(String id, String name, String address, Menu menu) {
        this.id = id; this.name = name; this.address = address; this.menu = menu;
    }

    // R11: only Manager calls this (compile-time enforcement via DD12)
    void updateMenu(Menu newMenu) { this.menu = newMenu; }
    void addTable(Table table) {
        tables.put(table.getId(), table);
        resMgr.registerTable(table.getId());
    }

    // R5: walk-in availability
    public List<Table> getAvailableTablesForWalkIn() {
        List<Table> avail = new ArrayList<>();
        for (Table t : tables.values()) if (t.isAvailable()) avail.add(t);
        return avail;
    }

    // R7: reservation search
    public List<String> findAvailableTablesForReservation(LocalDateTime start, LocalDateTime end) {
        return resMgr.findAvailableTables(new ArrayList<>(tables.keySet()), start, end);
    }

    // R6/R8: make reservation
    public Optional<Reservation> makeReservation(String resId, String tableId,
                                                   String customerId, LocalDateTime start,
                                                   LocalDateTime end, int partySize) {
        Table t = tables.get(tableId);
        if (t == null || t.getCapacity() < partySize) return Optional.empty();
        return resMgr.makeReservation(resId, tableId, customerId, start, end, partySize);
    }

    // R8: cancel reservation
    public boolean cancelReservation(String resId) { return resMgr.cancelReservation(resId); }

    // R3: create order for a table
    public Order createOrder(String orderId, String tableId, String serverId) {
        Table t = tables.get(tableId);
        if (t == null) throw new IllegalArgumentException("Table not found: " + tableId);
        t.setStatus(TableStatus.OCCUPIED);
        return orderMgr.createOrder(orderId, tableId, serverId);
    }

    // R3/R4: add item to order via menu lookup
    public void addItemToOrder(String orderId, String menuItemId, int qty, String note) {
        MenuItem item = menu.findItem(menuItemId)
            .orElseThrow(() -> new IllegalArgumentException("Menu item not found: " + menuItemId));
        orderMgr.addItemToOrder(orderId, item, qty, note);
    }

    // R10/R12: billing and payment
    public Bill generateBill(String billId, String orderId) {
        return orderMgr.generateBill(billId, orderId, 0.08); // 8% tax
    }

    public boolean processPayment(String billId, PaymentStrategy strategy) {
        boolean result = orderMgr.processPayment(billId, strategy);
        if (result) {
            Optional<Bill> billOpt = orderMgr.getBill(billId);

if (billOpt.isPresent()) {
    Bill b = billOpt.get();

    Optional<Order> orderOpt = orderMgr.getOrder(b.getOrderId());
    if (orderOpt.isPresent()) {
        Order o = orderOpt.get();

        String tableId = o.getTableId();
        Table t = tables.get(tableId);

        if (t != null) {
            t.setStatus(TableStatus.AVAILABLE);
        }
    }
}
        }
        return result;
    }

    public String getId()   { return id; }
    public String getName() { return name; }
    public Menu getMenu()   { return menu; }
    public OrderManager getOrderManager() { return orderMgr; }
}

// ══════════════════════════════════════════════════════════════════════════════
// RESTAURANT — top level (R1)
// ══════════════════════════════════════════════════════════════════════════════

class Restaurant {
    private final String id;
    private final String name;
    private final ConcurrentHashMap<String, Branch> branches = new ConcurrentHashMap<>();

    public Restaurant(String id, String name) { this.id = id; this.name = name; }
    public void addBranch(Branch b)           { branches.put(b.getId(), b); }
    public Optional<Branch> getBranch(String id) { return Optional.ofNullable(branches.get(id)); }
    public String getName() { return name; }
}

// ══════════════════════════════════════════════════════════════════════════════
// DEMO — exercises all requirements and design decisions
// ══════════════════════════════════════════════════════════════════════════════

public class RestaurantManagementSystem {

    public static void main(String[] args) {

        // ── Setup ──────────────────────────────────────────────────────────
        ReservationManager resMgr = ReservationManager.getInstance();
        resMgr.registerObserver(new EmailNotificationObserver()); // DD4
        resMgr.registerObserver(new SMSNotificationObserver());   // DD4

        // DD1: Composite menu
        Menu menu = new Menu("m1", "The Good Fork Menu");
        MenuSection starters = new MenuSection("Starters", "Light bites");
        starters.add(new MenuItem("i1", "Spring Rolls",    "Crispy veg rolls",        8.99));
        starters.add(new MenuItem("i2", "Soup of the Day", "Chef's special",           6.99));
        MenuSection mains = new MenuSection("Mains", "Main courses");
        mains.add(new MenuItem("i3", "Grilled Salmon",  "Atlantic salmon, lemon butter", 24.99));
        mains.add(new MenuItem("i4", "Pasta Primavera", "Fresh vegetable pasta",         16.99));
        menu.addSection(starters);
        menu.addSection(mains);

        System.out.println("═══ MENU ═══");
        menu.print();

        // Branch + tables (R1, R11)
        Branch downtown = new Branch("b1", "Downtown", "123 Main St", menu);
        Manager mgr = new Manager("emp0", "M001", "b1", "Alice", "alice@fork.com", "555-0000");
        mgr.addTableToBranch(downtown, new Table("t1", 4, SeatType.REGULAR));
        mgr.addTableToBranch(downtown, new Table("t2", 2, SeatType.BAR));
        mgr.addTableToBranch(downtown, new Table("t3", 6, SeatType.OUTDOOR));

        Restaurant restaurant = new Restaurant("r1", "The Good Fork");
        restaurant.addBranch(downtown);

        // ── Scenario 1: Reservation (R6, R7, DD5, DD6) ────────────────────
        System.out.println("\n═══ RESERVATION SCENARIO (DD5 + DD6) ═══");
        LocalDateTime tonight7 = LocalDateTime.now().withHour(19).withMinute(0).withSecond(0);
        LocalDateTime tonight9 = tonight7.plusHours(2);

        Optional<Reservation> res1 = downtown.makeReservation(
            "res1", "t1", "cust1", tonight7, tonight9, 3);
        res1.ifPresent(r -> System.out.printf("Reservation %s: status=%s%n", r.getId(), r.getStatus()));

        // Attempt double-booking same slot — DD6 blocks it
        Optional<Reservation> res2 = downtown.makeReservation(
            "res2", "t1", "cust2", tonight7, tonight9, 2);
        System.out.println("Double-book t1 same slot: "
            + (res2.isEmpty() ? "REJECTED ✓ (DD6 check-then-act held)" : "ACCEPTED ✗ (bug!)"));

        // Non-overlapping slot on same table — should succeed
        Optional<Reservation> res3 = downtown.makeReservation(
            "res3", "t1", "cust3", tonight9, tonight9.plusHours(2), 4);
        res3.ifPresent(r -> System.out.printf("Non-overlapping slot %s: status=%s%n", r.getId(), r.getStatus()));

        // Send reminder (DD4 Observer)
        res1.ifPresent(Reservation::sendReminder);

        // Cancel reservation (R8)
        boolean cancelled = downtown.cancelReservation("res3");
        System.out.println("Cancel res3: " + cancelled);

        // ── Scenario 2: Walk-in order + payment (R3, R4, R10, R12) ────────
        System.out.println("\n═══ ORDER + PAYMENT SCENARIO ═══");
        List<Table> walkIn = downtown.getAvailableTablesForWalkIn(); // R5
        System.out.println("Walk-in tables: " + walkIn.stream().map(Table::getId).toList());

        Server server = new Server("emp1", "S001", "b1", "Bob", "bob@fork.com", "555-0001");
        Order order = downtown.createOrder("ord1", "t2", server.getId());
        downtown.addItemToOrder("ord1", "i3", 1, "no lemon");   // R3/R4
        downtown.addItemToOrder("ord1", "i1", 2, "extra sauce"); // R3/R4
        order.confirm();
        order.markServed();
        System.out.printf("Order total (pre-tax): $%.2f%n", order.calculateTotal());

        Bill bill = downtown.generateBill("bill1", "ord1");
        System.out.printf("Bill total (8%% tax):   $%.2f%n", bill.getTotal());

        // DD2 Strategy + DD3 Template Method + DD11 CAS — pay by card
        boolean paid = downtown.processPayment("bill1", new CreditCardPayment("****1234"));
        System.out.println("Payment result: " + paid + " | BillStatus: " + bill.getStatus());

        // ── Scenario 3: Concurrent double-pay attempt (DD11) ──────────────
        System.out.println("\n═══ DOUBLE PAYMENT ATTEMPT (DD11 CAS) ═══");
        boolean paidAgain = downtown.processPayment("bill1", new CashPayment());
        System.out.println("Second payment: " + paidAgain
            + " (should be false — CAS gate blocked it)");

        // Table freed after payment
        System.out.println("Table t2 status after payment: "
            + downtown.getAvailableTablesForWalkIn().stream()
                      .map(Table::getId).toList());

        // ── Scenario 4: Manager updates menu (R11, DD12) ──────────────────
        System.out.println("\n═══ MANAGER MENU UPDATE (R11 + DD12) ═══");
        Menu updatedMenu = new Menu("m2", "Summer Menu");
        MenuSection specials = new MenuSection("Summer Specials", "Seasonal");
        specials.add(new MenuItem("i5", "Lobster Bisque", "Rich lobster soup", 18.99));
        updatedMenu.addSection(specials);
        mgr.updateBranchMenu(downtown, updatedMenu); // only Manager can call this
        System.out.println("Menu updated to: " + downtown.getMenu().getId());
        downtown.getMenu().print();
    }
}
