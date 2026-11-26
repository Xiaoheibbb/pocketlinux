#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
[ -f /root/xevwin.pid ] && kill $(cat /root/xevwin.pid) 2>/dev/null
sleep 0.5
: > /root/xevwin.log
nohup xev -geometry 680x1472+0+0 -event mouse > /root/xevwin.log 2>&1 &
echo $! > /root/xevwin.pid
sleep 1
echo XEV_RESET_DONE
