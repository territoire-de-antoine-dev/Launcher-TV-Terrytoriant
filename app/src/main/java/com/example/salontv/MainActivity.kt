package com.example.salontv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.tvprovider.media.tv.TvContractCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    /*
     * Plusieurs colonnes Android TV sont héritées d'interfaces Java.
     * Les écrire ici par leur nom évite les erreurs Kotlin
     * « Unresolved reference » sur table.COLUMN_...
     */
    private object WatchNextCols {
        val CONTENT_URI =
            TvContractCompat.WatchNextPrograms.CONTENT_URI

        const val ID = "_id"
        const val TITLE = "title"
        const val PACKAGE_NAME = "package_name"
        const val POSTER_ART_URI = "poster_art_uri"
        const val THUMBNAIL_URI = "thumbnail_uri"
        const val INTENT_URI = "intent_uri"
        const val LAST_PLAYBACK_POSITION =
            "last_playback_position_millis"
        const val DURATION = "duration_millis"
        const val LAST_ENGAGEMENT =
            "last_engagement_time_utc_millis"
        const val WATCH_NEXT_TYPE = "watch_next_type"
        const val BROWSABLE = "browsable"
        const val TYPE_CONTINUE = 0
    }

    private object ChannelCols {
        val CONTENT_URI =
            TvContractCompat.Channels.CONTENT_URI

        const val ID = "_id"
        const val DISPLAY_NAME = "display_name"
        const val PACKAGE_NAME = "package_name"
        const val TYPE = "type"
        const val BROWSABLE = "browsable"
        const val TYPE_PREVIEW = "TYPE_PREVIEW"
    }

    private object PreviewCols {
        const val ID = "_id"
        const val TITLE = "title"
        const val PACKAGE_NAME = "package_name"
        const val POSTER_ART_URI = "poster_art_uri"
        const val THUMBNAIL_URI = "thumbnail_uri"
        const val INTENT_URI = "intent_uri"
        const val LAST_PLAYBACK_POSITION =
            "last_playback_position_millis"
        const val DURATION = "duration_millis"
        const val BROWSABLE = "browsable"
    }

    private data class ProgramCard(
        val id: Long,
        val title: String,
        val ownerPackage: String,
        val artworkUri: String?,
        val launchUri: String?,
        val positionMillis: Long,
        val durationMillis: Long,
        val engagementMillis: Long
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

    private data class RecommendationRow(
        val title: String,
        val cards: List<ProgramCard>
    )

    private data class CityResult(
        val name: String,
        val region: String,
        val country: String,
        val latitude: Double,
        val longitude: Double
    )

    private lateinit var tabRow: LinearLayout
    private lateinit var page: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var clockWeather: TextView

    private val tabs = linkedMapOf<String, TextView>()
    private val worker = Executors.newSingleThreadExecutor()
    private val imageWorker = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    private val preferences by lazy {
        getSharedPreferences("salontv_settings", MODE_PRIVATE)
    }

    private var currentPage = "Pour vous"
    private var firstContentButton: View? = null
    private var lastRowButton: View? = null

    private var weatherLine = "Choisir une ville"
    private var lastWeatherUpdate = 0L

    private var tvPermissionRequested = false
    private var recommendationsStatus =
        "Chargement des recommandations Android TV…"

    private var continueWatching: List<ProgramCard> = emptyList()
    private var recommendationRows: List<RecommendationRow> =
        emptyList()

    private val clockTask = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_main)

        tabRow = findViewById(R.id.tabRow)
        page = findViewById(R.id.pageContent)
        pageScroll = findViewById(R.id.pageScroll)
        clockWeather = findViewById(R.id.clockWeather)

        findViewById<LinearLayout>(R.id.navBackground).background =
            rounded(0xEBF2F2F2.toInt(), 28)

        createNavigation()

        setupHeaderButton(
            findViewById(R.id.searchButton)
        ) {
            searchApplications()
        }

        setupHeaderButton(
            findViewById(R.id.settingsButton)
        ) {
            showSettings()
        }

        setupHeaderButton(
            findViewById(R.id.sleepButton)
        ) {
            openScreensaver()
        }

        findViewById<View>(R.id.logoBox).apply {
            isFocusable = true
            isClickable = true
            background = focusBackground(
                0xFF263A52.toInt(),
                9
            )
            setOnClickListener { chooseLogo() }
        }

        clockWeather.background =
            focusBackground(0xFF263A52.toInt(), 9)

        clockWeather.setOnClickListener {
            chooseCity()
        }

        restoreLogo()
        renderPage("Pour vous")

        tabRow.post {
            tabs["Pour vous"]?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()

        if (::page.isInitialized) {
            renderPage(currentPage)
        }

        handler.removeCallbacks(clockTask)
        handler.post(clockTask)

        updateWeather()
        refreshRecommendations()
    }

    override fun onPause() {
        handler.removeCallbacks(clockTask)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        imageWorker.shutdownNow()
        super.onDestroy()
    }

    private fun createNavigation() {
        val navigation = listOf(
            "⌂  Pour vous" to "Pour vous",
            "▦  Guide TV" to "Guide TV",
            "◷  Replay" to "Replay",
            "▣  Applis" to "Applis",
            "▶  VOD" to "VOD"
        )

        navigation.forEach { (label, name) ->
            val button = TextView(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                setTextColor(0xFF27303B.toInt())
                isFocusable = true
                isClickable = true
                contentDescription = name

                setOnFocusChangeListener { view, focused ->
                    (view as TextView).setTextColor(
                        if (focused) {
                            Color.WHITE
                        } else {
                            0xFF27303B.toInt()
                        }
                    )
                }

                setOnClickListener {
                    renderPage(name)
                }
            }

            tabRow.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                )
            )

            tabs[name] = button
        }
    }

    private fun setupHeaderButton(
        button: TextView,
        action: () -> Unit
    ) {
        button.background = navigationBackground(false)

        button.setOnFocusChangeListener { view, focused ->
            (view as TextView).setTextColor(
                if (focused) Color.WHITE
                else 0xFF27303B.toInt()
            )
        }

        button.setOnClickListener { action() }
    }

    private fun renderPage(name: String) {
        currentPage = name
        page.removeAllViews()
        firstContentButton = null
        lastRowButton = null

        tabs.forEach { (tabName, tab) ->
            tab.background =
                navigationBackground(tabName == name)
        }

        when (name) {
            "Pour vous" -> renderHome()

            "Guide TV" -> renderFreeSection(
                "Guide TV",
                "Le guide des programmes Free se trouve " +
                    "dans l'application Free TV. Ouvre-la puis " +
                    "sélectionne son guide."
            )

            "Replay" -> renderReplay()
            "Applis" -> renderApplications()
            "VOD" -> renderVod()
            else -> renderHome()
        }

        val destination =
            firstContentButton?.id ?: View.NO_ID

        tabs.values.forEach {
            it.nextFocusDownId = destination
        }

        findViewById<View>(
            R.id.searchButton
        ).nextFocusDownId = destination

        findViewById<View>(
            R.id.settingsButton
        ).nextFocusDownId = destination

        clockWeather.nextFocusDownId = destination

        findViewById<View>(
            R.id.logoBox
        ).nextFocusDownId = destination

        pageScroll.scrollTo(0, 0)
    }

    private fun renderHome() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(25), dp(13), dp(25), dp(13))
            background = getDrawable(R.drawable.bg_hero)
        }

        page.addView(
            hero,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(169)
            ).apply {
                topMargin = dp(13)
            }
        )

        hero.addView(
            label("Votre espace TV", 27f, true)
        )

        hero.addView(
            label(
                "Vos applications et vos programmes, " +
                    "au même endroit.",
                15f,
                false
            ).apply {
                setTextColor(0xFFDFE4EA.toInt())
            }
        )

        val tvButton = mainButton(
            "Accéder à la TV",
            true
        ) {
            startActivity(
                Intent(
                    this@MainActivity,
                    LiveTvActivity::class.java
                )
            )
        }

        hero.addView(
            tvButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(43)
            ).apply {
                topMargin = dp(15)
            }
        )

        registerFirst(tvButton)
        addRecommendationSections()

        sectionTitle("À la une")

        val categories = listOf(
            "À découvrir" to "Explorer vos services",
            "Films et séries" to "Voir les plateformes",
            "Documentaires" to "Explorer les applications",
            "Jeunesse" to "Contenus famille",
            "Nouveautés" to "Parcourir les catalogues"
        )

        val categoryRow = horizontalRow()
        var firstCategory: View? = null

        categories.forEachIndexed { index, pair ->
            val title = pair.first
            val subtitle = pair.second

            val card = categoryCard(
                title,
                subtitle,
                index
            ).apply {
                nextFocusUpId = tvButton.id

                setOnClickListener {
                    renderCategory(title)
                    firstContentButton?.requestFocus()
                }
            }

            categoryRow.addView(
                card,
                LinearLayout.LayoutParams(
                    dp(228),
                    dp(105)
                ).apply {
                    rightMargin = dp(13)
                }
            )

            if (firstCategory == null) {
                firstCategory = card
            }
        }

        tvButton.nextFocusDownId =
            firstCategory?.id ?: View.NO_ID

        lastRowButton = firstCategory

        sectionTitle("Les services")
        addAppRow(
            tvApps(),
            "Aucune application TV détectée."
        )
    }

    private fun addRecommendationSections() {
        if (continueWatching.isNotEmpty()) {
            addProgramRow(
                "Reprendre la lecture",
                continueWatching
            )
        }

        recommendationRows.forEach { row ->
            addProgramRow(row.title, row.cards)
        }

        if (
            continueWatching.isEmpty() &&
            recommendationRows.isEmpty()
        ) {
            sectionTitle("Recommandations Android TV")
            paragraph(recommendationsStatus)
        }
    }

    private fun addProgramRow(
        title: String,
        cards: List<ProgramCard>
    ) {
        if (cards.isEmpty()) return

        sectionTitle(title)

        val row = horizontalRow()
        var firstInRow: View? = null

        cards.forEach { card ->
            val tile = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(7), dp(7), dp(8))

                background = cardBackground(
                    (card.id % 5L).toInt()
                )

                isFocusable = true
                isClickable = true

                contentDescription =
                    "${card.title}. Ouvrir dans " +
                        card.ownerPackage

                lastRowButton?.let {
                    nextFocusUpId = it.id
                }

                setOnFocusChangeListener { view, focused ->
                    animateFocus(view, focused)
                }

                setOnClickListener {
                    launchPublishedProgram(card)
                }
            }

            val artwork = ImageView(this).apply {
                scaleType =
                    ImageView.ScaleType.CENTER_CROP

                setBackgroundColor(
                    0xFF26354E.toInt()
                )

                isFocusable = false
            }

            tile.addView(
                artwork,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(105)
                )
            )

            tile.addView(
                label(card.title, 14f, true).apply {
                    maxLines = 2
                    ellipsize =
                        TextUtils.TruncateAt.END
                    isFocusable = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(38)
                ).apply {
                    topMargin = dp(5)
                }
            )

            if (card.progressPercent > 0) {
                val progressBar = ProgressBar(
                    this,
                    null,
                    android.R.attr.progressBarStyleHorizontal
                ).apply {
                    max = 100
                    progress = card.progressPercent

                    progressTintList =
                        android.content.res.ColorStateList
                            .valueOf(
                                0xFFE60012.toInt()
                            )
                }

                tile.addView(
                    progressBar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(5)
                    )
                )
            }

            row.addView(
                tile,
                LinearLayout.LayoutParams(
                    dp(230),
                    dp(170)
                ).apply {
                    rightMargin = dp(13)
                }
            )

            loadProgramArtwork(
                artwork,
                card.artworkUri
            )

            if (firstInRow == null) {
                firstInRow = tile
            }

            registerFirst(tile)
        }

        lastRowButton = firstInRow
    }

    private fun launchPublishedProgram(
        card: ProgramCard
    ) {
        val storedUri = card.launchUri

        if (storedUri.isNullOrBlank()) {
            message(
                "Cette fiche ne contient pas de lien " +
                    "d'ouverture."
            )
            return
        }

        try {
            val intent = Intent.parseUri(
                storedUri,
                Intent.URI_INTENT_SCHEME
            )

            val explicitPackage =
                intent.component?.packageName

            if (
                explicitPackage != null &&
                explicitPackage != card.ownerPackage
            ) {
                throw SecurityException(
                    "Application cible différente " +
                        "de la source."
                )
            }

            intent.setPackage(card.ownerPackage)
            startActivity(intent)

        } catch (_: Exception) {
            message(
                "Impossible d'ouvrir ce contenu " +
                    "dans son application."
            )
        }
    }

    private fun refreshRecommendations() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            recommendationsStatus =
                "Cette version d'Android TV ne prend pas " +
                    "en charge ces recommandations."

            redrawHomeIfVisible()
            return
        }

        if (
            checkSelfPermission(
                READ_TV_LISTINGS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            recommendationsStatus =
                "Autorisation « programmes TV » " +
                    "non accordée."

            redrawHomeIfVisible()

            if (!tvPermissionRequested) {
                tvPermissionRequested = true

                requestPermissions(
                    arrayOf(READ_TV_LISTINGS),
                    REQUEST_TV_LISTINGS
                )
            }

            return
        }

        recommendationsStatus =
            "Chargement des recommandations…"

        worker.execute {
            try {
                val watchNext =
                    readContinueWatching()

                val channels =
                    readRecommendationChannels()

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    continueWatching = watchNext
                    recommendationRows = channels

                    recommendationsStatus =
                        if (
                            watchNext.isEmpty() &&
                            channels.isEmpty()
                        ) {
                            "Aucune fiche accessible publiée " +
                                "par les applications sur " +
                                "cette box."
                        } else {
                            "Recommandations actualisées."
                        }

                    redrawHomeIfVisible()
                }

            } catch (_: SecurityException) {
                runOnUiThread {
                    recommendationsStatus =
                        "La box refuse l'accès aux " +
                            "recommandations d'autres " +
                            "applications."

                    redrawHomeIfVisible()
                }

            } catch (error: Exception) {
                runOnUiThread {
                    recommendationsStatus =
                        "Lecture des recommandations " +
                            "impossible : " +
                            (error.message
                                ?: "erreur inconnue")

                    redrawHomeIfVisible()
                }
            }
        }
    }

    private fun redrawHomeIfVisible() {
        if (
            ::page.isInitialized &&
            currentPage == "Pour vous" &&
            !isFinishing &&
            !isDestroyed
        ) {
            renderPage("Pour vous")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_TV_LISTINGS) {
            if (
                grantResults.firstOrNull() ==
                PackageManager.PERMISSION_GRANTED
            ) {
                refreshRecommendations()
            } else {
                recommendationsStatus =
                    "Autorisation refusée. Les autres " +
                        "fonctions du launcher restent " +
                        "disponibles."

                redrawHomeIfVisible()
            }
        }
    }

    private fun readContinueWatching(): List<ProgramCard> {
        val projection = arrayOf(
            WatchNextCols.ID,
            WatchNextCols.TITLE,
            WatchNextCols.PACKAGE_NAME,
            WatchNextCols.POSTER_ART_URI,
            WatchNextCols.THUMBNAIL_URI,
            WatchNextCols.INTENT_URI,
            WatchNextCols.LAST_PLAYBACK_POSITION,
            WatchNextCols.DURATION,
            WatchNextCols.LAST_ENGAGEMENT,
            WatchNextCols.WATCH_NEXT_TYPE,
            WatchNextCols.BROWSABLE
        )

        val result = mutableListOf<ProgramCard>()

        contentResolver.query(
            WatchNextCols.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (
                cursor.moveToNext() &&
                result.size < 50
            ) {
                if (
                    cursor.intValue(
                        WatchNextCols.WATCH_NEXT_TYPE
                    ) != WatchNextCols.TYPE_CONTINUE
                ) {
                    continue
                }

                if (
                    cursor.intValue(
                        WatchNextCols.BROWSABLE
                    ) == 0
                ) {
                    continue
                }

                cursor.readProgramCard(
                    WatchNextCols.LAST_ENGAGEMENT
                )?.let {
                    result.add(it)
                }
            }
        }

        return result.sortedByDescending {
            it.engagementMillis
        }
    }

    private fun readRecommendationChannels():
        List<RecommendationRow> {

        val projection = arrayOf(
            ChannelCols.ID,
            ChannelCols.DISPLAY_NAME,
            ChannelCols.PACKAGE_NAME,
            ChannelCols.TYPE,
            ChannelCols.BROWSABLE
        )

        val rows = mutableListOf<RecommendationRow>()

        contentResolver.query(
            ChannelCols.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (
                cursor.moveToNext() &&
                rows.size < 12
            ) {
                if (
                    cursor.stringValue(
                        ChannelCols.TYPE
                    ) != ChannelCols.TYPE_PREVIEW
                ) {
                    continue
                }

                if (
                    cursor.intValue(
                        ChannelCols.BROWSABLE
                    ) == 0
                ) {
                    continue
                }

                val channelId =
                    cursor.longValue(
                        ChannelCols.ID
                    ) ?: continue

                val owner =
                    cursor.stringValue(
                        ChannelCols.PACKAGE_NAME
                    )?.takeIf {
                        it.isNotBlank()
                    } ?: continue

                val channelName =
                    cursor.stringValue(
                        ChannelCols.DISPLAY_NAME
                    )?.takeIf {
                        it.isNotBlank()
                    } ?: owner

                val programs =
                    readPreviewPrograms(
                        channelId,
                        owner
                    )

                if (programs.isNotEmpty()) {
                    rows.add(
                        RecommendationRow(
                            channelName,
                            programs
                        )
                    )
                }
            }
        }

        return rows
    }

    private fun readPreviewPrograms(
        channelId: Long,
        owner: String
    ): List<ProgramCard> {

        val projection = arrayOf(
            PreviewCols.ID,
            PreviewCols.TITLE,
            PreviewCols.PACKAGE_NAME,
            PreviewCols.POSTER_ART_URI,
            PreviewCols.THUMBNAIL_URI,
            PreviewCols.INTENT_URI,
            PreviewCols.LAST_PLAYBACK_POSITION,
            PreviewCols.DURATION,
            PreviewCols.BROWSABLE
        )

        val uri =
            TvContractCompat
                .buildPreviewProgramsUriForChannel(
                    channelId
                )

        val result = mutableListOf<ProgramCard>()

        contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (
                cursor.moveToNext() &&
                result.size < 20
            ) {
                if (
                    cursor.intValue(
                        PreviewCols.BROWSABLE
                    ) == 0
                ) {
                    continue
                }

                val card =
                    cursor.readProgramCard()

                if (
                    card != null &&
                    card.ownerPackage == owner
                ) {
                    result.add(card)
                }
            }
        }

        return result
    }

    private fun Cursor.readProgramCard(
        engagementColumn: String? = null
    ): ProgramCard? {
        val id =
            longValue(
                PreviewCols.ID
            ) ?: return null

        val title =
            stringValue(
                PreviewCols.TITLE
            )?.takeIf {
                it.isNotBlank()
            } ?: return null

        val owner =
            stringValue(
                PreviewCols.PACKAGE_NAME
            )?.takeIf {
                it.isNotBlank()
            } ?: return null

        val image =
            stringValue(
                PreviewCols.POSTER_ART_URI
            )?.takeIf {
                it.isNotBlank()
            } ?: stringValue(
                PreviewCols.THUMBNAIL_URI
            )

        return ProgramCard(
            id = id,
            title = title,
            ownerPackage = owner,
            artworkUri = image,
            launchUri = stringValue(
                PreviewCols.INTENT_URI
            ),
            positionMillis =
                longValue(
                    PreviewCols
                        .LAST_PLAYBACK_POSITION
                ) ?: 0L,
            durationMillis =
                longValue(
                    PreviewCols.DURATION
                ) ?: 0L,
            engagementMillis =
                engagementColumn?.let {
                    longValue(it)
                } ?: 0L
        )
    }

    private fun Cursor.stringValue(
        column: String
    ): String? {
        val index = getColumnIndex(column)

        return if (
            index < 0 ||
            isNull(index)
        ) {
            null
        } else {
            getString(index)
        }
    }

    private fun Cursor.longValue(
        column: String
    ): Long? {
        val index = getColumnIndex(column)

        return if (
            index < 0 ||
            isNull(index)
        ) {
            null
        } else {
            getLong(index)
        }
    }

    private fun Cursor.intValue(
        column: String
    ): Int? {
        val index = getColumnIndex(column)

        return if (
            index < 0 ||
            isNull(index)
        ) {
            null
        } else {
            getInt(index)
        }
    }

    private fun loadProgramArtwork(
        image: ImageView,
        rawUri: String?
    ) {
        image.tag = rawUri

        if (rawUri.isNullOrBlank()) {
            return
        }

        imageWorker.execute {
            val bitmap = try {
                decodeProgramArtwork(rawUri)
            } catch (_: Exception) {
                null
            }

            runOnUiThread {
                if (
                    !isFinishing &&
                    !isDestroyed &&
                    image.tag == rawUri
                ) {
                    image.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeProgramArtwork(
        rawUri: String
    ): Bitmap? {
        val uri = Uri.parse(rawUri)

        val input = when (
            uri.scheme?.lowercase(Locale.ROOT)
        ) {
            "content",
            "android.resource" ->
                contentResolver.openInputStream(uri)

            "https" -> {
                val connection =
                    URL(rawUri).openConnection()

                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.getInputStream()
            }

            else -> null
        } ?: return null

        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0

            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break

                total += count

                if (total > 2_000_000) {
                    throw IllegalArgumentException(
                        "Affiche trop volumineuse"
                    )
                }

                output.write(buffer, 0, count)
            }

            output.toByteArray()
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            bounds
        )

        val largest =
            maxOf(
                bounds.outWidth,
                bounds.outHeight
            )

        if (largest <= 0) return null

        var sample = 1

        while (largest / sample > 700) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            options
        )
    }

    private fun renderCategory(category: String) {
        currentPage = category
        page.removeAllViews()
        firstContentButton = null
        lastRowButton = null

        tabs.forEach { (name, tab) ->
            tab.background =
                navigationBackground(
                    name == "Pour vous"
                )
        }

        sectionTitle(category)

        val description = when (category) {
            "Nouveautés" ->
                "Consulte les programmes publiés dans " +
                    "les recommandations Android TV ou " +
                    "ouvre tes plateformes."

            "À découvrir" ->
                "Les applications TV disponibles " +
                    "sur ta box."

            else ->
                "Applications susceptibles de proposer " +
                    "ce type de contenu. Leur catalogue " +
                    "dépend de ton abonnement."
        }

        paragraph(description)

        val apps = tvApps()

        val matching = apps.filter {
            matchesCategory(it, category)
        }

        if (matching.isNotEmpty()) {
            addAppRow(
                matching,
                "Aucune application correspondante."
            )
        } else {
            paragraph(
                "Aucune application spécialisée " +
                    "détectée. Voici les applications " +
                    "disponibles :"
            )

            addAppRow(
                apps,
                "Aucune application TV détectée."
            )
        }

        firstContentButton?.nextFocusUpId =
            tabs.getValue("Pour vous").id

        pageScroll.scrollTo(0, 0)
    }

    private fun renderFreeSection(
        title: String,
        description: String
    ) {
        sectionTitle(title)
        paragraph(description)

        val button = mainButton(
            "Ouvrir Free TV",
            true
        ) {
            openFreeApp(title)
        }

        page.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)
            ).apply {
                topMargin = dp(14)
            }
        )

        registerFirst(button)

        paragraph(
            "Le guide et le replay sont gérés " +
                "par l'application officielle Free TV."
        )
    }

    private fun renderReplay() {
        renderFreeSection(
            "Replay",
            "Ouvre Free TV pour accéder à son replay, " +
                "ou sélectionne une autre application " +
                "ci-dessous."
        )

        sectionTitle("Autres applications")

        addAppRow(
            tvApps().filter {
                matchesCategory(it, "Replay")
            },
            "Aucune autre application de replay détectée."
        )
    }

    private fun renderApplications(
        filter: String = ""
    ) {
        sectionTitle("Toutes les applis")

        if (filter.isNotEmpty()) {
            paragraph("Recherche : $filter")
        } else {
            paragraph(
                "Sélectionne une application avec " +
                    "la télécommande pour l'ouvrir."
            )
        }

        addAppRow(
            tvApps().filter {
                appName(it).contains(
                    filter,
                    ignoreCase = true
                )
            },
            "Aucune application TV trouvée."
        )
    }

    private fun renderVod() {
        sectionTitle("VOD")

        paragraph(
            "Ouvre un service installé pour voir son " +
                "catalogue. Certains contenus nécessitent " +
                "un abonnement."
        )

        addAppRow(
            tvApps().filter {
                matchesCategory(it, "VOD")
            },
            "Aucune application VOD détectée."
        )
    }

    private fun matchesCategory(
        app: ResolveInfo,
        category: String
    ): Boolean {
        val name =
            appName(app).lowercase(Locale.ROOT)

        val cinema = listOf(
            "netflix", "prime video", "disney",
            "canal", "max", "paramount",
            "apple tv", "rakuten", "free tv",
            "oqee", "pluto", "molotov"
        )

        val documentary = listOf(
            "arte", "france.tv", "france tv",
            "canal", "free tv", "oqee",
            "netflix", "prime video", "disney"
        )

        val youth = listOf(
            "disney", "gulli", "netflix",
            "prime video", "canal", "free tv",
            "oqee", "france.tv", "france tv"
        )

        val replay = listOf(
            "free tv", "oqee", "france.tv",
            "france tv", "arte", "molotov",
            "mytf1", "tf1+", "m6+"
        )

        val keywords = when (category) {
            "Films et séries", "VOD" -> cinema
            "Documentaires" -> documentary
            "Jeunesse" -> youth
            "Replay" -> replay
            else -> return true
        }

        return keywords.any {
            name.contains(it)
        }
    }

    private fun tvApps(): List<ResolveInfo> {
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(
                Intent.CATEGORY_LEANBACK_LAUNCHER
            )
        }

        @Suppress("DEPRECATION")
        return packageManager
            .queryIntentActivities(query, 0)
            .filter {
                it.activityInfo.packageName !=
                    packageName
            }
            .distinctBy {
                it.activityInfo.packageName
            }
            .sortedBy {
                appName(it).lowercase(
                    Locale.getDefault()
                )
            }
    }

    private fun appName(
        app: ResolveInfo
    ): String {
        return app.loadLabel(
            packageManager
        ).toString()
    }

    private fun addAppRow(
        apps: List<ResolveInfo>,
        emptyMessage: String
    ) {
        if (apps.isEmpty()) {
            paragraph(emptyMessage)
            return
        }

        val row = horizontalRow()
        var firstInRow: View? = null

        apps.forEachIndexed { index, app ->
            val card = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                setPadding(
                    dp(8),
                    dp(7),
                    dp(8),
                    dp(7)
                )

                background = cardBackground(
                    index + 4
                )

                isFocusable = true
                isClickable = true

                contentDescription =
                    "Ouvrir ${appName(app)}"

                lastRowButton?.let {
                    nextFocusUpId = it.id
                }

                setOnFocusChangeListener { view, focused ->
                    animateFocus(view, focused)
                }

                setOnClickListener {
                    launchApp(app)
                }
            }

            val icon = ImageView(this).apply {
                try {
                    setImageDrawable(
                        app.loadIcon(packageManager)
                    )
                } catch (_: Exception) {
                    setImageResource(
                        android.R.drawable
                            .sym_def_app_icon
                    )
                }

                isFocusable = false
            }

            card.addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(45),
                    dp(45)
                )
            )

            card.addView(
                label(
                    appName(app),
                    13f,
                    false
                ).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize =
                        TextUtils.TruncateAt.END
                    isFocusable = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(7)
                }
            )

            row.addView(
                card,
                LinearLayout.LayoutParams(
                    dp(147),
                    dp(101)
                ).apply {
                    rightMargin = dp(12)
                }
            )

            if (firstInRow == null) {
                firstInRow = card
            }

            registerFirst(card)
        }

        lastRowButton = firstInRow
    }

    private fun openFreeApp(
        section: String
    ) {
        val apps = tvApps()

        val savedPackage =
            preferences.getString(
                "free_app_package",
                null
            )

        val selected = apps.firstOrNull {
            it.activityInfo.packageName ==
                savedPackage
        } ?: apps.firstOrNull {
            val name =
                appName(it).lowercase(Locale.ROOT)

            name.contains("free tv") ||
                name.contains("oqee")
        }

        if (selected != null) {
            launchApp(selected)
            return
        }

        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Free TV introuvable")
                .setMessage(
                    "Aucune application TV n'a été " +
                        "détectée sur cet appareil."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Choisir l'application pour $section"
            )
            .setMessage(
                "Sélectionne Free TV si elle apparaît. " +
                    "Ce choix sera mémorisé."
            )
            .setPositiveButton("Choisir") { _, _ ->
                chooseFreeApplication()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooseFreeApplication() {
        val apps = tvApps()

        if (apps.isEmpty()) {
            message(
                "Aucune application TV détectée."
            )
            return
        }

        val names = apps.map {
            appName(it)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Mon application TV Free")
            .setItems(names) { _, position ->
                val selected = apps[position]

                preferences.edit()
                    .putString(
                        "free_app_package",
                        selected.activityInfo.packageName
                    )
                    .apply()

                launchApp(selected)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launchApp(app: ResolveInfo) {
        try {
            val intent = packageManager
                .getLeanbackLaunchIntentForPackage(
                    app.activityInfo.packageName
                ) ?: Intent(Intent.ACTION_MAIN).apply {
                    setClassName(
                        app.activityInfo.packageName,
                        app.activityInfo.name
                    )
                    addCategory(
                        Intent.CATEGORY_LEANBACK_LAUNCHER
                    )
                }

            startActivity(intent)

        } catch (_: Exception) {
            message(
                "Impossible d'ouvrir cette application " +
                    "sur cet appareil."
            )
        }
    }

    private fun searchApplications() {
        val input = EditText(this).apply {
            hint = "Nom d'une application"
            isSingleLine = true
            setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(12)
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Chercher une application TV")
            .setView(input)
            .setPositiveButton("Chercher") { _, _ ->
                renderPage("Applis")
                page.removeAllViews()

                firstContentButton = null
                lastRowButton = null

                renderApplications(
                    input.text.toString().trim()
                )

                firstContentButton?.requestFocus()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /*
     * Android ne permet pas à une application ordinaire de lancer
     * directement l'économiseur d'écran. On ouvre donc ses réglages
     * et on l'indique clairement, sans faire croire qu'il démarre.
     */
    private fun openScreensaver() {
        val attempts = listOf(
            Intent(Settings.ACTION_DREAM_SETTINGS),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in attempts) {
            try {
                startActivity(intent)
                message(
                    "Lancement direct impossible : réglages de " +
                        "l'économiseur d'écran ouverts."
                )
                return
            } catch (_: Exception) {
                // On essaie l'écran de réglages suivant.
            }
        }

        message(
            "L'économiseur d'écran n'est pas accessible " +
                "sur cet appareil."
        )
    }

    private fun showSettings() {
        val choices = arrayOf(
            "Choisir ma ville",
            "Changer le logo",
            "Choisir l'application Free TV",
            "Retirer le logo choisi",
            "À propos de la météo"
        )

        AlertDialog.Builder(this)
            .setTitle("Réglages du launcher")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> chooseCity()
                    1 -> chooseLogo()
                    2 -> chooseFreeApplication()

                    3 -> {
                        preferences.edit()
                            .remove("logo_uri")
                            .apply()
                        restoreLogo()
                    }

                    4 -> AlertDialog.Builder(this)
                        .setTitle("Météo")
                        .setMessage(
                            "Prévisions et recherche de " +
                                "villes : Open-Meteo. " +
                                "La ville est choisie " +
                                "manuellement."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun chooseCity() {
        val input = EditText(this).apply {
            hint = "Exemple : Paris"
            isSingleLine = true

            setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(12)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Choisir une ville pour la météo"
            )
            .setView(input)
            .setPositiveButton("Rechercher") { _, _ ->
                val city =
                    input.text.toString().trim()

                if (city.length < 2) {
                    message(
                        "Entre au moins deux lettres."
                    )
                } else {
                    findCities(city)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun findCities(search: String) {
        weatherLine = "Recherche en cours…"
        updateClock()

        worker.execute {
            try {
                val encoded =
                    URLEncoder.encode(search, "UTF-8")

                val data = getJson(
                    "https://geocoding-api.open-meteo.com/" +
                        "v1/search?name=$encoded&count=5" +
                        "&language=fr"
                )

                val results =
                    data.optJSONArray("results")

                val cities =
                    mutableListOf<CityResult>()

                if (results != null) {
                    for (index in 0 until results.length()) {
                        val item =
                            results.getJSONObject(index)

                        cities.add(
                            CityResult(
                                item.optString("name"),
                                item.optString("admin1"),
                                item.optString("country"),
                                item.getDouble("latitude"),
                                item.getDouble("longitude")
                            )
                        )
                    }
                }

                runOnUiThread {
                    if (cities.isEmpty()) {
                        weatherLine =
                            "Ville introuvable"
                        updateClock()
                    } else {
                        showCityResults(cities)
                    }
                }

            } catch (_: Exception) {
                runOnUiThread {
                    weatherLine =
                        "Météo indisponible"
                    updateClock()
                }
            }
        }
    }

    private fun showCityResults(
        cities: List<CityResult>
    ) {
        val names = cities.map { city ->
            listOf(
                city.name,
                city.region,
                city.country
            )
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Sélectionner votre ville")
            .setItems(names) { _, position ->
                val chosen = cities[position]

                preferences.edit()
                    .putString(
                        "city_name",
                        chosen.name
                    )
                    .putString(
                        "city_lat",
                        chosen.latitude.toString()
                    )
                    .putString(
                        "city_lon",
                        chosen.longitude.toString()
                    )
                    .apply()

                lastWeatherUpdate = 0L
                updateWeather()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun updateWeather() {
        val city =
            preferences.getString(
                "city_name",
                null
            ) ?: return

        val latitude =
            preferences.getString(
                "city_lat",
                null
            ) ?: return

        val longitude =
            preferences.getString(
                "city_lon",
                null
            ) ?: return

        if (
            System.currentTimeMillis() -
            lastWeatherUpdate <
            30L * 60L * 1000L
        ) {
            updateClock()
            return
        }

        lastWeatherUpdate =
            System.currentTimeMillis()

        weatherLine = "$city · météo…"
        updateClock()

        worker.execute {
            try {
                val result = getJson(
                    "https://api.open-meteo.com/" +
                        "v1/forecast" +
                        "?latitude=$latitude" +
                        "&longitude=$longitude" +
                        "&current=temperature_2m," +
                        "weather_code&timezone=auto"
                )

                val current =
                    result.getJSONObject("current")

                val temperature =
                    current.getDouble(
                        "temperature_2m"
                    ).roundToInt()

                val code =
                    current.getInt("weather_code")

                val condition = when (code) {
                    0 -> "☀"
                    1, 2 -> "⛅"
                    3 -> "☁"
                    45, 48 -> "≋"
                    in 51..67, in 80..82 -> "☂"
                    in 71..77, 85, 86 -> "❄"
                    in 95..99 -> "⚡"
                    else -> "°"
                }

                runOnUiThread {
                    weatherLine =
                        "$city · $condition " +
                            "$temperature°C"

                    updateClock()
                }

            } catch (_: Exception) {
                lastWeatherUpdate = 0L

                runOnUiThread {
                    weatherLine =
                        "$city · météo indisponible"
                    updateClock()
                }
            }
        }
    }

    private fun getJson(address: String): JSONObject {
        val connection =
            URL(address).openConnection()
                as HttpURLConnection

        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"

        try {
            if (
                connection.responseCode !in 200..299
            ) {
                throw IllegalStateException(
                    "Réponse HTTP " +
                        connection.responseCode
                )
            }

            val text =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            return JSONObject(text)

        } finally {
            connection.disconnect()
        }
    }

    private fun updateClock() {
        if (!::clockWeather.isInitialized) {
            return
        }

        val time = SimpleDateFormat(
            "HH:mm",
            Locale.FRANCE
        ).format(Date())

        clockWeather.text =
            "$time\n$weatherLine"
    }

    private fun chooseLogo() {
        val pick =
            Intent(
                Intent.ACTION_OPEN_DOCUMENT
            ).apply {
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type = "image/*"

                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "image/png",
                        "image/jpeg"
                    )
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent
                            .FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                pick,
                REQUEST_LOGO
            )

        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle(
                    "Sélection de fichier indisponible"
                )
                .setMessage(
                    "Ce lecteur TV ne propose pas de " +
                        "sélecteur de fichiers. Tu peux " +
                        "ajouter logo_personnalise.png " +
                        "dans app/src/main/res/drawable/ " +
                        "sur GitHub, puis recompiler l'APK."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    @Deprecated(
        "Utilisé ici sans bibliothèque supplémentaire"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode != REQUEST_LOGO ||
            resultCode != RESULT_OK ||
            data?.data == null
        ) {
            return
        }

        val selected =
            data.data ?: return

        try {
            contentResolver
                .takePersistableUriPermission(
                    selected,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
        } catch (_: SecurityException) {
            // L'image peut rester accessible
            // pendant cette session.
        }

        preferences.edit()
            .putString(
                "logo_uri",
                selected.toString()
            )
            .apply()

        restoreLogo()
    }

    private fun restoreLogo() {
        val image =
            findViewById<ImageView>(
                R.id.customLogo
            )

        val placeholder =
            findViewById<TextView>(
                R.id.defaultLogo
            )

        val stored =
            preferences.getString(
                "logo_uri",
                null
            )

        if (stored != null) {
            try {
                val uri =
                    Uri.parse(stored)

                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                contentResolver
                    .openInputStream(uri)
                    ?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            options
                        )
                    }

                if (
                    options.outWidth <= 0 ||
                    options.outHeight <= 0
                ) {
                    throw IllegalArgumentException(
                        "Image non reconnue"
                    )
                }

                var sample = 1

                while (
                    options.outWidth / sample > 512 ||
                    options.outHeight / sample > 512
                ) {
                    sample *= 2
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = sample

                val bitmap =
                    contentResolver
                        .openInputStream(uri)
                        ?.use {
                            BitmapFactory.decodeStream(
                                it,
                                null,
                                options
                            )
                        } ?: throw IllegalArgumentException(
                        "Image illisible"
                    )

                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                placeholder.visibility = View.GONE

                return

            } catch (_: Exception) {
                preferences.edit()
                    .remove("logo_uri")
                    .apply()
            }
        }

        val bundledLogo =
            resources.getIdentifier(
                "logo_personnalise",
                "drawable",
                packageName
            )

        if (bundledLogo != 0) {
            image.setImageResource(bundledLogo)
            image.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
        }
    }

    private fun sectionTitle(title: String) {
        page.addView(
            label(title, 22f, true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(9)
            }
        )
    }

    private fun paragraph(text: String) {
        page.addView(
            label(text, 15f, false).apply {
                setTextColor(0xFFCFD7E2.toInt())
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
                bottomMargin = dp(8)
            }
        )
    }

    private fun label(
        value: String,
        size: Float,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)

            if (bold) {
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )
            }
        }
    }

    private fun mainButton(
        title: String,
        red: Boolean,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            id = View.generateViewId()
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)

            setTypeface(
                null,
                android.graphics.Typeface.BOLD
            )

            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)

            background = focusBackground(
                if (red) {
                    0xFFE60012.toInt()
                } else {
                    0xFF25354E.toInt()
                },
                24
            )

            isFocusable = true
            isClickable = true

            setOnClickListener {
                action()
            }
        }
    }

    private fun categoryCard(
        title: String,
        subtitle: String,
        index: Int
    ): LinearLayout {
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM

            setPadding(
                dp(15),
                dp(10),
                dp(15),
                dp(12)
            )

            background = cardBackground(index)
            isFocusable = true
            isClickable = true
            contentDescription =
                "$title. $subtitle"

            setOnFocusChangeListener { view, focused ->
                animateFocus(view, focused)
            }

            addView(
                label(title, 19f, true)
            )

            addView(
                label(
                    subtitle,
                    12f,
                    false
                ).apply {
                    setTextColor(
                        0xFFE2E6EC.toInt()
                    )
                }
            )
        }
    }

    private fun horizontalRow(): LinearLayout {
        val scroller =
            HorizontalScrollView(this).apply {
                isFocusable = false
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(4))
        }

        scroller.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        page.addView(
            scroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return row
    }

    private fun registerFirst(view: View) {
        if (firstContentButton == null) {
            firstContentButton = view

            view.nextFocusUpId =
                tabs[currentPage]?.id
                    ?: tabs.getValue("Pour vous").id
        }
    }

    private fun animateFocus(
        view: View,
        focused: Boolean
    ) {
        view.animate()
            .scaleX(
                if (focused) 1.035f else 1f
            )
            .scaleY(
                if (focused) 1.035f else 1f
            )
            .setDuration(120)
            .start()
    }

    private fun navigationBackground(
        selected: Boolean
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(
                    android.R.attr.state_focused
                ),
                rounded(
                    0xFFE60012.toInt(),
                    23
                )
            )

            addState(
                intArrayOf(),
                rounded(
                    if (selected) {
                        0xFFCDD0D5.toInt()
                    } else {
                        Color.TRANSPARENT
                    },
                    23
                )
            )
        }
    }

    private fun focusBackground(
        normalColor: Int,
        radius: Int
    ): StateListDrawable {
        val focused =
            rounded(
                0xFFE60012.toInt(),
                radius
            ).apply {
                setStroke(
                    dp(3),
                    Color.WHITE
                )
            }

        return StateListDrawable().apply {
            addState(
                intArrayOf(
                    android.R.attr.state_focused
                ),
                focused
            )

            addState(
                intArrayOf(),
                rounded(
                    normalColor,
                    radius
                )
            )
        }
    }

    private fun cardBackground(
        index: Int
    ): StateListDrawable {
        val palettes = arrayOf(
            intArrayOf(
                0xFF713850.toInt(),
                0xFF24223A.toInt()
            ),
            intArrayOf(
                0xFF255E7C.toInt(),
                0xFF18324D.toInt()
            ),
            intArrayOf(
                0xFF715638.toInt(),
                0xFF2A293C.toInt()
            ),
            intArrayOf(
                0xFF594982.toInt(),
                0xFF27253D.toInt()
            ),
            intArrayOf(
                0xFF286470.toInt(),
                0xFF1A333B.toInt()
            )
        )

        val normal = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            palettes[index % palettes.size]
        ).apply {
            cornerRadius = dp(13).toFloat()
        }

        val focused =
            rounded(
                0xFFE60012.toInt(),
                13
            ).apply {
                setStroke(
                    dp(3),
                    Color.WHITE
                )
            }

        return StateListDrawable().apply {
            addState(
                intArrayOf(
                    android.R.attr.state_focused
                ),
                focused
            )

            addState(
                intArrayOf(),
                normal
            )
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (
            value *
                resources.displayMetrics.density
            ).roundToInt()
    }

    private fun message(text: String) {
        Toast.makeText(
            this,
            text,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val REQUEST_LOGO = 42
        private const val REQUEST_TV_LISTINGS = 4001
        private const val READ_TV_LISTINGS =
            "android.permission.READ_TV_LISTINGS"
    }
}
