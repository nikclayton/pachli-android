/*
 * Copyright 2024 Pachli Association
 *
 * This file is a part of Pachli.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Pachli is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Pachli; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.pachli.mkrelease

import app.pachli.mkrelease.Section.Features
import app.pachli.mkrelease.Section.Fixes
import app.pachli.mkrelease.Section.Translations
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlinx.serialization.Serializable

val WEBSITE_DIR = Path("\\\\wsl.localhost\\Ubuntu\\home\\nik\\pachli\\website")

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM")

private const val INKSCAPE = "C:\\Program Files\\Inkscape\\bin\\inkscape.exe"

@Serializable
sealed interface BlogStep {
    abstract fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec?
    fun desc(): String = this.javaClass.simpleName
}

class Blog : CliktCommand(name = "blog") {
    private val globalFlags by requireObject<GlobalFlags>()

    override fun run() {
        val config = Config.from(CONFIG_FILE)
        val releaseSpec = ReleaseSpec.from(SPEC_FILE)

        val stepStyle = TextStyles.bold

        val steps = listOf(
            // Create assets directory
            EnsureAssetsDirectoryExists,
            // Create release image
            CreateReleaseImage,
            // Create blog post
            CreateBlogPost
        )

        for (step in steps) {
            terminal.println(stepStyle("-> ${step.desc()}"))
            runCatching {
                step.run(terminal, config, releaseSpec)
            }.onFailure { t->
                terminal.danger(t.message)
                return
            }
        }
    }
}

/**
 * Full path to asset dir for this release.
 *
 * For example, ".../assets/posts/2024-07-xx-2.7.0-release/"
 */
fun getAssetDir(spec: ReleaseSpec): Path {
    val now = LocalDate.now()
    // 2024-06-xx-2.6.0-release
    val postDir = "${formatter.format(now)}-xx-${spec.prevVersion.versionName()}-release"
    val assetsDir = WEBSITE_DIR / Path("assets/posts") / Path(postDir)
    return assetsDir
}

@Serializable
data object EnsureAssetsDirectoryExists : BlogStep {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val assetsDir = getAssetDir(spec)

        t.info(assetsDir)

        try {
            assetsDir.toFile().mkdirs()
        } catch (e: Exception) {
            t.warning("Error: ${e.cause}")
            t.confirm("Continue?", abort =true)
        }

        return null
    }
}

data object CreateReleaseImage : BlogStep {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        // Open image
        // Replace "2.6.0</tspan>" with new version number
        val releaseImageTemplateFile = WEBSITE_DIR / Path("pachli_release_header.svg")
        val newReleaseImage = releaseImageTemplateFile.readText().replace("2.6.0</tspan>",
            "${spec.prevVersion.versionName()}</tspan>")

        val newReleaseImageSvgPath = (getAssetDir(spec) / Path("og_image.svg"))
        val newReleaseImagePngPath = newReleaseImageSvgPath.parent / "og_image.png"
        val newReleaseImageSvgFile = newReleaseImageSvgPath.toFile()
        newReleaseImageSvgFile.writeText(newReleaseImage)

        t.info(newReleaseImageSvgPath)
        t.info(newReleaseImagePngPath)

        // Launch inkscape to convert the image to PNG
        val result = ProcessBuilder(INKSCAPE, newReleaseImageSvgFile.absolutePath,
            "-o", newReleaseImagePngPath.absolutePathString())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        t.info("Inkscape result: $result")
        return null
    }
}

data object CreateBlogPost : BlogStep {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val postsPath = WEBSITE_DIR / Path("_posts")

        val now = LocalDate.now()
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val date = dateFormatter.format(now)

        val dir = "${formatter.format(now)}-xx-${spec.prevVersion.versionName()}-release"
        val assetPath = getAssetDir(spec)

        val version = spec.prevVersion.versionName()

        val title = "Pachli $version released"

        val template = """
---
layout: post
title: "$title"
date: "$date 09:00:00 +0200"
categories: pachli
image:
  path: /assets/posts/${dir}/og_image.png
---
Pachli $version is now available. This release TODO

<!--more-->

## New features and other improvements

TODO

### Updates to translations

Languages with updated translations are:

- Finnish by [Kalle Kniivilä](https://github.com/pachli/pachli-android/commits?author=kalle.kniivila@gmail.com)
- Spanish by [Miles Krell](https://github.com/pachli/pachli-android/commits?author=noreply@mileskrell.com)
- Swedish by [Luna Jernberg](https://github.com/pachli/pachli-android/commits?author=bittin@reimu.nl)

If you would like to help improve Pachli's translation in to your language there's [information on how you can contribute](https://github.com/pachli/pachli-android/blob/main/docs/contributing/translate.md).

## Significant bug fixes

### 1

### 2

### 3

## Thank you

Thank you to everyone who took the time to report issues and provide additional followup information and screenshots.
        """.trimIndent()

        t.info(template)

        (postsPath / "${dir}.md").toFile().writeText(template)

        return null
    }
}



fun createBlogFromChangelog(t: Terminal, changeLogEntries: Map<Section, List<LogEntry>>, nextVersionName: String) {
    val blog = File(".", "blog.md")
    if (blog.exists()) blog.delete()
    blog.createNewFile()
    val w = blog.printWriter()

    w.println(
        """
        Pachli $nextVersionName is now available. [TODO: Describe goal of this release]

        Read on for more details about this, and other changes in this release.

        <!-- more -->
        """.trimIndent(),
    )

    val features = changeLogEntries[Features]
    features?.let {
        w.println(
            """
            ## New features and other improvements
            """.trimIndent(),
        )

        features.forEach {
            w.println(
                """
                    ### ${it.text}
                """.trimIndent(),
            )
        }
    }

    val translations = changeLogEntries[Translations]
    translations?.let {
        w.println(
            """
            ### Updates to translations

            The following people submitted changes to the different Pachli translations.
            """.trimIndent(),
        )

        it.forEach { entry ->
            w.println("- ${entry.text} by ${entry.author}")
        }

        w.println(
            """
If you would like to help improve Pachli's translation in to your language there's [information on how you can contribut
e](https://github.com/pachli/pachli-android/blob/main/docs/contributing/translate.md).
            """.trimIndent(),
        )
    }

    val fixes = changeLogEntries[Fixes]
    fixes?.let {
    }
}
