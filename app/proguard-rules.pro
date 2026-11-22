# 未启用代码混淆；保留规则以便后续开启 minify 时使用。
-keep class com.dsh.htmlviewer.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
