/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net

import android.os.Build
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.assertParcelSane
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class NetworkAgentConfigTest {
    @Rule @JvmField
    val ignoreRule = DevSdkIgnoreRule()

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    fun testParcelNetworkAgentConfig() {
        val config = NetworkAgentConfig.Builder().apply {
            setExplicitlySelected(true)
            setLegacyType(ConnectivityManager.TYPE_ETHERNET)
            setSubscriberId("MySubId")
            setPartialConnectivityAcceptable(false)
            setUnvalidatedConnectivityAcceptable(true)
        }.build()
        assertParcelSane(config, 9)
    }
}
