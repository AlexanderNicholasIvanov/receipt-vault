# R8 rules for the release build. Libraries ship their own rules (Room, WorkManager,
# kotlinx.serialization, ML Kit, Coil), so this file only covers the app itself.

# Readable crash stack traces: keep line numbers. Class and method names are restored with
# the mapping.txt that every GitHub Release publishes next to the APK:
#   retrace mapping.txt stacktrace.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
