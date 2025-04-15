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

plugins {
    alias(libs.plugins.pachli.android.library)
    alias(libs.plugins.pachli.android.hilt)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "app.pachli.feature.accountrouter"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
    }
}

dependencies {
    implementation(projects.core.activity)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.navigation)
    implementation(projects.core.network)
        ?.because("Payload.Notification relies on the network type")
//    implementation(projects.core.preferences)
    implementation(projects.core.ui)
//
    implementation(libs.bundles.androidx)
//    implementation(libs.androidx.webkit)
//
//    // Loading the logo
    implementation(libs.bundles.glide)
        ?.because("Loading logo and avatar images")
}
