import ctypes, time
X11 = ctypes.CDLL("libX11.so.6")
XTST = ctypes.CDLL("libXtst.so.6")
X11.XOpenDisplay.restype = ctypes.c_void_p
X11.XOpenDisplay.argtypes = [ctypes.c_char_p]
XTST.XTestFakeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
XTST.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
X11.XFlush.argtypes = [ctypes.c_void_p]
X11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
X11.XCloseDisplay.argtypes = [ctypes.c_void_p]
d = X11.XOpenDisplay(b":99")
assert d, "no display"
def f(): X11.XFlush(d)
time.sleep(0.3)
XTST.XTestFakeMotionEvent(d, 0, 300, 200, 0); f(); time.sleep(0.3)
XTST.XTestFakeMotionEvent(d, 0, 400, 300, 0); f(); time.sleep(0.2)
XTST.XTestFakeButtonEvent(d, 1, 1, 0); f(); time.sleep(0.2)
for (x, y) in [(420,330),(460,390),(520,470),(570,560),(600,650)]:
    XTST.XTestFakeMotionEvent(d, 0, x, y, 0); f(); time.sleep(0.12)
XTST.XTestFakeButtonEvent(d, 1, 0, 0); f(); time.sleep(0.2)
X11.XCloseDisplay(d)
print("XTEST_DONE")
