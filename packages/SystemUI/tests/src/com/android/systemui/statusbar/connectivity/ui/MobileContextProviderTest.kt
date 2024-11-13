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

package com.android.systemui.statusbar.connectivity.ui

import android.telephony.SubscriptionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class MobileContextProviderTest : SysuiTestCase() {
    @Mock private lateinit var networkController: NetworkController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var demoModeController: DemoModeController

    private lateinit var provider: MobileContextProvider
    private lateinit var signalCallback: SignalCallback

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        provider =
            MobileContextProvider(
                networkController,
                dumpManager,
                demoModeController,
            )

        signalCallback = withArgCaptor { verify(networkController).addCallback(capture()) }
    }

    @Test
    fun test_oneSubscription_contextHasMccMnc() {
        // GIVEN there is one SubscriptionInfo
        signalCallback.setSubs(listOf(SUB_1))

        // WHEN we ask for a mobile context
        val ctx = provider.getMobileContextForSub(SUB_1_ID, context)

        // THEN the configuration of that context reflect this subscription's MCC/MNC override
        val config = ctx.resources.configuration
        assertThat(config.mcc).isEqualTo(SUB_1_MCC)
        assertThat(config.mnc).isEqualTo(SUB_1_MNC)
    }

    @Test
    fun test_twoSubscriptions_eachContextReflectsMccMnc() {
        // GIVEN there are two SubscriptionInfos
        signalCallback.setSubs(listOf(SUB_1, SUB_2))

        // WHEN we ask for a mobile context for each sub
        val ctx1 = provider.getMobileContextForSub(SUB_1_ID, context)
        val ctx2 = provider.getMobileContextForSub(SUB_2_ID, context)

        // THEN the configuration of each context reflect this subscription's MCC/MNC override
        val config1 = ctx1.resources.configuration
        assertThat(config1.mcc).isEqualTo(SUB_1_MCC)
        assertThat(config1.mnc).isEqualTo(SUB_1_MNC)

        val config2 = ctx2.resources.configuration
        assertThat(config2.mcc).isEqualTo(SUB_2_MCC)
        assertThat(config2.mnc).isEqualTo(SUB_2_MNC)
    }

    @Test
    fun test_requestingContextForNonexistentSubscription_returnsGivenContext() {
        // GIVEN no SubscriptionInfos
        signalCallback.setSubs(listOf())

        // WHEN we ask for a mobile context for an unknown subscription
        val ctx = provider.getMobileContextForSub(SUB_1_ID, context)

        // THEN we get the original context back
        assertThat(ctx).isEqualTo(context)
    }

    private val SUB_1_ID = 1
    private val SUB_1_MCC = 123
    private val SUB_1_MNC = 456
    private val SUB_1 =
        mock<SubscriptionInfo>().also {
            whenever(it.subscriptionId).thenReturn(SUB_1_ID)
            whenever(it.mcc).thenReturn(SUB_1_MCC)
            whenever(it.mnc).thenReturn(SUB_1_MNC)
        }

    private val SUB_2_ID = 2
    private val SUB_2_MCC = 666
    private val SUB_2_MNC = 777
    private val SUB_2 =
        mock<SubscriptionInfo>().also {
            whenever(it.subscriptionId).thenReturn(SUB_2_ID)
            whenever(it.mcc).thenReturn(SUB_2_MCC)
            whenever(it.mnc).thenReturn(SUB_2_MNC)
        }
}
