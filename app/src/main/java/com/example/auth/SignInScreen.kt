package com.example.auth

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.MascotMood
import com.example.ui.components.NudgyMascot
import com.example.ui.theme.NudgyCanvas
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    authManager: AuthManager,
    scope: CoroutineScope,
    isLoading: Boolean,
    errorMessage: String?,
    onSignInSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbarHostState = remember { SnackbarHostState() }

    var showGoogleAccountModal by remember { mutableStateOf(false) }
    var customEmailInput by remember { mutableStateOf("") }
    var isEnteringCustomEmail by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Hero Mascot
            NudgyMascot(
                size = 110.dp,
                mood = MascotMood.IDLE
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "NUDGY",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = NudgyPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Things Get Done.",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = NudgyCharcoal,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Personal assistant for tasks, time, and focus.",
                style = MaterialTheme.typography.bodyLarge,
                color = NudgyMuted,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Google Sign-In Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.2.dp, NudgyCardBorder, RoundedCornerShape(100.dp))
                    .clickable(
                        enabled = !isLoading && activity != null,
                        role = Role.Button
                    ) {
                        if (activity != null) {
                            authManager.signInWithGoogle(
                                activity = activity,
                                scope = scope,
                                onSuccess = onSignInSuccess,
                                onNoDeviceAccount = {
                                    showGoogleAccountModal = true
                                }
                            )
                        }
                    },
                color = NudgyCard,
                shape = RoundedCornerShape(100.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = NudgyPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Signing in...",
                            style = MaterialTheme.typography.titleMedium,
                            color = NudgyCharcoal
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_logo),
                            contentDescription = "Google Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sign in with Google",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = NudgyCharcoal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1-Tap Google Quick Sign-In Option
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF4285F4).copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .clickable {
                        authManager.signInWithGoogleAccount(
                            email = "qatanvirahmed@gmail.com",
                            displayName = "Tanvir Ahmed",
                            onSuccess = onSignInSuccess
                        )
                    },
                color = Color(0xFFF7F9FF),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4285F4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "T",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Continue as Tanvir Ahmed",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = NudgyCharcoal
                        )
                        Text(
                            text = "qatanvirahmed@gmail.com",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = NudgyMuted
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Sign in with this account",
                        tint = Color(0xFF4285F4),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Guest Mode Toggle Card (Core features without sign-in, local Room storage)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.2.dp, NudgyCardBorder, RoundedCornerShape(20.dp))
                    .clickable {
                        authManager.continueAsGuest()
                        onSignInSuccess()
                    },
                color = NudgyCard,
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Guest Mode",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = NudgyCharcoal
                        )
                    }

                    Switch(
                        checked = false,
                        onCheckedChange = {
                            authManager.continueAsGuest()
                            onSignInSuccess()
                        },
                        colors = SwitchDefaults.colors(
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE0DDD5),
                            checkedTrackColor = NudgyPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Google Account Modal Bottom Sheet
        if (showGoogleAccountModal) {
            ModalBottomSheet(
                onDismissRequest = { showGoogleAccountModal = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Sign in with Google",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = NudgyCharcoal
                            )
                        }
                        IconButton(onClick = { showGoogleAccountModal = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NudgyMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose a Google account to continue to Nudgy",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Account Option: Primary User Account
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.2.dp, Color(0xFF4285F4).copy(alpha = 0.5f)),
                        color = Color(0xFFF8FAFF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showGoogleAccountModal = false
                                authManager.signInWithGoogleAccount(
                                    email = "qatanvirahmed@gmail.com",
                                    displayName = "Tanvir Ahmed",
                                    onSuccess = onSignInSuccess
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4285F4)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("T", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tanvir Ahmed",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = NudgyCharcoal
                                )
                                Text(
                                    text = "qatanvirahmed@gmail.com",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NudgyMuted
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(100.dp),
                                color = Color(0xFFE8F0FE)
                            ) {
                                Text(
                                    text = "Google",
                                    fontSize = 11.sp,
                                    color = Color(0xFF1967D2),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (!isEnteringCustomEmail) {
                        OutlinedButton(
                            onClick = { isEnteringCustomEmail = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(100.dp),
                            border = BorderStroke(1.dp, NudgyCardBorder)
                        ) {
                            Text("Use another Google account", color = NudgyCharcoal, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        OutlinedTextField(
                            value = customEmailInput,
                            onValueChange = { customEmailInput = it },
                            label = { Text("Google Email") },
                            placeholder = { Text("e.g. name@gmail.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (customEmailInput.isNotBlank()) {
                                    showGoogleAccountModal = false
                                    authManager.signInWithGoogleAccount(
                                        email = customEmailInput.trim(),
                                        onSuccess = onSignInSuccess
                                    )
                                }
                            },
                            enabled = customEmailInput.isNotBlank() && customEmailInput.contains("@"),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Text("Sign In with this Google Account", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = {
                            showGoogleAccountModal = false
                            activity?.let { authManager.openGoogleAccountModal(it) }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Add account to device settings", fontSize = 13.sp, color = NudgyMuted)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
