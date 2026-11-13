package com.example.musicplayer

import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val duration: Long
)

data class EqBand(
    val index: Short,
    val centerFreqHz: Int,
    val minLevel: Short,
    val maxLevel: Short,
    var currentLevel: Short
)

data class UserPlaylist(
    val name: String,
    val trackIds: MutableList<Long>
)

class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    var controller: MediaController? = null
        private set

    val allDeviceTracks = mutableStateListOf<AudioTrack>()
    val playQueue = mutableStateListOf<AudioTrack>()
    val playlists = mutableStateListOf<UserPlaylist>()

    var currentTrack = mutableStateOf<AudioTrack?>(null)
    var isPlaying = mutableStateOf(false)

    // Эквалайзер
    private var equalizer: Equalizer? = null
    val eqBands = mutableStateListOf<EqBand>()
    var isEqEnabled = mutableStateOf(false)

    // Telegram Синхронизация
    var botToken = mutableStateOf(prefs.getString("bot_token", "") ?: "")
    var allowedChatId = mutableStateOf(prefs.getString("allowed_chat_id", "") ?: "")
    var isSyncing = mutableStateOf(false)
    var syncStatus = mutableStateOf("Готов к синхронизации")
    val syncLogs = mutableStateListOf<String>()

    init {
        initController()
        loadPlaylists()
    }

    private fun initController() {
        val sessionToken = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(app, sessionToken).buildAsync()
        controllerFuture.addListener({
            controller = controllerFuture.get()
            setupPlayerListener()
        }, MoreExecutors.directExecutor())
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = controller?.currentMediaItemIndex ?: return
                if (index in playQueue.indices) {
                    currentTrack.value = playQueue[index]
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupEqualizer(audioSessionId)
            }
        })
    }

    // 1. Сканирование локальных аудиофайлов на телефоне
    fun loadTracksFromDevice() {
        allDeviceTracks.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            app.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Неизвестный трек"
                    val artist = cursor.getString(artistCol) ?: "Неизвестный исполнитель"
                    val duration = cursor.getLong(durCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    allDeviceTracks.add(AudioTrack(id, title, artist, uri, duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 2. Управление воспроизведением и Гибкой Очередью
    fun playTrackWithQueue(track: AudioTrack, sourceList: List<AudioTrack> = allDeviceTracks) {
        val c = controller ?: return
        playQueue.clear()
        playQueue.addAll(sourceList)

        val mediaItems = sourceList.map { it.toMediaItem() }
        val startIndex = sourceList.indexOf(track).coerceAtLeast(0)

        c.setMediaItems(mediaItems, startIndex, 0L)
        c.prepare()
        c.play()
        currentTrack.value = track
    }

    fun addTrackPlayNext(track: AudioTrack) {
        val c = controller ?: return
        if (playQueue.isEmpty()) {
            playTrackWithQueue(track, listOf(track))
            return
        }
        val insertIndex = (c.currentMediaItemIndex + 1).coerceAtMost(playQueue.size)
        playQueue.add(insertIndex, track)
        c.addMediaItem(insertIndex, track.toMediaItem())
    }

    fun addTrackToEndOfQueue(track: AudioTrack) {
        val c = controller ?: return
        if (playQueue.isEmpty()) {
            playTrackWithQueue(track, listOf(track))
            return
        }
        playQueue.add(track)
        c.addMediaItem(track.toMediaItem())
    }

    fun moveTrackInQueue(fromIndex: Int, toIndex: Int) {
        val c = controller ?: return
        if (fromIndex !in playQueue.indices || toIndex !in playQueue.indices) return
        val item = playQueue.removeAt(fromIndex)
        playQueue.add(toIndex, item)
        c.moveMediaItem(fromIndex, toIndex)
    }

    fun removeTrackFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in playQueue.indices) return
        playQueue.removeAt(index)
        c.removeMediaItem(index)
    }

    fun clearQueue() {
        val c = controller ?: return
        playQueue.clear()
        c.clearMediaItems()
        currentTrack.value = null
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrev() = controller?.seekToPreviousMediaItem()

    // 3. Пользовательские Плейлисты
    fun createPlaylist(name: String) {
        if (name.isBlank() || playlists.any { it.name == name }) return
        playlists.add(UserPlaylist(name.trim(), mutableListOf()))
        savePlaylists()
    }

    fun deletePlaylist(playlist: UserPlaylist) {
        playlists.remove(playlist)
        savePlaylists()
    }

    fun addTrackToPlaylist(playlistName: String, trackId: Long) {
        val idx = playlists.indexOfFirst { it.name == playlistName }
        if (idx != -1 && !playlists[idx].trackIds.contains(trackId)) {
            val updated = playlists[idx].trackIds.toMutableList().apply { add(trackId) }
            playlists[idx] = playlists[idx].copy(trackIds = updated)
            savePlaylists()
        }
    }

    private fun savePlaylists() {
        val raw = playlists.joinToString(";;") { pl ->
            "${pl.name}::${pl.trackIds.joinToString(",")}"
        }
        prefs.edit().putString("saved_playlists", raw).apply()
    }

    private fun loadPlaylists() {
        playlists.clear()
        val raw = prefs.getString("saved_playlists", "") ?: ""
        if (raw.isBlank()) return
        raw.split(";;").forEach { entry ->
            val parts = entry.split("::")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val ids = if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
                } else mutableListOf()
                playlists.add(UserPlaylist(parts[0], ids))
            }
        }
    }

    // 4. Настройка многополосного Эквалайзера
    fun setupEqualizer(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply {
                enabled = isEqEnabled.value
                val bandsCount = numberOfBands
                val minLevel = bandLevelRange[0]
                val maxLevel = bandLevelRange[1]

                eqBands.clear()
                for (i in 0 until bandsCount) {
                    val bandIndex = i.toShort()
                    eqBands.add(
                        EqBand(
                            index = bandIndex,
                            centerFreqHz = getCenterFreq(bandIndex) / 1000,
                            minLevel = minLevel,
                            maxLevel = maxLevel,
                            currentLevel = getBandLevel(bandIndex)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleEqualizer(enabled: Boolean) {
        isEqEnabled.value = enabled
        equalizer?.enabled = enabled
    }

    fun setBandLevel(bandIndex: Short, level: Short) {
        equalizer?.setBandLevel(bandIndex, level)
        val idx = eqBands.indexOfFirst { it.index == bandIndex }
        if (idx != -1) {
            eqBands[idx] = eqBands[idx].copy(currentLevel = level)
        }
    }

    // 5. Синхронизация с Telegram-ботом
    fun saveTelegramConfig(token: String, chatId: String) {
        val cleanToken = token.trim()
        val cleanChat = chatId.trim()
        botToken.value = cleanToken
        allowedChatId.value = cleanChat
        prefs.edit()
            .putString("bot_token", cleanToken)
            .putString("allowed_chat_id", cleanChat)
            .apply()
        syncStatus.value = "Настройки сохранены"
    }

    fun syncWithTelegramBot() {
        val token = botToken.value.trim()
        if (token.isEmpty()) {
            syncStatus.value = "Ошибка: сначала введите Bot Token!"
            return
        }
        if (isSyncing.value) return

        isSyncing.value = true
        syncStatus.value = "Подключение к Telegram API..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastOffset = prefs.getLong("tg_update_offset", 0L)
                val updatesUrl = "https://api.telegram.org/bot$token/getUpdates?offset=$lastOffset&timeout=5"
                val responseText = httpGet(updatesUrl)
                val json = JSONObject(responseText)

                if (!json.optBoolean("ok", false)) {
                    val desc = json.optString("description", "Неверный токен или ошибка сети")
                    withContext(Dispatchers.Main) {
                        syncStatus.value = "Ошибка API: $desc"
                        isSyncing.value = false
                    }
                    return@launch
                }

                val results = json.getJSONArray("result")
                var downloadedCount = 0
                var maxUpdateId = lastOffset

                for (i in 0 until results.length()) {
                    val update = results.getJSONObject(i)
                    val updateId = update.getLong("update_id")
                    if (updateId >= maxUpdateId) {
                        maxUpdateId = updateId + 1
                    }

                    val message = update.optJSONObject("message")
                        ?: update.optJSONObject("channel_post")
                        ?: continue

                    val chat = message.optJSONObject("chat")
                    val senderChatId = chat?.optLong("id")?.toString() ?: ""

                    // Фильтр по Chat ID, если пользователь его указал
                    val filterId = allowedChatId.value.trim()
                    if (filterId.isNotEmpty() && senderChatId != filterId) {
                        continue
                    }

                    // Проверяем обычное аудио или документ с аудиофайлом
                    val audioObj = message.optJSONObject("audio")
                        ?: message.optJSONObject("voice")
                        ?: message.optJSONObject("document")?.takeIf {
                            val mime = it.optString("mime_type", "")
                            val fname = it.optString("file_name", "").lowercase()
                            mime.startsWith("audio/") || fname.endsWith(".mp3") || fname.endsWith(".flac") || fname.endsWith(".m4a") || fname.endsWith(".wav")
                        }

                    if (audioObj != null) {
                        val fileId = audioObj.getString("file_id")
                        val performer = audioObj.optString("performer", "Telegram")
                        val title = audioObj.optString("title", "")
                        val origName = audioObj.optString("file_name", "")

                        val baseName = when {
                            origName.isNotBlank() -> origName
                            title.isNotBlank() -> "$performer - $title.mp3"
                            else -> "track_${System.currentTimeMillis()}.mp3"
                        }.replace(Regex("[\\/:*?"<>|]"), "_")

                        withContext(Dispatchers.Main) {
                            syncStatus.value = "Скачивание: $baseName..."
                        }

                        val saved = downloadTelegramFile(token, fileId, baseName, title.ifBlank { baseName.substringBeforeLast(".") }, performer)
                        if (saved) {
                            downloadedCount++
                            withContext(Dispatchers.Main) {
                                syncLogs.add(0, "✓ Скачан: $baseName (Chat ID: $senderChatId)")
                            }
                        }
                    } else if (senderChatId.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (allowedChatId.value.isBlank()) {
                                syncLogs.add(0, "ℹ Сообщение от Chat ID: $senderChatId (можете скопировать его в фильтр)")
                            }
                        }
                    }
                }

                prefs.edit().putLong("tg_update_offset", maxUpdateId).apply()

                withContext(Dispatchers.Main) {
                    loadTracksFromDevice()
                    syncStatus.value = if (downloadedCount > 0) {
                        "Готово! Скачано новых треков: $downloadedCount"
                    } else {
                        "Новых аудиофайлов в боте нет"
                    }
                    isSyncing.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    syncStatus.value = "Ошибка: ${e.localizedMessage ?: "Проверьте интернет"}"
                    isSyncing.value = false
                }
            }
        }
    }

    private fun downloadTelegramFile(token: String, fileId: String, fileName: String, title: String, artist: String): Boolean {
        val fileInfoJson = JSONObject(httpGet("https://api.telegram.org/bot$token/getFile?file_id=$fileId"))
        if (!fileInfoJson.optBoolean("ok", false)) return false

        val filePath = fileInfoJson.getJSONObject("result").getString("file_path")
        val downloadUrl = "https://api.telegram.org/file/bot$token/$filePath"

        val finalName = if (fileName.contains(".")) fileName else "$fileName.mp3"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, finalName)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/TelegramSync")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
            URL(downloadUrl).openStream().use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return true
        } else {
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "TelegramSync")
            if (!musicDir.exists()) musicDir.mkdirs()
            val targetFile = File(musicDir, finalName)
            URL(downloadUrl).openStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            MediaScannerConnection.scanFile(app, arrayOf(targetFile.absolutePath), arrayOf("audio/mpeg"), null)
            return true
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader().use { it.readText() }
    }

    private fun AudioTrack.toMediaItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(meta)
            .build()
    }

    override fun onCleared() {
        equalizer?.release()
        controller?.release()
        super.onCleared()
    }
}
