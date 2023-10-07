package com.android.systemui.statusbar.phone

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.HeadsUpManager
import dagger.Binds
import dagger.Module

@Module
interface HeadsUpModule {
    @Binds @SysUISingleton fun bindsHeadsUpManager(hum: HeadsUpManagerPhone): HeadsUpManager
}
