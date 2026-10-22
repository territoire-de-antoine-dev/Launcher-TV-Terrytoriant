package com.example.salontv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.Executors

class TvProgramAdapter(
    private val items: List<TvProgramCard>
) : RecyclerView.Adapter<TvProgramAdapter.CardHolder>() {

    class CardHolder(view: View) :
        RecyclerView.ViewHolder(view) {

        val poster: ImageView =
            view.findViewById(R.id.programPoster)

        val title: TextView =
            view.findViewById(R.id.programTitle)

        val progress: ProgressBar =
            view.findViewById(R.id.programProgress)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): CardHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(
                R.layout.item_tv_program,
                parent,
                false
            )

        return CardHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(
        holder: CardHolder,
        position: Int
    ) {
        val card = items[position]
        val context = holder.itemView.context

        holder.title.text = card.title
        holder.progress.progress = card.progressPercent
        holder.progress.visibility =
            if (card.progressPercent > 0) {
                View.VISIBLE
            } else {
                View.GONE
            }

        holder.itemView.setOnFocusChangeListener { view, focused ->
            view.background = context.getDrawable(
                if (focused) {
                    R.drawable.bg_tile_focused
                } else {
                    R.drawable.bg_tile
                }
            )

            view.scaleX = if (focused) 1.035f else 1f
            view.scaleY = if (focused) 1.035f else 1f
        }

        holder.itemView.setOnClickListener {
            openPublishedContent(
                context,
                card
            )
        }

        val imageKey =
            "${card.packageName}:${card.id}:${card.posterUri}"

        holder.poster.tag = imageKey
        holder.poster.setImageDrawable(null)

        loadPoster(
            context.applicationContext,
            card.posterUri
        ) { bitmap ->
            if (holder.poster.tag == imageKey) {
                holder.poster.setImageBitmap(bitmap)
            }
        }
    }

    private fun openPublishedContent(
        context: Context,
        card: TvProgramCard
    ) {
        val uri = card.intentUri

        if (uri.isNullOrBlank()) {
            Toast.makeText(
                context,
                "Cette fiche n'a pas de lien d'ouverture.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            val intent = Intent.parseUri(
                uri,
                Intent.URI_INTENT_SCHEME
            )

            // Une fiche ne doit pas nous envoyer vers une autre
            // application que celle qui l'a publiée.
            val explicitPackage =
                intent.component?.packageName

            if (
                explicitPackage != null &&
                explicitPackage != card.packageName
            ) {
                throw SecurityException(
                    "Application cible différente de la source."
                )
            }

            intent.setPackage(card.packageName)
            context.startActivity(intent)

        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Impossible d'ouvrir ce contenu dans " +
                    card.packageName,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadPoster(
        context: Context,
        rawUri: String?,
        callback: (Bitmap?) -> Unit
    ) {
        if (rawUri.isNullOrBlank()) {
            callback(null)
            return
        }

        imageExecutor.execute {
            val bitmap = try {
                val uri = Uri.parse(rawUri)

                val input = when (uri.scheme?.lowercase()) {
                    "content", "android.resource" ->
                        context.contentResolver.openInputStream(uri)

                    "https" ->
                        URL(rawUri).openConnection().apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                        }.getInputStream()

                    else -> null
                }

                val bytes = input?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0

                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break

                        total += count
                        if (total > 2_000_000) {
                            throw IllegalArgumentException(
                                "Image trop volumineuse"
                            )
                        }

                        output.write(buffer, 0, count)
                    }

                    output.toByteArray()
                }

                if (bytes == null) {
                    null
                } else {
                    val bounds = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        bounds
                    )

                    var sampleSize = 1
                    val largest = maxOf(
                        bounds.outWidth,
                        bounds.outHeight
                    )

                    while (
                        largest > 0 &&
                        largest / sampleSize > 700
                    ) {
                        sampleSize *= 2
                    }

                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }

                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        options
                    )
                }
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                callback(bitmap)
            }
        }
    }

    companion object {
        private val imageExecutor =
            Executors.newFixedThreadPool(2)

        private val mainHandler =
            Handler(Looper.getMainLooper())
    }
}
