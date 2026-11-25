package com.nextgentool.nudgy.ui.screens

/**
 * Step 5b (polish item 2) — the in-app Privacy Policy content.
 *
 * This is the FULL published policy
 * (https://tanvir-durlove.github.io/nudgy/privacy-policy.html,
 * last updated November 6, 2026), transcribed verbatim — NOT the
 * prototype's abbreviated summary. The prototype's privacy card
 * omits the ads / analytics / crash-reports sections entirely and
 * invents an "attachments up to 25 MB per file, up to 10 per task"
 * claim the published policy never makes; shipping that summary
 * would put wrong legal text in the app. The polish list asks for
 * the SAME content inside the app (and offline), so the published
 * text is what goes here.
 *
 * One correction vs the hosted page: its header line says package
 * `com.nextgen.nudgy` — a typo on the page. The real package is
 * `com.nextgentool.nudgy`, and that is what this copy says.
 *
 * Shape decision: a versioned Kotlin data file, not strings.xml.
 * The policy is a document with a last-updated date, not UI copy;
 * one file means a future policy update is a single-file diff, and
 * it keeps the EN/BN strings files clean. Body text is English
 * only: the published policy is English, and a Bengali translation
 * would be a NEW legal text, not the published one (spec Q10:
 * Bengali strings cover Settings + main screens only). The screen
 * title reuses the already-localized settings_section_privacy
 * string.
 *
 * When the published policy changes, update this file AND the
 * [PRIVACY_POLICY_LAST_UPDATED] date together, in one commit.
 */
data class PrivacyPolicySection(
    val heading: String,
    val paragraphs: List<String>,
)

/** The "Last updated" date of the published policy this copy tracks. */
const val PRIVACY_POLICY_LAST_UPDATED = "November 6, 2026"

/** Header line: app identity + developer, as on the published page. */
const val PRIVACY_POLICY_HEADER =
    "App: Nudgy — Things Get Done (Android) · Package: com.nextgentool.nudgy · Developer: NextGen Tool (nextgentoolbd@gmail.com)"

/** Intro paragraphs, shown before the numbered sections. */
val PRIVACY_POLICY_INTRO: List<String> = listOf(
    "Nudgy (\"we\", \"our\", \"the app\") is a personal productivity app for tasks, reminders, calendar and focus time.",
    "This policy explains what data the app collects, why, and your choices. If you do not agree, please do not use the app.",
)

/** The eleven sections of the published policy, in order. */
val PRIVACY_POLICY_SECTIONS: List<PrivacyPolicySection> = listOf(
    PrivacyPolicySection(
        heading = "1. Data stored on your device",
        paragraphs = listOf(
            "By default everything stays on your device, in the app's private local database:",
            "• Tasks, reminders, calendar events, focus sessions and chat history you create",
            "• App settings and preferences",
            "Guest Mode needs no account, and your tasks, reminders and settings stay on your device.",
            "Chat is the exception: messages handled on your device stay on your device, but messages sent to Gemini for a reply are processed in the cloud by Google (see section 5).",
        ),
    ),
    PrivacyPolicySection(
        heading = "2. Data synced when you sign in with Google",
        paragraphs = listOf(
            "If you choose \"Continue with Google\", we store the following in our cloud database (Google Firebase / Firestore) so your data syncs across your devices:",
            "• Your Google account identifier, display name, email address and profile photo URL",
            "• The tasks, reminders, calendar events and preferences described above",
            "Sign-in is handled by Google; we never see or store your Google password.",
            "If you create or join a group, group data is also stored in the cloud, as described in section 3.",
        ),
    ),
    PrivacyPolicySection(
        heading = "3. Groups — sharing with other people",
        paragraphs = listOf(
            "If you create or join a group, the tasks and schedules you share to the group are visible to the group's members, and the tasks and schedules other members share are visible to you.",
            "Group data is stored in our cloud database (Google Firebase / Firestore), including the group itself, its member list, the shared tasks and schedules, the join-code record and each member's membership record.",
            "• Join code: a group is joined with its join code. The code is a shared secret — anyone who knows the code can join the group. Share it only with people you trust.",
            "• Creator controls: the group's creator can delete the group, and any member can leave it. Deleting a group removes its shared tasks, schedules and membership records.",
            "• Completion stays private: whether you have completed a task is stored on your own device and is not shared with the group.",
            "Group members see only the tasks and schedules shared to the group — not your personal tasks or your other groups.",
        ),
    ),
    PrivacyPolicySection(
        heading = "4. Device permissions — what they are used for",
        paragraphs = listOf(
            "• Calendar (read/write): only if you connect Google Calendar, to show your events and block focus time. You can revoke this anytime in system settings.",
            "• Microphone: only when you tap the mic button in chat to dictate a task or message. Your speech is converted to text by your device's speech-recognition service; Nudgy does not record or store audio, and nothing listens in the background.",
            "• Notifications: to deliver the reminders you schedule.",
            "• Exact alarms: to fire reminders at the time you set. If denied, reminders fall back to inexact timing.",
        ),
    ),
    PrivacyPolicySection(
        heading = "5. AI features",
        paragraphs = listOf(
            "Common commands (\"add task…\", \"plan my day\", Bengali equivalents) are handled entirely on your device.",
            "Other chat messages may be sent to Google's Gemini API to generate a reply. We do not sell your data or use it for advertising.",
            "When a message is sent to Gemini, it goes through Firebase AI Logic using a key held by us on Google's servers — our key is never stored in the app. The message is sent together with your recent chat history (the last 6 turns) so the reply has context. If you have signed in, a per-user daily usage counter is stored in Firestore (free and bonus turns used today) to enforce the free daily limit; guests have a local-only cap on their device and no counter is stored for them. Requests are accompanied by an App Check attestation (Play Integrity) so Google can verify they come from a genuine copy of the app. Google processes these messages under Google's Privacy Policy.",
            "If you prefer, you may enter your own Gemini API key in the app's settings instead. Your key is stored only on your device and is used only to send your chat messages to Gemini.",
        ),
    ),
    PrivacyPolicySection(
        heading = "6. Analytics and crash reports",
        paragraphs = listOf(
            "We use Firebase Analytics and Remote Config to understand feature usage and improve the app (e.g. which screens are opened). This data is aggregated and not linked to your identity for advertising.",
            "If the app crashes, Firebase Crashlytics sends us a crash report (device model, Android version, and the technical state of the app at the time of the crash) so we can fix the problem. Crash reports do not include your tasks, events or chat content.",
        ),
    ),
    PrivacyPolicySection(
        heading = "7. Advertising",
        paragraphs = listOf(
            "Nudgy shows ads via Google AdMob. AdMob and its advertising partners may collect and use your device's advertising ID and coarse device information to serve, measure and improve ads, as described in Google's Privacy Policy. Where required, the app asks for your consent before personalized ads are shown, and you can reset or delete your advertising ID anytime in your device settings (Settings → Privacy → Ads).",
        ),
    ),
    PrivacyPolicySection(
        heading = "8. Your rights — access and deletion",
        paragraphs = listOf(
            "• In the app: open Your Nudgy → Profile to sign out or Delete Account. Deleting your account permanently removes your cloud data and local app data.",
            "• On the web: you can also request deletion at nudgy/delete-account.",
            "• To request a copy of your data or ask questions, contact us at the email below.",
        ),
    ),
    PrivacyPolicySection(
        heading = "9. Children",
        paragraphs = listOf(
            "Nudgy is not directed at children under 13, and we do not knowingly collect their data.",
        ),
    ),
    PrivacyPolicySection(
        heading = "10. Changes to this policy",
        paragraphs = listOf(
            "We may update this policy as the app evolves. The \"Last updated\" date above will always reflect the current version.",
        ),
    ),
    PrivacyPolicySection(
        heading = "11. Contact",
        paragraphs = listOf(
            "Questions about this policy: nextgentoolbd@gmail.com",
        ),
    ),
)
