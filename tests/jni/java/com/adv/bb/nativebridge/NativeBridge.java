package com.adv.bb.nativebridge;

/**
 * Mirror of the JNI symbols exported by {@code bb-bridge/src/jni.rs}.
 *
 * <p>This file exists twice on purpose: the Kotlin version in {@code android/bbcore}
 * is what the app ships, and this Java version is what the host smoke test loads
 * (plain {@code javac}/{@code java}, no Android SDK required). Both declare the
 * same class name and the same native methods, so a symbol rename breaks both.
 */
public final class NativeBridge {
    private NativeBridge() {
    }

    public static native int nativeAbiVersion();

    public static native long nativeCreate();

    public static native void nativeDestroy(long handle);

    public static native int nativeInitializeJson(long handle, String configJson);

    public static native String nativeRequestJson(long handle, String requestJson);

    public static native long nativeSubmitTaskJson(long handle, String commandJson);

    public static native String nativeTaskResultJson(long handle, long taskId);

    public static native String nativeSummaryJson(long handle);

    public static native String nativeLastError(long handle);
}
