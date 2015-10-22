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

import android.content.Context;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.LinearInterpolator;
import android.widget.Chronometer;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.statusbar.notification.NotificationHeaderView;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.stack.NotificationChildrenContainer;
import com.android.systemui.statusbar.stack.StackScrollState;
import com.android.systemui.statusbar.stack.StackStateAnimator;
import com.android.systemui.statusbar.stack.StackViewState;

import java.util.List;

public class ExpandableNotificationRow extends ActivatableNotificationView {

    private static final int DEFAULT_DIVIDER_ALPHA = 0x29;
    private static final int COLORED_DIVIDER_ALPHA = 0x7B;
    private final LinearInterpolator mLinearInterpolator = new LinearInterpolator();
    private int mRowMinHeight;

    /** Does this row contain layouts that can adapt to row expansion */
    private boolean mExpandable;
    /** Has the user actively changed the expansion state of this row */
    private boolean mHasUserChangedExpansion;
    /** If {@link #mHasUserChangedExpansion}, has the user expanded this row */
    private boolean mUserExpanded;
    /** Is the user touching this row */
    private boolean mUserLocked;
    /** Are we showing the "public" version */
    private boolean mShowingPublic;
    private boolean mSensitive;
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

    private NotificationContentView mPublicLayout;
    private NotificationContentView mPrivateLayout;
    private int mMaxExpandHeight;
    private int mHeadsUpHeight;
    private View mVetoButton;
    private boolean mClearable;
    private ExpansionLogger mLogger;
    private String mLoggingKey;
    private boolean mWasReset;
    private NotificationGuts mGuts;
    private StatusBarNotification mStatusBarNotification;
    private boolean mIsHeadsUp;
    private boolean mLastChronometerRunning = true;
    private NotificationHeaderView mNotificationHeader;
    private ViewStub mNotificationHeaderStub;
    private ViewStub mChildrenContainerStub;
    private NotificationGroupManager mGroupManager;
    private boolean mChildrenExpanded;
    private boolean mIsSummaryWithChildren;
    private NotificationChildrenContainer mChildrenContainer;
    private ViewStub mGutsStub;
    private boolean mHasNotificationHeader;
    private boolean mIsSystemChildExpanded;
    private boolean mIsPinned;
    private FalsingManager mFalsingManager;

    private boolean mJustClicked;
    private NotificationData.Entry mEntry;
    private boolean mShowNoBackground;
    private boolean mChildInGroup;

    public NotificationContentView getPrivateLayout() {
        return mPrivateLayout;
    }

    public NotificationContentView getPublicLayout() {
        return mPublicLayout;
    }

    public void setIconAnimationRunning(boolean running) {
        setIconAnimationRunning(running, mPublicLayout);
        setIconAnimationRunning(running, mPrivateLayout);
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

    private void setStatusBarNotification(StatusBarNotification statusBarNotification) {
        mStatusBarNotification = statusBarNotification;
        mPrivateLayout.setStatusBarNotification(statusBarNotification);
        updateVetoButton();
        onChildrenCountChanged();
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
        if (intrinsicBefore != getIntrinsicHeight()) {
            notifyHeightChanged(false  /* needsAnimation */);
        }
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
        mPrivateLayout.setGroupManager(groupManager);
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
        return mChildInGroup;
    }

    /**
     * @param isChildInGroup Is this notification now in a group
     * @param parent the new parent notification
     */
    public void setIsChildInGroup(boolean isChildInGroup, ExpandableNotificationRow parent) {
        mChildInGroup = BaseStatusBar.ENABLE_CHILD_NOTIFICATIONS && isChildInGroup;
        mShowNoBackground = mChildInGroup && hasSameBgColor(parent);
        mPrivateLayout.setIsChildInGroup(mShowNoBackground);
        updateBackground();
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
            StackStateAnimator stateAnimator, boolean withDelays, long delay, long duration) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.startAnimationToState(finalState, stateAnimator, withDelays, delay,
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
        mIsPinned = pinned;
        setChronometerRunning(mLastChronometerRunning);
    }

    public boolean isPinned() {
        return mIsPinned;
    }

    public int getHeadsUpHeight() {
        return mHeadsUpHeight;
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

    public void setEntry(NotificationData.Entry entry) {
        mEntry = entry;
        setStatusBarNotification(entry.notification);
    }

    public CharSequence getSubText() {
        Notification notification = mStatusBarNotification.getNotification();
        CharSequence subText = notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        if (subText == null) {
            subText = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        }
        return subText;
    }

    public void setContentSubTextVisible(boolean visible) {
        mPrivateLayout.setSubTextVisible(visible);
    }

    public interface ExpansionLogger {
        public void logNotificationExpansion(String key, boolean userAction, boolean expanded);
    }

    public ExpandableNotificationRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFalsingManager = FalsingManager.getInstance(context);
    }

    /**
     * Resets this view so it can be re-used for an updated notification.
     */
    @Override
    public void reset() {
        super.reset();
        mRowMinHeight = 0;
        final boolean wasExpanded = isExpanded();
        mMaxViewHeight = 0;
        mExpandable = false;
        mHasUserChangedExpansion = false;
        mUserLocked = false;
        mShowingPublic = false;
        mSensitive = false;
        mShowingPublicInitialized = false;
        mIsSystemExpanded = false;
        mOnKeyguard = false;
        mPublicLayout.reset(mIsHeadsUp);
        mPrivateLayout.reset(mIsHeadsUp);
        resetHeight();
        logExpansionEvent(false, wasExpanded);
    }

    public void resetHeight() {
        if (mIsHeadsUp) {
            resetActualHeight();
        }
        mMaxExpandHeight = 0;
        mHeadsUpHeight = 0;
        mWasReset = true;
        onHeightReset();
        requestLayout();
    }

    @Override
    protected boolean filterMotionEvent(MotionEvent event) {
        return mIsHeadsUp || super.filterMotionEvent(event);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPublicLayout = (NotificationContentView) findViewById(R.id.expandedPublic);
        mPrivateLayout = (NotificationContentView) findViewById(R.id.expanded);
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
        mNotificationHeaderStub = (ViewStub) findViewById(R.id.notification_header_stub);
        mNotificationHeaderStub.setOnInflateListener(new ViewStub.OnInflateListener() {
            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mNotificationHeader = (NotificationHeaderView) inflated;
                mNotificationHeader.setGroupManager(mGroupManager);
                mNotificationHeader.bind(mEntry);
            }
        });
        mChildrenContainerStub = (ViewStub) findViewById(R.id.child_container_stub);
        mChildrenContainerStub.setOnInflateListener(new ViewStub.OnInflateListener() {

            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mChildrenContainer = (NotificationChildrenContainer) inflated;
            }
        });
        mVetoButton = findViewById(R.id.veto);
    }

    public void inflateGuts() {
        if (mGuts == null) {
            mGutsStub.inflate();
        }
    }

    private void updateChildrenVisibility(boolean animated) {
        if (mChildrenContainer == null) {
            return;
        }
        mChildrenContainer.setVisibility(mIsSummaryWithChildren ? VISIBLE : INVISIBLE);
        mPrivateLayout.setVisibility(!mIsSummaryWithChildren ? VISIBLE : INVISIBLE);
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
    }

    public void setHeightRange(int rowMinHeight, int rowMaxHeight) {
        mRowMinHeight = rowMinHeight;
        mMaxViewHeight = rowMaxHeight;
    }

    public boolean isExpandable() {
        return mExpandable;
    }

    public void setExpandable(boolean expandable) {
        mExpandable = expandable;
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
        mFalsingManager.setNotificationExpanded();
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
        return mUserLocked;
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
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
            if (mChildrenContainer != null) {
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
                notifyHeightChanged(false  /* needsAnimation */);
            }
        }
    }

    /**
     * @return Can the underlying notification be cleared?
     */
    public boolean isClearable() {
        return mStatusBarNotification != null && mStatusBarNotification.isClearable();
    }

    /**
     * Apply an expansion state to the layout.
     */
    public void applyExpansionToLayout() {
        boolean expand = isExpanded();
        if (expand && mExpandable) {
            setActualHeight(mMaxExpandHeight);
        } else {
            setActualHeight(mRowMinHeight);
        }
    }

    @Override
    public int getIntrinsicHeight() {
        if (isUserLocked()) {
            return getActualHeight();
        }
        boolean inExpansionState = isExpanded();
        if (mSensitive && mHideSensitiveForIntrinsicHeight) {
            return mRowMinHeight;
        } else if (mIsSummaryWithChildren && !mOnKeyguard) {
            return mChildrenContainer.getIntrinsicHeight()
                    + mNotificationHeader.getHeight();
        } else if (mIsHeadsUp) {
            if (inExpansionState) {
                return Math.max(mMaxExpandHeight, mHeadsUpHeight);
            } else {
                return Math.max(mRowMinHeight, mHeadsUpHeight);
            }
        } else if (!inExpansionState || (mChildInGroup && !isGroupExpanded())) {
            return getMinHeight();
        } else {
            return getMaxExpandHeight();
        }
    }

    private boolean isGroupExpanded() {
        return mGroupManager.isGroupExpanded(mStatusBarNotification);
    }

    /**
     * @return whether this view has a header on the top of the content
     */
    private boolean hasNotificationHeader() {
        return mIsSummaryWithChildren;
    }

    private void onChildrenCountChanged() {
        mIsSummaryWithChildren  = BaseStatusBar.ENABLE_CHILD_NOTIFICATIONS
                && mGroupManager.hasGroupChildren(mStatusBarNotification);
        if (mIsSummaryWithChildren && mChildrenContainer == null) {
            mChildrenContainerStub.inflate();
        }
        updateNotificationHeader();
        updateChildrenVisibility(true);
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
        return !mOnKeyguard
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
        boolean updateExpandHeight = mMaxExpandHeight == 0 && !mWasReset;
        updateMaxHeights();
        if (updateExpandHeight) {
            applyExpansionToLayout();
        }
        mWasReset = false;
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

    public void setSensitive(boolean sensitive) {
        mSensitive = sensitive;
    }

    public void setHideSensitiveForIntrinsicHeight(boolean hideSensitive) {
        mHideSensitiveForIntrinsicHeight = hideSensitive;
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
            mPublicLayout.setAlpha(1f);
            mPrivateLayout.setAlpha(1f);
            mPublicLayout.setVisibility(mShowingPublic ? View.VISIBLE : View.INVISIBLE);
            mPrivateLayout.setVisibility(!mShowingPublic && !mIsSummaryWithChildren ? View.VISIBLE
                    : View.INVISIBLE);
        } else {
            animateShowingPublic(delay, duration);
        }

        updateVetoButton();
        mShowingPublicInitialized = true;
    }

    private void animateShowingPublic(long delay, long duration) {
        final View source = mShowingPublic ? mPrivateLayout : mPublicLayout;
        View target = mShowingPublic ? mPublicLayout : mPrivateLayout;
        source.setVisibility(View.VISIBLE);
        target.setVisibility(View.VISIBLE);
        target.setAlpha(0f);
        source.animate().cancel();
        target.animate().cancel();
        source.animate()
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(duration)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        source.setVisibility(View.INVISIBLE);
                    }
                });
        target.animate()
                .alpha(1f)
                .setStartDelay(delay)
                .setDuration(duration);
    }

    private void updateVetoButton() {
        // public versions cannot be dismissed
        mVetoButton.setVisibility(isClearable() && !mShowingPublic ? View.VISIBLE : View.GONE);
    }

    public void setChildrenExpanded(boolean expanded, boolean animate) {
        mChildrenExpanded = expanded;
        if (mChildrenContainer != null) {
            mChildrenContainer.setChildrenExpanded(expanded);
        }
    }

    public void updateNotificationHeader() {
        boolean hasHeader = hasNotificationHeader();
        if (hasHeader != mHasNotificationHeader) {
            if (hasHeader) {
                if (mNotificationHeader == null) {
                    mNotificationHeaderStub.inflate();
                }
                mNotificationHeader.setVisibility(View.VISIBLE);
            } else if (mNotificationHeader != null) {
                mNotificationHeader.setVisibility(View.GONE);
            }
            notifyHeightChanged(true  /* needsAnimation */);
        }
        if (hasHeader) {
            mNotificationHeader.bind(mEntry);
        }
        mHasNotificationHeader = hasHeader;
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

    @Override
    public boolean isContentExpandable() {
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.isContentExpandable();
    }

    @Override
    protected View getContentView() {
        return getShowingLayout();
    }

    @Override
    public void setActualHeight(int height, boolean notifyListeners) {
        super.setActualHeight(height, notifyListeners);
        int contentHeight = Math.max(getMinHeight(), height);
        mPrivateLayout.setContentHeight(contentHeight);
        mPublicLayout.setContentHeight(contentHeight);
        if (mGuts != null) {
            mGuts.setActualHeight(height);
        }
        invalidate();
    }

    @Override
    public int getMaxContentHeight() {
        if (mIsSummaryWithChildren && !mShowingPublic) {
            return mChildrenContainer.getMaxContentHeight()
                    + mNotificationHeader.getHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMaxHeight();
    }

    @Override
    public int getMinHeight() {
        if (mIsSummaryWithChildren && !mOnKeyguard) {
            return mChildrenContainer.getMinHeight()
                    + mNotificationHeader.getHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMinHeight();
    }

    @Override
    protected boolean shouldLimitViewHeight() {
        return !mIsSummaryWithChildren;
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

    public void notifyContentUpdated() {
        mPublicLayout.notifyContentUpdated();
        mPrivateLayout.notifyContentUpdated();
    }

    public boolean isMaxExpandHeightInitialized() {
        return mMaxExpandHeight != 0;
    }

    private NotificationContentView getShowingLayout() {
        return mShowingPublic ? mPublicLayout : mPrivateLayout;
    }

    @Override
    public void setShowingLegacyBackground(boolean showing) {
        super.setShowingLegacyBackground(showing);
        mPrivateLayout.setShowingLegacyBackground(showing);
        mPublicLayout.setShowingLegacyBackground(showing);
    }

    public void setExpansionLogger(ExpansionLogger logger, String key) {
        mLogger = logger;
        mLoggingKey = key;
    }

    private void logExpansionEvent(boolean userAction, boolean wasExpanded) {
        final boolean nowExpanded = isExpanded();
        if (wasExpanded != nowExpanded && mLogger != null) {
            mLogger.logNotificationExpansion(mLoggingKey, userAction, nowExpanded) ;
        }
    }
}
