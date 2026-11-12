package com.adv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppEntity::class,
        InstanceEntity::class,
        PermissionEntity::class,
        EngineConfigEntity::class,
        OperationRecordEntity::class,
        BackupRecordEntity::class,
    ],
    version = DbSchemaContract.VERSION,
    exportSchema = true,
)
abstract class AdvDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao

    abstract fun instanceDao(): InstanceDao

    abstract fun permissionDao(): PermissionDao

    abstract fun operationDao(): OperationDao

    abstract fun backupDao(): BackupDao

    abstract fun configDao(): ConfigDao

    companion object {
        const val NAME: String = DbSchemaContract.NAME

        /**
         * v2 adds the display label and icon path. Existing rows keep an empty
         * label and are back-filled by AppRepository once the app starts, so the
         * migration must not drop user data.
         */
        internal val MIGRATION_1_2 = object : Migration(
            DbSchemaContract.MIGRATION_1_2_FROM,
            DbSchemaContract.MIGRATION_1_2_TO,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DbSchemaContract.MIGRATION_1_2_SQL.forEach { sql -> db.execSQL(sql) }
            }
        }

        fun open(context: Context, name: String = NAME): AdvDatabase =
            Room.databaseBuilder(context.applicationContext, AdvDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
