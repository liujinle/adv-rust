package com.adv.bb.app.guest

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources

/**
 * Context handed to the guest Activity.
 *
 * The framework's Activity is built against the *host* ContextImpl, so a guest
 * that asks for `assets`, `resources`, `packageName`, `classLoader` or
 * `applicationInfo` would get the host's answers. Flutter is the clearest case:
 * it resolves `libflutter.so`, `flutter_assets` and the font manifest through
 * exactly those calls.
 *
 * Everything else (system services, files dir, permissions) still goes to the
 * host base context — that part is what the PackageManager/Environment proxy
 * stage will eventually take over.
 */
internal class GuestContext(
    private val base: Context,
    private val guest: GuestRuntime.Guest,
) : ContextWrapper(base) {

    private val packageManager by lazy { GuestPackageManager(base.packageManager, guest) }

    override fun getPackageManager(): PackageManager = packageManager

    override fun getAssets(): AssetManager = guest.resources.assets

    override fun getResources(): Resources = guest.resources

    override fun getPackageName(): String = guest.packageName

    override fun getClassLoader(): ClassLoader = guest.classLoader

    override fun getApplicationInfo(): ApplicationInfo = guest.applicationInfo

    /**
     * Must be the guest `Application`, not this wrapper: plugins (Flutter's
     * generated registrant among them) cast it and crash otherwise.
     */
    override fun getApplicationContext(): Context = guest.application ?: this
}
