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

package com.android.systemui.statusbar.notification.row;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_EXPANDED;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_HEADSUP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Trace;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ImageMessageConsumer;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.media.controls.util.MediaFeatureFlag;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.policy.InflatedSmartReplyState;
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder;
import com.android.systemui.statusbar.policy.SmartReplyStateInflater;
import com.android.systemui.util.Assert;

import java.util.HashMap;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * {@link NotificationContentInflater} binds content to a {@link ExpandableNotificationRow} by
 * asynchronously building the content's {@link RemoteViews} and applying it to the row.
 */
@SysUISingleton
@VisibleForTesting(visibility = PACKAGE)
public class NotificationContentInflater implements NotificationRowContentBinder {

    public static final String TAG = "NotifContentInflater";

    private boolean mInflateSynchronously = false;
    private final boolean mIsMediaInQS;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final NotifRemoteViewCache mRemoteViewCache;
    private final ConversationNotificationProcessor mConversationProcessor;
    private final Executor mBgExecutor;
    private final SmartReplyStateInflater mSmartReplyStateInflater;

    @Inject
    NotificationContentInflater(
            NotifRemoteViewCache remoteViewCache,
            NotificationRemoteInputManager remoteInputManager,
            ConversationNotificationProcessor conversationProcessor,
            MediaFeatureFlag mediaFeatureFlag,
            @Background Executor bgExecutor,
            SmartReplyStateInflater smartRepliesInflater) {
        mRemoteViewCache = remoteViewCache;
        mRemoteInputManager = remoteInputManager;
        mConversationProcessor = conversationProcessor;
        mIsMediaInQS = mediaFeatureFlag.getEnabled();
        mBgExecutor = bgExecutor;
        mSmartReplyStateInflater = smartRepliesInflater;
    }

    @Override
    public void bindContent(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            @InflationFlag int contentToBind,
            BindParams bindParams,
            boolean forceInflate,
            @Nullable InflationCallback callback) {
        if (row.isRemoved()) {
            // We don't want to reinflate anything for removed notifications. Otherwise views might
            // be readded to the stack, leading to leaks. This may happen with low-priority groups
            // where the removal of already removed children can lead to a reinflation.
            return;
        }

        StatusBarNotification sbn = entry.getSbn();

        // To check if the notification has inline image and preload inline image if necessary.
        row.getImageResolver().preloadImages(sbn.getNotification());

        if (forceInflate) {
            mRemoteViewCache.clearCache(entry);
        }

        // Cancel any pending frees on any view we're trying to bind since we should be bound after.
        cancelContentViewFrees(row, contentToBind);

        AsyncInflationTask task = new AsyncInflationTask(
                mBgExecutor,
                mInflateSynchronously,
                contentToBind,
                mRemoteViewCache,
                entry,
                mConversationProcessor,
                row,
                bindParams.isLowPriority,
                bindParams.usesIncreasedHeight,
                bindParams.usesIncreasedHeadsUpHeight,
                callback,
                mRemoteInputManager.getRemoteViewsOnClickHandler(),
                mIsMediaInQS,
                mSmartReplyStateInflater);
        if (mInflateSynchronously) {
            task.onPostExecute(task.doInBackground());
        } else {
            task.executeOnExecutor(mBgExecutor);
        }
    }

    @VisibleForTesting
    InflationProgress inflateNotificationViews(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            BindParams bindParams,
            boolean inflateSynchronously,
            @InflationFlag int reInflateFlags,
            Notification.Builder builder,
            Context packageContext,
            SmartReplyStateInflater smartRepliesInflater) {
        InflationProgress result = createRemoteViews(reInflateFlags,
                builder,
                bindParams.isLowPriority,
                bindParams.usesIncreasedHeight,
                bindParams.usesIncreasedHeadsUpHeight,
                packageContext);

        result = inflateSmartReplyViews(result, reInflateFlags, entry,
                row.getContext(), packageContext,
                row.getExistingSmartReplyState(),
                smartRepliesInflater);

        apply(
                mBgExecutor,
                inflateSynchronously,
                result,
                reInflateFlags,
                mRemoteViewCache,
                entry,
                row,
                mRemoteInputManager.getRemoteViewsOnClickHandler(),
                null);
        return result;
    }

    @Override
    public void cancelBind(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row) {
        entry.abortTask();
    }

    @Override
    public void unbindContent(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row,
            @InflationFlag int contentToUnbind) {
        int curFlag = 1;
        while (contentToUnbind != 0) {
            if ((contentToUnbind & curFlag) != 0) {
                freeNotificationView(entry, row, curFlag);
            }
            contentToUnbind &= ~curFlag;
            curFlag = curFlag << 1;
        }
    }

    /**
     * Frees the content view associated with the inflation flag as soon as the view is not showing.
     *
     * @param inflateFlag the flag corresponding to the content view which should be freed
     */
    private void freeNotificationView(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            @InflationFlag int inflateFlag) {
        switch (inflateFlag) {
            case FLAG_CONTENT_VIEW_CONTRACTED:
                row.getPrivateLayout().performWhenContentInactive(VISIBLE_TYPE_CONTRACTED, () -> {
                    row.getPrivateLayout().setContractedChild(null);
                    mRemoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED);
                });
                break;
            case FLAG_CONTENT_VIEW_EXPANDED:
                row.getPrivateLayout().performWhenContentInactive(VISIBLE_TYPE_EXPANDED, () -> {
                    row.getPrivateLayout().setExpandedChild(null);
                    mRemoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED);
                });
                break;
            case FLAG_CONTENT_VIEW_HEADS_UP:
                row.getPrivateLayout().performWhenContentInactive(VISIBLE_TYPE_HEADSUP, () -> {
                    row.getPrivateLayout().setHeadsUpChild(null);
                    mRemoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP);
                    row.getPrivateLayout().setHeadsUpInflatedSmartReplies(null);
                });
                break;
            case FLAG_CONTENT_VIEW_PUBLIC:
                row.getPublicLayout().performWhenContentInactive(VISIBLE_TYPE_CONTRACTED, () -> {
                    row.getPublicLayout().setContractedChild(null);
                    mRemoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC);
                });
                break;
            default:
                break;
        }
    }

    /**
     * Cancel any pending content view frees from {@link #freeNotificationView} for the provided
     * content views.
     *
     * @param row top level notification row containing the content views
     * @param contentViews content views to cancel pending frees on
     */
    private void cancelContentViewFrees(
            ExpandableNotificationRow row,
            @InflationFlag int contentViews) {
        if ((contentViews & FLAG_CONTENT_VIEW_CONTRACTED) != 0) {
            row.getPrivateLayout().removeContentInactiveRunnable(VISIBLE_TYPE_CONTRACTED);
        }
        if ((contentViews & FLAG_CONTENT_VIEW_EXPANDED) != 0) {
            row.getPrivateLayout().removeContentInactiveRunnable(VISIBLE_TYPE_EXPANDED);
        }
        if ((contentViews & FLAG_CONTENT_VIEW_HEADS_UP) != 0) {
            row.getPrivateLayout().removeContentInactiveRunnable(VISIBLE_TYPE_HEADSUP);
        }
        if ((contentViews & FLAG_CONTENT_VIEW_PUBLIC) != 0) {
            row.getPublicLayout().removeContentInactiveRunnable(VISIBLE_TYPE_CONTRACTED);
        }
    }

    private static InflationProgress inflateSmartReplyViews(
            InflationProgress result,
            @InflationFlag int reInflateFlags,
            NotificationEntry entry,
            Context context,
            Context packageContext,
            InflatedSmartReplyState previousSmartReplyState,
            SmartReplyStateInflater inflater) {
        boolean inflateContracted = (reInflateFlags & FLAG_CONTENT_VIEW_CONTRACTED) != 0
                && result.newContentView != null;
        boolean inflateExpanded = (reInflateFlags & FLAG_CONTENT_VIEW_EXPANDED) != 0
                && result.newExpandedView != null;
        boolean inflateHeadsUp = (reInflateFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0
                && result.newHeadsUpView != null;
        if (inflateContracted || inflateExpanded || inflateHeadsUp) {
            result.inflatedSmartReplyState = inflater.inflateSmartReplyState(entry);
        }
        if (inflateExpanded) {
            result.expandedInflatedSmartReplies = inflater.inflateSmartReplyViewHolder(
                    context, packageContext, entry, previousSmartReplyState,
                    result.inflatedSmartReplyState);
        }
        if (inflateHeadsUp) {
            result.headsUpInflatedSmartReplies = inflater.inflateSmartReplyViewHolder(
                    context, packageContext, entry, previousSmartReplyState,
                    result.inflatedSmartReplyState);
        }
        return result;
    }

    private static InflationProgress createRemoteViews(@InflationFlag int reInflateFlags,
            Notification.Builder builder, boolean isLowPriority, boolean usesIncreasedHeight,
            boolean usesIncreasedHeadsUpHeight, Context packageContext) {
        InflationProgress result = new InflationProgress();

        if ((reInflateFlags & FLAG_CONTENT_VIEW_CONTRACTED) != 0) {
            result.newContentView = createContentView(builder, isLowPriority, usesIncreasedHeight);
        }

        if ((reInflateFlags & FLAG_CONTENT_VIEW_EXPANDED) != 0) {
            result.newExpandedView = createExpandedView(builder, isLowPriority);
        }

        if ((reInflateFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0) {
            result.newHeadsUpView = builder.createHeadsUpContentView(usesIncreasedHeadsUpHeight);
        }

        if ((reInflateFlags & FLAG_CONTENT_VIEW_PUBLIC) != 0) {
            result.newPublicView = builder.makePublicContentView(isLowPriority);
        }

        result.packageContext = packageContext;
        result.headsUpStatusBarText = builder.getHeadsUpStatusBarText(false /* showingPublic */);
        result.headsUpStatusBarTextPublic = builder.getHeadsUpStatusBarText(
                true /* showingPublic */);
        return result;
    }

    private static CancellationSignal apply(
            Executor bgExecutor,
            boolean inflateSynchronously,
            InflationProgress result,
            @InflationFlag int reInflateFlags,
            NotifRemoteViewCache remoteViewCache,
            NotificationEntry entry,
            ExpandableNotificationRow row,
            RemoteViews.InteractionHandler remoteViewClickHandler,
            @Nullable InflationCallback callback) {
        NotificationContentView privateLayout = row.getPrivateLayout();
        NotificationContentView publicLayout = row.getPublicLayout();
        final HashMap<Integer, CancellationSignal> runningInflations = new HashMap<>();

        int flag = FLAG_CONTENT_VIEW_CONTRACTED;
        if ((reInflateFlags & flag) != 0) {
            boolean isNewView =
                    !canReapplyRemoteView(result.newContentView,
                            remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED));
            ApplyCallback applyCallback = new ApplyCallback() {
                @Override
                public void setResultView(View v) {
                    result.inflatedContentView = v;
                }

                @Override
                public RemoteViews getRemoteView() {
                    return result.newContentView;
                }
            };
            applyRemoteView(bgExecutor, inflateSynchronously, result, reInflateFlags, flag,
                    remoteViewCache, entry, row, isNewView, remoteViewClickHandler, callback,
                    privateLayout,  privateLayout.getContractedChild(),
                    privateLayout.getVisibleWrapper(
                            NotificationContentView.VISIBLE_TYPE_CONTRACTED),
                    runningInflations, applyCallback);
        }

        flag = FLAG_CONTENT_VIEW_EXPANDED;
        if ((reInflateFlags & flag) != 0) {
            if (result.newExpandedView != null) {
                boolean isNewView =
                        !canReapplyRemoteView(result.newExpandedView,
                                remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED));
                ApplyCallback applyCallback = new ApplyCallback() {
                    @Override
                    public void setResultView(View v) {
                        result.inflatedExpandedView = v;
                    }

                    @Override
                    public RemoteViews getRemoteView() {
                        return result.newExpandedView;
                    }
                };
                applyRemoteView(bgExecutor, inflateSynchronously, result, reInflateFlags, flag,
                        remoteViewCache, entry, row, isNewView, remoteViewClickHandler,
                        callback, privateLayout, privateLayout.getExpandedChild(),
                        privateLayout.getVisibleWrapper(
                                NotificationContentView.VISIBLE_TYPE_EXPANDED), runningInflations,
                        applyCallback);
            }
        }

        flag = FLAG_CONTENT_VIEW_HEADS_UP;
        if ((reInflateFlags & flag) != 0) {
            if (result.newHeadsUpView != null) {
                boolean isNewView =
                        !canReapplyRemoteView(result.newHeadsUpView,
                                remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP));
                ApplyCallback applyCallback = new ApplyCallback() {
                    @Override
                    public void setResultView(View v) {
                        result.inflatedHeadsUpView = v;
                    }

                    @Override
                    public RemoteViews getRemoteView() {
                        return result.newHeadsUpView;
                    }
                };
                applyRemoteView(bgExecutor, inflateSynchronously, result, reInflateFlags, flag,
                        remoteViewCache, entry, row, isNewView, remoteViewClickHandler,
                        callback, privateLayout, privateLayout.getHeadsUpChild(),
                        privateLayout.getVisibleWrapper(
                                VISIBLE_TYPE_HEADSUP), runningInflations,
                        applyCallback);
            }
        }

        flag = FLAG_CONTENT_VIEW_PUBLIC;
        if ((reInflateFlags & flag) != 0) {
            boolean isNewView =
                    !canReapplyRemoteView(result.newPublicView,
                            remoteViewCache.getCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC));
            ApplyCallback applyCallback = new ApplyCallback() {
                @Override
                public void setResultView(View v) {
                    result.inflatedPublicView = v;
                }

                @Override
                public RemoteViews getRemoteView() {
                    return result.newPublicView;
                }
            };
            applyRemoteView(bgExecutor, inflateSynchronously, result, reInflateFlags, flag,
                    remoteViewCache, entry, row, isNewView, remoteViewClickHandler, callback,
                    publicLayout, publicLayout.getContractedChild(),
                    publicLayout.getVisibleWrapper(NotificationContentView.VISIBLE_TYPE_CONTRACTED),
                    runningInflations, applyCallback);
        }

        // Let's try to finish, maybe nobody is even inflating anything
        finishIfDone(result, reInflateFlags, remoteViewCache, runningInflations, callback, entry,
                row);
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.setOnCancelListener(
                () -> runningInflations.values().forEach(CancellationSignal::cancel));

        return cancellationSignal;
    }

    @VisibleForTesting
    static void applyRemoteView(
            Executor bgExecutor,
            boolean inflateSynchronously,
            final InflationProgress result,
            final @InflationFlag int reInflateFlags,
            @InflationFlag int inflationId,
            final NotifRemoteViewCache remoteViewCache,
            final NotificationEntry entry,
            final ExpandableNotificationRow row,
            boolean isNewView,
            RemoteViews.InteractionHandler remoteViewClickHandler,
            @Nullable final InflationCallback callback,
            NotificationContentView parentLayout,
            View existingView,
            NotificationViewWrapper existingWrapper,
            final HashMap<Integer, CancellationSignal> runningInflations,
            ApplyCallback applyCallback) {
        RemoteViews newContentView = applyCallback.getRemoteView();
        if (inflateSynchronously) {
            try {
                if (isNewView) {
                    View v = newContentView.apply(
                            result.packageContext,
                            parentLayout,
                            remoteViewClickHandler);
                    validateView(v, entry, row.getResources());
                    v.setIsRootNamespace(true);
                    applyCallback.setResultView(v);
                } else {
                    newContentView.reapply(
                            result.packageContext,
                            existingView,
                            remoteViewClickHandler);
                    validateView(existingView, entry, row.getResources());
                    existingWrapper.onReinflated();
                }
            } catch (Exception e) {
                handleInflationError(runningInflations, e, row.getEntry(), callback);
                // Add a running inflation to make sure we don't trigger callbacks.
                // Safe to do because only happens in tests.
                runningInflations.put(inflationId, new CancellationSignal());
            }
            return;
        }
        RemoteViews.OnViewAppliedListener listener = new RemoteViews.OnViewAppliedListener() {

            @Override
            public void onViewInflated(View v) {
                if (v instanceof ImageMessageConsumer) {
                    ((ImageMessageConsumer) v).setImageResolver(row.getImageResolver());
                }
            }

            @Override
            public void onViewApplied(View v) {
                String invalidReason = isValidView(v, entry, row.getResources());
                if (invalidReason != null) {
                    handleInflationError(runningInflations, new InflationException(invalidReason),
                            row.getEntry(), callback);
                    runningInflations.remove(inflationId);
                    return;
                }
                if (isNewView) {
                    v.setIsRootNamespace(true);
                    applyCallback.setResultView(v);
                } else if (existingWrapper != null) {
                    existingWrapper.onReinflated();
                }
                runningInflations.remove(inflationId);
                finishIfDone(result, reInflateFlags, remoteViewCache, runningInflations,
                        callback, entry, row);
            }

            @Override
            public void onError(Exception e) {
                // Uh oh the async inflation failed. Due to some bugs (see b/38190555), this could
                // actually also be a system issue, so let's try on the UI thread again to be safe.
                try {
                    View newView = existingView;
                    if (isNewView) {
                        newView = newContentView.apply(
                                result.packageContext,
                                parentLayout,
                                remoteViewClickHandler);
                    } else {
                        newContentView.reapply(
                                result.packageContext,
                                existingView,
                                remoteViewClickHandler);
                    }
                    Log.wtf(TAG, "Async Inflation failed but normal inflation finished normally.",
                            e);
                    onViewApplied(newView);
                } catch (Exception anotherException) {
                    runningInflations.remove(inflationId);
                    handleInflationError(runningInflations, e, row.getEntry(),
                            callback);
                }
            }
        };
        CancellationSignal cancellationSignal;
        if (isNewView) {
            cancellationSignal = newContentView.applyAsync(
                    result.packageContext,
                    parentLayout,
                    bgExecutor,
                    listener,
                    remoteViewClickHandler);
        } else {
            cancellationSignal = newContentView.reapplyAsync(
                    result.packageContext,
                    existingView,
                    bgExecutor,
                    listener,
                    remoteViewClickHandler);
        }
        runningInflations.put(inflationId, cancellationSignal);
    }

    /**
     * Checks if the given View is a valid notification View.
     *
     * @return null == valid, non-null == invalid, String represents reason for rejection.
     */
    @VisibleForTesting
    @Nullable
    static String isValidView(View view,
            NotificationEntry entry,
            Resources resources) {
        if (!satisfiesMinHeightRequirement(view, entry, resources)) {
            return "inflated notification does not meet minimum height requirement";
        }
        return null;
    }

    private static boolean satisfiesMinHeightRequirement(View view,
            NotificationEntry entry,
            Resources resources) {
        if (!requiresHeightCheck(entry)) {
            return true;
        }
        Trace.beginSection("NotificationContentInflater#satisfiesMinHeightRequirement");
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int referenceWidth = resources.getDimensionPixelSize(
                R.dimen.notification_validation_reference_width);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(referenceWidth, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);
        int minHeight = resources.getDimensionPixelSize(
                R.dimen.notification_validation_minimum_allowed_height);
        boolean result = view.getMeasuredHeight() >= minHeight;
        Trace.endSection();
        return result;
    }

    private static boolean requiresHeightCheck(NotificationEntry entry) {
        // Undecorated custom views are disallowed from S onwards
        if (entry.targetSdk >= Build.VERSION_CODES.S) {
            return false;
        }
        // No need to check if the app isn't using any custom views
        Notification notification = entry.getSbn().getNotification();
        if (notification.contentView == null
                && notification.bigContentView == null
                && notification.headsUpContentView == null) {
            return false;
        }
        return true;
    }

    private static void validateView(View view,
            NotificationEntry entry,
            Resources resources) throws InflationException {
        String invalidReason = isValidView(view, entry, resources);
        if (invalidReason != null) {
            throw new InflationException(invalidReason);
        }
    }

    private static void handleInflationError(
            HashMap<Integer, CancellationSignal> runningInflations, Exception e,
            NotificationEntry notification, @Nullable InflationCallback callback) {
        Assert.isMainThread();
        runningInflations.values().forEach(CancellationSignal::cancel);
        if (callback != null) {
            callback.handleInflationException(notification, e);
        }
    }

    /**
     * Finish the inflation of the views
     *
     * @return true if the inflation was finished
     */
    private static boolean finishIfDone(InflationProgress result,
            @InflationFlag int reInflateFlags, NotifRemoteViewCache remoteViewCache,
            HashMap<Integer, CancellationSignal> runningInflations,
            @Nullable InflationCallback endListener, NotificationEntry entry,
            ExpandableNotificationRow row) {
        Assert.isMainThread();
        NotificationContentView privateLayout = row.getPrivateLayout();
        NotificationContentView publicLayout = row.getPublicLayout();
        if (runningInflations.isEmpty()) {
            boolean setRepliesAndActions = true;
            if ((reInflateFlags & FLAG_CONTENT_VIEW_CONTRACTED) != 0) {
                if (result.inflatedContentView != null) {
                    // New view case
                    privateLayout.setContractedChild(result.inflatedContentView);
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED,
                            result.newContentView);
                } else if (remoteViewCache.hasCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED)) {
                    // Reinflation case. Only update if it's still cached (i.e. view has not been
                    // freed while inflating).
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_CONTRACTED,
                            result.newContentView);
                }
                setRepliesAndActions = true;
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_EXPANDED) != 0) {
                if (result.inflatedExpandedView != null) {
                    privateLayout.setExpandedChild(result.inflatedExpandedView);
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED,
                            result.newExpandedView);
                } else if (result.newExpandedView == null) {
                    privateLayout.setExpandedChild(null);
                    remoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED);
                } else if (remoteViewCache.hasCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED)) {
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_EXPANDED,
                            result.newExpandedView);
                }
                if (result.newExpandedView != null) {
                    privateLayout.setExpandedInflatedSmartReplies(
                            result.expandedInflatedSmartReplies);
                } else {
                    privateLayout.setExpandedInflatedSmartReplies(null);
                }
                row.setExpandable(result.newExpandedView != null);
                setRepliesAndActions = true;
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0) {
                if (result.inflatedHeadsUpView != null) {
                    privateLayout.setHeadsUpChild(result.inflatedHeadsUpView);
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP,
                            result.newHeadsUpView);
                } else if (result.newHeadsUpView == null) {
                    privateLayout.setHeadsUpChild(null);
                    remoteViewCache.removeCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP);
                } else if (remoteViewCache.hasCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP)) {
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_HEADS_UP,
                            result.newHeadsUpView);
                }
                if (result.newHeadsUpView != null) {
                    privateLayout.setHeadsUpInflatedSmartReplies(
                            result.headsUpInflatedSmartReplies);
                } else {
                    privateLayout.setHeadsUpInflatedSmartReplies(null);
                }
                setRepliesAndActions = true;
            }
            if (setRepliesAndActions) {
                privateLayout.setInflatedSmartReplyState(result.inflatedSmartReplyState);
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_PUBLIC) != 0) {
                if (result.inflatedPublicView != null) {
                    publicLayout.setContractedChild(result.inflatedPublicView);
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC,
                            result.newPublicView);
                } else if (remoteViewCache.hasCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC)) {
                    remoteViewCache.putCachedView(entry, FLAG_CONTENT_VIEW_PUBLIC,
                            result.newPublicView);
                }
            }

            entry.headsUpStatusBarText = result.headsUpStatusBarText;
            entry.headsUpStatusBarTextPublic = result.headsUpStatusBarTextPublic;
            if (endListener != null) {
                endListener.onAsyncInflationFinished(entry);
            }
            return true;
        }
        return false;
    }

    private static RemoteViews createExpandedView(Notification.Builder builder,
            boolean isLowPriority) {
        RemoteViews bigContentView = builder.createBigContentView();
        if (bigContentView != null) {
            return bigContentView;
        }
        if (isLowPriority) {
            RemoteViews contentView = builder.createContentView();
            Notification.Builder.makeHeaderExpanded(contentView);
            return contentView;
        }
        return null;
    }

    private static RemoteViews createContentView(Notification.Builder builder,
            boolean isLowPriority, boolean useLarge) {
        if (isLowPriority) {
            return builder.makeLowPriorityContentView(false /* useRegularSubtext */);
        }
        return builder.createContentView(useLarge);
    }

    /**
     * @param newView The new view that will be applied
     * @param oldView The old view that was applied to the existing view before
     * @return {@code true} if the RemoteViews are the same and the view can be reused to reapply.
     */
     @VisibleForTesting
     static boolean canReapplyRemoteView(final RemoteViews newView,
            final RemoteViews oldView) {
        return (newView == null && oldView == null) ||
                (newView != null && oldView != null
                        && oldView.getPackage() != null
                        && newView.getPackage() != null
                        && newView.getPackage().equals(oldView.getPackage())
                        && newView.getLayoutId() == oldView.getLayoutId()
                        && !oldView.hasFlags(RemoteViews.FLAG_REAPPLY_DISALLOWED));
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    public void setInflateSynchronously(boolean inflateSynchronously) {
        mInflateSynchronously = inflateSynchronously;
    }

    public static class AsyncInflationTask extends AsyncTask<Void, Void, InflationProgress>
            implements InflationCallback, InflationTask {

        private static final long IMG_PRELOAD_TIMEOUT_MS = 1000L;
        private final NotificationEntry mEntry;
        private final Context mContext;
        private final boolean mInflateSynchronously;
        private final boolean mIsLowPriority;
        private final boolean mUsesIncreasedHeight;
        private final InflationCallback mCallback;
        private final boolean mUsesIncreasedHeadsUpHeight;
        private final @InflationFlag int mReInflateFlags;
        private final NotifRemoteViewCache mRemoteViewCache;
        private final Executor mBgExecutor;
        private ExpandableNotificationRow mRow;
        private Exception mError;
        private RemoteViews.InteractionHandler mRemoteViewClickHandler;
        private CancellationSignal mCancellationSignal;
        private final ConversationNotificationProcessor mConversationProcessor;
        private final boolean mIsMediaInQS;
        private final SmartReplyStateInflater mSmartRepliesInflater;

        private AsyncInflationTask(
                Executor bgExecutor,
                boolean inflateSynchronously,
                @InflationFlag int reInflateFlags,
                NotifRemoteViewCache cache,
                NotificationEntry entry,
                ConversationNotificationProcessor conversationProcessor,
                ExpandableNotificationRow row,
                boolean isLowPriority,
                boolean usesIncreasedHeight,
                boolean usesIncreasedHeadsUpHeight,
                InflationCallback callback,
                RemoteViews.InteractionHandler remoteViewClickHandler,
                boolean isMediaFlagEnabled,
                SmartReplyStateInflater smartRepliesInflater) {
            mEntry = entry;
            mRow = row;
            mBgExecutor = bgExecutor;
            mInflateSynchronously = inflateSynchronously;
            mReInflateFlags = reInflateFlags;
            mRemoteViewCache = cache;
            mSmartRepliesInflater = smartRepliesInflater;
            mContext = mRow.getContext();
            mIsLowPriority = isLowPriority;
            mUsesIncreasedHeight = usesIncreasedHeight;
            mUsesIncreasedHeadsUpHeight = usesIncreasedHeadsUpHeight;
            mRemoteViewClickHandler = remoteViewClickHandler;
            mCallback = callback;
            mConversationProcessor = conversationProcessor;
            mIsMediaInQS = isMediaFlagEnabled;
            entry.setInflationTask(this);
        }

        @VisibleForTesting
        @InflationFlag
        public int getReInflateFlags() {
            return mReInflateFlags;
        }

        void updateApplicationInfo(StatusBarNotification sbn) {
            String packageName = sbn.getPackageName();
            int userId = UserHandle.getUserId(sbn.getUid());
            final ApplicationInfo appInfo;
            try {
                // This method has an internal cache, so we don't need to add our own caching here.
                appInfo = mContext.getPackageManager().getApplicationInfoAsUser(packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            } catch (PackageManager.NameNotFoundException e) {
                return;
            }
            Notification.addFieldsFromContext(appInfo, sbn.getNotification());
        }

        @Override
        protected InflationProgress doInBackground(Void... params) {
            try {
                final StatusBarNotification sbn = mEntry.getSbn();
                // Ensure the ApplicationInfo is updated before a builder is recovered.
                updateApplicationInfo(sbn);
                final Notification.Builder recoveredBuilder
                        = Notification.Builder.recoverBuilder(mContext,
                        sbn.getNotification());

                Context packageContext = sbn.getPackageContext(mContext);
                if (recoveredBuilder.usesTemplate()) {
                    // For all of our templates, we want it to be RTL
                    packageContext = new RtlEnabledContext(packageContext);
                }
                if (mEntry.getRanking().isConversation()) {
                    mConversationProcessor.processNotification(mEntry, recoveredBuilder);
                }
                InflationProgress inflationProgress = createRemoteViews(mReInflateFlags,
                        recoveredBuilder, mIsLowPriority, mUsesIncreasedHeight,
                        mUsesIncreasedHeadsUpHeight, packageContext);
                InflatedSmartReplyState previousSmartReplyState = mRow.getExistingSmartReplyState();
                InflationProgress result = inflateSmartReplyViews(
                        inflationProgress,
                        mReInflateFlags,
                        mEntry,
                        mContext,
                        packageContext,
                        previousSmartReplyState,
                        mSmartRepliesInflater);

                // wait for image resolver to finish preloading
                mRow.getImageResolver().waitForPreloadedImages(IMG_PRELOAD_TIMEOUT_MS);

                return result;
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(InflationProgress result) {
            if (mError == null) {
                mCancellationSignal = apply(
                        mBgExecutor,
                        mInflateSynchronously,
                        result,
                        mReInflateFlags,
                        mRemoteViewCache,
                        mEntry,
                        mRow,
                        mRemoteViewClickHandler,
                        this);
            } else {
                handleError(mError);
            }
        }

        private void handleError(Exception e) {
            mEntry.onInflationTaskFinished();
            StatusBarNotification sbn = mEntry.getSbn();
            final String ident = sbn.getPackageName() + "/0x"
                    + Integer.toHexString(sbn.getId());
            Log.e(CentralSurfaces.TAG, "couldn't inflate view for notification " + ident, e);
            if (mCallback != null) {
                mCallback.handleInflationException(mRow.getEntry(),
                        new InflationException("Couldn't inflate contentViews" + e));
            }

            // Cancel any image loading tasks, not useful any more
            mRow.getImageResolver().cancelRunningTasks();
        }

        @Override
        public void abort() {
            cancel(true /* mayInterruptIfRunning */);
            if (mCancellationSignal != null) {
                mCancellationSignal.cancel();
            }
        }

        @Override
        public void handleInflationException(NotificationEntry entry, Exception e) {
            handleError(e);
        }

        @Override
        public void onAsyncInflationFinished(NotificationEntry entry) {
            mEntry.onInflationTaskFinished();
            mRow.onNotificationUpdated();
            if (mCallback != null) {
                mCallback.onAsyncInflationFinished(mEntry);
            }

            // Notify the resolver that the inflation task has finished,
            // try to purge unnecessary cached entries.
            mRow.getImageResolver().purgeCache();

            // Cancel any image loading tasks that have not completed at this point
            mRow.getImageResolver().cancelRunningTasks();
        }

        private static class RtlEnabledContext extends ContextWrapper {
            private RtlEnabledContext(Context packageContext) {
                super(packageContext);
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                ApplicationInfo applicationInfo = new ApplicationInfo(super.getApplicationInfo());
                applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
                return applicationInfo;
            }
        }
    }

    @VisibleForTesting
    static class InflationProgress {
        private RemoteViews newContentView;
        private RemoteViews newHeadsUpView;
        private RemoteViews newExpandedView;
        private RemoteViews newPublicView;

        @VisibleForTesting
        Context packageContext;

        private View inflatedContentView;
        private View inflatedHeadsUpView;
        private View inflatedExpandedView;
        private View inflatedPublicView;
        private CharSequence headsUpStatusBarText;
        private CharSequence headsUpStatusBarTextPublic;

        private InflatedSmartReplyState inflatedSmartReplyState;
        private InflatedSmartReplyViewHolder expandedInflatedSmartReplies;
        private InflatedSmartReplyViewHolder headsUpInflatedSmartReplies;
    }

    @VisibleForTesting
    abstract static class ApplyCallback {
        public abstract void setResultView(View v);
        public abstract RemoteViews getRemoteView();
    }
}
