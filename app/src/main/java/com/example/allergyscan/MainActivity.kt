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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
        setContent { AllergyOCRApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllergyOCRApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Terms state (persisted via DataStore) ---
    var terms by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { TermStore.termsFlow(context).collectLatest { terms = it } }

    // Text-based editor (one term per line)
    var termsText by remember { mutableStateOf("") }
    var termsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(terms) {
        if (!termsLoaded) {
            termsText = terms.joinToString("\n")
            termsLoaded = true
        }
    }

    // --- OCR state ---
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var recognized by remember { mutableStateOf<Text?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ---- Pick from gallery (Photo Picker) ----
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                selectedImageUri = uri
                runOcr(
                    uri,
                    onStart = { isProcessing = true; errorMessage = null },
                    onDone = { isProcessing = false; recognized = it },
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
                selectedImageUri = uri
                runOcr(
                    uri,
                    onStart = { isProcessing = true; errorMessage = null },
                    onDone = { isProcessing = false; recognized = it },
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
                                "1) Edit terms  2) Pick/Take photo  3) Matches are highlighted",
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions.Default
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
                        scope.launch { TermStore.saveTerms(context, updated) }
                    }) { Text("Save terms") }

                    OutlinedButton(onClick = {
                        termsText = ""
                        scope.launch { TermStore.saveTerms(context, emptySet()) }
                    }) { Text("Clear all") }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

                // ---- Results summary + highlighted text ----
                recognized?.let { text ->
                    val recognizedStr = text.text
                    val currentTerms = remember(termsText) {
                        termsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                    val (highlighted, matchCount) = remember(recognizedStr, currentTerms) {
                        highlightAndCount(recognizedStr, currentTerms)
                    }

                    val summary = if (matchCount > 0) "Matches found: $matchCount" else "No matches"
                    Text(summary, style = MaterialTheme.typography.titleSmall)

                    Text(
                        highlighted,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                            .background(Color(0xFFF5F5F5), shape = MaterialTheme.shapes.small)
                            .padding(12.dp)
                    )
                } ?: Text(
                    "Pick or take a photo of the ingredients list. We'll OCR the text and highlight matches.\n" +
                    "Edit your terms above; save to persist.",
                    color = Color.Gray
                )
            }
        }
    }
}

// Build highlighted text and return total match count
private fun highlightAndCount(text: String, terms: Set<String>): Pair<AnnotatedString, Int> {
    val escaped = terms.filter { it.isNotBlank() }
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    if (escaped.isBlank()) return AnnotatedString(text) to 0

    val regex = Regex(escaped, RegexOption.IGNORE_CASE)
    var last = 0
    var count = 0
    val out = buildAnnotatedString {
        for (m in regex.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > last) append(text.substring(last, start))
            withStyle(SpanStyle(background = Color.Yellow, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(start, end))
            }
            last = end
            count++
        }
        if (last < text.length) append(text.substring(last))
    }
    return out to count
}

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
