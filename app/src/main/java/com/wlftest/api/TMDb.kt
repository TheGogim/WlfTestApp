package com.wlftest.api

import com.wlftest.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object TMDb {
    private const val API_KEY = "e7b6dbfc019233d9870ba65e6ae6fd34"
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // --- TMDB Response Models (snake_case to match API) ---

    @Serializable
    data class SearchResp(
        val page: Int,
        val results: List<MultiResult>,
        val total_pages: Int,
    )

    @Serializable
    data class MultiResult(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val original_title: String? = null,
        val original_name: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String? = null,
        val vote_average: Float = 0f,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val media_type: String? = null,
    )

    @Serializable
    data class MovieDetailResp(
        val id: Int,
        val title: String,
        val original_title: String,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String,
        val vote_average: Float = 0f,
        val release_date: String? = null,
        val runtime: Int? = null,
        val genres: List<GenreResp> = emptyList(),
        val credits: CreditsResp? = null,
    )

    @Serializable
    data class TvDetailResp(
        val id: Int,
        val name: String,
        val original_name: String,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String,
        val vote_average: Float = 0f,
        val first_air_date: String? = null,
        val number_of_seasons: Int? = null,
        val genres: List<GenreResp> = emptyList(),
        val credits: CreditsResp? = null,
    )

    @Serializable
    data class GenreResp(val id: Int, val name: String)

    @Serializable
    data class CreditsResp(val cast: List<CastResp>? = null)

    @Serializable
    data class CastResp(
        val name: String,
        val character: String,
        val profile_path: String? = null,
    )

    @Serializable
    data class SeasonResp(
        val episodes: List<EpisodeResp>,
    )

    @Serializable
    data class EpisodeResp(
        val episode_number: Int,
        val name: String,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null,
    )

    // --- Helper ---

    private fun img(path: String?, size: String = "w500") =
        if (path.isNullOrBlank()) null else "$IMG/$size$path"

    private fun extractYear(dateStr: String?): String? =
        dateStr?.take(4)?.takeIf { it.toIntOrNull() != null }

    // --- API Calls ---

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = "$BASE$path&api_key=$API_KEY"
        val request = Request.Builder().url(url)
            .header("User-Agent", "WlfTest/1.0")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("TMDB ${response.code}: ${response.message}")
        response.body?.string() ?: throw Exception("Empty response")
    }

    suspend fun search(query: String, page: Int = 1): List<ShowItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = fetch("/search/multi?query=$encoded&language=es-ES&page=$page")
        val resp = json.decodeFromString<SearchResp>(body)
        return resp.results
            .filter { it.media_type in listOf("movie", "tv") && !it.poster_path.isNullOrBlank() }
            .map { it.toShowItem() }
    }

    suspend fun trending(page: Int = 1): List<ShowItem> {
        val body = fetch("/trending/all/week?language=es-ES&page=$page")
        val resp = json.decodeFromString<SearchResp>(body)
        return resp.results
            .filter { !it.poster_path.isNullOrBlank() }
            .map { it.toShowItem() }
    }

    suspend fun getDetail(id: Int, type: ShowType): ShowDetail = when (type) {
        ShowType.MOVIE -> {
            val body = fetch("/movie/$id?language=es-ES&append_to_response=credits")
            json.decodeFromString<MovieDetailResp>(body).toShowDetail()
        }
        ShowType.TV -> {
            val body = fetch("/tv/$id?language=es-ES&append_to_response=credits")
            json.decodeFromString<TvDetailResp>(body).toShowDetail()
        }
    }

    suspend fun getSeasonEpisodes(tvId: Int, seasonNumber: Int): List<EpisodeItem> {
        val body = fetch("/tv/$tvId/season/$seasonNumber?language=es-ES")
        val resp = json.decodeFromString<SeasonResp>(body)
        return resp.episodes.map { it.toEpisodeItem() }
    }

    // --- Mappers ---

    private fun MultiResult.toShowItem() = ShowItem(
        id = id,
        title = title ?: name ?: "Sin título",
        originalTitle = original_title ?: original_name ?: "",
        posterUrl = img(poster_path),
        backdropUrl = img(backdrop_path, "w1280"),
        year = extractYear(release_date ?: first_air_date),
        rating = vote_average,
        type = if (media_type == "tv") ShowType.TV else ShowType.MOVIE,
        overview = overview,
    )

    private fun MovieDetailResp.toShowDetail() = ShowDetail(
        id = id,
        title = title,
        originalTitle = original_title,
        posterUrl = img(poster_path),
        backdropUrl = img(backdrop_path, "w1280"),
        year = extractYear(release_date),
        rating = vote_average,
        type = ShowType.MOVIE,
        overview = overview,
        genres = genres.map { it.name },
        runtime = runtime,
        numberOfSeasons = null,
        cast = credits?.cast?.take(15)?.map { it.toCastMember() } ?: emptyList(),
    )

    private fun TvDetailResp.toShowDetail() = ShowDetail(
        id = id,
        title = name,
        originalTitle = original_name,
        posterUrl = img(poster_path),
        backdropUrl = img(backdrop_path, "w1280"),
        year = extractYear(first_air_date),
        rating = vote_average,
        type = ShowType.TV,
        overview = overview,
        genres = genres.map { it.name },
        runtime = null,
        numberOfSeasons = number_of_seasons,
        cast = credits?.cast?.take(15)?.map { it.toCastMember() } ?: emptyList(),
    )

    private fun CastResp.toCastMember() = CastMember(
        name = name,
        character = character,
        photoUrl = img(profile_path, "w185"),
    )

    private fun EpisodeResp.toEpisodeItem() = EpisodeItem(
        episodeNumber = episode_number,
        name = name,
        overview = overview,
        posterUrl = img(still_path, "w300"),
        airDate = air_date,
    )
}