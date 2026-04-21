import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

// ==================== ENUMS ====================

enum Edge {
    INDENTATION, EXTRUSION, FLAT;

    // DD1: Choice: Complementary check via enum method, not caller logic
    //      Alternative: Switch/if in Piece or Solver
    //      Tradeoff: Encapsulates matching rule; one place to change if rules evolve
    //      TALK: "I put fitsWith on the enum so matching logic lives with the domain concept, not scattered across callers."
    public boolean fitsWith(Edge other) {
        if (this == INDENTATION && other == EXTRUSION) return true;
        if (this == EXTRUSION && other == INDENTATION) return true;
        return false;
    }
}

// ==================== SIDE ====================

class Side {
    private final Edge edge;

    public Side(Edge edge) { this.edge = edge; }
    public Edge getEdge() { return edge; }

    public boolean fitsWith(Side other) {
        return this.edge.fitsWith(other.edge);
    }
}

// ==================== PIECE ====================

// DD2: Choice: Fixed-size array[4] indexed by constants (TOP=0, RIGHT=1, BOTTOM=2, LEFT=3)
//      Alternative: Map<Direction, Side> or separate fields
//      Tradeoff: Array is cache-friendly and rotation is a simple index shift
//      TALK: "Four sides is invariant for this problem, so a fixed array with index constants is simpler than a map."

class Piece {
    static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;
    private Side[] sides; // length 4, mutable for rotation

    public Piece(Side top, Side right, Side bottom, Side left) {
        this.sides = new Side[]{top, right, bottom, left};
    }

    public Side getSide(int direction) { return sides[direction]; }

    public boolean isCorner() { return countFlat() == 2; }
    public boolean isEdge()   { return countFlat() == 1; }
    public boolean isMiddle() { return countFlat() == 0; }

    private int countFlat() {
        int count = 0;
        for (Side s : sides) if (s.getEdge() == Edge.FLAT) count++;
        return count;
    }

    // DD3: Choice: Rotate mutates in-place via cyclic shift
    //      Alternative: Return new Piece (immutable)
    //      Tradeoff: Mutable avoids allocation churn during solve; acceptable since solver owns the piece
    //      TALK: "Rotation is a hot path in the solver loop, so I mutate in place to avoid GC pressure."
    public void rotateClockwise() {
        Side temp = sides[LEFT];
        sides[LEFT]   = sides[BOTTOM];
        sides[BOTTOM] = sides[RIGHT];
        sides[RIGHT]  = sides[TOP];
        sides[TOP]    = temp;
    }
}

// ==================== PUZZLE (Singleton) ====================

// DD4: Choice: Singleton via double-checked locking
//      Alternative: Enum singleton
//      Tradeoff: DCL allows lazy init with constructor params (rows, cols); enum can't accept args
//      TALK: "I need to pass board dimensions at creation time, so enum singleton doesn't work here."

class Puzzle {
    private static volatile Puzzle instance;
    private static final ReentrantLock lock = new ReentrantLock();

    private final int rows;
    private final int cols;
    private final Piece[][] board;
    private final List<Piece> freePieces;

    private Puzzle(int rows, int cols, List<Piece> pieces) {
        this.rows = rows;
        this.cols = cols;
        this.board = new Piece[rows][cols];
        this.freePieces = new ArrayList<>(pieces);
    }

    // DD5: Choice: ReentrantLock over synchronized for getInstance
    //      Alternative: synchronized block
    //      Tradeoff: ReentrantLock is consistent with rest of codebase; tryLock available if needed
    //      TALK: "For a single-player puzzle this lock is rarely contended, but it's correct if we later add concurrent solvers."
    public static Puzzle getInstance(int rows, int cols, List<Piece> pieces) {
        if (instance == null) {
            lock.lock();
            try {
                if (instance == null) {
                    instance = new Puzzle(rows, cols, pieces);
                }
            } finally {
                lock.unlock();
            }
        }
        return instance;
    }

    public static Puzzle getInstance() {
        if (instance == null) throw new IllegalStateException("Puzzle not initialized");
        return instance;
    }

    // DD6: Choice: Validate fit against neighbors before insertion
    //      Alternative: Insert blindly, validate at end
    //      Tradeoff: Fail-fast avoids wasted work; solver can backtrack immediately
    //      TALK: "Checking neighbors on insert lets the solver prune bad placements early."
    public boolean insertPiece(Piece piece, int row, int col) {
        if (board[row][col] != null) return false;
        if (!fitsAt(piece, row, col)) return false;
        board[row][col] = piece;
        freePieces.remove(piece);
        return true;
    }

    public void removePiece(int row, int col) {
        Piece p = board[row][col];
        if (p != null) {
            board[row][col] = null;
            freePieces.add(p);
        }
    }

    // DD7: Choice: Boundary = must be FLAT; interior = must fitsWith neighbor
    //      Alternative: Single generic check
    //      Tradeoff: Explicit boundary logic catches edge/corner misplacements that generic check misses
    //      TALK: "A piece on row 0 must have a FLAT top — I enforce this structurally, not just via matching."
    private boolean fitsAt(Piece piece, int row, int col) {
        // Boundary checks: edges of board must be FLAT
        if (row == 0        && piece.getSide(Piece.TOP).getEdge()    != Edge.FLAT) return false;
        if (row == rows - 1 && piece.getSide(Piece.BOTTOM).getEdge() != Edge.FLAT) return false;
        if (col == 0        && piece.getSide(Piece.LEFT).getEdge()   != Edge.FLAT) return false;
        if (col == cols - 1 && piece.getSide(Piece.RIGHT).getEdge()  != Edge.FLAT) return false;

        // Interior sides must NOT be flat
        if (row > 0        && piece.getSide(Piece.TOP).getEdge()    == Edge.FLAT) return false;
        if (row < rows - 1 && piece.getSide(Piece.BOTTOM).getEdge() == Edge.FLAT) return false;
        if (col > 0        && piece.getSide(Piece.LEFT).getEdge()   == Edge.FLAT) return false;
        if (col < cols - 1 && piece.getSide(Piece.RIGHT).getEdge()  == Edge.FLAT) return false;

        // Neighbor matching
        if (row > 0 && board[row - 1][col] != null)
            if (!piece.getSide(Piece.TOP).fitsWith(board[row - 1][col].getSide(Piece.BOTTOM))) return false;
        if (row < rows - 1 && board[row + 1][col] != null)
            if (!piece.getSide(Piece.BOTTOM).fitsWith(board[row + 1][col].getSide(Piece.TOP))) return false;
        if (col > 0 && board[row][col - 1] != null)
            if (!piece.getSide(Piece.LEFT).fitsWith(board[row][col - 1].getSide(Piece.RIGHT))) return false;
        if (col < cols - 1 && board[row][col + 1] != null)
            if (!piece.getSide(Piece.RIGHT).fitsWith(board[row][col + 1].getSide(Piece.LEFT))) return false;

        return true;
    }

    public List<Piece> getFreePieces() { return Collections.unmodifiableList(freePieces); }
    public Piece[][] getBoard()        { return board; }
    public int getRows()               { return rows; }
    public int getCols()               { return cols; }

    // For testing: reset singleton
    static void resetInstance() { instance = null; }
}

// ==================== PUZZLE SOLVER ====================

// DD8: Choice: Backtracking solver, row-major order, tries all rotations per piece
//      Alternative: Constraint-propagation / heuristic (most-constrained-first)
//      Tradeoff: Backtracking is simple and correct; heuristic is faster but overkill for interview scope
//      TALK: "Backtracking with rotation gives O(n! * 4^n) worst case, but the fit-check prunes aggressively."

class PuzzleSolver {

    public boolean solve(Puzzle puzzle) {
        return solveAt(puzzle, 0, 0);
    }

    private boolean solveAt(Puzzle puzzle, int row, int col) {
        if (row == puzzle.getRows()) return true; // all rows filled

        int nextRow = (col == puzzle.getCols() - 1) ? row + 1 : row;
        int nextCol = (col == puzzle.getCols() - 1) ? 0 : col + 1;

        // DD9: Choice: Snapshot freePieces before iterating to avoid ConcurrentModificationException
        //      TALK: "Insert/remove mutates the list, so I copy before iterating."
        List<Piece> candidates = new ArrayList<>(puzzle.getFreePieces());

        for (Piece piece : candidates) {
            for (int rotation = 0; rotation < 4; rotation++) {
                if (puzzle.insertPiece(piece, row, col)) {
                    if (solveAt(puzzle, nextRow, nextCol)) return true;
                    puzzle.removePiece(row, col);
                }
                piece.rotateClockwise();
            }
        }
        return false;
    }
}

// ==================== DEMO ====================

class JigsawPuzzleDemo {
    public static void main(String[] args) {
        // Build a simple 2x2 puzzle
        //  [Corner] [Corner]
        //  [Corner] [Corner]
        // Each corner has 2 FLAT sides; matching sides use IND/EXT pairs

        List<Piece> pieces = new ArrayList<>();

        // Top-left: FLAT top, EXT right, EXT bottom, FLAT left
        pieces.add(new Piece(
            new Side(Edge.FLAT), new Side(Edge.EXTRUSION),
            new Side(Edge.EXTRUSION), new Side(Edge.FLAT)));

        // Top-right: FLAT top, FLAT right, IND bottom, IND left
        pieces.add(new Piece(
            new Side(Edge.FLAT), new Side(Edge.FLAT),
            new Side(Edge.INDENTATION), new Side(Edge.INDENTATION)));

        // Bottom-left: IND top, EXT right, FLAT bottom, FLAT left
        pieces.add(new Piece(
            new Side(Edge.INDENTATION), new Side(Edge.EXTRUSION),
            new Side(Edge.FLAT), new Side(Edge.FLAT)));

        // Bottom-right: EXT top, FLAT right, FLAT bottom, IND left
        pieces.add(new Piece(
            new Side(Edge.EXTRUSION), new Side(Edge.FLAT),
            new Side(Edge.FLAT), new Side(Edge.INDENTATION)));

        Puzzle.resetInstance();
        Puzzle puzzle = Puzzle.getInstance(2, 2, pieces);
        PuzzleSolver solver = new PuzzleSolver();

        boolean solved = solver.solve(puzzle);
        System.out.println("Solved: " + solved);

        if (solved) {
            Piece[][] board = puzzle.getBoard();
            for (int r = 0; r < puzzle.getRows(); r++) {
                for (int c = 0; c < puzzle.getCols(); c++) {
                    Piece p = board[r][c];
                    System.out.printf("[%s %s %s %s] ",
                        p.getSide(Piece.TOP).getEdge().name().charAt(0),
                        p.getSide(Piece.RIGHT).getEdge().name().charAt(0),
                        p.getSide(Piece.BOTTOM).getEdge().name().charAt(0),
                        p.getSide(Piece.LEFT).getEdge().name().charAt(0));
                }
                System.out.println();
            }
        }
    }
}
