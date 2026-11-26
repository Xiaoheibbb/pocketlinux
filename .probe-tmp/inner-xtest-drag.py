import ctypes, time
X11 = ctypes.CDLL("libX11.so.6"); XTST = ctypes.CDLL("libXtst.so.6")
X11.XOpenDisplay.restype = ctypes.c_void_p
X11.XOpenDisplay.argtypes = [ctypes.c_char_p]
XTST.XTestFakeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
XTST.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
X11.XFlush.argtypes = [ctypes.c_void_p]; X11.XCloseDisplay.argtypes = [ctypes.c_void_p]
d = X11.XOpenDisplay(b":99"); assert d
def f(): X11.XFlush(d)
XTST.XTestFakeMotionEvent(d,0,150,715,0); f(); time.sleep(0.3)
XTST.XTestFakeButtonEvent(d,1,1,0); f(); time.sleep(0.2)
for i in range(1,11):
    XTST.XTestFakeMotionEvent(d,0,150+25*i,715,0); f(); time.sleep(0.06)
XTST.XTestFakeButtonEvent(d,1,0,0); f(); time.sleep(0.2)
X11.XCloseDisplay(d); print("DRAG_DONE")
