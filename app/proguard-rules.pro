# Keep TFLite classes
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Keep AIDL generated stubs
-keep class com.hound.controller.filter.** { *; }

# Keep Device Admin receiver
-keep class com.hound.controller.mdm.AppDeviceAdminReceiver { *; }

# Keep partner command service
-keep class com.hound.controller.fcm.PartnerFcmService { *; }

# Keep Glide generated API
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }
