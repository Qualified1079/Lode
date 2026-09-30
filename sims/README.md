# sims/ — SUPERSEDED

Synthetic-terrain simulations, kept only as a cautionary record. **Do not trust these numbers.**

They came first and were badly wrong, for two reasons:

1. They benchmarked against a **hand-rolled model of the Anvil format** rather than real files.
   That model was 8.58× too pessimistic, which inflated every ratio.
2. The synthetic terrain had **26 distinct block states**; real worlds have **1,118**. It also had
   no biome variety, no structures, and scattered single-voxel ores instead of ellipsoidal blobs.

Consequences: `VoxSim9` reported feature extraction as a 4.7× win. On real data it is 1.065×.

Sequence: v1 baseline encoders → v2 bit-packing + DEFLATE → v3 realistic light + diversity →
v4/v5/v6 scaling to 4096² → v7 realistic worldgen features → v8/v9 feature extraction.

See `../docs/FINDINGS.md` for what actually held up.
