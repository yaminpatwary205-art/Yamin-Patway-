package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.firebase.onGoogleSignInClicked
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.GlassCard

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onContinueWithoutLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pulseTransition = rememberInfiniteTransition(label = "logo_pulse")
    val logoScale by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    AnimatedMeshBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onContinueWithoutLogin,
                    enabled = !isLoading,
                    modifier = Modifier.testTag("skip_login_top_button")
                ) {
                    Text("Skip • স্কিপ", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            Image(
                painter = painterResource(id = R.drawable.ic_revision_alarm_1795004301502),
                contentDescription = "Revision Alarm Logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(116.dp)
                    .scale(logoScale)
                    .clip(RoundedCornerShape(30.dp))
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "রিভিশন অ্যালার্ম",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "অ্যালার্মের মতো নির্দিষ্ট সময় পরপর ফুল-স্ক্রিন পড়া রিভিশন, ইংরেজি প্যারাগ্রাফ ও ভোকাবুলারি প্র্যাকটিস",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 460.dp)
            )

            Spacer(Modifier.height(28.dp))

            GlassCard(modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureRow(
                        icon = Icons.Rounded.Alarm,
                        title = "স্মার্ট রিভিশন অ্যালার্ম",
                        subtitle = "১১টি বই, অধ্যায় ও নোট অনুযায়ী আলাদা টাইমার এবং রাতের বিরতি"
                    )
                    FeatureRow(
                        icon = Icons.Rounded.CloudDone,
                        title = "ক্লাউড সিঙ্ক ও অফলাইন মোড",
                        subtitle = "Google দিয়ে সাইন-ইন করলে সব ডিভাইসে অটো ব্যাকআপ ও সিঙ্ক"
                    )
                    FeatureRow(
                        icon = Icons.Rounded.OfflineBolt,
                        title = "তাৎক্ষণিক বাংলা অর্থ (AI)",
                        subtitle = "ইংরেজি লাইন বা শব্দ প্রদর্শনের মুহূর্তে সহজ বাংলা অর্থ ও অনুশীলন"
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            AnimatedVisibility(visible = errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    onGoogleSignInClicked(
                        context = context,
                        credentialManager = credentialManager,
                        scope = scope,
                        onAuthSuccess = {
                            isLoading = false
                            onAuthSuccess()
                        },
                        onAuthError = { msg ->
                            isLoading = false
                            errorMessage = msg
                        },
                        onAuthCancelled = {
                            isLoading = false
                        }
                    )
                },
                enabled = !isLoading,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_sign_in_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("সাইন-ইন হচ্ছে...")
                } else {
                    Text("Google দিয়ে সাইন-ইন করুন", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onContinueWithoutLogin,
                enabled = !isLoading,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("continue_local_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Skip — অ্যাকাউন্ট ছাড়াই চালিয়ে যান", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Google অ্যাকাউন্ট না থাকলেও Skip করে সব ফিচার ব্যবহার করতে পারবেন।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
