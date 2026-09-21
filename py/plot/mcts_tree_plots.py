import matplotlib.pyplot as plt
import pandas as pd

# 1. Load data directly from CSV files
df_action = pd.read_csv("tree_stats_by_action_size.csv")
df_turn = pd.read_csv("tree_stats_by_turn.csv")

# 2. Map (RunID, Agent) to line styling and labels matching the reference plot
label_mapping = {
    (1, "PruningMCTSPlayer"): ("hard pruning & move pruning", "red", "-"),
    (2, "PruningMCTSPlayer"): ("hard pruning", "green", "--"),
    (3, "PruningMCTSPlayer"): ("pw & move pruning", "blue", "-"),
    (4, "PruningMCTSPlayer"): ("pw", "y", "--"),
    (1, "MCTSPlayer"): ("original MCTS", "purple", ":"),
}

# 3. Create 2x2 subplot layout
fig, axes = plt.subplots(2, 2, figsize=(12, 9))

# -------------------------------------------------------------------------
# Figure 2: MCTS depth under different action space sizes (Top-Left)
# -------------------------------------------------------------------------
ax2 = axes[0, 0]
for (run_id, agent), (label, color, linestyle) in label_mapping.items():
    sub = (
        df_action[(df_action["RunID"] == run_id) & (df_action["Agent"] == agent)]
        .groupby("ActionSpaceSize")["AvgSubtreeDepth"]
        .mean()
        .reset_index()
    )
    ax2.plot(
        sub["ActionSpaceSize"],
        sub["AvgSubtreeDepth"],
        label=label,
        color=color,
        linestyle=linestyle,
    )

ax2.set_xlabel("Action Size")
ax2.set_ylabel("Depth")
ax2.legend()
ax2.set_title("Figure 2: MCTS depth under different action space sizes.", y=-0.25)

# -------------------------------------------------------------------------
# Figure 4: The depth of MCTS in different turns (Top-Right)
# -------------------------------------------------------------------------
ax4 = axes[0, 1]
for (run_id, agent), (label, color, linestyle) in label_mapping.items():
    sub = (
        df_turn[(df_turn["RunID"] == run_id) & (df_turn["Agent"] == agent)]
        .groupby("Turn")["AvgSubtreeDepth"]
        .mean()
        .reset_index()
    )
    sub = sub[sub["Turn"] <= 50]
    ax4.plot(
        sub["Turn"],
        sub["AvgSubtreeDepth"],
        label=label,
        color=color,
        linestyle=linestyle,
    )

ax4.set_xlabel("Turn")
ax4.set_ylabel("depth")
ax4.legend()
ax4.set_title("Figure 4: The depth of MCTS in different turns.", y=-0.25)

# -------------------------------------------------------------------------
# Figure 3: Fully explored rate under action space sizes (Bottom-Left)
# -------------------------------------------------------------------------
ax3 = axes[1, 0]
for (run_id, agent), (label, color, linestyle) in label_mapping.items():
    sub = (
        df_action[(df_action["RunID"] == run_id) & (df_action["Agent"] == agent)]
        .groupby("ActionSpaceSize")["AvgFullyExpandedRatio"]
        .mean()
        .reset_index()
    )
    ax3.plot(
        sub["ActionSpaceSize"],
        sub["AvgFullyExpandedRatio"],
        label=label,
        color=color,
        linestyle=linestyle,
    )

ax3.set_xlabel("Action Size")
ax3.set_ylabel("fully explored ratio")
ax3.legend()
ax3.set_title(
    "Figure 3: The fully explored rate for the recommended action\nin MCTS under different action space sizes.",
    y=-0.32,
)

# -------------------------------------------------------------------------
# Figure 5: Fully explored rate in different turns (Bottom-Right)
# -------------------------------------------------------------------------
ax5 = axes[1, 1]
for (run_id, agent), (label, color, linestyle) in label_mapping.items():
    sub = (
        df_turn[(df_turn["RunID"] == run_id) & (df_turn["Agent"] == agent)]
        .groupby("Turn")["AvgFullyExpandedRatio"]
        .mean()
        .reset_index()
    )
    sub = sub[sub["Turn"] <= 50]
    ax5.plot(
        sub["Turn"],
        sub["AvgFullyExpandedRatio"],
        label=label,
        color=color,
        linestyle=linestyle,
    )

ax5.set_xlabel("Turn")
ax5.set_ylabel("fully explored ratio")
ax5.legend(loc="lower left")
ax5.set_title(
    "Figure 5: The fully explored rate for the recommended action\nin MCTS under different turn",
    y=-0.32,
)

plt.tight_layout()

# Save image file directly
plt.savefig("graphs_recreated.png", dpi=300, bbox_inches="tight")
plt.close()

print("Plot successfully saved as 'graphs_recreated.png'!")