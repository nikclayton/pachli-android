/*
 * Copyright 2023 Tusky Contributors
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

package app.pachli.components.filters

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import app.pachli.R
import app.pachli.core.ui.await

internal suspend fun Activity.showDeleteFilterDialog(filterTitle: String) = AlertDialog.Builder(this)
    .setMessage(getString(R.string.dialog_delete_filter_text, filterTitle))
    .setCancelable(true)
    .create()
    .await(R.string.dialog_delete_filter_positive_action, android.R.string.cancel)
