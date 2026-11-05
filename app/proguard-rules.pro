# R8 rules for Fiero Nav release builds. No libraries, nothing reflective to keep.

# Keep line numbers so release crash traces in logcat stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
