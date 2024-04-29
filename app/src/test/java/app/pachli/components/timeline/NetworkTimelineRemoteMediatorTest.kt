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

package app.pachli.components.timeline

import androidx.paging.ExperimentalPagingApi
import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pachli.components.timeline.viewmodel.NetworkTimelineRemoteMediator
import app.pachli.components.timeline.viewmodel.Page
import app.pachli.components.timeline.viewmodel.PageCache
import app.pachli.core.accounts.AccountManager
import app.pachli.core.database.model.AccountEntity
import app.pachli.core.model.Timeline
import app.pachli.core.network.model.Status
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

@Config(sdk = [29])
@RunWith(AndroidJUnit4::class)
class NetworkTimelineRemoteMediatorTest {
    private val accountManager: AccountManager = mock {
        on { activeAccount } doReturn AccountEntity(
            id = 1,
            domain = "mastodon.example",
            accessToken = "token",
            clientId = "id",
            clientSecret = "secret",
            isActive = true,
        )
    }

    private lateinit var pagingSourceFactory: InvalidatingPagingSourceFactory<String, Status>

    @Before
    fun setup() {
        pagingSourceFactory = mock()
    }

    @Test
    @ExperimentalPagingApi
    fun `should return error when network call returns error code`() = runTest {
        // Given
        val remoteMediator = NetworkTimelineRemoteMediator(
            viewModelScope = this,
            api = mock(defaultAnswer = { Response.error<String>(500, "".toResponseBody()) }),
            accountManager = accountManager,
            factory = pagingSourceFactory,
            pageCache = PageCache(),
            timeline = Timeline.Home,
        )

        // When
        val result = remoteMediator.load(LoadType.REFRESH, state())

        // Then
        assertThat(result).isInstanceOf(RemoteMediator.MediatorResult.Error::class.java)
        assertThat((result as RemoteMediator.MediatorResult.Error).throwable).isInstanceOf(HttpException::class.java)
        assertThat((result.throwable as HttpException).code()).isEqualTo(500)
    }

    @Test
    @ExperimentalPagingApi
    fun `should return error when network call fails`() = runTest {
        // Given
        val remoteMediator = NetworkTimelineRemoteMediator(
            viewModelScope = this,
            api = mock(defaultAnswer = { throw IOException() }),
            accountManager,
            factory = pagingSourceFactory,
            pageCache = PageCache(),
            timeline = Timeline.Home,
        )

        // When
        val result = remoteMediator.load(LoadType.REFRESH, state())

        // Then
        assertThat(result).isInstanceOf(RemoteMediator.MediatorResult.Error::class.java)
        assertThat((result as RemoteMediator.MediatorResult.Error).throwable).isInstanceOf(IOException::class.java)
    }

    @Test
    @ExperimentalPagingApi
    fun `should do initial loading`() = runTest {
        // Given
        val pages = PageCache()
        val remoteMediator = NetworkTimelineRemoteMediator(
            viewModelScope = this,
            api = mock {
                onBlocking { homeTimeline(maxId = anyOrNull(), minId = anyOrNull(), limit = anyOrNull(), sinceId = anyOrNull()) } doReturn Response.success(
                    listOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    Headers.headersOf(
                        "Link",
                        "<https://mastodon.example/api/v1/timelines/home?max_id=5>; rel=\"next\", <https://mastodon.example/api/v1/timelines/homefavourites?min_id=7>; rel=\"prev\"",
                    ),
                )
            },
            accountManager = accountManager,
            factory = pagingSourceFactory,
            pageCache = pages,
            timeline = Timeline.Home,
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null,
                ),
            ),
        )

        // When
        val result = remoteMediator.load(LoadType.REFRESH, state)

        // Then
        val expectedPages = PageCache().apply {
            add(
                Page(
                    data = mutableListOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
                LoadType.REFRESH,
            )
        }

        assertThat(result).isInstanceOf(RemoteMediator.MediatorResult.Success::class.java)
        assertThat((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached).isFalse()
        assertThat(pages.idToPage).containsExactlyEntriesIn(expectedPages.idToPage)

        // Page cache was modified, so the pager should have been invalidated
        verify(pagingSourceFactory).invalidate()
    }

    @Test
    @ExperimentalPagingApi
    fun `should prepend statuses`() = runTest {
        // Given
        val pages = PageCache().apply {
            add(
                Page(
                    data = mutableListOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
                LoadType.REFRESH,
            )
        }

        val remoteMediator = NetworkTimelineRemoteMediator(
            viewModelScope = this,
            api = mock {
                onBlocking { homeTimeline(maxId = anyOrNull(), minId = anyOrNull(), limit = anyOrNull(), sinceId = anyOrNull()) } doReturn Response.success(
                    listOf(mockStatus("10"), mockStatus("9"), mockStatus("8")),
                    Headers.headersOf(
                        "Link",
                        "<https://mastodon.example/api/v1/timelines/home?max_id=8>; rel=\"next\", <https://mastodon.example/api/v1/timelines/homefavourites?min_id=10>; rel=\"prev\"",
                    ),
                )
            },
            accountManager = accountManager,
            factory = pagingSourceFactory,
            pageCache = pages,
            timeline = Timeline.Home,
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = listOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
            ),
        )

        // When
        val result = remoteMediator.load(LoadType.PREPEND, state)

        // Then
        val expectedPages = PageCache().apply {
            add(
                Page(
                    data = mutableListOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
                LoadType.REFRESH,
            )
            add(
                Page(
                    data = mutableListOf(mockStatus("10"), mockStatus("9"), mockStatus("8")),
                    prevKey = "10",
                    nextKey = "8",
                ),
                LoadType.PREPEND,
            )
        }

        assertThat(result).isInstanceOf(RemoteMediator.MediatorResult.Success::class.java)
        assertThat((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached).isFalse()
        assertThat(pages.idToPage).containsExactlyEntriesIn(expectedPages.idToPage)

        // Page cache was modified, so the pager should have been invalidated
        verify(pagingSourceFactory).invalidate()
    }

    @Test
    @ExperimentalPagingApi
    fun `should append statuses`() = runTest {
        // Given
        val pages = PageCache().apply {
            add(
                Page(
                    data = mutableListOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
                LoadType.REFRESH,
            )
        }

        val remoteMediator = NetworkTimelineRemoteMediator(
            viewModelScope = this,
            api = mock {
                onBlocking { homeTimeline(maxId = anyOrNull(), minId = anyOrNull(), limit = anyOrNull(), sinceId = anyOrNull()) } doReturn Response.success(
                    listOf(mockStatus("4"), mockStatus("3"), mockStatus("2")),
                    Headers.headersOf(
                        "Link",
                        "<https://mastodon.example/api/v1/timelines/home?max_id=2>; rel=\"next\", <https://mastodon.example/api/v1/timelines/homefavourites?min_id=4>; rel=\"prev\"",
                    ),
                )
            },
            accountManager = accountManager,
            factory = pagingSourceFactory,
            pageCache = pages,
            timeline = Timeline.Home,
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = listOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
            ),
        )

        // When
        val result = remoteMediator.load(LoadType.APPEND, state)

        // Then
        val expectedPages = PageCache().apply {
            add(
                Page(
                    data = mutableListOf(mockStatus("7"), mockStatus("6"), mockStatus("5")),
                    prevKey = "7",
                    nextKey = "5",
                ),
                LoadType.REFRESH,
            )
            add(
                Page(
                    data = mutableListOf(mockStatus("4"), mockStatus("3"), mockStatus("2")),
                    prevKey = "4",
                    nextKey = "2",
                ),
                LoadType.APPEND,
            )
        }

        assertThat(result).isInstanceOf(RemoteMediator.MediatorResult.Success::class.java)
        assertThat((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached).isFalse()
        assertThat(pages.idToPage).containsExactlyEntriesIn(expectedPages.idToPage)

        // Page cache was modified, so the pager should have been invalidated
        verify(pagingSourceFactory).invalidate()
    }

    companion object {
        private const val PAGE_SIZE = 20

        private fun state(pages: List<PagingSource.LoadResult.Page<String, Status>> = emptyList()) =
            PagingState(
                pages = pages,
                anchorPosition = null,
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE,
                ),
                leadingPlaceholderCount = 0,
            )
    }
}
