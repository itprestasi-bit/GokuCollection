package com.collectionfield.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShiftEntity::class, TelemetryPointEntity::class, OutletEntity::class, VisitEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class CollectionDatabase : RoomDatabase() {
    abstract fun shiftDao(): ShiftDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun outletDao(): OutletDao
    abstract fun visitDao(): VisitDao

    companion object {
        @Volatile private var instance: CollectionDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shifts ADD COLUMN collectorUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shifts ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE telemetry_points ADD COLUMN collectorUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE visits ADD COLUMN collectorUid TEXT NOT NULL DEFAULT ''")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_shifts_collectorUid ON shifts(collectorUid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shifts_syncStatus ON shifts(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_points_collectorUid ON telemetry_points(collectorUid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_visits_collectorUid ON visits(collectorUid)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outlets ADD COLUMN totalPiutang REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outlets ADD COLUMN jatuhTempo TEXT")
                db.execSQL("ALTER TABLE visits ADD COLUMN photoUrl TEXT")
                db.execSQL("ALTER TABLE visits ADD COLUMN arrivalLat REAL")
                db.execSQL("ALTER TABLE visits ADD COLUMN arrivalLng REAL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outlets ADD COLUMN piutangJson TEXT")
            }
        }

        fun get(context: Context): CollectionDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CollectionDatabase::class.java,
                    "collection_field.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
