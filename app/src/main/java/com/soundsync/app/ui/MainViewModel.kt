package com.soundsync.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundsync.app.data.local.SoundSyncDatabase
import com.soundsync.app.data.local.TrackEntity
import com.soundsync.app.data.model.SoundCloudUserProfile
import com.soundsync.app.data.preferences.UserPreferences
import com.soundsync.app.data.soundcloud.SoundCloudClient
import com.soundsync.app.data.sync.SyncEngine
import com.soundsync.app.data.sync.SyncState
import com.soundsync.app.service.SyncForegroundService
import com.soundsync.app.service.SyncWorker
import com.soundsync.app.util.StorageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val isResolvingProfile: Boolean = false,
    val profileError: String? = null,
    val profile: SoundCloudUserProfile? = null,
    val syncState: SyncState = SyncState.Idle,
    val tracks: List<TrackEntity> = emptyList(),
    val searchQuery: String = "",
    val autoDeleteUnliked: Boolean = true,
    val wifiOnly: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val oauthToken: String = "",
    val customClientId: String = "",
    val lastSyncTime: Long = 0L,
    val totalStorageSizeBytes: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val db = SoundSyncDatabase.getInstance(context)
    private val trackDao = db.trackDao()
    private val prefs = UserPreferences(context)
    private val syncEngine = SyncEngine(context)

    private data class ProfileState(
        val profile: SoundCloudUserProfile?,
        val isResolving: Boolean,
        val error: String?
    )

    private val _profileState = MutableStateFlow(
        ProfileState(
            profile = if (prefs.userId > 0L) {
                SoundCloudUserProfile(
                    id = prefs.userId,
                    username = prefs.userName,
                    permalink = prefs.profileInput,
                    avatarUrl = prefs.avatarUrl,
                    likesCount = 0
                )
            } else null,
            isResolving = false,
            error = null
        )
    )

    private val _searchQuery = MutableStateFlow("")

    // Expose isResolving and profileError as derived flows for backward compatibility
    private val _isResolving get() = _profileState.value.isResolving
    private val _profileError get() = _profileState.value.error
    private val _profile get() = _profileState.value.profile

    // combine() supports max 5 flows — split into two stages
    val uiState: StateFlow<MainUiState> = combine(
        _profileState,
        syncEngine.syncState,
        trackDao.getAllTracksFlow(),
        _searchQuery
    ) { profileState, syncState, allTracks, query ->
        val filteredTracks = if (query.isBlank()) {
            allTracks
        } else {
            allTracks.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
        }

        val totalSize = allTracks.sumOf { it.fileSizeBytes }

        MainUiState(
            isResolvingProfile = profileState.isResolving,
            profileError = profileState.error,
            profile = profileState.profile,
            syncState = syncState,
            tracks = filteredTracks,
            searchQuery = query,
            autoDeleteUnliked = prefs.autoDeleteUnliked,
            wifiOnly = prefs.wifiOnly,
            autoSyncEnabled = prefs.autoSyncEnabled,
            oauthToken = prefs.oauthToken,
            customClientId = prefs.customClientId,
            lastSyncTime = prefs.lastSyncTimestamp,
            totalStorageSizeBytes = totalSize
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun connectProfile(input: String, oauthToken: String = "", customClientId: String = "") {
        if (input.isBlank()) {
            _profileState.value = _profileState.value.copy(error = "Введите ссылку или юзернейм SoundCloud")
            return
        }

        viewModelScope.launch {
            _profileState.value = _profileState.value.copy(isResolving = true, error = null)

            val client = SoundCloudClient(
                customClientId = customClientId.ifBlank { null },
                oauthToken = oauthToken.ifBlank { null }
            )

            val result = client.resolveUserProfile(input)
            result.onSuccess { user ->
                prefs.profileInput = input
                prefs.userId = user.id
                prefs.userName = user.username
                prefs.avatarUrl = user.avatarUrl
                prefs.oauthToken = oauthToken
                prefs.customClientId = customClientId

                _profileState.value = ProfileState(profile = user, isResolving = false, error = null)
            }.onFailure { error ->
                _profileState.value = _profileState.value.copy(
                    isResolving = false,
                    error = error.message ?: "Ошибка подключения к профилю"
                )
            }
        }
    }

    fun startSync() {
        if (prefs.userId <= 0L) {
            _profileState.value = _profileState.value.copy(error = "Сначала укажите профиль SoundCloud")
            return
        }
        SyncForegroundService.startSync(context)
    }

    fun cancelSync() {
        SyncForegroundService.stopSync(context)
    }

    fun updateSettings(
        autoDeleteUnliked: Boolean,
        wifiOnly: Boolean,
        autoSyncEnabled: Boolean,
        oauthToken: String,
        customClientId: String
    ) {
        prefs.autoDeleteUnliked = autoDeleteUnliked
        prefs.wifiOnly = wifiOnly
        prefs.autoSyncEnabled = autoSyncEnabled
        prefs.oauthToken = oauthToken
        prefs.customClientId = customClientId

        if (autoSyncEnabled) {
            SyncWorker.schedulePeriodicSync(context, wifiOnly)
        } else {
            SyncWorker.cancelPeriodicSync(context)
        }
    }

    fun deleteTrackManually(track: TrackEntity) {
        viewModelScope.launch {
            StorageHelper.deleteTrackFile(context, track.localFilePath, track.mediaStoreUri)
            trackDao.deleteById(track.trackId)
        }
    }
}
