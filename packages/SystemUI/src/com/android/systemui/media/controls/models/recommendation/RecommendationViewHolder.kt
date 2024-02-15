/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media.controls.models.recommendation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.android.internal.widget.CachingIconView
import com.android.systemui.res.R
import com.android.systemui.media.controls.models.GutsViewHolder
import com.android.systemui.media.controls.ui.IlluminationDrawable
import com.android.systemui.util.animation.TransitionLayout

private const val TAG = "RecommendationViewHolder"

/** ViewHolder for a Smartspace media recommendation. */
class RecommendationViewHolder private constructor(itemView: View) {

    val recommendations = itemView as TransitionLayout

    // Recommendation screen
    val cardTitle: TextView = itemView.requireViewById(R.id.media_rec_title)

    val mediaCoverContainers =
        listOf<ViewGroup>(
            itemView.requireViewById(R.id.media_cover1_container),
            itemView.requireViewById(R.id.media_cover2_container),
            itemView.requireViewById(R.id.media_cover3_container)
        )
    val mediaAppIcons: List<CachingIconView> =
        mediaCoverContainers.map { it.requireViewById(R.id.media_rec_app_icon) }
    val mediaTitles: List<TextView> =
        mediaCoverContainers.map { it.requireViewById(R.id.media_title) }
    val mediaSubtitles: List<TextView> =
        mediaCoverContainers.map { it.requireViewById(R.id.media_subtitle) }
    val mediaProgressBars: List<SeekBar> =
        mediaCoverContainers.map {
            it.requireViewById<SeekBar?>(R.id.media_progress_bar).apply {
                // Media playback is in the direction of tape, not time, so it stays LTR
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
        }

    val mediaCoverItems: List<ImageView> =
        mediaCoverContainers.map { it.requireViewById(R.id.media_cover) }
    val gutsViewHolder = GutsViewHolder(itemView)

    init {
        (recommendations.background as IlluminationDrawable).let { background ->
            mediaCoverContainers.forEach { background.registerLightSource(it) }
            background.registerLightSource(gutsViewHolder.cancel)
            background.registerLightSource(gutsViewHolder.dismiss)
            background.registerLightSource(gutsViewHolder.settings)
        }
    }

    fun marquee(start: Boolean, delay: Long) {
        gutsViewHolder.marquee(start, delay, TAG)
    }

    companion object {
        /**
         * Creates a RecommendationViewHolder.
         *
         * @param inflater LayoutInflater to use to inflate the layout.
         * @param parent Parent of inflated view.
         */
        @JvmStatic
        fun create(inflater: LayoutInflater, parent: ViewGroup): RecommendationViewHolder {
            val itemView =
                inflater.inflate(R.layout.media_recommendations, parent, false /* attachToRoot */)
            // Because this media view (a TransitionLayout) is used to measure and layout the views
            // in various states before being attached to its parent, we can't depend on the default
            // LAYOUT_DIRECTION_INHERIT to correctly resolve the ltr direction.
            itemView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            return RecommendationViewHolder(itemView)
        }

        // Res Ids for the control components on the recommendation view.
        val controlsIds =
            setOf(
                R.id.media_rec_title,
                R.id.media_cover,
                R.id.media_cover1_container,
                R.id.media_cover2_container,
                R.id.media_cover3_container,
                R.id.media_title,
                R.id.media_subtitle,
            )

        val mediaTitlesAndSubtitlesIds =
            setOf(
                R.id.media_title,
                R.id.media_subtitle,
            )

        val mediaContainersIds =
            setOf(
                R.id.media_cover1_container,
                R.id.media_cover2_container,
                R.id.media_cover3_container
            )

        val backgroundId = R.id.sizing_view
    }
}
