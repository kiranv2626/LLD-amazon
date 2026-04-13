import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.time.*;

/**
 * ============================================================================
 * ONLINE STOCK BROKERAGE SYSTEM — Low-Level Design (Strong Hire Target)
 * ============================================================================
 *
 * DD1  — Factory Pattern for Order Creation
 *        Choice:   OrderFactory.create(type, params) returns typed Order subclass.
 *        Why:      Centralizes validation + creation; new order types = one new case + subclass.
 *        Alt:      Builder alone — but Builder is for multi-step config, Factory picks the *type*.
 *        Tradeoff: Factory + Builder combined adds classes; justified by 4 distinct order types
 *                  with different required fields.
 *        TALK ABOUT: "Factory selects the order type; Builder configures it. Separating
 *                     type-selection from construction keeps each concern single-responsibility."
 *
 * DD2  — Builder Pattern for Order Construction
 *        Choice:   OrderBuilder chains .symbol().qty().limitPrice().stopPrice().build().
 *        Why:      Stop-limit needs 4+ params; telescoping constructors unreadable at interview.
 *        Alt:      Parameter object / record — simpler but loses fluent validation per step.
 *        Tradeoff: More code than a record; pays off when orders get richer (time-in-force, etc).
 *        TALK ABOUT: "Builder prevents invalid intermediate states — e.g., you can't set a
 *                     limitPrice on a MarketOrder because the builder validates at build()."
 *
 * DD3  — Strategy Pattern for Payment Processing
 *        Choice:   PaymentStrategy interface with ElectronicTransfer, Wire, Check impls.
 *        Why:      Each channel has different latency, fees, validation — encapsulate per-channel.
 *        Alt:      If/else in Account.deposit() — violates OCP; every new channel touches Account.
 *        Tradeoff: One class per channel; N channels = N classes. Acceptable for 3-5 channels.
 *        TALK ABOUT: "Strategy lets me add crypto deposits without touching Account. The account
 *                     just calls strategy.execute(amount) — it doesn't know the channel."
 *
 * DD4  — Observer Pattern for Price & Order Notifications
 *        Choice:   StockExchange publishes price ticks; Watchlists + StopOrders subscribe.
 *        Why:      Decouples price feed from consumers; watchlists and stop-orders are both
 *                  observers but react differently (alert vs trigger execution).
 *        Alt:      Polling — wastes CPU, adds latency. Callback registry — same as Observer
 *                  but less structured.
 *        Tradeoff: CopyOnWriteArrayList for observer list means O(n) copy on subscribe; fine
 *                  because subscribes are rare vs price ticks.
 *        TALK ABOUT: "Observer decouples the price feed from consumers. Adding a new consumer
 *                     (e.g., analytics engine) requires zero changes to StockExchange."
 *
 * DD5  — Decorator Pattern for Notification Channels
 *        Choice:   BaseNotifier → EmailDecorator → SMSDecorator; stack as needed.
 *        Why:      User may want email-only, SMS-only, or both. Decorator composes at runtime.
 *        Alt:      Strategy per channel — but can't combine channels without a composite.
 *                  Composite + Strategy works but Decorator is simpler for additive behavior.
 *        Tradeoff: Deep decorator stacks get hard to debug; mitigated by capping at 3 layers.
 *        TALK ABOUT: "Decorator lets me add logging or rate-limiting on top of SMS on top of
 *                     email — each layer is independent and testable."
 *
 * DD6  — Singleton for StockExchange
 *        Choice:   Enum singleton — thread-safe by JLS, serialization-safe, reflection-proof.
 *        Why:      One exchange instance manages the central order book + price feed.
 *        Alt:      Double-checked locking — works but verbose and error-prone pre-Java5.
 *                  Static holder — safe but less explicit than enum.
 *        Tradeoff: Enum can't extend classes; acceptable since StockExchange has no superclass.
 *        TALK ABOUT: "Enum singleton is the Joshua Bloch recommendation — JVM guarantees exactly
 *                     one instance with zero synchronization overhead."
 *
 * DD7  — Command Pattern for Order Execution Queue
 *        Choice:   Each order becomes a command in a ConcurrentLinkedQueue; executor processes.
 *        Why:      Decouples order submission from execution; enables logging, undo, retry.
 *        Alt:      Direct synchronous execution — simpler but blocks the caller on exchange I/O.
 *        Tradeoff: Adds async complexity; compensated by natural audit trail (command log).
 *        TALK ABOUT: "Command pattern gives me a replayable audit log for free — every order
 *                     is a serializable object I can persist, retry, or roll back."
 *
 * DD8  — Composite Pattern for Portfolio → Positions → Lots
 *        Choice:   Portfolio contains StockPositions; each StockPosition contains multiple Lots.
 *        Why:      R3 requires distinguishing lots of the same stock. Composite lets
 *                  totalValue() recurse: Portfolio → sum(Position.totalValue()) → sum(Lot.value()).
 *        Alt:      Flat list of lots with groupBy — works but loses the positional aggregation.
 *        Tradeoff: Three-level tree adds traversal cost; negligible for typical portfolio sizes.
 *        TALK ABOUT: "Composite lets me call portfolio.totalValue() and it recursively aggregates
 *                     across positions and lots — uniform interface at every level."
 *
 * DD9  — Template Method for Notification Sending
 *        Choice:   AbstractNotifier defines send() skeleton: format → deliver → log.
 *        Why:      All channels share format+log; only delivery differs.
 *        Alt:      Strategy — but here the *algorithm skeleton* is shared, not just one step.
 *        Tradeoff: Inheritance coupling; acceptable for a small, stable notification hierarchy.
 *        TALK ABOUT: "Template Method enforces that every notification is formatted and logged
 *                     identically — subclasses only override the transport-specific delivery."
 *
 * DD10 — Order State Machine with Enum + Transitions
 *        Choice:   OrderStatus enum with allowed-transition validation.
 *        Why:      Prevents illegal transitions (e.g., FILLED → PENDING); makes states explicit.
 *        Alt:      Boolean flags (isFilled, isCancelled) — combinatorial explosion, easy to
 *                  create impossible states.
 *        Tradeoff: Transition table is rigid; if workflows diverge per order type, consider
 *                  State pattern. For 4 types with same lifecycle, enum suffices.
 *        TALK ABOUT: "Enum state machine makes illegal transitions unrepresentable — the
 *                     canTransitionTo() method is the single source of truth for order lifecycle."
 *
 * DD11 — ReentrantReadWriteLock per Account for Balance Operations
 *        Choice:   Each Account has its own RWLock; deposit/withdraw take writeLock,
 *                  getBalance takes readLock.
 *        Why:      Avoids global lock contention; two users trading simultaneously don't block.
 *        Alt:      AtomicDouble (doesn't exist natively), or synchronized(this) — coarser.
 *                  AtomicReference<BigDecimal> with CAS — viable but complex for multi-step
 *                  (check balance + deduct + create lot must be atomic).
 *        Tradeoff: RWLock per account = more memory; trivial at realistic account counts.
 *        TALK ABOUT: "Per-account RWLock is the SDE2 differentiator — coarse locks serialize
 *                     all users; per-entity locks let independent accounts trade concurrently."
 *
 * DD12 — ConcurrentHashMap for Stock Inventory + Order Books
 *        Choice:   ConcurrentHashMap<String, Stock> for O(1) symbol lookup, lock-free reads.
 *        Why:      Price lookups vastly outnumber inserts; CHM optimizes for read-heavy.
 *        Alt:      Synchronized HashMap — simple but serializes all reads.
 *                  TreeMap for sorted iteration — not needed; we look up by symbol, not range.
 *        Tradeoff: No global consistent snapshot; acceptable since stocks are independent.
 *        TALK ABOUT: "ConcurrentHashMap gives me lock-free reads for price checks, which are
 *                     the 99% operation — writes only lock the bucket."
 *
 * DD13 — CopyOnWriteArrayList for Observer Lists
 *        Choice:   Price observers stored in COWAL per stock symbol.
 *        Why:      Iteration (notify all) vastly outnumbers mutation (subscribe/unsubscribe).
 *                  COWAL gives snapshot iteration with zero locking during notification.
 *        Alt:      Synchronized ArrayList — locks during iteration, blocking new subscribes.
 *                  RWLock + ArrayList — works but more boilerplate for same semantics.
 *        Tradeoff: O(n) copy on add; fine because subscriber changes are infrequent.
 *        TALK ABOUT: "COWAL is purpose-built for observe-heavy, mutate-light lists — exactly
 *                     the observer pattern's access profile."
 *
 * DD14 — AtomicInteger for Order ID Generation
 *        Choice:   Global AtomicInteger.incrementAndGet() for unique order IDs.
 *        Why:      Lock-free, monotonic, sufficient for single-JVM LLD scope.
 *        Alt:      UUID — globally unique but unsortable and verbose.
 *                  Database sequence — production-grade but out of LLD scope.
 *        Tradeoff: Resets on restart; in production, use persistent sequence.
 *        TALK ABOUT: "AtomicInteger gives lock-free monotonic IDs — in production I'd swap
 *                     to a DB sequence or Snowflake ID for persistence + distribution."
 *
 * DD15 — Check-Then-Act Atomicity in Order Execution
 *        Choice:   Inside Account.writeLock: check balance → deduct → create lot → update order.
 *        Why:      Without atomic CTA, two concurrent market orders could both pass the balance
 *                  check and overdraw. The writeLock serializes per-account.
 *        Alt:      Optimistic CAS on balance — retries on contention. Works for simple deduct
 *                  but can't atomically create the lot in the same CAS.
 *        Tradeoff: WriteLock blocks concurrent sells on the same account; acceptable because
 *                  one user's orders are inherently sequential from their perspective.
 *        TALK ABOUT: "The balance check and deduction MUST be inside the same writeLock — any
 *                     gap between check and act is a race condition that causes overdraft."
 */

// ===================== ENUMS =====================

enum OrderType { MARKET, LIMIT, STOP_LOSS, STOP_LIMIT }

// DD10: State machine with explicit transition validation

    // DD10: State machine — simplified with switch
enum OrderStatus {
    PENDING, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED;
    boolean canTransitionTo(OrderStatus next) {
        switch (this) {
            case PENDING:
                return next == PARTIALLY_FILLED || next == FILLED
                    || next == CANCELLED || next == REJECTED;
            case PARTIALLY_FILLED:
                return next == FILLED || next == CANCELLED;
            case FILLED:
            case CANCELLED:
            case REJECTED:
                return false;  // terminal states — no transitions allowed
            default:
                return false;
        }
    }
}

enum OrderSide { BUY, SELL }

enum TransactionType { DEPOSIT, WITHDRAWAL }

enum PaymentMode { ELECTRONIC_TRANSFER, WIRE, CHECK }

// ===================== VALUE OBJECTS =====================

class Money {
    private final double amount; // DD: BigDecimal in production; double for LLD brevity
    Money(double amount) { this.amount = amount; }
    double getAmount() { return amount; }
    Money add(Money other) { return new Money(this.amount + other.amount); }
    Money subtract(Money other) { return new Money(this.amount - other.amount); }
    boolean isGreaterThanOrEqual(Money other) { return this.amount >= other.amount; }
    @Override public String toString() { return String.format("$%.2f", amount); }
}

// ===================== STOCK & LOT (DD8: Composite leaf) =====================

class Stock {
    private final String symbol;
    private final String name;
    private volatile double currentPrice; // volatile for visibility across threads

    Stock(String symbol, String name, double initialPrice) {
        this.symbol = symbol;
        this.name = name;
        this.currentPrice = initialPrice;
    }

    String getSymbol() { return symbol; }
    String getName() { return name; }
    double getCurrentPrice() { return currentPrice; }
    void setCurrentPrice(double price) { this.currentPrice = price; }
}

// DD8: Composite leaf — individual purchase lot
class StockLot {
    private final String lotId;
    private final String symbol;
    private final int quantity;
    private final double purchasePrice;
    private final Instant purchaseTime;

    StockLot(String lotId, String symbol, int quantity, double purchasePrice) {
        this.lotId = lotId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.purchasePrice = purchasePrice;
        this.purchaseTime = Instant.now();
    }

    String getLotId() { return lotId; }
    String getSymbol() { return symbol; }
    int getQuantity() { return quantity; }
    double getPurchasePrice() { return purchasePrice; }
    double getCostBasis() { return quantity * purchasePrice; }
    double getCurrentValue(double currentPrice) { return quantity * currentPrice; }

    @Override
    public String toString() {
        return String.format("Lot[%s: %d@%.2f on %s]", lotId, quantity, purchasePrice, purchaseTime);
    }
}

// DD8: Composite intermediate — aggregates lots for one symbol
class StockPosition {
    private final String symbol;
    private final List<StockLot> lots = new CopyOnWriteArrayList<>(); // DD13

    StockPosition(String symbol) { this.symbol = symbol; }

    String getSymbol() { return symbol; }
    List<StockLot> getLots() { return Collections.unmodifiableList(lots); }

    void addLot(StockLot lot) { lots.add(lot); }

    // Returns lots removed to fill qty; partial lot handling omitted for LLD brevity
    List<StockLot> removeLots(int qty) {
        List<StockLot> removed = new ArrayList<>();
        int remaining = qty;
        Iterator<StockLot> it = lots.iterator();
        while (it.hasNext() && remaining > 0) {
            StockLot lot = it.next();
            if (lot.getQuantity() <= remaining) {
                removed.add(lot);
                lots.remove(lot);
                remaining -= lot.getQuantity();
            } else {
                // Partial lot — split (simplified: remove whole, add remainder)
                lots.remove(lot);
                removed.add(new StockLot(lot.getLotId(), lot.getSymbol(), remaining, lot.getPurchasePrice()));
                lots.add(new StockLot(lot.getLotId() + "-rem", lot.getSymbol(),
                        lot.getQuantity() - remaining, lot.getPurchasePrice()));
                remaining = 0;
            }
        }
        return removed;
    }

    int getTotalQuantity() { return lots.stream().mapToInt(StockLot::getQuantity).sum(); }

    double getTotalCostBasis() { return lots.stream().mapToDouble(StockLot::getCostBasis).sum(); }

    double getCurrentValue(double currentPrice) {
        return lots.stream().mapToDouble(l -> l.getCurrentValue(currentPrice)).sum();
    }
}

// DD8: Composite root — aggregates positions
class Portfolio {
    private final ConcurrentHashMap<String, StockPosition> positions = new ConcurrentHashMap<>();

    StockPosition getOrCreatePosition(String symbol) {
        return positions.computeIfAbsent(symbol, s -> new StockPosition(s));
    }

    StockPosition getPosition(String symbol) { return positions.get(symbol); }

    Map<String, StockPosition> getAllPositions() {
        return Collections.unmodifiableMap(positions);
    }

    double getTotalValue(Map<String, Double> currentPrices) {
        return positions.entrySet().stream()
            .mapToDouble(e -> e.getValue().getCurrentValue(
                currentPrices.getOrDefault(e.getKey(), 0.0)))
            .sum();
    }
}

// ===================== WATCHLIST (DD4: Observer subscriber) =====================

// DD4: Observer interface
interface PriceObserver {
    void onPriceUpdate(String symbol, double oldPrice, double newPrice);
}

class WatchList implements PriceObserver {
    private final String watchListId;
    private final String name;
    private final Set<String> symbols = ConcurrentHashMap.newKeySet();
    private final String ownerId;

    WatchList(String watchListId, String name, String ownerId) {
        this.watchListId = watchListId;
        this.name = name;
        this.ownerId = ownerId;
    }

    String getWatchListId() { return watchListId; }
    String getOwnerId() { return ownerId; }

    void addSymbol(String symbol) { symbols.add(symbol); }
    void removeSymbol(String symbol) { symbols.remove(symbol); }
    Set<String> getSymbols() { return Collections.unmodifiableSet(symbols); }

    @Override
    public void onPriceUpdate(String symbol, double oldPrice, double newPrice) {
        if (symbols.contains(symbol)) {
            System.out.printf("[Watchlist:%s] %s price moved %.2f → %.2f%n",
                    name, symbol, oldPrice, newPrice);
        }
    }
}

// ===================== ORDERS (DD1: Factory, DD2: Builder) =====================

abstract class Order {
    private final int orderId;
    private final String accountId;
    private final String symbol;
    private final int quantity;
    private final OrderSide side;
    private final OrderType type;
    private volatile OrderStatus status; // DD10
    private final Instant createdAt;

    Order(int orderId, String accountId, String symbol, int quantity,
          OrderSide side, OrderType type) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.side = side;
        this.type = type;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    int getOrderId() { return orderId; }
    String getAccountId() { return accountId; }
    String getSymbol() { return symbol; }
    int getQuantity() { return quantity; }
    OrderSide getSide() { return side; }
    OrderType getType() { return type; }
    OrderStatus getStatus() { return status; }

    // DD10: Validated state transition
    synchronized void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "Invalid transition: " + status + " → " + next + " for order " + orderId);
        }
        this.status = next;
    }

    // Can this order execute at the given market price?
    abstract boolean isTriggered(double marketPrice);

    // Effective execution price
    abstract double getExecutionPrice(double marketPrice);

    @Override
    public String toString() {
        return String.format("Order[%d %s %s %d %s status=%s]",
                orderId, side, symbol, quantity, type, status);
    }
}

class MarketOrder extends Order {
    MarketOrder(int id, String acct, String sym, int qty, OrderSide side) {
        super(id, acct, sym, qty, side, OrderType.MARKET);
    }
    @Override boolean isTriggered(double marketPrice) { return true; }
    @Override double getExecutionPrice(double marketPrice) { return marketPrice; }
}

class LimitOrder extends Order {
    private final double limitPrice;
    LimitOrder(int id, String acct, String sym, int qty, OrderSide side, double limitPrice) {
        super(id, acct, sym, qty, side, OrderType.LIMIT);
        this.limitPrice = limitPrice;
    }
    @Override boolean isTriggered(double marketPrice) {
        return getSide() == OrderSide.BUY ? marketPrice <= limitPrice : marketPrice >= limitPrice;
    }
    @Override double getExecutionPrice(double marketPrice) { return limitPrice; }
    double getLimitPrice() { return limitPrice; }
}

class StopLossOrder extends Order {
    private final double stopPrice;
    StopLossOrder(int id, String acct, String sym, int qty, OrderSide side, double stopPrice) {
        super(id, acct, sym, qty, side, OrderType.STOP_LOSS);
        this.stopPrice = stopPrice;
    }
    @Override boolean isTriggered(double marketPrice) {
        return getSide() == OrderSide.BUY ? marketPrice >= stopPrice : marketPrice <= stopPrice;
    }
    @Override double getExecutionPrice(double marketPrice) { return marketPrice; }
}

class StopLimitOrder extends Order {
    private final double stopPrice;
    private final double limitPrice;
    private volatile boolean activated = false;

    StopLimitOrder(int id, String acct, String sym, int qty, OrderSide side,
                   double stopPrice, double limitPrice) {
        super(id, acct, sym, qty, side, OrderType.STOP_LIMIT);
        this.stopPrice = stopPrice;
        this.limitPrice = limitPrice;
    }
    @Override boolean isTriggered(double marketPrice) {
        if (!activated) {
            activated = (getSide() == OrderSide.BUY)
                ? marketPrice >= stopPrice : marketPrice <= stopPrice;
        }
        if (!activated) return false;
        return (getSide() == OrderSide.BUY)
            ? marketPrice <= limitPrice : marketPrice >= limitPrice;
    }
    @Override double getExecutionPrice(double marketPrice) { return limitPrice; }
}

// DD2: Builder for fluent order construction
class OrderBuilder {
    private String accountId;
    private String symbol;
    private int quantity;
    private OrderSide side;
    private OrderType type;
    private double limitPrice = -1;
    private double stopPrice = -1;

    private static final AtomicInteger ID_GEN = new AtomicInteger(0); // DD14

    OrderBuilder accountId(String id) { this.accountId = id; return this; }
    OrderBuilder symbol(String s) { this.symbol = s; return this; }
    OrderBuilder quantity(int q) { this.quantity = q; return this; }
    OrderBuilder side(OrderSide s) { this.side = s; return this; }
    OrderBuilder type(OrderType t) { this.type = t; return this; }
    OrderBuilder limitPrice(double p) { this.limitPrice = p; return this; }
    OrderBuilder stopPrice(double p) { this.stopPrice = p; return this; }

    // DD1: Factory logic inside build()
    Order build() {
        Objects.requireNonNull(accountId, "accountId required");
        Objects.requireNonNull(symbol, "symbol required");
        Objects.requireNonNull(side, "side required");
        Objects.requireNonNull(type, "type required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");

        int id = ID_GEN.incrementAndGet();
        switch (type) {
            case MARKET:     return new MarketOrder(id, accountId, symbol, quantity, side);
            case LIMIT:
                if (limitPrice <= 0) throw new IllegalArgumentException("limitPrice required for LIMIT");
                return new LimitOrder(id, accountId, symbol, quantity, side, limitPrice);
            case STOP_LOSS:
                if (stopPrice <= 0) throw new IllegalArgumentException("stopPrice required for STOP_LOSS");
                return new StopLossOrder(id, accountId, symbol, quantity, side, stopPrice);
            case STOP_LIMIT:
                if (stopPrice <= 0 || limitPrice <= 0)
                    throw new IllegalArgumentException("Both stopPrice and limitPrice required for STOP_LIMIT");
                return new StopLimitOrder(id, accountId, symbol, quantity, side, stopPrice, limitPrice);
            default: throw new IllegalArgumentException("Unknown order type: " + type);
        }
    }
}

// ===================== PAYMENT STRATEGY (DD3) =====================

interface PaymentStrategy {
    boolean execute(String accountId, Money amount, TransactionType txnType);
    PaymentMode getMode();
}

class ElectronicTransferStrategy implements PaymentStrategy {
    @Override
    public boolean execute(String accountId, Money amount, TransactionType txnType) {
        // Stub: integrate with bank ACH API
        System.out.printf("[ElectronicTransfer] %s %s for account %s%n", txnType, amount, accountId);
        return true;
    }
    @Override public PaymentMode getMode() { return PaymentMode.ELECTRONIC_TRANSFER; }
}

class WireTransferStrategy implements PaymentStrategy {
    @Override
    public boolean execute(String accountId, Money amount, TransactionType txnType) {
        System.out.printf("[WireTransfer] %s %s for account %s%n", txnType, amount, accountId);
        return true;
    }
    @Override public PaymentMode getMode() { return PaymentMode.WIRE; }
}

class CheckStrategy implements PaymentStrategy {
    @Override
    public boolean execute(String accountId, Money amount, TransactionType txnType) {
        System.out.printf("[Check] %s %s for account %s%n", txnType, amount, accountId);
        return true;
    }
    @Override public PaymentMode getMode() { return PaymentMode.CHECK; }
}

// DD3: Strategy registry — avoids if/else chain
class PaymentStrategyRegistry {
    private static final Map<PaymentMode, PaymentStrategy> STRATEGIES = Map.of(
        PaymentMode.ELECTRONIC_TRANSFER, new ElectronicTransferStrategy(),
        PaymentMode.WIRE, new WireTransferStrategy(),
        PaymentMode.CHECK, new CheckStrategy()
    );

    static PaymentStrategy get(PaymentMode mode) {
        PaymentStrategy s = STRATEGIES.get(mode);
        if (s == null) throw new IllegalArgumentException("No strategy for " + mode);
        return s;
    }
}

// ===================== NOTIFICATION (DD5: Decorator, DD9: Template Method) =====================

// DD9: Template Method — skeleton: format → deliver → log
abstract class Notifier {
    final void send(String userId, String subject, String body) {
        String formatted = format(subject, body);
        deliver(userId, formatted);
        log(userId, subject);
    }

    private String format(String subject, String body) {
        return String.format("[%s] %s: %s", Instant.now(), subject, body);
    }

    protected abstract void deliver(String userId, String formatted);

    private void log(String userId, String subject) {
        System.out.printf("[NotifLog] Sent '%s' to user %s via %s%n", subject, userId, getChannel());
    }

    protected abstract String getChannel();
}

class EmailNotifier extends Notifier {
    @Override protected void deliver(String userId, String formatted) {
        System.out.printf("[Email→%s] %s%n", userId, formatted);
    }
    @Override protected String getChannel() { return "EMAIL"; }
}

class SMSNotifier extends Notifier {
    @Override protected void deliver(String userId, String formatted) {
        System.out.printf("[SMS→%s] %s%n", userId, formatted);
    }
    @Override protected String getChannel() { return "SMS"; }
}

// DD5: Decorator — wraps any Notifier to add channels
abstract class NotifierDecorator extends Notifier {
    protected final Notifier wrapped;
    NotifierDecorator(Notifier wrapped) { this.wrapped = wrapped; }

    @Override
    final void send(String userId, String subject, String body) {
        wrapped.send(userId, subject, body);     // delegate to wrapped
        deliverAdditional(userId, subject, body); // add this layer
    }

    protected abstract void deliverAdditional(String userId, String subject, String body);
}

class SMSDecoratorNotifier extends NotifierDecorator {
    SMSDecoratorNotifier(Notifier wrapped) { super(wrapped); }

    @Override protected void deliverAdditional(String userId, String subject, String body) {
        System.out.printf("[SMS-addon→%s] %s: %s%n", userId, subject, body);
    }
    @Override protected void deliver(String userId, String formatted) { /* unused */ }
    @Override protected String getChannel() { return "SMS-DECORATOR"; }
}

class PushDecoratorNotifier extends NotifierDecorator {
    PushDecoratorNotifier(Notifier wrapped) { super(wrapped); }

    @Override protected void deliverAdditional(String userId, String subject, String body) {
        System.out.printf("[Push-addon→%s] %s: %s%n", userId, subject, body);
    }
    @Override protected void deliver(String userId, String formatted) { /* unused */ }
    @Override protected String getChannel() { return "PUSH-DECORATOR"; }
}

// ===================== ACCOUNT (DD11: per-account RWLock) =====================

class Account {
    private final String accountId;
    private final String memberId;
    private double balance;
    private final Portfolio portfolio;
    private final List<WatchList> watchLists = new CopyOnWriteArrayList<>(); // DD13
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(); // DD11
    private Notifier notifier;

    Account(String accountId, String memberId, double initialBalance) {
        this.accountId = accountId;
        this.memberId = memberId;
        this.balance = initialBalance;
        this.portfolio = new Portfolio();
        this.notifier = new EmailNotifier(); // default
    }

    String getAccountId() { return accountId; }
    String getMemberId() { return memberId; }
    Portfolio getPortfolio() { return portfolio; }

    void setNotifier(Notifier notifier) { this.notifier = notifier; }
    Notifier getNotifier() { return notifier; }

    double getBalance() {
        lock.readLock().lock();
        try { return balance; }
        finally { lock.readLock().unlock(); }
    }

    // DD3: Strategy-based deposit
    boolean deposit(Money amount, PaymentMode mode) {
        PaymentStrategy strategy = PaymentStrategyRegistry.get(mode);
        if (!strategy.execute(accountId, amount, TransactionType.DEPOSIT)) return false;
        lock.writeLock().lock();
        try {
            balance += amount.getAmount();
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    boolean withdraw(Money amount, PaymentMode mode) {
        lock.writeLock().lock();
        try {
            if (balance < amount.getAmount()) return false;
            PaymentStrategy strategy = PaymentStrategyRegistry.get(mode);
            if (!strategy.execute(accountId, amount, TransactionType.WITHDRAWAL)) return false;
            balance -= amount.getAmount();
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    // DD15: Atomic check-then-act for BUY — balance check + deduct + lot creation
    boolean executeBuy(String symbol, int qty, double price) {
        double cost = qty * price;
        lock.writeLock().lock();
        try {
            if (balance < cost) return false;
            balance -= cost;
            String lotId = accountId + "-" + symbol + "-" + Instant.now().toEpochMilli();
            StockLot lot = new StockLot(lotId, symbol, qty, price);
            portfolio.getOrCreatePosition(symbol).addLot(lot);
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    // DD15: Atomic sell — verify shares + remove lots + credit balance
    boolean executeSell(String symbol, int qty, double price) {
        lock.writeLock().lock();
        try {
            StockPosition pos = portfolio.getPosition(symbol);
            if (pos == null || pos.getTotalQuantity() < qty) return false;
            pos.removeLots(qty);
            balance += qty * price;
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    WatchList createWatchList(String name) {
        WatchList wl = new WatchList(accountId + "-wl-" + watchLists.size(), name, accountId);
        watchLists.add(wl);
        return wl;
    }

    List<WatchList> getWatchLists() { return Collections.unmodifiableList(watchLists); }
}

// ===================== STOCK EXCHANGE SINGLETON (DD6, DD7) =====================

// DD6: Enum singleton
class StockExchange {
    // DD6: Lazy singleton with double-checked locking
    private static volatile StockExchange instance;

    private StockExchange() {}  // private constructor — no one can call new

    static StockExchange getInstance() {
        if (instance == null) {                      // first check — no lock
            synchronized (StockExchange.class) {
                if (instance == null) {              // second check — inside lock
                    instance = new StockExchange();
                }
            }
        }
        return instance;
    }

    // everything else same
    private final ConcurrentHashMap<String, Stock> stocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<PriceObserver>> observers =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Order> orderQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Order> activeOrders = new ConcurrentHashMap<>();

    


    void registerStock(Stock stock) {
        stocks.put(stock.getSymbol(), stock);
        observers.putIfAbsent(stock.getSymbol(), new CopyOnWriteArrayList<>());
    }

    Stock getStock(String symbol) { return stocks.get(symbol); }

    void registerAccount(Account account) {
        accounts.put(account.getAccountId(), account);
    }

    Account getAccount(String accountId) { return accounts.get(accountId); }

    // DD4: Subscribe to price changes
    void subscribe(String symbol, PriceObserver observer) {
        observers.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>()).add(observer);
    }

    void unsubscribe(String symbol, PriceObserver observer) {
        CopyOnWriteArrayList<PriceObserver> list = observers.get(symbol);
        if (list != null) list.remove(observer);
    }

    // Price tick — notifies all observers (DD4)
    void updatePrice(String symbol, double newPrice) {
        Stock stock = stocks.get(symbol);
        if (stock == null) return;
        double oldPrice = stock.getCurrentPrice();
        stock.setCurrentPrice(newPrice);

        // Notify observers
        CopyOnWriteArrayList<PriceObserver> list = observers.get(symbol);
        if (list != null) {
            for (PriceObserver obs : list) {
                obs.onPriceUpdate(symbol, oldPrice, newPrice);
            }
        }

        // Check pending stop/limit orders triggered by this price
        checkTriggeredOrders(symbol, newPrice);
    }

    // DD7: Submit order to command queue
    void submitOrder(Order order) {
        activeOrders.put(order.getOrderId(), order);
        orderQueue.add(order);
        System.out.printf("[Exchange] Order %d queued: %s%n", order.getOrderId(), order);
    }

    // DD10: Cancel with state machine validation
    boolean cancelOrder(int orderId) {
        Order order = activeOrders.get(orderId);
        if (order == null) return false;
        try {
            order.transitionTo(OrderStatus.CANCELLED);
            activeOrders.remove(orderId);
            System.out.printf("[Exchange] Order %d cancelled%n", orderId);
            notifyOrderStatus(order);
            return true;
        } catch (IllegalStateException e) {
            System.out.printf("[Exchange] Cannot cancel order %d: %s%n", orderId, e.getMessage());
            return false;
        }
    }

    // DD7: Process order queue (called by executor thread / main loop)
    void processOrders() {
        Order order;
        while ((order = orderQueue.poll()) != null) {
            if (order.getStatus() != OrderStatus.PENDING) continue; // skip cancelled

            Stock stock = stocks.get(order.getSymbol());
            if (stock == null) {
                order.transitionTo(OrderStatus.REJECTED);
                notifyOrderStatus(order);
                continue;
            }

            double marketPrice = stock.getCurrentPrice();
            if (!order.isTriggered(marketPrice)) {
                // Re-queue non-triggered orders (stop/limit waiting for price)
                orderQueue.add(order);
                continue;
            }

            executeOrder(order, marketPrice);
        }
    }

    // DD15: Atomic execution
    private void executeOrder(Order order, double marketPrice) {
        Account account = accounts.get(order.getAccountId());
        if (account == null) {
            order.transitionTo(OrderStatus.REJECTED);
            notifyOrderStatus(order);
            return;
        }

        double execPrice = order.getExecutionPrice(marketPrice);
        boolean success;

        if (order.getSide() == OrderSide.BUY) {
            success = account.executeBuy(order.getSymbol(), order.getQuantity(), execPrice);
        } else {
            success = account.executeSell(order.getSymbol(), order.getQuantity(), execPrice);
        }

        if (success) {
            order.transitionTo(OrderStatus.FILLED);
            activeOrders.remove(order.getOrderId());
            System.out.printf("[Exchange] Order %d FILLED at %.2f%n", order.getOrderId(), execPrice);
        } else {
            order.transitionTo(OrderStatus.REJECTED);
            activeOrders.remove(order.getOrderId());
            System.out.printf("[Exchange] Order %d REJECTED (insufficient balance/shares)%n",
                    order.getOrderId());
        }

        notifyOrderStatus(order);
    }

    // Check if any pending stop/limit orders are now triggered
    private void checkTriggeredOrders(String symbol, double newPrice) {
        for (Order order : activeOrders.values()) {
            if (order.getSymbol().equals(symbol)
                    && order.getStatus() == OrderStatus.PENDING
                    && order.isTriggered(newPrice)) {
                executeOrder(order, newPrice);
            }
        }
    }

    // DD5 + DD9: Notify via account's decorated notifier
    private void notifyOrderStatus(Order order) {
        Account account = accounts.get(order.getAccountId());
        if (account != null && account.getNotifier() != null) {
            account.getNotifier().send(
                account.getMemberId(),
                "Order " + order.getOrderId() + " " + order.getStatus(),
                order.toString()
            );
        }
    }

    // Search stock inventory
    List<Stock> searchStocks(String query) {
        List<Stock> results = new ArrayList<>();
        String q = query.toUpperCase();
        for (Stock s : stocks.values()) {
            if (s.getSymbol().contains(q) || s.getName().toUpperCase().contains(q)) {
                results.add(s);
            }
        }
        return results;
    }
}

// ===================== MEMBER / ADMIN =====================

class Member {
    private final String memberId;
    private String name;
    private String email;
    private Account account;

    Member(String memberId, String name, String email) {
        this.memberId = memberId;
        this.name = name;
        this.email = email;
    }

    String getMemberId() { return memberId; }
    String getName() { return name; }
    Account getAccount() { return account; }

    void setAccount(Account account) { this.account = account; }

    // Place order via builder — clean API for member
    Order placeOrder(OrderType type, String symbol, int qty, OrderSide side,
                     double limitPrice, double stopPrice) {
        Order order = new OrderBuilder()
                .accountId(account.getAccountId())
                .symbol(symbol)
                .quantity(qty)
                .side(side)
                .type(type)
                .limitPrice(limitPrice)
                .stopPrice(stopPrice)
                .build();
        StockExchange.INSTANCE.submitOrder(order);
        return order;
    }
}

// ===================== DEMO =====================

public class StockBrokerageSystem {
    public static void main(String[] args) {
        StockExchange exchange = StockExchange.INSTANCE;

        // --- Setup stocks ---
        exchange.registerStock(new Stock("AAPL", "Apple Inc.", 175.00));
        exchange.registerStock(new Stock("GOOG", "Alphabet Inc.", 140.00));
        exchange.registerStock(new Stock("AMZN", "Amazon.com Inc.", 185.00));

        // --- Create member + account ---
        Member alice = new Member("m1", "Alice", "alice@example.com");
        Account aliceAccount = new Account("acc1", "m1", 50000.00);

        // DD5: Decorate notifier — email + SMS + push
        Notifier notifier = new PushDecoratorNotifier(
                new SMSDecoratorNotifier(new EmailNotifier()));
        aliceAccount.setNotifier(notifier);

        alice.setAccount(aliceAccount);
        exchange.registerAccount(aliceAccount);

        // --- Watchlist with observer ---
        WatchList techWatch = aliceAccount.createWatchList("Tech Stocks");
        techWatch.addSymbol("AAPL");
        techWatch.addSymbol("GOOG");
        exchange.subscribe("AAPL", techWatch);
        exchange.subscribe("GOOG", techWatch);

        // --- Deposit via wire ---
        aliceAccount.deposit(new Money(10000), PaymentMode.WIRE);
        System.out.printf("Balance after deposit: $%.2f%n%n", aliceAccount.getBalance());

        // --- Market buy AAPL ---
        System.out.println("=== MARKET BUY AAPL ===");
        Order marketBuy = alice.placeOrder(
                OrderType.MARKET, "AAPL", 100, OrderSide.BUY, -1, -1);
        exchange.processOrders();

        System.out.printf("Balance: $%.2f%n", aliceAccount.getBalance());
        StockPosition aaplPos = aliceAccount.getPortfolio().getPosition("AAPL");
        System.out.printf("AAPL lots: %d shares across %d lots%n%n",
                aaplPos.getTotalQuantity(), aaplPos.getLots().size());

        // --- Limit buy GOOG ---
        System.out.println("=== LIMIT BUY GOOG @135 ===");
        Order limitBuy = alice.placeOrder(
                OrderType.LIMIT, "GOOG", 50, OrderSide.BUY, 135.00, -1);
        exchange.processOrders(); // won't fill — price is 140

        System.out.printf("GOOG order status: %s (price 140 > limit 135)%n%n", limitBuy.getStatus());

        // Price drops — triggers limit order
        System.out.println("=== PRICE DROP: GOOG → 134 ===");
        exchange.updatePrice("GOOG", 134.00); // triggers watchlist + fills limit order

        System.out.printf("GOOG order status after drop: %s%n", limitBuy.getStatus());
        StockPosition googPos = aliceAccount.getPortfolio().getPosition("GOOG");
        if (googPos != null) {
            System.out.printf("GOOG lots: %d shares%n%n", googPos.getTotalQuantity());
        }

        // --- Stop-loss sell AAPL ---
        System.out.println("=== STOP-LOSS SELL AAPL @170 ===");
        Order stopLoss = alice.placeOrder(
                OrderType.STOP_LOSS, "AAPL", 50, OrderSide.SELL, -1, 170.00);
        // stopPrice passed as stopPrice param (builder routes it)
        // Correction: builder expects stopPrice in stopPrice field
        // Re-place with correct builder usage:
        Order stopLoss2 = new OrderBuilder()
                .accountId("acc1").symbol("AAPL").quantity(50)
                .side(OrderSide.SELL).type(OrderType.STOP_LOSS)
                .stopPrice(170.00).build();
        exchange.submitOrder(stopLoss2);

        System.out.println("=== PRICE DROP: AAPL → 169 ===");
        exchange.updatePrice("AAPL", 169.00); // triggers stop-loss + watchlist

        System.out.printf("Balance after stop-loss sell: $%.2f%n", aliceAccount.getBalance());
        System.out.printf("AAPL remaining: %d shares%n%n",
                aliceAccount.getPortfolio().getPosition("AAPL").getTotalQuantity());

        // --- Cancel order ---
        System.out.println("=== CANCEL ORDER ===");
        Order cancelTarget = alice.placeOrder(
                OrderType.LIMIT, "AMZN", 20, OrderSide.BUY, 180.00, -1);
        System.out.printf("Before cancel: %s%n", cancelTarget.getStatus());
        exchange.cancelOrder(cancelTarget.getOrderId());
        System.out.printf("After cancel: %s%n%n", cancelTarget.getStatus());

        // --- Withdraw ---
        System.out.println("=== WITHDRAW ===");
        boolean withdrawn = aliceAccount.withdraw(new Money(5000), PaymentMode.ELECTRONIC_TRANSFER);
        System.out.printf("Withdrawal success: %b, Balance: $%.2f%n%n", withdrawn, aliceAccount.getBalance());

        // --- Search ---
        System.out.println("=== SEARCH ===");
        List<Stock> results = exchange.searchStocks("amaz");
        results.forEach(s -> System.out.printf("Found: %s (%s) @ %.2f%n",
                s.getName(), s.getSymbol(), s.getCurrentPrice()));

        // --- Portfolio summary ---
        System.out.println("\n=== PORTFOLIO SUMMARY ===");
        Map<String, Double> prices = new HashMap<>();
        for (Map.Entry<String, StockPosition> e : aliceAccount.getPortfolio().getAllPositions().entrySet()) {
            Stock s = exchange.getStock(e.getKey());
            if (s != null) prices.put(e.getKey(), s.getCurrentPrice());
            System.out.printf("  %s: %d shares, cost basis $%.2f, current value $%.2f%n",
                    e.getKey(), e.getValue().getTotalQuantity(), e.getValue().getTotalCostBasis(),
                    e.getValue().getCurrentValue(prices.getOrDefault(e.getKey(), 0.0)));
        }
        System.out.printf("  Total portfolio value: $%.2f%n", aliceAccount.getPortfolio().getTotalValue(prices));
        System.out.printf("  Cash balance: $%.2f%n", aliceAccount.getBalance());
    }
}
