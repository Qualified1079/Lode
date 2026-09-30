package lightdump;
import org.bukkit.*; import org.bukkit.command.*; import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.*; import java.util.zip.GZIPOutputStream;
// /lightdump <world> <minChunk> <maxChunk> <outFile> <settleTicks>
// Phase 1 loads every chunk in the square and pins it with a plugin ticket, so cross-chunk light
// propagation can complete. Phase 2 waits settleTicks. Phase 3 dumps (sky<<4|block) per block, gzip.
public class LightDump extends JavaPlugin {
  @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
    World w = Bukkit.getWorld(a[0]); int mn = Integer.parseInt(a[1]), mx = Integer.parseInt(a[2]);
    File out = new File(a[3]); int settle = Integer.parseInt(a[4]);
    int minY = w.getMinHeight(), maxY = w.getMaxHeight();
    long t0 = System.nanoTime();
    new BukkitRunnable() {
      int x = mn - 1, z = mn - 1, phase = 0, wait = 0; long n = 0; DataOutputStream os;
      public void run() {
        try {
          if (phase == 0) {
            // pin one extra ring so edge chunks have lit neighbours too
            for (int k = 0; k < 64 && x <= mx + 1; k++) {
              w.getChunkAt(x, z).addPluginChunkTicket(LightDump.this);
              if (++z > mx + 1) { z = mn - 1; x++; }
            }
            if (x > mx + 1) { phase = 1; getLogger().info("LIGHTDUMP pinned in " + (System.nanoTime() - t0) / 1_000_000 + " ms"); }
          } else if (phase == 1) {
            if (++wait >= settle) { phase = 2; x = mn; z = mn;
              os = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(out), 1 << 16), 1 << 20)); }
          } else {
            for (int k = 0; k < 64 && x <= mx; k++) {
              ChunkSnapshot sn = w.getChunkAt(x, z).getChunkSnapshot(false, false, false);
              os.writeInt(x); os.writeInt(z);
              for (int y = minY; y < maxY; y++) for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++)
                os.writeByte((sn.getBlockSkyLight(xx, y, zz) << 4) | sn.getBlockEmittedLight(xx, y, zz));
              n++;
              if (++z > mx) { z = mn; x++; }
            }
            if (x > mx) { os.close(); cancel(); w.removePluginChunkTickets(LightDump.this);
              getLogger().info("LIGHTDUMP DONE " + n + " chunks, total " + (System.nanoTime() - t0) / 1_000_000 + " ms -> " + out); }
          }
        } catch (IOException e) { getLogger().severe("LIGHTDUMP FAILED " + e); cancel(); }
      }
    }.runTaskTimer(this, 1, 1);
    return true;
  }
}
