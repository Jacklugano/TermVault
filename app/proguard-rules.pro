# sshj / BouncyCastle
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

# Keepass2Android plugin SDK (i nomi delle classi sono referenziati da KP2A)
-keep class keepass2android.pluginsdk.** { *; }

# Termux terminal
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
