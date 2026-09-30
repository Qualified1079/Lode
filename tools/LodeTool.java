import io.github.ensgijs.nbt.io.*;
import io.github.ensgijs.nbt.tag.*;
import com.github.luben.zstd.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

// LodeTool — a real Lode codec (encode + decode), a byte-exact round-trip check, a benchmark
// whose every number comes from chunks that survived that check, and an exporter that writes
// decoded chunks back out as a vanilla .mca world so the game itself can load them.
//
//   java -cp mcbench.jar LodeTool bench  <world> [--dict-from <otherWorld>]
//   java -cp mcbench.jar LodeTool export <world> <outWorld> [--drop-light]
//
// Lode transform, per chunk NBT (nothing else changes, key order is preserved):
//   sections[*].block_states.palette : list of compounds -> IntArray of global block-state ids
//   sections[*].biomes.palette       : list of strings   -> IntArray of global biome ids
//   --drop-light additionally removes sections[*].SkyLight/BlockLight and Heightmaps.
// The global tables are a side file; its compressed size is charged to every Lode total.
public class LodeTool {

    // ---------------------------------------------------------------- NBT helpers
    static byte[] ser(Tag<?> t) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 15);
            try (BigEndianNbtOutputStream os = new BigEndianNbtOutputStream(bo, false)) {
                os.writeTag(new NamedTag("", t), 512);
            }
            return bo.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    static Tag<?> parse(byte[] b) {
        try (BigEndianNbtInputStream in = new BigEndianNbtInputStream(new ByteArrayInputStream(b))) {
            return in.readTag(512).getTag();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ---------------------------------------------------------------- global tables
    static final class Table {
        final ArrayList<Tag<?>> list = new ArrayList<>();
        final HashMap<String, Integer> idx = new HashMap<>();
        // Structural-equality fast path. Two entries equal but differing in key order would collide
        // here; Minecraft never writes that, and the byte-exact round-trip check would catch it.
        final HashMap<Tag<?>, Integer> fast = new HashMap<>();
        int id(Tag<?> t) {
            Integer f = fast.get(t);
            if (f != null) return f;
            int v = exactId(t);
            fast.put(t.clone(), v);
            return v;
        }
        int exactId(Tag<?> t) {
            // keyed by exact serialized bytes, so decode reproduces the entry byte-for-byte
            String k = new String(ser(t), java.nio.charset.StandardCharsets.ISO_8859_1);
            Integer v = idx.get(k);
            if (v == null) { v = list.size(); idx.put(k, v); list.add(t.clone()); }
            return v;
        }
        Tag<?> get(int i) { return list.get(i); }
    }
    static final class Tables {
        final Table blocks = new Table(), biomes = new Table(), ids = new Table();
        byte[] serialize() {
            CompoundTag c = new CompoundTag();
            ListTag<?> b = ListTag.createUnchecked(CompoundTag.class);
            for (Tag<?> t : blocks.list) addUnchecked(b, t);
            ListTag<?> bi = ListTag.createUnchecked(StringTag.class);
            for (Tag<?> t : biomes.list) addUnchecked(bi, t);
            ListTag<?> id = ListTag.createUnchecked(StringTag.class);
            for (Tag<?> t : ids.list) addUnchecked(id, t);
            c.put("blocks", b); c.put("biomes", bi); c.put("ids", id);
            return ser(c);
        }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void addUnchecked(ListTag l, Tag<?> t) { l.add(t); }

    // ---------------------------------------------------------------- codec
    static boolean paletteIs(CompoundTag holder, Class<?> type) {
        if (holder == null) return false;
        Tag<?> p = holder.get("palette");
        if (!(p instanceof ListTag<?> l) || l.size() == 0) return false;
        return type.isInstance(l.get(0));
    }

    static CompoundTag encode(CompoundTag chunk, Tables T, boolean dropLight) {
        return encodeInPlace(chunk.clone(), T, dropLight);
    }
    // Light-derived state that must go with the light arrays: Paper/Moonrise records per-section
    // light state and a light version, and trusts them over isLightOn. Leaving them while dropping
    // the arrays makes it fail to parse light for every chunk (field-verified on Paper 1.21.8-60).
    static final String[] SECTION_LIGHT_KEYS = {"SkyLight", "BlockLight", "starlight.skylight_state", "starlight.blocklight_state"};
    static final String[] CHUNK_LIGHT_KEYS = {"Heightmaps", "starlight.light_version"};

    static CompoundTag encodeInPlace(CompoundTag o, Tables T, boolean dropLight) {
        if (dropLight) for (String k : CHUNK_LIGHT_KEYS) o.remove(k);
        if (o.get("block_entities") != null) idsToInts(o.get("block_entities"), T.ids);
        Tag<?> st = o.get("sections");
        if (st instanceof ListTag<?> secs) {
            for (int i = 0; i < secs.size(); i++) {
                if (!(secs.get(i) instanceof CompoundTag s)) continue;
                if (dropLight) for (String k : SECTION_LIGHT_KEYS) s.remove(k);
                CompoundTag bs = s.getCompoundTag("block_states");
                if (paletteIs(bs, CompoundTag.class)) {
                    ListTag<?> p = bs.getListTag("palette");
                    int[] ids = new int[p.size()];
                    for (int k = 0; k < ids.length; k++) ids[k] = T.blocks.id((Tag<?>) p.get(k));
                    bs.put("palette", new IntArrayTag(ids));
                }
                CompoundTag bio = s.getCompoundTag("biomes");
                if (paletteIs(bio, StringTag.class)) {
                    ListTag<?> p = bio.getListTag("palette");
                    int[] ids = new int[p.size()];
                    for (int k = 0; k < ids.length; k++) ids[k] = T.biomes.id((Tag<?>) p.get(k));
                    bio.put("palette", new IntArrayTag(ids));
                }
            }
        }
        return o;
    }

    static CompoundTag decode(CompoundTag lode, Tables T) {
        return decodeInPlace(lode.clone(), T);
    }
    // Inside block_entities every "id" string (block-entity type, item id, nested shulker contents...)
    // becomes an IntTag index into a global table. The type change is the marker; an "id" that was
    // already an int would decode to a string and fail the byte-exact round-trip check, never silently.
    static void idsToInts(Tag<?> t, Table tab) {
        if (t instanceof CompoundTag c) {
            for (String k : new ArrayList<>(c.keySet())) {
                Tag<?> v = c.get(k);
                if (k.equals("id") && v instanceof StringTag) c.put(k, new IntTag(tab.id(v)));
                else idsToInts(v, tab);
            }
        } else if (t instanceof ListTag<?> l) {
            for (int i = 0; i < l.size(); i++) idsToInts((Tag<?>) l.get(i), tab);
        }
    }
    static void intsToIds(Tag<?> t, Table tab) {
        if (t instanceof CompoundTag c) {
            for (String k : new ArrayList<>(c.keySet())) {
                Tag<?> v = c.get(k);
                if (k.equals("id") && v instanceof IntTag it) c.put(k, tab.get(it.asInt()).clone());
                else intsToIds(v, tab);
            }
        } else if (t instanceof ListTag<?> l) {
            for (int i = 0; i < l.size(); i++) intsToIds((Tag<?>) l.get(i), tab);
        }
    }

    static CompoundTag decodeInPlace(CompoundTag o, Tables T) {
        if (o.get("block_entities") != null) intsToIds(o.get("block_entities"), T.ids);
        Tag<?> st = o.get("sections");
        if (st instanceof ListTag<?> secs) {
            for (int i = 0; i < secs.size(); i++) {
                if (!(secs.get(i) instanceof CompoundTag s)) continue;
                undo(s.getCompoundTag("block_states"), T.blocks, CompoundTag.class);
                undo(s.getCompoundTag("biomes"), T.biomes, StringTag.class);
            }
        }
        return o;
    }
    static void undo(CompoundTag holder, Table t, Class<?> type) {
        if (holder == null || !(holder.get("palette") instanceof IntArrayTag ia)) return;
        int[] ids = ia.getValue();
        ListTag<?> l = ListTag.createUnchecked(type, ids.length);
        for (int id : ids) addUnchecked(l, t.get(id).clone());
        holder.put("palette", l);
    }

    // ---------------------------------------------------------------- columnar layout
    // The id-form chunk still interleaves block data with section NBT framing. Columnar pulls every
    // section's block palette+data into one stream and biomes into a second, leaving a ByteTag
    // placeholder at the original key position so the residual NBT keeps exact key order:
    //   varint len(blocks) | blocks | varint len(biomes) | biomes | residual NBT
    // A holder with any key beyond palette/data is left in the residual untouched.
    static void wVar(ByteArrayOutputStream b, long n) {
        while ((n & ~0x7FL) != 0) { b.write((int) ((n & 0x7F) | 0x80)); n >>>= 7; }
        b.write((int) n);
    }
    static long rVar(DataInputStream in) throws IOException {
        long r = 0; int sh = 0, b;
        do { b = in.readUnsignedByte(); r |= (long) (b & 0x7F) << sh; sh += 7; } while ((b & 0x80) != 0);
        return r;
    }
    static boolean columnable(Tag<?> t) {
        if (!(t instanceof CompoundTag c) || !(c.get("palette") instanceof IntArrayTag)) return false;
        for (String k : c.keySet()) if (!k.equals("palette") && !(k.equals("data") && c.get(k) instanceof LongArrayTag)) return false;
        return true;
    }
    static void pull(CompoundTag s, String key, ByteArrayOutputStream out, List<Object[]> undo) {
        if (!columnable(s.get(key))) return;
        CompoundTag c = s.getCompoundTag(key);
        int[] ids = ((IntArrayTag) c.get("palette")).getValue();
        long[] data = c.get("data") instanceof LongArrayTag la ? la.getValue() : null;
        boolean paletteFirst = c.keySet().iterator().next().equals("palette");
        out.write(data == null ? 1 : paletteFirst ? 2 : 3);
        wVar(out, ids.length);
        for (int id : ids) wVar(out, id);
        if (data != null) {
            wVar(out, data.length);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(data.length * 8);
            bb.asLongBuffer().put(data);
            out.write(bb.array(), 0, bb.capacity());
        }
        undo.add(new Object[]{s, key, c});
        s.put(key, new ByteTag((byte) 0));
    }
    // Mutates in place and restores before returning — no deep copy of light arrays on the write path.
    static byte[] pack(CompoundTag o) {
        ArrayList<Object[]> undo = new ArrayList<>();
        ByteArrayOutputStream blk = new ByteArrayOutputStream(1 << 15), bio = new ByteArrayOutputStream(1 << 10);
        if (o.get("sections") instanceof ListTag<?> secs)
            for (int i = 0; i < secs.size(); i++)
                if (secs.get(i) instanceof CompoundTag s) { pull(s, "block_states", blk, undo); pull(s, "biomes", bio, undo); }
        ByteArrayOutputStream out = new ByteArrayOutputStream(blk.size() + bio.size() + 4096);
        wVar(out, blk.size()); out.writeBytes(blk.toByteArray());
        wVar(out, bio.size()); out.writeBytes(bio.toByteArray());
        try { out.writeBytes(ser(o)); }
        finally { for (Object[] u : undo) ((CompoundTag) u[0]).put((String) u[1], (Tag<?>) u[2]); }
        return out.toByteArray();
    }
    static void push(CompoundTag s, String key, DataInputStream in) throws IOException {
        if (!(s.get(key) instanceof ByteTag)) return;
        int kind = in.readUnsignedByte();
        int[] ids = new int[(int) rVar(in)];
        for (int k = 0; k < ids.length; k++) ids[k] = (int) rVar(in);
        long[] data = null;
        if (kind != 1) {
            data = new long[(int) rVar(in)];
            byte[] raw = new byte[data.length * 8]; in.readFully(raw);
            java.nio.ByteBuffer.wrap(raw).asLongBuffer().get(data);
        }
        CompoundTag c = new CompoundTag();
        if (kind == 3) { c.put("data", new LongArrayTag(data)); c.put("palette", new IntArrayTag(ids)); }
        else { c.put("palette", new IntArrayTag(ids)); if (data != null) c.put("data", new LongArrayTag(data)); }
        s.put(key, c);
    }
    static CompoundTag unpack(byte[] b) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            byte[] blk = new byte[(int) rVar(in)]; in.readFully(blk);
            byte[] bio = new byte[(int) rVar(in)]; in.readFully(bio);
            CompoundTag o = (CompoundTag) parse(in.readAllBytes());
            DataInputStream bi = new DataInputStream(new ByteArrayInputStream(blk)), ei = new DataInputStream(new ByteArrayInputStream(bio));
            if (o.get("sections") instanceof ListTag<?> secs)
                for (int i = 0; i < secs.size(); i++)
                    if (secs.get(i) instanceof CompoundTag s) { push(s, "block_states", bi); push(s, "biomes", ei); }
            return o;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static CompoundTag stripLight(CompoundTag chunk) {
        CompoundTag o = chunk.clone();
        for (String k : CHUNK_LIGHT_KEYS) o.remove(k);
        if (o.get("sections") instanceof ListTag<?> secs)
            for (int i = 0; i < secs.size(); i++)
                if (secs.get(i) instanceof CompoundTag s) for (String k : SECTION_LIGHT_KEYS) s.remove(k);
        return o;
    }

    // ---------------------------------------------------------------- vanilla region I/O
    static final class RawChunk {
        int index, sectors, timestamp; byte[] nbt; int compression;
    }
    static final class Fail {
        final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        long vanillaBytesExcluded;
        void add(String why) { counts.merge(why, 1, Integer::sum); }
        int total() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    static List<RawChunk> readRegion(File f, Fail fail) throws IOException {
        ArrayList<RawChunk> out = new ArrayList<>();
        byte[] all = Files.readAllBytes(f.toPath());
        if (all.length < 8192) { if (all.length > 0) { fail.add("region shorter than header"); fail.vanillaBytesExcluded += all.length; } return out; }
        for (int i = 0; i < 1024; i++) {
            int off = ((all[i * 4] & 0xFF) << 16) | ((all[i * 4 + 1] & 0xFF) << 8) | (all[i * 4 + 2] & 0xFF);
            int cnt = all[i * 4 + 3] & 0xFF;
            if (off == 0 || cnt == 0) continue;
            long pos = (long) off * 4096;
            if (pos + 5 > all.length) { fail.add("chunk offset past EOF"); fail.vanillaBytesExcluded += cnt * 4096L; continue; }
            int len = ((all[(int) pos] & 0xFF) << 24) | ((all[(int) pos + 1] & 0xFF) << 16) | ((all[(int) pos + 2] & 0xFF) << 8) | (all[(int) pos + 3] & 0xFF);
            int ctype = all[(int) pos + 4] & 0xFF;
            byte[] comp;
            if ((ctype & 0x80) != 0) {
                int cx = i & 31, cz = i >> 5;
                String[] p = f.getName().split("\\.");
                int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
                File mcc = new File(f.getParentFile(), "c." + (rx * 32 + cx) + "." + (rz * 32 + cz) + ".mcc");
                if (!mcc.isFile()) { fail.add("missing .mcc"); fail.vanillaBytesExcluded += cnt * 4096L; continue; }
                comp = Files.readAllBytes(mcc.toPath());
                ctype &= 0x7F;
            } else {
                if (len < 1 || pos + 4 + len > all.length) { fail.add("chunk length past EOF"); fail.vanillaBytesExcluded += cnt * 4096L; continue; }
                comp = Arrays.copyOfRange(all, (int) pos + 5, (int) pos + 4 + len);
            }
            byte[] nbt;
            try {
                if (ctype == 2) nbt = new InflaterInputStream(new ByteArrayInputStream(comp)).readAllBytes();
                else if (ctype == 1) nbt = new GZIPInputStream(new ByteArrayInputStream(comp)).readAllBytes();
                else if (ctype == 3) nbt = comp;
                else { fail.add("unsupported compression type " + ctype); fail.vanillaBytesExcluded += cnt * 4096L; continue; }
            } catch (IOException e) { fail.add("decompress error"); fail.vanillaBytesExcluded += cnt * 4096L; continue; }
            RawChunk rc = new RawChunk();
            rc.index = i; rc.sectors = cnt; rc.nbt = nbt; rc.compression = ctype;
            rc.timestamp = ((all[4096 + i * 4] & 0xFF) << 24) | ((all[4096 + i * 4 + 1] & 0xFF) << 16) | ((all[4096 + i * 4 + 2] & 0xFF) << 8) | (all[4096 + i * 4 + 3] & 0xFF);
            out.add(rc);
        }
        return out;
    }

    static void writeRegion(File f, byte[][] nbtByIndex, int[] timestamps) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = new byte[8192];
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            if (nbtByIndex[i] == null) continue;
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(z)) { d.write(nbtByIndex[i]); }
            byte[] c = z.toByteArray();
            int len = c.length + 1;
            int secs = (len + 4 + 4095) / 4096;
            if (secs > 255) throw new IOException("chunk too large for inline storage");
            DataOutputStream d = new DataOutputStream(body);
            d.writeInt(len); d.writeByte(2); d.write(c);
            int pad = secs * 4096 - (len + 4);
            body.write(new byte[pad]);
            header[i * 4] = (byte) (sector >> 16); header[i * 4 + 1] = (byte) (sector >> 8); header[i * 4 + 2] = (byte) sector; header[i * 4 + 3] = (byte) secs;
            int ts = timestamps[i];
            header[4096 + i * 4] = (byte) (ts >> 24); header[4096 + i * 4 + 1] = (byte) (ts >> 16); header[4096 + i * 4 + 2] = (byte) (ts >> 8); header[4096 + i * 4 + 3] = (byte) ts;
            sector += secs;
        }
        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(header); body.writeTo(fo); }
    }

    static byte[] deflate(byte[] in, int level) {
        Deflater d = new Deflater(level); d.setInput(in); d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(in.length / 4 + 64);
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
        d.end(); return bo.toByteArray();
    }
    static byte[] inflate(byte[] in, int hint) throws DataFormatException {
        Inflater f = new Inflater(); f.setInput(in);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(hint);
        byte[] buf = new byte[1 << 16];
        while (!f.finished()) { int n = f.inflate(buf); if (n == 0 && (f.needsInput() || f.needsDictionary())) break; bo.write(buf, 0, n); }
        f.end(); return bo.toByteArray();
    }

    static File regionDir(File world) {
        File r = new File(world, "region");
        return r.isDirectory() ? r : world;
    }
    static File[] regions(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (fs == null) return new File[0];
        Arrays.sort(fs);
        return fs;
    }

    // ---------------------------------------------------------------- bench
    static final int[] ZLEVELS = {1, 3, 6, 9, 12, 19};

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.out.println("usage: LodeTool bench <world> [--dict-from <otherWorld>] [--full-only] [--chunks minX minZ maxX maxZ] [--time-only]");
            System.out.println("       LodeTool export <world> <outWorld> [--drop-light]");
            System.out.println("       LodeTool diff   <worldA> <worldB>   (voxel/biome/BE/light/field comparison)");
            return;
        }
        if (a[0].equals("diff")) { diff(new File(a[1]), new File(a[2])); return; }
        if (a[0].equals("export")) { export(new File(a[1]), new File(a[2]), Arrays.asList(a).contains("--drop-light")); return; }
        File dictWorld = null;
        for (int i = 2; i < a.length; i++) if (a[i].equals("--dict-from")) dictWorld = new File(a[++i]);
        timeOnly = Arrays.asList(a).contains("--time-only");
        for (int i = 2; i < a.length; i++) if (a[i].equals("--chunks"))
            area = new int[]{Integer.parseInt(a[i + 1]), Integer.parseInt(a[i + 2]), Integer.parseInt(a[i + 3]), Integer.parseInt(a[i + 4])};
        bench(new File(a[1]), dictWorld, Arrays.asList(a).contains("--full-only"));
    }

    static boolean timeOnly;
    static int[] area; // optional chunk filter: minX minZ maxX maxZ (global chunk coords)
    static void bench(File world, File dictWorld, boolean fullOnly) throws Exception {
        File[] files = regions(regionDir(world));
        System.out.println("=============== LodeTool bench ===============");
        System.out.printf("world: %s  (%d regions)%n", world, files.length);
        Fail fail = new Fail();
        Tables TK = new Tables(), TD = new Tables();

        // pass 1: read, encode both variants, verify round-trip byte-exact, keep bytes
        ArrayList<byte[]> vanNbt = new ArrayList<>(), kept = new ArrayList<>(), dropped = new ArrayList<>();
        ArrayList<byte[]> keptNbt = new ArrayList<>(), droppedNbt = new ArrayList<>(); // non-columnar id form, for comparison
        ArrayList<Integer> regionOf = new ArrayList<>(), chunkIdx = new ArrayList<>();
        long skippedNotFull = 0;
        long vanFile = 0, vanPayload = 0, vanSectorsOk = 0;
        long libExact = 0, rtKept = 0, rtDropped = 0, nChunks = 0;
        long lightBytesRaw = 0;
        for (int ri = 0; ri < files.length; ri++) {
            File f = files[ri];
            long flen = f.length();
            List<RawChunk> cs = readRegion(f, fail);
            if (area != null) {
                String[] np = f.getName().split("\\.");
                int rx = Integer.parseInt(np[1]), rz = Integer.parseInt(np[2]);
                cs.removeIf(rc -> { int gx = rx * 32 + (rc.index & 31), gz = rz * 32 + (rc.index >> 5);
                    return gx < area[0] || gz < area[1] || gx > area[2] || gz > area[3]; });
                if (cs.isEmpty()) continue;
            }
            if (cs.isEmpty() && flen > 0) { fail.vanillaBytesExcluded += flen; continue; }
            vanFile += flen;
            for (RawChunk rc : cs) {
                CompoundTag c;
                try { c = (CompoundTag) parse(rc.nbt); }
                catch (RuntimeException e) { fail.add("NBT parse error"); fail.vanillaBytesExcluded += rc.sectors * 4096L; continue; }
                if (fullOnly && !"minecraft:full".equals(c.getString("Status"))) { skippedNotFull++; continue; }
                byte[] reser = ser(c);
                if (Arrays.equals(reser, rc.nbt)) libExact++;
                CompoundTag k = encode(c, TK, false), d = encode(c, TD, true);
                byte[] kb = pack(k), db = pack(d);
                boolean okK = Arrays.equals(ser(decode(unpack(kb), TK)), reser);
                boolean okD = Arrays.equals(ser(decode(unpack(db), TD)), ser(stripLight(c)));
                keptNbt.add(ser(k)); droppedNbt.add(ser(d));
                if (okK) rtKept++;
                if (okD) rtDropped++;
                if (!okK || !okD) { fail.add("ROUND-TRIP MISMATCH"); fail.vanillaBytesExcluded += rc.sectors * 4096L; continue; }
                lightBytesRaw += reser.length - ser(stripLight(c)).length;
                vanNbt.add(rc.nbt); kept.add(kb); dropped.add(db); regionOf.add(ri); chunkIdx.add(rc.index);
                vanSectorsOk += rc.sectors;
                vanPayload += rc.nbt.length;
                nChunks++;
            }
        }
        // Denominator = sectors actually allocated to verified chunks + an 8 KiB header per region.
        // Conservative: file length also includes free/fragmented sectors, which are real disk cost.
        long nRegionsOk = regionOf.stream().distinct().count();
        long vanCounted = vanSectorsOk * 4096L + 8192L * nRegionsOk;
        System.out.printf("%nchunks encoded + verified : %,d%n", nChunks);
        System.out.printf("  round-trip, light kept   : %,d / %,d byte-exact%n", rtKept, nChunks);
        System.out.printf("  round-trip, light dropped: %,d / %,d byte-exact (vs source minus light+heightmaps)%n", rtDropped, nChunks);
        System.out.printf("  source NBT == lib reserialize: %,d / %,d%n", libExact, nChunks);
        if (fullOnly) System.out.printf("  skipped (Status != full, --full-only): %,d%n", skippedNotFull);
        System.out.printf("failures: %d%s%n", fail.total(), fail.total() == 0 ? "" : "  " + fail.counts);
        System.out.printf("block states in table: %,d   biomes: %,d%n", TK.blocks.list.size(), TK.biomes.list.size());
        long beRaw = 0, beCount = 0;
        for (byte[] b : vanNbt) { CompoundTag c = (CompoundTag) parse(b);
            if (c.get("block_entities") instanceof ListTag<?> l && l.size() > 0) { beCount += l.size(); beRaw += ser(l).length; } }
        System.out.printf("block entities: %,d (%.1f/chunk), %,d B uncompressed NBT (%.1f%% of chunk NBT)%n",
            beCount, beCount / (double) Math.max(1, nChunks), beRaw, 100.0 * beRaw / Math.max(1, vanPayload));
        if (nChunks == 0) return;

        byte[] tabK = Zstd.compress(TK.serialize(), 19), tabD = Zstd.compress(TD.serialize(), 19);
        System.out.printf("state table (zstd19): %,d B - charged to every Lode row%n", tabK.length);

        // dictionaries: trained on a different world when given, else on this one (flagged)
        byte[] dictK = null, dictD = null, dictKN = null, dictDN = null;
        String dictNote;
        if (dictWorld != null) {
            List<byte[]>[] s = sampleLode(dictWorld, TK, TD);
            dictK = train(s[0]); dictD = train(s[1]); dictKN = train(s[2]); dictDN = train(s[3]);
            dictNote = "trained on a DIFFERENT world (" + dictWorld.getName() + ")";
        } else {
            dictK = train(kept); dictD = train(dropped); dictKN = train(keptNbt); dictDN = train(droppedNbt);
            dictNote = "trained on THIS world (optimistic - pass --dict-from)";
        }
        // Tables grew while sampling the dict world; the grown table is what ships, so re-measure it.
        tabK = Zstd.compress(TK.serialize(), 19); tabD = Zstd.compress(TD.serialize(), 19);
        System.out.printf("dictionary: %,d B, %s%n", dictK.length, dictNote);

        // container header per region for per-chunk formats: 1024 x (u32 offset, u32 length, u32 timestamp)
        int nRegions = (int) regionOf.stream().distinct().count();
        long hdr = 12288L * nRegions;

        if (timeOnly) { timing(vanNbt, TK, dictK); return; }
        System.out.println("\n--- SIZE (vanilla bytes counted only for chunks that verified) ---");
        System.out.printf("  %-52s %,15d  %6s%n", "vanilla .mca (allocated sectors + headers)", vanCounted, "1.00x");
        System.out.printf("  %-52s %,15d  (file lengths, incl. free sectors)%n", "  cf. vanilla .mca file lengths", vanFile);
        System.out.printf("  %-52s %,15d%n", "  cf. uncompressed chunk NBT", vanPayload);
        System.out.printf("  %-52s %,15d%n", "  cf. uncompressed light+heightmap NBT", lightBytesRaw);
        row("vanilla NBT, deflate6 per chunk, no padding", sumDeflate(vanNbt) + 8192L * nRegions, vanCounted);
        row("Linear-style: vanilla NBT, zstd1 per region", perGroup(vanNbt, regionOf, chunkIdx, 5, 1) + 8192L * nRegions, vanCounted);
        row("Linear-style: vanilla NBT, zstd19 per region", perGroup(vanNbt, regionOf, chunkIdx, 5, 19) + 8192L * nRegions, vanCounted);
        for (int pass = 0; pass < 2; pass++) {
            List<byte[]> L = pass == 0 ? kept : dropped;
            long tab = pass == 0 ? tabK.length : tabD.length;
            byte[] dict = pass == 0 ? dictK : dictD;
            String tag = pass == 0 ? "light kept" : "light dropped";
            List<byte[]> N = pass == 0 ? keptNbt : droppedNbt;
            System.out.printf("  -- Lode, %s --%n", tag);
            row("(NBT id-form, not columnar) deflate6, per chunk", sumDeflate(N) + hdr + tab, vanCounted);
            row("(NBT id-form, not columnar) zstd6 + dict, per chunk", sumZstd(N, 6, pass == 0 ? dictKN : dictDN) + hdr + tab + dict.length, vanCounted);
            row("deflate6, per chunk", sumDeflate(L) + hdr + tab, vanCounted);
            for (int lv : ZLEVELS) row("zstd" + lv + ", per chunk", sumZstd(L, lv, null) + hdr + tab, vanCounted);
            for (int lv : ZLEVELS) row("zstd" + lv + " + dict, per chunk", sumZstd(L, lv, dict) + hdr + tab + dict.length, vanCounted);
            // groups of 2x2 / 4x4 chunks: one frame each, read amplification 4 / 16 chunks
            for (int g = 1; g <= 2; g++) for (int lv : new int[]{3, 9, 19})
                row("zstd" + lv + ", per " + (1 << g) + "x" + (1 << g) + " group", perGroup(L, regionOf, chunkIdx, g, lv) + hdr + tab, vanCounted);
            for (int lv : new int[]{3, 9, 19})
                row("zstd" + lv + " + dict, per 2x2 group", perGroupDict(L, regionOf, chunkIdx, 1, lv, dict) + hdr + tab + dict.length, vanCounted);
            row("zstd19, per region (not live: read = whole region)", perGroup(L, regionOf, chunkIdx, 5, 19) + 8192L * nRegions + tab, vanCounted);
        }

        timing(vanNbt, TK, dictK);
    }

    static void row(String name, long bytes, long van) {
        System.out.printf("  %-52s %,15d  %5.2fx%n", name, bytes, van / (double) bytes);
    }
    static long sumDeflate(List<byte[]> L) { return L.parallelStream().mapToLong(b -> deflate(b, 6).length).sum(); }
    static long sumZstd(List<byte[]> L, int lv, byte[] dict) {
        if (dict == null) return L.parallelStream().mapToLong(b -> Zstd.compress(b, lv).length).sum();
        try (ZstdDictCompress dc = new ZstdDictCompress(dict, lv)) {
            return L.parallelStream().mapToLong(b -> Zstd.compress(b, dc).length).sum();
        }
    }
    // Concatenate chunks into groups of (1<<g) x (1<<g) chunks within a region (g=5: whole region),
    // each chunk length-prefixed, one zstd frame per group.
    static List<byte[]> groups(List<byte[]> L, List<Integer> regionOf, List<Integer> chunkIdx, int g) {
        LinkedHashMap<Long, ByteArrayOutputStream> m = new LinkedHashMap<>();
        for (int i = 0; i < L.size(); i++) {
            int ci = chunkIdx.get(i), x = (ci & 31) >> g, z = (ci >> 5) >> g;
            long key = ((long) regionOf.get(i) << 20) | (x << 10) | z;
            ByteArrayOutputStream bo = m.computeIfAbsent(key, k -> new ByteArrayOutputStream(1 << 16));
            byte[] b = L.get(i);
            bo.write(b.length >> 24); bo.write(b.length >> 16); bo.write(b.length >> 8); bo.write(b.length); bo.write(b, 0, b.length);
        }
        ArrayList<byte[]> out = new ArrayList<>();
        for (ByteArrayOutputStream bo : m.values()) out.add(bo.toByteArray());
        return out;
    }
    static long perGroup(List<byte[]> L, List<Integer> regionOf, List<Integer> chunkIdx, int g, int lv) {
        return sumZstd(groups(L, regionOf, chunkIdx, g), lv, null);
    }
    static long perGroupDict(List<byte[]> L, List<Integer> regionOf, List<Integer> chunkIdx, int g, int lv, byte[] dict) {
        return sumZstd(groups(L, regionOf, chunkIdx, g), lv, dict);
    }
    static byte[] train(List<byte[]> samples) {
        // ~100x the dictionary size is zstd's recommended sample budget; take an even spread.
        final int budget = 32 << 20;
        long total = 0; for (byte[] b : samples) total += b.length;
        int stride = (int) Math.max(1, total / budget);
        ZstdDictTrainer tr = new ZstdDictTrainer(budget, 112 * 1024);
        for (int i = 0; i < samples.size(); i += stride) if (!tr.addSample(samples.get(i))) break;
        return tr.trainSamples();
    }
    @SuppressWarnings("unchecked")
    static List<byte[]>[] sampleLode(File world, Tables TK, Tables TD) throws IOException {
        ArrayList<byte[]> k = new ArrayList<>(), d = new ArrayList<>(), kn = new ArrayList<>(), dn = new ArrayList<>();
        Fail ignored = new Fail();
        for (File f : regions(regionDir(world)))
            for (RawChunk rc : readRegion(f, ignored)) {
                CompoundTag c = (CompoundTag) parse(rc.nbt);
                CompoundTag ek = encode(c, TK, false), ed = encode(c, TD, true);
                k.add(pack(ek)); d.add(pack(ed)); kn.add(ser(ek)); dn.add(ser(ed));
            }
        return new List[]{k, d, kn, dn};
    }

    // ---------------------------------------------------------------- timing
    // Split into two parts, because they have different fates in a real integration:
    //   codec     : vanilla = serialize + zlib(default)   / inflate + parse
    //               Lode    = serialize + zstd            / zstd + parse       (on the id-form tag)
    //   transform : palette entries <-> global ids. A real engine already holds block states as
    //               registry ids at runtime, so an integration writes the id form directly and this
    //               cost disappears; it is reported so nobody has to take that on faith.
    static void timing(List<byte[]> vanNbt, Tables T, byte[] dict) throws Exception {
        int n = Math.min(2000, vanNbt.size());
        ArrayList<CompoundTag> tags = new ArrayList<>();
        for (int i = 0; i < n; i++) tags.add((CompoundTag) parse(vanNbt.get((int) ((long) i * vanNbt.size() / n))));
        ArrayList<CompoundTag> lk = new ArrayList<>(), ld = new ArrayList<>();
        for (CompoundTag c : tags) { lk.add(encode(c, T, false)); ld.add(encode(c, T, true)); }
        System.out.printf("%n--- TIME per chunk, single thread (mean over %d chunks, after warm-up) ---%n", n);
        for (int warm = 0; warm < 2; warm++) {
            boolean print = warm == 1;
            long t0 = System.nanoTime(); ArrayList<byte[]> vz = new ArrayList<>();
            for (CompoundTag c : tags) vz.add(deflate(ser(c), Deflater.DEFAULT_COMPRESSION));
            long vW = System.nanoTime() - t0;
            t0 = System.nanoTime();
            for (byte[] z : vz) parse(inflate(z, 1 << 16));
            long vR = System.nanoTime() - t0;
            if (print) System.out.printf("  %-40s write %8.1f us   read %8.1f us%n", "vanilla codec (zlib default)", vW / 1e3 / n, vR / 1e3 / n);
            for (int drop = 0; drop < 2; drop++) for (int lv : new int[]{1, 3, 6, 9, 12, 19}) for (int useDict = 0; useDict < 2; useDict++) {
                List<CompoundTag> src = drop == 1 ? ld : lk;
                ZstdDictCompress dc = useDict == 1 ? new ZstdDictCompress(dict, lv) : null;
                ZstdDictDecompress dd = useDict == 1 ? new ZstdDictDecompress(dict) : null;
                t0 = System.nanoTime(); ArrayList<byte[]> lz = new ArrayList<>();
                for (CompoundTag c : src) { byte[] b = pack(c); lz.add(dc != null ? Zstd.compress(b, dc) : Zstd.compress(b, lv)); }
                long lW = System.nanoTime() - t0;
                t0 = System.nanoTime();
                for (byte[] z : lz) {
                    int sz = (int) Zstd.decompressedSize(z);
                    unpack(dd != null ? Zstd.decompress(z, dd, sz) : Zstd.decompress(z, sz));
                }
                long lR = System.nanoTime() - t0;
                if (dc != null) { dc.close(); dd.close(); }
                if (print) System.out.printf("  %-40s write %8.1f us   read %8.1f us%n",
                    "Lode codec zstd" + lv + (useDict == 1 ? "+dict" : "") + (drop == 1 ? ", no light" : ", light"),
                    lW / 1e3 / n, lR / 1e3 / n);
            }
            ArrayList<CompoundTag> ca = new ArrayList<>(), cb = new ArrayList<>();
            for (CompoundTag c : tags) ca.add(c.clone());
            for (CompoundTag c : lk) cb.add(c.clone());
            t0 = System.nanoTime();
            for (CompoundTag c : ca) encodeInPlace(c, T, false);
            long tE = System.nanoTime() - t0;
            t0 = System.nanoTime();
            for (CompoundTag c : cb) decodeInPlace(c, T);
            long tD = System.nanoTime() - t0;
            if (print) System.out.printf("  %-40s write %8.1f us   read %8.1f us%n", "transform palette<->ids (in place)", tE / 1e3 / n, tD / 1e3 / n);
        }
        System.out.println("  (no light: add the game's relight cost on first load - measured separately)");
    }

    // ---------------------------------------------------------------- export: .mca -> Lode -> .mca
    // Writes a vanilla-format world whose every chunk went through encode+decode. With --drop-light
    // the chunks are written without light/heightmaps and with isLightOn=0, which asks the game to
    // relight on load. Everything outside region/ is copied untouched.
    static void export(File world, File out, boolean dropLight) throws Exception {
        copyTree(world.toPath(), out.toPath());
        File[] files = regions(regionDir(world));
        File outDir = new File(out, "region");
        Tables T = new Tables();
        Fail fail = new Fail();
        long n = 0;
        for (File f : files) {
            byte[][] by = new byte[1024][]; int[] ts = new int[1024];
            for (RawChunk rc : readRegion(f, fail)) {
                CompoundTag c = (CompoundTag) parse(rc.nbt);
                byte[] stored = Zstd.compress(pack(encode(c, T, dropLight)), 3);
                CompoundTag back = decode(unpack(Zstd.decompress(stored, (int) Zstd.decompressedSize(stored))), T);
                if (dropLight) back.put("isLightOn", new ByteTag((byte) 0));
                by[rc.index] = ser(back); ts[rc.index] = rc.timestamp; n++;
            }
            writeRegion(new File(outDir, f.getName()), by, ts);
        }
        System.out.printf("exported %,d chunks (%s) to %s; failures %d %s%n", n, dropLight ? "light dropped" : "light kept", out, fail.total(), fail.counts);
    }
    static void copyTree(Path src, Path dst) throws IOException {
        try (var w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else if (!p.getFileName().toString().equals("session.lock")) Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ---------------------------------------------------------------- diff: semantic world comparison
    // Voxel-level, so palette reordering on re-save is not a difference. Light is compared per nibble,
    // with an absent array read as all-zero and reported separately.
    static String[] unpackPalette(CompoundTag holder, int n, boolean blocks) {
        if (holder == null) return null;
        ListTag<?> pal = holder.getListTag("palette");
        String[] keys = new String[pal.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = new String(ser((Tag<?>) pal.get(i)), java.nio.charset.StandardCharsets.ISO_8859_1);
        String[] out = new String[n];
        if (keys.length == 1 || !holder.containsKey("data")) { Arrays.fill(out, keys[0]); return out; }
        long[] data = ((LongArrayTag) holder.get("data")).getValue();
        int bits = Math.max(blocks ? 4 : 1, 32 - Integer.numberOfLeadingZeros(keys.length - 1));
        int per = 64 / bits; long mask = (1L << bits) - 1;
        for (int i = 0; i < n; i++) {
            int v = (int) ((data[i / per] >>> ((i % per) * bits)) & mask);
            out[i] = v < keys.length ? keys[v] : "<bad index " + v + ">";
        }
        return out;
    }
    static Map<Integer, CompoundTag> sectionsByY(CompoundTag c) {
        TreeMap<Integer, CompoundTag> m = new TreeMap<>();
        if (c.get("sections") instanceof ListTag<?> l)
            for (int i = 0; i < l.size(); i++) if (l.get(i) instanceof CompoundTag s) m.put((int) s.getByte("Y"), s);
        return m;
    }
    static Map<Long, CompoundTag> loadWorld(File world, Fail fail) throws IOException {
        HashMap<Long, CompoundTag> m = new HashMap<>();
        for (File f : regions(regionDir(world))) {
            String[] p = f.getName().split("\\.");
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            for (RawChunk rc : readRegion(f, fail)) {
                long cx = rx * 32L + (rc.index & 31), cz = rz * 32L + (rc.index >> 5);
                m.put((cx << 32) | (cz & 0xFFFFFFFFL), (CompoundTag) parse(rc.nbt));
            }
        }
        return m;
    }
    static void diff(File wa, File wb) throws Exception {
        Fail fa = new Fail(), fb = new Fail();
        Map<Long, CompoundTag> A = loadWorld(wa, fa), B = loadWorld(wb, fb);
        long both = 0, onlyA = 0, onlyB = 0, statusFullBoth = 0;
        long voxDiff = 0, voxTotal = 0, bioDiff = 0, chunksVox = 0, chunksBio = 0, chunksBE = 0;
        long skyNib = 0, skyDiff = 0, blkDiff = 0, chunksSky = 0, chunksBlk = 0, absentLightA = 0, absentLightB = 0;
        long hmChunks = 0;
        TreeMap<String, Long> fieldDiff = new TreeMap<>();
        TreeMap<Integer, Long> skyDiffByDelta = new TreeMap<>();
        for (var e : A.entrySet()) {
            CompoundTag a = e.getValue(), b = B.get(e.getKey());
            if (b == null) { onlyA++; continue; }
            both++;
            if ("minecraft:full".equals(a.getString("Status")) && "minecraft:full".equals(b.getString("Status"))) statusFullBoth++;
            // top-level fields other than sections / block_entities / Heightmaps
            TreeSet<String> keys = new TreeSet<>(a.keySet()); keys.addAll(b.keySet());
            for (String k : keys) {
                if (k.equals("sections") || k.equals("block_entities")) continue;
                Tag<?> x = a.get(k), y = b.get(k);
                if (x == null || y == null || !Arrays.equals(ser(x), ser(y))) fieldDiff.merge(k, 1L, Long::sum);
            }
            if (!beSet(a).equals(beSet(b))) chunksBE++;
            Map<Integer, CompoundTag> sa = sectionsByY(a), sb = sectionsByY(b);
            TreeSet<Integer> ys = new TreeSet<>(sa.keySet()); ys.addAll(sb.keySet());
            boolean cv = false, cb = false, cs = false, cl = false;
            for (int y : ys) {
                CompoundTag x = sa.get(y), z = sb.get(y);
                String[] va = x == null ? null : unpackPalette(x.getCompoundTag("block_states"), 4096, true);
                String[] vb = z == null ? null : unpackPalette(z.getCompoundTag("block_states"), 4096, true);
                if (va != null || vb != null) {
                    voxTotal += 4096;
                    for (int i = 0; i < 4096; i++) if (va == null || vb == null || !va[i].equals(vb[i])) { voxDiff++; cv = true; }
                }
                String[] ba = x == null ? null : unpackPalette(x.getCompoundTag("biomes"), 64, false);
                String[] bb = z == null ? null : unpackPalette(z.getCompoundTag("biomes"), 64, false);
                if (ba != null || bb != null)
                    for (int i = 0; i < 64; i++) if (ba == null || bb == null || !ba[i].equals(bb[i])) { bioDiff++; cb = true; }
                for (int L = 0; L < 2; L++) {
                    String key = L == 0 ? "SkyLight" : "BlockLight";
                    byte[] la = x != null && x.get(key) instanceof ByteArrayTag t ? t.getValue() : null;
                    byte[] lb = z != null && z.get(key) instanceof ByteArrayTag t ? t.getValue() : null;
                    if (la == null && lb == null) continue;
                    if (la == null) absentLightA++;
                    if (lb == null) absentLightB++;
                    for (int i = 0; i < 4096; i++) {
                        int na = la == null ? 0 : (la[i >> 1] >> ((i & 1) * 4)) & 15;
                        int nb = lb == null ? 0 : (lb[i >> 1] >> ((i & 1) * 4)) & 15;
                        if (L == 0) skyNib++;
                        if (na != nb) {
                            if (L == 0) { skyDiff++; cs = true; skyDiffByDelta.merge(nb - na, 1L, Long::sum); }
                            else { blkDiff++; cl = true; }
                        }
                    }
                }
            }
            if (cv) chunksVox++; if (cb) chunksBio++; if (cs) chunksSky++; if (cl) chunksBlk++;
        }
        for (Long k : B.keySet()) if (!A.containsKey(k)) onlyB++;
        System.out.printf("A: %s (%,d chunks, %d read failures)%nB: %s (%,d chunks, %d read failures)%n", wa, A.size(), fa.total(), wb, B.size(), fb.total());
        System.out.printf("chunks in both: %,d (both Status=full: %,d)   only A: %,d   only B: %,d%n", both, statusFullBoth, onlyA, onlyB);
        System.out.printf("block states : %,d / %,d voxels differ, in %,d chunks%n", voxDiff, voxTotal, chunksVox);
        System.out.printf("biomes       : %,d cells differ, in %,d chunks%n", bioDiff, chunksBio);
        System.out.printf("block ents   : %,d chunks differ%n", chunksBE);
        System.out.printf("sky light    : %,d / %,d nibbles differ (%.4f%%), in %,d chunks; delta(B-A) histogram %s%n", skyDiff, skyNib, 100.0 * skyDiff / Math.max(1, skyNib), chunksSky, skyDiffByDelta);
        System.out.printf("block light  : %,d nibbles differ, in %,d chunks%n", blkDiff, chunksBlk);
        System.out.printf("light arrays absent: in A %,d, in B %,d (compared as all-zero)%n", absentLightA, absentLightB);
        System.out.printf("other top-level fields differing (chunks): %s%n", fieldDiff);
    }
    static Set<String> beSet(CompoundTag c) {
        HashSet<String> s = new HashSet<>();
        if (c.get("block_entities") instanceof ListTag<?> l)
            for (int i = 0; i < l.size(); i++) s.add(new String(ser((Tag<?>) l.get(i)), java.nio.charset.StandardCharsets.ISO_8859_1));
        return s;
    }
}
