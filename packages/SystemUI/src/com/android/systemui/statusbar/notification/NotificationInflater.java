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

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationContentView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.Objects;

/**
 * A utility that inflates the right kind of contentView based on the state
 */
public class NotificationInflater {

    private static final int FLAG_REINFLATE_ALL = ~0;
    private static final int FLAG_REINFLATE_CONTENT_VIEW = 1<<0;
    private static final int FLAG_REINFLATE_EXPANDED_VIEW = 1<<1;
    private static final int FLAG_REINFLATE_HEADS_UP_VIEW = 1<<2;
    private static final int FLAG_REINFLATE_PUBLIC_VIEW = 1<<3;
    private static final int FLAG_REINFLATE_AMBIENT_VIEW = 1<<4;

    private final ExpandableNotificationRow mRow;
    private boolean mIsLowPriority;
    private boolean mUsesIncreasedHeight;
    private boolean mUsesIncreasedHeadsUpHeight;
    private RemoteViews.OnClickHandler mRemoteViewClickHandler;
    private boolean mIsChildInGroup;
    private InflationExceptionHandler mInflateExceptionHandler;

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
    public boolean setIsChildInGroup(boolean childInGroup) {
        if (childInGroup != mIsChildInGroup) {
            mIsChildInGroup = childInGroup;
            if (mIsLowPriority) {
                try {
                    int flags = FLAG_REINFLATE_CONTENT_VIEW | FLAG_REINFLATE_EXPANDED_VIEW;
                    inflateNotificationViews(flags);
                } catch (InflationException e) {
                    mInflateExceptionHandler.handleInflationException(
                            mRow.getStatusBarNotification(), e);
                }
            }
            return true;
        }
        return false;
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

    public void inflateNotificationViews() throws InflationException {
        inflateNotificationViews(FLAG_REINFLATE_ALL);
    }

    /**
     * reinflate all views for the specified flags
     * @param reInflateFlags flags which views should be reinflated. Use {@link #FLAG_REINFLATE_ALL}
     *                       to reinflate all of views.
     * @throws InflationException
     */
    private void inflateNotificationViews(int reInflateFlags)
            throws InflationException {
        NotificationData.Entry entry = mRow.getEntry();
        StatusBarNotification sbn = entry.notification;
        Context context = mRow.getContext();
        NotificationContentView privateLayout = mRow.getPrivateLayout();
        try {
            final Notification.Builder recoveredBuilder
                    = Notification.Builder.recoverBuilder(context, sbn.getNotification());
            boolean isLowPriority = mIsLowPriority && !mIsChildInGroup;
            if ((reInflateFlags & FLAG_REINFLATE_CONTENT_VIEW) != 0) {
                final RemoteViews newContentView = createContentView(recoveredBuilder,
                        isLowPriority, mUsesIncreasedHeadsUpHeight);
                if (!compareRemoteViews(newContentView,
                        entry.cachedContentView)) {
                    View contentViewLocal = newContentView.apply(
                            sbn.getPackageContext(context),
                            privateLayout,
                            mRemoteViewClickHandler);
                    contentViewLocal.setIsRootNamespace(true);
                    privateLayout.setContractedChild(contentViewLocal);
                } else {
                    newContentView.reapply(sbn.getPackageContext(context),
                            privateLayout.getContractedChild(),
                            mRemoteViewClickHandler);
                }
                entry.cachedContentView = newContentView;
            }

            if ((reInflateFlags & FLAG_REINFLATE_EXPANDED_VIEW) != 0) {
                final RemoteViews newBigContentView = createBigContentView(
                        recoveredBuilder, isLowPriority);
                if (newBigContentView != null) {
                    if (!compareRemoteViews(newBigContentView, entry.cachedBigContentView)) {
                        View bigContentViewLocal = newBigContentView.apply(
                                sbn.getPackageContext(context),
                                privateLayout,
                                mRemoteViewClickHandler);
                        bigContentViewLocal.setIsRootNamespace(true);
                        privateLayout.setExpandedChild(bigContentViewLocal);
                    } else {
                        newBigContentView.reapply(sbn.getPackageContext(context),
                                privateLayout.getExpandedChild(),
                                mRemoteViewClickHandler);
                    }
                } else if (entry.cachedBigContentView != null) {
                    privateLayout.setExpandedChild(null);
                }
                entry.cachedBigContentView = newBigContentView;
                mRow.setExpandable(newBigContentView != null);
            }

            if ((reInflateFlags & FLAG_REINFLATE_HEADS_UP_VIEW) != 0) {
                final RemoteViews newHeadsUpContentView =
                        recoveredBuilder.createHeadsUpContentView(mUsesIncreasedHeight);
                if (newHeadsUpContentView != null) {
                    if (!compareRemoteViews(newHeadsUpContentView,
                            entry.cachedHeadsUpContentView)) {
                        View headsUpContentViewLocal = newHeadsUpContentView.apply(
                                sbn.getPackageContext(context),
                                privateLayout,
                                mRemoteViewClickHandler);
                        headsUpContentViewLocal.setIsRootNamespace(true);
                        privateLayout.setHeadsUpChild(headsUpContentViewLocal);
                    } else {
                        newHeadsUpContentView.reapply(sbn.getPackageContext(context),
                                privateLayout.getHeadsUpChild(),
                                mRemoteViewClickHandler);
                    }
                } else if (entry.cachedHeadsUpContentView != null) {
                    privateLayout.setHeadsUpChild(null);
                }
                entry.cachedHeadsUpContentView = newHeadsUpContentView;
            }

            if ((reInflateFlags & FLAG_REINFLATE_PUBLIC_VIEW) != 0) {
                NotificationContentView publicLayout = mRow.getPublicLayout();
                final RemoteViews newPublicNotification
                        = recoveredBuilder.makePublicContentView();
                if (!compareRemoteViews(newPublicNotification, entry.cachedPublicContentView)) {
                    View publicContentView = newPublicNotification.apply(
                            sbn.getPackageContext(context),
                            publicLayout,
                            mRemoteViewClickHandler);
                    publicContentView.setIsRootNamespace(true);
                    publicLayout.setContractedChild(publicContentView);
                } else {
                    newPublicNotification.reapply(sbn.getPackageContext(context),
                            publicLayout.getContractedChild(),
                            mRemoteViewClickHandler);
                }
                entry.cachedPublicContentView = newPublicNotification;
            }

            if ((reInflateFlags & FLAG_REINFLATE_AMBIENT_VIEW) != 0) {
                final RemoteViews newAmbientNotification
                        = recoveredBuilder.makeAmbientNotification();
                if (!compareRemoteViews(newAmbientNotification, entry.cachedAmbientContentView)) {
                    View ambientContentView = newAmbientNotification.apply(
                            sbn.getPackageContext(context),
                            privateLayout,
                            mRemoteViewClickHandler);
                    ambientContentView.setIsRootNamespace(true);
                    privateLayout.setAmbientChild(ambientContentView);
                } else {
                    newAmbientNotification.reapply(sbn.getPackageContext(context),
                            privateLayout.getAmbientChild(),
                            mRemoteViewClickHandler);
                }
                entry.cachedAmbientContentView = newAmbientNotification;
            }

        } catch (RuntimeException e) {
            final String ident = sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId());
            Log.e(StatusBar.TAG, "couldn't inflate view for notification " + ident, e);
            throw new InflationException("Couldn't inflate contentViews");
        }
    }

    private RemoteViews createBigContentView(Notification.Builder builder,
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

    private RemoteViews createContentView(Notification.Builder builder,
            boolean isLowPriority, boolean useLarge) {
        if (isLowPriority) {
            return builder.makeLowPriorityContentView(false /* useRegularSubtext */);
        }
        return builder.createContentView(useLarge);
    }

    // Returns true if the RemoteViews are the same.
    private boolean compareRemoteViews(final RemoteViews a, final RemoteViews b) {
        return (a == null && b == null) ||
                (a != null && b != null
                        && b.getPackage() != null
                        && a.getPackage() != null
                        && a.getPackage().equals(b.getPackage())
                        && a.getLayoutId() == b.getLayoutId());
    }

    public void setInflateExceptionHandler(InflationExceptionHandler inflateExceptionHandler) {
        mInflateExceptionHandler = inflateExceptionHandler;
    }

    public interface InflationExceptionHandler {
        void handleInflationException(StatusBarNotification notification, InflationException e);
    }
    public void onDensityOrFontScaleChanged() {
        NotificationData.Entry entry = mRow.getEntry();
        entry.cachedAmbientContentView = null;
        entry.cachedBigContentView = null;
        entry.cachedContentView = null;
        entry.cachedHeadsUpContentView = null;
        entry.cachedPublicContentView = null;
        try {
            inflateNotificationViews();
        } catch (InflationException e) {
            mInflateExceptionHandler.handleInflationException(
                    mRow.getStatusBarNotification(), e);
        }
    }

}
