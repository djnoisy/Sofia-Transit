package bg.sofia.transit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import bg.sofia.transit.data.db.entity.Shape

@Dao
interface ShapeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shapes: List<Shape>)

    @Query("SELECT * FROM shapes WHERE shapeId = :id")
    suspend fun getById(id: String): Shape?

    @Query("SELECT COUNT(*) FROM shapes")
    suspend fun count(): Int

    @Query("DELETE FROM shapes")
    suspend fun deleteAll()
}
