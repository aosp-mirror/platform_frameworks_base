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

package com.android.systemui.statusbar.notification.row

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.provider.DeviceConfig
import androidx.core.os.bundleOf
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.logging.MetricsLogger
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.TestableDependency
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.DevicePolicyManagerWrapper
import com.android.systemui.shared.system.PackageManagerWrapper
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.RankingBuilder
import com.android.systemui.statusbar.SmartReplyController
import com.android.systemui.statusbar.notification.ColorUpdateLogger
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManagerImpl
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManagerImpl
import com.android.systemui.statusbar.notification.icon.IconBuilder
import com.android.systemui.statusbar.notification.icon.IconManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow.CoordinateOnClickListener
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow.ExpandableNotificationRowLogger
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow.OnExpandClickListener
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.statusbar.notification.row.shared.NotificationRowContentBinderRefactor
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerLogger
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.SmartActionInflaterImpl
import com.android.systemui.statusbar.policy.SmartReplyConstants
import com.android.systemui.statusbar.policy.SmartReplyInflaterImpl
import com.android.systemui.statusbar.policy.SmartReplyStateInflaterImpl
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent
import com.android.systemui.util.Assert.runWithCurrentThreadAsMainThread
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.wmshell.BubblesManager
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class ExpandableNotificationRowBuilder(
    private val context: Context,
    dependency: TestableDependency,
    private val featureFlags: FakeFeatureFlagsClassic = FakeFeatureFlagsClassic()
) {

    private val mMockLogger: ExpandableNotificationRowLogger
    private val mStatusBarStateController: StatusBarStateController
    private val mKeyguardBypassController: KeyguardBypassController
    private val mGroupMembershipManager: GroupMembershipManager
    private val mGroupExpansionManager: GroupExpansionManager
    private val mHeadsUpManager: HeadsUpManager
    private val mIconManager: IconManager
    private val mContentBinder: NotificationRowContentBinder
    private val mBindStage: RowContentBindStage
    private val mBindPipeline: NotifBindPipeline
    private val mBindPipelineEntryListener: NotifCollectionListener
    private val mPeopleNotificationIdentifier: PeopleNotificationIdentifier
    private val mOnUserInteractionCallback: OnUserInteractionCallback
    private val mDismissibilityProvider: NotificationDismissibilityProvider
    private val mSmartReplyController: SmartReplyController
    private val mSmartReplyConstants: SmartReplyConstants
    private val mTestScope: TestScope = TestScope()
    private val mBgCoroutineContext = mTestScope.backgroundScope.coroutineContext
    private val mMainCoroutineContext = mTestScope.coroutineContext
    private val mFakeSystemClock = FakeSystemClock()
    private val mMainExecutor = FakeExecutor(mFakeSystemClock)

    init {
        featureFlags.setDefault(Flags.ENABLE_NOTIFICATIONS_SIMULATE_SLOW_MEASURE)
        featureFlags.setDefault(Flags.BIGPICTURE_NOTIFICATION_LAZY_LOADING)

        dependency.injectTestDependency(FeatureFlags::class.java, featureFlags)
        dependency.injectMockDependency(NotificationMediaManager::class.java)
        dependency.injectMockDependency(NotificationShadeWindowController::class.java)
        dependency.injectMockDependency(MediaOutputDialogManager::class.java)

        mMockLogger = Mockito.mock(ExpandableNotificationRowLogger::class.java)
        mStatusBarStateController = Mockito.mock(StatusBarStateController::class.java)
        mKeyguardBypassController = Mockito.mock(KeyguardBypassController::class.java)
        mGroupMembershipManager = GroupMembershipManagerImpl()
        mSmartReplyController = Mockito.mock(SmartReplyController::class.java)

        val dumpManager = DumpManager()
        mGroupExpansionManager = GroupExpansionManagerImpl(dumpManager, mGroupMembershipManager)
        mHeadsUpManager = Mockito.mock(HeadsUpManager::class.java)
        mIconManager =
            IconManager(
                Mockito.mock(CommonNotifCollection::class.java),
                Mockito.mock(LauncherApps::class.java),
                IconBuilder(context),
                mTestScope,
                mBgCoroutineContext,
                mMainCoroutineContext,
            )

        mSmartReplyConstants =
            SmartReplyConstants(
                /* mainExecutor = */ mMainExecutor,
                /* context = */ context,
                /* deviceConfig = */ DeviceConfigProxyFake().apply {
                    setProperty(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SSIN_SHOW_IN_HEADS_UP,
                        "true",
                        true
                    )
                    setProperty(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SSIN_ENABLED,
                        "true",
                        true
                    )
                    setProperty(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SSIN_REQUIRES_TARGETING_P,
                        "false",
                        true
                    )
                }
            )
        val remoteViewsFactories = getNotifRemoteViewsFactoryContainer(featureFlags)
        val remoteInputManager = Mockito.mock(NotificationRemoteInputManager::class.java)
        val smartReplyStateInflater =
            SmartReplyStateInflaterImpl(
                constants = mSmartReplyConstants,
                activityManagerWrapper = ActivityManagerWrapper.getInstance(),
                packageManagerWrapper = PackageManagerWrapper.getInstance(),
                devicePolicyManagerWrapper = DevicePolicyManagerWrapper.getInstance(),
                smartRepliesInflater =
                    SmartReplyInflaterImpl(
                        constants = mSmartReplyConstants,
                        keyguardDismissUtil = mock(),
                        remoteInputManager = remoteInputManager,
                        smartReplyController = mSmartReplyController,
                        context = context
                    ),
                smartActionsInflater =
                    SmartActionInflaterImpl(
                        constants = mSmartReplyConstants,
                        activityStarter = mock(),
                        smartReplyController = mSmartReplyController,
                        headsUpManager = mHeadsUpManager
                    )
            )
        val notifLayoutInflaterFactoryProvider =
            object : NotifLayoutInflaterFactory.Provider {
                override fun provide(
                    row: ExpandableNotificationRow,
                    layoutType: Int
                ): NotifLayoutInflaterFactory =
                    NotifLayoutInflaterFactory(row, layoutType, remoteViewsFactories)
            }
        val conversationProcessor =
            ConversationNotificationProcessor(
                mock(),
                mock(),
            )
        mContentBinder =
            if (NotificationRowContentBinderRefactor.isEnabled)
                NotificationRowContentBinderImpl(
                    mock(),
                    remoteInputManager,
                    conversationProcessor,
                    mock(),
                    mock(),
                    smartReplyStateInflater,
                    notifLayoutInflaterFactoryProvider,
                    mock(),
                    mock(),
                )
            else
                NotificationContentInflater(
                    mock(),
                    remoteInputManager,
                    conversationProcessor,
                    mock(),
                    mock(),
                    smartReplyStateInflater,
                    notifLayoutInflaterFactoryProvider,
                    mock(),
                    mock(),
                )
        mContentBinder.setInflateSynchronously(true)
        mBindStage =
            RowContentBindStage(
                mContentBinder,
                mock(),
                mock(),
            )

        val collection = Mockito.mock(CommonNotifCollection::class.java)

        mBindPipeline =
            NotifBindPipeline(
                collection,
                Mockito.mock(NotifBindPipelineLogger::class.java),
                NotificationEntryProcessorFactoryExecutorImpl(mMainExecutor),
            )
        mBindPipeline.setStage(mBindStage)

        val collectionListenerCaptor = ArgumentCaptor.forClass(NotifCollectionListener::class.java)
        Mockito.verify(collection).addCollectionListener(collectionListenerCaptor.capture())
        mBindPipelineEntryListener = collectionListenerCaptor.value
        mPeopleNotificationIdentifier = Mockito.mock(PeopleNotificationIdentifier::class.java)
        mOnUserInteractionCallback = Mockito.mock(OnUserInteractionCallback::class.java)
        mDismissibilityProvider = Mockito.mock(NotificationDismissibilityProvider::class.java)
        val mFutureDismissalRunnable = Mockito.mock(Runnable::class.java)
        whenever(
                mOnUserInteractionCallback.registerFutureDismissal(
                    ArgumentMatchers.any(),
                    ArgumentMatchers.anyInt()
                )
            )
            .thenReturn(mFutureDismissalRunnable)
    }

    private fun getNotifRemoteViewsFactoryContainer(
        featureFlags: FeatureFlags,
    ): NotifRemoteViewsFactoryContainer {
        return NotifRemoteViewsFactoryContainerImpl(
            featureFlags,
            PrecomputedTextViewFactory(),
            BigPictureLayoutInflaterFactory(),
            NotificationOptimizedLinearLayoutFactory(),
            { Mockito.mock(NotificationViewFlipperFactory::class.java) },
        )
    }

    fun createRow(notification: Notification): ExpandableNotificationRow {
        val channel =
            NotificationChannel(
                notification.channelId,
                notification.channelId,
                NotificationManager.IMPORTANCE_DEFAULT
            )
        channel.isBlockable = true
        val entry =
            NotificationEntryBuilder()
                .setPkg(PKG)
                .setOpPkg(PKG)
                .setId(123321)
                .setUid(UID)
                .setInitialPid(2000)
                .setNotification(notification)
                .setUser(USER_HANDLE)
                .setPostTime(System.currentTimeMillis())
                .setChannel(channel)
                .build()

        // it is for mitigating Rank building process.
        if (notification.isConversationStyleNotification) {
            val rb = RankingBuilder(entry.ranking)
            rb.setIsConversation(true)
            entry.ranking = rb.build()
        }

        return generateRow(entry, FLAG_CONTENT_VIEW_ALL)
    }

    private fun generateRow(
        entry: NotificationEntry,
        @InflationFlag extraInflationFlags: Int
    ): ExpandableNotificationRow {
        // NOTE: This flag is read when the ExpandableNotificationRow is inflated, so it needs to be
        //  set, but we do not want to override an existing value that is needed by a specific test.

        val rowFuture: SettableFuture<ExpandableNotificationRow> = SettableFuture.create()
        val rowInflaterTask =
            RowInflaterTask(mFakeSystemClock, Mockito.mock(RowInflaterTaskLogger::class.java))
        rowInflaterTask.inflate(context, null, entry, MoreExecutors.directExecutor()) { inflatedRow
            ->
            rowFuture.set(inflatedRow)
        }
        val row = rowFuture.get(1, TimeUnit.SECONDS)

        entry.row = row
        mIconManager.createIcons(entry)
        mBindPipelineEntryListener.onEntryInit(entry)
        mBindPipeline.manageRow(entry, row)
        row.initialize(
            entry,
            Mockito.mock(RemoteInputViewSubcomponent.Factory::class.java),
            APP_NAME,
            entry.key,
            mMockLogger,
            mKeyguardBypassController,
            mGroupMembershipManager,
            mGroupExpansionManager,
            mHeadsUpManager,
            mBindStage,
            Mockito.mock(OnExpandClickListener::class.java),
            Mockito.mock(CoordinateOnClickListener::class.java),
            FalsingManagerFake(),
            mStatusBarStateController,
            mPeopleNotificationIdentifier,
            mOnUserInteractionCallback,
            Optional.of(Mockito.mock(BubblesManager::class.java)),
            Mockito.mock(NotificationGutsManager::class.java),
            mDismissibilityProvider,
            Mockito.mock(MetricsLogger::class.java),
            Mockito.mock(NotificationChildrenContainerLogger::class.java),
            Mockito.mock(ColorUpdateLogger::class.java),
            mSmartReplyConstants,
            mSmartReplyController,
            featureFlags,
            Mockito.mock(IStatusBarService::class.java)
        )
        row.setAboveShelfChangedListener { aboveShelf: Boolean -> }
        mBindStage.getStageParams(entry).requireContentViews(extraInflationFlags)
        inflateAndWait(entry)
        return row
    }

    private fun inflateAndWait(entry: NotificationEntry) {
        val countDownLatch = CountDownLatch(1)
        mBindStage.requestRebind(entry) { en: NotificationEntry? -> countDownLatch.countDown() }
        runWithCurrentThreadAsMainThread(mMainExecutor::runAllReady)
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS))
    }

    companion object {
        private const val APP_NAME = "appName"
        private const val PKG = "com.android.systemui"
        private const val UID = 1000
        private val USER_HANDLE = UserHandle.of(ActivityManager.getCurrentUser())

        private const val IS_CONVERSATION_FLAG = "test.isConversation"

        private val Notification.isConversationStyleNotification
            get() = extras.getBoolean(IS_CONVERSATION_FLAG, false)

        fun markAsConversation(builder: Notification.Builder) {
            builder.addExtras(bundleOf(IS_CONVERSATION_FLAG to true))
        }
    }
}
