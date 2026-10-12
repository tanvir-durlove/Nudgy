# Nudgy — Things Get Done 🤖✨

**Nudgy** is a personal AI productivity assistant for Android featuring natural language conversational scheduling, smart contextual reminders, and voice interactions.

---

## 🚀 How to Build & Download the Android APK via GitHub Actions

To guarantee a clean, uncorrupted APK download directly onto your Android device:

### Step 1: Export / Push to GitHub
1. In the AI Studio top navigation bar or settings menu, select **Push to GitHub** (or connect your git repository).
2. The code, Gradle configuration, and `.github/workflows/build-apk.yml` will be committed to your repository.

### Step 2: Open GitHub Actions
1. Navigate to your repository on [GitHub](https://github.com).
2. Click on the **Actions** tab at the top.
3. In the left sidebar, click on **Build Nudgy Android APK**.

### Step 3: Run the Workflow
1. Click the **Run workflow** button on the right.
2. Select the branch (`main` or `master`) and click **Run workflow**.

### Step 4: Download & Install the APK
Once the workflow finishes (typically ~1-2 minutes):
- **Option A (GitHub Releases):** Go to your repository's **Releases** section to download `nudgy.apk` directly to your phone with 1 tap.
- **Option B (Workflow Artifacts):** Scroll down to the bottom of the completed workflow run and download the `nudgy-android-apk` artifact.

---

## 📱 In-App APK Builder Launcher

You can also trigger and view the GitHub build steps directly from within the app:
1. Open Nudgy.
2. Tap the top-right avatar or go to **Your Nudgy**.
3. Tap **Build APK in GitHub Actions**.
4. Tap **Open GitHub Actions in Browser** to instantly jump to your workflow!

---

## 🛠️ App Details
- **Application ID:** `com.aistudio.nudgy.prod`
- **Minimum SDK:** Android 7.0 (API 24)
- **Target SDK:** Android 14+ (API 36)
- **Architecture:** Kotlin, Jetpack Compose, Material 3, Room, Firebase Firestore & Auth
