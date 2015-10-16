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
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
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
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
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
     * Whether the notification expansion is disabled. This is the case on Keyguard.
     */
    private boolean mExpansionDisabled;

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
    private View mExpandButton;
    private View mExpandButtonDivider;
    private ViewStub mExpandButtonStub;
    private ViewStub mChildrenContainerStub;
    private NotificationGroupManager mGroupManager;
    private View mExpandButtonContainer;
    private boolean mChildrenExpanded;
    private NotificationChildrenContainer mChildrenContainer;
    private ValueAnimator mChildExpandAnimator;
    private float mChildrenExpandProgress;
    private float mExpandButtonStart;
    private ViewStub mGutsStub;
    private boolean mHasExpandAction;
    private boolean mIsSystemChildExpanded;
    private boolean mIsPinned;
    private OnClickListener mExpandClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            mGroupManager.setGroupExpanded(mStatusBarNotification,
                    !mChildrenExpanded);
        }
    };

    private boolean mJustClicked;

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

    public void setStatusBarNotification(StatusBarNotification statusBarNotification) {
        mStatusBarNotification = statusBarNotification;
        updateVetoButton();
        updateExpandButton();
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
    }

    public void removeChildNotification(ExpandableNotificationRow row) {
        if (mChildrenContainer != null) {
            mChildrenContainer.removeNotification(row);
        }
    }

    @Override
    public boolean areChildrenExpanded() {
        return mChildrenExpanded;
    }

    public List<ExpandableNotificationRow> getNotificationChildren() {
        return mChildrenContainer == null ? null : mChildrenContainer.getNotificationChildren();
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
        if (mChildrenExpanded) {
            StackViewState parentState = resultState.getViewStateForView(this);
            mChildrenContainer.getState(resultState, parentState);
        }
    }

    public void applyChildrenState(StackScrollState state) {
        if (mChildrenExpanded) {
            mChildrenContainer.applyState(state);
        }
    }

    public void prepareExpansionChanged(StackScrollState state) {
        if (mChildrenExpanded) {
            mChildrenContainer.prepareExpansionChanged(state);
        }
    }

    public void startChildAnimation(StackScrollState finalState,
            StackStateAnimator stateAnimator, boolean withDelays, long delay, long duration) {
        if (mChildrenExpanded) {
            mChildrenContainer.startAnimationToState(finalState, stateAnimator, withDelays, delay,
                    duration);
        }
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        if (!mChildrenExpanded) {
            return this;
        } else {
            ExpandableNotificationRow view = mChildrenContainer.getViewAtPosition(y);
            return view == null ? this : view;
        }
    }

    public NotificationGuts getGuts() {
        return mGuts;
    }

    protected int calculateContentHeightFromActualHeight(int actualHeight) {
        int realActualHeight = actualHeight;
        if (hasBottomDecor()) {
            realActualHeight -= getBottomDecorHeight();
        }
        realActualHeight = Math.max(getMinHeight(), realActualHeight);
        return realActualHeight;
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

    public interface ExpansionLogger {
        public void logNotificationExpansion(String key, boolean userAction, boolean expanded);
    }

    public ExpandableNotificationRow(Context context, AttributeSet attrs) {
        super(context, attrs);
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
        mExpansionDisabled = false;
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
        mExpandButtonStub = (ViewStub) findViewById(R.id.more_button_stub);
        mExpandButtonStub.setOnInflateListener(new ViewStub.OnInflateListener() {

            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mExpandButtonContainer = inflated;
                mExpandButton = inflated.findViewById(R.id.notification_expand_button);
                mExpandButtonDivider = inflated.findViewById(R.id.notification_expand_divider);
                mExpandButtonContainer.setOnClickListener(mExpandClickListener);
            }
        });
        mChildrenContainerStub = (ViewStub) findViewById(R.id.child_container_stub);
        mChildrenContainerStub.setOnInflateListener(new ViewStub.OnInflateListener() {

            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mChildrenContainer = (NotificationChildrenContainer) inflated;
                mChildrenContainer.setCollapseClickListener(mExpandClickListener);
                updateChildrenVisibility(false);
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
        if (mChildExpandAnimator != null) {
            mChildExpandAnimator.cancel();
        }
        float targetProgress = mChildrenExpanded ? 1.0f : 0.0f;
        if (animated) {
            if (mChildrenExpanded) {
                mChildrenContainer.setVisibility(VISIBLE);
            }
            mExpandButtonStart = mExpandButtonContainer.getTranslationY();
            mChildExpandAnimator = ValueAnimator.ofFloat(mChildrenExpandProgress, targetProgress);
            mChildExpandAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    setChildrenExpandProgress((float) animation.getAnimatedValue());
                }
            });
            mChildExpandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mChildExpandAnimator = null;
                    if (!mChildrenExpanded) {
                        mChildrenContainer.setVisibility(INVISIBLE);
                    }
                }
            });
            mChildExpandAnimator.setInterpolator(mLinearInterpolator);
            mChildExpandAnimator.setDuration(
                    StackStateAnimator.ANIMATION_DURATION_EXPAND_CLICKED);
            mChildExpandAnimator.start();
        } else {
            setChildrenExpandProgress(targetProgress);
            mChildrenContainer.setVisibility(mChildrenExpanded ? VISIBLE : INVISIBLE);
        }
    }

    private void setChildrenExpandProgress(float progress) {
        mChildrenExpandProgress = progress;
        updateExpandButtonAppearance();
        NotificationContentView showingLayout = getShowingLayout();
        float alpha = 1.0f - mChildrenExpandProgress;
        alpha = PhoneStatusBar.ALPHA_OUT.getInterpolation(alpha);
        showingLayout.setAlpha(alpha);
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
        }
    }

    /**
     * @param expansionDisabled whether to prevent notification expansion
     */
    public void setExpansionDisabled(boolean expansionDisabled) {
        if (expansionDisabled != mExpansionDisabled) {
            final boolean wasExpanded = isExpanded();
            mExpansionDisabled = expansionDisabled;
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
            setContentHeight(mMaxExpandHeight);
        } else {
            setContentHeight(mRowMinHeight);
        }
    }

    @Override
    public int getIntrinsicHeight() {
        if (isUserLocked()) {
            return getActualHeight();
        }
        boolean inExpansionState = isExpanded();
        int maxContentHeight;
        if (mSensitive && mHideSensitiveForIntrinsicHeight) {
            return mRowMinHeight;
        } else if (mIsHeadsUp) {
            if (inExpansionState) {
                maxContentHeight = Math.max(mMaxExpandHeight, mHeadsUpHeight);
            } else {
                maxContentHeight = Math.max(mRowMinHeight, mHeadsUpHeight);
            }
        } else if ((!inExpansionState && !mChildrenExpanded)) {
            maxContentHeight = mRowMinHeight;
        } else if (mChildrenExpanded) {
            maxContentHeight = mChildrenContainer.getIntrinsicHeight();
        } else {
            maxContentHeight = getMaxExpandHeight();
        }
        return maxContentHeight + getBottomDecorHeight();
    }

    @Override
    protected boolean hasBottomDecor() {
        return BaseStatusBar.ENABLE_CHILD_NOTIFICATIONS
                && !mIsHeadsUp && mGroupManager.hasGroupChildren(mStatusBarNotification);
    }

    @Override
    protected boolean canHaveBottomDecor() {
        return BaseStatusBar.ENABLE_CHILD_NOTIFICATIONS && !mIsHeadsUp;
    }

    /**
     * Check whether the view state is currently expanded. This is given by the system in {@link
     * #setSystemExpanded(boolean)} and can be overridden by user expansion or
     * collapsing in {@link #setUserExpanded(boolean)}. Note that the visual appearance of this
     * view can differ from this state, if layout params are modified from outside.
     *
     * @return whether the view state is currently expanded.
     */
    private boolean isExpanded() {
        return !mExpansionDisabled
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

    @Override
    protected boolean isChildInvisible(View child) {

        // We don't want to layout the ChildrenContainer if this is a heads-up view, otherwise the
        // view will get too high and the shadows will be off.
        boolean isInvisibleChildContainer = child == mChildrenContainer && mIsHeadsUp;
        return super.isChildInvisible(child) || isInvisibleChildContainer;
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
            mPrivateLayout.setVisibility(mShowingPublic ? View.INVISIBLE : View.VISIBLE);
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
        updateChildrenVisibility(animate);
    }

    public void updateExpandButton() {
        boolean hasExpand = hasBottomDecor();
        if (hasExpand != mHasExpandAction) {
            if (hasExpand) {
                if (mExpandButtonContainer == null) {
                    mExpandButtonStub.inflate();
                }
                mExpandButtonContainer.setVisibility(View.VISIBLE);
                updateExpandButtonAppearance();
                updateExpandButtonColor();
            } else if (mExpandButtonContainer != null) {
                mExpandButtonContainer.setVisibility(View.GONE);
            }
            notifyHeightChanged(true  /* needsAnimation */);
        }
        mHasExpandAction = hasExpand;
    }

    private void updateExpandButtonAppearance() {
        if (mExpandButtonContainer == null) {
            return;
        }
        float expandButtonAlpha = 0.0f;
        float expandButtonTranslation = 0.0f;
        float containerTranslation = 0.0f;
        int minHeight = getMinHeight();
        if (!mChildrenExpanded || mChildExpandAnimator != null) {
            int expandActionHeight = getBottomDecorHeight();
            int translationY = getActualHeight() - expandActionHeight;
            if (translationY > minHeight) {
                containerTranslation = translationY;
                expandButtonAlpha = 1.0f;
                expandButtonTranslation = 0.0f;
            } else {
                containerTranslation = minHeight;
                float progress = expandActionHeight != 0
                        ? (minHeight - translationY) / (float) expandActionHeight
                        : 1.0f;
                expandButtonTranslation = -progress * expandActionHeight * 0.7f;
                float alphaProgress = Math.min(progress / 0.7f, 1.0f);
                alphaProgress = PhoneStatusBar.ALPHA_OUT.getInterpolation(alphaProgress);
                expandButtonAlpha = 1.0f - alphaProgress;
            }
        }
        if (mChildExpandAnimator != null || mChildrenExpanded) {
            expandButtonAlpha = (1.0f - mChildrenExpandProgress)
                    * expandButtonAlpha;
            expandButtonTranslation = (1.0f - mChildrenExpandProgress)
                    * expandButtonTranslation;
            float newTranslation = -getBottomDecorHeight();

            // We don't want to take the actual height of the view as this is already
            // interpolated by a custom interpolator leading to a confusing animation. We want
            // to have a stable end value to interpolate in between
            float collapsedHeight = !mChildrenExpanded
                    ? Math.max(StackStateAnimator.getFinalActualHeight(this)
                            - getBottomDecorHeight(), minHeight)
                    : mExpandButtonStart;
            float translationProgress = mFastOutSlowInInterpolator.getInterpolation(
                    mChildrenExpandProgress);
            containerTranslation = (1.0f - translationProgress) * collapsedHeight
                    + translationProgress * newTranslation;
        }
        mExpandButton.setAlpha(expandButtonAlpha);
        mExpandButtonDivider.setAlpha(expandButtonAlpha);
        mExpandButton.setTranslationY(expandButtonTranslation);
        mExpandButtonContainer.setTranslationY(containerTranslation);
        NotificationContentView showingLayout = getShowingLayout();
        float layoutTranslation =
                mExpandButtonContainer.getTranslationY() - showingLayout.getContentHeight();
        layoutTranslation = Math.min(layoutTranslation, 0);
        if (!mChildrenExpanded && mChildExpandAnimator == null) {
            // Needed for the DragDownHelper in order not to jump there, as the position
            // can be negative for a short time.
            layoutTranslation = 0;
        }
        showingLayout.setTranslationY(layoutTranslation);
        if (mChildrenContainer != null) {
            mChildrenContainer.setTranslationY(
                    mExpandButtonContainer.getTranslationY() + getBottomDecorHeight());
        }
    }

    private void updateExpandButtonColor() {
        // TODO: This needs some more baking, currently only the divider is colored according to
        // the tint, but legacy black doesn't work yet perfectly for the button etc.
        int color = getRippleColor();
        if (color == mNormalRippleColor) {
            color = 0;
        }
        if (mExpandButtonDivider != null) {
            applyTint(mExpandButtonDivider, color);
        }
        if (mChildrenContainer != null) {
            mChildrenContainer.setTintColor(color);
        }
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
        int contentHeight = calculateContentHeightFromActualHeight(height);
        mPrivateLayout.setContentHeight(contentHeight);
        mPublicLayout.setContentHeight(contentHeight);
        if (mGuts != null) {
            mGuts.setActualHeight(height);
        }
        invalidate();
        updateExpandButtonAppearance();
    }

    @Override
    public int getMaxContentHeight() {
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMaxHeight();
    }

    @Override
    public int getMinHeight() {
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMinHeight();
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
