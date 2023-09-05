package app.pachli.components.timeline

import android.os.Looper.getMainLooper
import androidx.paging.ExperimentalPagingApi
import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pachli.components.timeline.viewmodel.CachedTimelineRemoteMediator
import app.pachli.components.timeline.viewmodel.CachedTimelineRemoteMediator.Companion.TIMELINE_ID
import app.pachli.db.AccountEntity
import app.pachli.db.AccountManager
import app.pachli.db.AppDatabase
import app.pachli.db.Converters
import app.pachli.db.RemoteKeyEntity
import app.pachli.db.RemoteKeyKind
import app.pachli.db.TimelineStatusWithAccount
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@Config(sdk = [28])
@RunWith(AndroidJUnit4::class)
class CachedTimelineRemoteMediatorTest {

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

    private lateinit var db: AppDatabase

    private lateinit var pagingSourceFactory: InvalidatingPagingSourceFactory<Int, TimelineStatusWithAccount>

    @Before
    @ExperimentalCoroutinesApi
    fun setup() {
        shadowOf(getMainLooper()).idle()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addTypeConverter(Converters(Gson()))
            .build()

        pagingSourceFactory = mock()
    }

    @After
    @ExperimentalCoroutinesApi
    fun tearDown() {
        db.close()
    }

    @Test
    @ExperimentalPagingApi
    fun `should return error when network call returns error code`() {
        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doReturn Response.error(500, "".toResponseBody())
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val result = runBlocking { remoteMediator.load(LoadType.REFRESH, state()) }

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is HttpException)
        assertEquals(500, (result.throwable as HttpException).code())
    }

    @Test
    @ExperimentalPagingApi
    fun `should return error when network call fails`() {
        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doThrow IOException()
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val result = runBlocking { remoteMediator.load(LoadType.REFRESH, state()) }

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is IOException)
    }

    @Test
    @ExperimentalPagingApi
    fun `should not prepend statuses`() {
        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock(),
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = listOf(
                        mockStatusEntityWithAccount("3"),
                    ),
                    prevKey = null,
                    nextKey = 1,
                ),
            ),
        )

        val result = runBlocking { remoteMediator.load(LoadType.PREPEND, state) }

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
    }

    @Test
    @ExperimentalPagingApi
    fun `should refresh and not insert placeholder when less than a whole page is loaded`() {
        val statusesAlreadyInDb = listOf(
            mockStatusEntityWithAccount("3"),
            mockStatusEntityWithAccount("2"),
            mockStatusEntityWithAccount("1"),
        )

        db.insert(statusesAlreadyInDb)

        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(limit = 20) } doReturn Response.success(
                    listOf(
                        mockStatus("8"),
                        mockStatus("7"),
                        mockStatus("5"),
                    ),
                )
                onBlocking { homeTimeline(maxId = "3", limit = 20) } doReturn Response.success(
                    listOf(
                        mockStatus("3"),
                        mockStatus("2"),
                        mockStatus("1"),
                    ),
                )
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val state = state(
            pages = listOf(
                PagingSource.LoadResult.Page(
                    data = statusesAlreadyInDb,
                    prevKey = null,
                    nextKey = 0,
                ),
            ),
        )

        val result = runBlocking { remoteMediator.load(LoadType.REFRESH, state) }

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)

        db.assertStatuses(
            listOf(
                mockStatusEntityWithAccount("8"),
                mockStatusEntityWithAccount("7"),
                mockStatusEntityWithAccount("5"),
                mockStatusEntityWithAccount("3"),
                mockStatusEntityWithAccount("2"),
                mockStatusEntityWithAccount("1"),
            ),
        )
    }

    @Test
    @ExperimentalPagingApi
    fun `should refresh and not insert placeholders when there is overlap with existing statuses`() {
        val statusesAlreadyInDb = listOf(
            mockStatusEntityWithAccount("3"),
            mockStatusEntityWithAccount("2"),
            mockStatusEntityWithAccount("1"),
        )

        db.insert(statusesAlreadyInDb)

        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(limit = 3) } doReturn Response.success(
                    listOf(
                        mockStatus("6"),
                        mockStatus("4"),
                        mockStatus("3"),
                    ),
                )
                onBlocking { homeTimeline(maxId = "3", limit = 3) } doReturn Response.success(
                    listOf(
                        mockStatus("3"),
                        mockStatus("2"),
                        mockStatus("1"),
                    ),
                )
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = statusesAlreadyInDb,
                    prevKey = null,
                    nextKey = 0,
                ),
            ),
            pageSize = 3,
        )

        val result = runBlocking { remoteMediator.load(LoadType.REFRESH, state) }

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)

        db.assertStatuses(
            listOf(
                mockStatusEntityWithAccount("6"),
                mockStatusEntityWithAccount("4"),
                mockStatusEntityWithAccount("3"),
                mockStatusEntityWithAccount("2"),
                mockStatusEntityWithAccount("1"),
            ),
        )
    }

    @Test
    @ExperimentalPagingApi
    fun `should not try to refresh already cached statuses when db is empty`() {
        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(limit = 20) } doReturn Response.success(
                    listOf(
                        mockStatus("5"),
                        mockStatus("4"),
                        mockStatus("3"),
                    ),
                )
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = 0,
                ),
            ),
        )

        val result = runBlocking { remoteMediator.load(LoadType.REFRESH, state) }

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)

        db.assertStatuses(
            listOf(
                mockStatusEntityWithAccount("5"),
                mockStatusEntityWithAccount("4"),
                mockStatusEntityWithAccount("3"),
            ),
        )
    }

    @Test
    @ExperimentalPagingApi
    fun `should remove deleted status from db and keep state of other cached statuses`() = runTest {
        // Given
        val statusesAlreadyInDb = listOf(
            mockStatusEntityWithAccount("3", expanded = true),
            mockStatusEntityWithAccount("2"),
            mockStatusEntityWithAccount("1", expanded = false),
        )

        db.insert(statusesAlreadyInDb)

        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(limit = 20) } doReturn Response.success(
                    listOf(
                        mockStatus("3"),
                        mockStatus("1"),
                    ),
                )
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = statusesAlreadyInDb,
                    prevKey = null,
                    nextKey = 0,
                ),
            ),
        )

        // When
        val result = remoteMediator.load(LoadType.REFRESH, state)

        // Then
        assertThat(result).isInstanceOf(RemoteMediator.MediatorResult.Success::class.java)

        db.assertStatuses(
            listOf(
                // id="2" was in the database initially, but not in the results returned
                // from the API, so it should have been deleted here.
                mockStatusEntityWithAccount("3", expanded = true),
                mockStatusEntityWithAccount("1", expanded = false),
            ),
        )
    }

    @Test
    @ExperimentalPagingApi
    fun `should append statuses`() = runTest {
        val statusesAlreadyInDb = listOf(
            mockStatusEntityWithAccount("8"),
            mockStatusEntityWithAccount("7"),
            mockStatusEntityWithAccount("5"),
        )

        db.insert(statusesAlreadyInDb)
        db.remoteKeyDao().upsert(RemoteKeyEntity(1, TIMELINE_ID, RemoteKeyKind.PREV, "8"))
        db.remoteKeyDao().upsert(RemoteKeyEntity(1, TIMELINE_ID, RemoteKeyKind.NEXT, "5"))

        val remoteMediator = CachedTimelineRemoteMediator(
            accountManager = accountManager,
            api = mock {
                onBlocking { homeTimeline(maxId = "5", limit = 20) } doReturn Response.success(
                    listOf(
                        mockStatus("3"),
                        mockStatus("2"),
                        mockStatus("1"),
                    ),
                    Headers.Builder().add(
                        "Link: <http://example.com/?min_id=3>; rel=\"prev\", <http://example.com/?max_id=1>; rel=\"next\"",
                    ).build(),
                )
            },
            factory = pagingSourceFactory,
            db = db,
            gson = Gson(),
        )

        val state = state(
            listOf(
                PagingSource.LoadResult.Page(
                    data = statusesAlreadyInDb,
                    prevKey = null,
                    nextKey = 0,
                ),
            ),
        )

        val result = runBlocking { remoteMediator.load(LoadType.APPEND, state) }

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        db.assertStatuses(
            listOf(
                mockStatusEntityWithAccount("8"),
                mockStatusEntityWithAccount("7"),
                mockStatusEntityWithAccount("5"),
                mockStatusEntityWithAccount("3"),
                mockStatusEntityWithAccount("2"),
                mockStatusEntityWithAccount("1"),
            ),
        )
    }

    private fun state(
        pages: List<PagingSource.LoadResult.Page<Int, TimelineStatusWithAccount>> = emptyList(),
        pageSize: Int = 20,
    ) = PagingState(
        pages = pages,
        anchorPosition = null,
        config = PagingConfig(
            pageSize = pageSize,
        ),
        leadingPlaceholderCount = 0,
    )

    private fun AppDatabase.insert(statuses: List<TimelineStatusWithAccount>) {
        runBlocking {
            statuses.forEach { statusWithAccount ->
                statusWithAccount.account.let { account ->
                    timelineDao().insertAccount(account)
                }
                statusWithAccount.reblogAccount?.let { account ->
                    timelineDao().insertAccount(account)
                }
                timelineDao().insertStatus(statusWithAccount.status)
            }
        }
    }

    private fun AppDatabase.assertStatuses(
        expected: List<TimelineStatusWithAccount>,
        forAccount: Long = 1,
    ) {
        val pagingSource = timelineDao().getStatuses(forAccount)

        val loadResult = runBlocking {
            pagingSource.load(PagingSource.LoadParams.Refresh(null, 100, false))
        }

        val loadedStatuses = (loadResult as PagingSource.LoadResult.Page).data

        assertEquals(expected.size, loadedStatuses.size)

        for ((exp, prov) in expected.zip(loadedStatuses)) {
            assertEquals(exp.status, prov.status)
            assertEquals(exp.account, prov.account)
            assertEquals(exp.reblogAccount, prov.reblogAccount)
        }
    }
}