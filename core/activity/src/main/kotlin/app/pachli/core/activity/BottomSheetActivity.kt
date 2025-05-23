/*
 * Copyright 2018 Conny Duck
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

package app.pachli.core.activity

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import app.pachli.core.activity.extensions.TransitionKind
import app.pachli.core.activity.extensions.startActivityWithDefaultTransition
import app.pachli.core.activity.extensions.startActivityWithTransition
import app.pachli.core.navigation.AccountActivityIntent
import app.pachli.core.navigation.ViewThreadActivityIntent
import app.pachli.core.network.retrofit.MastodonApi
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Base class for all activities that open links
 *
 * Links are checked against the api if they are mastodon links so they can be opened in Pachli
 *
 * Subclasses must have a bottom sheet with Id item_status_bottom_sheet in their layout hierarchy
 */
abstract class BottomSheetActivity : BaseActivity() {

    lateinit var bottomSheet: BottomSheetBehavior<LinearLayout>
    var searchUrl: String? = null

    @Inject
    lateinit var mastodonApi: MastodonApi

    @Inject
    lateinit var openUrl: OpenUrlUseCase

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        val bottomSheetLayout: LinearLayout = findViewById(R.id.item_status_bottom_sheet)
        bottomSheet = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheet.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        cancelActiveSearch()
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            },
        )
    }

    open fun viewUrl(pachliAccountId: Long, url: String, lookupFallbackBehavior: PostLookupFallbackBehavior = PostLookupFallbackBehavior.OPEN_IN_BROWSER) {
        if (!looksLikeMastodonUrl(url)) {
            openLink(url)
            return
        }

        onBeginSearch(url)

        lifecycleScope.launch {
            mastodonApi.search(query = url, resolve = true).onSuccess { searchResult ->
                val (accounts, statuses) = searchResult.body
                if (getCancelSearchRequested(url)) return@onSuccess
                onEndSearch(url)

                statuses.firstOrNull()?.let {
                    viewThread(pachliAccountId, it.id, it.url)
                    return@onSuccess
                }

                // Some servers return (unrelated) accounts for url searches (#2804)
                // Verify that the account's url matches the query
                accounts.firstOrNull { it.url.equals(url, ignoreCase = true) }?.let {
                    viewAccount(pachliAccountId, it.id)
                    return@onSuccess
                }

                performUrlFallbackAction(url, lookupFallbackBehavior)
            }.onFailure {
                if (!getCancelSearchRequested(url)) {
                    onEndSearch(url)
                    performUrlFallbackAction(url, lookupFallbackBehavior)
                }
            }
        }
    }

    open fun viewThread(pachliAccountId: Long, statusId: String, url: String?) {
        if (!isSearching()) {
            val intent = ViewThreadActivityIntent(this, pachliAccountId, statusId, url)
            startActivityWithTransition(intent, TransitionKind.SLIDE_FROM_END)
        }
    }

    open fun viewAccount(pachliAccountId: Long, id: String) {
        val intent = AccountActivityIntent(this, pachliAccountId, id)
        startActivityWithDefaultTransition(intent)
    }

    protected open fun performUrlFallbackAction(url: String, fallbackBehavior: PostLookupFallbackBehavior) {
        when (fallbackBehavior) {
            PostLookupFallbackBehavior.OPEN_IN_BROWSER -> openLink(url)
            PostLookupFallbackBehavior.DISPLAY_ERROR -> Toast.makeText(this, getString(R.string.post_lookup_error_format, url), Toast.LENGTH_SHORT).show()
        }
    }

    @VisibleForTesting
    fun onBeginSearch(url: String) {
        searchUrl = url
        showQuerySheet()
    }

    @VisibleForTesting
    fun getCancelSearchRequested(url: String) = url != searchUrl

    @VisibleForTesting
    fun isSearching() = searchUrl != null

    @VisibleForTesting
    fun onEndSearch(url: String?) {
        if (url == searchUrl) {
            // Don't clear query if there's no match,
            // since we might just now be getting the response for a canceled search
            searchUrl = null
            hideQuerySheet()
        }
    }

    @VisibleForTesting
    fun cancelActiveSearch() {
        if (isSearching()) {
            onEndSearch(searchUrl)
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    open fun openLink(url: String) {
        openUrl(url)
    }

    private fun showQuerySheet() {
        bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun hideQuerySheet() {
        bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
    }

    companion object {
        // https://mastodon.foo.bar/@User
        // https://mastodon.foo.bar/@User/43456787654678
        // https://mastodon.foo.bar/users/User/statuses/43456787654678
        // https://pleroma.foo.bar/users/User
        // https://pleroma.foo.bar/users/9qTHT2ANWUdXzENqC0
        // https://pleroma.foo.bar/notice/9sBHWIlwwGZi5QGlHc
        // https://pleroma.foo.bar/objects/d4643c42-3ae0-4b73-b8b0-c725f5819207
        // https://friendica.foo.bar/profile/user
        // https://friendica.foo.bar/display/d4643c42-3ae0-4b73-b8b0-c725f5819207
        // https://misskey.foo.bar/notes/83w6r388br (always lowercase)
        // https://pixelfed.social/p/connyduck/391263492998670833
        // https://pixelfed.social/connyduck
        // https://gts.foo.bar/@goblin/statuses/01GH9XANCJ0TA8Y95VE9H3Y0Q2
        // https://gts.foo.bar/@goblin
        // https://foo.microblog.pub/o/5b64045effd24f48a27d7059f6cb38f5
        @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        fun looksLikeMastodonUrl(urlString: String): Boolean {
            val uri: URI
            try {
                uri = URI(urlString)
            } catch (e: URISyntaxException) {
                return false
            }

            if (uri.query != null ||
                uri.fragment != null ||
                uri.path == null
            ) {
                return false
            }

            return uri.path.let {
                it.matches("^/@[^/]+$".toRegex()) ||
                    it.matches("^/@[^/]+/\\d+$".toRegex()) ||
                    it.matches("^/users/[^/]+/statuses/\\d+$".toRegex()) ||
                    it.matches("^/users/\\w+$".toRegex()) ||
                    it.matches("^/notice/[a-zA-Z0-9]+$".toRegex()) ||
                    it.matches("^/objects/[-a-f0-9]+$".toRegex()) ||
                    it.matches("^/notes/[a-z0-9]+$".toRegex()) ||
                    it.matches("^/display/[-a-f0-9]+$".toRegex()) ||
                    it.matches("^/profile/\\w+$".toRegex()) ||
                    it.matches("^/p/\\w+/\\d+$".toRegex()) ||
                    it.matches("^/\\w+$".toRegex()) ||
                    it.matches("^/@[^/]+/statuses/[a-zA-Z0-9]+$".toRegex()) ||
                    it.matches("^/o/[a-f0-9]+$".toRegex())
            }
        }
    }
}

enum class PostLookupFallbackBehavior {
    OPEN_IN_BROWSER,
    DISPLAY_ERROR,
}
