-keepattributes SourceFile,LineNumberTable,EnclosingMethod,InnerClasses
-dontobfuscate

-keep class org.eclipse.jgit.internal.JGitText { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class !org.bouncycastle.jce.provider.X509LDAPCertStoreSpi,org.bouncycastle.jce.provider.** { *; }

# JGit 5 references JMX (java.lang.management, javax.management), absent on Android. Both
# paths are unreachable: WindowCache JMX stats are disabled in Application.onCreate and
# GcOperation runs the GC steps without the PID lock that queries ManagementFactory.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
