
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.tools.texturepacker.TexturePacker
import com.badlogic.gdx.utils.Json
import com.google.common.io.Files
import com.unciv.build.BuildConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files as NioFiles
import java.nio.file.attribute.BasicFileAttributes

plugins {
    id("kotlin")
}

sourceSets {
    main {
        java.srcDir("src/")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}
java {
    // required for building Unciv with a Java version higher than 24 (e.g. Java 25)
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_1_8
}

val mainClassName = "com.unciv.app.desktop.DesktopLauncher"
val assetsDir = file("../android/assets")
val discordDir = file("discord_rpc")
val deployFolder = file("../deploy")

tasks.register<JavaExec>("run") {
    dependsOn(tasks.getByName("classes"))
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
    workingDir = assetsDir
    isIgnoreExitValue = true
}

tasks.register<JavaExec>("debug") {
    dependsOn(tasks.getByName("classes"))
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
    workingDir = assetsDir
    isIgnoreExitValue = true
    debug = true
}

tasks.register<Jar>("dist") { // Compiles the jar file
    dependsOn(tasks.getByName("classes"))

    // META-INF/INDEX.LIST and META-INF/io.netty.versions.properties are duplicated, but I don't know why
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(files(sourceSets.main.get().output.resourcesDir))
    from(files(sourceSets.main.get().output.classesDirs))
    // see Laurent1967's comment on https://github.com/libgdx/libgdx/issues/5491
    from({
        (
            configurations.runtimeClasspath.get().resolve() // kotlin coroutine classes live here, thanks https://stackoverflow.com/a/59021222
            + configurations.compileClasspath.get().resolve()
        ).map { if (it.isDirectory) it else zipTree(it) }})
    from(files(assetsDir))
    exclude("mods", "SaveFiles", "MultiplayerFiles", "GameSettings.json", "lasterror.txt")
    // This is for the .dll and .so files to make the Discord RPC work on all desktops
    from(files(discordDir))
    archiveFileName.set("${BuildConfig.appName}.jar")

    manifest {
        attributes(mapOf("Main-Class" to mainClassName, "Specification-Version" to BuildConfig.appVersion))
    }
}

// Headless regeneration of the committed split atlases (../android/assets/<Name>.atlas + .png) from the
// ../android/Images.<Name>/ source dirs. Mirrors what DesktopLauncher's ImagePacker does on a dev run -
// same 2048 page size / padding, and the `*Icons` folders get a Linear magnification filter (matching the
// committed atlas headers) while others keep MipMapLinearLinear. Only repacks atlases whose source images
// are newer than the atlas, so unchanged categories stay byte-identical. The fat-jar `dist` bundles these
// committed atlases (it does NOT pack), so run this after adding/removing source PNGs:
//     ./gradlew :desktop:packImages
tasks.register("packImages") {
    val imageExtensions = listOf("png", "jpg", "jpeg")
    val sourceRoot = file("../android")
    val outDir = assetsDir

    fun defaultSettings(linearMag: Boolean) = TexturePacker.Settings().apply {
        maxWidth = 2048
        maxHeight = 2048
        combineSubdirectories = true
        pot = true
        fast = true
        paddingX = 8
        paddingY = 8
        duplicatePadding = true
        filterMin = Texture.TextureFilter.MipMapLinearLinear
        filterMag = if (linearMag) Texture.TextureFilter.Linear else Texture.TextureFilter.MipMapLinearLinear
    }

    fun isOutdated(input: File, atlasName: String): Boolean {
        val atlasFile = File(outDir, "$atlasName.atlas")
        if (!atlasFile.exists() || !File(outDir, "$atlasName.png").exists()) return true
        val atlasModTime = atlasFile.lastModified()
        return input.walkTopDown().any { f ->
            if (!f.isFile) return@any false
            if (f.extension !in imageExtensions && f.name != "TexturePacker.settings") return@any false
            val attr = NioFiles.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            f.lastModified() > atlasModTime || attr.creationTime().toMillis() > atlasModTime
        }
    }

    doLast {
        // Each ../android/Images.<Name> folder -> <Name>.atlas (a plain "Images" folder would map to "game",
        // but this fork uses only the split layout, so there is none).
        val folders = sourceRoot.listFiles()!!
            .filter { it.isDirectory && it.nameWithoutExtension == "Images" && it.name != "Images" }
            .sortedBy { it.name }
        var packed = 0
        for (folder in folders) {
            val atlasName = folder.extension  // "Images.NationIcons" -> "NationIcons"
            if (!isOutdated(folder, atlasName)) continue
            val settingsFile = File(folder, "TexturePacker.settings")
            val settings = if (settingsFile.exists())
                Json().fromJson(TexturePacker.Settings::class.java, settingsFile.reader(Charsets.UTF_8))
            else defaultSettings(folder.name.endsWith("Icons"))
            TexturePacker.process(settings, folder.path, outDir.path, atlasName)
            logger.lifecycle("packImages: regenerated $atlasName.atlas")
            packed++
        }
        logger.lifecycle("packImages: $packed of ${folders.size} atlas(es) regenerated")
    }
}


enum class Platform(val desc: String) {
    Windows32("windows32"), Windows64("windows64"), Linux32("linux32"), Linux64("linux64"), MacOS("mac");
}

class PackrConfig(
    var platform: Platform? = null,
    var jdk: String? = null,
    var executable: String? = null,
    var classpath: List<String>? = null,
    var removePlatformLibs: List<String>? = null,
    var mainClass: String? = null,
    var vmArgs: List<String>? = null,
    var minimizeJre: String? = null,
    var cacheJre: File? = null,
    var resources: List<File>? = null,
    var outDir: File? = null,
    var platformLibsOutDir: File? = null,
    var iconResource: File? = null,
    var bundleIdentifier: String? = null
)

for (platform in Platform.values()) {
    val platformName = platform.toString()

    tasks.create("packr${platformName}") {
        // This task assumes that 'dist' has already been called - does not 'gradle depend' on it
        // so we can run 'dist' from one job and then run the packr builds from a different job

        // Needs to be here and not in doLast because the zip task depends on the outDir
        val jarFile = "$rootDir/desktop/build/libs/${BuildConfig.appName}.jar"
        val outputDir = file("packr")
        

        doLast {
            //  https://gist.github.com/seanf/58b76e278f4b7ec0a2920d8e5870eed6
            fun String.runCommand(workingDir: File) {
                val process = ProcessBuilder(*split(" ").toTypedArray())
                    .directory(workingDir)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()

                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroy()
                    throw RuntimeException("execution timed out: $this")
                }
                if (process.exitValue() != 0) {
                    throw RuntimeException("execution failed with code ${process.exitValue()}: $this")
                }
                println(process.inputStream.bufferedReader().readText())
            }


            if (outputDir.exists()) delete(outputDir)

            // Requires that both packr and the jre are downloaded, as per buildAndDeploy.yml, "Upload to itch.io"

            val jdkFile = when (platform) {
                Platform.Linux64 -> "jre-linux-64.tar.gz"
                Platform.Windows64 -> "jdk-windows-64.zip"
                else -> "jre-macOS.tar.gz"
            }

            val platformNameForPackrCmd =
                    if (platform == Platform.MacOS) "mac"
                    else platform.name.lowercase()

            val command = "java -jar $rootDir/packr-all-4.0.0.jar" +
                    " --platform $platformNameForPackrCmd" +
                    " --jdk $jdkFile" +
                    " --executable Unciv" +
                    " --classpath $jarFile" +
                    " --mainclass $mainClassName" +
                    " --vmargs Xmx4G " +
                    " --output $outputDir"
            command.runCommand(rootDir)
            Files.copy(File("$rootDir/extraImages/Icons/Unciv.ico"), File(outputDir, "Unciv.ico"))
        }

        tasks.register<Zip>("zip${platformName}") {
            archiveFileName.set("${BuildConfig.appName}-${platformName}.zip")
            from(outputDir)
            destinationDirectory.set(deployFolder)
        }

        finalizedBy("zip${platformName}")
    }
}

tasks.register<Zip>("zipLinuxFilesForJar") {
    archiveFileName.set("linuxFilesForJar.zip")
    from(file("linuxFilesForJar"))
    destinationDirectory.set(deployFolder)
}
