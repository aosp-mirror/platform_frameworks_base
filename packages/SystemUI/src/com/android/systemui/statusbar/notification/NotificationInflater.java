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

package com.android.systemui.statusbar.notification;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationContentView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.util.Assert;

import java.util.HashMap;

/**
 * A utility that inflates the right kind of contentView based on the state
 */
public class NotificationInflater {

    @VisibleForTesting
    static final int FLAG_REINFLATE_ALL = ~0;
    private static final int FLAG_REINFLATE_CONTENT_VIEW = 1<<0;
    @VisibleForTesting
    static final int FLAG_REINFLATE_EXPANDED_VIEW = 1<<1;
    private static final int FLAG_REINFLATE_HEADS_UP_VIEW = 1<<2;
    private static final int FLAG_REINFLATE_PUBLIC_VIEW = 1<<3;
    private static final int FLAG_REINFLATE_AMBIENT_VIEW = 1<<4;

    private final ExpandableNotificationRow mRow;
    private boolean mIsLowPriority;
    private boolean mUsesIncreasedHeight;
    private boolean mUsesIncreasedHeadsUpHeight;
    private RemoteViews.OnClickHandler mRemoteViewClickHandler;
    private boolean mIsChildInGroup;
    private InflationCallback mCallback;
    private boolean mRedactAmbient;

    public NotificationInflater(ExpandableNotificationRow row) {
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
                int flags = FLAG_REINFLATE_CONTENT_VIEW | FLAG_REINFLATE_EXPANDED_VIEW;
                inflateNotificationViews(flags);
            }
        } ;
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

    public void setRedactAmbient(boolean redactAmbient) {
        if (mRedactAmbient != redactAmbient) {
            mRedactAmbient = redactAmbient;
            if (mRow.getEntry() == null) {
                return;
            }
            inflateNotificationViews(FLAG_REINFLATE_AMBIENT_VIEW);
        }
    }

    /**
     * Inflate all views of this notification on a background thread. This is asynchronous and will
     * notify the callback once it's finished.
     */
    public void inflateNotificationViews() {
        inflateNotificationViews(FLAG_REINFLATE_ALL);
    }

    /**
     * Reinflate all views for the specified flags on a background thread. This is asynchronous and
     * will notify the callback once it's finished.
     *
     * @param reInflateFlags flags which views should be reinflated. Use {@link #FLAG_REINFLATE_ALL}
     *                       to reinflate all of views.
     */
    @VisibleForTesting
    void inflateNotificationViews(int reInflateFlags) {
        if (mRow.isRemoved()) {
            // We don't want to reinflate anything for removed notifications. Otherwise views might
            // be readded to the stack, leading to leaks. This may happen with low-priority groups
            // where the removal of already removed children can lead to a reinflation.
            return;
        }
        StatusBarNotification sbn = mRow.getEntry().notification;
        new AsyncInflationTask(sbn, reInflateFlags, mRow, mIsLowPriority,
                mIsChildInGroup, mUsesIncreasedHeight, mUsesIncreasedHeadsUpHeight, mRedactAmbient,
                mCallback, mRemoteViewClickHandler).execute();
    }

    @VisibleForTesting
    InflationProgress inflateNotificationViews(int reInflateFlags,
            Notification.Builder builder, Context packageContext) {
        InflationProgress result = createRemoteViews(reInflateFlags, builder, mIsLowPriority,
                mIsChildInGroup, mUsesIncreasedHeight, mUsesIncreasedHeadsUpHeight,
                mRedactAmbient, packageContext);
        apply(result, reInflateFlags, mRow, mRedactAmbient, mRemoteViewClickHandler, null);
        return result;
    }

    private static InflationProgress createRemoteViews(int reInflateFlags,
            Notification.Builder builder, boolean isLowPriority, boolean isChildInGroup,
            boolean usesIncreasedHeight, boolean usesIncreasedHeadsUpHeight, boolean redactAmbient,
            Context packageContext) {
        InflationProgress result = new InflationProgress();
        isLowPriority = isLowPriority && !isChildInGroup;
        if ((reInflateFlags & FLAG_REINFLATE_CONTENT_VIEW) != 0) {
            result.newContentView = createContentView(builder, isLowPriority, usesIncreasedHeight);
        }

        if ((reInflateFlags & FLAG_REINFLATE_EXPANDED_VIEW) != 0) {
            result.newExpandedView = createExpandedView(builder, isLowPriority);
        }

        if ((reInflateFlags & FLAG_REINFLATE_HEADS_UP_VIEW) != 0) {
            result.newHeadsUpView = builder.createHeadsUpContentView(usesIncreasedHeadsUpHeight);
        }

        if ((reInflateFlags & FLAG_REINFLATE_PUBLIC_VIEW) != 0) {
            result.newPublicView = builder.makePublicContentView();
        }

        if ((reInflateFlags & FLAG_REINFLATE_AMBIENT_VIEW) != 0) {
            result.newAmbientView = redactAmbient ? builder.makePublicAmbientNotification()
                    : builder.makeAmbientNotification();
        }
        result.packageContext = packageContext;
        return result;
    }

    public static CancellationSignal apply(InflationProgress result, int reInflateFlags,
            ExpandableNotificationRow row, boolean redactAmbient,
            RemoteViews.OnClickHandler remoteViewClickHandler,
            @Nullable InflationCallback callback) {
        NotificationData.Entry entry = row.getEntry();
        NotificationContentView privateLayout = row.getPrivateLayout();
        NotificationContentView publicLayout = row.getPublicLayout();
        final HashMap<Integer, CancellationSignal> runningInflations = new HashMap<>();

        int flag = FLAG_REINFLATE_CONTENT_VIEW;
        if ((reInflateFlags & flag) != 0) {
            boolean isNewView = !compareRemoteViews(result.newContentView, entry.cachedContentView);
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
            applyRemoteView(result, reInflateFlags, flag, row, redactAmbient,
                    isNewView, remoteViewClickHandler, callback, entry, privateLayout,
                    privateLayout.getContractedChild(),
                    runningInflations, applyCallback);
        }

        flag = FLAG_REINFLATE_EXPANDED_VIEW;
        if ((reInflateFlags & flag) != 0) {
            if (result.newExpandedView != null) {
                boolean isNewView = !compareRemoteViews(result.newExpandedView,
                        entry.cachedBigContentView);
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
                applyRemoteView(result, reInflateFlags, flag, row,
                        redactAmbient, isNewView, remoteViewClickHandler, callback, entry,
                        privateLayout, privateLayout.getExpandedChild(), runningInflations,
                        applyCallback);
            }
        }

        flag = FLAG_REINFLATE_HEADS_UP_VIEW;
        if ((reInflateFlags & flag) != 0) {
            if (result.newHeadsUpView != null) {
                boolean isNewView = !compareRemoteViews(result.newHeadsUpView,
                        entry.cachedHeadsUpContentView);
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
                applyRemoteView(result, reInflateFlags, flag, row,
                        redactAmbient, isNewView, remoteViewClickHandler, callback, entry,
                        privateLayout, privateLayout.getHeadsUpChild(), runningInflations,
                        applyCallback);
            }
        }

        flag = FLAG_REINFLATE_PUBLIC_VIEW;
        if ((reInflateFlags & flag) != 0) {
            boolean isNewView = !compareRemoteViews(result.newPublicView,
                    entry.cachedPublicContentView);
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
            applyRemoteView(result, reInflateFlags, flag, row,
                    redactAmbient, isNewView, remoteViewClickHandler, callback, entry,
                    publicLayout, publicLayout.getContractedChild(), runningInflations,
                    applyCallback);
        }

        flag = FLAG_REINFLATE_AMBIENT_VIEW;
        if ((reInflateFlags & flag) != 0) {
            NotificationContentView newParent = redactAmbient ? publicLayout : privateLayout;
            boolean isNewView = !canReapplyAmbient(row, redactAmbient) ||
                    !compareRemoteViews(result.newAmbientView, entry.cachedAmbientContentView);
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
            applyRemoteView(result, reInflateFlags, flag, row,
                    redactAmbient, isNewView, remoteViewClickHandler, callback, entry,
                    newParent, newParent.getAmbientChild(), runningInflations,
                    applyCallback);
        }

        // Let's try to finish, maybe nobody is even inflating anything
        finishIfDone(result, reInflateFlags, runningInflations, callback, row,
                redactAmbient);
        CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.setOnCancelListener(
                () -> runningInflations.values().forEach(CancellationSignal::cancel));
        return cancellationSignal;
    }

    private static void applyRemoteView(final InflationProgress result,
            final int reInflateFlags, int inflationId,
            final ExpandableNotificationRow row,
            final boolean redactAmbient, boolean isNewView,
            RemoteViews.OnClickHandler remoteViewClickHandler,
            @Nullable final InflationCallback callback, NotificationData.Entry entry,
            NotificationContentView parentLayout, View existingView,
            final HashMap<Integer, CancellationSignal> runningInflations,
            ApplyCallback applyCallback) {
        RemoteViews.OnViewAppliedListener listener
                = new RemoteViews.OnViewAppliedListener() {

            @Override
            public void onViewApplied(View v) {
                if (isNewView) {
                    v.setIsRootNamespace(true);
                    applyCallback.setResultView(v);
                }
                runningInflations.remove(inflationId);
                finishIfDone(result, reInflateFlags, runningInflations, callback, row,
                        redactAmbient);
            }

            @Override
            public void onError(Exception e) {
                runningInflations.remove(inflationId);
                handleInflationError(runningInflations, e, entry.notification, callback);
            }
        };
        CancellationSignal cancellationSignal;
        RemoteViews newContentView = applyCallback.getRemoteView();
        if (isNewView) {
            cancellationSignal = newContentView.applyAsync(
                    result.packageContext,
                    parentLayout,
                    null /* executor */,
                    listener,
                    remoteViewClickHandler);
        } else {
            cancellationSignal = newContentView.reapplyAsync(
                    result.packageContext,
                    existingView,
                    null /* executor */,
                    listener,
                    remoteViewClickHandler);
        }
        runningInflations.put(inflationId, cancellationSignal);
    }

    private static void handleInflationError(HashMap<Integer, CancellationSignal> runningInflations,
            Exception e, StatusBarNotification notification, @Nullable InflationCallback callback) {
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
    private static boolean finishIfDone(InflationProgress result, int reInflateFlags,
            HashMap<Integer, CancellationSignal> runningInflations,
            @Nullable InflationCallback endListener, ExpandableNotificationRow row,
            boolean redactAmbient) {
        Assert.isMainThread();
        NotificationData.Entry entry = row.getEntry();
        NotificationContentView privateLayout = row.getPrivateLayout();
        NotificationContentView publicLayout = row.getPublicLayout();
        if (runningInflations.isEmpty()) {
            if ((reInflateFlags & FLAG_REINFLATE_CONTENT_VIEW) != 0) {
                if (result.inflatedContentView != null) {
                    privateLayout.setContractedChild(result.inflatedContentView);
                }
                entry.cachedContentView = result.newContentView;
            }

            if ((reInflateFlags & FLAG_REINFLATE_EXPANDED_VIEW) != 0) {
                if (result.inflatedExpandedView != null) {
                    privateLayout.setExpandedChild(result.inflatedExpandedView);
                } else if (result.newExpandedView == null) {
                    privateLayout.setExpandedChild(null);
                }
                entry.cachedBigContentView = result.newExpandedView;
                row.setExpandable(result.newExpandedView != null);
            }

            if ((reInflateFlags & FLAG_REINFLATE_HEADS_UP_VIEW) != 0) {
                if (result.inflatedHeadsUpView != null) {
                    privateLayout.setHeadsUpChild(result.inflatedHeadsUpView);
                } else if (result.newHeadsUpView == null) {
                    privateLayout.setHeadsUpChild(null);
                }
                entry.cachedHeadsUpContentView = result.newHeadsUpView;
            }

            if ((reInflateFlags & FLAG_REINFLATE_PUBLIC_VIEW) != 0) {
                if (result.inflatedPublicView != null) {
                    publicLayout.setContractedChild(result.inflatedPublicView);
                }
                entry.cachedPublicContentView = result.newPublicView;
            }

            if ((reInflateFlags & FLAG_REINFLATE_AMBIENT_VIEW) != 0) {
                if (result.inflatedAmbientView != null) {
                    NotificationContentView newParent = redactAmbient
                            ? publicLayout : privateLayout;
                    NotificationContentView otherParent = !redactAmbient
                            ? publicLayout : privateLayout;
                    newParent.setAmbientChild(result.inflatedAmbientView);
                    otherParent.setAmbientChild(null);
                }
                entry.cachedAmbientContentView = result.newAmbientView;
            }
            if (endListener != null) {
                endListener.onAsyncInflationFinished(row.getEntry());
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

    // Returns true if the RemoteViews are the same.
    private static boolean compareRemoteViews(final RemoteViews a, final RemoteViews b) {
        return (a == null && b == null) ||
                (a != null && b != null
                        && b.getPackage() != null
                        && a.getPackage() != null
                        && a.getPackage().equals(b.getPackage())
                        && a.getLayoutId() == b.getLayoutId());
    }

    public void setInflationCallback(InflationCallback callback) {
        mCallback = callback;
    }

    public interface InflationCallback {
        void handleInflationException(StatusBarNotification notification, Exception e);
        void onAsyncInflationFinished(NotificationData.Entry entry);
    }

    public void onDensityOrFontScaleChanged() {
        NotificationData.Entry entry = mRow.getEntry();
        entry.cachedAmbientContentView = null;
        entry.cachedBigContentView = null;
        entry.cachedContentView = null;
        entry.cachedHeadsUpContentView = null;
        entry.cachedPublicContentView = null;
        inflateNotificationViews();
    }

    private static boolean canReapplyAmbient(ExpandableNotificationRow row, boolean redactAmbient) {
        NotificationContentView ambientView = redactAmbient ? row.getPublicLayout()
                : row.getPrivateLayout();            ;
        return ambientView.getAmbientChild() != null;
    }

    public static class AsyncInflationTask extends AsyncTask<Void, Void, InflationProgress>
            implements InflationCallback, InflationTask {

        private final StatusBarNotification mSbn;
        private final Context mContext;
        private final boolean mIsLowPriority;
        private final boolean mIsChildInGroup;
        private final boolean mUsesIncreasedHeight;
        private final InflationCallback mCallback;
        private final boolean mUsesIncreasedHeadsUpHeight;
        private final boolean mRedactAmbient;
        private int mReInflateFlags;
        private ExpandableNotificationRow mRow;
        private Exception mError;
        private RemoteViews.OnClickHandler mRemoteViewClickHandler;
        private CancellationSignal mCancellationSignal;

        private AsyncInflationTask(StatusBarNotification notification,
                int reInflateFlags, ExpandableNotificationRow row, boolean isLowPriority,
                boolean isChildInGroup, boolean usesIncreasedHeight,
                boolean usesIncreasedHeadsUpHeight, boolean redactAmbient,
                InflationCallback callback,
                RemoteViews.OnClickHandler remoteViewClickHandler) {
            mRow = row;
            mSbn = notification;
            mReInflateFlags = reInflateFlags;
            mContext = mRow.getContext();
            mIsLowPriority = isLowPriority;
            mIsChildInGroup = isChildInGroup;
            mUsesIncreasedHeight = usesIncreasedHeight;
            mUsesIncreasedHeadsUpHeight = usesIncreasedHeadsUpHeight;
            mRedactAmbient = redactAmbient;
            mRemoteViewClickHandler = remoteViewClickHandler;
            mCallback = callback;
            NotificationData.Entry entry = row.getEntry();
            entry.setInflationTask(this);
        }

        @VisibleForTesting
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
                if (mIsLowPriority) {
                    int backgroundColor = mContext.getColor(
                            R.color.notification_material_background_low_priority_color);
                    recoveredBuilder.setBackgroundColorHint(backgroundColor);
                }
                if (notification.isMediaNotification()) {
                    MediaNotificationProcessor processor = new MediaNotificationProcessor(mContext,
                            packageContext);
                    processor.setIsLowPriority(mIsLowPriority);
                    processor.processNotification(notification, recoveredBuilder);
                }
                return createRemoteViews(mReInflateFlags,
                        recoveredBuilder, mIsLowPriority, mIsChildInGroup,
                        mUsesIncreasedHeight, mUsesIncreasedHeadsUpHeight, mRedactAmbient,
                        packageContext);
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(InflationProgress result) {
            if (mError == null) {
                mCancellationSignal = apply(result, mReInflateFlags, mRow, mRedactAmbient,
                        mRemoteViewClickHandler, this);
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
        public void onAsyncInflationFinished(NotificationData.Entry entry) {
            mRow.getEntry().onInflationTaskFinished();
            mRow.onNotificationUpdated();
            mCallback.onAsyncInflationFinished(mRow.getEntry());
        }
    }

    private static class InflationProgress {
        private RemoteViews newContentView;
        private RemoteViews newHeadsUpView;
        private RemoteViews newExpandedView;
        private RemoteViews newAmbientView;
        private RemoteViews newPublicView;

        private Context packageContext;

        private View inflatedContentView;
        private View inflatedHeadsUpView;
        private View inflatedExpandedView;
        private View inflatedAmbientView;
        private View inflatedPublicView;
    }

    private abstract static class ApplyCallback {
        public abstract void setResultView(View v);
        public abstract RemoteViews getRemoteView();
    }
}
