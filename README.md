# Lode v0.3.0

Research into compressing Minecraft Java world saves: a benchmark tool, and now a real codec
(encoder + decoder) with a byte-exact round-trip check and an in-game load test.

**Headline (all rows round-trip verified, 22,801 real chunks, Paper 1.21.8):**

| Use | Setting | Light | Ratio vs vanilla | Write | Read |
|---|---|---|---|---|---|
| Vanilla Anvil | zlib, per chunk, 4 KiB sectors | stored | 1.00× | 929 µs | 265 µs |
| **Live, drop-in shape** | **zstd6 + dict, per chunk** | stored | **1.83×** | 536 µs | 150 µs |
| **Live, relight on load** | **zstd6 + dict, per chunk** | relit (+~0.3 ms/chunk once) | **2.20×** | 460 µs | 129 µs |
| Live, fastest | zstd3, per chunk | stored | 1.55× | 272 µs | 133 µs |
| Idle recompress | zstd19 + dict, per chunk | relit | 2.48× | 4.6 ms | 140 µs |
| Archival (no random access) | zstd19, per region | relit | 2.71× | — | — |
| *Linear-style reference* | *zstd19, per region, vanilla NBT* | *stored* | *2.21×* | — | — |

**Built-up chunks (v0.3.0)** — a simulated survival town, 41 block entities/chunk, block-entity
NBT 29% of each chunk (`tools/survivalsim`):

| Setting | Light stored | Light relit |
|---|---|---|
| zstd6, per chunk | 1.44× | 1.86× |
| zstd19, per region | 2.17× | 2.54× |
| *Linear-style reference* | *2.03×* | — |

A whole world's ratio depends on how much of it is built. Linear blend of the two measured
per-chunk figures (zstd6+dict; an estimate, not a measurement): 10% built → 1.75× / 2.13×,
30% built → 1.63× / 2.02×, 100% built → 1.43× / 1.82×.

Ratios are against the sectors vanilla actually allocates to these chunks plus region headers.
Against raw vanilla file lengths (which also count free sectors) every ratio is ~3% higher.
Timings are single-thread means over 2,000 chunks, serialize+compress / decompress+parse.

What "verified" means now, concretely:

| Claim | Status |
|---|---|
| Lossless | **Measured: 22,801 / 22,801 chunks byte-exact** after `.mca` → Lode → NBT, light kept; same with light dropped (vs source minus light) |
| Loads in the game | **Measured**: round-tripped worlds loaded in Paper 1.21.8, all 16,641 full chunks, zero errors; light-kept world is semantically identical to a control load |
| Relight is correct | **Measured**: game relight vs stored light differs in 0.009% of block-light / 0.0001% of sky-light values — *the same as two relights of the same world differ from each other* (live-server noise floor) |
| Relight cost | **Measured in-game**: ~0.3 ms/chunk (98 s vs 93 s to load 16,641 chunks) — not the 1.6 ms naive estimate |
| Plays in Minecraft (players, edits, saves) | **Not attempted** — loaded and lit, never played |
| Dictionary generalises | **Measured on one held-out world** (trained on seed 777123, evaluated on 48920571). One pair is not a survey |
| Built-up chunks | **Measured on a simulated town** (256 chunks, 7,680 containers, enchanted/named items, shulker boxes, signs, redstone, farms, strip mine): byte-exact round trip, loads in Paper, relit town light within the noise floor. Simulated, not a real player world — see FINDINGS v0.3.0 for how it's biased |
| Real long-lived survival worlds | **Untested** — the remaining gap. Run `LodeTool bench` on one |

Things v0.2.0 corrected about v0.1.0 — read before quoting old numbers:

- **The global state table is worth ~3%, not 9%,** at matched codec and region granularity
  (Linear-style 2.21× → Lode light-kept 2.28×). It is worth ~7–10% at per-chunk granularity,
  where the compressor's window can't dedupe palette strings across chunks.
- **"Drop light" was broken in-game.** Paper stores `starlight.light_version` and per-section
  `starlight.*_state` and trusts them over `isLightOn`; dropping the arrays but keeping those
  made Paper fail to parse light for *every* chunk. They are light state and go with the light.
- **The per-chunk dictionary is the main live-format win**: +8–12% at zstd6–19 per chunk,
  without giving up random access. Charged in full (112 KiB) to every row that uses it.
- **Columnar layout** (all block data in one stream, biomes in another, NBT framing after) is
  worth another ~3%, lossless, verified. The old benchmark did this without a decoder.
- **Block-entity `id` strings go in a global table too (v0.3.0)**: +12–17% on built-up chunks,
  no effect on wilderness. Without it, bases compressed ~15% worse than the numbers above.
- **zstd19 is not a live setting**: 5–10 ms/chunk to write. The old "a bit faster" claim came
  from a feature-extraction prototype that isn't the shipped design.

The v0.1.0 text below is kept for its analysis; where its numbers disagree with the table above,
the table above wins.

---

## The result

Measured on 6 real region files (Minecraft 1.21.8, Paper-generated, 6,144 chunks). Every Lode row
is **live-safe** — biomes plus every non-derivable chunk NBT field (`block_ticks`, `fluid_ticks`,
`structures`, `PostProcessing`, `Status`, `InhabitedTime`, positions, block entities) carried
verbatim. Only light and heightmaps are treated as derivable. All Lode rows come from the one
shipped tool, `tools/mcbench.jar`, in a single run:

| Approach | Codec / granularity | Size | Ratio |
|---|---|---|---|
| Vanilla Anvil | zlib, per chunk | 61,186,048 B | 1.00× |
| Linear lvl1 (live-write setting) | zstd1, per region | 48,089,330 B | 1.27× |
| **Linear lvl19** (idle recompress) | zstd19, per region | 32,618,365 B | **1.88×** |
| Lode, light dropped + relight | deflate6, per chunk | 32,721,765 B | 1.87× |
| Lode, light kept | zstd19, per region | 29,859,866 B | 2.05× |
| Lode, light dropped + relight | zstd19, per chunk | 29,227,712 B | 2.09× |
| **Lode, light dropped + relight** | zstd19, per region | 25,477,176 B | **2.40×** |
| *Lode, blocks+biomes+BE only (**not** a viable format)* | deflate6, per chunk | 26,177,461 B | *2.34×* |

Read the codec column before quoting a number. Two things move the ratio as much as the data model
does:

- **Granularity.** Region-wide compression (one zstd stream per 1,024 chunks) is worth ~15% over
  per-chunk, and costs you random access — you inflate the whole region to read one chunk. Linear
  makes that same trade, so the region-vs-region comparison (2.40× vs 1.88×) is the fair one.
- **Codec.** zstd19 is worth ~12% over deflate6 on the same bytes.

**A claim this repo used to make and shouldn't have:** "Lode with deflate6 beats Linear with
zstd19." That compared Lode's *non-viable* blocks-only figure (2.34×) against Linear's fully
lossless one. Live-safe Lode at deflate6/per-chunk is 1.87× — level with Linear's 1.88×, not
ahead of it, despite *also* having dropped light. Lode's win over Linear only appears once you
match Linear's codec and granularity: 2.05× with light kept, 2.40× with light dropped.

## Where the size actually goes

Decomposition of a real 5,849,088-byte region file:

| Component | Share | Recoverable? |
|---|---|---|
| Block data (palette + packed indices) | 36.5% | No — irreducible |
| **Sector padding** (4 KiB granularity) | **32.7%** | Yes — just don't pad |
| **Light data** | **18.8%** | Yes — derivable from blocks |
| **Palette strings + NBT wrappers** | **14.3%** | Yes — global integer table |

Roughly two thirds of an Anvil file is packaging. That's the whole finding.

## How this differs from Linear

Linear is a **container** fix: it removes sector padding and swaps zlib for zstd, while keeping
chunk NBT byte-identical. It's drop-in, lossless, production-tested, and has a
[Paper integration in progress](https://github.com/PaperMC/Paper/issues/14045).

Lode is a **data model** re-encode: a global block-state table instead of per-section palette
strings, and dropping derivable data (light, heightmaps).

The two are complementary and compose. Holding codec and granularity fixed at Linear's setting
(zstd19, per region), the data-model changes are worth:

- Global state table instead of per-section palette strings: **1.88× → 2.05×** (+9%)
- Also dropping light and relighting on load: **2.05× → 2.40×** (+17% more)

Neither is free. The state table is a global side file, so a region is no longer independently
readable. The light removal needs a relight pass on load.

**If you want one thing from this repo:** the global-state-table trick is an 8% win that would
slot into Linear as a small patch, and it's byte-exact-compatible in a way the light removal isn't.

## Costs

| | |
|---|---|
| Chunk load (compressed → queryable) | 136–270 µs |
| Chunk encode (zstd3 / deflate6) | 148 µs / 704 µs |
| **Relight, if light is dropped** | **~1,647 µs/chunk** (naive BFS; a real engine is faster) |
| Materialised RAM per chunk | ~17.7 KB (4.7× the compressed form) |

Relight is the load-bearing cost. It's ~3% of worldgen (48.9 ms/chunk measured), so a preload
still avoids 97% of the work it exists to avoid — but it's 6–12× the chunk load cost, so for a
live server you either keep light (2.04×) or cache relit chunks as you go.

## Things that did NOT work

Tested and killed, with data. Recorded so nobody repeats them:

| Idea | Result |
|---|---|
| Surface / boundary encoding of monoblock masses | **0.09×** — 10× *worse* than vanilla |
| SVDAG (sparse voxel DAG) | Sublinearity was a transient; reverts to linear by 4096² |
| Column dictionaries (dedupe whole column profiles) | 98% of columns are unique |
| Quadtree-DAG over column indices | Worse than plain DEFLATE on the index array |
| Column-RLE | 23% worse than Minecraft's own palette+bitpack |
| Feature extraction (pull ore blobs out as shapes) | Works, bit-exact, worth only **6.5%** |
| Per-chunk hybrid best-of-N encoding | Selector byte costs more than it saves |

The broad lesson: **Minecraft's block encoding is already good.** Every attempt to out-compress
the block data lost. The win is in not storing packaging.

## Using the benchmark

```
java -jar tools/mcbench.jar <world-folder> [maxRegions]                     # quick estimate
java -cp tools/mcbench.jar LodeTool bench <world> [--dict-from <otherWorld>] # verified codec + timings
java -cp tools/mcbench.jar LodeTool export <world> <out> [--drop-light]     # .mca -> Lode -> .mca
java -cp tools/mcbench.jar LodeTool diff <worldA> <worldB>                  # voxel/biome/BE/light diff
```

`tools/lightdump/` is a small Paper plugin that dumps live light from a running server, and
`tools/gametest/` holds the scripts used for the in-game load and relight tests.

Point it at a folder containing `region/`. Read-only, writes nothing. Defaults to the 40 largest
regions (the built-up ones — the conservative sample). Needs Java 21+.

Reports vanilla size, payload vs padding, terrain complexity, block-entity density, and the Lode
re-encode ratio.

To rebuild it:

```
./build.sh
```

Fetches `io.github.ens-gijs.nbt:nbt-mca` and `com.github.luben:zstd-jni`, compiles, and produces
`tools/mcbench.jar`.

## Repo layout

```
docs/FINDINGS.md   full research log — every measurement, every wrong turn, and why
docs/*.png         rendered region files (validation that written .mca is game-valid)
tools/             MCBench — the portable benchmark
research/          the measurement programs that produced the real numbers
sims/              synthetic-terrain sims. SUPERSEDED — kept only as a cautionary record
```

**A warning about `sims/`.** Those came first and produced badly wrong answers — up to 226×,
because they benchmarked against a hand-rolled model of Anvil rather than a real file, and used
synthetic terrain with 26 block states where real worlds have 1,118. The headline number moved
226× → 73× → 19× → 89× → 10.5× → 2.77× → 2.49× across the study, and **every single revision was
a modelling error, not a change to the encoders.** Read `docs/FINDINGS.md` before trusting
anything in `sims/`.

## Caveats

- See **Verification status** above. No decoder, never run in Minecraft.
- Worlds tested: modern wilderness (16,641 chunks), structure-saturated (4,225 chunks), and one
  player-built city map (which turned out to be a flat platform, 89.7% air — a soft test).
  **No long-running survival world** — the biggest untested case, and the one where concentrated
  block entities (storage rooms) could bite.
- The relight measurement is a naive BFS and is a pessimistic upper bound.
- Read performance vs *real* Minecraft is unmeasured. The only vanilla read number here is through
  a library that allocates an NBT tree per block state, which isn't a fair proxy.
- Ratios vary enormously by content: the 6-region sample above is dense modern terrain; sparse or
  flat worlds reach 19.67×. **~2.4× at region granularity, ~2.1× at chunk granularity is what to
  plan against**; any bigger aggregate figure you see in `docs/FINDINGS.md` is inflated by sparse
  regions or by a non-live-safe configuration.
- Per-chunk worst case on payload alone is 1.33–1.51×; a world made only of worst-1% chunks would
  be 1.38–1.73×.

## Prior art

- [Linear](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools) — the closest thing;
  container-level, drop-in, shipping
- [LinearReader](https://github.com/Bugfunbug/LinearReader) — Linear as a server mod, two-stage compression
- [ZVCR](https://github.com/2b2tplace/zvcr) — zstd + palettes, archival
- [paper-zstd](https://github.com/UltraVanilla/paper-zstd) — zstd chunks for Paper

## Dependencies

- [ens-gijs/NBT](https://github.com/ens-gijs/NBT) — MCA/NBT parsing
- [zstd-jni](https://github.com/luben/zstd-jni) — zstd bindings

Check their licenses before redistributing built jars.
