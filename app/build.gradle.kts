import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release signing configuration.
//
// Values are read from local.properties (never committed) and may be
// overridden by environment variables so CI can inject them at build time:
//
//   local.properties key   environment variable
//   --------------------   --------------------
//   terebi.storeFile       TEREBI_STORE_FILE
//   terebi.storePassword   TEREBI_STORE_PASSWORD
//   terebi.keyAlias        TEREBI_KEY_ALIAS
//   terebi.keyPassword     TEREBI_KEY_PASSWORD
//
// The release build type is wired to the signing config only when ALL FOUR
// values are present. If any is missing (a fresh clone has none of them) the
// release build stays unsigned: Gradle still configures and assembles
// successfully, producing app-release-unsigned.apk, instead of failing.
// ---------------------------------------------------------------------------
val terebiSigningProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun terebiSigningValue(propKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: terebiSigningProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

val terebiStoreFile = terebiSigningValue("terebi.storeFile", "TEREBI_STORE_FILE")
val terebiStorePassword = terebiSigningValue("terebi.storePassword", "TEREBI_STORE_PASSWORD")
val terebiKeyAlias = terebiSigningValue("terebi.keyAlias", "TEREBI_KEY_ALIAS")
val terebiKeyPassword = terebiSigningValue("terebi.keyPassword", "TEREBI_KEY_PASSWORD")

val hasReleaseSigning = !terebiStoreFile.isNullOrBlank() &&
    !terebiStorePassword.isNullOrBlank() &&
    !terebiKeyAlias.isNullOrBlank() &&
    !terebiKeyPassword.isNullOrBlank()

android {
    namespace = "com.terebibro.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.terebibro.tv"
        // Spec minimum is API 30 (Android 11). API 28 (Android 9) is supported
        // as a compatibility fallback for older TV hardware; the immersive
        // path falls back to the legacy systemUiVisibility flags below API 30.
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // Created only when every signing value is available (see the guard
        // above). Without a "release" signing config the release build type
        // falls back to Gradle's default unsigned output.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(terebiStoreFile!!)
                storePassword = terebiStorePassword
                keyAlias = terebiKeyAlias
                keyPassword = terebiKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Belt-and-braces: attach the signing config only when it exists.
            // If it does not, the release build remains unsigned instead of
            // failing, so a fresh clone still configures and builds.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/versions/9/module-info.class",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE"
            )
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("org.slf4j:slf4j-nop:2.0.7")

    testImplementation("junit:junit:4.13.2")
}
