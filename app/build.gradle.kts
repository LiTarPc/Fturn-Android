import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Подпись release из keystore.properties (вне git). Нет файла - подпись через IDE.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.freeturn.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.litar.freeturn"
        // WireGuard GoBackend (com.wireguard.android:tunnel) требует minSdk 24.
        minSdk = 24
        targetSdk = 37
        versionName = "3.7.2" // x-release-please-version
        // Производный от versionName (M*10000+m*100+p) - release-please бампит только строку версии
        versionCode = versionName!!.split(".").let { (ma, mi, pa) ->
            ma.toInt() * 10000 + mi.toInt() * 100 + pa.substringBefore("-").toInt()
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "FreeTurn Debug")
        }
        release {
            resValue("string", "app_name", "FreeTurn")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // WireGuard tunnel-либа использует java.time, поэтому нужен desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

composeCompiler {
    if (project.findProperty("composeReports") == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}

/** Pinned gomobile AAR with both Android ABIs. The task output is a Gradle file dependency. */
abstract class FetchFreeturnAar : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val expectedSha256: Property<String>
    @get:OutputFile abstract val aarFile: RegularFileProperty

    @TaskAction fun fetch() {
        val dest = aarFile.get().asFile
        if (dest.isFile && sha256(dest).equals(expectedSha256.get(), ignoreCase = true)) {
            didWork = false
            return
        }
        dest.parentFile.mkdirs()
        val tag = version.get().let { if (it.startsWith("v")) it else "v$it" }
        var url = URI("https://github.com/LiTarPc/fturn-core/releases/download/$tag/freeturn.aar").toURL()
        val temp = File(dest.parentFile, "${dest.name}.part")
        try {
            repeat(6) {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 300_000
                    setRequestProperty("User-Agent", "freeturn-android-build")
                }
                try {
                    when (val code = connection.responseCode) {
                        in 200..299 -> {
                            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                            val actual = sha256(temp)
                            if (!actual.equals(expectedSha256.get(), ignoreCase = true)) {
                                throw GradleException("FreeTurn AAR SHA-256 mismatch: $actual")
                            }
                            temp.copyTo(dest, overwrite = true)
                            return
                        }
                        301, 302, 303, 307, 308 -> {
                            val location = connection.getHeaderField("Location")
                                ?: throw GradleException("FreeTurn AAR redirect without Location")
                            url = url.toURI().resolve(location).toURL()
                        }
                        else -> throw GradleException("FreeTurn AAR download: HTTP $code from $url")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw GradleException("Too many redirects downloading FreeTurn AAR")
        } finally {
            temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val fetchFreeturnAar = tasks.register<FetchFreeturnAar>("fetchFreeturnAar") {
    group = "build"
    description = "Download and verify the pinned FreeTurn gomobile AAR"
    version.set(providers.gradleProperty("freeturnCore"))
    expectedSha256.set("d90ce768ceaab3f3e0ce1b27505a53646c1081cd0f049741422f43d1001d0102")
    aarFile.set(layout.buildDirectory.file("freeturn/freeturn.aar"))
}

dependencies {
    implementation(files(fetchFreeturnAar))
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.wireguard.tunnel)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.nav.suite)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.koin.androidx.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinxCoroutinesTest)
}

