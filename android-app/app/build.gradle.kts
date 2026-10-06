import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pledgex.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pledgex.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "3.0.1"
        // A keyed RPC (e.g. Helius devnet) lives in local.properties as rpc.url, never in
        // the repo; without one the app uses the public devnet endpoint.
        // A devnet-only key holding a little SOL that tops up empty wallets when the
        // public airdrop is down. It has no authority over the program or the token.
        buildConfigField("String", "SPONSOR_SEED", "\"${localProps().getProperty("sponsor.seed") ?: ""}\"")
        buildConfigField("String", "RPC_URL", "\"${localProps().getProperty("rpc.url") ?: "https://api.devnet.solana.com"}\"")
    }

    // The release key and its passwords live in local.properties and keys/, both
    // git-ignored: a build without them is unsigned, never signed with a public key.
    val release = localProps()
    signingConfigs {
        if (release.getProperty("release.storeFile") != null) {
            create("release") {
                storeFile = file(release.getProperty("release.storeFile"))
                storePassword = release.getProperty("release.storePassword")
                keyAlias = release.getProperty("release.keyAlias")
                keyPassword = release.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.mwa.clientlib.ktx)
    implementation(libs.web3.solana)
    implementation(libs.multimult)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}

fun localProps(): Properties {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    return props
}

// `gradle testDebugUnitTest -Pe2e` runs DevnetE2ETest: the app's own instruction bytes,
// message compiler and Ed25519 signing, sent to the live devnet program.
tasks.withType<Test>().configureEach {
    systemProperty("pledgex.e2e", project.hasProperty("e2e").toString())
    systemProperty("pledgex.root", rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "failed", "skipped", "standardOut"); showStandardStreams = true }
}
