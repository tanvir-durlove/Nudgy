package com.example.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.ai.WakeWordForegroundService
import com.example.data.local.NudgyDatabase
import android.content.Intent
import android.provider.Settings
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import android.accounts.AccountManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AuthManager(private val context: Context) {
    private val authScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Nullable: Firebase is unavailable when google-services.json is absent (e.g. CI builds).
    private val auth: FirebaseAuth? = try {
        Firebase.auth
    } catch (e: Exception) {
        Log.w(TAG, "FirebaseAuth unavailable: ${e.message}")
        null
    }
    private val credentialManager: CredentialManager = CredentialManager.create(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _userState = MutableStateFlow<NudgyUser?>(extractUser())
    val userState: StateFlow<NudgyUser?> = _userState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        val isGuest = prefs.getBoolean(KEY_IS_GUEST, false)
        val explicitlySignedOut = prefs.getBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
        if (isGuest && !explicitlySignedOut && auth?.currentUser == null) {
            _userState.value = NudgyUser(
                uid = GUEST_USER_ID,
                displayName = "Guest User",
                email = "guest@nudgy.local",
                isGuest = true
            )
        }

        auth?.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                prefs.edit()
                    .putString(KEY_ACTIVE_USER_ID, user.uid)
                    .putBoolean(KEY_IS_GUEST, false)
                    .putBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
                    .apply()
                _userState.value = NudgyUser(
                    uid = user.uid,
                    displayName = resolveDisplayName(user.displayName, user.email),
                    email = resolveEmail(user.email),
                    photoUrl = user.photoUrl?.toString(),
                    isGuest = false
                )
            } else if (!prefs.getBoolean(KEY_IS_GUEST, false)) {
                _userState.value = null
            }
        }
    }

    fun continueAsGuest() {
        val guest = NudgyUser(
            uid = GUEST_USER_ID,
            displayName = "Guest User",
            email = "guest@nudgy.local",
            isGuest = true
        )
        prefs.edit()
            .putString(KEY_ACTIVE_USER_ID, GUEST_USER_ID)
            .putBoolean(KEY_IS_GUEST, true)
            .putBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
            .apply()
        _userState.value = guest
    }

    private fun extractUser(): NudgyUser? {
        val user = auth?.currentUser
        if (user != null) {
            prefs.edit()
                .putString(KEY_ACTIVE_USER_ID, user.uid)
                .apply()
            return NudgyUser(
                uid = user.uid,
                displayName = resolveDisplayName(user.displayName, user.email),
                email = resolveEmail(user.email),
                photoUrl = user.photoUrl?.toString()
            )
        }
        return null
    }

    private fun getWebClientId(): String {
        return try {
            val resId = context.resources.getIdentifier(
                "default_web_client_id", "string", context.packageName
            )
            if (resId != 0) {
                context.getString(resId)
            } else {
                try {
                    context.getString(R.string.default_web_client_id)
                } catch (e: Exception) {
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Missing default_web_client_id string resource", e)
            ""
        }
    }

    /**
     * Silent Auto-Sign-In on App Startup
     */
    fun attemptSilentSignIn(scope: CoroutineScope) {
        if (auth?.currentUser != null) {
            _userState.value = extractUser()
            return
        }

        val explicitlySignedOut = prefs.getBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
        if (explicitlySignedOut) {
            Log.d(TAG, "Silent sign-in skipped: user explicitly signed out")
            return
        }

        if (prefs.getBoolean(KEY_IS_GUEST, false)) {
            _userState.value = NudgyUser(
                uid = GUEST_USER_ID,
                displayName = "Guest User",
                email = "guest@nudgy.local",
                isGuest = true
            )
            return
        }

        val clientId = getWebClientId()
        if (clientId.isEmpty()) return

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val firebaseAuth = auth ?: return

        scope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = firebaseAuth.signInWithCredential(authCredential).await()
                    val user = authResult.user
                    if (user != null) {
                        prefs.edit()
                            .putBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
                            .putBoolean(KEY_IS_GUEST, false)
                            .putString(KEY_ACTIVE_USER_ID, user.uid)
                            .apply()
                        migrateGuestDataToUser(user.uid)
                        syncUserToFirestore(user.uid, user.displayName, user.email, user.photoUrl?.toString())
                    }
                }
            } catch (e: Exception) {
                // Expected when no prior authorized account is stored on device
                Log.d(TAG, "Silent sign-in not available: ${e.message}")
            }
        }
    }

    /**
     * Interactive Google Sign-In with Credential Manager
     */
    fun signInWithGoogle(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit = {},
        onNoDeviceAccount: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val clientId = getWebClientId()
        if (clientId.isEmpty()) {
            val error = "Google Sign-In configuration missing: client ID not found"
            _errorMessage.value = error
            onError(error)
            onNoDeviceAccount()
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        val firebaseAuth = auth ?: run {
            _isLoading.value = false
            onNoDeviceAccount()
            return
        }

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                val isGoogleToken = credential is CustomCredential && (
                    credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL ||
                    credential.type.contains("google", ignoreCase = true)
                )

                if (isGoogleToken && credential is CustomCredential) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = firebaseAuth.signInWithCredential(authCredential).await()
                    val user = authResult.user

                    if (user != null) {
                        prefs.edit()
                            .putBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
                            .putBoolean(KEY_IS_GUEST, false)
                            .putString(KEY_ACTIVE_USER_ID, user.uid)
                            .apply()
                        migrateGuestDataToUser(user.uid)
                        syncUserToFirestore(user.uid, user.displayName, user.email, user.photoUrl?.toString())
                        _userState.value = NudgyUser(
                            uid = user.uid,
                            displayName = resolveDisplayName(user.displayName, user.email),
                            email = resolveEmail(user.email),
                            photoUrl = user.photoUrl?.toString(),
                            isGuest = false
                        )
                        _isLoading.value = false
                        withContext(Dispatchers.Main) {
                            onSuccess()
                        }
                    } else {
                        _isLoading.value = false
                        withContext(Dispatchers.Main) {
                            onNoDeviceAccount()
                        }
                    }
                } else {
                    _isLoading.value = false
                    withContext(Dispatchers.Main) {
                        onNoDeviceAccount()
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}")
                _isLoading.value = false
            } catch (e: Exception) {
                val isCancelled = e.message?.contains("cancel", ignoreCase = true) == true ||
                        e.message?.contains("user canceled", ignoreCase = true) == true

                if (isCancelled) {
                    Log.d(TAG, "Google Sign-In cancelled by user")
                    _isLoading.value = false
                    return@launch
                }

                _isLoading.value = false
                Log.d(TAG, "No Google accounts on device: ${e.message}; opening Google sign-in sheet")
                withContext(Dispatchers.Main) {
                    onNoDeviceAccount()
                }
            }
        }
    }

    /**
     * Sign in directly with a Google account email (for emulators or direct Google identity selection).
     */
    fun signInWithGoogleAccount(
        email: String,
        displayName: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        val resolvedEmail = email.trim()
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: resolveDisplayName(null, resolvedEmail)
        val uid = "google_" + java.util.UUID.nameUUIDFromBytes(resolvedEmail.lowercase().toByteArray()).toString().replace("-", "")

        val user = NudgyUser(
            uid = uid,
            displayName = name,
            email = resolvedEmail,
            photoUrl = null,
            isGuest = false
        )
        prefs.edit()
            .putString(KEY_ACTIVE_USER_ID, uid)
            .putBoolean(KEY_IS_GUEST, false)
            .putBoolean(KEY_EXPLICITLY_SIGNED_OUT, false)
            .apply()
        _userState.value = user
        _isLoading.value = false
        _errorMessage.value = null

        authScope.launch {
            migrateGuestDataToUser(uid)
            try {
                syncUserToFirestore(uid, name, resolvedEmail, null)
            } catch (e: Exception) {
                Log.d(TAG, "Local Google user initialized: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                onSuccess()
            }
        }
    }

    /**
     * Launch the standard Google Account Sign-In / Account Picker modal
     * when no saved Google credentials are found on the device.
     */
    fun openGoogleAccountModal(activity: Activity) {
        _isLoading.value = false
        _errorMessage.value = null
        try {
            val chooseAccountIntent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
            activity.startActivity(chooseAccountIntent)
        } catch (e: Exception) {
            try {
                val addAccountIntent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                    putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                }
                activity.startActivity(addAccountIntent)
            } catch (ex: Exception) {
                Log.w(TAG, "Could not open Google account modal", ex)
            }
        }
    }

    /**
     * Migrate local guest-created tasks, memories, calendar events, and chat messages
     * to the authenticated user account so nothing is lost.
     */
    suspend fun migrateGuestDataToUser(newUserId: String) = withContext(Dispatchers.IO) {
        if (newUserId.isBlank() || newUserId == GUEST_USER_ID) return@withContext
        try {
            val db = NudgyDatabase.getInstance(context)
            db.taskDao().reassignUser(GUEST_USER_ID, newUserId)
            db.memoryDao().reassignUser(GUEST_USER_ID, newUserId)
            db.chatDao().reassignUser(GUEST_USER_ID, newUserId)
            db.calendarDao().reassignUser(GUEST_USER_ID, newUserId)
            db.dailyBriefDao().reassignUser(GUEST_USER_ID, newUserId)
            Log.d(TAG, "Migrated all local guest data to user $newUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating guest data", e)
        }
    }

    /**
     * Store or update user metadata in Firestore
     */
    private suspend fun syncUserToFirestore(
        uid: String,
        displayName: String?,
        email: String?,
        photoUrl: String?
    ) = withContext(Dispatchers.IO) {
        if (uid == GUEST_USER_ID) return@withContext
        try {
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val userDoc = db.collection("users").document(uid)

            val resolvedName = resolveDisplayName(displayName, email)
            val resolvedEmail = resolveEmail(email)

            val payload = hashMapOf<String, Any>(
                "userId" to uid,
                "displayName" to resolvedName,
                "email" to resolvedEmail,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (photoUrl != null) {
                payload["photoUrl"] = photoUrl
            }
            payload["preferredLanguage"] = "en"
            payload["workingHoursStart"] = "09:00"
            payload["workingHoursEnd"] = "18:00"
            payload["streakCount"] = 1

            userDoc.set(payload, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.w(TAG, "Firestore user profile sync warning: ${e.message}")
        }
    }

    /**
     * Sign Out
     */
    fun signOut(scope: CoroutineScope, onComplete: () -> Unit = {}) {
        WakeWordForegroundService.stop(context)
        auth?.signOut()
        prefs.edit()
            .clear()
            .putBoolean(KEY_EXPLICITLY_SIGNED_OUT, true)
            .apply()
        _userState.value = null
        scope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear credential state", e)
            } finally {
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    /**
     * Delete Account (Google Play Policy Compliant)
     * Performs a full coordinated purge across Firestore subcollections, local Room database,
     * and voice/audio file caches.
     */
    fun deleteAccount(scope: CoroutineScope, onComplete: () -> Unit = {}, onError: (String) -> Unit = {}) {
        WakeWordForegroundService.stop(context)
        val user = auth?.currentUser
        if (user == null) {
            val uid = getActiveUserId(context)
            scope.launch(Dispatchers.IO) {
                purgeLocalData(uid)
                withContext(Dispatchers.Main) {
                    prefs.edit().clear().putBoolean(KEY_EXPLICITLY_SIGNED_OUT, true).apply()
                    _userState.value = null
                    onComplete()
                }
            }
            return
        }

        _isLoading.value = true
        scope.launch {
            try {
                val uid = user.uid
                val dbId = context.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(dbId)
                val userDocRef = db.collection("users").document(uid)

                withContext(Dispatchers.IO) {
                    purgeFirestoreSubcollection(userDocRef.collection("tasks"))
                    purgeFirestoreSubcollection(userDocRef.collection("memories"))
                    purgeFirestoreSubcollection(userDocRef.collection("events"))
                    purgeFirestoreSubcollection(userDocRef.collection("chats"))
                    try {
                        userDocRef.delete().await()
                    } catch (e: Exception) {
                        Log.w(TAG, "User doc delete error: ${e.message}")
                    }
                    purgeLocalData(uid)
                }

                user.delete().await()
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
                prefs.edit().clear().putBoolean(KEY_EXPLICITLY_SIGNED_OUT, true).apply()
                _userState.value = null
                _isLoading.value = false
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Account deletion failed", e)
                val msg = e.localizedMessage ?: "Failed to delete account"
                _errorMessage.value = msg
                _isLoading.value = false
                withContext(Dispatchers.Main) {
                    onError(msg)
                }
            }
        }
    }

    private suspend fun purgeFirestoreSubcollection(collection: CollectionReference) {
        try {
            val snapshot = collection.get().await()
            for (doc in snapshot.documents) {
                doc.reference.delete().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subcollection purge error (${collection.path}): ${e.message}")
        }
    }

    private suspend fun purgeLocalData(userId: String) = withContext(Dispatchers.IO) {
        try {
            val db = NudgyDatabase.getInstance(context)
            db.taskDao().clearTasksForUser(userId)
            db.calendarDao().clearEventsForUser(userId)
            db.memoryDao().clearMemoriesForUser(userId)
            db.chatDao().clearMessages(userId)
            db.dailyBriefDao().clearBriefsForUser(userId)
        } catch (e: Exception) {
            Log.w(TAG, "Local DB purge error: ${e.message}")
        }
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".pcm") || file.name.endsWith(".wav") || file.name.contains("audio") || file.name.contains("voice")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio cache purge error: ${e.message}")
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    companion object {
        private const val TAG = "NudgyAuth"
        const val PREFS_NAME = "nudgy_auth_prefs"
        const val KEY_IS_GUEST = "is_guest"
        const val KEY_ACTIVE_USER_ID = "active_user_id"
        const val KEY_EXPLICITLY_SIGNED_OUT = "nudgy_explicitly_signed_out"
        const val GUEST_USER_ID = "guest_user_local"

        fun resolveDisplayName(displayName: String?, email: String?): String {
            return displayName?.takeIf { it.isNotBlank() }
                ?: email?.substringBefore("@")?.takeIf { it.isNotBlank() }
                ?: "User"
        }

        fun resolveEmail(email: String?): String {
            return email ?: ""
        }

        fun getActiveUserId(context: Context): String {
            val firebaseAuth = try { Firebase.auth } catch (e: Exception) { null }
            firebaseAuth?.currentUser?.uid?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_ACTIVE_USER_ID, "") ?: ""
        }
    }
}
