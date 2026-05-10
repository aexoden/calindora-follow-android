import java.io.ByteArrayOutputStream
import java.util.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val keystoreProperties: Provider<Properties> =
    providers
        .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText
        .map { contents -> Properties().apply { load(contents.reader()) } }

fun signingValue(propertyKey: String, envKey: String): Provider<String> =
    keystoreProperties
        .flatMap { props -> providers.provider { props.getProperty(propertyKey) } }
        .orElse(providers.environmentVariable(envKey))

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

/** Reads the `describe` field from `.git-archive-info`, when populated by `git archive`. */
abstract class ArchiveDescribeValueSource :
    ValueSource<String, ArchiveDescribeValueSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val archiveFile: RegularFileProperty
  }

  override fun obtain(): String? {
    val file = parameters.archiveFile.asFile.get()
    if (!file.exists()) return null
    val describe =
        Properties().apply { file.inputStream().use { load(it) } }.getProperty("describe")?.trim()
    return describe?.takeIf { it.isNotEmpty() && !it.startsWith($$"$Format:") }
  }
}

fun archiveDescribe(): Provider<String> =
    providers.of(ArchiveDescribeValueSource::class.java) {
      parameters.archiveFile.set(rootProject.layout.projectDirectory.file(".git-archive-info"))
    }

private val describeRegex = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?(?:-dirty)?$""")

/**
 * Parse a `git describe --tags --always --dirty` output and derive an Android versionCode.
 *
 * Recognized forms:
 * - "v1.2.3"
 * - "v1.2.3-25-g1234567"
 * - "v1.2.3[-...]-dirty"
 *
 * versionCode = MAJOR * 10_000_000 + MINOR * 100_000 + PATCH * 1_000 + COMMITS_SINCE_TAG
 */
fun deriveVersionCode(describe: String): Int {
  val match = describeRegex.matchEntire(describe) ?: return 1
  val (major, minor, patch, commits) = match.destructured
  return major.toInt() * 10_000_000 +
      minor.toInt() * 100_000 +
      patch.toInt() * 1_000 +
      (commits.toIntOrNull() ?: 0)
}

val gitVersionName: Provider<String> =
    gitCommand("describe", "--tags", "--always", "--dirty")
        .orElse(archiveDescribe())
        .orElse("unknown")

val gitVersionCode: Provider<Int> = gitVersionName.map { deriveVersionCode(it) }

android {
  namespace = "com.calindora.follow"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.calindora.follow"
    minSdk = 33
    targetSdk = 37
    versionCode = gitVersionCode.get()
    versionName = gitVersionName.get()

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val store = signingValue("storeFile", "FOLLOW_KEYSTORE_PATH").orNull
      val storePass = signingValue("storePassword", "FOLLOW_KEYSTORE_PASSWORD").orNull
      val alias = signingValue("keyAlias", "FOLLOW_KEY_ALIAS").orNull
      val keyPass = signingValue("keyPassword", "FOLLOW_KEY_PASSWORD").orNull

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

  testOptions {
    // Stub Android framework calls (e.g. `android.util.Log`) to return defaults in JVM unit tests
    // instead of throwing. Lets production code that logs through `Log.w` run uninstrumented.
    unitTests { isReturnDefaultValues = true }
  }

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
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging.interceptor)
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  implementation(libs.tink.android)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
