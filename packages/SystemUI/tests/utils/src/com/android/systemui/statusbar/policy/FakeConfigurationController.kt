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

    override fun isLayoutRtl(): Boolean = false
}

@Module
interface FakeConfigurationControllerModule {
    @Binds fun bindFake(fake: FakeConfigurationController): ConfigurationController
}
