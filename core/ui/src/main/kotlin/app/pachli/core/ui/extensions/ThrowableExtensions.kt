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

package app.pachli.core.ui.extensions

import android.content.Context
import androidx.annotation.DrawableRes
import app.pachli.core.common.string.unicodeWrap
import app.pachli.core.network.R as NR
import app.pachli.core.network.extensions.getServerErrorMessage
import app.pachli.core.ui.R
import com.squareup.moshi.JsonDataException
import java.io.IOException
import retrofit2.HttpException

/** @return A drawable resource to accompany the error message for this throwable */
@DrawableRes
fun Throwable.getDrawableRes(): Int = when (this) {
    is IOException -> R.drawable.errorphant_offline
    is HttpException -> {
        if (code() == 404) {
            R.drawable.elephant_friend_empty
        } else {
            R.drawable.errorphant_offline
        }
    }
    else -> R.drawable.errorphant_error
}

/** @return A unicode-wrapped string error message for this throwable */
fun Throwable.getErrorString(context: Context): String = (
    getServerErrorMessage() ?: when (this) {
        is IOException -> String.format(context.getString(NR.string.error_network_fmt), localizedMessage)
        is HttpException -> {
            if (code() == 404) {
                String.format(context.getString(NR.string.error_404_not_found_fmt), localizedMessage)
            } else {
                String.format(context.getString(NR.string.error_generic_fmt), localizedMessage)
            }
        }
        is JsonDataException -> String.format(context.getString(NR.string.error_json_data_fmt), localizedMessage)
        else -> String.format(context.getString(NR.string.error_generic_fmt), localizedMessage)
    }
    ).unicodeWrap()
