package com.music.apumusic

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.music.apumusic.data.MediaStoreRepository
import com.music.apumusic.data.Song
import com.music.apumusic.lyrics.LyricsRepository
import com.music.apumusic.lyrics.LrcLine
import com.music.apumusic.network.YouTubeMusicRepository
import com.music.apumusic.smartplaylist.MixVibe
import com.music.apumusic.smartplaylist.SmartPlaylistGenerator
import com.music.apumusic.ui.screens.NowPlayingSheet
import com.music.apumusic.ui.screens.SettingsScreen
import com.music.apumusic.ui.theme.APUMusicTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repo: MediaStoreRepository
    private val ytRepo = YouTubeMusicRepository()
    private val lyricsRepo = LyricsRepository()
    private val smartGen = SmartPlaylistGenerator()

    private var localSongs by mutableStateOf<List<Song>>(emptyList())
    private var searchResults by mutableStateOf<List<Song>>(emptyList())
    private var currentSong by mutableStateOf<Song?>(null)
    private var isPlaying by mutableStateOf(false)
    private var currentPositionMs by mutableStateOf(0L)
    private var currentLyrics by mutableStateOf<List<LrcLine>>(emptyList())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) loadLocalMusic()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = MediaStoreRepository(this)
        checkStoragePermissions()

        setContent {
            APUMusicTheme(darkTheme = true) {
                var selectedTab by remember { mutableStateOf(0) }
                var showFullNowPlaying by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = {
                            Column {
                                // 1. Liquid Glass Mini-Player (if a song is selected)
                                currentSong?.let { song ->
                                    MiniPlayer(
                                        song = song,
                                        isPlaying = isPlaying,
                                        onPlayPause = { isPlaying = !isPlaying },
                                        onClick = { showFullNowPlaying = true }
                                    )
                                }

                                // 2. Frosted Navigation Bar
                                NavigationBar(
                                    containerColor = Color(0xFF14141A).copy(alpha = 0.95f),
                                    tonalElevation = 8.dp
                                ) {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        icon = { Icon(Icons.Default.Explore, contentDescription = "Explore") },
                                        label = { Text("Explore") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                                        label = { Text("Library") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = { selectedTab = 2 },
                                        icon = { Icon(Icons.Default.Radio, contentDescription = "Mixes") },
                                        label = { Text("Smart Mixes") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 3,
                                        onClick = { selectedTab = 3 },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                        label = { Text("Settings") }
                                    )
                                }
                            }
                        }
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        ) {
                            when (selectedTab) {
                                0 -> ExploreScreen(
                                    searchResults = searchResults,
                                    onSearch = { query ->
                                        scope.launch {
                                            searchResults = ytRepo.searchSongs(query)
                                        }
                                    },
                                    onSongSelected = { song -> playSong(song) }
                                )
                                1 -> LibraryScreen(
                                    songs = localSongs,
                                    onSongSelected = { song -> playSong(song) }
                                )
                                2 -> SmartMixesScreen(
                                    allSongs = localSongs,
                                    onSongSelected = { song -> playSong(song) }
                                )
                                3 -> SettingsScreen()
                            }
                        }
                    }

                    // 3. Apple Music Fullscreen Sliding Now Playing Sheet
                    AnimatedVisibility(
                        visible = showFullNowPlaying,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        NowPlayingSheet(
                            song = currentSong,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            durationMs = currentSong?.durationMs ?: 0L,
                            syncedLyrics = currentLyrics,
                            onPlayPause = { isPlaying = !isPlaying },
                            onNext = {},
                            onPrevious = {},
                            onSeekTo = { pos -> currentPositionMs = pos },
                            onClose = { showFullNowPlaying = false }
                        )
                    }
                }
            }
        }
    }

    private fun playSong(song: Song) {
        currentSong = song
        isPlaying = true
        currentPositionMs = 0L

        // Automatically fetch LRCLIB lyrics in background
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = lyricsRepo.fetchLyrics(song.title, song.artist)
            currentLyrics = result.syncedLines
        }
    }

    private fun checkStoragePermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadLocalMusic()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun loadLocalMusic() {
        localSongs = repo.loadLocalSongs()
    }
}

// ----------------- UI COMPOSABLES -----------------

@Composable
fun ExploreScreen(
    searchResults: List<Song>,
    onSearch: (String) -> Unit,
    onSongSelected: (Song) -> Unit
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Explore & Stream",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "YouTube Music catalog • Ad-free & lossless ready",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.length >= 2) onSearch(it)
            },
            placeholder = { Text("Search songs, artists, or albums...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1E1E26),
                unfocusedContainerColor = Color(0xFF1A1A22),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (searchResults.isNotEmpty()) {
            Text(
                text = "Search Results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchResults) { song ->
                    SongRow(song = song, onClick = { onSongSelected(song) })
                }
            }
        } else {
            Text(
                text = "Trending Vibes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(listOf("Acoustic Glass", "Hyper Focus", "Midnight Lofi", "Indie Wave")) { vibe ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF242432)),
                        modifier = Modifier
                            .size(width = 160.dp, height = 100.dp)
                            .clickable { onSearch(vibe) }
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                            Text(text = vibe, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmartMixesScreen(
    allSongs: List<Song>,
    onSongSelected: (Song) -> Unit
) {
    val smartGen = remember { SmartPlaylistGenerator() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Smart Radios",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Algorithmic mixes generated from your listening history",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(MixVibe.values()) { vibe ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2A)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val mix = smartGen.generateSmartMix(allSongs, vibe)
                            mix.generatedTracks.firstOrNull()?.let { onSongSelected(it) }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF64D2FF), Color(0xFFBF5AF2)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(text = vibe.title, fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(text = vibe.subtitle, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    songs: List<Song>,
    onSongSelected: (Song) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Local Library",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "${songs.size} tracks found on device",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(songs) { song ->
                SongRow(song = song, onClick = { onSongSelected(song) })
            }
        }
    }
}

@Composable
fun SongRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(52.dp)
        ) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = song.title, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            Text(text = song.artist, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color(0xFF64D2FF))
        }
    }
}

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22222E)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(44.dp)
            ) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Text(text = song.artist, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}
