package com.android.systemui.deviceentry.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [DeviceEntryRepository] */
@SysUISingleton
class FakeDeviceEntryRepository @Inject constructor() : DeviceEntryRepository {

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

@Module
interface FakeDeviceEntryRepositoryModule {
    @Binds fun bindFake(fake: FakeDeviceEntryRepository): DeviceEntryRepository
}
