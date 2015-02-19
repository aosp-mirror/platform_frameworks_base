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
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import com.android.systemui.R;

public class ExpandableNotificationRow extends ActivatableNotificationView {
    private int mRowMinHeight;
    private int mRowMaxHeight;

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
    private boolean mShowingPublicForIntrinsicHeight;

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
    private View mVetoButton;
    private boolean mClearable;
    private ExpansionLogger mLogger;
    private String mLoggingKey;
    private boolean mWasReset;
    private NotificationGuts mGuts;

    private StatusBarNotification mStatusBarNotification;
    private boolean mIsHeadsUp;

    public void setIconAnimationRunning(boolean running) {
        setIconAnimationRunning(running, mPublicLayout);
        setIconAnimationRunning(running, mPrivateLayout);
    }

    private void setIconAnimationRunning(boolean running, NotificationContentView layout) {
        if (layout != null) {
            View contractedChild = layout.getContractedChild();
            View expandedChild = layout.getExpandedChild();
            setIconAnimationRunningForChild(running, contractedChild);
            setIconAnimationRunningForChild(running, expandedChild);
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
    }

    public StatusBarNotification getStatusBarNotification() {
        return mStatusBarNotification;
    }

    public void setHeadsUp(boolean isHeadsUp) {
        mIsHeadsUp = isHeadsUp;
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
        mRowMaxHeight = 0;
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
        ViewStub gutsStub = (ViewStub) findViewById(R.id.notification_guts_stub);
        gutsStub.setOnInflateListener(new ViewStub.OnInflateListener() {
            @Override
            public void onInflate(ViewStub stub, View inflated) {
                mGuts = (NotificationGuts) inflated;
                mGuts.setClipTopAmount(getClipTopAmount());
                mGuts.setActualHeight(getActualHeight());
            }
        });
        mVetoButton = findViewById(R.id.veto);
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEvent(child, event)) {
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
        mRowMaxHeight = rowMaxHeight;
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
            notifyHeightChanged();
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
                notifyHeightChanged();
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
        if (!inExpansionState) {
            // not expanded, so we return the collapsed size
            return mRowMinHeight;
        }

        return mShowingPublicForIntrinsicHeight ? mRowMinHeight : getMaxExpandHeight();
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
                && (!hasUserChangedExpansion() && isSystemExpanded() || isUserExpanded());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        boolean updateExpandHeight = mMaxExpandHeight == 0 && !mWasReset;
        updateMaxExpandHeight();
        if (updateExpandHeight) {
            applyExpansionToLayout();
        }
        mWasReset = false;
    }

    private void updateMaxExpandHeight() {
        int intrinsicBefore = getIntrinsicHeight();
        mMaxExpandHeight = mPrivateLayout.getMaxHeight();
        if (intrinsicBefore != getIntrinsicHeight()) {
            notifyHeightChanged();
        }
    }

    public void setSensitive(boolean sensitive) {
        mSensitive = sensitive;
    }

    public void setHideSensitiveForIntrinsicHeight(boolean hideSensitive) {
        mShowingPublicForIntrinsicHeight = mSensitive && hideSensitive;
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

    public int getMaxExpandHeight() {
        return mShowingPublicForIntrinsicHeight ? mRowMinHeight : mMaxExpandHeight;
    }

    @Override
    public boolean isContentExpandable() {
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.isContentExpandable();
    }

    @Override
    public void setActualHeight(int height, boolean notifyListeners) {
        mPrivateLayout.setActualHeight(height);
        mPublicLayout.setActualHeight(height);
        if (mGuts != null) {
            mGuts.setActualHeight(height);
        }
        invalidate();
        super.setActualHeight(height, notifyListeners);
    }

    @Override
    public int getMaxHeight() {
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
