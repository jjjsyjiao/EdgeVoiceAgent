# EdgeVoiceAgent app proguard rules
-keep class com.edgevoice.agent.media.ring.NativeRingBuffer { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.edgevoice.agent.app.BuildConfig { *; }
