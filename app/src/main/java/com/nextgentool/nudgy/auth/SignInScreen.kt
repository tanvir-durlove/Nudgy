package com.nextgentool.nudgy.auth

import android.app.Activity
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonOutline
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.components.NudgyLogoMark
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCardBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * SignInScreen — Sprint 7: the prototype sign-in hero.
 * Hero: the cascade logo mark + "Welcome to Nudgy" + sub-copy —
 * no mascot, no floating chips (Sprint 7 spec §3.1).
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

    // Staggered entrance: hero -> action sheet
    val heroAnim = remember { Animatable(0f) }
    val sheetAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { heroAnim.animateTo(1f, tween(550)) }
    LaunchedEffect(Unit) { delay(260); sheetAnim.animateTo(1f, tween(550)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NudgyCanvas)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {

            // ---------- HERO (Sprint 7: prototype sign-in, no mascot) ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        alpha = heroAnim.value
                        translationY = (1f - heroAnim.value) * 48f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                NudgyLogoMark(width = 72.dp, height = 32.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Welcome to Nudgy",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = NudgyCharcoal,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sign in to sync your tasks across devices, or start as a guest — everything works offline.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
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
                        .navigationBarsPadding()
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
