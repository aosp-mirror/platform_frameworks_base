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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.FloatProperty
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin
import com.android.systemui.statusbar.notification.people.DataListener
import com.android.systemui.statusbar.notification.people.PersonViewModel
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView

private val TRANSLATE_CONTENT = object : FloatProperty<PeopleHubView>("translate") {
    override fun setValue(view: PeopleHubView, value: Float) {
        view.translation = value
    }

    override fun get(view: PeopleHubView) = view.translation
}

class PeopleHubView(context: Context, attrs: AttributeSet) :
        ActivatableNotificationView(context, attrs), SwipeableView {

    private lateinit var contents: ViewGroup
    private lateinit var personControllers: List<PersonDataListenerImpl>
    private var translateAnim: ObjectAnimator? = null

    val personViewAdapters: Sequence<DataListener<PersonViewModel?>>
        get() = personControllers.asSequence()

    override fun onFinishInflate() {
        super.onFinishInflate()
        contents = requireViewById(R.id.people_list)
        personControllers = (0 until contents.childCount)
                .asSequence()
                .mapNotNull { idx ->
                    (contents.getChildAt(idx) as? LinearLayout)?.let(::PersonDataListenerImpl)
                }
                .toList()
    }

    override fun getContentView(): View = contents

    override fun hasFinishedInitialization(): Boolean = true

    override fun createMenu(): NotificationMenuRowPlugin? = null

    override fun getTranslateViewAnimator(
        leftTarget: Float,
        listener: ValueAnimator.AnimatorUpdateListener?
    ): Animator =
            ObjectAnimator
                    .ofFloat(this, TRANSLATE_CONTENT, leftTarget)
                    .apply {
                        listener?.let { addUpdateListener(listener) }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(anim: Animator) {
                                translateAnim = null
                            }
                        })
                    }
                    .also {
                        translateAnim?.cancel()
                        translateAnim = it
                    }

    override fun resetTranslation() {
        translateAnim?.cancel()
        translationX = 0f
    }

    private inner class PersonDataListenerImpl(val viewGroup: ViewGroup) :
            DataListener<PersonViewModel?> {

        val nameView = viewGroup.requireViewById<TextView>(R.id.person_name)
        val avatarView = viewGroup.requireViewById<ImageView>(R.id.person_icon)

        override fun onDataChanged(data: PersonViewModel?) {
            viewGroup.visibility = data?.let { View.VISIBLE } ?: View.INVISIBLE
            nameView.text = data?.name
            avatarView.setImageDrawable(data?.icon)
            viewGroup.setOnClickListener { data?.onClick?.invoke() }
        }
    }
}