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

import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_AMBIENT;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_HEADSUP;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ImageMessageConsumer;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.MediaNotificationProcessor;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.InflatedSmartReplies;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.util.Assert;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility that inflates the right kind of contentView based on the state
 */
public class NotificationContentInflater {

    public static final String TAG = "NotifContentInflater";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            prefix = {"FLAG_CONTENT_VIEW_"},
            value = {
                FLAG_CONTENT_VIEW_CONTRACTED,
                FLAG_CONTENT_VIEW_EXPANDED,
                FLAG_CONTENT_VIEW_HEADS_UP,
                FLAG_CONTENT_VIEW_AMBIENT,
                FLAG_CONTENT_VIEW_PUBLIC,
                FLAG_CONTENT_VIEW_ALL})
    public @interface InflationFlag {}
    /**
     * The default, contracted view.  Seen when the shade is pulled down and in the lock screen
     * if there is no worry about content sensitivity.
     */
    public static final int FLAG_CONTENT_VIEW_CONTRACTED = 1;

    /**
     * The expanded view.  Seen when the user expands a notification.
     */
    public static final int FLAG_CONTENT_VIEW_EXPANDED = 1 << 1;

    /**
     * The heads up view.  Seen when a high priority notification peeks in from the top.
     */
    public static final int FLAG_CONTENT_VIEW_HEADS_UP = 1 << 2;

    /**
     * The ambient view.  Seen when a high priority notification is received and the phone
     * is dozing.
     */
    public static final int FLAG_CONTENT_VIEW_AMBIENT = 1 << 3;

    /**
     * The public view.  This is a version of the contracted view that hides sensitive
     * information and is used on the lock screen if we determine that the notification's
     * content should be hidden.
     */
    public static final int FLAG_CONTENT_VIEW_PUBLIC = 1 << 4;

    public static final int FLAG_CONTENT_VIEW_ALL = ~0;

    /**
     * Content views that must be inflated at all times.
     */
    @InflationFlag
    private static final int REQUIRED_INFLATION_FLAGS =
            FLAG_CONTENT_VIEW_CONTRACTED
            | FLAG_CONTENT_VIEW_EXPANDED;

    /**
     * The set of content views to inflate.
     */
    @InflationFlag
    private int mInflationFlags = REQUIRED_INFLATION_FLAGS;

    static final InflationExecutor EXECUTOR = new InflationExecutor();

    private final ExpandableNotificationRow mRow;
    private boolean mIsLowPriority;
    private boolean mUsesIncreasedHeight;
    private boolean mUsesIncreasedHeadsUpHeight;
    private RemoteViews.OnClickHandler mRemoteViewClickHandler;
    private boolean mIsChildInGroup;
    private InflationCallback mCallback;
    private boolean mRedactAmbient;
    private boolean mInflateSynchronously = false;
    private final ArrayMap<Integer, RemoteViews> mCachedContentViews = new ArrayMap<>();

    public NotificationContentInflater(ExpandableNotificationRow row) {
        mRow = row;
    }

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
    }

    /**
     * Set whether the notification is a child in a group
     *
     * @return whether the view was re-inflated
     */
    public void setIsChildInGroup(boolean childInGroup) {
        if (childInGroup != mIsChildInGroup) {
            mIsChildInGroup = childInGroup;
            if (mIsLowPriority) {
                int flags = FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED;
                inflateNotificationViews(flags);
            }
        }
    }

    public void setUsesIncreasedHeight(boolean usesIncreasedHeight) {
        mUsesIncreasedHeight = usesIncreasedHeight;
    }

    public void setUsesIncreasedHeadsUpHeight(boolean usesIncreasedHeight) {
        mUsesIncreasedHeadsUpHeight = usesIncreasedHeight;
    }

    public void setRemoteViewClickHandler(RemoteViews.OnClickHandler remoteViewClickHandler) {
        mRemoteViewClickHandler = remoteViewClickHandler;
    }

    /**
     * Update whether or not the notification is redacted on the lock screen.  If the notification
     * is now redacted, we should inflate the public contracted view and public ambient view to
     * now show on the lock screen.
     *
     * @param needsRedaction true if the notification should now be redacted on the lock screen
     */
    public void updateNeedsRedaction(boolean needsRedaction) {
        mRedactAmbient = needsRedaction;
        if (mRow.getEntry() == null) {
            return;
        }
        int flags = FLAG_CONTENT_VIEW_AMBIENT;
        if (needsRedaction) {
            flags |= FLAG_CONTENT_VIEW_PUBLIC;
        }
        inflateNotificationViews(flags);
    }

    /**
     * Set whether or not a particular content view is needed and whether or not it should be
     * inflated.  These flags will be used when we inflate or reinflate.
     *
     * @param flag the {@link InflationFlag} corresponding to the view that should/should not be
     *             inflated
     * @param shouldInflate true if the view should be inflated, false otherwise
     */
    public void updateInflationFlag(@InflationFlag int flag, boolean shouldInflate) {
        if (shouldInflate) {
            mInflationFlags |= flag;
        } else if ((REQUIRED_INFLATION_FLAGS & flag) == 0) {
            mInflationFlags &= ~flag;
        }
    }

    /**
     * Convenience method for setting multiple flags at once.
     *
     * @param flags a set of {@link InflationFlag} corresponding to content views that should be
     *              inflated
     */
    @VisibleForTesting
    public void addInflationFlags(@InflationFlag int flags) {
        mInflationFlags |= flags;
    }

    /**
     * Whether or not the view corresponding to the flag is set to be inflated currently.
     *
     * @param flag the {@link InflationFlag} corresponding to the view
     * @return true if the flag is set and view will be inflated, false o/w
     */
    public boolean isInflationFlagSet(@InflationFlag int flag) {
        return ((mInflationFlags & flag) != 0);
    }

    /**
     * Inflate views for set flags on a background thread. This is asynchronous and will
     * notify the callback once it's finished.
     */
    public void inflateNotificationViews() {
        inflateNotificationViews(mInflationFlags);
    }

    /**
     * Inflate all views for the specified flags on a background thread.  This is asynchronous and
     * will notify the callback once it's finished.  If the content view is already inflated, this
     * will reinflate it.
     *
     * @param reInflateFlags flags which views should be inflated. Should be a subset of
     *                       {@link #mInflationFlags} as only those will be inflated/reinflated.
     */
    private void inflateNotificationViews(@InflationFlag int reInflateFlags) {
        if (mRow.isRemoved()) {
            // We don't want to reinflate anything for removed notifications. Otherwise views might
            // be readded to the stack, leading to leaks. This may happen with low-priority groups
            // where the removal of already removed children can lead to a reinflation.
            return;
        }
        // Only inflate the ones that are set.
        reInflateFlags &= mInflationFlags;
        StatusBarNotification sbn = mRow.getEntry().notification;

        // To check if the notification has inline image and preload inline image if necessary.
        mRow.getImageResolver().preloadImages(sbn.getNotification());

        AsyncInflationTask task = new AsyncInflationTask(
                sbn,
                mInflateSynchronously,
                reInflateFlags,
                mCachedContentViews,
                mRow,
                mIsLowPriority,
                mIsChildInGroup,
                mUsesIncreasedHeight,
                mUsesIncreasedHeadsUpHeight,
                mRedactAmbient,
                mCallback,
                mRemoteViewClickHandler);
        if (mInflateSynchronously) {
            task.onPostExecute(task.doInBackground());
        } else {
            task.execute();
        }
    }

    @VisibleForTesting
    InflationProgress inflateNotificationViews(
            boolean inflateSynchronously,
            @InflationFlag int reInflateFlags,
            Notification.Builder builder,
            Context packageContext) {
        InflationProgress result = createRemoteViews(reInflateFlags, builder, mIsLowPriority,
                mIsChildInGroup, mUsesIncreasedHeight, mUsesIncreasedHeadsUpHeight,
                mRedactAmbient, packageContext);
        result = inflateSmartReplyViews(result, reInflateFlags, mRow.getEntry(),
                mRow.getContext(), mRow.getHeadsUpManager());
        apply(
                inflateSynchronously,
                result,
                reInflateFlags,
                mCachedContentViews,
                mRow,
                mRedactAmbient,
                mRemoteViewClickHandler,
                null);
        return result;
    }

    /**
     * Frees the content view associated with the inflation flag.  Will only succeed if the
     * view is safe to remove.
     *
     * @param inflateFlag the flag corresponding to the content view which should be freed
     */
    public void freeNotificationView(@InflationFlag int inflateFlag) {
        if ((mInflationFlags & inflateFlag) != 0) {
            // The view should still be inflated.
            return;
        }
        switch (inflateFlag) {
            case FLAG_CONTENT_VIEW_HEADS_UP:
                if (mRow.getPrivateLayout().isContentViewInactive(VISIBLE_TYPE_HEADSUP)) {
                    mRow.getPrivateLayout().setHeadsUpChild(null);
                    mCachedContentViews.remove(FLAG_CONTENT_VIEW_HEADS_UP);
                    mRow.getPrivateLayout().setHeadsUpInflatedSmartReplies(null);
                }
                break;
            case FLAG_CONTENT_VIEW_AMBIENT:
                boolean privateSafeToRemove = mRow.getPrivateLayout().isContentViewInactive(
                        VISIBLE_TYPE_AMBIENT);
                boolean publicSafeToRemove = mRow.getPublicLayout().isContentViewInactive(
                        VISIBLE_TYPE_AMBIENT);
                if (privateSafeToRemove) {
                    mRow.getPrivateLayout().setAmbientChild(null);
                }
                if (publicSafeToRemove) {
                    mRow.getPublicLayout().setAmbientChild(null);
                }
                if (privateSafeToRemove && publicSafeToRemove) {
                    mCachedContentViews.remove(FLAG_CONTENT_VIEW_AMBIENT);
                }
                break;
            case FLAG_CONTENT_VIEW_PUBLIC:
                if (mRow.getPublicLayout().isContentViewInactive(VISIBLE_TYPE_CONTRACTED)) {
                    mRow.getPublicLayout().setContractedChild(null);
                    mCachedContentViews.remove(FLAG_CONTENT_VIEW_PUBLIC);
                }
                break;
            case FLAG_CONTENT_VIEW_CONTRACTED:
            case FLAG_CONTENT_VIEW_EXPANDED:
            default:
                break;
        }
    }

    private static InflationProgress inflateSmartReplyViews(InflationProgress result,
            @InflationFlag int reInflateFlags, NotificationEntry entry, Context context,
            HeadsUpManager headsUpManager) {
        SmartReplyConstants smartReplyConstants = Dependency.get(SmartReplyConstants.class);
        SmartReplyController smartReplyController = Dependency.get(SmartReplyController.class);
        if ((reInflateFlags & FLAG_CONTENT_VIEW_EXPANDED) != 0 && result.newExpandedView != null) {
            result.expandedInflatedSmartReplies =
                    InflatedSmartReplies.inflate(
                            context, entry, smartReplyConstants, smartReplyController,
                            headsUpManager);
        }
        if ((reInflateFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0 && result.newHeadsUpView != null) {
            result.headsUpInflatedSmartReplies =
                    InflatedSmartReplies.inflate(
                            context, entry, smartReplyConstants, smartReplyController,
                            headsUpManager);
        }
        return result;
    }

    private static InflationProgress createRemoteViews(@InflationFlag int reInflateFlags,
            Notification.Builder builder, boolean isLowPriority, boolean isChildInGroup,
            boolean usesIncreasedHeight, boolean usesIncreasedHeadsUpHeight, boolean redactAmbient,
            Context packageContext) {
        InflationProgress result = new InflationProgress();
        isLowPriority = isLowPriority && !isChildInGroup;

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
            result.newPublicView = builder.makePublicContentView();
        }

        if ((reInflateFlags & FLAG_CONTENT_VIEW_AMBIENT) != 0) {
            result.newAmbientView = redactAmbient ? builder.makePublicAmbientNotification()
                    : builder.makeAmbientNotification();
        }
        result.packageContext = packageContext;
        result.headsUpStatusBarText = builder.getHeadsUpStatusBarText(false /* showingPublic */);
        result.headsUpStatusBarTextPublic = builder.getHeadsUpStatusBarText(
                true /* showingPublic */);
        return result;
    }

    public static CancellationSignal apply(
            boolean inflateSynchronously,
            InflationProgress result,
            @InflationFlag int reInflateFlags,
            ArrayMap<Integer, RemoteViews> cachedContentViews,
            ExpandableNotificationRow row,
            boolean redactAmbient,
            RemoteViews.OnClickHandler remoteViewClickHandler,
            @Nullable InflationCallback callback) {
        NotificationContentView privateLayout = row.getPrivateLayout();
        NotificationContentView publicLayout = row.getPublicLayout();
        final HashMap<Integer, CancellationSignal> runningInflations = new HashMap<>();

        int flag = FLAG_CONTENT_VIEW_CONTRACTED;
        if ((reInflateFlags & flag) != 0) {
            boolean isNewView =
                    !canReapplyRemoteView(result.newContentView,
                            cachedContentViews.get(FLAG_CONTENT_VIEW_CONTRACTED));
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
            applyRemoteView(inflateSynchronously, result, reInflateFlags, flag, cachedContentViews,
                    row, redactAmbient, isNewView, remoteViewClickHandler, callback, privateLayout,
                    privateLayout.getContractedChild(), privateLayout.getVisibleWrapper(
                            NotificationContentView.VISIBLE_TYPE_CONTRACTED),
                    runningInflations, applyCallback);
        }

        flag = FLAG_CONTENT_VIEW_EXPANDED;
        if ((reInflateFlags & flag) != 0) {
            if (result.newExpandedView != null) {
                boolean isNewView =
                        !canReapplyRemoteView(result.newExpandedView,
                                cachedContentViews.get(FLAG_CONTENT_VIEW_EXPANDED));
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
                applyRemoteView(inflateSynchronously, result, reInflateFlags, flag,
                        cachedContentViews, row, redactAmbient, isNewView, remoteViewClickHandler,
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
                                cachedContentViews.get(FLAG_CONTENT_VIEW_HEADS_UP));
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
                applyRemoteView(inflateSynchronously, result, reInflateFlags, flag,
                        cachedContentViews, row, redactAmbient, isNewView, remoteViewClickHandler,
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
                            cachedContentViews.get(FLAG_CONTENT_VIEW_PUBLIC));
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
            applyRemoteView(inflateSynchronously, result, reInflateFlags, flag, cachedContentViews,
                    row, redactAmbient, isNewView, remoteViewClickHandler, callback,
                    publicLayout, publicLayout.getContractedChild(),
                    publicLayout.getVisibleWrapper(NotificationContentView.VISIBLE_TYPE_CONTRACTED),
                    runningInflations, applyCallback);
        }

        flag = FLAG_CONTENT_VIEW_AMBIENT;
        if ((reInflateFlags & flag) != 0) {
            NotificationContentView newParent = redactAmbient ? publicLayout : privateLayout;
            boolean isNewView = (!canReapplyAmbient(row, redactAmbient)
                    || !canReapplyRemoteView(result.newAmbientView,
                            cachedContentViews.get(FLAG_CONTENT_VIEW_AMBIENT)));
            ApplyCallback applyCallback = new ApplyCallback() {
                @Override
                public void setResultView(View v) {
                    result.inflatedAmbientView = v;
                }

                @Override
                public RemoteViews getRemoteView() {
                    return result.newAmbientView;
                }
            };
            applyRemoteView(inflateSynchronously, result, reInflateFlags, flag, cachedContentViews,
                    row, redactAmbient, isNewView, remoteViewClickHandler, callback,
                    newParent, newParent.getAmbientChild(), newParent.getVisibleWrapper(
                            NotificationContentView.VISIBLE_TYPE_AMBIENT), runningInflations,
                    applyCallback);
        }

        // Let's try to finish, maybe nobody is even inflating anything
        finishIfDone(result, reInflateFlags, cachedContentViews, runningInflations, callback, row,
                redactAmbient);
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.setOnCancelListener(
                () -> runningInflations.values().forEach(CancellationSignal::cancel));
        return cancellationSignal;
    }

    @VisibleForTesting
    static void applyRemoteView(
            boolean inflateSynchronously,
            final InflationProgress result,
            final @InflationFlag int reInflateFlags,
            @InflationFlag int inflationId,
            final ArrayMap<Integer, RemoteViews> cachedContentViews,
            final ExpandableNotificationRow row,
            final boolean redactAmbient,
            boolean isNewView,
            RemoteViews.OnClickHandler remoteViewClickHandler,
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
                    v.setIsRootNamespace(true);
                    applyCallback.setResultView(v);
                } else {
                    newContentView.reapply(
                            result.packageContext,
                            existingView,
                            remoteViewClickHandler);
                    existingWrapper.onReinflated();
                }
            } catch (Exception e) {
                handleInflationError(runningInflations, e, row.getStatusBarNotification(), callback);
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
                if (isNewView) {
                    v.setIsRootNamespace(true);
                    applyCallback.setResultView(v);
                } else if (existingWrapper != null) {
                    existingWrapper.onReinflated();
                }
                runningInflations.remove(inflationId);
                finishIfDone(result, reInflateFlags, cachedContentViews, runningInflations,
                        callback, row, redactAmbient);
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
                    handleInflationError(runningInflations, e, row.getStatusBarNotification(),
                            callback);
                }
            }
        };
        CancellationSignal cancellationSignal;
        if (isNewView) {
            cancellationSignal = newContentView.applyAsync(
                    result.packageContext,
                    parentLayout,
                    EXECUTOR,
                    listener,
                    remoteViewClickHandler);
        } else {
            cancellationSignal = newContentView.reapplyAsync(
                    result.packageContext,
                    existingView,
                    EXECUTOR,
                    listener,
                    remoteViewClickHandler);
        }
        runningInflations.put(inflationId, cancellationSignal);
    }

    private static void handleInflationError(
            HashMap<Integer, CancellationSignal> runningInflations, Exception e,
            StatusBarNotification notification, @Nullable InflationCallback callback) {
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
            @InflationFlag int reInflateFlags, ArrayMap<Integer, RemoteViews> cachedContentViews,
            HashMap<Integer, CancellationSignal> runningInflations,
            @Nullable InflationCallback endListener, ExpandableNotificationRow row,
            boolean redactAmbient) {
        Assert.isMainThread();
        NotificationEntry entry = row.getEntry();
        NotificationContentView privateLayout = row.getPrivateLayout();
        NotificationContentView publicLayout = row.getPublicLayout();
        if (runningInflations.isEmpty()) {
            if ((reInflateFlags & FLAG_CONTENT_VIEW_CONTRACTED) != 0) {
                if (result.inflatedContentView != null) {
                    // New view case
                    privateLayout.setContractedChild(result.inflatedContentView);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_CONTRACTED, result.newContentView);
                } else if (cachedContentViews.get(FLAG_CONTENT_VIEW_CONTRACTED) != null) {
                    // Reinflation case. Only update if it's still cached (i.e. view has not been
                    // freed while inflating).
                    cachedContentViews.put(FLAG_CONTENT_VIEW_CONTRACTED, result.newContentView);
                }
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_EXPANDED) != 0) {
                if (result.inflatedExpandedView != null) {
                    privateLayout.setExpandedChild(result.inflatedExpandedView);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_EXPANDED, result.newExpandedView);
                } else if (result.newExpandedView == null) {
                    privateLayout.setExpandedChild(null);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_EXPANDED, null);
                } else if (cachedContentViews.get(FLAG_CONTENT_VIEW_EXPANDED) != null) {
                    cachedContentViews.put(FLAG_CONTENT_VIEW_EXPANDED, result.newExpandedView);
                }
                if (result.newExpandedView != null) {
                    privateLayout.setExpandedInflatedSmartReplies(
                            result.expandedInflatedSmartReplies);
                } else {
                    privateLayout.setExpandedInflatedSmartReplies(null);
                }
                row.setExpandable(result.newExpandedView != null);
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0) {
                if (result.inflatedHeadsUpView != null) {
                    privateLayout.setHeadsUpChild(result.inflatedHeadsUpView);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_HEADS_UP, result.newHeadsUpView);
                } else if (result.newHeadsUpView == null) {
                    privateLayout.setHeadsUpChild(null);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_HEADS_UP, null);
                } else if (cachedContentViews.get(FLAG_CONTENT_VIEW_HEADS_UP) != null) {
                    cachedContentViews.put(FLAG_CONTENT_VIEW_HEADS_UP, result.newHeadsUpView);
                }
                if (result.newHeadsUpView != null) {
                    privateLayout.setHeadsUpInflatedSmartReplies(
                            result.headsUpInflatedSmartReplies);
                } else {
                    privateLayout.setHeadsUpInflatedSmartReplies(null);
                }
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_PUBLIC) != 0) {
                if (result.inflatedPublicView != null) {
                    publicLayout.setContractedChild(result.inflatedPublicView);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_PUBLIC, result.newPublicView);
                } else if (cachedContentViews.get(FLAG_CONTENT_VIEW_PUBLIC) != null) {
                    cachedContentViews.put(FLAG_CONTENT_VIEW_PUBLIC, result.newPublicView);
                }
            }

            if ((reInflateFlags & FLAG_CONTENT_VIEW_AMBIENT) != 0) {
                if (result.inflatedAmbientView != null) {
                    NotificationContentView newParent = redactAmbient
                            ? publicLayout : privateLayout;
                    NotificationContentView otherParent = !redactAmbient
                            ? publicLayout : privateLayout;
                    newParent.setAmbientChild(result.inflatedAmbientView);
                    otherParent.setAmbientChild(null);
                    cachedContentViews.put(FLAG_CONTENT_VIEW_AMBIENT, result.newAmbientView);
                } else if (cachedContentViews.get(FLAG_CONTENT_VIEW_AMBIENT) != null) {
                    cachedContentViews.put(FLAG_CONTENT_VIEW_AMBIENT, result.newAmbientView);
                }
            }
            entry.headsUpStatusBarText = result.headsUpStatusBarText;
            entry.headsUpStatusBarTextPublic = result.headsUpStatusBarTextPublic;
            if (endListener != null) {
                endListener.onAsyncInflationFinished(row.getEntry(), reInflateFlags);
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

    public void setInflationCallback(InflationCallback callback) {
        mCallback = callback;
    }

    public interface InflationCallback {
        void handleInflationException(StatusBarNotification notification, Exception e);

        /**
         * Callback for after the content views finish inflating.
         *
         * @param entry the entry with the content views set
         * @param inflatedFlags the flags associated with the content views that were inflated
         */
        void onAsyncInflationFinished(NotificationEntry entry, @InflationFlag int inflatedFlags);
    }

    public void clearCachesAndReInflate() {
        mCachedContentViews.clear();
        inflateNotificationViews();
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    void setInflateSynchronously(boolean inflateSynchronously) {
        mInflateSynchronously = inflateSynchronously;
    }

    private static boolean canReapplyAmbient(ExpandableNotificationRow row, boolean redactAmbient) {
        NotificationContentView ambientView = redactAmbient ? row.getPublicLayout()
                : row.getPrivateLayout();
        return ambientView.getAmbientChild() != null;
    }

    public static class AsyncInflationTask extends AsyncTask<Void, Void, InflationProgress>
            implements InflationCallback, InflationTask {

        private final StatusBarNotification mSbn;
        private final Context mContext;
        private final boolean mInflateSynchronously;
        private final boolean mIsLowPriority;
        private final boolean mIsChildInGroup;
        private final boolean mUsesIncreasedHeight;
        private final InflationCallback mCallback;
        private final boolean mUsesIncreasedHeadsUpHeight;
        private final boolean mRedactAmbient;
        private @InflationFlag int mReInflateFlags;
        private final ArrayMap<Integer, RemoteViews> mCachedContentViews;
        private ExpandableNotificationRow mRow;
        private Exception mError;
        private RemoteViews.OnClickHandler mRemoteViewClickHandler;
        private CancellationSignal mCancellationSignal;

        private AsyncInflationTask(
                StatusBarNotification notification,
                boolean inflateSynchronously,
                @InflationFlag int reInflateFlags,
                ArrayMap<Integer, RemoteViews> cachedContentViews,
                ExpandableNotificationRow row,
                boolean isLowPriority,
                boolean isChildInGroup,
                boolean usesIncreasedHeight,
                boolean usesIncreasedHeadsUpHeight,
                boolean redactAmbient,
                InflationCallback callback,
                RemoteViews.OnClickHandler remoteViewClickHandler) {
            mRow = row;
            mSbn = notification;
            mInflateSynchronously = inflateSynchronously;
            mReInflateFlags = reInflateFlags;
            mCachedContentViews = cachedContentViews;
            mContext = mRow.getContext();
            mIsLowPriority = isLowPriority;
            mIsChildInGroup = isChildInGroup;
            mUsesIncreasedHeight = usesIncreasedHeight;
            mUsesIncreasedHeadsUpHeight = usesIncreasedHeadsUpHeight;
            mRedactAmbient = redactAmbient;
            mRemoteViewClickHandler = remoteViewClickHandler;
            mCallback = callback;
            NotificationEntry entry = row.getEntry();
            entry.setInflationTask(this);
        }

        @VisibleForTesting
        @InflationFlag
        public int getReInflateFlags() {
            return mReInflateFlags;
        }

        @Override
        protected InflationProgress doInBackground(Void... params) {
            try {
                final Notification.Builder recoveredBuilder
                        = Notification.Builder.recoverBuilder(mContext,
                        mSbn.getNotification());

                Context packageContext = mSbn.getPackageContext(mContext);
                Notification notification = mSbn.getNotification();
                if (notification.isMediaNotification()) {
                    MediaNotificationProcessor processor = new MediaNotificationProcessor(mContext,
                            packageContext);
                    processor.processNotification(notification, recoveredBuilder);
                }
                InflationProgress inflationProgress = createRemoteViews(mReInflateFlags,
                        recoveredBuilder, mIsLowPriority,
                        mIsChildInGroup, mUsesIncreasedHeight, mUsesIncreasedHeadsUpHeight,
                        mRedactAmbient, packageContext);
                return inflateSmartReplyViews(inflationProgress, mReInflateFlags, mRow.getEntry(),
                        mRow.getContext(), mRow.getHeadsUpManager());
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(InflationProgress result) {
            if (mError == null) {
                mCancellationSignal = apply(mInflateSynchronously, result, mReInflateFlags,
                        mCachedContentViews, mRow, mRedactAmbient, mRemoteViewClickHandler, this);
            } else {
                handleError(mError);
            }
        }

        private void handleError(Exception e) {
            mRow.getEntry().onInflationTaskFinished();
            StatusBarNotification sbn = mRow.getStatusBarNotification();
            final String ident = sbn.getPackageName() + "/0x"
                    + Integer.toHexString(sbn.getId());
            Log.e(StatusBar.TAG, "couldn't inflate view for notification " + ident, e);
            mCallback.handleInflationException(sbn,
                    new InflationException("Couldn't inflate contentViews" + e));
        }

        @Override
        public void abort() {
            cancel(true /* mayInterruptIfRunning */);
            if (mCancellationSignal != null) {
                mCancellationSignal.cancel();
            }
        }

        @Override
        public void supersedeTask(InflationTask task) {
            if (task instanceof AsyncInflationTask) {
                // We want to inflate all flags of the previous task as well
                mReInflateFlags |= ((AsyncInflationTask) task).mReInflateFlags;
            }
        }

        @Override
        public void handleInflationException(StatusBarNotification notification, Exception e) {
            handleError(e);
        }

        @Override
        public void onAsyncInflationFinished(NotificationEntry entry,
                @InflationFlag int inflatedFlags) {
            mRow.getEntry().onInflationTaskFinished();
            mRow.onNotificationUpdated();
            mCallback.onAsyncInflationFinished(mRow.getEntry(), inflatedFlags);

            // Notify the resolver that the inflation task has finished,
            // try to purge unnecessary cached entries.
            mRow.getImageResolver().purgeCache();
        }
    }

    @VisibleForTesting
    static class InflationProgress {
        private RemoteViews newContentView;
        private RemoteViews newHeadsUpView;
        private RemoteViews newExpandedView;
        private RemoteViews newAmbientView;
        private RemoteViews newPublicView;

        @VisibleForTesting
        Context packageContext;

        private View inflatedContentView;
        private View inflatedHeadsUpView;
        private View inflatedExpandedView;
        private View inflatedAmbientView;
        private View inflatedPublicView;
        private CharSequence headsUpStatusBarText;
        private CharSequence headsUpStatusBarTextPublic;

        private InflatedSmartReplies expandedInflatedSmartReplies;
        private InflatedSmartReplies headsUpInflatedSmartReplies;
    }

    @VisibleForTesting
    abstract static class ApplyCallback {
        public abstract void setResultView(View v);
        public abstract RemoteViews getRemoteView();
    }

    /**
     * A custom executor that allows more tasks to be queued. Default values are copied from
     * AsyncTask
      */
    private static class InflationExecutor implements Executor {
        private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
        // We want at least 2 threads and at most 4 threads in the core pool,
        // preferring to have 1 less than the CPU count to avoid saturating
        // the CPU with background work
        private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
        private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
        private static final int KEEP_ALIVE_SECONDS = 30;

        private static final ThreadFactory sThreadFactory = new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);

            public Thread newThread(Runnable r) {
                return new Thread(r, "InflaterThread #" + mCount.getAndIncrement());
            }
        };

        private final ThreadPoolExecutor mExecutor;

        private InflationExecutor() {
            mExecutor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), sThreadFactory);
            mExecutor.allowCoreThreadTimeOut(true);
        }

        @Override
        public void execute(Runnable runnable) {
            mExecutor.execute(runnable);
        }
    }
}
