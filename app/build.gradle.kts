plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.vagrant326.atvt9"
    compileSdk = 35

    // Pinned to what the dev image installs. AGP 8.7.3 defaults to 34.0.0, which the image does
    // not carry, so every container was silently re-downloading it into a filesystem that dies
    // with the container — and a build with no network failed outright.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.vagrant326.atvt9"
        minSdk = 23
        targetSdk = 35
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("versionName").orNull ?: "0.0.0-dev"

        // The instrumented side runs on JUnit 4, which is not a choice: AndroidJUnitRunner is
        // what a device test is started by and it has no Jupiter engine. The JVM tests below
        // stay on Jupiter, in a source set this runner never sees.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * Two channels, and deliberately two *applications*. The dev build carries its own
     * applicationId, so it installs alongside the released one instead of replacing it — which
     * is the whole point: an experiment that takes over the d-pad has already cost this project
     * a TV, and the way to try the next one is with a working keyboard still installed.
     *
     * Each channel updates from its own releases. The tag prefix is what keeps them apart.
     */
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            buildConfigField("String", "RELEASE_TAG_PREFIX", "\"v\"")
            buildConfigField("String", "RELEASE_ALIAS", "\"latest\"")
            buildConfigField("String", "RELEASE_ASSET", "\"atv-t9.apk\"")
        }
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "RELEASE_TAG_PREFIX", "\"dev-\"")
            buildConfigField("String", "RELEASE_ALIAS", "\"latest-dev\"")
            buildConfigField("String", "RELEASE_ASSET", "\"atv-t9-dev.apk\"")
        }
    }

    // Release signing comes from the environment so the keystore never touches the
    // repository. Absent locally, in which case release builds stay unsigned rather
    // than silently falling back to the debug key - a debug-signed APK will not install
    // over a release-signed one, and finding that out on the TV is expensive.
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    androidResources {
        // The dictionaries are already dense, so the packer wins little on them and costs a
        // decompression of the whole file at every keyboard start. Storing them uncompressed
        // trades a little download for a startup that does not scale with vocabulary size.
        noCompress += "bin"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * Unit tests for this module, which had none.
 *
 * Every bug the television has shown lived here rather than in `core`, where a hundred and eight
 * tests were watching code that had not broken. They were not Android bugs - see `Editor` - and
 * they run on the JVM like everything else.
 */
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Receiver registration flags only, for the install-result broadcast in the updater.
    implementation(libs.androidx.core)

    // One smoke test on a device. UiAutomator because everything it has to do first — enable the
    // keyboard, select it, tell Android to show it while a hardware keyboard is attached — is a
    // shell command, and because the keyboard's own window belongs to no activity the test owns.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

/**
 * A device on this machine: boot the emulator, install the dev build, make it the keyboard.
 *
 *     ./gradlew :app:tv                    the television
 *     ./gradlew :app:tv -Pavd=mobile     a phone, where a finger can use the grid
 *
 * Worth a task rather than a line in the README because every step is a thing somebody gets wrong
 * once and then loses twenty minutes to. The keyboard is deaf while hidden — by design, since
 * consuming the d-pad while hidden once left a television unnavigable — so an emulator driven
 * from a desk keyboard sees nothing at all until `show_ime_with_hard_keyboard` is set, and there
 * is nothing on screen to suggest that is why.
 *
 * Both kinds are worth having open. A television takes no touch at all, which is the point of it:
 * the grid is a label there and the number keys are the only way in. A phone takes a finger, so
 * the same grid is a keyboard and the decoder can be tried without a hardware keypad at all.
 *
 * It will not create the virtual device or download a system image. Both are large, slow and the
 * user's to decide on; the task says what to run and stops.
 *
 * Every path is resolved here rather than inside the task, because a task body that reaches back
 * into the script cannot be stored in the configuration cache — which this build uses.
 */
val avdName: String = providers.gradleProperty("avd").getOrElse("atv")
val exeSuffix: String = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
val adbPath: String = File(android.sdkDirectory, "platform-tools/adb$exeSuffix").path
val emulatorPath: String = File(android.sdkDirectory, "emulator/emulator$exeSuffix").path
val imeService = "io.github.vagrant326.atvt9.dev/io.github.vagrant326.atvt9.ime.T9ImeService"
val devApk: File = File(layout.buildDirectory.get().asFile, "outputs/apk/dev/debug/app-dev-debug.apk")

/**
 * Boots [avdName] unless it is already running, and waits for the boot rather than for a fixed
 * time.
 *
 * "Already running" is checked per virtual device rather than per anything attached, because two
 * are worth having open at once and asking for the phone while the television is up must start
 * the phone. A device that is not an emulator — the real television over `adb connect` — is left
 * alone whatever is asked for: starting an emulator over the top of a real one is the mistake
 * that wastes a whole session, since everything afterwards silently goes to the wrong device.
 */
tasks.register("bootTv") {
    group = "verification"
    description = "Starts the emulator named by -Pavd (default atv) and waits for it to boot"
    val avd = avdName
    val adb = adbPath
    val emulator = emulatorPath
    doLast {
        fun run(command: String, vararg arguments: String): String {
            val process = ProcessBuilder(listOf(command) + arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output.trim()
        }

        fun attached(): List<String> = run(adb, "devices")
            .lines()
            .drop(1)
            .filter { it.endsWith("device") }
            .map { it.substringBefore('	').trim() }

        fun avdOf(serial: String): String =
            if (serial.startsWith("emulator-")) {
                run(adb, "-s", serial, "emu", "avd", "name").lines().firstOrNull()?.trim().orEmpty()
            } else {
                serial
            }

        if (attached().any { avdOf(it) == avd || !it.startsWith("emulator-") }) {
            logger.lifecycle("$avd is already attached, leaving it alone")
            return@doLast
        }

        val known = run(emulator, "-list-avds").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (avd !in known) {
            error(
                """
                no virtual device called `$avd`. To make a television (about 1 GB, once):

                  sdkmanager --install "system-images;android-36;android-tv;x86_64"
                  avdmanager create avd -n atv -k "system-images;android-36;android-tv;x86_64" -d tv_1080p

                Known here: ${known.joinToString()}
                """.trimIndent()
            )
        }

        logger.lifecycle("starting $avd")
        ProcessBuilder(emulator, "-avd", avd, "-no-boot-anim")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val deadline = System.currentTimeMillis() + 5 * 60 * 1000
        while (true) {
            val serial = attached().firstOrNull { avdOf(it) == avd }
            if (serial != null &&
                run(adb, "-s", serial, "shell", "getprop", "sys.boot_completed") == "1"
            ) {
                break
            }
            if (System.currentTimeMillis() > deadline) {
                error("$avd did not finish booting in five minutes")
            }
            Thread.sleep(2_000)
        }
        logger.lifecycle("$avd is up")
    }
}

/**
 * Installs onto the device that was asked for, by serial.
 *
 * AGP's own install task would do this, and cannot be told which device to use when two are
 * attached — which is the normal state here, a television for the layout and a phone for the
 * touch. So the APK goes over with `adb -s` instead, and the build task only has to build it.
 */
tasks.register("tv") {
    group = "verification"
    description = "Boots the device, installs the dev build and selects it as the keyboard"
    dependsOn("bootTv", "assembleDevDebug")
    val avd = avdName
    val adb = adbPath
    val service = imeService
    val apk = devApk
    doLast {
        fun run(vararg arguments: String): String {
            val process = ProcessBuilder(listOf(adb) + arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output.trim()
        }

        val devices = run("devices")
            .lines()
            .drop(1)
            .filter { it.endsWith("device") }
            .map { it.substringBefore('	').trim() }
        val serial = devices.firstOrNull { serial ->
            serial.startsWith("emulator-") &&
                run("-s", serial, "emu", "avd", "name").lines().firstOrNull()?.trim() == avd
        } ?: devices.singleOrNull()
        ?: error("no device for `$avd`, and ${devices.size} others attached: ${devices.joinToString()}")

        logger.lifecycle(run("-s", serial, "install", "-r", apk.path).lines().last())
        run("-s", serial, "shell", "ime", "enable", service)
        run("-s", serial, "shell", "ime", "set", service)
        // Android hides every soft keyboard while a hardware one is attached, and an emulator
        // always has one. Without this the keyboard is installed, selected, and completely deaf.
        run("-s", serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
        logger.lifecycle(
            "keyboard ready on $serial, " + run("-s", serial, "shell", "getprop", "ro.product.model")
        )
    }
}
