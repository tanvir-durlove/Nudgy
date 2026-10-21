package com.example.auth

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.theme.NudgyLavender
import com.example.ui.theme.NudgyLavenderBorder
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPeachBorder
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgyPrimaryLight
import com.example.ui.theme.NudgySage
import com.example.ui.theme.NudgySageBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * SignInScreen — "Warm Welcome" redesign.
 * Hero: large mascot on a soft coral glow with floating feature chips.
 * Actions live in a bottom sheet: Google sign-in (official style),
 * then a plain "Continue as Guest" button (no toggle switch).
 * Copy uses planner framing only — Nudgy is a daily planner.
 */
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

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            authManager.clearError()
        }
    }

    // Staggered entrance: hero -> headline -> action sheet
    val heroAnim = remember { Animatable(0f) }
    val textAnim = remember { Animatable(0f) }
    val sheetAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { heroAnim.animateTo(1f, tween(550)) }
    LaunchedEffect(Unit) { delay(130); textAnim.animateTo(1f, tween(550)) }
    LaunchedEffect(Unit) { delay(260); sheetAnim.animateTo(1f, tween(550)) }

    // Gentle mascot bob
    val bobTransition = rememberInfiniteTransition(label = "mascotBob")
    val bob by bobTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(2100), RepeatMode.Reverse),
        label = "bob"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NudgyCanvas)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ---------- HERO ----------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        alpha = heroAnim.value
                        translationY = (1f - heroAnim.value) * 48f
                    },
                contentAlignment = Alignment.Center
            ) {
                // Soft coral glow behind the mascot
                Box(
                    modifier = Modifier
                        .size(320.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    NudgyPrimaryLight.copy(alpha = 0.50f),
                                    NudgyPeach,
                                    NudgyCanvas.copy(alpha = 0f)
                                )
                            )
                        )
                )

                Box(modifier = Modifier.offset(y = bob.dp)) {
                    NudgyMascot(
                        size = 148.dp,
                        mood = MascotMood.IDLE
                    )
                }

                FloatingChip(
                    icon = Icons.Default.NotificationsActive,
                    label = "Reminders",
                    container = NudgyPeach,
                    border = NudgyPeachBorder,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 26.dp, top = 44.dp)
                )
                FloatingChip(
                    icon = Icons.Default.CalendarMonth,
                    label = "Calendar",
                    container = NudgyLavender,
                    border = NudgyLavenderBorder,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 18.dp)
                        .offset(y = (-26).dp)
                )
                FloatingChip(
                    icon = Icons.Default.Timer,
                    label = "Focus time",
                    container = NudgySage,
                    border = NudgySageBorder,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 38.dp, bottom = 6.dp)
                )
            }

            // ---------- HEADLINE ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        alpha = textAnim.value
                        translationY = (1f - textAnim.value) * 48f
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NudgyCharcoal,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Your daily planner for tasks, reminders, and focus. Small nudges — and things get done.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NudgyMuted,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            // ---------- ACTION SHEET ----------
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = sheetAnim.value
                        translationY = (1f - sheetAnim.value) * 64f
                    },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = NudgyCard,
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                ) {
                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            color = Color(0xFFFFEBEE),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFCDD2)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Error",
                                    tint = Color(0xFFD32F2F),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = Color(0xFFC62828),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Google Sign-In Button (official light style)
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
                                        onSuccess = onSignInSuccess
                                    )
                                }
                            },
                        color = NudgyCard,
                        shape = RoundedCornerShape(100.dp),
                        shadowElevation = 1.dp
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

                    // Divider
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(NudgyCardBorder)
                        )
                        Text(
                            text = "or",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(NudgyCardBorder)
                        )
                    }

                    // Guest entry — a plain button, NOT a settings toggle
                    OutlinedButton(
                        onClick = {
                            authManager.continueAsGuest()
                            onSignInSuccess()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.2.dp, NudgyPrimary.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NudgyCharcoal)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOutline,
                            contentDescription = null,
                            tint = NudgyPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Continue as Guest",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        )
                    }

                    Text(
                        text = "No sign-in needed — your data stays on this device",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = NudgyMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    )

                    Text(
                        text = "By continuing you agree to our Terms & Privacy Policy.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = NudgyMuted.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp)
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FloatingChip(
    icon: ImageVector,
    label: String,
    container: Color,
    border: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100.dp),
        color = container,
        border = BorderStroke(1.dp, border),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NudgyCharcoal,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = NudgyCharcoal
            )
        }
    }
}
