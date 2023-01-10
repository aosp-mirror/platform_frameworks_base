/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.collection

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever

private const val SDK_VERSION = 33
private const val PACKAGE = "pkg"
private const val USER_ID = -1

@SmallTest
@RunWith(AndroidTestingRunner::class)
class TargetSdkResolverTest : SysuiTestCase() {
    private val packageManager: PackageManager = mock()
    private val applicationInfo = ApplicationInfo().apply { targetSdkVersion = SDK_VERSION }

    private lateinit var targetSdkResolver: TargetSdkResolver
    private lateinit var notifListener: NotifCollectionListener

    @Before
    fun setUp() {
        targetSdkResolver = TargetSdkResolver(mContext)
        mContext.setMockPackageManager(packageManager)

        val notifCollection: CommonNotifCollection = mock()
        targetSdkResolver.initialize(notifCollection)
        notifListener = withArgCaptor {
            verify(notifCollection).addCollectionListener(capture())
        }
    }

    @Test
    fun resolveFromNotificationExtras() {
        val extras = Bundle().apply {
            putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, applicationInfo)
        }
        val notification = Notification().apply { this.extras = extras }
        val sbn = createSbn(notification)
        val entry = createNotificationEntry(sbn)

        notifListener.onEntryBind(entry, sbn)

        assertEquals(SDK_VERSION, entry.targetSdk)
        verifyZeroInteractions(packageManager)
    }

    @Test
    fun resolveFromPackageManager() {
        val sbn = createSbn(Notification())
        val entry = createNotificationEntry(sbn)
        whenever(packageManager.getApplicationInfo(anyString(), anyInt()))
                .thenReturn(applicationInfo)

        notifListener.onEntryBind(entry, sbn)

        assertEquals(SDK_VERSION, entry.targetSdk)
        verify(packageManager).getApplicationInfo(eq(PACKAGE), anyInt())
    }

    @Test
    fun resolveFromPackageManager_andPackageManagerCrashes() {
        val sbn = createSbn(Notification())
        val entry = createNotificationEntry(sbn)
        whenever(packageManager.getApplicationInfo(anyString(), anyInt()))
                .thenThrow(PackageManager.NameNotFoundException())

        notifListener.onEntryBind(entry, sbn)

        assertEquals(0, entry.targetSdk)
        verify(packageManager).getApplicationInfo(eq(PACKAGE), anyInt())
    }

    private fun createSbn(notification: Notification) = StatusBarNotification(
            PACKAGE, "opPkg", 0, "tag", 0, 0,
            notification, UserHandle(USER_ID), "", 0
    )

    private fun createNotificationEntry(sbn: StatusBarNotification) =
            NotificationEntry(sbn, createRanking(sbn.key), 0)

    private fun createRanking(key: String) = Ranking().apply {
        populate(
                key,
                0,
                false,
                0,
                0,
                NotificationManager.IMPORTANCE_DEFAULT,
                null, null,
                null, null, null, true, 0, false, -1, false, null, null, false, false,
                false, null, 0, false)
    }
}
