package com.android.systemui.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.IntegerRes
import com.android.systemui.R
import com.android.systemui.util.animation.TransitionLayout

/** ViewHolder for a Smartspace media recommendation. */
class RecommendationViewHolder private constructor(itemView: View) {

    val recommendations = itemView as TransitionLayout
    val mediaCoverItems = listOf<ImageView>(
        itemView.requireViewById(R.id.media_cover1),
        itemView.requireViewById(R.id.media_cover2),
        itemView.requireViewById(R.id.media_cover3),
        itemView.requireViewById(R.id.media_cover4))
    val mediaLogoItems = listOf<ImageView>(
        itemView.requireViewById(R.id.media_logo1),
        itemView.requireViewById(R.id.media_logo2),
        itemView.requireViewById(R.id.media_logo3),
        itemView.requireViewById(R.id.media_logo4))
    val mediaCoverItemsResIds = listOf<@IntegerRes Int>(
        R.id.media_cover1,
        R.id.media_cover2,
        R.id.media_cover3,
        R.id.media_cover4)
    val mediaLogoItemsResIds = listOf<@IntegerRes Int>(
        R.id.media_logo1,
        R.id.media_logo2,
        R.id.media_logo3,
        R.id.media_logo4)

    init {
        (recommendations.background as IlluminationDrawable).let { background ->
            mediaCoverItems.forEach { background.registerLightSource(it) }
            mediaLogoItems.forEach { background.registerLightSource(it) }
        }
    }

    companion object {
        /**
         * Creates a PlayerViewHolder.
         *
         * @param inflater LayoutInflater to use to inflate the layout.
         * @param parent Parent of inflated view.
         */
        @JvmStatic fun create(inflater: LayoutInflater, parent: ViewGroup):
            RecommendationViewHolder {
            val itemView =
                inflater.inflate(R.layout.smartspace_card_media, parent, /* attachToRoot= */ false)
            // Because this media view (a TransitionLayout) is used to measure and layout the views
            // in various states before being attached to its parent, we can't depend on the default
            // LAYOUT_DIRECTION_INHERIT to correctly resolve the ltr direction.
            itemView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            return RecommendationViewHolder(itemView)
        }
    }
}