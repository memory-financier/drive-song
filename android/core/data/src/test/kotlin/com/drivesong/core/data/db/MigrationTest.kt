package com.drivesong.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v5 → v6 (설계 §8.3): 에뮬레이터에 등록한 가족·클립·노래가 남고 새 열은 기본값을 갖는다.
 * v5 스키마는 v5 의 Room 생성 코드(DriveSongDatabase_Impl)에서 그대로 옮겼다. Room 이 열린 뒤 스키마를 검사하므로
 * 마이그레이션이 엔티티와 다르면 여기서 실패한다.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `v5 database keeps members, clips and songs after migrating to v6`() = runTest {
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V5_SCHEMA.forEach(db::execSQL)
            db.execSQL("INSERT INTO members VALUES ('mem_1', '엄마', 0, 1, 0)")
            db.execSQL("INSERT INTO clips (memberId, slot, localPath, serverId, uploadState, lastUsedSeq, recordedAtMs) " +
                "VALUES ('mem_1', 'CALL', '/x.m4a', 'clip_1', 'UPLOADED', 0, 0)")
            db.execSQL("INSERT INTO songs VALUES ('song_1', 'trip_1', '강릉 가는 길', '[verse]', 1, '/s.m4a', '[]')")
            db.version = 5
        }

        val room = Room.databaseBuilder(context, DriveSongDatabase::class.java, name)
            .addMigrations(MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val member = room.voiceDao().members().first().single()
            assertEquals("엄마", member.nickname)
            assertFalse(member.conversationConsent)
            assertFalse(member.voiceConversionConsent)
            assertEquals("clip_1", room.voiceDao().clipsOf("mem_1").single().serverId)
            val song = room.songDao().songs().first().single()
            assertEquals("강릉 가는 길", song.title)
            assertEquals(emptyList<String>(), song.singers)
        } finally {
            room.close()
        }
    }

    private companion object {
        val V5_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `trips` (`id` TEXT NOT NULL, `startedAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `endReason` TEXT, `passengers` TEXT NOT NULL, `destinationLat` REAL, `destinationLng` REAL, `destinationName` TEXT, `etaAtMs` INTEGER, `summaryJson` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `trip_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` TEXT NOT NULL, `recordedAtMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_trip_points_tripId` ON `trip_points` (`tripId`)",
            "CREATE TABLE IF NOT EXISTS `trip_speed_minutes` (`tripId` TEXT NOT NULL, `minuteStartMs` INTEGER NOT NULL, `maxKmh` REAL NOT NULL, `avgKmh` REAL NOT NULL, `samples` INTEGER NOT NULL, PRIMARY KEY(`tripId`, `minuteStartMs`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `members` (`id` TEXT NOT NULL, `nickname` TEXT NOT NULL, `isChild` INTEGER NOT NULL, `voiceConsent` INTEGER NOT NULL, `guardianConsent` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `clips` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memberId` TEXT NOT NULL, `slot` TEXT NOT NULL, `localPath` TEXT NOT NULL, `serverId` TEXT, `uploadState` TEXT NOT NULL, `lastUsedSeq` INTEGER NOT NULL, `recordedAtMs` INTEGER NOT NULL, FOREIGN KEY(`memberId`) REFERENCES `members`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_clips_memberId` ON `clips` (`memberId`)",
            "CREATE TABLE IF NOT EXISTS `songs` (`id` TEXT NOT NULL, `tripId` TEXT NOT NULL, `title` TEXT NOT NULL, `lyrics` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `audioPath` TEXT NOT NULL, `waypoints` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}
