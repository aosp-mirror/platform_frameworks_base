package com.android.systemui.bouncer.data.repository

import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants
import com.android.systemui.bouncer.shared.model.BouncerShowMessageModel
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
    private val _panelExpansionAmount = MutableStateFlow(KeyguardBouncerConstants.EXPANSION_HIDDEN)
    override val panelExpansionAmount = _panelExpansionAmount.asStateFlow()
    private val _keyguardPosition = MutableStateFlow<Float?>(null)
    override val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    override val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()
    private val _keyguardAuthenticated = MutableStateFlow<Boolean?>(null)
    override val keyguardAuthenticated = _keyguardAuthenticated.asStateFlow()
    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    override val showMessage = _showMessage.asStateFlow()
    private val _resourceUpdateRequests = MutableStateFlow(false)
    override val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()
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
