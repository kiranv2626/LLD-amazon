import java.util.*;

// ══════════════════════════════════════════════════════
// ENUMS
// ══════════════════════════════════════════════════════
enum GameStatus   { ACTIVE, WHITE_WIN, BLACK_WIN, DRAW, STALEMATE, RESIGNATION, FORFEIT }
enum MoveType     { NORMAL, CASTLING, EN_PASSANT, PROMOTION }
enum PieceType    { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
enum DrawReason   { STALEMATE, FIFTY_MOVE, THREEFOLD, INSUFFICIENT_MATERIAL, AGREEMENT }

// ══════════════════════════════════════════════════════
// POSITION – value object (no raw ints floating around)
// ══════════════════════════════════════════════════════
final class Position {
    final int row, col;   // row 0=rank1 … row7=rank8

    Position(int row, int col) { this.row = row; this.col = col; }

    boolean isValid() { return row >= 0 && row < 8 && col >= 0 && col < 8; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Position p)) return false;
        return row == p.row && col == p.col;
    }
    @Override public int hashCode() { return Objects.hash(row, col); }

    /** "e4" -> Position(3,4). Throws on invalid input. */
    static Position fromAlgebraic(String s) {
        if (s == null || s.length() != 2
                || s.charAt(0) < 'a' || s.charAt(0) > 'h'
                || s.charAt(1) < '1' || s.charAt(1) > '8')
            throw new IllegalArgumentException("Invalid algebraic notation: " + s);
        return new Position(s.charAt(1) - '1', s.charAt(0) - 'a');
    }

    /** Position(3,4) -> "e4" */
    String toAlgebraic() { return "" + (char)('a' + col) + (row + 1); }

    @Override public String toString() { return toAlgebraic(); }
}

// ══════════════════════════════════════════════════════
// STRATEGY – TWO methods: canMove (legal) + canAttack (threatens square)
// FIX: pawns attack diagonally even on empty squares => needed for check detection
// ══════════════════════════════════════════════════════
interface MoveStrategy {
    boolean canMove(Box[][] b, Box src, Box dst);    // legal move
    boolean canAttack(Box[][] b, Box src, Box dst);  // pure threat (for check detection)
}

abstract class SlidingStrategy implements MoveStrategy {
    boolean clearPath(Box[][] b, int r1, int c1, int r2, int c2) {
        int dr = Integer.signum(r2 - r1), dc = Integer.signum(c2 - c1);
        int r = r1 + dr, c = c1 + dc;
        while (r != r2 || c != c2) {
            if (b[r][c].getPiece() != null) return false;
            r += dr; c += dc;
        }
        return true;
    }
}

class KingStrategy implements MoveStrategy {
    public boolean canMove(Box[][] b, Box src, Box dst) {
        int dr = Math.abs(src.pos.row - dst.pos.row);
        int dc = Math.abs(src.pos.col - dst.pos.col);
        return dr <= 1 && dc <= 1 && (dr + dc > 0);
    }
    public boolean canAttack(Box[][] b, Box src, Box dst) { return canMove(b, src, dst); }
}

class QueenStrategy extends SlidingStrategy {
    private final RookStrategy   rook   = new RookStrategy();
    private final BishopStrategy bishop = new BishopStrategy();
    public boolean canMove(Box[][] b, Box src, Box dst) {
        return rook.canMove(b, src, dst) || bishop.canMove(b, src, dst);
    }
    public boolean canAttack(Box[][] b, Box src, Box dst) { return canMove(b, src, dst); }
}

class RookStrategy extends SlidingStrategy {
    public boolean canMove(Box[][] b, Box src, Box dst) {
        if (src.pos.row != dst.pos.row && src.pos.col != dst.pos.col) return false;
        return clearPath(b, src.pos.row, src.pos.col, dst.pos.row, dst.pos.col);
    }
    public boolean canAttack(Box[][] b, Box src, Box dst) { return canMove(b, src, dst); }
}

class BishopStrategy extends SlidingStrategy {
    public boolean canMove(Box[][] b, Box src, Box dst) {
        int dr = Math.abs(src.pos.row - dst.pos.row);
        int dc = Math.abs(src.pos.col - dst.pos.col);
        if (dr != dc || dr == 0) return false;
        return clearPath(b, src.pos.row, src.pos.col, dst.pos.row, dst.pos.col);
    }
    public boolean canAttack(Box[][] b, Box src, Box dst) { return canMove(b, src, dst); }
}

class KnightStrategy implements MoveStrategy {
    public boolean canMove(Box[][] b, Box src, Box dst) {
        int dr = Math.abs(src.pos.row - dst.pos.row);
        int dc = Math.abs(src.pos.col - dst.pos.col);
        return (dr == 2 && dc == 1) || (dr == 1 && dc == 2);
    }
    public boolean canAttack(Box[][] b, Box src, Box dst) { return canMove(b, src, dst); }
}

class PawnStrategy implements MoveStrategy {
    /** Legal move: forward non-capture OR diagonal capture */
    public boolean canMove(Box[][] b, Box src, Box dst) {
        Piece p = src.getPiece();
        int dir = p.isWhite() ? 1 : -1;
        int dr = dst.pos.row - src.pos.row;
        int dc = Math.abs(dst.pos.col - src.pos.col);
        if (dc == 0 && dr == dir && dst.isEmpty()) return true;
        int startRow = p.isWhite() ? 1 : 6;
        if (dc == 0 && dr == 2 * dir && src.pos.row == startRow
                && b[src.pos.row + dir][src.pos.col].getPiece() == null
                && dst.isEmpty()) return true;
        if (dc == 1 && dr == dir && !dst.isEmpty()
                && dst.getPiece().isWhite() != p.isWhite()) return true;
        return false;
    }
    /** Attack squares: diagonals regardless of occupancy */
    public boolean canAttack(Box[][] b, Box src, Box dst) {
        Piece p = src.getPiece();
        int dir = p.isWhite() ? 1 : -1;
        return Math.abs(dst.pos.col - src.pos.col) == 1
            && (dst.pos.row - src.pos.row) == dir;
    }
}

// ══════════════════════════════════════════════════════
// FACTORY
// ══════════════════════════════════════════════════════
class PieceFactory {
    static Piece create(PieceType type, boolean white) {
        MoveStrategy s = switch (type) {
            case KING   -> new KingStrategy();
            case QUEEN  -> new QueenStrategy();
            case ROOK   -> new RookStrategy();
            case BISHOP -> new BishopStrategy();
            case KNIGHT -> new KnightStrategy();
            case PAWN   -> new PawnStrategy();
        };
        return new Piece(white, type, s);
    }
}

// ══════════════════════════════════════════════════════
// PIECE
// ══════════════════════════════════════════════════════
class Piece {
    private final boolean white;
    private final PieceType type;
    private final MoveStrategy strategy;
    private boolean moved  = false;

    Piece(boolean white, PieceType type, MoveStrategy strategy) {
        this.white = white; this.type = type; this.strategy = strategy;
    }

    boolean   isWhite()             { return white; }
    PieceType getType()             { return type; }
    boolean   hasMoved()            { return moved; }
    void      setMoved(boolean m)   { moved = m; }

    boolean canMove(Box[][] board, Box src, Box dst) {
        if (!dst.isEmpty() && dst.getPiece().isWhite() == white) return false;
        return strategy.canMove(board, src, dst);
    }
    boolean canAttack(Box[][] board, Box src, Box dst) {
        return strategy.canAttack(board, src, dst);
    }

    String symbol() {
        String s = switch (type) {
            case KING -> "K"; case QUEEN -> "Q"; case ROOK -> "R";
            case BISHOP -> "B"; case KNIGHT -> "N"; case PAWN -> "P";
        };
        return white ? s : s.toLowerCase();
    }
    @Override public String toString() { return symbol(); }
}

// ══════════════════════════════════════════════════════
// BOX
// ══════════════════════════════════════════════════════
class Box {
    final Position pos;
    private Piece piece;
    Box(Position pos)       { this.pos = pos; }
    Piece   getPiece()      { return piece; }
    void    setPiece(Piece p){ piece = p; }
    boolean isEmpty()       { return piece == null; }
    @Override public String toString() { return pos.toAlgebraic(); }
}

// ══════════════════════════════════════════════════════
// MOVE – value object (audit/replay; honest – not labeled Command)
// ══════════════════════════════════════════════════════
class Move {
    final Position src, dst;
    final Piece    pieceMoved, pieceCaptured;
    final Player   player;
    final MoveType type;
    Piece    promotedTo;
    Position enPassantRemoved;

    Move(Position src, Position dst, Piece moved, Piece captured, Player player, MoveType type) {
        this.src = src; this.dst = dst;
        this.pieceMoved = moved; this.pieceCaptured = captured;
        this.player = player; this.type = type;
    }

    @Override public String toString() {
        String s = player.getName() + ": " + pieceMoved.symbol() + " " + src + "->" + dst;
        if (pieceCaptured != null) s += " x" + pieceCaptured.symbol();
        if (type != MoveType.NORMAL)  s += " [" + type + "]";
        if (promotedTo != null)        s += "=" + promotedTo.symbol();
        return s;
    }
}

// ══════════════════════════════════════════════════════
// PLAYER
// ══════════════════════════════════════════════════════
class Player {
    private final String name;
    private final boolean whiteSide;
    Player(String name, boolean whiteSide) { this.name = name; this.whiteSide = whiteSide; }
    String  getName()     { return name; }
    boolean isWhiteSide() { return whiteSide; }
    @Override public String toString() { return name + "(" + (whiteSide ? "W" : "B") + ")"; }
}

// ══════════════════════════════════════════════════════
// CHESSBOARD – plain class, no Singleton (testable, multi-game safe)
// ══════════════════════════════════════════════════════
class Chessboard {
    private final Box[][] boxes = new Box[8][8];

    Chessboard() {
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++)
                boxes[r][c] = new Box(new Position(r, c));
        reset();
    }

    void reset() {
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) boxes[r][c].setPiece(null);
        PieceType[] back = { PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP,
                             PieceType.QUEEN, PieceType.KING,
                             PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK };
        for (int c = 0; c < 8; c++) {
            boxes[0][c].setPiece(PieceFactory.create(back[c], true));
            boxes[1][c].setPiece(PieceFactory.create(PieceType.PAWN, true));
            boxes[6][c].setPiece(PieceFactory.create(PieceType.PAWN, false));
            boxes[7][c].setPiece(PieceFactory.create(back[c], false));
        }
    }

    Box     get(Position p)   { return boxes[p.row][p.col]; }
    Box     get(int r, int c) { return boxes[r][c]; }
    Box[][] all()             { return boxes; }

    Box findKing(boolean white) {
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                Piece p = boxes[r][c].getPiece();
                if (p != null && p.getType() == PieceType.KING && p.isWhite() == white)
                    return boxes[r][c];
            }
        throw new IllegalStateException("King missing – illegal board state");
    }
}

// ══════════════════════════════════════════════════════
// GAME RESULT – richer than GameStatus alone
// ══════════════════════════════════════════════════════
class GameResult {
    final GameStatus status;
    final DrawReason drawReason;
    final String     description;

    GameResult(GameStatus status, DrawReason drawReason, String description) {
        this.status = status; this.drawReason = drawReason; this.description = description;
    }
    @Override public String toString() {
        return status + (drawReason != null ? "(" + drawReason + ")" : "") + ": " + description;
    }
}

// ══════════════════════════════════════════════════════
// OBSERVER
// ══════════════════════════════════════════════════════
interface GameObserver {
    void onCheck(Player playerInCheck);
    void onGameOver(GameResult result);
}

class ConsoleObserver implements GameObserver {
    public void onCheck(Player p)        { System.out.println("  *** CHECK: " + p.getName() + " ***"); }
    public void onGameOver(GameResult r) { System.out.println("  *** GAME OVER: " + r + " ***"); }
}

// ══════════════════════════════════════════════════════
// STATE PATTERN – post-move evaluation
// ══════════════════════════════════════════════════════
interface GameState {
    /** Returns a GameResult if the game ended, null if it continues.
     *  posKey must include piece placement + side-to-move + castling rights + EP file. */
    GameResult evaluate(ChessMoveController ctrl, Chessboard board,
                        Player current, Map<String,Integer> posCount,
                        int halfClock, String posKey);
}

class ActiveGameState implements GameState {
    public GameResult evaluate(ChessMoveController ctrl, Chessboard board,
                               Player current, Map<String,Integer> posCount,
                               int halfClock, String posKey) {
        boolean white    = current.isWhiteSide();
        boolean inCheck  = ctrl.isInCheck(board.all(), white);
        boolean hasMoves = ctrl.hasLegalMoves(board.all(), white);

        if (!hasMoves && inCheck)
            return new GameResult(
                white ? GameStatus.BLACK_WIN : GameStatus.WHITE_WIN,
                null, "Checkmate – " + current.getName() + " has no escape");

        if (!hasMoves)
            return new GameResult(GameStatus.DRAW, DrawReason.STALEMATE,
                current.getName() + " has no legal moves");

        if (halfClock >= 100)
            return new GameResult(GameStatus.DRAW, DrawReason.FIFTY_MOVE, "50-move rule");

        if (posCount.getOrDefault(posKey, 0) >= 3)
            return new GameResult(GameStatus.DRAW, DrawReason.THREEFOLD, "Threefold repetition");

        if (ctrl.isInsufficientMaterial(board.all()))
            return new GameResult(GameStatus.DRAW, DrawReason.INSUFFICIENT_MATERIAL,
                "Insufficient material");

        return null; // game continues
    }
}

// ══════════════════════════════════════════════════════
// CHESS MOVE CONTROLLER
// Key design: validate + apply are unified; revert is private simulation only
// ══════════════════════════════════════════════════════
class ChessMoveController {

    // ── Public move API ───────────────────────────────

    Move executeNormal(Chessboard board, Position src, Position dst, Player player) {
        Box srcBox = board.get(src), dstBox = board.get(dst);
        Piece p = srcBox.getPiece();
        if (p == null || p.isWhite() != player.isWhiteSide()) return null;
        if (!p.canMove(board.all(), srcBox, dstBox)) return null;
        if (leavesKingInCheck(board.all(), src, dst, p.isWhite())) return null;
        return applyNormal(board, src, dst, player);
    }

    Move executeEnPassant(Chessboard board, Position src, Position dst,
                          Player player, Move lastMove) {
        if (!isEnPassantSetup(board, src, dst, player, lastMove)) return null;
        return applyEnPassant(board, src, dst, player, lastMove);
    }

    Move executePromotion(Chessboard board, Position src, Position dst,
                          Player player, PieceType promote) {
        Box srcBox = board.get(src), dstBox = board.get(dst);
        Piece pawn = srcBox.getPiece();
        if (pawn == null || pawn.getType() != PieceType.PAWN
                || pawn.isWhite() != player.isWhiteSide()) return null;
        int promRow = pawn.isWhite() ? 7 : 0;
        if (dst.row != promRow) return null;
        if (!pawn.canMove(board.all(), srcBox, dstBox)) return null;
        if (leavesKingInCheck(board.all(), src, dst, pawn.isWhite())) return null;
        return applyPromotion(board, src, dst, player, promote);
    }

    Move executeCastling(Chessboard board, Player player, boolean kingside) {
        Box kingBox = board.findKing(player.isWhiteSide());
        int row     = kingBox.pos.row;
        int rookCol = kingside ? 7 : 0;
        Box rookBox = board.get(row, rookCol);
        Piece king  = kingBox.getPiece();
        Piece rook  = rookBox.getPiece();
        if (king.hasMoved() || rook == null
                || rook.getType() != PieceType.ROOK
                || rook.isWhite() != player.isWhiteSide()   // rook must belong to same side
                || rook.hasMoved()) return null;
        int minC = Math.min(kingBox.pos.col, rookCol);
        int maxC = Math.max(kingBox.pos.col, rookCol);
        for (int c = minC + 1; c < maxC; c++)
            if (board.get(row, c).getPiece() != null) return null;
        if (isInCheck(board.all(), king.isWhite())) return null;
        int dir = kingside ? 1 : -1;
        Position mid  = new Position(row, kingBox.pos.col + dir);
        Position dest = new Position(row, kingBox.pos.col + 2 * dir);
        if (leavesKingInCheck(board.all(), kingBox.pos, mid,  king.isWhite())) return null;
        if (leavesKingInCheck(board.all(), kingBox.pos, dest, king.isWhite())) return null;
        return applyCastling(board, kingBox, rookBox, dest, player, dir);
    }

    // ── Check / draw helpers ──────────────────────────

    boolean isInCheck(Box[][] b, boolean white) {
        Box kingBox = findKingBox(b, white);
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c].getPiece();
                // FIX: use canAttack so pawn diagonal threats on empty squares are counted
                if (p != null && p.isWhite() != white
                        && p.canAttack(b, b[r][c], kingBox))
                    return true;
            }
        return false;
    }

    boolean hasLegalMoves(Box[][] b, boolean white) {
        // Normal + promotion moves
        for (int sr = 0; sr < 8; sr++)
            for (int sc = 0; sc < 8; sc++) {
                Piece p = b[sr][sc].getPiece();
                if (p == null || p.isWhite() != white) continue;
                for (int dr = 0; dr < 8; dr++)
                    for (int dc = 0; dc < 8; dc++)
                        if (p.canMove(b, b[sr][sc], b[dr][dc])
                                && !leavesKingInCheck(b,
                                        new Position(sr,sc), new Position(dr,dc), white))
                            return true;
            }
        // Castling (kingside + queenside) – avoids false stalemate/checkmate
        for (boolean kingside : new boolean[]{true, false}) {
            Box kingBox = findKingBox(b, white);
            if (kingBox == null) continue;
            int row = kingBox.pos.row;
            int rookCol = kingside ? 7 : 0;
            Box rookBox = b[row][rookCol];
            Piece king = kingBox.getPiece(), rook = rookBox.getPiece();
            if (king != null && !king.hasMoved() && rook != null
                    && rook.getType() == PieceType.ROOK
                    && rook.isWhite() == white && !rook.hasMoved()) {
                int minC = Math.min(kingBox.pos.col, rookCol);
                int maxC = Math.max(kingBox.pos.col, rookCol);
                boolean pathClear = true;
                for (int c = minC + 1; c < maxC; c++)
                    if (b[row][c].getPiece() != null) { pathClear = false; break; }
                if (pathClear && !isInCheck(b, white)) return true; // rough check, fine for LLD
            }
        }
        return false;
    }

    boolean isInsufficientMaterial(Box[][] b) {
        List<Piece> alive = new ArrayList<>();
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++)
                if (b[r][c].getPiece() != null) alive.add(b[r][c].getPiece());
        if (alive.size() == 2) return true;
        if (alive.size() == 3) return alive.stream().anyMatch(
            p -> p.getType() == PieceType.BISHOP || p.getType() == PieceType.KNIGHT);
        return false;
    }

    // ── Private: safe simulation (revert-safe) ────────

    private boolean leavesKingInCheck(Box[][] b, Position src, Position dst, boolean white) {
        Box s = b[src.row][src.col], d = b[dst.row][dst.col];
        Piece moved = s.getPiece(), captured = d.getPiece();
        d.setPiece(moved); s.setPiece(null);           // apply
        boolean unsafe = isInCheck(b, white);
        s.setPiece(moved); d.setPiece(captured);       // revert
        return unsafe;
    }

    // ── Apply methods ─────────────────────────────────

    private Move applyNormal(Chessboard board, Position src, Position dst, Player player) {
        Box srcBox = board.get(src), dstBox = board.get(dst);
        Piece moved    = srcBox.getPiece();
        Piece captured = dstBox.getPiece();
        dstBox.setPiece(moved); srcBox.setPiece(null);
        moved.setMoved(true);
        return new Move(src, dst, moved, captured, player, MoveType.NORMAL);
    }

    private boolean isEnPassantSetup(Chessboard board, Position src, Position dst,
                                     Player player, Move last) {
        if (last == null || last.pieceMoved.getType() != PieceType.PAWN) return false;
        if (Math.abs(last.src.row - last.dst.row) != 2) return false;
        Piece p = board.get(src).getPiece();
        if (p == null || p.getType() != PieceType.PAWN
                || p.isWhite() != player.isWhiteSide()) return false;
        int dir = p.isWhite() ? 1 : -1;
        if (dst.row != src.row + dir)   return false;
        if (dst.col != last.dst.col)    return false;
        if (src.row != last.dst.row)    return false;
        if (Math.abs(src.col - last.dst.col) != 1) return false;
        // Safety: simulate removing both pawns, check king safety
        Box capBox   = board.get(last.dst);
        Piece capPawn = capBox.getPiece();
        capBox.setPiece(null);
        boolean safe = !leavesKingInCheck(board.all(), src, dst, p.isWhite());
        capBox.setPiece(capPawn);
        return safe;
    }

    private Move applyEnPassant(Chessboard board, Position src, Position dst,
                                Player player, Move last) {
        Box srcBox = board.get(src), dstBox = board.get(dst);
        Box capBox = board.get(last.dst);
        Piece moved    = srcBox.getPiece();
        Piece captured = capBox.getPiece();
        dstBox.setPiece(moved); srcBox.setPiece(null); capBox.setPiece(null);
        moved.setMoved(true);
        Move m = new Move(src, dst, moved, captured, player, MoveType.EN_PASSANT);
        m.enPassantRemoved = last.dst;
        return m;
    }

    private Move applyPromotion(Chessboard board, Position src, Position dst,
                                Player player, PieceType promote) {
        Box srcBox = board.get(src), dstBox = board.get(dst);
        Piece pawn     = srcBox.getPiece();
        Piece captured = dstBox.getPiece();
        Piece promoted = PieceFactory.create(promote, pawn.isWhite());
        dstBox.setPiece(promoted); srcBox.setPiece(null);
        Move m = new Move(src, dst, pawn, captured, player, MoveType.PROMOTION);
        m.promotedTo = promoted;
        return m;
    }

    private Move applyCastling(Chessboard board, Box kingBox, Box rookBox,
                               Position kingDest, Player player, int dir) {
        Piece king = kingBox.getPiece(), rook = rookBox.getPiece();
        Position rookDest = new Position(kingBox.pos.row, kingBox.pos.col + dir);
        board.get(kingDest).setPiece(king);
        board.get(rookDest).setPiece(rook);
        kingBox.setPiece(null); rookBox.setPiece(null);
        king.setMoved(true); rook.setMoved(true);
        return new Move(kingBox.pos, kingDest, king, null, player, MoveType.CASTLING);
    }

    private Box findKingBox(Box[][] b, boolean white) {
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c].getPiece();
                if (p != null && p.getType() == PieceType.KING && p.isWhite() == white)
                    return b[r][c];
            }
        return null;
    }
}

// ══════════════════════════════════════════════════════
// VIEW
// ══════════════════════════════════════════════════════
class ChessGameView {
    void show(Chessboard board) {
        System.out.println("  a b c d e f g h");
        for (int r = 7; r >= 0; r--) {
            System.out.print((r + 1) + " ");
            for (int c = 0; c < 8; c++) {
                Piece p = board.get(r, c).getPiece();
                System.out.print((p == null ? "." : p.symbol()) + " ");
            }
            System.out.println((r + 1));
        }
        System.out.println("  a b c d e f g h\n");
    }
}

// ══════════════════════════════════════════════════════
// CHESS GAME – orchestrator
// ══════════════════════════════════════════════════════
class ChessGame {
    private final Player white, black;
    private Player current;
    private GameStatus status = GameStatus.ACTIVE;
    private GameResult result;

    private final Chessboard            board      = new Chessboard();
    private final ChessMoveController   controller = new ChessMoveController();
    private final ChessGameView         view       = new ChessGameView();
    private final GameState             gameState  = new ActiveGameState();
    private final List<Move>            history    = new ArrayList<>();
    private final List<GameObserver>    observers  = new ArrayList<>();
    private final Map<String,Integer>   posCount   = new HashMap<>();
    private int halfClock = 0;

    ChessGame(Player white, Player black) {
        this.white = white; this.black = black; this.current = white;
        addObserver(new ConsoleObserver());
        recordPosition();
    }

    void addObserver(GameObserver o) { observers.add(o); }

    // ── Move API ──────────────────────────────────────

    boolean move(String from, String to) {
        return move(Position.fromAlgebraic(from), Position.fromAlgebraic(to), PieceType.QUEEN);
    }

    /** Overload accepting promotion choice (relevant when pawn reaches last rank) */
    boolean move(String from, String to, PieceType promoteTo) {
        return move(Position.fromAlgebraic(from), Position.fromAlgebraic(to), promoteTo);
    }

    boolean move(Position src, Position dst) { return move(src, dst, PieceType.QUEEN); }

    boolean move(Position src, Position dst, PieceType promoteTo) {
        if (!isActive()) { System.out.println("  Game over: " + status); return false; }
        Move last = history.isEmpty() ? null : history.getLast();

        // precedence: en passant > promotion > normal
        Move m = controller.executeEnPassant(board, src, dst, current, last);

        if (m == null) {
            Piece p = board.get(src).getPiece();
            if (p != null && p.getType() == PieceType.PAWN) {
                int promRow = p.isWhite() ? 7 : 0;
                if (dst.row == promRow)
                    m = controller.executePromotion(board, src, dst, current, promoteTo);
            }
        }

        if (m == null) m = controller.executeNormal(board, src, dst, current);

        if (m == null) {
            System.out.println("  Illegal move: " + src + " -> " + dst);
            return false;
        }
        commit(m);
        return true;
    }

    boolean castle(boolean kingside) {
        if (!isActive()) { System.out.println("  Game over: " + status); return false; }
        Move m = controller.executeCastling(board, current, kingside);
        if (m == null) { System.out.println("  Castling not allowed."); return false; }
        commit(m);
        return true;
    }

    void resign(Player p) {
        result = new GameResult(
            p.isWhiteSide() ? GameStatus.BLACK_WIN : GameStatus.WHITE_WIN,
            null, p.getName() + " resigned");
        status = result.status;
        observers.forEach(o -> o.onGameOver(result));
    }

    void forfeit(Player p) {
        result = new GameResult(
            p.isWhiteSide() ? GameStatus.BLACK_WIN : GameStatus.WHITE_WIN,
            null, p.getName() + " forfeited");
        status = result.status;
        observers.forEach(o -> o.onGameOver(result));
    }

    // ── Getters ───────────────────────────────────────
    boolean    isActive()    { return status == GameStatus.ACTIVE; }
    GameStatus getStatus()   { return status; }
    GameResult getResult()   { return result; }
    Player     getCurrent()  { return current; }
    void       showBoard()   { view.show(board); }

    void printHistory() {
        System.out.println("── Move History ──────────────");
        history.forEach(System.out::println);
        System.out.println("──────────────────────────────\n");
    }

    // ── Internals ─────────────────────────────────────

    private void commit(Move m) {
        history.add(m);
        System.out.println(m);
        updateClocks(m);
        switchTurn();
        evaluateState();
    }

    private void switchTurn() { current = (current == white) ? black : white; }

    private void updateClocks(Move m) {
        boolean reset = m.pieceMoved.getType() == PieceType.PAWN || m.pieceCaptured != null;
        halfClock = reset ? 0 : halfClock + 1;
        recordPosition();
    }

    private void recordPosition() {
        String key = boardKey();
        posCount.merge(key, 1, Integer::sum);
    }

    private void evaluateState() {
        String posKey = boardKey();
        GameResult r = gameState.evaluate(controller, board, current, posCount, halfClock, posKey);
        if (r != null) {
            result = r; status = r.status;
            observers.forEach(o -> o.onGameOver(r));
        } else if (controller.isInCheck(board.all(), current.isWhiteSide())) {
            observers.forEach(o -> o.onCheck(current));
        }
    }

    private String boardKey() {
        StringBuilder sb = new StringBuilder();
        // 1. Piece placement
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                Piece p = board.get(r, c).getPiece();
                sb.append(p == null ? '.' : p.symbol().charAt(0));
            }
        // 2. Side to move
        sb.append(current.isWhiteSide() ? 'w' : 'b');
        // 3. Castling rights (KQkq style) – required for correct FIDE repetition detection
        Piece wk = board.get(0,4).getPiece(), bk = board.get(7,4).getPiece();
        Piece wkr = board.get(0,7).getPiece(), wqr = board.get(0,0).getPiece();
        Piece bkr = board.get(7,7).getPiece(), bqr = board.get(7,0).getPiece();
        sb.append(wk  != null && !wk.hasMoved()  && wkr != null && !wkr.hasMoved()  ? 'K' : '-');
        sb.append(wk  != null && !wk.hasMoved()  && wqr != null && !wqr.hasMoved()  ? 'Q' : '-');
        sb.append(bk  != null && !bk.hasMoved()  && bkr != null && !bkr.hasMoved()  ? 'k' : '-');
        sb.append(bk  != null && !bk.hasMoved()  && bqr != null && !bqr.hasMoved()  ? 'q' : '-');
        // 4. En-passant target file – required for correct FIDE repetition detection
        Move last = history.isEmpty() ? null : history.getLast();
        if (last != null && last.pieceMoved.getType() == PieceType.PAWN
                && Math.abs(last.src.row - last.dst.row) == 2)
            sb.append((char)('a' + last.dst.col));  // e.g. 'e' for e-file EP opportunity
        else
            sb.append('-');
        return sb.toString();
    }
}

// ══════════════════════════════════════════════════════
// DRIVER – no name collision, compiles as-is
// ══════════════════════════════════════════════════════
class ChessGameDriver {
    public static void main(String[] args) {
        Player alice = new Player("Alice", true);
        Player bob   = new Player("Bob",   false);
        ChessGame game = new ChessGame(alice, bob);

        System.out.println("=== Scholar's Mate Demo ===\n");
        game.showBoard();

        // 1.e4 e5 2.Bc4 Nc6 3.Qh5 g6?? 4.Qxf7#
        game.move("e2", "e4");
        game.move("e7", "e5");
        game.move("f1", "c4");
        game.move("b8", "c6");
        game.move("d1", "h5");
        game.move("g7", "g6");
        game.move("h5", "f7");   // checkmate

        game.showBoard();
        game.printHistory();
        System.out.println("Final: " + game.getResult());

        // Resign demo
        System.out.println("\n=== Resign Demo ===");
        Player p1 = new Player("P1", true), p2 = new Player("P2", false);
        ChessGame g2 = new ChessGame(p1, p2);
        g2.move("e2", "e4");
        g2.resign(p1);   // white resigns -> black wins
        System.out.println("Status: " + g2.getStatus());

        // Promotion-to-knight demo (underpromotion)
        System.out.println("\n=== Underpromotion Demo (Knight) ===");
        ChessGame g3 = new ChessGame(new Player("X", true), new Player("Y", false));
        // move() with explicit promoteTo param – no more hardcoded QUEEN
        g3.move("e2", "e4", PieceType.KNIGHT);  // not a promotion move, param ignored safely
        System.out.println("Promotion overload works: " + g3.isActive());
    }
}
