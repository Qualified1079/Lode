# research/

The measurement programs that produced the numbers in `../docs/FINDINGS.md`.
All run against **real `.mca` files**, unlike `../sims/`.

Each is a standalone `main()`. Compile against the ens-gijs NBT jars (and zstd-jni for the
codec comparisons) — see `../build.sh` for dependency coordinates.

| File | What it measures |
|---|---|
| `RealEncode.java` | Core: our re-encode vs real Anvil, per-chunk DEFLATE |
| `RealWorld.java` | Vanilla baseline, terrain complexity, block-entity density |
| `ZstdCmp.java` | DEFLATE vs zstd at several levels, per-chunk vs per-region |
| `LiveAddon.java` | Cost of carrying non-derivable NBT (the live-safe number) |
| `WorstChunk.java` | Per-chunk ratio distribution — establishes the ~2× floor |
| `AccessBench.java` | Per-region ratio spread; mesh/light/random query patterns |
| `CacheBench.java` | Inflate vs structure-build split; RAM footprint; lazy vs eager |
| `RelightCost.java` | Cost of regenerating dropped light |
| `WritePath.java` | Encode cost, overlay append/flush, server load model |
| `Phase0.java` | Connected-component census (shape reuse, surface-to-volume) |
| `Phase0b.java` | Partition economics: naive split vs offset index vs bitmask |
| `Phase0c.java` | Options B/C/D incl. sparse coordinate anchors |
| `Phase1.java` | Feature extraction — the decisive test (verdict: 6.5%) |
| `CalibrateMca.java` | Writes synthetic terrain as genuine `.mca` via the real library |
| `Verify.java` / `Sectors.java` | Round-trip validation; payload vs padding |
| `Profile.java` | Vertical profile and veneer test (for player-built maps) |
| `MapRender.java` | Renders a region file to PNG — validates written `.mca` is game-valid |
| `Probe.java` | Layout isolation: same voxels, different byte orders |
| `BiomeCost.java` | Cost of biome data — found it was being dropped entirely (0.2%) |
