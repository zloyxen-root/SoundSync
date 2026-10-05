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
import kotlinx.coroutines.flow.asStateFlow
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

    private val _isResolving = MutableStateFlow(false)
    private val _profileError = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")

    private val _profile = MutableStateFlow(
        if (prefs.userId > 0L) {
            SoundCloudUserProfile(
                id = prefs.userId,
                username = prefs.userName,
                permalink = prefs.profileInput,
                avatarUrl = prefs.avatarUrl,
                likesCount = 0
            )
        } else null
    )

    val uiState: StateFlow<MainUiState> = combine(
        _profile,
        _isResolving,
        _profileError,
        syncEngine.syncState,
        trackDao.getAllTracksFlow(),
        _searchQuery
    ) { profile, isResolving, profileError, syncState, allTracks, query ->
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
            isResolvingProfile = isResolving,
            profileError = profileError,
            profile = profile,
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
            _profileError.value = "Введите ссылку или юзернейм SoundCloud"
            return
        }

        viewModelScope.launch {
            _isResolving.value = true
            _profileError.value = null

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

                _profile.value = user
                _isResolving.value = false
            }.onFailure { error ->
                _profileError.value = error.message ?: "Ошибка подключения к профилю"
                _isResolving.value = false
            }
        }
    }

    fun startSync() {
        if (prefs.userId <= 0L) {
            _profileError.value = "Сначала укажите профиль SoundCloud"
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
