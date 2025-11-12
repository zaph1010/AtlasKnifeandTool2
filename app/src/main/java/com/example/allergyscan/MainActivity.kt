package com.example.allergyscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.allergyscan.data.TermStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

// ---- File-scope helper: create a FileProvider-backed temp photo URI ----
fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("capture_", ".jpg", dir)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AllergenOCRApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllergenOCRApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Persisted terms (source of truth) ---
    var terms by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        TermStore.termsFlow(context).collectLatest { loaded ->
            terms = loaded
        }
    }

    // Text editor mirrors persisted terms
    var termsText by remember { mutableStateOf("") }
    LaunchedEffect(terms) { termsText = terms.joinToString("\n") }

    // --- OCR/UI state ---
    var recognized by remember { mutableStateOf<Text?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ---- Pick from gallery (Photo Picker) ----
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                runOcr(
                    uri,
                    onStart = { isProcessing = true; errorMessage = null },
                    onDone = { result -> isProcessing = false; recognized = result },
                    onError = { e -> isProcessing = false; errorMessage = e.message }
                )
            }
        }
    )

    // ---- Take picture (to our FileProvider URI) ----
    var tempCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { ok ->
            if (ok && tempCaptureUri != null) {
                val uri = tempCaptureUri!!
                runOcr(
                    uri,
                    onStart = { isProcessing = true; errorMessage = null },
                    onDone = { result -> isProcessing = false; recognized = result },
                    onError = { e -> isProcessing = false; errorMessage = e.message }
                )
            }
        }
    )

    // ---- Runtime CAMERA permission ----
    val requestCameraPermission = rememberLauncherForActivityResult(
        contract = RequestPermission(),
        onResult = { granted ->
            if (granted) {
                try {
                    val uri = createTempImageUri(context)
                    tempCaptureUri = uri
                    takePicture.launch(uri)
                } catch (e: Exception) {
                    errorMessage = "Could not prepare camera destination: ${e.localizedMessage}"
                }
            } else {
                errorMessage = "Camera permission denied."
            }
        }
    )

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Allergen OCR")
                            Text(
                                "1) Edit terms  2) Pick/Take photo  3) Use Next to jump to matches",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(12.dp)
                    .fillMaxSize()
            ) {
                // ---- Text-based terms editor ----
                Text("Allergen terms (one per line)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    value = termsText,
                    onValueChange = { termsText = it },
                    placeholder = { Text("barley\nbarley flour\nmalted barley\nbeer") },
                    singleLine = false,
                    minLines = 5,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions.Default
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        val updated = termsText
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                        terms = updated                         // update UI immediately
                        scope.launch { TermStore.saveTerms(context, updated) }  // persist
                    }) { Text("Save terms") }

                    OutlinedButton(onClick = {
                        termsText = ""
                        terms = emptySet()
                        scope.launch { TermStore.saveTerms(context, emptySet()) }
                    }) { Text("Clear all") }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("Pick photo") }

                    Button(onClick = {
                        val camPerm = Manifest.permission.CAMERA
                        val granted = ContextCompat.checkSelfPermission(context, camPerm) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            try {
                                val uri = createTempImageUri(context)
                                tempCaptureUri = uri
                                takePicture.launch(uri)
                            } catch (e: Exception) {
                                errorMessage = "Could not prepare camera destination: ${e.localizedMessage}"
                            }
                        } else {
                            requestCameraPermission.launch(camPerm)
                        }
                    }) { Text("Take photo") }
                }

                Spacer(Modifier.height(12.dp))

                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Processing…")
                }
                errorMessage?.let { Text("Error: $it", color = Color(0xFFD32F2F)) }

                // ---- Results summary + highlighted text with navigation ----
                recognized?.let { text ->
                    val recognizedStr = text.text

                    // Build regex from PERSISTED terms (not the editor text)
                    val currentTerms = terms
                    val regex = remember(currentTerms) {
                        val escaped = currentTerms
                            .filter { it.isNotBlank() }
                            .sortedByDescending { it.length }
                            .joinToString("|") { Regex.escape(it) }
                        if (escaped.isBlank()) null else Regex(escaped, RegexOption.IGNORE_CASE)
                    }

                    val lines = remember(recognizedStr) { recognizedStr.lines() }

                    // Precompute matches across the whole text
                    val matchPositions = remember(recognizedStr, regex) {
                        if (regex == null) emptyList()
                        else regex.findAll(recognizedStr).map { it.range.first }.toList()
                    }
                    val matchCount = matchPositions.size

                    fun posToLineIndex(pos: Int): Int {
                        var acc = 0
                        lines.forEachIndexed { idx, ln ->
                            val end = acc + ln.length
                            if (pos <= end) return idx
                            acc = end + 1 // +1 for newline
                        }
                        return lines.lastIndex
                    }
                    val matchLineIndices = remember(matchPositions, lines) {
                        matchPositions.map { posToLineIndex(it) }
                    }

                    // Navigation state
                    val listState = rememberLazyListState()
                    var currentMatchIdx by remember { mutableStateOf(0) }
                    LaunchedEffect(recognizedStr, currentTerms) { currentMatchIdx = 0 }

                    // Summary + Nav Buttons
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (matchCount > 0) "Matches found: $matchCount" else "No matches",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val hasMatches = matchCount > 0
                            OutlinedButton(
                                onClick = {
                                    if (hasMatches) {
                                        currentMatchIdx = 0
                                        scope.launch {
                                            listState.animateScrollToItem(matchLineIndices[0])
                                        }
                                    }
                                },
                                enabled = hasMatches
                            ) { Text("First") }

                            OutlinedButton(
                                onClick = {
                                    if (hasMatches) {
                                        currentMatchIdx = (currentMatchIdx - 1 + matchCount) % matchCount
                                        scope.launch {
                                            listState.animateScrollToItem(matchLineIndices[currentMatchIdx])
                                        }
                                    }
                                },
                                enabled = hasMatches
                            ) { Text("Prev") }

                            Button(
                                onClick = {
                                    if (hasMatches) {
                                        currentMatchIdx = (currentMatchIdx + 1) % matchCount
                                        scope.launch {
                                            listState.animateScrollToItem(matchLineIndices[currentMatchIdx])
                                        }
                                    }
                                },
                                enabled = hasMatches
                            ) { Text("Next") }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Render lines with highlights; focused line tinted
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5), shape = MaterialTheme.shapes.small)
                            .padding(12.dp)
                    ) {
                        itemsIndexed(lines) { idx, line ->
                            val (annotated, _) = remember(line, regex) { highlightLine(line, regex) }
                            val isFocused =
                                matchCount > 0 && idx == matchLineIndices.getOrNull(currentMatchIdx)
                            val rowBg = if (isFocused) Color(0xFFFFF9C4) else Color.Transparent
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(rowBg)
                                    .padding(vertical = 2.dp)
                            ) { Text(annotated) }
                        }
                    }
                } ?: Text(
                    "Pick or take a photo of the ingredients list. We'll OCR the text and highlight matches.\n" +
                    "Edit your terms above; Save to persist.",
                    color = Color.Gray
                )
            }
        }
    }
}

// Highlight all term matches within a single line
private fun highlightLine(line: String, regex: Regex?): Pair<AnnotatedString, Int> {
    if (regex == null || line.isEmpty()) return AnnotatedString(line) to 0
    var last = 0
    var count = 0
    val out = buildAnnotatedString {
        for (m in regex.findAll(line)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > last) append(line.substring(last, start))
            withStyle(SpanStyle(background = Color.Yellow, fontWeight = FontWeight.SemiBold)) {
                append(line.substring(start, end))
            }
            last = end
            count++
        }
        if (last < line.length) append(line.substring(last))
    }
    return out to count
}

// Run OCR with explicit callbacks (matches call sites)
private fun runOcr(
    uri: Uri,
    onStart: () -> Unit,
    onDone: (Text) -> Unit,
    onError: (Throwable) -> Unit
) {
    onStart()
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val image = InputImage.fromFilePath(App.instance, uri)
        recognizer.process(image)
            .addOnSuccessListener { result -> onDone(result) }
            .addOnFailureListener { e -> onError(e) }
    } catch (e: Exception) {
        onError(e)
    }
}

// Application class so we have a safe Context for ML Kit file loading
class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    companion object {
        lateinit var instance: App
            private set
    }
}
