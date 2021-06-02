/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.systemui.globalactions

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.testing.AndroidTestingRunner
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.globalactions.GlobalActionsInfoProvider
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyObject
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

private const val PREFERENCE = "global_actions_info_prefs"
private const val KEY_VIEW_COUNT = "view_count"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

@SmallTest
@RunWith(AndroidTestingRunner::class)
class GlobalActionsInfoProviderTest : SysuiTestCase() {

    @Mock private lateinit var walletClient: QuickAccessWalletClient
    @Mock private lateinit var controlsController: ControlsController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockResources: Resources
    @Mock private lateinit var sharedPrefs: SharedPreferences
    @Mock private lateinit var sharedPrefsEditor: SharedPreferences.Editor

    private lateinit var infoProvider: GlobalActionsInfoProvider

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mockContext = spy(context)
        mockResources = spy(context.resources)
        whenever(mockContext.resources).thenReturn(mockResources)
        whenever(mockResources.getBoolean(R.bool.global_actions_show_change_info))
                .thenReturn(true)
        whenever(mockContext.getSharedPreferences(eq(PREFERENCE), anyInt()))
                .thenReturn(sharedPrefs)
        whenever(sharedPrefs.edit()).thenReturn(sharedPrefsEditor)
        whenever(sharedPrefsEditor.putInt(anyString(), anyInt())).thenReturn(sharedPrefsEditor)
        whenever(sharedPrefsEditor.putBoolean(anyString(), anyBoolean()))
                .thenReturn(sharedPrefsEditor)

        infoProvider = GlobalActionsInfoProvider(
                mockContext,
                walletClient,
                controlsController,
                activityStarter
        )
    }

    @Test
    fun testIsEligible_noCards() {
        whenever(sharedPrefs.contains(eq(KEY_VIEW_COUNT))).thenReturn(false)
        whenever(walletClient.isWalletFeatureAvailable).thenReturn(false)

        assertFalse(infoProvider.shouldShowMessage())
    }

    @Test
    fun testIsEligible_hasCards() {
        whenever(sharedPrefs.contains(eq(KEY_VIEW_COUNT))).thenReturn(false)
        whenever(walletClient.isWalletFeatureAvailable).thenReturn(true)

        assertTrue(infoProvider.shouldShowMessage())
    }

    @Test
    fun testNotEligible_shouldNotShow() {
        whenever(mockResources.getBoolean(R.bool.global_actions_show_change_info))
                .thenReturn(false)

        assertFalse(infoProvider.shouldShowMessage())
    }

    @Test
    fun testTooManyButtons_doesNotAdd() {
        val configuration = Configuration()
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        whenever(mockResources.configuration).thenReturn(configuration)

        val parent = mock(ViewGroup::class.java)
        infoProvider.addPanel(mockContext, parent, 5, { })

        verify(parent, never()).addView(anyObject(), anyInt())
    }

    @Test
    fun testLimitTimesShown() {
        whenever(sharedPrefs.getInt(eq(KEY_VIEW_COUNT), anyInt())).thenReturn(4)

        assertFalse(infoProvider.shouldShowMessage())
    }
}