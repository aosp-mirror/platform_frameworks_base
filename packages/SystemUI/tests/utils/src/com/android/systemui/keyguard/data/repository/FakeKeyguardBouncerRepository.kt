/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.repository

import com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [KeyguardRepository] */
class FakeKeyguardBouncerRepository : KeyguardBouncerRepository {
    private val _primaryBouncerShow = MutableStateFlow(false)
    override val primaryBouncerShow = _primaryBouncerShow.asStateFlow()
    private val _primaryBouncerShowingSoon = MutableStateFlow(false)
    override val primaryBouncerShowingSoon = _primaryBouncerShowingSoon.asStateFlow()
    private val _primaryBouncerStartingToHide = MutableStateFlow(false)
    override val primaryBouncerStartingToHide = _primaryBouncerStartingToHide.asStateFlow()
    private val _primaryBouncerDisappearAnimation = MutableStateFlow<Runnable?>(null)
    override val primaryBouncerStartingDisappearAnimation =
        _primaryBouncerDisappearAnimation.asStateFlow()
    private val _primaryBouncerScrimmed = MutableStateFlow(false)
    override val primaryBouncerScrimmed = _primaryBouncerScrimmed.asStateFlow()
    private val _panelExpansionAmount = MutableStateFlow(EXPANSION_HIDDEN)
    override val panelExpansionAmount = _panelExpansionAmount.asStateFlow()
    private val _keyguardPosition = MutableStateFlow(0f)
    override val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    override val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()
    private val _keyguardAuthenticated = MutableStateFlow<Boolean?>(null)
    override val keyguardAuthenticated = _keyguardAuthenticated.asStateFlow()
    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    override val showMessage = _showMessage.asStateFlow()
    private val _resourceUpdateRequests = MutableStateFlow(false)
    override val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()
    override val bouncerPromptReason = 0
    override val bouncerErrorMessage: CharSequence? = null
    private val _isAlternateBouncerVisible = MutableStateFlow(false)
    override val alternateBouncerVisible = _isAlternateBouncerVisible.asStateFlow()
    override var lastAlternateBouncerVisibleTime: Long = 0L
    private val _isAlternateBouncerUIAvailable = MutableStateFlow<Boolean>(false)
    override val alternateBouncerUIAvailable = _isAlternateBouncerUIAvailable.asStateFlow()
    private val _sideFpsShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val sideFpsShowing: StateFlow<Boolean> = _sideFpsShowing.asStateFlow()

    override fun setPrimaryScrimmed(isScrimmed: Boolean) {
        _primaryBouncerScrimmed.value = isScrimmed
    }

    override fun setAlternateVisible(isVisible: Boolean) {
        _isAlternateBouncerVisible.value = isVisible
    }

    override fun setAlternateBouncerUIAvailable(isAvailable: Boolean) {
        _isAlternateBouncerUIAvailable.value = isAvailable
    }

    override fun setPrimaryShow(isShowing: Boolean) {
        _primaryBouncerShow.value = isShowing
    }

    override fun setPrimaryShowingSoon(showingSoon: Boolean) {
        _primaryBouncerShowingSoon.value = showingSoon
    }

    override fun setPrimaryStartingToHide(startingToHide: Boolean) {
        _primaryBouncerStartingToHide.value = startingToHide
    }

    override fun setPrimaryStartDisappearAnimation(runnable: Runnable?) {
        _primaryBouncerDisappearAnimation.value = runnable
    }

    override fun setPanelExpansion(panelExpansion: Float) {
        _panelExpansionAmount.value = panelExpansion
    }

    override fun setKeyguardPosition(keyguardPosition: Float) {
        _keyguardPosition.value = keyguardPosition
    }

    override fun setResourceUpdateRequests(willUpdateResources: Boolean) {
        _resourceUpdateRequests.value = willUpdateResources
    }

    override fun setShowMessage(bouncerShowMessageModel: BouncerShowMessageModel?) {
        _showMessage.value = bouncerShowMessageModel
    }

    override fun setKeyguardAuthenticated(keyguardAuthenticated: Boolean?) {
        _keyguardAuthenticated.value = keyguardAuthenticated
    }

    override fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean) {
        _isBackButtonEnabled.value = isBackButtonEnabled
    }

    override fun setSideFpsShowing(isShowing: Boolean) {
        _sideFpsShowing.value = isShowing
    }
}
