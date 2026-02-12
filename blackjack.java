import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Online Blackjack LLD (Amazon/Apple SDE2-ready)
 * Patterns:
 *  - State: TableState + RoundState
 *  - Observer: EventBus listeners
 *  - Factory: CardFactory + ShoeFactory
 *  - Strategy: StartPolicy (timer/fill/hybrid), optional DecisionStrategy
 *  - Singleton: TableRegistry
 *
 * Out of scope (easy extensions): split, double, insurance, surrender.
 */
public class OnlineBlackjackLLD {

    // ===================== Enums =====================
    enum Suit { HEARTS, DIAMONDS, CLUBS, SPADES }

    enum Rank {
        ACE(11), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7),
        EIGHT(8), NINE(9), TEN(10), JACK(10), QUEEN(10), KING(10);
        final int value;
        Rank(int v) { this.value = v; }
    }

    enum Action { HIT, STAND }

    enum SeatStatus { SEATED, WAITING_NEXT_ROUND, LEFT }

    enum TablePhase { LOBBY, ROUND_ACTIVE, CLOSED }

    enum RoundPhase { BETTING, DEALING, PLAYER_TURNS, DEALER_TURN, SETTLEMENT }

    enum EventType {
        TABLE_CREATED, PLAYER_JOINED, PLAYER_LEFT, STATE_CHANGED,
        BET_PLACED, CARD_DEALT, TURN_CHANGED, ROUND_RESULT, ERROR
    }

    // ===================== Events (Observer) =====================
    static final class GameEvent {
        final EventType type;
        final String tableId;
        final String playerId;   // may be null
        final String message;

        GameEvent(EventType type, String tableId, String playerId, String message) {
            this.type = type;
            this.tableId = tableId;
            this.playerId = playerId;
            this.message = message;
        }

        @Override public String toString() {
            return "GameEvent{" + type + ", table=" + tableId +
                    (playerId != null ? ", player=" + playerId : "") +
                    ", msg='" + message + "'}";
        }
    }

    interface GameEventListener { void onEvent(GameEvent e); }

    static final class EventBus {
        private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();
        void subscribe(GameEventListener l) { listeners.add(l); }
        void publish(GameEvent e) { for (GameEventListener l : listeners) l.onEvent(e); }
    }

    // ===================== Core domain =====================
    static final class Card {
        final Suit suit;
        final Rank rank;
        Card(Suit suit, Rank rank) { this.suit = suit; this.rank = rank; }
        int baseValue() { return rank.value; }
        @Override public String toString() { return rank + " of " + suit; }
    }

    static final class Hand {
        private final List<Card> cards = new ArrayList<>();
        void clear() { cards.clear(); }
        void add(Card c) { cards.add(c); }
        List<Card> cards() { return Collections.unmodifiableList(cards); }

        int bestScore() {
            int total = 0;
            int aces = 0;
            for (Card c : cards) {
                total += c.baseValue();
                if (c.rank == Rank.ACE) aces++;
            }
            // adjust Aces from 11 -> 1 while bust
            while (total > 21 && aces > 0) {
                total -= 10;
                aces--;
            }
            return total;
        }

        boolean isBust() { return bestScore() > 21; }
        boolean isBlackjack() { return cards.size() == 2 && bestScore() == 21; }

        boolean isSoft17() {
            // soft 17 means total 17 with an Ace counted as 11 (unreduced)
            int raw = 0, aces = 0;
            for (Card c : cards) {
                raw += c.baseValue();
                if (c.rank == Rank.ACE) aces++;
            }
            return raw == 17 && aces > 0; // simple, good enough for interview
        }

        @Override public String toString() { return cards + " score=" + bestScore(); }
    }

    static abstract class Participant {
        final String id;
        final Hand hand = new Hand();
        Participant(String id) { this.id = id; }
    }

    static final class Dealer extends Participant {
        Dealer() { super("DEALER"); }
    }

    static final class Player extends Participant {
        long balanceCents;
        long betCents;
        SeatStatus seatStatus = SeatStatus.SEATED;

        Player(String id, long balanceCents) {
            super(id);
            this.balanceCents = balanceCents;
        }

        void placeBet(long cents) {
            if (cents <= 0) throw new IllegalArgumentException("Bet must be > 0");
            if (balanceCents < cents) throw new IllegalStateException("Insufficient balance");
            betCents = cents;
            balanceCents -= cents; // take bet upfront
        }

        void winPayout(double multiplier) {
            long payout = betCents + Math.round(betCents * multiplier);
            balanceCents += payout;
            betCents = 0;
        }

        void pushRefund() { balanceCents += betCents; betCents = 0; }
        void loseBet() { betCents = 0; }
    }

    // ===================== Factory (Cards/Shoe) =====================
    static final class CardFactory {
        Card create(Suit s, Rank r) { return new Card(s, r); }
    }

    static final class Shoe {
        private final Deque<Card> cards = new ArrayDeque<>();
        private final int reshuffleThreshold;

        Shoe(List<Card> allCards, int reshuffleThreshold) {
            for (Card c : allCards) cards.addLast(c);
            this.reshuffleThreshold = reshuffleThreshold;
        }

        Card deal() {
            if (cards.isEmpty()) throw new IllegalStateException("Shoe empty");
            return cards.removeFirst();
        }

        boolean needsReshuffle() { return cards.size() < reshuffleThreshold; }
        int remaining() { return cards.size(); }
    }

    static final class ShoeFactory {
        private final CardFactory cardFactory = new CardFactory();

        Shoe create(int decks, Random rng) {
            if (decks <= 0) throw new IllegalArgumentException("decks must be >= 1");
            List<Card> all = new ArrayList<>(decks * 52);

            for (int d = 0; d < decks; d++) {
                for (Suit s : Suit.values()) {
                    for (Rank r : Rank.values()) {
                        all.add(cardFactory.create(s, r));
                    }
                }
            }
            Collections.shuffle(all, rng);
            int threshold = Math.max(52, all.size() / 5);
            return new Shoe(all, threshold);
        }
    }

    // ===================== Strategy: Start Policy =====================
    interface StartPolicy {
        boolean shouldStartRound(BlackjackTable table);
        String name();
    }

    // Recommended online default: start as soon as >=1 seated player exists.
    static final class TimerStartPolicy implements StartPolicy {
        private final int minPlayers; // usually 1
        TimerStartPolicy(int minPlayers) { this.minPlayers = minPlayers; }

        public boolean shouldStartRound(BlackjackTable table) {
            return table.activeSeatedPlayersCount() >= minPlayers;
        }
        public String name() { return "TIMER_START(minPlayers=" + minPlayers + ")"; }
    }

    // ===================== State: Table + Round =====================
    interface TableState {
        TablePhase phase();
        void onEnter(BlackjackTable ctx);
        void join(BlackjackTable ctx, Player p);
        void leave(BlackjackTable ctx, String playerId);
        void startIfPossible(BlackjackTable ctx);
    }

    interface RoundState {
        RoundPhase phase();
        void onEnter(BlackjackTable ctx);
        void placeBet(BlackjackTable ctx, String playerId, long cents);
        void act(BlackjackTable ctx, String playerId, Action action);
        void onBettingWindowExpired(BlackjackTable ctx);    // timer event
        void onTurnTimedOut(BlackjackTable ctx);            // timer event
    }

    // ----- Table states -----
    static final class LobbyState implements TableState {
        public TablePhase phase() { return TablePhase.LOBBY; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "TableState=LOBBY, policy=" + ctx.startPolicy.name());
        }

        public void join(BlackjackTable ctx, Player p) {
            ctx.players.put(p.id, p);
            p.seatStatus = SeatStatus.SEATED;
            ctx.emit(EventType.PLAYER_JOINED, p.id, "Joined with balance=" + p.balanceCents);
            startIfPossible(ctx);
        }

        public void leave(BlackjackTable ctx, String playerId) {
            Player p = ctx.players.get(playerId);
            if (p == null) return;
            p.seatStatus = SeatStatus.LEFT;
            ctx.emit(EventType.PLAYER_LEFT, playerId, "Left table (lobby)");
        }

        public void startIfPossible(BlackjackTable ctx) {
            if (ctx.startPolicy.shouldStartRound(ctx)) {
                ctx.transitionTableState(new RoundActiveState());
            }
        }
    }

    static final class RoundActiveState implements TableState {
        public TablePhase phase() { return TablePhase.ROUND_ACTIVE; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "TableState=ROUND_ACTIVE");
            ctx.startNewRound();
        }

        public void join(BlackjackTable ctx, Player p) {
            // Online rule: joining mid-round => wait next round
            ctx.players.put(p.id, p);
            p.seatStatus = SeatStatus.WAITING_NEXT_ROUND;
            ctx.emit(EventType.PLAYER_JOINED, p.id, "Joined mid-round => WAITING_NEXT_ROUND");
        }

        public void leave(BlackjackTable ctx, String playerId) {
            Player p = ctx.players.get(playerId);
            if (p == null) return;
            p.seatStatus = SeatStatus.LEFT;

            // Requirement-style: if they leave during round and had bet => lose (dealer wins)
            if (p.betCents > 0) {
                p.loseBet();
                ctx.emit(EventType.ROUND_RESULT, p.id, "LOSE (left mid-round)");
            }
            ctx.emit(EventType.PLAYER_LEFT, playerId, "Left table (round active)");
        }

        public void startIfPossible(BlackjackTable ctx) {
            // already active; no-op
        }
    }

    // ----- Round states -----
    static final class BettingState implements RoundState {
        public RoundPhase phase() { return RoundPhase.BETTING; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "RoundState=BETTING (windowMs=" + ctx.bettingWindowMs + ")");
            // In real system, scheduler triggers ctx.onBettingWindowExpired() after bettingWindowMs.
        }

        public void placeBet(BlackjackTable ctx, String playerId, long cents) {
            Player p = ctx.requirePlayer(playerId);

            // Only seated players can bet; waiting-next-round joins next round
            if (p.seatStatus != SeatStatus.SEATED) {
                ctx.emit(EventType.ERROR, playerId, "Cannot bet: not seated for this round");
                return;
            }

            p.placeBet(cents);
            ctx.emit(EventType.BET_PLACED, playerId, "Bet=" + cents);

            // Optional: if all seated players bet, you can auto-advance early.
            if (ctx.allEligiblePlayersBet()) {
                ctx.transitionRoundState(new DealingState());
            }
        }

        public void act(BlackjackTable ctx, String playerId, Action action) {
            ctx.emit(EventType.ERROR, playerId, "Cannot act in BETTING");
        }

        public void onBettingWindowExpired(BlackjackTable ctx) {
            // Players who did not bet sit out this round automatically.
            if (!ctx.anyBetPlaced()) {
                // No bets => back to lobby (or keep waiting). For online, go back to LOBBY.
                ctx.emit(EventType.STATE_CHANGED, null, "No bets placed => back to LOBBY");
                ctx.transitionTableState(new LobbyState());
                return;
            }
            ctx.transitionRoundState(new DealingState());
        }

        public void onTurnTimedOut(BlackjackTable ctx) { /* not applicable */ }
    }

    static final class DealingState implements RoundState {
        public RoundPhase phase() { return RoundPhase.DEALING; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "RoundState=DEALING");

            ctx.resetHandsForRound();
            ctx.dealer.hand.clear();
            ctx.dealerHoleRevealed = false;
            ctx.dealerHoleCard = null;

            // deal 2 to each betting player + dealer
            for (int i = 0; i < 2; i++) {
                for (Player p : ctx.eligiblePlayersThisRound()) {
                    ctx.dealToPlayer(p, true);
                }
                boolean faceUp = (i == 0);
                ctx.dealToDealer(faceUp);
            }

            ctx.transitionRoundState(new PlayerTurnsState());
        }

        public void placeBet(BlackjackTable ctx, String playerId, long cents) {
            ctx.emit(EventType.ERROR, playerId, "Betting closed");
        }

        public void act(BlackjackTable ctx, String playerId, Action action) {
            ctx.emit(EventType.ERROR, playerId, "Cannot act in DEALING");
        }

        public void onBettingWindowExpired(BlackjackTable ctx) { /* not applicable */ }
        public void onTurnTimedOut(BlackjackTable ctx) { /* not applicable */ }
    }

    static final class PlayerTurnsState implements RoundState {
        public RoundPhase phase() { return RoundPhase.PLAYER_TURNS; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "RoundState=PLAYER_TURNS (turnTimeoutMs=" + ctx.turnTimeoutMs + ")");
            ctx.turnOrder = ctx.eligiblePlayersThisRound();
            ctx.currentTurnIndex = 0;
            ctx.advanceToNextTurnOrDealer();
        }

        public void placeBet(BlackjackTable ctx, String playerId, long cents) {
            ctx.emit(EventType.ERROR, playerId, "Betting closed");
        }

       public void act(BlackjackTable ctx, String playerId, Action action) {
    // 0) Guard: must be in player-turns phase (realtime systems get out-of-order calls)
    if (ctx.roundState == null || ctx.roundState.phase() != RoundPhase.PLAYER_TURNS) {
        ctx.emit(EventType.ERROR, playerId, "Cannot act: not in PLAYER_TURNS");
        return;
    }

    Player current = ctx.getCurrentTurnPlayer();
    if (current == null) return;

    // 1) Must be the current player
    if (!current.id.equals(playerId)) {
        ctx.emit(EventType.ERROR, playerId, "Not your turn");
        return;
    }

    // 2) If already terminal, end turn (safety for retries / double clicks)
    if (current.hand.isBlackjack()) {
        ctx.emit(EventType.TURN_CHANGED, current.id, "Auto-stand (blackjack)");
        ctx.nextTurn();
        return;
    }
    if (current.hand.isBust()) {
        ctx.emit(EventType.TURN_CHANGED, current.id, "Already bust => turn ends");
        ctx.nextTurn();
        return;
    }

    // 3) Apply action
    switch (action) {
        case HIT: {
            ctx.dealToPlayer(current, true);

            int score = current.hand.bestScore();
            if (score > 21) {
                ctx.emit(EventType.TURN_CHANGED, current.id, "HIT => BUST (" + score + ") => turn ends");
                ctx.nextTurn();
                return;
            }

            // Common realtime UX: auto-stand on 21
            if (score == 21) {
                ctx.emit(EventType.TURN_CHANGED, current.id, "HIT => 21 => auto-stand");
                ctx.nextTurn();
                return;
            }

            // Still player's turn
            ctx.emit(EventType.TURN_CHANGED, current.id, "HIT => still your turn (score=" + score + ")");
            // In real system: reset turn timer here (scheduler), but we keep signature unchanged.
            // ctx.resetTurnTimer();
            return;
        }

        case STAND:
            ctx.emit(EventType.TURN_CHANGED, current.id, "STAND => turn ends");
            ctx.nextTurn();
            return;

        default:
            ctx.emit(EventType.ERROR, playerId, "Unsupported action: " + action);
    }
}


        public void onBettingWindowExpired(BlackjackTable ctx) { /* not applicable */ }

        public void onTurnTimedOut(BlackjackTable ctx) {
            Player current = ctx.getCurrentTurnPlayer();
            if (current == null) return;
            ctx.emit(EventType.TURN_CHANGED, current.id, "TURN TIMEOUT => default STAND");
            ctx.nextTurn();
        }
    }

    static final class DealerTurnState implements RoundState {
        public RoundPhase phase() { return RoundPhase.DEALER_TURN; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "RoundState=DEALER_TURN (hitSoft17=" + ctx.hitSoft17 + ")");

            ctx.revealDealerHole();

            // Dealer hits until >=17 (optionally hit soft 17)
            while (true) {
                int score = ctx.dealer.hand.bestScore();
                if (score < 17) {
                    ctx.dealToDealer(true);
                    continue;
                }
                if (score == 17 && ctx.hitSoft17 && ctx.dealer.hand.isSoft17()) {
                    ctx.dealToDealer(true);
                    continue;
                }
                break;
            }

            ctx.transitionRoundState(new SettlementState());
        }

        public void placeBet(BlackjackTable ctx, String playerId, long cents) {
            ctx.emit(EventType.ERROR, playerId, "Betting closed");
        }

        public void act(BlackjackTable ctx, String playerId, Action action) {
            ctx.emit(EventType.ERROR, playerId, "No player actions in DEALER_TURN");
        }

        public void onBettingWindowExpired(BlackjackTable ctx) { /* not applicable */ }
        public void onTurnTimedOut(BlackjackTable ctx) { /* not applicable */ }
    }

    static final class SettlementState implements RoundState {
        public RoundPhase phase() { return RoundPhase.SETTLEMENT; }

        public void onEnter(BlackjackTable ctx) {
            ctx.emit(EventType.STATE_CHANGED, null, "RoundState=SETTLEMENT");

            boolean dealerBust = ctx.dealer.hand.isBust();
            boolean dealerBJ = ctx.dealer.hand.isBlackjack();
            int dealerScore = ctx.dealer.hand.bestScore();

            for (Player p : ctx.turnOrder) {
                if (p.betCents <= 0) continue;

                boolean playerBust = p.hand.isBust();
                boolean playerBJ = p.hand.isBlackjack();
                int playerScore = p.hand.bestScore();

                if (p.seatStatus != SeatStatus.SEATED) {
                    // left/disconnected mid-round
                    p.loseBet();
                    ctx.emit(EventType.ROUND_RESULT, p.id, "LOSE (left/disconnected)");
                    continue;
                }

                if (playerBust) {
                    p.loseBet();
                    ctx.emit(EventType.ROUND_RESULT, p.id, "LOSE (bust)");
                    continue;
                }

                if (playerBJ && !dealerBJ) {
                    p.winPayout(1.5); // 3:2
                    ctx.emit(EventType.ROUND_RESULT, p.id, "WIN (blackjack 3:2)");
                    continue;
                }
                if (dealerBJ && !playerBJ) {
                    p.loseBet();
                    ctx.emit(EventType.ROUND_RESULT, p.id, "LOSE (dealer blackjack)");
                    continue;
                }
                if (dealerBJ && playerBJ) {
                    p.pushRefund();
                    ctx.emit(EventType.ROUND_RESULT, p.id, "PUSH (both blackjack)");
                    continue;
                }

                if (dealerBust) {
                    p.winPayout(1.0);
                    ctx.emit(EventType.ROUND_RESULT, p.id, "WIN (dealer bust)");
                    continue;
                }

                if (playerScore > dealerScore) {
                    p.winPayout(1.0);
                    ctx.emit(EventType.ROUND_RESULT, p.id, "WIN");
                } else if (playerScore < dealerScore) {
                    p.loseBet();
                    ctx.emit(EventType.ROUND_RESULT, p.id, "LOSE");
                } else {
                    p.pushRefund();
                    ctx.emit(EventType.ROUND_RESULT, p.id, "PUSH");
                }
            }

            // Round done => admit waiting players into seated
            ctx.promoteWaitingPlayers();

            // Next round: if still players, go BETTING; else go LOBBY.
            if (ctx.activeSeatedPlayersCount() > 0) {
                ctx.transitionRoundState(new BettingState());
            } else {
                ctx.transitionTableState(new LobbyState());
            }
        }

        public void placeBet(BlackjackTable ctx, String playerId, long cents) {
            ctx.emit(EventType.ERROR, playerId, "Betting closed");
        }

        public void act(BlackjackTable ctx, String playerId, Action action) {
            ctx.emit(EventType.ERROR, playerId, "No actions in SETTLEMENT");
        }

        public void onBettingWindowExpired(BlackjackTable ctx) { /* not applicable */ }
        public void onTurnTimedOut(BlackjackTable ctx) { /* not applicable */ }
    }

    // ===================== The Table (Context / Orchestrator) =====================
    static final class BlackjackTable {
        final String tableId;
        final EventBus bus = new EventBus();
        final ReentrantLock lock = new ReentrantLock(true);

        final Map<String, Player> players = new HashMap<>();
        final Dealer dealer = new Dealer();

        final ShoeFactory shoeFactory = new ShoeFactory();
        final Random rng = new Random();
        Shoe shoe;

        // config
        final int numDecks;
        final boolean hitSoft17;
        final long bettingWindowMs;
        final long turnTimeoutMs;
        final StartPolicy startPolicy;

        // state
        TableState tableState;
        RoundState roundState;

        // round context
        List<Player> turnOrder = new ArrayList<>();
        int currentTurnIndex = 0;

        // dealer hole
        boolean dealerHoleRevealed = false;
        Card dealerHoleCard = null;

        BlackjackTable(String tableId,
                       int numDecks,
                       boolean hitSoft17,
                       long bettingWindowMs,
                       long turnTimeoutMs,
                       StartPolicy startPolicy) {
            this.tableId = tableId;
            this.numDecks = numDecks;
            this.hitSoft17 = hitSoft17;
            this.bettingWindowMs = bettingWindowMs;
            this.turnTimeoutMs = turnTimeoutMs;
            this.startPolicy = startPolicy;

            this.shoe = shoeFactory.create(numDecks, rng);
            transitionTableState(new LobbyState());
            emit(EventType.TABLE_CREATED, null, "Created (decks=" + numDecks + ")");
        }

        void subscribe(GameEventListener l) { bus.subscribe(l); }

        // ---- public API ----
        void join(Player p) {
            lock.lock();
            try { tableState.join(this, p); }
            finally { lock.unlock(); }
        }

        void leave(String playerId) {
            lock.lock();
            try { tableState.leave(this, playerId); }
            finally { lock.unlock(); }
        }

        void placeBet(String playerId, long cents) {
            lock.lock();
            try {
                if (roundState == null) { emit(EventType.ERROR, playerId, "No active round"); return; }
                roundState.placeBet(this, playerId, cents);
            } finally { lock.unlock(); }
        }

        void act(String playerId, Action action) {
            lock.lock();
            try {
                if (roundState == null) { emit(EventType.ERROR, playerId, "No active round"); return; }
                roundState.act(this, playerId, action);
            } finally { lock.unlock(); }
        }

        // Timer events (in real system called by scheduler)
        void onBettingWindowExpired() {
            lock.lock();
            try {
                if (roundState != null) roundState.onBettingWindowExpired(this);
            } finally { lock.unlock(); }
        }

        void onTurnTimedOut() {
            lock.lock();
            try {
                if (roundState != null) roundState.onTurnTimedOut(this);
            } finally { lock.unlock(); }
        }

        // ---- transitions ----
        void transitionTableState(TableState next) {
            this.tableState = next;
            this.roundState = null; // no round in pure lobby (until RoundActive enters)
            emit(EventType.STATE_CHANGED, null, "TablePhase=" + next.phase());
            next.onEnter(this);
        }

        void transitionRoundState(RoundState next) {
            this.roundState = next;
            emit(EventType.STATE_CHANGED, null, "RoundPhase=" + next.phase());
            next.onEnter(this);
        }

        void startNewRound() {
            // In ROUND_ACTIVE, round begins at BETTING
            transitionRoundState(new BettingState());
        }

        // ---- helpers ----
        void emit(EventType type, String playerId, String msg) {
            bus.publish(new GameEvent(type, tableId, playerId, msg));
        }

        Player requirePlayer(String id) {
            Player p = players.get(id);
            if (p == null) throw new IllegalArgumentException("Unknown player: " + id);
            return p;
        }

        int activeSeatedPlayersCount() {
            int c = 0;
            for (Player p : players.values()) if (p.seatStatus == SeatStatus.SEATED) c++;
            return c;
        }

        boolean anyBetPlaced() {
            for (Player p : players.values()) if (p.betCents > 0) return true;
            return false;
        }

        boolean allEligiblePlayersBet() {
            boolean anyEligible = false;
            for (Player p : players.values()) {
                if (p.seatStatus == SeatStatus.SEATED) {
                    anyEligible = true;
                    if (p.betCents <= 0) return false;
                }
            }
            return anyEligible;
        }

        List<Player> eligiblePlayersThisRound() {
            List<Player> list = new ArrayList<>();
            for (Player p : players.values()) {
                if (p.seatStatus == SeatStatus.SEATED && p.betCents > 0) list.add(p);
            }
            // stable order (optional). In real systems, seat number decides.
            list.sort(Comparator.comparing(a -> a.id));
            return list;
        }

        void resetHandsForRound() {
            for (Player p : players.values()) {
                p.hand.clear();
            }
        }

        void dealToPlayer(Player p, boolean faceUp) {
            reshuffleIfNeeded();
            Card c = shoe.deal();
            p.hand.add(c);
            emit(EventType.CARD_DEALT, p.id, "Dealt " + (faceUp ? c : "[HIDDEN]") + " | " + p.hand.bestScore());
        }

        void dealToDealer(boolean faceUp) {
            reshuffleIfNeeded();
            Card c = shoe.deal();
            dealer.hand.add(c);
            if (!faceUp && dealerHoleCard == null) dealerHoleCard = c;
            emit(EventType.CARD_DEALT, dealer.id, "Dealer got " + (faceUp ? c : "[HOLE]"));
        }

        void revealDealerHole() {
            dealerHoleRevealed = true;
            if (dealerHoleCard != null) emit(EventType.CARD_DEALT, dealer.id, "Reveal hole: " + dealerHoleCard);
        }

        void reshuffleIfNeeded() {
            if (shoe.needsReshuffle()) {
                shoe = shoeFactory.create(numDecks, rng);
                emit(EventType.STATE_CHANGED, null, "Reshuffled shoe");
            }
        }

        Player getCurrentTurnPlayer() {
            if (turnOrder == null || turnOrder.isEmpty()) return null;
            if (currentTurnIndex < 0 || currentTurnIndex >= turnOrder.size()) return null;
            return turnOrder.get(currentTurnIndex);
        }

        void advanceToNextTurnOrDealer() {
            // skip bust / blackjack auto-turn end
            while (true) {
                Player cur = getCurrentTurnPlayer();
                if (cur == null) {
                    transitionRoundState(new DealerTurnState());
                    return;
                }
                emit(EventType.TURN_CHANGED, cur.id, "Your turn (score=" + cur.hand.bestScore() + ")");
                // In real system: start per-turn timer here.
                if (cur.hand.isBlackjack() || cur.hand.isBust()) {
                    nextTurn();
                    continue;
                }
                return;
            }
        }

        void nextTurn() {
            currentTurnIndex++;
            if (currentTurnIndex >= turnOrder.size()) {
                transitionRoundState(new DealerTurnState());
            } else {
                advanceToNextTurnOrDealer();
            }
        }

        void promoteWaitingPlayers() {
            for (Player p : players.values()) {
                if (p.seatStatus == SeatStatus.WAITING_NEXT_ROUND) {
                    p.seatStatus = SeatStatus.SEATED;
                    emit(EventType.STATE_CHANGED, p.id, "Promoted to SEATED for next round");
                }
                // Clear bets for those who sat out or finished
                p.betCents = 0;
            }
        }
    }

    // ===================== Singleton Table Registry =====================
    static final class TableRegistry {
        private static final TableRegistry INSTANCE = new TableRegistry();
        private final Map<String, BlackjackTable> tables = new HashMap<>();
        private TableRegistry() {}

        static TableRegistry getInstance() { return INSTANCE; }

        synchronized BlackjackTable createTable(int decks,
                                                boolean hitSoft17,
                                                long bettingWindowMs,
                                                long turnTimeoutMs,
                                                StartPolicy startPolicy) {
            String id = "T-" + UUID.randomUUID().toString().substring(0, 8);
            BlackjackTable t = new BlackjackTable(id, decks, hitSoft17, bettingWindowMs, turnTimeoutMs, startPolicy);
            tables.put(id, t);
            return t;
        }

        synchronized BlackjackTable getTable(String tableId) { return tables.get(tableId); }
    }

    // ===================== Observer example =====================
    static final class ConsoleLogger implements GameEventListener {
        public void onEvent(GameEvent e) { System.out.println(e); }
    }

    // ===================== Quick demo =====================
    public static void main(String[] args) {
        StartPolicy policy = new TimerStartPolicy(1); // online default
        BlackjackTable table = TableRegistry.getInstance()
                .createTable(4, false, 12_000, 15_000, policy);

        table.subscribe(new ConsoleLogger());

        table.join(new Player("P1", 10_000));
        table.join(new Player("P2", 10_000)); // may join mid-round depending timing

        // Round started => BETTING
        table.placeBet("P1", 1000);
        table.placeBet("P2", 1000);

        // If not all bet, you’d call table.onBettingWindowExpired() from scheduler.
        // table.onBettingWindowExpired();

        // During turns:
        // table.act("P1", Action.HIT);
        // table.act("P1", Action.STAND);
        // etc.
    }
}
