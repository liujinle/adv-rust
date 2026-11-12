package com.adv.bb.nativebridge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host-side JNI smoke test: verifies the hand-written function table offsets,
 * Java string marshalling and the error-code path against a real JVM.
 *
 * <p>Run through {@code tests/jni/run-smoke.ps1}; it needs the cdylib next to
 * {@code java.library.path}.
 */
public final class BridgeSmokeTest {

    public static void main(String[] args) throws Exception {
        System.loadLibrary("bb_native");

        check(NativeBridge.nativeAbiVersion() == 1, "abi version mismatch");

        long handle = NativeBridge.nativeCreate();
        check(handle != 0L, "engine create returned null");

        Path root = Files.createTempDirectory("bb-jni-smoke");
        String escapedRoot = root.toString().replace("\\", "\\\\");
        String config = "{\"root_path\":\"" + escapedRoot + "\",\"io_threads\":2}";
        int code = NativeBridge.nativeInitializeJson(handle, config);
        check(code == 0, "initialize failed code=" + code + " error=" + NativeBridge.nativeLastError(handle));

        String listResponse = NativeBridge.nativeRequestJson(handle, "{\"type\":\"list_instances\"}");
        check(listResponse != null, "list_instances returned null");
        check(listResponse.contains("\"instances\""), "unexpected response: " + listResponse);
        System.out.println("list_instances -> " + listResponse);

        long taskId = NativeBridge.nativeSubmitTaskJson(handle, "{\"command\":\"list_instances\"}");
        check(taskId > 0L, "submit returned " + taskId);

        String taskJson = null;
        for (int attempt = 0; attempt < 500; attempt++) {
            taskJson = NativeBridge.nativeTaskResultJson(handle, taskId);
            if (taskJson != null
                    && (taskJson.contains("\"succeeded\"") || taskJson.contains("\"failed\""))) {
                break;
            }
            Thread.sleep(10L);
        }
        check(taskJson != null && taskJson.contains("\"succeeded\""), "task result: " + taskJson);
        System.out.println("task -> " + taskJson);

        String metrics = NativeBridge.nativeRequestJson(handle, "{\"type\":\"metrics\"}");
        check(metrics != null && metrics.contains("snapshot_json"), "metrics: " + metrics);

        String summary = NativeBridge.nativeSummaryJson(handle);
        check(summary != null && summary.contains("abi_version"), "summary: " + summary);

        NativeBridge.nativeDestroy(handle);

        // Invalid JSON must surface as INVALID_ARGUMENT (-1) plus a readable message.
        long second = NativeBridge.nativeCreate();
        int badCode = NativeBridge.nativeInitializeJson(second, "{ not json");
        check(badCode == -1, "expected INVALID_ARGUMENT, got " + badCode);
        String lastError = NativeBridge.nativeLastError(second);
        check(lastError != null && !lastError.isEmpty(), "lastError was empty");
        System.out.println("expected failure message -> " + lastError);
        NativeBridge.nativeDestroy(second);

        // Destroyed handle must not be dereferenced.
        NativeBridge.nativeDestroy(handle);

        System.out.println("JNI smoke test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
