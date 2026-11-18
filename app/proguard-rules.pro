# The voice codec is native C: the name of the class and of the methods must
# stay as they are, or the native code will not find them.
-keep class app.wifigram.call.OpusCodec { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The WebRTC library talks to its native part by the names of its classes and
# methods. The library brings no rules of its own, so without these lines the
# shrinking of the release build breaks the call.
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-dontwarn org.webrtc.**
