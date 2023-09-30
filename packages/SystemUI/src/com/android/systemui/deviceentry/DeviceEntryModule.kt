package com.android.systemui.deviceentry

import com.android.systemui.deviceentry.data.repository.DeviceEntryRepositoryModule
import dagger.Module

@Module(
    includes =
        [
            DeviceEntryRepositoryModule::class,
        ],
)
object DeviceEntryModule
