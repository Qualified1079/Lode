#!/usr/bin/env bash
# Start Paper on world $1, dump live light for chunks -64..64 via LightDump, stop.
set -u
cd /tmp/claude-0/w
W=$1
sed -i "s/^level-name=.*/level-name=$W/" server.properties
rm -f in "$W/session.lock"; mkfifo in
LOG=light-$W.log
( tail -f in | java -Xmx12G -jar paper.jar --nogui > "$LOG" 2>&1 ) &
until grep -q "Done (" "$LOG"; do sleep 2; done
printf "lightdump $W ${2:--64} ${3:-64} /tmp/claude-0/w/light-$W.bin.gz 600\n" > in
until grep -qE "LIGHTDUMP (DONE|FAILED)" "$LOG"; do sleep 3; done
grep -E "LIGHTDUMP" "$LOG"
printf 'stop\n' > in
until grep -q "All RegionFile I/O tasks to complete" "$LOG"; do sleep 2; done
sleep 20
pkill -f "tail -f in"
while ps -eo cmd | grep -q "[p]aper.jar --nogui"; do sleep 2; done
echo "ERROR lines: $(grep -c 'ERROR' "$LOG")"
grep -E 'ERROR' "$LOG" | sed 's/^\[[0-9:]*\] //; s/chunk \[.*\]/chunk [..]/' | sort | uniq -c | sort -rn | head -5
