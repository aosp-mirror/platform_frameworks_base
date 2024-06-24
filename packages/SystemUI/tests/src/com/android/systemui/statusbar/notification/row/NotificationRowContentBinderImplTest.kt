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
package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.content.Context
import android.os.AsyncTask
import android.os.Build
import android.os.CancellationSignal
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_SP
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.BindParams
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationCallback
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.statusbar.notification.row.shared.HeadsUpStatusBarModel
import com.android.systemui.statusbar.notification.row.shared.NewRemoteViews
import com.android.systemui.statusbar.notification.row.shared.NotificationContentModel
import com.android.systemui.statusbar.notification.row.shared.NotificationRowContentBinderRefactor
import com.android.systemui.statusbar.notification.row.shared.RichOngoingContentModel
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel
import com.android.systemui.statusbar.policy.InflatedSmartReplyState
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder
import com.android.systemui.statusbar.policy.SmartReplyStateInflater
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.DisposableHandle
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@EnableFlags(NotificationRowContentBinderRefactor.FLAG_NAME)
class NotificationRowContentBinderImplTest : SysuiTestCase() {
    private lateinit var notificationInflater: NotificationRowContentBinderImpl
    private lateinit var builder: Notification.Builder
    private lateinit var row: ExpandableNotificationRow
    private lateinit var testHelper: NotificationTestHelper

    private val cache: NotifRemoteViewCache = mock()
    private val layoutInflaterFactoryProvider =
        object : NotifLayoutInflaterFactory.Provider {
            override fun provide(
                row: ExpandableNotificationRow,
                layoutType: Int
            ): NotifLayoutInflaterFactory = mock()
        }
    private val smartReplyStateInflater: SmartReplyStateInflater =
        object : SmartReplyStateInflater {
            private val inflatedSmartReplyState: InflatedSmartReplyState = mock()
            private val inflatedSmartReplies: InflatedSmartReplyViewHolder = mock()

            override fun inflateSmartReplyViewHolder(
                sysuiContext: Context,
                notifPackageContext: Context,
                entry: NotificationEntry,
                existingSmartReplyState: InflatedSmartReplyState?,
                newSmartReplyState: InflatedSmartReplyState
            ): InflatedSmartReplyViewHolder {
                return inflatedSmartReplies
            }

            override fun inflateSmartReplyState(entry: NotificationEntry): InflatedSmartReplyState {
                return inflatedSmartReplyState
            }
        }

    private var fakeRonContentModel: RichOngoingContentModel? = null
    private val fakeRonExtractor =
        object : RichOngoingNotificationContentExtractor {
            override fun extractContentModel(
                entry: NotificationEntry,
                builder: Notification.Builder,
                systemUIContext: Context,
                packageContext: Context
            ): RichOngoingContentModel? = fakeRonContentModel
        }

    private var fakeRonViewHolder: InflatedContentViewHolder? = null
    private val fakeRonViewInflater =
        spy(
            object : RichOngoingNotificationViewInflater {
                override fun inflateView(
                    contentModel: RichOngoingContentModel,
                    existingView: View?,
                    entry: NotificationEntry,
                    systemUiContext: Context,
                    parentView: ViewGroup
                ): InflatedContentViewHolder? = fakeRonViewHolder
            }
        )

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        builder =
            Notification.Builder(mContext, "no-id")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setStyle(Notification.BigTextStyle().bigText("big text"))
        testHelper = NotificationTestHelper(mContext, mDependency)
        row = spy(testHelper.createRow(builder.build()))
        notificationInflater =
            NotificationRowContentBinderImpl(
                cache,
                mock(),
                mock<ConversationNotificationProcessor>(),
                fakeRonExtractor,
                fakeRonViewInflater,
                mock(),
                smartReplyStateInflater,
                layoutInflaterFactoryProvider,
                mock<HeadsUpStyleProvider>(),
                mock()
            )
    }

    @Test
    fun testIncreasedHeadsUpBeingUsed() {
        val params = BindParams()
        params.usesIncreasedHeadsUpHeight = true
        val builder = spy(builder)
        notificationInflater.inflateNotificationViews(
            row.entry,
            row,
            params,
            true /* inflateSynchronously */,
            FLAG_CONTENT_VIEW_ALL,
            builder,
            mContext,
            smartReplyStateInflater
        )
        verify(builder).createHeadsUpContentView(true)
    }

    @Test
    fun testIncreasedHeightBeingUsed() {
        val params = BindParams()
        params.usesIncreasedHeight = true
        val builder = spy(builder)
        notificationInflater.inflateNotificationViews(
            row.entry,
            row,
            params,
            true /* inflateSynchronously */,
            FLAG_CONTENT_VIEW_ALL,
            builder,
            mContext,
            smartReplyStateInflater
        )
        verify(builder).createContentView(true)
    }

    @Test
    fun testInflationCallsUpdated() {
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_ALL, row)
        verify(row).onNotificationUpdated()
    }

    @Test
    fun testInflationOnlyInflatesSetFlags() {
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_HEADS_UP, row)
        Assert.assertNotNull(row.privateLayout.headsUpChild)
        verify(row).onNotificationUpdated()
    }

    @Test
    fun testInflationThrowsErrorDoesntCallUpdated() {
        row.privateLayout.removeAllViews()
        row.entry.sbn.notification.contentView =
            RemoteViews(mContext.packageName, R.layout.status_bar)
        inflateAndWait(
            true /* expectingException */,
            notificationInflater,
            FLAG_CONTENT_VIEW_ALL,
            row
        )
        Assert.assertTrue(row.privateLayout.childCount == 0)
        verify(row, times(0)).onNotificationUpdated()
    }

    @Test
    fun testAsyncTaskRemoved() {
        row.entry.abortTask()
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_ALL, row)
        verify(row).onNotificationUpdated()
    }

    @Test
    fun testRemovedNotInflated() {
        row.setRemoved()
        notificationInflater.setInflateSynchronously(true)
        notificationInflater.bindContent(
            row.entry,
            row,
            FLAG_CONTENT_VIEW_ALL,
            BindParams(),
            false /* forceInflate */,
            null /* callback */
        )
        Assert.assertNull(row.entry.runningTask)
    }

    @Test
    @Ignore("b/345418902")
    fun testInflationIsRetriedIfAsyncFails() {
        val headsUpStatusBarModel = HeadsUpStatusBarModel("private", "public")
        val result =
            NotificationRowContentBinderImpl.InflationProgress(
                packageContext = mContext,
                remoteViews = NewRemoteViews(),
                contentModel = NotificationContentModel(headsUpStatusBarModel)
            )
        val countDownLatch = CountDownLatch(1)
        NotificationRowContentBinderImpl.applyRemoteView(
            AsyncTask.SERIAL_EXECUTOR,
            inflateSynchronously = false,
            isMinimized = false,
            result = result,
            reInflateFlags = FLAG_CONTENT_VIEW_EXPANDED,
            inflationId = 0,
            remoteViewCache = mock(),
            entry = row.entry,
            row = row,
            isNewView = true, /* isNewView */
            remoteViewClickHandler = { _, _, _ -> true },
            callback =
                object : InflationCallback {
                    override fun handleInflationException(entry: NotificationEntry, e: Exception) {
                        countDownLatch.countDown()
                        throw RuntimeException("No Exception expected")
                    }

                    override fun onAsyncInflationFinished(entry: NotificationEntry) {
                        countDownLatch.countDown()
                    }
                },
            parentLayout = row.privateLayout,
            existingView = null,
            existingWrapper = null,
            runningInflations = HashMap(),
            applyCallback =
                object : NotificationRowContentBinderImpl.ApplyCallback() {
                    override fun setResultView(v: View) {}

                    override val remoteView: RemoteViews
                        get() =
                            AsyncFailRemoteView(
                                mContext.packageName,
                                com.android.systemui.tests.R.layout.custom_view_dark
                            )
                },
            logger = mock(),
        )
        Assert.assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun doesntReapplyDisallowedRemoteView() {
        builder.setStyle(Notification.MediaStyle())
        val mediaView = builder.createContentView()
        builder.setStyle(Notification.DecoratedCustomViewStyle())
        builder.setCustomContentView(
            RemoteViews(context.packageName, com.android.systemui.tests.R.layout.custom_view_dark)
        )
        val decoratedMediaView = builder.createContentView()
        Assert.assertFalse(
            "The decorated media style doesn't allow a view to be reapplied!",
            NotificationRowContentBinderImpl.canReapplyRemoteView(mediaView, decoratedMediaView)
        )
    }

    @Test
    @Ignore("b/345418902")
    fun testUsesSameViewWhenCachedPossibleToReuse() {
        // GIVEN a cached view.
        val contractedRemoteView = builder.createContentView()
        whenever(cache.hasCachedView(row.entry, FLAG_CONTENT_VIEW_CONTRACTED)).thenReturn(true)
        whenever(cache.getCachedView(row.entry, FLAG_CONTENT_VIEW_CONTRACTED))
            .thenReturn(contractedRemoteView)

        // GIVEN existing bound view with same layout id.
        val view = contractedRemoteView.apply(mContext, null /* parent */)
        row.privateLayout.setContractedChild(view)

        // WHEN inflater inflates
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)

        // THEN the view should be re-used
        Assert.assertEquals(
            "Binder inflated a new view even though the old one was cached and usable.",
            view,
            row.privateLayout.contractedChild
        )
    }

    @Test
    fun testInflatesNewViewWhenCachedNotPossibleToReuse() {
        // GIVEN a cached remote view.
        val contractedRemoteView = builder.createHeadsUpContentView()
        whenever(cache.hasCachedView(row.entry, FLAG_CONTENT_VIEW_CONTRACTED)).thenReturn(true)
        whenever(cache.getCachedView(row.entry, FLAG_CONTENT_VIEW_CONTRACTED))
            .thenReturn(contractedRemoteView)

        // GIVEN existing bound view with different layout id.
        val view: View = TextView(mContext)
        row.privateLayout.setContractedChild(view)

        // WHEN inflater inflates
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)

        // THEN the view should be a new view
        Assert.assertNotEquals(
            "Binder (somehow) used the same view when inflating.",
            view,
            row.privateLayout.contractedChild
        )
    }

    @Test
    fun testInflationCachesCreatedRemoteView() {
        // WHEN inflater inflates
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)

        // THEN inflater informs cache of the new remote view
        verify(cache).putCachedView(eq(row.entry), eq(FLAG_CONTENT_VIEW_CONTRACTED), any())
    }

    @Test
    fun testUnbindRemovesCachedRemoteView() {
        // WHEN inflated unbinds content
        notificationInflater.unbindContent(row.entry, row, FLAG_CONTENT_VIEW_HEADS_UP)

        // THEN inflated informs cache to remove remote view
        verify(cache).removeCachedView(eq(row.entry), eq(FLAG_CONTENT_VIEW_HEADS_UP))
    }

    @Test
    fun testRonModelRequiredForRonView() {
        fakeRonContentModel = null
        val ronView = View(context)
        fakeRonViewHolder = InflatedContentViewHolder(view = ronView, binder = mock())
        // WHEN inflater inflates
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)
        verify(fakeRonViewInflater, never()).inflateView(any(), any(), any(), any(), any())
    }

    @Test
    fun testRonModelTriggersInflationOfRonView() {
        val mockRonModel = mock<TimerContentModel>()
        val ronView = View(context)
        val mockBinder = mock<DeferredContentViewBinder>()

        val entry = row.entry
        val privateLayout = row.privateLayout

        fakeRonContentModel = mockRonModel
        fakeRonViewHolder = InflatedContentViewHolder(view = ronView, binder = mockBinder)
        // WHEN inflater inflates
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)
        // VERIFY that the inflater is invoked
        verify(fakeRonViewInflater)
            .inflateView(eq(mockRonModel), any(), eq(entry), any(), eq(privateLayout))
        assertThat(row.privateLayout.contractedChild).isSameInstanceAs(ronView)
        verify(mockBinder).setupContentViewBinder()
    }

    @Test
    fun ronViewAppliesElementsInOrder() {
        val oldHandle = mock<DisposableHandle>()
        val mockRonModel = mock<TimerContentModel>()
        val ronView = View(context)
        val mockBinder = mock<DeferredContentViewBinder>()

        row.privateLayout.mContractedBinderHandle = oldHandle
        val entry = spy(row.entry)
        row.entry = entry
        val privateLayout = spy(row.privateLayout)
        row.privateLayout = privateLayout

        fakeRonContentModel = mockRonModel
        fakeRonViewHolder = InflatedContentViewHolder(view = ronView, binder = mockBinder)
        // WHEN inflater inflates
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)

        // Validate that these 4 steps happen in this precise order
        inOrder(oldHandle, entry, privateLayout, mockBinder) {
            verify(oldHandle).dispose()
            verify(entry).setContentModel(argThat { richOngoingContentModel === mockRonModel })
            verify(privateLayout).setContractedChild(eq(ronView))
            verify(mockBinder).setupContentViewBinder()
        }
    }

    @Test
    fun testRonNotReinflating() {
        val handle0 = mock<DisposableHandle>()
        val handle1 = mock<DisposableHandle>()
        val ronView = View(context)
        val mockRonModel1 = mock<TimerContentModel>()
        val mockRonModel2 = mock<TimerContentModel>()
        val mockBinder1 = mock<DeferredContentViewBinder>()
        doReturn(handle1).whenever(mockBinder1).setupContentViewBinder()

        row.privateLayout.mContractedBinderHandle = handle0
        val entry = spy(row.entry)
        row.entry = entry
        val privateLayout = spy(row.privateLayout)
        row.privateLayout = privateLayout

        // WHEN inflater inflates both a model and a view
        fakeRonContentModel = mockRonModel1
        fakeRonViewHolder = InflatedContentViewHolder(view = ronView, binder = mockBinder1)
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)

        // Validate that these 4 steps happen in this precise order
        inOrder(handle0, entry, privateLayout, mockBinder1, handle1) {
            verify(handle0).dispose()
            verify(entry).setContentModel(argThat { richOngoingContentModel === mockRonModel1 })
            verify(privateLayout).setContractedChild(eq(ronView))
            verify(mockBinder1).setupContentViewBinder()
            verify(handle1, never()).dispose()
        }

        clearInvocations(handle0, entry, privateLayout, mockBinder1, handle1)

        // THEN when the inflater inflates just a model
        fakeRonContentModel = mockRonModel2
        fakeRonViewHolder = null
        inflateAndWait(notificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, row)

        // Validate that for reinflation, the only thing we do us update the model
        verify(handle1, never()).dispose()
        verify(entry).setContentModel(argThat { richOngoingContentModel === mockRonModel2 })
        verify(privateLayout, never()).setContractedChild(any())
        verify(mockBinder1, never()).setupContentViewBinder()
        verify(handle1, never()).dispose()
    }

    @Test
    fun testNotificationViewHeightTooSmallFailsValidation() {
        val validationError =
            getValidationError(
                measuredHeightDp = 5f,
                targetSdk = Build.VERSION_CODES.R,
                contentView = mock(),
            )
        Assert.assertNotNull(validationError)
    }

    @Test
    fun testNotificationViewHeightPassesForNewerSDK() {
        val validationError =
            getValidationError(
                measuredHeightDp = 5f,
                targetSdk = Build.VERSION_CODES.S,
                contentView = mock(),
            )
        Assert.assertNull(validationError)
    }

    @Test
    fun testNotificationViewHeightPassesForTemplatedViews() {
        val validationError =
            getValidationError(
                measuredHeightDp = 5f,
                targetSdk = Build.VERSION_CODES.R,
                contentView = null,
            )
        Assert.assertNull(validationError)
    }

    @Test
    fun testNotificationViewPassesValidation() {
        val validationError =
            getValidationError(
                measuredHeightDp = 20f,
                targetSdk = Build.VERSION_CODES.R,
                contentView = mock(),
            )
        Assert.assertNull(validationError)
    }

    private fun getValidationError(
        measuredHeightDp: Float,
        targetSdk: Int,
        contentView: RemoteViews?
    ): String? {
        val view: View = mock()
        whenever(view.measuredHeight)
            .thenReturn(
                TypedValue.applyDimension(
                        COMPLEX_UNIT_SP,
                        measuredHeightDp,
                        mContext.resources.displayMetrics
                    )
                    .toInt()
            )
        row.entry.targetSdk = targetSdk
        row.entry.sbn.notification.contentView = contentView
        return NotificationRowContentBinderImpl.isValidView(view, row.entry, mContext.resources)
    }

    @Test
    fun testInvalidNotificationDoesNotInvokeCallback() {
        row.privateLayout.removeAllViews()
        row.entry.sbn.notification.contentView =
            RemoteViews(
                mContext.packageName,
                com.android.systemui.tests.R.layout.invalid_notification_height
            )
        inflateAndWait(true, notificationInflater, FLAG_CONTENT_VIEW_ALL, row)
        Assert.assertEquals(0, row.privateLayout.childCount.toLong())
        verify(row, times(0)).onNotificationUpdated()
    }

    private class ExceptionHolder {
        var exception: Exception? = null
    }

    private class AsyncFailRemoteView(packageName: String?, layoutId: Int) :
        RemoteViews(packageName, layoutId) {

        override fun apply(context: Context, parent: ViewGroup): View {
            return super.apply(context, parent)
        }

        override fun applyAsync(
            context: Context,
            parent: ViewGroup,
            executor: Executor,
            listener: OnViewAppliedListener,
            handler: InteractionHandler?
        ): CancellationSignal {
            executor.execute { listener.onError(RuntimeException("Failed to inflate async")) }
            return CancellationSignal()
        }

        override fun applyAsync(
            context: Context,
            parent: ViewGroup,
            executor: Executor,
            listener: OnViewAppliedListener
        ): CancellationSignal {
            return applyAsync(context, parent, executor, listener, null)
        }
    }

    companion object {
        private fun inflateAndWait(
            inflater: NotificationRowContentBinderImpl,
            @InflationFlag contentToInflate: Int,
            row: ExpandableNotificationRow
        ) {
            inflateAndWait(false /* expectingException */, inflater, contentToInflate, row)
        }

        private fun inflateAndWait(
            expectingException: Boolean,
            inflater: NotificationRowContentBinderImpl,
            @InflationFlag contentToInflate: Int,
            row: ExpandableNotificationRow,
        ) {
            val countDownLatch = CountDownLatch(1)
            val exceptionHolder = ExceptionHolder()
            inflater.setInflateSynchronously(true)
            val callback: InflationCallback =
                object : InflationCallback {
                    override fun handleInflationException(entry: NotificationEntry, e: Exception) {
                        if (!expectingException) {
                            exceptionHolder.exception = e
                        }
                        countDownLatch.countDown()
                    }

                    override fun onAsyncInflationFinished(entry: NotificationEntry) {
                        if (expectingException) {
                            exceptionHolder.exception =
                                RuntimeException(
                                    "Inflation finished even though there should be an error"
                                )
                        }
                        countDownLatch.countDown()
                    }
                }
            inflater.bindContent(
                row.entry,
                row,
                contentToInflate,
                BindParams(),
                false /* forceInflate */,
                callback /* callback */
            )
            Assert.assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS))
            exceptionHolder.exception?.let { throw it }
        }
    }
}
