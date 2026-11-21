# Pusher client uses Gson reflection for its protocol messages
-keep class com.pusher.client.** { *; }
-dontwarn org.slf4j.**

# started by the system from the manifest
-keep class com.figolabs.figochat.FigoChatInitProvider { <init>(); }
