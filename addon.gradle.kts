import java.time.Year

val templateModGroup = "com.myname.mymodid"
val templateModId = "mymodid"
val templateModName = "MyMod"
val templateMainClass = "MyMod"
val templateSourceDir = "src/main/java/com/myname/mymodid"

fun Project.readInitProperty(name: String): String? =
    (findProperty("init.$name") as String?)?.trim()?.takeIf { it.isNotEmpty() }

fun prompt(label: String, default: String = ""): String {
    val suffix = if (default.isNotEmpty()) " [$default]" else ""
    val promptText = "$label$suffix: "

    System.console()?.let { console ->
        return console.readLine(promptText)?.trim().orEmpty().ifEmpty { default }
    }

    // Gradle daemon does not attach a console; stdin fallback works with --no-daemon in a real terminal.
    return try {
        System.out.print(promptText)
        System.out.flush()
        java.io.BufferedReader(java.io.InputStreamReader(System.`in`, Charsets.UTF_8))
            .readLine()
            ?.trim()
            .orEmpty()
            .ifEmpty { default }
    } catch (exception: Exception) {
        throw GradleException(
            "Interactive input is unavailable. Use scripts/init-project.ps1 or scripts/init-project.sh, " +
                "run .\\gradlew.bat initProject --no-daemon --no-configuration-cache from a terminal, " +
                "or pass -Pinit.modName=... -Pinit.modId=... -Pinit.modGroup=... and other init.* properties.",
            exception,
        )
    }
}

fun toModId(modName: String): String =
    modName.lowercase().replace(Regex("[^a-z0-9]+"), "")

fun toMainClassName(modName: String): String =
    modName.split(Regex("[^a-zA-Z0-9]+"))
        .filter { it.isNotEmpty() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        .ifEmpty { templateMainClass }

fun toDefaultModGroup(author: String, modId: String): String {
    val authorPart = author.split(Regex("[^a-zA-Z0-9]+"))
        .filter { it.isNotEmpty() }
        .joinToString("") { part -> part.lowercase() }
        .ifEmpty { "example" }
    return "com.$authorPart.$modId"
}

fun updateGradleProperty(file: java.io.File, key: String, value: String) {
    val pattern = Regex("""^(\s*)$key\s*=.*$""")
    val lines = file.readLines().toMutableList()
    var replaced = false
    for (index in lines.indices) {
        if (pattern.matches(lines[index])) {
            lines[index] = "${pattern.find(lines[index])!!.groupValues[1]}$key = $value"
            replaced = true
            break
        }
    }
    if (!replaced) {
        lines.add("$key = $value")
    }
    file.writeText(lines.joinToString("\n") + "\n")
}

fun transformJavaSource(
    content: String,
    modGroup: String,
    modId: String,
    modName: String,
    mainClass: String,
): String {
    var result = content
        .replace(templateModGroup, modGroup)
        .replace(templateModId, modId)
        .replace("\"$templateModName\"", "\"$modName\"")
        .replace(Regex("""\b$templateMainClass\b"""), mainClass)
        .replace("I am $mainClass at version", "I am $modName at version")
    return result
}

fun transformJavaTree(root: java.io.File, modGroup: String, modId: String, modName: String, mainClass: String) {
    if (!root.exists()) {
        return
    }
    root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
        file.writeText(transformJavaSource(file.readText(), modGroup, modId, modName, mainClass))
    }
}

fun updateMcModInfo(
    file: java.io.File,
    description: String,
    url: String,
    authors: List<String>,
) {
    var content = file.readText()
    content = content.replace(
        Regex(""""description"\s*:\s*"[^"]*""""),
        """"description": "${description.replace("\"", "\\\"")}"""",
    )
    content = content.replace(
        Regex(""""url"\s*:\s*"[^"]*""""),
        """"url": "$url"""",
    )
    val authorJson = authors.joinToString(", ") { """"$it"""" }
    content = content.replace(
        Regex(""""authorList"\s*:\s*\[[^\]]*]"""),
        """"authorList": [$authorJson]""",
    )
    file.writeText(content)
}

fun applyServerOnlyAnnotation(mainClassFile: java.io.File, serverOnly: Boolean) {
    var content = mainClassFile.readText()
    val remoteVersionsClause = """, acceptableRemoteVersions = "*""""
    val remoteVersionsPattern = Regex(""",\s*acceptableRemoteVersions\s*=\s*"\*"""")

    content = if (serverOnly) {
        if (remoteVersionsPattern.containsMatchIn(content)) {
            content
        } else {
            content.replace(
                Regex("""(acceptedMinecraftVersions\s*=\s*"\[[^\]]+\]")(\s*\))"""),
                "$1$remoteVersionsClause$2",
            )
        }
    } else {
        content.replace(remoteVersionsPattern, "")
    }

    mainClassFile.writeText(content)
}

fun findMainModClassFile(sourceRoot: java.io.File): java.io.File? =
    sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .firstOrNull { it.readText().contains("@Mod(") }

fun parseBooleanProperty(value: String?): Boolean? =
    value?.trim()?.lowercase()?.let {
        when (it) {
            "true", "yes", "y", "1" -> true
            "false", "no", "n", "0" -> false
            else -> null
        }
    }

tasks.register("initProject") {
    group = "GTNH Buildscript"
    description = "Configure template placeholders for a new mod (name, id, package, sources)"
    notCompatibleWithConfigurationCache("Rewrites template project files")

    doLast {
        val force = (project.findProperty("init.force") as String?)?.toBooleanStrictOrNull() == true
        val oldSourceRoot = layout.projectDirectory.dir(templateSourceDir).asFile

        if (!oldSourceRoot.exists()) {
            if (!force) {
                throw GradleException(
                    "Template sources not found at $templateSourceDir. " +
                        "The project may already be initialized. Use -Pinit.force=true to run property and mcmod.info updates only.",
                )
            }
            logger.lifecycle("Template sources missing; updating configuration files only.")
        }

        val modName = project.readInitProperty("modName") ?: prompt("Mod name (human-readable)", templateModName)
        val defaultModId = toModId(modName).ifEmpty { templateModId }
        val modId = project.readInitProperty("modId") ?: prompt("Mod ID (lowercase, e.g. mymod)", defaultModId)
        val author = project.readInitProperty("author") ?: prompt("Author name", "Developer")
        val defaultModGroup = toDefaultModGroup(author, modId)
        val modGroup = project.readInitProperty("modGroup") ?: prompt("Root package (modGroup)", defaultModGroup)
        val mainClass = project.readInitProperty("mainClass") ?: prompt("Main @Mod class name", toMainClassName(modName))
        val description = project.readInitProperty("description")
            ?: prompt("Short mod description", "A Minecraft 1.7.10 Forge mod.")
        val url = project.readInitProperty("url") ?: prompt("Project URL (optional)", "")
        val writeLicense = project.readInitProperty("license")?.toBooleanStrictOrNull()
            ?: prompt("Create LICENSE from LICENSE-template? (y/N)", "n").let { it.equals("y", true) || it.equals("yes", true) }
        val serverOnlyMod = project.readInitProperty("serverOnly")?.let { parseBooleanProperty(it) }
            ?: prompt("Server-only mod (clients without the mod can connect)? (y/N)", "n").let { it.equals("y", true) || it.equals("yes", true) }
        val copyright = when {
            !writeLicense -> project.readInitProperty("copyright") ?: author
            else -> project.readInitProperty("copyright") ?: prompt("Copyright holder for LICENSE", author)
        }

        require(modId.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            "Mod ID must start with a letter and contain only lowercase letters, digits, and underscores: $modId"
        }
        require(modGroup.matches(Regex("^[a-zA-Z_][\\w.]*$"))) {
            "modGroup must be a valid Java package name: $modGroup"
        }
        require(mainClass.matches(Regex("^[A-Z][\\w]*$"))) {
            "Main class name must be a valid PascalCase Java identifier: $mainClass"
        }

        val gradleProperties = layout.projectDirectory.file("gradle.properties").asFile
        updateGradleProperty(gradleProperties, "modName", modName)
        updateGradleProperty(gradleProperties, "modId", modId)
        updateGradleProperty(gradleProperties, "modGroup", modGroup)
        updateGradleProperty(gradleProperties, "generateGradleTokenClass", "$modGroup.Tags")
        updateGradleProperty(gradleProperties, "serverOnlyMod", serverOnlyMod.toString())

        var mainModClassFile: java.io.File? = null

        if (oldSourceRoot.exists()) {
            val newSourceRoot = layout.projectDirectory.dir("src/main/java/${modGroup.replace('.', '/')}").asFile
            newSourceRoot.parentFile.mkdirs()
            if (!oldSourceRoot.renameTo(newSourceRoot)) {
                oldSourceRoot.copyRecursively(newSourceRoot, overwrite = true)
                oldSourceRoot.deleteRecursively()
            }

            transformJavaTree(newSourceRoot, modGroup, modId, modName, mainClass)

            val oldMainFile = java.io.File(newSourceRoot, "$templateMainClass.java")
            val newMainFile = java.io.File(newSourceRoot, "$mainClass.java")
            if (mainClass != templateMainClass) {
                if (!oldMainFile.exists()) {
                    throw GradleException("Expected main class file not found: ${oldMainFile.path}")
                }
                if (!oldMainFile.renameTo(newMainFile)) {
                    oldMainFile.copyTo(newMainFile, overwrite = true)
                    oldMainFile.delete()
                }
            }

            mainModClassFile = newMainFile.takeIf { it.exists() } ?: oldMainFile.takeIf { it.exists() }
        } else {
            val sourceRoot = layout.projectDirectory.dir("src/main/java/${modGroup.replace('.', '/')}").asFile
            mainModClassFile = java.io.File(sourceRoot, "$mainClass.java").takeIf { it.exists() }
                ?: findMainModClassFile(sourceRoot)
        }

        mainModClassFile?.let { applyServerOnlyAnnotation(it, serverOnlyMod) }
            ?: logger.warn("Main @Mod class not found; skipped serverOnlyMod annotation update.")

        val mcModInfo = layout.projectDirectory.file("src/main/resources/mcmod.info").asFile
        updateMcModInfo(
            mcModInfo,
            description,
            url,
            author.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )

        if (writeLicense) {
            val licenseTemplate = layout.projectDirectory.file("LICENSE-template").asFile
            require(licenseTemplate.exists()) { "LICENSE-template not found." }
            val licenseText = licenseTemplate.readText()
                .replace("<year>", Year.now().toString())
                .replace("<copyright holders>", copyright)
            layout.projectDirectory.file("LICENSE").asFile.writeText(licenseText)
        }

        logger.lifecycle("")
        logger.lifecycle("Project initialized:")
        logger.lifecycle("  modName     = $modName")
        logger.lifecycle("  modId       = $modId")
        logger.lifecycle("  modGroup    = $modGroup")
        logger.lifecycle("  main class  = $mainClass")
        logger.lifecycle("  serverOnly  = $serverOnlyMod")
        logger.lifecycle("")
        logger.lifecycle("Next steps:")
        logger.lifecycle("  .\\gradlew.bat setupDecompWorkspace")
        logger.lifecycle("  .\\gradlew.bat build")
    }
}

tasks.register("syncServerOnlyMod") {
    group = "GTNH Buildscript"
    description = "Applies or removes acceptableRemoteVersions on the @Mod class from serverOnlyMod in gradle.properties"
    notCompatibleWithConfigurationCache("Rewrites main mod source file")

    doLast {
        val modGroup = (project.findProperty("modGroup") as String?)?.trim().orEmpty()
        require(modGroup.isNotEmpty()) { "modGroup is not set in gradle.properties." }

        val serverOnlyMod = parseBooleanProperty(project.findProperty("serverOnlyMod") as String?)
            ?: throw GradleException("serverOnlyMod must be true or false in gradle.properties.")

        val sourceRoot = layout.projectDirectory.dir("src/main/java/${modGroup.replace('.', '/')}").asFile
        val mainModClassFile = findMainModClassFile(sourceRoot)
            ?: throw GradleException("Could not find a @Mod class under src/main/java/${modGroup.replace('.', '/')}.")

        applyServerOnlyAnnotation(mainModClassFile, serverOnlyMod)
        logger.lifecycle(
            "Updated ${mainModClassFile.name}: acceptableRemoteVersions = \"*\" is ${if (serverOnlyMod) "enabled" else "removed"}.",
        )
    }
}
