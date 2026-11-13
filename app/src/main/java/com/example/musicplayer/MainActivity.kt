package com.example.musicplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MusicPlayerScreen()
                }
            }
        }
    }
}

@Composable
fun MusicPlayerScreen(vm: PlayerViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadTracksFromDevice()
    }

    LaunchedEffect(Unit) {
        launcher.launch(permission)
    }

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayerBar(vm)
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                        label = { Text("Треки") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                        label = { Text("Очередь (${vm.playQueue.size})") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.FeaturedPlayList, contentDescription = null) },
                        label = { Text("Плейлисты") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Equalizer, contentDescription = null) },
                        label = { Text("Звук") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                        label = { Text("TG Бот") }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> AllTracksTab(vm)
                1 -> QueueTab(vm)
                2 -> PlaylistsTab(vm)
                3 -> EqualizerTab(vm)
                4 -> TelegramSyncTab(vm)
            }
        }
    }
}

@Composable
fun AllTracksTab(vm: PlayerViewModel) {
    var trackForPlaylistDialog by remember { mutableStateOf<AudioTrack?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Найдено треков: ${vm.allDeviceTracks.size}", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.loadTracksFromDevice() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }
        HorizontalDivider()

        if (vm.allDeviceTracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Аудиофайлы не найдены")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.loadTracksFromDevice() }) {
                        Text("Просканировать память")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(vm.allDeviceTracks) { _, track ->
                    ListItem(
                        headlineContent = { Text(track.title, maxLines = 1) },
                        supportingContent = { Text(track.artist, maxLines = 1) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { vm.addTrackPlayNext(track) }) {
                                    Icon(Icons.Default.PlaylistPlay, contentDescription = "Играть следующим")
                                }
                                IconButton(onClick = { vm.addTrackToEndOfQueue(track) }) {
                                    Icon(Icons.Default.AddToQueue, contentDescription = "В конец очереди")
                                }
                                IconButton(onClick = { trackForPlaylistDialog = track }) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = "В плейлист")
                                }
                            }
                        },
                        modifier = Modifier.clickable { vm.playTrackWithQueue(track) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    trackForPlaylistDialog?.let { track ->
        AlertDialog(
            onDismissRequest = { trackForPlaylistDialog = null },
            title = { Text("Добавить в плейлист") },
            text = {
                if (vm.playlists.isEmpty()) {
                    Text("Сначала создайте плейлист во вкладке «Плейлисты».")
                } else {
                    Column {
                        vm.playlists.forEach { pl ->
                            TextButton(
                                onClick = {
                                    vm.addTrackToPlaylist(pl.name, track.id)
                                    trackForPlaylistDialog = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${pl.name} (${pl.trackIds.size})")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { trackForPlaylistDialog = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@Composable
fun QueueTab(vm: PlayerViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Очередь воспроизведения", style = MaterialTheme.typography.titleMedium)
            if (vm.playQueue.isNotEmpty()) {
                TextButton(onClick = { vm.clearQueue() }) {
                    Text("Очистить")
                }
            }
        }
        HorizontalDivider()

        if (vm.playQueue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Очередь пуста. Выберите трек или добавьте из списка.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(vm.playQueue) { index, track ->
                    val isCurrent = vm.currentTrack.value?.id == track.id
                    ListItem(
                        headlineContent = {
                            Text(
                                text = if (isCurrent) "▶ ${track.title}" else "${index + 1}. ${track.title}",
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        },
                        supportingContent = { Text(track.artist, maxLines = 1) },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { vm.moveTrackInQueue(index, index - 1) },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Выше")
                                }
                                IconButton(
                                    onClick = { vm.moveTrackInQueue(index, index + 1) },
                                    enabled = index < vm.playQueue.lastIndex
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Ниже")
                                }
                                IconButton(onClick = { vm.removeTrackFromQueue(index) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Удалить")
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            vm.controller?.seekToDefaultPosition(index)
                            vm.controller?.play()
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun PlaylistsTab(vm: PlayerViewModel) {
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<UserPlaylist?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = { Text("Название нового плейлиста") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                vm.createPlaylist(newPlaylistName)
                newPlaylistName = ""
            }) {
                Text("Создать")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (selectedPlaylist == null) {
            if (vm.playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("У вас пока нет созданных плейлистов")
                }
            } else {
                LazyColumn {
                    items(vm.playlists) { pl ->
                        ListItem(
                            headlineContent = { Text(pl.name) },
                            supportingContent = { Text("Треков: ${pl.trackIds.size}") },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        val tracks = vm.allDeviceTracks.filter { pl.trackIds.contains(it.id) }
                                        if (tracks.isNotEmpty()) {
                                            vm.playTrackWithQueue(tracks.first(), tracks)
                                        }
                                    }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Запустить плейлист")
                                    }
                                    IconButton(onClick = { vm.deletePlaylist(pl) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                                    }
                                }
                            },
                            modifier = Modifier.clickable { selectedPlaylist = pl }
                        )
                        HorizontalDivider()
                    }
                }
            }
        } else {
            val pl = selectedPlaylist!!
            val tracks = vm.allDeviceTracks.filter { pl.trackIds.contains(it.id) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedPlaylist = null }) {
                    Text("← Назад к плейлистам")
                }
                Spacer(Modifier.weight(1f))
                Text(pl.name, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            LazyColumn {
                items(tracks) { track ->
                    ListItem(
                        headlineContent = { Text(track.title) },
                        supportingContent = { Text(track.artist) },
                        modifier = Modifier.clickable { vm.playTrackWithQueue(track, tracks) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun EqualizerTab(vm: PlayerViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Системный эквалайзер", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = vm.isEqEnabled.value,
                onCheckedChange = { vm.toggleEqualizer(it) }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (vm.eqBands.isEmpty()) {
            Text("Включите воспроизведение любого трека, чтобы активировать полосы частот эквалайзера.")
        } else {
            vm.eqBands.forEach { band ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("${band.centerFreqHz} Гц: ${band.currentLevel / 100} дБ")
                    Slider(
                        value = band.currentLevel.toFloat(),
                        onValueChange = { vm.setBandLevel(band.index, it.toInt().toShort()) },
                        valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
                        enabled = vm.isEqEnabled.value
                    )
                }
            }
        }
    }
}

@Composable
fun TelegramSyncTab(vm: PlayerViewModel) {
    var tokenInput by remember { mutableStateOf(vm.botToken.value) }
    var chatIdInput by remember { mutableStateOf(vm.allowedChatId.value) }
    var showToken by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Синхронизация через Telegram Бота", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Пересылайте MP3-файлы своему боту в Telegram и нажимайте «Скачать новые треки». Они сохранятся в папку Music/TelegramSync.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Bot API Token (от @BotFather)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Показать/скрыть токен"
                    )
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = chatIdInput,
            onValueChange = { chatIdInput = it },
            label = { Text("Ваш личный Chat ID (необязательно, для защиты)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { vm.saveTelegramConfig(tokenInput, chatIdInput) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Сохранить")
            }

            Button(
                onClick = {
                    vm.saveTelegramConfig(tokenInput, chatIdInput)
                    vm.syncWithTelegramBot()
                },
                enabled = !vm.isSyncing.value,
                modifier = Modifier.weight(1f)
            ) {
                if (vm.isSyncing.value) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text("Скачать треки")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Статус: ${vm.syncStatus.value}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Журнал загрузок:", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(vm.syncLogs) { log ->
                Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun MiniPlayerBar(vm: PlayerViewModel) {
    val track = vm.currentTrack.value ?: return
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Row {
                IconButton(onClick = { vm.skipPrev() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий")
                }
                IconButton(onClick = { vm.togglePlayPause() }) {
                    Icon(
                        if (vm.isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Плей/Пауза"
                    )
                }
                IconButton(onClick = { vm.skipNext() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Следующий")
                }
            }
        }
    }
}
