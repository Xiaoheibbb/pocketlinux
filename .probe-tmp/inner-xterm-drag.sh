#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
L=/root/xtermdrag.log; : > $L
nohup xterm -geometry 80x24+30+700 -bg white -fg black -title XTERMTEST > /root/xterm2.log 2>&1 &
XPID=$!
sleep 2
import -window root /root/xd_before.png 2>>$L
xwininfo -root -tree 2>>$L | grep XTERMTEST >> $L
python3 /root/inner-xtest-drag.py >> $L 2>&1
sleep 0.7
import -window root /root/xd_after.png 2>>$L
xwininfo -root -tree 2>>$L | grep XTERMTEST >> $L
kill $XPID 2>/dev/null
echo DONE >> $L
