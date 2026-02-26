import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

// ========================= ENUMS (as per skeleton) =========================
enum ATMStatus {
    Idle,
    HasCard,
    SelectionOption,
    Withdraw,
    TransferMoney,
    BalanceInquiry
}

enum TransactionType {
    BalanceInquiry,
    CashWithdrawal,
    FundsTransfer,
    ChangePIN,
    Cancel
}

// ========================= DOMAIN (as per skeleton) =========================
class User {
    private ATMCard card;
    private BankAccount account;

    public User(ATMCard card, BankAccount account) {
        this.card = card;
        this.account = account;
    }

    public ATMCard getCard() { return card; }
    public BankAccount getAccount() { return account; }
}

class ATMCard {
    private String cardNumber;
    private String customerName;
    private Date cardExpiryDate;
    private int pin;

    public ATMCard(String cardNumber, String customerName, Date expiry, int pin) {
        this.cardNumber = cardNumber;
        this.customerName = customerName;
        this.cardExpiryDate = expiry;
        this.pin = pin;
    }

    public String getCardNumber() { return cardNumber; }
    public String getCustomerName() { return customerName; }
    public Date getCardExpiryDate() { return cardExpiryDate; }

    public boolean verifyPin(int entered) { return this.pin == entered; }
    public void setPin(int newPin) { this.pin = newPin; }
}

class Bank {
    private String name;
    private String bankCode;

    public Bank(String name, String bankCode) {
        this.name = name;
        this.bankCode = bankCode;
    }

    public String getBankCode() { return bankCode; }
    public String getName() { return name; }
}

/**
 * NOTE: In real world, ATM does NOT directly mutate balances;
 * it calls bank/core services. For interview LLD, we keep this simple but clean.
 */
abstract class BankAccount {
    protected int accountNumber;
    protected double totalBalance;
    protected double availableBalance;

    public BankAccount(int accountNumber, double totalBalance) {
        this.accountNumber = accountNumber;
        this.totalBalance = totalBalance;
        this.availableBalance = totalBalance;
    }

    public int getAccountNumber() { return accountNumber; }

    public double getAvailableBalance() { return availableBalance; }

    public boolean withdraw(double amount) {
        if (amount <= 0) return false;
        if (amount <= availableBalance) {
            availableBalance -= amount;
            totalBalance -= amount;
            return true;
        }
        return false;
    }

    public boolean deposit(double amount) {
        if (amount <= 0) return false;
        availableBalance += amount;
        totalBalance += amount;
        return true;
    }

    public boolean transfer(BankAccount toAccount, double amount) {
        if (toAccount == null || amount <= 0) return false;
        if (amount <= availableBalance) {
            availableBalance -= amount;
            totalBalance -= amount;
            toAccount.availableBalance += amount;
            toAccount.totalBalance += amount;
            return true;
        }
        return false;
    }

    public abstract double getWithdrawLimit();
}

class SavingAccount extends BankAccount {
    public SavingAccount(int accountNumber, double totalBalance) { super(accountNumber, totalBalance); }
    @Override public double getWithdrawLimit() { return 1000.0; }
}

class CurrentAccount extends BankAccount {
    public CurrentAccount(int accountNumber, double totalBalance) { super(accountNumber, totalBalance); }
    @Override public double getWithdrawLimit() { return 5000.0; }
}

// ========================= HARDWARE (as per skeleton) =========================
class CardReader {
    public boolean readCard(ATMCard card) { return card != null; }
}

class CashDispenser {
    public boolean dispenseCash(int amount) { return amount > 0; }
}

class Keypad {
    public String getInput() { return ""; }
}

class Screen {
    public void showMessage(String message) { System.out.println(message); }
}

class Printer {
    public void printReceipt(String details) { System.out.println("Receipt: " + details); }
}

// ========================= OBSERVER PATTERN =========================
enum ATMEventType { INFO, ERROR, STATE_CHANGED, TXN_SUCCESS, TXN_FAILED }

final class ATMEvent {
    final ATMEventType type;
    final String message;
    ATMEvent(ATMEventType type, String message) {
        this.type = type; this.message = message;
    }
}

interface ATMEventListener {
    void onEvent(ATMEvent event);
}

final class ATMEventBus {
    private final List<ATMEventListener> listeners = new CopyOnWriteArrayList<>();
    public void register(ATMEventListener l) { if (l != null) listeners.add(l); }
    public void publish(ATMEvent e) { for (ATMEventListener l : listeners) l.onEvent(e); }
}

// Simple observers
final class ScreenObserver implements ATMEventListener {
    private final Screen screen;
    ScreenObserver(Screen screen) { this.screen = screen; }
    @Override public void onEvent(ATMEvent event) {
        screen.showMessage("[" + event.type + "] " + event.message);
    }
}

final class AuditObserver implements ATMEventListener {
    @Override public void onEvent(ATMEvent event) {
        // In real world: write to secure audit log (append-only)
        System.out.println("[AUDIT] " + event.type + " :: " + event.message);
    }
}

// ========================= CHAIN OF RESPONSIBILITY (cash breakdown) =========================
final class CashBundle {
    final Map<Integer, Integer> denomToCount = new LinkedHashMap<>();
    int total() {
        int sum = 0;
        for (Map.Entry<Integer,Integer> e : denomToCount.entrySet()) sum += e.getKey() * e.getValue();
        return sum;
    }
    void add(int denom, int cnt) {
        if (cnt <= 0) return;
        denomToCount.put(denom, denomToCount.getOrDefault(denom, 0) + cnt);
    }
    @Override public String toString() { return denomToCount.toString(); }
}

abstract class CashWithdrawProcessor {
    protected CashWithdrawProcessor next;
    protected final int denom;

    protected CashWithdrawProcessor(int denom) { this.denom = denom; }

    public CashWithdrawProcessor linkWith(CashWithdrawProcessor n) { this.next = n; return n; }

    public void withdraw(ATM atm, int amount, CashBundle bundle) {
        if (amount <= 0) return;

        int availableNotes = atm.getBillCount(denom);
        int need = amount / denom;
        int used = Math.min(need, availableNotes);

        if (used > 0) {
            bundle.add(denom, used);
            atm.decrementBills(denom, used);
            amount -= used * denom;
        }

        if (amount > 0) {
            if (next == null) {
                // rollback if cannot satisfy
                throw new IllegalStateException("Cannot dispense exact amount with available denominations");
            }
            next.withdraw(atm, amount, bundle);
        }
    }
}

// For your skeleton denominations: 100, 50, 10
final class HundredDollarWithdrawProcessor extends CashWithdrawProcessor {
    HundredDollarWithdrawProcessor() { super(100); }
}
final class FiftyDollarWithdrawProcessor extends CashWithdrawProcessor {
    FiftyDollarWithdrawProcessor() { super(50); }
}
final class TenDollarWithdrawProcessor extends CashWithdrawProcessor {
    TenDollarWithdrawProcessor() { super(10); }
}

// ========================= STRATEGY + FACTORY =========================
final class TransactionRequest {
    final TransactionType type;
    final double amount;              // for withdraw/transfer
    final BankAccount toAccount;      // for transfer
    final Integer newPin;             // for change pin

    private TransactionRequest(TransactionType type, double amount, BankAccount toAccount, Integer newPin) {
        this.type = type; this.amount = amount; this.toAccount = toAccount; this.newPin = newPin;
    }

    public static TransactionRequest balance() { return new TransactionRequest(TransactionType.BalanceInquiry, 0, null, null); }
    public static TransactionRequest withdraw(double amount) { return new TransactionRequest(TransactionType.CashWithdrawal, amount, null, null); }
    public static TransactionRequest transfer(BankAccount to, double amount) { return new TransactionRequest(TransactionType.FundsTransfer, amount, to, null); }
    public static TransactionRequest changePin(int newPin) { return new TransactionRequest(TransactionType.ChangePIN, 0, null, newPin); }
    public static TransactionRequest cancel() { return new TransactionRequest(TransactionType.Cancel, 0, null, null); }
}

interface TransactionStrategy {
    void execute(ATM atm, ATMCard card, TransactionRequest req);
}

final class BalanceInquiryTxn implements TransactionStrategy {
    @Override public void execute(ATM atm, ATMCard card, TransactionRequest req) {
        BankAccount acc = atm.getActiveAccount();
        atm.getEventBus().publish(new ATMEvent(ATMEventType.TXN_SUCCESS,
                "Balance for A/c " + acc.getAccountNumber() + " = $" + acc.getAvailableBalance()));
        atm.getPrinter().printReceipt("Balance: $" + acc.getAvailableBalance());
        atm.setState(new SelectionOptionState());
    }
}

final class CashWithdrawalTxn implements TransactionStrategy {
    @Override public void execute(ATM atm, ATMCard card, TransactionRequest req) {
        double amountD = req.amount;
        int amount = (int) amountD;

        if (amount <= 0 || amountD != amountD || amountD != amount) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Invalid withdrawal amount"));
            atm.setState(new SelectionOptionState());
            return;
        }

        BankAccount acc = atm.getActiveAccount();
        if (amount > acc.getWithdrawLimit()) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Exceeds account withdraw limit: $" + acc.getWithdrawLimit()));
            atm.setState(new SelectionOptionState());
            return;
        }
        if (amount > acc.getAvailableBalance()) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Insufficient account balance"));
            atm.setState(new SelectionOptionState());
            return;
        }
        if (amount > atm.getAtmBalance()) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "ATM has insufficient cash"));
            atm.setState(new SelectionOptionState());
            return;
        }
        // validity with denominations (100,50,10)
        if (amount % 10 != 0) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Amount must be multiple of 10 for this ATM"));
            atm.setState(new SelectionOptionState());
            return;
        }

        // Chain: 100 -> 50 -> 10
        CashWithdrawProcessor chain = new HundredDollarWithdrawProcessor();
        chain.linkWith(new FiftyDollarWithdrawProcessor()).linkWith(new TenDollarWithdrawProcessor());

        // Snapshot for rollback if chain fails
        int b100 = atm.getBillCount(100);
        int b50 = atm.getBillCount(50);
        int b10 = atm.getBillCount(10);

        try {
            CashBundle bundle = new CashBundle();
            chain.withdraw(atm, amount, bundle);

            // Now dispense and update balances
            boolean dispensed = atm.getCashDispenser().dispenseCash(amount);
            if (!dispensed) throw new IllegalStateException("Hardware dispense failed");

            boolean ok = acc.withdraw(amount);
            if (!ok) throw new IllegalStateException("Account debit failed");

            atm.decreaseAtmBalance(amount);

            atm.getEventBus().publish(new ATMEvent(ATMEventType.TXN_SUCCESS,
                    "Dispensed $" + amount + " using " + bundle));
            atm.getPrinter().printReceipt("Withdraw: $" + amount + " | Notes: " + bundle);

        } catch (Exception ex) {
            // rollback bills if failure
            atm.setBillCount(100, b100);
            atm.setBillCount(50, b50);
            atm.setBillCount(10, b10);

            atm.getEventBus().publish(new ATMEvent(ATMEventType.TXN_FAILED, "Withdrawal failed: " + ex.getMessage()));
        } finally {
            atm.setState(new SelectionOptionState());
        }
    }
}

final class FundsTransferTxn implements TransactionStrategy {
    @Override public void execute(ATM atm, ATMCard card, TransactionRequest req) {
        BankAccount from = atm.getActiveAccount();
        BankAccount to = req.toAccount;

        if (to == null) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "To-account required"));
            atm.setState(new SelectionOptionState());
            return;
        }
        if (req.amount <= 0) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Invalid transfer amount"));
            atm.setState(new SelectionOptionState());
            return;
        }

        boolean ok = from.transfer(to, req.amount);
        if (ok) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.TXN_SUCCESS,
                    "Transferred $" + req.amount + " from " + from.getAccountNumber() + " to " + to.getAccountNumber()));
            atm.getPrinter().printReceipt("Transfer: $" + req.amount + " -> A/c " + to.getAccountNumber());
        } else {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.TXN_FAILED, "Transfer failed: insufficient balance"));
        }

        atm.setState(new SelectionOptionState());
    }
}

final class ChangePinTxn implements TransactionStrategy {
    @Override public void execute(ATM atm, ATMCard card, TransactionRequest req) {
        if (req.newPin == null || req.newPin < 1000 || req.newPin > 9999) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "PIN must be 4 digits"));
            atm.setState(new SelectionOptionState());
            return;
        }
        card.setPin(req.newPin);
        atm.getEventBus().publish(new ATMEvent(ATMEventType.TXN_SUCCESS, "PIN changed successfully"));
        atm.getPrinter().printReceipt("PIN changed");
        atm.setState(new SelectionOptionState());
    }
}

final class CancelTxn implements TransactionStrategy {
    @Override public void execute(ATM atm, ATMCard card, TransactionRequest req) {
        atm.getEventBus().publish(new ATMEvent(ATMEventType.INFO, "Transaction canceled"));
        atm.setState(new SelectionOptionState());
    }
}

// Factory Pattern: returns correct strategy based on TransactionType
final class TransactionFactory {
    public static TransactionStrategy get(TransactionType type) {
        switch (type) {
            case BalanceInquiry: return new BalanceInquiryTxn();
            case CashWithdrawal: return new CashWithdrawalTxn();
            case FundsTransfer:  return new FundsTransferTxn();
            case ChangePIN:      return new ChangePinTxn();
            case Cancel:         return new CancelTxn();
            default: throw new IllegalArgumentException("Unknown txn type");
        }
    }
}

// Factory Pattern: hardware creation (keeps ATM constructor clean)
final class HardwareFactory {
    public static CardReader createCardReader() { return new CardReader(); }
    public static CashDispenser createCashDispenser() { return new CashDispenser(); }
    public static Keypad createKeypad() { return new Keypad(); }
    public static Screen createScreen() { return new Screen(); }
    public static Printer createPrinter() { return new Printer(); }
}

// ========================= STATE PATTERN (as per your skeleton) =========================
abstract class ATMState {
    public void insertCard(ATM atm, ATMCard card) { invalid(atm, "insertCard"); }
    public void authenticatePin(ATM atm, ATMCard card, int pin) { invalid(atm, "authenticatePin"); }
    public void selectOperation(ATM atm, TransactionType tType) { invalid(atm, "selectOperation"); }
    public void cashWithdrawal(ATM atm, ATMCard card, double amount) { invalid(atm, "cashWithdrawal"); }
    public void displayBalance(ATM atm, ATMCard card) { invalid(atm, "displayBalance"); }
    public void transferMoney(ATM atm, ATMCard card, BankAccount toAccount, double amount) { invalid(atm, "transferMoney"); }
    public void changePin(ATM atm, ATMCard card, int newPin) { invalid(atm, "changePin"); }
    public void cancelTransaction(ATM atm) { invalid(atm, "cancelTransaction"); }
    public void returnCard(ATM atm) { invalid(atm, "returnCard"); }
    public void exit(ATM atm) { invalid(atm, "exit"); }

    protected void invalid(ATM atm, String op) {
        atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Invalid op '" + op + "' in state " + atm.getAtmStatus()));
    }
}

// Idle -> accepts card
class IdleState extends ATMState {
    @Override
    public void insertCard(ATM atm, ATMCard card) {
        if (!atm.getCardReader().readCard(card)) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Card read failed"));
            return;
        }
        atm.setInsertedCard(card);
        atm.setAtmStatus(ATMStatus.HasCard);
        atm.setState(new HasCardState());
        atm.getEventBus().publish(new ATMEvent(ATMEventType.STATE_CHANGED, "Card inserted"));
    }
}

// HasCard -> PIN auth
class HasCardState extends ATMState {
    @Override
    public void authenticatePin(ATM atm, ATMCard card, int pin) {
        if (card == null || atm.getInsertedCard() == null) {
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "No card in ATM"));
            atm.setState(new IdleState());
            atm.setAtmStatus(ATMStatus.Idle);
            return;
        }
        if (!atm.getInsertedCard().verifyPin(pin)) {
            atm.incrementPinTries();
            atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Invalid PIN"));
            if (atm.getPinTries() >= 3) {
                atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Max PIN tries exceeded. Ejecting card"));
                atm.returnCardAndReset();
            }
            return;
        }
        atm.resetPinTries();
        atm.setAtmStatus(ATMStatus.SelectionOption);
        atm.setState(new SelectionOptionState());
        atm.getEventBus().publish(new ATMEvent(ATMEventType.STATE_CHANGED, "Authenticated"));
    }

    @Override
    public void returnCard(ATM atm) {
        atm.returnCardAndReset();
    }
}

// SelectionOption -> choose txn (strategy)
class SelectionOptionState extends ATMState {
    @Override
    public void selectOperation(ATM atm, TransactionType tType) {
        if (tType == TransactionType.Cancel) {
            cancelTransaction(atm);
            return;
        }
        atm.setSelectedTransaction(tType);
        switch (tType) {
            case BalanceInquiry:
                atm.setAtmStatus(ATMStatus.BalanceInquiry);
                atm.setState(new BalanceInquiryState());
                break;
            case CashWithdrawal:
                atm.setAtmStatus(ATMStatus.Withdraw);
                atm.setState(new CashWithdrawalState());
                break;
            case FundsTransfer:
                atm.setAtmStatus(ATMStatus.TransferMoney);
                atm.setState(new TransferMoneyState());
                break;
            case ChangePIN:
                atm.setState(new ChangePinState());
                break;
            default:
                atm.getEventBus().publish(new ATMEvent(ATMEventType.ERROR, "Unsupported transaction"));
        }
        atm.getEventBus().publish(new ATMEvent(ATMEventType.STATE_CHANGED, "Selected: " + tType));
    }

    @Override
    public void cancelTransaction(ATM atm) {
        TransactionFactory.get(TransactionType.Cancel).execute(atm, atm.getInsertedCard(), TransactionRequest.cancel());
    }

    @Override
    public void returnCard(ATM atm) {
        atm.returnCardAndReset();
    }
}

// BalanceInquiryState -> executes balance strategy
class BalanceInquiryState extends ATMState {
    @Override
    public void displayBalance(ATM atm, ATMCard card) {
        TransactionFactory.get(TransactionType.BalanceInquiry).execute(atm, card, TransactionRequest.balance());
    }

    @Override
    public void cancelTransaction(ATM atm) { atm.setState(new SelectionOptionState()); }
}

// WithdrawalState -> executes withdrawal strategy (CoR inside)
class CashWithdrawalState extends ATMState {
    @Override
    public void cashWithdrawal(ATM atm, ATMCard card, double amount) {
        TransactionFactory.get(TransactionType.CashWithdrawal).execute(atm, card, TransactionRequest.withdraw(amount));
    }

    @Override
    public void cancelTransaction(ATM atm) { atm.setState(new SelectionOptionState()); }
}

// TransferMoneyState -> executes transfer strategy
class TransferMoneyState extends ATMState {
    @Override
    public void transferMoney(ATM atm, ATMCard card, BankAccount toAccount, double amount) {
        TransactionFactory.get(TransactionType.FundsTransfer).execute(atm, card, TransactionRequest.transfer(toAccount, amount));
    }

    @Override
    public void cancelTransaction(ATM atm) { atm.setState(new SelectionOptionState()); }
}

// ChangePinState -> executes change pin strategy
class ChangePinState extends ATMState {
    @Override
    public void changePin(ATM atm, ATMCard card, int newPin) {
        TransactionFactory.get(TransactionType.ChangePIN).execute(atm, card, TransactionRequest.changePin(newPin));
    }

    @Override
    public void cancelTransaction(ATM atm) { atm.setState(new SelectionOptionState()); }
}

// ========================= ATM (Singleton + your skeleton fields) =========================
class ATM {
    private static final ATM atmObject = new ATM();

    private ATMState currentATMState;
    private ATMStatus atmStatus;

    private int atmBalance;
    private int noOfHundredDollarBills;
    private int noOfFiftyDollarBills;
    private int noOfTenDollarBills;

    private CardReader cardReader;
    private CashDispenser cashDispenser;
    private Keypad keypad;
    private Screen screen;
    private Printer printer;

    // Session-ish fields (minimal)
    private User activeUser;
    private ATMCard insertedCard;
    private TransactionType selectedTransaction;
    private int pinTries;

    // Observer bus
    private final ATMEventBus eventBus = new ATMEventBus();

    private ATM() {
        // Factory to create hardware
        this.cardReader = HardwareFactory.createCardReader();
        this.cashDispenser = HardwareFactory.createCashDispenser();
        this.keypad = HardwareFactory.createKeypad();
        this.screen = HardwareFactory.createScreen();
        this.printer = HardwareFactory.createPrinter();

        // Default state
        this.currentATMState = new IdleState();
        this.atmStatus = ATMStatus.Idle;

        // Default observers
        eventBus.register(new ScreenObserver(screen));
        eventBus.register(new AuditObserver());
    }

    public static ATM getInstance() { return atmObject; }

    // ========================= Init / Helpers =========================
    public void initializeATM(int atmBalance, int noOfHundreds, int noOfFifties, int noOfTens) {
        this.atmBalance = atmBalance;
        this.noOfHundredDollarBills = noOfHundreds;
        this.noOfFiftyDollarBills = noOfFifties;
        this.noOfTenDollarBills = noOfTens;

        eventBus.publish(new ATMEvent(ATMEventType.INFO,
                "ATM initialized with balance $" + atmBalance + " [100x" + noOfHundreds + ", 50x" + noOfFifties + ", 10x" + noOfTens + "]"));
    }

    public void setActiveUser(User user) { this.activeUser = user; }
    public BankAccount getActiveAccount() { return activeUser.getAccount(); }

    public ATMState getCurrentATMState() { return currentATMState; }
    public void setState(ATMState s) { this.currentATMState = s; eventBus.publish(new ATMEvent(ATMEventType.STATE_CHANGED, "State -> " + s.getClass().getSimpleName())); }

    public ATMStatus getAtmStatus() { return atmStatus; }
    public void setAtmStatus(ATMStatus status) { this.atmStatus = status; }

    public ATMCard getInsertedCard() { return insertedCard; }
    public void setInsertedCard(ATMCard card) { this.insertedCard = card; }

    public void setSelectedTransaction(TransactionType t) { this.selectedTransaction = t; }

    public int getPinTries() { return pinTries; }
    public void incrementPinTries() { this.pinTries++; }
    public void resetPinTries() { this.pinTries = 0; }

    public int getAtmBalance() { return atmBalance; }
    public void decreaseAtmBalance(int amount) { this.atmBalance -= amount; }

    public CardReader getCardReader() { return cardReader; }
    public CashDispenser getCashDispenser() { return cashDispenser; }
    public Printer getPrinter() { return printer; }
    public ATMEventBus getEventBus() { return eventBus; }

    // Denomination helpers for CoR
    public int getBillCount(int denom) {
        if (denom == 100) return noOfHundredDollarBills;
        if (denom == 50) return noOfFiftyDollarBills;
        if (denom == 10) return noOfTenDollarBills;
        return 0;
    }

    public void setBillCount(int denom, int count) {
        if (denom == 100) noOfHundredDollarBills = count;
        else if (denom == 50) noOfFiftyDollarBills = count;
        else if (denom == 10) noOfTenDollarBills = count;
    }

    public void decrementBills(int denom, int used) {
        setBillCount(denom, getBillCount(denom) - used);
    }

    public void returnCardAndReset() {
        eventBus.publish(new ATMEvent(ATMEventType.INFO, "Returning card: " + (insertedCard != null ? insertedCard.getCardNumber() : "none")));
        insertedCard = null;
        selectedTransaction = null;
        resetPinTries();

        setAtmStatus(ATMStatus.Idle);
        setState(new IdleState());
    }

    public void displayCurrentState() {
        System.out.println("ATM Status: " + atmStatus + " | ATM Balance: $" + atmBalance +
                " | Bills: [100=" + noOfHundredDollarBills + ", 50=" + noOfFiftyDollarBills + ", 10=" + noOfTenDollarBills + "]");
    }
}

// ========================= DRIVER (tiny demo) =========================
public class ATMLLD_Demo {
    public static void main(String[] args) {
        Bank bank = new Bank("Sample Bank", "SB001");

        BankAccount accAlice = new SavingAccount(1001, 1200.0);
        BankAccount accBob = new CurrentAccount(1002, 8000.0);

        ATMCard cardAlice = new ATMCard("123456", "Alice", new Date(), 1111);
        ATMCard cardBob = new ATMCard("654321", "Bob", new Date(), 2222);

        User alice = new User(cardAlice, accAlice);
        User bob = new User(cardBob, accBob);

        ATM atm = ATM.getInstance();
        atm.initializeATM(20000, 100, 40, 50); // balance, 100s, 50s, 10s
        atm.displayCurrentState();

        System.out.println("\n=== Alice: Balance Inquiry ===");
        atm.setActiveUser(alice);
        atm.getCurrentATMState().insertCard(atm, alice.getCard());
        atm.getCurrentATMState().authenticatePin(atm, alice.getCard(), 1111);
        atm.getCurrentATMState().selectOperation(atm, TransactionType.BalanceInquiry);
        atm.getCurrentATMState().displayBalance(atm, alice.getCard());
        atm.getCurrentATMState().returnCard(atm);

        System.out.println("\n=== Alice: Withdraw $500 ===");
        atm.setActiveUser(alice);
        atm.getCurrentATMState().insertCard(atm, alice.getCard());
        atm.getCurrentATMState().authenticatePin(atm, alice.getCard(), 1111);
        atm.getCurrentATMState().selectOperation(atm, TransactionType.CashWithdrawal);
        atm.getCurrentATMState().cashWithdrawal(atm, alice.getCard(), 500.0);
        atm.getCurrentATMState().returnCard(atm);

        System.out.println("\n=== Bob: Transfer $1000 to Alice ===");
        atm.setActiveUser(bob);
        atm.getCurrentATMState().insertCard(atm, bob.getCard());
        atm.getCurrentATMState().authenticatePin(atm, bob.getCard(), 2222);
        atm.getCurrentATMState().selectOperation(atm, TransactionType.FundsTransfer);
        atm.getCurrentATMState().transferMoney(atm, bob.getCard(), accAlice, 1000.0);
        atm.getCurrentATMState().returnCard(atm);

        System.out.println("\n=== Bob: Change PIN to 9999 ===");
        atm.setActiveUser(bob);
        atm.getCurrentATMState().insertCard(atm, bob.getCard());
        atm.getCurrentATMState().authenticatePin(atm, bob.getCard(), 2222);
        atm.getCurrentATMState().selectOperation(atm, TransactionType.ChangePIN);
        atm.getCurrentATMState().changePin(atm, bob.getCard(), 9999);
        atm.getCurrentATMState().returnCard(atm);

        System.out.println("\n=== Final State ===");
        atm.displayCurrentState();
        System.out.println("Alice balance: $" + accAlice.getAvailableBalance());
        System.out.println("Bob balance: $" + accBob.getAvailableBalance());
    }
}
