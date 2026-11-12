package com.adv.core.database

/** Room 结构、迁移 SQL 与导出 schema 的共同契约；单测和构建配置都从这里取版本。 */
internal object DbSchemaContract {

    const val NAME: String = "adv-client.db"
    const val VERSION: Int = 2
    const val MIGRATION_1_2_FROM: Int = 1
    const val MIGRATION_1_2_TO: Int = 2

    val TABLE_NAMES: List<String> = listOf(
        "apps",
        "instances",
        "permissions",
        "engine_config",
        "operation_records",
        "backup_records",
    )

    /** v2 只新增列：禁止 DROP/CREATE，避免迁移把已有实例和账本删掉。 */
    val MIGRATION_1_2_SQL: List<String> = listOf(
        "ALTER TABLE apps ADD COLUMN label TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE apps ADD COLUMN iconPath TEXT",
    )
}
