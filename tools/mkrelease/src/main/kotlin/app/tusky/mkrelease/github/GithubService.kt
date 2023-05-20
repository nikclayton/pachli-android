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

package app.tusky.mkrelease.github

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object GithubService {
    private val httpClient: OkHttpClient
    val pulls: PullsApi
    val releases: ReleasesApi
    val repositories: RepositoriesApi

    init {
        val githubToken = System.getenv("GITHUB_TOKEN")
        httpClient = OkHttpClient.Builder()
//            .addInterceptor(TokenAuthInterceptor(githubToken))
            .addInterceptor(headerInterceptor("Authorization", "Bearer $githubToken"))
            .addInterceptor(headerInterceptor("Accept", "application/vnd.github+json"))
            .addInterceptor(headerInterceptor("X-GitHub-Api-Version", "2022-11-28"))
            .addInterceptor(
                HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.BASIC) } )
            .build()

        val moshi = Moshi.Builder()
            .add(GithubMoshiAdapters)
            .addLast(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(httpClient)
            .build()

        pulls = retrofit.create(PullsApi::class.java)
        releases = retrofit.create(ReleasesApi::class.java)
        repositories = retrofit.create(RepositoriesApi::class.java)
    }

    // Shutdown HTTP client, otherwise JVM waits for a few minutes
    // https://github.com/square/retrofit/issues/3144
    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

fun headerInterceptor(name: String, value: String) = Interceptor { chain ->
    chain.proceed(
        chain.request()
            .newBuilder()
            .header(name, value)
            .build()
    )
}
