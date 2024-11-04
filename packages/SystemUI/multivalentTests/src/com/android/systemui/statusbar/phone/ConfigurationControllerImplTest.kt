/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone

import android.content.res.Configuration
import android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_LTR
import android.content.res.Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Configuration.UI_MODE_TYPE_CAR
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@SmallTest
class ConfigurationControllerImplTest : SysuiTestCase() {

    private lateinit var mConfigurationController: ConfigurationControllerImpl

    @Before
    fun setUp() {
        mConfigurationController = ConfigurationControllerImpl(mContext)
    }

    @Test
    fun testThemeChange() {
        val listener = mock(ConfigurationListener::class.java)
        mConfigurationController.addCallback(listener)

        mConfigurationController.notifyThemeChanged()
        verify(listener).onThemeChanged()
    }

    @Test
    fun testRemoveListenerDuringCallback() {
        val listener = mock(ConfigurationListener::class.java)
        mConfigurationController.addCallback(listener)
        val listener2 = mock(ConfigurationListener::class.java)
        mConfigurationController.addCallback(listener2)

        doAnswer {
            mConfigurationController.removeCallback(listener2)
            null
        }.`when`(listener).onThemeChanged()

        mConfigurationController.notifyThemeChanged()
        verify(listener).onThemeChanged()
        verify(listener2, never()).onThemeChanged()
    }

    @Test
    fun configChanged_listenerNotified() {
        val config = mContext.resources.configuration
        config.densityDpi = 12
        config.smallestScreenWidthDp = 240
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the config is updated
        config.densityDpi = 20
        config.smallestScreenWidthDp = 300
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.changedConfig?.densityDpi).isEqualTo(20)
        assertThat(listener.changedConfig?.smallestScreenWidthDp).isEqualTo(300)
    }

    @Test
    fun densityChanged_listenerNotified() {
        val config = mContext.resources.configuration
        config.densityDpi = 12
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the density is updated
        config.densityDpi = 20
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.densityOrFontScaleChanged).isTrue()
    }

    @Test
    fun fontChanged_listenerNotified() {
        val config = mContext.resources.configuration
        config.fontScale = 1.5f
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the font is updated
        config.fontScale = 1.4f
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.densityOrFontScaleChanged).isTrue()
    }

    @Test
    fun isCarAndUiModeChanged_densityListenerNotified() {
        val config = mContext.resources.configuration
        config.uiMode = UI_MODE_TYPE_CAR or UI_MODE_NIGHT_YES
        // Re-create the controller since we calculate car mode on creation
        mConfigurationController = ConfigurationControllerImpl(mContext)

        val listener = createAndAddListener()

        // WHEN the ui mode is updated
        config.uiMode = UI_MODE_TYPE_CAR or UI_MODE_NIGHT_NO
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.densityOrFontScaleChanged).isTrue()
    }

    @Test
    fun isNotCarAndUiModeChanged_densityListenerNotNotified() {
        val config = mContext.resources.configuration
        config.uiMode = UI_MODE_NIGHT_YES
        // Re-create the controller since we calculate car mode on creation
        mConfigurationController = ConfigurationControllerImpl(mContext)

        val listener = createAndAddListener()

        // WHEN the ui mode is updated
        config.uiMode = UI_MODE_NIGHT_NO
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is not notified because it's not car mode
        assertThat(listener.densityOrFontScaleChanged).isFalse()
    }

    @Test
    fun smallestScreenWidthChanged_listenerNotified() {
        val config = mContext.resources.configuration
        config.smallestScreenWidthDp = 240
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the width is updated
        config.smallestScreenWidthDp = 300
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.smallestScreenWidthChanged).isTrue()
    }

    @Test
    fun maxBoundsChange_newConfigObject_listenerNotified() {
        val config = mContext.resources.configuration
        config.windowConfiguration.setMaxBounds(0, 0, 200, 200)
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN a new configuration object with new bounds is sent
        val newConfig = Configuration()
        newConfig.windowConfiguration.setMaxBounds(0, 0, 100, 100)
        mConfigurationController.onConfigurationChanged(newConfig)

        // THEN the listener is notified
        assertThat(listener.maxBoundsChanged).isTrue()
    }

    // Regression test for b/245799099
    @Test
    fun maxBoundsChange_sameObject_listenerNotified() {
        val config = mContext.resources.configuration
        config.windowConfiguration.setMaxBounds(0, 0, 200, 200)
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the existing config is updated with new bounds
        config.windowConfiguration.setMaxBounds(0, 0, 100, 100)
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.maxBoundsChanged).isTrue()
    }


    @Test
    fun localeListChanged_listenerNotified() {
        val config = mContext.resources.configuration
        config.setLocales(LocaleList(Locale.CANADA, Locale.GERMANY))
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the locales are updated
        config.setLocales(LocaleList(Locale.FRANCE, Locale.JAPAN, Locale.CHINESE))
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.localeListChanged).isTrue()
    }

    @Test
    fun uiModeChanged_listenerNotified() {
        val config = mContext.resources.configuration
        config.uiMode = UI_MODE_NIGHT_YES
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the ui mode is updated
        config.uiMode = UI_MODE_NIGHT_NO
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.uiModeChanged).isTrue()
    }

    @Test
    fun layoutDirectionUpdated_listenerNotified() {
        val config = mContext.resources.configuration
        config.screenLayout = SCREENLAYOUT_LAYOUTDIR_LTR
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the layout is updated
        config.screenLayout = SCREENLAYOUT_LAYOUTDIR_RTL
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.layoutDirectionChanged).isTrue()
    }

    @Test
    fun assetPathsUpdated_listenerNotified() {
        val config = mContext.resources.configuration
        config.assetsSeq = 45
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the assets sequence is updated
        config.assetsSeq = 46
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.themeChanged).isTrue()
    }

    @Test
    fun orientationUpdated_listenerNotified() {
        val config = mContext.resources.configuration
        config.orientation = Configuration.ORIENTATION_LANDSCAPE
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN the orientation is updated
        config.orientation = Configuration.ORIENTATION_PORTRAIT
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified
        assertThat(listener.orientationChanged).isTrue()
    }


    @Test
    fun multipleUpdates_listenerNotifiedOfAll() {
        val config = mContext.resources.configuration
        config.densityDpi = 14
        config.windowConfiguration.setMaxBounds(0, 0, 2, 2)
        config.uiMode = UI_MODE_NIGHT_YES
        mConfigurationController.onConfigurationChanged(config)

        val listener = createAndAddListener()

        // WHEN multiple fields are updated
        config.densityDpi = 20
        config.windowConfiguration.setMaxBounds(0, 0, 3, 3)
        config.uiMode = UI_MODE_NIGHT_NO
        mConfigurationController.onConfigurationChanged(config)

        // THEN the listener is notified of all of them
        assertThat(listener.densityOrFontScaleChanged).isTrue()
        assertThat(listener.maxBoundsChanged).isTrue()
        assertThat(listener.uiModeChanged).isTrue()
    }

    @Test
    @Ignore("b/261408895")
    fun equivalentConfigObject_listenerNotNotified() {
        val config = mContext.resources.configuration
        val listener = createAndAddListener()

        // WHEN we update with the new object that has all the same fields
        mConfigurationController.onConfigurationChanged(Configuration(config))

        listener.assertNoMethodsCalled()
    }

    private fun createAndAddListener(): TestListener {
        val listener = TestListener()
        mConfigurationController.addCallback(listener)
        // Adding a listener can trigger some callbacks, so we want to reset the values right
        // after the listener is added
        listener.reset()
        return listener
    }

    private class TestListener : ConfigurationListener {
        var changedConfig: Configuration? = null
        var densityOrFontScaleChanged = false
        var smallestScreenWidthChanged = false
        var maxBoundsChanged = false
        var uiModeChanged = false
        var themeChanged = false
        var localeListChanged = false
        var layoutDirectionChanged = false
        var orientationChanged = false

        override fun onConfigChanged(newConfig: Configuration?) {
            changedConfig = newConfig
        }
        override fun onDensityOrFontScaleChanged() {
            densityOrFontScaleChanged = true
        }
        override fun onSmallestScreenWidthChanged() {
            smallestScreenWidthChanged = true
        }
        override fun onMaxBoundsChanged() {
            maxBoundsChanged = true
        }
        override fun onUiModeChanged() {
            uiModeChanged = true
        }
        override fun onThemeChanged() {
            themeChanged = true
        }
        override fun onLocaleListChanged() {
            localeListChanged = true
        }
        override fun onLayoutDirectionChanged(isLayoutRtl: Boolean) {
            layoutDirectionChanged = true
        }
        override fun onOrientationChanged(orientation: Int) {
            orientationChanged = true
        }

        fun assertNoMethodsCalled() {
            assertThat(densityOrFontScaleChanged).isFalse()
            assertThat(smallestScreenWidthChanged).isFalse()
            assertThat(maxBoundsChanged).isFalse()
            assertThat(uiModeChanged).isFalse()
            assertThat(themeChanged).isFalse()
            assertThat(localeListChanged).isFalse()
            assertThat(layoutDirectionChanged).isFalse()
        }

        fun reset() {
            changedConfig = null
            densityOrFontScaleChanged = false
            smallestScreenWidthChanged = false
            maxBoundsChanged = false
            uiModeChanged = false
            themeChanged = false
            localeListChanged = false
            layoutDirectionChanged = false
        }
    }
}
