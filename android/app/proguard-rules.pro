# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep FFT classes
-keep class com.fourier.audioanalyzer.fft.** { *; }

# Keep audio processing classes
-keep class com.fourier.audioanalyzer.audio.** { *; }
