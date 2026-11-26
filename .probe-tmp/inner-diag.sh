#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
L=/root/diag.log
: > $L
echo "== id ==" >> $L; id >> $L 2>&1
echo "== bins ==" >> $L; ls -l /usr/bin/xev /usr/bin/script /usr/bin/stdbuf /usr/bin/xdpyinfo /usr/bin/xterm /usr/bin/import 2>&1 >> $L
echo "== xdpyinfo ==" >> $L; timeout 8 xdpyinfo >> $L 2>&1; echo "rc=$?" >> $L
echo "== xev direct stdbuf 2s ==" >> $L
timeout 2 stdbuf -oL xev -root -event mouse > /root/ev1.log 2>&1; echo "rc=$?" >> $L; wc -c /root/ev1.log >> $L
echo "== xev via script pty 2s ==" >> $L
timeout 2 script -qefc "xev -root -event mouse" /dev/null > /root/ev2.log 2>&1; echo "rc=$?" >> $L; wc -c /root/ev2.log >> $L
echo "== tail ev1 ==" >> $L; tail -5 /root/ev1.log >> $L; echo "== tail ev2 ==" >> $L; tail -5 /root/ev2.log >> $L
echo "DONE" >> $L
