// The Room annotations Entities.kt uses — enough to compile it without Android.
package androidx.room

annotation class Entity(val tableName: String = "", val foreignKeys: Array<ForeignKey> = [],
                        val indices: Array<Index> = [], val primaryKeys: Array<String> = [])
annotation class ForeignKey(val entity: kotlin.reflect.KClass<*>, val parentColumns: Array<String>,
                            val childColumns: Array<String>, val onDelete: Int = 0) {
    companion object { const val CASCADE = 5 }
}
annotation class Index(vararg val value: String)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
