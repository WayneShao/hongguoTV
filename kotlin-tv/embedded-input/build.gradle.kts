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
