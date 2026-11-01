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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.music.apumusic.data.MediaStoreRepository
import com.music.apumusic.data.Song
import com.music.apumusic.ui.screens.SettingsScreen
import com.music.apumusic.ui.theme.APUMusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

// ==================== MODELS & ENUMS ====================

data class LrcLine(
    val timestampMs: Long,
    val text: String
)

enum class MixVibe(val title: String, val subtitle: String) {
    CHILL_GLASS("Liquid Chill", "Mellow acoustic & ambient soundscapes"),
    ENERGY_BURST("High Voltage", "Dynamic uptempo beats to elevate focus"),
    DEEP_CUTS("Deep Cuts Radar", "Lesser-known gems from your favourite artists"),
    NOSTALGIA_REPLAY("Memory Replay", "Tracks that defined your most frequent listening"),
    GENRE_DNA("Genre Fusion", "Algorithmic harmony across your top genres")
}

data class SmartMix(
    val vibe: MixVibe,
    val seedArtist: String,
    val generatedTracks: List<Song>
)

// ==================== SELF-CONTAINED ENGINES ====================

class YouTubeMusicClient {
    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val resultSongs = mutableListOf<Song>()
        try {
            val url = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.doOutput = true

            val jsonPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241031.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                put("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%3D")
            }

            conn.outputStream.use { os ->
                os.write(jsonPayload.toString().toByteArray())
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseText = reader.readText()
                reader.close()

                val root = JSONObject(responseText)
                val contents = root.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (contents != null) {
                    for (i in 0 until contents.length()) {
                        val section = contents.optJSONObject(i)?.optJSONObject("musicShelfRenderer")
                        val items = section?.optJSONArray("contents") ?: continue
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val videoId = item.optJSONArray("flexColumns")
                                ?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId") ?: continue

                            val title = item.optJSONArray("flexColumns")
                                ?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text") ?: "YouTube Track"

                            val artistRuns = item.optJSONArray("flexColumns")
                                ?.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                            val artist = artistRuns?.optJSONObject(0)?.optString("text") ?: "YouTube Music"
                            val thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                            resultSongs.add(
                                Song(
                                    id = videoId.hashCode().toLong(),
                                    title = title,
                                    artist = artist,
                                    album = "YouTube Music Stream",
                                    durationMs = 210000L,
                                    contentUri = Uri.parse("https://www.youtube.com/watch?v=$videoId"),
                                    albumArtUri = Uri.parse(thumbnail)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        resultSongs
    }
}

class LyricsClient {
    private val timePattern = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    suspend fun fetchLyrics(track: String, artist: String): List<LrcLine> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<LrcLine>()
        try {
            val encodedTrack = URLEncoder.encode(track, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = URL("https://lrclib.net/api/get?track_name=$encodedTrack&artist_name=$encodedArtist")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "APUMusic/1.9 (Android; +apurbam59@gmail.com)")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val body = reader.readText()
                reader.close()

                val json = JSONObject(body)
                val syncedLrc = json.optString("syncedLyrics", "")
                if (syncedLrc.isNotBlank()) {
                    syncedLrc.lines().forEach { rawLine ->
                        val matcher = timePattern.matcher(rawLine.trim())
                        if (matcher.matches()) {
                            val min = matcher.group(1)?.toLongOrNull() ?: 0L
                            val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                            val msStr = matcher.group(3) ?: "0"
                            val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                            val text = matcher.group(4)?.trim() ?: ""
                            val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                            lines.add(LrcLine(totalMs, text))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        lines.sortedBy { it.timestampMs }
    }
}

class SmartMixGenerator {
    fun generateMix(allSongs: List<Song>, vibe: MixVibe): SmartMix {
        if (allSongs.isEmpty()) {
            return SmartMix(vibe, "APU Selection", emptyList())
        }
        val artist = allSongs.groupBy { it.artist }.maxByOrNull { it.value.size }?.key ?: "APU Selection"
        val selected = when (vibe) {
            MixVibe.CHILL_GLASS -> allSongs.filter { it.durationMs > 180000 }.shuffled().take(20)
            MixVibe.ENERGY_BURST -> allSongs.filter { it.durationMs in 120000..240000 }.shuffled().take(20)
            MixVibe.DEEP_CUTS -> allSongs.filter { it.artist.contains(artist, ignoreCase = true) }.takeLast(15).ifEmpty { allSongs.shuffled().take(15) }
            MixVibe.NOSTALGIA_REPLAY -> allSongs.shuffled().take(25)
            MixVibe.GENRE_DNA -> allSongs.shuffled().take(25)
        }
        return SmartMix(vibe, artist, selected)
    }
}

// ==================== MAIN ACTIVITY ====================

class MainActivity : ComponentActivity() {

    private lateinit var repo: MediaStoreRepository
    private val ytClient = YouTubeMusicClient()
    private val lyricsClient = LyricsClient()
    private val smartMixGen = SmartMixGenerator()

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
                                currentSong?.let { song ->
                                    MiniPlayer(
                                        song = song,
                                        isPlaying = isPlaying,
                                        onPlayPause = { isPlaying = !isPlaying },
                                        onClick = { showFullNowPlaying = true }
                                    )
                                }
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
                                            searchResults = ytClient.searchSongs(query)
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
                                    smartMixGen = smartMixGen,
                                    onSongSelected = { song -> playSong(song) }
                                )
                                3 -> SettingsScreen()
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showFullNowPlaying,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        NowPlayingSheetView(
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

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val lyrics = lyricsClient.fetchLyrics(song.title, song.artist)
            currentLyrics = lyrics
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

// ==================== SCREEN COMPOSABLES ====================

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
    smartMixGen: SmartMixGenerator,
    onSongSelected: (Song) -> Unit
) {
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
                            val mix = smartMixGen.generateMix(allSongs, vibe)
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

@Composable
fun NowPlayingSheetView(
    song: Song?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    syncedLyrics: List<LrcLine>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit
) {
    if (song == null) return

    var showLyrics by remember { mutableStateOf(false) }
    var showStatsForNerds by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1E1E2E).copy(alpha = 0.98f), Color(0xFF0F0F14))
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse", tint = Color.White)
                }
                Text(
                    text = "Playing from APU Music",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium
                )
                IconButton(onClick = { showStatsForNerds = !showStatsForNerds }) {
                    Icon(Icons.Default.Info, contentDescription = "Stats for Nerds", tint = Color.White.copy(alpha = 0.7f))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showLyrics && syncedLyrics.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(syncedLyrics) { line ->
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(vertical = 12.dp)
                                    .clickable { onSeekTo(line.timestampMs) }
                            )
                        }
                    }
                } else {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                        modifier = Modifier
                            .size(300.dp)
                            .clip(RoundedCornerShape(24.dp))
                    ) {
                        AsyncImage(
                            model = song.albumArtUri,
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        imageVector = Icons.Default.ChatBubble,
                        contentDescription = "Toggle Lyrics",
                        tint = if (showLyrics) Color(0xFF64D2FF) else Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Slider(
                value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                onValueChange = { factor -> onSeekTo((factor * durationMs).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF64D2FF),
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}
