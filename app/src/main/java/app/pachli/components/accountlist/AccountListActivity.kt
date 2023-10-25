/* Copyright 2017 Andrew Dawson
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
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package app.pachli.components.accountlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.commit
import app.pachli.BottomSheetActivity
import app.pachli.R
import app.pachli.databinding.ActivityAccountListBinding
import app.pachli.interfaces.AppBarLayoutHost
import app.pachli.network.StatusId
import app.pachli.util.viewBinding
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.parcelize.Parcelize

@AndroidEntryPoint
class AccountListActivity : BottomSheetActivity(), AppBarLayoutHost {
    private val binding: ActivityAccountListBinding by viewBinding(ActivityAccountListBinding::inflate)

    override val appBarLayout: AppBarLayout
        get() = binding.includedToolbar.appbar

    @Parcelize
    sealed class Type : Parcelable{
        data class Follows(val accountId: String): Type()
        data class Followers(val accountId: String): Type()
        data object Blocks: Type()
        data object Mutes: Type()
        data object FollowRequests: Type()
        data class RebloggedBy(val statusId: StatusId): Type()
        data class Favourited(val statusId: StatusId): Type()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val type = intent.getParcelableExtra<Type>(EXTRA_TYPE)!!

        setSupportActionBar(binding.includedToolbar.toolbar)
        supportActionBar?.apply {
            when (type) {
                is Type.Blocks -> setTitle(R.string.title_blocks)
                is Type.Mutes -> setTitle(R.string.title_mutes)
                is Type.FollowRequests -> setTitle(R.string.title_follow_requests)
                is Type.Followers -> setTitle(R.string.title_followers)
                is Type.Follows -> setTitle(R.string.title_follows)
                is Type.RebloggedBy -> setTitle(R.string.title_reblogged_by)
                is Type.Favourited -> setTitle(R.string.title_favourited_by)
            }
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        supportFragmentManager.commit {
            replace(R.id.fragment_container, AccountListFragment.newInstance(type))
        }
    }

    companion object {
        private const val EXTRA_TYPE = "type"

        fun newIntent(context: Context, type: Type): Intent {
            return Intent(context, AccountListActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
            }
        }
    }
}
