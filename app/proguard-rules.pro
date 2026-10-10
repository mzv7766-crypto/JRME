
# keep app classes readable in crash logs
-keepattributes SourceFile,LineNumberTable
-dontwarn org.slf4j.**

# --- PRO ---
# MediaPipe (on-device AI) uses JNI + protobuf reflection
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
# Credential Manager / Sign in with Google
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
# WorkManager worker is created by reflection
-keep class com.jeremy.dashcam.core.pro.DriveUploadWorker { <init>(...); }
