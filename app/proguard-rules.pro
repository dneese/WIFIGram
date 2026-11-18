# The voice codec is native C: the name of the class and of the methods must
# stay as they are, or the native code will not find them.
-keep class app.wifigram.call.OpusCodec { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
