# WebRTC is a native library: the names of the classes and of the methods must
# stay as they are, or the native code will not find them.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The voice of the call lives in a process of its own and is found by name.
-keep class app.wifigram.call.VoiceService { *; }
-keep class app.wifigram.call.VoiceClient { *; }
-keep class app.wifigram.call.WebRtcEngine { *; }
-keep class app.wifigram.call.VoiceTrace { *; }
