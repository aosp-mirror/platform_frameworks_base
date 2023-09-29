package com.android.systemui.deviceentry.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [DeviceEntryRepository] */
class FakeDeviceEntryRepository : DeviceEntryRepository {

    private var isInsecureLockscreenEnabled = true
    private var isBypassEnabled = false

    private val _isUnlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    override fun isBypassEnabled(): Boolean {
        return isBypassEnabled
    }

    override suspend fun isInsecureLockscreenEnabled(): Boolean {
        return isInsecureLockscreenEnabled
    }

    fun setUnlocked(isUnlocked: Boolean) {
        _isUnlocked.value = isUnlocked
    }

    fun setInsecureLockscreenEnabled(isLockscreenEnabled: Boolean) {
        this.isInsecureLockscreenEnabled = isLockscreenEnabled
    }

    fun setBypassEnabled(isBypassEnabled: Boolean) {
        this.isBypassEnabled = isBypassEnabled
    }
}
