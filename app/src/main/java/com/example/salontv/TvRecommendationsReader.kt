package com.example.salontv

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat

data class TvProgramCard(
    val id: Long,
    val title: String,
    val packageName: String,
    val posterUri: String?,
    val intentUri: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val lastEngagementMillis: Long
) {
    val progressPercent: Int
        get() {
            if (durationMillis <= 0L) return 0

            return (
                positionMillis.coerceIn(0L, durationMillis) *
                    100L / durationMillis
                ).toInt()
        }
}

data class TvRecommendationChannel(
    val id: Long,
    val name: String,
    val packageName: String,
    val programs: List<TvProgramCard>
)

data class TvRecommendationsResult(
    val continueWatching: List<TvProgramCard> = emptyList(),
    val channels: List<TvRecommendationChannel> = emptyList(),
    val error: String? = null
)

class TvRecommendationsReader(
    private val context: Context
) {
    companion object {
        const val READ_TV_LISTINGS =
            "android.permission.READ_TV_LISTINGS"
    }

    /*
     * Ne pas appeler read() depuis le thread de l'interface.
     * ContentResolver.query() peut prendre du temps.
     */
    fun read(): TvRecommendationsResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return TvRecommendationsResult(
                error = "Les recommandations Android TV demandent Android 8 ou plus."
            )
        }

        if (
            context.checkSelfPermission(READ_TV_LISTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return TvRecommendationsResult(
                error = "Autorisation « programmes TV » non accordée."
            )
        }

        return try {
            TvRecommendationsResult(
                continueWatching = readContinueWatching(),
                channels = readChannels()
            )
        } catch (error: SecurityException) {
            TvRecommendationsResult(
                error = "La Freebox refuse l'accès aux recommandations TV."
            )
        } catch (error: Exception) {
            TvRecommendationsResult(
                error = "Lecture des recommandations impossible : " +
                    (error.message ?: "erreur inconnue")
            )
        }
    }

    private fun readContinueWatching(): List<TvProgramCard> {
        val table = TvContractCompat.WatchNextPrograms

        val projection = arrayOf(
            table._ID,
            table.COLUMN_TITLE,
            table.COLUMN_PACKAGE_NAME,
            table.COLUMN_POSTER_ART_URI,
            table.COLUMN_THUMBNAIL_URI,
            table.COLUMN_INTENT_URI,
            table.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            table.COLUMN_DURATION_MILLIS,
            table.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            table.COLUMN_WATCH_NEXT_TYPE,
            table.COLUMN_BROWSABLE
        )

        val result = mutableListOf<TvProgramCard>()

        context.contentResolver.query(
            table.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 50) {
                val type = cursor.intOrNull(
                    table.COLUMN_WATCH_NEXT_TYPE
                )

                if (
                    type != table.WATCH_NEXT_TYPE_CONTINUE
                ) {
                    continue
                }

                if (
                    cursor.intOrNull(table.COLUMN_BROWSABLE) == 0
                ) {
                    continue
                }

                cursor.toProgramCard()?.let(result::add)
            }
        }

        return result.sortedByDescending {
            it.lastEngagementMillis
        }
    }

    private fun readChannels(): List<TvRecommendationChannel> {
        val table = TvContractCompat.Channels

        val projection = arrayOf(
            table._ID,
            table.COLUMN_DISPLAY_NAME,
            table.COLUMN_PACKAGE_NAME,
            table.COLUMN_TYPE,
            table.COLUMN_BROWSABLE
        )

        val result = mutableListOf<TvRecommendationChannel>()

        context.contentResolver.query(
            table.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 15) {
                if (
                    cursor.stringOrNull(table.COLUMN_TYPE) !=
                    table.TYPE_PREVIEW
                ) {
                    continue
                }

                if (
                    cursor.intOrNull(table.COLUMN_BROWSABLE) == 0
                ) {
                    continue
                }

                val id = cursor.longOrNull(table._ID)
                    ?: continue

                val owner = cursor.stringOrNull(
                    table.COLUMN_PACKAGE_NAME
                )?.takeIf(String::isNotBlank)
                    ?: continue

                val name = cursor.stringOrNull(
                    table.COLUMN_DISPLAY_NAME
                )?.takeIf(String::isNotBlank)
                    ?: owner

                val programs = readProgramsForChannel(
                    id,
                    owner
                )

                if (programs.isNotEmpty()) {
                    result.add(
                        TvRecommendationChannel(
                            id = id,
                            name = name,
                            packageName = owner,
                            programs = programs
                        )
                    )
                }
            }
        }

        return result
    }

    private fun readProgramsForChannel(
        channelId: Long,
        channelPackage: String
    ): List<TvProgramCard> {
        val table = TvContractCompat.PreviewPrograms

        val projection = arrayOf(
            table._ID,
            table.COLUMN_TITLE,
            table.COLUMN_PACKAGE_NAME,
            table.COLUMN_POSTER_ART_URI,
            table.COLUMN_THUMBNAIL_URI,
            table.COLUMN_INTENT_URI,
            table.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            table.COLUMN_DURATION_MILLIS,
            table.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            table.COLUMN_BROWSABLE
        )

        val uri: Uri =
            TvContractCompat.buildPreviewProgramsUriForChannel(
                channelId
            )

        val result = mutableListOf<TvProgramCard>()

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 20) {
                if (
                    cursor.intOrNull(table.COLUMN_BROWSABLE) == 0
                ) {
                    continue
                }

                val card = cursor.toProgramCard()
                    ?: continue

                // Ne mélange pas les fiches d'une autre application
                // avec la chaîne qui est censée les publier.
                if (card.packageName == channelPackage) {
                    result.add(card)
                }
            }
        }

        return result
    }

    private fun Cursor.toProgramCard(): TvProgramCard? {
        val id = longOrNull(
            TvContractCompat.PreviewPrograms._ID
        ) ?: return null

        val owner = stringOrNull(
            TvContractCompat.PreviewPrograms.COLUMN_PACKAGE_NAME
        )?.takeIf(String::isNotBlank)
            ?: return null

        val title = stringOrNull(
            TvContractCompat.PreviewPrograms.COLUMN_TITLE
        )?.takeIf(String::isNotBlank)
            ?: return null

        val poster = stringOrNull(
            TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI
        )?.takeIf(String::isNotBlank)
            ?: stringOrNull(
                TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI
            )

        return TvProgramCard(
            id = id,
            title = title,
            packageName = owner,
            posterUri = poster,
            intentUri = stringOrNull(
                TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI
            ),
            positionMillis = longOrNull(
                TvContractCompat.PreviewPrograms
                    .COLUMN_LAST_PLAYBACK_POSITION_MILLIS
            ) ?: 0L,
            durationMillis = longOrNull(
                TvContractCompat.PreviewPrograms
                    .COLUMN_DURATION_MILLIS
            ) ?: 0L,
            lastEngagementMillis = longOrNull(
                TvContractCompat.WatchNextPrograms
                    .COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
            ) ?: 0L
        )
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)

        return if (index < 0 || isNull(index)) {
            null
        } else {
            getString(index)
        }
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)

        return if (index < 0 || isNull(index)) {
            null
        } else {
            getLong(index)
        }
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndex(column)

        return if (index < 0 || isNull(index)) {
            null
        } else {
            getInt(index)
        }
    }
}
