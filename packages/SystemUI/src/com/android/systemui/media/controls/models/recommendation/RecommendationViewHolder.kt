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
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.media.controls.models.GutsViewHolder
import com.android.systemui.media.controls.ui.IlluminationDrawable
import com.android.systemui.util.animation.TransitionLayout

private const val TAG = "RecommendationViewHolder"

/** ViewHolder for a Smartspace media recommendation. */
class RecommendationViewHolder private constructor(itemView: View) {

    val recommendations = itemView as TransitionLayout

    // Recommendation screen
    val cardIcon = itemView.requireViewById<ImageView>(R.id.recommendation_card_icon)
    val mediaCoverItems =
        listOf<ImageView>(
            itemView.requireViewById(R.id.media_cover1),
            itemView.requireViewById(R.id.media_cover2),
            itemView.requireViewById(R.id.media_cover3)
        )
    val mediaCoverContainers =
        listOf<ViewGroup>(
            itemView.requireViewById(R.id.media_cover1_container),
            itemView.requireViewById(R.id.media_cover2_container),
            itemView.requireViewById(R.id.media_cover3_container)
        )
    val mediaTitles: List<TextView> =
        listOf(
            itemView.requireViewById(R.id.media_title1),
            itemView.requireViewById(R.id.media_title2),
            itemView.requireViewById(R.id.media_title3)
        )
    val mediaSubtitles: List<TextView> =
        listOf(
            itemView.requireViewById(R.id.media_subtitle1),
            itemView.requireViewById(R.id.media_subtitle2),
            itemView.requireViewById(R.id.media_subtitle3)
        )

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
                inflater.inflate(
                    R.layout.media_smartspace_recommendations,
                    parent,
                    false /* attachToRoot */
                )
            // Because this media view (a TransitionLayout) is used to measure and layout the views
            // in various states before being attached to its parent, we can't depend on the default
            // LAYOUT_DIRECTION_INHERIT to correctly resolve the ltr direction.
            itemView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            return RecommendationViewHolder(itemView)
        }

        // Res Ids for the control components on the recommendation view.
        val controlsIds =
            setOf(
                R.id.recommendation_card_icon,
                R.id.media_cover1,
                R.id.media_cover2,
                R.id.media_cover3,
                R.id.media_cover1_container,
                R.id.media_cover2_container,
                R.id.media_cover3_container,
                R.id.media_title1,
                R.id.media_title2,
                R.id.media_title3,
                R.id.media_subtitle1,
                R.id.media_subtitle2,
                R.id.media_subtitle3
            )

        val mediaTitlesAndSubtitlesIds =
            setOf(
                R.id.media_title1,
                R.id.media_title2,
                R.id.media_title3,
                R.id.media_subtitle1,
                R.id.media_subtitle2,
                R.id.media_subtitle3
            )

        val mediaContainersIds =
            setOf(
                R.id.media_cover1_container,
                R.id.media_cover2_container,
                R.id.media_cover3_container
            )
    }
}
