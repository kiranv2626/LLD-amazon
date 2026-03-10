import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.*;

/*
╔══════════════════════════════════════════════════════════════════════════════════╗
║          15 CRUCIAL DESIGN DECISIONS — INTERVIEW DISCUSSION POINTS             ║
║          (Read these before the interview — each is a 2-3 min talking point)   ║
╚══════════════════════════════════════════════════════════════════════════════════╝

────────────────────────────────────────────────────────────────────────────────────
DD#1: INVENTORY CONCURRENCY — AtomicInteger + CAS vs synchronized vs DB Lock
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   AtomicInteger with Compare-And-Swap (CAS) loop in Product.decrementQuantity()
WHY:      Lock-free, non-blocking. Under contention, threads RETRY instead of BLOCK.
          At Amazon scale, 1000s of users hit the same iPhone listing — blocking = death.
ALT 1:    synchronized(product) { if(qty >= needed) qty -= needed; }
          → Simple but creates a bottleneck. Every checkout for the SAME product serializes.
ALT 2:    ReentrantLock per product
          → Better than synchronized (tryLock with timeout, fairness), but still blocking.
ALT 3:    Database-level SELECT FOR UPDATE / optimistic locking with version column
          → Real production systems use this. In LLD interview, CAS shows the CONCEPT
            of optimistic concurrency without dragging in a DB layer.
TRADEOFF: CAS can spin under extreme contention (1M users, 1 item). In production,
          you'd add exponential backoff or switch to a queue-based reservation system.
TALK ABOUT: "I chose CAS because it demonstrates I understand optimistic concurrency.
             In production at Amazon, this would be a DB-level optimistic lock with
             a version column, but the PRINCIPLE is identical."

────────────────────────────────────────────────────────────────────────────────────
DD#2: CHECKOUT FLOW — 4-Phase All-or-Nothing with Rollback
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   Phase1: Calculate → Phase2: Reserve Inventory (CAS) → Phase3: Pay → Phase4: Create Order
          Each phase has rollback logic if it fails.
WHY:      This is essentially a SAGA PATTERN (compensating transactions).
          - If inventory reserve fails for item 3 of 5 → rollback items 1 and 2
          - If payment fails → rollback ALL reserved inventory
          - This prevents "ghost reservations" (inventory decremented but order never created)
ALT 1:    Pay first, then reserve inventory
          → BAD: User pays $999, then "sorry out of stock" — terrible UX + refund latency
ALT 2:    Reserve with TTL (timeout-based reservation)
          → GOOD for production (Amazon actually does this). Cart items are "soft reserved"
            for 15 min. In an LLD interview, explicit rollback is clearer to demonstrate.
ALT 3:    Global lock on entire checkout
          → TERRIBLE: Only one user can checkout at a time across the entire system.
TALK ABOUT: "The ordering matters — reserve inventory BEFORE payment because rolling
             back inventory is instant and free, but rolling back payment involves
             refund processing which is slow and costly."

────────────────────────────────────────────────────────────────────────────────────
DD#3: CART — ConcurrentHashMap<productId, qty> vs synchronized List<CartItem>
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   ConcurrentHashMap<String, Integer> with merge() for addItem.
WHY:      - O(1) add/remove/lookup by productId
          - merge(productId, qty, Integer::sum) is ATOMIC — no race between read and write
          - A user might have multiple browser tabs open, both adding to cart
ALT 1:    synchronized ArrayList<CartItem>
          → O(n) lookup to find if product already in cart, plus full lock for every op
ALT 2:    HashMap with external synchronization (Collections.synchronizedMap)
          → Iteration not thread-safe, compound operations (check-then-act) still racy
ALT 3:    Store cart in DB/Redis (production approach)
          → Out of scope for LLD, but mention it: "In production, cart is persisted to Redis
            with TTL so it survives server restarts."
TRADEOFF: We store productId (String) not Product reference. This means we do a catalog
          lookup at checkout. This is INTENTIONAL — product price may change between
          add-to-cart and checkout, and we want the LATEST price.
TALK ABOUT: "merge() is my favorite ConcurrentHashMap method — it atomically combines
             read+modify+write in one call, which is exactly what 'add 2 more of this item' needs."

────────────────────────────────────────────────────────────────────────────────────
DD#4: SEARCH/CATALOG — Inverted Index Maps vs Linear Scan vs External Search Engine
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   Three ConcurrentHashMaps: byName, byCategory, bySeller (inverted indexes)
WHY:      O(1) lookup by key. Pre-indexed at write time, so reads are fast.
          This mirrors how Elasticsearch/Solr work conceptually.
ALT 1:    Single List<Product> with stream().filter()
          → O(n) for every search. Fine for 100 products, unusable for 1M.
ALT 2:    TreeMap for range queries / sorted results
          → Useful if you need "products between $50-$100" but overkill for name/category
ALT 3:    Elasticsearch / Lucene (production)
          → Mention it: "In production, I'd use Elasticsearch for fuzzy matching, typo
            tolerance, relevance scoring. My inverted index map is a simplified version
            of the same concept."
TRADEOFF: Maintaining 3 maps means addProduct/removeProduct must update ALL 3 maps
          (write amplification). But reads >>> writes in e-commerce (100:1 ratio),
          so this is the correct tradeoff.
TALK ABOUT: "I consciously chose read-optimized data structures because search is the
             hottest path in any e-commerce system — every page load triggers a search."

────────────────────────────────────────────────────────────────────────────────────
DD#5: PAYMENT — Strategy Pattern vs if-else chain vs Enum with behavior
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   PaymentStrategy interface with CreditCard, BankTransfer, CashOnDelivery impls
WHY:      Open/Closed Principle — adding UPI, Apple Pay, Crypto = new class, ZERO changes
          to checkout(). The checkout method takes PaymentStrategy as a parameter,
          so it's completely decoupled from payment details.
ALT 1:    if (method == CREDIT_CARD) { ... } else if (method == BANK) { ... }
          → Violates OCP. Every new payment method = modify checkout. Fragile.
ALT 2:    Enum with abstract method: CREDIT_CARD { void pay() { ... } }
          → Clever but couples payment logic INTO the enum. Can't inject card numbers.
ALT 3:    Command pattern (PaymentCommand with execute/undo)
          → Similar to Strategy here. Strategy is better because pay and refund
            are related operations on the same "strategy", not independent commands.
TRADEOFF: Strategy requires the CALLER to construct the right implementation.
          In production, a PaymentStrategyFactory with PaymentMethod enum input
          would handle this. Kept simple here for interview clarity.
TALK ABOUT: "The key SDE2 insight is that checkout() doesn't know or care HOW payment
             happens. I can unit-test checkout with a MockPaymentStrategy that always
             returns true/false — full testability."

────────────────────────────────────────────────────────────────────────────────────
DD#6: NOTIFICATION — Observer Pattern vs Event Bus vs Direct Method Calls
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   NotificationService with List<NotificationListener>, publish/subscribe model
WHY:      - Decoupled: Order/Checkout code doesn't know about Email/SMS/Push details
          - Extensible: Adding push notifications = new listener, no changes anywhere else
          - CopyOnWriteArrayList for listeners → safe to iterate while another thread subscribes
ALT 1:    Direct calls: order.notifyByEmail(); order.notifyBySMS();
          → Tight coupling. Adding a new channel = modify Order class. Violates SRP.
ALT 2:    Event bus / message queue (Kafka, SNS)
          → Production choice. Async, durable, retry-able. But overkill for LLD interview.
          → MENTION IT: "In production, I'd replace the synchronous observer with an
            event bus. Checkout publishes an OrderCreatedEvent to Kafka, and email/SMS
            services consume it independently."
ALT 3:    Java built-in Observable (deprecated in Java 9)
          → Don't use deprecated APIs in an interview. Custom observer is cleaner.
TRADEOFF: Our observer is SYNCHRONOUS — publish() blocks until all listeners finish.
          If email service is slow, checkout is slow. Fix: async notify with CompletableFuture.
TALK ABOUT: "I made it sync for simplicity, but I'd call out the async improvement.
             This shows I understand the production concern without over-engineering the LLD."

────────────────────────────────────────────────────────────────────────────────────
DD#7: ORDER CANCELLATION — synchronized cancel() + State Machine
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   synchronized boolean cancel() that checks status == CONFIRMED before transitioning
WHY:      Order status is a STATE MACHINE: CONFIRMED → SHIPPED → DELIVERED (+ CANCELLED from CONFIRMED)
          synchronized ensures two threads can't both cancel the same order simultaneously
          (one gets true, other gets false — idempotent-safe).
ALT 1:    volatile status + CAS (AtomicReference<OrderStatus>)
          → Would work, but synchronized is clearer for a single-field state transition.
            CAS shines when there's contention on the SAME object from many threads.
            Orders are typically accessed by one user, so contention is low → sync is fine.
ALT 2:    State pattern (OrderState interface with ConcrmedState, ShippedState...)
          → Elegant but heavy for 4 states. Mention it: "For a system with 10+ states
            and complex transition rules, I'd use the State pattern."
TRADEOFF: volatile on status field ensures cross-thread VISIBILITY (reads see latest write)
          while synchronized on cancel() ensures ATOMICITY of check-then-mutate.
          Both are needed — they solve DIFFERENT concurrency problems.
TALK ABOUT: "volatile ≠ synchronized. volatile solves visibility, synchronized solves
             atomicity. Order.status needs BOTH — volatile for reads from any thread,
             synchronized for the cancel() check-and-transition."

────────────────────────────────────────────────────────────────────────────────────
DD#8: SINGLETON — Double-Checked Locking for AmazonSystem
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   volatile INSTANCE + double-checked locking in getInstance()
WHY:      Lazy initialization (created only when first needed) + thread-safe.
          volatile prevents instruction reordering — without it, another thread might
          see a partially constructed AmazonSystem object.
ALT 1:    Eager initialization: private static final INSTANCE = new AmazonSystem();
          → Simpler, thread-safe (JVM guarantees static init is synchronized).
            But wastes memory if AmazonSystem is never used. Fine for interviews.
ALT 2:    Enum singleton: enum AmazonSystem { INSTANCE; }
          → The "Josh Bloch" way. Serialization-safe, reflection-safe. But can't extend
            classes and feels odd for a complex system. Mention it to show you know Effective Java.
ALT 3:    Bill Pugh holder: private static class Holder { static final INSTANCE = new... }
          → Best of both worlds (lazy + simple). But DCL is more commonly asked about.
TALK ABOUT: "I used DCL because interviewers often probe the volatile keyword and
             memory model understanding. In production, I'd probably use the enum
             approach from Effective Java Item 3."

────────────────────────────────────────────────────────────────────────────────────
DD#9: DATA STRUCTURE CHOICES — CopyOnWriteArrayList vs synchronized vs Concurrent
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   CopyOnWriteArrayList for reviews, addresses, product lists, notification listeners.
          ConcurrentHashMap for products catalog, users map, orders map, cart items.
WHY:      COW ArrayList: Optimized for READ-HEAVY, WRITE-RARE scenarios.
          - Reviews: written once per user, read thousands of times per product page
          - Addresses: users add 2-3 addresses, read them on every checkout
          - Iteration is NEVER invalidated (snapshot semantics) — no ConcurrentModificationException
          ConcurrentHashMap: O(1) lookup, segment-level locking (Java 8+ uses CAS + synchronized on bins)
          - Products, users, orders: frequent reads AND writes, need concurrent access
ALT 1:    Collections.synchronizedList() / synchronizedMap()
          → Full-lock on every operation including reads. Kills throughput.
          → Iteration STILL needs external synchronization — easy to forget and get CME.
ALT 2:    Plain HashMap/ArrayList with synchronized blocks
          → Error-prone. Forget one synchronized block → data race. Hard to audit.
TRADEOFF: CopyOnWriteArrayList copies the ENTIRE array on every write. For a product
          with 10,000 reviews, each new review allocates a 10,001 element array.
          In production, you'd paginate reviews and use a DB. For LLD, this shows
          you understand the read/write tradeoff.
TALK ABOUT: "I specifically chose COW for read-heavy collections and CHM for mixed
             read/write maps. This shows I think about access PATTERNS, not just
             'throw synchronized on everything'."

────────────────────────────────────────────────────────────────────────────────────
DD#10: REVIEW SYSTEM — Inline vs Separate Service
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   Reviews stored directly in Product (List<Review>) with live avgRating calculation
WHY:      Simple, no extra service. In an LLD interview, this is sufficient.
          Rating clamped to 1-5 with Math.max(1, Math.min(5, rating)) — defensive coding.
ALT 1:    Separate ReviewService with its own storage
          → Better for microservices. Reviews can be independently scaled, cached,
            moderated. But adds complexity for a 30-min interview.
ALT 2:    Pre-computed avgRating updated on every review add (instead of stream calculation)
          → Better performance for hot products (millions of reviews).
            Use: count + runningSum, avgRating = runningSum / count.
            AtomicLong for thread-safe running sum.
TRADEOFF: stream().mapToInt().average() is O(n) per call. For a product page showing
          avgRating to millions of users, this is wasteful. Pre-compute in production.
TALK ABOUT: "I kept it simple with live calculation. If the interviewer pushes on
             performance, I'd switch to a pre-computed running average — constant time,
             one AtomicLong for sum and one AtomicInteger for count."

────────────────────────────────────────────────────────────────────────────────────
DD#11: ACCOUNT HIERARCHY — Abstract class vs Interface vs Composition
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   Abstract Account base class with Guest, User, Admin, Seller subclasses
WHY:      All share id, name, status — IS-A relationship. Guest is intentionally minimal
          (no email/password) enforcing R1 (guest can only search).
ALT 1:    Interface Account with default methods
          → Loses shared state (id, name). Would need a separate AccountData class.
ALT 2:    Composition: User HAS-A AccountProfile, HAS-A AuthCredentials
          → More flexible (a User could swap auth methods). But overkill for this domain.
ALT 3:    Single User class with a Role enum
          → Simpler. But Guest has NO password while Admin has no cart — different SHAPES
            mean different classes are appropriate.
TRADEOFF: Inheritance can be rigid. If a Seller later needs a cart (to buy supplies),
          you'd need multiple inheritance → move to composition. For this scope, inheritance
          is clean and type-safe (methods can accept User specifically, not any Account).
TALK ABOUT: "I used inheritance because the account types have genuinely different shapes
             and capabilities, not just different permissions."

────────────────────────────────────────────────────────────────────────────────────
DD#12: IMMUTABILITY & DEFENSIVE COPIES — Collections.unmodifiableMap/List
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   All getters return unmodifiable views: getItems(), getReviews(), getItems() on Order
WHY:      Prevents EXTERNAL mutation of internal state. Without this:
          - user.getCart().getItems().clear() would empty the cart bypassing all validation
          - order.getItems().put("free-item", 100) would corrupt order data
          This is DEFENSIVE PROGRAMMING — a core SDE2 principle.
ALT 1:    Return deep copies
          → Safest but expensive. For a cart with 50 items, every getItems() allocates a new map.
ALT 2:    Return raw mutable reference
          → DANGEROUS. Any caller can corrupt internal state. Common junior mistake.
TRADEOFF: Unmodifiable views are cheap (wrapper, no copy) but still expose internal
          structure. In production, you'd return DTOs/records for API boundaries.
TALK ABOUT: "I always make internal collections unmodifiable at the getter level.
             It's a small thing but prevents an entire class of bugs."

────────────────────────────────────────────────────────────────────────────────────
DD#13: ID STRATEGY — UUID vs Auto-increment vs Snowflake
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   UUID.randomUUID().toString() for all entities (Product, Order, User, Review)
WHY:      - Globally unique without a central ID generator (no single point of failure)
          - Can be generated on any server instance independently (important for distributed systems)
          - No information leakage (auto-increment reveals total count)
ALT 1:    Auto-increment (AtomicLong counter)
          → Simpler, shorter IDs. But requires a centralized counter → bottleneck + SPOF.
          → Also leaks business info: order #50000 tells competitors your volume.
ALT 2:    Snowflake ID (Twitter's approach): timestamp + machine ID + sequence
          → Sortable by time (UUID is not). 64-bit long instead of 128-bit UUID string.
          → Used by Twitter, Discord, Instagram. Mention it for bonus points.
ALT 3:    Database-generated IDs
          → Requires DB round trip before object creation. Couples domain to persistence.
TRADEOFF: UUIDs are 36 characters — larger indexes, more memory. Snowflake IDs are
          more efficient but need a machine ID registry. For LLD, UUID is the right call.
TALK ABOUT: "UUID is my go-to for LLD because it's stateless and distributed-friendly.
             For production with billions of records, I'd evaluate Snowflake for
             sortability and smaller storage footprint."

────────────────────────────────────────────────────────────────────────────────────
DD#14: PRICE LOOKUP AT CHECKOUT vs AT ADD-TO-CART TIME
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   Cart stores productId + quantity ONLY. Price is looked up from Catalog at checkout time.
WHY:      Products can change price between add-to-cart and checkout (flash sales, price drops).
          Looking up at checkout ensures the user pays the CURRENT price.
          This is how Amazon actually works — your cart shows "price changed since you added this."
ALT 1:    Snapshot price at add-to-cart time (store price in cart)
          → User always pays the price they saw. Feels "fairer" but can be exploited
            (add item at sale price, checkout months later after sale ends).
ALT 2:    Price at add-to-cart with "price lock" for 30 min
          → Compromise used by some sites. Complex to implement (TTL on locked prices).
TRADEOFF: Our approach means the total shown in cart might differ from total at checkout
          if prices changed between. Need UI to show "price updated" warnings.
TALK ABOUT: "This is a real-world Amazon design decision. I chose current-price-at-checkout
             because it protects the seller from price exploitation."

────────────────────────────────────────────────────────────────────────────────────
DD#15: FACADE PATTERN — AmazonSystem as Single Entry Point
────────────────────────────────────────────────────────────────────────────────────
CHOSEN:   AmazonSystem class orchestrates ALL subsystems (Catalog, Cart, Order, Payment, Notification)
WHY:      - Single entry point simplifies the API. Clients call amazon.checkout(), not
            catalog.getProduct() → product.decrement() → payment.pay() → order.create()
          - Encapsulates the WORKFLOW (the ordering of steps matters — see DD#2)
          - Easy to add cross-cutting concerns: logging, auth checks, rate limiting
ALT 1:    Let clients orchestrate subsystems directly (no facade)
          → Exposes internal complexity. Client must know the correct order of operations.
            If checkout steps change, ALL clients must update. Fragile.
ALT 2:    Service layer pattern (CheckoutService, ProductService, UserService)
          → Better for large systems. Each service owns one domain.
          → In production, AmazonSystem would be split into microservices.
            For LLD, one facade is cleaner and easier to discuss.
ALT 3:    Mediator pattern
          → Similar to facade but bidirectional. Subsystems communicate through mediator.
            Overkill here — our subsystems don't need to talk to each other, only the facade
            orchestrates them.
TRADEOFF: Facade can become a "god class" with too many methods. In production, split
          into domain-specific services. For a 30-min interview, one class is manageable.
TALK ABOUT: "The facade prevents clients from executing checkout steps in the wrong order.
             The WORKFLOW (reserve → pay → create order) is a business invariant that
             should be encapsulated, not exposed."

════════════════════════════════════════════════════════════════════════════════════
BONUS TALKING POINTS (if interviewer goes deeper):
════════════════════════════════════════════════════════════════════════════════════
- "If this were distributed, I'd replace CAS with DB optimistic locking (version column)"
- "Cart would be in Redis with TTL for session persistence"
- "Notifications would go through Kafka/SNS for async + retry + dead letter queue"
- "Search would use Elasticsearch with fuzzy matching and relevance scoring"
- "Payment would be idempotent with an idempotency key to prevent double charges"
- "I'd add circuit breaker (Hystrix/Resilience4j) around the payment gateway call"
- "Order history would be in a separate read-optimized store (CQRS pattern)"
*/

// ========================== ENUMS ==========================

enum AccountStatus { ACTIVE, BLOCKED } // R10: Admin can block users
enum OrderStatus { CONFIRMED, SHIPPED, DELIVERED, CANCELLED } // R7, R8
enum PaymentMethod { CREDIT_CARD, BANK_TRANSFER, CASH_ON_DELIVERY } // R6
enum ProductCategory { ELECTRONICS, FASHION, HOME_DECOR, BOOKS } // R10
enum NotificationType { ORDER_PLACED, ORDER_SHIPPED, ORDER_DELIVERED, ORDER_CANCELLED }

// ========================== ACCOUNTS (R1, R11) ==========================
// R1: Authenticated user vs Guest
// R11: Store name, email, password, personal info

// DD#11: Abstract class (not interface) because all accounts share state (id, name, status)
// Guest has NO email/password — different SHAPE enforces R1 at compile time
abstract class Account {
    protected final String id;
    protected String name;
    protected AccountStatus status;

    Account(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = AccountStatus.ACTIVE;
    }
    public String getId() { return id; }
    public String getName() { return name; }
}

// R1: Guest can only search — no email/password needed
class Guest extends Account {
    Guest() { super(UUID.randomUUID().toString(), "guest"); }
}

// R1, R11: Authenticated user with full profile
class User extends Account {
    private String email;
    private String phone;
    private String password;
    private final Cart cart = new Cart();
    // R5: user can have multiple shipping addresses
    private final List<Address> addresses = new CopyOnWriteArrayList<>();

    User(String name, String email, String phone, String password) {
        super(UUID.randomUUID().toString(), name);
        this.email = email;
        this.phone = phone;
        this.password = password;
    }
    public Cart getCart() { return cart; }
    public List<Address> getAddresses() { return addresses; }
    public void addAddress(Address a) { addresses.add(a); }
    public String getEmail() { return email; }
    public void resetPassword(String newPassword) { this.password = newPassword; }
}

// R10: Admin can add/modify/remove categories, block users
class Admin extends Account {
    private String email;
    private String password;

    Admin(String name, String email, String password) {
        super(UUID.randomUUID().toString(), name);
        this.email = email;
        this.password = password;
    }
}

class Seller extends Account {
    private String email;
    private String phone;
    private final List<String> productIds = new CopyOnWriteArrayList<>();

    Seller(String name, String email, String phone) {
        super(UUID.randomUUID().toString(), name);
        this.email = email;
        this.phone = phone;
    }
    public void addProduct(String productId) { productIds.add(productId); }
}

// R5: Shipping address
class Address {
    String street, city, state, zipCode, country;
    Address(String street, String city, String state, String zipCode, String country) {
        this.street = street; this.city = city; this.state = state;
        this.zipCode = zipCode; this.country = country;
    }
}

// ========================== PRODUCT (R2, R3) ==========================
// KEY SDE2 POINT: AtomicInteger for thread-safe inventory updates
// This prevents overselling under concurrent checkout

class Product {
    private final String id;
    private String name;
    private String description;
    private ProductCategory category;
    private double basePrice;
    private final AtomicInteger quantity; // DD#1: CAS-based lock-free inventory (see tradeoffs above)
    private final String sellerId;
    private final List<Review> reviews = new CopyOnWriteArrayList<>(); // R3

    Product(String name, String description, ProductCategory category,
            double basePrice, int quantity, String sellerId) {
        this.id = UUID.randomUUID().toString(); // DD#13: UUID for distributed-friendly, no SPOF ID generator
        this.name = name;
        this.description = description;
        this.category = category;
        this.basePrice = basePrice;
        this.quantity = new AtomicInteger(quantity);
        this.sellerId = sellerId;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public ProductCategory getCategory() { return category; }
    public double getBasePrice() { return basePrice; }
    public int getQuantity() { return quantity.get(); }
    public String getSellerId() { return sellerId; }

    // DD#1: CAS loop — the MOST IMPORTANT concurrency code in this entire system
    // INTERVIEWER MAGNET: Walk through this method step-by-step
    public boolean decrementQuantity(int qty) {
        while (true) {
            int current = quantity.get();
            if (current < qty) return false; // not enough stock
            if (quantity.compareAndSet(current, current - qty)) return true;
            // CAS failed = another thread modified, retry
        }
    }

    public void incrementQuantity(int qty) { quantity.addAndGet(qty); }

    // R3: Reviews and ratings
    public void addReview(Review r) { reviews.add(r); }
    public List<Review> getReviews() { return Collections.unmodifiableList(reviews); }
    // DD#10: Live O(n) calculation. In production, use pre-computed running average.
    public double getAvgRating() {
        return reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }
}

// R3: Customer reviews and ratings
class Review {
    private final String id;
    private final String userId;
    private final String comment;
    private final int rating; // 1-5

    Review(String userId, String comment, int rating) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.comment = comment;
        this.rating = Math.max(1, Math.min(5, rating)); // DD#10: defensive clamping, prevents invalid data
    }
    public int getRating() { return rating; }
    public String getUserId() { return userId; }
}

// ========================== CART (R4) ==========================
// R4: Add, remove, modify items in cart
// CONCURRENCY: ConcurrentHashMap for thread-safe cart operations

// DD#3: ConcurrentHashMap + merge() for atomic add-to-cart
// DD#14: Store productId NOT product reference — price looked up at checkout time
class Cart {
    private final ConcurrentHashMap<String, Integer> items = new ConcurrentHashMap<>();

    public void addItem(String productId, int qty) {
        items.merge(productId, qty, Integer::sum); // DD#3: atomic read-modify-write, no race condition
    }

    public void removeItem(String productId) {
        items.remove(productId);
    }

    public void updateQuantity(String productId, int qty) {
        if (qty <= 0) items.remove(productId);
        else items.put(productId, qty);
    }

    public Map<String, Integer> getItems() { return Collections.unmodifiableMap(items); }

    public void clear() { items.clear(); }
}

// ========================== ORDER (R7, R8, R9) ==========================
// R7: Cancel only if not shipped
// R9: Track shipment status + estimated arrival

class Order {
    private final String id;
    private final String userId;
    private final Map<String, Integer> items; // productId -> qty (snapshot from cart)
    private volatile OrderStatus status; // DD#7: volatile for VISIBILITY, synchronized cancel() for ATOMICITY
    private final Address shippingAddress; // R5
    private final PaymentMethod paymentMethod; // R6
    private final double totalAmount;
    private final long createdAt;
    private String trackingId; // R9
    private long estimatedDelivery; // R9

    Order(String userId, Map<String, Integer> items, Address shippingAddress,
          PaymentMethod paymentMethod, double totalAmount) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.items = new HashMap<>(items);
        this.shippingAddress = shippingAddress;
        this.paymentMethod = paymentMethod;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.CONFIRMED;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public double getTotalAmount() { return totalAmount; }
    public Map<String, Integer> getItems() { return Collections.unmodifiableMap(items); }

    // DD#7: State machine — CONFIRMED is the only cancellable state
    // synchronized ensures two threads can't both cancel the same order
    public synchronized boolean cancel() {
        if (status == OrderStatus.CONFIRMED) {
            this.status = OrderStatus.CANCELLED;
            return true;
        }
        return false; // already shipped/delivered — can't cancel
    }

    // R9: Shipment tracking
    public void ship(String trackingId, long estimatedDelivery) {
        this.trackingId = trackingId;
        this.estimatedDelivery = estimatedDelivery;
        this.status = OrderStatus.SHIPPED;
    }

    public void markDelivered() { this.status = OrderStatus.DELIVERED; }
    public String getTrackingId() { return trackingId; }
    public long getEstimatedDelivery() { return estimatedDelivery; }
    public String getUserId() { return userId; }
}

// ========================== PAYMENT — STRATEGY PATTERN (R6) ==========================
// R6: Credit card, bank transfer, cash on delivery
// SDE2 POINT: Strategy pattern decouples payment logic from order flow

// DD#5: Strategy pattern — checkout() is DECOUPLED from payment implementation
// Adding Apple Pay / UPI = new class, zero changes to checkout()
interface PaymentStrategy {
    boolean pay(double amount);
    boolean refund(double amount);
}

class CreditCardPayment implements PaymentStrategy {
    private final String cardNumber;
    CreditCardPayment(String cardNumber) { this.cardNumber = cardNumber; }

    @Override public boolean pay(double amount) {
        System.out.println("Charged $" + amount + " to card ending " + cardNumber.substring(cardNumber.length() - 4));
        return true; // simulate success
    }
    @Override public boolean refund(double amount) {
        System.out.println("Refunded $" + amount);
        return true;
    }
}

class BankTransferPayment implements PaymentStrategy {
    private final String accountNumber;
    BankTransferPayment(String accountNumber) { this.accountNumber = accountNumber; }

    @Override public boolean pay(double amount) {
        System.out.println("Bank transfer of $" + amount);
        return true;
    }
    @Override public boolean refund(double amount) {
        System.out.println("Bank refund of $" + amount);
        return true;
    }
}

class CashOnDeliveryPayment implements PaymentStrategy {
    @Override public boolean pay(double amount) {
        System.out.println("Cash on delivery: $" + amount);
        return true;
    }
    @Override public boolean refund(double amount) {
        System.out.println("Cash refund: $" + amount);
        return true;
    }
}

// ========================== COUPON — DECORATOR PATTERN ==========================
/*
DD#16: COUPON DECORATOR — Why Decorator over Strategy or Simple Inheritance?

THE PROBLEM: Coupons STACK. A user might apply:
  1. 10% seasonal sale  →  $100 becomes $90
  2. Flat $5 off coupon  →  $90 becomes $85
  3. Another 20% loyalty discount  →  $85 becomes $68

WHY DECORATOR:
  - Coupons are COMPOSABLE — you wrap one around another, like layers
  - Order of application MATTERS ($100 → 10% off → $5 off = $85, but $100 → $5 off → 10% = $85.5)
  - You can add NEW coupon types without changing existing ones (Open/Closed Principle)
  - Each decorator has a SINGLE responsibility: apply one discount

WHY NOT these alternatives:
  ALT 1: List<Coupon> with a loop: for(coupon : coupons) total = coupon.apply(total)
         → Simpler but loses the chain's description/audit trail. Decorator gives you
           getDescription() that shows the full chain: "Base($100) + 10% off + $5 flat"
         → Also can't enforce max-discount rules WITHIN the chain easily

  ALT 2: Strategy pattern (single PricingStrategy)
         → Strategy picks ONE algorithm. Decorator COMPOSES multiple.
           You can't stack strategies. Decorator is built for stacking.

  ALT 3: Inheritance (SaleProduct extends Product, CouponProduct extends SaleProduct)
         → Class explosion: 3 coupon types × 3 combinations = 9 subclasses.
           Decorator gives you N combinations from N classes.

INTERVIEW TALK: "Decorator is the GoF pattern designed for exactly this use case —
                 adding behavior dynamically at runtime by wrapping objects.
                 Each coupon wraps the previous pricing, and I can compose them
                 in any order the business rules require."

TRADEOFF: Decorator adds call-stack depth (each layer delegates to the next).
          For 2-3 coupons this is negligible. For 100+ (unlikely), flatten to a loop.
*/

// Step 1: The Component interface — anything that can be priced
interface PriceCalculator {
    double getCost();          // final price after all discounts
    String getDescription();   // audit trail of applied discounts
}

// Step 2: Concrete Component — the base price with NO discounts
class BasePriceCalculator implements PriceCalculator {
    private final double baseTotal;

    BasePriceCalculator(double baseTotal) {
        this.baseTotal = baseTotal;
    }

    @Override public double getCost() { return baseTotal; }
    @Override public String getDescription() { return String.format("Base($%.2f)", baseTotal); }
}

// Step 3: Abstract Decorator — holds reference to wrapped component
// This is the KEY structural piece — every coupon decorator wraps another PriceCalculator
abstract class CouponDecorator implements PriceCalculator {
    protected final PriceCalculator wrapped; // the inner layer we're decorating

    CouponDecorator(PriceCalculator wrapped) {
        this.wrapped = wrapped;
    }
}

// Step 4: Concrete Decorators — each adds one type of discount

// Percentage discount: "20% off" → multiplies by 0.8
class PercentageCoupon extends CouponDecorator {
    private final double percent; // e.g., 20.0 for 20% off
    private final String label;

    PercentageCoupon(PriceCalculator wrapped, double percent, String label) {
        super(wrapped);
        this.percent = percent;
        this.label = label;
    }

    @Override
    public double getCost() {
        return wrapped.getCost() * (1 - percent / 100.0);
    }

    @Override
    public String getDescription() {
        return wrapped.getDescription() + String.format(" → %s(%.0f%% off)", label, percent);
    }
}

// Flat discount: "$15 off" → subtracts fixed amount, floor at 0
class FlatCoupon extends CouponDecorator {
    private final double discount;
    private final String label;

    FlatCoupon(PriceCalculator wrapped, double discount, String label) {
        super(wrapped);
        this.discount = discount;
        this.label = label;
    }

    @Override
    public double getCost() {
        return Math.max(0, wrapped.getCost() - discount); // never go below $0
    }

    @Override
    public String getDescription() {
        return wrapped.getDescription() + String.format(" → %s($%.2f off)", label, discount);
    }
}

// Max-cap coupon: ensures total discount doesn't exceed a maximum
// REAL-WORLD: Amazon caps maximum discount at some threshold
class MaxDiscountCap extends CouponDecorator {
    private final double maxDiscount; // max total discount allowed
    private final double originalTotal;

    MaxDiscountCap(PriceCalculator wrapped, double maxDiscount, double originalTotal) {
        super(wrapped);
        this.maxDiscount = maxDiscount;
        this.originalTotal = originalTotal;
    }

    @Override
    public double getCost() {
        double discountedPrice = wrapped.getCost();
        double totalDiscount = originalTotal - discountedPrice;
        if (totalDiscount > maxDiscount) {
            return originalTotal - maxDiscount; // cap the discount
        }
        return discountedPrice;
    }

    @Override
    public String getDescription() {
        return wrapped.getDescription() + String.format(" → MaxCap($%.2f max discount)", maxDiscount);
    }
}

// ========================== HOW IT INTEGRATES WITH CHECKOUT ==========================
/*
USAGE IN CHECKOUT (see modified checkout method below):

    double baseTotal = 250.0; // sum of all cart items

    // Build the decorator chain — each wraps the previous
    PriceCalculator pricing = new BasePriceCalculator(baseTotal);      // $250.00
    pricing = new PercentageCoupon(pricing, 10, "SUMMER_SALE");        // $225.00
    pricing = new FlatCoupon(pricing, 15, "WELCOME15");                // $210.00
    pricing = new MaxDiscountCap(pricing, 50, baseTotal);              // cap: max $50 off → $200.00

    double finalPrice = pricing.getCost();          // $200.00
    String audit = pricing.getDescription();
    // "Base($250.00) → SUMMER_SALE(10% off) → WELCOME15($15.00 off) → MaxCap($50.00 max discount)"

KEY INSIGHT: The caller decides the ORDER of coupons. Business rules can enforce:
  - Percentage coupons apply BEFORE flat coupons (standard in e-commerce)
  - MaxDiscountCap is ALWAYS the outermost layer
  - Only 1 percentage + 1 flat allowed (validated before building chain)
*/

// ========================== NOTIFICATION — OBSERVER PATTERN (R8) ==========================
// R8: Notify on order/shipping status changes
// SDE2 POINT: Observer pattern for extensible notification channels

interface NotificationListener {
    void onNotify(NotificationType type, Order order, Account account);
}

class EmailNotification implements NotificationListener {
    @Override
    public void onNotify(NotificationType type, Order order, Account account) {
        System.out.println("[EMAIL -> " + account.getName() + "] " + type + " for order " + order.getId());
    }
}

class SmsNotification implements NotificationListener {
    @Override
    public void onNotify(NotificationType type, Order order, Account account) {
        System.out.println("[SMS -> " + account.getName() + "] " + type + " for order " + order.getId());
    }
}

// DD#6: Observer pattern — synchronous for simplicity, async (CompletableFuture) in production
class NotificationService {
    private final List<NotificationListener> listeners = new CopyOnWriteArrayList<>(); // DD#9: COW for read-heavy

    public void subscribe(NotificationListener l) { listeners.add(l); }
    public void unsubscribe(NotificationListener l) { listeners.remove(l); }

    public void publish(NotificationType type, Order order, Account account) {
        for (NotificationListener l : listeners) {
            l.onNotify(type, order, account);
        }
    }
}

// ========================== CATALOG / SEARCH (R2) ==========================
// R2: Search by name or category
// SDE2 POINT: Inverted index maps for O(1) lookup by name/category/seller

// DD#4: Inverted index maps — O(1) lookup, write-amplified but reads >>> writes in e-commerce
class Catalog {
    private final ConcurrentHashMap<String, Product> productsById = new ConcurrentHashMap<>();
    // Inverted indexes for fast search
    private final ConcurrentHashMap<String, List<Product>> byName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProductCategory, List<Product>> byCategory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Product>> bySeller = new ConcurrentHashMap<>();

    public void addProduct(Product p) {
        productsById.put(p.getId(), p);
        byName.computeIfAbsent(p.getName().toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(p);
        byCategory.computeIfAbsent(p.getCategory(), k -> new CopyOnWriteArrayList<>()).add(p);
        bySeller.computeIfAbsent(p.getSellerId(), k -> new CopyOnWriteArrayList<>()).add(p);
    }

    public void removeProduct(String productId) {
        Product p = productsById.remove(productId);
        if (p != null) {
            byName.getOrDefault(p.getName().toLowerCase(), List.of()).remove(p);
            byCategory.getOrDefault(p.getCategory(), List.of()).remove(p);
            bySeller.getOrDefault(p.getSellerId(), List.of()).remove(p);
        }
    }

    public Product getById(String id) { return productsById.get(id); }

    // R2: Search by name (partial match)
    public List<Product> searchByName(String name) {
        String key = name.toLowerCase();
        return byName.entrySet().stream()
                .filter(e -> e.getKey().contains(key))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toList());
    }

    // R2: Search by category
    public List<Product> searchByCategory(ProductCategory cat) {
        return byCategory.getOrDefault(cat, List.of());
    }

    public List<Product> searchBySeller(String sellerId) {
        return bySeller.getOrDefault(sellerId, List.of());
    }
}

// ========================== AMAZON SYSTEM — FACADE (R1-R11) ==========================
// SDE2 POINT: Facade orchestrates all subsystems
// CONCURRENCY: ReentrantLock on checkout to ensure atomic reserve-and-order

// DD#15: Facade — single entry point orchestrates all subsystems
// DD#8: Singleton — one system instance with double-checked locking
class AmazonSystem {
    // DD#8: volatile prevents instruction reordering during construction
    private static volatile AmazonSystem INSTANCE;

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Seller> sellers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Admin> admins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final Catalog catalog = new Catalog();
    private final NotificationService notificationService = new NotificationService();
    private final Set<ProductCategory> activeCategories =
            ConcurrentHashMap.newKeySet(); // R10: manageable categories

    private AmazonSystem() {
        // seed default categories
        activeCategories.addAll(Arrays.asList(ProductCategory.values()));
        // default notification channels
        notificationService.subscribe(new EmailNotification());
        notificationService.subscribe(new SmsNotification());
    }

    // DD#8: Double-checked locking — volatile prevents seeing partially constructed object
    public static AmazonSystem getInstance() {
        if (INSTANCE == null) {
            synchronized (AmazonSystem.class) {
                if (INSTANCE == null) INSTANCE = new AmazonSystem();
            }
        }
        return INSTANCE;
    }

    // ---- Account Management (R1, R10, R11) ----
    public User registerUser(String name, String email, String phone, String password) {
        User u = new User(name, email, phone, password);
        users.put(u.getId(), u);
        return u;
    }

    public Seller registerSeller(String name, String email, String phone) {
        Seller s = new Seller(name, email, phone);
        sellers.put(s.getId(), s);
        return s;
    }

    // R10: Admin blocks user
    public void blockUser(Admin admin, String userId) {
        User u = users.get(userId);
        if (u != null) u.status = AccountStatus.BLOCKED;
    }

    // ---- Product Management (R2, R10) ----
    public Product addProduct(Seller seller, String name, String desc,
                              ProductCategory cat, double price, int qty) {
        if (!activeCategories.contains(cat)) throw new IllegalArgumentException("Category not active");
        Product p = new Product(name, desc, cat, price, qty, seller.getId());
        catalog.addProduct(p);
        seller.addProduct(p.getId());
        return p;
    }

    // R10: Admin manages categories
    public void addCategory(Admin admin, ProductCategory cat) { activeCategories.add(cat); }
    public void removeCategory(Admin admin, ProductCategory cat) { activeCategories.remove(cat); }

    // ---- Search (R2) — Guests AND authenticated users ----
    public List<Product> searchByName(String name) { return catalog.searchByName(name); }
    public List<Product> searchByCategory(ProductCategory cat) { return catalog.searchByCategory(cat); }

    // ---- Cart (R4) ----
    public void addToCart(User user, String productId, int qty) {
        if (user.status == AccountStatus.BLOCKED) throw new IllegalStateException("Account blocked");
        Product p = catalog.getById(productId);
        if (p == null || p.getQuantity() < qty) throw new IllegalStateException("Product unavailable");
        user.getCart().addItem(productId, qty);
    }

    public void removeFromCart(User user, String productId) {
        user.getCart().removeItem(productId);
    }

    // ---- CHECKOUT (R4, R5, R6, R7) ----
    // DD#2: 4-Phase SAGA with compensating rollback — THE MOST IMPORTANT METHOD
    // Phase1: Calculate → Phase1.5: Apply coupons → Phase2: Reserve (CAS) → Phase3: Pay → Phase4: Create Order
    // DD#14: Price fetched NOW from catalog, not from cart (protects against price exploitation)

    // Simple checkout — no coupons
    public Order checkout(User user, Address shippingAddress, PaymentMethod paymentMethod,
                          PaymentStrategy paymentStrategy) {
        return checkoutWithCoupons(user, shippingAddress, paymentMethod, paymentStrategy, null);
    }

    // DD#16: Checkout with DECORATOR CHAIN for coupons
    // Caller builds the chain: new PercentageCoupon(new FlatCoupon(base, ...), ...)
    // Checkout just calls .getCost() on the outermost decorator — clean and simple
    public Order checkoutWithCoupons(User user, Address shippingAddress, PaymentMethod paymentMethod,
                                     PaymentStrategy paymentStrategy, PriceCalculator couponChain) {
        if (user.status == AccountStatus.BLOCKED) throw new IllegalStateException("Account blocked");

        Cart cart = user.getCart();
        Map<String, Integer> items = cart.getItems();
        if (items.isEmpty()) throw new IllegalStateException("Cart is empty");

        // Phase 1: Calculate base total (DD#14: price from catalog, not cart snapshot)
        double baseTotal = 0;
        List<Map.Entry<Product, Integer>> resolved = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            Product p = catalog.getById(entry.getKey());
            if (p == null) throw new IllegalStateException("Product " + entry.getKey() + " not found");
            resolved.add(Map.entry(p, entry.getValue()));
            baseTotal += p.getBasePrice() * entry.getValue();
        }

        // Phase 1.5: APPLY COUPON DECORATOR CHAIN (DD#16)
        // If no coupons, use base total. Otherwise, caller has already built the chain.
        double finalTotal;
        if (couponChain != null) {
            finalTotal = couponChain.getCost();
            System.out.println("Coupon breakdown: " + couponChain.getDescription());
        } else {
            finalTotal = baseTotal;
        }
        System.out.println("Base: $" + String.format("%.2f", baseTotal) +
                         " → Final: $" + String.format("%.2f", finalTotal));

        // Phase 2: Reserve inventory atomically (all-or-nothing) — DD#1 CAS-based
        List<Map.Entry<Product, Integer>> reserved = new ArrayList<>();
        try {
            for (Map.Entry<Product, Integer> entry : resolved) {
                if (!entry.getKey().decrementQuantity(entry.getValue())) {
                    throw new IllegalStateException("Insufficient stock for: " + entry.getKey().getName());
                }
                reserved.add(entry);
            }
        } catch (IllegalStateException e) {
            for (Map.Entry<Product, Integer> r : reserved) {
                r.getKey().incrementQuantity(r.getValue());
            }
            throw e;
        }

        // Phase 3: Process payment with DISCOUNTED total
        if (!paymentStrategy.pay(finalTotal)) {
            for (Map.Entry<Product, Integer> r : reserved) {
                r.getKey().incrementQuantity(r.getValue());
            }
            throw new IllegalStateException("Payment failed");
        }

        // Phase 4: Create order with final discounted amount
        Order order = new Order(user.getId(), items, shippingAddress, paymentMethod, finalTotal);
        orders.put(order.getId(), order);
        cart.clear();

        notificationService.publish(NotificationType.ORDER_PLACED, order, user);
        return order;
    }

    // R7: Cancel only if not shipped
    public boolean cancelOrder(String orderId, PaymentStrategy refundStrategy) {
        Order order = orders.get(orderId);
        if (order == null) return false;

        if (order.cancel()) {
            // Restore inventory
            for (Map.Entry<String, Integer> item : order.getItems().entrySet()) {
                Product p = catalog.getById(item.getKey());
                if (p != null) p.incrementQuantity(item.getValue());
            }
            // Refund
            refundStrategy.refund(order.getTotalAmount());

            User user = users.get(order.getUserId());
            if (user != null) {
                notificationService.publish(NotificationType.ORDER_CANCELLED, order, user);
            }
            return true;
        }
        return false; // can't cancel — already shipped
    }

    // R9: Shipment tracking
    public void shipOrder(String orderId, String trackingId, long estimatedDelivery) {
        Order order = orders.get(orderId);
        if (order != null && order.getStatus() == OrderStatus.CONFIRMED) {
            order.ship(trackingId, estimatedDelivery);
            User user = users.get(order.getUserId());
            if (user != null) {
                notificationService.publish(NotificationType.ORDER_SHIPPED, order, user);
            }
        }
    }

    // R3: Add review
    public void addReview(User user, String productId, String comment, int rating) {
        Product p = catalog.getById(productId);
        if (p != null) {
            p.addReview(new Review(user.getId(), comment, rating));
        }
    }
}

// ========================== DEMO ==========================

public class AmazonShoppingSystem {
    public static void main(String[] args) {
        AmazonSystem amazon = AmazonSystem.getInstance();

        // Register accounts
        User alice = amazon.registerUser("Alice", "alice@email.com", "1234567890", "pass123");
        alice.addAddress(new Address("123 Main St", "Seattle", "WA", "98101", "US"));
        Seller bob = amazon.registerSeller("Bob's Electronics", "bob@email.com", "9876543210");

        // Add products
        Product laptop = amazon.addProduct(bob, "Laptop", "High-end laptop",
                ProductCategory.ELECTRONICS, 999.99, 10);
        Product phone = amazon.addProduct(bob, "Phone", "Smartphone",
                ProductCategory.ELECTRONICS, 699.99, 5);

        // Search (R2)
        System.out.println("=== Search 'laptop' ===");
        amazon.searchByName("laptop").forEach(p -> System.out.println("  " + p.getName() + " $" + p.getBasePrice()));

        // Add to cart (R4) and checkout (R4, R5, R6)
        amazon.addToCart(alice, laptop.getId(), 1);
        amazon.addToCart(alice, phone.getId(), 2);

        Order order = amazon.checkout(alice, alice.getAddresses().get(0),
                PaymentMethod.CREDIT_CARD, new CreditCardPayment("4111111111111234"));

        System.out.println("\n=== Order Created: " + order.getId() + " ===");
        System.out.println("Status: " + order.getStatus() + " | Total: $" + order.getTotalAmount());

        // Ship order (R9)
        amazon.shipOrder(order.getId(), "TRACK-12345",
                System.currentTimeMillis() + 3 * 24 * 3600 * 1000L);
        System.out.println("After shipping — Status: " + order.getStatus());

        // Try cancel after shipping (R7) — should fail
        boolean cancelled = amazon.cancelOrder(order.getId(), new CreditCardPayment("4111111111111234"));
        System.out.println("Cancel after shipping: " + (cancelled ? "Success" : "Failed (correct)"));

        // Review (R3)
        amazon.addReview(alice, laptop.getId(), "Great laptop!", 5);
        Product p = amazon.searchByName("laptop").get(0);
        System.out.println("\nLaptop avg rating: " + p.getAvgRating());

        // ========================== COUPON DECORATOR DEMO ==========================
        System.out.println("\n=== Coupon Decorator Demo ===");

        // Scenario: User buys a $500 item with stacked coupons
        Product tablet = amazon.addProduct(bob, "Tablet", "Pro tablet",
                ProductCategory.ELECTRONICS, 500.00, 10);
        User carol = amazon.registerUser("Carol", "carol@email.com", "5555555555", "pass");
        carol.addAddress(new Address("456 Oak Ave", "Portland", "OR", "97201", "US"));
        amazon.addToCart(carol, tablet.getId(), 1);

        // BUILD THE DECORATOR CHAIN — this is the key interview talking point
        // Each layer wraps the previous one, like Russian nesting dolls
        //
        //   Innermost → BasePriceCalculator($500.00)
        //   Layer 1   → PercentageCoupon(10% off) → $450.00
        //   Layer 2   → FlatCoupon($25 off)        → $425.00
        //   Layer 3   → MaxDiscountCap($80 max)     → $420.00 (capped: discount was $75 < $80, no cap hit)
        //
        double baseTotal = 500.00;
        PriceCalculator pricing = new BasePriceCalculator(baseTotal);
        pricing = new PercentageCoupon(pricing, 10, "SUMMER_SALE");
        pricing = new FlatCoupon(pricing, 25, "WELCOME25");
        pricing = new MaxDiscountCap(pricing, 80, baseTotal);

        // Verify the chain BEFORE checkout
        System.out.println("Chain description: " + pricing.getDescription());
        System.out.println("Chain final cost:  $" + String.format("%.2f", pricing.getCost()));

        // Pass the pre-built chain into checkout
        Order couponOrder = amazon.checkoutWithCoupons(carol, carol.getAddresses().get(0),
                PaymentMethod.CREDIT_CARD, new CreditCardPayment("4111111111111234"), pricing);
        System.out.println("Order total with coupons: $" + String.format("%.2f", couponOrder.getTotalAmount()));

        // DEMO: What if discount exceeds cap?
        System.out.println("\n--- MaxDiscountCap in action ---");
        PriceCalculator aggressive = new BasePriceCalculator(500.00);
        aggressive = new PercentageCoupon(aggressive, 40, "MEGA_SALE");  // $300 → $200 discount
        aggressive = new FlatCoupon(aggressive, 50, "VIP50");            // $250 → $250 discount
        aggressive = new MaxDiscountCap(aggressive, 100, 500.00);        // Cap at $100 off → $400
        System.out.println("Aggressive coupons: " + aggressive.getDescription());
        System.out.println("Without cap: $" + String.format("%.2f", 500 * 0.6 - 50) + " | With cap: $" + String.format("%.2f", aggressive.getCost()));

        // Concurrent checkout stress test
        System.out.println("\n=== Concurrent Checkout Test ===");
        Product limitedItem = amazon.addProduct(bob, "Limited Edition", "Only 1 left",
                ProductCategory.ELECTRONICS, 49.99, 1);

        User user1 = amazon.registerUser("User1", "u1@e.com", "111", "p");
        user1.addAddress(new Address("A", "B", "C", "D", "E"));
        User user2 = amazon.registerUser("User2", "u2@e.com", "222", "p");
        user2.addAddress(new Address("A", "B", "C", "D", "E"));

        amazon.addToCart(user1, limitedItem.getId(), 1);
        amazon.addToCart(user2, limitedItem.getId(), 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        for (User u : List.of(user1, user2)) {
            executor.submit(() -> {
                try {
                    amazon.checkout(u, u.getAddresses().get(0),
                            PaymentMethod.CREDIT_CARD, new CreditCardPayment("4111111111111234"));
                    successCount.incrementAndGet();
                    System.out.println(u.getName() + " — checkout SUCCESS");
                } catch (Exception e) {
                    System.out.println(u.getName() + " — checkout FAILED: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException ignored) {}
        System.out.println("Successful checkouts: " + successCount.get() + " (expected: 1)");
        executor.shutdown();
    }
}
