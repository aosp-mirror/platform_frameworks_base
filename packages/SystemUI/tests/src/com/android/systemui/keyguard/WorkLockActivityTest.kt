/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.keyguard

import android.annotation.UserIdInt
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/** runtest systemui -c com.android.systemui.keyguard.WorkLockActivityTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
class WorkLockActivityTest : SysuiTestCase() {
    private val context: Context = mock()
    private val userManager: UserManager = mock()
    private val packageManager: PackageManager = mock()
    private val broadcastDispatcher: BroadcastDispatcher = mock()
    private val drawable: Drawable = mock()
    private val badgedDrawable: Drawable = mock()
    private lateinit var activity: WorkLockActivity

    private class WorkLockActivityTestable
    constructor(
        baseContext: Context,
        broadcastDispatcher: BroadcastDispatcher,
        userManager: UserManager,
        packageManager: PackageManager,
    ) : WorkLockActivity(broadcastDispatcher, userManager, packageManager) {
        init {
            attachBaseContext(baseContext)
        }
    }

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        activity =
            WorkLockActivityTestable(
                baseContext = context,
                broadcastDispatcher = broadcastDispatcher,
                userManager = userManager,
                packageManager = packageManager
            )
    }

    @Test
    fun testGetBadgedIcon() {
        val info = ApplicationInfo()
        whenever(
                packageManager.getApplicationInfoAsUser(
                    eq(CALLING_PACKAGE_NAME),
                    any<ApplicationInfoFlags>(),
                    eq(USER_ID)
                )
            )
            .thenReturn(info)
        whenever(packageManager.getApplicationIcon(ArgumentMatchers.eq(info))).thenReturn(drawable)
        whenever(userManager.getBadgedIconForUser(any(), eq(UserHandle.of(USER_ID))))
            .thenReturn(badgedDrawable)
        activity.intent =
            Intent()
                .putExtra(Intent.EXTRA_USER_ID, USER_ID)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, CALLING_PACKAGE_NAME)
        assertEquals(badgedDrawable, activity.badgedIcon)
    }

    @Test
    fun testUnregisteredFromDispatcher() {
        activity.unregisterBroadcastReceiver()
        verify(broadcastDispatcher).unregisterReceiver(any())
        verify(context, never()).unregisterReceiver(nullable())
    }

    @Test
    fun onBackPressed_finishActivity() {
        assertFalse(activity.isFinishing)

        activity.onBackPressed()

        assertFalse(activity.isFinishing)
    }

    companion object {
        @UserIdInt private val USER_ID = 270
        private const val CALLING_PACKAGE_NAME = "com.android.test"
    }
}
