# Keep all tether-related classes
-keep class com.github.kr328.clash.tether.** { *; }

# Keep AIDL generated interfaces
-keep interface com.github.kr328.clash.tether.ITetherService { *; }
-keep class com.github.kr328.clash.tether.ITetherService$Stub { *; }

# Keep Shizuku service implementation
-keep class com.github.kr328.clash.tether.shizuku.TetherShellService { *; }
-keep class com.github.kr328.clash.tether.shizuku.TetherClient { *; }

# Keep hidden API bypass usage
-dontwarn org.lsposed.hiddenapibypass.HiddenApiBypass
-keep class org.lsposed.hiddenapibypass.HiddenApiBypass { *; }

# Keep Shizuku API
-dontwarn rikka.shizuku.**
-keep class rikka.shizuku.** { *; }
