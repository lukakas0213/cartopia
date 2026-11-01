# Pleos SDKs bind to car services through AIDL and parse payloads with Gson; keep them intact.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn ai.pleos.**
-dontwarn com.google.gson.**

# Methods the 3D village page calls through window.RoadCanvas.
-keepclassmembers class com.roadcanvas.app.ui.VillageBridge {
    @android.webkit.JavascriptInterface <methods>;
}
