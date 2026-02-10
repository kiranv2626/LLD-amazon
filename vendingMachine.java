import java.util.*;

// ===== Observer (minimal) =====
interface Observer {
    void onMessage(String msg);
    void onLowStock(int rack, int remaining);
}

class ConsoleObserver implements Observer {
    public void onMessage(String msg) { System.out.println(msg); }
    public void onLowStock(int rack, int remaining) {
        System.out.println("[LOW_STOCK] rack=" + rack + " remaining=" + remaining);
    }
}

// ===== Payment Strategy (CASH + CARD, interview-safe semantics) =====
interface PaymentStrategy {
    String name();

    // returns authId (null if declined)
    String authorize(long priceCents);

    // returns captureId (null if failed)
    String capture(String authId, long priceCents);

    // for card: void the authorization if dispense fails or user cancels before capture
    void voidAuth(String authId);

    // for card: refund a captured payment
    void refund(String captureId, long amountCents);

    default void returnChange(long changeCents) { /* cash only; default no-op */ }
}

class CashPayment implements PaymentStrategy {
    public String name() { return "CASH"; }

    public String authorize(long priceCents) { return "CASH-AUTH"; } // cash is validated by balance check
    public String capture(String authId, long priceCents) { return "CASH-CAP"; } // no-op capture
    public void voidAuth(String authId) { /* no-op */ }

    public void refund(String captureId, long amountCents) {
        System.out.println("[PAY] cash refund " + amountCents + " cents");
    }

    public void returnChange(long changeCents) {
        System.out.println("[PAY] cash change " + changeCents + " cents");
    }
}

class CardPayment implements PaymentStrategy {
    private static int SEQ = 1;

    public String name() { return "CARD"; }

    public String authorize(long priceCents) {
        // simulate gateway OK
        return "AUTH-" + (SEQ++);
    }

    public String capture(String authId, long priceCents) {
        System.out.println("[PAY] card charged " + priceCents + " cents (auth=" + authId + ")");
        return "CAP-" + (SEQ++);
    }

    public void voidAuth(String authId) {
        System.out.println("[PAY] card void auth " + authId);
    }

    public void refund(String captureId, long amountCents) {
        System.out.println("[PAY] card refund " + amountCents + " cents (cap=" + captureId + ")");
    }
}

// ===== Inventory =====
class Product {
    final int id;
    final String name;
    final long priceCents;
    Product(int id, String name, long priceCents) {
        this.id = id; this.name = name; this.priceCents = priceCents;
    }
}

class Rack {
    final int rackNo;
    Product product;
    int qty;

    Rack(int rackNo) { this.rackNo = rackNo; }

    void load(Product p, int addQty) {
        if (addQty <= 0) throw new IllegalArgumentException("qty must be > 0");
        product = p;
        qty += addQty;
    }

    boolean empty() { return product == null || qty <= 0; }

    void dispenseOne() {
        if (empty()) throw new IllegalStateException("out of stock");
        qty--;
    }
}

class Inventory {
    private final Map<Integer, Rack> racks = new HashMap<>();
    void addRack(int rackNo) { racks.put(rackNo, new Rack(rackNo)); }
    Rack get(int rackNo) { return racks.get(rackNo); }
    Collection<Rack> all() { return racks.values(); }
}

// ===== State =====
interface State {
    void insertMoney(VendingMachine vm, long cents); // cash only
    void pressButton(VendingMachine vm, int rackNo);
    void cancel(VendingMachine vm);
}

class NoMoneyState implements State {
    public void insertMoney(VendingMachine vm, long cents) {
        if (vm.payment instanceof CardPayment) {
            vm.notifyMsg("Card selected. No cash needed. Press button to buy.");
            vm.state = vm.readyState;
            return;
        }
        if (cents <= 0) { vm.notifyMsg("Insert positive amount."); return; }
        vm.balance += cents;
        vm.notifyMsg("Inserted " + cents + " cents. Balance=" + vm.balance);
        vm.state = vm.readyState;
    }

    public void pressButton(VendingMachine vm, int rackNo) {
        if (vm.payment instanceof CardPayment) {
            vm.notifyMsg("Card selected. Proceeding to selection.");
            vm.state = vm.readyState;
            vm.state.pressButton(vm, rackNo);
            return;
        }
        vm.notifyMsg("Insert money first (or choose card).");
    }

    public void cancel(VendingMachine vm) {
        vm.notifyMsg("Nothing to cancel.");
    }
}

class ReadyState implements State {
    public void insertMoney(VendingMachine vm, long cents) {
        if (vm.payment instanceof CardPayment) {
            vm.notifyMsg("Card selected. No cash insert needed.");
            return;
        }
        if (cents <= 0) { vm.notifyMsg("Insert positive amount."); return; }
        vm.balance += cents;
        vm.notifyMsg("Inserted " + cents + " cents. Balance=" + vm.balance);
    }

    public void pressButton(VendingMachine vm, int rackNo) {
        Rack r = vm.inventory.get(rackNo);
        if (r == null || r.product == null) {
            vm.notifyMsg("Invalid rack. Refund/cancel.");
            vm.refund();
            return;
        }
        if (r.empty()) {
            vm.notifyMsg("Out of stock. Refund/cancel.");
            vm.refund();
            return;
        }

        long price = r.product.priceCents;

        // CASH: ensure enough balance BEFORE authorizing
        if (vm.payment instanceof CashPayment && vm.balance < price) {
            vm.notifyMsg("Insufficient cash. Price=" + price + " Balance=" + vm.balance);
            return;
        }

        // 1) AUTHORIZE (card: real; cash: placeholder)
        String authId = vm.payment.authorize(price);
        if (authId == null) {
            vm.notifyMsg("Authorization failed. Refund/cancel.");
            vm.refund();
            return;
        }
        vm.lastAuthId = authId;

        // 2) DISPENSE state
        vm.state = vm.dispenseState;

        // 3) DISPENSE FLOW handles dispense -> capture
        vm.dispense(rackNo);
    }

    public void cancel(VendingMachine vm) {
        vm.notifyMsg("Cancelled. Refund/cancel.");
        vm.refund();
    }
}

class DispenseState implements State {
    public void insertMoney(VendingMachine vm, long cents) { vm.notifyMsg("Dispensing... wait"); }
    public void pressButton(VendingMachine vm, int rackNo) { vm.notifyMsg("Dispensing... wait"); }
    public void cancel(VendingMachine vm) { vm.notifyMsg("Cannot cancel during dispense"); }
}

// ===== VendingMachine (Singleton) =====
public class VendingMachine {
    private static VendingMachine INSTANCE;
    public static synchronized VendingMachine getInstance() {
        if (INSTANCE == null) INSTANCE = new VendingMachine();
        return INSTANCE;
    }

    final Inventory inventory = new Inventory();
    final List<Observer> observers = new ArrayList<>();

    final State noMoneyState = new NoMoneyState();
    final State readyState = new ReadyState();
    final State dispenseState = new DispenseState();

    State state = noMoneyState;

    PaymentStrategy payment = new CashPayment(); // default
    long balance = 0;

    // "transaction-ish" fields (minimal)
    String lastAuthId = null;
    String lastCaptureId = null;
    long lastCapturedAmount = 0;

    private final int LOW_STOCK = 1;

    private VendingMachine() { observers.add(new ConsoleObserver()); }

    // Customer API (synchronized is fine for single kiosk)
    public synchronized void setPayment(PaymentStrategy p) {
        if (balance > 0 || lastAuthId != null || lastCaptureId != null) {
            notifyMsg("Switching payment -> cancelling current txn (refund/void as needed).");
            refund();
        }
        payment = p;
        notifyMsg("Payment set to " + p.name());
    }

    public synchronized void insertMoney(long cents) { state.insertMoney(this, cents); }
    public synchronized void pressButton(int rackNo) { state.pressButton(this, rackNo); }
    public synchronized void cancel() { state.cancel(this); }

    // Admin
    public synchronized void addRack(int rackNo) { inventory.addRack(rackNo); }

    public synchronized void load(int rackNo, Product p, int qty) {
        Rack r = inventory.get(rackNo);
        if (r == null) throw new IllegalArgumentException("Rack not found: " + rackNo);
        r.load(p, qty);
    }

    public synchronized void show() {
        notifyMsg("Inventory:");
        for (Rack r : inventory.all()) {
            if (r.product == null) continue;
            notifyMsg("Rack " + r.rackNo + ": " + r.product.name + " price=" + r.product.priceCents + " qty=" + r.qty);
        }
    }

    // Internal ops: AUTHORIZE already done. Here: DISPENSE -> CAPTURE.
    void dispense(int rackNo) {
        Rack r = inventory.get(rackNo);
        Product p = r.product;
        long price = p.priceCents;

        try {
            // 1) DISPENSE FIRST (may fail)
            r.dispenseOne();
            notifyMsg("Dispensed: " + p.name);

            // 2) CAPTURE AFTER successful dispense
            String capId = payment.capture(lastAuthId, price);
            if (capId == null) {
                notifyMsg("Capture failed AFTER dispense. Needs reconciliation.");
                // In real world: alert + reconcile offline
            } else {
                lastCaptureId = capId;
                lastCapturedAmount = price;
            }

            // 3) CASH change
            if (payment instanceof CashPayment) {
                long change = balance - price;
                if (change > 0) payment.returnChange(change);
            }

            if (r.qty <= LOW_STOCK) notifyLowStock(rackNo, r.qty);

        } catch (Exception ex) {
            notifyMsg("Dispense failed: " + ex.getMessage());

            // If we authorized a card, void it
            if (lastAuthId != null) payment.voidAuth(lastAuthId);

            // Refund cash if any
            refund();
            return;

        } finally {
            // reset to idle after dispense attempt (success or failure)
            balance = 0;
            lastAuthId = null;
            state = noMoneyState;
        }
    }

    void refund() {
        // CASH: refund inserted balance
        if (payment instanceof CashPayment) {
            if (balance > 0) payment.refund("CASH", balance);
        } else {
            // CARD: if captured, refund; else if authorized, void auth
            if (lastCaptureId != null && lastCapturedAmount > 0) {
                payment.refund(lastCaptureId, lastCapturedAmount);
            } else if (lastAuthId != null) {
                payment.voidAuth(lastAuthId);
            }
        }

        // reset all txn fields
        balance = 0;
        lastAuthId = null;
        lastCaptureId = null;
        lastCapturedAmount = 0;
        state = noMoneyState;
    }

    void notifyMsg(String msg) { for (Observer o : observers) o.onMessage(msg); }
    void notifyLowStock(int rack, int remaining) { for (Observer o : observers) o.onLowStock(rack, remaining); }

    // Demo
    public static void main(String[] args) {
        VendingMachine vm = VendingMachine.getInstance();

        vm.addRack(1); vm.addRack(2);
        vm.load(1, new Product(101, "Soda", 150), 2);
        vm.load(2, new Product(102, "Chips", 100), 1);
        vm.show();

        vm.setPayment(new CashPayment());
        vm.insertMoney(200);
        vm.pressButton(1);

        vm.setPayment(new CashPayment());
        vm.insertMoney(50);
        vm.pressButton(1);
        vm.cancel();

        vm.setPayment(new CardPayment());
        vm.pressButton(2);

        vm.show();
    }
}
