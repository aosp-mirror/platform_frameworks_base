package com.android.systemui.statusbar.notification.collection.inflation

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotifUiAdjustmentProviderTest : SysuiTestCase() {
    private val lockscreenUserManager: NotificationLockscreenUserManager = mock()
    private val sectionStyleProvider: SectionStyleProvider = mock()

    private val adjustmentProvider = NotifUiAdjustmentProvider(
        lockscreenUserManager,
        sectionStyleProvider,
    )

    @Test
    fun notifLockscreenStateChangeWillNotifDirty() {
        val dirtyListener = mock<Runnable>()
        adjustmentProvider.addDirtyListener(dirtyListener)
        val notifLocksreenStateChangeListener =
            withArgCaptor<NotificationLockscreenUserManager.NotificationStateChangedListener> {
                verify(lockscreenUserManager).addNotificationStateChangedListener(capture())
            }
        notifLocksreenStateChangeListener.onNotificationStateChanged()
        verify(dirtyListener).run();
    }
}
