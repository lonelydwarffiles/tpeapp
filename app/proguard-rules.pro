# Keep TFLite classes
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Keep AIDL generated stubs
-keep class com.tpeapp.filter.** { *; }

# Keep Device Admin receiver
-keep class com.tpeapp.mdm.AppDeviceAdminReceiver { *; }

# Keep FCM service
-keep class com.tpeapp.fcm.PartnerFcmService { *; }

# Keep Glide generated API
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }
