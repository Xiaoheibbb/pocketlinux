#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
for f in /root/xevwin.pid /root/xterm.pid; do [ -f $f ] && kill $(cat $f) 2>/dev/null; done
pkill -f "xev -geometry" 2>/dev/null
pkill -f XTERMTEST 2>/dev/null
sleep 0.5
import -window root /root/desk_clean.png 2>/dev/null
echo CLEANED
