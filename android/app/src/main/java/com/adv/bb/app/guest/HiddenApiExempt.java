package com.adv.bb.app.guest;

import java.lang.reflect.Method;

/**
 * hidden API 豁免执行体。
 *
 * 这个类会被 [HiddenApiUnsealer] 用 `DexFile.loadClass(name, null)` 以
 * **boot class loader（null）** 再定义一份副本：那份副本落在 ART 的 core-platform 域，
 * 因此可以调用 `VMRuntime.setHiddenApiExemptions`（Android 15/16 上它被标成
 * `BLOCKED,CORE-PLATFORM-API`，从 app 域直接反射会被拒）。
 *
 * 约束：只能依赖 `java.lang` / `java.lang.reflect`。以 boot class loader 定义时，
 * 引用工程内任何其它类都会解析失败。
 */
public final class HiddenApiExempt {

    private HiddenApiExempt() {
    }

    /**
     * 设置 hidden API 豁免前缀。
     *
     * @param prefix 签名前缀，`"L"` 表示豁免全部隐藏 API
     * @return 调用是否成功
     */
    public static boolean exempt(String prefix) {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Object vmRuntime = vmRuntimeClass.getMethod("getRuntime").invoke(null);
            Method setter = vmRuntimeClass.getMethod("setHiddenApiExemptions", String[].class);
            setter.invoke(vmRuntime, (Object) new String[]{prefix});
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
