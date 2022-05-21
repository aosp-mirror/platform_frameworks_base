package com.android.systemui.statusbar.policy

import android.content.res.Configuration

/** Fake implementation of [ConfigurationController] for tests. */
class FakeConfigurationController : ConfigurationController {

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
