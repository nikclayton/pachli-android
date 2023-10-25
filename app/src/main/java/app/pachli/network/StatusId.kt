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

package app.pachli.network

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@JvmInline
value class StatusId(val s: String) : CharSequence by s, Parcelable, Comparable<StatusId> {
    override fun compareTo(other: StatusId): Int {
        return when {
            this.length < other.length -> -1
            this.length > other.length -> +1
            else -> this.s.compareTo(other.s)
        }
    }
}

//{
//    constructor(parcel: Parcel) : this(parcel.readString()!!) {
//    }
//
//    override fun writeToParcel(parcel: Parcel, flags: Int) {
//        parcel.writeString(s)
//    }
//
//    override fun describeContents(): Int {
//        return 0
//    }
//
//    companion object CREATOR : Parcelable.Creator<StatusId> {
//        override fun createFromParcel(parcel: Parcel): StatusId {
//            return StatusId(parcel)
//        }
//
//        override fun newArray(size: Int): Array<StatusId?> {
//            return arrayOfNulls(size)
//        }
//    }
//}

/**
 * A < B (strictly) by length and then by content.
 * Examples:
 * "abc" < "bcd"
 * "ab"  < "abc"
 * "cb"  < "abc"
 * not: "ab" < "ab"
 * not: "abc" > "cb"
 */
//fun StatusId.isLessThan(other: StatusId): Boolean {
//    return when {
//        this.length < other.length -> true
//        this.length > other.length -> false
//        else -> this < other
//    }
//}

/**
 * A <= B (strictly) by length and then by content.
 * Examples:
 * "abc" <= "bcd"
 * "ab"  <= "abc"
 * "cb"  <= "abc"
 * "ab"  <= "ab"
 * not: "abc" > "cb"
 */
//fun StatusId.isLessThanOrEqual(other: StatusId): Boolean {
//    return this == other || isLessThan(other)
//}
