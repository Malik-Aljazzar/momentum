# kotlinx.serialization: keep generated serializers of the API models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# Credential Manager loads its Play Services provider by reflection
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.momentum.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.momentum.app.**$$serializer { *; }
