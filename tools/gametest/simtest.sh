#!/usr/bin/env bash
# Build the survival simulation into world $1 over chunks [$2..$3]^2, save, stop.
set -u
cd /tmp/claude-0/w
W=$1
sed -i "s/^level-name=.*/level-name=$W/" server.properties
rm -f in "$W/session.lock"; mkfifo in
LOG=sim-$W.log
( tail -f in | java -Xmx8G -jar paper.jar --nogui > "$LOG" 2>&1 ) &
until grep -q "Done (" "$LOG"; do sleep 2; done
printf "survivalsim $W $2 $2 $3 $3 42\n" > in
until grep -qE "SURVIVALSIM DONE|Exception" "$LOG"; do sleep 3; done
grep -E "SURVIVALSIM" "$LOG"
sleep 5
printf 'save-all flush\n' > in
until grep -q "Saved the game" "$LOG"; do sleep 2; done
printf 'stop\n' > in
until grep -q "All RegionFile I/O tasks to complete" "$LOG"; do sleep 2; done
sleep 20
pkill -f "tail -f in"
while ps -eo cmd | grep -q "[p]aper.jar --nogui"; do sleep 2; done
echo "ERROR lines: $(grep -c 'ERROR' "$LOG")"
grep -E 'ERROR|Exception' "$LOG" | head -5
