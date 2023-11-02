package com.android.systemui.smartspace.data.repository

import android.app.smartspace.SmartspaceTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSmartspaceRepository(
    smartspaceRemoteViewsEnabled: Boolean = true,
) : SmartspaceRepository {

    override val isSmartspaceRemoteViewsEnabled = smartspaceRemoteViewsEnabled

    private val _lockscreenSmartspaceTargets: MutableStateFlow<List<SmartspaceTarget>> =
        MutableStateFlow(emptyList())
    override val lockscreenSmartspaceTargets: Flow<List<SmartspaceTarget>> =
        _lockscreenSmartspaceTargets

    fun setLockscreenSmartspaceTargets(targets: List<SmartspaceTarget>) {
        _lockscreenSmartspaceTargets.value = targets
    }
}
