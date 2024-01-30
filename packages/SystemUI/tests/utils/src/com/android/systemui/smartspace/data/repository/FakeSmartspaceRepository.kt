package com.android.systemui.smartspace.data.repository

import android.app.smartspace.SmartspaceTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSmartspaceRepository(
    smartspaceRemoteViewsEnabled: Boolean = true,
) : SmartspaceRepository {

    override val isSmartspaceRemoteViewsEnabled = smartspaceRemoteViewsEnabled

    private val _communalSmartspaceTargets: MutableStateFlow<List<SmartspaceTarget>> =
        MutableStateFlow(emptyList())
    override val communalSmartspaceTargets: Flow<List<SmartspaceTarget>> =
        _communalSmartspaceTargets

    fun setCommunalSmartspaceTargets(targets: List<SmartspaceTarget>) {
        _communalSmartspaceTargets.value = targets
    }
}
