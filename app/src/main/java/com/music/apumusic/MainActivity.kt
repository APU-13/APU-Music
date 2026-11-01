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
