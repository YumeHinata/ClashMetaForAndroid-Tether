plugins {
    kotlin("android")
    id("com.android.library")
}

val golangSource = file("src/main/golang/tetherpath")
val libsDir = file("libs")
val gomobileOutput = file("$libsDir/tetherpath.aar")

android {
    namespace = "com.github.kr328.clash.shizukutether"
    buildFeatures {
        aidl = true
    }
    
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

repositories {
    flatDir {
        dirs("libs")
    }
}

// gomobile bind task - builds Go code into AAR
val gomobileBind by tasks.registering(Exec::class) {
    workingDir = golangSource
    
    val gopath = System.getenv("GOPATH") ?: "${System.getProperty("user.home")}/go"
    val gomobilePath = "$gopath/bin/gomobile"
    val androidSdkPath = android.sdkDirectory.absolutePath
    val ndkPath = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT") ?: "$androidSdkPath/ndk/25.2.9519653"
    
    // Ensure output directory exists
    doFirst {
        libsDir.mkdirs()
    }
    
    commandLine(
        gomobilePath, "bind",
        "-v",
        "-target=android",
        "-androidapi=21",
        "-o=${gomobileOutput.absolutePath}",
        "."
    )
    
    inputs.dir(golangSource)
    outputs.file(gomobileOutput)
    
    environment("CGO_ENABLED", "1")
    environment("GOPATH", gopath)
    environment("ANDROID_HOME", androidSdkPath)
    environment("ANDROID_SDK_ROOT", androidSdkPath)
    environment("ANDROID_NDK_HOME", ndkPath)
    environment("ANDROID_NDK_ROOT", ndkPath)
}

// Verify AAR was created
val verifyGomobileOutput by tasks.registering {
    dependsOn(gomobileBind)
    doLast {
        if (!gomobileOutput.exists()) {
            throw GradleException("gomobile bind failed to produce ${gomobileOutput.absolutePath}")
        }
        println("✓ tetherpath.aar generated successfully (${gomobileOutput.length()} bytes)")
    }
}

// Ensure gomobile bind runs before compilation
tasks.named("preBuild") {
    dependsOn(verifyGomobileOutput)
}

dependencies {
    implementation(project(":common"))
    
    // gomobile generated AAR via flatDir
    implementation(":tetherpath@aar")

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
