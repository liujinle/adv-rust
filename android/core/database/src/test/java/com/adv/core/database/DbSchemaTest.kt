package com.adv.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class DbSchemaTest {

    private val schemaFile = File(
        "schemas/com.adv.core.database.AdvDatabase/${DbSchemaContract.VERSION}.json",
    )

    @Test
    fun exportedSchemaMatchesContractTables() {
        assertTrue("Room schema 尚未导出：${schemaFile.absolutePath}", schemaFile.isFile)
        val json = schemaFile.readText()
        assertTrue(json.contains("\"version\": ${DbSchemaContract.VERSION}"))
        DbSchemaContract.TABLE_NAMES.forEach { table ->
            assertTrue("缺少表 $table", json.contains("\"tableName\": \"$table\""))
        }
        val createTableCount = Regex("CREATE TABLE").findAll(json).count()
        assertTrue("createSql 数量不足：$createTableCount", createTableCount >= DbSchemaContract.TABLE_NAMES.size)
        assertTrue("缺少主键约束", json.contains("PRIMARY KEY"))
    }

    @Test
    fun entitySourceKeepsEveryDeclaredTable() {
        val source = File("src/main/java/com/adv/core/database/Entities.kt")
        assertTrue(source.isFile)
        val text = source.readText()
        DbSchemaContract.TABLE_NAMES.forEach { table ->
            assertTrue("Entities.kt 缺少 $table", text.contains("tableName = \"$table\""))
        }
    }

    @Test
    fun migrationOnlyAddsColumnsAndNeverDropsTables() {
        assertEquals(2, DbSchemaContract.VERSION)
        assertEquals(DbSchemaContract.NAME, AdvDatabase.NAME)
        assertEquals(DbSchemaContract.MIGRATION_1_2_FROM, AdvDatabase.MIGRATION_1_2.startVersion)
        assertEquals(DbSchemaContract.MIGRATION_1_2_TO, AdvDatabase.MIGRATION_1_2.endVersion)
        DbSchemaContract.MIGRATION_1_2_SQL.forEach { sql ->
            assertTrue(sql.startsWith("ALTER TABLE apps"))
            assertFalse("迁移不允许 DROP：$sql", sql.contains("DROP", ignoreCase = true))
        }

        val statements = mutableListOf<String>()
        val db = recordingDatabase(statements)
        AdvDatabase.MIGRATION_1_2.migrate(db)
        assertEquals(DbSchemaContract.MIGRATION_1_2_SQL, statements)
    }

    private fun recordingDatabase(statements: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { proxy, method, args ->
            val first = args?.firstOrNull()
            when {
                method.name == "execSQL" && first is String -> {
                    statements += first
                    null
                }

                method.name == "toString" -> "recording-db"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === first
                else -> defaultReturn(method.returnType)
            }
        } as SupportSQLiteDatabase

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
