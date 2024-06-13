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

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.AsyncTask
import android.os.Build
import android.os.CancellationSignal
import android.os.Trace
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.NotificationHeaderView
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.RemoteViews.InteractionHandler
import android.widget.RemoteViews.OnViewAppliedListener
import com.android.app.tracing.TraceUtils
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.widget.ImageMessageConsumer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.NotifInflation
import com.android.systemui.res.R
import com.android.systemui.statusbar.InflationTask
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor
import com.android.systemui.statusbar.notification.InflationException
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_CONTRACTED
import com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_EXPANDED
import com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_HEADSUP
import com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_SINGLELINE
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.BindParams
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_GROUP_SUMMARY_HEADER
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationCallback
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.shared.HeadsUpStatusBarModel
import com.android.systemui.statusbar.notification.row.shared.NewRemoteViews
import com.android.systemui.statusbar.notification.row.shared.NotificationContentModel
import com.android.systemui.statusbar.notification.row.shared.NotificationRowContentBinderRefactor
import com.android.systemui.statusbar.notification.row.shared.RichOngoingContentModel
import com.android.systemui.statusbar.notification.row.ui.viewbinder.SingleLineConversationViewBinder
import com.android.systemui.statusbar.notification.row.ui.viewbinder.SingleLineViewBinder
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer
import com.android.systemui.statusbar.policy.InflatedSmartReplyState
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder
import com.android.systemui.statusbar.policy.SmartReplyStateInflater
import com.android.systemui.util.Assert
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

/**
 * [NotificationRowContentBinderImpl] binds content to a [ExpandableNotificationRow] by
 * asynchronously building the content's [RemoteViews] and applying it to the row.
 */
@SysUISingleton
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
class NotificationRowContentBinderImpl
@Inject
constructor(
    private val remoteViewCache: NotifRemoteViewCache,
    private val remoteInputManager: NotificationRemoteInputManager,
    private val conversationProcessor: ConversationNotificationProcessor,
    private val ronExtractor: RichOngoingNotificationContentExtractor,
    @NotifInflation private val inflationExecutor: Executor,
    private val smartReplyStateInflater: SmartReplyStateInflater,
    private val notifLayoutInflaterFactoryProvider: NotifLayoutInflaterFactory.Provider,
    private val headsUpStyleProvider: HeadsUpStyleProvider,
    private val logger: NotificationRowContentBinderLogger
) : NotificationRowContentBinder {

    init {
        /* check if */ NotificationRowContentBinderRefactor.isUnexpectedlyInLegacyMode()
    }

    private var inflateSynchronously = false

    override fun bindContent(
        entry: NotificationEntry,
        row: ExpandableNotificationRow,
        @InflationFlag contentToBind: Int,
        bindParams: BindParams,
        forceInflate: Boolean,
        callback: InflationCallback?
    ) {
        if (row.isRemoved) {
            // We don't want to reinflate anything for removed notifications. Otherwise views might
            // be readded to the stack, leading to leaks. This may happen with low-priority groups
            // where the removal of already removed children can lead to a reinflation.
            logger.logNotBindingRowWasRemoved(entry)
            return
        }
        logger.logBinding(entry, contentToBind)
        val sbn: StatusBarNotification = entry.sbn

        // To check if the notification has inline image and preload inline image if necessary.
        row.imageResolver.preloadImages(sbn.notification)
        if (forceInflate) {
            remoteViewCache.clearCache(entry)
        }

        // Cancel any pending frees on any view we're trying to bind since we should be bound after.
        cancelContentViewFrees(row, contentToBind)
        val task =
            AsyncInflationTask(
                inflationExecutor,
                inflateSynchronously,
                /* reInflateFlags = */ contentToBind,
                remoteViewCache,
                entry,
                conversationProcessor,
                ronExtractor,
                row,
                bindParams.isMinimized,
                bindParams.usesIncreasedHeight,
                bindParams.usesIncreasedHeadsUpHeight,
                callback,
                remoteInputManager.remoteViewsOnClickHandler,
                /* isMediaFlagEnabled = */ smartReplyStateInflater,
                notifLayoutInflaterFactoryProvider,
                headsUpStyleProvider,
                logger
            )
        if (inflateSynchronously) {
            task.onPostExecute(task.doInBackground())
        } else {
            task.executeOnExecutor(inflationExecutor)
        }
    }

    @VisibleForTesting
    fun inflateNotificationViews(
        entry: NotificationEntry,
        row: ExpandableNotificationRow,
        bindParams: BindParams,
        inflateSynchronously: Boolean,
        @InflationFlag reInflateFlags: Int,
        builder: Notification.Builder,
        packageContext: Context,
        smartRepliesInflater: SmartReplyStateInflater
    ): InflationProgress {
        val systemUIContext = row.context
        val result =
            beginInflationAsync(
                reInflateFlags = reInflateFlags,
                entry = entry,
                builder = builder,
                isMinimized = bindParams.isMinimized,
                usesIncreasedHeight = bindParams.usesIncreasedHeight,
                usesIncreasedHeadsUpHeight = bindParams.usesIncreasedHeadsUpHeight,
                systemUIContext = systemUIContext,
                packageContext = packageContext,
                row = row,
                notifLayoutInflaterFactoryProvider = notifLayoutInflaterFactoryProvider,
                headsUpStyleProvider = headsUpStyleProvider,
                conversationProcessor = conversationProcessor,
                ronExtractor = ronExtractor,
                logger = logger,
            )
        inflateSmartReplyViews(
            result,
            reInflateFlags,
            entry,
            systemUIContext,
            packageContext,
            row.existingSmartReplyState,
            smartRepliesInflater,
            logger,
        )
        if (AsyncHybridViewInflation.isEnabled) {
            result.inflatedSingleLineView =
                result.contentModel.singleLineViewModel?.let { viewModel ->
                    SingleLineViewInflater.inflateSingleLineViewHolder(
                        viewModel.isConversation(),
                        reInflateFlags,
                        entry,
                        systemUIContext,
                        logger,
                    )
                }
        }
        apply(
            inflationExecutor,
            inflateSynchronously,
            bindParams.isMinimized,
            result,
            reInflateFlags,
            remoteViewCache,
            entry,
            row,
            remoteInputManager.remoteViewsOnClickHandler,
            /* callback= */ null,
            logger
        )
        return result
    }

    override fun cancelBind(entry: NotificationEntry, row: ExpandableNotificationRow): Boolean {
        val abortedTask: Boolean = entry.abortTask()
        if (abortedTask) {
            logger.logCancelBindAbortedTask(entry)
        }
        return abortedTask
    }

    @SuppressLint("WrongConstant")
    override fun unbindContent(
        entry: NotificationEntry,
        row: ExpandableNotificationRow,
        @InflationFlag contentToUnbind: Int
    ) {
        logger.logUnbinding(entry, contentToUnbind)
        var curFlag = 1
        var contentLeftToUnbind = contentToUnbind
        while (contentLeftToUnbind != 0) {
            if (contentLeftToUnbind and curFlag != 0) {
                freeNotificationView(entry, row, curFlag)
            }
            contentLeftToUnbind = contentLeftToUnbind and curFlag.inv()
            curFlag = curFlag shl 1
        }
    }

    /**
     * Frees the content view associated with the inflation flag as soon as the view is not showing.
     *
     * @param inflateFlag the flag corresponding to the content view which should be freed
     */
    private fun freeNotificationView(
        entry: NotificationEntry,
        row: ExpandableNotificationRow,
        @InflationFlag inflateFlag: Int
    ) {
        when (inflateFlag) {
            FLAG_CONTENT_VIEW_CONTRACTED ->
                row.privateLayout.performWhenContentInactive(VISIBLE_TYPE_CONTRACTED) {
                    row.privateLayout.setContractedChild(null)
                    remoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED)
                }
            FLAG_CONTENT_VIEW_EXPANDED ->
                row.privateLayout.performWhenContentInactive(VISIBLE_TYPE_EXPANDED) {
                    row.privateLayout.setExpandedChild(null)
                    remoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED)
                }
            FLAG_CONTENT_VIEW_HEADS_UP ->
                row.privateLayout.performWhenContentInactive(VISIBLE_TYPE_HEADSUP) {
                    row.privateLayout.setHeadsUpChild(null)
                    remoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP)
                    row.privateLayout.setHeadsUpInflatedSmartReplies(null)
                }
            FLAG_CONTENT_VIEW_PUBLIC ->
                row.publicLayout.performWhenContentInactive(VISIBLE_TYPE_CONTRACTED) {
                    row.publicLayout.setContractedChild(null)
                    remoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC)
                }
            FLAG_CONTENT_VIEW_SINGLE_LINE -> {
                if (AsyncHybridViewInflation.isEnabled) {
                    row.privateLayout.performWhenContentInactive(VISIBLE_TYPE_SINGLELINE) {
                        row.privateLayout.setSingleLineView(null)
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Cancel any pending content view frees from [.freeNotificationView] for the provided content
     * views.
     *
     * @param row top level notification row containing the content views
     * @param contentViews content views to cancel pending frees on
     */
    private fun cancelContentViewFrees(
        row: ExpandableNotificationRow,
        @InflationFlag contentViews: Int
    ) {
        if (contentViews and FLAG_CONTENT_VIEW_CONTRACTED != 0) {
            row.privateLayout.removeContentInactiveRunnable(VISIBLE_TYPE_CONTRACTED)
        }
        if (contentViews and FLAG_CONTENT_VIEW_EXPANDED != 0) {
            row.privateLayout.removeContentInactiveRunnable(VISIBLE_TYPE_EXPANDED)
        }
        if (contentViews and FLAG_CONTENT_VIEW_HEADS_UP != 0) {
            row.privateLayout.removeContentInactiveRunnable(VISIBLE_TYPE_HEADSUP)
        }
        if (contentViews and FLAG_CONTENT_VIEW_PUBLIC != 0) {
            row.publicLayout.removeContentInactiveRunnable(VISIBLE_TYPE_CONTRACTED)
        }
        if (
            AsyncHybridViewInflation.isEnabled &&
                contentViews and FLAG_CONTENT_VIEW_SINGLE_LINE != 0
        ) {
            row.privateLayout.removeContentInactiveRunnable(VISIBLE_TYPE_SINGLELINE)
        }
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    override fun setInflateSynchronously(inflateSynchronously: Boolean) {
        this.inflateSynchronously = inflateSynchronously
    }

    class AsyncInflationTask(
        private val inflationExecutor: Executor,
        private val inflateSynchronously: Boolean,
        @get:InflationFlag @get:VisibleForTesting @InflationFlag val reInflateFlags: Int,
        private val remoteViewCache: NotifRemoteViewCache,
        private val entry: NotificationEntry,
        private val conversationProcessor: ConversationNotificationProcessor,
        private val ronExtractor: RichOngoingNotificationContentExtractor,
        private val row: ExpandableNotificationRow,
        private val isMinimized: Boolean,
        private val usesIncreasedHeight: Boolean,
        private val usesIncreasedHeadsUpHeight: Boolean,
        private val callback: InflationCallback?,
        private val remoteViewClickHandler: InteractionHandler?,
        private val smartRepliesInflater: SmartReplyStateInflater,
        private val notifLayoutInflaterFactoryProvider: NotifLayoutInflaterFactory.Provider,
        private val headsUpStyleProvider: HeadsUpStyleProvider,
        private val logger: NotificationRowContentBinderLogger
    ) : AsyncTask<Void, Void, Result<InflationProgress>>(), InflationCallback, InflationTask {
        private val context: Context
            get() = row.context

        private var cancellationSignal: CancellationSignal? = null

        init {
            entry.setInflationTask(this)
        }

        private fun updateApplicationInfo(sbn: StatusBarNotification) {
            val packageName: String = sbn.packageName
            val userId: Int = UserHandle.getUserId(sbn.uid)
            val appInfo: ApplicationInfo
            try {
                // This method has an internal cache, so we don't need to add our own caching here.
                appInfo =
                    context.packageManager.getApplicationInfoAsUser(
                        packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        userId
                    )
            } catch (e: PackageManager.NameNotFoundException) {
                return
            }
            Notification.addFieldsFromContext(appInfo, sbn.notification)
        }

        override fun onPreExecute() {
            Trace.beginAsyncSection(ASYNC_TASK_TRACE_METHOD, System.identityHashCode(this))
        }

        public override fun doInBackground(vararg params: Void): Result<InflationProgress> {
            return TraceUtils.trace(
                "NotificationContentInflater.AsyncInflationTask#doInBackground"
            ) {
                try {
                    return@trace Result.success(doInBackgroundInternal())
                } catch (e: Exception) {
                    logger.logAsyncTaskException(entry, "inflating", e)
                    return@trace Result.failure(e)
                }
            }
        }

        private fun doInBackgroundInternal(): InflationProgress {
            val sbn: StatusBarNotification = entry.sbn
            // Ensure the ApplicationInfo is updated before a builder is recovered.
            updateApplicationInfo(sbn)
            val recoveredBuilder = Notification.Builder.recoverBuilder(context, sbn.notification)
            var packageContext: Context = sbn.getPackageContext(context)
            if (recoveredBuilder.usesTemplate()) {
                // For all of our templates, we want it to be RTL
                packageContext = RtlEnabledContext(packageContext)
            }
            val inflationProgress =
                beginInflationAsync(
                    reInflateFlags = reInflateFlags,
                    entry = entry,
                    builder = recoveredBuilder,
                    isMinimized = isMinimized,
                    usesIncreasedHeight = usesIncreasedHeight,
                    usesIncreasedHeadsUpHeight = usesIncreasedHeadsUpHeight,
                    systemUIContext = context,
                    packageContext = packageContext,
                    row = row,
                    notifLayoutInflaterFactoryProvider = notifLayoutInflaterFactoryProvider,
                    headsUpStyleProvider = headsUpStyleProvider,
                    conversationProcessor = conversationProcessor,
                    ronExtractor = ronExtractor,
                    logger = logger
                )
            logger.logAsyncTaskProgress(
                entry,
                "getting existing smart reply state (on wrong thread!)"
            )
            val previousSmartReplyState: InflatedSmartReplyState? = row.existingSmartReplyState
            logger.logAsyncTaskProgress(entry, "inflating smart reply views")
            inflateSmartReplyViews(
                /* result = */ inflationProgress,
                reInflateFlags,
                entry,
                context,
                packageContext,
                previousSmartReplyState,
                smartRepliesInflater,
                logger,
            )
            if (AsyncHybridViewInflation.isEnabled) {
                logger.logAsyncTaskProgress(entry, "inflating single line view")
                inflationProgress.inflatedSingleLineView =
                    inflationProgress.contentModel.singleLineViewModel?.let {
                        SingleLineViewInflater.inflateSingleLineViewHolder(
                            it.isConversation(),
                            reInflateFlags,
                            entry,
                            context,
                            logger
                        )
                    }
            }
            logger.logAsyncTaskProgress(entry, "getting row image resolver (on wrong thread!)")
            val imageResolver = row.imageResolver
            // wait for image resolver to finish preloading
            logger.logAsyncTaskProgress(entry, "waiting for preloaded images")
            imageResolver.waitForPreloadedImages(IMG_PRELOAD_TIMEOUT_MS)
            return inflationProgress
        }

        public override fun onPostExecute(result: Result<InflationProgress>) {
            Trace.endAsyncSection(ASYNC_TASK_TRACE_METHOD, System.identityHashCode(this))
            result
                .onSuccess { progress ->
                    // Logged in detail in apply.
                    cancellationSignal =
                        apply(
                            inflationExecutor,
                            inflateSynchronously,
                            isMinimized,
                            progress,
                            reInflateFlags,
                            remoteViewCache,
                            entry,
                            row,
                            remoteViewClickHandler,
                            this /* callback */,
                            logger
                        )
                }
                .onFailure { error -> handleError(error as Exception) }
        }

        override fun onCancelled(result: Result<InflationProgress>) {
            Trace.endAsyncSection(ASYNC_TASK_TRACE_METHOD, System.identityHashCode(this))
        }

        private fun handleError(e: Exception) {
            entry.onInflationTaskFinished()
            val sbn: StatusBarNotification = entry.sbn
            val ident: String = (sbn.packageName + "/0x" + Integer.toHexString(sbn.id))
            Log.e(TAG, "couldn't inflate view for notification $ident", e)
            callback?.handleInflationException(
                row.entry,
                InflationException("Couldn't inflate contentViews$e")
            )

            // Cancel any image loading tasks, not useful any more
            row.imageResolver.cancelRunningTasks()
        }

        override fun abort() {
            logger.logAsyncTaskProgress(entry, "cancelling inflate")
            cancel(/* mayInterruptIfRunning= */ true)
            if (cancellationSignal != null) {
                logger.logAsyncTaskProgress(entry, "cancelling apply")
                cancellationSignal!!.cancel()
            }
            logger.logAsyncTaskProgress(entry, "aborted")
        }

        override fun handleInflationException(entry: NotificationEntry, e: Exception) {
            handleError(e)
        }

        override fun onAsyncInflationFinished(entry: NotificationEntry) {
            this.entry.onInflationTaskFinished()
            row.onNotificationUpdated()
            callback?.onAsyncInflationFinished(this.entry)

            // Notify the resolver that the inflation task has finished,
            // try to purge unnecessary cached entries.
            row.imageResolver.purgeCache()

            // Cancel any image loading tasks that have not completed at this point
            row.imageResolver.cancelRunningTasks()
        }

        class RtlEnabledContext(packageContext: Context) : ContextWrapper(packageContext) {
            override fun getApplicationInfo(): ApplicationInfo {
                val applicationInfo = ApplicationInfo(super.getApplicationInfo())
                applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
                return applicationInfo
            }
        }

        companion object {
            private const val IMG_PRELOAD_TIMEOUT_MS = 1000L
        }
    }

    @VisibleForTesting
    class InflationProgress(
        @VisibleForTesting val packageContext: Context,
        val remoteViews: NewRemoteViews,
        val contentModel: NotificationContentModel,
    ) {

        var inflatedContentView: View? = null
        var inflatedHeadsUpView: View? = null
        var inflatedExpandedView: View? = null
        var inflatedPublicView: View? = null
        var inflatedGroupHeaderView: NotificationHeaderView? = null
        var inflatedMinimizedGroupHeaderView: NotificationHeaderView? = null
        var inflatedSmartReplyState: InflatedSmartReplyState? = null
        var expandedInflatedSmartReplies: InflatedSmartReplyViewHolder? = null
        var headsUpInflatedSmartReplies: InflatedSmartReplyViewHolder? = null

        // Inflated SingleLineView that lacks the UI State
        var inflatedSingleLineView: HybridNotificationView? = null
    }

    @VisibleForTesting
    abstract class ApplyCallback {
        abstract fun setResultView(v: View)

        abstract val remoteView: RemoteViews
    }

    companion object {
        const val TAG = "NotifContentInflater"

        private fun inflateSmartReplyViews(
            result: InflationProgress,
            @InflationFlag reInflateFlags: Int,
            entry: NotificationEntry,
            context: Context,
            packageContext: Context,
            previousSmartReplyState: InflatedSmartReplyState?,
            inflater: SmartReplyStateInflater,
            logger: NotificationRowContentBinderLogger
        ) {
            val inflateContracted =
                (reInflateFlags and FLAG_CONTENT_VIEW_CONTRACTED != 0 &&
                    result.remoteViews.contracted != null)
            val inflateExpanded =
                (reInflateFlags and FLAG_CONTENT_VIEW_EXPANDED != 0 &&
                    result.remoteViews.expanded != null)
            val inflateHeadsUp =
                (reInflateFlags and FLAG_CONTENT_VIEW_HEADS_UP != 0 &&
                    result.remoteViews.headsUp != null)

            if (inflateContracted || inflateExpanded || inflateHeadsUp) {
                logger.logAsyncTaskProgress(entry, "inflating contracted smart reply state")
                result.inflatedSmartReplyState = inflater.inflateSmartReplyState(entry)
            }
            if (inflateExpanded) {
                logger.logAsyncTaskProgress(entry, "inflating expanded smart reply state")
                result.expandedInflatedSmartReplies =
                    inflater.inflateSmartReplyViewHolder(
                        context,
                        packageContext,
                        entry,
                        previousSmartReplyState,
                        result.inflatedSmartReplyState!!
                    )
            }
            if (inflateHeadsUp) {
                logger.logAsyncTaskProgress(entry, "inflating heads up smart reply state")
                result.headsUpInflatedSmartReplies =
                    inflater.inflateSmartReplyViewHolder(
                        context,
                        packageContext,
                        entry,
                        previousSmartReplyState,
                        result.inflatedSmartReplyState!!
                    )
            }
        }

        private fun beginInflationAsync(
            @InflationFlag reInflateFlags: Int,
            entry: NotificationEntry,
            builder: Notification.Builder,
            isMinimized: Boolean,
            usesIncreasedHeight: Boolean,
            usesIncreasedHeadsUpHeight: Boolean,
            systemUIContext: Context,
            packageContext: Context,
            row: ExpandableNotificationRow,
            notifLayoutInflaterFactoryProvider: NotifLayoutInflaterFactory.Provider,
            headsUpStyleProvider: HeadsUpStyleProvider,
            conversationProcessor: ConversationNotificationProcessor,
            ronExtractor: RichOngoingNotificationContentExtractor,
            logger: NotificationRowContentBinderLogger
        ): InflationProgress {
            // process conversations and extract the messaging style
            val messagingStyle =
                if (entry.ranking.isConversation) {
                    conversationProcessor.processNotification(entry, builder, logger)
                } else null

            val richOngoingContentModel =
                if (reInflateFlags and CONTENT_VIEWS_TO_CREATE_RICH_ONGOING != 0) {
                    ronExtractor.extractContentModel(
                        entry = entry,
                        builder = builder,
                        systemUIContext = systemUIContext,
                        packageContext = packageContext
                    )
                } else null

            val remoteViewsFlags = getRemoteViewsFlags(reInflateFlags, richOngoingContentModel)

            val remoteViews =
                createRemoteViews(
                    reInflateFlags = remoteViewsFlags,
                    builder = builder,
                    isMinimized = isMinimized,
                    usesIncreasedHeight = usesIncreasedHeight,
                    usesIncreasedHeadsUpHeight = usesIncreasedHeadsUpHeight,
                    row = row,
                    notifLayoutInflaterFactoryProvider = notifLayoutInflaterFactoryProvider,
                    headsUpStyleProvider = headsUpStyleProvider,
                    logger = logger,
                )

            val singleLineViewModel =
                if (
                    AsyncHybridViewInflation.isEnabled &&
                        reInflateFlags and FLAG_CONTENT_VIEW_SINGLE_LINE != 0
                ) {
                    logger.logAsyncTaskProgress(entry, "inflating single line view model")
                    SingleLineViewInflater.inflateSingleLineViewModel(
                        notification = entry.sbn.notification,
                        messagingStyle = messagingStyle,
                        builder = builder,
                        systemUiContext = systemUIContext,
                    )
                } else null

            val headsUpStatusBarModel =
                HeadsUpStatusBarModel(
                    privateText = builder.getHeadsUpStatusBarText(/* publicMode= */ false),
                    publicText = builder.getHeadsUpStatusBarText(/* publicMode= */ true),
                )

            val contentModel =
                NotificationContentModel(
                    headsUpStatusBarModel = headsUpStatusBarModel,
                    singleLineViewModel = singleLineViewModel,
                    richOngoingContentModel = richOngoingContentModel,
                )

            return InflationProgress(
                packageContext = packageContext,
                remoteViews = remoteViews,
                contentModel = contentModel,
            )
        }

        private fun createRemoteViews(
            @InflationFlag reInflateFlags: Int,
            builder: Notification.Builder,
            isMinimized: Boolean,
            usesIncreasedHeight: Boolean,
            usesIncreasedHeadsUpHeight: Boolean,
            row: ExpandableNotificationRow,
            notifLayoutInflaterFactoryProvider: NotifLayoutInflaterFactory.Provider,
            headsUpStyleProvider: HeadsUpStyleProvider,
            logger: NotificationRowContentBinderLogger
        ): NewRemoteViews {
            return TraceUtils.trace("NotificationContentInflater.createRemoteViews") {
                val entryForLogging: NotificationEntry = row.entry
                val contracted =
                    if (reInflateFlags and FLAG_CONTENT_VIEW_CONTRACTED != 0) {
                        logger.logAsyncTaskProgress(
                            entryForLogging,
                            "creating contracted remote view"
                        )
                        createContentView(builder, isMinimized, usesIncreasedHeight)
                    } else null
                val expanded =
                    if (reInflateFlags and FLAG_CONTENT_VIEW_EXPANDED != 0) {
                        logger.logAsyncTaskProgress(
                            entryForLogging,
                            "creating expanded remote view"
                        )
                        createExpandedView(builder, isMinimized)
                    } else null
                val headsUp =
                    if (reInflateFlags and FLAG_CONTENT_VIEW_HEADS_UP != 0) {
                        logger.logAsyncTaskProgress(
                            entryForLogging,
                            "creating heads up remote view"
                        )
                        val isHeadsUpCompact = headsUpStyleProvider.shouldApplyCompactStyle()
                        if (isHeadsUpCompact) {
                            builder.createCompactHeadsUpContentView()
                        } else {
                            builder.createHeadsUpContentView(usesIncreasedHeadsUpHeight)
                        }
                    } else null
                val public =
                    if (reInflateFlags and FLAG_CONTENT_VIEW_PUBLIC != 0) {
                        logger.logAsyncTaskProgress(entryForLogging, "creating public remote view")
                        builder.makePublicContentView(isMinimized)
                    } else null
                val normalGroupHeader =
                    if (
                        AsyncGroupHeaderViewInflation.isEnabled &&
                            reInflateFlags and FLAG_GROUP_SUMMARY_HEADER != 0
                    ) {
                        logger.logAsyncTaskProgress(
                            entryForLogging,
                            "creating group summary remote view"
                        )
                        builder.makeNotificationGroupHeader()
                    } else null
                val minimizedGroupHeader =
                    if (
                        AsyncGroupHeaderViewInflation.isEnabled &&
                            reInflateFlags and FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER != 0
                    ) {
                        logger.logAsyncTaskProgress(
                            entryForLogging,
                            "creating low-priority group summary remote view"
                        )
                        builder.makeLowPriorityContentView(true /* useRegularSubtext */)
                    } else null
                NewRemoteViews(
                        contracted = contracted,
                        headsUp = headsUp,
                        expanded = expanded,
                        public = public,
                        normalGroupHeader = normalGroupHeader,
                        minimizedGroupHeader = minimizedGroupHeader
                    )
                    .withLayoutInflaterFactory(row, notifLayoutInflaterFactoryProvider)
            }
        }

        private fun NewRemoteViews.withLayoutInflaterFactory(
            row: ExpandableNotificationRow,
            provider: NotifLayoutInflaterFactory.Provider
        ): NewRemoteViews {
            contracted?.let {
                it.layoutInflaterFactory = provider.provide(row, FLAG_CONTENT_VIEW_CONTRACTED)
            }
            expanded?.let {
                it.layoutInflaterFactory = provider.provide(row, FLAG_CONTENT_VIEW_EXPANDED)
            }
            headsUp?.let {
                it.layoutInflaterFactory = provider.provide(row, FLAG_CONTENT_VIEW_HEADS_UP)
            }
            public?.let {
                it.layoutInflaterFactory = provider.provide(row, FLAG_CONTENT_VIEW_PUBLIC)
            }
            return this
        }

        private fun apply(
            inflationExecutor: Executor,
            inflateSynchronously: Boolean,
            isMinimized: Boolean,
            result: InflationProgress,
            @InflationFlag reInflateFlags: Int,
            remoteViewCache: NotifRemoteViewCache,
            entry: NotificationEntry,
            row: ExpandableNotificationRow,
            remoteViewClickHandler: InteractionHandler?,
            callback: InflationCallback?,
            logger: NotificationRowContentBinderLogger
        ): CancellationSignal {
            Trace.beginAsyncSection(APPLY_TRACE_METHOD, System.identityHashCode(row))
            val privateLayout = row.privateLayout
            val publicLayout = row.publicLayout
            val runningInflations = HashMap<Int, CancellationSignal>()
            var flag = FLAG_CONTENT_VIEW_CONTRACTED
            if (reInflateFlags and flag != 0 && result.remoteViews.contracted != null) {
                val isNewView =
                    !canReapplyRemoteView(
                        newView = result.remoteViews.contracted,
                        oldView = remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED)
                    )
                val applyCallback: ApplyCallback =
                    object : ApplyCallback() {
                        override fun setResultView(v: View) {
                            logger.logAsyncTaskProgress(entry, "contracted view applied")
                            result.inflatedContentView = v
                        }

                        override val remoteView: RemoteViews
                            get() = result.remoteViews.contracted
                    }
                logger.logAsyncTaskProgress(entry, "applying contracted view")
                applyRemoteView(
                    inflationExecutor = inflationExecutor,
                    inflateSynchronously = inflateSynchronously,
                    isMinimized = isMinimized,
                    result = result,
                    reInflateFlags = reInflateFlags,
                    inflationId = flag,
                    remoteViewCache = remoteViewCache,
                    entry = entry,
                    row = row,
                    isNewView = isNewView,
                    remoteViewClickHandler = remoteViewClickHandler,
                    callback = callback,
                    parentLayout = privateLayout,
                    existingView = privateLayout.contractedChild,
                    existingWrapper = privateLayout.getVisibleWrapper(VISIBLE_TYPE_CONTRACTED),
                    runningInflations = runningInflations,
                    applyCallback = applyCallback,
                    logger = logger
                )
            }
            flag = FLAG_CONTENT_VIEW_EXPANDED
            if (reInflateFlags and flag != 0 && result.remoteViews.expanded != null) {
                val isNewView =
                    !canReapplyRemoteView(
                        newView = result.remoteViews.expanded,
                        oldView = remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED)
                    )
                val applyCallback: ApplyCallback =
                    object : ApplyCallback() {
                        override fun setResultView(v: View) {
                            logger.logAsyncTaskProgress(entry, "expanded view applied")
                            result.inflatedExpandedView = v
                        }

                        override val remoteView: RemoteViews
                            get() = result.remoteViews.expanded
                    }
                logger.logAsyncTaskProgress(entry, "applying expanded view")
                applyRemoteView(
                    inflationExecutor = inflationExecutor,
                    inflateSynchronously = inflateSynchronously,
                    isMinimized = isMinimized,
                    result = result,
                    reInflateFlags = reInflateFlags,
                    inflationId = flag,
                    remoteViewCache = remoteViewCache,
                    entry = entry,
                    row = row,
                    isNewView = isNewView,
                    remoteViewClickHandler = remoteViewClickHandler,
                    callback = callback,
                    parentLayout = privateLayout,
                    existingView = privateLayout.expandedChild,
                    existingWrapper = privateLayout.getVisibleWrapper(VISIBLE_TYPE_EXPANDED),
                    runningInflations = runningInflations,
                    applyCallback = applyCallback,
                    logger = logger
                )
            }
            flag = FLAG_CONTENT_VIEW_HEADS_UP
            if (reInflateFlags and flag != 0 && result.remoteViews.headsUp != null) {
                val isNewView =
                    !canReapplyRemoteView(
                        newView = result.remoteViews.headsUp,
                        oldView = remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP)
                    )
                val applyCallback: ApplyCallback =
                    object : ApplyCallback() {
                        override fun setResultView(v: View) {
                            logger.logAsyncTaskProgress(entry, "heads up view applied")
                            result.inflatedHeadsUpView = v
                        }

                        override val remoteView: RemoteViews
                            get() = result.remoteViews.headsUp
                    }
                logger.logAsyncTaskProgress(entry, "applying heads up view")
                applyRemoteView(
                    inflationExecutor = inflationExecutor,
                    inflateSynchronously = inflateSynchronously,
                    isMinimized = isMinimized,
                    result = result,
                    reInflateFlags = reInflateFlags,
                    inflationId = flag,
                    remoteViewCache = remoteViewCache,
                    entry = entry,
                    row = row,
                    isNewView = isNewView,
                    remoteViewClickHandler = remoteViewClickHandler,
                    callback = callback,
                    parentLayout = privateLayout,
                    existingView = privateLayout.headsUpChild,
                    existingWrapper = privateLayout.getVisibleWrapper(VISIBLE_TYPE_HEADSUP),
                    runningInflations = runningInflations,
                    applyCallback = applyCallback,
                    logger = logger
                )
            }
            flag = FLAG_CONTENT_VIEW_PUBLIC
            if (reInflateFlags and flag != 0) {
                val isNewView =
                    !canReapplyRemoteView(
                        newView = result.remoteViews.public,
                        oldView = remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC)
                    )
                val applyCallback: ApplyCallback =
                    object : ApplyCallback() {
                        override fun setResultView(v: View) {
                            logger.logAsyncTaskProgress(entry, "public view applied")
                            result.inflatedPublicView = v
                        }

                        override val remoteView: RemoteViews
                            get() = result.remoteViews.public!!
                    }
                logger.logAsyncTaskProgress(entry, "applying public view")
                applyRemoteView(
                    inflationExecutor = inflationExecutor,
                    inflateSynchronously = inflateSynchronously,
                    isMinimized = isMinimized,
                    result = result,
                    reInflateFlags = reInflateFlags,
                    inflationId = flag,
                    remoteViewCache = remoteViewCache,
                    entry = entry,
                    row = row,
                    isNewView = isNewView,
                    remoteViewClickHandler = remoteViewClickHandler,
                    callback = callback,
                    parentLayout = publicLayout,
                    existingView = publicLayout.contractedChild,
                    existingWrapper = publicLayout.getVisibleWrapper(VISIBLE_TYPE_CONTRACTED),
                    runningInflations = runningInflations,
                    applyCallback = applyCallback,
                    logger = logger
                )
            }
            if (AsyncGroupHeaderViewInflation.isEnabled) {
                val childrenContainer: NotificationChildrenContainer =
                    row.getChildrenContainerNonNull()
                if (reInflateFlags and FLAG_GROUP_SUMMARY_HEADER != 0) {
                    val isNewView =
                        !canReapplyRemoteView(
                            newView = result.remoteViews.normalGroupHeader,
                            oldView =
                                remoteViewCache.getCachedView(entry, FLAG_GROUP_SUMMARY_HEADER)
                        )
                    val applyCallback: ApplyCallback =
                        object : ApplyCallback() {
                            override fun setResultView(v: View) {
                                logger.logAsyncTaskProgress(entry, "group header view applied")
                                result.inflatedGroupHeaderView = v as NotificationHeaderView?
                            }

                            override val remoteView: RemoteViews
                                get() = result.remoteViews.normalGroupHeader!!
                        }
                    logger.logAsyncTaskProgress(entry, "applying group header view")
                    applyRemoteView(
                        inflationExecutor = inflationExecutor,
                        inflateSynchronously = inflateSynchronously,
                        isMinimized = isMinimized,
                        result = result,
                        reInflateFlags = reInflateFlags,
                        inflationId = FLAG_GROUP_SUMMARY_HEADER,
                        remoteViewCache = remoteViewCache,
                        entry = entry,
                        row = row,
                        isNewView = isNewView,
                        remoteViewClickHandler = remoteViewClickHandler,
                        callback = callback,
                        parentLayout = childrenContainer,
                        existingView = childrenContainer.groupHeader,
                        existingWrapper = childrenContainer.notificationHeaderWrapper,
                        runningInflations = runningInflations,
                        applyCallback = applyCallback,
                        logger = logger
                    )
                }
                if (reInflateFlags and FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER != 0) {
                    val isNewView =
                        !canReapplyRemoteView(
                            newView = result.remoteViews.minimizedGroupHeader,
                            oldView =
                                remoteViewCache.getCachedView(
                                    entry,
                                    FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER
                                )
                        )
                    val applyCallback: ApplyCallback =
                        object : ApplyCallback() {
                            override fun setResultView(v: View) {
                                logger.logAsyncTaskProgress(
                                    entry,
                                    "low-priority group header view applied"
                                )
                                result.inflatedMinimizedGroupHeaderView =
                                    v as NotificationHeaderView?
                            }

                            override val remoteView: RemoteViews
                                get() = result.remoteViews.minimizedGroupHeader!!
                        }
                    logger.logAsyncTaskProgress(entry, "applying low priority group header view")
                    applyRemoteView(
                        inflationExecutor = inflationExecutor,
                        inflateSynchronously = inflateSynchronously,
                        isMinimized = isMinimized,
                        result = result,
                        reInflateFlags = reInflateFlags,
                        inflationId = FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER,
                        remoteViewCache = remoteViewCache,
                        entry = entry,
                        row = row,
                        isNewView = isNewView,
                        remoteViewClickHandler = remoteViewClickHandler,
                        callback = callback,
                        parentLayout = childrenContainer,
                        existingView = childrenContainer.minimizedNotificationHeader,
                        existingWrapper = childrenContainer.minimizedGroupHeaderWrapper,
                        runningInflations = runningInflations,
                        applyCallback = applyCallback,
                        logger = logger
                    )
                }
            }

            // Let's try to finish, maybe nobody is even inflating anything
            finishIfDone(
                result,
                isMinimized,
                reInflateFlags,
                remoteViewCache,
                runningInflations,
                callback,
                entry,
                row,
                logger
            )
            val cancellationSignal = CancellationSignal()
            cancellationSignal.setOnCancelListener {
                logger.logAsyncTaskProgress(entry, "apply cancelled")
                Trace.endAsyncSection(APPLY_TRACE_METHOD, System.identityHashCode(row))
                runningInflations.values.forEach(
                    Consumer { obj: CancellationSignal -> obj.cancel() }
                )
            }
            return cancellationSignal
        }

        @VisibleForTesting
        fun applyRemoteView(
            inflationExecutor: Executor?,
            inflateSynchronously: Boolean,
            isMinimized: Boolean,
            result: InflationProgress,
            @InflationFlag reInflateFlags: Int,
            @InflationFlag inflationId: Int,
            remoteViewCache: NotifRemoteViewCache,
            entry: NotificationEntry,
            row: ExpandableNotificationRow,
            isNewView: Boolean,
            remoteViewClickHandler: InteractionHandler?,
            callback: InflationCallback?,
            parentLayout: ViewGroup?,
            existingView: View?,
            existingWrapper: NotificationViewWrapper?,
            runningInflations: HashMap<Int, CancellationSignal>,
            applyCallback: ApplyCallback,
            logger: NotificationRowContentBinderLogger
        ) {
            val newContentView: RemoteViews = applyCallback.remoteView
            if (inflateSynchronously) {
                try {
                    if (isNewView) {
                        val v: View =
                            newContentView.apply(
                                result.packageContext,
                                parentLayout,
                                remoteViewClickHandler
                            )
                        validateView(v, entry, row.resources)
                        applyCallback.setResultView(v)
                    } else {
                        requireNotNull(existingView)
                        requireNotNull(existingWrapper)
                        newContentView.reapply(
                            result.packageContext,
                            existingView,
                            remoteViewClickHandler
                        )
                        validateView(existingView, entry, row.resources)
                        existingWrapper.onReinflated()
                    }
                } catch (e: Exception) {
                    handleInflationError(
                        runningInflations,
                        e,
                        row.entry,
                        callback,
                        logger,
                        "applying view synchronously"
                    )
                    // Add a running inflation to make sure we don't trigger callbacks.
                    // Safe to do because only happens in tests.
                    runningInflations[inflationId] = CancellationSignal()
                }
                return
            }
            val listener: OnViewAppliedListener =
                object : OnViewAppliedListener {
                    override fun onViewInflated(v: View) {
                        if (v is ImageMessageConsumer) {
                            (v as ImageMessageConsumer).setImageResolver(row.imageResolver)
                        }
                    }

                    override fun onViewApplied(v: View) {
                        val invalidReason = isValidView(v, entry, row.resources)
                        if (invalidReason != null) {
                            handleInflationError(
                                runningInflations,
                                InflationException(invalidReason),
                                row.entry,
                                callback,
                                logger,
                                "applied invalid view"
                            )
                            runningInflations.remove(inflationId)
                            return
                        }
                        if (isNewView) {
                            applyCallback.setResultView(v)
                        } else {
                            existingWrapper?.onReinflated()
                        }
                        runningInflations.remove(inflationId)
                        finishIfDone(
                            result,
                            isMinimized,
                            reInflateFlags,
                            remoteViewCache,
                            runningInflations,
                            callback,
                            entry,
                            row,
                            logger
                        )
                    }

                    override fun onError(e: Exception) {
                        // Uh oh the async inflation failed. Due to some bugs (see b/38190555), this
                        // could
                        // actually also be a system issue, so let's try on the UI thread again to
                        // be safe.
                        try {
                            val newView =
                                if (isNewView) {
                                    newContentView.apply(
                                        result.packageContext,
                                        parentLayout,
                                        remoteViewClickHandler
                                    )
                                } else {
                                    newContentView.reapply(
                                        result.packageContext,
                                        existingView,
                                        remoteViewClickHandler
                                    )
                                    existingView!!
                                }
                            Log.wtf(
                                TAG,
                                "Async Inflation failed but normal inflation finished normally.",
                                e
                            )
                            onViewApplied(newView)
                        } catch (anotherException: Exception) {
                            runningInflations.remove(inflationId)
                            handleInflationError(
                                runningInflations,
                                e,
                                row.entry,
                                callback,
                                logger,
                                "applying view"
                            )
                        }
                    }
                }
            val cancellationSignal: CancellationSignal =
                if (isNewView) {
                    newContentView.applyAsync(
                        result.packageContext,
                        parentLayout,
                        inflationExecutor,
                        listener,
                        remoteViewClickHandler
                    )
                } else {
                    newContentView.reapplyAsync(
                        result.packageContext,
                        existingView,
                        inflationExecutor,
                        listener,
                        remoteViewClickHandler
                    )
                }
            runningInflations[inflationId] = cancellationSignal
        }

        /**
         * Checks if the given View is a valid notification View.
         *
         * @return null == valid, non-null == invalid, String represents reason for rejection.
         */
        @VisibleForTesting
        fun isValidView(view: View, entry: NotificationEntry, resources: Resources): String? {
            return if (!satisfiesMinHeightRequirement(view, entry, resources)) {
                "inflated notification does not meet minimum height requirement"
            } else null
        }

        private fun satisfiesMinHeightRequirement(
            view: View,
            entry: NotificationEntry,
            resources: Resources
        ): Boolean {
            return if (!requiresHeightCheck(entry)) {
                true
            } else
                TraceUtils.trace("NotificationContentInflater#satisfiesMinHeightRequirement") {
                    val heightSpec =
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    val referenceWidth =
                        resources.getDimensionPixelSize(
                            R.dimen.notification_validation_reference_width
                        )
                    val widthSpec =
                        View.MeasureSpec.makeMeasureSpec(referenceWidth, View.MeasureSpec.EXACTLY)
                    view.measure(widthSpec, heightSpec)
                    val minHeight =
                        resources.getDimensionPixelSize(
                            R.dimen.notification_validation_minimum_allowed_height
                        )
                    view.measuredHeight >= minHeight
                }
        }

        /**
         * Notifications with undecorated custom views need to satisfy a minimum height to avoid
         * visual issues.
         */
        private fun requiresHeightCheck(entry: NotificationEntry): Boolean {
            // Undecorated custom views are disallowed from S onwards
            if (entry.targetSdk >= Build.VERSION_CODES.S) {
                return false
            }
            // No need to check if the app isn't using any custom views
            val notification: Notification = entry.sbn.notification
            @Suppress("DEPRECATION")
            return !(notification.contentView == null &&
                notification.bigContentView == null &&
                notification.headsUpContentView == null)
        }

        @Throws(InflationException::class)
        private fun validateView(view: View, entry: NotificationEntry, resources: Resources) {
            val invalidReason = isValidView(view, entry, resources)
            if (invalidReason != null) {
                throw InflationException(invalidReason)
            }
        }

        private fun handleInflationError(
            runningInflations: HashMap<Int, CancellationSignal>,
            e: Exception,
            notification: NotificationEntry,
            callback: InflationCallback?,
            logger: NotificationRowContentBinderLogger,
            logContext: String
        ) {
            Assert.isMainThread()
            logger.logAsyncTaskException(notification, logContext, e)
            runningInflations.values.forEach(Consumer { obj: CancellationSignal -> obj.cancel() })
            callback?.handleInflationException(notification, e)
        }

        /**
         * Finish the inflation of the views
         *
         * @return true if the inflation was finished
         */
        private fun finishIfDone(
            result: InflationProgress,
            isMinimized: Boolean,
            @InflationFlag reInflateFlags: Int,
            remoteViewCache: NotifRemoteViewCache,
            runningInflations: HashMap<Int, CancellationSignal>,
            endListener: InflationCallback?,
            entry: NotificationEntry,
            row: ExpandableNotificationRow,
            logger: NotificationRowContentBinderLogger
        ): Boolean {
            Assert.isMainThread()
            if (runningInflations.isNotEmpty()) {
                return false
            }
            logger.logAsyncTaskProgress(entry, "finishing")
            setViewsFromRemoteViews(
                reInflateFlags,
                entry,
                remoteViewCache,
                result,
                row,
                isMinimized,
            )
            result.inflatedSmartReplyState?.let { row.privateLayout.setInflatedSmartReplyState(it) }

            if (
                AsyncHybridViewInflation.isEnabled &&
                    reInflateFlags and FLAG_CONTENT_VIEW_SINGLE_LINE != 0
            ) {
                val singleLineView = result.inflatedSingleLineView
                val viewModel = result.contentModel.singleLineViewModel
                if (singleLineView != null && viewModel != null) {
                    if (viewModel.isConversation()) {
                        SingleLineConversationViewBinder.bind(viewModel, singleLineView)
                    } else {
                        SingleLineViewBinder.bind(viewModel, singleLineView)
                    }
                    row.privateLayout.setSingleLineView(result.inflatedSingleLineView)
                }
            }
            entry.setContentModel(result.contentModel)
            Trace.endAsyncSection(APPLY_TRACE_METHOD, System.identityHashCode(row))
            endListener?.onAsyncInflationFinished(entry)
            return true
        }

        private fun setViewsFromRemoteViews(
            reInflateFlags: Int,
            entry: NotificationEntry,
            remoteViewCache: NotifRemoteViewCache,
            result: InflationProgress,
            row: ExpandableNotificationRow,
            isMinimized: Boolean,
        ) {
            val privateLayout = row.privateLayout
            val publicLayout = row.publicLayout
            val remoteViewsUpdater = RemoteViewsUpdater(reInflateFlags, entry, remoteViewCache)
            remoteViewsUpdater.setContentView(
                FLAG_CONTENT_VIEW_CONTRACTED,
                result.remoteViews.contracted,
                result.inflatedContentView,
                privateLayout::setContractedChild
            )
            remoteViewsUpdater.setContentView(
                FLAG_CONTENT_VIEW_EXPANDED,
                result.remoteViews.expanded,
                result.inflatedExpandedView,
                privateLayout::setExpandedChild
            )
            remoteViewsUpdater.setSmartReplies(
                FLAG_CONTENT_VIEW_EXPANDED,
                result.remoteViews.expanded,
                result.expandedInflatedSmartReplies,
                privateLayout::setExpandedInflatedSmartReplies
            )
            if (reInflateFlags and FLAG_CONTENT_VIEW_EXPANDED != 0) {
                row.setExpandable(result.remoteViews.expanded != null)
            }
            remoteViewsUpdater.setContentView(
                FLAG_CONTENT_VIEW_HEADS_UP,
                result.remoteViews.headsUp,
                result.inflatedHeadsUpView,
                privateLayout::setHeadsUpChild
            )
            remoteViewsUpdater.setSmartReplies(
                FLAG_CONTENT_VIEW_HEADS_UP,
                result.remoteViews.headsUp,
                result.headsUpInflatedSmartReplies,
                privateLayout::setHeadsUpInflatedSmartReplies
            )
            remoteViewsUpdater.setContentView(
                FLAG_CONTENT_VIEW_PUBLIC,
                result.remoteViews.public,
                result.inflatedPublicView,
                publicLayout::setContractedChild
            )
            if (AsyncGroupHeaderViewInflation.isEnabled) {
                remoteViewsUpdater.setContentView(
                    FLAG_GROUP_SUMMARY_HEADER,
                    result.remoteViews.normalGroupHeader,
                    result.inflatedGroupHeaderView,
                ) { views ->
                    row.setIsMinimized(isMinimized)
                    row.setGroupHeader(views)
                }
                remoteViewsUpdater.setContentView(
                    FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER,
                    result.remoteViews.minimizedGroupHeader,
                    result.inflatedMinimizedGroupHeaderView,
                ) { views ->
                    row.setIsMinimized(isMinimized)
                    row.setMinimizedGroupHeader(views)
                }
            }
        }

        private class RemoteViewsUpdater(
            @InflationFlag private val reInflateFlags: Int,
            private val entry: NotificationEntry,
            private val remoteViewCache: NotifRemoteViewCache,
        ) {
            fun <V : View> setContentView(
                @InflationFlag flagState: Int,
                remoteViews: RemoteViews?,
                view: V?,
                setView: (V?) -> Unit,
            ) {
                val clearViewFlags = FLAG_CONTENT_VIEW_HEADS_UP or FLAG_CONTENT_VIEW_EXPANDED
                val shouldClearView = flagState and clearViewFlags != 0
                if (reInflateFlags and flagState != 0) {
                    if (view != null) {
                        setView(view)
                        remoteViewCache.putCachedView(entry, flagState, remoteViews)
                    } else if (shouldClearView && remoteViews == null) {
                        setView(null)
                        remoteViewCache.removeCachedView(entry, flagState)
                    } else if (remoteViewCache.hasCachedView(entry, flagState)) {
                        // Re-inflation case. Only update if it's still cached (i.e. view has not
                        // been freed while inflating).
                        remoteViewCache.putCachedView(entry, flagState, remoteViews)
                    }
                }
            }

            fun setSmartReplies(
                @InflationFlag flagState: Int,
                remoteViews: RemoteViews?,
                smartReplies: InflatedSmartReplyViewHolder?,
                setSmartReplies: (InflatedSmartReplyViewHolder?) -> Unit,
            ) {
                if (reInflateFlags and flagState != 0) {
                    if (remoteViews != null) {
                        setSmartReplies(smartReplies)
                    } else {
                        setSmartReplies(null)
                    }
                }
            }
        }

        private fun createExpandedView(
            builder: Notification.Builder,
            isMinimized: Boolean
        ): RemoteViews? {
            @Suppress("DEPRECATION")
            val bigContentView: RemoteViews? = builder.createBigContentView()
            if (bigContentView != null) {
                return bigContentView
            }
            if (isMinimized) {
                @Suppress("DEPRECATION") val contentView: RemoteViews = builder.createContentView()
                Notification.Builder.makeHeaderExpanded(contentView)
                return contentView
            }
            return null
        }

        private fun createContentView(
            builder: Notification.Builder,
            isMinimized: Boolean,
            useLarge: Boolean
        ): RemoteViews {
            return if (isMinimized) {
                builder.makeLowPriorityContentView(false /* useRegularSubtext */)
            } else builder.createContentView(useLarge)
        }

        /**
         * @param newView The new view that will be applied
         * @param oldView The old view that was applied to the existing view before
         * @return `true` if the RemoteViews are the same and the view can be reused to reapply.
         */
        @VisibleForTesting
        fun canReapplyRemoteView(newView: RemoteViews?, oldView: RemoteViews?): Boolean {
            return newView == null && oldView == null ||
                newView != null &&
                    oldView != null &&
                    oldView.getPackage() != null &&
                    newView.getPackage() != null &&
                    newView.getPackage() == oldView.getPackage() &&
                    newView.layoutId == oldView.layoutId &&
                    !oldView.hasFlags(RemoteViews.FLAG_REAPPLY_DISALLOWED)
        }

        @InflationFlag
        private fun getRemoteViewsFlags(
            @InflationFlag reInflateFlags: Int,
            richOngoingContentModel: RichOngoingContentModel?
        ): Int =
            if (richOngoingContentModel != null) {
                reInflateFlags and CONTENT_VIEWS_TO_CREATE_RICH_ONGOING.inv()
            } else {
                reInflateFlags
            }

        @InflationFlag
        private const val CONTENT_VIEWS_TO_CREATE_RICH_ONGOING =
            FLAG_CONTENT_VIEW_CONTRACTED or FLAG_CONTENT_VIEW_EXPANDED or FLAG_CONTENT_VIEW_HEADS_UP

        private const val ASYNC_TASK_TRACE_METHOD =
            "NotificationRowContentBinderImpl.AsyncInflationTask"
        private const val APPLY_TRACE_METHOD = "NotificationRowContentBinderImpl#apply"
    }
}
