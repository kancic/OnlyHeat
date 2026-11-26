# R8 repackaged kotlin.text.StringsKt__StringBuilderKt out of kotlin.text while its package-private superclass
# stayed, so OkHttp crashed the app on start (IllegalAccessError). Keep Kotlin's multi-file classes in their packages.
-keeppackagenames kotlin.**
