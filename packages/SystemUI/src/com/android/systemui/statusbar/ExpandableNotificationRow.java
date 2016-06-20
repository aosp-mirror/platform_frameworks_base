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
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Property;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewStub;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Chronometer;
import android.widget.ImageView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.stack.NotificationChildrenContainer;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackScrollState;
import com.android.systemui.statusbar.stack.StackStateAnimator;
import com.android.systemui.statusbar.stack.StackViewState;

import java.util.ArrayList;
import java.util.List;

public class ExpandableNotificationRow extends ActivatableNotificationView {

    private static final int DEFAULT_DIVIDER_ALPHA = 0x29;
    private static final int COLORED_DIVIDER_ALPHA = 0x7B;
    private int mNotificationMinHeightLegacy;
    private int mMaxHeadsUpHeightLegacy;
    private int mMaxHeadsUpHeight;
    private int mNotificationMinHeight;
    private int mNotificationMaxHeight;
    private int mIncreasedPaddingBetweenElements;

    /** Does this row contain layouts that can adapt to row expansion */
    private boolean mExpandable;
    /** Has the user actively changed the expansion state of this row */
    private boolean mHasUserChangedExpansion;
    /** If {@link #mHasUserChangedExpansion}, has the user expanded this row */
    private boolean mUserExpanded;

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
    private int mMaxExpandHeight;
    private int mHeadsUpHeight;
    private View mVetoButton;
    private int mNotificationColor;
    private boolean mClearable;
    private ExpansionLogger mLogger;
    private String mLoggingKey;
    private NotificationSettingsIconRow mSettingsIconRow;
    private NotificationGuts mGuts;
    private NotificationData.Entry mEntry;
    private StatusBarNotification mStatusBarNotification;
    private String mAppName;
    private boolean mIsHeadsUp;
    private boolean mLastChronometerRunning = true;
    private ViewStub mChildrenContainerStub;
    private NotificationGroupManager mGroupManager;
    private boolean mChildrenExpanded;
    private boolean mIsSummaryWithChildren;
    private NotificationChildrenContainer mChildrenContainer;
    private ViewStub mSettingsIconRowStub;
    private ViewStub mGutsStub;
    private boolean mIsSystemChildExpanded;
    private boolean mIsPinned;
    private FalsingManager mFalsingManager;
    private HeadsUpManager mHeadsUpManager;

    private boolean mJustClicked;
    private boolean mIconAnimationRunning;
    private boolean mShowNoBackground;
    private ExpandableNotificationRow mNotificationParent;
    private OnExpandClickListener mOnExpandClickListener;
    private boolean mGroupExpansionChanging;

    private OnClickListener mExpandClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!mShowingPublic && mGroupManager.isSummaryOfGroup(mStatusBarNotification)) {
                mGroupExpansionChanging = true;
                final boolean wasExpanded = mGroupManager.isGroupExpanded(mStatusBarNotification);
                boolean nowExpanded = mGroupManager.toggleGroupExpansion(mStatusBarNotification);
                mOnExpandClickListener.onExpandClicked(mEntry, nowExpanded);
                MetricsLogger.action(mContext, MetricsEvent.ACTION_NOTIFICATION_GROUP_EXPANDER,
                        nowExpanded);
                logExpansionEvent(true /* userAction */, wasExpanded);
            } else {
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
        setIconAnimationRunning(running, mPublicLayout);
        setIconAnimationRunning(running, mPrivateLayout);
        if (mIsSummaryWithChildren) {
            setIconAnimationRunningForChild(running, mChildrenContainer.getHeaderView());
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

    public void onNotificationUpdated(NotificationData.Entry entry) {
        mEntry = entry;
        mStatusBarNotification = entry.notification;
        mPrivateLayout.onNotificationUpdated(entry);
        mPublicLayout.onNotificationUpdated(entry);
        mShowingPublicInitialized = false;
        updateNotificationColor();
        updateClearability();
        if (mIsSummaryWithChildren) {
            mChildrenContainer.recreateNotificationHeader(mExpandClickListener, mEntry.notification);
            mChildrenContainer.onNotificationUpdated();
        }
        if (mIconAnimationRunning) {
            setIconAnimationRunning(true);
        }
        if (mNotificationParent != null) {
            mNotificationParent.updateChildrenHeaderAppearance();
        }
        onChildrenCountChanged();
        // The public layouts expand button is always visible
        mPublicLayout.updateExpandButtons(true);
        updateLimits();
    }

    private void updateLimits() {
        updateLimitsForView(mPrivateLayout);
        updateLimitsForView(mPublicLayout);
    }

    private void updateLimitsForView(NotificationContentView layout) {
        boolean customView = layout.getContractedChild().getId()
                != com.android.internal.R.id.status_bar_latest_event_content;
        boolean beforeN = mEntry.targetSdk < Build.VERSION_CODES.N;
        int minHeight = customView && beforeN && !mIsSummaryWithChildren ?
                mNotificationMinHeightLegacy : mNotificationMinHeight;
        boolean headsUpCustom = layout.getHeadsUpChild() != null &&
                layout.getHeadsUpChild().getId()
                        != com.android.internal.R.id.status_bar_latest_event_content;
        int headsUpheight = headsUpCustom && beforeN ? mMaxHeadsUpHeightLegacy
                : mMaxHeadsUpHeight;
        layout.setHeights(minHeight, headsUpheight, mNotificationMaxHeight);
    }

    public StatusBarNotification getStatusBarNotification() {
        return mStatusBarNotification;
    }

    public boolean isHeadsUp() {
        return mIsHeadsUp;
    }

    public void setHeadsUp(boolean isHeadsUp) {
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
        if (mSettingsIconRow != null) {
            mSettingsIconRow.setAppName(mAppName);
        }
    }

    public void addChildNotification(ExpandableNotificationRow row) {
        addChildNotification(row, -1);
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
    }

    public boolean isChildInGroup() {
        return mNotificationParent != null;
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mNotificationParent;
    }

    /**
     * @param isChildInGroup Is this notification now in a group
     * @param parent the new parent notification
     */
    public void setIsChildInGroup(boolean isChildInGroup, ExpandableNotificationRow parent) {;
        boolean childInGroup = BaseStatusBar.ENABLE_CHILD_NOTIFICATIONS && isChildInGroup;
        mNotificationParent = childInGroup ? parent : null;
        mPrivateLayout.setIsChildInGroup(childInGroup);
        resetBackgroundAlpha();
        updateBackgroundForGroupState();
        updateClickAndFocus();
        if (mNotificationParent != null) {
            mNotificationParent.updateBackgroundForGroupState();
        }
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
        if (mSettingsIconRow != null && mSettingsIconRow.isVisible()) {
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
     * @return whether the list order has changed
     */
    public boolean applyChildOrder(List<ExpandableNotificationRow> childOrder) {
        return mChildrenContainer != null && mChildrenContainer.applyChildOrder(childOrder);
    }

    public void getChildrenStates(StackScrollState resultState) {
        if (mIsSummaryWithChildren) {
            StackViewState parentState = resultState.getViewStateForView(this);
            mChildrenContainer.getState(resultState, parentState);
        }
    }

    public void applyChildrenState(StackScrollState state) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.applyState(state);
        }
    }

    public void prepareExpansionChanged(StackScrollState state) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.prepareExpansionChanged(state);
        }
    }

    public void startChildAnimation(StackScrollState finalState,
            StackStateAnimator stateAnimator, long delay, long duration) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.startAnimationToState(finalState, stateAnimator, delay,
                    duration);
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
        mIsPinned = pinned;
        if (intrinsicHeight != getIntrinsicHeight()) {
            notifyHeightChanged(false);
        }
        if (pinned) {
            setIconAnimationRunning(true);
            mExpandedWhenPinned = false;
        } else if (mExpandedWhenPinned) {
            setUserExpanded(true);
        }
        setChronometerRunning(mLastChronometerRunning);
    }

    public boolean isPinned() {
        return mIsPinned;
    }

    /**
     * @param atLeastMinHeight should the value returned be at least the minimum height.
     *                         Used to avoid cyclic calls
     * @return the height of the heads up notification when pinned
     */
    public int getPinnedHeadsUpHeight(boolean atLeastMinHeight) {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getIntrinsicHeight();
        }
        if(mExpandedWhenPinned) {
            return Math.max(getMaxExpandHeight(), mHeadsUpHeight);
        } else if (atLeastMinHeight) {
            return Math.max(getCollapsedHeight(), mHeadsUpHeight);
        } else {
            return mHeadsUpHeight;
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

    private NotificationHeaderView getVisibleNotificationHeader() {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getHeaderView();
        }
        return getShowingLayout().getVisibleNotificationHeader();
    }

    public void setOnExpandClickListener(OnExpandClickListener onExpandClickListener) {
        mOnExpandClickListener = onExpandClickListener;
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

    public void reInflateViews() {
        initDimens();
        if (mIsSummaryWithChildren) {
            if (mChildrenContainer != null) {
                mChildrenContainer.reInflateViews(mExpandClickListener, mEntry.notification);
            }
        }
        if (mGuts != null) {
            View oldGuts = mGuts;
            int index = indexOfChild(oldGuts);
            removeView(oldGuts);
            mGuts = (NotificationGuts) LayoutInflater.from(mContext).inflate(
                    R.layout.notification_guts, this, false);
            mGuts.setVisibility(oldGuts.getVisibility());
            addView(mGuts, index);
        }
        if (mSettingsIconRow != null) {
            View oldSettings = mSettingsIconRow;
            int settingsIndex = indexOfChild(oldSettings);
            removeView(oldSettings);
            mSettingsIconRow = (NotificationSettingsIconRow) LayoutInflater.from(mContext).inflate(
                    R.layout.notification_settings_icon_row, this, false);
            mSettingsIconRow.setNotificationRowParent(ExpandableNotificationRow.this);
            mSettingsIconRow.setAppName(mAppName);
            mSettingsIconRow.setVisibility(oldSettings.getVisibility());
            addView(mSettingsIconRow, settingsIndex);

        }
        mPrivateLayout.reInflateViews();
        mPublicLayout.reInflateViews();
    }

    public void setContentBackground(int customBackgroundColor, boolean animate,
            NotificationContentView notificationContentView) {
        if (getShowingLayout() == notificationContentView) {
            setTintColor(customBackgroundColor, animate);
        }
    }

    public void closeRemoteInput() {
        mPrivateLayout.closeRemoteInput();
        mPublicLayout.closeRemoteInput();
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
        mNotificationColor = NotificationColorUtil.resolveContrastColor(mContext,
                getStatusBarNotification().getNotification().color);
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

    public void setDismissed(boolean dismissed, boolean fromAccessibility) {
        mDismissed = dismissed;
        mGroupParentWhenDismissed = mNotificationParent;
        mRefocusOnDismiss = fromAccessibility;
        mChildAfterViewWhenDismissed = null;
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

    public boolean isRemoved() {
        return mRemoved;
    }

    public void setRemoved() {
        mRemoved = true;

        mPrivateLayout.setRemoved();
    }

    public NotificationChildrenContainer getChildrenContainer() {
        return mChildrenContainer;
    }

    public void setHeadsupDisappearRunning(boolean running) {
        mHeadsupDisappearRunning = running;
        mPrivateLayout.setHeadsupDisappearRunning(running);
    }

    public View getChildAfterViewWhenDismissed() {
        return mChildAfterViewWhenDismissed;
    }

    public View getGroupParentWhenDismissed() {
        return mGroupParentWhenDismissed;
    }

    public interface ExpansionLogger {
        public void logNotificationExpansion(String key, boolean userAction, boolean expanded);
    }

    public ExpandableNotificationRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFalsingManager = FalsingManager.getInstance(context);
        initDimens();
    }

    private void initDimens() {
        mNotificationMinHeightLegacy = getFontScaledHeight(R.dimen.notification_min_height_legacy);
        mNotificationMinHeight = getFontScaledHeight(R.dimen.notification_min_height);
        mNotificationMaxHeight = getFontScaledHeight(R.dimen.notification_max_height);
        mMaxHeadsUpHeightLegacy = getFontScaledHeight(
                R.dimen.notification_max_heads_up_height_legacy);
        mMaxHeadsUpHeight = getFontScaledHeight(R.dimen.notification_max_heads_up_height);
        mIncreasedPaddingBetweenElements = getResources()
                .getDimensionPixelSize(R.dimen.notification_divider_height_increased);
    }

    /**
     * @param dimenId the dimen to look up
     * @return the font scaled dimen as if it were in sp but doesn't shrink sizes below dp
     */
    private int getFontScaledHeight(int dimenId) {
        int dimensionPixelSize = getResources().getDimensionPixelSize(dimenId);
        float factor = Math.max(1.0f, getResources().getDisplayMetrics().scaledDensity /
                getResources().getDisplayMetrics().density);
        return (int) (dimensionPixelSize * factor);
    }

    /**
     * Resets this view so it can be re-used for an updated notification.
     */
    @Override
    public void reset() {
        super.reset();
        final boolean wasExpanded = isExpanded();
        mExpandable = false;
        mHasUserChangedExpansion = false;
        mUserLocked = false;
        mShowingPublic = false;
        mSensitive = false;
        mShowingPublicInitialized = false;
        mIsSystemExpanded = false;
        mOnKeyguard = false;
        mPublicLayout.reset();
        mPrivateLayout.reset();
        resetHeight();
        resetTranslation();
        logExpansionEvent(false, wasExpanded);
    }

    public void resetHeight() {
        mMaxExpandHeight = 0;
        mHeadsUpHeight = 0;
        onHeightReset();
        requestLayout();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPublicLayout = (NotificationContentView) findViewById(R.id.expandedPublic);
        mPublicLayout.setContainingNotification(this);
        mPrivateLayout = (NotificationContentView) findViewById(R.id.expanded);
        mPrivateLayout.setExpandClickListener(mExpandClickListener);
        mPrivateLayout.setContainingNotification(this);
        mPublicLayout.setExpandClickListener(mExpandClickListener);
        mSettingsIconRowStub = (ViewStub) findViewById(R.id.settings_icon_row_stub);
        mSettingsIconRowStub.setOnInflateListener(new ViewStub.OnInflateListener() {
            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mSettingsIconRow = (NotificationSettingsIconRow) inflated;
                mSettingsIconRow.setNotificationRowParent(ExpandableNotificationRow.this);
                mSettingsIconRow.setAppName(mAppName);
            }
        });
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
                mChildrenContainer.setNotificationParent(ExpandableNotificationRow.this);
                mChildrenContainer.onNotificationUpdated();
                mTranslateableViews.add(mChildrenContainer);
            }
        });
        mVetoButton = findViewById(R.id.veto);

        // Add the views that we translate to reveal the gear
        mTranslateableViews = new ArrayList<View>();
        for (int i = 0; i < getChildCount(); i++) {
            mTranslateableViews.add(getChildAt(i));
        }
        // Remove views that don't translate
        mTranslateableViews.remove(mVetoButton);
        mTranslateableViews.remove(mSettingsIconRowStub);
        mTranslateableViews.remove(mChildrenContainerStub);
        mTranslateableViews.remove(mGutsStub);
    }

    public void resetTranslation() {
        if (mTranslateableViews != null) {
            for (int i = 0; i < mTranslateableViews.size(); i++) {
                mTranslateableViews.get(i).setTranslationX(0);
            }
        }
        invalidateOutline();
        if (mSettingsIconRow != null) {
            mSettingsIconRow.resetState();
        }
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
        if (areGutsExposed()) {
            // Don't translate if guts are showing.
            return;
        }
        // Translate the group of views
        for (int i = 0; i < mTranslateableViews.size(); i++) {
            if (mTranslateableViews.get(i) != null) {
                mTranslateableViews.get(i).setTranslationX(translationX);
            }
        }
        invalidateOutline();
        if (mSettingsIconRow != null) {
            mSettingsIconRow.updateSettingsIcons(translationX, getMeasuredWidth());
        }
    }

    @Override
    public float getTranslation() {
        if (mTranslateableViews != null && mTranslateableViews.size() > 0) {
            // All of the views in the list should have same translation, just use first one.
            return mTranslateableViews.get(0).getTranslationX();
        }
        return 0;
    }

    public Animator getTranslateViewAnimator(final float leftTarget,
            AnimatorUpdateListener listener) {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }
        if (areGutsExposed()) {
            // No translation if guts are exposed.
            return null;
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
                if (!cancelled && mSettingsIconRow != null && leftTarget == 0) {
                    mSettingsIconRow.resetState();
                    mTranslateAnim = null;
                }
            }
        });
        mTranslateAnim = translateAnim;
        return translateAnim;
    }

    public float getSpaceForGear() {
        if (mSettingsIconRow != null) {
            return mSettingsIconRow.getSpaceForGear();
        }
        return 0;
    }

    public NotificationSettingsIconRow getSettingsRow() {
        if (mSettingsIconRow == null) {
            mSettingsIconRowStub.inflate();
        }
        return mSettingsIconRow;
    }

    public void inflateGuts() {
        if (mGuts == null) {
            mGutsStub.inflate();
        }
    }

    private void updateChildrenVisibility() {
        mPrivateLayout.setVisibility(!mShowingPublic && !mIsSummaryWithChildren ? VISIBLE
                : INVISIBLE);
        if (mChildrenContainer != null) {
            mChildrenContainer.setVisibility(!mShowingPublic && mIsSummaryWithChildren ? VISIBLE
                    : INVISIBLE);
            mChildrenContainer.updateHeaderVisibility(!mShowingPublic && mIsSummaryWithChildren
                    ? VISIBLE
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

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
        super.setDark(dark, fade, delay);
        final NotificationContentView showing = getShowingLayout();
        if (showing != null) {
            showing.setDark(dark, fade, delay);
        }
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setDark(dark, fade, delay);
        }
    }

    public boolean isExpandable() {
        if (mIsSummaryWithChildren && !mShowingPublic) {
            return !mChildrenExpanded;
        }
        return mExpandable;
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
        if (mIsSummaryWithChildren && !mShowingPublic && allowChildExpansion) {
            final boolean wasExpanded = mGroupManager.isGroupExpanded(mStatusBarNotification);
            mGroupManager.setGroupExpanded(mStatusBarNotification, userExpanded);
            logExpansionEvent(true /* userAction */, wasExpanded);
            return;
        }
        if (userExpanded && !mExpandable) return;
        final boolean wasExpanded = isExpanded();
        mHasUserChangedExpansion = true;
        mUserExpanded = userExpanded;
        logExpansionEvent(true, wasExpanded);
    }

    public void resetUserExpansion() {
        mHasUserChangedExpansion = false;
        mUserExpanded = false;
    }

    public boolean isUserLocked() {
        return mUserLocked && !mForceUnlocked;
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
        mPrivateLayout.setUserExpanding(userLocked);
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setUserLocked(userLocked);
            if (userLocked || (!userLocked && !isGroupExpanded())) {
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
            logExpansionEvent(false, wasExpanded);
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
            final boolean wasExpanded = isExpanded();
            mOnKeyguard = onKeyguard;
            logExpansionEvent(false, wasExpanded);
            if (wasExpanded != isExpanded()) {
                if (mIsSummaryWithChildren) {
                    mChildrenContainer.updateGroupOverflow();
                }
                notifyHeightChanged(false /* needsAnimation */);
            }
        }
    }

    /**
     * @return Can the underlying notification be cleared?
     */
    public boolean isClearable() {
        return mStatusBarNotification != null && mStatusBarNotification.isClearable();
    }

    @Override
    public int getIntrinsicHeight() {
        if (isUserLocked()) {
            return getActualHeight();
        }
        if (mGuts != null && mGuts.areGutsExposed()) {
            return mGuts.getHeight();
        } else if ((isChildInGroup() && !isGroupExpanded())) {
            return mPrivateLayout.getMinHeight();
        } else if (mSensitive && mHideSensitiveForIntrinsicHeight) {
            return getMinHeight();
        } else if (mIsSummaryWithChildren && !mOnKeyguard) {
            return mChildrenContainer.getIntrinsicHeight();
        } else if (mIsHeadsUp || mHeadsupDisappearRunning) {
            if (isPinned() || mHeadsupDisappearRunning) {
                return getPinnedHeadsUpHeight(true /* atLeastMinHeight */);
            } else if (isExpanded()) {
                return Math.max(getMaxExpandHeight(), mHeadsUpHeight);
            } else {
                return Math.max(getCollapsedHeight(), mHeadsUpHeight);
            }
        } else if (isExpanded()) {
            return getMaxExpandHeight();
        } else {
            return getCollapsedHeight();
        }
    }

    public boolean isGroupExpanded() {
        return mGroupManager.isGroupExpanded(mStatusBarNotification);
    }

    private void onChildrenCountChanged() {
        mIsSummaryWithChildren = BaseStatusBar.ENABLE_CHILD_NOTIFICATIONS
                && mChildrenContainer != null && mChildrenContainer.getNotificationChildCount() > 0;
        if (mIsSummaryWithChildren && mChildrenContainer.getHeaderView() == null) {
            mChildrenContainer.recreateNotificationHeader(mExpandClickListener,
                    mEntry.notification);
        }
        getShowingLayout().updateBackgroundColor(false /* animate */);
        mPrivateLayout.updateExpandButtons(isExpandable());
        updateChildrenHeaderAppearance();
        updateChildrenVisibility();
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateMaxHeights();
        if (mSettingsIconRow != null) {
            mSettingsIconRow.updateVerticalLocation();
        }
    }

    private void updateMaxHeights() {
        int intrinsicBefore = getIntrinsicHeight();
        View expandedChild = mPrivateLayout.getExpandedChild();
        if (expandedChild == null) {
            expandedChild = mPrivateLayout.getContractedChild();
        }
        mMaxExpandHeight = expandedChild.getHeight();
        View headsUpChild = mPrivateLayout.getHeadsUpChild();
        if (headsUpChild == null) {
            headsUpChild = mPrivateLayout.getContractedChild();
        }
        mHeadsUpHeight = headsUpChild.getHeight();
        if (intrinsicBefore != getIntrinsicHeight()) {
            notifyHeightChanged(false  /* needsAnimation */);
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

    public void setHideSensitive(boolean hideSensitive, boolean animated, long delay,
            long duration) {
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
            animateShowingPublic(delay, duration);
        }
        NotificationContentView showingLayout = getShowingLayout();
        showingLayout.updateBackgroundColor(animated);
        mPrivateLayout.updateExpandButtons(isExpandable());
        updateClearability();
        mShowingPublicInitialized = true;
    }

    private void animateShowingPublic(long delay, long duration) {
        View[] privateViews = mIsSummaryWithChildren
                ? new View[] {mChildrenContainer}
                : new View[] {mPrivateLayout};
        View[] publicViews = new View[] {mPublicLayout};
        View[] hiddenChildren = mShowingPublic ? privateViews : publicViews;
        View[] shownChildren = mShowingPublic ? publicViews : privateViews;
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

    public boolean mustStayOnScreen() {
        return mIsHeadsUp;
    }

    private void updateClearability() {
        // public versions cannot be dismissed
        mVetoButton.setVisibility(canViewBeDismissed() ? View.VISIBLE : View.GONE);
    }

    private boolean canViewBeDismissed() {
        return isClearable() && (!mShowingPublic || !mSensitiveHiddenInGeneral);
    }

    public void makeActionsVisibile() {
        setUserExpanded(true, true);
        if (isChildInGroup()) {
            mGroupManager.setGroupExpanded(mStatusBarNotification, true);
        }
        notifyHeightChanged(false);
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
        return mMaxExpandHeight;
    }

    public boolean areGutsExposed() {
        return (mGuts != null && mGuts.areGutsExposed());
    }

    @Override
    public boolean isContentExpandable() {
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.isContentExpandable();
    }

    @Override
    protected View getContentView() {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer;
        }
        return getShowingLayout();
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
        super.setActualHeight(height, notifyListeners);
        if (mGuts != null && mGuts.areGutsExposed()) {
            mGuts.setActualHeight(height);
            return;
        }
        int contentHeight = Math.max(getMinHeight(), height);
        mPrivateLayout.setContentHeight(contentHeight);
        mPublicLayout.setContentHeight(contentHeight);
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setActualHeight(height);
        }
        if (mGuts != null) {
            mGuts.setActualHeight(height);
        }
    }

    @Override
    public int getMaxContentHeight() {
        if (mIsSummaryWithChildren && !mShowingPublic) {
            return mChildrenContainer.getMaxContentHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMaxHeight();
    }

    @Override
    public int getMinHeight() {
        if (mIsHeadsUp && mHeadsUpManager.isTrackingHeadsUp()) {
                return getPinnedHeadsUpHeight(false /* atLeastMinHeight */);
        } else if (mIsSummaryWithChildren && !isGroupExpanded() && !mShowingPublic) {
            return mChildrenContainer.getMinHeight();
        } else if (mIsHeadsUp) {
            return mHeadsUpHeight;
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMinHeight();
    }

    @Override
    public int getCollapsedHeight() {
        if (mIsSummaryWithChildren && !mShowingPublic) {
            return mChildrenContainer.getCollapsedHeight();
        }
        return getMinHeight();
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        mPrivateLayout.setClipTopAmount(clipTopAmount);
        mPublicLayout.setClipTopAmount(clipTopAmount);
        if (mGuts != null) {
            mGuts.setClipTopAmount(clipTopAmount);
        }
    }

    public boolean isMaxExpandHeightInitialized() {
        return mMaxExpandHeight != 0;
    }

    public NotificationContentView getShowingLayout() {
        return mShowingPublic ? mPublicLayout : mPrivateLayout;
    }

    @Override
    public void setShowingLegacyBackground(boolean showing) {
        super.setShowingLegacyBackground(showing);
        mPrivateLayout.setShowingLegacyBackground(showing);
        mPublicLayout.setShowingLegacyBackground(showing);
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
            mShowNoBackground = isGroupExpanded() && !isGroupExpansionChanging() && !isUserLocked();
            mChildrenContainer.updateHeaderForExpansion(mShowNoBackground);
            List<ExpandableNotificationRow> children = mChildrenContainer.getNotificationChildren();
            for (int i = 0; i < children.size(); i++) {
                children.get(i).updateBackgroundForGroupState();
            }
        } else if (isChildInGroup()) {
            final int childColor = getShowingLayout().getBackgroundColorForExpansionState();
            // Only show a background if the group is expanded OR if it is expanding / collapsing
            // and has a custom background color
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
                return mChildrenContainer.getGroupExpandFraction();
            }
        }
        return 0.0f;
    }

    @Override
    protected boolean disallowSingleClick(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        NotificationHeaderView header = getVisibleNotificationHeader();
        if (header != null) {
            return header.isInTouchRect(x - getTranslation(), y);
        }
        return super.disallowSingleClick(event);
    }

    private void logExpansionEvent(boolean userAction, boolean wasExpanded) {
        boolean nowExpanded = isExpanded();
        if (mIsSummaryWithChildren) {
            nowExpanded = mGroupManager.isGroupExpanded(mStatusBarNotification);
        }
        if (wasExpanded != nowExpanded && mLogger != null) {
            mLogger.logNotificationExpansion(mLoggingKey, userAction, nowExpanded) ;
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        if (canViewBeDismissed()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_DISMISS:
                NotificationStackScrollLayout.performDismiss(this, mGroupManager,
                        true /* fromAccessibility */);
                return true;
        }
        return false;
    }

    public boolean shouldRefocusOnDismiss() {
        return mRefocusOnDismiss || isAccessibilityFocused();
    }

    public interface OnExpandClickListener {
        void onExpandClicked(NotificationData.Entry clickedEntry, boolean nowExpanded);
    }
}
