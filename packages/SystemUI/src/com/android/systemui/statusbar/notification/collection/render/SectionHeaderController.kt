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

package com.android.systemui.statusbar.notification.collection.render

import android.annotation.StringRes
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.systemui.res.R
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.notification.dagger.HeaderClickAction
import com.android.systemui.statusbar.notification.dagger.HeaderText
import com.android.systemui.statusbar.notification.dagger.NodeLabel
import com.android.systemui.statusbar.notification.dagger.SectionHeaderScope
import com.android.systemui.statusbar.notification.stack.SectionHeaderView
import javax.inject.Inject

interface SectionHeaderController {
    fun reinflateView(parent: ViewGroup)
    val headerView: SectionHeaderView?
    fun setClearSectionEnabled(enabled: Boolean)
    fun setOnClearSectionClickListener(listener: View.OnClickListener)
}

@SectionHeaderScope
class SectionHeaderNodeControllerImpl @Inject constructor(
    @NodeLabel override val nodeLabel: String,
    private val layoutInflater: LayoutInflater,
    @HeaderText @StringRes private val headerTextResId: Int,
    private val activityStarter: ActivityStarter,
    @HeaderClickAction private val clickIntentAction: String
) : NodeController, SectionHeaderController {

    private var _view: SectionHeaderView? = null
    private var clearAllButtonEnabled = false
    private var clearAllClickListener: View.OnClickListener? = null
    private val onHeaderClickListener = View.OnClickListener {
        activityStarter.startActivity(
                Intent(clickIntentAction),
                true /* onlyProvisioned */,
                true /* dismissShade */,
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun reinflateView(parent: ViewGroup) {
        var oldPos = -1
        _view?.let { _view ->
            _view.removeFromTransientContainer()
            if (_view.parent === parent) {
                oldPos = parent.indexOfChild(_view)
                parent.removeView(_view)
            }
        }
        val inflated = layoutInflater.inflate(
                R.layout.status_bar_notification_section_header,
                parent,
                false /* attachToRoot */)
                as SectionHeaderView
        inflated.setHeaderText(headerTextResId)
        inflated.setOnHeaderClickListener(onHeaderClickListener)
        clearAllClickListener?.let { inflated.setOnClearAllClickListener(it) }
        if (oldPos != -1) {
            parent.addView(inflated, oldPos)
        }
        _view = inflated
        _view?.setClearSectionButtonEnabled(clearAllButtonEnabled)
    }

    override val headerView: SectionHeaderView?
        get() = _view

    override fun setClearSectionEnabled(enabled: Boolean) {
        clearAllButtonEnabled = enabled
        _view?.setClearSectionButtonEnabled(enabled)
    }

    override fun setOnClearSectionClickListener(listener: View.OnClickListener) {
        clearAllClickListener = listener
        _view?.setOnClearAllClickListener(listener)
    }

    override fun onViewAdded() {
        headerView?.setContentVisibleAnimated(true)
    }

    override val view: View
        get() = _view!!
    override fun offerToKeepInParentForAnimation(): Boolean = false
    override fun removeFromParentIfKeptForAnimation(): Boolean = false
    override fun resetKeepInParentForAnimation() {}
}
