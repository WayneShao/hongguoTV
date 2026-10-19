plugins { id("com.android.library") }
android {
    namespace = "io.github.wayneshao.rayneo.ime"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    sourceSets.getByName("main") {
        java.srcDir("generated/java")
        assets.srcDir("generated/assets")
        jniLibs.srcDir("generated/jniLibs")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))
    System.getenv("RAYNEO_ROBOLECTRIC_SDK")?.let {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", it)
    }
}
