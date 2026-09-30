import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.mca.util.PalettizedCuboid;
import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.ensgijs.nbt.tag.StringTag;
import java.io.File;
import java.util.*;

// Writes VoxSim9's synthetic terrain out as a GENUINE .mca via a real MC NBT/MCA library and
// measures the file on disk. This calibrates the vanilla baseline -- the dominant uncertainty in
// every ratio quoted so far (my hand model bracketed 17-191 MB at side 1024 depending entirely on
// an ASSUMPTION about how light data compresses).
//
// Reuses VoxSim9's generator/encoders directly (same package, package-private statics).
// side=512 == exactly one region (32x32 chunks).

public class CalibrateMca {
    static final int HEIGHT=VoxSim9.HEIGHT, Y_MIN=VoxSim9.Y_MIN;
    static final byte AIR=VoxSim9.AIR, WATER=VoxSim9.WATER, GLASS=VoxSim9.GLASS, LEAVES=VoxSim9.LEAVES;

    static final String[] NAMES=new String[26];
    static {
        NAMES[VoxSim9.AIR]="minecraft:air";             NAMES[VoxSim9.BEDROCK]="minecraft:bedrock";
        NAMES[VoxSim9.DEEPSLATE]="minecraft:deepslate"; NAMES[VoxSim9.STONE]="minecraft:stone";
        NAMES[VoxSim9.DIRT]="minecraft:dirt";           NAMES[VoxSim9.GRASS]="minecraft:grass_block";
        NAMES[VoxSim9.WATER]="minecraft:water";         NAMES[VoxSim9.COAL]="minecraft:coal_ore";
        NAMES[VoxSim9.IRON]="minecraft:iron_ore";       NAMES[VoxSim9.COPPER]="minecraft:copper_ore";
        NAMES[VoxSim9.DIAMOND]="minecraft:diamond_ore"; NAMES[VoxSim9.GOLD]="minecraft:gold_ore";
        NAMES[VoxSim9.REDSTONE]="minecraft:redstone_ore";NAMES[VoxSim9.SAND]="minecraft:sand";
        NAMES[VoxSim9.GRAVEL]="minecraft:gravel";       NAMES[VoxSim9.ANDESITE]="minecraft:andesite";
        NAMES[VoxSim9.GRANITE]="minecraft:granite";     NAMES[VoxSim9.DIORITE]="minecraft:diorite";
        NAMES[VoxSim9.TUFF]="minecraft:tuff";           NAMES[VoxSim9.SNOW]="minecraft:snow_block";
        NAMES[VoxSim9.CLAY]="minecraft:clay";           NAMES[VoxSim9.LOG]="minecraft:oak_log";
        NAMES[VoxSim9.LEAVES]="minecraft:oak_leaves";   NAMES[VoxSim9.PLANKS]="minecraft:oak_planks";
        NAMES[VoxSim9.COBBLE]="minecraft:cobblestone";  NAMES[VoxSim9.GLASS]="minecraft:glass";
    }
    static CompoundTag[] TAGS=new CompoundTag[26];
    static {
        for(int i=0;i<26;i++){
            CompoundTag t=new CompoundTag();
            t.putString("Name", NAMES[i]!=null?NAMES[i]:"minecraft:stone");
            TAGS[i]=t;
        }
    }

    static boolean opaque(byte b){ return b!=AIR && b!=WATER && b!=GLASS && b!=LEAVES; }

    // Realistic skylight: 15 with direct sky access, decaying below the surface (approximating
    // horizontal bleed), 0 deep underground. BlockLight is all-zero in fresh worldgen (no player
    // torches) -- itself an important calibration fact, since all-zero compresses to ~nothing.
    static byte[] skyLightFor(byte[][] v,int side,int[] topCache,int cx,int cz,int secY){
        byte[] nib=new byte[2048];
        boolean any=false;
        for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
            int gx=cx*16+lx, gz=cz*16+lz, yi=secY*16+ly;
            if(gx>=side||gz>=side||yi>=HEIGHT) continue;
            int top=topCache[gx*side+gz];
            int level = (yi>top) ? 15 : Math.max(0, 15-(top-yi));
            if(level>0) any=true;
            int idx=(ly*16+lz)*16+lx;
            if((idx&1)==0) nib[idx>>1]|=(byte)(level&0xF);
            else           nib[idx>>1]|=(byte)((level&0xF)<<4);
        }
        return any?nib:null;
    }

    public static void main(String[] args) throws Exception {
        int side=512;
        System.out.println("=== MCA CALIBRATION (written by a real MC library, measured on disk) ===");
        long t0=System.currentTimeMillis();
        List<VoxSim9.Feat> log=new ArrayList<>();
        byte[][][] both=VoxSim9.generate(side,0.15,log);
        byte[][] v=both[1];
        System.out.printf("  generated %dx%d (%d features) in %ds%n",
            side,side,log.size(),(System.currentTimeMillis()-t0)/1000);

        int[] topCache=new int[side*side];
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            int top=-1;
            for(int y=HEIGHT-1;y>=0;y--) if(opaque(VoxSim9.get(v,side,x,z,y))){ top=y; break; }
            topCache[x*side+z]=top;
        }

        File dir=new File("/tmp/claude-0/-home-user-Rumor/2f60bdb3-2ddf-5bdd-98c8-cc57a7840615/scratchpad/mcaout");
        dir.mkdirs();
        for(File f:dir.listFiles()) f.delete();

        File fA=new File(dir,"r.0.0.mca");
        long t=System.currentTimeMillis();
        writeRegion(v,side,topCache,fA,true,true);
        System.out.printf("  [A] blocks + skyLight + blockLight  : %,13d bytes   (%ds)%n",
            fA.length(),(System.currentTimeMillis()-t)/1000);

        File fB=new File(dir,"r.0.1.mca");
        writeRegion(v,side,topCache,fB,true,false);
        System.out.printf("  [B] blocks + skyLight only          : %,13d bytes%n",fB.length());

        File fC=new File(dir,"r.0.2.mca");
        writeRegion(v,side,topCache,fC,false,false);
        System.out.printf("  [C] blocks only, no light           : %,13d bytes%n",fC.length());

        long harsh=VoxSim9.encVanilla(v,side);
        System.out.printf("  [--] my hand model (harsh)          : %,13d bytes%n",harsh);

        long colrle=VoxSim9.encColRLE(v,side);
        long featBytes=VoxSim9.encFeatureList(log,side);
        long baseRle=VoxSim9.encColRLE(both[0],side);
        long feat=baseRle+featBytes;

        System.out.printf("%n  Column-RLE+DEFLATE                  : %,13d bytes%n",colrle);
        System.out.printf("  Feature-extract (base+list)         : %,13d bytes%n",feat);

        System.out.printf("%n  === RATIOS vs REAL MCA ===%n");
        System.out.printf("    Column-RLE   vs [A] full        : %7.2fx%n",fA.length()/(double)colrle);
        System.out.printf("    Column-RLE   vs [B] sky only    : %7.2fx%n",fB.length()/(double)colrle);
        System.out.printf("    Column-RLE   vs [C] blocks only : %7.2fx%n",fC.length()/(double)colrle);
        System.out.printf("    Feature-extr vs [A] full        : %7.2fx%n",fA.length()/(double)feat);
        System.out.printf("    Feature-extr vs [B] sky only    : %7.2fx%n",fB.length()/(double)feat);
        System.out.printf("    Feature-extr vs [C] blocks only : %7.2fx%n",fC.length()/(double)feat);
        System.out.printf("%n    my harsh hand model overstated [A] by %.2fx%n",harsh/(double)fA.length());
        System.out.printf("    real mca per chunk: [A] %,d B  [B] %,d B  [C] %,d B%n",
            fA.length()/1024, fB.length()/1024, fC.length()/1024);
    }

    static void writeRegion(byte[][] v,int side,int[] topCache,File out,boolean sky,boolean blockLight)
            throws Exception {
        McaRegionFile mca=new McaRegionFile(0,0);
        int chunks=side/16;
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++){
            TerrainChunk c=TerrainChunk.newChunk();
            for(int secY=0;secY<HEIGHT/16;secY++){
                boolean any=false;
                outer:
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++)
                    if(VoxSim9.get(v,side,cx*16+lx,cz*16+lz,secY*16+ly)!=AIR){ any=true; break outer; }
                if(!any) continue;
                TerrainSection s=c.createSection(secY+(Y_MIN/16));
                PalettizedCuboid<CompoundTag> pc=new PalettizedCuboid<>(16,TAGS[AIR]);
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++){
                    byte b=VoxSim9.get(v,side,cx*16+lx,cz*16+lz,secY*16+ly);
                    if(b!=AIR) pc.set(lx,ly,lz,TAGS[b&0xFF]);
                }
                s.setBlockStates(pc);
                s.setBiomes(new PalettizedCuboid<>(4,new StringTag("minecraft:plains")));
                if(sky){ byte[] sl=skyLightFor(v,side,topCache,cx,cz,secY); if(sl!=null) s.setSkyLight(sl); }
                if(blockLight) s.setBlockLight(TerrainSectionBase.createBlockLightBuffer());
            }
            mca.setChunk(cx,cz,c);
        }
        McaFileHelpers.write(mca,out,true);
    }
}
