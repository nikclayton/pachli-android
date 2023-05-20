/*
 * Copyright 2023 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>.
 */

plugins {
    kotlin("plugin.serialization") version "1.8.21"
}

application {
    mainClass.set("app.tusky.mkrelease.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

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

    // APIs (GitHub)
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")

    implementation("com.damnhandy:handy-uri-templates:2.1.7")

    // Request logging
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // Parsing
    //implementation("com.github.h0tk3y.betterParse:better-parse:0.4.4")

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
