package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class PendingRemovalStoreTest : SysuiTestCase() {

    @Mock
    private lateinit var logger: BroadcastDispatcherLogger
    @Mock
    private lateinit var receiverOne: BroadcastReceiver
    @Mock
    private lateinit var receiverTwo: BroadcastReceiver

    private lateinit var store: PendingRemovalStore

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        store = PendingRemovalStore(logger)
    }

    @Test
    fun testTagForRemoval_logged() {
        val user = 10
        store.tagForRemoval(receiverOne, 10)

        verify(logger).logTagForRemoval(user, receiverOne)
    }

    @Test
    fun testClearedPendingRemoval_logged() {
        val user = UserHandle.USER_ALL
        store.clearPendingRemoval(receiverOne, user)

        verify(logger).logClearedAfterRemoval(user, receiverOne)
    }

    @Test
    fun testTaggedReceiverMarkedAsPending_specificUser() {
        val user = 10
        store.tagForRemoval(receiverOne, user)

        assertThat(store.isPendingRemoval(receiverOne, user)).isTrue()
        assertThat(store.isPendingRemoval(receiverOne, user + 1)).isFalse()
        assertThat(store.isPendingRemoval(receiverOne, UserHandle.USER_ALL)).isFalse()
    }

    @Test
    fun testTaggedReceiverMarkedAsPending_allUsers() {
        val user = 10
        store.tagForRemoval(receiverOne, UserHandle.USER_ALL)

        assertThat(store.isPendingRemoval(receiverOne, user)).isTrue()
        assertThat(store.isPendingRemoval(receiverOne, user + 1)).isTrue()
        assertThat(store.isPendingRemoval(receiverOne, UserHandle.USER_ALL)).isTrue()
    }

    @Test
    fun testOnlyBlockCorrectReceiver() {
        val user = 10
        store.tagForRemoval(receiverOne, user)

        assertThat(store.isPendingRemoval(receiverOne, user)).isTrue()
        assertThat(store.isPendingRemoval(receiverTwo, user)).isFalse()
    }
}
