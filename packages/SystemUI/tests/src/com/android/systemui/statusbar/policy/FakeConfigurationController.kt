package com.android.systemui.statusbar.policy

import android.content.res.Configuration

/** Fake implementation of [ConfigurationController] for tests. */
class FakeConfigurationController : ConfigurationController {

    private var listener: ConfigurationController.ConfigurationListener? = null

    override fun addCallback(listener: ConfigurationController.ConfigurationListener) {
        this.listener = listener
    }

    override fun removeCallback(listener: ConfigurationController.ConfigurationListener) {
        this.listener = null
    }

    override fun onConfigurationChanged(newConfiguration: Configuration?) {
        listener?.onConfigChanged(newConfiguration)
    }

    override fun notifyThemeChanged() {
        listener?.onThemeChanged()
    }

    fun notifyConfigurationChanged() {
        onConfigurationChanged(newConfiguration = null)
    }

    override fun isLayoutRtl(): Boolean = false
}
