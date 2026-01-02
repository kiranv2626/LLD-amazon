import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 30-min interview-friendly Elevator System using Building */
public class ElevatorWithBuilding {

    /* ===================== ENUMS ===================== */
    enum Direction { UP, DOWN, IDLE }
    enum ElevatorState { IDLE, UP, DOWN, MAINTENANCE, EMERGENCY }
    enum DoorState { OPEN, CLOSED }

    /* ===================== BUTTONS (your structure) ===================== */
    public static abstract class Button {
        protected boolean pressed;
        public void pressDown() { pressed = true; }
        public void reset() { pressed = false; }
        public abstract boolean isPressed();
    }

    public static class DoorButton extends Button { @Override public boolean isPressed() { return pressed; } }

    public static class HallButton extends Button {
        private final Direction direction;
        public HallButton(Direction dir) { this.direction = dir; }
        public Direction getDirection() { return direction; }
        @Override public boolean isPressed() { return pressed; }
    }

    public static class ElevatorButton extends Button {
        private final int destinationFloor;
        public ElevatorButton(int floor) { this.destinationFloor = floor; }
        public int getDestinationFloor() { return destinationFloor; }
        @Override public boolean isPressed() { return pressed; }
    }

    public static class EmergencyButton extends Button {
        public boolean getPressed() { return pressed; }
        public void setPressed(boolean val) { pressed = val; }
        @Override public boolean isPressed() { return pressed; }
    }

    /* ===================== PANELS ===================== */
    public static class ElevatorPanel {
        private final List<ElevatorButton> floorButtons = new ArrayList<>();
        private final DoorButton openButton = new DoorButton();
        private final DoorButton closeButton = new DoorButton();
        private final EmergencyButton emergencyButton = new EmergencyButton();

        public ElevatorPanel(int numFloors) {
            for (int f = 0; f < numFloors; f++) floorButtons.add(new ElevatorButton(f));
        }
        public List<ElevatorButton> getFloorButtons() { return floorButtons; }
        public DoorButton getOpenButton() { return openButton; }
        public DoorButton getCloseButton() { return closeButton; }
        public EmergencyButton getEmergencyButton() { return emergencyButton; }
    }

    public static class HallPanel {
        private final HallButton up;
        private final HallButton down;

        public HallPanel(int floorNumber, int topFloor) {
            up = (floorNumber < topFloor) ? new HallButton(Direction.UP) : null;
            down = (floorNumber > 0) ? new HallButton(Direction.DOWN) : null;
        }
        public HallButton getUpButton() { return up; }
        public HallButton getDownButton() { return down; }
    }

    /* ===================== OBSERVER + DISPLAY ===================== */
    static class CarStatus {
        final int carId, floor, loadKg;
        final Direction direction;
        final ElevatorState state;
        final DoorState doorState;
        final boolean overloaded, maintenance;

        CarStatus(int carId, int floor, Direction direction, ElevatorState state, DoorState doorState,
                  int loadKg, boolean overloaded, boolean maintenance) {
            this.carId = carId;
            this.floor = floor;
            this.direction = direction;
            this.state = state;
            this.doorState = doorState;
            this.loadKg = loadKg;
            this.overloaded = overloaded;
            this.maintenance = maintenance;
        }
    }

    interface CarObserver { void onUpdate(CarStatus s); }

    public static class Display implements CarObserver {
        @Override public void onUpdate(CarStatus s) {
            // Keep quiet by default for interview. Uncomment for debug:
            // System.out.println("Car#" + s.carId + " floor=" + s.floor + " dir=" + s.direction +
            //     " state=" + s.state + " door=" + s.doorState + " load=" + s.loadKg +
            //     (s.overloaded ? " OVERLOAD" : "") + (s.maintenance ? " MAINT" : ""));
        }
    }

    /* ===================== DOOR ===================== */
    static class Door {
        private DoorState state = DoorState.CLOSED;
        void open() { state = DoorState.OPEN; }
        void close() { state = DoorState.CLOSED; }
        boolean isOpen() { return state == DoorState.OPEN; }
        DoorState getState() { return state; }
    }

    /* ===================== MOVEMENT STRATEGY (car routing) ===================== */
    interface MovementStrategy {
        Integer nextStop(int cur, Direction dir, NavigableSet<Integer> stops, int topFloor);
    }

    /** ✅ Real-world best: LOOK (selective collective) */
    static class LookMovement implements MovementStrategy {
        public Integer nextStop(int cur, Direction dir, NavigableSet<Integer> stops, int topFloor) {
            if (stops.isEmpty()) return null;

            if (dir == Direction.UP) {
                Integer up = stops.ceiling(cur + 1);
                return (up != null) ? up : stops.floor(cur - 1); // reverse early
            }
            if (dir == Direction.DOWN) {
                Integer down = stops.floor(cur - 1);
                return (down != null) ? down : stops.ceiling(cur + 1);
            }

            // IDLE -> nearest
            Integer lo = stops.floor(cur - 1), hi = stops.ceiling(cur + 1);
            if (lo == null) return hi;
            if (hi == null) return lo;
            return (Math.abs(cur - lo) <= Math.abs(hi - cur)) ? lo : hi;
        }
    }

    /* ===================== DISPATCH STRATEGY (which car gets hall call) ===================== */
    interface DispatchStrategy {
        ElevatorCar chooseCar(List<ElevatorCar> cars, int floor, Direction dir);
    }

    /** ✅ Best practical dispatch: nearest + prefer idle + prefer moving-toward */
    static class NearestTowardDispatch implements DispatchStrategy {
        public ElevatorCar chooseCar(List<ElevatorCar> cars, int floor, Direction dir) {
            ElevatorCar best = null;
            long bestScore = Long.MAX_VALUE;

            for (ElevatorCar c : cars) {
                if (!c.isDispatchable()) continue;

                long score = Math.abs(c.getCurrentFloor() - floor) * 10;
                if (c.getState() == ElevatorState.IDLE) score -= 15;
                if (c.isMovingToward(floor, dir)) score -= 20;
                score += c.pendingStops() * 5;

                if (score < bestScore) { bestScore = score; best = c; }
            }
            return best;
        }
    }

    /* ===================== FLOOR ===================== */
    public static class Floor {
        private final int floorNumber;
        private final HallPanel panel;
        private final Display display;

        public Floor(int floorNumber, int topFloor) {
            this.floorNumber = floorNumber;
            this.panel = new HallPanel(floorNumber, topFloor);
            this.display = new Display();
        }
        public int getFloorNumber() { return floorNumber; }
        public HallPanel getPanel(int index) { return panel; } // single panel
        public Display getDisplay() { return display; }
    }

    /* ===================== ELEVATOR CAR (1 thread per car) ===================== */
    public static class ElevatorCar implements Runnable {
        private final int id;
        private final int topFloor;

        private volatile int currentFloor = 0;
        private volatile ElevatorState state = ElevatorState.IDLE;
        private volatile Direction direction = Direction.IDLE;

        private final Door door = new Door();
        private final ElevatorPanel panel;

        private final NavigableSet<Integer> stops = new TreeSet<>();
        private final Object monitor = new Object();
        private volatile boolean running = true;

        // safety
        private int loadKg = 0;
        private boolean overloaded = false;
        private boolean maintenance = false;

        // observer
        private final List<CarObserver> observers = new CopyOnWriteArrayList<>();

        // strategy
        private volatile MovementStrategy movement;

        // config
        private static final int MAX_LOAD = 680;
        private static final int MOVE_MS = 150;
        private static final int DOOR_MS = 1500;

        public ElevatorCar(int id, int numFloors, MovementStrategy movement) {
            this.id = id;
            this.topFloor = numFloors - 1;
            this.panel = new ElevatorPanel(numFloors);
            this.movement = movement;
        }

        public int getId() { return id; }
        public int getCurrentFloor() { return currentFloor; }
        public ElevatorState getState() { return state; }
        public ElevatorPanel getPanel() { return panel; }
        public boolean isDispatchable() { return state != ElevatorState.MAINTENANCE && state != ElevatorState.EMERGENCY; }

        public void addObserver(CarObserver o) { observers.add(o); }

        private void notifyObservers() {
            CarStatus s = new CarStatus(id, currentFloor, direction, state, door.getState(),
                    loadKg, overloaded, maintenance);
            for (CarObserver o : observers) o.onUpdate(s);
        }

        public boolean isMovingToward(int reqFloor, Direction reqDir) {
            if (state == ElevatorState.UP && reqDir == Direction.UP) return currentFloor <= reqFloor;
            if (state == ElevatorState.DOWN && reqDir == Direction.DOWN) return currentFloor >= reqFloor;
            return false;
        }

        public int pendingStops() {
            synchronized (monitor) { return stops.size(); }
        }

        public void registerRequest(int floor) {
            synchronized (monitor) {
                if (maintenance || state == ElevatorState.EMERGENCY) return;
                if (floor < 0 || floor > topFloor) return;
                stops.add(floor);
                monitor.notify(); // wake car thread
            }
        }

        public void enterMaintenance() {
            synchronized (monitor) {
                maintenance = true;
                state = ElevatorState.MAINTENANCE;
                stops.clear();
                door.close();
                notifyObservers();
                monitor.notify();
            }
        }

        public void exitMaintenance() {
            synchronized (monitor) {
                maintenance = false;
                state = ElevatorState.IDLE;
                direction = Direction.IDLE;
                notifyObservers();
                monitor.notify();
            }
        }

        public void emergencyStop() {
            synchronized (monitor) {
                if (maintenance) return;
                state = ElevatorState.EMERGENCY;
                stops.clear();
                door.close();
                System.out.println("ALERT: Emergency stop car " + id);
                notifyObservers();
                monitor.notify();
            }
        }

        public void resetEmergency() {
            synchronized (monitor) {
                if (state == ElevatorState.EMERGENCY) {
                    state = ElevatorState.IDLE;
                    direction = Direction.IDLE;
                    notifyObservers();
                    monitor.notify();
                }
            }
        }

        public void setLoadKg(int kg) {
            synchronized (monitor) {
                loadKg = Math.max(0, kg);
                overloaded = loadKg > MAX_LOAD;
                if (overloaded) System.out.println("ALERT: Overload car " + id);
                notifyObservers();
                monitor.notify();
            }
        }

        @Override
        public void run() {
            while (running) {
                int target;

                // 1) WAIT until car is allowed to move
                synchronized (monitor) {
                    while (running && (maintenance || state == ElevatorState.EMERGENCY || overloaded || stops.isEmpty())) {
                        if (maintenance) state = ElevatorState.MAINTENANCE;
                        else if (state == ElevatorState.EMERGENCY) state = ElevatorState.EMERGENCY;
                        else state = ElevatorState.IDLE;

                        if (state == ElevatorState.IDLE) direction = Direction.IDLE;
                        notifyObservers();

                        try { monitor.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (!running) return;

                    Integer t = movement.nextStop(currentFloor, direction, stops, topFloor);
                    if (t == null) continue;
                    target = t;

                    if (target > currentFloor) { direction = Direction.UP; state = ElevatorState.UP; }
                    else if (target < currentFloor) { direction = Direction.DOWN; state = ElevatorState.DOWN; }
                    else { direction = Direction.IDLE; state = ElevatorState.IDLE; }
                }

                // 2) MOVE ONE FLOOR (outside lock)
                if (target > currentFloor) currentFloor++;
                else if (target < currentFloor) currentFloor--;
                notifyObservers();

                // 3) SERVICE STOP
                synchronized (monitor) {
                    if (stops.contains(currentFloor)) {
                        stops.remove(currentFloor);
                        state = ElevatorState.IDLE;
                        direction = Direction.IDLE;

                        // door open only in IDLE
                        door.open();
                        notifyObservers();
                    }
                }

                // 4) DOOR AUTO CLOSE (+ extend while OPEN held)
                if (door.isOpen()) {
                    long end = System.currentTimeMillis() + DOOR_MS;
                    while (System.currentTimeMillis() < end) {
                        if (panel.getOpenButton().isPressed()) end = System.currentTimeMillis() + DOOR_MS;
                        try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                    }
                    door.close();
                    notifyObservers();
                }

                try { Thread.sleep(MOVE_MS); } catch (InterruptedException e) { return; }
            }
        }

        public void shutdown() {
            running = false;
            synchronized (monitor) { monitor.notify(); }
        }
    }

    /* ===================== BUILDING ===================== */
    public static class Building {
        private final List<Floor> floors;
        private final List<ElevatorCar> cars;

        public Building(int numFloors, int numCars, int numPanels, int numDisplaysPerFloor, MovementStrategy movement) {
            floors = new ArrayList<>();
            for (int f = 0; f < numFloors; f++) floors.add(new Floor(f, numFloors - 1));

            cars = new ArrayList<>();
            for (int i = 0; i < numCars; i++) {
                ElevatorCar car = new ElevatorCar(i, numFloors, movement);
                // Attach building displays if you want (simple: one display observer per car)
                car.addObserver(new Display());
                cars.add(car);
            }
        }

        public List<Floor> getFloors() { return floors; }
        public List<ElevatorCar> getCars() { return cars; }
    }

    /* ===================== ELEVATOR SYSTEM (Singleton Controller) ===================== */
    public static class ElevatorSystem {
        private static volatile ElevatorSystem system;
        private final Building building;
        private final DispatchStrategy dispatch;

        // Hall-call queue + monitor for dispatcher thread
        private final Queue<HallCall> hallCalls = new ArrayDeque<>();
        private final Object dispatcherMonitor = new Object();
        private volatile boolean running = true;
        private final Thread dispatcherThread;

        static class HallCall {
            final int floor;
            final Direction dir;
            HallCall(int floor, Direction dir) { this.floor = floor; this.dir = dir; }
        }

        private ElevatorSystem(int floors, int cars) {
            // Build any building config (easy to replace later)
            this.building = new Building(floors, cars, 1, 1, new LookMovement());
            this.dispatch = new NearestTowardDispatch();

            // start car threads
            for (ElevatorCar c : building.getCars()) {
                new Thread(c, "Car-" + c.getId()).start();
            }

            // start dispatcher thread
            dispatcherThread = new Thread(this::dispatcher, "Dispatcher");
            dispatcherThread.start();
        }

        public static ElevatorSystem getInstance(int floors, int cars) {
            if (system == null) {
                synchronized (ElevatorSystem.class) {
                    if (system == null) system = new ElevatorSystem(floors, cars);
                }
            }
            return system;
        }

        public List<ElevatorCar> getCars() { return building.getCars(); }
        public Building getBuilding() { return building; }

        public void callElevator(int floorNum, Direction dir) {
            synchronized (dispatcherMonitor) {
                hallCalls.offer(new HallCall(floorNum, dir));
                dispatcherMonitor.notify(); // wake dispatcher
            }
        }

        /** Dispatcher thread loop: pick hall call, choose best car, assign stop */
        public void dispatcher() {
            while (running) {
                HallCall call;
                synchronized (dispatcherMonitor) {
                    while (running && hallCalls.isEmpty()) {
                        try { dispatcherMonitor.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (!running) return;
                    call = hallCalls.poll();
                }

                ElevatorCar chosen = dispatch.chooseCar(building.getCars(), call.floor, call.dir);
                if (chosen != null) chosen.registerRequest(call.floor);
                else synchronized (dispatcherMonitor) { hallCalls.offer(call); } // retry later
            }
        }

        public void monitoring() {
            // Optional: periodically print statuses / metrics (wait time, etc.)
        }

        public void shutdown() {ā
            running = false;
            synchronized (dispatcherMonitor) { dispatcherMonitor.notify(); }
            for (ElevatorCar c : building.getCars()) c.shutdown();
        }
    }

    /* ===================== DEMO ===================== */
    public static void main(String[] args) throws Exception {
        ElevatorSystem sys = ElevatorSystem.getInstance(16, 3); // floors 0..15, 3 cars

        sys.callElevator(3, Direction.UP);
        sys.callElevator(10, Direction.DOWN);

        // safety
        sys.getCars().get(0).setLoadKg(700);
        Thread.sleep(500);
        sys.getCars().get(0).setLoadKg(600);

        sys.getCars().get(1).emergencyStop();
        Thread.sleep(500);
        sys.getCars().get(1).resetEmergency();

        sys.getCars().get(2).enterMaintenance();
        Thread.sleep(500);
        sys.getCars().get(2).exitMaintenance();

        Thread.sleep(3000);
        sys.shutdown();
    }
}
