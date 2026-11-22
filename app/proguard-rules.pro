-keepclasseswithmembernames class * { native <methods>; }
# readable crash reports (app is small; obfuscation adds little protection here)
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
