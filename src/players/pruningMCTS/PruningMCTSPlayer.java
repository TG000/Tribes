package players.pruningMCTS;

import core.Types;
import core.actions.Action;
import core.actions.tribeactions.EndTurn;
import core.actions.unitactions.Move;
import core.game.GameState;
import core.game.Board;
import players.Agent;
import players.heuristics.StateHeuristic;
import utils.ElapsedCpuTimer;
import utils.stats.MCTSTreeStatsLogger;

import java.util.*;

import static core.Types.ACTION.END_TURN;
import static core.Types.ACTION.DESTROY;

public class PruningMCTSPlayer extends Agent {

    private Random m_rnd;
    private PruningMCTSParams params;

    public PruningMCTSPlayer(long seed) {
        super(seed);
        m_rnd = new Random(seed);
        this.params = new PruningMCTSParams();
    }

    public PruningMCTSPlayer(long seed, PruningMCTSParams params) {
        this(seed);
        this.params = params;
    }

    @Override
    public Action act(GameState gs, ElapsedCpuTimer ect) {
        ArrayList<Action> allActions = gs.getAllAvailableActions();

        // Rule: Exclude destroy actions across the board
        allActions.removeIf(action -> action.getActionType() == DESTROY);

        if (allActions.size() == 0) {
            return new EndTurn();
        }
        if (allActions.size() == 1) {
            return allActions.get(0);
        }

        ArrayList<Action> rootActions = params.PRIORITIZE_ROOT ? determineActionGroup(gs, m_rnd) : allActions;
        if (rootActions == null || rootActions.isEmpty())
            return new EndTurn();

        // Setup temporary tracking metrics to build the heuristic framework at root level
        ArrayList<Integer> allIDs = allPlayerIDs;
        StateHeuristic rootStateHeuristic = params.getStateHeuristic(this.playerID, allIDs);

        // Perform move heuristic pruning at the root level if configured
        if (params.PRUNE_MOVES) {
            rootActions = preFilterMoveActions(gs, rootActions, rootStateHeuristic);
        }

        PruningTreeNode m_root = new PruningTreeNode(params, m_rnd, rootActions, this.playerID);
        m_root.setRootGameState(m_root, gs, allPlayerIDs);
        m_root.mctsSearch(ect);

        PruningTreeNode.SelectedAction selected = m_root.getBestActionWithStats();

        MCTSTreeStatsLogger.logTreeMetrics(
                getClass().getSimpleName(),                         // Agent Class Name
                gs.getTick(),                                       // Game Turn
                allActions.size(),                                  // Action Space Size
                m_root.selectedSubtreeDepth(selected.index),        // Selected Subtree Depth
                m_root.selectedFullyExpandedRatio(selected.index)   // Fully Expanded Ratio
        );

        return selected.action;
    }

    private ArrayList<Action> preFilterMoveActions(GameState contextState, ArrayList<Action> actionPool, StateHeuristic heuristic) {
        ArrayList<Action> nonMoveActions = new ArrayList<>();
        ArrayList<Action> rawMoveActions = new ArrayList<>();

        for (Action action : actionPool) {
            if (action instanceof Move) {
                rawMoveActions.add(action);
            } else {
                nonMoveActions.add(action);
            }
        }

        // T is the minimum number of actions that need to be kept after pruning. If there is any action set smaller than T, pruning is not applied.
        if (rawMoveActions.size() <= 5) {
            return actionPool;
        }

        ArrayList<Action> standardMoves = new ArrayList<>();
        ArrayList<Action> highValueMoves = new ArrayList<>();
        Board board = contextState.getBoard();

        for (Action act : rawMoveActions) {
            Move m = (Move) act;
            utils.Vector2d dest = m.getDestination();

            Types.TERRAIN terrain = board.getTerrainAt(dest.x, dest.y);
            boolean isCity = (terrain == Types.TERRAIN.CITY);

            Types.RESOURCE resource = board.getResourceAt(dest.x, dest.y);
            boolean isRuin = (resource == Types.RESOURCE.RUINS);

            if (isCity || isRuin) {
                highValueMoves.add(act);
            } else {
                standardMoves.add(act);
            }
        }

        int branchingFactor = standardMoves.size();
        // Equation 1: remainingNodes = max(alpha * log10(n), T) with alpha = 1.0, T = 5
        int remainingCount = (int) Math.max(1.0 * Math.log10(branchingFactor), 5);

        if (standardMoves.size() > remainingCount) {
            List<MoveScoreRecord> scoredMoves = new ArrayList<>();
            for (Action act : standardMoves) {
                GameState copyState = contextState.copy();
                copyState.advance(act, true);
                double score = heuristic.evaluateState(contextState, copyState);
                scoredMoves.add(new MoveScoreRecord(act, score));
            }

            // Sort in descending order to keep highest value actions
            scoredMoves.sort((r1, r2) -> Double.compare(r2.score, r1.score));

            standardMoves.clear();
            for (int i = 0; i < Math.min(remainingCount, scoredMoves.size()); i++) {
                standardMoves.add(scoredMoves.get(i).action);
            }
        }

        ArrayList<Action> consolidated = new ArrayList<>();
        consolidated.addAll(nonMoveActions);
        consolidated.addAll(highValueMoves);
        consolidated.addAll(standardMoves);
        return consolidated;
    }

    @Override
    public Agent copy() {
        return null;
    }

    private static class MoveScoreRecord {
        Action action;
        double score;
        MoveScoreRecord(Action action, double score) {
            this.action = action;
            this.score = score;
        }
    }

    private static class PruningTreeNode {
        private PruningMCTSParams params;
        private PruningTreeNode root;
        private PruningTreeNode parent;
        private PruningTreeNode[] children;

        private double totValue;
        private int nVisits;
        private Random m_rnd;
        private int m_depth;
        private double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE};
        private int fmCallsCount;
        private int playerID;

        private ArrayList<Action> activeActions;
        private boolean[] actionPrunedStatus;
        private GameState state;
        private GameState rootState;
        private StateHeuristic rootStateHeuristic;

        PruningTreeNode(PruningMCTSParams p, Random rnd, ArrayList<Action> initialActions, int playerID) {
            this.params = p;
            this.fmCallsCount = 0;
            this.parent = null;
            this.m_rnd = rnd;
            this.playerID = playerID;
            this.m_depth = 0;
            this.root = this;

            this.activeActions = new ArrayList<>(initialActions);
            this.children = new PruningTreeNode[activeActions.size()];
            this.actionPrunedStatus = new boolean[activeActions.size()];
            this.totValue = 0.0;
        }

        PruningTreeNode(PruningMCTSParams p, PruningTreeNode parent, Random rnd,
                        ArrayList<Action> actions, int playerID, PruningTreeNode root, GameState state) {
            this.params = p;
            this.parent = parent;
            this.m_rnd = rnd;
            this.activeActions = new ArrayList<>(actions);
            this.root = root;
            this.playerID = playerID;
            this.state = state;
            this.m_depth = parent.m_depth + 1;
            this.rootStateHeuristic = parent.rootStateHeuristic;
            this.children = new PruningTreeNode[activeActions.size()];
            this.actionPrunedStatus = new boolean[activeActions.size()];
            this.totValue = 0.0;
        }

        void setRootGameState(PruningTreeNode root, GameState gs, ArrayList<Integer> allIDs) {
            this.state = gs;
            this.root = root;
            this.rootState = gs;
            this.rootStateHeuristic = params.getStateHeuristic(playerID, allIDs);
        }

        void mctsSearch(ElapsedCpuTimer elapsedTimer) {
            double avgTimeTaken;
            double acumTimeTaken = 0;
            long remaining;
            int numIters = 0;
            int remainingLimit = 5;
            boolean stop = false;

            while (!stop) {
                ElapsedCpuTimer elapsedTimerIteration = new ElapsedCpuTimer();
                PruningTreeNode selected = treePolicy();
                double delta = selected.rollOut();
                backUp(selected, delta);
                numIters++;

                if (params.stop_type == params.STOP_TIME) {
                    acumTimeTaken += (elapsedTimerIteration.elapsedMillis());
                    avgTimeTaken = acumTimeTaken / numIters;
                    remaining = elapsedTimer.remainingTimeMillis();
                    stop = remaining <= 2 * avgTimeTaken || remaining <= remainingLimit;
                } else if (params.stop_type == params.STOP_ITERATIONS) {
                    stop = numIters >= params.num_iterations;
                } else if (params.stop_type == params.STOP_FMCALLS) {
                    stop = root.fmCallsCount > params.num_fmcalls;
                }
            }
        }

        private PruningTreeNode treePolicy() {
            PruningTreeNode cur = this;
            while (!cur.state.isGameOver() && cur.m_depth < params.ROLLOUT_LENGTH) {
                // Check if we need to dynamically unprune an action (Section 3.4)
                if (params.PROGBIAS) {
                    cur.executeProgressiveWidening();
                }

                // Execute structural pruning threshold rule (Section 3.3)
                if (cur.nVisits == params.VISIT_THRESHOLD) {
                    cur.executeHardPruning();
                }

                if (cur.notFullyExpanded()) {
                    return cur.expand();
                } else {
                    cur = cur.uct();
                }
            }
            return cur;
        }

        private void executeProgressiveWidening() {
            // Count current size of the unpruned set (n)
            int unprunedCount = 0;
            ArrayList<Integer> prunedIndices = new ArrayList<>();
            for (int i = 0; i < actionPrunedStatus.length; i++) {
                if (!actionPrunedStatus[i]) {
                    unprunedCount++;
                } else {
                    prunedIndices.add(i);
                }
            }

            // If nothing is pruned, progressive widening cannot unprune anything
            if (prunedIndices.isEmpty()) {
                return;
            }

            // Calculate Equation 2: Upsilon = alpha * beta^(n - n_init)
            double exponent = unprunedCount - params.N_INIT;
            double upsilon = params.PW_ALPHA * Math.pow(params.PW_BETA, exponent);

            // If our selection iteration exceeds Upsilon, unprune one action uniformly at random
            if (this.nVisits > upsilon) {
                int randomIndex = prunedIndices.get(m_rnd.nextInt(prunedIndices.size()));
                actionPrunedStatus[randomIndex] = false;
            }
        }

        private void executeHardPruning() {
            int branchingFactor = activeActions.size();
            if (branchingFactor <= params.T_MIN) {
                return;
            }

            // Equation 1 using Math.log10 to properly scale branching factors
            int remainingCount = (int) Math.max(params.ALPHA * Math.log10(branchingFactor), params.T_MIN);
            if (remainingCount >= branchingFactor) {
                return;
            }

            List<ActionScoreRecord> records = new ArrayList<>();
            for (int i = 0; i < activeActions.size(); i++) {
                if (actionPrunedStatus[i]) continue;

                double evaluationScore;
                if (children[i] != null) {
                    evaluationScore = children[i].totValue / (children[i].nVisits + params.epsilon);
                } else {
                    GameState speculativeState = state.copy();
                    speculativeState.advance(activeActions.get(i), true);
                    root.fmCallsCount++;
                    evaluationScore = rootStateHeuristic.evaluateState(root.rootState, speculativeState);
                }
                records.add(new ActionScoreRecord(i, evaluationScore));
            }

            records.sort(Comparator.comparingDouble(r -> r.score));

            int pruneAmount = branchingFactor - remainingCount;
            for (int i = 0; i < Math.min(pruneAmount, records.size()); i++) {
                int targetIndex = records.get(i).index;
                actionPrunedStatus[targetIndex] = true;
                children[targetIndex] = null;
            }
        }

        private int tryForceEnd(GameState state, EndTurn endTurn, int depth) {
            boolean willForceEnd = (depth > 0 && (depth % params.FORCE_TURN_END) == 0) && endTurn.isFeasible(state);
            if (!willForceEnd) return -1;

            for (int i = 0; i < activeActions.size(); i++) {
                if (!actionPrunedStatus[i] && activeActions.get(i).getActionType() == END_TURN) {
                    return i;
                }
            }
            return -1;
        }

        private PruningTreeNode expand() {
            int bestAction = tryForceEnd(state, new EndTurn(state.getActiveTribeID()), this.m_depth);
            if (bestAction == -1) {
                double bestValue = -1;
                for (int i = 0; i < children.length; i++) {
                    if (actionPrunedStatus[i]) continue;

                    double x = m_rnd.nextDouble();
                    if (x > bestValue && children[i] == null) {
                        bestAction = i;
                        bestValue = x;
                    }
                }
            }

            if (bestAction == -1) {
                for (int i = 0; i < children.length; i++) {
                    if (!actionPrunedStatus[i]) {
                        bestAction = i;
                        break;
                    }
                }
                if (bestAction == -1 && !activeActions.isEmpty()) {
                    bestAction = 0;
                    actionPrunedStatus[0] = false;
                }
            }

            if (bestAction == -1 || activeActions.isEmpty()) {
                GameState nextState = state.copy();
                EndTurn fallbackTurn = new EndTurn(state.getActiveTribeID());
                nextState.advance(fallbackTurn, true);
                root.fmCallsCount++;
                ArrayList<Action> rawAvailable = new ArrayList<>();
                return new PruningTreeNode(params, this, this.m_rnd, rawAvailable, this.playerID, this.root, nextState);
            }

            GameState nextState = state.copy();
            nextState.advance(activeActions.get(bestAction), true);
            root.fmCallsCount++;

            ArrayList<Action> rawAvailable = nextState.getAllAvailableActions();
            // Rule: Ensure destroy actions are stripped out during deep tree generations
            rawAvailable.removeIf(action -> action.getActionType() == DESTROY);

            PruningTreeNode tn = new PruningTreeNode(params, this, this.m_rnd, rawAvailable,
                    this.playerID, this.root, nextState);
            children[bestAction] = tn;
            return tn;
        }

        private PruningTreeNode uct() {
            PruningTreeNode selected = null;
            boolean IamMoving = (state.getActiveTribeID() == this.playerID);
            int bestAction = tryForceEnd(state, new EndTurn(state.getActiveTribeID()), this.m_depth);

            if (bestAction == -1) {
                double bestValue = IamMoving ? -Double.MAX_VALUE : Double.MAX_VALUE;

                for (int i = 0; i < this.children.length; ++i) {
                    if (actionPrunedStatus[i] || children[i] == null) continue;

                    PruningTreeNode child = children[i];
                    double childValue = child.totValue / (child.nVisits + params.epsilon);
                    childValue = normalise(childValue, bounds[0], bounds[1]);

                    double uctValue = childValue + params.K * Math.sqrt(Math.log(this.nVisits + 1) / (child.nVisits + params.epsilon));
                    uctValue = noise(uctValue, params.epsilon, this.m_rnd.nextDouble());

                    if ((IamMoving && uctValue > bestValue) || (!IamMoving && uctValue < bestValue)) {
                        selected = child;
                        bestValue = uctValue;
                    }
                }

                if (selected == null) {
                    List<Integer> activeIndices = new ArrayList<>();
                    for (int i = 0; i < children.length; i++) {
                        if (!actionPrunedStatus[i] && children[i] != null) activeIndices.add(i);
                    }
                    if (!activeIndices.isEmpty()) {
                        selected = children[activeIndices.get(m_rnd.nextInt(activeIndices.size()))];
                    } else {
                        selected = this;
                    }
                }
            } else {
                selected = children[bestAction];
            }

            root.fmCallsCount++;
            return selected;
        }

        private double rollOut() {
            if (params.ROLOUTS_ENABLED) {
                GameState rolloutState = state.copy();
                int thisDepth = this.m_depth;
                while (thisDepth < params.ROLLOUT_LENGTH && !rolloutState.isGameOver()) {
                    EndTurn endTurn = new EndTurn(rolloutState.getActiveTribeID());
                    boolean willForceEnd = (thisDepth > 0 && (thisDepth % params.FORCE_TURN_END) == 0) && endTurn.isFeasible(rolloutState);

                    Action next;
                    if (willForceEnd) {
                        next = endTurn;
                    } else {
                        ArrayList<Action> rollActions = rolloutState.getAllAvailableActions();
                        rollActions.removeIf(action -> action.getActionType() == DESTROY);
                        if (rollActions.isEmpty()) {
                            next = endTurn;
                        } else {
                            next = rollActions.get(m_rnd.nextInt(rollActions.size()));
                        }
                    }
                    rolloutState.advance(next, true);
                    root.fmCallsCount++;
                    thisDepth++;
                }
                return normalise(this.rootStateHeuristic.evaluateState(root.rootState, rolloutState), 0, 1);
            }
            return normalise(this.rootStateHeuristic.evaluateState(root.rootState, this.state), 0, 1);
        }

        private void backUp(PruningTreeNode node, double result) {
            PruningTreeNode n = node;
            while (n != null) {
                n.nVisits++;
                n.totValue += result;
                if (result < n.bounds[0]) n.bounds[0] = result;
                if (result > n.bounds[1]) n.bounds[1] = result;
                n = n.parent;
            }
        }

        Action getBestAction() {
            return getBestActionWithStats().action;
        }

        SelectedAction getBestActionWithStats() {
            int selected = -1;
            double bestValue = -Double.MAX_VALUE;
            boolean allEqual = true;
            double first = -1;

            for (int i = 0; i < children.length; i++) {
                if (children[i] != null && !actionPrunedStatus[i]) {
                    if (first == -1) first = children[i].nVisits;
                    else if (first != children[i].nVisits) allEqual = false;

                    double childValue = children[i].nVisits;
                    childValue = noise(childValue, params.epsilon, this.m_rnd.nextDouble());
                    if (childValue > bestValue) {
                        bestValue = childValue;
                        selected = i;
                    }
                }
            }

            if (selected == -1) {
                selected = getBestQAction();
            } else if (allEqual) {
                selected = getBestQAction();
            }

            if (selected >= 0 && selected < activeActions.size() && activeActions.get(selected) != null) {
                return new SelectedAction(selected, activeActions.get(selected));
            }

            for (int i = 0; i < activeActions.size(); i++) {
                if (activeActions.get(i) != null) {
                    return new SelectedAction(i, activeActions.get(i));
                }
            }

            return new SelectedAction(-1, new EndTurn(state.getActiveTribeID()));
        }

        int maxDepth() {
            int deepest = m_depth;
            for (PruningTreeNode child : children) {
                if (child != null) {
                    deepest = Math.max(deepest, child.maxDepth());
                }
            }
            return deepest;
        }

        int selectedSubtreeDepth(int selectedAction) {
            if (selectedAction < 0 || selectedAction >= children.length || children[selectedAction] == null) {
                return m_depth;
            }
            return children[selectedAction].maxDepth();
        }

        double selectedFullyExpandedRatio(int selectedAction) {
            if (selectedAction < 0 || selectedAction >= children.length || children[selectedAction] == null) {
                return 0.0;
            }
            return children[selectedAction].fullyExpandedRatio();
        }

        int selectedExpandedChildren(int selectedAction) {
            if (selectedAction < 0 || selectedAction >= children.length || children[selectedAction] == null) {
                return 0;
            }
            return children[selectedAction].expandedChildren();
        }

        int selectedAvailableChildren(int selectedAction) {
            if (selectedAction < 0 || selectedAction >= children.length || children[selectedAction] == null) {
                return 0;
            }
            return children[selectedAction].availableChildren();
        }

        public double fullyExpandedRatio() {
            int available = availableChildren();
            if (available == 0) {
                return 1.0;
            }
            return (double) expandedChildren() / (double) children.length;
        }

        private int availableChildren() {
            int available = 0;
            for (int i = 0; i < children.length; i++) {
                if (!actionPrunedStatus[i]) {
                    available++;
                }
            }
            return available;
        }

        private int expandedChildren() {
            int expanded = 0;
            for (int i = 0; i < children.length; i++) {
                if (!actionPrunedStatus[i] && children[i] != null) {
                    expanded++;
                }
            }
            return expanded;
        }

        private int getBestQAction() {
            int selected = -1;
            double bestValue = -Double.MAX_VALUE;

            for (int i = 0; i < children.length; i++) {
                if (children[i] != null && !actionPrunedStatus[i]) {
                    double childValue = children[i].totValue / (children[i].nVisits + params.epsilon);
                    childValue = noise(childValue, params.epsilon, this.m_rnd.nextDouble());
                    if (childValue > bestValue) {
                        bestValue = childValue;
                        selected = i;
                    }
                }
            }
            return selected == -1 ? 0 : selected;
        }

        private boolean notFullyExpanded() {
            for (int i = 0; i < children.length; i++) {
                if (!actionPrunedStatus[i] && children[i] == null) {
                    return true;
                }
            }
            return false;
        }

        private double normalise(double a_value, double a_min, double a_max) {
            if (a_min < a_max) return (a_value - a_min) / (a_max - a_min);
            return a_value;
        }

        private double noise(double input, double epsilon, double random) {
            return (input + epsilon) * (1.0 + epsilon * (random - 0.5));
        }

        private static class ActionScoreRecord {
            int index;
            double score;

            ActionScoreRecord(int index, double score) {
                this.index = index;
                this.score = score;
            }
        }

        private static class SelectedAction {
            int index;
            Action action;

            SelectedAction(int index, Action action) {
                this.index = index;
                this.action = action;
            }
        }
    }
}
