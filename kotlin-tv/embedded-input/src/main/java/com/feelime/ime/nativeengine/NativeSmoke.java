package com.feelime.ime.nativeengine;

/** JNI ABI of the pinned Feelime native library. Names must match its exports. */
public final class NativeSmoke {
    static { System.loadLibrary("feelime_smoke"); }
    private NativeSmoke() { }
    public static native boolean rimeInitialize(String sharedDir, String userDir, String stagingDir);
    public static native long rimeCreateSession(String schema);
    public static native boolean rimeProcessKey(long session, int keyCode, int modifiers);
    public static native boolean rimeSelectCandidate(long session, int pageIndex);
    public static native String rimeContext(long session);
    public static native String rimeCommit(long session);
    public static native void rimeDestroySession(long session);
}
