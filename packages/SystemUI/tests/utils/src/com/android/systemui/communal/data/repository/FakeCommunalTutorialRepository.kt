package com.android.systemui.communal.data.repository

import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
import android.provider.Settings.Secure.HubModeTutorialState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fake implementation of [CommunalTutorialRepository] */
class FakeCommunalTutorialRepository() : CommunalTutorialRepository {
    private val _tutorialSettingState = MutableStateFlow(HUB_MODE_TUTORIAL_NOT_STARTED)
    override val tutorialSettingState: StateFlow<Int> = _tutorialSettingState
    override suspend fun setTutorialState(@HubModeTutorialState state: Int) {
        setTutorialSettingState(state)
    }

    fun setTutorialSettingState(@HubModeTutorialState state: Int) {
        _tutorialSettingState.value = state
    }
}
