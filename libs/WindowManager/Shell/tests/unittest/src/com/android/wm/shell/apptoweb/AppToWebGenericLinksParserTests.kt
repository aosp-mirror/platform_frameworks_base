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

package com.android.wm.shell.apptoweb

import android.provider.DeviceConfig
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableResources
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.apptoweb.AppToWebGenericLinksParser.Companion.FLAG_GENERIC_LINKS
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.quality.Strictness

/**
 * Tests for [AppToWebGenericLinksParser].
 *
 * Build/Install/Run: atest WMShellUnitTests:AppToWebGenericLinksParserTests
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class AppToWebGenericLinksParserTests : ShellTestCase() {
    @Mock private lateinit var mockExecutor: ShellExecutor

    private lateinit var genericLinksParser: AppToWebGenericLinksParser
    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var resources: TestableResources

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        mockitoSession =
            mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(DesktopModeStatus::class.java)
                .startMocking()
        resources = mContext.getOrCreateTestableResources()
        resources.addOverride(R.string.generic_links_list, BUILD_TIME_LIST)
        DeviceConfig.setProperty(
            NAMESPACE,
            FLAG_GENERIC_LINKS,
            SERVER_SIDE_LIST,
            false /* makeDefault */
        )
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun init_usingBuildTimeList() {
        doReturn(true).`when` { DesktopModeStatus.useAppToWebBuildTimeGenericLinks() }
        genericLinksParser = AppToWebGenericLinksParser(mContext, mockExecutor)
        // Assert build-time list correctly parsed
        assertEquals(URL_B, genericLinksParser.getGenericLink(PACKAGE_NAME_1))
    }

    @Test
    fun init_usingServerSideList() {
        doReturn(false).`when` { DesktopModeStatus.useAppToWebBuildTimeGenericLinks() }
        genericLinksParser = AppToWebGenericLinksParser(mContext, mockExecutor)
        // Assert server side list correctly parsed
        assertEquals(URL_S, genericLinksParser.getGenericLink(PACKAGE_NAME_1))
    }

    @Test
    fun init_ignoresMalformedPair() {
        doReturn(true).`when` { DesktopModeStatus.useAppToWebBuildTimeGenericLinks() }
        val packageName2 = "com.google.android.slides"
        val url2 = "https://docs.google.com"
        resources.addOverride(R.string.generic_links_list,
                "$PACKAGE_NAME_1:$URL_B error $packageName2:$url2")
        genericLinksParser = AppToWebGenericLinksParser(mContext, mockExecutor)
        // Assert generics links list correctly parsed
        assertEquals(URL_B, genericLinksParser.getGenericLink(PACKAGE_NAME_1))
        assertEquals(url2, genericLinksParser.getGenericLink(packageName2))
    }


    @Test
    fun onlySavesValidPackageToUrlMaps() {
        doReturn(true).`when` { DesktopModeStatus.useAppToWebBuildTimeGenericLinks() }
        resources.addOverride(R.string.generic_links_list, "$PACKAGE_NAME_1:www.yout")
        genericLinksParser = AppToWebGenericLinksParser(mContext, mockExecutor)
        // Verify map with invalid url not saved
        assertNull(genericLinksParser.getGenericLink(PACKAGE_NAME_1))
    }

    companion object {
        private const val PACKAGE_NAME_1 = "com.google.android.youtube"

        private const val URL_B = "http://www.youtube.com"
        private const val URL_S = "http://www.google.com"

        private const val SERVER_SIDE_LIST = "$PACKAGE_NAME_1:$URL_S"
        private const val BUILD_TIME_LIST = "$PACKAGE_NAME_1:$URL_B"

        private const val NAMESPACE = DeviceConfig.NAMESPACE_APP_COMPAT_OVERRIDES
    }
}
