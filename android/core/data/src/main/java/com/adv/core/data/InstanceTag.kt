package com.adv.core.data

/**
 * 同一个 APK、同一个版本的多个实例，靠名称是分不出来的：导入时都叫 APK 的
 * `android:label`，自动命名后是「<应用名> N」（更早的旧数据是「<应用名> 副本 N」）。
 *
 * `instanceId` 是 UUID，稳定且不会因删除/重建重号，所以用它前 8 位做**展示用短 id**。
 * 这里只是纯字符串规则，不加数据库列、不升 Room 版本、不碰 Rust 账本：
 * 列表、首页、详情顶栏、CLI 与 recents 任务卡都复用同一套格式。
 */
object InstanceTag {
    /** 短 id 长度：足够区分 4 个槽位，短到能在手机列表里完整显示。 */
    const val SHORT_ID_LENGTH = 8

    fun shortId(instanceId: String): String =
        instanceId.take(SHORT_ID_LENGTH).lowercase()

    /** 带 `#` 的标签，便于直接当选择器输入（列表副标题、CLI、日志都用它）。 */
    fun hash(instanceId: String): String = "#${shortId(instanceId)}"

    /** recents 任务卡标题：优先用用户改过的实例名，再补短 id。 */
    fun recents(displayName: String, instanceId: String): String {
        val name = displayName.trim().ifBlank { "虚拟应用" }
        return if (name.endsWith(hash(instanceId))) name else "$name · ${hash(instanceId)}"
    }
}
