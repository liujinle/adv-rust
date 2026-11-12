package com.adv.bb.app.guest

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.ComponentInfo
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.ComponentInfoFlags
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.UserHandle
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * PackageManager seen by the guest.
 *
 * The host PMS has never heard of the guest package, so `getApplicationInfo` for
 * it throws NameNotFoundException — Flutter's ApplicationInfoLoader hits that on
 * the first line of engine start-up. The guest's own package is answered from the
 * metadata parsed at import time; everything else is forwarded to the host.
 *
 * `PackageManager` is an abstract class with ~100 abstract members and no usable
 * in-framework base implementation (android.test.mock was removed), so the
 * forwarding boilerplate has to live here.
 */
internal class GuestPackageManager(
    private val base: PackageManager,
    private val guest: GuestRuntime.Guest,
) : PackageManager() {

    /**
     * Android 17 上 `Activity.shouldShowRequestPermissionRationale()` 会转调
     * `PackageManager.shouldShowRequestPermissionRationale(String)`。
     *
     * 这个方法是 framework 里的 **@hide abstract** 方法：`android-36` / `android-37`
     * 的 `android.jar` 都没有它的 stub，所以我们继承 `PackageManager` 时编译期完全
     * 看不见它；等到运行时虚分派落到我们这个子类，就抛
     * `AbstractMethodError: abstract method ... on receiver`。
     *
     * 哔哩哔哩 9.16.1 必踩：`BiliContext.onActivityPreCreated` →
     * `SystemPushDialogHelperKt` 一进播放页就调 `Activity.shouldShowRequestPermissionRationale`，
     * 于是「一播放就闪退」。
     *
     * 因为编译期基类没有这个方法，这里只能写成**同签名的普通方法**（不能加 `override`）：
     * 生成的字节码里会有一个 public 实例方法，运行时虚调用正好落上来。返回 false
     * = 不认为"该弹权限说明"，与系统对未申请过权限的应用的默认行为一致。
     */
    @Suppress("unused")
    fun shouldShowRequestPermissionRationale(permission: String): Boolean = false

    override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo =
        if (packageName == guest.packageName) guest.applicationInfo
        else base.getApplicationInfo(packageName, flags)

    override fun getPackageInfo(packageName: String, flags: Int): PackageInfo =
        if (packageName == guest.packageName) guest.packageInfo
        else base.getPackageInfo(packageName, flags)

    override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo =
        if (versionedPackage.packageName == guest.packageName) guest.packageInfo
        else base.getPackageInfo(versionedPackage, flags)

    /**
     * Android 13+ 的新签名（`PackageInfoFlags`）。
     *
     * `PackageManager` 里这些重载的默认实现是 `throw UnsupportedOperationException
     * ("getPackageInfo not implemented in subclass")`，不覆写就是一次崩溃 ——
     * androidx.profileinstaller 的 `ProfileInstallerInitializer1` 线程实测撞到的
     * 就是这条（被 crash probe 吞掉，但说明宿主 PackageManager 的覆盖不完整）。
     */
    override fun getPackageInfo(packageName: String, flags: PackageInfoFlags): PackageInfo =
        if (packageName == guest.packageName) guest.packageInfo
        else base.getPackageInfo(packageName, flags)

    override fun getPackageInfo(
        versionedPackage: VersionedPackage,
        flags: PackageInfoFlags,
    ): PackageInfo =
        if (versionedPackage.packageName == guest.packageName) guest.packageInfo
        else base.getPackageInfo(versionedPackage, flags)

    override fun getApplicationInfo(packageName: String, flags: ApplicationInfoFlags): ApplicationInfo =
        if (packageName == guest.packageName) guest.applicationInfo
        else base.getApplicationInfo(packageName, flags)

    override fun getLaunchIntentForPackage(packageName: String): Intent? =
        if (packageName == guest.packageName) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(guest.packageName, guest.launcherActivity)
            }
        } else {
            base.getLaunchIntentForPackage(packageName)
        }

    override fun getResourcesForApplication(appInfo: ApplicationInfo): Resources =
        if (appInfo.packageName == guest.packageName) guest.resources
        else base.getResourcesForApplication(appInfo)

    override fun getResourcesForApplication(packageName: String): Resources =
        if (packageName == guest.packageName) guest.resources
        else base.getResourcesForApplication(packageName)

    override fun getApplicationLabel(info: ApplicationInfo): CharSequence =
        if (info.packageName == guest.packageName) guest.packageName
        else base.getApplicationLabel(info)

    // ---- pure forwarding: the guest is not installed in the host PMS ----

    override fun addPackageToPreferred(packageName: String) = base.addPackageToPreferred(packageName)

    override fun addPermission(info: PermissionInfo): Boolean = base.addPermission(info)

    override fun addPermissionAsync(info: PermissionInfo): Boolean = base.addPermissionAsync(info)

    override fun addPreferredActivity(
        filter: IntentFilter,
        match: Int,
        set: Array<out ComponentName>?,
        activity: ComponentName,
    ) = base.addPreferredActivity(filter, match, set, activity)

    override fun canRequestPackageInstalls(): Boolean = base.canRequestPackageInstalls()

    override fun canonicalToCurrentPackageNames(names: Array<out String>): Array<out String> =
        base.canonicalToCurrentPackageNames(names)

    override fun checkPermission(permName: String, pkgName: String): Int =
        base.checkPermission(permName, pkgName)

    override fun checkSignatures(uid1: Int, uid2: Int): Int = base.checkSignatures(uid1, uid2)

    override fun checkSignatures(pkg1: String, pkg2: String): Int = base.checkSignatures(pkg1, pkg2)

    override fun clearInstantAppCookie() = base.clearInstantAppCookie()

    override fun clearPackagePreferredActivities(packageName: String) =
        base.clearPackagePreferredActivities(packageName)

    override fun currentToCanonicalPackageNames(names: Array<out String>): Array<out String> =
        base.currentToCanonicalPackageNames(names)

    override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsToDelay: Long) =
        base.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay)

    override fun getActivityBanner(activityName: ComponentName): Drawable? =
        base.getActivityBanner(activityName)

    override fun getActivityBanner(intent: Intent): Drawable? = base.getActivityBanner(intent)

    override fun getActivityIcon(activityName: ComponentName): Drawable =
        base.getActivityIcon(activityName)

    override fun getActivityIcon(intent: Intent): Drawable = base.getActivityIcon(intent)

    override fun getActivityInfo(component: ComponentName, flags: Int): ActivityInfo =
        proxyActivityInfo(component)
            ?: guestActivityInfo(component)
            ?: base.getActivityInfo(component, flags)

    /**
     * Android 13+ 的 `ComponentInfoFlags` 签名。
     *
     * 和 `getPackageInfo(String, PackageInfoFlags)` 一样，默认实现是
     * `throw UnsupportedOperationException("getActivityInfo not implemented in subclass")`。
     */
    override fun getActivityInfo(component: ComponentName, flags: ComponentInfoFlags): ActivityInfo =
        // Android 14 起 `ComponentInfoFlags` 的取值是 long。
        getActivityInfo(component, flags.value.toInt())

    /**
     * guest 清单里声明的 Activity。
     *
     * 播放器初始化（`com.ss.android.videoshop.fullscreen.FullScreenOperator`）会用
     * 当前 Activity 调 `PackageManager.getActivityInfo` 读 `screenOrientation` /
     * `configChanges`。宿主 PMS 不认识 `com.phoenix.read/...ShortSeriesActivity`，
     * 这一问就抛 `NameNotFoundException`，`VideoContext` 构造中断，播放页只剩
     * "网络错误，请稍后重试" —— 视频其实根本没开始取地址。
     */
    private fun guestActivityInfo(component: ComponentName): ActivityInfo? {
        if (component.packageName != guest.packageName) return null
        val activities = guest.packageInfo.activities.orEmpty()
        val hit = activities.firstOrNull { it.name == component.className }
            ?: activities.firstOrNull { fullName(it.name) == fullName(component.className) }
            ?: run {
                GuestRuntime.trace(
                    "PM.getActivityInfo(${component.flattenToShortString()}) 不在 guest 清单",
                )
                return null
            }
        return ActivityInfo(hit).apply {
            packageName = guest.packageName
            applicationInfo = guest.applicationInfo
            if (processName.isNullOrEmpty()) processName = guest.packageName
        }.also { info ->
            if (seenActivityLookups.add(component.className)) {
                GuestRuntime.trace(
                    "PM.getActivityInfo(${component.flattenToShortString()}) -> guest" +
                        " orientation=${info.screenOrientation}",
                )
            }
        }
    }

    /** 把 `.Xxx` / `Xxx` 这两种简写补成 guest 包名下的全名。 */
    private fun fullName(name: String?): String = when {
        name.isNullOrEmpty() -> ""
        name.startsWith(".") -> guest.packageName + name
        !name.contains('.') -> "${guest.packageName}.$name"
        else -> name
    }

    /** 只在首次查某个 Activity 时写 trace，别把日志刷爆。 */
    private val seenActivityLookups = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * guest 清单里声明的 Service。
     *
     * Service 组件翻译（IActivityManager 代理把 ComponentName 换成
     * [GuestServiceProxy]）之后，guest 自己仍会反查服务信息：哔哩哔哩会拿自己的
     * 服务名问 `processName` / `foregroundServiceType`。宿主 PMS 不认识这些组件，
     * 转发只会 `NameNotFoundException`。
     */
    private fun guestServiceInfo(component: ComponentName): ServiceInfo? {
        if (component.packageName != guest.packageName) return null
        val services = guest.packageInfo.services.orEmpty()
        val hit = services.firstOrNull { it.name == component.className }
            ?: services.firstOrNull { fullName(it.name) == fullName(component.className) }
            ?: run {
                GuestRuntime.trace(
                    "PM.getServiceInfo(${component.flattenToShortString()}) 不在 guest 清单",
                )
                return null
            }
        return ServiceInfo(hit).apply {
            packageName = guest.packageName
            applicationInfo = guest.applicationInfo
            if (processName.isNullOrEmpty()) processName = guest.packageName
        }.also { info ->
            if (seenServiceLookups.add(component.className)) {
                GuestRuntime.trace(
                    "PM.getServiceInfo(${component.flattenToShortString()}) -> guest" +
                        " process=${info.processName} fg=${info.foregroundServiceType}",
                )
            }
        }
    }

    private val seenServiceLookups = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 把宿主代理组件翻译成 guest 自己的 ActivityInfo。
     *
     * 虚拟应用会反查"我正在跑的 Activity 属于哪个进程"。宿主 PMS 对
     * `GuestActivityProxy` 的回答是 `com.adv.bb.app`，进程名一比对就不是自己，
     * 红果的 Timon 据此判定"本进程被别的应用占用"，直接 `killProcess()`。
     * 翻译成 guest 的包名/进程名之后，这个比对才是自洽的。
     */
    private fun proxyActivityInfo(component: ComponentName): ActivityInfo? {
        if (component.packageName == guest.packageName) return null
        if (!GuestSlots.isProxyClass(component.className)) return null
        val info = ActivityInfo()
        info.packageName = guest.packageName
        info.name = GuestRuntime.currentGuestActivityName ?: guest.launcherActivity
        info.processName = guest.packageName
        info.applicationInfo = guest.applicationInfo
        info.exported = true
        info.enabled = true
        GuestRuntime.trace(
            "PM.getActivityInfo(${component.flattenToShortString()})" +
                " -> ${info.packageName}/${info.name} process=${info.processName}",
        )
        return info
    }

    override fun getActivityLogo(activityName: ComponentName): Drawable? =
        base.getActivityLogo(activityName)

    override fun getActivityLogo(intent: Intent): Drawable? = base.getActivityLogo(intent)

    override fun getAllPermissionGroups(flags: Int): MutableList<PermissionGroupInfo> =
        base.getAllPermissionGroups(flags)

    override fun getApplicationBanner(info: ApplicationInfo): Drawable? =
        base.getApplicationBanner(info)

    override fun getApplicationBanner(packageName: String): Drawable? =
        base.getApplicationBanner(packageName)

    override fun getApplicationEnabledSetting(packageName: String): Int =
        base.getApplicationEnabledSetting(packageName)

    override fun getApplicationIcon(info: ApplicationInfo): Drawable = base.getApplicationIcon(info)

    override fun getApplicationIcon(packageName: String): Drawable = base.getApplicationIcon(packageName)

    override fun getApplicationLogo(info: ApplicationInfo): Drawable? = base.getApplicationLogo(info)

    override fun getApplicationLogo(packageName: String): Drawable? =
        base.getApplicationLogo(packageName)

    override fun getChangedPackages(sequenceNumber: Int): ChangedPackages? =
        base.getChangedPackages(sequenceNumber)

    /**
     * guest 组件在宿主 PMS 里根本不存在，转发只会抛
     * `IllegalArgumentException: Unknown component`。7.4.6.32 启动时
     * `com.bytedance.push.PushImpl.initOnApplication` 会连问 8 个推送 Service，
     * 每个都把堆栈打到 System.err，初始化还可能被中途打断。
     *
     * 正确做法：按 guest 清单里的 `android:enabled` 回答，运行期被
     * [setComponentEnabledSetting] 改过的用本地状态覆盖；宿主自己的组件才转发。
     */
    override fun getComponentEnabledSetting(componentName: ComponentName): Int {
        if (componentName.packageName != guest.packageName) {
            return base.getComponentEnabledSetting(componentName)
        }
        val overridden = guestComponentStates[fullName(componentName.className)]
        if (overridden != null) return overridden
        val declared = guestComponent(componentName)
        return if (declared?.enabled == false) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
    }

    /** guest 通过 `setComponentEnabledSetting` 改的状态只存在本进程里。 */
    private val guestComponentStates = ConcurrentHashMap<String, Int>()

    private fun guestComponent(component: ComponentName): ComponentInfo? {
        val target = fullName(component.className)
        val all = mutableListOf<ComponentInfo>()
        guest.packageInfo.activities?.let { all.addAll(it) }
        guest.packageInfo.services?.let { all.addAll(it) }
        guest.packageInfo.receivers?.let { all.addAll(it) }
        guest.packageInfo.providers?.let { all.addAll(it) }
        return all.firstOrNull { fullName(it.name) == target }
    }

    override fun getDefaultActivityIcon(): Drawable = base.defaultActivityIcon

    override fun getDrawable(packageName: String, resid: Int, appInfo: ApplicationInfo?): Drawable? =
        base.getDrawable(packageName, resid, appInfo)

    override fun getInstalledApplications(flags: Int): MutableList<ApplicationInfo> =
        base.getInstalledApplications(flags)

    override fun getInstalledPackages(flags: Int): MutableList<PackageInfo> =
        base.getInstalledPackages(flags)

    override fun getInstallerPackageName(packageName: String): String? =
        base.getInstallerPackageName(packageName)

    override fun getInstantAppCookie(): ByteArray = base.instantAppCookie

    override fun getInstantAppCookieMaxBytes(): Int = base.instantAppCookieMaxBytes

    override fun getInstrumentationInfo(className: ComponentName, flags: Int): InstrumentationInfo =
        base.getInstrumentationInfo(className, flags)

    override fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? =
        base.getLeanbackLaunchIntentForPackage(packageName)

    override fun getNameForUid(uid: Int): String? = base.getNameForUid(uid)

    override fun getPackageGids(packageName: String): IntArray =
        if (packageName == guest.packageName) intArrayOf(guest.applicationInfo.uid)
        else base.getPackageGids(packageName)

    override fun getPackageGids(packageName: String, flags: Int): IntArray =
        if (packageName == guest.packageName) intArrayOf(guest.applicationInfo.uid)
        else base.getPackageGids(packageName, flags)

    override fun getPackageInstaller(): PackageInstaller = base.packageInstaller

    override fun getPackageUid(packageName: String, flags: Int): Int =
        if (packageName == guest.packageName) guest.applicationInfo.uid
        else base.getPackageUid(packageName, flags)

    override fun getPackagesForUid(uid: Int): Array<out String>? = base.getPackagesForUid(uid)

    override fun getPackagesHoldingPermissions(
        permissions: Array<out String>,
        flags: Int,
    ): MutableList<PackageInfo> = base.getPackagesHoldingPermissions(permissions, flags)

    override fun getPermissionGroupInfo(name: String, flags: Int): PermissionGroupInfo =
        base.getPermissionGroupInfo(name, flags)

    override fun getPermissionInfo(name: String, flags: Int): PermissionInfo =
        base.getPermissionInfo(name, flags)

    override fun getPreferredActivities(
        outFilters: MutableList<IntentFilter>,
        outActivities: MutableList<ComponentName>,
        packageName: String?,
    ): Int = base.getPreferredActivities(outFilters, outActivities, packageName)

    override fun getPreferredPackages(flags: Int): MutableList<PackageInfo> =
        base.getPreferredPackages(flags)

    /**
     * guest 自己的 provider 要从导入时解析的清单里答。
     *
     * `androidx.startup.AppInitializer` 的第一步就是
     * `packageManager.getProviderInfo(InitializationProvider, GET_META_DATA)` 读
     * 初始化列表；转发给宿主 PMS 只会得到
     * `NameNotFoundException: ComponentInfo{com.phoenix.read/androidx.startup.InitializationProvider}`，
     * 整条 Startup 初始化链就此断掉 —— 红果的账号/实验等组件都挂在上面。
     */
    override fun getProviderInfo(component: ComponentName, flags: Int): ProviderInfo =
        if (component.packageName == guest.packageName) {
            guest.packageInfo.providers
                ?.firstOrNull { it.name == component.className }
                ?.let { info ->
                    GuestRuntime.trace(
                        "PM.getProviderInfo(${component.flattenToShortString()}) -> guest",
                    )
                    ProviderInfo(info)
                }
                ?: base.getProviderInfo(component, flags)
        } else {
            base.getProviderInfo(component, flags)
        }

    override fun getReceiverInfo(component: ComponentName, flags: Int): ActivityInfo =
        base.getReceiverInfo(component, flags)

    override fun getResourcesForActivity(activityName: ComponentName): Resources =
        base.getResourcesForActivity(activityName)

    override fun getServiceInfo(component: ComponentName, flags: Int): ServiceInfo =
        guestServiceInfo(component) ?: base.getServiceInfo(component, flags)

    override fun getSharedLibraries(flags: Int): MutableList<SharedLibraryInfo> =
        base.getSharedLibraries(flags)

    override fun getSystemAvailableFeatures(): Array<FeatureInfo> = base.systemAvailableFeatures

    override fun getSystemSharedLibraryNames(): Array<out String>? = base.systemSharedLibraryNames

    override fun getText(packageName: String, resid: Int, appInfo: ApplicationInfo?): CharSequence? =
        base.getText(packageName, resid, appInfo)

    override fun getUserBadgedDrawableForDensity(
        drawable: Drawable,
        user: UserHandle,
        badgeLocation: Rect?,
        badgeDensity: Int,
    ): Drawable = base.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity)

    override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable =
        base.getUserBadgedIcon(drawable, user)

    override fun getUserBadgedLabel(label: CharSequence, user: UserHandle): CharSequence =
        base.getUserBadgedLabel(label, user)

    override fun getXml(packageName: String, resid: Int, appInfo: ApplicationInfo?): XmlResourceParser? =
        base.getXml(packageName, resid, appInfo)

    override fun hasSystemFeature(featureName: String): Boolean = base.hasSystemFeature(featureName)

    override fun hasSystemFeature(featureName: String, version: Int): Boolean =
        base.hasSystemFeature(featureName, version)

    override fun isInstantApp(): Boolean = base.isInstantApp

    override fun isInstantApp(packageName: String): Boolean = base.isInstantApp(packageName)

    override fun isPermissionRevokedByPolicy(permName: String, pkgName: String): Boolean =
        base.isPermissionRevokedByPolicy(permName, pkgName)

    override fun isSafeMode(): Boolean = base.isSafeMode

    override fun queryBroadcastReceivers(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        base.queryBroadcastReceivers(intent, flags)

    override fun queryContentProviders(
        processName: String?,
        uid: Int,
        flags: Int,
    ): MutableList<ProviderInfo> = base.queryContentProviders(processName, uid, flags)

    override fun queryInstrumentation(targetPackage: String, flags: Int): MutableList<InstrumentationInfo> =
        base.queryInstrumentation(targetPackage, flags)

    override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        base.queryIntentActivities(intent, flags)

    override fun queryIntentActivityOptions(
        caller: ComponentName?,
        specifics: Array<out Intent>?,
        intent: Intent,
        flags: Int,
    ): MutableList<ResolveInfo> = base.queryIntentActivityOptions(caller, specifics, intent, flags)

    override fun queryIntentContentProviders(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        base.queryIntentContentProviders(intent, flags)

    override fun queryIntentServices(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        base.queryIntentServices(intent, flags)

    override fun queryPermissionsByGroup(group: String?, flags: Int): MutableList<PermissionInfo> =
        base.queryPermissionsByGroup(group, flags)

    override fun removePackageFromPreferred(packageName: String) =
        base.removePackageFromPreferred(packageName)

    override fun removePermission(name: String) = base.removePermission(name)

    override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? =
        base.resolveActivity(intent, flags)

    override fun resolveContentProvider(name: String, flags: Int): ProviderInfo? =
        base.resolveContentProvider(name, flags)

    /**
     * 显式指向 guest 服务时自己回答，不再问宿主 PMS（宿主清单里没有这些组件）。
     * 隐式 Intent 仍交给 base：IntentFilter 不在 [PackageInfo.services] 里，扫不出来。
     */
    override fun resolveService(intent: Intent, flags: Int): ResolveInfo? {
        val component = intent.component
        if (component != null) {
            val info = guestServiceInfo(component)
            if (info != null) return ResolveInfo().apply { serviceInfo = info }
        }
        return base.resolveService(intent, flags)
    }

    override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) =
        base.setApplicationCategoryHint(packageName, categoryHint)

    override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) =
        base.setApplicationEnabledSetting(packageName, newState, flags)

    override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
        if (componentName.packageName == guest.packageName) {
            // guest 没装进系统 PMS，不能让它去改一个宿主不认识的组件（会抛
            // IllegalArgumentException）；记在本地，getComponentEnabledSetting 读回。
            guestComponentStates[fullName(componentName.className)] = newState
            return
        }
        base.setComponentEnabledSetting(componentName, newState, flags)
    }

    override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) =
        base.setInstallerPackageName(targetPackage, installerPackageName)

    override fun updateInstantAppCookie(cookie: ByteArray?) = base.updateInstantAppCookie(cookie)

    override fun verifyPendingInstall(id: Int, verificationCode: Int) =
        base.verifyPendingInstall(id, verificationCode)
}
