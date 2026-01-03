```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/* =========================
   ENUMS (as given)
   ========================= */
enum BookFormat { HARDCOVER, PAPERBACK, AUDIOBOOK, EBOOK, NEWSPAPER, MAGAZINE, JOURNAL }
enum BookStatus { AVAILABLE, RESERVED, LOANED, LOST }
enum ReservationStatus { WAITING, PENDING, CANCELED, NONE }
enum AccountStatus { ACTIVE, CLOSED, CANCELED, BLOCKLISTED, NONE }

/* =========================
   DATA CLASSES (as given / minimal)
   ========================= */
class Address {
    private String streetAddress;
    private String city;
    private String state;
    private int zipCode;
    private String country;
}

class Person {
    private String name;
    private Address address;
    private String email;
    private String phone;
}

class Author {
    private final String name;
    public Author(String name) { this.name = name; }
    public String getName() { return name; }
}

/* Book is abstract as in your design */
abstract class Book {
    private String isbn;
    private String title;
    private String subject;
    private String publisher;
    private String language;
    private int numberOfPages;
    private BookFormat bookFormat;
    private List<Author> authors;

    public String getIsbn() { return isbn; }
    public String getTitle() { return title; }
    public String getSubject() { return subject; }
    public List<Author> getAuthors() { return authors == null ? Collections.emptyList() : authors; }

    // quick setters (helpful in interview / demo)
    public void setIsbn(String isbn) { this.isbn = isbn; }
    public void setTitle(String title) { this.title = title; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setAuthors(List<Author> authors) { this.authors = authors; }
}

/* Simple concrete book for demo/testing */
class SimpleBook extends Book {
    public SimpleBook(String isbn, String title, String subject, List<Author> authors) {
        setIsbn(isbn);
        setTitle(title);
        setSubject(subject);
        setAuthors(authors);
    }
}

class Rack {
    private int number;
    private String locationIdentifier;
    private List<BookItem> bookItems = new ArrayList<>();

    public Rack(int number, String locationIdentifier) {
        this.number = number;
        this.locationIdentifier = locationIdentifier;
    }

    public void addBookItem(BookItem bookItem) { bookItems.add(bookItem); }
}

class BookItem {
    private String id;
    private boolean isReferenceOnly;
    private Date borrowed;
    private Date dueDate;
    private double price;
    private BookStatus status;
    private Date dateOfPurchase;
    private Date publicationDate;
    private Rack placedAt;
    private Book book;  // aggregation

    // concurrency: lock per physical copy
    private final transient ReentrantLock lock = new ReentrantLock(true);

    public BookItem(String id, Book book, boolean isReferenceOnly) {
        this.id = id;
        this.book = book;
        this.isReferenceOnly = isReferenceOnly;
        this.status = BookStatus.AVAILABLE;
    }

    public String getId() { return id; }
    public boolean isReferenceOnly() { return isReferenceOnly; }
    public Date getBorrowed() { return borrowed; }
    public Date getDueDate() { return dueDate; }
    public BookStatus getStatus() { return status; }
    public Book getBook() { return book; }
    public ReentrantLock getLock() { return lock; }

    public void setPlacedAt(Rack rack) { this.placedAt = rack; }
    public void setAddedBy(Librarian librarian) { /* could log */ }

    public void markLoaned(Date borrowed, Date dueDate) {
        this.borrowed = borrowed;
        this.dueDate = dueDate;
        this.status = BookStatus.LOANED;
    }

    public void markAvailable() {
        this.borrowed = null;
        this.dueDate = null;
        this.status = BookStatus.AVAILABLE;
    }

    public void markReserved() { this.status = BookStatus.RESERVED; }
}

/* =========================
   SEARCH + CATALOG (real-time indexes)
   ========================= */
interface Search {
    List<Book> searchByTitle(String title);
    List<Book> searchByAuthor(String author);
    List<Book> searchBySubject(String subject);
    List<Book> searchByPublicationDate(Date publishDate);
}

class Catalog implements Search {
    private HashMap<String, List<Book>> bookTitles = new HashMap<>();
    private HashMap<String, List<Book>> bookAuthors = new HashMap<>();
    private HashMap<String, List<Book>> bookSubjects = new HashMap<>();
    private HashMap<String, List<Book>> bookPublicationDates = new HashMap<>();

    // many reads, few writes
    private final ReadWriteLock rw = new ReentrantReadWriteLock(true);

    public void index(Book b, Date publicationDate) {
        rw.writeLock().lock();
        try {
            indexTokens(bookTitles, b.getTitle(), b);
            for (Author a : b.getAuthors()) indexTokens(bookAuthors, a.getName(), b);
            indexTokens(bookSubjects, b.getSubject(), b);

            String pubKey = pubKey(publicationDate);
            addUnique(bookPublicationDates, pubKey, b);
        } finally {
            rw.writeLock().unlock();
        }
    }

    @Override public List<Book> searchByTitle(String query) { return searchTokens(bookTitles, query); }
    @Override public List<Book> searchByAuthor(String query) { return searchTokens(bookAuthors, query); }
    @Override public List<Book> searchBySubject(String query) { return searchTokens(bookSubjects, query); }

    @Override
    public List<Book> searchByPublicationDate(Date publishDate) {
        rw.readLock().lock();
        try {
            String key = pubKey(publishDate);
            return new ArrayList<>(bookPublicationDates.getOrDefault(key, Collections.emptyList()));
        } finally {
            rw.readLock().unlock();
        }
    }

    private List<Book> searchTokens(HashMap<String, List<Book>> idx, String query) {
        List<String> tokens = tokenize(query);
        rw.readLock().lock();
        try {
            if (tokens.isEmpty()) return Collections.emptyList();

            List<Book> result = null;
            for (String t : tokens) {
                List<Book> list = idx.getOrDefault(t, Collections.emptyList());
                if (result == null) result = new ArrayList<>(list);
                else result.retainAll(list); // AND match
                if (result.isEmpty()) break;
            }
            return result == null ? Collections.emptyList() : result;
        } finally {
            rw.readLock().unlock();
        }
    }

    private void indexTokens(HashMap<String, List<Book>> idx, String text, Book b) {
        for (String token : tokenize(text)) addUnique(idx, token, b);
    }

    private void addUnique(HashMap<String, List<Book>> idx, String key, Book b) {
        List<Book> list = idx.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(b)) list.add(b);
    }

    private List<String> tokenize(String s) {
        if (s == null) return Collections.emptyList();
        String norm = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        String[] parts = norm.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private String pubKey(Date d) {
        if (d == null) return "unknown";
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        int day = c.get(Calendar.DAY_OF_MONTH);
        return y + "-" + m + "-" + day;
    }
}

/* =========================
   USERS (minimal)
   ========================= */
abstract class User {
    protected String id;
    protected AccountStatus status = AccountStatus.ACTIVE;
    public String getId() { return id; }
    public AccountStatus getStatus() { return status; }
    public abstract boolean resetPassword();
}

class Librarian extends User {
    public Librarian(String id) { this.id = id; }
    @Override public boolean resetPassword() { return true; }
}

class Member extends User {
    private final ReentrantLock lock = new ReentrantLock(true);
    private int totalBooksCheckedOut = 0;

    public Member(String id) { this.id = id; }

    public ReentrantLock getLock() { return lock; }
    public int getTotalBooksCheckedOut() { return totalBooksCheckedOut; }
    public void incBorrowed() { totalBooksCheckedOut++; }
    public void decBorrowed() { totalBooksCheckedOut--; }

    @Override public boolean resetPassword() { return true; }
}

/* =========================
   LENDING + RESERVATION RECORDS
   ========================= */
class BookLending {
    final String lendingId;
    final String bookItemId;
    final String memberId;
    final Date issueDate;
    Date dueDate;
    Date returnDate;
    int renewCount;

    BookLending(String lendingId, String bookItemId, String memberId, Date issueDate, Date dueDate) {
        this.lendingId = lendingId;
        this.bookItemId = bookItemId;
        this.memberId = memberId;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
    }
}

class BookReservation {
    final String reservationId;
    final String bookItemId;
    final String memberId;
    ReservationStatus status = ReservationStatus.WAITING;
    final Date createdOn = new Date();

    BookReservation(String reservationId, String bookItemId, String memberId) {
        this.reservationId = reservationId;
        this.bookItemId = bookItemId;
        this.memberId = memberId;
    }
}

/* =========================
   FINE STRATEGY
   ========================= */
interface FineStrategy {
    double calculate(Date dueDate, Date returnDate);
}

class MonthlyFineStrategy implements FineStrategy {
    private final double perMonth;
    MonthlyFineStrategy(double perMonth) { this.perMonth = perMonth; }

    @Override
    public double calculate(Date dueDate, Date returnDate) {
        if (dueDate == null || returnDate == null || !returnDate.after(dueDate)) return 0.0;
        long daysLate = (returnDate.getTime() - dueDate.getTime()) / (1000L * 60 * 60 * 24);
        long months = (daysLate + 29) / 30;
        return months * perMonth;
    }
}

/* =========================
   OBSERVER (NotificationCenter)
   ========================= */
interface Observer {
    void onEvent(String type, String memberId, String bookItemId, Date dueDate);
}

class NotificationCenter {
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    public void subscribe(Observer o) { observers.add(o); }

    public void publish(String type, String memberId, String bookItemId, Date dueDate) {
        for (Observer o : observers) o.onEvent(type, memberId, bookItemId, dueDate);
    }
}

/* Two example observers */
class EmailObserver implements Observer {
    @Override
    public void onEvent(String type, String memberId, String bookItemId, Date dueDate) {
        System.out.println("[EMAIL] type=" + type + " member=" + memberId + " item=" + bookItemId + " due=" + dueDate);
    }
}

class PostalObserver implements Observer {
    @Override
    public void onEvent(String type, String memberId, String bookItemId, Date dueDate) {
        System.out.println("[POST] type=" + type + " member=" + memberId + " item=" + bookItemId + " due=" + dueDate);
    }
}

/* =========================
   Due Reminder Scheduler (simple)
   ========================= */
class DueReminderScheduler {
    private final ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, List<ScheduledFuture<?>>> jobs = new ConcurrentHashMap<>();
    private final int[] offsets = {30, 15, 3, 0};

    public void schedule(String lendingId, Runnable notifyFn, Date dueDate) {
        cancel(lendingId);

        List<ScheduledFuture<?>> list = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (int off : offsets) {
            Date fireAt = addDays(dueDate, -off);
            long delay = fireAt.getTime() - now;
            if (delay < 0) continue;
            list.add(sch.schedule(notifyFn, delay, TimeUnit.MILLISECONDS));
        }
        jobs.put(lendingId, list);
    }

    public void cancel(String lendingId) {
        List<ScheduledFuture<?>> old = jobs.remove(lendingId);
        if (old != null) for (ScheduledFuture<?> f : old) f.cancel(false);
    }

    private Date addDays(Date base, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(base);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }
}

/* =========================
   LIBRARY MANAGEMENT SYSTEM (Singleton facade)
   - Reserve / Checkout / Renew / Return
   - Real-time search via Catalog
   - Observer notifications
   - Due reminders scheduler
   - Concurrency via Member lock + BookItem lock
   ========================= */
class LibraryManagementSystem {
    private static final LibraryManagementSystem INSTANCE = new LibraryManagementSystem();
    public static LibraryManagementSystem getInstance() { return INSTANCE; }

    // policies from requirements
    private static final int MAX_BORROW = 10;
    private static final int LOAN_DAYS = 15;
    private static final int MAX_RENEWALS = 2;

    private final Catalog catalog = new Catalog();

    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Map<String, BookItem> items = new ConcurrentHashMap<>();

    // one active lending per book item
    private final Map<String, BookLending> lendingByItem = new ConcurrentHashMap<>();

    // only one reservation per item
    private final Map<String, BookReservation> reservationByItem = new ConcurrentHashMap<>();

    private FineStrategy fineStrategy = new MonthlyFineStrategy(5.0);

    // Observer + reminders
    private final NotificationCenter notifier = new NotificationCenter();
    private final DueReminderScheduler dueScheduler = new DueReminderScheduler();

    private LibraryManagementSystem() {
        notifier.subscribe(new EmailObserver());
        notifier.subscribe(new PostalObserver());
    }

    public void setFineStrategy(FineStrategy strategy) { this.fineStrategy = strategy; }

    /* Setup */
    public void addMember(Member m) { members.put(m.getId(), m); }
    public void addBookItem(BookItem item, Date publicationDate) {
        items.put(item.getId(), item);
        catalog.index(item.getBook(), publicationDate);
    }

    /* Search */
    public List<Book> searchByTitle(String q) { return catalog.searchByTitle(q); }
    public List<Book> searchByAuthor(String q) { return catalog.searchByAuthor(q); }
    public List<Book> searchBySubject(String q) { return catalog.searchBySubject(q); }
    public List<Book> searchByPublicationDate(Date d) { return catalog.searchByPublicationDate(d); }

    /* Reserve */
    public void reserve(String memberId, String itemId) {
        requireMember(memberId);
        BookItem item = requireItem(itemId);

        item.getLock().lock();
        try {
            BookReservation existing = reservationByItem.get(itemId);
            if (existing != null && existing.status != ReservationStatus.CANCELED) {
                throw new IllegalStateException("Already reserved");
            }
            BookReservation r = new BookReservation(UUID.randomUUID().toString(), itemId, memberId);
            reservationByItem.put(itemId, r);

            if (item.getStatus() == BookStatus.AVAILABLE) item.markReserved();
            notifier.publish("RESERVED", memberId, itemId, null);
        } finally {
            item.getLock().unlock();
        }
    }

    /* Checkout */
    public void checkout(String memberId, String itemId) {
        Member m = requireMember(memberId);
        BookItem item = requireItem(itemId);

        // lock order avoids deadlocks
        m.getLock().lock();
        try {
            if (m.getStatus() != AccountStatus.ACTIVE) throw new IllegalStateException("Member not active");
            if (m.getTotalBooksCheckedOut() >= MAX_BORROW) throw new IllegalStateException("Max borrow limit");

            item.getLock().lock();
            try {
                if (item.isReferenceOnly()) throw new IllegalStateException("Reference only");
                if (item.getStatus() == BookStatus.LOANED) throw new IllegalStateException("Already loaned");
                if (item.getStatus() == BookStatus.LOST) throw new IllegalStateException("Lost");

                // reservation rule
                BookReservation r = reservationByItem.get(itemId);
                if (item.getStatus() == BookStatus.RESERVED && r != null && !r.memberId.equals(memberId)) {
                    throw new IllegalStateException("Reserved by another member");
                }

                Date now = new Date();
                Date due = addDays(now, LOAN_DAYS);

                item.markLoaned(now, due);
                m.incBorrowed();

                BookLending lending = new BookLending(UUID.randomUUID().toString(), itemId, memberId, now, due);
                lendingByItem.put(itemId, lending);

                // remove reservation if it was same member
                if (r != null && r.memberId.equals(memberId)) reservationByItem.remove(itemId);

                notifier.publish("CHECKED_OUT", memberId, itemId, due);

                // schedule due reminders
                dueScheduler.schedule(
                        lending.lendingId,
                        () -> notifier.publish("DUE_REMINDER", memberId, itemId, lending.dueDate),
                        lending.dueDate
                );

            } finally {
                item.getLock().unlock();
            }
        } finally {
            m.getLock().unlock();
        }
    }

    /* Renew */
    public void renew(String memberId, String itemId) {
        Member m = requireMember(memberId);
        BookItem item = requireItem(itemId);

        m.getLock().lock();
        try {
            item.getLock().lock();
            try {
                BookLending lending = lendingByItem.get(itemId);
                if (lending == null) throw new IllegalStateException("No active lending");
                if (!lending.memberId.equals(memberId)) throw new IllegalStateException("Not your book");
                if (lending.renewCount >= MAX_RENEWALS) throw new IllegalStateException("Max renewals reached");

                // cannot renew if someone else reserved
                BookReservation r = reservationByItem.get(itemId);
                if (r != null && !r.memberId.equals(memberId)) throw new IllegalStateException("Reserved by other member");

                lending.renewCount++;
                lending.dueDate = addDays(lending.dueDate, LOAN_DAYS);
                item.markLoaned(item.getBorrowed(), lending.dueDate);

                notifier.publish("RENEWED", memberId, itemId, lending.dueDate);

                // reschedule reminders
                dueScheduler.schedule(
                        lending.lendingId,
                        () -> notifier.publish("DUE_REMINDER", memberId, itemId, lending.dueDate),
                        lending.dueDate
                );

            } finally {
                item.getLock().unlock();
            }
        } finally {
            m.getLock().unlock();
        }
    }

    /* Return */
    public double returnBook(String memberId, String itemId) {
        Member m = requireMember(memberId);
        BookItem item = requireItem(itemId);

        m.getLock().lock();
        try {
            item.getLock().lock();
            try {
                BookLending lending = lendingByItem.get(itemId);
                if (lending == null) throw new IllegalStateException("No active lending");
                if (!lending.memberId.equals(memberId)) throw new IllegalStateException("Not your book");

                Date ret = new Date();
                lending.returnDate = ret;

                // stop reminders
                dueScheduler.cancel(lending.lendingId);

                double fine = fineStrategy.calculate(lending.dueDate, ret);

                lendingByItem.remove(itemId);
                item.markAvailable();
                m.decBorrowed();

                notifier.publish("RETURNED", memberId, itemId, null);

                // if reserved -> notify availability
                BookReservation r = reservationByItem.get(itemId);
                if (r != null && r.status != ReservationStatus.CANCELED) {
                    item.markReserved();
                    notifier.publish("RESERVATION_AVAILABLE", r.memberId, itemId, null);
                }

                return fine;
            } finally {
                item.getLock().unlock();
            }
        } finally {
            m.getLock().unlock();
        }
    }

    /* Helpers */
    private Member requireMember(String id) {
        Member m = members.get(id);
        if (m == null) throw new IllegalArgumentException("Member not found: " + id);
        return m;
    }

    private BookItem requireItem(String id) {
        BookItem it = items.get(id);
        if (it == null) throw new IllegalArgumentException("BookItem not found: " + id);
        return it;
    }

    private Date addDays(Date base, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(base);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }
}

/* =========================
   OPTIONAL DEMO (main)
   ========================= */
class Demo {
    public static void main(String[] args) {
        LibraryManagementSystem lms = LibraryManagementSystem.getInstance();

        Member m1 = new Member("M1");
        Member m2 = new Member("M2");
        lms.addMember(m1);
        lms.addMember(m2);

        Book b = new SimpleBook("ISBN-1", "Clean Code", "Programming", Arrays.asList(new Author("Robert Martin")));
        BookItem item1 = new BookItem("BI-1", b, false);

        lms.addBookItem(item1, new Date());

        System.out.println("Search by title: " + lms.searchByTitle("clean").size());   // should be 1
        System.out.println("Search by author: " + lms.searchByAuthor("martin").size()); // should be 1

        lms.checkout("M1", "BI-1");
        // M2 tries reserve while loaned
        lms.reserve("M2", "BI-1");

        double fine = lms.returnBook("M1", "BI-1");
        System.out.println("Fine: " + fine);

        // Now M2 can checkout (since reserved & available)
        lms.checkout("M2", "BI-1");
    }
}
