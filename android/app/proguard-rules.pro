# Regras do kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.emuhub.app.**$$serializer { *; }
-keepclassmembers class com.emuhub.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.emuhub.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
