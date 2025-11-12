package com.example.allergyscan

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.allergyscan.data.TermStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import androidx.activity.result.PickVisualMediaRequest

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

    var terms by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { TermStore.termsFlow(context).collectLatest { terms = it } }
    var newTerm by remember { mutableStateOf("") }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var recognized by remember { mutableStateOf<Text?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                selectedImageUri = uri
                runOcr(uri, onStart = { isProcessing = true; errorMessage = null }, onDone = {
                    isProcessing = false; recognized = it
                }, onError = { e ->
                    isProcessing = false; errorMessage = e.message
                })
            }
        }
    )

    var tempCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { ok ->
            if (ok && tempCaptureUri != null) {
                val uri = tempCaptureUri!!
                selectedImageUri = uri
                runOcr(uri, onStart = { isProcessing = true; errorMessage = null }, onDone = {
                    isProcessing = false; recognized = it
                }, onError = { e ->
                    isProcessing = false; errorMessage = e.message
                })
            }
        }
    )

    fun createImageUri(): Uri? {
        val resolver = context.contentResolver
        val name = "allergy_ocr_${Instant.now().toEpochMilli()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Allergy OCR (Prototype)") }) }) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(12.dp)
                    .fillMaxSize()
            ) {
                Text("Search terms", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = newTerm,
                        onValueChange = { newTerm = it },
                        label = { Text("Add term (e.g., “barley flour”)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val t = newTerm.trim()
                            if (t.isNotEmpty()) {
                                val updated = (terms + t).toSet()
                                scope.launch { TermStore.saveTerms(context, updated) }
                                newTerm = ""
                            }
                        })
                    )
                    Button(onClick = {
                        val t = newTerm.trim()
                        if (t.isNotEmpty()) {
                            val updated = (terms + t).toSet()
                            scope.launch { TermStore.saveTerms(context, updated) }
                            newTerm = ""
                        }
                    }) { Text("Add") }
                }
                Spacer(Modifier.height(6.dp))

                // Show terms list
                FlowList(terms = terms)

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Pick photo") }
                    Button(onClick = {
                        val uri = createImageUri()
                        if (uri != null) {
                            tempCaptureUri = uri
                            takePicture.launch(uri)
                        }
                    }) { Text("Take photo") }
                }

                Spacer(Modifier.height(12.dp))

                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Processing…")
                }
                errorMessage?.let { Text("Error: " + it, color = Color(0xFFD32F2F)) }

                recognized?.let { text ->
                    val recognizedStr = text.text
                    val highlighted = remember(recognizedStr, terms) {
                        buildHighlighted(recognizedStr, terms)
                    }
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
                    "Pick or take a photo of the ingredients list. We'll OCR the text and highlight matches.",
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun FlowList(terms: Set<String>) {
    val chips = terms.sortedBy { it.lowercase() }
    Column {
        Row(Modifier.fillMaxWidth()) {
            chips.forEach { term ->
                AssistChip(
                    onClick = {},
                    label = { Text(term) },
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    enabled = false
                )
            }
        }
    }
}

private fun buildHighlighted(text: String, terms: Set<String>) = buildAnnotatedString {
    val escaped = terms.filter { it.isNotBlank() }
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    if (escaped.isBlank()) { append(text); return@buildAnnotatedString }
    val regex = Regex(escaped, RegexOption.IGNORE_CASE)

    var last = 0
    for (m in regex.findAll(text)) {
        val start = m.range.first
        val end = m.range.last + 1
        if (start > last) append(text.substring(last, start))
        withStyle(SpanStyle(background = Color.Yellow, fontWeight = FontWeight.SemiBold)) {
            append(text.substring(start, end))
        }
        last = end
    }
    if (last < text.length) append(text.substring(last))
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
