import java.io.ByteArrayOutputStream
import java.util.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val keystoreProperties =
    Properties().apply {
      val file = rootProject.file("keystore.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)

/** Reads the trimmed standard output of a `git` invocation as a [Provider]. */
abstract class GitCommandValueSource : ValueSource<String, GitCommandValueSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val args: ListProperty<String>
    val workingDir: DirectoryProperty
  }

  @get:Inject abstract val execOperations: ExecOperations

  override fun obtain(): String? {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    return try {
      val result = execOperations.exec {
        commandLine(listOf("git") + parameters.args.get())
        workingDir = parameters.workingDir.asFile.get()
        standardOutput = stdout
        errorOutput = stderr
        isIgnoreExitValue = true
      }

      if (result.exitValue == 0) {
        stdout.toString(Charsets.UTF_8).trim().ifEmpty { null }
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }
  }
}

fun gitCommand(vararg args: String): Provider<String> =
    providers.of(GitCommandValueSource::class.java) {
      parameters.args.set(args.toList())
      parameters.workingDir.set(rootProject.layout.projectDirectory)
    }

val gitVersionName: Provider<String> =
    gitCommand("describe", "--tags", "--always", "--dirty").orElse("unknown")

val gitCommitCount: Provider<Int> =
    gitCommand("rev-list", "--count", "HEAD").map { it.toIntOrNull() ?: 1 }.orElse(1)

android {
  namespace = "com.calindora.follow"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.calindora.follow"
    minSdk = 33
    targetSdk = 37
    versionCode = gitCommitCount.get()
    versionName = gitVersionName.get()

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val store = signingValue("storeFile", "FOLLOW_KEYSTORE_PATH")
      val storePass = signingValue("storePassword", "FOLLOW_KEYSTORE_PASSWORD")
      val alias = signingValue("keyAlias", "FOLLOW_KEY_ALIAS")
      val keyPass = signingValue("keyPassword", "FOLLOW_KEY_PASSWORD")

      if (store != null && storePass != null && alias != null && keyPass != null) {
        storeFile = file(store)
        storePassword = storePass
        keyAlias = alias
        keyPassword = keyPass
      }
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }

    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )

      signingConfigs
          .getByName("release")
          .takeIf { it.storeFile != null }
          ?.let { signingConfig = it }
    }
  }

  sourceSets { getByName("androidTest").assets.directories += "$projectDir/schemas" }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.appcompat)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.livedata.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.material)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging.interceptor)
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  implementation(libs.tink.android)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
