# Project-specific R8/ProGuard rules.
# Release builds keep shrinking/optimization enabled while preserving the public IPC contract.

# Android components referenced from AndroidManifest.xml.
# Their class names are part of the system binding contract.
-keep class com.geely.online.service.OnlineMusicService { *; }
-keep class com.geely.online.service.MediaSessionNotificationListenerService { *; }
-keep class com.geely.online.service.OnlineMusicUpdateReceiver { *; }

# AIDL-generated Binder contract exposed to other packages.
# Keep interface names, Stub/Proxy nested classes, transaction methods, descriptors, and listener callbacks.
-keep class com.geely.online.music.** { *; }
-keep class com.geely.online.music.**$* { *; }

# Parcelable classes used in AIDL. Their class names and public fields/methods must remain stable for cross-process unmarshalling.
-keep class com.geely.online.music.bean.** { *; }
-keep class com.geely.online.music.bean.**$* { *; }
-keep class com.tencent.wecarflow.control.data.** { *; }
-keep class com.tencent.wecarflow.control.data.**$* { *; }

# Generic safety net for any future AIDL interfaces/Binders added to the module.
-keep class * implements android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }
-keep class **$Stub { *; }
-keep class **$Stub$Proxy { *; }

# Parcelable safety net for any future IPC models.
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep Kotlin/Java metadata needed by generated nested classes and annotation-based tooling.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# Keep Kotlin serialization entry points if any serializable models are added later.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    #noinspection ShrinkerUnresolvedReference
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class com.geely.lib.** { *; }
-keepnames class com.geely.lib.** { *; }

-keep class com.geely.mediacenterservice.** { *; }
-keepnames class com.geely.mediacenterservice.** { *; }

-keep class com.tencent.** { *; }
-keepnames class com.tencent.** { *; }
