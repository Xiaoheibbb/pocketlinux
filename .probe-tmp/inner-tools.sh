#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
L=/root/tools.log
: > $L
for b in python3 xdotool xinput xte gcc cc perl xwininfo wmctrl xprop xev xterm import convert; do
  printf '%-10s ' "$b" >> $L; command -v $b >> $L 2>&1 || echo MISSING >> $L
done
echo "== libXtst ==" >> $L
ldconfig -p 2>/dev/null | grep -E "libXtst|libX11\.so" >> $L
echo DONE >> $L
