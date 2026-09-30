#!/usr/bin/env bash
# Builds tools/mcbench.jar — fetches deps, compiles, packages.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p deps build tools

NBT_MCA="io.github.ens-gijs.nbt:nbt-mca:0.2.1-SNAPSHOT"
ZSTD="com.github.luben:zstd-jni:1.5.6-9"

if [ ! -f deps/.fetched ]; then
  echo ">> fetching dependencies"
  cat > deps/build.gradle.kts <<EOF
plugins { java }
repositories {
  mavenCentral()
  maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
  maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
}
dependencies {
  implementation("$NBT_MCA")
  implementation("$ZSTD")
}
tasks.register<Copy>("fetch") { from(configurations.runtimeClasspath); into("libs") }
EOF
  printf 'rootProject.name = "lode-deps"\n' > deps/settings.gradle.kts
  (cd deps && gradle -q --console=plain fetch)
  touch deps/.fetched
fi

CP=$(find deps/libs -name '*.jar' | tr '\n' ':')
echo ">> compiling"
javac -cp "$CP" -d build tools/MCBench.java tools/LodeTool.java

echo ">> packaging"
rm -rf build/fat && mkdir -p build/fat
for j in deps/libs/*.jar; do (cd build/fat && unzip -o -q "../../$j"); done
rm -rf build/fat/META-INF/*.SF build/fat/META-INF/*.DSA build/fat/META-INF/*.RSA build/fat/META-INF/MANIFEST.MF
cp build/*.class build/fat/
printf 'Main-Class: MCBench\n' > build/manifest.txt
jar cfm tools/mcbench.jar build/manifest.txt -C build/fat .
echo ">> built tools/mcbench.jar  (java -jar = MCBench; java -cp tools/mcbench.jar LodeTool ... = codec/round-trip/export/diff)"
ls -la tools/mcbench.jar
