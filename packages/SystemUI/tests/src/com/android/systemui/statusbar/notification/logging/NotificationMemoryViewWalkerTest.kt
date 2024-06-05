package com.android.systemui.statusbar.notification.logging

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.testing.TestableLooper
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.tests.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class NotificationMemoryViewWalkerTest : SysuiTestCase() {

    private lateinit var testHelper: NotificationTestHelper

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        testHelper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
    }

    @Test
    fun testViewWalker_nullRow_returnsEmptyView() {
        val result = NotificationMemoryViewWalker.getViewUsage(null)
        assertThat(result).isNotNull()
        assertThat(result).isEmpty()
    }

    @Test
    fun testViewWalker_plainNotification() {
        val row = testHelper.createRow()
        val result = NotificationMemoryViewWalker.getViewUsage(row)
        assertThat(result).hasSize(3)
        assertThat(result)
            .contains(NotificationViewUsage(ViewType.PRIVATE_EXPANDED_VIEW, 0, 0, 0, 0, 0, 0))
        assertThat(result)
            .contains(NotificationViewUsage(ViewType.PRIVATE_CONTRACTED_VIEW, 0, 0, 0, 0, 0, 0))
        assertThat(result).contains(NotificationViewUsage(ViewType.TOTAL, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun testViewWalker_plainNotification_withPublicView() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        val publicIcon = Icon.createWithBitmap(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
        testHelper.setDefaultInflationFlags(NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL)
        val row =
            testHelper.createRow(
                Notification.Builder(mContext)
                    .setContentText("Test")
                    .setContentTitle("title")
                    .setSmallIcon(icon)
                    .setPublicVersion(
                        Notification.Builder(mContext)
                            .setContentText("Public Test")
                            .setContentTitle("title")
                            .setSmallIcon(publicIcon)
                            .build()
                    )
                    .build()
            )
        val result = NotificationMemoryViewWalker.getViewUsage(row)
        assertThat(result).hasSize(4)
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PRIVATE_EXPANDED_VIEW,
                    icon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    0,
                    icon.bitmap.allocationByteCount
                )
            )
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PRIVATE_CONTRACTED_VIEW,
                    icon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    0,
                    icon.bitmap.allocationByteCount
                )
            )
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PUBLIC_VIEW,
                    publicIcon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    0,
                    publicIcon.bitmap.allocationByteCount
                )
            )
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.TOTAL,
                    icon.bitmap.allocationByteCount + publicIcon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    0,
                    icon.bitmap.allocationByteCount + publicIcon.bitmap.allocationByteCount
                )
            )
    }

    @Test
    fun testViewWalker_bigPictureNotification() {
        val bigPicture = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        val largeIcon = Icon.createWithBitmap(Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888))
        val row =
            testHelper.createRow(
                Notification.Builder(mContext)
                    .setContentText("Test")
                    .setContentTitle("title")
                    .setSmallIcon(icon)
                    .setLargeIcon(largeIcon)
                    .setStyle(Notification.BigPictureStyle().bigPicture(bigPicture))
                    .build()
            )
        val result = NotificationMemoryViewWalker.getViewUsage(row)
        assertThat(result).hasSize(3)
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PRIVATE_EXPANDED_VIEW,
                    icon.bitmap.allocationByteCount,
                    largeIcon.bitmap.allocationByteCount,
                    0,
                    bigPicture.allocationByteCount,
                    0,
                    bigPicture.allocationByteCount +
                        icon.bitmap.allocationByteCount +
                        largeIcon.bitmap.allocationByteCount
                )
            )

        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PRIVATE_CONTRACTED_VIEW,
                    icon.bitmap.allocationByteCount,
                    largeIcon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    icon.bitmap.allocationByteCount + largeIcon.bitmap.allocationByteCount
                )
            )
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.TOTAL,
                    icon.bitmap.allocationByteCount,
                    largeIcon.bitmap.allocationByteCount,
                    0,
                    bigPicture.allocationByteCount,
                    0,
                    bigPicture.allocationByteCount +
                        icon.bitmap.allocationByteCount +
                        largeIcon.bitmap.allocationByteCount
                )
            )
    }

    @Test
    fun testViewWalker_customView() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)

        val views = RemoteViews(mContext.packageName, R.layout.custom_view_dark)
        views.setImageViewBitmap(R.id.custom_view_dark_image, bitmap)
        val row =
            testHelper.createRow(
                Notification.Builder(mContext)
                    .setContentText("Test")
                    .setContentTitle("title")
                    .setSmallIcon(icon)
                    .setCustomContentView(views)
                    .setCustomBigContentView(views)
                    .build()
            )
        val result = NotificationMemoryViewWalker.getViewUsage(row)
        assertThat(result).hasSize(3)
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PRIVATE_CONTRACTED_VIEW,
                    icon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    bitmap.allocationByteCount,
                    bitmap.allocationByteCount + icon.bitmap.allocationByteCount
                )
            )
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.PRIVATE_EXPANDED_VIEW,
                    icon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    bitmap.allocationByteCount,
                    bitmap.allocationByteCount + icon.bitmap.allocationByteCount
                )
            )
        assertThat(result)
            .contains(
                NotificationViewUsage(
                    ViewType.TOTAL,
                    icon.bitmap.allocationByteCount,
                    0,
                    0,
                    0,
                    bitmap.allocationByteCount,
                    bitmap.allocationByteCount + icon.bitmap.allocationByteCount
                )
            )
    }
}
