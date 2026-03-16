import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.*;

// ══════════════════════════════════════════════════════════════════
//  STACK OVERFLOW — Low-Level Design  |  Strong Hire  |  Java
//  Requirements covered: R1–R11
//  Patterns: Composite · Observer · Strategy · Decorator · Singleton
// ══════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────
//  ENUMS
// ─────────────────────────────────────────────────────────────────

enum AccountType    { GUEST, USER, MODERATOR }
enum AccountStatus  { ACTIVE, INACTIVE, BANNED }
enum QuestionStatus { OPEN, CLOSED, DELETED }
enum VoteType       { UPVOTE, DOWNVOTE }
enum FlagReason     { SPAM, OFFENSIVE, PLAGIARISM, LOW_QUALITY }

enum NotificationType {
    ANSWER_POSTED,       // someone answered your question                (R8)
    COMMENT_ON_QUESTION, // comment on a question you posted or answered  (R8)
    COMMENT_ON_ANSWER,   // comment on your answer                        (R8)
    VOTE_RECEIVED,       // your post was upvoted / downvoted             (R8)
    BADGE_EARNED,        // you earned a badge                            (R8, R9)
    BOUNTY_EXPIRED       // your bounty expired without being awarded     (R6, R8)
}

enum BadgeType {
    CURIOUS    ("Asked a well-received question",        10),
    SCHOLAR    ("Accepted an answer",                    20),
    ENLIGHTENED("First answer accepted with 10+ votes",  50),
    GURU       ("Accepted answer with 40+ votes",       100);

    final String description;
    final int    reputationThreshold;
    BadgeType(String d, int t) { description = d; reputationThreshold = t; }
}

// ─────────────────────────────────────────────────────────────────
//  INTERFACES
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD1 — Interface Segregation: three focused interfaces over one God-iface │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   Split behaviour into Votable / Flaggable / Commentable. Question and  │
// │   Answer implement all three. Comment implements only Votable+Flaggable  │
// │   — callers holding a Comment reference never see downvote() or         │
// │   addComment() at the call site; the R4 restriction lives in the type.  │
// │                                                                          │
// │ ALTERNATIVE 1 — One God-interface PostBehavior:                         │
// │   Everything implements upvote/downvote/addComment/flag. Comment         │
// │   throws UnsupportedOperationException on downvote/addComment.          │
// │   Problem: the restriction is hidden inside the implementation, not      │
// │   visible at the call site. Callers discover it at runtime, not at      │
// │   compile time.                                                          │
// │                                                                          │
// │ ALTERNATIVE 2 — Enum capability guard (getCapabilities()):             │
// │   Every method checks contains(Capability.DOWNVOTE) before executing.  │
// │   Problem: callers must query the object before every call — the guard  │
// │   leaks out of the class into every call site.                           │
// │                                                                          │
// │ ALTERNATIVE 3 — Abstract class hierarchy (no interfaces):               │
// │   VotableContent → CommentableContent → Question/Answer, Comment        │
// │   branches off VotableContent. Problem: Java single-inheritance means   │
// │   BountyDecorator (which wraps Question and must be Commentable) cannot │
// │   inherit from two abstract classes. Interfaces give composability.     │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   ContentNode still declares downvote() because Votable is implemented  │
// │   at that level, so ContentNode node = new Comment(); node.downvote()   │
// │   compiles but throws. The fully ISP-pure fix (remove downvote from     │
// │   ContentNode; only add on Question/Answer) forces casting at every     │
// │   polymorphic voting call site. We accepted the localized throw as the  │
// │   lesser evil. Commentable is cleanly absent from Comment — that is the │
// │   more commonly misused path and it is fully blocked at compile time.   │
// └──────────────────────────────────────────────────────────────────────────┘
interface Votable {
    boolean upvote(User voter);
    boolean downvote(User voter);
    int     getVoteCount();
}

interface Flaggable {
    void            flag(User reporter, FlagReason reason);
    List<FlagEntry> getFlags();
}

interface Commentable {
    void          addComment(Comment comment);
    List<Comment> getComments();
}

// ─────────────────────────────────────────────────────────────────
//  COMPOSITE PATTERN — ContentNode  (R1 view, R4 comments/votes)
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD2 — Composite pattern for the content tree                             │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   Question → [Answer → [Comment], Comment] forms a uniform tree.        │
// │   All nodes extend ContentNode. Traversal code (flag aggregation,       │
// │   search indexing, notification routing) operates on ContentNode         │
// │   without switching on concrete type. Leaf nodes restrict unsupported   │
// │   operations rather than inheriting dead weight.                         │
// │                                                                          │
// │ ALTERNATIVE 1 — Flat independent classes, no shared base:              │
// │   Question, Answer, Comment are unrelated POJOs. Every service touching │
// │   voting or flagging must accept three separate overloads or use         │
// │   instanceof checks. Adding WikiPost requires updating every service    │
// │   method.                                                                │
// │                                                                          │
// │ ALTERNATIVE 2 — Single Post class with a 'type' enum field:            │
// │   Post { PostType type; String parentId; }. Simpler, but leaks type     │
// │   checks into every consumer: if (post.type == COMMENT) skip downvote.  │
// │   Logic that belongs in the type system ends up scattered in callers.   │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   Deep inheritance trees are harder to test in isolation and risk        │
// │   fragile base-class issues. We kept ContentNode thin — only shared     │
// │   state (id, authorId, body, voteCount, flags) — and pushed all         │
// │   type-specific behaviour into concrete classes and interfaces.          │
// └──────────────────────────────────────────────────────────────────────────┘
abstract class ContentNode implements Votable, Flaggable {

    protected final String id;
    protected final String authorId;
    protected final String body;
    protected final long   createdAt;

    // ┌──────────────────────────────────────────────────────────────────────┐
    // │ DD3 — AtomicInteger for vote counts (lock-free CAS)                  │
    // │                                                                       │
    // │ WHAT WE DID:                                                          │
    // │   incrementAndGet() / decrementAndGet() resolve in a single          │
    // │   hardware CAS instruction. No thread ever blocks waiting for a lock │
    // │   just to change a counter.                                           │
    // │                                                                       │
    // │ ALTERNATIVE 1 — synchronized(this) on upvote/downvote:              │
    // │   Correct, but exclusive mutex serializes ALL threads on the same    │
    // │   ContentNode, including readers calling getVoteCount(). Under high  │
    // │   read volume on a popular question this is a throughput cliff.      │
    // │                                                                       │
    // │ ALTERNATIVE 2 — volatile int + manual increment:                    │
    // │   volatile guarantees visibility but NOT atomicity. Two threads can  │
    // │   both read 5, both write 6, losing one increment — the classic      │
    // │   lost-update bug.                                                    │
    // │                                                                       │
    // │ ALTERNATIVE 3 — LongAdder:                                           │
    // │   Higher throughput under extreme write contention (stripes the      │
    // │   counter across cells). Tradeoff: sum() is not an instantaneous     │
    // │   snapshot — a concurrent increment may be transiently missed.       │
    // │   Acceptable for a page-view counter; not ideal for a vote count     │
    // │   where exact reads matter. We chose AtomicInteger for correctness.  │
    // │                                                                       │
    // │ TRADEOFF WE ACCEPTED:                                                 │
    // │   AtomicInteger gives exact reads at the cost of slightly lower      │
    // │   write throughput vs LongAdder. For vote counts on a single post    │
    // │   this is never the bottleneck.                                       │
    // └──────────────────────────────────────────────────────────────────────┘
    protected final AtomicInteger voteCount = new AtomicInteger(0);

    // ┌──────────────────────────────────────────────────────────────────────┐
    // │ DD4 — ConcurrentHashMap.newKeySet() for voter-ID dedup              │
    // │                                                                       │
    // │ WHAT WE DID:                                                          │
    // │   Two sets of voter IDs (upvoters, downvoters). add() returns false  │
    // │   if already present — idempotent duplicate rejection with O(1)      │
    // │   average time, zero external locking.                                │
    // │                                                                       │
    // │ ALTERNATIVE 1 — HashSet + synchronized block:                       │
    // │   Correct but holds a monitor lock across the add+remove+increment   │
    // │   sequence. Every concurrent vote on the same post serializes.       │
    // │                                                                       │
    // │ ALTERNATIVE 2 — Bloom filter:                                        │
    // │   Sub-linear memory, extremely fast. Tradeoff: false positives mean  │
    // │   a user who hasn't voted might be told they have. Acceptable for a  │
    // │   view counter; unacceptable for vote integrity. Rejected.           │
    // │                                                                       │
    // │ ALTERNATIVE 3 — DB-level unique constraint (userId, postId):        │
    // │   In a real system this is the authoritative guard. In-memory sets   │
    // │   here are the fast-path that prevents redundant DB round-trips.     │
    // │   Both layers coexist in production.                                  │
    // │                                                                       │
    // │ TRADEOFF WE ACCEPTED:                                                 │
    // │   Two sets per ContentNode means memory per post grows with voters.  │
    // │   For posts with millions of votes these sets are persisted to DB    │
    // │   and evicted from memory rather than kept fully in-process.         │
    // └──────────────────────────────────────────────────────────────────────┘
    protected final Set<String>                     upvoterIds   = ConcurrentHashMap.newKeySet();
    protected final Set<String>                     downvoterIds = ConcurrentHashMap.newKeySet();
    protected final CopyOnWriteArrayList<FlagEntry> flags        = new CopyOnWriteArrayList<>();

    protected ContentNode(String id, String authorId, String body) {
        this.id        = id;
        this.authorId  = authorId;
        this.body      = body;
        this.createdAt = System.currentTimeMillis();
    }

    @Override
    public boolean upvote(User voter) {
        if (voter.getId().equals(authorId)) return false;   // no self-vote
        if (upvoterIds.add(voter.getId())) {
            downvoterIds.remove(voter.getId());             // flip if previously downvoted
            voteCount.incrementAndGet();
            return true;
        }
        return false;
    }

    @Override
    public boolean downvote(User voter) {
        if (voter.getId().equals(authorId)) return false;
        if (downvoterIds.add(voter.getId())) {
            upvoterIds.remove(voter.getId());
            voteCount.decrementAndGet();
            return true;
        }
        return false;
    }

    @Override public int            getVoteCount() { return voteCount.get(); }
    @Override public void           flag(User reporter, FlagReason reason) { flags.add(new FlagEntry(reporter.getId(), reason)); }
    @Override public List<FlagEntry>getFlags()     { return Collections.unmodifiableList(flags); }

    public String getId()        { return id; }
    public String getAuthorId()  { return authorId; }
    public String getBody()      { return body; }
    public long   getCreatedAt() { return createdAt; }
}

class FlagEntry {
    final String     reporterId;
    final FlagReason reason;
    final long       timestamp;
    FlagEntry(String r, FlagReason f) {
        reporterId = r; reason = f; timestamp = System.currentTimeMillis();
    }
}

// ─────────────────────────────────────────────────────────────────
//  COMMENT — leaf node
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD5 — Enforcing upvote-only on Comment at the type boundary (R4)        │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   Comment.downvote() throws UnsupportedOperationException with a        │
// │   message naming the requirement. Comment does NOT implement Commentable │
// │   so addComment() doesn't exist on the type at all.                     │
// │                                                                          │
// │ ALTERNATIVE 1 — Silent no-op on downvote():                            │
// │   return false without throwing. Caller gets no signal the operation    │
// │   was illegal. The vote counter just never moves — hardest bug to find. │
// │                                                                          │
// │ ALTERNATIVE 2 — isDownvotable() guard method:                          │
// │   Comment.isDownvotable() returns false; caller checks before calling.  │
// │   Every call site must remember to check. One caller that forgets       │
// │   produces a silent no-op or exception. The type system should carry    │
// │   this guarantee, not caller discipline.                                 │
// │                                                                          │
// │ ALTERNATIVE 3 — Remove downvote() from ContentNode entirely:           │
// │   Only Question and Answer declare it. Fully ISP-correct. Tradeoff:    │
// │   VotingService methods wanting to downvote any ContentNode need a cast │
// │   or separate overloads. We accepted the throw as the lesser evil       │
// │   compared to polluting every voting call site with casts.              │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   ContentNode node = new Comment(); node.downvote() compiles but        │
// │   throws. This is a known, documented, localized wart. Any code path    │
// │   handing a Comment to a generic downvote() method is a caller bug      │
// │   caught immediately in testing, not silent data corruption.            │
// └──────────────────────────────────────────────────────────────────────────┘
class Comment extends ContentNode {

    Comment(String id, String authorId, String body) {
        super(id, authorId, body);
    }

    @Override
    public boolean downvote(User voter) {
        throw new UnsupportedOperationException("Comments support upvote only (R4)");
    }
}

// ─────────────────────────────────────────────────────────────────
//  ANSWER — intermediate composite node
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD6 — CopyOnWriteArrayList for Answer's comment list (read-heavy)       │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   COWL on comments inside Answer. Reads (rendering the answer page)     │
// │   take a zero-lock array snapshot. Writes (new comment) pay O(n) copy.  │
// │                                                                          │
// │ ALTERNATIVE 1 — Collections.synchronizedList:                          │
// │   Every read — even a simple size() — acquires a mutex. On a page       │
// │   rendering 50 answers with 10 comments each, that is 500 lock          │
// │   acquisitions just to display. Bad under read load.                    │
// │                                                                          │
// │ ALTERNATIVE 2 — ConcurrentLinkedDeque:                                 │
// │   O(1) append, O(1) concurrent reads via iterator. Better if comment   │
// │   write frequency were high. Tradeoff: no random-access by index;       │
// │   size() is O(n). For a read-dominant feature COWL wins on simplicity.  │
// │                                                                          │
// │ ALTERNATIVE 3 — Immutable list rebuilt on each write:                  │
// │   Functional style; reads always see a consistent snapshot. Copying on  │
// │   every comment is identical to COWL with more boilerplate.             │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   COWL's O(n) copy on write is fine because comments per answer are     │
// │   bounded (SO limits ~30 comments per post). If this were a chat thread │
// │   with thousands of writes per second, ConcurrentLinkedDeque or a       │
// │   segment-locked structure would be the right answer.                   │
// └──────────────────────────────────────────────────────────────────────────┘
class Answer extends ContentNode implements Commentable {

    private final String                        questionId;
    private volatile boolean                    accepted = false;
    private final CopyOnWriteArrayList<Comment> comments = new CopyOnWriteArrayList<>();

    Answer(String id, String questionId, String authorId, String body) {
        super(id, authorId, body);
        this.questionId = questionId;
    }

    @Override public void          addComment(Comment c) { comments.add(c); }
    @Override public List<Comment> getComments()         { return Collections.unmodifiableList(comments); }

    public void    accept()        { this.accepted = true; }
    public boolean isAccepted()    { return accepted; }
    public String  getQuestionId() { return questionId; }
}

// ─────────────────────────────────────────────────────────────────
//  QUESTION — root composite node
// ─────────────────────────────────────────────────────────────────

class Question extends ContentNode implements Commentable {

    private final String      title;
    private final Set<String> tags;

    // ┌──────────────────────────────────────────────────────────────────────┐
    // │ DD7 — Per-Question ReentrantReadWriteLock                            │
    // │                                                                       │
    // │ WHAT WE DID:                                                          │
    // │   One RW lock per Question instance. Multiple threads read the       │
    // │   answer list concurrently via readLock. Closing, deleting, or       │
    // │   adding an answer requires exclusive writeLock.                     │
    // │                                                                       │
    // │ ALTERNATIVE 1 — Single global lock for all questions:               │
    // │   Thread adding an answer to Question A blocks a thread reading      │
    // │   Question B. A single-threaded bottleneck across the entire system. │
    // │                                                                       │
    // │ ALTERNATIVE 2 — synchronized(this) on each method:                  │
    // │   Per-object, correct. But an exclusive mutex — concurrent reads     │
    // │   serialize too. A question with 10k readers/sec serves one at a     │
    // │   time.                                                               │
    // │                                                                       │
    // │ ALTERNATIVE 3 — StampedLock with optimistic reads:                  │
    // │   tryOptimisticRead() + validate() allows reads with no lock at all  │
    // │   when no write is in progress. Higher throughput for overwhelmingly  │
    // │   read-heavy access. Tradeoff: more complex code; must re-read on    │
    // │   validation failure. Worth it at Google-scale; overkill here.       │
    // │                                                                       │
    // │ TRADEOFF WE ACCEPTED:                                                 │
    // │   RRWL is the right balance: multiple concurrent readers, exclusive  │
    // │   writers, simple enough to explain in 30 seconds. This is the       │
    // │   SDE1→SDE2 concurrency differentiator — coarse global locking is   │
    // │   an automatic downgrade.                                             │
    // └──────────────────────────────────────────────────────────────────────┘
    private final ReentrantReadWriteLock lock   = new ReentrantReadWriteLock();
    private volatile QuestionStatus      status = QuestionStatus.OPEN;

    private final CopyOnWriteArrayList<Answer>  answers  = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Comment> comments = new CopyOnWriteArrayList<>();

    private final AtomicInteger closeVotes  = new AtomicInteger(0);
    private final AtomicInteger deleteVotes = new AtomicInteger(0);
    private static final int    VOTE_THRESHOLD = 5;

    // ┌──────────────────────────────────────────────────────────────────────┐
    // │ DD8 — answererIds set for targeted multi-subscriber notification (R8)│
    // │                                                                       │
    // │ WHAT WE DID:                                                          │
    // │   Question owns a ConcurrentHashMap.newKeySet() of user IDs who have │
    // │   answered it. When postAnswer() fires, NotificationService          │
    // │   subscribes the answerer to the question's notification stream.     │
    // │   A single publish(COMMENT_ON_QUESTION, questionId) fans out to both │
    // │   the question author and all answerers simultaneously.              │
    // │                                                                       │
    // │ ALTERNATIVE 1 — Store subscriber list inside NotificationService:   │
    // │   NotificationService tracks who answered which question. Problem:   │
    // │   the notification layer acquires domain knowledge (what "answering" │
    // │   means). Couples a cross-cutting concern to domain logic. Harder    │
    // │   to test and change independently.                                  │
    // │                                                                       │
    // │ ALTERNATIVE 2 — Scan answers list on each comment event:            │
    // │   On each comment, iterate answers to collect authorIds. O(n answers)│
    // │   on the hot notification path. On a popular question with 200       │
    // │   answers this is a linear scan per comment. The set buys O(1).     │
    // │                                                                       │
    // │ TRADEOFF WE ACCEPTED:                                                 │
    // │   answererIds grows with the number of unique answerers. In practice │
    // │   SO caps effective answers via community votes; unbounded growth is  │
    // │   not a realistic concern. The O(1) lookup is worth the memory.      │
    // └──────────────────────────────────────────────────────────────────────┘
    final Set<String> answererIds = ConcurrentHashMap.newKeySet();

    Question(String id, String authorId, String title, String body, Set<String> tags) {
        super(id, authorId, body);
        this.title = title;
        this.tags  = Collections.unmodifiableSet(new HashSet<>(tags));
    }

    public boolean addAnswer(Answer answer) {
        lock.writeLock().lock();
        try {
            if (status != QuestionStatus.OPEN) return false;
            answers.add(answer);
            answererIds.add(answer.getAuthorId());
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    @Override
    public void addComment(Comment comment) {
        lock.readLock().lock();
        try { comments.add(comment); }
        finally { lock.readLock().unlock(); }
    }

    public boolean voteToClose(User voter) {
        if (closeVotes.incrementAndGet() >= VOTE_THRESHOLD) {
            lock.writeLock().lock();
            try { status = QuestionStatus.CLOSED; }
            finally { lock.writeLock().unlock(); }
            return true;
        }
        return false;
    }

    public boolean voteToDelete(User voter) {
        if (deleteVotes.incrementAndGet() >= VOTE_THRESHOLD) {
            lock.writeLock().lock();
            try { status = QuestionStatus.DELETED; }
            finally { lock.writeLock().unlock(); }
            return true;
        }
        return false;
    }

    public List<Answer> getAnswers() {
        lock.readLock().lock();
        try { return Collections.unmodifiableList(answers); }
        finally { lock.readLock().unlock(); }
    }

    @Override public List<Comment> getComments() { return Collections.unmodifiableList(comments); }

    public void setStatus(QuestionStatus s) {
        lock.writeLock().lock();
        try { this.status = s; }
        finally { lock.writeLock().unlock(); }
    }

    public QuestionStatus getStatus() { return status; }
    public String         getTitle()  { return title; }
    public Set<String>    getTags()   { return tags; }
}

// ─────────────────────────────────────────────────────────────────
//  DECORATOR PATTERN — Bounty  (R6)
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD9 — Decorator for Bounty instead of a nullable field on Question (R6) │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   BountyDecorator wraps an existing Question, adding expiry logic and   │
// │   award behaviour. Created on bounty start, discarded on expiry/award.  │
// │   Question has zero bounty awareness.                                   │
// │                                                                          │
// │ ALTERNATIVE 1 — bountyAmount + expiryTimestamp nullable fields on Q:   │
// │   The simplest approach. Problems: (a) every Question object carries    │
// │   bounty state even though less than 0.1% of questions have bounties    │
// │   — memory waste at scale. (b) Adding FeaturedDecorator or Sponsored   │
// │   Decorator later means adding more nullable fields — OCP violation.    │
// │                                                                          │
// │ ALTERNATIVE 2 — BountyService holding Map<questionId, BountyInfo>:     │
// │   Completely decoupled. Tradeoff: callers checking "does this question  │
// │   have a bounty?" must query a separate service. Bounty state is not    │
// │   collocated with the question. Works well in microservices; awkward    │
// │   in a single-process LLD where colocation aids readability.            │
// │                                                                          │
// │ ALTERNATIVE 3 — Strategy for bounty behaviour:                         │
// │   Question holds BountyStrategy (NoBounty vs ActiveBounty). Cleaner     │
// │   than Decorator for a single axis of variation, but Decorator is       │
// │   better when you need stacking: BountyDecorator + FeaturedDecorator    │
// │   on the same question without class explosion.                          │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   QuestionDecorator must delegate every method of ContentNode and       │
// │   Commentable to the wrapped Question — boilerplate delegation. This    │
// │   is the canonical Decorator cost. Worth it because Question remains    │
// │   unchanged and new decorators stack freely without modifying any       │
// │   existing class.                                                        │
// └──────────────────────────────────────────────────────────────────────────┘
abstract class QuestionDecorator extends ContentNode implements Commentable {
    protected final Question wrapped;

    QuestionDecorator(Question w) {
        super(w.getId(), w.getAuthorId(), w.getBody());
        this.wrapped = w;
    }

    @Override public boolean         upvote(User v)             { return wrapped.upvote(v); }
    @Override public boolean         downvote(User v)           { return wrapped.downvote(v); }
    @Override public int             getVoteCount()             { return wrapped.getVoteCount(); }
    @Override public void            flag(User r, FlagReason f) { wrapped.flag(r, f); }
    @Override public List<FlagEntry> getFlags()                 { return wrapped.getFlags(); }
    @Override public void            addComment(Comment c)      { wrapped.addComment(c); }
    @Override public List<Comment>   getComments()              { return wrapped.getComments(); }

    public Question getWrapped()         { return wrapped; }
    public abstract int     getBountyAmount();
    public abstract boolean isExpired();
}

class BountyDecorator extends QuestionDecorator {

    private final int        reputationAmount;
    private final long       expiryTimestamp;
    private volatile String  awardedToUserId = null;

    BountyDecorator(Question q, int repAmount, long durationMs) {
        super(q);
        this.reputationAmount = repAmount;
        this.expiryTimestamp  = System.currentTimeMillis() + durationMs;
    }

    @Override public int     getBountyAmount() { return reputationAmount; }
    @Override public boolean isExpired()       { return System.currentTimeMillis() > expiryTimestamp; }

    public boolean awardBounty(String recipientUserId) {
        if (awardedToUserId != null || isExpired()) return false;
        awardedToUserId = recipientUserId;
        return true;
    }

    public String getAwardedToUserId() { return awardedToUserId; }
    public long   getExpiryTimestamp() { return expiryTimestamp; }
}

// ─────────────────────────────────────────────────────────────────
//  OBSERVER PATTERN — Notifications  (R8)
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD10 — Content-scoped subscriptions over a global observer list         │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   NotificationService holds ConcurrentHashMap<contentId, Set<userId>>.  │
// │   publish(event) fans out only to subscribers of event.contentId —      │
// │   O(subscribers of that post), not O(all users in the system).          │
// │                                                                          │
// │ ALTERNATIVE 1 — Global observer list (classic textbook Observer):      │
// │   One List<NotificationObserver> for all events. Every publish          │
// │   iterates all registered users. At 50M users this is 50M object        │
// │   touches for every vote and comment event. Does not scale.             │
// │                                                                          │
// │ ALTERNATIVE 2 — Event bus / message queue (Kafka, RabbitMQ):           │
// │   Users subscribe to topics. Broker handles fan-out and persistence.    │
// │   Correct at production scale. Tradeoff: massively out of scope for     │
// │   an LLD round. Mention as scale-out path to score talking-points.      │
// │                                                                          │
// │ ALTERNATIVE 3 — Push via WebSocket per user:                           │
// │   Each user holds an open WebSocket; publish() writes to the socket     │
// │   directly. This is the delivery mechanism, orthogonal to routing       │
// │   logic. Can be plugged in behind onNotification() without changing     │
// │   the subscription or publish model.                                    │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   Content-scoped subscriptions require StackOverflow.postQuestion and   │
// │   postAnswer to call notificationService.subscribe() explicitly. This   │
// │   couples the facade to the notification service at the write path.     │
// │   The alternative — NotificationService reads Question.answererIds      │
// │   directly — inverts the coupling direction but leaks domain knowledge  │
// │   into the notification layer. Explicit subscribe calls at the service  │
// │   layer is the cleaner boundary.                                         │
// └──────────────────────────────────────────────────────────────────────────┘
class NotificationEvent {
    final NotificationType type;
    final String           contentId;
    final String           actorId;
    final String           message;
    final long             timestamp;

    NotificationEvent(NotificationType t, String cid, String aid, String msg) {
        type = t; contentId = cid; actorId = aid; message = msg;
        timestamp = System.currentTimeMillis();
    }
}

interface NotificationObserver {
    void   onNotification(NotificationEvent event);
    String getObserverId();
}

class UserNotificationObserver implements NotificationObserver {
    private final User                                    user;
    private final CopyOnWriteArrayList<NotificationEvent> inbox = new CopyOnWriteArrayList<>();

    UserNotificationObserver(User user) { this.user = user; }

    @Override
    public void onNotification(NotificationEvent event) {
        inbox.add(event);
        System.out.printf("[NOTIFY → %-8s] %-22s : %s%n",
            user.getName(), event.type, event.message);
    }

    @Override public String               getObserverId() { return user.getId(); }
    public List<NotificationEvent>        getInbox()      { return Collections.unmodifiableList(inbox); }
}

class NotificationService {
    private final ConcurrentHashMap<String, Set<String>>           subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NotificationObserver>  observers     = new ConcurrentHashMap<>();

    public void registerObserver(NotificationObserver obs) {
        observers.put(obs.getObserverId(), obs);
    }

    public void subscribe(String observerId, String contentId) {
        subscriptions.computeIfAbsent(contentId, k -> ConcurrentHashMap.newKeySet()).add(observerId);
    }

    public void unsubscribe(String observerId, String contentId) {
        Set<String> subs = subscriptions.get(contentId);
        if (subs != null) subs.remove(observerId);
    }

    public void publish(NotificationEvent event) {
        Set<String> subscribers = subscriptions.getOrDefault(event.contentId, Collections.emptySet());
        for (String id : subscribers) {
            if (id.equals(event.actorId)) continue;   // actor never notifies themselves
            NotificationObserver obs = observers.get(id);
            if (obs != null) obs.onNotification(event);
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  STRATEGY PATTERN — Search  (R1)
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD11 — Strategy for search: pluggable, OCP-compliant (R1)               │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   SearchService holds a SearchStrategy reference swapped at runtime.    │
// │   Adding full-text or fuzzy search requires only a new class — zero     │
// │   changes to SearchService or callers. DELETED filtering lives inside   │
// │   each strategy so guests never see deleted posts without caller guards.│
// │                                                                          │
// │ ALTERNATIVE 1 — if/else switch on SearchType enum in one method:       │
// │   search(String q, SearchType type). Simple but violates OCP: every     │
// │   new search mode edits the same method. Signals extensibility was not  │
// │   considered.                                                            │
// │                                                                          │
// │ ALTERNATIVE 2 — Three independent methods with no shared interface:    │
// │   searchByTag, searchByKeyword, searchByUser directly in StackOverflow. │
// │   Honest and readable. Tradeoff: DELETED-filtering and guest-visibility │
// │   logic must be duplicated across all three. Strategy centralises it.   │
// │                                                                          │
// │ ALTERNATIVE 3 — Specification pattern:                                  │
// │   Compose predicates: TagSpec.and(NotDeletedSpec). Very powerful for    │
// │   arbitrary filter combinations. Tradeoff: more infrastructure for only │
// │   three well-known search modes. Strategy is proportionate; mention     │
// │   Specification as the scale-up path.                                   │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   setStrategy() is not thread-safe — two concurrent search calls with   │
// │   different strategies could race on the stored reference. In           │
// │   production, SearchService would be stateless (strategy passed as a    │
// │   parameter, not stored). Flagging this is a strong hire talking point. │
// └──────────────────────────────────────────────────────────────────────────┘
interface SearchStrategy {
    List<Question> search(String query, Collection<Question> questions);
}

class SearchByTagStrategy implements SearchStrategy {
    @Override
    public List<Question> search(String query, Collection<Question> all) {
        String q = query.toLowerCase();
        return all.stream()
            .filter(x -> x.getStatus() != QuestionStatus.DELETED)
            .filter(x -> x.getTags().stream().anyMatch(t -> t.toLowerCase().contains(q)))
            .collect(Collectors.toList());
    }
}

class SearchByKeywordStrategy implements SearchStrategy {
    @Override
    public List<Question> search(String query, Collection<Question> all) {
        String q = query.toLowerCase();
        return all.stream()
            .filter(x -> x.getStatus() != QuestionStatus.DELETED)
            .filter(x -> x.getTitle().toLowerCase().contains(q)
                      || x.getBody().toLowerCase().contains(q))
            .collect(Collectors.toList());
    }
}

class SearchByUserStrategy implements SearchStrategy {
    private final ConcurrentHashMap<String, User> userRegistry;
    SearchByUserStrategy(ConcurrentHashMap<String, User> reg) { this.userRegistry = reg; }

    @Override
    public List<Question> search(String query, Collection<Question> all) {
        Set<String> matchIds = userRegistry.values().stream()
            .filter(u -> u.getName().toLowerCase().contains(query.toLowerCase()))
            .map(User::getId)
            .collect(Collectors.toSet());
        return all.stream()
            .filter(x -> x.getStatus() != QuestionStatus.DELETED)
            .filter(x -> matchIds.contains(x.getAuthorId()))
            .collect(Collectors.toList());
    }
}

class SearchService {
    private SearchStrategy strategy;
    SearchService(SearchStrategy s) { this.strategy = s; }
    public void           setStrategy(SearchStrategy s) { this.strategy = s; }
    public List<Question> search(String q, Collection<Question> all) { return strategy.search(q, all); }
}

// ─────────────────────────────────────────────────────────────────
//  TAG REGISTRY — most popular tags  (R11)
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD12 — TagRegistry with O(n log k) top-K retrieval via min-heap (R11)   │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   ConcurrentHashMap<tag, AtomicInteger> for thread-safe frequency        │
// │   counting. getTopTags(k) uses a size-k min-heap — O(n log k) vs the   │
// │   O(n log n) of sorting the full map.                                    │
// │                                                                          │
// │ ALTERNATIVE 1 — Sort the full map on each getTopTags() call:           │
// │   Simple. O(n log n) per call. Fine for 100k tags queried once per      │
// │   minute; too slow for a dashboard refreshing every second.             │
// │                                                                          │
// │ ALTERNATIVE 2 — TreeMap<Integer, List<String>> sorted by count:        │
// │   O(log n) insert/update. Subtle to maintain: when a tag's count goes   │
// │   5→6 you remove from bucket 5 and add to bucket 6 — race conditions   │
// │   unless the entire update is atomic. AtomicInteger + min-heap on read  │
// │   is simpler and correct.                                                │
// │                                                                          │
// │ ALTERNATIVE 3 — Redis sorted set (ZINCRBY + ZREVRANGE):                │
// │   O(log n) insert, O(k) read, distributed, persistent. The right        │
// │   answer in a production system. Mention as scale-out path; in-memory   │
// │   TagRegistry is the single-process implementation.                     │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   Tag frequency lives in TagRegistry, not in Question. Deliberate       │
// │   separation: Question owns its tags (content model); TagRegistry owns  │
// │   their frequencies (analytics model). The coupling point is            │
// │   StackOverflow.postQuestion calling tagRegistry.registerTags() —       │
// │   one explicit seam that is easy to test and replace.                   │
// └──────────────────────────────────────────────────────────────────────────┘
class TagRegistry {
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    public void registerTags(Set<String> tags) {
        tags.forEach(t -> counts.computeIfAbsent(t, k -> new AtomicInteger(0)).incrementAndGet());
    }

    public void deregisterTags(Set<String> tags) {
        tags.forEach(t -> { AtomicInteger c = counts.get(t); if (c != null) c.decrementAndGet(); });
    }

    public List<String> getTopTags(int k) {
        PriorityQueue<Map.Entry<String, AtomicInteger>> minHeap =
            new PriorityQueue<>(Comparator.comparingInt(e -> e.getValue().get()));
        for (Map.Entry<String, AtomicInteger> e : counts.entrySet()) {
            minHeap.offer(e);
            if (minHeap.size() > k) minHeap.poll();
        }
        LinkedList<String> result = new LinkedList<>();
        while (!minHeap.isEmpty()) result.addFirst(minHeap.poll().getKey());
        return result;
    }
}

// ─────────────────────────────────────────────────────────────────
//  ACCOUNTS
// ─────────────────────────────────────────────────────────────────

abstract class Account {
    protected final String        id;
    protected final String        name;
    protected final AccountType   type;
    protected volatile AccountStatus status;

    Account(String name, AccountType type) {
        this.id     = UUID.randomUUID().toString();
        this.name   = name;
        this.type   = type;
        this.status = AccountStatus.ACTIVE;
    }

    public String        getId()     { return id; }
    public String        getName()   { return name; }
    public AccountType   getType()   { return type; }
    public AccountStatus getStatus() { return status; }
}

class Guest extends Account {
    Guest() { super("Guest-" + UUID.randomUUID().toString().substring(0, 8), AccountType.GUEST); }
}

class User extends Account {

    private final String                          email;
    private final AtomicInteger                   reputationScore = new AtomicInteger(0);
    private final CopyOnWriteArrayList<BadgeType> badges          = new CopyOnWriteArrayList<>();
    private final Set<String>                     postedQIds      = ConcurrentHashMap.newKeySet();
    private final Set<String>                     postedAIds      = ConcurrentHashMap.newKeySet();

    User(String name, String email) {
        super(name, AccountType.USER);
        this.email = email;
    }

    // ┌──────────────────────────────────────────────────────────────────────┐
    // │ DD13 — Inline badge threshold check triggered by each rep delta (R9) │
    // │                                                                       │
    // │ WHAT WE DID:                                                          │
    // │   addReputation() calls checkAndAwardBadges() immediately after the  │
    // │   AtomicInteger update. Badge award latency equals reputation event   │
    // │   latency. Idempotent via badges.contains() check.                   │
    // │                                                                       │
    // │ ALTERNATIVE 1 — Scheduled polling job (e.g., every 5 minutes):      │
    // │   Background thread scans all users and checks thresholds. Tradeoff: │
    // │   badges are awarded up to 5 min late. Users crossing a threshold    │
    // │   wait for the next sweep — bad UX. Also O(n users) work repeatedly. │
    // │                                                                       │
    // │ ALTERNATIVE 2 — BadgeService as a separate Observer on rep events:  │
    // │   ReputationService publishes REP_CHANGED; BadgeService subscribes   │
    // │   and checks thresholds. Clean separation — User doesn't know about  │
    // │   badges. Tradeoff: one more layer of indirection. Right split for a │
    // │   microservice architecture; adds complexity without benefit in LLD. │
    // │                                                                       │
    // │ ALTERNATIVE 3 — Badge as a Decorator on User:                       │
    // │   BadgedUser wraps User and adds badge logic. Excessive — badge      │
    // │   state is just a list, not a behaviourally different type.          │
    // │   Decorator is for adding behaviour; a field suffices here.          │
    // │                                                                       │
    // │ TRADEOFF WE ACCEPTED:                                                 │
    // │   checkAndAwardBadges() runs on every rep delta — including small    │
    // │   ones that can't cross a threshold. Cost is O(BadgeType.values()    │
    // │   .length) = O(4), constant. Negligible and correct.                 │
    // └──────────────────────────────────────────────────────────────────────┘
    public int addReputation(int delta) {
        int newScore = reputationScore.addAndGet(delta);
        checkAndAwardBadges(newScore);
        return newScore;
    }

    private void checkAndAwardBadges(int score) {
        for (BadgeType b : BadgeType.values()) {
            if (score >= b.reputationThreshold && !badges.contains(b)) badges.add(b);
        }
    }

    public int             getReputationScore() { return reputationScore.get(); }
    public List<BadgeType> getBadges()           { return Collections.unmodifiableList(badges); }
    public String          getEmail()            { return email; }
    public void            trackQuestion(String qId) { postedQIds.add(qId); }
    public void            trackAnswer(String aId)   { postedAIds.add(aId); }
    public Set<String>     getPostedQuestionIds()    { return Collections.unmodifiableSet(postedQIds); }
}

class Moderator extends Account {
    Moderator(String name, String email) { super(name, AccountType.MODERATOR); }

    public void closeQuestion(Question q)          { q.setStatus(QuestionStatus.CLOSED); }
    public void restoreQuestion(Question q)        { q.setStatus(QuestionStatus.OPEN); }
    public void deleteAnswer(Answer a, Question q) {
        System.out.printf("[MOD] Answer %s removed by moderator %s%n", a.getId(), this.getName());
    }
}

// ─────────────────────────────────────────────────────────────────
//  REPUTATION SERVICE — Façade over all rep-changing events
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD14 — ReputationService as Façade: single seam for all rep changes     │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   All rep-changing events (upvote Q/A, downvote Q/A, accept) route      │
// │   through ReputationService. Rep deltas are constants in one place.     │
// │   Vote notifications are also fired here, not scattered at call sites.  │
// │                                                                          │
// │ ALTERNATIVE 1 — Call user.addReputation() inline in StackOverflow:     │
// │   upvoteQuestion() computes +5 and calls addReputation(5) directly.    │
// │   Simple. Problem: daily rep caps (+200/day on real SO), anti-gaming    │
// │   logic (ignore votes from accounts < 15 rep), and decay rules would   │
// │   scatter across every voting method. One Façade is the only            │
// │   maintainable home for these cross-cutting rules.                      │
// │                                                                          │
// │ ALTERNATIVE 2 — Event sourcing (rep computed from vote event log):     │
// │   Never store an integer; replay vote events to derive score on demand. │
// │   Enables time-travel queries. Tradeoff: read latency for current score │
// │   unless cached. Out of scope for LLD; strong architecture talking point.│
// │                                                                          │
// │ ALTERNATIVE 3 — Each ContentNode manages its author's reputation:      │
// │   Question.upvote() fetches the author User and calls addReputation().  │
// │   Problem: content nodes should not know about the user registry —      │
// │   circular dependency (User owns Questions; Question references User).  │
// │   ReputationService is the neutral third party that breaks the cycle.   │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   ReputationService holds a reference to userRegistry (CHM). It is a   │
// │   read-only consumer — it never adds/removes users. This shared-ref     │
// │   design is correct but means ReputationService cannot be unit-tested   │
// │   without a populated registry. Injecting a UserRepository interface    │
// │   would make it fully testable in isolation.                             │
// └──────────────────────────────────────────────────────────────────────────┘
class ReputationService {

    private static final int UPVOTE_Q_DELTA   = +5;
    private static final int DOWNVOTE_Q_DELTA = -2;
    private static final int UPVOTE_A_DELTA   = +10;
    private static final int DOWNVOTE_A_DELTA = -2;
    private static final int ACCEPTED_DELTA   = +15;
    private static final int DOWNVOTE_COST    = -1;  // voter pays 1 rep to downvote

    private final ConcurrentHashMap<String, User> userRegistry;
    private final NotificationService             ns;

    ReputationService(ConcurrentHashMap<String, User> reg, NotificationService ns) {
        this.userRegistry = reg;
        this.ns           = ns;
    }

    public void onQuestionUpvoted  (Question q, User voter) { applyRep(q.getAuthorId(), UPVOTE_Q_DELTA,   q.getId(), voter.getId(), VoteType.UPVOTE);   }
    public void onQuestionDownvoted(Question q, User voter) { applyRep(q.getAuthorId(), DOWNVOTE_Q_DELTA, q.getId(), voter.getId(), VoteType.DOWNVOTE); applyRep(voter.getId(), DOWNVOTE_COST, null, null, null); }
    public void onAnswerUpvoted    (Answer a,   User voter) { applyRep(a.getAuthorId(), UPVOTE_A_DELTA,   a.getId(), voter.getId(), VoteType.UPVOTE);   }
    public void onAnswerDownvoted  (Answer a,   User voter) { applyRep(a.getAuthorId(), DOWNVOTE_A_DELTA, a.getId(), voter.getId(), VoteType.DOWNVOTE); applyRep(voter.getId(), DOWNVOTE_COST, null, null, null); }
    public void onAnswerAccepted   (Answer a)               { applyRep(a.getAuthorId(), ACCEPTED_DELTA,   a.getId(), null, null); }

    private void applyRep(String userId, int delta, String contentId, String actorId, VoteType vt) {
        User u = userRegistry.get(userId);
        if (u == null) return;
        int oldBadgeCount = u.getBadges().size();
        u.addReputation(delta);
        int newBadgeCount = u.getBadges().size();

        if (contentId != null && vt != null) {
            ns.publish(new NotificationEvent(NotificationType.VOTE_RECEIVED, contentId, actorId,
                "Your post received a " + vt.name().toLowerCase()));
        }
        if (newBadgeCount > oldBadgeCount) {
            BadgeType earned = u.getBadges().get(newBadgeCount - 1);
            ns.publish(new NotificationEvent(NotificationType.BADGE_EARNED, userId, userId,
                "You earned the " + earned.name() + " badge! (" + earned.description + ")"));
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  CATALOG — primary store + inverted indexes
// ─────────────────────────────────────────────────────────────────

// ┌──────────────────────────────────────────────────────────────────────────┐
// │ DD15 — Two-layer Catalog: primary store + inverted indexes              │
// │                                                                          │
// │ WHAT WE DID:                                                             │
// │   questionsById (ConcurrentHashMap) is the primary O(1) store.          │
// │   byTag and byUser are secondary inverted indexes (CHM → COWL) built    │
// │   at write time. Hot path (fetch by ID) never touches indexes.          │
// │   SearchService then applies a Strategy on the index's collection —     │
// │   Catalog indexes, Strategy filters: clean separation.                  │
// │                                                                          │
// │ ALTERNATIVE 1 — No secondary indexes, full scan on every search:       │
// │   searchByTag iterates all 50M questions and checks tags. O(n) per      │
// │   query — unusable at any realistic scale.                              │
// │                                                                          │
// │ ALTERNATIVE 2 — Single map with composite keys ("tag:java", "user:x"):  │
// │   Consolidates into one data structure. Tradeoff: key construction is   │
// │   error-prone; tag namespace may collide with user namespace. Separate  │
// │   maps are clearer and type-safe.                                        │
// │                                                                          │
// │ ALTERNATIVE 3 — Delegate all search to an external engine:             │
// │   Catalog is the primary store; all search goes to Elasticsearch via    │
// │   a SearchStrategy adapter. Correct for production. In-memory indexes   │
// │   serve as the fast-path / fallback layer for the LLD scope.            │
// │                                                                          │
// │ TRADEOFF WE ACCEPTED:                                                    │
// │   byTag and byUser are not automatically consistent with questionsById   │
// │   under concurrent deletes: a question removed from questionsById may   │
// │   transiently still appear in byTag. Fixed in production by wrapping    │
// │   the composite delete in a write lock, or by treating index entries    │
// │   as soft references that verify against questionsById before returning. │
// │   Naming this tradeoff explicitly in an interview is a strong hire      │
// │   signal — it shows you know where your design has rough edges.         │
// └──────────────────────────────────────────────────────────────────────────┘
class Catalog {
    private final ConcurrentHashMap<String, Question>                       questionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Question>> byTag         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Question>> byUser        = new ConcurrentHashMap<>();

    public void addQuestion(Question q) {
        questionsById.put(q.getId(), q);
        q.getTags().forEach(t -> byTag.computeIfAbsent(t, k -> new CopyOnWriteArrayList<>()).add(q));
        byUser.computeIfAbsent(q.getAuthorId(), k -> new CopyOnWriteArrayList<>()).add(q);
    }

    public void removeQuestion(String qId) {
        Question q = questionsById.remove(qId);
        if (q == null) return;
        q.getTags().forEach(t -> { CopyOnWriteArrayList<Question> l = byTag.get(t);  if (l != null) l.remove(q); });
        CopyOnWriteArrayList<Question> ul = byUser.get(q.getAuthorId()); if (ul != null) ul.remove(q);
    }

    public Question            getById(String id) { return questionsById.get(id); }
    public Collection<Question>getAllQuestions()   { return questionsById.values(); }
}

// ─────────────────────────────────────────────────────────────────
//  STACKOVERFLOW FACADE — Singleton  (R1–R11 orchestration)
// ─────────────────────────────────────────────────────────────────

public class StackOverflow {

    // Double-checked locking: volatile ensures the partially constructed
    // instance is never visible to threads that skip the synchronized block.
    private static volatile StackOverflow instance;

    private final ConcurrentHashMap<String, User>            userRegistry   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Moderator>       modRegistry    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BountyDecorator> activeBounties = new ConcurrentHashMap<>();

    private final Catalog             catalog;
    private final TagRegistry         tagRegistry;
    private final NotificationService notificationService;
    private final ReputationService   reputationService;
    private final SearchService       searchService;

    private StackOverflow() {
        catalog             = new Catalog();
        tagRegistry         = new TagRegistry();
        notificationService = new NotificationService();
        reputationService   = new ReputationService(userRegistry, notificationService);
        searchService       = new SearchService(new SearchByKeywordStrategy());
    }

    public static StackOverflow getInstance() {
        if (instance == null) {
            synchronized (StackOverflow.class) {
                if (instance == null) instance = new StackOverflow();
            }
        }
        return instance;
    }

    // ── User management ──────────────────────────────────────────────────────

    public User registerUser(String name, String email) {
        User user = new User(name, email);
        userRegistry.put(user.getId(), user);
        notificationService.registerObserver(new UserNotificationObserver(user));
        return user;
    }

    public Moderator registerModerator(String name, String email) {
        Moderator mod = new Moderator(name, email);
        modRegistry.put(mod.getId(), mod);
        return mod;
    }

    // ── Questions (R2, R10) ───────────────────────────────────────────────────

    public Question postQuestion(User user, String title, String body, Set<String> tags) {
        String   id = UUID.randomUUID().toString();
        Question q  = new Question(id, user.getId(), title, body, tags);
        catalog.addQuestion(q);
        tagRegistry.registerTags(tags);
        user.trackQuestion(id);
        notificationService.subscribe(user.getId(), id);   // author auto-subscribes
        return q;
    }

    // ── Answers (R2, R8) ─────────────────────────────────────────────────────

    public Answer postAnswer(User user, String questionId, String body) {
        Question q = catalog.getById(questionId);
        if (q == null) throw new IllegalArgumentException("Question not found: " + questionId);

        String answerId = UUID.randomUUID().toString();
        Answer answer   = new Answer(answerId, questionId, user.getId(), body);
        if (!q.addAnswer(answer)) throw new IllegalStateException("Question is closed/deleted");

        user.trackAnswer(answerId);

        // Answerer subscribes to the question's stream — any future
        // COMMENT_ON_QUESTION event will reach both the author (subscribed
        // at postQuestion time) and this answerer via a single publish() call.
        notificationService.subscribe(user.getId(), questionId);

        notificationService.publish(new NotificationEvent(
            NotificationType.ANSWER_POSTED, questionId, user.getId(),
            user.getName() + " answered your question"
        ));
        return answer;
    }

    // ── Comments (R4, R8) ────────────────────────────────────────────────────

    public Comment addCommentToQuestion(User commenter, String questionId, String body) {
        Question q = catalog.getById(questionId);
        if (q == null) throw new IllegalArgumentException("Question not found");
        Comment c = new Comment(UUID.randomUUID().toString(), commenter.getId(), body);
        q.addComment(c);
        // One publish reaches question author AND all answerers (both subscribed to questionId)
        notificationService.publish(new NotificationEvent(
            NotificationType.COMMENT_ON_QUESTION, questionId, commenter.getId(),
            commenter.getName() + " commented on a question you're watching"
        ));
        return c;
    }

    public Comment addCommentToAnswer(User commenter, Answer answer, String body) {
        Comment c = new Comment(UUID.randomUUID().toString(), commenter.getId(), body);
        answer.addComment(c);
        notificationService.subscribe(answer.getAuthorId(), answer.getId());
        notificationService.publish(new NotificationEvent(
            NotificationType.COMMENT_ON_ANSWER, answer.getId(), commenter.getId(),
            commenter.getName() + " commented on your answer"
        ));
        return c;
    }

    // ── Voting (R4, R8) ──────────────────────────────────────────────────────

    public void upvoteQuestion  (User v, String qId) { Question q = catalog.getById(qId); if (q != null && q.upvote(v))   reputationService.onQuestionUpvoted(q, v);   }
    public void downvoteQuestion(User v, String qId) { Question q = catalog.getById(qId); if (q != null && q.downvote(v)) reputationService.onQuestionDownvoted(q, v); }
    public void upvoteAnswer    (User v, Answer a)   { if (a.upvote(v))   reputationService.onAnswerUpvoted(a, v);   }
    public void downvoteAnswer  (User v, Answer a)   { if (a.downvote(v)) reputationService.onAnswerDownvoted(a, v); }
    public void upvoteComment   (User v, Comment c)  { c.upvote(v); /* no rep change per SO rules */ }

    // ── Accept answer ────────────────────────────────────────────────────────

    public void acceptAnswer(User questionAuthor, Answer answer) {
        Question q = catalog.getById(answer.getQuestionId());
        if (q == null || !q.getAuthorId().equals(questionAuthor.getId())) return;
        answer.accept();
        reputationService.onAnswerAccepted(answer);
    }

    // ── Bounty (R6) ──────────────────────────────────────────────────────────

    public BountyDecorator addBounty(User user, String questionId, int repAmount, long durationMs) {
        Question q = catalog.getById(questionId);
        if (q == null || !q.getAuthorId().equals(user.getId())) return null;
        if (user.getReputationScore() < repAmount) return null;
        user.addReputation(-repAmount);   // rep deducted immediately on bounty start
        BountyDecorator bounty = new BountyDecorator(q, repAmount, durationMs);
        activeBounties.put(questionId, bounty);
        return bounty;
    }

    public void awardBounty(String questionId, Answer winningAnswer) {
        BountyDecorator bounty = activeBounties.remove(questionId);
        if (bounty == null || bounty.isExpired()) return;
        if (bounty.awardBounty(winningAnswer.getAuthorId())) {
            User recipient = userRegistry.get(winningAnswer.getAuthorId());
            if (recipient != null) recipient.addReputation(bounty.getBountyAmount());
        }
    }

    // ── Flagging (R3) ────────────────────────────────────────────────────────

    public void flagQuestion(User reporter, String questionId, FlagReason reason) {
        Question q = catalog.getById(questionId); if (q != null) q.flag(reporter, reason);
    }
    public void flagAnswer (User reporter, Answer a,  FlagReason reason) { a.flag(reporter, reason); }
    public void flagComment(User reporter, Comment c, FlagReason reason) { c.flag(reporter, reason); }

    // ── Community close/delete votes (R5) ────────────────────────────────────

    public void voteToClose (User voter, String qId) { Question q = catalog.getById(qId); if (q != null) q.voteToClose(voter);  }
    public void voteToDelete(User voter, String qId) { Question q = catalog.getById(qId); if (q != null) q.voteToDelete(voter); }

    // ── Moderator actions (R7) ───────────────────────────────────────────────

    public void modClose      (Moderator m, String qId) { Question q = catalog.getById(qId); if (q != null) m.closeQuestion(q);   }
    public void modRestore    (Moderator m, String qId) { Question q = catalog.getById(qId); if (q != null) m.restoreQuestion(q); }
    public void modDeleteAnswer(Moderator m, Answer a)  { Question q = catalog.getById(a.getQuestionId()); if (q != null) m.deleteAnswer(a, q); }

    // ── Search (R1) ──────────────────────────────────────────────────────────

    public List<Question> searchByTag    (String tag)  { searchService.setStrategy(new SearchByTagStrategy());                 return searchService.search(tag,      catalog.getAllQuestions()); }
    public List<Question> searchByKeyword(String kw)   { searchService.setStrategy(new SearchByKeywordStrategy());             return searchService.search(kw,       catalog.getAllQuestions()); }
    public List<Question> searchByUser   (String name) { searchService.setStrategy(new SearchByUserStrategy(userRegistry));    return searchService.search(name,     catalog.getAllQuestions()); }

    // ── Popular tags (R11) ───────────────────────────────────────────────────

    public List<String> getTopTags(int k)              { return tagRegistry.getTopTags(k); }
    public Question     getQuestion(String questionId) { return catalog.getById(questionId); }

    // ─────────────────────────────────────────────────────────────────────────
    //  DEMO — key scenario: Alice posts → Bob answers → Carol comments
    //  Expected: BOTH Alice (question author) AND Bob (answerer) notified
    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        StackOverflow so = StackOverflow.getInstance();

        User      alice = so.registerUser("Alice", "alice@example.com");
        User      bob   = so.registerUser("Bob",   "bob@example.com");
        User      carol = so.registerUser("Carol", "carol@example.com");
        Moderator mike  = so.registerModerator("ModMike", "mod@so.com");

        System.out.println("═══ Alice posts a question ═══");
        Question q = so.postQuestion(alice,
            "How does HashMap work in Java?",
            "I'm confused about hash collision handling...",
            new HashSet<>(Arrays.asList("java", "hashmap", "data-structures")));

        System.out.println("\n═══ Bob answers ═══");
        Answer a = so.postAnswer(bob, q.getId(),
            "HashMap uses an array of linked lists; on collision it chains entries...");

        System.out.println("\n═══ Carol comments on the question ═══");
        System.out.println("    ↳ Alice (posted it) AND Bob (answered it) should be notified");
        so.addCommentToQuestion(carol, q.getId(), "Really helpful framing of the question!");

        System.out.println("\n═══ Carol upvotes Alice's question (+5 rep) ═══");
        so.upvoteQuestion(carol, q.getId());

        System.out.println("\n═══ Carol upvotes Bob's answer (+10 rep) ═══");
        so.upvoteAnswer(carol, a);

        System.out.println("\n═══ Alice accepts Bob's answer (+15 rep to Bob) ═══");
        so.acceptAnswer(alice, a);

        System.out.println("\n═══ Alice adds a 50-rep bounty ═══");
        alice.addReputation(300);
        BountyDecorator bounty = so.addBounty(alice, q.getId(), 50, 7 * 24 * 60 * 60 * 1000L);
        System.out.printf("    Bounty: %d rep, expires: %s%n",
            bounty != null ? bounty.getBountyAmount() : 0, bounty != null ? "in 7 days" : "N/A");

        System.out.println("\n═══ Carol comments on Bob's answer ═══");
        so.addCommentToAnswer(carol, a, "Excellent explanation, very clear!");

        System.out.println("\n═══ Community votes to close (needs 5 votes) ═══");
        so.voteToClose(carol, q.getId());
        System.out.println("    Status: " + so.getQuestion(q.getId()).getStatus());

        System.out.println("\n═══ Moderator restores question ═══");
        so.modRestore(mike, q.getId());
        System.out.println("    Status: " + so.getQuestion(q.getId()).getStatus());

        System.out.println("\n═══ More questions for tag analytics ═══");
        so.postQuestion(alice, "What is a B-Tree?", "Explain B-Tree structure...",
            new HashSet<>(Arrays.asList("data-structures", "trees", "databases")));
        so.postQuestion(bob, "Java Stream vs for-loop performance?", "Which is faster...",
            new HashSet<>(Arrays.asList("java", "performance", "streams")));

        System.out.println("\n═══ Top 3 Tags (R11) ═══");
        so.getTopTags(3).forEach(t -> System.out.println("    #" + t));

        System.out.println("\n═══ Search by tag 'java' (R1) ═══");
        so.searchByTag("java").forEach(x -> System.out.println("    • " + x.getTitle()));

        System.out.println("\n═══ Bob's final state ═══");
        System.out.printf("    Reputation : %d%n", bob.getReputationScore());
        System.out.printf("    Badges     : %s%n", bob.getBadges());
    }
}
