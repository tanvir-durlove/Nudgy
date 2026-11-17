package com.nextgentool.nudgy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nextgentool.nudgy.ui.navigation.NudgyApp

/**
 * Sprint 1-0 — the app root is now NudgyApp() (NavHost + bottom nav).
 *
 * The old 1,494-line MainActivity (DI, chat handling, ads, sheets,
 * state-based navigation) is NOT moved here — it lives on in the git
 * history (scratch/rebuild-v2 @ 859cb4bc and earlier) and its behaviour
 * is rebuilt screen by screen in Sprint 1–6. What this step knowingly
 * drops until its sprint lands: sign-in gate, splash timing, ads,
 * notification-tap → Groups, and the App Links join-code prefill while
 * signed out (the /nudgy/join deep link itself still routes via the
 * NavHost). NudgyApp() owns the theme (NudgyTheme) so this file stays
 * a one-line root.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NudgyApp() }
    }
}
