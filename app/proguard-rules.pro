# Minification is off for now. If enabled, keep kotlinx.serialization classes used for bank packs.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class lk.expensetracker.engine.**$$serializer { *; }
-keepclassmembers class lk.expensetracker.engine.** { *** Companion; }
