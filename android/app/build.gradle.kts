plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 桌面端的页面脚本与管理页保持单一源码：构建时从仓库根目录复制进 assets，
// Android 侧不另存一份，避免两边逻辑跑偏。
val syncWebAssets = tasks.register<Copy>("syncWebAssets") {
    val repoRoot = rootProject.projectDir.parentFile
    from(repoRoot.resolve("src/inject/engine.js"))
    from(repoRoot.resolve("src/ui/manage.html"))
    from(repoRoot.resolve("src/ui/manage.js"))
    from(repoRoot.resolve("src/ui/manage.css"))
    into(layout.buildDirectory.dir("generated/webAssets"))
}

android {
    namespace = "cn.paratranz.desktop"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.paratranz.desktop"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 签名参数从 gradle.properties / 环境变量取，缺省时只出未签名包
            val storePath = (project.findProperty("pt.keystore") as String?) ?: System.getenv("PT_KEYSTORE")
            if (!storePath.isNullOrBlank()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(storePath)
                    storePassword = (project.findProperty("pt.keystorePassword") as String?) ?: System.getenv("PT_KEYSTORE_PASSWORD")
                    keyAlias = (project.findProperty("pt.keyAlias") as String?) ?: System.getenv("PT_KEY_ALIAS")
                    keyPassword = (project.findProperty("pt.keyPassword") as String?) ?: System.getenv("PT_KEY_PASSWORD")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += "js"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncWebAssets)
}
tasks.named("preBuild") { dependsOn(syncWebAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
