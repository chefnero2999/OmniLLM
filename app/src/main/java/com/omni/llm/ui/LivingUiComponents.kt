package com.omni.llm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.omni.llm.ui.theme.OmniPalette

/**
 * Living Quick Mode Pill for ChatScreen.
 */
@Composable
fun QuickModePill(
    label: String,
    active: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (active) tint.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (active) tint.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        ),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Real Live Voice Mode Overlay powered by Android [SpeechRecognizer].
 * Requests [Manifest.permission.RECORD_AUDIO] at runtime, streams partial speech transcripts,
 * and sends the captured speech to the active model.
 */
@Composable
fun LiveVoiceOverlay(
    onDismiss: () -> Unit,
    onSendQuery: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isListening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Tap mic to speak") }

    val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember {
        if (speechAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    fun startListening() {
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        statusText = "Listening..."
        isListening = true
        rec.startListening(intent)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted && speechAvailable) {
            startListening()
        } else if (!granted) {
            statusText = "Microphone permission denied — type below or grant mic access"
        }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusText = "Listening..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                statusText = "Processing..."
            }
            override fun onError(error: Int) {
                isListening = false
                statusText = "Tap mic to try again (code $error)"
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    transcript = text
                    statusText = "Captured — tap Send or re-record"
                } else {
                    statusText = "Didn't catch that — tap mic to retry"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) transcript = partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose {
            runCatching { recognizer?.destroy() }
        }
    }

    LaunchedEffect(Unit) {
        if (!speechAvailable) {
            statusText = "Speech service unavailable on this device — type below"
        } else if (!hasMicPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListening()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "VoiceAuraTransition")
    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(animation = tween(1800), repeatMode = RepeatMode.Reverse),
        label = "PulseScale1"
    )
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1.1f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(animation = tween(2200), repeatMode = RepeatMode.Reverse),
        label = "PulseScale2"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE0B0F17)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close voice mode", tint = Color.White)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    "Live Voice",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmniPalette.info,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(36.dp))

                Box(modifier = Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .scale(if (!isListening) 1f else pulseScale2)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        OmniPalette.geminiPurple.copy(alpha = if (!isListening) 0.08f else 0.25f),
                                        Color.Transparent,
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .scale(if (!isListening) 1f else pulseScale1)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        OmniPalette.geminiBlue.copy(alpha = if (!isListening) 0.18f else 0.45f),
                                        OmniPalette.geminiPurple.copy(alpha = 0.2f),
                                        Color.Transparent,
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(OmniPalette.geminiBlue, OmniPalette.geminiPurple, OmniPalette.ok)
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Recognized speech appears here...") },
                    shape = RoundedCornerShape(14.dp),
                    maxLines = 3,
                )

                Spacer(Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledIconButton(
                        onClick = {
                            if (!speechAvailable) return@FilledIconButton
                            if (!hasMicPermission) {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (isListening) {
                                recognizer?.stopListening()
                                isListening = false
                            } else {
                                startListening()
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening) OmniPalette.danger else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Toggle mic",
                            tint = Color.White,
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (transcript.isNotBlank()) {
                                onSendQuery(transcript.trim())
                                onDismiss()
                            }
                        },
                        enabled = transcript.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (transcript.isNotBlank()) OmniPalette.ok else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (transcript.isNotBlank()) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice prompt")
                    }
                }
            }
        }
    }
}
