/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.decor

import android.content.res.Resources
import android.testing.AndroidTestingRunner
import android.view.DisplayCutout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@SmallTest
class PrivacyDotDecorProviderFactoryTest : SysuiTestCase() {
    private lateinit var mPrivacyDotDecorProviderFactory: PrivacyDotDecorProviderFactory

    @Mock private lateinit var resources: Resources

    @Before
    fun setUp() {
        resources = spy(mContext.resources)
        mPrivacyDotDecorProviderFactory = PrivacyDotDecorProviderFactory(resources)
    }

    private fun setPrivacyDotResources(isEnable: Boolean) {
        whenever(resources.getBoolean(R.bool.config_enablePrivacyDot)).thenReturn(isEnable)
    }

    @Test
    fun testGetNoCornerDecorProviderWithNoPrivacyDot() {
        setPrivacyDotResources(false)

        Assert.assertEquals(false, mPrivacyDotDecorProviderFactory.hasProviders)
        Assert.assertEquals(0, mPrivacyDotDecorProviderFactory.providers.size)
    }

    @Test
    fun testGet4CornerDecorProvidersWithPrivacyDot() {
        setPrivacyDotResources(true)
        val providers = mPrivacyDotDecorProviderFactory.providers

        Assert.assertEquals(true, mPrivacyDotDecorProviderFactory.hasProviders)
        Assert.assertEquals(4, providers.size)
        Assert.assertEquals(1, providers.count {
            ((it.viewId == R.id.privacy_dot_top_left_container)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT))
        })
        Assert.assertEquals(1, providers.count {
            ((it.viewId == R.id.privacy_dot_top_right_container)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_RIGHT))
        })
        Assert.assertEquals(1, providers.count {
            ((it.viewId == R.id.privacy_dot_bottom_left_container)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_BOTTOM)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT))
        })
        Assert.assertEquals(1, providers.count {
            ((it.viewId == R.id.privacy_dot_bottom_right_container)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_BOTTOM)
                    and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_RIGHT))
        })
    }
}