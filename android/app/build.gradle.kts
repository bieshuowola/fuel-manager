plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.bieshuowola.fuelmanager"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.bieshuowola.fuelmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    signingConfigs { getByName("debug") { storeFile = rootProject.file("debug/fuel-manager-debug.p12"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
val prepareWeb by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("python3", "scripts/prepare_web.py")
    inputs.files(file("../../index.html"), file("../../manifest.webmanifest"), file("../../icon.svg"), rootProject.file("bridge.js"), rootProject.file("scripts/prepare_web.py"))
    outputs.dir(layout.buildDirectory.dir("generated/webAssets"))
}
tasks.named("preBuild") { dependsOn(prepareWeb) }
dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
}

// Honour the runner's existing TLS trust and proxy configuration in test child JVMs.
tasks.withType<Test>().configureEach {
    val testHome = gradle.gradleUserHomeDir.resolve("fuel-manager-test-home")
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts", "javax.net.ssl.trustStore").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
