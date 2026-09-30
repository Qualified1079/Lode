# Minecraft save compression — findings log

Scratch container is ephemeral. Move this somewhere durable if it matters.

## Headline number, revision history

Every revision was caused by a modeling error on my side, not by changing the encoders:

| # | Claim | Why it was wrong |
|---|---|---|
| 226× | simple terrain, hand-model vanilla with zero-filled light | light zeros → DEFLATE erased them; terrain had no realistic features |
| 73× | added block diversity, hand-model with random-filled light | random light is incompressible → vanilla 8.58× too big |
| 19× | realistic ore blobs + trees + villages | correct direction, still hand-model vanilla |
| 89× | feature extraction | correct technique, still hand-model vanilla |
| **10.5×** | **measured against genuine .mca written by a real library** | **first number grounded in ground truth** |

Lesson: the answer was dominated by the *baseline model*, not by encoder choice. Never quote a
compression ratio against a hand-rolled model of the incumbent format.

## Calibrated ground truth (side=512 = one region, 1024 chunks)

Real `.mca` written via `io.github.ens-gijs.nbt:nbt-mca`, verified readable
(1024 chunks / 11,218 sections, DataVersion 4903):

| Variant | On disk | Payload | Padding |
|---|---|---|---|
| blocks + skyLight + blockLight | 5,849,088 B | 4,070,592 B (3,975/chunk) | 30.3% |
| blocks + skyLight only | 5,849,088 B (identical) | — | — |
| blocks only | 4,202,496 B | 2,970,963 B (2,901/chunk) | 29.2% |

Decomposition of the 5,849,088-byte file:

| Component | Bytes | Share |
|---|---|---|
| Pure block data (paletted, bit-packed, DEFLATE'd) | 2,132,238 | 36.5% |
| NBT overhead (palette strings, biome palettes, heightmaps, section tags) | 838,725 | 14.3% |
| Light data | 1,099,629 | 18.8% |
| Sector padding (4 KiB granularity) | 1,770,304 | 30.3% |

BlockLight is all-zero in fresh worldgen (no player torches) → compresses to nothing. Adding it
changed the file by 0 bytes.

## LAYOUT ISOLATION — refutes the "DEFLATE can't see the runs" theory

Same voxels, same DEFLATE, different layout:

| Layout | Size | vs vanilla order |
|---|---|---|
| L1 vanilla order (YZX, y outermost) | 2,345,261 B | — |
| L2 section, Y innermost | 2,403,205 B | 0.98× WORSE |
| L3 full column, no section split | 3,257,848 B | 0.72× MUCH WORSE |
| L4 vanilla + palette + bit-packed | **2,132,238 B** | **1.10× BETTER** |
| L5 column-RLE | 2,626,383 B | 0.89× WORSE |

Conclusions:
- Axis order is NOT the lever. Y-innermost is slightly worse.
- The 16³ section split HELPS compression (keeps similar material together).
- Bit-packing HELPS (shrinks input before DEFLATE).
- **Column-RLE is 23% WORSE than Minecraft's own scheme on the block data.**
- ~63% of the measured "win" is overhead avoidance (padding + light + palette strings),
  NOT better compression.

**Do not try to beat Minecraft's palette + bit-pack + DEFLATE on block data. Use it.**

## Decode timing (first measured; matters for live use)

| Format | Decode | Throughput | Per chunk |
|---|---|---|---|
| Real MCA (library read + all states) | 1,957 ms | 51 Mvox/s | ~1.9 ms |
| Column-RLE (inflate + fill) | 524 ms | 192 Mvox/s | ~0.5 ms |
| Feature-extract | 751 ms (514 base + 237 replay) | 134 Mvox/s | ~0.73 ms |

Caveat: the library read allocates an NBT tag tree per block state — not representative of
Minecraft's optimized reader. Treat as "not a decode problem", not "3.7× faster than Mojang".
Feature replay = 2 µs/feature; a live per-chunk read needs only intersecting features (~113) ≈
0.23 ms. Needs a spatial index, and blobs straddle chunk boundaries (a chunk read must replay
features anchored in neighbours).

## Scheme verdicts

DEAD — do not revisit:
- **Surface / boundary encoding**: 0.09–0.10× (10× WORSE than vanilla). Minecraft masses are
  riddled with interior detail (ore every ~1000 voxels); a single isolated ore voxel = 6 faces.
  Surface encoding assumes interiors are featureless. They never are.
- **ColDict** (dedupe whole column profiles): 98% of columns unique with realistic features.
  A 384-block column is too information-rich to hash-cons.
- **ColDictQuad** (quadtree-DAG over column indices): worse than plain DEFLATE on the index array.
- **Per-chunk hybrid best-of**: selector byte is pure overhead; one scheme wins nearly every chunk.

ALIVE:
- **Feature extraction** — the one real win. Store terrain WITHOUT ore/variant blobs plus a
  compact (template, position) list. 4.7× better than encoding the final volume, verified
  bit-exact (0 / 402,653,184 voxel mismatch). 3.98 bytes/feature, stable across sizes.
  Works by removing blobs from the voxel data, so it helps ANY encoder — should be layered on
  Minecraft's paletted encoding, not column-RLE. **That control has NOT been run yet.**

## Scaling — no sublinearity is available

All measured exponents (size ~ area^b), realistic features:

| Scheme | b |
|---|---|
| vanilla | 0.998 |
| Column-RLE | 1.000 |
| Feature-extract | 1.000 |
| SVDAG | 0.925 → **1.009** (reverted) |
| ColDict | 0.946 → 1.031 |

SVDAG's apparent b=0.839 at one doubling was a transient, not a trend. Confirmed at side=4096:
SVDAG did not overtake Column-RLE, it fell further behind (1.075× → 1.087×).

**Why dedup degrades with scale** (two mechanisms, second is fundamental):
1. Pointer-width inflation: cost/node = (8 + 8·log₂N)/8 bytes. 1M→3.3M nodes pushed pointers
   20→22 bits, so every node got 8% more expensive.
2. Dedup saturates bottom-up but never top-down. At 2³ only a few hundred configurations occur →
   instant saturation. At 64³+ essentially every subtree is unique, and you need one per region →
   linear in volume by definition. Cost = saturating bottom term + linear top term; linear wins.

**Dedup buys a large constant factor, never a better exponent.** Node counts confirm: 89K → 3,335K
for 64× area (b=0.870 overall) but b=1.009 on the last doubling.

Corollary: dedup units must be coarse enough to repeat, fine enough to saturate. A tree or an ore
blob qualifies. An arbitrary 64³ cube does not.

## Worldgen facts used (minecraft.wiki)

- Ores: ellipsoidal blobs. Coal 20 attempts/chunk size≤17; iron 10/≤4; copper 16/≤10;
  diamond 7/≤4; gold 4/≤9; redstone 8/≤8. Discard-on-air-exposure 0.5 for coal/diamond.
- Trees: birch trunk 5–7 + ~55 leaves, NO branches; oak small/large; spruce broad/tall.
  A handful of templates → thousands of near-identical trees.
- Structures: jigsaw template pools — literally the same .nbt pieces placed repeatedly.
  Villages spacing ~34 chunks. Mineshafts use frequency not spacing.
- Stone variants (andesite/granite/diorite/tuff) are blob features, not noise.
- Chunks: 24 sections (y −64..320) since 1.18; biomes paletted per-section in 4×4×4 cells.
- Entities and POI live in SEPARATE parallel .mca trees since 1.17.
- Region format: 32×32 chunks, 8 KiB header, 4 KiB sectors, zlib (LZ4 since 24w04a),
  >1020 KiB spills to c.x.z.mcc.

**Critical: the features that repeat well (trees, buildings) are volumetrically negligible
(logs 0.025%, leaves 0.25%, planks ~0.0001%). The volumetrically significant ones (ore/variant
blobs, ~4%) repeat in SHAPE but not POSITION — and octree/column dedup is position-sensitive.**
That is why feature extraction works and spatial dedup doesn't.

## Open items (need real save files, not more simulation)

1. **NBT floor** — my synthetic world has ZERO block entities. Real chests/signs/spawners are
   opaque NBT both formats carry verbatim, so they're an additive constant that caps the ratio by
   Amdahl. This is the biggest threat to the 10.5×. Terrain-type dependent: wilderness ≈ 0
   (best case, and it IS the large-preload use case), village/mineshaft moderate, player base huge.
2. **Feature-detection rate** — my encoder KNOWS the generation algorithm. A real one must infer
   ore blobs and trees from raw voxels with explicit-voxel fallback. Multiply 10.5× by this.
3. **Real terrain complexity** — my chunks are 3,975 B payload; real ones are bigger (Properties
   compounds, 40–100 distinct states, multiple biomes, real structures). Direction favours us
   (most of that is vanilla-side overhead) but magnitude unknown.

## Architecture (current best)

```
Region archive:
├─ region_meta (DataVersion, section-Y bounds)
├─ de-featured terrain, encoded with MC's OWN palette + bit-pack + DEFLATE (not column-RLE)
├─ feature list: (template, position) varints, spatially sorted, DEFLATE'd
├─ block-entity NBT carried verbatim (the irreducible floor)
└─ per-chunk bitmap: empty / has-diff / escape-hatch
+ entities/ and poi/ trees: re-zip, don't be clever
```
Heightmaps and light are DERIVABLE (drop them, recompute) — that's 33% of vanilla's file for free.
Sector padding (30%) is avoided by construction.
Live use: baseline immutable, edits go to a diff overlay, promote a chunk to escape-hatch when its
diff exceeds a threshold. Guarantees never-worse-than-vanilla per chunk.

## Files

`VoxSim{1..9}.java` — encoder sims (v9 = feature extraction, lossless verified)
`CalibrateMca.java` — writes genuine .mca via real library, measures on disk
`Verify.java` / `Sectors.java` — roundtrip validation, payload-vs-padding analysis
`Probe.java` — layout isolation + decode timing
`mcaout/*.mca` — valid region files
`nbt-mca-0.2.1-SNAPSHOT.jar`, `nbt-0.1.1.jar` — the parser library

---

# REAL-OVERHEAD RERUN (addresses NBT floor + realistic vanilla overhead)

Added to the written .mca: block-state **Properties** compounds, **multi-entry biome palettes**
(4 biomes over 4×4×4 cells), **heightmaps** (4 kinds), status, and **block entities** at swept density.

Real .mca at density 0 went **5,849,088 → 7,356,416 B (+26%)** from Properties + biomes + heightmaps
alone. Confirms the earlier worry: the simple world understated vanilla.

## Block-entity density sweep (side=512, 1024 chunks)

| BE/chunk | real .mca | BE-NBT floor | ours (vox+BE) | ours (featex) | ratio | featex ratio |
|---|---|---|---|---|---|---|
| 0 (wilderness) | 7,356,416 | 0 | 2,901,193 | 658,535 | 2.54× | **11.17×** |
| 2 | 7,970,816 | 30,756 | 2,931,949 | 689,291 | 2.72× | **11.56×** |
| 10 (village-ish) | 8,294,400 | 140,255 | 3,041,448 | 798,790 | 2.73× | **10.38×** |
| 40 (built-up) | 8,396,800 | 531,201 | 3,432,394 | 1,189,736 | 2.45× | **7.06×** |

**The NBT floor is real but not fatal: 11× → 7× across wilderness → built-up.** At density 40 the
floor is ~45% of our total — exactly the Amdahl cap predicted. Two effects soften it:

1. **We aggregate block-entity NBT region-wide before compressing**; vanilla compresses per chunk.
   Density 0→40 cost us 531 KB but cost vanilla ~1,040 KB for the same data.
2. **Vanilla pays sector-padding granularity on top.** At density 2, vanilla grew 614,400 B (150
   extra 4 KiB sectors) for what costs us 30,756 B — a 20× difference for identical content.

Density 2 therefore scores *better* than density 0: vanilla's cost grows faster than ours.

**The large-preload / mostly-untouched-wilderness case is the BEST case (~11×).** That is the
stated use case, so this is favourable.

## NEW FINDING — bit-width uniformity beats per-section minimisation

My palette+bitpack here used `bits = max(1, log2ceil(np))`; Probe's L4 used `max(4, ...)` like real
MC. Result: this one is **2,901,193 B vs L4's 2,132,238 B — the "better" variable-width packing is
36% WORSE.** Cause: fixed 4-bit packing makes every section structurally similar, so DEFLATE finds
cross-section matches. Variable 1/2/3-bit packing makes each section's byte pattern unique and kills
that matching.

Consequence: **the voxel-encoder choice is still open.** Best measured remains Probe's L4
(2,132,238 B). Column-RLE (2,626,895 B) beat this rerun's paletted attempt. "Use MC's scheme" is
directionally right but needs real tuning, not assumption. Do NOT treat column-RLE-vs-paletted as
settled.

## Which earlier sims are invalidated

INVALIDATED:
- Every ratio-vs-vanilla quoted before the .mca calibration (hand model was 8.58× too harsh).
  Relative ordering of encoders is unaffected.
- "Column-RLE is the right encoder" — unsettled, see above.
- My layout/DEFLATE mechanism explanations (axis order, bit-packing hurting DEFLATE) — both false.

STILL VALID:
- All scaling exponents ≈ 1.0 (measured consistently within our own formats).
- Surface encoding dead (0.09×) — margin far too large for baseline error to rescue.
- ColDict / ColDictQuad dead — structural facts (98% unique columns), not ratios.
- Dedup-saturates-bottom-up mechanism + node counts.
- Feature extraction is a real, orthogonal win — now re-confirmed at 11.17× against a
  realistic-overhead real .mca, and it survives the NBT floor down to 7× worst case.

## Revised headline

**~7–11× against genuine .mca with realistic overhead**, depending on block-entity density.
Wilderness preloads ~11×; heavily-built worlds ~7×. Still to be multiplied by feature-detection
rate on real saves.

---

# SUBCHUNK-vs-COLUMN LAYOUT MISMATCH (was unhandled; now measured)

Every ratio quoted before this section used **region-wide DEFLATE** (one stream per 32×32 chunks).
That is unusable for live access, so those ratios and the "0.5 ms/chunk, fine for live" claim were
in tension. Measuring the dial resolves it.

## Ratio vs access granularity (same voxels, same encoder)

| Granularity | Size | vs region-wide | Section read amplification |
|---|---|---|---|
| G1 region-wide | 2,105,569 B | — | **11,235×** |
| G2 per-chunk (**vanilla's choice**) | 2,181,942 B | **1.036×** | 11.0× |
| G3 per-section | 2,491,269 B | 1.183× | 1.0× |
| G4 per-section + shared dict | 2,545,077 B | 1.209× | 1.0× |

Inflate one section: **5.7 µs (G3) vs 122 ms (G1) = 21,296× slower.**

- **Per-chunk granularity costs only 3.6%.** Vanilla's choice is nearly free. Copy it.
- Per-section costs 18.3% for true O(1) section reads.
- **Shared DEFLATE dictionary HURT** (−14% recovery). 2 KB primed dict doesn't pay for itself.

## COLUMN-RLE IS DEAD (dominated on both axes)

Column-RLE = 2,626,895 B, which is worse than **G3 per-section (2,491,269 B)** — a format with
*perfect* access properties. Combined with L3 (removing the section split = 0.72×), the section
structure is simply correct and column-major was the wrong instinct. Do not revisit.

## Feature spill across boundaries

| Metric | Value |
|---|---|
| spanning >1 section | 38,067 / 115,727 (32.9%) |
| spanning >1 chunk | 63,505 / 115,727 (54.9%) |
| replays needed per chunk | avg **201.2**, max 236 (vs 113 anchored) |
| replay inflation | **1.78×** |

Nothing spans >2 sections or >4 chunks (blob radii ≤ ~4 vs 16-wide sections), so the neighbour set
is bounded at 2×2×2 and the index can be implicit: bucket by anchor chunk, read self + 8 adjacent.

Design choice — cheap-looking option is wrong:
- index by affected region, replay neighbours → 1.78× replay work, list stays 461 KB ✓
- duplicate feature into every chunk it touches → list inflates to ~820 KB ✗
- clip features at boundaries → destroys template dedup, i.e. the whole mechanism ✗

## FINAL ARCHITECTURE

```
Per chunk (vanilla's granularity, +3.6% over region-wide):
├─ de-featured voxels: 16³ sections, paletted, FIXED 4-bit packing, DEFLATE per chunk
├─ feature list bucketed by anchor chunk (read self + 8 neighbours, replay intersecting)
└─ block-entity NBT verbatim (the irreducible floor)
Derived at load, never stored: heightmaps, light   (33% of vanilla's file, free)
Never paid: sector padding (30%), palette strings (14%)
```

## FINAL HEADLINE (chunk-accessible, realistic overhead, real .mca baseline)

**~10.8× wilderness → ~6.8× heavily-built.** Still to be multiplied by feature-detection rate on
real saves, which remains the largest unmeasured quantity and cannot be settled by simulation.

---

# DETECTION RATE — DEFINED AND MEASURED

**Definition.** The sims HAND the encoder the feature placement log (it generated the features). A
real encoder gets raw voxels and must reverse-engineer which voxels came from which placement.
  detection rate = (voxels correctly attributed to a recognized template) / (feature-placed voxels)
Undetected features stay baked into the voxel data and are stored explicitly.

## The curve (side=512, chunk-granular encoder, vs real .mca 7,356,416 B)

| Detection | Voxels | Feature list | Total | Ratio | % of perfect |
|---|---|---|---|---|---|
| 100% | 253,165 | 461,019 | 714,184 | **10.30×** | 100% |
| 95% | 410,963 | 439,244 | 850,207 | 8.65× | 84% |
| 90% | 532,823 | 417,463 | 950,286 | 7.74× | 75% |
| 75% | 851,873 | 351,633 | 1,203,506 | 6.11× | 59% |
| 50% | 1,324,443 | 239,931 | 1,564,374 | 4.70× | 46% |
| 25% | 1,766,696 | 124,075 | 1,890,771 | 3.89× | 38% |
| 0% | 2,181,942 | 0 | 2,181,942 | **3.37×** | 33% |

**Steep at the top**: −5% detection = −16% of the win. Mechanism: an undetected feature costs
**~27 B** (it shatters runs and inflates the paletted encoding) vs **3.98 B** as a stored reference
— a ~7× asymmetry. That asymmetry IS the leverage.

## Per-class: only blobs matter

| Detect only… | Features | Total | Ratio |
|---|---|---|---|
| **Blobs (ore/variant)** | 111,616 | 850,161 | **8.65×** |
| Trees | 4,111 | 2,040,749 | 3.60× |
| Buildings | 0 (none generated at side=512) | 2,181,942 | 3.37× |
| Nothing (floor) | — | 2,181,942 | 3.37× |

Blobs alone = 84% of the achievable win, and 96.4% of features by count. Trees close only 7% of the
gap to the floor. **So skip tree and building detection entirely** — which is fortunate, because
those are the hard inverse problems (merged canopies, terrain-adapted foundations, player damage)
while blobs are the tractable one (distinctive block types, small, ellipsoidal, small shape family).

Caveat: the buildings row is UNTESTED — village spacing is 34 chunks so side=512 has one candidate
cell and its placement hash skipped it. Needs side >= 1088 to exercise. Volumetrically trivial.

## Final honest range

- **3.37× floor** — no detection at all, pure overhead avoidance (padding 30% + light 19% +
  heightmaps + palette strings 14%). Trivially implementable, no inverse problem.
- **~7–8×** — realistic blob detection (80–90%).
- **10.30× ceiling** — perfect blob detection.

Downside is bounded and the upside needs exactly one tractable detector. That is a much more robust
shape of answer than a single number.

Blob-detector requirements (for whoever builds it): connected-component over ore/variant block
types, fit to the ellipsoid shape family, recover (shape, size, centre); MUST be lossless, so emit
a correction list for any voxel the fitted template gets wrong, and fall back to explicit storage
when corrections exceed the reference cost. Failure modes to handle: overlapping blobs merging into
one component, blobs truncated by caves or later placements, player-mined partials.

---

# WRITE PATH (was unmeasured; write is the harder direction — DEFLATE is asymmetric)

Raw payloads: ours de-featured **6,198 B** | ours full 22,644 B | vanilla-equiv **68,884 B**.

## Deflate per chunk — we WIN the write path, because vanilla must deflate too

| Level | Ours | Vanilla-equiv | Ours faster | Out (ours) |
|---|---|---|---|---|
| 1 | 157.9 µs | 246.6 µs | 1.56× | 353 B |
| **6** (zlib default = MC's) | **388.9 µs** | **1,107.3 µs** | **2.85×** | **250 B** |
| 9 | 615.8 µs | 6,332.2 µs | 10.28× | 247 B |

We hand DEFLATE ~11× less data, so we are 2.85× faster at MC's own level. **Adopting this format
IMPROVES save performance rather than costing it.** Level 9 is pointless (247 vs 250 B for +58%
time); level 1 saves 59% time but costs 41% ratio. Level 6 is correct.

## Live hot path is free

| Operation | Cost | Rate |
|---|---|---|
| Overlay append (1 block edit) | **0.047 µs** | 21.3 M edits/s |
| Overlay flush, 16 / 128 / 1024 edits | 44 / 26 / 61 µs | (16-vs-128 inversion = JIT noise) |

## Server load model

| Loaded chunks | full re-encode lvl6 | lvl1 | overlay-only |
|---|---|---|---|
| 200 | 8.8 ms | 5.5 ms | 2.4 ms |
| 800 | 35.2 ms | 22.0 ms | 9.8 ms |
| 2,000 | 88.1 ms | 54.9 ms | 24.4 ms |

Autosave interval is ~5 min; spread across ticks 2,000 chunks = 2.2 ms/tick. No stutter risk.

## The one slow number, and why it does not matter for live

**Full re-encode incl. rebuilding the palette from scratch: 2,547 µs/chunk (393 chunks/s).**
Deflate is only 389 µs of that; ~2.1 ms is the per-voxel palette-build loop (unoptimised —
allocates a ByteArrayOutputStream per section).

- Live: irrelevant. You keep the paletted container in memory as MC does and only serialize+deflate
  on save → 389 µs.
- Archival: a 10k×10k world = 390,625 chunks ≈ **16 min** to compress. Fine for an offline tool.

**Encode/decode asymmetry:** ~63 µs/chunk decode (11 sections × 5.7 µs inflate) vs 389 µs encode
= 6.2×. Expected DEFLATE shape, both well inside budget.

## Write hazards specific to this design (architectural, not perf)

1. **Feature-list rewriting** — a region-wide feature stream would mean re-DEFLATEing 461 KB for one
   block edit. Fatal. Fix: bucket features per anchor chunk (same bucketing reads already need).
2. **Feature invalidation** — a mined ore blob makes its (template,position) reference stale. Keep
   the reference and record removed voxels in the overlay (cheap). Do NOT re-bake into voxels.
3. **Promotion re-encode** — when an overlay exceeds threshold, promote to escape-hatch = full
   re-encode. One-time per chunk; frequency set by the threshold.

## VERDICT: live viability

Read fine (0.5 ms/chunk), write fine (0.39 ms/chunk, 2.85× faster than vanilla), hot path free
(21 M edits/s). **Live use is not blocked by performance in either direction.** Remaining live
risks are architectural (the three hazards above), not throughput.

---

# REAL MINECRAFT DATA — first numbers not from a simulation

World generated in-container: Paper 1.21.8 + Chunky 1.4.40 + JDK 25, seed 48920571,
2048×2048 blocks, 16,641 chunks pregenerated in 13:34, structures on.
(Version-gate chain: MC 26.3 needs Java 25; Chunky 1.5.3 needs Java 25 but only supports MC 26.x;
Chunky 1.4.40 supports 1.21.8. Container had Java 21 → pulled Temurin 25.)

## Real vanilla baseline (22,801 chunks, 36 regions)

| Metric | REAL | my synthetic | gap |
|---|---|---|---|
| file bytes/chunk | **7,943 B** | 5,712 B | 1.39× bigger |
| payload/chunk | **5,181 B** | 3,975 B | 1.30× |
| sector padding | 32.7% | 30.3% | ~same |
| sections/chunk | **24.7** | 11.0 | 2.24× |

sections/chunk >24 is correct, not a bug: MC stores light-only boundary sections.

## Terrain complexity — the gap finally quantified

| Metric | REAL | mine |
|---|---|---|
| distinct block NAMES | 299 | 26 |
| **distinct block STATES (Name+Properties)** | **1,118** | 26 |
| distinct biomes | 33 | 1 |
| palette/section avg | 3.9 | 6.9 |
| palette/section max | 68 | 13 |
| air fraction | 75.3% | 9.3% |

Real palette AVERAGE is lower than mine despite 43× more states — real worlds are bimodal:
mostly trivial sections (395,666 of 547,224 = 72% are whole-air) plus a few very complex ones.

## NBT floor — the worry was misplaced for wilderness

**0.19 block entities per chunk. 3 B/chunk deflated.** Negligible.
I swept 0/2/10/40 per chunk and fretted over the 40 case; real fresh-gen is 0.19.
The world does contain real structures (800 chests, 704 brushable_block, 659 decorated_pot,
546 mob_spawner, 432 dispenser, 415 vault, 249 trial_spawner — trial chambers present), they are
just sparse across 22,801 chunks. NBT only threatens player-built worlds, not preloads.

## Ore-like volume — my model was right

**4.21%** of all blocks, vs ~4% assumed. Composition differs (real is dominated by stone variants:
tuff 18.4M, andesite 16.1M, diorite 15.3M, granite 14.9M; true ores rarer — copper 1.16M,
coal 1.12M, iron 0.82M) but the feature-extraction target volume is as modelled.

## *** THE FLOOR, MEASURED ON REAL DATA: 2.73× ***

Our encoder (per-section palette of global state ids + fixed-min-4-bit packing, DEFLATE per chunk,
block entities verbatim; light/heightmaps derived, padding and palette strings not paid):

| | bytes | per chunk |
|---|---|---|
| vanilla on disk | 181,112,832 | 7,943 B |
| ours, blocks only | 65,925,371 | 2,891 B |
| ours + block entities | **66,244,750** | **2,905 B** |
| **ratio** | **2.73×** | |

**Synthetic predicted 3.37×; real is 2.73× — 19% worse.** Terrain complexity (1,118 states vs 26)
cost more than the larger real baseline (7,943 vs 5,712 B/chunk) gained.

## Status of the two numbers

- **Floor (no feature detection): 2.73×, MEASURED ON REAL DATA.** Solid.
- **Ceiling (with feature extraction): still unmeasured on real data.** Synthetic said 10.30×.
  If the same ~19% degradation applies it lands near ~8×, but that is extrapolation, and it is
  gated on a real ore-blob detector that does not exist yet. Do not quote it as measured.

---

# PHASE 0b/0c/1 — PARTITION ECONOMICS AND THE DECISIVE TEST (all on REAL .mca)

## Partition options, chunk-accessible, 4 real regions, 305,854 components

| Encoding | Bytes | verdict |
|---|---|---|
| NAIVE-SPLIT (cut at chunk bounds) | 55,203,550 | cut faces inflate surface 1.13× |
| A: OFFSET-INDEX (faces sorted by chunk + sparse index) | 50,243,078 | only 3.2% over non-accessible whole-surface |
| B: resident side table, giants unsplit | 2,121,684 (giants only) | **5.4× WORSE** than splitting them |
| C: chunk-local column-RLE | 20,389,045 | 2.6× worse than bitmask |
| **BITMASK-SPLIT per chunk piece** | **7,989,342** | **WINS, 6.3× over surface** |
| D: bitmask + sparse coord anchors | **7,022,976** | **10% better; knee at X=256** |

Key reversal vs Phase 0a: 0a said surface beat bitmask for giant components. That was because 0a
used each component's GLOBAL bbox (~30M mostly-empty cells for a region-spanning dirt sheet).
Chunk-splitting fits a TIGHT bbox per piece, so **the access requirement rescued bitmask**.
Splitting is not a tax for bitmask — it is an optimization.

Sparse-anchor calibration (option D): saving saturates by X=256 (9,138 B of anchors, walk ≤256
pieces); X=16384 buys another 0.001× for a 64× longer walk. Addressable fraction was ~10% (the
positional chunk id); type+bbox are real data and survive.

Surface encoding could not be rescued by a better index: its payload is 23,577,172 faces × 2 B =
47.2 MB vs bitmask's 5.45 MB of bits. Even a zero-cost index leaves it ~6.7× behind.

## *** PHASE 1: THE DECISIVE TEST — feature extraction buys 6.5% ***

3 real regions, 207,194 components, 264,301 pieces, 11,013,159 voxels moved.
**Verified lossless: 0 mismatching voxels.**

| | bytes | ratio |
|---|---|---|
| vanilla on disk | 20,307,968 | — |
| BASELINE (no extraction) | 7,839,878 | 2.59× |
| de-featured voxels | 3,826,803 | |
| component stream | 3,531,942 | |
| **EXTRACTED total** | **7,358,745** | **2.76×** |
| **extraction vs baseline** | | **1.065× better** |

De-featuring DOES halve terrain cost (7.84M → 3.83M, 2.05× cheaper) — the mechanism is real.
But the component stream costs 3.53M, eating 88% of the 4.01M saved. Net +481,133 B = 6.5%.

**Why synthetic said 4.7× and real says 1.065×:** synthetic de-featured terrain compressed to
96,745 B for a whole region — the base had nothing left in it. Real de-featured terrain still costs
~1.3 MB/region (caves, 33 biomes, 523 block states). The synthetic win measured the absence of
complexity I had not modelled.

Headroom not in this number: shape dictionary for components ≤64 voxels (Phase 0 measured 12.73×
reuse) could cut ~0.9 MB from the component stream → maybe 2.85×. Another ~3%.

# FINAL VERDICT

**SHIP THE FLOOR: 2.77×** on the complete world folder (region + entities + poi + level.dat),
measured on real .mca, verified lossless, ~0.5 ms/chunk decode, **encodes 2.85× FASTER than
vanilla**. No detector, no CCL, no inverse problem — it is a re-encode.

Feature extraction: real, lossless, and worth 6.5% for a connected-component pass over every region
plus a detector that must survive player-modified worlds. **Not worth the price.**

Where the 2.77× actually comes from — all of it is not paying for what vanilla pays for:
  * 32.7% sector padding (4 KiB granularity)
  * 18.8% light data (derivable — skylight is a function of the heightmap)
  * 14.3% palette strings + per-section NBT wrappers
  * heightmaps (derivable)
Every exotic encoding tested — surface, SVDAG, column dictionaries, quadtree-DAG, column-RLE,
dominant-page, shape dictionaries — lost to simply not paying vanilla's overhead.

---

# PER-REGION SPREAD + REAL ACCESS PATTERNS (corrects the headline)

## Ratio is NOT uniform across regions

| | ratio |
|---|---|
| aggregate | 2.75× |
| **WORST region** (r.1.-2) | **2.26×** |
| p10 | 2.38× |
| median | 6.47× |
| p90 | 26.92× |
| best (r.-3.-3) | 53.42× |

No bad tail BELOW (worst is only 18% under aggregate) — the floor is solid. But median 6.47× vs
aggregate 2.75× means many regions compress enormously: those are **partially-generated edge
regions** of the 2048×2048 pregen, where vanilla pays a 4 KiB sector minimum for near-empty chunks
and we pay ~nothing.

**CORRECTION TO HEADLINE: a genuinely dense world is ~2.3–2.4×, not 2.77×.**
Dense regions cluster at 2.26–2.38×. The 2.77× aggregate is inflated by pregen-boundary sparsity.
Always check the distribution before quoting an aggregate.

## Access patterns — the game does coherent bursts, not random points

| pattern | time | per query |
|---|---|---|
| LOAD compressed → queryable | 262.7 µs/chunk | — |
| MESH section sweep, 6-neighbour (19,208 q) | 5.0 µs | ~0.26 ns* |
| LIGHT BFS flood (3,000 blocks) | 71.8 µs | 23.9 ns |
| RANDOM uniform points | 241.3 µs | 58.9 ns |
| **NAIVE re-inflate per burst** | 117.3 µs | **23.5× WORSE** |

\* 0.26 ns = 0.78 cycles/query — treat as "essentially free", likely JIT hoisting / ILP on
L1-resident data. The LIGHT figure includes real BFS bookkeeping and is the trustworthy one.

Findings:
1. **There is no walk in the shipped format.** A query is a bit-extract + palette index, O(1),
   identical to vanilla's PalettedContainer. Sparse-anchor walks only exist in the feature-
   extraction component stream, which Phase 1 showed is not worth shipping.
2. **Coherent bursts are FASTER per query than random points** (2–200×), because a section's packed
   data is ~2–3 KB and sits in L1. The game's real access pattern is the favourable case; the
   earlier random-point benchmark was pessimistic.
3. **Materialise once per chunk load and cache it** — exactly what Minecraft already does. Naive
   re-inflate per burst costs 23.5×. This is the failure mode to document.

Load 262.7 µs/chunk: a player pulling 20–50 chunks/s costs ~5–13 ms/s ≈ 1% of one core.
Write 388.9 µs/chunk at level 6, still **2.85× faster than vanilla** (we hand DEFLATE ~11× less).

---

## Correction: the shipped benchmark was dropping all non-derivable chunk NBT

Found by a late audit (`research/FieldAudit.java`), which enumerated every key present in 6,144
real chunks with per-chunk raw bytes. `MCBench` was carrying block states, biomes and block
entities and silently discarding everything else. Raw bytes per chunk of what was being dropped:

| Field | B/chunk raw | Derivable? |
|---|---|---|
| `block_ticks` | 7,746 | No |
| `structures` | 3,573 | No |
| `fluid_ticks` | 1,592 | No |
| `PostProcessing` | 462 | No |
| `starlight.*` | ~245 | No (server-mod state) |
| `Status`, `DataVersion`, `xPos`/`yPos`/`zPos`, `LastUpdate`, `InhabitedTime` | ~70 | No |
| `Heightmaps` | — | Yes, from blocks |
| `BlockLight` / `SkyLight` | ~900 deflated | Yes, from blocks |

13,451 B/chunk raw. `MCBench` now serialises the residual chunk NBT verbatim and includes it in
every ratio it reports. Effect at deflate6/per-chunk: **2.34× → 1.87×**.

The `LiveAddon` measurement had already carried these correctly, which is why its 2.04×/2.49×
rows were never wrong — but the tool shipped for other people to run was, and that is the one
that matters.

Same root cause as the biomes bug: **there is no round-trip test.** Every correctness check in
this study compared one specific representation against itself. A byte-level `.mca` → Lode →
`.mca` diff is the only thing that would have caught either, and it does not exist.

### Reconciling the codec/granularity spread

The 1.87× and the 2.49× in this document are the same payload under different compressor settings,
not a disagreement. `MCBench` now reports all of them in one run, so the table in `README.md` is
internally consistent:

| Codec / granularity | Light | Size | Ratio |
|---|---|---|---|
| deflate6, per chunk | dropped | 32,721,765 B | 1.87× |
| zstd19, per chunk | dropped | 29,227,712 B | 2.09× |
| zstd19, per region | kept | 29,859,866 B | 2.05× |
| zstd19, per region | dropped | 25,477,176 B | 2.40× |

zstd19 over deflate6 is ~12%. Region granularity over per-chunk is ~15%, paid for with read
amplification (inflating 1,024 chunks to read one). Linear lvl19 measures 1.88× on these same
regions at zstd19/per-region, so **2.40× vs 1.88× is the apples-to-apples comparison** and the
earlier "deflate6 beats Linear's zstd19" claim was comparing a non-viable blocks-only figure
against a lossless one.

---

# v0.2.0 — DECODER, ROUND TRIP, IN-GAME VALIDATION

World: Paper 1.21.8-60, seed 48920571, Chunky radius 1024 — the same world as the study above
(22,801 chunks in 36 regions, 16,641 of them `Status=full`). Dictionary-training world: seed
777123, radius 512, never evaluated on. Raw outputs in `results/`.

## Codec

`tools/LodeTool.java`. Per chunk NBT: block-state palette entries → ids in a global table (keyed by
exact serialized bytes), biome palette → ids, then a columnar layout — every section's block
palette+data into one stream, biomes into another, residual NBT last with a 1-byte placeholder at
each pulled key so key order survives. `--drop-light` also removes `Heightmaps`, `SkyLight`,
`BlockLight` and Paper's `starlight.*` light state.

## Round trip: 22,801 / 22,801 byte-exact

Decode(encode(chunk)) re-serializes to exactly the source bytes, light kept; with light dropped it
equals the source minus the light fields. The parser itself re-serializes all 22,801 source chunks
byte-exactly, so the check is against what's on disk, not against a library's reading of it.
Every ratio in the tables below counts only chunks that passed.

## Sizes (vs vanilla allocated sectors + headers = 175,833,088 B; file lengths are 3.0% larger)

| | light kept | light dropped |
|---|---|---|
| vanilla NBT, deflate6/chunk, no padding | 1.49× | — |
| Linear-style, zstd19/region, vanilla NBT | 2.21× | — |
| Lode NBT id-form, deflate6/chunk | 1.60× | 1.96× |
| Lode columnar, deflate6/chunk | 1.64× | 2.02× |
| Lode columnar, zstd3/chunk | 1.55× | 1.90× |
| Lode columnar, zstd6/chunk | 1.67× | 2.04× |
| **Lode columnar, zstd6+dict/chunk** | **1.83×** | **2.20×** |
| Lode columnar, zstd9+dict/chunk | 1.86× | 2.24× |
| Lode columnar, zstd9+dict/2×2 group | 1.90× | 2.27× |
| Lode columnar, zstd19+dict/chunk | 2.07× | 2.48× |
| Lode columnar, zstd19/4×4 group | 2.15× | 2.56× |
| Lode columnar, zstd19/region | 2.28× | 2.71× |

Findings:
- **zstd below level 6 loses to deflate6 on single chunks** (zstd3 1.55× vs deflate6 1.64×). At
  ~30 KB inputs zstd's fast levels don't have deflate6's match search.
- **The dictionary is worth +8–12% per chunk, trained on a different seed.** 112 KiB, charged to
  every row. It recovers most of what per-region compression gains, without read amplification.
- **2×2 grouping buys ~3% over per-chunk with a dictionary.** Probably not worth the 4× write
  amplification for a live format. 4×4 without a dict is worse than per-chunk with one at zstd9.
- **Global table at region granularity: +3%** (2.21× → 2.28×), not the +9% the v0.1.0 README
  claimed. Its value is at small windows, where zstd can't see palette strings from other chunks.

## Time (single thread, mean of 2,000 chunks, µs)

| | write | read |
|---|---|---|
| vanilla: serialize + zlib default / inflate + parse | 929 | 265 |
| Lode zstd3, light kept | 272 | 133 |
| Lode zstd6+dict, light kept | 536 | 150 |
| Lode zstd6+dict, light dropped | 460 | 129 |
| Lode zstd9+dict, light kept | 847 | 146 |
| Lode zstd19+dict, light dropped | 4,615 | 140 |
| palette ↔ id transform | 43 | 26 |

Run-to-run noise on these is ~5–10% (vanilla write measured 908–973 µs across runs). The
transform was 779 µs before replacing a serialize-per-lookup with a structural-equality map; a
game integration would not need it at all, since runtime block states are already registry ids.
The first columnar pack was 2× slower than necessary because it wrote each data long byte-by-byte
into a synchronized `ByteArrayOutputStream` — fixed with one bulk write per section.

## In-game

1. **Light kept:** exported (`.mca` → Lode → `.mca`), loaded every chunk in Paper, zero errors.
   Semantically identical to a control load of the original world (`LodeTool diff`): both differ
   from disk only in the ~49 chunks near spawn that ticked (`LastUpdate`, one fluid block).
2. **Light dropped, first attempt:** `Failed to parse light data` for **every** chunk —
   `SWMRNibbleArray: Data cannot be null and have state be initialised`. Paper (Moonrise/Starlight)
   records per-section `starlight.skylight_state` / `starlight.blocklight_state` and
   `starlight.light_version`, and trusts those over `isLightOn`. The v0.1.0 field audit listed
   `starlight.*` as non-derivable; it is light state and must be dropped with the light.
3. **Light dropped, fixed:** zero errors. Relight validated with `tools/lightdump` (a plugin that
   pins every chunk, lets light settle 600 ticks, then dumps sky/block light per block):

   | comparison | sky light differs | block light differs |
   |---|---|---|
   | naive dump (no settle) — relit vs stored | 0.0093% | 0.102% (all darker) |
   | settled — relit vs stored | 0.00010% (109 chunks) | 0.0092% (1,553 chunks) |
   | settled — relit vs **relit again** | 0.00007% (101 chunks) | 0.0089% (1,530 chunks) |

   Relit-vs-stored is the same size as relit-vs-relit: the residue is the live server changing
   between runs, not relight error. The first row is a measurement artifact (a chunk was dumped
   before its neighbours had loaded) and is recorded because it looked like a real bug.
4. **Relight cost in the real engine:** loading all 16,641 chunks took 97.8 s with light dropped vs
   92.6 s with light stored (and 100.0 vs 95.2 s in the unsettled run) — **~0.3 ms/chunk**, a fifth
   of the naive-BFS estimate. The naive estimate was a skylight-only single-chunk BFS; Starlight is
   simply faster.

## MCBench fixes

- Vanilla bytes were added before a region was parsed; unparseable regions/chunks stayed in the
  denominator while contributing 0 to ours. Now only chunks fully re-encoded count, on both sides.
- Empty `catch(Throwable){}` around biomes, block entities and residual NBT could silently shrink
  "ours". A chunk is now encoded completely or excluded, and failures are printed by cause.
- On this (clean) world neither bug changed Lode's byte count. MCBench now prints the ratio against
  both allocated sectors (2.08×) and file lengths (2.14×, the v0.1.0 denominator).

---

# v0.3.0 — BUILT-UP CHUNKS (simulated survival base)

The one untested case v0.1.0 flagged as the biggest threat: concentrated block entities.

## The test world

`tools/survivalsim` (Paper plugin) builds a lived-in chunk in each of 16×16 chunks around spawn of
the same seed-48920571 world: a stone-brick/plank house with glass, a slab roof in two states, a
storage room of 20 double-chest halves over a hopper line, 6 barrels, 4 furnaces/blast furnaces with
fuel/input/output, 3 signs, lanterns + glowstone, redstone dust/repeater/lamp, a 4×4 wheat/carrot
farm with random crop ages and a water source, and a 2-high strip mine with torches at y=-54.
Containers: 60% "sorted storage" (1–3 bulk item types, full stacks), 40% "junk" (any item, random
counts, 1-in-8 a tool with damage + 1–3 enchantments + sometimes a custom name, 1-in-40 a shulker box
with its own contents). Deterministic (seed 42). **41.1 block entities/chunk; block-entity NBT is
29% of the chunk NBT** (wilderness: 0.2/chunk, 0.1%).

Honest limits of the simulation: every chunk gets the *same house*, so cross-chunk redundancy is
higher than any real town — treat the grouped/per-region rows below as optimistic. Container
contents are drawn from the full item registry, which is *more* varied than real storage. Entities
(item frames, armour stands, villagers) live in `entities/` and aren't part of this test.

## New transform: block-entity `id` strings → global table

Inside `block_entities`, every `id` string — block-entity type, item id, nested shulker contents —
becomes an IntTag into a third global table (`ids`). The type change is the marker; an `id` that
was already an int would decode to a string and fail the byte-exact check rather than pass silently.

## Town chunks only (256 chunks, all round-trip byte-exact)

| setting | light kept, before → after `ids` | light dropped, before → after |
|---|---|---|
| Linear-style zstd19/region | 2.03× | — |
| deflate6/chunk | 1.24× → 1.37× | 1.55× → 1.76× |
| zstd6/chunk | 1.28× → **1.44×** | 1.60× → **1.86×** |
| zstd6+dict/chunk (wilderness dict) | 1.28× → 1.43× | 1.58× → 1.82× |
| zstd9+dict/2×2 group | 1.47× → 1.63× | 1.74× → 1.97× |
| zstd19/4×4 group | 1.89× → 2.03× | 2.22× → 2.41× |
| zstd19/region | 2.08× → 2.17× | 2.42× → **2.54×** |

- **Built-up chunks are the hard case, as predicted:** live per-chunk ratios fall from ~1.8–2.2× to
  ~1.4–1.9×. The `ids` table recovers 12–17% of it at every setting and costs nothing on wilderness
  (world A final: 1.83× / 2.21× at zstd6+dict, unchanged).
- **The wilderness-trained dictionary does nothing for town chunks** (it has never seen item NBT);
  plain zstd6 is marginally better. A shipped dictionary must be trained on real player worlds.
- **Grouping helps much more here than in wilderness** (+15–25% vs +3%), but that is exactly the
  part the identical-house simulation inflates.

## In game

Exported town world, light kept and light dropped: all 16,641 chunks load in Paper with no errors
beyond the standard startup warnings. Decoded region files diff clean against the source (0 voxels,
0 block-entity chunks, 0 light nibbles, 0 other fields).

Relight in the town (lanterns, glowstone, torches, glass, water — the case the wilderness test
couldn't exercise), 24×24 chunks around the town, settled 600 ticks:

| comparison | sky differs | block differs |
|---|---|---|
| relit vs stored | 0.0086% (97 chunks) | 0.0009% (13 chunks) |
| stored vs stored (two fresh copies) | 0.0115% (108 chunks) | 0.0004% (10 chunks) |

Same noise floor as before. The ±1–2 sky deltas in the town come from the farm water, placed with
physics off, starting to flow once chunks tick. (The light-dropped town world was loaded once for
the error check before its light dump, so relit light may have been saved in between; either way
the compared light was computed by the game.)
