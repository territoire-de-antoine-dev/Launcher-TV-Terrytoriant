package com.example.salontv

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var tabRow: LinearLayout
    private lateinit var featuredRow: LinearLayout
    private lateinit var appsRow: LinearLayout
    private lateinit var tvButton: TextView

    private val tabs = linkedMapOf<String, TextView>()
    private var activeTab = "Pour vous"
    private var firstFeatured: View? = null
    private var firstApp: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_main)

        tabRow = findViewById(R.id.tabRow)
        featuredRow = findViewById(R.id.featuredRow)
        appsRow = findViewById(R.id.appsRow)
        tvButton = findViewById(R.id.tvButton)

        findViewById<LinearLayout>(R.id.topBar).background =
            rounded(0xEDEFEFEF.toInt(), 28)

        findViewById<FrameLayout>(R.id.hero).clipToOutline = true

        // Facultatif : afficher res/drawable/hero_photo.jpg si ajouté.
        val photoId = resources.getIdentifier(
            "hero_photo",
            "drawable",
            packageName
        )
        if (photoId != 0) {
            findViewById<ImageView>(R.id.heroImage).apply {
                setImageResource(photoId)
                visibility = View.VISIBLE
            }
        }

        createTabs()
        createFeaturedCards()
        populateApps()

        tvButton.setOnClickListener { chooseTvApp() }
        tvButton.nextFocusUpId = tabs.getValue("Pour vous").id

        setupAction(
            findViewById(R.id.searchButton)
        ) { searchApps() }

        setupAction(
            findViewById(R.id.settingsButton)
        ) { openSettings() }

        tabRow.post {
            tabs.getValue("Pour vous").requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::appsRow.isInitialized) {
            populateApps()
        }
    }

    private fun setupAction(view: TextView, action: () -> Unit) {
        view.nextFocusDownId = R.id.tvButton
        view.background = navBackground(false)

        view.setOnFocusChangeListener { v, focused ->
            (v as TextView).setTextColor(
                if (focused) Color.WHITE else 0xFF252A32.toInt()
            )
        }

        view.setOnClickListener { action() }
    }

    private fun createTabs() {
        val entries = listOf(
            "⌂  Pour vous" to "Pour vous",
            "▦  Guide TV" to "Guide TV",
            "◷  Replay" to "Replay",
            "▣  Applis" to "Applis",
            "▶  VOD" to "VOD",
            "♟  Jeux" to "Jeux",
            "◇  Les Offres" to "Les Offres"
        )

        entries.forEach { (label, name) ->
            val tab = TextView(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(0xFF252A32.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                isFocusable = true
                isClickable = true
                contentDescription = name
                nextFocusDownId = R.id.tvButton

                setOnFocusChangeListener { view, focused ->
                    (view as TextView).setTextColor(
                        if (focused) Color.WHITE
                        else 0xFF252A32.toInt()
                    )
                }

                setOnClickListener {
                    setActiveTab(name)

                    when (name) {
                        "Pour vous" -> tvButton.requestFocus()
                        "Applis" -> {
                            if (firstApp?.requestFocus() != true) {
                                message("Aucune application TV détectée.")
                            }
                        }
                        else -> showDemoNotice(name)
                    }
                }
            }

            tabRow.addView(
                tab,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                )
            )
            tabs[name] = tab
        }

        setActiveTab("Pour vous")
    }

    private fun setActiveTab(name: String) {
        activeTab = name
        tabs.forEach { (tabName, view) ->
            view.background = navBackground(tabName == activeTab)

            if (!view.isFocused) {
                view.setTextColor(0xFF252A32.toInt())
            }
        }
    }

    private fun createFeaturedCards() {
        val features = listOf(
            "À découvrir" to "Sélection du moment",
            "Films et séries" to "Vos idées de soirée",
            "Documentaires" to "Explorer et apprendre",
            "Jeunesse" to "Pour toute la famille",
            "Nouveautés" to "D'autres suggestions"
        )

        features.forEachIndexed { index, (title, subtitle) ->
            val card = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(dp(17), dp(12), dp(17), dp(14))
                background = cardBackground(index)
                isFocusable = true
                isClickable = true
                contentDescription = "$title — $subtitle"
                nextFocusUpId = R.id.tvButton

                setOnFocusChangeListener { view, focused ->
                    animateFocus(view, focused)
                }

                setOnClickListener {
                    showDemoNotice(title)
                }
            }

            card.addView(TextView(this).apply {
                text = title
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            card.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(0xFFE9E9E9.toInt())
            })

            featuredRow.addView(
                card,
                LinearLayout.LayoutParams(dp(246), dp(112)).apply {
                    rightMargin = dp(12)
                }
            )

            if (index == 0) {
                firstFeatured = card
                tvButton.nextFocusDownId = card.id
            }
        }
    }

    private fun installedTvApps(): List<ResolveInfo> {
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy {
                it.loadLabel(packageManager)
                    .toString()
                    .lowercase(Locale.getDefault())
            }
    }

    private fun populateApps(filter: String = "") {
        appsRow.removeAllViews()
        firstApp = null

        val apps = installedTvApps().filter {
            it.loadLabel(packageManager)
                .toString()
                .contains(filter, ignoreCase = true)
        }

        if (apps.isEmpty()) {
            appsRow.addView(
                TextView(this).apply {
                    text = if (filter.isEmpty()) {
                        "Aucune application TV détectée"
                    } else {
                        "Aucun résultat pour « $filter »"
                    }
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(104)
                )
            )
            return
        }

        apps.forEachIndexed { index, app ->
            val appName = app.loadLabel(packageManager).toString()

            val card = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = cardBackground(index + 5)
                isFocusable = true
                isClickable = true
                contentDescription = "Ouvrir $appName"

                firstFeatured?.let {
                    nextFocusUpId = it.id
                }

                setOnFocusChangeListener { view, focused ->
                    animateFocus(view, focused)
                }

                setOnClickListener {
                    launchApp(app)
                }
            }

            card.addView(
                ImageView(this).apply {
                    try {
                        setImageDrawable(app.loadIcon(packageManager))
                    } catch (_: Exception) {
                        setImageResource(
                            android.R.drawable.sym_def_app_icon
                        )
                    }
                    isFocusable = false
                },
                LinearLayout.LayoutParams(dp(46), dp(46))
            )

            card.addView(
                TextView(this).apply {
                    text = appName
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    isFocusable = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(9)
                }
            )

            appsRow.addView(
                card,
                LinearLayout.LayoutParams(dp(148), dp(104)).apply {
                    rightMargin = dp(12)
                }
            )

            if (index == 0) {
                firstApp = card
            }
        }

        firstApp?.let { first ->
            for (index in 0 until featuredRow.childCount) {
                featuredRow.getChildAt(index).nextFocusDownId = first.id
            }
        }
    }

    private fun chooseTvApp() {
        val apps = installedTvApps()

        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Accéder à la TV")
                .setMessage(
                    "Aucune application TV compatible n'est détectée. " +
                    "Installez d'abord une application TV sur cet appareil."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = apps.map {
            it.loadLabel(packageManager).toString()
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choisir une application TV")
            .setItems(names) { _, position ->
                launchApp(apps[position])
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launchApp(app: ResolveInfo) {
        try {
            val activity = app.activityInfo

            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(
                        activity.packageName,
                        activity.name
                    )
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                }
            )
        } catch (_: Exception) {
            message("Impossible d'ouvrir cette application.")
        }
    }

    private fun searchApps() {
        val input = EditText(this).apply {
            hint = "Nom d'une application"
            isSingleLine = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val wrapper = FrameLayout(this).apply {
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Rechercher une application TV")
            .setView(wrapper)
            .setPositiveButton("Rechercher") { _, _ ->
                populateApps(input.text.toString().trim())

                if (firstApp?.requestFocus() != true) {
                    message("Aucune application trouvée.")
                }
            }
            .setNeutralButton("Tout afficher") { _, _ ->
                populateApps()
                firstApp?.requestFocus()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            message("Paramètres indisponibles sur cet appareil.")
        }
    }

    private fun showDemoNotice(section: String) {
        AlertDialog.Builder(this)
            .setTitle(section)
            .setMessage(
                "Cette section présente l'interface. Pour afficher " +
                "de vrais contenus, il faut une source de données autorisée."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun animateFocus(view: View, focused: Boolean) {
        view.animate()
            .scaleX(if (focused) 1.035f else 1f)
            .scaleY(if (focused) 1.035f else 1f)
            .setDuration(120)
            .start()
    }

    private fun navBackground(selected: Boolean): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rounded(0xFFE60012.toInt(), 23)
            )
            addState(
                intArrayOf(),
                rounded(
                    if (selected) 0xFFCED0D4.toInt()
                    else Color.TRANSPARENT,
                    23
                )
            )
        }
    }

    private fun cardBackground(index: Int): StateListDrawable {
        val palettes = arrayOf(
            intArrayOf(0xFF70304C.toInt(), 0xFF202032.toInt()),
            intArrayOf(0xFF276785.toInt(), 0xFF172A42.toInt()),
            intArrayOf(0xFF725638.toInt(), 0xFF242033.toInt()),
            intArrayOf(0xFF675086.toInt(), 0xFF24223A.toInt()),
            intArrayOf(0xFF3C6970.toInt(), 0xFF182B32.toInt())
        )

        val normal = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            palettes[index % palettes.size]
        ).apply {
            cornerRadius = dp(14).toFloat()
        }

        val focused = GradientDrawable().apply {
            setColor(0xFFE60012.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(3), Color.WHITE)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
