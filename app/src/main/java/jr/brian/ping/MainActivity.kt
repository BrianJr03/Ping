package jr.brian.ping // TODO: replace with your app package

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

private val BgDark = Color(0xFF0A0A0F)
private val Surface1 = Color(0xFF13131A)
private val Surface2 = Color(0xFF1C1C27)
private val AccentBlue = Color(0xFF4FC3F7)
private val TextPrimary = Color(0xFFE8EAF6)
private val TextMuted = Color(0xFF6B7280)
private val ActiveGreen = Color(0xFF00E676)
private val ErrorRed = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Handled reactively in the UI via checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PingScreen(
                    context = this,
                    onRequestPermissions = { requestBlePermissions() },
                    onRequestBatteryOptimization = { requestBatteryExemption() }
                )
            }
        }
    }

    private fun requestBlePermissions() {
        val perms =
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        permissionLauncher.launch(perms)
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        startActivity(intent)
    }
}

@Composable
fun PingScreen(
    context: Context,
    onRequestPermissions: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    val context = LocalContext.current
    var userId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }

    val hasPermissions = remember { mutableStateOf(checkBlePermissions(context)) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )


    LaunchedEffect(Unit) {
        PingService.onEncounter = { address, profile ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Pinged ${profile.displayName}: ${profile.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "PING",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 12.sp,
                    color = AccentBlue
                )
                Text(
                    text = "proximity exchange",
                    fontSize = 12.sp,
                    letterSpacing = 4.sp,
                    color = TextMuted
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(ActiveGreen.copy(alpha = 0.15f))
                    )
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) ActiveGreen.copy(alpha = 0.2f)
                            else Surface2
                        )
                        .border(
                            width = 2.dp,
                            color = if (isRunning) ActiveGreen else TextMuted.copy(alpha = 0.3f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRunning) "●" else "○",
                        fontSize = 24.sp,
                        color = if (isRunning) ActiveGreen else TextMuted
                    )
                }
            }

            AnimatedContent(
                targetState = isRunning,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "statusText"
            ) { running ->
                Text(
                    text = if (running) "Broadcasting · Scanning" else "Idle",
                    fontSize = 13.sp,
                    color = if (running) ActiveGreen else TextMuted,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (!hasPermissions.value) {
                WarningCard(
                    message = "Bluetooth permissions are required",
                    actionLabel = "Grant",
                    onAction = {
                        onRequestPermissions()
                        hasPermissions.value = checkBlePermissions(context)
                    }
                )
            }

            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                WarningCard(
                    message = "Disable battery optimization for reliable background scanning",
                    actionLabel = "Fix",
                    onAction = onRequestBatteryOptimization
                )
            }

            PingTextField(
                value = userId,
                onValueChange = { userId = it },
                label = "User ID",
                placeholder = "e.g. player_001",
                enabled = !isRunning
            )
            PingTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display Name",
                placeholder = "What others will see",
                enabled = !isRunning
            )
            PingTextField(
                value = message,
                onValueChange = { if (it.length <= 120) message = it },
                label = "Message",
                placeholder = "A greeting for nearby players…",
                enabled = !isRunning,
                singleLine = false,
                minLines = 3
            )

            Text(
                text = "${message.length}/120",
                fontSize = 11.sp,
                color = TextMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.height(4.dp))

            val canStart = hasPermissions.value
                    && userId.isNotBlank()
                    && displayName.isNotBlank()

            Button(
                onClick = {
                    if (isRunning) {
                        context.stopService(Intent(context, PingService::class.java))
                        isRunning = false
                    } else {
                        if (!canStart) return@Button
                        val intent = PingService.buildIntent(
                            context = context,
                            userId = userId.trim(),
                            displayName = displayName.trim(),
                            message = message.trim()
                        )
                        context.startForegroundService(intent)
                        isRunning = true
                    }
                },
                enabled = isRunning || canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) ErrorRed.copy(alpha = 0.15f) else AccentBlue.copy(
                        alpha = 0.15f
                    ),
                    contentColor = if (isRunning) ErrorRed else AccentBlue,
                    disabledContainerColor = Surface2,
                    disabledContentColor = TextMuted
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isRunning) ErrorRed.copy(alpha = 0.5f)
                    else if (canStart) AccentBlue.copy(alpha = 0.5f)
                    else TextMuted.copy(alpha = 0.2f)
                )
            ) {
                Text(
                    text = if (isRunning) "STOP PING" else "START PING",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            }
        }
    }
}

@Composable
private fun PingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = TextMuted, fontSize = 13.sp) },
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = Surface2,
            disabledBorderColor = Surface2.copy(alpha = 0.5f),
            focusedLabelColor = AccentBlue,
            unfocusedLabelColor = TextMuted,
            focusedContainerColor = Surface1,
            unfocusedContainerColor = Surface1,
            disabledContainerColor = Surface1.copy(alpha = 0.5f),
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextMuted
        )
    )
}

@Composable
private fun WarningCard(
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFFFF6F00).copy(alpha = 0.12f),
                        Color(0xFFFF6F00).copy(alpha = 0.06f)
                    )
                )
            )
            .border(
                1.dp,
                Color(0xFFFF6F00).copy(alpha = 0.3f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = Color(0xFFFFB74D),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = actionLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB74D)
            )
        }
    }
}

private fun checkBlePermissions(context: Context): Boolean {
    return listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}