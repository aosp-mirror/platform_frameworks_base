package com.android.systemui.statusbar.policy

import android.content.res.Configuration
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject

/** Fake implementation of [ConfigurationController] for tests. */
@SysUISingleton
class FakeConfigurationController @Inject constructor() : ConfigurationController {

    private var listeners = mutableListOf<ConfigurationController.ConfigurationListener>()
    private var isRtl = false

    override fun addCallback(listener: ConfigurationController.ConfigurationListener) {
        listeners += listener
    }

    override fun removeCallback(listener: ConfigurationController.ConfigurationListener) {
        listeners -= listener
    }

    override fun onConfigurationChanged(newConfiguration: Configuration?) {
        listeners.forEach { it.onConfigChanged(newConfiguration) }
    }

    override fun notifyThemeChanged() {
        listeners.forEach { it.onThemeChanged() }
    }

    fun notifyDensityOrFontScaleChanged() {
        listeners.forEach { it.onDensityOrFontScaleChanged() }
    }

    fun notifyConfigurationChanged() {
        onConfigurationChanged(newConfiguration = null)
    }

    fun notifyLayoutDirectionChanged(isRtl: Boolean) {
        this.isRtl = isRtl
        listeners.forEach { it.onLayoutDirectionChanged(isRtl) }
    }

    override fun isLayoutRtl(): Boolean = isRtl
}

@Module
interface FakeConfigurationControllerModule {
    @Binds fun bindFake(fake: FakeConfigurationController): ConfigurationController
}
