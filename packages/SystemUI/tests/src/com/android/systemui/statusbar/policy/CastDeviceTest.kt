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

package com.android.systemui.statusbar.policy

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.MediaRouter
import android.media.projection.MediaProjectionInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.CastDevice.Companion.toCastDevice
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
class CastDeviceTest : SysuiTestCase() {
    private val mockAppInfo =
        mock<ApplicationInfo>().apply { whenever(this.loadLabel(any())).thenReturn("") }

    private val packageManager =
        mock<PackageManager>().apply {
            whenever(
                    this.checkPermission(
                        Manifest.permission.REMOTE_DISPLAY_PROVIDER,
                        HEADLESS_REMOTE_PACKAGE,
                    )
                )
                .thenReturn(PackageManager.PERMISSION_GRANTED)
            whenever(
                    this.checkPermission(
                        Manifest.permission.REMOTE_DISPLAY_PROVIDER,
                        NORMAL_PACKAGE,
                    )
                )
                .thenReturn(PackageManager.PERMISSION_DENIED)

            doAnswer {
                    // See Utils.isHeadlessRemoteDisplayProvider
                    if ((it.arguments[0] as Intent).`package` == HEADLESS_REMOTE_PACKAGE) {
                        emptyList()
                    } else {
                        listOf(mock<ResolveInfo>())
                    }
                }
                .whenever(this)
                .queryIntentActivities(any(), ArgumentMatchers.anyInt())

            whenever(this.getApplicationInfo(any<String>(), any<Int>())).thenReturn(mockAppInfo)
        }

    @Test
    fun isCasting_disconnected_false() {
        val device =
            CastDevice(
                id = "id",
                name = "name",
                state = CastDevice.CastState.Disconnected,
                origin = CastDevice.CastOrigin.MediaRouter,
            )

        assertThat(device.isCasting).isFalse()
    }

    @Test
    fun isCasting_connecting_true() {
        val device =
            CastDevice(
                id = "id",
                name = "name",
                state = CastDevice.CastState.Connecting,
                origin = CastDevice.CastOrigin.MediaRouter,
            )

        assertThat(device.isCasting).isTrue()
    }

    @Test
    fun isCasting_connected_true() {
        val device =
            CastDevice(
                id = "id",
                name = "name",
                state = CastDevice.CastState.Connected,
                origin = CastDevice.CastOrigin.MediaRouter,
            )

        assertThat(device.isCasting).isTrue()
    }

    @Test
    fun routeToCastDevice_statusNone_stateDisconnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_NONE)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Disconnected)
    }

    @Test
    fun routeToCastDevice_statusNotAvailable_stateDisconnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_NOT_AVAILABLE)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Disconnected)
    }

    @Test
    fun routeToCastDevice_statusScanning_stateDisconnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_SCANNING)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Disconnected)
    }

    @Test
    fun routeToCastDevice_statusAvailable_isSelectedFalse_stateDisconnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_AVAILABLE)
                whenever(this.isSelected).thenReturn(false)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Disconnected)
    }

    @Test
    fun routeToCastDevice_statusAvailable_isSelectedTrue_stateConnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_AVAILABLE)
                whenever(this.isSelected).thenReturn(true)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connected)
    }

    @Test
    fun routeToCastDevice_statusInUse_isSelectedFalse_stateDisconnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_IN_USE)
                whenever(this.isSelected).thenReturn(false)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Disconnected)
    }

    @Test
    fun routeToCastDevice_statusInUse_isSelectedTrue_stateConnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_IN_USE)
                whenever(this.isSelected).thenReturn(true)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connected)
    }

    @Test
    fun routeToCastDevice_statusConnecting_isSelectedFalse_stateConnecting() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_CONNECTING)
                whenever(this.isSelected).thenReturn(false)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connecting)
    }

    @Test
    fun routeToCastDevice_statusConnecting_isSelectedTrue_stateConnecting() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_CONNECTING)
                whenever(this.isSelected).thenReturn(true)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connecting)
    }

    @Test
    fun routeToCastDevice_statusConnected_isSelectedFalse_stateConnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_CONNECTED)
                whenever(this.isSelected).thenReturn(false)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connected)
    }

    @Test
    fun routeToCastDevice_statusConnected_isSelectedTrue_stateConnected() {
        val route =
            mockRouteInfo().apply {
                whenever(this.statusCode).thenReturn(MediaRouter.RouteInfo.STATUS_CONNECTED)
                whenever(this.isSelected).thenReturn(true)
            }

        val device = route.toCastDevice(context)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connected)
    }

    @Test
    fun routeToCastDevice_tagIsStringType_idMatchesTag() {
        val route = mock<MediaRouter.RouteInfo>().apply { whenever(this.tag).thenReturn("FakeTag") }

        val device = route.toCastDevice(context)

        assertThat(device.id).isEqualTo("FakeTag")
    }

    @Test
    fun routeToCastDevice_tagIsOtherType_idMatchesTag() {
        val tag = listOf("tag1", "tag2")
        val route = mock<MediaRouter.RouteInfo>().apply { whenever(this.tag).thenReturn(tag) }

        val device = route.toCastDevice(context)

        assertThat(device.id).isEqualTo(tag.toString())
    }

    @Test
    fun routeToCastDevice_nameMatchesName() {
        val route = mockRouteInfo().apply { whenever(this.getName(context)).thenReturn("FakeName") }

        val device = route.toCastDevice(context)

        assertThat(device.name).isEqualTo("FakeName")
    }

    @Test
    fun routeToCastDevice_descriptionMatchesDescription() {
        val route =
            mockRouteInfo().apply { whenever(this.description).thenReturn("FakeDescription") }

        val device = route.toCastDevice(context)

        assertThat(device.description).isEqualTo("FakeDescription")
    }

    @Test
    fun routeToCastDevice_tagIsRoute() {
        val route = mockRouteInfo()

        val device = route.toCastDevice(context)

        assertThat(device.tag).isEqualTo(route)
    }

    @Test
    fun routeToCastDevice_originIsMediaRouter() {
        val route = mockRouteInfo()

        val device = route.toCastDevice(context)

        assertThat(device.origin).isEqualTo(CastDevice.CastOrigin.MediaRouter)
    }

    @Test
    fun routeToCastDevice_nullValues_ok() {
        val device = mockRouteInfo().toCastDevice(context)

        assertThat(device.name).isNull()
        assertThat(device.description).isNull()
    }

    @Test
    fun projectionToCastDevice_idMatchesPackage() {
        val projection =
            mock<MediaProjectionInfo>().apply {
                whenever(this.packageName).thenReturn("fake.package")
            }

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.id).isEqualTo("fake.package")
    }

    @Test
    fun projectionToCastDevice_name_packageIsHeadlessRemote_isEmpty() {
        val projection =
            mock<MediaProjectionInfo>().apply {
                whenever(this.packageName).thenReturn(HEADLESS_REMOTE_PACKAGE)
            }

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.name).isEmpty()
    }

    @Test
    fun projectionToCastDevice_name_packageMissingFromPackageManager_isPackageName() {
        val projection =
            mock<MediaProjectionInfo>().apply {
                whenever(this.packageName).thenReturn(NORMAL_PACKAGE)
            }

        whenever(packageManager.getApplicationInfo(eq(NORMAL_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.name).isEqualTo(NORMAL_PACKAGE)
    }

    @Test
    fun projectionToCastDevice_name_nameFromPackageManagerEmpty_isPackageName() {
        val projection =
            mock<MediaProjectionInfo>().apply {
                whenever(this.packageName).thenReturn(NORMAL_PACKAGE)
            }

        val appInfo = mock<ApplicationInfo>()
        whenever(appInfo.loadLabel(packageManager)).thenReturn("")
        whenever(packageManager.getApplicationInfo(eq(NORMAL_PACKAGE), any<Int>()))
            .thenReturn(appInfo)

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.name).isEqualTo(NORMAL_PACKAGE)
    }

    @Test
    fun projectionToCastDevice_name_packageManagerHasName_isName() {
        val projection =
            mock<MediaProjectionInfo>().apply {
                whenever(this.packageName).thenReturn(NORMAL_PACKAGE)
            }

        val appInfo = mock<ApplicationInfo>()
        whenever(appInfo.loadLabel(packageManager)).thenReturn("Valid App Name")
        whenever(packageManager.getApplicationInfo(eq(NORMAL_PACKAGE), any<Int>()))
            .thenReturn(appInfo)

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.name).isEqualTo("Valid App Name")
    }

    @Test
    fun projectionToCastDevice_descriptionIsCasting() {
        val projection = mockProjectionInfo()

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.description).isEqualTo(context.getString(R.string.quick_settings_casting))
    }

    @Test
    fun projectionToCastDevice_stateIsConnected() {
        val projection = mockProjectionInfo()

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.state).isEqualTo(CastDevice.CastState.Connected)
    }

    @Test
    fun projectionToCastDevice_tagIsProjection() {
        val projection = mockProjectionInfo()

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.tag).isEqualTo(projection)
    }

    @Test
    fun projectionToCastDevice_originIsMediaProjection() {
        val projection = mockProjectionInfo()

        val device = projection.toCastDevice(context, packageManager)

        assertThat(device.origin).isEqualTo(CastDevice.CastOrigin.MediaProjection)
    }

    private fun mockRouteInfo(): MediaRouter.RouteInfo {
        return mock<MediaRouter.RouteInfo>().apply { whenever(this.tag).thenReturn(Any()) }
    }

    private fun mockProjectionInfo(): MediaProjectionInfo {
        return mock<MediaProjectionInfo>().apply {
            whenever(this.packageName).thenReturn("fake.package")
        }
    }

    private companion object {
        const val HEADLESS_REMOTE_PACKAGE = "headless.remote.package"
        const val NORMAL_PACKAGE = "normal.package"
    }
}
