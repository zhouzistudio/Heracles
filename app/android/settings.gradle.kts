pluginManagement {
    // ★ 2026-11-22：原来这里直接 `file("local.properties").inputStream()`，
    //   在没有 local.properties 的机器上（干净的 checkout、CI、以及
    //   **没有装 Flutter SDK 的手机**）会抛一个光秃秃的
    //   `local.properties (No such file or directory)`，看不出要做什么。
    //   现在按 local.properties → FLUTTER_ROOT → PATH 里的 flutter 依次找，
    //   都找不到才报错，并把「怎么修」写进消息里。
    val flutterSdkPath: String =
        run {
            val fromLocalProperties =
                file("local.properties")
                    .takeIf { it.isFile }
                    ?.let { f ->
                        java.util.Properties()
                            .apply { f.inputStream().use { load(it) } }
                            .getProperty("flutter.sdk")
                    }

            val fromEnv = System.getenv("FLUTTER_ROOT")

            // PATH 里找 flutter 可执行文件（软件包管理器装的 Flutter 常有这种情况）
            val fromPath =
                System.getenv("PATH")
                    ?.split(java.io.File.pathSeparator)
                    ?.map { java.io.File(it, "flutter") }
                    ?.firstOrNull { it.isFile && it.canExecute() }
                    ?.let { it.parentFile?.parentFile?.absolutePath }

            sequenceOf(fromLocalProperties, fromEnv, fromPath)
                .filterNotNull()
                .firstOrNull { it.isNotBlank() && java.io.File(it).isDirectory }
                ?: throw GradleException(
                    """
                    |找不到 Flutter SDK，本构建无法配置（这是 Flutter 宿主工程，不是纯 Android 工程）。
                    |
                    |我按这个顺序找过：
                    |  1) app/android/local.properties 里的 flutter.sdk —— ${if (file("local.properties").exists()) "文件在，但没写 flutter.sdk 或路径无效" else "文件不存在"}
                    |  2) 环境变量 FLUTTER_ROOT —— ${fromEnv ?: "未设置"}
                    |  3) PATH 里的 flutter —— ${fromPath ?: "没找到"}
                    |
                    |怎么修（任选一条）：
                    |  · 装了 Flutter：在 app/ 里跑 `flutter pub get`，它会自己生成 local.properties；
                    |    或者手工写一行：flutter.sdk=/绝对路径/to/flutter
                    |  · 没装 Flutter：本机编不出来。用云端编译：
                    |    gh workflow run android-apk.yml --ref ci/android-apk
                    |    （工作流见 .github/workflows/android-apk.yml，产物是 app-debug.apk）
                    """.trimMargin(),
                )
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        // ★ 2026-11-22：国内镜像优先。实测直连 Google Maven ≈1.5 MB/s、
        //   Maven Central（Cloudflare）更慢；腾讯/阿里云镜像约 5 MB/s。
        //   原仓库保留在最后兜底（镜像缺件时仍然能构建）。
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false

    // ★ 主工程的纯 JVM 模块用这个插件。
    //   版本必须与 kotlin.android 一致，否则两个 Kotlin 编译器会在同一
    //   构建里打架（Gradle 只允许 classpath 上有一个 Kotlin plugin 版本）。
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
}

include(":app")

// ---------------------------------------------------------------------------
// ★ 本构建**只**含 `:app`（2026-10-22，Dart 重写）
//
// 原来这里还 include 了 5 个纯 JVM 模块：
//   :core / :provider-http / :workspace / :agent / :toolchain
// 它们指向 `app/android/../../core` 等路径。**那些目录已被用户删除**
// （后端改用 Dart 重写，见 `docs/DART-MIGRATION-AUDIT-2026-10-22.md` §2.1），
// 于是 Gradle 在**配置阶段**就失败：
//
//   Configuring project ':workspace' without an existing directory is not allowed
//
// ⇒ 不要从 git 恢复那 5 个目录、不要重新 include。Kotlin 侧现在**只有设备能力**
//   （`heracles/device` 通道：文件 IO / shell / 健康读数 / 选择器 / 构建通知）。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// ★ 强制所有 Android 子项目 compile against ≥ 36
//
// 背景（2026-10-10 实测）：
//   `file_picker` 的 android/build.gradle 写死了 `compileSdk 34`，
//   而它的传递依赖 `flutter_plugin_android_lifecycle` 要求
//   **所有消费者**都 compile against ≥ 36。冲突直接让构建失败：
//
//     :file_picker is currently compiled against android-34
//     :flutter_plugin_android_lifecycle requires ... version 36 or later
//
//   这是**插件配置过时**（file_picker 9.2.3 仍未修），不是我们的代码问题。
//
// ⚠️ 为什么必须在 settings 里用 beforeProject：
//   app/android/build.gradle.kts 里的 `subprojects { evaluationDependsOn(":app") }`
//   会让所有子项目**立即求值**，此后任何 afterEvaluate / plugins.withId
//   都太晚了（实测两者都不生效）。
//   `gradle.beforeProject` 在**每个项目求值之前**触发，是唯一可靠的时机。
// ---------------------------------------------------------------------------
gradle.beforeProject {
    if (this.path == ":") return@beforeProject
    plugins.withId("com.android.library") {
        extensions.findByName("android")?.let { ext ->
            runCatching {
                ext.javaClass
                    .getMethod("compileSdkVersion", Int::class.javaPrimitiveType)
                    .invoke(ext, 36)
                logger.lifecycle("[heracles] ${project.name}: compileSdk -> 36")
            }
        }
    }
}
