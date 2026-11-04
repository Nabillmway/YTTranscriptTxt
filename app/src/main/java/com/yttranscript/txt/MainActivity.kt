package com.yttranscript.txt

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class TranscriptLine(
    val startSeconds: Double,
    val durationSeconds: Double,
    val text: String
)

data class VideoTranscriptResult(
    val videoId: String,
    val title: String,
    val languageCode: String,
    val lines: List<TranscriptLine>,
    val error: String? = null
) {
    fun formatText(includeTimestamps: Boolean): String {
        if (error != null) return "Erreur ($videoId) : $error"
        return buildString {
            appendLine("=== $title ($videoId) [Langue: $languageCode] ===")
            appendLine()
            for (line in lines) {
                if (includeTimestamps) {
                    val totalSec = line.startSeconds.toInt()
                    val minutes = totalSec / 60
                    val seconds = totalSec % 60
                    append(String.format("[%02d:%02d] ", minutes, seconds))
                }
                appendLine(line.text)
            }
        }.trim()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        } else {
            ""
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TranscriptAppScreen(initialInput = sharedText)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptAppScreen(initialInput: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlsInput by remember { mutableStateOf(initialInput) }
    var preferredLang by remember { mutableStateOf("fr") }
    var includeTimestamps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<VideoTranscriptResult>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("") }

    val combinedTranscriptText = remember(results, includeTimestamps) {
        results.joinToString("\n\n" + "=".repeat(40) + "\n\n") {
            it.formatText(includeTimestamps)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null && combinedTranscriptText.isNotBlank()) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(combinedTranscriptText)
                    }
                }
                Toast.makeText(context, "Fichier .txt enregistré avec succès !", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur d'écriture : ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YouTube Transcript -> .TXT", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = urlsInput,
                onValueChange = { urlsInput = it },
                label = { Text("Liens YouTube (un lien par ligne)") },
                placeholder = { Text("https://www.youtube.com/watch?v=...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 150.dp),
                maxLines = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = preferredLang,
                    onValueChange = { preferredLang = it.trim().lowercase() },
                    label = { Text("Langue (fr, en...)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1.3f)
                ) {
                    Switch(
                        checked = includeTimestamps,
                        onCheckedChange = { includeTimestamps = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Horodatages [MM:SS]", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    val ids = YouTubeTranscriptExtractor.extractVideoIds(urlsInput)
                    if (ids.isEmpty()) {
                        Toast.makeText(context, "Veuillez coller au moins un lien YouTube valide.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    statusMessage = "Extraction en cours pour ${ids.size} vidéo(s)..."
                    scope.launch {
                        val extracted = ids.map { id ->
                            YouTubeTranscriptExtractor.fetchTranscript(id, preferredLang)
                        }
                        results = extracted
                        isLoading = false
                        val okCount = extracted.count { it.error == null }
                        statusMessage = "$okCount/${extracted.size} transcription(s) récupérée(s)."
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extraction...")
                } else {
                    Text("Générer la transcription")
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (results.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val firstId = results.firstOrNull()?.videoId ?: "youtube"
                            val fileName = "transcription_${firstId}.txt"
                            saveDirectlyToDownloads(context, fileName, combinedTranscriptText)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Télécharger .TXT (Téléchargements)")
                    }

                    OutlinedButton(
                        onClick = {
                            val firstId = results.firstOrNull()?.videoId ?: "youtube"
                            createDocumentLauncher.launch("transcription_${firstId}.txt")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Enregistrer sous...")
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    SelectionContainer {
                        Text(
                            text = combinedTranscriptText,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

fun saveDirectlyToDownloads(context: Context, fileName: String, content: String) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain; charset=utf-8")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                        writer.write(content)
                    }
                }
                Toast.makeText(
                    context,
                    "Enregistré dans Téléchargements : $fileName",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            Toast.makeText(
                context,
                "Enregistré : ${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Erreur lors de l'enregistrement : ${e.localizedMessage}",
            Toast.LENGTH_LONG
        ).show()
    }
}

object YouTubeTranscriptExtractor {

    private val VIDEO_ID_REGEX = Pattern.compile(
        "(?:v=|youtu\\.be/|shorts/|embed/)([a-zA-Z0-9_-]{11})"
    )

    fun extractVideoIds(rawText: String): List<String> {
        val ids = mutableListOf<String>()
        val matcher = VIDEO_ID_REGEX.matcher(rawText)
        while (matcher.find()) {
            val id = matcher.group(1)
            if (id != null && !ids.contains(id)) {
                ids.add(id)
            }
        }
        if (ids.isEmpty() && rawText.trim().matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            ids.add(rawText.trim())
        }
        return ids
    }

    suspend fun fetchTranscript(videoId: String, preferredLang: String): VideoTranscriptResult =
        withContext(Dispatchers.IO) {
            try {
                val playerJson = fetchPlayerResponse(videoId)
                val videoDetails = playerJson.optJSONObject("videoDetails")
                val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() } ?: "Vidéo YouTube"

                val tracklistRenderer = playerJson
                    .optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                val captionTracks = tracklistRenderer?.optJSONArray("captionTracks")

                if (captionTracks == null || captionTracks.length() == 0) {
                    return@withContext VideoTranscriptResult(
                        videoId = videoId,
                        title = title,
                        languageCode = "",
                        lines = emptyList(),
                        error = "Aucun sous-titre ou transcription disponible sur cette vidéo."
                    )
                }

                val selectedTrack = selectBestTrack(captionTracks, preferredLang)
                val rawBaseUrl = selectedTrack.getString("baseUrl")
                val origLang = selectedTrack.optString("languageCode", preferredLang)
        val needsTranslation = preferredLang.isNotBlank() && !origLang.lowercase().startsWith(preferredLang.lowercase())
        val baseUrl = if (needsTranslation) "$rawBaseUrl&tlang=$preferredLang" else rawBaseUrl
        val langCode = if (needsTranslation) "$origLang->$preferredLang" else origLang

                val xmlContent = httpGet(baseUrl)
                val parsedLines = parseTranscriptXml(xmlContent)

                VideoTranscriptResult(
                    videoId = videoId,
                    title = title,
                    languageCode = langCode,
                    lines = parsedLines
                )
            } catch (e: Exception) {
                VideoTranscriptResult(
                    videoId = videoId,
                    title = "Vidéo YouTube",
                    languageCode = "",
                    lines = emptyList(),
                    error = e.localizedMessage ?: "Erreur réseau ou analyse impossible"
                )
            }
        }

    private fun fetchPlayerResponse(videoId: String): JSONObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        val url = URL(endpoint)
        val ua = listOf("com", "google", "android", "youtube").joinToString(".") + "/" + "19.09.37 (Linux; U; Android 14) gzip"
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", ua)
            connectTimeout = 15000
            readTimeout = 15000
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 30)
                    put("userAgent", ua) 
                    put("hl", "en")
                put("gl", "US")
            })
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val responseText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
            it.readText()
        }
        return JSONObject(responseText)
    }

    private fun selectBestTrack(tracks: JSONArray, preferredLang: String): JSONObject {
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val lang = track.optString("languageCode", "").lowercase()
            if (lang == preferredLang || lang.startsWith("$preferredLang-")) {
                return track
            }
        }
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val lang = track.optString("languageCode", "").lowercase()
            if (lang.startsWith("en")) {
                return track
            }
        }
        return tracks.getJSONObject(0)
    }

    private fun httpGet(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
        }
        return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
            it.readText()
        }
    }

    private fun parseTranscriptXml(xml: String): List<TranscriptLine> {
        val lines = mutableListOf<TranscriptLine>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var currentStart = 0.0
        var currentDur = 0.0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "text" || parser.name == "p") {
                        val startAttr = parser.getAttributeValue(null, "start")
                            ?: parser.getAttributeValue(null, "t")?.let { (it.toDoubleOrNull() ?: 0.0) / 1000.0 }?.toString()
                        val durAttr = parser.getAttributeValue(null, "dur")
                            ?: parser.getAttributeValue(null, "d")?.let { (it.toDoubleOrNull() ?: 0.0) / 1000.0 }?.toString()

                        currentStart = startAttr?.toDoubleOrNull() ?: 0.0
                        currentDur = durAttr?.toDoubleOrNull() ?: 0.0

                        val rawText = parser.nextText()
                        val decoded = Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .replace("\n", " ")
                            .trim()
                        if (decoded.isNotEmpty()) {
                            lines.add(TranscriptLine(currentStart, currentDur, decoded))
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return lines
    }
}
