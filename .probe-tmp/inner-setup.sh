#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
: > /root/xevwin.log
nohup xev -geometry 680x1472+0+0 -event mouse > /root/xevwin.log 2>&1 &
echo $! > /root/xevwin.pid
sleep 1
nohup xterm -geometry 80x24+30+700 -bg white -fg black -title XTERMTEST > /root/xterm.log 2>&1 &
echo $! > /root/xterm.pid
sleep 2
xwininfo -root -tree > /root/xwininfo.txt 2>&1
import -window root /root/desk0.png 2>/root/import.log
echo SETUP_DONE >> /root/setup.log
date >> /root/setup.log
tail -f /dev/null
