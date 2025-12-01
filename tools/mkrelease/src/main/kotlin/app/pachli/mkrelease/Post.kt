/*
 * Copyright (c) 2025 Pachli Association
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
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

@Serializable
sealed interface PostStep {
    fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec?
    fun desc(): String = this.javaClass.simpleName
}

/**
 * Map translator GitHub e-mail addresses to the text to use for attribution.
 * Ideally this is their Fediverse handle, but it might just be their name
 * if not known.
 *
 * A value of the empty string means "Use the user's reported display name
 * instead of their Fediverse handle". A missing entry is an error, as it
 * means we need to figure our their handle, and either include it here, or
 * explicitly use the empty string.
 */
val translatorDisplay = mapOf(
    "htetoh2006@outlook.com" to "", // ""--//--",
    "github@bjoernengel.de" to "@BjoernEngel@social.anoxinon.de",
    "182783629+doctorreditshere@users.noreply.github.com" to "@alkaf@pixelfed.fr",
    "aindriu80@gmail.com" to "", // "Aindriú Mac Giolla Eoin",
    "boffire@users.noreply.hosted.weblate.org" to "@ButterflyOfFire@mstdn.fr",
    "Gateway_31@protonmail.com" to "@Vac@crumb.lt",
    "weblate@turtle.garden" to "", // "Sunniva Løvstad",
    "sunniva@users.noreply.hosted.weblate.org" to "", // "Sunniva Løvstad",
    "dakilla@gmail.com" to "@LukaszHorodecki@pol.social",
    "rastislav.podracky@outlook.com" to "@Russssty@mastodon.social",
    "russssty@users.noreply.hosted.weblate.org" to "@Russssty@mastodon.social",
    "jumase@disroot.org" to "", // ""Juan M Sevilla",
    "jens@persson.cx" to "@MrShark@mathstodon.xyz",
    "anishprabu.t@gmail.com" to "", // ""தமிழ்நேரம்",
    "yurtpage+weblate@gmail.com" to "", // "Yurt Page",
    "tcloer@mac.com" to "@teezeh@ieji.de",
    "Edgars+Weblate@gaitenis.id.lv" to "", // "Edgars Andersons",
    "jrthwlate@users.noreply.hosted.weblate.org" to "Priit Jõerüüt",
    "kalle.kniivila@gmail.com" to "@kallekn@mastodonsweden.se",
    "weblate.delirium794@passmail.net" to "", // "Dizro"
    "correoxm@disroot.org" to "", // ?
    "kachelkaiser@htpst.de" to "@Kachelkaiser@social.tchncs.de",
    "realzero@protonmail.com" to "@BryanGreyson@social.tchncs.de",
)

/**
 * Generate initial text for the (posts) for this release.
 */
class Post : CliktCommand(name = "post") {
    private val globalFlags by requireObject<GlobalFlags>()

    override fun run() {
        val config = Config.from(CONFIG_FILE)
        val releaseSpec = ReleaseSpec.from(SPEC_FILE)

        val stepStyle = TextStyles.bold

        val steps = listOf(
            CreatePostText,
        )

        for (step in steps) {
            terminal.println(stepStyle("-> ${step.desc()}"))
            runCatching {
                step.run(terminal, config, releaseSpec)
            }.onFailure { t ->
                terminal.danger(t.message)
                return
            }
        }
    }
}

data class UtmLink(
    val base: String,
    val utmMedium: String?,
    val utmSource: String?,
    val utmCampaign: String?,
) {
    override fun toString(): String {
        val params = listOfNotNull(
            utmMedium?.let { "utm_medium=$utmMedium" },
            utmSource?.let { "utm_source=$utmSource" },
            utmCampaign?.let { "utm_campaign=$utmCampaign" },
        )

        return if (params.isEmpty()) {
            base
        } else {
            "$base?${params.joinToString("&")}"
        }
    }
}

@Serializable
data object CreatePostText : PostStep {
    override fun run(t: Terminal, config: Config, spec: ReleaseSpec): ReleaseSpec? {
        val version = spec.prevVersion.versionName()

        val title = "Pachli $version is released"

        val repo = config.repositoryMain
        val root = config.pachliMainRoot

        val git = ensureRepo(t, repo.gitUrl, root).also { it.ensureClean(t) }
        val changes = getChangelog(t, git, spec)
//        t.info("Got changelog: $changes")

        val features = changes[Features]?.joinToString("\n") { "- ${it.withoutLinks()}" }
        val fixes = changes[Section.Fixes]?.joinToString("\n") { "- ${it.withoutLinks()}" }

        val translations = changes[Section.Translations]
            ?.filter { it.author.emailAddress != "nik@ngo.org.uk" }
            ?.joinToString("\n") {
                val rxLang = "Update (.*) translations".toRegex()
                val lang = rxLang.find(it.text)?.groupValues[1]
                val displayText = translatorDisplay[it.author.emailAddress]?.ifEmpty { it.author.name }
                if (displayText == null) {
//                    t.danger("${it.author.emailAddress} not in translatorDisplay")
                    error("${it.author.emailAddress} not in translatorDisplay")
                }
                "- $lang by $displayText"
            }

        // Construct the URL for the release blog post. Looks like
        // https://pachli.app/pachli/2025/09/14/2.16.1-release.html?utm_medium=social&utm_source=mastodon&utm_campaign=release-2.16.1
        // Assumes this is being run on the same day as the blog post.
        val now = LocalDate.now()
        val dateAsPathFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        val dateAsPath = dateAsPathFormatter.format(now)
        val postLink = UtmLink(
            base = "https://pachli.app/pachli/$dateAsPath/$version-release.html",
            utmMedium = "social",
            utmSource = "mastodon",
            utmCampaign = "release-$version",
        )

        val urlBlogPost = postLink.toString()
        val urlNivenlyDiscord = postLink.copy(
            utmMedium = "discord",
            utmSource = "nivenly",
        )
        val urlRAndroidDiscord = postLink.copy(
            utmMedium = "discord",
            utmSource = "r-androiddev",
        )

        val template = """
---
# $title

## New features

$features

## Fixes

$fixes

$urlBlogPost

#MastoDev #AndroidDev
---
This release also includes translation updates:

$translations

It also benefits from feedback from (in no particular order): ...

My thanks to everyone who contributed to this release, and apologies to anyone I missed.
---

Other links:

Nivenly Discord:
$urlNivenlyDiscord

r/AndroidDev Discord:
$urlRAndroidDiscord
        """.trimIndent()

        t.info(template)
        return null
    }
}
