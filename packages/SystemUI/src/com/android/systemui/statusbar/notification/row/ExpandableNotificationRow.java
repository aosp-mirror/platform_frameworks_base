/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.systemui.statusbar.notification.ActivityLaunchAnimator.ExpandAnimationParameters;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_HEADS_UP;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_PUBLIC;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationCallback;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_HEADSUP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.MathUtils;
import android.util.Property;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.widget.CachingIconView;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.MenuItem;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.AboveShelfChangedListener;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationCounters;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.InflatedSmartReplies.SmartRepliesAndActions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * View representing a notification item - this can be either the individual child notification or
 * the group summary (which contains 1 or more child notifications).
 */
public class ExpandableNotificationRow extends ActivatableNotificationView
        implements PluginListener<NotificationMenuRowPlugin> {

    private static final boolean DEBUG = false;
    private static final int DEFAULT_DIVIDER_ALPHA = 0x29;
    private static final int COLORED_DIVIDER_ALPHA = 0x7B;
    private static final int MENU_VIEW_INDEX = 0;
    private static final String TAG = "ExpandableNotifRow";
    public static final float DEFAULT_HEADER_VISIBLE_AMOUNT = 1.0f;
    private static final long RECENTLY_ALERTED_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(30);
    private boolean mUpdateBackgroundOnUpdate;
    private boolean mNotificationTranslationFinished = false;
    /**
     * Listener for when {@link ExpandableNotificationRow} is laid out.
     */
    public interface LayoutListener {
        void onLayout();

    }

    private StatusBarStateController mStatusbarStateController;
    private KeyguardBypassController mBypassController;
    private LayoutListener mLayoutListener;
    private final NotificationContentInflater mNotificationInflater;
    private int mIconTransformContentShift;
    private int mIconTransformContentShiftNoIcon;
    private int mMaxHeadsUpHeightBeforeN;
    private int mMaxHeadsUpHeightBeforeP;
    private int mMaxHeadsUpHeight;
    private int mMaxHeadsUpHeightIncreased;
    private int mNotificationMinHeightBeforeN;
    private int mNotificationMinHeightBeforeP;
    private int mNotificationMinHeight;
    private int mNotificationMinHeightLarge;
    private int mNotificationMinHeightMedia;
    private int mNotificationMaxHeight;
    private int mIncreasedPaddingBetweenElements;
    private int mNotificationLaunchHeight;
    private boolean mMustStayOnScreen;

    /** Does this row contain layouts that can adapt to row expansion */
    private boolean mExpandable;
    /** Has the user actively changed the expansion state of this row */
    private boolean mHasUserChangedExpansion;
    /** If {@link #mHasUserChangedExpansion}, has the user expanded this row */
    private boolean mUserExpanded;
    /** Whether the blocking helper is showing on this notification (even if dismissed) */
    private boolean mIsBlockingHelperShowing;

    /**
     * Has this notification been expanded while it was pinned
     */
    private boolean mExpandedWhenPinned;
    /** Is the user touching this row */
    private boolean mUserLocked;
    /** Are we showing the "public" version */
    private boolean mShowingPublic;
    private boolean mSensitive;
    private boolean mSensitiveHiddenInGeneral;
    private boolean mShowingPublicInitialized;
    private boolean mHideSensitiveForIntrinsicHeight;
    private float mHeaderVisibleAmount = DEFAULT_HEADER_VISIBLE_AMOUNT;

    /**
     * Is this notification expanded by the system. The expansion state can be overridden by the
     * user expansion.
     */
    private boolean mIsSystemExpanded;

    /**
     * Whether the notification is on the keyguard and the expansion is disabled.
     */
    private boolean mOnKeyguard;

    private Animator mTranslateAnim;
    private ArrayList<View> mTranslateableViews;
    private NotificationContentView mPublicLayout;
    private NotificationContentView mPrivateLayout;
    private NotificationContentView[] mLayouts;
    private int mNotificationColor;
    private ExpansionLogger mLogger;
    private String mLoggingKey;
    private NotificationGuts mGuts;
    private NotificationEntry mEntry;
    private StatusBarNotification mStatusBarNotification;
    private String mAppName;

    /**
     * Whether or not the notification is using the heads up view and should peek from the top.
     */
    private boolean mIsHeadsUp;

    /**
     * Whether or not the notification should be redacted on the lock screen, i.e has sensitive
     * content which should be redacted on the lock screen.
     */
    private boolean mNeedsRedaction;
    private boolean mLastChronometerRunning = true;
    private ViewStub mChildrenContainerStub;
    private NotificationGroupManager mGroupManager;
    private boolean mChildrenExpanded;
    private boolean mIsSummaryWithChildren;
    private NotificationChildrenContainer mChildrenContainer;
    private NotificationMenuRowPlugin mMenuRow;
    private ViewStub mGutsStub;
    private boolean mIsSystemChildExpanded;
    private boolean mIsPinned;
    private FalsingManager mFalsingManager;
    private boolean mExpandAnimationRunning;
    private AboveShelfChangedListener mAboveShelfChangedListener;
    private HeadsUpManager mHeadsUpManager;
    private Consumer<Boolean> mHeadsUpAnimatingAwayListener;
    private boolean mChildIsExpanding;

    private boolean mJustClicked;
    private boolean mIconAnimationRunning;
    private boolean mShowNoBackground;
    private ExpandableNotificationRow mNotificationParent;
    private OnExpandClickListener mOnExpandClickListener;
    private View.OnClickListener mOnAppOpsClickListener;

    // Listener will be called when receiving a long click event.
    // Use #setLongPressPosition to optionally assign positional data with the long press.
    private LongPressListener mLongPressListener;

    private boolean mGroupExpansionChanging;

    /**
     * A supplier that returns true if keyguard is secure.
     */
    private BooleanSupplier mSecureStateProvider;

    /**
     * Whether or not a notification that is not part of a group of notifications can be manually
     * expanded by the user.
     */
    private boolean mEnableNonGroupedNotificationExpand;

    /**
     * Whether or not to update the background of the header of the notification when its expanded.
     * If {@code true}, the header background will disappear when expanded.
     */
    private boolean mShowGroupBackgroundWhenExpanded;

    private OnClickListener mExpandClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!shouldShowPublic() && (!mIsLowPriority || isExpanded())
                    && mGroupManager.isSummaryOfGroup(mStatusBarNotification)) {
                mGroupExpansionChanging = true;
                final boolean wasExpanded = mGroupManager.isGroupExpanded(mStatusBarNotification);
                boolean nowExpanded = mGroupManager.toggleGroupExpansion(mStatusBarNotification);
                mOnExpandClickListener.onExpandClicked(mEntry, nowExpanded);
                MetricsLogger.action(mContext, MetricsEvent.ACTION_NOTIFICATION_GROUP_EXPANDER,
                        nowExpanded);
                onExpansionChanged(true /* userAction */, wasExpanded);
            } else if (mEnableNonGroupedNotificationExpand) {
                if (v.isAccessibilityFocused()) {
                    mPrivateLayout.setFocusOnVisibilityChange();
                }
                boolean nowExpanded;
                if (isPinned()) {
                    nowExpanded = !mExpandedWhenPinned;
                    mExpandedWhenPinned = nowExpanded;
                } else {
                    nowExpanded = !isExpanded();
                    setUserExpanded(nowExpanded);
                }
                notifyHeightChanged(true);
                mOnExpandClickListener.onExpandClicked(mEntry, nowExpanded);
                MetricsLogger.action(mContext, MetricsEvent.ACTION_NOTIFICATION_EXPANDER,
                        nowExpanded);
            }
        }
    };
    private boolean mForceUnlocked;
    private boolean mDismissed;
    private boolean mKeepInParent;
    private boolean mRemoved;
    private static final Property<ExpandableNotificationRow, Float> TRANSLATE_CONTENT =
            new FloatProperty<ExpandableNotificationRow>("translate") {
                @Override
                public void setValue(ExpandableNotificationRow object, float value) {
                    object.setTranslation(value);
                }

                @Override
                public Float get(ExpandableNotificationRow object) {
                    return object.getTranslation();
                }
            };
    private OnClickListener mOnClickListener;
    private boolean mHeadsupDisappearRunning;
    private View mChildAfterViewWhenDismissed;
    private View mGroupParentWhenDismissed;
    private boolean mRefocusOnDismiss;
    private float mContentTransformationAmount;
    private boolean mIconsVisible = true;
    private boolean mAboveShelf;
    private boolean mIsLastChild;
    private Runnable mOnDismissRunnable;
    private boolean mIsLowPriority;
    private boolean mIsColorized;
    private boolean mUseIncreasedCollapsedHeight;
    private boolean mUseIncreasedHeadsUpHeight;
    private float mTranslationWhenRemoved;
    private boolean mWasChildInGroupWhenRemoved;
    private NotificationInlineImageResolver mImageResolver;
    private NotificationMediaManager mMediaManager;

    private SystemNotificationAsyncTask mSystemNotificationAsyncTask =
            new SystemNotificationAsyncTask();

    /**
     * Returns whether the given {@code statusBarNotification} is a system notification.
     * <b>Note</b>, this should be run in the background thread if possible as it makes multiple IPC
     * calls.
     */
    private static Boolean isSystemNotification(
            Context context, StatusBarNotification statusBarNotification) {
        PackageManager packageManager = StatusBar.getPackageManagerForUser(
                context, statusBarNotification.getUser().getIdentifier());
        Boolean isSystemNotification = null;

        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    statusBarNotification.getPackageName(), PackageManager.GET_SIGNATURES);

            isSystemNotification =
                    com.android.settingslib.Utils.isSystemPackage(
                            context.getResources(), packageManager, packageInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "cacheIsSystemNotification: Could not find package info");
        }
        return isSystemNotification;
    }

    @Override
    public boolean isGroupExpansionChanging() {
        if (isChildInGroup()) {
            return mNotificationParent.isGroupExpansionChanging();
        }
        return mGroupExpansionChanging;
    }

    public void setGroupExpansionChanging(boolean changing) {
        mGroupExpansionChanging = changing;
    }

    @Override
    public void setActualHeightAnimating(boolean animating) {
        if (mPrivateLayout != null) {
            mPrivateLayout.setContentHeightAnimating(animating);
        }
    }

    public NotificationContentView getPrivateLayout() {
        return mPrivateLayout;
    }

    public NotificationContentView getPublicLayout() {
        return mPublicLayout;
    }

    public void setIconAnimationRunning(boolean running) {
        for (NotificationContentView l : mLayouts) {
            setIconAnimationRunning(running, l);
        }
        if (mIsSummaryWithChildren) {
            setIconAnimationRunningForChild(running, mChildrenContainer.getHeaderView());
            setIconAnimationRunningForChild(running, mChildrenContainer.getLowPriorityHeaderView());
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getNotificationChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.setIconAnimationRunning(running);
            }
        }
        mIconAnimationRunning = running;
    }

    private void setIconAnimationRunning(boolean running, NotificationContentView layout) {
        if (layout != null) {
            View contractedChild = layout.getContractedChild();
            View expandedChild = layout.getExpandedChild();
            View headsUpChild = layout.getHeadsUpChild();
            setIconAnimationRunningForChild(running, contractedChild);
            setIconAnimationRunningForChild(running, expandedChild);
            setIconAnimationRunningForChild(running, headsUpChild);
        }
    }

    private void setIconAnimationRunningForChild(boolean running, View child) {
        if (child != null) {
            ImageView icon = (ImageView) child.findViewById(com.android.internal.R.id.icon);
            setIconRunning(icon, running);
            ImageView rightIcon = (ImageView) child.findViewById(
                    com.android.internal.R.id.right_icon);
            setIconRunning(rightIcon, running);
        }
    }

    private void setIconRunning(ImageView imageView, boolean running) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AnimationDrawable) {
                AnimationDrawable animationDrawable = (AnimationDrawable) drawable;
                if (running) {
                    animationDrawable.start();
                } else {
                    animationDrawable.stop();
                }
            } else if (drawable instanceof AnimatedVectorDrawable) {
                AnimatedVectorDrawable animationDrawable = (AnimatedVectorDrawable) drawable;
                if (running) {
                    animationDrawable.start();
                } else {
                    animationDrawable.stop();
                }
            }
        }
    }

    /**
     * Set the entry for the row.
     *
     * @param entry the entry this row is tied to
     */
    public void setEntry(@NonNull NotificationEntry entry) {
        mEntry = entry;
        mStatusBarNotification = entry.notification;
        cacheIsSystemNotification();
    }

    /**
     * Inflate views based off the inflation flags set. Inflation happens asynchronously.
     */
    public void inflateViews() {
        mNotificationInflater.inflateNotificationViews();
    }

    /**
     * Marks a content view as freeable, setting it so that future inflations do not reinflate
     * and ensuring that the view is freed when it is safe to remove.
     *
     * @param inflationFlag flag corresponding to the content view to be freed
     */
    public void freeContentViewWhenSafe(@InflationFlag int inflationFlag) {
        // View should not be reinflated in the future
        updateInflationFlag(inflationFlag, false);
        Runnable freeViewRunnable = () ->
                mNotificationInflater.freeNotificationView(inflationFlag);
        switch (inflationFlag) {
            case FLAG_CONTENT_VIEW_HEADS_UP:
                getPrivateLayout().performWhenContentInactive(VISIBLE_TYPE_HEADSUP,
                        freeViewRunnable);
                break;
            case FLAG_CONTENT_VIEW_PUBLIC:
                getPublicLayout().performWhenContentInactive(VISIBLE_TYPE_CONTRACTED,
                        freeViewRunnable);
            default:
                break;
        }
    }

    /**
     * Update whether or not a content view should be inflated.
     *
     * @param flag the flag corresponding to the content view
     * @param shouldInflate true if it should be inflated, false if it should not
     */
    public void updateInflationFlag(@InflationFlag int flag, boolean shouldInflate) {
        mNotificationInflater.updateInflationFlag(flag, shouldInflate);
    }

    /**
     * Whether or not a content view should be inflated.
     *
     * @param flag the flag corresponding to the content view
     * @return true if the flag is set, false otherwise
     */
    public boolean isInflationFlagSet(@InflationFlag int flag) {
        return mNotificationInflater.isInflationFlagSet(flag);
    }

    /**
     * Caches whether or not this row contains a system notification. Note, this is only cached
     * once per notification as the packageInfo can't technically change for a notification row.
     */
    private void cacheIsSystemNotification() {
        if (mEntry != null && mEntry.mIsSystemNotification == null) {
            if (mSystemNotificationAsyncTask.getStatus() == AsyncTask.Status.PENDING) {
                // Run async task once, only if it hasn't already been executed. Note this is
                // executed in serial - no need to parallelize this small task.
                mSystemNotificationAsyncTask.execute();
            }
        }
    }

    /**
     * Returns whether this row is considered non-blockable (i.e. it's a non-blockable system notif
     * or is in a whitelist).
     */
    public boolean getIsNonblockable() {
        boolean isNonblockable = Dependency.get(NotificationBlockingHelperManager.class)
                .isNonblockable(mStatusBarNotification.getPackageName(),
                        mEntry.getChannel().getId());

        // If the SystemNotifAsyncTask hasn't finished running or retrieved a value, we'll try once
        // again, but in-place on the main thread this time. This should rarely ever get called.
        if (mEntry != null && mEntry.mIsSystemNotification == null) {
            if (DEBUG) {
                Log.d(TAG, "Retrieving isSystemNotification on main thread");
            }
            mSystemNotificationAsyncTask.cancel(true /* mayInterruptIfRunning */);
            mEntry.mIsSystemNotification = isSystemNotification(mContext, mStatusBarNotification);
        }

        isNonblockable |= mEntry.getChannel().isImportanceLockedByOEM();
        isNonblockable |= mEntry.getChannel().isImportanceLockedByCriticalDeviceFunction();

        if (!isNonblockable && mEntry != null && mEntry.mIsSystemNotification != null) {
            if (mEntry.mIsSystemNotification) {
                if (mEntry.getChannel() != null
                        && !mEntry.getChannel().isBlockableSystem()) {
                    isNonblockable = true;
                }
            }
        }
        return isNonblockable;
    }

    public void onNotificationUpdated() {
        for (NotificationContentView l : mLayouts) {
            l.onNotificationUpdated(mEntry);
        }
        mIsColorized = mStatusBarNotification.getNotification().isColorized();
        mShowingPublicInitialized = false;
        updateNotificationColor();
        if (mMenuRow != null) {
            mMenuRow.onNotificationUpdated(mStatusBarNotification);
            mMenuRow.setAppName(mAppName);
        }
        if (mIsSummaryWithChildren) {
            mChildrenContainer.recreateNotificationHeader(mExpandClickListener);
            mChildrenContainer.onNotificationUpdated();
        }
        if (mIconAnimationRunning) {
            setIconAnimationRunning(true);
        }
        if (mLastChronometerRunning) {
            setChronometerRunning(true);
        }
        if (mNotificationParent != null) {
            mNotificationParent.updateChildrenHeaderAppearance();
        }
        onChildrenCountChanged();
        // The public layouts expand button is always visible
        mPublicLayout.updateExpandButtons(true);
        updateLimits();
        updateIconVisibilities();
        updateShelfIconColor();
        updateRippleAllowed();
        if (mUpdateBackgroundOnUpdate) {
            mUpdateBackgroundOnUpdate = false;
            updateBackgroundColors();
        }
    }

    /** Called when the notification's ranking was changed (but nothing else changed). */
    public void onNotificationRankingUpdated() {
        if (mMenuRow != null) {
            mMenuRow.onNotificationUpdated(mStatusBarNotification);
        }
    }

    @VisibleForTesting
    void updateShelfIconColor() {
        StatusBarIconView expandedIcon = mEntry.expandedIcon;
        boolean isPreL = Boolean.TRUE.equals(expandedIcon.getTag(R.id.icon_is_pre_L));
        boolean colorize = !isPreL || NotificationUtils.isGrayscale(expandedIcon,
                ContrastColorUtil.getInstance(mContext));
        int color = StatusBarIconView.NO_COLOR;
        if (colorize) {
            NotificationHeaderView header = getVisibleNotificationHeader();
            if (header != null) {
                color = header.getOriginalIconColor();
            } else {
                color = mEntry.getContrastedColor(mContext, mIsLowPriority && !isExpanded(),
                        getBackgroundColorWithoutTint());
            }
        }
        expandedIcon.setStaticDrawableColor(color);
    }

    public void setAboveShelfChangedListener(AboveShelfChangedListener aboveShelfChangedListener) {
        mAboveShelfChangedListener = aboveShelfChangedListener;
    }

    /**
     * Sets a supplier that can determine whether the keyguard is secure or not.
     * @param secureStateProvider A function that returns true if keyguard is secure.
     */
    public void setSecureStateProvider(BooleanSupplier secureStateProvider) {
        mSecureStateProvider = secureStateProvider;
    }

    @Override
    public boolean isDimmable() {
        if (!getShowingLayout().isDimmable()) {
            return false;
        }
        if (showingPulsing()) {
            return false;
        }
        return super.isDimmable();
    }

    private void updateLimits() {
        for (NotificationContentView l : mLayouts) {
            updateLimitsForView(l);
        }
    }

    private void updateLimitsForView(NotificationContentView layout) {
        boolean customView = layout.getContractedChild() != null
                && layout.getContractedChild().getId()
                != com.android.internal.R.id.status_bar_latest_event_content;
        boolean beforeN = mEntry.targetSdk < Build.VERSION_CODES.N;
        boolean beforeP = mEntry.targetSdk < Build.VERSION_CODES.P;
        int minHeight;

        View expandedView = layout.getExpandedChild();
        boolean isMediaLayout = expandedView != null
                && expandedView.findViewById(com.android.internal.R.id.media_actions) != null;
        boolean showCompactMediaSeekbar = mMediaManager.getShowCompactMediaSeekbar();

        if (customView && beforeP && !mIsSummaryWithChildren) {
            minHeight = beforeN ? mNotificationMinHeightBeforeN : mNotificationMinHeightBeforeP;
        } else if (isMediaLayout && showCompactMediaSeekbar) {
            minHeight = mNotificationMinHeightMedia;
        } else if (mUseIncreasedCollapsedHeight && layout == mPrivateLayout) {
            minHeight = mNotificationMinHeightLarge;
        } else {
            minHeight = mNotificationMinHeight;
        }
        boolean headsUpCustom = layout.getHeadsUpChild() != null &&
                layout.getHeadsUpChild().getId()
                        != com.android.internal.R.id.status_bar_latest_event_content;
        int headsUpHeight;
        if (headsUpCustom && beforeP) {
            headsUpHeight = beforeN ? mMaxHeadsUpHeightBeforeN : mMaxHeadsUpHeightBeforeP;
        } else if (mUseIncreasedHeadsUpHeight && layout == mPrivateLayout) {
            headsUpHeight = mMaxHeadsUpHeightIncreased;
        } else {
            headsUpHeight = mMaxHeadsUpHeight;
        }
        NotificationViewWrapper headsUpWrapper = layout.getVisibleWrapper(
                VISIBLE_TYPE_HEADSUP);
        if (headsUpWrapper != null) {
            headsUpHeight = Math.max(headsUpHeight, headsUpWrapper.getMinLayoutHeight());
        }
        layout.setHeights(minHeight, headsUpHeight, mNotificationMaxHeight);
    }

    public StatusBarNotification getStatusBarNotification() {
        return mStatusBarNotification;
    }

    public NotificationEntry getEntry() {
        return mEntry;
    }

    @Override
    public boolean isHeadsUp() {
        return mIsHeadsUp;
    }

    public void setHeadsUp(boolean isHeadsUp) {
        boolean wasAboveShelf = isAboveShelf();
        int intrinsicBefore = getIntrinsicHeight();
        mIsHeadsUp = isHeadsUp;
        mPrivateLayout.setHeadsUp(isHeadsUp);
        if (mIsSummaryWithChildren) {
            // The overflow might change since we allow more lines as HUN.
            mChildrenContainer.updateGroupOverflow();
        }
        if (intrinsicBefore != getIntrinsicHeight()) {
            notifyHeightChanged(false  /* needsAnimation */);
        }
        if (isHeadsUp) {
            mMustStayOnScreen = true;
            setAboveShelf(true);
        } else if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
    }

    @Override
    public boolean showingPulsing() {
        return isHeadsUpState() && (isDozing() || (mOnKeyguard && isBypassEnabled()));
    }

    /**
     * @return if the view is in heads up state, i.e either still heads upped or it's disappearing.
     */
    public boolean isHeadsUpState() {
        return mIsHeadsUp || mHeadsupDisappearRunning;
    }


    public void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
        mPrivateLayout.setGroupManager(groupManager);
    }

    public void setRemoteInputController(RemoteInputController r) {
        mPrivateLayout.setRemoteInputController(r);
    }

    public void setAppName(String appName) {
        mAppName = appName;
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.setAppName(mAppName);
        }
    }

    public void addChildNotification(ExpandableNotificationRow row) {
        addChildNotification(row, -1);
    }

    /**
     * Set the how much the header should be visible. A value of 0 will make the header fully gone
     * and a value of 1 will make the notification look just like normal.
     * This is being used for heads up notifications, when they are pinned to the top of the screen
     * and the header content is extracted to the statusbar.
     *
     * @param headerVisibleAmount the amount the header should be visible.
     */
    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        if (mHeaderVisibleAmount != headerVisibleAmount) {
            mHeaderVisibleAmount = headerVisibleAmount;
            for (NotificationContentView l : mLayouts) {
                l.setHeaderVisibleAmount(headerVisibleAmount);
            }
            if (mChildrenContainer != null) {
                mChildrenContainer.setHeaderVisibleAmount(headerVisibleAmount);
            }
            notifyHeightChanged(false /* needsAnimation */);
        }
    }

    @Override
    public float getHeaderVisibleAmount() {
        return mHeaderVisibleAmount;
    }

    @Override
    public void setHeadsUpIsVisible() {
        super.setHeadsUpIsVisible();
        mMustStayOnScreen = false;
    }

    /**
     * Add a child notification to this view.
     *
     * @param row the row to add
     * @param childIndex the index to add it at, if -1 it will be added at the end
     */
    public void addChildNotification(ExpandableNotificationRow row, int childIndex) {
        if (mChildrenContainer == null) {
            mChildrenContainerStub.inflate();
        }
        mChildrenContainer.addNotification(row, childIndex);
        onChildrenCountChanged();
        row.setIsChildInGroup(true, this);
    }

    public void removeChildNotification(ExpandableNotificationRow row) {
        if (mChildrenContainer != null) {
            mChildrenContainer.removeNotification(row);
        }
        onChildrenCountChanged();
        row.setIsChildInGroup(false, null);
        row.setBottomRoundness(0.0f, false /* animate */);
    }

    @Override
    public boolean isChildInGroup() {
        return mNotificationParent != null;
    }

    /**
     * @return whether this notification is the only child in the group summary
     */
    public boolean isOnlyChildInGroup() {
        return mGroupManager.isOnlyChildInGroup(getStatusBarNotification());
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mNotificationParent;
    }

    /**
     * @param isChildInGroup Is this notification now in a group
     * @param parent the new parent notification
     */
    public void setIsChildInGroup(boolean isChildInGroup, ExpandableNotificationRow parent) {
        boolean childInGroup = StatusBar.ENABLE_CHILD_NOTIFICATIONS && isChildInGroup;
        if (mExpandAnimationRunning && !isChildInGroup && mNotificationParent != null) {
            mNotificationParent.setChildIsExpanding(false);
            mNotificationParent.setExtraWidthForClipping(0.0f);
            mNotificationParent.setMinimumHeightForClipping(0);
        }
        mNotificationParent = childInGroup ? parent : null;
        mPrivateLayout.setIsChildInGroup(childInGroup);
        mNotificationInflater.setIsChildInGroup(childInGroup);
        resetBackgroundAlpha();
        updateBackgroundForGroupState();
        updateClickAndFocus();
        if (mNotificationParent != null) {
            setOverrideTintColor(NO_COLOR, 0.0f);
            // Let's reset the distance to top roundness, as this isn't applied to group children
            setDistanceToTopRoundness(NO_ROUNDNESS);
            mNotificationParent.updateBackgroundForGroupState();
        }
        updateIconVisibilities();
        updateBackgroundClipping();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN
                || !isChildInGroup() || isGroupExpanded()) {
            return super.onTouchEvent(event);
        } else {
            return false;
        }
    }

    @Override
    protected boolean handleSlideBack() {
        if (mMenuRow != null && mMenuRow.isMenuVisible()) {
            animateTranslateNotification(0 /* targetLeft */);
            return true;
        }
        return false;
    }

    @Override
    protected boolean shouldHideBackground() {
        return super.shouldHideBackground() || mShowNoBackground;
    }

    @Override
    public boolean isSummaryWithChildren() {
        return mIsSummaryWithChildren;
    }

    @Override
    public boolean areChildrenExpanded() {
        return mChildrenExpanded;
    }

    public List<ExpandableNotificationRow> getNotificationChildren() {
        return mChildrenContainer == null ? null : mChildrenContainer.getNotificationChildren();
    }

    public int getNumberOfNotificationChildren() {
        if (mChildrenContainer == null) {
            return 0;
        }
        return mChildrenContainer.getNotificationChildren().size();
    }

    /**
     * Apply the order given in the list to the children.
     *
     * @param childOrder the new list order
     * @param visualStabilityManager
     * @param callback the callback to invoked in case it is not allowed
     * @return whether the list order has changed
     */
    public boolean applyChildOrder(List<ExpandableNotificationRow> childOrder,
            VisualStabilityManager visualStabilityManager,
            VisualStabilityManager.Callback callback) {
        return mChildrenContainer != null && mChildrenContainer.applyChildOrder(childOrder,
                visualStabilityManager, callback);
    }

    /** Updates states of all children. */
    public void updateChildrenStates(AmbientState ambientState) {
        if (mIsSummaryWithChildren) {
            ExpandableViewState parentState = getViewState();
            mChildrenContainer.updateState(parentState, ambientState);
        }
    }

    /** Applies children states. */
    public void applyChildrenState() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.applyState();
        }
    }

    /** Prepares expansion changed. */
    public void prepareExpansionChanged() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.prepareExpansionChanged();
        }
    }

    /** Starts child animations. */
    public void startChildAnimation(AnimationProperties properties) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.startAnimationToState(properties);
        }
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        if (!mIsSummaryWithChildren || !mChildrenExpanded) {
            return this;
        } else {
            ExpandableNotificationRow view = mChildrenContainer.getViewAtPosition(y);
            return view == null ? this : view;
        }
    }

    public NotificationGuts getGuts() {
        return mGuts;
    }

    /**
     * Set this notification to be pinned to the top if {@link #isHeadsUp()} is true. By doing this
     * the notification will be rendered on top of the screen.
     *
     * @param pinned whether it is pinned
     */
    public void setPinned(boolean pinned) {
        int intrinsicHeight = getIntrinsicHeight();
        boolean wasAboveShelf = isAboveShelf();
        mIsPinned = pinned;
        if (intrinsicHeight != getIntrinsicHeight()) {
            notifyHeightChanged(false /* needsAnimation */);
        }
        if (pinned) {
            setIconAnimationRunning(true);
            mExpandedWhenPinned = false;
        } else if (mExpandedWhenPinned) {
            setUserExpanded(true);
        }
        setChronometerRunning(mLastChronometerRunning);
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
    }

    @Override
    public boolean isPinned() {
        return mIsPinned;
    }

    @Override
    public int getPinnedHeadsUpHeight() {
        return getPinnedHeadsUpHeight(true /* atLeastMinHeight */);
    }

    /**
     * @param atLeastMinHeight should the value returned be at least the minimum height.
     *                         Used to avoid cyclic calls
     * @return the height of the heads up notification when pinned
     */
    private int getPinnedHeadsUpHeight(boolean atLeastMinHeight) {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getIntrinsicHeight();
        }
        if(mExpandedWhenPinned) {
            return Math.max(getMaxExpandHeight(), getHeadsUpHeight());
        } else if (atLeastMinHeight) {
            return Math.max(getCollapsedHeight(), getHeadsUpHeight());
        } else {
            return getHeadsUpHeight();
        }
    }

    /**
     * Mark whether this notification was just clicked, i.e. the user has just clicked this
     * notification in this frame.
     */
    public void setJustClicked(boolean justClicked) {
        mJustClicked = justClicked;
    }

    /**
     * @return true if this notification has been clicked in this frame, false otherwise
     */
    public boolean wasJustClicked() {
        return mJustClicked;
    }

    public void setChronometerRunning(boolean running) {
        mLastChronometerRunning = running;
        setChronometerRunning(running, mPrivateLayout);
        setChronometerRunning(running, mPublicLayout);
        if (mChildrenContainer != null) {
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getNotificationChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.setChronometerRunning(running);
            }
        }
    }

    private void setChronometerRunning(boolean running, NotificationContentView layout) {
        if (layout != null) {
            running = running || isPinned();
            View contractedChild = layout.getContractedChild();
            View expandedChild = layout.getExpandedChild();
            View headsUpChild = layout.getHeadsUpChild();
            setChronometerRunningForChild(running, contractedChild);
            setChronometerRunningForChild(running, expandedChild);
            setChronometerRunningForChild(running, headsUpChild);
        }
    }

    private void setChronometerRunningForChild(boolean running, View child) {
        if (child != null) {
            View chronometer = child.findViewById(com.android.internal.R.id.chronometer);
            if (chronometer instanceof Chronometer) {
                ((Chronometer) chronometer).setStarted(running);
            }
        }
    }

    public NotificationHeaderView getNotificationHeader() {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getHeaderView();
        }
        return mPrivateLayout.getNotificationHeader();
    }

    /**
     * @return the currently visible notification header. This can be different from
     * {@link #getNotificationHeader()} in case it is a low-priority group.
     */
    public NotificationHeaderView getVisibleNotificationHeader() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getVisibleHeader();
        }
        return getShowingLayout().getVisibleNotificationHeader();
    }


    /**
     * @return the contracted notification header. This can be different from
     * {@link #getNotificationHeader()} and also {@link #getVisibleNotificationHeader()} and only
     * returns the contracted version.
     */
    public NotificationHeaderView getContractedNotificationHeader() {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getHeaderView();
        }
        return mPrivateLayout.getContractedNotificationHeader();
    }

    public void setOnExpandClickListener(OnExpandClickListener onExpandClickListener) {
        mOnExpandClickListener = onExpandClickListener;
    }

    public void setLongPressListener(LongPressListener longPressListener) {
        mLongPressListener = longPressListener;
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        super.setOnClickListener(l);
        mOnClickListener = l;
        updateClickAndFocus();
    }

    private void updateClickAndFocus() {
        boolean normalChild = !isChildInGroup() || isGroupExpanded();
        boolean clickable = mOnClickListener != null && normalChild;
        if (isFocusable() != normalChild) {
            setFocusable(normalChild);
        }
        if (isClickable() != clickable) {
            setClickable(clickable);
        }
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public HeadsUpManager getHeadsUpManager() {
        return mHeadsUpManager;
    }

    public void setGutsView(MenuItem item) {
        if (mGuts != null && item.getGutsView() instanceof NotificationGuts.GutsContent) {
            ((NotificationGuts.GutsContent) item.getGutsView()).setGutsParent(mGuts);
            mGuts.setGutsContent((NotificationGuts.GutsContent) item.getGutsView());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEntry.setInitializationTime(SystemClock.elapsedRealtime());
        Dependency.get(PluginManager.class).addPluginListener(this,
                NotificationMenuRowPlugin.class, false /* Allow multiple */);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(PluginManager.class).removePluginListener(this);
    }

    @Override
    public void onPluginConnected(NotificationMenuRowPlugin plugin, Context pluginContext) {
        boolean existed = mMenuRow != null && mMenuRow.getMenuView() != null;
        if (existed) {
            removeView(mMenuRow.getMenuView());
        }
        if (plugin == null) {
            return;
        }
        mMenuRow = plugin;
        if (mMenuRow.shouldUseDefaultMenuItems()) {
            ArrayList<MenuItem> items = new ArrayList<>();
            items.add(NotificationMenuRow.createInfoItem(mContext));
            items.add(NotificationMenuRow.createSnoozeItem(mContext));
            items.add(NotificationMenuRow.createAppOpsItem(mContext));
            mMenuRow.setMenuItems(items);
        }
        if (existed) {
            createMenu();
        }
    }

    @Override
    public void onPluginDisconnected(NotificationMenuRowPlugin plugin) {
        boolean existed = mMenuRow.getMenuView() != null;
        mMenuRow = new NotificationMenuRow(mContext); // Back to default
        if (existed) {
            createMenu();
        }
    }

    /**
     * Get a handle to a NotificationMenuRowPlugin whose menu view has been added to our hierarchy,
     * or null if there is no menu row
     *
     * @return a {@link NotificationMenuRowPlugin}, or null
     */
    @Nullable
    public NotificationMenuRowPlugin createMenu() {
        if (mMenuRow == null) {
            return null;
        }

        if (mMenuRow.getMenuView() == null) {
            mMenuRow.createMenu(this, mStatusBarNotification);
            mMenuRow.setAppName(mAppName);
            FrameLayout.LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            addView(mMenuRow.getMenuView(), MENU_VIEW_INDEX, lp);
        }
        return mMenuRow;
    }

    @Nullable
    public NotificationMenuRowPlugin getProvider() {
        return mMenuRow;
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        initDimens();
        initBackground();
        reInflateViews();
    }

    private void reInflateViews() {
        // Let's update our childrencontainer. This is intentionally not guarded with
        // mIsSummaryWithChildren since we might have had children but not anymore.
        if (mChildrenContainer != null) {
            mChildrenContainer.reInflateViews(mExpandClickListener, mEntry.notification);
        }
        if (mGuts != null) {
            NotificationGuts oldGuts = mGuts;
            int index = indexOfChild(oldGuts);
            removeView(oldGuts);
            mGuts = (NotificationGuts) LayoutInflater.from(mContext).inflate(
                    R.layout.notification_guts, this, false);
            mGuts.setVisibility(oldGuts.isExposed() ? VISIBLE : GONE);
            addView(mGuts, index);
        }
        View oldMenu = mMenuRow == null ? null : mMenuRow.getMenuView();
        if (oldMenu != null) {
            int menuIndex = indexOfChild(oldMenu);
            removeView(oldMenu);
            mMenuRow.createMenu(ExpandableNotificationRow.this, mStatusBarNotification);
            mMenuRow.setAppName(mAppName);
            addView(mMenuRow.getMenuView(), menuIndex);
        }
        for (NotificationContentView l : mLayouts) {
            l.initView();
            l.reInflateViews();
        }
        mStatusBarNotification.clearPackageContext();
        mNotificationInflater.clearCachesAndReInflate();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onConfigurationChanged();
        }
    }

    public void onUiModeChanged() {
        mUpdateBackgroundOnUpdate = true;
        reInflateViews();
        if (mChildrenContainer != null) {
            for (ExpandableNotificationRow child : mChildrenContainer.getNotificationChildren()) {
                child.onUiModeChanged();
            }
        }
    }

    public void setContentBackground(int customBackgroundColor, boolean animate,
            NotificationContentView notificationContentView) {
        if (getShowingLayout() == notificationContentView) {
            setTintColor(customBackgroundColor, animate);
        }
    }

    @Override
    protected void setBackgroundTintColor(int color) {
        super.setBackgroundTintColor(color);
        NotificationContentView view = getShowingLayout();
        if (view != null) {
            view.setBackgroundTintColor(color);
        }
    }

    public void closeRemoteInput() {
        for (NotificationContentView l : mLayouts) {
            l.closeRemoteInput();
        }
    }

    /**
     * Set by how much the single line view should be indented.
     */
    public void setSingleLineWidthIndention(int indention) {
        mPrivateLayout.setSingleLineWidthIndention(indention);
    }

    public int getNotificationColor() {
        return mNotificationColor;
    }

    private void updateNotificationColor() {
        Configuration currentConfig = getResources().getConfiguration();
        boolean nightMode = (currentConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        mNotificationColor = ContrastColorUtil.resolveContrastColor(mContext,
                getStatusBarNotification().getNotification().color,
                getBackgroundColorWithoutTint(), nightMode);
    }

    public HybridNotificationView getSingleLineView() {
        return mPrivateLayout.getSingleLineView();
    }

    public boolean isOnKeyguard() {
        return mOnKeyguard;
    }

    public void removeAllChildren() {
        List<ExpandableNotificationRow> notificationChildren
                = mChildrenContainer.getNotificationChildren();
        ArrayList<ExpandableNotificationRow> clonedList = new ArrayList<>(notificationChildren);
        for (int i = 0; i < clonedList.size(); i++) {
            ExpandableNotificationRow row = clonedList.get(i);
            if (row.keepInParent()) {
                continue;
            }
            mChildrenContainer.removeNotification(row);
            row.setIsChildInGroup(false, null);
        }
        onChildrenCountChanged();
    }

    public void setForceUnlocked(boolean forceUnlocked) {
        mForceUnlocked = forceUnlocked;
        if (mIsSummaryWithChildren) {
            List<ExpandableNotificationRow> notificationChildren = getNotificationChildren();
            for (ExpandableNotificationRow child : notificationChildren) {
                child.setForceUnlocked(forceUnlocked);
            }
        }
    }

    public void setDismissed(boolean fromAccessibility) {
        setLongPressListener(null);
        mDismissed = true;
        mGroupParentWhenDismissed = mNotificationParent;
        mRefocusOnDismiss = fromAccessibility;
        mChildAfterViewWhenDismissed = null;
        mEntry.icon.setDismissed();
        if (isChildInGroup()) {
            List<ExpandableNotificationRow> notificationChildren =
                    mNotificationParent.getNotificationChildren();
            int i = notificationChildren.indexOf(this);
            if (i != -1 && i < notificationChildren.size() - 1) {
                mChildAfterViewWhenDismissed = notificationChildren.get(i + 1);
            }
        }
    }

    public boolean isDismissed() {
        return mDismissed;
    }

    public boolean keepInParent() {
        return mKeepInParent;
    }

    public void setKeepInParent(boolean keepInParent) {
        mKeepInParent = keepInParent;
    }

    @Override
    public boolean isRemoved() {
        return mRemoved;
    }

    public void setRemoved() {
        mRemoved = true;
        mTranslationWhenRemoved = getTranslationY();
        mWasChildInGroupWhenRemoved = isChildInGroup();
        if (isChildInGroup()) {
            mTranslationWhenRemoved += getNotificationParent().getTranslationY();
        }
        for (NotificationContentView l : mLayouts) {
            l.setRemoved();
        }
    }

    public boolean wasChildInGroupWhenRemoved() {
        return mWasChildInGroupWhenRemoved;
    }

    public float getTranslationWhenRemoved() {
        return mTranslationWhenRemoved;
    }

    public NotificationChildrenContainer getChildrenContainer() {
        return mChildrenContainer;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        boolean wasAboveShelf = isAboveShelf();
        boolean changed = headsUpAnimatingAway != mHeadsupDisappearRunning;
        mHeadsupDisappearRunning = headsUpAnimatingAway;
        mPrivateLayout.setHeadsUpAnimatingAway(headsUpAnimatingAway);
        if (changed && mHeadsUpAnimatingAwayListener != null) {
            mHeadsUpAnimatingAwayListener.accept(headsUpAnimatingAway);
        }
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
    }

    public void setHeadsUpAnimatingAwayListener(Consumer<Boolean> listener) {
        mHeadsUpAnimatingAwayListener = listener;
    }

    /**
     * @return if the view was just heads upped and is now animating away. During such a time the
     * layout needs to be kept consistent
     */
    @Override
    public boolean isHeadsUpAnimatingAway() {
        return mHeadsupDisappearRunning;
    }

    public View getChildAfterViewWhenDismissed() {
        return mChildAfterViewWhenDismissed;
    }

    public View getGroupParentWhenDismissed() {
        return mGroupParentWhenDismissed;
    }

    /**
     * Dismisses the notification with the option of showing the blocking helper in-place if we have
     * a negative user sentiment.
     *
     * @param fromAccessibility whether this dismiss is coming from an accessibility action
     * @return whether a blocking helper is shown in this row
     */
    public boolean performDismissWithBlockingHelper(boolean fromAccessibility) {
        NotificationBlockingHelperManager manager =
                Dependency.get(NotificationBlockingHelperManager.class);
        boolean isBlockingHelperShown = manager.perhapsShowBlockingHelper(this, mMenuRow);

        Dependency.get(MetricsLogger.class).count(NotificationCounters.NOTIFICATION_DISMISSED, 1);

        // Continue with dismiss since we don't want the blocking helper to be directly associated
        // with a certain notification.
        performDismiss(fromAccessibility);
        return isBlockingHelperShown;
    }

    public void performDismiss(boolean fromAccessibility) {
        if (isOnlyChildInGroup()) {
            NotificationEntry groupSummary =
                    mGroupManager.getLogicalGroupSummary(getStatusBarNotification());
            if (groupSummary.isClearable()) {
                // If this is the only child in the group, dismiss the group, but don't try to show
                // the blocking helper affordance!
                groupSummary.getRow().performDismiss(fromAccessibility);
            }
        }
        setDismissed(fromAccessibility);
        if (mEntry.isClearable()) {
            // TODO: track dismiss sentiment
            if (mOnDismissRunnable != null) {
                mOnDismissRunnable.run();
            }
        }
    }

    public void setBlockingHelperShowing(boolean isBlockingHelperShowing) {
        mIsBlockingHelperShowing = isBlockingHelperShowing;
    }

    public boolean isBlockingHelperShowing() {
        return mIsBlockingHelperShowing;
    }

    public boolean isBlockingHelperShowingAndTranslationFinished() {
        return mIsBlockingHelperShowing && mNotificationTranslationFinished;
    }

    public void setOnDismissRunnable(Runnable onDismissRunnable) {
        mOnDismissRunnable = onDismissRunnable;
    }

    public View getNotificationIcon() {
        NotificationHeaderView notificationHeader = getVisibleNotificationHeader();
        if (notificationHeader != null) {
            return notificationHeader.getIcon();
        }
        return null;
    }

    /**
     * @return whether the notification is currently showing a view with an icon.
     */
    public boolean isShowingIcon() {
        if (areGutsExposed()) {
            return false;
        }
        return getVisibleNotificationHeader() != null;
    }

    /**
     * Set how much this notification is transformed into an icon.
     *
     * @param contentTransformationAmount A value from 0 to 1 indicating how much we are transformed
     *                                 to the content away
     * @param isLastChild is this the last child in the list. If true, then the transformation is
     *                    different since it's content fades out.
     */
    public void setContentTransformationAmount(float contentTransformationAmount,
            boolean isLastChild) {
        boolean changeTransformation = isLastChild != mIsLastChild;
        changeTransformation |= mContentTransformationAmount != contentTransformationAmount;
        mIsLastChild = isLastChild;
        mContentTransformationAmount = contentTransformationAmount;
        if (changeTransformation) {
            updateContentTransformation();
        }
    }

    /**
     * Set the icons to be visible of this notification.
     */
    public void setIconsVisible(boolean iconsVisible) {
        if (iconsVisible != mIconsVisible) {
            mIconsVisible = iconsVisible;
            updateIconVisibilities();
        }
    }

    @Override
    protected void onBelowSpeedBumpChanged() {
        updateIconVisibilities();
    }

    private void updateContentTransformation() {
        if (mExpandAnimationRunning) {
            return;
        }
        float contentAlpha;
        float translationY = -mContentTransformationAmount * mIconTransformContentShift;
        if (mIsLastChild) {
            contentAlpha = 1.0f - mContentTransformationAmount;
            contentAlpha = Math.min(contentAlpha / 0.5f, 1.0f);
            contentAlpha = Interpolators.ALPHA_OUT.getInterpolation(contentAlpha);
            translationY *= 0.4f;
        } else {
            contentAlpha = 1.0f;
        }
        for (NotificationContentView l : mLayouts) {
            l.setAlpha(contentAlpha);
            l.setTranslationY(translationY);
        }
        if (mChildrenContainer != null) {
            mChildrenContainer.setAlpha(contentAlpha);
            mChildrenContainer.setTranslationY(translationY);
            // TODO: handle children fade out better
        }
    }

    private void updateIconVisibilities() {
        boolean visible = isChildInGroup() || mIconsVisible;
        for (NotificationContentView l : mLayouts) {
            l.setIconsVisible(visible);
        }
        if (mChildrenContainer != null) {
            mChildrenContainer.setIconsVisible(visible);
        }
    }

    /**
     * Get the relative top padding of a view relative to this view. This recursively walks up the
     * hierarchy and does the corresponding measuring.
     *
     * @param view the view to the the padding for. The requested view has to be a child of this
     *             notification.
     * @return the toppadding
     */
    public int getRelativeTopPadding(View view) {
        int topPadding = 0;
        while (view.getParent() instanceof ViewGroup) {
            topPadding += view.getTop();
            view = (View) view.getParent();
            if (view instanceof ExpandableNotificationRow) {
                return topPadding;
            }
        }
        return topPadding;
    }

    public float getContentTranslation() {
        return mPrivateLayout.getTranslationY();
    }

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
        mPrivateLayout.setIsLowPriority(isLowPriority);
        mNotificationInflater.setIsLowPriority(mIsLowPriority);
        if (mChildrenContainer != null) {
            mChildrenContainer.setIsLowPriority(isLowPriority);
        }
    }

    public boolean isLowPriority() {
        return mIsLowPriority;
    }

    public void setUseIncreasedCollapsedHeight(boolean use) {
        mUseIncreasedCollapsedHeight = use;
        mNotificationInflater.setUsesIncreasedHeight(use);
    }

    public void setUseIncreasedHeadsUpHeight(boolean use) {
        mUseIncreasedHeadsUpHeight = use;
        mNotificationInflater.setUsesIncreasedHeadsUpHeight(use);
    }

    public void setRemoteViewClickHandler(RemoteViews.OnClickHandler remoteViewClickHandler) {
        mNotificationInflater.setRemoteViewClickHandler(remoteViewClickHandler);
    }

    public void setInflationCallback(InflationCallback callback) {
        mNotificationInflater.setInflationCallback(callback);
    }

    public void setNeedsRedaction(boolean needsRedaction) {
        if (mNeedsRedaction != needsRedaction) {
            mNeedsRedaction = needsRedaction;
            updateInflationFlag(FLAG_CONTENT_VIEW_PUBLIC, needsRedaction /* shouldInflate */);
            mNotificationInflater.updateNeedsRedaction(needsRedaction);
            if (!needsRedaction) {
                freeContentViewWhenSafe(FLAG_CONTENT_VIEW_PUBLIC);
            }
        }
    }

    @VisibleForTesting
    public NotificationContentInflater getNotificationInflater() {
        return mNotificationInflater;
    }

    public interface ExpansionLogger {
        void logNotificationExpansion(String key, boolean userAction, boolean expanded);
    }

    public ExpandableNotificationRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFalsingManager = Dependency.get(FalsingManager.class);  // TODO: inject into a controller.
        mNotificationInflater = new NotificationContentInflater(this);
        mMenuRow = new NotificationMenuRow(mContext);
        mImageResolver = new NotificationInlineImageResolver(context,
                new NotificationInlineImageCache());
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        initDimens();
    }

    public void setBypassController(KeyguardBypassController bypassController) {
        mBypassController = bypassController;
    }

    public void setStatusBarStateController(StatusBarStateController statusBarStateController) {
        mStatusbarStateController = statusBarStateController;
    }

    private void initDimens() {
        mNotificationMinHeightBeforeN = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_legacy);
        mNotificationMinHeightBeforeP = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_before_p);
        mNotificationMinHeight = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height);
        mNotificationMinHeightLarge = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_increased);
        mNotificationMinHeightMedia = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_media);
        mNotificationMaxHeight = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_height);
        mMaxHeadsUpHeightBeforeN = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height_legacy);
        mMaxHeadsUpHeightBeforeP = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height_before_p);
        mMaxHeadsUpHeight = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height);
        mMaxHeadsUpHeightIncreased = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height_increased);

        Resources res = getResources();
        mIncreasedPaddingBetweenElements = res.getDimensionPixelSize(
                R.dimen.notification_divider_height_increased);
        mIconTransformContentShiftNoIcon = res.getDimensionPixelSize(
                R.dimen.notification_icon_transform_content_shift);
        mEnableNonGroupedNotificationExpand =
                res.getBoolean(R.bool.config_enableNonGroupedNotificationExpand);
        mShowGroupBackgroundWhenExpanded =
                res.getBoolean(R.bool.config_showGroupNotificationBgWhenExpanded);
    }

    NotificationInlineImageResolver getImageResolver() {
        return mImageResolver;
    }

    /**
     * Resets this view so it can be re-used for an updated notification.
     */
    public void reset() {
        mShowingPublicInitialized = false;
        onHeightReset();
        requestLayout();
    }

    public void showAppOpsIcons(ArraySet<Integer> activeOps) {
        if (mIsSummaryWithChildren && mChildrenContainer.getHeaderView() != null) {
            mChildrenContainer.getHeaderView().showAppOpsIcons(activeOps);
        }
        mPrivateLayout.showAppOpsIcons(activeOps);
        mPublicLayout.showAppOpsIcons(activeOps);
    }

    /** Sets the last time the notification being displayed audibly alerted the user. */
    public void setLastAudiblyAlertedMs(long lastAudiblyAlertedMs) {
        if (NotificationUtils.useNewInterruptionModel(mContext)) {
            long timeSinceAlertedAudibly = System.currentTimeMillis() - lastAudiblyAlertedMs;
            boolean alertedRecently =
                    timeSinceAlertedAudibly < RECENTLY_ALERTED_THRESHOLD_MS;

            applyAudiblyAlertedRecently(alertedRecently);

            removeCallbacks(mExpireRecentlyAlertedFlag);
            if (alertedRecently) {
                long timeUntilNoLongerRecent =
                        RECENTLY_ALERTED_THRESHOLD_MS - timeSinceAlertedAudibly;
                postDelayed(mExpireRecentlyAlertedFlag, timeUntilNoLongerRecent);
            }
        }
    }

    private final Runnable mExpireRecentlyAlertedFlag = () -> applyAudiblyAlertedRecently(false);

    private void applyAudiblyAlertedRecently(boolean audiblyAlertedRecently) {
        if (mIsSummaryWithChildren && mChildrenContainer.getHeaderView() != null) {
            mChildrenContainer.getHeaderView().setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        }
        mPrivateLayout.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        mPublicLayout.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
    }

    public View.OnClickListener getAppOpsOnClickListener() {
        return mOnAppOpsClickListener;
    }

    public void setAppOpsOnClickListener(ExpandableNotificationRow.OnAppOpsClickListener l) {
        mOnAppOpsClickListener = v -> {
            createMenu();
            NotificationMenuRowPlugin provider = getProvider();
            if (provider == null) {
                return;
            }
            MenuItem menuItem = provider.getAppOpsMenuItem(mContext);
            if (menuItem != null) {
                l.onClick(this, v.getWidth() / 2, v.getHeight() / 2, menuItem);
            }
        };
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPublicLayout = (NotificationContentView) findViewById(R.id.expandedPublic);
        mPrivateLayout = (NotificationContentView) findViewById(R.id.expanded);
        mLayouts = new NotificationContentView[] {mPrivateLayout, mPublicLayout};

        for (NotificationContentView l : mLayouts) {
            l.setExpandClickListener(mExpandClickListener);
            l.setContainingNotification(this);
        }
        mGutsStub = (ViewStub) findViewById(R.id.notification_guts_stub);
        mGutsStub.setOnInflateListener(new ViewStub.OnInflateListener() {
            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mGuts = (NotificationGuts) inflated;
                mGuts.setClipTopAmount(getClipTopAmount());
                mGuts.setActualHeight(getActualHeight());
                mGutsStub = null;
            }
        });
        mChildrenContainerStub = (ViewStub) findViewById(R.id.child_container_stub);
        mChildrenContainerStub.setOnInflateListener(new ViewStub.OnInflateListener() {

            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mChildrenContainer = (NotificationChildrenContainer) inflated;
                mChildrenContainer.setIsLowPriority(mIsLowPriority);
                mChildrenContainer.setContainingNotification(ExpandableNotificationRow.this);
                mChildrenContainer.onNotificationUpdated();

                if (mShouldTranslateContents) {
                    mTranslateableViews.add(mChildrenContainer);
                }
            }
        });

        if (mShouldTranslateContents) {
            // Add the views that we translate to reveal the menu
            mTranslateableViews = new ArrayList<>();
            for (int i = 0; i < getChildCount(); i++) {
                mTranslateableViews.add(getChildAt(i));
            }
            // Remove views that don't translate
            mTranslateableViews.remove(mChildrenContainerStub);
            mTranslateableViews.remove(mGutsStub);
        }
    }

    private void doLongClickCallback() {
        doLongClickCallback(getWidth() / 2, getHeight() / 2);
    }

    public void doLongClickCallback(int x, int y) {
        createMenu();
        NotificationMenuRowPlugin provider = getProvider();
        MenuItem menuItem = null;
        if (provider != null) {
            menuItem = provider.getLongpressMenuItem(mContext);
        }
        doLongClickCallback(x, y, menuItem);
    }

    private void doLongClickCallback(int x, int y, MenuItem menuItem) {
        if (mLongPressListener != null && menuItem != null) {
            mLongPressListener.onLongPress(this, x, y, menuItem);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            if (!event.isCanceled()) {
                performClick();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            doLongClickCallback();
            return true;
        }
        return false;
    }

    public void resetTranslation() {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }

        if (!mShouldTranslateContents) {
            setTranslationX(0);
        } else if (mTranslateableViews != null) {
            for (int i = 0; i < mTranslateableViews.size(); i++) {
                mTranslateableViews.get(i).setTranslationX(0);
            }
            invalidateOutline();
            getEntry().expandedIcon.setScrollX(0);
        }

        if (mMenuRow != null) {
            mMenuRow.resetMenu();
        }
    }

    void onGutsOpened() {
        resetTranslation();
        updateContentAccessibilityImportanceForGuts(false /* isEnabled */);
    }

    void onGutsClosed() {
        updateContentAccessibilityImportanceForGuts(true /* isEnabled */);
    }

    /**
     * Updates whether all the non-guts content inside this row is important for accessibility.
     *
     * @param isEnabled whether the content views should be enabled for accessibility
     */
    private void updateContentAccessibilityImportanceForGuts(boolean isEnabled) {
        if (mChildrenContainer != null) {
            updateChildAccessibilityImportance(mChildrenContainer, isEnabled);
        }
        if (mLayouts != null) {
            for (View view : mLayouts) {
                updateChildAccessibilityImportance(view, isEnabled);
            }
        }

        if (isEnabled) {
            this.requestAccessibilityFocus();
        }
    }

    /**
     * Updates whether the given childView is important for accessibility based on
     * {@code isEnabled}.
     */
    private void updateChildAccessibilityImportance(View childView, boolean isEnabled) {
        childView.setImportantForAccessibility(isEnabled
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    public CharSequence getActiveRemoteInputText() {
        return mPrivateLayout.getActiveRemoteInputText();
    }

    public void animateTranslateNotification(final float leftTarget) {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }
        mTranslateAnim = getTranslateViewAnimator(leftTarget, null /* updateListener */);
        if (mTranslateAnim != null) {
            mTranslateAnim.start();
        }
    }

    @Override
    public void setTranslation(float translationX) {
        if (isBlockingHelperShowingAndTranslationFinished()) {
            mGuts.setTranslationX(translationX);
            return;
        } else if (!mShouldTranslateContents) {
            setTranslationX(translationX);
        } else if (mTranslateableViews != null) {
            // Translate the group of views
            for (int i = 0; i < mTranslateableViews.size(); i++) {
                if (mTranslateableViews.get(i) != null) {
                    mTranslateableViews.get(i).setTranslationX(translationX);
                }
            }
            invalidateOutline();

            // In order to keep the shelf in sync with this swiping, we're simply translating
            // it's icon by the same amount. The translation is already being used for the normal
            // positioning, so we can use the scrollX instead.
            getEntry().expandedIcon.setScrollX((int) -translationX);
        }

        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onParentTranslationUpdate(translationX);
        }
    }

    @Override
    public float getTranslation() {
        if (!mShouldTranslateContents) {
            return getTranslationX();
        }

        if (isBlockingHelperShowingAndCanTranslate()) {
            return mGuts.getTranslationX();
        }

        if (mTranslateableViews != null && mTranslateableViews.size() > 0) {
            // All of the views in the list should have same translation, just use first one.
            return mTranslateableViews.get(0).getTranslationX();
        }

        return 0;
    }

    private boolean isBlockingHelperShowingAndCanTranslate() {
        return areGutsExposed() && mIsBlockingHelperShowing && mNotificationTranslationFinished;
    }

    public Animator getTranslateViewAnimator(final float leftTarget,
            AnimatorUpdateListener listener) {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }

        final ObjectAnimator translateAnim = ObjectAnimator.ofFloat(this, TRANSLATE_CONTENT,
                leftTarget);
        if (listener != null) {
            translateAnim.addUpdateListener(listener);
        }
        translateAnim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator anim) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator anim) {
                if (mIsBlockingHelperShowing) {
                    mNotificationTranslationFinished = true;
                }
                if (!cancelled && leftTarget == 0) {
                    if (mMenuRow != null) {
                        mMenuRow.resetMenu();
                    }
                    mTranslateAnim = null;
                }
            }
        });
        mTranslateAnim = translateAnim;
        return translateAnim;
    }

    void ensureGutsInflated() {
        if (mGuts == null) {
            mGutsStub.inflate();
        }
    }

    private void updateChildrenVisibility() {
        boolean hideContentWhileLaunching = mExpandAnimationRunning && mGuts != null
                && mGuts.isExposed();
        mPrivateLayout.setVisibility(!mShowingPublic && !mIsSummaryWithChildren
                && !hideContentWhileLaunching ? VISIBLE : INVISIBLE);
        if (mChildrenContainer != null) {
            mChildrenContainer.setVisibility(!mShowingPublic && mIsSummaryWithChildren
                    && !hideContentWhileLaunching ? VISIBLE
                    : INVISIBLE);
        }
        // The limits might have changed if the view suddenly became a group or vice versa
        updateLimits();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // Add a record for the entire layout since its content is somehow small.
            // The event comes from a leaf view that is interacted with.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    public void applyExpandAnimationParams(ExpandAnimationParameters params) {
        if (params == null) {
            return;
        }
        float zProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                params.getProgress(0, 50));
        float translationZ = MathUtils.lerp(params.getStartTranslationZ(),
                mNotificationLaunchHeight,
                zProgress);
        setTranslationZ(translationZ);
        float extraWidthForClipping = params.getWidth() - getWidth()
                + MathUtils.lerp(0, mOutlineRadius * 2, params.getProgress());
        setExtraWidthForClipping(extraWidthForClipping);
        int top = params.getTop();
        float interpolation = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(params.getProgress());
        int startClipTopAmount = params.getStartClipTopAmount();
        if (mNotificationParent != null) {
            float parentY = mNotificationParent.getTranslationY();
            top -= parentY;
            mNotificationParent.setTranslationZ(translationZ);
            int parentStartClipTopAmount = params.getParentStartClipTopAmount();
            if (startClipTopAmount != 0) {
                int clipTopAmount = (int) MathUtils.lerp(parentStartClipTopAmount,
                        parentStartClipTopAmount - startClipTopAmount,
                        interpolation);
                mNotificationParent.setClipTopAmount(clipTopAmount);
            }
            mNotificationParent.setExtraWidthForClipping(extraWidthForClipping);
            float clipBottom = Math.max(params.getBottom(),
                    parentY + mNotificationParent.getActualHeight()
                            - mNotificationParent.getClipBottomAmount());
            float clipTop = Math.min(params.getTop(), parentY);
            int minimumHeightForClipping = (int) (clipBottom - clipTop);
            mNotificationParent.setMinimumHeightForClipping(minimumHeightForClipping);
        } else if (startClipTopAmount != 0) {
            int clipTopAmount = (int) MathUtils.lerp(startClipTopAmount, 0, interpolation);
            setClipTopAmount(clipTopAmount);
        }
        setTranslationY(top);
        setActualHeight(params.getHeight());

        mBackgroundNormal.setExpandAnimationParams(params);
    }

    public void setExpandAnimationRunning(boolean expandAnimationRunning) {
        View contentView;
        if (mIsSummaryWithChildren) {
            contentView =  mChildrenContainer;
        } else {
            contentView = getShowingLayout();
        }
        if (mGuts != null && mGuts.isExposed()) {
            contentView = mGuts;
        }
        if (expandAnimationRunning) {
            contentView.animate()
                    .alpha(0f)
                    .setDuration(ActivityLaunchAnimator.ANIMATION_DURATION_FADE_CONTENT)
                    .setInterpolator(Interpolators.ALPHA_OUT);
            setAboveShelf(true);
            mExpandAnimationRunning = true;
            getViewState().cancelAnimations(this);
            mNotificationLaunchHeight = AmbientState.getNotificationLaunchHeight(getContext());
        } else {
            mExpandAnimationRunning = false;
            setAboveShelf(isAboveShelf());
            if (mGuts != null) {
                mGuts.setAlpha(1.0f);
            }
            if (contentView != null) {
                contentView.setAlpha(1.0f);
            }
            setExtraWidthForClipping(0.0f);
            if (mNotificationParent != null) {
                mNotificationParent.setExtraWidthForClipping(0.0f);
                mNotificationParent.setMinimumHeightForClipping(0);
            }
        }
        if (mNotificationParent != null) {
            mNotificationParent.setChildIsExpanding(mExpandAnimationRunning);
        }
        updateChildrenVisibility();
        updateClipping();
        mBackgroundNormal.setExpandAnimationRunning(expandAnimationRunning);
    }

    private void setChildIsExpanding(boolean isExpanding) {
        mChildIsExpanding = isExpanding;
        updateClipping();
        invalidate();
    }

    @Override
    public boolean hasExpandingChild() {
        return mChildIsExpanding;
    }

    @Override
    protected boolean shouldClipToActualHeight() {
        return super.shouldClipToActualHeight() && !mExpandAnimationRunning;
    }

    @Override
    public boolean isExpandAnimationRunning() {
        return mExpandAnimationRunning;
    }

    /**
     * Tap sounds should not be played when we're unlocking.
     * Doing so would cause audio collision and the system would feel unpolished.
     */
    @Override
    public boolean isSoundEffectsEnabled() {
        final boolean mute = mStatusbarStateController != null
                && mStatusbarStateController.isDozing()
                && mSecureStateProvider != null &&
                !mSecureStateProvider.getAsBoolean();
        return !mute && super.isSoundEffectsEnabled();
    }

    public boolean isExpandable() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return !mChildrenExpanded;
        }
        return mEnableNonGroupedNotificationExpand && mExpandable;
    }

    public void setExpandable(boolean expandable) {
        mExpandable = expandable;
        mPrivateLayout.updateExpandButtons(isExpandable());
    }

    @Override
    public void setClipToActualHeight(boolean clipToActualHeight) {
        super.setClipToActualHeight(clipToActualHeight || isUserLocked());
        getShowingLayout().setClipToActualHeight(clipToActualHeight || isUserLocked());
    }

    /**
     * @return whether the user has changed the expansion state
     */
    public boolean hasUserChangedExpansion() {
        return mHasUserChangedExpansion;
    }

    public boolean isUserExpanded() {
        return mUserExpanded;
    }

    /**
     * Set this notification to be expanded by the user
     *
     * @param userExpanded whether the user wants this notification to be expanded
     */
    public void setUserExpanded(boolean userExpanded) {
        setUserExpanded(userExpanded, false /* allowChildExpansion */);
    }

    /**
     * Set this notification to be expanded by the user
     *
     * @param userExpanded whether the user wants this notification to be expanded
     * @param allowChildExpansion whether a call to this method allows expanding children
     */
    public void setUserExpanded(boolean userExpanded, boolean allowChildExpansion) {
        mFalsingManager.setNotificationExpanded();
        if (mIsSummaryWithChildren && !shouldShowPublic() && allowChildExpansion
                && !mChildrenContainer.showingAsLowPriority()) {
            final boolean wasExpanded = mGroupManager.isGroupExpanded(mStatusBarNotification);
            mGroupManager.setGroupExpanded(mStatusBarNotification, userExpanded);
            onExpansionChanged(true /* userAction */, wasExpanded);
            return;
        }
        if (userExpanded && !mExpandable) return;
        final boolean wasExpanded = isExpanded();
        mHasUserChangedExpansion = true;
        mUserExpanded = userExpanded;
        onExpansionChanged(true /* userAction */, wasExpanded);
        if (!wasExpanded && isExpanded()
                && getActualHeight() != getIntrinsicHeight()) {
            notifyHeightChanged(true /* needsAnimation */);
        }
    }

    public void resetUserExpansion() {
        boolean wasExpanded = isExpanded();
        mHasUserChangedExpansion = false;
        mUserExpanded = false;
        if (wasExpanded != isExpanded()) {
            if (mIsSummaryWithChildren) {
                mChildrenContainer.onExpansionChanged();
            }
            notifyHeightChanged(false /* needsAnimation */);
        }
        updateShelfIconColor();
    }

    public boolean isUserLocked() {
        return mUserLocked && !mForceUnlocked;
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
        mPrivateLayout.setUserExpanding(userLocked);
        // This is intentionally not guarded with mIsSummaryWithChildren since we might have had
        // children but not anymore.
        if (mChildrenContainer != null) {
            mChildrenContainer.setUserLocked(userLocked);
            if (mIsSummaryWithChildren && (userLocked || !isGroupExpanded())) {
                updateBackgroundForGroupState();
            }
        }
    }

    /**
     * @return has the system set this notification to be expanded
     */
    public boolean isSystemExpanded() {
        return mIsSystemExpanded;
    }

    /**
     * Set this notification to be expanded by the system.
     *
     * @param expand whether the system wants this notification to be expanded.
     */
    public void setSystemExpanded(boolean expand) {
        if (expand != mIsSystemExpanded) {
            final boolean wasExpanded = isExpanded();
            mIsSystemExpanded = expand;
            notifyHeightChanged(false /* needsAnimation */);
            onExpansionChanged(false /* userAction */, wasExpanded);
            if (mIsSummaryWithChildren) {
                mChildrenContainer.updateGroupOverflow();
            }
        }
    }

    /**
     * @param onKeyguard whether to prevent notification expansion
     */
    public void setOnKeyguard(boolean onKeyguard) {
        if (onKeyguard != mOnKeyguard) {
            boolean wasAboveShelf = isAboveShelf();
            final boolean wasExpanded = isExpanded();
            mOnKeyguard = onKeyguard;
            onExpansionChanged(false /* userAction */, wasExpanded);
            if (wasExpanded != isExpanded()) {
                if (mIsSummaryWithChildren) {
                    mChildrenContainer.updateGroupOverflow();
                }
                notifyHeightChanged(false /* needsAnimation */);
            }
            if (isAboveShelf() != wasAboveShelf) {
                mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
            }
        }
        updateRippleAllowed();
    }

    private void updateRippleAllowed() {
        boolean allowed = isOnKeyguard()
                || mEntry.notification.getNotification().contentIntent == null;
        setRippleAllowed(allowed);
    }

    @Override
    public int getIntrinsicHeight() {
        if (isUserLocked()) {
            return getActualHeight();
        }
        if (mGuts != null && mGuts.isExposed()) {
            return mGuts.getIntrinsicHeight();
        } else if ((isChildInGroup() && !isGroupExpanded())) {
            return mPrivateLayout.getMinHeight();
        } else if (mSensitive && mHideSensitiveForIntrinsicHeight) {
            return getMinHeight();
        } else if (mIsSummaryWithChildren) {
            return mChildrenContainer.getIntrinsicHeight();
        } else if (canShowHeadsUp() && isHeadsUpState()) {
            if (isPinned() || mHeadsupDisappearRunning) {
                return getPinnedHeadsUpHeight(true /* atLeastMinHeight */);
            } else if (isExpanded()) {
                return Math.max(getMaxExpandHeight(), getHeadsUpHeight());
            } else {
                return Math.max(getCollapsedHeight(), getHeadsUpHeight());
            }
        } else if (isExpanded()) {
            return getMaxExpandHeight();
        } else {
            return getCollapsedHeight();
        }
    }

    /**
     * @return {@code true} if the notification can show it's heads up layout. This is mostly true
     *         except for legacy use cases.
     */
    public boolean canShowHeadsUp() {
        if (mOnKeyguard && !isDozing() && !isBypassEnabled()) {
            return false;
        }
        return true;
    }

    private boolean isBypassEnabled() {
        return mBypassController == null || mBypassController.getBypassEnabled();
    }

    private boolean isDozing() {
        return mStatusbarStateController != null && mStatusbarStateController.isDozing();
    }

    @Override
    public boolean isGroupExpanded() {
        return mGroupManager.isGroupExpanded(mStatusBarNotification);
    }

    private void onChildrenCountChanged() {
        mIsSummaryWithChildren = StatusBar.ENABLE_CHILD_NOTIFICATIONS
                && mChildrenContainer != null && mChildrenContainer.getNotificationChildCount() > 0;
        if (mIsSummaryWithChildren && mChildrenContainer.getHeaderView() == null) {
            mChildrenContainer.recreateNotificationHeader(mExpandClickListener
            );
        }
        getShowingLayout().updateBackgroundColor(false /* animate */);
        mPrivateLayout.updateExpandButtons(isExpandable());
        updateChildrenHeaderAppearance();
        updateChildrenVisibility();
        applyChildrenRoundness();
    }
    /**
     * Returns the number of channels covered by the notification row (including its children if
     * it's a summary notification).
     */
    public int getNumUniqueChannels() {
        return getUniqueChannels().size();
    }

    /**
     * Returns the channels covered by the notification row (including its children if
     * it's a summary notification).
     */
    public ArraySet<NotificationChannel> getUniqueChannels() {
        ArraySet<NotificationChannel> channels = new ArraySet<>();

        channels.add(mEntry.getChannel());

        // If this is a summary, then add in the children notification channels for the
        // same user and pkg.
        if (mIsSummaryWithChildren) {
            final List<ExpandableNotificationRow> childrenRows = getNotificationChildren();
            final int numChildren = childrenRows.size();
            for (int i = 0; i < numChildren; i++) {
                final ExpandableNotificationRow childRow = childrenRows.get(i);
                final NotificationChannel childChannel = childRow.getEntry().getChannel();
                final StatusBarNotification childSbn = childRow.getStatusBarNotification();
                if (childSbn.getUser().equals(mStatusBarNotification.getUser()) &&
                        childSbn.getPackageName().equals(mStatusBarNotification.getPackageName())) {
                    channels.add(childChannel);
                }
            }
        }

        return channels;
    }

    public void updateChildrenHeaderAppearance() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.updateChildrenHeaderAppearance();
        }
    }

    /**
     * Check whether the view state is currently expanded. This is given by the system in {@link
     * #setSystemExpanded(boolean)} and can be overridden by user expansion or
     * collapsing in {@link #setUserExpanded(boolean)}. Note that the visual appearance of this
     * view can differ from this state, if layout params are modified from outside.
     *
     * @return whether the view state is currently expanded.
     */
    public boolean isExpanded() {
        return isExpanded(false /* allowOnKeyguard */);
    }

    public boolean isExpanded(boolean allowOnKeyguard) {
        return (!mOnKeyguard || allowOnKeyguard)
                && (!hasUserChangedExpansion() && (isSystemExpanded() || isSystemChildExpanded())
                || isUserExpanded());
    }

    private boolean isSystemChildExpanded() {
        return mIsSystemChildExpanded;
    }

    public void setSystemChildExpanded(boolean expanded) {
        mIsSystemChildExpanded = expanded;
    }

    public void setLayoutListener(LayoutListener listener) {
        mLayoutListener = listener;
    }

    public void removeListener() {
        mLayoutListener = null;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int intrinsicBefore = getIntrinsicHeight();
        super.onLayout(changed, left, top, right, bottom);
        if (intrinsicBefore != getIntrinsicHeight() && intrinsicBefore != 0) {
            notifyHeightChanged(true  /* needsAnimation */);
        }
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onParentHeightUpdate();
        }
        updateContentShiftHeight();
        if (mLayoutListener != null) {
            mLayoutListener.onLayout();
        }
    }

    /**
     * Updates the content shift height such that the header is completely hidden when coming from
     * the top.
     */
    private void updateContentShiftHeight() {
        NotificationHeaderView notificationHeader = getVisibleNotificationHeader();
        if (notificationHeader != null) {
            CachingIconView icon = notificationHeader.getIcon();
            mIconTransformContentShift = getRelativeTopPadding(icon) + icon.getHeight();
        } else {
            mIconTransformContentShift = mIconTransformContentShiftNoIcon;
        }
    }

    @Override
    public void notifyHeightChanged(boolean needsAnimation) {
        super.notifyHeightChanged(needsAnimation);
        getShowingLayout().requestSelectLayout(needsAnimation || isUserLocked());
    }

    public void setSensitive(boolean sensitive, boolean hideSensitive) {
        mSensitive = sensitive;
        mSensitiveHiddenInGeneral = hideSensitive;
    }

    @Override
    public void setHideSensitiveForIntrinsicHeight(boolean hideSensitive) {
        mHideSensitiveForIntrinsicHeight = hideSensitive;
        if (mIsSummaryWithChildren) {
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getNotificationChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.setHideSensitiveForIntrinsicHeight(hideSensitive);
            }
        }
    }

    @Override
    public void setHideSensitive(boolean hideSensitive, boolean animated, long delay,
            long duration) {
        if (getVisibility() == GONE) {
            // If we are GONE, the hideSensitive parameter will not be calculated and always be
            // false, which is incorrect, let's wait until a real call comes in later.
            return;
        }
        boolean oldShowingPublic = mShowingPublic;
        mShowingPublic = mSensitive && hideSensitive;
        if (mShowingPublicInitialized && mShowingPublic == oldShowingPublic) {
            return;
        }

        // bail out if no public version
        if (mPublicLayout.getChildCount() == 0) return;

        if (!animated) {
            mPublicLayout.animate().cancel();
            mPrivateLayout.animate().cancel();
            if (mChildrenContainer != null) {
                mChildrenContainer.animate().cancel();
                mChildrenContainer.setAlpha(1f);
            }
            mPublicLayout.setAlpha(1f);
            mPrivateLayout.setAlpha(1f);
            mPublicLayout.setVisibility(mShowingPublic ? View.VISIBLE : View.INVISIBLE);
            updateChildrenVisibility();
        } else {
            animateShowingPublic(delay, duration, mShowingPublic);
        }
        NotificationContentView showingLayout = getShowingLayout();
        showingLayout.updateBackgroundColor(animated);
        mPrivateLayout.updateExpandButtons(isExpandable());
        updateShelfIconColor();
        mShowingPublicInitialized = true;
    }

    private void animateShowingPublic(long delay, long duration, boolean showingPublic) {
        View[] privateViews = mIsSummaryWithChildren
                ? new View[] {mChildrenContainer}
                : new View[] {mPrivateLayout};
        View[] publicViews = new View[] {mPublicLayout};
        View[] hiddenChildren = showingPublic ? privateViews : publicViews;
        View[] shownChildren = showingPublic ? publicViews : privateViews;
        for (final View hiddenView : hiddenChildren) {
            hiddenView.setVisibility(View.VISIBLE);
            hiddenView.animate().cancel();
            hiddenView.animate()
                    .alpha(0f)
                    .setStartDelay(delay)
                    .setDuration(duration)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            hiddenView.setVisibility(View.INVISIBLE);
                        }
                    });
        }
        for (View showView : shownChildren) {
            showView.setVisibility(View.VISIBLE);
            showView.setAlpha(0f);
            showView.animate().cancel();
            showView.animate()
                    .alpha(1f)
                    .setStartDelay(delay)
                    .setDuration(duration);
        }
    }

    @Override
    public boolean mustStayOnScreen() {
        return mIsHeadsUp && mMustStayOnScreen;
    }

    /**
     * @return Whether this view is allowed to be dismissed. Only valid for visible notifications as
     *         otherwise some state might not be updated. To request about the general clearability
     *         see {@link NotificationEntry#isClearable()}.
     */
    public boolean canViewBeDismissed() {
        return mEntry.isClearable() && (!shouldShowPublic() || !mSensitiveHiddenInGeneral);
    }

    private boolean shouldShowPublic() {
        return mSensitive && mHideSensitiveForIntrinsicHeight;
    }

    public void makeActionsVisibile() {
        setUserExpanded(true, true);
        if (isChildInGroup()) {
            mGroupManager.setGroupExpanded(mStatusBarNotification, true);
        }
        notifyHeightChanged(false /* needsAnimation */);
    }

    public void setChildrenExpanded(boolean expanded, boolean animate) {
        mChildrenExpanded = expanded;
        if (mChildrenContainer != null) {
            mChildrenContainer.setChildrenExpanded(expanded);
        }
        updateBackgroundForGroupState();
        updateClickAndFocus();
    }

    public static void applyTint(View v, int color) {
        int alpha;
        if (color != 0) {
            alpha = COLORED_DIVIDER_ALPHA;
        } else {
            color = 0xff000000;
            alpha = DEFAULT_DIVIDER_ALPHA;
        }
        if (v.getBackground() instanceof ColorDrawable) {
            ColorDrawable background = (ColorDrawable) v.getBackground();
            background.mutate();
            background.setColor(color);
            background.setAlpha(alpha);
        }
    }

    public int getMaxExpandHeight() {
        return mPrivateLayout.getExpandHeight();
    }


    private int getHeadsUpHeight() {
        return getShowingLayout().getHeadsUpHeight(false /* forceNoHeader */);
    }

    public boolean areGutsExposed() {
        return (mGuts != null && mGuts.isExposed());
    }

    @Override
    public boolean isContentExpandable() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return true;
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.isContentExpandable();
    }

    @Override
    protected View getContentView() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer;
        }
        return getShowingLayout();
    }

    @Override
    public long performRemoveAnimation(long duration, long delay, float translationDirection,
            boolean isHeadsUpAnimation, float endLocation, Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        if (mMenuRow != null && mMenuRow.isMenuVisible()) {
            Animator anim = getTranslateViewAnimator(0f, null /* listener */);
            if (anim != null) {
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        ExpandableNotificationRow.super.performRemoveAnimation(
                                duration, delay, translationDirection, isHeadsUpAnimation,
                                endLocation, onFinishedRunnable, animationListener);
                    }
                });
                anim.start();
                return anim.getDuration();
            }
        }
        return super.performRemoveAnimation(duration, delay, translationDirection,
                isHeadsUpAnimation, endLocation, onFinishedRunnable, animationListener);
    }

    @Override
    protected void onAppearAnimationFinished(boolean wasAppearing) {
        super.onAppearAnimationFinished(wasAppearing);
        if (wasAppearing) {
            // During the animation the visible view might have changed, so let's make sure all
            // alphas are reset
            if (mChildrenContainer != null) {
                mChildrenContainer.setAlpha(1.0f);
                mChildrenContainer.setLayerType(LAYER_TYPE_NONE, null);
            }
            for (NotificationContentView l : mLayouts) {
                l.setAlpha(1.0f);
                l.setLayerType(LAYER_TYPE_NONE, null);
            }
        } else {
            setHeadsUpAnimatingAway(false);
        }
    }

    @Override
    public int getExtraBottomPadding() {
        if (mIsSummaryWithChildren && isGroupExpanded()) {
            return mIncreasedPaddingBetweenElements;
        }
        return 0;
    }

    @Override
    public void setActualHeight(int height, boolean notifyListeners) {
        boolean changed = height != getActualHeight();
        super.setActualHeight(height, notifyListeners);
        if (changed && isRemoved()) {
            // TODO: remove this once we found the gfx bug for this.
            // This is a hack since a removed view sometimes would just stay blank. it occured
            // when sending yourself a message and then clicking on it.
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) {
                parent.invalidate();
            }
        }
        if (mGuts != null && mGuts.isExposed()) {
            mGuts.setActualHeight(height);
            return;
        }
        int contentHeight = Math.max(getMinHeight(), height);
        for (NotificationContentView l : mLayouts) {
            l.setContentHeight(contentHeight);
        }
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setActualHeight(height);
        }
        if (mGuts != null) {
            mGuts.setActualHeight(height);
        }
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onParentHeightUpdate();
        }
    }

    @Override
    public int getMaxContentHeight() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getMaxContentHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMaxHeight();
    }

    @Override
    public int getMinHeight(boolean ignoreTemporaryStates) {
        if (!ignoreTemporaryStates && mGuts != null && mGuts.isExposed()) {
            return mGuts.getIntrinsicHeight();
        } else if (!ignoreTemporaryStates && canShowHeadsUp() && mIsHeadsUp
                && mHeadsUpManager.isTrackingHeadsUp()) {
                return getPinnedHeadsUpHeight(false /* atLeastMinHeight */);
        } else if (mIsSummaryWithChildren && !isGroupExpanded() && !shouldShowPublic()) {
            return mChildrenContainer.getMinHeight();
        } else if (!ignoreTemporaryStates && canShowHeadsUp() && mIsHeadsUp) {
            return getHeadsUpHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMinHeight();
    }

    @Override
    public int getCollapsedHeight() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getCollapsedHeight();
        }
        return getMinHeight();
    }

    @Override
    public int getHeadsUpHeightWithoutHeader() {
        if (!canShowHeadsUp() || !mIsHeadsUp) {
            return getCollapsedHeight();
        }
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getCollapsedHeightWithoutHeader();
        }
        return getShowingLayout().getHeadsUpHeight(true /* forceNoHeader */);
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        for (NotificationContentView l : mLayouts) {
            l.setClipTopAmount(clipTopAmount);
        }
        if (mGuts != null) {
            mGuts.setClipTopAmount(clipTopAmount);
        }
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        if (mExpandAnimationRunning) {
            return;
        }
        if (clipBottomAmount != mClipBottomAmount) {
            super.setClipBottomAmount(clipBottomAmount);
            for (NotificationContentView l : mLayouts) {
                l.setClipBottomAmount(clipBottomAmount);
            }
            if (mGuts != null) {
                mGuts.setClipBottomAmount(clipBottomAmount);
            }
        }
        if (mChildrenContainer != null && !mChildIsExpanding) {
            // We have to update this even if it hasn't changed, since the children locations can
            // have changed
            mChildrenContainer.setClipBottomAmount(clipBottomAmount);
        }
    }

    public NotificationContentView getShowingLayout() {
        return shouldShowPublic() ? mPublicLayout : mPrivateLayout;
    }

    public View getExpandedContentView() {
        return getPrivateLayout().getExpandedChild();
    }

    public void setLegacy(boolean legacy) {
        for (NotificationContentView l : mLayouts) {
            l.setLegacy(legacy);
        }
    }

    @Override
    protected void updateBackgroundTint() {
        super.updateBackgroundTint();
        updateBackgroundForGroupState();
        if (mIsSummaryWithChildren) {
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getNotificationChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.updateBackgroundForGroupState();
            }
        }
    }

    /**
     * Called when a group has finished animating from collapsed or expanded state.
     */
    public void onFinishedExpansionChange() {
        mGroupExpansionChanging = false;
        updateBackgroundForGroupState();
    }

    /**
     * Updates the parent and children backgrounds in a group based on the expansion state.
     */
    public void updateBackgroundForGroupState() {
        if (mIsSummaryWithChildren) {
            // Only when the group has finished expanding do we hide its background.
            mShowNoBackground = !mShowGroupBackgroundWhenExpanded && isGroupExpanded()
                    && !isGroupExpansionChanging() && !isUserLocked();
            mChildrenContainer.updateHeaderForExpansion(mShowNoBackground);
            List<ExpandableNotificationRow> children = mChildrenContainer.getNotificationChildren();
            for (int i = 0; i < children.size(); i++) {
                children.get(i).updateBackgroundForGroupState();
            }
        } else if (isChildInGroup()) {
            final int childColor = getShowingLayout().getBackgroundColorForExpansionState();
            // Only show a background if the group is expanded OR if it is expanding / collapsing
            // and has a custom background color.
            final boolean showBackground = isGroupExpanded()
                    || ((mNotificationParent.isGroupExpansionChanging()
                    || mNotificationParent.isUserLocked()) && childColor != 0);
            mShowNoBackground = !showBackground;
        } else {
            // Only children or parents ever need no background.
            mShowNoBackground = false;
        }
        updateOutline();
        updateBackground();
    }

    public int getPositionOfChild(ExpandableNotificationRow childRow) {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getPositionInLinearLayout(childRow);
        }
        return 0;
    }

    public void setExpansionLogger(ExpansionLogger logger, String key) {
        mLogger = logger;
        mLoggingKey = key;
    }

    public void onExpandedByGesture(boolean userExpanded) {
        int event = MetricsEvent.ACTION_NOTIFICATION_GESTURE_EXPANDER;
        if (mGroupManager.isSummaryOfGroup(getStatusBarNotification())) {
            event = MetricsEvent.ACTION_NOTIFICATION_GROUP_GESTURE_EXPANDER;
        }
        MetricsLogger.action(mContext, event, userExpanded);
    }

    @Override
    public float getIncreasedPaddingAmount() {
        if (mIsSummaryWithChildren) {
            if (isGroupExpanded()) {
                return 1.0f;
            } else if (isUserLocked()) {
                return mChildrenContainer.getIncreasedPaddingAmount();
            }
        } else if (isColorized() && (!mIsLowPriority || isExpanded())) {
            return -1.0f;
        }
        return 0.0f;
    }

    private boolean isColorized() {
        return mIsColorized && mBgTint != NO_COLOR;
    }

    @Override
    protected boolean disallowSingleClick(MotionEvent event) {
        if (areGutsExposed()) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        NotificationHeaderView header = getVisibleNotificationHeader();
        if (header != null && header.isInTouchRect(x - getTranslation(), y)) {
            return true;
        }
        if ((!mIsSummaryWithChildren || shouldShowPublic())
                && getShowingLayout().disallowSingleClick(x, y)) {
            return true;
        }
        return super.disallowSingleClick(event);
    }

    private void onExpansionChanged(boolean userAction, boolean wasExpanded) {
        boolean nowExpanded = isExpanded();
        if (mIsSummaryWithChildren && (!mIsLowPriority || wasExpanded)) {
            nowExpanded = mGroupManager.isGroupExpanded(mStatusBarNotification);
        }
        if (nowExpanded != wasExpanded) {
            updateShelfIconColor();
            if (mLogger != null) {
                mLogger.logNotificationExpansion(mLoggingKey, userAction, nowExpanded);
            }
            if (mIsSummaryWithChildren) {
                mChildrenContainer.onExpansionChanged();
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
        if (canViewBeDismissed()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        }
        boolean expandable = shouldShowPublic();
        boolean isExpanded = false;
        if (!expandable) {
            if (mIsSummaryWithChildren) {
                expandable = true;
                if (!mIsLowPriority || isExpanded()) {
                    isExpanded = isGroupExpanded();
                }
            } else {
                expandable = mPrivateLayout.isContentExpandable();
                isExpanded = isExpanded();
            }
        }
        if (expandable) {
            if (isExpanded) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
            } else {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
        }
        NotificationMenuRowPlugin provider = getProvider();
        if (provider != null) {
            MenuItem snoozeMenu = provider.getSnoozeMenuItem(getContext());
            if (snoozeMenu != null) {
                AccessibilityAction action = new AccessibilityAction(R.id.action_snooze,
                    getContext().getResources()
                        .getString(R.string.notification_menu_snooze_action));
                info.addAction(action);
            }
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_DISMISS:
                performDismissWithBlockingHelper(true /* fromAccessibility */);
                return true;
            case AccessibilityNodeInfo.ACTION_COLLAPSE:
            case AccessibilityNodeInfo.ACTION_EXPAND:
                mExpandClickListener.onClick(this);
                return true;
            case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                doLongClickCallback();
                return true;
            default:
                if (action == R.id.action_snooze) {
                    NotificationMenuRowPlugin provider = getProvider();
                    if (provider == null && mMenuRow != null) {
                        provider = createMenu();
                    } else {
                        return false;
                    }
                    MenuItem snoozeMenu = provider.getSnoozeMenuItem(getContext());
                    if (snoozeMenu != null) {
                        doLongClickCallback(getWidth() / 2, getHeight() / 2, snoozeMenu);
                    }
                    return true;
                }
        }
        return false;
    }

    public boolean shouldRefocusOnDismiss() {
        return mRefocusOnDismiss || isAccessibilityFocused();
    }

    public interface OnExpandClickListener {
        void onExpandClicked(NotificationEntry clickedEntry, boolean nowExpanded);
    }

    @Override
    public ExpandableViewState createExpandableViewState() {
        return new NotificationViewState();
    }

    @Override
    public boolean isAboveShelf() {
        return (canShowHeadsUp()
                && (mIsPinned || mHeadsupDisappearRunning || (mIsHeadsUp && mAboveShelf)
                || mExpandAnimationRunning || mChildIsExpanding));
    }

    @Override
    public boolean topAmountNeedsClipping() {
        if (isGroupExpanded()) {
            return true;
        }
        if (isGroupExpansionChanging()) {
            return true;
        }
        if (getShowingLayout().shouldClipToRounding(true /* topRounded */,
                false /* bottomRounded */)) {
            return true;
        }
        if (mGuts != null && mGuts.getAlpha() != 0.0f) {
            return true;
        }
        return false;
    }

    @Override
    protected boolean childNeedsClipping(View child) {
        if (child instanceof NotificationContentView) {
            NotificationContentView contentView = (NotificationContentView) child;
            if (isClippingNeeded()) {
                return true;
            } else if (!hasNoRounding()
                    && contentView.shouldClipToRounding(getCurrentTopRoundness() != 0.0f,
                    getCurrentBottomRoundness() != 0.0f)) {
                return true;
            }
        } else if (child == mChildrenContainer) {
            if (isClippingNeeded() || !hasNoRounding()) {
                return true;
            }
        } else if (child instanceof NotificationGuts) {
            return !hasNoRounding();
        }
        return super.childNeedsClipping(child);
    }

    @Override
    protected void applyRoundness() {
        super.applyRoundness();
        applyChildrenRoundness();
    }

    private void applyChildrenRoundness() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setCurrentBottomRoundness(getCurrentBottomRoundness());
        }
    }

    @Override
    public Path getCustomClipPath(View child) {
        if (child instanceof NotificationGuts) {
            return getClipPath(true /* ignoreTranslation */);
        }
        return super.getCustomClipPath(child);
    }

    private boolean hasNoRounding() {
        return getCurrentBottomRoundness() == 0.0f && getCurrentTopRoundness() == 0.0f;
    }

    //TODO: this logic can't depend on layout if we are recycling!
    public boolean isMediaRow() {
        return getExpandedContentView() != null
                && getExpandedContentView().findViewById(
                com.android.internal.R.id.media_actions) != null;
    }

    public boolean isTopLevelChild() {
        return getParent() instanceof NotificationStackScrollLayout;
    }

    public boolean isGroupNotFullyVisible() {
        return getClipTopAmount() > 0 || getTranslationY() < 0;
    }

    public void setAboveShelf(boolean aboveShelf) {
        boolean wasAboveShelf = isAboveShelf();
        mAboveShelf = aboveShelf;
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
    }

    /** Sets whether dismiss gestures are right-to-left (instead of left-to-right). */
    public void setDismissRtl(boolean dismissRtl) {
        if (mMenuRow != null) {
            mMenuRow.setDismissRtl(dismissRtl);
        }
    }

    private static class NotificationViewState extends ExpandableViewState {

        @Override
        public void applyToView(View view) {
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                if (row.isExpandAnimationRunning()) {
                    return;
                }
                handleFixedTranslationZ(row);
                super.applyToView(view);
                row.applyChildrenState();
            }
        }

        private void handleFixedTranslationZ(ExpandableNotificationRow row) {
            if (row.hasExpandingChild()) {
                zTranslation = row.getTranslationZ();
                clipTopAmount = row.getClipTopAmount();
            }
        }

        @Override
        protected void onYTranslationAnimationFinished(View view) {
            super.onYTranslationAnimationFinished(view);
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                if (row.isHeadsUpAnimatingAway()) {
                    row.setHeadsUpAnimatingAway(false);
                }
            }
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (row.isExpandAnimationRunning()) {
                    return;
                }
                handleFixedTranslationZ(row);
                super.animateTo(child, properties);
                row.startChildAnimation(properties);
            }
        }
    }

    /**
     * Returns the Smart Suggestions backing the smart suggestion buttons in the notification.
     */
    public SmartRepliesAndActions getExistingSmartRepliesAndActions() {
        return mPrivateLayout.getCurrentSmartRepliesAndActions();
    }

    @VisibleForTesting
    protected void setChildrenContainer(NotificationChildrenContainer childrenContainer) {
        mChildrenContainer = childrenContainer;
    }

    @VisibleForTesting
    protected void setPrivateLayout(NotificationContentView privateLayout) {
        mPrivateLayout = privateLayout;
    }

    @VisibleForTesting
    protected void setPublicLayout(NotificationContentView publicLayout) {
        mPublicLayout = publicLayout;
    }

    /**
     * Equivalent to View.OnLongClickListener with coordinates
     */
    public interface LongPressListener {
        /**
         * Equivalent to {@link View.OnLongClickListener#onLongClick(View)} with coordinates
         * @return whether the longpress was handled
         */
        boolean onLongPress(View v, int x, int y, MenuItem item);
    }

    /**
     * Equivalent to View.OnClickListener with coordinates
     */
    public interface OnAppOpsClickListener {
        /**
         * Equivalent to {@link View.OnClickListener#onClick(View)} with coordinates
         * @return whether the click was handled
         */
        boolean onClick(View v, int x, int y, MenuItem item);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("  Notification: " + getStatusBarNotification().getKey());
        pw.print("    visibility: " + getVisibility());
        pw.print(", alpha: " + getAlpha());
        pw.print(", translation: " + getTranslation());
        pw.print(", removed: " + isRemoved());
        pw.print(", expandAnimationRunning: " + mExpandAnimationRunning);
        NotificationContentView showingLayout = getShowingLayout();
        pw.print(", privateShowing: " + (showingLayout == mPrivateLayout));
        pw.println();
        showingLayout.dump(fd, pw, args);
        pw.print("    ");
        if (getViewState() != null) {
            getViewState().dump(fd, pw, args);
        } else {
            pw.print("no viewState!!!");
        }
        pw.println();
        pw.println();
        if (mIsSummaryWithChildren) {
            pw.print("  ChildrenContainer");
            pw.print(" visibility: " + mChildrenContainer.getVisibility());
            pw.print(", alpha: " + mChildrenContainer.getAlpha());
            pw.print(", translationY: " + mChildrenContainer.getTranslationY());
            pw.println();
            List<ExpandableNotificationRow> notificationChildren = getNotificationChildren();
            pw.println("  Children: " + notificationChildren.size());
            pw.println("  {");
            for(ExpandableNotificationRow child : notificationChildren) {
                child.dump(fd, pw, args);
            }
            pw.println("  }");
            pw.println();
        }
    }

    /**
     * Background task for executing IPCs to check if the notification is a system notification. The
     * output is used for both the blocking helper and the notification info.
     */
    private class SystemNotificationAsyncTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... voids) {
            return isSystemNotification(mContext, mStatusBarNotification);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (mEntry != null) {
                mEntry.mIsSystemNotification = result;
            }
        }
    }
}
