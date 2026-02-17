import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class MeetingScheduler30Min {

    // ================= Enums =================
    enum RSVPStatus { PENDING, ACCEPTED, DECLINED, REMOVED, CANCELED }
    enum CalendarEntryStatus { TENTATIVE, CONFIRMED }

    // Observer/Event Bus enums
    enum MeetingEventType {
        MEETING_CREATED,
        MEETING_UPDATED,
        MEETING_CANCELED,
        PARTICIPANT_ADDED,
        PARTICIPANT_REMOVED,
        RSVP_CHANGED
    }

    // ================= Value Object =================
    static final class Interval {
        final Instant start;
        final Instant end;

        Interval(Instant start, Instant end) {
            if (start == null || end == null || !start.isBefore(end)) {
                throw new IllegalArgumentException("Invalid interval");
            }
            this.start = start;
            this.end = end;
        }

        boolean overlaps(Interval other) {
            return this.start.isBefore(other.end) && this.end.isAfter(other.start);
        }

        @Override public String toString() { return "[" + start + " - " + end + "]"; }
    }

    // ================= Entities =================
    static final class MeetingRoom {
        final String id;
        final String name;
        final int capacity;

        // interview-friendly: O(n) scan; can be TreeSet for O(log n) later
        private final List<Interval> booked = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();

        MeetingRoom(String id, String name, int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            this.capacity = capacity;
        }

        boolean isAvailable(Interval interval) {
            for (Interval b : booked) if (b.overlaps(interval)) return false;
            return true;
        }

        void book(Interval interval) { booked.add(interval); }

        void release(Interval interval) {
            booked.removeIf(b -> b.start.equals(interval.start) && b.end.equals(interval.end));
        }

        ReentrantLock lock() { return lock; }

        @Override public String toString() { return "Room(" + id + ", cap=" + capacity + ")"; }
    }

    static final class CalendarEntry {
        final String meetingId;
        Interval interval;
        CalendarEntryStatus status;
        String subject;
        String roomId;

        CalendarEntry(String meetingId, Interval interval, CalendarEntryStatus status, String subject, String roomId) {
            this.meetingId = meetingId;
            this.interval = interval;
            this.status = status;
            this.subject = subject;
            this.roomId = roomId;
        }
    }

    static final class UserCalendar {
        private final List<CalendarEntry> entries = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();

        boolean hasConflict(Interval interval, String excludeMeetingId) {
            lock.lock();
            try {
                for (CalendarEntry e : entries) {
                    // for update flow: ignore the same meeting so it doesn't conflict with itself
                    if (excludeMeetingId != null && e.meetingId.equals(excludeMeetingId)) continue;
                    // Treat both tentative+confirmed as busy (policy can be changed later)
                    if (e.interval.overlaps(interval)) return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        void upsert(CalendarEntry entry) {
            lock.lock();
            try {
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).meetingId.equals(entry.meetingId)) {
                        entries.set(i, entry);
                        return;
                    }
                }
                entries.add(entry);
            } finally {
                lock.unlock();
            }
        }

        void remove(String meetingId) {
            lock.lock();
            try {
                entries.removeIf(e -> e.meetingId.equals(meetingId));
            } finally {
                lock.unlock();
            }
        }

        List<CalendarEntry> snapshot() {
            lock.lock();
            try {
                return new ArrayList<>(entries);
            } finally {
                lock.unlock();
            }
        }

        ReentrantLock lock() { return lock; } // for future multi-lock (room + calendars)
    }

    static final class User {
        final String id;
        final String name;
        final String email;
        final UserCalendar calendar = new UserCalendar();

        User(String id, String name, String email) {
            this.id = id; this.name = name; this.email = email;
        }
    }

    static final class Meeting {
        final String id;
        final String organizerId;
        Interval interval;
        String roomId;
        String subject;
        String agenda;

        final Map<String, RSVPStatus> rsvpByUser = new HashMap<>();

        Meeting(String id, String organizerId, List<String> participants,
                Interval interval, String roomId, String subject, String agenda) {
            this.id = id;
            this.organizerId = organizerId;
            this.interval = interval;
            this.roomId = roomId;
            this.subject = subject;
            this.agenda = agenda;

            // organizer auto-accepted
            rsvpByUser.put(organizerId, RSVPStatus.ACCEPTED);
            for (String p : participants) rsvpByUser.putIfAbsent(p, RSVPStatus.PENDING);
        }

        Set<String> allUserIds() { return new HashSet<>(rsvpByUser.keySet()); }
    }

    // ================= Strategy =================
    interface RoomSelectionStrategy {
        MeetingRoom pick(List<MeetingRoom> candidates, int requiredCapacity);
    }

    // Smallest room that fits (nice default)
    static final class SmallestFitStrategy implements RoomSelectionStrategy {
        public MeetingRoom pick(List<MeetingRoom> candidates, int requiredCapacity) {
            MeetingRoom best = null;
            for (MeetingRoom r : candidates) {
                if (r.capacity < requiredCapacity) continue;
                if (best == null || r.capacity < best.capacity) best = r;
            }
            return best;
        }
    }

    // ================= Notifications =================
    interface NotificationService {
        void notify(User user, String message);
    }

    static final class ConsoleNotificationService implements NotificationService {
        public void notify(User user, String message) {
            System.out.println("🔔 " + user.email + " | " + message);
        }
    }

    // ================= EventBus (Observer Pattern) =================
    static final class MeetingEvent {
        final MeetingEventType type;
        final String meetingId;
        final String actorUserId;
        final Set<String> targetUserIds;
        final String message;

        MeetingEvent(MeetingEventType type, String meetingId, String actorUserId,
                     Set<String> targetUserIds, String message) {
            this.type = type;
            this.meetingId = meetingId;
            this.actorUserId = actorUserId;
            this.targetUserIds = targetUserIds;
            this.message = message;
        }
    }

    interface MeetingEventListener {
        void onEvent(MeetingEvent event);
    }

    static final class EventBus {
        private final List<MeetingEventListener> listeners = new ArrayList<>();

        void subscribe(MeetingEventListener listener) {
            listeners.add(listener);
        }

        void publish(MeetingEvent event) {
            for (MeetingEventListener l : listeners) {
                l.onEvent(event);
            }
        }
    }

    static final class NotificationListener implements MeetingEventListener {
        private final Map<String, User> users;
        private final NotificationService notifier;

        NotificationListener(Map<String, User> users, NotificationService notifier) {
            this.users = users;
            this.notifier = notifier;
        }

        @Override
        public void onEvent(MeetingEvent event) {
            for (String uid : event.targetUserIds) {
                User u = users.get(uid);
                if (u != null) {
                    notifier.notify(u, event.message);
                }
            }
        }
    }

    // ================= Scheduler (Facade) =================
    static final class MeetingScheduler {
        // In-memory storage (interview friendly)
        private final Map<String, User> users = new HashMap<>();
        private final Map<String, MeetingRoom> rooms = new HashMap<>();
        private final Map<String, Meeting> meetings = new HashMap<>();

        private final RoomSelectionStrategy roomStrategy;

        // EventBus as Subject
        private final EventBus eventBus = new EventBus();

        MeetingScheduler(RoomSelectionStrategy roomStrategy, NotificationService notifier) {
            this.roomStrategy = roomStrategy;

            // Subscribe notification observer
            eventBus.subscribe(new NotificationListener(users, notifier));
        }

        // Optional: allow adding more listeners in future (audit, metrics, async delivery, etc.)
        void subscribe(MeetingEventListener listener) { eventBus.subscribe(listener); }

        // R1: manage rooms
        void addRoom(MeetingRoom room) { rooms.put(room.id, room); }

        // R6: manage users/calendars
        void addUser(User user) { users.put(user.id, user); }

        // R3 + R4 + R5 + R6 + R8 + R12
        Meeting scheduleMeeting(String organizerId, List<String> participantIds, Interval interval,
                                String subject, String agenda) {

            requireUser(organizerId);
            List<String> all = new ArrayList<>();
            all.add(organizerId);
            all.addAll(participantIds);

            // Attendee conflict check (R8)
            for (String uid : all) {
                User u = requireUser(uid);
                if (u.calendar.hasConflict(interval, null)) {
                    throw new IllegalStateException("User busy: " + u.email);
                }
            }

            int requiredCapacity = all.size();

            // Find available rooms (R2/R3)
            List<MeetingRoom> candidates = new ArrayList<>();
            for (MeetingRoom r : rooms.values()) {
                if (r.capacity >= requiredCapacity && r.isAvailable(interval)) candidates.add(r);
            }
            MeetingRoom chosen = roomStrategy.pick(candidates, requiredCapacity);
            if (chosen == null) throw new IllegalStateException("No room available");

            // Lock room to avoid double-book (concurrency)
            chosen.lock().lock();
            try {
                if (!chosen.isAvailable(interval)) throw new IllegalStateException("Room booked concurrently");
                chosen.book(interval);
            } finally {
                chosen.lock().unlock();
            }

            String meetingId = UUID.randomUUID().toString();
            Meeting m = new Meeting(meetingId, organizerId, participantIds, interval, chosen.id, subject, agenda);
            meetings.put(meetingId, m);

            // Update calendars (R12)
            syncCalendars(m);

            // Publish event (Observer) (R4)
            publish(MeetingEventType.MEETING_CREATED, m, organizerId, m.allUserIds(),
                    "Meeting created: \"" + subject + "\" " + interval + " in " + chosen.name);

            return m;
        }

        // R9 + R12
        Meeting updateMeeting(String meetingId, String actorId, Interval newInterval,
                              String newSubjectOrNull, String newAgendaOrNull) {
            Meeting m = requireMeeting(meetingId);
            if (!m.organizerId.equals(actorId)) throw new SecurityException("Only organizer can update");

            // attendee conflicts excluding this meeting entry (R8)
            for (String uid : m.allUserIds()) {
                User u = requireUser(uid);
                if (u.calendar.hasConflict(newInterval, meetingId)) {
                    throw new IllegalStateException("User busy: " + u.email);
                }
            }

            MeetingRoom room = requireRoom(m.roomId);

            // Room re-book (lock)
            room.lock().lock();
            try {
                // release old then book new (rollback if needed)
                Interval old = m.interval;
                room.release(old);
                if (!room.isAvailable(newInterval)) {
                    room.book(old); // rollback
                    throw new IllegalStateException("Room not available for new time");
                }
                room.book(newInterval);
                m.interval = newInterval;
            } finally {
                room.lock().unlock();
            }

            if (newSubjectOrNull != null) m.subject = newSubjectOrNull;
            if (newAgendaOrNull != null) m.agenda = newAgendaOrNull;

            // calendars + event (R12, R4)
            syncCalendars(m);

            publish(MeetingEventType.MEETING_UPDATED, m, actorId, m.allUserIds(),
                    "Meeting updated: \"" + m.subject + "\" now " + m.interval);

            return m;
        }

        // R7
        void addParticipants(String meetingId, String organizerId, List<String> newParticipantIds) {
            Meeting m = requireMeeting(meetingId);
            if (!m.organizerId.equals(organizerId)) throw new SecurityException("Only organizer can add participants");

            MeetingRoom room = requireRoom(m.roomId);

            // Add as PENDING (R5)
            Set<String> added = new HashSet<>();
            for (String uid : newParticipantIds) {
                requireUser(uid);
                if (!m.rsvpByUser.containsKey(uid)) {
                    m.rsvpByUser.put(uid, RSVPStatus.PENDING);
                    added.add(uid);
                }
            }

            // capacity check (R2)
            int requiredCapacity = m.allUserIds().size();
            if (room.capacity < requiredCapacity) {
                for (String uid : added) m.rsvpByUser.remove(uid);
                throw new IllegalStateException("Room capacity insufficient");
            }

            // calendars + event (R12, R4)
            syncCalendars(m);

            publish(MeetingEventType.PARTICIPANT_ADDED, m, organizerId, added,
                    "You were added to meeting: \"" + m.subject + "\" " + m.interval);
        }

        // R7 + R11
        void removeParticipant(String meetingId, String organizerId, String participantId) {
            Meeting m = requireMeeting(meetingId);
            if (!m.organizerId.equals(organizerId)) throw new SecurityException("Only organizer can remove participants");
            if (participantId.equals(m.organizerId)) throw new IllegalArgumentException("Organizer cannot be removed");

            if (!m.rsvpByUser.containsKey(participantId)) return;

            m.rsvpByUser.put(participantId, RSVPStatus.REMOVED);

            // remove from calendar (R11)
            User u = requireUser(participantId);
            u.calendar.remove(meetingId);

            publish(MeetingEventType.PARTICIPANT_REMOVED, m, organizerId, Collections.singleton(participantId),
                    "You were removed from meeting: \"" + m.subject + "\"");
        }

        // R10 + R12
        void cancelMeeting(String meetingId, String requesterId) {
            Meeting m = requireMeeting(meetingId);
            if (!m.organizerId.equals(requesterId)) throw new SecurityException("Only organizer can cancel");

            MeetingRoom room = requireRoom(m.roomId);

            room.lock().lock();
            try {
                room.release(m.interval);
            } finally {
                room.lock().unlock();
            }

            // remove from all calendars (R12)
            for (String uid : m.allUserIds()) {
                User u = requireUser(uid);
                u.calendar.remove(meetingId);
                m.rsvpByUser.put(uid, RSVPStatus.CANCELED);
            }

            meetings.remove(meetingId);

            publish(MeetingEventType.MEETING_CANCELED, m, requesterId, m.allUserIds(),
                    "Meeting canceled: \"" + m.subject + "\"");
        }

        // R5 + R11 + R12
        void respond(String meetingId, String userId, RSVPStatus response) {
            if (response != RSVPStatus.ACCEPTED && response != RSVPStatus.DECLINED) {
                throw new IllegalArgumentException("Only ACCEPTED/DECLINED allowed");
            }

            Meeting m = requireMeeting(meetingId);
            if (!m.rsvpByUser.containsKey(userId)) throw new SecurityException("Not invited");

            m.rsvpByUser.put(userId, response);

            User u = requireUser(userId);
            if (response == RSVPStatus.ACCEPTED) {
                u.calendar.upsert(new CalendarEntry(m.id, m.interval, CalendarEntryStatus.CONFIRMED, m.subject, m.roomId));
            } else {
                u.calendar.remove(m.id); // decline removes (R11)
            }

            // publish RSVP event (notify organizer)
            publish(MeetingEventType.RSVP_CHANGED, m, userId, Collections.singleton(m.organizerId),
                    "RSVP: " + userId + " " + response + " for \"" + m.subject + "\"");
        }

        // ---------- helpers ----------
        private void publish(MeetingEventType type, Meeting m, String actorId, Set<String> targets, String msg) {
            eventBus.publish(new MeetingEvent(type, m.id, actorId, targets, msg));
        }

        private void syncCalendars(Meeting m) {
            for (Map.Entry<String, RSVPStatus> e : m.rsvpByUser.entrySet()) {
                String uid = e.getKey();
                RSVPStatus s = e.getValue();
                User u = requireUser(uid);

                if (s == RSVPStatus.DECLINED || s == RSVPStatus.REMOVED || s == RSVPStatus.CANCELED) {
                    u.calendar.remove(m.id);
                } else if (s == RSVPStatus.ACCEPTED) {
                    u.calendar.upsert(new CalendarEntry(m.id, m.interval, CalendarEntryStatus.CONFIRMED, m.subject, m.roomId));
                } else { // PENDING
                    u.calendar.upsert(new CalendarEntry(m.id, m.interval, CalendarEntryStatus.TENTATIVE, m.subject, m.roomId));
                }
            }
        }

        private User requireUser(String userId) {
            User u = users.get(userId);
            if (u == null) throw new IllegalArgumentException("Unknown user: " + userId);
            return u;
        }

        private MeetingRoom requireRoom(String roomId) {
            MeetingRoom r = rooms.get(roomId);
            if (r == null) throw new IllegalArgumentException("Unknown room: " + roomId);
            return r;
        }

        private Meeting requireMeeting(String meetingId) {
            Meeting m = meetings.get(meetingId);
            if (m == null) throw new IllegalArgumentException("Meeting not found: " + meetingId);
            return m;
        }
    }

    // ================= Demo =================
    public static void main(String[] args) {
        MeetingScheduler scheduler = new MeetingScheduler(new SmallestFitStrategy(), new ConsoleNotificationService());

        // Rooms (R1,R2)
        scheduler.addRoom(new MeetingRoom("r1", "Room A", 4));
        scheduler.addRoom(new MeetingRoom("r2", "Room B", 8));

        // Users (R6)
        scheduler.addUser(new User("u1", "Alice", "alice@org.com"));
        scheduler.addUser(new User("u2", "Bob", "bob@org.com"));
        scheduler.addUser(new User("u3", "Charlie", "charlie@org.com"));

        Interval i1 = new Interval(Instant.parse("2026-02-20T16:00:00Z"), Instant.parse("2026-02-20T17:00:00Z"));
        Meeting m = scheduler.scheduleMeeting("u1", Arrays.asList("u2", "u3"), i1, "Design Review", "API discussion");

        scheduler.respond(m.id, "u2", RSVPStatus.ACCEPTED);
        scheduler.respond(m.id, "u3", RSVPStatus.DECLINED);

        Interval i2 = new Interval(Instant.parse("2026-02-20T18:00:00Z"), Instant.parse("2026-02-20T19:00:00Z"));
        scheduler.updateMeeting(m.id, "u1", i2, "Design Review v2", null);

        scheduler.cancelMeeting(m.id, "u1");
    }
}
