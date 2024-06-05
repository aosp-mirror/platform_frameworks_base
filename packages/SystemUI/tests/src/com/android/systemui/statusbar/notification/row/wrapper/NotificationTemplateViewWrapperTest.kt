/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.wrapper

import android.app.PendingIntent
import android.app.PendingIntent.CancelListener
import android.content.Intent
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestUiOffloadThread
import com.android.systemui.UiOffloadThread
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.row.wrapper.NotificationTemplateViewWrapper.ActionPendingIntentCancellationHandler
import com.android.systemui.util.leak.ReferenceTestUtils.waitForCondition
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class NotificationTemplateViewWrapperTest : SysuiTestCase() {

    private lateinit var helper: NotificationTestHelper

    private lateinit var root: ViewGroup
    private lateinit var view: ViewGroup
    private lateinit var row: ExpandableNotificationRow
    private lateinit var actions: ViewGroup

    private lateinit var looper: TestableLooper

    @Before
    fun setUp() {
        looper = TestableLooper.get(this)
        allowTestableLooperAsMainThread()
        // Use main thread instead of UI offload thread to fix flakes.
        mDependency.injectTestDependency(
            UiOffloadThread::class.java,
            TestUiOffloadThread(looper.looper)
        )

        helper = NotificationTestHelper(mContext, mDependency)
        row = helper.createRow()
        // Some code in the view iterates through parents so we need some extra containers around
        // it.
        root = FrameLayout(mContext)
        val root2 = FrameLayout(mContext)
        root.addView(root2)
        view =
            (LayoutInflater.from(mContext)
                .inflate(R.layout.notification_template_material_big_text, root2) as ViewGroup)
        actions = view.findViewById(R.id.actions)!!
        ViewUtils.attachView(root)
    }

    @Test
    fun noActionsPresent_noCrash() {
        view.removeView(actions)
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        wrapper.onContentUpdated(row)
    }

    @Test
    fun actionPendingIntentCancelled_actionDisabled() {
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        val action1 = createActionWithPendingIntent()
        val action2 = createActionWithPendingIntent()
        val action3 = createActionWithPendingIntent()
        wrapper.onContentUpdated(row)

        val pi3 = getPendingIntent(action3)
        pi3.cancel()

        waitForActionDisabled(action3)
        assertThat(action1.isEnabled).isTrue()
        assertThat(action2.isEnabled).isTrue()
        assertThat(action3.isEnabled).isFalse()
        assertThat(wrapper.mCancelledPendingIntents)
            .doesNotContain(getPendingIntent(action1).hashCode())
        assertThat(wrapper.mCancelledPendingIntents)
            .doesNotContain(getPendingIntent(action2).hashCode())
        assertThat(wrapper.mCancelledPendingIntents).contains(pi3.hashCode())
    }

    @Test
    fun newActionWithSamePendingIntentPosted_actionDisabled() {
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        val action = createActionWithPendingIntent()
        wrapper.onContentUpdated(row)

        // Cancel the intent and check action is now false.
        val pi = getPendingIntent(action)
        pi.cancel()

        waitForActionDisabled(action)
        assertThat(action.isEnabled).isFalse()

        // Create a NEW action and make sure that one will also be cancelled with same PI.
        actions.removeView(action)
        val newAction = createActionWithPendingIntent(pi)
        wrapper.onContentUpdated(row)
        looper.processAllMessages() // Wait for listener callbacks to execute

        assertThat(newAction.isEnabled).isFalse()
        assertThat(wrapper.mCancelledPendingIntents).containsExactly(pi.hashCode())
    }

    @Test
    fun twoActionsWithSameCancelledIntent_bothActionsDisabled() {
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        val action1 = createActionWithPendingIntent()
        val action2 = createActionWithPendingIntent()
        val action3 = createActionWithPendingIntent(getPendingIntent(action2))
        wrapper.onContentUpdated(row)
        looper.processAllMessages()

        val pi = getPendingIntent(action2)
        pi.cancel()

        waitForActionDisabled(action2)
        waitForActionDisabled(action3)
        assertThat(action1.isEnabled).isTrue()
        assertThat(action2.isEnabled).isFalse()
        assertThat(action3.isEnabled).isFalse()
    }

    @Test
    fun actionPendingIntentCancelled_whileDetached_actionDisabled() {
        ViewUtils.detachView(root)
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        val action = createActionWithPendingIntent()
        wrapper.onContentUpdated(row)
        getPendingIntent(action).cancel()
        looper.processAllMessages()

        ViewUtils.attachView(root)
        looper.processAllMessages()

        waitForActionDisabled(action)
        assertThat(action.isEnabled).isFalse()
    }

    @Test
    fun actionViewDetached_pendingIntentListenersDeregistered() {
        val pi =
            PendingIntent.getActivity(
                mContext,
                System.currentTimeMillis().toInt(),
                Intent(Intent.ACTION_VIEW),
                PendingIntent.FLAG_IMMUTABLE
            )
        val spy = Mockito.spy(pi)
        createActionWithPendingIntent(spy)
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        wrapper.onContentUpdated(row)
        ViewUtils.detachView(root)
        looper.processAllMessages()

        val captor = ArgumentCaptor.forClass(CancelListener::class.java)
        verify(spy, times(1)).registerCancelListener(captor.capture())
        verify(spy, times(1)).unregisterCancelListener(captor.value)
    }

    @Test
    fun actionViewUpdated_oldPendingIntentListenersRemoved() {
        val pi =
            PendingIntent.getActivity(
                mContext,
                System.currentTimeMillis().toInt(),
                Intent(Intent.ACTION_VIEW),
                PendingIntent.FLAG_IMMUTABLE
            )
        val spy = Mockito.spy(pi)
        val action = createActionWithPendingIntent(spy)
        val wrapper = NotificationTemplateViewWrapper(mContext, view, row)
        wrapper.onContentUpdated(row)
        looper.processAllMessages()

        // Grab set attach listener
        val attachListener =
            Mockito.spy(action.getTag(com.android.systemui.res.R.id.pending_intent_listener_tag))
                as ActionPendingIntentCancellationHandler
        action.setTag(com.android.systemui.res.R.id.pending_intent_listener_tag, attachListener)

        // Update pending intent in the existing action
        val newPi =
            PendingIntent.getActivity(
                mContext,
                System.currentTimeMillis().toInt(),
                Intent(Intent.ACTION_ALARM_CHANGED),
                PendingIntent.FLAG_IMMUTABLE
            )
        action.setTagInternal(R.id.pending_intent_tag, newPi)
        wrapper.onContentUpdated(row)
        looper.processAllMessages()

        // Listeners for original pending intent need to be cleaned up now.
        val captor = ArgumentCaptor.forClass(CancelListener::class.java)
        verify(spy, times(1)).registerCancelListener(captor.capture())
        verify(spy, times(1)).unregisterCancelListener(captor.value)
        // Attach listener has to be replaced with a new one.
        assertThat(action.getTag(com.android.systemui.res.R.id.pending_intent_listener_tag))
            .isNotEqualTo(attachListener)
        verify(attachListener).remove()
    }

    private fun createActionWithPendingIntent(): View {
        val pi =
            PendingIntent.getActivity(
                mContext,
                System.currentTimeMillis().toInt(),
                Intent(Intent.ACTION_VIEW),
                PendingIntent.FLAG_IMMUTABLE
            )
        return createActionWithPendingIntent(pi)
    }

    private fun createActionWithPendingIntent(pi: PendingIntent): View {
        val view =
            LayoutInflater.from(mContext)
                .inflate(R.layout.notification_material_action, null, false)
        view.setTagInternal(R.id.pending_intent_tag, pi)
        actions.addView(view)
        return view
    }

    private fun getPendingIntent(action: View): PendingIntent {
        val pendingIntent = action.getTag(R.id.pending_intent_tag) as PendingIntent
        assertThat(pendingIntent).isNotNull()
        return pendingIntent
    }

    private fun waitForActionDisabled(action: View) {
        waitForCondition {
            looper.processAllMessages()
            !action.isEnabled
        }
    }
}
