# GENERAL OPTIONS

# turn on all optimizations except those that are known to cause problems on Android
-optimizations !code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 6
-allowaccessmodification

-dontusemixedcaseclassnames
-keepattributes *Annotation*

# For native methods, see http://proguard.sourceforge.net/manual/examples.html#native
-keepclasseswithmembernames class * {
    native <methods>;
}
# keep setters in Views so that animations can still work.
# see http://proguard.sourceforge.net/manual/examples.html#beans
-keepclassmembers public class * extends android.view.View {
    void set*(***);
    *** get*();
}
# We want to keep methods in Activity that could be used in the XML attribute onClick
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
# For enumeration classes, see http://proguard.sourceforge.net/manual/examples.html#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

# Pachli specific options

# keep members of our model classes, they are used in json de/serialization
-keepclassmembers class app.pachli.core.network.model.** { *; }
-keepclassmembers class app.pachli.core.model.** { *; }

-keep public enum app.pachli.core.network.model.*$** {
    **[] $VALUES;
    public *;
}

-keepclassmembers class app.pachli.core.database.model.ConversationAccountEntity { *; }
-keepclassmembers class app.pachli.core.model.DraftAttachment { *; }
-keep class app.pachli.core.model.TimelineJsonAdapter { *; }

-keep enum app.pachli.core.model.DraftAttachment$Type {
    public *;
}

# Keep class names. Obfuscating them serves no purpose in an open source
# project and adds an additional step to de-obfuscate them when managing user
# error reports
-keepnames class *

# Retain generic signatures of classes used in MastodonApi so Retrofit works
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.collections.List
-keep,allowobfuscation,allowshrinking class kotlin.collections.Map
-keep,allowobfuscation,allowshrinking class retrofit2.Call

# https://r8.googlesource.com/r8/+/refs/heads/master/compatibility-faq.md#retrofit
-keepattributes Signature
-keep class kotlin.coroutines.Continuation

# preserve line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# remove all logging from production apk
-assumenosideeffects class android.util.Log {
    public static *** getStackTraceString(...);
    public static *** d(...);
    public static *** w(...);
    public static *** v(...);
    public static *** i(...);
}
-assumenosideeffects class java.lang.String {
    public static java.lang.String format(...);
}

# remove some kotlin overhead
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

# Preference fragments can be referenced by name, ensure they remain
# https://github.com/tuskyapp/Tusky/issues/3161
-keep class * extends androidx.preference.PreferenceFragmentCompat
