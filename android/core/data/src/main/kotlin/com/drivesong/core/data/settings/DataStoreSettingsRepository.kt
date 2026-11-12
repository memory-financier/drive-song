package com.drivesong.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.SongStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
internal class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    override val simulateDrivingRestriction: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[SIMULATE_DRIVING_RESTRICTION] ?: false }
            .distinctUntilChanged()

    override suspend fun setSimulateDrivingRestriction(enabled: Boolean) {
        context.settingsDataStore.edit { it[SIMULATE_DRIVING_RESTRICTION] = enabled }
    }

    override val demoMode: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[DEMO_MODE] ?: false }
            .distinctUntilChanged()

    override suspend fun setDemoMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[DEMO_MODE] = enabled }
    }

    override val destinationPresetId: Flow<String?> =
        context.settingsDataStore.data
            .map { it[DESTINATION_PRESET_ID] }
            .distinctUntilChanged()

    override suspend fun setDestinationPresetId(id: String?) {
        context.settingsDataStore.edit {
            if (id == null) it.remove(DESTINATION_PRESET_ID) else it[DESTINATION_PRESET_ID] = id
        }
    }

    override val selectedPassengerIds: Flow<Set<String>> =
        context.settingsDataStore.data
            .map { it[SELECTED_PASSENGER_IDS] ?: emptySet() }
            .distinctUntilChanged()

    override suspend fun setSelectedPassengerIds(ids: Set<String>) {
        context.settingsDataStore.edit { it[SELECTED_PASSENGER_IDS] = ids }
    }

    override val clipPlanMode: Flow<ClipPlanMode> =
        context.settingsDataStore.data
            .map { prefs -> ClipPlanMode.entries.find { it.name == prefs[CLIP_PLAN_MODE] } ?: ClipPlanMode.RANDOM }
            .distinctUntilChanged()

    override suspend fun setClipPlanMode(mode: ClipPlanMode) {
        context.settingsDataStore.edit { it[CLIP_PLAN_MODE] = mode.name }
    }

    override val songStyle: Flow<SongStyle> =
        context.settingsDataStore.data
            .map { prefs -> SongStyle.entries.find { it.name == prefs[SONG_STYLE] } ?: SongStyle.CITY_POP }
            .distinctUntilChanged()

    override suspend fun setSongStyle(style: SongStyle) {
        context.settingsDataStore.edit { it[SONG_STYLE] = style.name }
    }

    override val autoPlay: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[AUTO_PLAY] ?: true }
            .distinctUntilChanged()

    override suspend fun setAutoPlay(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_PLAY] = enabled }
    }

    override val ambientLightShow: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[AMBIENT_LIGHT_SHOW] ?: true }
            .distinctUntilChanged()

    override suspend fun setAmbientLightShow(enabled: Boolean) {
        context.settingsDataStore.edit { it[AMBIENT_LIGHT_SHOW] = enabled }
    }

    private companion object {
        val SIMULATE_DRIVING_RESTRICTION = booleanPreferencesKey("simulate_driving_restriction")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val DESTINATION_PRESET_ID = stringPreferencesKey("destination_preset_id")
        val SELECTED_PASSENGER_IDS = stringSetPreferencesKey("selected_passenger_ids")
        val CLIP_PLAN_MODE = stringPreferencesKey("clip_plan_mode")
        val SONG_STYLE = stringPreferencesKey("song_style")
        val AUTO_PLAY = booleanPreferencesKey("auto_play")
        val AMBIENT_LIGHT_SHOW = booleanPreferencesKey("ambient_light_show")
    }
}
