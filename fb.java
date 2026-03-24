// ═══════════════════════════════════════════════════════════════════════════════
// FACEBOOK / SOCIAL NETWORK — LOW-LEVEL DESIGN   (SDE2 Strong Hire)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ┌─────────────────────────────────────────────────────────────────────────────┐
// │  DESIGN DECISIONS (DD#) — TRADEOFFS REFERENCE                              │
// ├──────┬──────────────────────────────────────────────────────────────────────┤
// │ DD1  │ COMPOSITE — Comment tree                                             │
// │      │ CommentComponent (interface) → CommentLeaf + CommentThread           │
// │      │ Alt: flat list + parentId FK.  Chosen: tree enables uniform          │
// │      │ traversal for like-count aggregation, depth-limited display, delete- │
// │      │ subtree — all O(subtree) without extra joins.                        │
// │      │ TALK: "Composite lets me treat a single reply and an entire thread   │
// │      │ identically when recursively rendering or counting likes."           │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD2  │ OBSERVER — Notifications                                             │
// │      │ NotificationObserver interface; User implements; NotificationService │
// │      │ is the subject.  Push chosen over pull to eliminate polling.         │
// │      │ Alt: in-process event bus (Guava EventBus).  Chosen: Observer is     │
// │      │ self-contained, no extra dep, interviewers know the pattern.         │
// │      │ TALK: "Observer decouples event producers (post, message) from       │
// │      │ consumers; adding a new event type never touches User code."         │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD3  │ STRATEGY — Privacy checking                                          │
// │      │ PrivacyStrategy: canView(viewer, owner). THREE impls: PUBLIC,        │
// │      │ FRIENDS_ONLY, ONLY_ME. Injected into Post & Profile at runtime.     │
// │      │ Alt: enum + switch in Post.  Strategy isolates extension: adding     │
// │      │ CUSTOM_LIST = new class, zero change to Post.                        │
// │      │ TALK: "Strategy avoids if-else chains in content access; swapping    │
// │      │ algorithms is a constructor argument."                               │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD4  │ FACTORY METHOD — Notification subtypes                               │
// │      │ NotificationFactory.create(type,actor,target) → typed Notification.  │
// │      │ Alt: NotificationService switch block.  Factory isolates payload     │
// │      │ construction; service stays thin.                                    │
// │      │ TALK: "Factory ensures each notification type carries the right       │
// │      │ payload string without polluting the dispatch service."              │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD5  │ SINGLETON — SearchService                                            │
// │      │ Double-checked locking.  Maintains an in-memory inverted index       │
// │      │ shared across the system; avoids duplicate indexing overhead.        │
// │      │ Alt: DI-managed singleton (Spring @Bean).  DCL Singleton shows       │
// │      │ intent clearly in interview without framework magic.                 │
// │      │ TALK: "A single index means a new post is visible system-wide        │
// │      │ immediately after indexing; no stale replica lag."                   │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD6  │ STATE PATTERN — FriendRequest lifecycle                              │
// │      │ PENDING → ACCEPTED / REJECTED / WITHDRAWN.  State object holds       │
// │      │ allowed transitions; illegal calls throw at compile-time via missing │
// │      │ method.                                                              │
// │      │ Alt: enum + switch; State prevents illegal transitions structurally  │
// │      │ rather than via runtime guards.                                      │
// │      │ TALK: "State makes accepting an already-rejected request structurally│
// │      │ impossible — no defensive if-checks needed."                         │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD7  │ INTERFACE SEGREGATION — content interactions                         │
// │      │ Likeable / Commentable / Shareable — separate interfaces.            │
// │      │ Post implements all three; Comment implements Likeable only.         │
// │      │ Alt: fat IContent interface.  ISP means disabling comments on a Page │
// │      │ post = drop Commentable; nothing else breaks.                        │
// │      │ TALK: "ISP means a Page post and a Group post share Likeable without │
// │      │ inheriting an unwanted comment contract."                            │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD8  │ ReentrantReadWriteLock — friend list & post list                     │
// │      │ Multiple concurrent readers; writes exclusive.                       │
// │      │ Alt: synchronized.  RWLock raises read throughput for news-feed      │
// │      │ loads (reads >> writes in production traffic).                       │
// │      │ TALK: "News-feed reads dwarf writes; RWLock gives us free concurrency│
// │      │ on reads vs a blanket synchronized."                                 │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD9  │ AtomicInteger CAS — like counts                                      │
// │      │ CAS loop on likeCount avoids a per-post lock for this hot path.      │
// │      │ Alt: synchronized block; CAS is lock-free and scales under burst     │
// │      │ traffic (viral post scenario).                                       │
// │      │ TALK: "Like counts are the hottest field in the system; CAS avoids   │
// │      │ lock contention without sacrificing correctness."                    │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD10 │ CopyOnWriteArrayList — notification subscribers                      │
// │      │ Reads (dispatch) >> writes (subscribe/unsubscribe).                  │
// │      │ Alt: RWLock + ArrayList.  COW is simpler; allocation cost on writes  │
// │      │ is acceptable given the rare-write profile.                          │
// │      │ TALK: "COW means notification dispatch never blocks on a concurrent  │
// │      │ subscriber change — the common case is always lock-free."            │
// ├──────┼──────────────────────────────────────────────────────────────────────┤
// │ DD11 │ STRATEGY — Feed algorithm                                            │
// │      │ FeedStrategy interface: ChronologicalFeedStrategy (default) +        │
// │      │ RankedFeedStrategy (stub).  Injected per user preference.            │
// │      │ TALK: "A/B testing two ranking algorithms is just swapping the       │
// │      │ strategy reference — no feed retrieval code changes."                │
// └──────┴──────────────────────────────────────────────────────────────────────┘

package facebook;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.*;

// ═══════════════════════════════════════════════════════
// SECTION 1 — ENUMS
// ═══════════════════════════════════════════════════════

enum PrivacyLevel    { PUBLIC, FRIENDS_ONLY, ONLY_ME }
enum AccountStatus   { ACTIVE, BLOCKED, DEACTIVATED }
enum FriendReqStatus { PENDING, ACCEPTED, REJECTED, WITHDRAWN }
enum NotifType       { FRIEND_REQUEST, MESSAGE, POST_LIKE, POST_COMMENT, POST_SHARE, GROUP_INVITE }
enum ContentType     { POST, GROUP, PAGE, USER }

// ═══════════════════════════════════════════════════════
// SECTION 2 — INTERFACES (DD7 — Interface Segregation)
// ═══════════════════════════════════════════════════════

interface Likeable {
    void like(User liker);
    void unlike(User liker);
    int getLikeCount();
}

interface Commentable {
    CommentLeaf addComment(User author, String body);
    List<CommentComponent> getComments();
}

interface Shareable {
    void share(User sharer);
    int getShareCount();
}

// DD3 — Privacy Strategy
interface PrivacyStrategy {
    boolean canView(User viewer, User owner);
}

// DD2 — Observer
interface NotificationObserver {
    void onNotification(Notification notification);
}

// DD11 — Feed Strategy
interface FeedStrategy {
    List<Post> buildFeed(User user, List<Post> candidatePosts);
}

// DD6 — State pattern interface for FriendRequest
interface FriendRequestState {
    void accept(FriendRequest ctx);
    void reject(FriendRequest ctx);
    void withdraw(FriendRequest ctx);
    FriendReqStatus status();
}

// ═══════════════════════════════════════════════════════
// SECTION 3 — PRIVACY STRATEGIES (DD3)
// ═══════════════════════════════════════════════════════

class PublicPrivacyStrategy implements PrivacyStrategy {
    @Override public boolean canView(User viewer, User owner) { return true; }
}

class FriendsOnlyPrivacyStrategy implements PrivacyStrategy {
    @Override public boolean canView(User viewer, User owner) {
        if (viewer == null) return false;
        if (viewer.getUserId().equals(owner.getUserId())) return true;
        return owner.isFriendWith(viewer.getUserId());          // DD8 — uses RWLock internally
    }
}

class OnlyMePrivacyStrategy implements PrivacyStrategy {
    @Override public boolean canView(User viewer, User owner) {
        return viewer != null && viewer.getUserId().equals(owner.getUserId());
    }
}

class PrivacyStrategyFactory {
    public static PrivacyStrategy of(PrivacyLevel level) {
        return switch (level) {
            case PUBLIC       -> new PublicPrivacyStrategy();
            case FRIENDS_ONLY -> new FriendsOnlyPrivacyStrategy();
            case ONLY_ME      -> new OnlyMePrivacyStrategy();
        };
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 4 — NOTIFICATIONS (DD2 Observer + DD4 Factory)
// ═══════════════════════════════════════════════════════

class Notification {
    private final String   notificationId;
    private final NotifType type;
    private final String   actorId;
    private final String   targetId;
    private final String   message;
    private final Instant  createdAt;
    private volatile boolean read = false;

    public Notification(NotifType type, String actorId, String targetId, String message) {
        this.notificationId = UUID.randomUUID().toString();
        this.type      = type;
        this.actorId   = actorId;
        this.targetId  = targetId;
        this.message   = message;
        this.createdAt = Instant.now();
    }

    public void markRead() { this.read = true; }
    public NotifType getType()    { return type; }
    public String    getMessage() { return message; }
    public boolean   isRead()     { return read; }
    @Override public String toString() {
        return "[" + type + "] " + message + (read ? " (read)" : " (unread)");
    }
}

// DD4 — Notification Factory
class NotificationFactory {
    public static Notification create(NotifType type, User actor, User target) {
        String msg = switch (type) {
            case FRIEND_REQUEST -> actor.getName() + " sent you a friend request.";
            case MESSAGE        -> actor.getName() + " sent you a message.";
            case POST_LIKE      -> actor.getName() + " liked your post.";
            case POST_COMMENT   -> actor.getName() + " commented on your post.";
            case POST_SHARE     -> actor.getName() + " shared your post.";
            case GROUP_INVITE   -> actor.getName() + " invited you to join a group.";
        };
        return new Notification(type, actor.getUserId(), target.getUserId(), msg);
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 5 — NOTIFICATION SERVICE (DD2 Subject, DD5-style singleton)
// ═══════════════════════════════════════════════════════

class NotificationService {
    private static volatile NotificationService instance;
    // DD10 — CopyOnWriteArrayList: dispatch reads >> subscribe writes
    private final CopyOnWriteArrayList<NotificationObserver> globalObservers
            = new CopyOnWriteArrayList<>();

    private NotificationService() {}

    public static NotificationService getInstance() {
        if (instance == null) {
            synchronized (NotificationService.class) {
                if (instance == null) instance = new NotificationService();
            }
        }
        return instance;
    }

    public void dispatch(Notification notification, NotificationObserver target) {
        target.onNotification(notification);  // direct push to target user
        globalObservers.forEach(obs -> obs.onNotification(notification)); // e.g. audit log
    }

    public void registerGlobal(NotificationObserver observer) {
        globalObservers.addIfAbsent(observer);
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 6 — COMPOSITE: COMMENT TREE (DD1)
// ═══════════════════════════════════════════════════════

interface CommentComponent {
    String getCommentId();
    String getBody();
    User   getAuthor();
    void   display(int depth);                // recursive tree print
    int    getTotalLikes();                   // aggregate across subtree
}

// Leaf node: a single comment or reply
// DD9 — AtomicInteger for like count (hot field)
class CommentLeaf implements CommentComponent, Likeable {
    private final String commentId;
    private       String body;
    private final User   author;
    private final Instant createdAt;
    private final AtomicInteger likeCount = new AtomicInteger(0);
    private final Set<String>   likerIds  = ConcurrentHashMap.newKeySet();

    public CommentLeaf(User author, String body) {
        this.commentId = UUID.randomUUID().toString();
        this.author    = author;
        this.body      = body;
        this.createdAt = Instant.now();
    }

    @Override public String getCommentId()   { return commentId; }
    @Override public String getBody()        { return body; }
    public    void   updateBody(String b)    { this.body = b; }
    @Override public User   getAuthor()      { return author; }
    @Override public int    getTotalLikes()  { return likeCount.get(); }

    // DD9 — CAS-based like (idempotent add)
    @Override public void like(User liker) {
        if (likerIds.add(liker.getUserId())) likeCount.incrementAndGet();
    }
    @Override public void unlike(User liker) {
        if (likerIds.remove(liker.getUserId())) likeCount.decrementAndGet();
    }
    @Override public int getLikeCount() { return likeCount.get(); }

    @Override public void display(int depth) {
        System.out.println(" ".repeat(depth * 2) + author.getName() + ": " + body
                + " [♥ " + likeCount.get() + "]");
    }
}

// Composite node: a comment that also holds replies
class CommentThread implements CommentComponent, Likeable, Commentable {
    private final CommentLeaf root;                         // the comment itself
    private final List<CommentComponent> replies            // DD8 guarded by lock
            = new ArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public CommentThread(User author, String body) {
        this.root = new CommentLeaf(author, body);
    }

    @Override public String  getCommentId()  { return root.getCommentId(); }
    @Override public String  getBody()       { return root.getBody(); }
    @Override public User    getAuthor()     { return root.getAuthor(); }

    @Override public int getTotalLikes() {
        lock.readLock().lock();
        try {
            return root.getTotalLikes()
                    + replies.stream().mapToInt(CommentComponent::getTotalLikes).sum();
        } finally { lock.readLock().unlock(); }
    }

    @Override public void like(User u)   { root.like(u); }
    @Override public void unlike(User u) { root.unlike(u); }
    @Override public int  getLikeCount() { return root.getLikeCount(); }

    // DD8 — write lock for structural mutation
    @Override public CommentLeaf addComment(User author, String body) {
        lock.writeLock().lock();
        try {
            CommentLeaf leaf = new CommentLeaf(author, body);
            replies.add(leaf);
            return leaf;
        } finally { lock.writeLock().unlock(); }
    }

    @Override public List<CommentComponent> getComments() {
        lock.readLock().lock();
        try { return Collections.unmodifiableList(new ArrayList<>(replies)); }
        finally { lock.readLock().unlock(); }
    }

    @Override public void display(int depth) {
        root.display(depth);
        lock.readLock().lock();
        try { replies.forEach(r -> r.display(depth + 1)); }
        finally { lock.readLock().unlock(); }
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 7 — POST (Likeable + Commentable + Shareable, DD7 ISP)
// ═══════════════════════════════════════════════════════

class Post implements Likeable, Commentable, Shareable {
    private final String   postId;
    private       String   content;
    private final User     author;
    private final Instant  createdAt;
    private       PrivacyStrategy privacyStrategy;         // DD3 — injected strategy

    // DD9 — CAS like counter
    private final AtomicInteger likeCount  = new AtomicInteger(0);
    private final AtomicInteger shareCount = new AtomicInteger(0);
    private final Set<String>   likerIds   = ConcurrentHashMap.newKeySet();

    // DD8 — RWLock for comment list structural mutations
    private final List<CommentComponent> comments = new ArrayList<>();
    private final ReadWriteLock          lock     = new ReentrantReadWriteLock();

    public Post(User author, String content, PrivacyLevel privacy) {
        this.postId          = UUID.randomUUID().toString();
        this.author          = author;
        this.content         = content;
        this.createdAt       = Instant.now();
        this.privacyStrategy = PrivacyStrategyFactory.of(privacy);  // DD3
    }

    public String  getPostId()   { return postId; }
    public User    getAuthor()   { return author; }
    public Instant getCreatedAt(){ return createdAt; }
    public String  getContent()  { return content; }

    public void updateContent(String c) { this.content = c; }
    public void setPrivacy(PrivacyLevel level) {
        this.privacyStrategy = PrivacyStrategyFactory.of(level);
    }

    public boolean isVisibleTo(User viewer) {
        return privacyStrategy.canView(viewer, author);             // DD3
    }

    // DD9 — idempotent CAS like
    @Override public void like(User liker) {
        if (likerIds.add(liker.getUserId())) likeCount.incrementAndGet();
    }
    @Override public void unlike(User liker) {
        if (likerIds.remove(liker.getUserId())) likeCount.decrementAndGet();
    }
    @Override public int getLikeCount()  { return likeCount.get(); }

    @Override public void share(User sharer) { shareCount.incrementAndGet(); }
    @Override public int  getShareCount()    { return shareCount.get(); }

    // DD8 — write lock for structural add
    @Override public CommentLeaf addComment(User author, String body) {
        lock.writeLock().lock();
        try {
            CommentLeaf leaf = new CommentLeaf(author, body);
            comments.add(leaf);
            return leaf;
        } finally { lock.writeLock().unlock(); }
    }

    @Override public List<CommentComponent> getComments() {
        lock.readLock().lock();
        try { return Collections.unmodifiableList(new ArrayList<>(comments)); }
        finally { lock.readLock().unlock(); }
    }

    @Override public String toString() {
        return "[Post:" + postId.substring(0,8) + "] " + author.getName()
                + ": " + content + " | ♥ " + likeCount + " ↺ " + shareCount;
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 8 — FRIEND REQUEST STATE MACHINE (DD6)
// ═══════════════════════════════════════════════════════

class PendingState implements FriendRequestState {
    @Override public void accept(FriendRequest ctx) {
        ctx.getFrom().addFriend(ctx.getTo());
        ctx.getTo().addFriend(ctx.getFrom());
        ctx.setState(new AcceptedState());
    }
    @Override public void reject(FriendRequest ctx)   { ctx.setState(new RejectedState()); }
    @Override public void withdraw(FriendRequest ctx) { ctx.setState(new WithdrawnState()); }
    @Override public FriendReqStatus status()         { return FriendReqStatus.PENDING; }
}

class AcceptedState implements FriendRequestState {
    @Override public void accept(FriendRequest ctx)   { throw new IllegalStateException("Already accepted"); }
    @Override public void reject(FriendRequest ctx)   { throw new IllegalStateException("Already accepted"); }
    @Override public void withdraw(FriendRequest ctx) { throw new IllegalStateException("Already accepted"); }
    @Override public FriendReqStatus status()         { return FriendReqStatus.ACCEPTED; }
}

class RejectedState implements FriendRequestState {
    @Override public void accept(FriendRequest ctx)   { throw new IllegalStateException("Already rejected"); }
    @Override public void reject(FriendRequest ctx)   { throw new IllegalStateException("Already rejected"); }
    @Override public void withdraw(FriendRequest ctx) { throw new IllegalStateException("Already rejected"); }
    @Override public FriendReqStatus status()         { return FriendReqStatus.REJECTED; }
}

class WithdrawnState implements FriendRequestState {
    @Override public void accept(FriendRequest ctx)   { throw new IllegalStateException("Withdrawn"); }
    @Override public void reject(FriendRequest ctx)   { throw new IllegalStateException("Withdrawn"); }
    @Override public void withdraw(FriendRequest ctx) { throw new IllegalStateException("Withdrawn"); }
    @Override public FriendReqStatus status()         { return FriendReqStatus.WITHDRAWN; }
}

class FriendRequest {
    private final String  requestId;
    private final User    from;
    private final User    to;
    private final Instant sentAt;
    private FriendRequestState state;

    public FriendRequest(User from, User to) {
        this.requestId = UUID.randomUUID().toString();
        this.from      = from;
        this.to        = to;
        this.sentAt    = Instant.now();
        this.state     = new PendingState();                        // DD6 — start PENDING
    }

    public void accept()   { state.accept(this); }
    public void reject()   { state.reject(this); }
    public void withdraw() { state.withdraw(this); }

    void setState(FriendRequestState s) { this.state = s; }
    public FriendReqStatus getStatus()  { return state.status(); }
    public User  getFrom()  { return from; }
    public User  getTo()    { return to; }
    public String getRequestId() { return requestId; }
}

// ═══════════════════════════════════════════════════════
// SECTION 9 — PROFILE & EXPERIENCE ENTRIES
// ═══════════════════════════════════════════════════════

record WorkExperience(String company, String title, int startYear, Integer endYear) {}
record Education(String institution, String degree, int graduationYear) {}
record PlaceOfLiving(String city, String country, Instant from, Instant to) {}

class Profile {
    private String name;
    private String email;
    private String phone;
    private String address;
    private PrivacyStrategy privacyStrategy;                        // DD3

    private final List<WorkExperience> workHistory = new CopyOnWriteArrayList<>();
    private final List<Education>      education   = new CopyOnWriteArrayList<>();
    private final List<PlaceOfLiving>  places      = new CopyOnWriteArrayList<>();

    public Profile(String name, String email) {
        this.name            = name;
        this.email           = email;
        this.privacyStrategy = new PublicPrivacyStrategy();
    }

    // Fluent setters
    public Profile setPhone(String p)   { this.phone   = p; return this; }
    public Profile setAddress(String a) { this.address = a; return this; }
    public Profile setPrivacy(PrivacyLevel l) {
        this.privacyStrategy = PrivacyStrategyFactory.of(l); return this; // DD3
    }

    public void addWorkExperience(WorkExperience w) { workHistory.add(w); }
    public void addEducation(Education e)           { education.add(e); }
    public void addPlace(PlaceOfLiving p)           { places.add(p); }

    public String getName()  { return name; }
    public String getEmail() { return email; }

    public boolean isVisibleTo(User viewer, User owner) {
        return privacyStrategy.canView(viewer, owner);              // DD3
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 10 — MESSAGE
// ═══════════════════════════════════════════════════════

class Message {
    private final String  messageId;
    private final String  senderId;
    private final String  receiverId;
    private final String  content;
    private final Instant sentAt;
    private volatile boolean read = false;

    public Message(String senderId, String receiverId, String content) {
        this.messageId  = UUID.randomUUID().toString();
        this.senderId   = senderId;
        this.receiverId = receiverId;
        this.content    = content;
        this.sentAt     = Instant.now();
    }

    public void markRead() { this.read = true; }
    public String getSenderId()   { return senderId; }
    public String getReceiverId() { return receiverId; }
    public String getContent()    { return content; }
    public boolean isRead()       { return read; }
    @Override public String toString() {
        return senderId + " → " + receiverId + ": " + content;
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 11 — GROUP & PAGE
// ═══════════════════════════════════════════════════════

class Group {
    private final String    groupId;
    private       String    name;
    private       PrivacyLevel privacy;
    private final String    ownerId;

    // DD8 — RWLock for member list
    private final Set<String>   memberIds = ConcurrentHashMap.newKeySet();
    private final Set<String>   blockedIds = ConcurrentHashMap.newKeySet();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final List<Post> posts = new CopyOnWriteArrayList<>();

    public Group(User owner, String name, PrivacyLevel privacy) {
        this.groupId  = UUID.randomUUID().toString();
        this.ownerId  = owner.getUserId();
        this.name     = name;
        this.privacy  = privacy;
        memberIds.add(owner.getUserId());
    }

    public String getGroupId() { return groupId; }
    public String getName()    { return name; }
    public String getOwnerId() { return ownerId; }

    public void join(User u) {
        if (blockedIds.contains(u.getUserId()))
            throw new IllegalStateException("User is blocked from this group");
        memberIds.add(u.getUserId());
    }

    public void leave(User u)         { memberIds.remove(u.getUserId()); }
    public void blockUser(String uid) { blockedIds.add(uid);  memberIds.remove(uid); }
    public void unblockUser(String uid){ blockedIds.remove(uid); }
    public boolean isMember(String uid){ return memberIds.contains(uid); }

    public void setPrivacy(PrivacyLevel p, User requester) {
        if (!requester.getUserId().equals(ownerId))
            throw new SecurityException("Only owner can change privacy");
        this.privacy = p;
    }

    public Post addPost(User author, String content) {
        if (!memberIds.contains(author.getUserId()))
            throw new SecurityException("Only members can post");
        Post post = new Post(author, content, privacy == PrivacyLevel.PUBLIC
                ? PrivacyLevel.PUBLIC : PrivacyLevel.FRIENDS_ONLY);
        posts.add(post);
        return post;
    }

    public List<Post> getPosts() { return Collections.unmodifiableList(posts); }

    @Override public String toString() { return "[Group:" + name + " | " + privacy + "]"; }
}

// Page — simpler than group (no join gate, followers model)
class Page {
    private final String  pageId;
    private       String  name;
    private final String  ownerId;
    private final Set<String> followerIds = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedIds  = ConcurrentHashMap.newKeySet();
    private final List<Post>  posts       = new CopyOnWriteArrayList<>();

    public Page(User owner, String name) {
        this.pageId  = UUID.randomUUID().toString();
        this.ownerId = owner.getUserId();
        this.name    = name;
    }

    public String getPageId()  { return pageId; }
    public String getName()    { return name; }
    public String getOwnerId() { return ownerId; }

    public void follow(User u) {
        if (blockedIds.contains(u.getUserId()))
            throw new IllegalStateException("User is blocked from this page");
        followerIds.add(u.getUserId());
    }
    public void unfollow(User u)       { followerIds.remove(u.getUserId()); }
    public void blockUser(String uid)  { blockedIds.add(uid); followerIds.remove(uid); }
    public void unblockUser(String uid){ blockedIds.remove(uid); }
    public boolean isFollower(String uid){ return followerIds.contains(uid); }
    public int     followerCount()     { return followerIds.size(); }

    public Post addPost(User author, String content) {
        Post post = new Post(author, content, PrivacyLevel.PUBLIC);
        posts.add(post);
        return post;
    }

    @Override public String toString() { return "[Page:" + name + " | followers:" + followerIds.size() + "]"; }
}

// ═══════════════════════════════════════════════════════
// SECTION 12 — SEARCH SERVICE (DD5 — Singleton)
// ═══════════════════════════════════════════════════════

class SearchService {
    private static volatile SearchService instance;

    // Inverted index: token → set of entity IDs (users/groups/pages/posts)
    // ConcurrentHashMap for thread-safe concurrent index updates   // DD8
    private final ConcurrentHashMap<String, Set<String>> userIndex  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> groupIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> pageIndex  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> postIndex  = new ConcurrentHashMap<>();

    // Reference stores for retrieval
    private final ConcurrentHashMap<String, User>  users  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Page>  pages  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Post>  posts  = new ConcurrentHashMap<>();

    private SearchService() {}

    public static SearchService getInstance() {
        if (instance == null) {
            synchronized (SearchService.class) {
                if (instance == null) instance = new SearchService();
            }
        }
        return instance;
    }

    private void index(ConcurrentHashMap<String, Set<String>> idx, String id, String text) {
        for (String token : text.toLowerCase().split("\\s+")) {
            idx.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    public void indexUser(User u)   { users.put(u.getUserId(), u);
        index(userIndex, u.getUserId(), u.getName()); }
    public void indexGroup(Group g) { groups.put(g.getGroupId(), g);
        index(groupIndex, g.getGroupId(), g.getName()); }
    public void indexPage(Page p)   { pages.put(p.getPageId(), p);
        index(pageIndex, p.getPageId(), p.getName()); }
    public void indexPost(Post p)   { posts.put(p.getPostId(), p);
        index(postIndex, p.getPostId(), p.getContent()); }

    public List<User> searchUsers(String query) {
        return lookupIds(userIndex, query).stream()
                .map(users::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
    public List<Group> searchGroups(String query) {
        return lookupIds(groupIndex, query).stream()
                .map(groups::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
    public List<Page> searchPages(String query) {
        return lookupIds(pageIndex, query).stream()
                .map(pages::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
    public List<Post> searchPosts(String query) {
        return lookupIds(postIndex, query).stream()
                .map(posts::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private Set<String> lookupIds(ConcurrentHashMap<String, Set<String>> idx, String query) {
        Set<String> result = new HashSet<>();
        for (String token : query.toLowerCase().split("\\s+")) {
            Set<String> ids = idx.get(token);
            if (ids != null) result.addAll(ids);
        }
        return result;
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 13 — FEED STRATEGIES (DD11)
// ═══════════════════════════════════════════════════════

class ChronologicalFeedStrategy implements FeedStrategy {
    @Override
    public List<Post> buildFeed(User user, List<Post> candidatePosts) {
        return candidatePosts.stream()
                .filter(p -> p.isVisibleTo(user))
                .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}

// STUB — production would score by engagement, recency, affinity
class RankedFeedStrategy implements FeedStrategy {
    @Override
    public List<Post> buildFeed(User user, List<Post> candidatePosts) {
        // TODO: ML ranking model — engagement score = α·likes + β·comments + γ·shares
        return new ChronologicalFeedStrategy().buildFeed(user, candidatePosts);
    }
}

// ═══════════════════════════════════════════════════════
// SECTION 14 — USER (core entity, implements NotificationObserver)
// ═══════════════════════════════════════════════════════

class User implements NotificationObserver {

    private final String  userId;
    private final Profile profile;
    private       AccountStatus status = AccountStatus.ACTIVE;

    // DD8 — RWLock for friend list (reads >> writes on a hot social graph)
    private final Set<String>   friendIds  = new HashSet<>();
    private final ReadWriteLock friendLock = new ReentrantReadWriteLock();

    private final Set<String>   followingIds = ConcurrentHashMap.newKeySet();
    private final Set<String>   blockedIds   = ConcurrentHashMap.newKeySet();

    // Pending inbound friend requests: requestId → FriendRequest
    private final ConcurrentHashMap<String, FriendRequest> inboundRequests
            = new ConcurrentHashMap<>();

    // DD8 — RWLock for post list
    private final List<Post>    posts     = new ArrayList<>();
    private final ReadWriteLock postLock  = new ReentrantReadWriteLock();

    // Inbox
    private final ConcurrentLinkedQueue<Message>      inbox         = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Notification> notifications = new ConcurrentLinkedQueue<>();

    // DD11 — pluggable feed strategy per user
    private FeedStrategy feedStrategy = new ChronologicalFeedStrategy();

    public User(String name, String email) {
        this.userId  = UUID.randomUUID().toString();
        this.profile = new Profile(name, email);
        SearchService.getInstance().indexUser(this);            // DD5 — auto-index on creation
    }

    // ── Getters ────────────────────────────────────────────
    public String  getUserId() { return userId; }
    public String  getName()   { return profile.getName(); }
    public Profile getProfile(){ return profile; }
    public AccountStatus getStatus() { return status; }

    // ── Friend management ──────────────────────────────────

    // Called by FriendRequest.accept() (DD6 State)
    void addFriend(User u) {
        friendLock.writeLock().lock();
        try { friendIds.add(u.getUserId()); }
        finally { friendLock.writeLock().unlock(); }
    }

    public boolean isFriendWith(String uid) {
        friendLock.readLock().lock();                            // DD8 — read lock
        try { return friendIds.contains(uid); }
        finally { friendLock.readLock().unlock(); }
    }

    public void unfriend(User other) {
        friendLock.writeLock().lock();
        try {
            friendIds.remove(other.getUserId());
            other.friendIds.remove(this.userId);                // mutual unfriend under same lock
        } finally { friendLock.writeLock().unlock(); }
    }

    public void block(User other) {
        blockedIds.add(other.getUserId());
        unfriend(other);                                        // blocking implies unfriending
    }

    public void unblock(User other)   { blockedIds.remove(other.getUserId()); }
    public boolean isBlocked(User u)  { return blockedIds.contains(u.getUserId()); }

    public Set<String> getFriendIds() {
        friendLock.readLock().lock();
        try { return Collections.unmodifiableSet(new HashSet<>(friendIds)); }
        finally { friendLock.readLock().unlock(); }
    }

    // ── Friend requests ────────────────────────────────────

    public FriendRequest sendFriendRequest(User to) {
        if (isBlocked(to) || to.isBlocked(this))
            throw new IllegalStateException("Cannot send friend request to/from a blocked user");
        FriendRequest req = new FriendRequest(this, to);
        to.inboundRequests.put(req.getRequestId(), req);
        // DD2 + DD4 — Notify target
        Notification n = NotificationFactory.create(NotifType.FRIEND_REQUEST, this, to);
        NotificationService.getInstance().dispatch(n, to);
        return req;
    }

    public void acceptFriendRequest(String requestId) {
        FriendRequest req = inboundRequests.remove(requestId);
        if (req == null) throw new IllegalArgumentException("Request not found");
        req.accept();                                           // DD6 — state transition
    }

    public void rejectFriendRequest(String requestId) {
        FriendRequest req = inboundRequests.remove(requestId);
        if (req == null) throw new IllegalArgumentException("Request not found");
        req.reject();                                           // DD6
    }

    // ── Follow ─────────────────────────────────────────────

    public void follow(User other)   { followingIds.add(other.getUserId()); }
    public void unfollow(User other) { followingIds.remove(other.getUserId()); }
    public boolean isFollowing(String uid) { return followingIds.contains(uid); }

    // ── Posts ──────────────────────────────────────────────

    public Post createPost(String content, PrivacyLevel privacy) {
        Post post = new Post(this, content, privacy);
        postLock.writeLock().lock();                            // DD8
        try { posts.add(post); }
        finally { postLock.writeLock().unlock(); }
        SearchService.getInstance().indexPost(post);           // DD5
        return post;
    }

    public boolean deletePost(String postId) {
        postLock.writeLock().lock();
        try { return posts.removeIf(p -> p.getPostId().equals(postId)); }
        finally { postLock.writeLock().unlock(); }
    }

    public List<Post> getPosts(User viewer) {
        postLock.readLock().lock();                            // DD8 — read lock
        try {
            return posts.stream()
                    .filter(p -> p.isVisibleTo(viewer))        // DD3 — privacy strategy
                    .collect(Collectors.toList());
        } finally { postLock.readLock().unlock(); }
    }

    // ── Interactions on posts ──────────────────────────────

    public void likePost(Post post) {
        post.like(this);                                       // DD9 — CAS
        Notification n = NotificationFactory.create(NotifType.POST_LIKE, this, post.getAuthor());
        NotificationService.getInstance().dispatch(n, post.getAuthor()); // DD2
    }

    public CommentLeaf commentOnPost(Post post, String body) {
        CommentLeaf comment = post.addComment(this, body);
        Notification n = NotificationFactory.create(NotifType.POST_COMMENT, this, post.getAuthor());
        NotificationService.getInstance().dispatch(n, post.getAuthor()); // DD2
        return comment;
    }

    public void sharePost(Post post) {
        post.share(this);                                       // DD9 share counter
        Notification n = NotificationFactory.create(NotifType.POST_SHARE, this, post.getAuthor());
        NotificationService.getInstance().dispatch(n, post.getAuthor()); // DD2
    }

    // ── Messaging ─────────────────────────────────────────

    public Message sendMessage(User to, String content) {
        if (isBlocked(to) || to.isBlocked(this))
            throw new IllegalStateException("Cannot message a blocked user");
        Message msg = new Message(this.userId, to.getUserId(), content);
        to.inbox.add(msg);
        Notification n = NotificationFactory.create(NotifType.MESSAGE, this, to);
        NotificationService.getInstance().dispatch(n, to);     // DD2
        return msg;
    }

    public List<Message> getInbox() { return new ArrayList<>(inbox); }

    // ── DD2 — Observer callback ────────────────────────────

    @Override
    public void onNotification(Notification notification) {
        notifications.add(notification);
        System.out.println("  🔔 [" + getName() + "] " + notification.getMessage());
    }

    public List<Notification> getUnreadNotifications() {
        return notifications.stream().filter(n -> !n.isRead()).collect(Collectors.toList());
    }

    // ── Feed ──────────────────────────────────────────────

    public void setFeedStrategy(FeedStrategy s) { this.feedStrategy = s; } // DD11

    public List<Post> getFeed(List<User> allUsers) {
        List<Post> candidates = new ArrayList<>();
        for (User u : allUsers) {
            if (isFriendWith(u.getUserId()) || isFollowing(u.getUserId())) {
                candidates.addAll(u.getPosts(this));
            }
        }
        return feedStrategy.buildFeed(this, candidates);        // DD11
    }

    // ── Account ───────────────────────────────────────────

    public void deactivate() { this.status = AccountStatus.DEACTIVATED; }

    @Override public String toString() { return "[User:" + getName() + "]"; }
}

// ═══════════════════════════════════════════════════════
// SECTION 15 — SOCIAL MEDIA SYSTEM FACADE
// ═══════════════════════════════════════════════════════
// Single entry point that orchestrates subsystems.
// Keeps demo code clean and mirrors a real service layer.

class SocialMediaSystem {
    private final SearchService searchService = SearchService.getInstance(); // DD5

    // ── User management ───────────────────────────────────
    public User createUser(String name, String email) {
        return new User(name, email);          // DD5 — indexing happens inside User constructor
    }

    // ── Group / Page management ───────────────────────────
    public Group createGroup(User owner, String name, PrivacyLevel privacy) {
        Group g = new Group(owner, name, privacy);
        searchService.indexGroup(g);                            // DD5
        return g;
    }

    public Page createPage(User owner, String name) {
        Page p = new Page(owner, name);
        searchService.indexPage(p);                             // DD5
        return p;
    }

    // ── Invite user to group (DD2 notification) ───────────
    public void inviteToGroup(User inviter, User invitee, Group group) {
        if (!group.isMember(inviter.getUserId()))
            throw new SecurityException("Only members can invite");
        Notification n = NotificationFactory.create(NotifType.GROUP_INVITE, inviter, invitee);
        NotificationService.getInstance().dispatch(n, invitee); // DD2
    }

    // ── Search ────────────────────────────────────────────
    public List<User>  searchUsers(String q)  { return searchService.searchUsers(q); }
    public List<Group> searchGroups(String q) { return searchService.searchGroups(q); }
    public List<Page>  searchPages(String q)  { return searchService.searchPages(q); }
    public List<Post>  searchPosts(String q)  { return searchService.searchPosts(q); }
}

// ═══════════════════════════════════════════════════════
// SECTION 16 — DEMO / DRIVER
// ═══════════════════════════════════════════════════════

public class FacebookLLD {

    public static void main(String[] args) {

        SocialMediaSystem system = new SocialMediaSystem();

        // ── Create users ──────────────────────────────────
        User alice = system.createUser("Alice",   "alice@example.com");
        User bob   = system.createUser("Bob",     "bob@example.com");
        User carol = system.createUser("Carol",   "carol@example.com");

        // ── Profile edit ──────────────────────────────────
        alice.getProfile()
             .setPhone("555-0100")
             .setAddress("1 Infinite Loop")
             .setPrivacy(PrivacyLevel.FRIENDS_ONLY);            // DD3
        alice.getProfile().addWorkExperience(
                new WorkExperience("Amazon", "SDE2", 2022, null));
        alice.getProfile().addEducation(
                new Education("MIT", "B.Sc. CS", 2022));

        System.out.println("\n── FRIEND REQUEST FLOW (DD6 State) ──");
        FriendRequest req = alice.sendFriendRequest(bob);       // triggers DD2 notification
        System.out.println("Request status: " + req.getStatus());
        bob.acceptFriendRequest(req.getRequestId());
        System.out.println("Request status: " + req.getStatus());

        // Illegal transition demo
        try { req.reject(); }
        catch (IllegalStateException e) {
            System.out.println("Caught expected: " + e.getMessage());
        }

        System.out.println("\n── FOLLOW (no friend requirement) ──");
        carol.follow(alice);
        System.out.println("Carol follows Alice: " + carol.isFollowing(alice.getUserId()));

        System.out.println("\n── POST + PRIVACY (DD3 Strategy) ──");
        Post alicePost  = alice.createPost("Hello friends!",   PrivacyLevel.FRIENDS_ONLY);
        Post publicPost = alice.createPost("Public service announcement", PrivacyLevel.PUBLIC);
        System.out.println("Carol sees FRIENDS_ONLY post: " + alicePost.isVisibleTo(carol));
        System.out.println("Carol sees PUBLIC post:       " + publicPost.isVisibleTo(carol));
        System.out.println("Bob   sees FRIENDS_ONLY post: " + alicePost.isVisibleTo(bob));

        System.out.println("\n── LIKE / COMMENT / SHARE (DD7 ISP + DD9 CAS) ──");
        bob.likePost(alicePost);                                // DD9 CAS + DD2 notification
        bob.likePost(alicePost);                                // idempotent — count stays 1
        System.out.println("Like count (idempotent): " + alicePost.getLikeCount());
        CommentLeaf c1 = bob.commentOnPost(alicePost, "Great post!");
        CommentLeaf c2 = carol.commentOnPost(publicPost, "Agreed!");
        bob.sharePost(publicPost);
        System.out.println("Share count: " + publicPost.getShareCount());

        System.out.println("\n── COMPOSITE COMMENT TREE (DD1) ──");
        // Promote alicePost's first comment to a CommentThread for nested replies
        CommentThread thread = new CommentThread(bob.getProfile().getName() != null
                ? bob : bob, "Nested thread root");
        thread.addComment(carol, "Carol's reply");
        thread.addComment(alice, "Alice's reply");
        carol.likePost(alicePost);                              // also like alice's post
        c1.like(carol);                                         // like the comment leaf
        thread.display(0);
        System.out.println("Thread total likes: " + thread.getTotalLikes());

        System.out.println("\n── MESSAGING (DD2 Notification) ──");
        Message m = alice.sendMessage(bob, "Hey Bob!");
        System.out.println("Bob's inbox: " + bob.getInbox().get(0));

        System.out.println("\n── GROUP & PAGE ──");
        Group devGroup = system.createGroup(alice, "Java Devs", PrivacyLevel.PUBLIC);
        bob.getProfile();  // Bob joins
        devGroup.join(bob);
        devGroup.join(carol);
        Post groupPost = devGroup.addPost(bob, "LLD tips inside!");
        System.out.println("Group: " + devGroup + " | Members: bob=" + devGroup.isMember(bob.getUserId()));

        Page techPage = system.createPage(alice, "Tech Trends");
        techPage.follow(bob);
        techPage.follow(carol);
        System.out.println("Page: " + techPage);

        system.inviteToGroup(alice, carol, devGroup);           // DD2 GROUP_INVITE notification

        System.out.println("\n── BLOCK / UNFRIEND ──");
        alice.block(carol);
        System.out.println("Carol blocked by Alice: " + alice.isBlocked(carol));
        try { carol.sendMessage(alice, "Hi Alice"); }
        catch (IllegalStateException e) { System.out.println("Blocked message caught: " + e.getMessage()); }

        System.out.println("\n── SEARCH (DD5 Singleton + Inverted Index) ──");
        List<User>  foundUsers  = system.searchUsers("alice");
        List<Group> foundGroups = system.searchGroups("java");
        List<Page>  foundPages  = system.searchPages("tech");
        List<Post>  foundPosts  = system.searchPosts("friends");
        System.out.println("Search 'alice':   " + foundUsers);
        System.out.println("Search 'java':    " + foundGroups);
        System.out.println("Search 'tech':    " + foundPages);
        System.out.println("Search 'friends': " + foundPosts);

        System.out.println("\n── FEED (DD11 Strategy) ──");
        List<User> allUsers = List.of(alice, bob, carol);
        List<Post> bobFeed = bob.getFeed(allUsers);
        bobFeed.forEach(p -> System.out.println("  " + p));

        // Swap to ranked strategy at runtime
        bob.setFeedStrategy(new RankedFeedStrategy());
        System.out.println("Switched to RankedFeedStrategy (stub)");

        System.out.println("\n── NOTIFICATIONS (DD2 Observer) ──");
        System.out.println("Bob unread notifications: " + bob.getUnreadNotifications().size());
        bob.getUnreadNotifications().forEach(n -> System.out.println("  " + n));

        System.out.println("\nDone.");
    }
}
