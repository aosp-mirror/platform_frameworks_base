/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import android.annotation.ColorInt
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin
import com.android.systemui.statusbar.notification.people.DataListener
import com.android.systemui.statusbar.notification.people.PersonViewModel
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView

class PeopleHubView(context: Context, attrs: AttributeSet) :
        StackScrollerDecorView(context, attrs), SwipeableView {

    private lateinit var contents: ViewGroup
    private lateinit var label: TextView

    lateinit var personViewAdapters: Sequence<DataListener<PersonViewModel?>>
        private set

    override fun onFinishInflate() {
        contents = requireViewById(R.id.people_list)
        label = requireViewById(R.id.header_label)
        personViewAdapters = (0 until contents.childCount)
                .asSequence() // so we can map
                .mapNotNull { idx ->
                    // get all our people slots
                    (contents.getChildAt(idx) as? ImageView)?.let(::PersonDataListenerImpl)
                }
                .toList() // cache it
                .asSequence() // but don't reveal it's a list
        super.onFinishInflate()
        setVisible(true /* nowVisible */, false /* animate */)
    }

    fun setTextColor(@ColorInt color: Int) = label.setTextColor(color)

    override fun findContentView(): View = contents
    override fun findSecondaryView(): View? = null

    override fun hasFinishedInitialization(): Boolean = true

    override fun createMenu(): NotificationMenuRowPlugin? = null

    override fun resetTranslation() {
        translationX = 0f
    }

    override fun setTranslation(translation: Float) {
        if (canSwipe) {
            super.setTranslation(translation)
        }
    }

    var canSwipe: Boolean = false
        set(value) {
            if (field != value) {
                if (field) {
                    resetTranslation()
                }
                field = value
            }
        }

    override fun needsClippingToShelf(): Boolean = true

    override fun applyContentTransformation(contentAlpha: Float, translationY: Float) {
        super.applyContentTransformation(contentAlpha, translationY)
        for (i in 0 until contents.childCount) {
            val view = contents.getChildAt(i)
            view.alpha = contentAlpha
            view.translationY = translationY
        }
    }

    fun setOnHeaderClickListener(listener: OnClickListener) = label.setOnClickListener(listener)

    private inner class PersonDataListenerImpl(val avatarView: ImageView) :
            DataListener<PersonViewModel?> {

        override fun onDataChanged(data: PersonViewModel?) {
            avatarView.visibility = data?.let { View.VISIBLE } ?: View.GONE
            avatarView.setImageDrawable(data?.icon)
            avatarView.setOnClickListener { data?.onClick?.invoke() }
        }
    }
}