#!/usr/bin/env bash
# Load every chunk of world $1 in Paper (Chunky over the pregenerated square), save, stop.
set -u
cd /tmp/claude-0/w
W=$1
sed -i "s/^level-name=.*/level-name=$W/" server.properties
rm -f in "$W/session.lock"; mkfifo in
LOG=load-$W.log
( tail -f in | java -Xmx8G -jar paper.jar --nogui > "$LOG" 2>&1 ) &
until grep -q "Done (" "$LOG"; do sleep 2; done
start=$(date +%s)
printf "chunky world $W\nchunky center 0 0\nchunky radius 1024\nchunky shape square\nchunky start\n" > in
until grep -q "Task finished" "$LOG"; do sleep 3; done
echo "chunky pass: $(( $(date +%s) - start ))s"
printf 'save-all flush\nstop\n' > in
until grep -q "Closing Server\|All RegionFile I/O tasks to complete" "$LOG"; do sleep 2; done
sleep 20
pkill -f "tail -f in"
while ps -eo cmd | grep -q "[p]aper.jar --nogui"; do sleep 2; done
grep "Task finished" "$LOG"
echo "WARN/ERROR lines: $(grep -cE 'WARN|ERROR' "$LOG")"
grep -E 'WARN|ERROR' "$LOG" | sed 's/^\[[0-9:]*\] //' | sort | uniq -c | sort -rn | head -15
