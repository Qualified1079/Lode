package survivalsim;
import org.bukkit.*; import org.bukkit.block.*; import org.bukkit.block.data.*; import org.bukkit.block.data.type.*;
import org.bukkit.command.*; import org.bukkit.enchantments.Enchantment; import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*; import org.bukkit.plugin.java.JavaPlugin; import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import java.util.*;
// /survivalsim <world> <minCx> <minCz> <maxCx> <maxCz> <seed>
// Builds a "lived-in" chunk in every chunk of the square: house, storage room of filled chests,
// furnaces, hopper line, signs, lights, redstone, crop farm with water, and a strip mine below.
// Deterministic for a given seed. Physics off, so nothing drops or flows during placement.
public class SurvivalSim extends JavaPlugin {
  List<Material> items;
  public void onEnable() {
    items = new ArrayList<>();
    for (Material m : Material.values())
      if (!m.isLegacy() && m.isItem() && !m.isAir() && m != Material.WRITTEN_BOOK && m != Material.BUNDLE) items.add(m);
  }
  static final Material[] BULK = {Material.COBBLESTONE, Material.DIRT, Material.COBBLED_DEEPSLATE, Material.ANDESITE,
    Material.OAK_LOG, Material.SPRUCE_LOG, Material.IRON_INGOT, Material.COAL, Material.REDSTONE, Material.WHEAT,
    Material.BONE, Material.ROTTEN_FLESH, Material.ARROW, Material.GUNPOWDER, Material.STRING, Material.SAND,
    Material.GRAVEL, Material.OAK_PLANKS, Material.TORCH, Material.BREAD, Material.RAW_COPPER, Material.GLASS};
  static final Material[] TOOLS = {Material.DIAMOND_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_SWORD,
    Material.IRON_AXE, Material.BOW, Material.DIAMOND_CHESTPLATE, Material.IRON_HELMET, Material.SHEARS,
    Material.FISHING_ROD, Material.NETHERITE_SHOVEL, Material.DIAMOND_BOOTS, Material.CROSSBOW};
  static final String[] WORDS = {"Home", "Storage", "Farm", "Mine", "Base", "Nether", "Portal", "Iron", "Sorted",
    "Misc", "Junk", "Food", "Tools", "Wood", "Stone", "Ores", "Keep out", "Welcome", "Alex", "Steve", "->", "<-"};

  public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
    World w = Bukkit.getWorld(a[0]);
    int x0 = Integer.parseInt(a[1]), z0 = Integer.parseInt(a[2]), x1 = Integer.parseInt(a[3]), z1 = Integer.parseInt(a[4]);
    long seed = Long.parseLong(a[5]);
    long t0 = System.nanoTime();
    new BukkitRunnable() {
      int cx = x0, cz = z0; long n = 0, chests = 0;
      public void run() {
        for (int k = 0; k < 4 && cx <= x1; k++) {
          Random r = new Random(seed * 1_000_003L + cx * 31_337L + cz);
          chests += build(w, cx, cz, r); n++;
          if (++cz > z1) { cz = z0; cx++; }
        }
        if (cx > x1) { cancel(); getLogger().info("SURVIVALSIM DONE " + n + " chunks, " + chests + " containers, " + (System.nanoTime() - t0) / 1_000_000 + " ms"); }
      }
    }.runTaskTimer(this, 1, 1);
    return true;
  }

  int build(World w, int cx, int cz, Random r) {
    int bx = cx * 16, bz = cz * 16;
    int y = w.getHighestBlockYAt(bx + 8, bz + 8);
    int containers = 0;
    // foundation + floor
    for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
      set(w, bx + x, y, bz + z, Material.STONE_BRICKS);
      for (int h = 1; h <= 6; h++) set(w, bx + x, y + h, bz + z, Material.AIR);
    }
    // walls with windows + door gap, roof of slabs
    for (int h = 1; h <= 4; h++) for (int i = 1; i < 15; i++) {
      Material m = (h == 2 || h == 3) && i % 3 == 0 ? Material.GLASS_PANE : Material.OAK_PLANKS;
      set(w, bx + i, y + h, bz + 1, m); set(w, bx + i, y + h, bz + 14, m);
      set(w, bx + 1, y + h, bz + i, m); set(w, bx + 14, y + h, bz + i, i == 7 && h <= 2 ? Material.AIR : m);
    }
    for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
      Block b = w.getBlockAt(bx + x, y + 5, bz + z);
      b.setType(Material.SPRUCE_SLAB, false);
      if ((x + z) % 2 == 0) { Slab sl = (Slab) b.getBlockData(); sl.setType(Slab.Type.TOP); b.setBlockData(sl, false); }
    }
    // storage room: two rows of chests (double chests), hopper line under the first row
    for (int i = 2; i < 12; i++) {
      for (int row = 0; row < 2; row++) {
        int z = 3 + row * 2;
        Block b = w.getBlockAt(bx + i, y + 1, bz + z);
        b.setType(Material.CHEST, false);
        org.bukkit.block.data.type.Chest cd = (org.bukkit.block.data.type.Chest) b.getBlockData();
        cd.setFacing(BlockFace.SOUTH);
        cd.setType(i % 2 == 0 ? org.bukkit.block.data.type.Chest.Type.LEFT : org.bukkit.block.data.type.Chest.Type.RIGHT);
        b.setBlockData(cd, false);
        fill(((org.bukkit.block.Chest) b.getState()).getSnapshotInventory(), r, b);
        containers++;
      }
      Block h = w.getBlockAt(bx + i, y, bz + 3);
      h.setType(Material.HOPPER, false);
    }
    // barrels
    for (int i = 2; i < 8; i++) {
      Block b = w.getBlockAt(bx + i, y + 1, bz + 8);
      b.setType(Material.BARREL, false);
      fill(((org.bukkit.block.Barrel) b.getState()).getSnapshotInventory(), r, b); containers++;
    }
    // furnaces with fuel / input / output
    for (int i = 2; i < 6; i++) {
      Block b = w.getBlockAt(bx + i, y + 1, bz + 12);
      b.setType(i % 2 == 0 ? Material.FURNACE : Material.BLAST_FURNACE, false);
      org.bukkit.block.Furnace f = (org.bukkit.block.Furnace) b.getState();
      FurnaceInventory fi = f.getSnapshotInventory();
      fi.setFuel(new ItemStack(Material.COAL, 1 + r.nextInt(64)));
      fi.setSmelting(new ItemStack(Material.RAW_IRON, 1 + r.nextInt(64)));
      fi.setResult(new ItemStack(Material.IRON_INGOT, 1 + r.nextInt(64)));
      f.setCookTime((short) r.nextInt(200)); f.setBurnTime((short) r.nextInt(1600));
      f.update(true, false); containers++;
    }
    // signs
    for (int i = 0; i < 3; i++) {
      Block b = w.getBlockAt(bx + 9 + i, y + 2, bz + 2);
      b.setType(Material.OAK_WALL_SIGN, false);
      WallSign ws = (WallSign) b.getBlockData(); ws.setFacing(BlockFace.SOUTH); b.setBlockData(ws, false);
      org.bukkit.block.Sign sg = (org.bukkit.block.Sign) b.getState();
      for (int line = 0; line < 4; line++) if (r.nextBoolean()) sg.getSide(org.bukkit.block.sign.Side.FRONT).line(line, Component.text(WORDS[r.nextInt(WORDS.length)]));
      sg.update(true, false);
    }
    // lights
    for (int i = 0; i < 6; i++) {
      Block b = w.getBlockAt(bx + 2 + i * 2, y + 4, bz + 7);
      b.setType(i % 2 == 0 ? Material.LANTERN : Material.GLOWSTONE, false);
    }
    // redstone: dust line with repeaters and a lamp
    for (int i = 9; i < 14; i++) {
      Block b = w.getBlockAt(bx + i, y + 1, bz + 10);
      if (i == 11) { b.setType(Material.REPEATER, false); Repeater rp = (Repeater) b.getBlockData(); rp.setDelay(1 + r.nextInt(4)); rp.setFacing(BlockFace.EAST); b.setBlockData(rp, false); }
      else b.setType(Material.REDSTONE_WIRE, false);
    }
    set(w, bx + 13, y + 2, bz + 10, Material.REDSTONE_LAMP);
    // farm outside is inside the fence line: 4x4 farmland with wheat of random age, water in the middle
    for (int x = 9; x < 13; x++) for (int z = 4; z < 8; z++) {
      if (x == 10 && z == 5) { set(w, bx + x, y, bz + z, Material.WATER); continue; }
      set(w, bx + x, y, bz + z, Material.FARMLAND);
      Block b = w.getBlockAt(bx + x, y + 1, bz + z);
      b.setType(r.nextBoolean() ? Material.WHEAT : Material.CARROTS, false);
      Ageable ag = (Ageable) b.getBlockData(); ag.setAge(r.nextInt(ag.getMaximumAge() + 1)); b.setBlockData(ag, false);
    }
    // strip mine: 2-high tunnels along x every 3 blocks at y=-54, torches every 8
    for (int z = 1; z < 16; z += 3) for (int x = 0; x < 16; x++) {
      set(w, bx + x, -54, bz + z, Material.AIR); set(w, bx + x, -53, bz + z, Material.AIR);
      if (x % 8 == 4) set(w, bx + x, -54, bz + z, Material.TORCH);
    }
    return containers;
  }

  void set(World w, int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }

  void fill(Inventory inv, Random r, Block b) {
    int size = inv.getSize();
    int kind = r.nextInt(10);
    if (kind < 6) {
      // sorted storage: 1-3 item types, full stacks, mostly full
      Material[] ms = new Material[1 + r.nextInt(3)];
      for (int i = 0; i < ms.length; i++) ms[i] = BULK[r.nextInt(BULK.length)];
      int n = size - r.nextInt(size / 2);
      for (int i = 0; i < n; i++) { Material m = ms[r.nextInt(ms.length)]; inv.setItem(i, new ItemStack(m, m.getMaxStackSize())); }
    } else {
      // junk chest: random items, random counts, some enchanted / damaged / named tools, a shulker box
      for (int i = 0; i < size; i++) {
        if (r.nextInt(3) == 0) continue;
        ItemStack it;
        if (r.nextInt(8) == 0) {
          it = new ItemStack(TOOLS[r.nextInt(TOOLS.length)]);
          ItemMeta meta = it.getItemMeta();
          if (meta instanceof Damageable d && it.getType().getMaxDurability() > 0) d.setDamage(r.nextInt(it.getType().getMaxDurability()));
          if (r.nextBoolean()) meta.displayName(Component.text(WORDS[r.nextInt(WORDS.length)] + " " + r.nextInt(100)));
          it.setItemMeta(meta);
          Enchantment[] es = {Enchantment.UNBREAKING, Enchantment.EFFICIENCY, Enchantment.MENDING, Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.FORTUNE};
          for (int e = 0; e < 1 + r.nextInt(3); e++) it.addUnsafeEnchantment(es[r.nextInt(es.length)], 1 + r.nextInt(4));
        } else if (r.nextInt(40) == 0) {
          it = new ItemStack(Material.SHULKER_BOX);
          BlockStateMeta bsm = (BlockStateMeta) it.getItemMeta();
          ShulkerBox sb = (ShulkerBox) bsm.getBlockState();
          for (int j = 0; j < 27; j++) if (r.nextBoolean()) { Material m = BULK[r.nextInt(BULK.length)]; sb.getInventory().setItem(j, new ItemStack(m, 1 + r.nextInt(m.getMaxStackSize()))); }
          bsm.setBlockState(sb); it.setItemMeta(bsm);
        } else {
          Material m = items.get(r.nextInt(items.size()));
          it = new ItemStack(m, 1 + r.nextInt(Math.max(1, m.getMaxStackSize())));
        }
        inv.setItem(i, it);
      }
    }
    BlockState st = b.getState();
    if (st instanceof Container ct) { ct.getInventory().setContents(inv.getContents()); }
  }
}
