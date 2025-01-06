/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.window

import android.os.Parcel
import android.os.Parcelable
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.view.Display.DEFAULT_DISPLAY
import android.window.ConfigurationChangeSetting.SETTING_TYPE_UNKNOWN
import android.window.ConfigurationChangeSetting.SETTING_TYPE_DISPLAY_DENSITY
import android.window.ConfigurationChangeSetting.SETTING_TYPE_FONT_SCALE
import android.window.ConfigurationChangeSetting.ConfigurationChangeSettingInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.server.LocalServices
import com.android.window.flags.Flags
import com.google.common.truth.Truth.assertThat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * Build/Install/Run:
 * atest FrameworksCoreTests:ConfigurationChangeSettingTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4::class)
class ConfigurationChangeSettingTest {
    @get:Rule
    val setFlagsRule: SetFlagsRule = SetFlagsRule()

    private val mMockConfigurationChangeSettingInternal = mock<ConfigurationChangeSettingInternal>()

    @BeforeTest
    fun setup() {
        tearDownLocalService()
        LocalServices.addService(
            ConfigurationChangeSettingInternal::class.java,
            mMockConfigurationChangeSettingInternal,
        )
    }

    @AfterTest
    fun tearDown() {
        tearDownLocalService()
    }

    @Test(expected = IllegalStateException::class)
    @DisableFlags(Flags.FLAG_CONDENSE_CONFIGURATION_CHANGE_FOR_SIMPLE_MODE)
    fun settingCreation_whenFlagDisabled_throwsException() {
        ConfigurationChangeSetting.DensitySetting(DEFAULT_DISPLAY, TEST_DENSITY)
    }

    @Test
    fun invalidSettingType_appClient_throwsException() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(SETTING_TYPE_UNKNOWN)
            parcel.setDataPosition(0)

            assertThrows(IllegalArgumentException::class.java) {
                DEFAULT_CREATOR.createFromParcel(parcel)
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_CONDENSE_CONFIGURATION_CHANGE_FOR_SIMPLE_MODE)
    fun densitySettingParcelable_appClient_recreatesSucceeds() {
        val setting = ConfigurationChangeSetting.DensitySetting(DEFAULT_DISPLAY, TEST_DENSITY)

        val recreated = setting.recreateFromParcel()

        verify(mMockConfigurationChangeSettingInternal, never()).createImplFromParcel(any(), any())
        assertThat(recreated).isEqualTo(setting)
    }

    @Test
    @EnableFlags(Flags.FLAG_CONDENSE_CONFIGURATION_CHANGE_FOR_SIMPLE_MODE)
    fun densitySettingParcelable_systemServer_createsImplFromInternal() {
        val setting = ConfigurationChangeSetting.DensitySetting(DEFAULT_DISPLAY, TEST_DENSITY)
        val mockDensitySetting = mock<ConfigurationChangeSetting.DensitySetting>()
        mMockConfigurationChangeSettingInternal.stub {
            on { createImplFromParcel(any(), any()) } doReturn mockDensitySetting
        }

        val recreated = setting.recreateFromParcel(TEST_SYSTEM_SERVER_CREATOR)

        verify(mMockConfigurationChangeSettingInternal).createImplFromParcel(
            eq(SETTING_TYPE_DISPLAY_DENSITY),
            any(),
        )
        assertThat(recreated).isEqualTo(mockDensitySetting)
    }

    @Test
    @EnableFlags(Flags.FLAG_CONDENSE_CONFIGURATION_CHANGE_FOR_SIMPLE_MODE)
    fun fontScaleSettingParcelable_appClient_recreatesSucceeds() {
        val setting = ConfigurationChangeSetting.FontScaleSetting(TEST_FONT_SCALE)

        val recreated = setting.recreateFromParcel()

        verify(mMockConfigurationChangeSettingInternal, never()).createImplFromParcel(any(), any())
        assertThat(recreated).isEqualTo(setting)
    }

    @Test
    @EnableFlags(Flags.FLAG_CONDENSE_CONFIGURATION_CHANGE_FOR_SIMPLE_MODE)
    fun fontScaleSettingParcelable_systemServer_createsImplFromInternal() {
        val setting = ConfigurationChangeSetting.FontScaleSetting(TEST_FONT_SCALE)
        val mockFontScaleSetting = mock<ConfigurationChangeSetting.FontScaleSetting>()
        mMockConfigurationChangeSettingInternal.stub {
            on { createImplFromParcel(any(), any()) } doReturn mockFontScaleSetting
        }

        val recreated = setting.recreateFromParcel(TEST_SYSTEM_SERVER_CREATOR)

        verify(mMockConfigurationChangeSettingInternal).createImplFromParcel(
            eq(SETTING_TYPE_FONT_SCALE),
            any(),
        )
        assertThat(recreated).isEqualTo(mockFontScaleSetting)
    }

    companion object {
        private const val TEST_DENSITY = 400
        private const val TEST_FONT_SCALE = 1.5f

        private val DEFAULT_CREATOR = ConfigurationChangeSetting.CREATOR
        private val TEST_SYSTEM_SERVER_CREATOR =
            ConfigurationChangeSetting.CreatorImpl(true /* isSystem */)

        private fun tearDownLocalService() =
            LocalServices.removeServiceForTest(ConfigurationChangeSettingInternal::class.java)

        private fun ConfigurationChangeSetting.recreateFromParcel(
            creator: Parcelable.Creator<ConfigurationChangeSetting> = DEFAULT_CREATOR,
        ): ConfigurationChangeSetting {
            val parcel = Parcel.obtain()
            try {
                writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return creator.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }
    }
}