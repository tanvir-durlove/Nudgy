package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.MascotMood
import com.example.ui.components.NudgyMascot
import com.example.ui.theme.NudgyBorder
import com.example.ui.theme.NudgyCanvas
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgySage
import com.example.ui.theme.NudgyTheme

/**
 * BuildApkActivity
 * Dedicated activity allowing users to trigger or view the GitHub Actions workflow
 * that builds, signs, and packages the pristine, uncorrupted Android APK.
 */
class BuildApkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NudgyTheme {
                BuildApkScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun BuildApkScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var repoUrl by remember { mutableStateOf("https://github.com") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = NudgyCanvas,
        topBar = {
            Surface(
                color = NudgyCanvas,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .background(NudgyCard, CircleShape)
                            .border(1.dp, NudgyBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NudgyCharcoal
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = "GitHub Actions APK Builder",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NudgyCharcoal
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Mascot Banner
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NudgyPeach),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NudgyMascot(
                        mood = MascotMood.CELEBRATING,
                        size = 56.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Clean & Verified APK",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "GitHub Actions compiles directly on clean Ubuntu runners so your APK is never corrupted by web file downloads.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            color = NudgyMuted
                        )
                    }
                }
            }

            // GitHub Action Run Section
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NudgyCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Run Action in GitHub",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = NudgyCharcoal
                    )

                    Text(
                        text = "Enter your GitHub repository URL or open GitHub Actions to trigger the build workflow:",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = NudgyMuted
                    )

                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        label = { Text("GitHub Repo URL") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NudgyPrimary,
                            unfocusedBorderColor = NudgyBorder
                        )
                    )

                    Button(
                        onClick = {
                            val targetUrl = if (repoUrl.contains("/actions")) {
                                repoUrl
                            } else if (repoUrl.startsWith("http")) {
                                "$repoUrl/actions"
                            } else {
                                "https://github.com"
                            }
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open GitHub Actions in Browser",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }
                }
            }

            // Step-by-Step Instructions
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NudgyCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "How to Build & Download APK",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = NudgyCharcoal
                    )

                    BuildStepItem(
                        step = "1",
                        title = "Push to GitHub",
                        description = "In AI Studio Settings, select 'Push to GitHub' or push via git remote."
                    )

                    BuildStepItem(
                        step = "2",
                        title = "Select Workflow",
                        description = "Go to your GitHub repo → click the 'Actions' tab → select 'Build Nudgy Android APK'."
                    )

                    BuildStepItem(
                        step = "3",
                        title = "Run Workflow & Download",
                        description = "Click 'Run workflow' (or push code). When the run completes, download 'nudgy-debug.apk' from Artifacts or Releases directly to your Android phone!"
                    )
                }
            }

            // Configuration Info
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NudgySage),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NudgyCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Build Pipeline Specifications",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = NudgyCharcoal
                        )
                    }

                    Text("• Workflow File: .github/workflows/build-apk.yml", fontSize = 12.sp, color = NudgyCharcoal)
                    Text("• Runner: Ubuntu Latest (Temurin JDK 21)", fontSize = 12.sp, color = NudgyCharcoal)
                    Text("• Command: ./gradlew assembleDebug", fontSize = 12.sp, color = NudgyCharcoal)
                    Text("• Package: com.aistudio.nudgy.prod", fontSize = 12.sp, color = NudgyCharcoal)
                    Text("• Keystore: Restored from debug.keystore.base64", fontSize = 12.sp, color = NudgyCharcoal)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BuildStepItem(
    step: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(NudgyPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = NudgyMuted
            )
        }
    }
}
