/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ControlsRequestReceiverTest : SysuiTestCase() {

    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var activityManager: ActivityManager
    @Mock
    private lateinit var control: Control

    private val componentName = ComponentName("test_pkg", "test_cls")
    private lateinit var receiver: ControlsRequestReceiver
    private lateinit var wrapper: MyWrapper
    private lateinit var intent: Intent

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mContext.setMockPackageManager(packageManager)
        `when`(packageManager.hasSystemFeature(PackageManager.FEATURE_CONTROLS)).thenReturn(true)
        mContext.addMockSystemService(ActivityManager::class.java, activityManager)

        receiver = ControlsRequestReceiver()

        wrapper = MyWrapper(context)

        intent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
            putExtra(ControlsProviderService.EXTRA_CONTROL, control)
        }
    }

    @Test
    fun testPackageVerification_nonExistentPackage() {
        `when`(packageManager.getPackageUid(anyString(), anyInt()))
                .thenThrow(PackageManager.NameNotFoundException::class.java)

        assertFalse(ControlsRequestReceiver.isPackageInForeground(mContext, "TEST"))
    }

    @Test
    fun testPackageVerification_uidNotInForeground() {
        `when`(packageManager.getPackageUid(anyString(), anyInt())).thenReturn(12345)

        `when`(activityManager.getUidImportance(anyInt())).thenReturn(IMPORTANCE_GONE)

        assertFalse(ControlsRequestReceiver.isPackageInForeground(mContext, "TEST"))
    }

    @Test
    fun testPackageVerification_OK() {
        `when`(packageManager.getPackageUid(anyString(), anyInt())).thenReturn(12345)

        `when`(activityManager.getUidImportance(anyInt())).thenReturn(IMPORTANCE_GONE)
        `when`(activityManager.getUidImportance(12345)).thenReturn(IMPORTANCE_FOREGROUND)

        assertTrue(ControlsRequestReceiver.isPackageInForeground(mContext, "TEST"))
    }

    @Test
    fun testOnReceive_packageNotVerified_nameNotFound() {
        `when`(packageManager.getPackageUid(eq(componentName.packageName), anyInt()))
                .thenThrow(PackageManager.NameNotFoundException::class.java)

        receiver.onReceive(wrapper, intent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testOnReceive_packageNotVerified_notForeground() {
        `when`(packageManager.getPackageUid(eq(componentName.packageName), anyInt()))
                .thenReturn(12345)

        `when`(activityManager.getUidImportance(anyInt())).thenReturn(IMPORTANCE_GONE)

        receiver.onReceive(wrapper, intent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testOnReceive_OK() {
        `when`(packageManager.getPackageUid(eq(componentName.packageName), anyInt()))
                .thenReturn(12345)

        `when`(activityManager.getUidImportance(eq(12345))).thenReturn(IMPORTANCE_FOREGROUND)

        receiver.onReceive(wrapper, intent)

        wrapper.intent?.let {
            assertEquals(ComponentName(wrapper, ControlsRequestDialog::class.java), it.component)

            assertEquals(control, it.getParcelableExtra(ControlsProviderService.EXTRA_CONTROL))

            assertEquals(componentName, it.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME))
        } ?: run { fail("Null start intent") }
    }

    @Test
    fun testFeatureDisabled_activityNotStarted() {
        `when`(packageManager.hasSystemFeature(PackageManager.FEATURE_CONTROLS)).thenReturn(false)
        receiver.onReceive(wrapper, intent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testClassCastExceptionComponentName_noCrash() {
        val badIntent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, Intent())
            putExtra(ControlsProviderService.EXTRA_CONTROL, control)
        }
        receiver.onReceive(wrapper, badIntent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testClassCastExceptionControl_noCrash() {
        val badIntent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
            putExtra(ControlsProviderService.EXTRA_CONTROL, Intent())
        }
        receiver.onReceive(wrapper, badIntent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testClassNotFoundExceptionComponent_noCrash() {
        val bundle = Bundle().apply {
            putParcelable(Intent.EXTRA_COMPONENT_NAME, PrivateParcelable())
            putParcelable(ControlsProviderService.EXTRA_CONTROL, control)
        }
        val parcel = Parcel.obtain()
        bundle.writeToParcel(parcel, 0)

        parcel.setDataPosition(0)

        val badIntent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            parcel.readBundle()?.let { putExtras(it) }
        }
        receiver.onReceive(wrapper, badIntent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testClassNotFoundExceptionControl_noCrash() {
        val bundle = Bundle().apply {
            putParcelable(Intent.EXTRA_COMPONENT_NAME, componentName)
            putParcelable(ControlsProviderService.EXTRA_CONTROL, PrivateParcelable())
        }
        val parcel = Parcel.obtain()
        bundle.writeToParcel(parcel, 0)

        parcel.setDataPosition(0)

        val badIntent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            parcel.readBundle()?.let { putExtras(it) }
        }
        receiver.onReceive(wrapper, badIntent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testMissingComponentName_noCrash() {
        val badIntent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            putExtra(ControlsProviderService.EXTRA_CONTROL, control)
        }
        receiver.onReceive(wrapper, badIntent)

        assertNull(wrapper.intent)
    }

    @Test
    fun testMissingControl_noCrash() {
        val badIntent = Intent(ControlsProviderService.ACTION_ADD_CONTROL).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
        }
        receiver.onReceive(wrapper, badIntent)

        assertNull(wrapper.intent)
    }

    class MyWrapper(context: Context) : ContextWrapper(context) {
        var intent: Intent? = null

        override fun startActivityAsUser(intent: Intent, user: UserHandle) {
            // Always launch activity as system
            assertTrue(user == UserHandle.SYSTEM)
            this.intent = intent
        }

        override fun startActivity(intent: Intent) {
            this.intent = intent
        }
    }

    class PrivateParcelable : Parcelable {
        override fun describeContents() = 0

        override fun writeToParcel(dest: Parcel, flags: Int) {}

        companion object CREATOR : Parcelable.Creator<PrivateParcelable?> {
            override fun createFromParcel(source: Parcel?): PrivateParcelable {
                return PrivateParcelable()
            }

            override fun newArray(size: Int): Array<PrivateParcelable?> {
                return arrayOfNulls(size)
            }
        }
    }
}
