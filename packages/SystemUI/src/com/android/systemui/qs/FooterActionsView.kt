/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.qs

import android.app.StatusBarManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.UserManager
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.android.settingslib.Utils
import com.android.settingslib.drawable.UserIconDrawable
import com.android.systemui.R
import com.android.systemui.statusbar.phone.MultiUserSwitch
import com.android.systemui.statusbar.phone.SettingsButton

/**
 * Quick Settings bottom buttons placed in footer (aka utility bar) - always visible in expanded QS,
 * in split shade mode visible also in collapsed state. May contain up to 5 buttons: settings,
 * edit tiles, power off and conditionally: user switch and tuner
 */
class FooterActionsView(context: Context?, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private lateinit var settingsContainer: View
    private lateinit var settingsButton: SettingsButton
    private lateinit var multiUserSwitch: MultiUserSwitch
    private lateinit var multiUserAvatar: ImageView
    private lateinit var tunerIcon: View

    private var qsDisabled = false
    private var expansionAmount = 0f

    override fun onFinishInflate() {
        super.onFinishInflate()
        settingsButton = findViewById(R.id.settings_button)
        settingsContainer = findViewById(R.id.settings_button_container)
        multiUserSwitch = findViewById(R.id.multi_user_switch)
        multiUserAvatar = multiUserSwitch.findViewById(R.id.multi_user_avatar)
        tunerIcon = requireViewById(R.id.tuner_icon)

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        if (settingsButton.background is RippleDrawable) {
            (settingsButton.background as RippleDrawable).setForceSoftware(true)
        }
        updateResources()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        updateResources()
    }

    private fun updateResources() {
        val tunerIconTranslation = mContext.resources
                .getDimensionPixelOffset(R.dimen.qs_footer_tuner_icon_translation).toFloat()
        tunerIcon.translationX = if (isLayoutRtl) (-tunerIconTranslation) else tunerIconTranslation
    }

    fun disable(
        state2: Int,
        isTunerEnabled: Boolean,
        multiUserEnabled: Boolean
    ) {
        val disabled = state2 and StatusBarManager.DISABLE2_QUICK_SETTINGS != 0
        if (disabled == qsDisabled) return
        qsDisabled = disabled
        updateEverything(isTunerEnabled, multiUserEnabled)
    }

    fun updateEverything(
        isTunerEnabled: Boolean,
        multiUserEnabled: Boolean
    ) {
        post {
            updateVisibilities(isTunerEnabled, multiUserEnabled)
            updateClickabilities()
            isClickable = false
        }
    }

    private fun updateClickabilities() {
        multiUserSwitch.isClickable = multiUserSwitch.visibility == VISIBLE
        settingsButton.isClickable = settingsButton.visibility == VISIBLE
    }

    private fun updateVisibilities(
        isTunerEnabled: Boolean,
        multiUserEnabled: Boolean
    ) {
        settingsContainer.visibility = if (qsDisabled) GONE else VISIBLE
        tunerIcon.visibility = if (isTunerEnabled) VISIBLE else INVISIBLE
        multiUserSwitch.visibility = if (multiUserEnabled) VISIBLE else GONE
        val isDemo = UserManager.isDeviceInDemoMode(context)
        settingsButton.visibility = if (isDemo) INVISIBLE else VISIBLE
    }

    fun onUserInfoChanged(picture: Drawable?, isGuestUser: Boolean) {
        var pictureToSet = picture
        if (picture != null && isGuestUser && picture !is UserIconDrawable) {
            pictureToSet = picture.constantState.newDrawable(resources).mutate()
            pictureToSet.setColorFilter(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorForeground),
                    PorterDuff.Mode.SRC_IN)
        }
        multiUserAvatar.setImageDrawable(pictureToSet)
    }
}