// Single-file Parking Lot System demonstrating:
// Singleton, Strategy, Factory Method, Observer, and Proxy patterns.

import java.sql.Timestamp;         // For time stamps on tickets and payments
import java.util.*;                // For collections and utilities

// ========================== ENUMS ===============================

// Enum to represent payment status lifecycle
enum PaymentStatus {
    UNPAID,                        // Payment not started
    PENDING,                       // Payment in progress
    COMPLETED,                     // Payment successful
    FAILED,                        // Payment failed
    REFUNDED                       // Payment refunded
}

// Enum to represent ticket status lifecycle
enum TicketStatus {
    UNPAID,                        // Ticket created but not paid
    PAID,                          // Ticket fully paid
    VALIDATED,                     // Ticket validated at exit (optional)
    ISSUED,                        // Ticket issued at entry
    INUSE                          // Ticket currently active
}

// ========================== OBSERVER PATTERN ====================

// Observer interface for display boards or other listeners
interface Observer {
    void update(Map<String, Integer> freeSpotsByType, boolean isLotFull);
}

// Subject interface implemented by ParkingLot
interface Subject {
    void registerObserver(Observer o);
    void removeObserver(Observer o);
    void notifyObservers();
}

// ========================== STRATEGY PATTERN ====================

// Strategy interface to calculate parking price
interface PricingStrategy {
    int calculatePrice(ParkingTicket ticket);
}

// Default pricing: base hour + extra hours + multipliers
class DefaultPricingStrategy implements PricingStrategy {

    private final int firstHourRate = 5;       // Base rate for first hour
    private final int additionalHourRate = 3;  // Rate for each additional hour

    @Override
    public int calculatePrice(ParkingTicket ticket) {
        // Get entry and exit times
        Timestamp in = ticket.getEntryTime();
        Timestamp out = ticket.getExitTime();

        // If either is null, we cannot compute; return 0
        if (in == null || out == null) {
            return 0;
        }

        // Compute duration in hours
        long millis = out.getTime() - in.getTime();
        double hours = millis / (1000.0 * 60.0 * 60.0);
        if (hours <= 0) {
            hours = 1.0; // Minimum 1 hour billing
        }

        // Base calculation
        double base;
        if (hours <= 1) {
            base = firstHourRate;
        } else {
            base = firstHourRate + Math.ceil(hours - 1.0) * additionalHourRate;
        }

        // Apply multipliers from vehicle and spot
        Vehicle vehicle = ticket.getVehicle();
        ParkingSpot spot = ticket.getParkingSpot();

        double vehicleMultiplier = (vehicle != null) ? vehicle.getPriceMultiplier() : 1.0;
        double spotMultiplier = (spot != null) ? spot.getPriceMultiplier() : 1.0;

        return (int) Math.round(base * vehicleMultiplier * spotMultiplier);
    }
}

// Weekend pricing: uses DefaultPricingStrategy then adds weekend premium
class WeekendPricingStrategy implements PricingStrategy {

    private final PricingStrategy baseStrategy = new DefaultPricingStrategy(); // Reuse base

    @Override
    @SuppressWarnings("deprecation") // Using getDay() from Timestamp for simplicity
    public int calculatePrice(ParkingTicket ticket) {
        // First compute base amount
        int amount = baseStrategy.calculatePrice(ticket);

        Timestamp in = ticket.getEntryTime();
        if (in != null) {
            int day = in.getDay(); // 0 = Sunday, 6 = Saturday
            if (day == 0 || day == 6) {
                // Add 20% weekend premium
                amount = (int) Math.round(amount * 1.2);
            }
        }
        return amount;
    }
}

// Night pricing: discount if both entry and exit are during night hours
class NightPricingStrategy implements PricingStrategy {

    private final PricingStrategy baseStrategy = new DefaultPricingStrategy(); // Reuse base

    @Override
    @SuppressWarnings("deprecation") // Using getHours() for simplicity
    public int calculatePrice(ParkingTicket ticket) {
        int amount = baseStrategy.calculatePrice(ticket);

        Timestamp in = ticket.getEntryTime();
        Timestamp out = ticket.getExitTime();

        if (in != null && out != null) {
            int startHour = in.getHours(); // 0-23
            int endHour = out.getHours();  // 0-23

            // Simple logic: if both in and out are between 10pm-6am → night discount
            boolean night =
                    (startHour >= 22 || startHour < 6) &&
                    (endHour   >= 22 || endHour   < 6);

            if (night) {
                // 20% discount
                amount = (int) Math.round(amount * 0.8);
            }
        }
        return amount;
    }
}

// EV-only pricing: discount if spot is ElectricSpot
class EVOnlyPricingStrategy implements PricingStrategy {

    private final PricingStrategy baseStrategy = new DefaultPricingStrategy(); // Reuse base

    @Override
    public int calculatePrice(ParkingTicket ticket) {
        int amount = baseStrategy.calculatePrice(ticket);

        ParkingSpot spot = ticket.getParkingSpot();
        // If parked in an EV spot, give discount
        if (spot instanceof ElectricSpot) {
            amount = (int) Math.round(amount * 0.7); // 30% discount
        }

        return amount;
    }
}

// ========================== PROXY PATTERN =======================

// Payment gateway interface
interface PaymentGateway {
    boolean charge(int amount);
}

// Real payment gateway implementation (simulated)
class RealPaymentGateway implements PaymentGateway {
    @Override
    public boolean charge(int amount) {
        System.out.println("[RealPaymentGateway] Charging $" + amount + " with external provider...");
        return true; // Always succeeds in this demo
    }
}

// Proxy that wraps the real payment gateway
class PaymentGatewayProxy implements PaymentGateway {
    private final PaymentGateway realGateway;

    public PaymentGatewayProxy(PaymentGateway realGateway) {
        this.realGateway = realGateway;
    }

    @Override
    public boolean charge(int amount) {
        if (amount <= 0) {
            System.out.println("[PaymentGatewayProxy] Invalid amount: $" + amount);
            return false;
        }
        System.out.println("[PaymentGatewayProxy] Valid amount. Forwarding to real gateway...");
        return realGateway.charge(amount);
    }
}

// ========================== BASIC PERSON / ADDRESS (OPTIONAL) ====

// Simple Address class
class Address {
    private String street;
    private String city;
    private String state;
    private String zipCode;
    private String country;

    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
}

// Simple Person class
class Person {
    private String name;
    private String email;
    private String phoneNumber;
    private Address address;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
}

// ========================== VEHICLE HIERARCHY ====================

// Abstract vehicle base class
abstract class Vehicle {
    private String licensePlate;
    private String color;
    private ParkingTicket ticket;

    public abstract boolean canParkIn(ParkingSpot spot);

    public double getPriceMultiplier() {
        return 1.0;
    }

    public abstract boolean assignParkingTicket();

    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public ParkingTicket getTicket() { return ticket; }
    public void setTicket(ParkingTicket ticket) { this.ticket = ticket; }
}

// Car type
class Car extends Vehicle {
    @Override
    public boolean canParkIn(ParkingSpot spot) {
        return spot instanceof CompactSpot ||
               spot instanceof LargeSpot ||
               spot instanceof AccessibleSpot ||
               spot instanceof ElectricSpot;
    }

    @Override
    public double getPriceMultiplier() {
        return 1.0;
    }

    @Override
    public boolean assignParkingTicket() {
        return true;
    }
}

// Truck type
class Truck extends Vehicle {
    @Override
    public boolean canParkIn(ParkingSpot spot) {
        return spot instanceof LargeSpot || spot instanceof AccessibleSpot;
    }

    @Override
    public double getPriceMultiplier() {
        return 1.5;
    }

    @Override
    public boolean assignParkingTicket() {
        return true;
    }
}

// Van type
class Van extends Vehicle {
    @Override
    public boolean canParkIn(ParkingSpot spot) {
        return spot instanceof LargeSpot ||
               spot instanceof AccessibleSpot ||
               spot instanceof CompactSpot;
    }

    @Override
    public double getPriceMultiplier() {
        return 1.3;
    }

    @Override
    public boolean assignParkingTicket() {
        return true;
    }
}

// Motorcycle type
class MotorCycle extends Vehicle {
    @Override
    public boolean canParkIn(ParkingSpot spot) {
        return spot instanceof MotorCycleSpot ||
               spot instanceof CompactSpot;
    }

    @Override
    public double getPriceMultiplier() {
        return 0.7;
    }

    @Override
    public boolean assignParkingTicket() {
        return true;
    }
}

// ======================= PARKING SPOT HIERARCHY ==================

// Abstract base for parking spots
abstract class ParkingSpot {
    private int id;
    private boolean free = true;
    private Vehicle vehicle;

    private Map<Integer, Integer> distanceFromEntrance = new HashMap<>();

    public boolean assignVehicle(Vehicle v) {
        if (!free) return false;
        if (!v.canParkIn(this)) return false;
        this.vehicle = v;
        this.free = false;
        return true;
    }

    public boolean removeVehicle() {
        this.vehicle = null;
        this.free = true;
        return true;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public boolean isFree() { return free; }
    public void setFree(boolean free) { this.free = free; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public void setDistanceForEntrance(int entranceId, int distance) {
        distanceFromEntrance.put(entranceId, distance);
    }

    public int getDistanceFromEntrance(int entranceId) {
        return distanceFromEntrance.getOrDefault(entranceId, Integer.MAX_VALUE);
    }

    public double getPriceMultiplier() {
        return 1.0;
    }
}

// Concrete spot types
class CompactSpot extends ParkingSpot {
    public CompactSpot(int id) {
        setId(id);
    }
}

class LargeSpot extends ParkingSpot {
    public LargeSpot(int id) {
        setId(id);
    }
}

class AccessibleSpot extends ParkingSpot {
    public AccessibleSpot(int id) {
        setId(id);
    }
}

class MotorCycleSpot extends ParkingSpot {
    public MotorCycleSpot(int id) {
        setId(id);
    }
}

class ElectricSpot extends ParkingSpot {
    public ElectricSpot(int id) {
        setId(id);
    }

    @Override
    public double getPriceMultiplier() {
        return 1.2;
    }
}

// ========================== TICKET ===============================

class ParkingTicket {
    private final String id;
    private Timestamp entryTime;
    private Timestamp exitTime;
    private TicketStatus status;
    private int amount;
    private Vehicle vehicle;
    private ParkingSpot parkingSpot;

    public ParkingTicket() {
        this.id = UUID.randomUUID().toString();
        this.status = TicketStatus.ISSUED;
    }

    public String getId() { return id; }

    public Timestamp getEntryTime() { return entryTime; }
    public void setEntryTime(Timestamp entryTime) { this.entryTime = entryTime; }

    public Timestamp getExitTime() { return exitTime; }
    public void setExitTime(Timestamp exitTime) { this.exitTime = exitTime; }

    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public ParkingSpot getParkingSpot() { return parkingSpot; }
    public void setParkingSpot(ParkingSpot parkingSpot) { this.parkingSpot = parkingSpot; }
}

// ========================== PAYMENT ==============================

abstract class Payment {
    private int amount;
    private PaymentStatus status;
    private Timestamp paidAt;

    public abstract boolean initiateTransaction(ParkingTicket ticket);

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public Timestamp getPaidAt() { return paidAt; }
    public void setPaidAt(Timestamp paidAt) { this.paidAt = paidAt; }
}

class CardPayment extends Payment {
    private final PaymentGateway gatewayProxy;

    public CardPayment() {
        this.gatewayProxy = new PaymentGatewayProxy(new RealPaymentGateway());
    }

    @Override
    public boolean initiateTransaction(ParkingTicket ticket) {
        setAmount(ticket.getAmount());
        setStatus(PaymentStatus.PENDING);

        System.out.println("[CardPayment] Charging $" + getAmount() + " for ticket " + ticket.getId());
        boolean success = gatewayProxy.charge(getAmount());
        setPaidAt(new Timestamp(System.currentTimeMillis()));
        setStatus(success ? PaymentStatus.COMPLETED : PaymentStatus.FAILED);
        System.out.println("[CardPayment] Status: " + getStatus());
        return success;
    }
}

class CashPayment extends Payment {
    @Override
    public boolean initiateTransaction(ParkingTicket ticket) {
        setAmount(ticket.getAmount());
        setPaidAt(new Timestamp(System.currentTimeMillis()));
        setStatus(PaymentStatus.COMPLETED);
        System.out.println("[CashPayment] Received $" + getAmount() + " for ticket " + ticket.getId());
        return true;
    }
}

// ========================== DISPLAY BOARD (OBSERVER) =============

class DisplayBoard implements Observer {
    private final int id;
    private final String locationName;
    private Map<String, Integer> lastFreeCounts = new HashMap<>();
    private boolean lastIsFull = false;

    public DisplayBoard(int id, String locationName) {
        this.id = id;
        this.locationName = locationName;
    }

    @Override
    public void update(Map<String, Integer> freeSpotsByType, boolean isLotFull) {
        this.lastFreeCounts = new HashMap<>(freeSpotsByType);
        this.lastIsFull = isLotFull;
        show();
    }

    public void show() {
        System.out.println("=== DisplayBoard #" + id + " @ " + locationName + " ===");
        if (lastIsFull) {
            System.out.println("PARKING LOT FULL");
        } else {
            System.out.println("Available spots by type:");
            for (Map.Entry<String, Integer> e : lastFreeCounts.entrySet()) {
                System.out.println("  " + e.getKey() + ": " + e.getValue());
            }
        }
        System.out.println("==========================================");
    }
}

// ========================== PARKING FLOOR =========================

class ParkingFloor {
    private final int floorNumber;
    private final DisplayBoard displayBoard;
    private final List<ParkingSpot> spots;

    public ParkingFloor(int floorNumber, DisplayBoard displayBoard) {
        this.floorNumber = floorNumber;
        this.displayBoard = displayBoard;
        this.spots = new ArrayList<>();
    }

    public int getFloorNumber() { return floorNumber; }
    public DisplayBoard getDisplayBoard() { return displayBoard; }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
    }

    public List<ParkingSpot> getSpots() {
        return spots;
    }
}

// ========================== ENTRY & EXIT GATES ====================

class Entry {
    private static int idCounter = 1;
    private final int id;

    public Entry() {
        this.id = idCounter++;
        ParkingLot.getInstance().registerEntry(this);
    }

    public int getId() { return id; }

    public ParkingTicket issueTicket(Vehicle vehicle) {
        ParkingLot lot = ParkingLot.getInstance();

        if (lot.isFull()) {
            System.out.println("[Entry " + id + "] Lot full. Cannot issue ticket.");
            return null;
        }

        ParkingSpot spot = lot.assignSpot(this, vehicle);
        if (spot == null) {
            System.out.println("[Entry " + id + "] No suitable spot found.");
            return null;
        }

        ParkingTicket ticket = new ParkingTicket();
        ticket.setVehicle(vehicle);
        ticket.setParkingSpot(spot);
        ticket.setEntryTime(new Timestamp(System.currentTimeMillis()));
        ticket.setStatus(TicketStatus.ISSUED);
        vehicle.setTicket(ticket);

        lot.registerTicket(ticket);

        System.out.println("[Entry " + id + "] Ticket " + ticket.getId() +
                " issued for " + vehicle.getLicensePlate() +
                " at spot " + spot.getId() + " (" + spot.getClass().getSimpleName() + ")");
        return ticket;
    }
}

class Exit {
    private static int idCounter = 1;
    private final int id;

    public Exit() {
        this.id = idCounter++;
        ParkingLot.getInstance().registerExit(this);
    }

    public int getId() { return id; }

    public void processExit(String ticketId, Payment paymentMethod) {
        ParkingLot lot = ParkingLot.getInstance();

        ParkingTicket ticket = lot.getTicket(ticketId);
        if (ticket == null) {
            System.out.println("[Exit " + id + "] Invalid ticket ID: " + ticketId);
            return;
        }

        ticket.setExitTime(new Timestamp(System.currentTimeMillis()));

        int amount = lot.getPricingStrategy().calculatePrice(ticket);
        ticket.setAmount(amount);
        ticket.setStatus(TicketStatus.UNPAID);

        boolean paid = paymentMethod.initiateTransaction(ticket);

        if (paid) {
            ticket.setStatus(TicketStatus.PAID);
            lot.releaseSpot(ticket.getParkingSpot());
            System.out.println("[Exit " + id + "] Payment done. Gate opened for ticket " + ticketId);
        } else {
            System.out.println("[Exit " + id + "] Payment failed for ticket " + ticketId);
        }
    }
}

// ========================== FACTORY METHOD PATTERN ===============

class VehicleFactory {
    public static Vehicle createVehicle(String type) {
        String t = type.toLowerCase();
        switch (t) {
            case "car":
                return new Car();
            case "truck":
                return new Truck();
            case "van":
                return new Van();
            case "motorcycle":
                return new MotorCycle();
            default:
                throw new IllegalArgumentException("Unknown vehicle type: " + type);
        }
    }
}

class ParkingSpotFactory {
    public static ParkingSpot createSpot(String type, int id) {
        String t = type.toLowerCase();
        switch (t) {
            case "compact":
                return new CompactSpot(id);
            case "large":
                return new LargeSpot(id);
            case "accessible":
                return new AccessibleSpot(id);
            case "motorcycle":
                return new MotorCycleSpot(id);
            case "electric":
                return new ElectricSpot(id);
            default:
                throw new IllegalArgumentException("Unknown spot type: " + type);
        }
    }
}

// ========================== SINGLETON: PARKING LOT ===============

class ParkingLot implements Subject {
    private static volatile ParkingLot instance;

    private final String name;
    private final int maxCapacity;

    private int occupiedCount;

    private final Map<Integer, ParkingFloor> floors;
    private final Map<Integer, Entry> entries;
    private final Map<Integer, Exit> exits;
    private final Map<String, ParkingTicket> tickets;

    private final Set<ParkingSpot> availableSpots;
    private final Set<ParkingSpot> reservedSpots;

    private final Map<Integer, PriorityQueue<ParkingSpot>> entranceHeaps;

    private PricingStrategy pricingStrategy;

    private final List<Observer> observers;

    private ParkingLot(String name, int maxCapacity) {
        this.name = name;
        this.maxCapacity = maxCapacity;
        this.occupiedCount = 0;

        this.floors = new HashMap<>();
        this.entries = new HashMap<>();
        this.exits = new HashMap<>();
        this.tickets = new HashMap<>();

        this.availableSpots = new HashSet<>();
        this.reservedSpots = new HashSet<>();

        this.entranceHeaps = new HashMap<>();

        this.pricingStrategy = new DefaultPricingStrategy(); // can be changed at runtime

        this.observers = new ArrayList<>();
    }

    public static ParkingLot getInstance() {
        if (instance == null) {
            synchronized (ParkingLot.class) {
                if (instance == null) {
                    instance = new ParkingLot("MainParkingLot", 40000);
                }
            }
        }
        return instance;
    }

    public String getName() { return name; }

    public PricingStrategy getPricingStrategy() { return pricingStrategy; }
    public void setPricingStrategy(PricingStrategy pricingStrategy) { this.pricingStrategy = pricingStrategy; }

    public void registerFloor(ParkingFloor floor) {
        floors.put(floor.getFloorNumber(), floor);
        registerObserver(floor.getDisplayBoard());
        for (ParkingSpot spot : floor.getSpots()) {
            availableSpots.add(spot);
        }
        rebuildAllEntranceHeaps();
        notifyObservers();
    }

    public void registerEntry(Entry entry) {
        entries.put(entry.getId(), entry);
        PriorityQueue<ParkingSpot> heap = new PriorityQueue<>(
                Comparator.comparingInt(s -> s.getDistanceFromEntrance(entry.getId()))
        );
        heap.addAll(availableSpots);
        entranceHeaps.put(entry.getId(), heap);
    }

    public void registerExit(Exit exit) {
        exits.put(exit.getId(), exit);
    }

    public void registerTicket(ParkingTicket ticket) {
        tickets.put(ticket.getId(), ticket);
    }

    public ParkingTicket getTicket(String ticketId) {
        return tickets.get(ticketId);
    }

    public void addParkingSpot(ParkingSpot spot, int floorNumber) {
        ParkingFloor floor = floors.get(floorNumber);
        if (floor != null) {
            floor.addSpot(spot);
        }
        availableSpots.add(spot);
        rebuildAllEntranceHeaps();
        notifyObservers();
    }

    public boolean isFull() {
        return occupiedCount >= maxCapacity;
    }

    public synchronized ParkingSpot assignSpot(Entry entry, Vehicle vehicle) {
        if (isFull()) return null;

        PriorityQueue<ParkingSpot> heap = entranceHeaps.get(entry.getId());
        if (heap == null) return null;

        ParkingSpot chosen = null;
        List<ParkingSpot> skipped = new ArrayList<>();

        while (!heap.isEmpty() && chosen == null) {
            ParkingSpot candidate = heap.poll();
            if (!candidate.isFree() || !availableSpots.contains(candidate)) {
                continue;
            }
            if (!vehicle.canParkIn(candidate)) {
                skipped.add(candidate);
                continue;
            }
            chosen = candidate;
        }

        heap.addAll(skipped);

        if (chosen == null) return null;

        chosen.assignVehicle(vehicle);
        availableSpots.remove(chosen);
        reservedSpots.add(chosen);
        occupiedCount++;

        for (Map.Entry<Integer, PriorityQueue<ParkingSpot>> e : entranceHeaps.entrySet()) {
            if (e.getKey() == entry.getId()) continue;
            e.getValue().remove(chosen);
        }

        notifyObservers();
        return chosen;
    }

    public synchronized void releaseSpot(ParkingSpot spot) {
        if (spot == null) return;
        spot.removeVehicle();
        reservedSpots.remove(spot);
        availableSpots.add(spot);
        occupiedCount = Math.max(0, occupiedCount - 1);

        for (PriorityQueue<ParkingSpot> heap : entranceHeaps.values()) {
            heap.add(spot);
        }

        notifyObservers();
    }

    private void rebuildAllEntranceHeaps() {
        for (Integer entryId : entries.keySet()) {
            PriorityQueue<ParkingSpot> heap = new PriorityQueue<>(
                    Comparator.comparingInt(s -> s.getDistanceFromEntrance(entryId))
            );
            heap.addAll(availableSpots);
            entranceHeaps.put(entryId, heap);
        }
    }

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    @Override
    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    @Override
    public void notifyObservers() {
        Map<String, Integer> counts = new HashMap<>();
        for (ParkingSpot spot : availableSpots) {
            String key = spot.getClass().getSimpleName();
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        boolean full = isFull();
        for (Observer o : observers) {
            o.update(counts, full);
        }
    }
}

// ========================== PUBLIC MAIN CLASS =====================

public class ParkingLotMain {
    public static void main(String[] args) throws InterruptedException {
        ParkingLot lot = ParkingLot.getInstance();

        // Choose a pricing strategy here:
        // lot.setPricingStrategy(new DefaultPricingStrategy());
        // lot.setPricingStrategy(new WeekendPricingStrategy());
        // lot.setPricingStrategy(new NightPricingStrategy());
        lot.setPricingStrategy(new EVOnlyPricingStrategy()); // Example: EV-only strategy

        // Create display boards
        DisplayBoard floor1Board = new DisplayBoard(1, "Floor 1");
        DisplayBoard floor2Board = new DisplayBoard(2, "Floor 2");

        // Create floors
        ParkingFloor floor1 = new ParkingFloor(1, floor1Board);
        ParkingFloor floor2 = new ParkingFloor(2, floor2Board);

        // Create spots on floor 1
        ParkingSpot f1s1 = ParkingSpotFactory.createSpot("compact", 101);
        ParkingSpot f1s2 = ParkingSpotFactory.createSpot("large", 102);
        ParkingSpot f1s3 = ParkingSpotFactory.createSpot("motorcycle", 103);

        f1s1.setDistanceForEntrance(1, 5);
        f1s1.setDistanceForEntrance(2, 10);
        f1s2.setDistanceForEntrance(1, 15);
        f1s2.setDistanceForEntrance(2, 3);
        f1s3.setDistanceForEntrance(1, 2);
        f1s3.setDistanceForEntrance(2, 8);

        floor1.addSpot(f1s1);
        floor1.addSpot(f1s2);
        floor1.addSpot(f1s3);

        // Create spots on floor 2
        ParkingSpot f2s1 = ParkingSpotFactory.createSpot("accessible", 201);
        ParkingSpot f2s2 = ParkingSpotFactory.createSpot("electric", 202);

        f2s1.setDistanceForEntrance(1, 7);
        f2s1.setDistanceForEntrance(2, 12);
        f2s2.setDistanceForEntrance(1, 20);
        f2s2.setDistanceForEntrance(2, 1);

        floor2.addSpot(f2s1);
        floor2.addSpot(f2s2);

        // Register floors with lot
        lot.registerFloor(floor1);
        lot.registerFloor(floor2);

        // Create entries and exits
        Entry entry1 = new Entry();
        Entry entry2 = new Entry();
        Exit exit1 = new Exit();
        Exit exit2 = new Exit();

        // Create vehicles via factory
        Vehicle car = VehicleFactory.createVehicle("car");
        car.setLicensePlate("CAR-123");
        car.setColor("Green");

        Vehicle truck = VehicleFactory.createVehicle("truck");
        truck.setLicensePlate("TRK-999");
        truck.setColor("Blue");

        // Car enters via entry1
        ParkingTicket carTicket = entry1.issueTicket(car);
        if (carTicket == null) {
            System.out.println("Car could not enter.");
            return;
        }

        // Truck enters via entry2
        ParkingTicket truckTicket = entry2.issueTicket(truck);
        if (truckTicket == null) {
            System.out.println("Truck could not enter.");
            return;
        }

        // Simulate some time passing
        Thread.sleep(1000);

        // Car exits via exit1 with card payment
        Payment carPayment = new CardPayment();
        exit1.processExit(carTicket.getId(), carPayment);

        // Simulate some more time
        Thread.sleep(1000);

        // Truck exits via exit2 with cash payment
        Payment truckPayment = new CashPayment();
        exit2.processExit(truckTicket.getId(), truckPayment);

        System.out.println("Demo completed.");
    }
}
