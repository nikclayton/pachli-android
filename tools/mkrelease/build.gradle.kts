/*
 * Copyright 2023 Pachli Association
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

plugins {
    kotlin("plugin.serialization") version "1.9.0"
}

application {
    mainClass.set("app.pachli.mkrelease.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.8.21")

    // Mordant (Terminal UI)
    implementation("com.github.ajalt.mordant:mordant:2.0.0-beta13")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")

    // JGit with BouncyCastle (GPG) support
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.5.0.202303070854-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.gpg.bc:6.5.0.202303070854-r")

    // Gradle API
    // https://docs.gradle.org/current/userguide/third_party_integration.html#embedding
    implementation("org.gradle:gradle-tooling-api:8.1.1")
    implementation("com.android.tools.build:gradle:8.0.0")

    // GitHub API
    implementation("org.kohsuke:github-api:1.316-SNAPSHOT")
    // implementation("com.github.nikclayton:github-api:1595-makelatest-SNAPSHOT")
    // implementation(files("\\\\wsl.localhost\\Ubuntu\\home\\nik\\.m2\\repository\\org\\kohsuke\\github-api\\1.316-SNAPSHOT\\github-api-1.316-SNAPSHOT.jar"))

    // Gitlab API
    implementation("org.gitlab4j:gitlab4j-api:5.2.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:4.0.0-beta-28")
    implementation("ch.qos.logback:logback-classic:1.3.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.2") // for parameterized tests
}

tasks.test {
    useJUnitPlatform()
}

// Fixes:
//   The input line is too long.
//   The syntax of the command is incorrect.
// error with the Android Gradle plugin on Windows
// https://gist.github.com/romixch/8f5dbcbb473d499c67eae1fbf34007bb
tasks.withType<CreateStartScripts>(CreateStartScripts::class.java) {
    doLast {
        var text = windowsScript.readText()
        text = text.replaceFirst(Regex("(set CLASSPATH=%APP_HOME%\\\\lib\\\\).*"), "set CLASSPATH=%APP_HOME%\\\\lib\\\\*")
        windowsScript.writeText(text)
    }
}
