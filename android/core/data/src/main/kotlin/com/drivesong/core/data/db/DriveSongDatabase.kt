package com.drivesong.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 앱 로컬 DB (주행 기록, 클립 메타, 곡 목록 — 설계 §4.1).
 *
 * 엔티티 추가 방법:
 * 1. `db/` 아래에 @Entity 와 @Dao 를 만든다.
 * 2. 아래 `entities` 에 추가하고 `version` 을 올린다.
 * 3. 여기에 `abstract fun xxxDao(): XxxDao` 를 추가하고, [com.drivesong.core.data.di.DatabaseModule] 에서 @Provides 한다.
 * 출시 전 MVP 단계라 대부분 파괴적 재생성을 쓴다 (DatabaseModule 참조). v5 → v6 은 에뮬레이터에 등록한 가족·클립·노래를
 * 지키려고 [MIGRATION_5_6] 을 둔다.
 */
@Database(
    entities = [
        TripEntity::class, TripPointEntity::class, SpeedMinuteEntity::class,
        MemberEntity::class, ClipEntity::class,
        SongEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class DriveSongDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun voiceDao(): VoiceDao
    abstract fun songDao(): SongDao
}

/** v0.4 (설계 §8.3, §7.5): 구성원 선택 동의 2개, 노래를 부른 가족. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `members` ADD COLUMN `conversationConsent` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `members` ADD COLUMN `voiceConversionConsent` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `singers` TEXT NOT NULL DEFAULT '[]'")
    }
}
