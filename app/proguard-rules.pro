# Proguard rules for SoundSync
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
