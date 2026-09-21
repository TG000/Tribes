package utils.stats;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MCTSTreeStatsLogger {
    private static final String TOURNAMENT_SUMMARY_FILE = "tournament_summary.csv";
    private static final String BY_TURN_METRICS_FILE = "tree_stats_by_turn.csv";
    private static final String BY_ACTION_SIZE_METRICS_FILE = "tree_stats_by_action_size.csv";

    // 3-digit Run ID generated once per tournament run (e.g., "001", "002")
    private static final String RUN_ID = _determineNextRunId();

    private static BufferedWriter tournamentSummaryWriter;

    // Game context tracking
    private static int currentGameId;
    private static long currentLevelSeed;
    private static int currentRepetition;

    // In-Memory Aggregators
    private static final Map<String, Accumulator> byTurnStats = new HashMap<>();
    private static final Map<String, Accumulator> byActionSizeStats = new HashMap<>();

    private static class Accumulator {
        long count = 0;
        double sumDepth = 0.0;
        double sumExpandedRatio = 0.0;

        void add(int depth, double ratio) {
            count++;
            sumDepth += depth;
            sumExpandedRatio += ratio;
        }
    }

    static {
        try {
            File summaryFile = new File(TOURNAMENT_SUMMARY_FILE);
            boolean fileExists = summaryFile.exists() && summaryFile.length() > 0;

            // Open in APPEND mode (true)
            tournamentSummaryWriter = new BufferedWriter(new FileWriter(summaryFile, true));

            // Write header only if the file is brand new
            if (!fileExists) {
                tournamentSummaryWriter.write("RunID,ParticipantID,Agent,Repetitions,LevelSeeds,TotalGames,Wins,WinRate,AvgScore,AvgTechPercent,AvgFinalCities,AvgProduction,AvgWars,AvgStars\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Guarantees buffered streams are flushed and closed if JVM exits early
        Runtime.getRuntime().addShutdownHook(new Thread(MCTSTreeStatsLogger::close));
    }

    /**
     * Determines the next 3-digit sequential RunID by reading existing CSV entries.
     */
    private static String _determineNextRunId() {
        int maxRunId = 0;
        File summaryFile = new File(TOURNAMENT_SUMMARY_FILE);

        if (summaryFile.exists() && summaryFile.length() > 0) {
            try (BufferedReader reader = new BufferedReader(new FileReader(summaryFile))) {
                String line;
                boolean isHeader = true;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) {
                        isHeader = false;
                        continue;
                    }
                    String[] parts = line.split(",");
                    if (parts.length > 0 && parts[0].matches("\\d{3}")) {
                        int id = Integer.parseInt(parts[0]);
                        if (id > maxRunId) {
                            maxRunId = id;
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        int nextId = (maxRunId % 999) + 1;
        return String.format("%03d", nextId);
    }

    /**
     * Sets context tracking variables for the active game instance.
     */
    public static synchronized void setGameContext(int gameId, long levelSeed, int repetition) {
        currentGameId = gameId;
        currentLevelSeed = levelSeed;
        currentRepetition = repetition;
    }

    /**
     * Accumulates tree depth and fully explored ratio into in-memory maps.
     */
    public static synchronized void logTreeMetrics(String agentClass, int turn, int actionSpaceSize,
                                                   int subtreeDepth, double fullyExpandedRatio) {
        // Track turns 0 through 50 individually
        if (turn >= 0 && turn <= 50) {
            String turnKey = agentClass + "_" + turn;
            byTurnStats.computeIfAbsent(turnKey, k -> new Accumulator()).add(subtreeDepth, fullyExpandedRatio);
        }

        // Track action space sizes 0 through 500 individually
        if (actionSpaceSize >= 0 && actionSpaceSize <= 500) {
            String actionKey = agentClass + "_" + actionSpaceSize;
            byActionSizeStats.computeIfAbsent(actionKey, k -> new Accumulator()).add(subtreeDepth, fullyExpandedRatio);
        }
    }

    public static synchronized void logTournamentSummary(String agent, int participantId, int repetitions, int levelSeeds,
                                                         int games, int wins, double winRate, double avgScore,
                                                         double avgTechs, double avgCities, double avgProduction,
                                                         double avgWars, double avgStars) {
        if (tournamentSummaryWriter == null) return;
        try {
            tournamentSummaryWriter.write(String.format(Locale.US, "%s,%d,%s,%d,%d,%d,%d,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                    RUN_ID, participantId, agent, repetitions, levelSeeds, games, wins, winRate, avgScore, avgTechs, avgCities,
                    avgProduction, avgWars, avgStars));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void close() {
        _exportByTurnCSV();
        _exportByActionSizeCSV();

        try {
            if (tournamentSummaryWriter != null) {
                tournamentSummaryWriter.flush();
                tournamentSummaryWriter.close();
                tournamentSummaryWriter = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void _exportByTurnCSV() {
        File turnFile = new File(BY_TURN_METRICS_FILE);
        boolean fileExists = turnFile.exists() && turnFile.length() > 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(turnFile, true))) {
            if (!fileExists) {
                writer.write("RunID,Agent,Turn,SampleCount,AvgSubtreeDepth,AvgFullyExpandedRatio\n");
            }

            for (Map.Entry<String, Accumulator> entry : byTurnStats.entrySet()) {
                String[] parts = entry.getKey().split("_");
                String agent = parts[0];
                int turn = Integer.parseInt(parts[1]);
                Accumulator acc = entry.getValue();

                writer.write(String.format(Locale.US, "%s,%s,%d,%d,%.2f,%.4f\n",
                        RUN_ID, agent, turn, acc.count, acc.sumDepth / acc.count, acc.sumExpandedRatio / acc.count));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void _exportByActionSizeCSV() {
        File actionFile = new File(BY_ACTION_SIZE_METRICS_FILE);
        boolean fileExists = actionFile.exists() && actionFile.length() > 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(actionFile, true))) {
            if (!fileExists) {
                writer.write("RunID,Agent,ActionSpaceSize,SampleCount,AvgSubtreeDepth,AvgFullyExpandedRatio\n");
            }

            for (Map.Entry<String, Accumulator> entry : byActionSizeStats.entrySet()) {
                String[] parts = entry.getKey().split("_");
                String agent = parts[0];
                int actionSize = Integer.parseInt(parts[1]);
                Accumulator acc = entry.getValue();

                writer.write(String.format(Locale.US, "%s,%s,%d,%d,%.2f,%.4f\n",
                        RUN_ID, agent, actionSize, acc.count, acc.sumDepth / acc.count, acc.sumExpandedRatio / acc.count));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}