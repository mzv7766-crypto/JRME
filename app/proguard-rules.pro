# keep app classes readable in crash logs
-keepattributes SourceFile,LineNumberTable
-dontwarn org.slf4j.**

# Credential Manager / Sign in with Google
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
# WebRTC uses JNI callbacks into these classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# WorkManager creates the Drive worker by reflection
-keep class com.shomer.bodycam.cloud.DriveUploadWorker { <init>(...); }
