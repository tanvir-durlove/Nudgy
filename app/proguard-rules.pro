# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Moshi generated adapters (defense-in-depth, S1-1 2026-11-16): a
# @JsonClass(generateAdapter = true) adapter is found by Moshi via
# reflection (Class.forName), so R8 sees no static reference and strips
# it; Moshi then falls back to the reflective ClassJsonAdapter, which
# crashes on obfuscated enum fields (ci-345 launch crash — the generated
# SavedViewFilterJsonAdapter was absent from mapping.txt).
# SavedViewFilter itself no longer uses Moshi (manual JSON), but any
# future generated adapter must survive R8.
-keep class **JsonAdapter {
    <init>(...);
    <fields>;
}
