/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.systemui.Flags.notificationColorUpdateLogger;

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.systemui.Dumpable;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.Roundable;
import com.android.systemui.statusbar.notification.RoundableState;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.util.Compile;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * An abstract view for expandable views.
 */
public abstract class ExpandableView extends FrameLayout implements Dumpable, Roundable {
    private static final String TAG = "ExpandableView";
    /** whether the dump() for this class should include verbose details */
    protected static final boolean DUMP_VERBOSE = Compile.IS_DEBUG
            && (Log.isLoggable(TAG, Log.VERBOSE) || notificationColorUpdateLogger());

    private RoundableState mRoundableState = null;
    protected OnHeightChangedListener mOnHeightChangedListener;
    private int mActualHeight;
    protected int mClipTopAmount;
    protected int mClipBottomAmount;
    protected int mMinimumHeightForClipping = 0;
    protected float mExtraWidthForClipping = 0;
    private ArrayList<View> mMatchParentViews = new ArrayList<View>();
    private static Rect mClipRect = new Rect();
    private boolean mWillBeGone;
    private boolean mClipToActualHeight = true;
    private boolean mChangingPosition = false;
    private ViewGroup mTransientContainer;

    // Needs to be added as transient view when removed from parent, because it's in animation
    private boolean mInRemovalAnimation;
    private boolean mInShelf;
    private boolean mTransformingInShelf;
    protected float mContentTransformationAmount;
    protected boolean mIsLastChild;
    protected int mContentShift;
    @NonNull private final ExpandableViewState mViewState;
    private float mContentTranslation;
    protected boolean mLastInSection;
    protected boolean mFirstInSection;

    public ExpandableView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mViewState = createExpandableViewState();
        initDimens();
    }

    @Override
    public RoundableState getRoundableState() {
        if (mRoundableState == null) {
            mRoundableState = new RoundableState(this, this, 0f);
        }
        return mRoundableState;
    }

    @Override
    public int getClipHeight() {
        int clipHeight = Math.max(mActualHeight - mClipTopAmount - mClipBottomAmount, 0);
        return Math.max(clipHeight, mMinimumHeightForClipping);
    }

    private void initDimens() {
        mContentShift = getResources().getDimensionPixelSize(
                R.dimen.shelf_transform_content_shift);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initDimens();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int givenHeight = MeasureSpec.getSize(heightMeasureSpec);
        final int viewHorizontalPadding = getPaddingStart() + getPaddingEnd();

        // Max height is as large as possible, unless otherwise requested
        int ownMaxHeight = Integer.MAX_VALUE;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode != MeasureSpec.UNSPECIFIED && givenHeight != 0) {
            // Set our max height to what was requested from the parent
            ownMaxHeight = Math.min(givenHeight, ownMaxHeight);
        }

        // height of the largest child
        int maxChildHeight = 0;
        int atMostOwnMaxHeightSpec = MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.AT_MOST);
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childHeightSpec = atMostOwnMaxHeightSpec;
            ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
            if (layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                if (layoutParams.height >= 0) {
                    // If an actual height is set, cap it to the max height
                    childHeightSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(layoutParams.height, ownMaxHeight),
                            MeasureSpec.EXACTLY);
                }
                child.measure(getChildMeasureSpec(
                        widthMeasureSpec, viewHorizontalPadding, layoutParams.width),
                        childHeightSpec);
                int childHeight = child.getMeasuredHeight();
                maxChildHeight = Math.max(maxChildHeight, childHeight);
            } else {
                mMatchParentViews.add(child);
            }
        }

        // Set our own height to the given height, or the height of the largest child
        int ownHeight = heightMode == MeasureSpec.EXACTLY
                ? givenHeight
                : Math.min(ownMaxHeight, maxChildHeight);
        int exactlyOwnHeightSpec = MeasureSpec.makeMeasureSpec(ownHeight, MeasureSpec.EXACTLY);

        // Now that we know our own height, measure the children that are MATCH_PARENT
        for (View child : mMatchParentViews) {
            child.measure(getChildMeasureSpec(
                    widthMeasureSpec, viewHorizontalPadding, child.getLayoutParams().width),
                    exactlyOwnHeightSpec);
        }
        mMatchParentViews.clear();

        // Finish up
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, ownHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateClipping();
    }

    @Override
    public boolean pointInView(float localX, float localY, float slop) {
        float top = Math.max(0, mClipTopAmount);
        float bottom = mActualHeight;
        return localX >= -slop && localY >= top - slop && localX < ((mRight - mLeft) + slop) &&
                localY < (bottom + slop);
    }

    /**
     * @return if this view needs to be clipped to the shelf
     */
    public boolean needsClippingToShelf() {
        return true;
    }


    public boolean isPinned() {
        return false;
    }

    public boolean isHeadsUpAnimatingAway() {
        return false;
    }

    /**
     * Sets the actual height of this notification. This is different than the laid out
     * {@link View#getHeight()}, as we want to avoid layouting during scrolling and expanding.
     *
     * @param actualHeight The height of this notification.
     * @param notifyListeners Whether the listener should be informed about the change.
     */
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        if (mActualHeight != actualHeight) {
            mActualHeight = actualHeight;
            updateClipping();
            if (notifyListeners) {
                notifyHeightChanged(false  /* needsAnimation */);
            }
        }
    }

    public void setActualHeight(int actualHeight) {
        setActualHeight(actualHeight, true /* notifyListeners */);
    }

    /**
     * See {@link #setActualHeight}.
     *
     * @return The current actual height of this notification.
     */
    public int getActualHeight() {
        return mActualHeight;
    }

    public boolean isExpandAnimationRunning() {
        return false;
    }

    /**
     * @return The maximum height of this notification.
     */
    public int getMaxContentHeight() {
        return getHeight();
    }

    /**
     * @return The minimum content height of this notification. This also respects the temporary
     * states of the view.
     */
    public int getMinHeight() {
        return getMinHeight(false /* ignoreTemporaryStates */);
    }

    /**
     * Get the minimum height of this view.
     *
     * @param ignoreTemporaryStates should temporary states be ignored like the guts or heads-up.
     *
     * @return The minimum height that this view needs.
     */
    public int getMinHeight(boolean ignoreTemporaryStates) {
        return getHeight();
    }

    /**
     * @return The collapsed height of this view. Note that this might be different
     * than {@link #getMinHeight()} because some elements like groups may have different sizes when
     * they are system expanded.
     */
    public int getCollapsedHeight() {
        return getHeight();
    }

    public boolean isRemoved() {
        return false;
    }

    /**
     * See {@link #setHideSensitive}. This is a variant which notifies this view in advance about
     * the upcoming state of hiding sensitive notifications. It gets called at the very beginning
     * of a stack scroller update such that the updated intrinsic height (which is dependent on
     * whether private or public layout is showing) gets taken into account into all layout
     * calculations.
     */
    public void setHideSensitiveForIntrinsicHeight(boolean hideSensitive) {
    }

    /**
     * Sets whether the notification should hide its private contents if it is sensitive.
     */
    public void setHideSensitive(boolean hideSensitive, boolean animated, long delay,
            long duration) {
    }

    public int getHeightWithoutLockscreenConstraints() {
        // ExpandableNotificationRow overrides this.
        return getHeight();
    }

    /**
     * @return The desired notification height.
     */
    public int getIntrinsicHeight() {
        return getHeight();
    }

    /**
     * Sets the amount this view should be clipped from the top. This is used when an expanded
     * notification is scrolling in the top or bottom stack.
     *
     * @param clipTopAmount The amount of pixels this view should be clipped from top.
     */
    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        updateClipping();
    }

    /**
     * Set the amount the the notification is clipped on the bottom in addition to the regular
     * clipping. This is mainly used to clip something in a non-animated way without changing the
     * actual height of the notification and is purely visual.
     *
     * @param clipBottomAmount the amount to clip.
     */
    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        updateClipping();
    }

    public int getClipTopAmount() {
        return mClipTopAmount;
    }

    public int getClipBottomAmount() {
        return mClipBottomAmount;
    }

    public void setOnHeightChangedListener(OnHeightChangedListener listener) {
        mOnHeightChangedListener = listener;
    }

    /**
     * @return Whether we can expand this views content.
     */
    public boolean isContentExpandable() {
        return false;
    }

    public void notifyHeightChanged(boolean needsAnimation) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(this, needsAnimation);
        }
    }

    public boolean isTransparent() {
        return false;
    }

    /**
     * Perform a remove animation on this view.
     *
     * @param duration             The duration of the remove animation.
     * @param delay                The delay of the animation
     * @param translationDirection The direction value from [-1 ... 1] indicating in which the
     *                             animation should be performed. A value of -1 means that The
     *                             remove animation should be performed upwards,
     *                             such that the  child appears to be going away to the top. 1
     *                             Should mean the opposite.
     * @param isHeadsUpAnimation   Is this a headsUp animation.
     * @param onFinishedRunnable   A runnable which should be run when the animation is finished.
     * @param animationListener    An animation listener to add to the animation.
     * @return The additional delay, in milliseconds, that this view needs to add before the
     * animation starts.
     */
    public abstract long performRemoveAnimation(long duration,
            long delay, float translationDirection, boolean isHeadsUpAnimation,
            Runnable onStartedRunnable,
            Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener, ClipSide clipSide);

    public enum ClipSide {
        TOP,
        BOTTOM
    }

    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear) {
        performAddAnimation(delay, duration, isHeadsUpAppear, null);
    }

    public abstract void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear,
            Runnable onEndRunnable);

    public int getPinnedHeadsUpHeight() {
        return getIntrinsicHeight();
    }


    /**
     * Sets the translation of the view.
     */
    public void setTranslation(float translation) {
        setTranslationX(translation);
    }

    /**
     * Gets the translation of the view.
     */
    public float getTranslation() {
        return getTranslationX();
    }

    public void onHeightReset() {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onReset(this);
        }
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children in the {@link NotificationStackScrollLayout} at
     * position 0 and usually the translation is neglected. Since we are manually clipping this
     * view,we also need to subtract the clipTopAmount from the top. This is needed in order to
     * ensure that accessibility and focusing work correctly.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        outRect.left += getTranslationX();
        outRect.right += getTranslationX();
        outRect.bottom = (int) (outRect.top + getTranslationY() + getActualHeight());
        outRect.top += getTranslationY() + getClipTopAmount();
    }

    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        super.getBoundsOnScreen(outRect, clipToParent);
        if (getTop() + getTranslationY() < 0) {
            // We got clipped to the parent here - make sure we undo that.
            outRect.top += getTop() + getTranslationY();
        }
        outRect.bottom = outRect.top + getActualHeight();
        outRect.top += Math.max(0, getClipTopAmount());
    }

    public boolean isSummaryWithChildren() {
        return false;
    }

    public boolean areChildrenExpanded() {
        return false;
    }

    protected void updateClipping() {
        if (mClipToActualHeight && shouldClipToActualHeight()) {
            int top = getClipTopAmount();
            int bottom = Math.max(Math.max(getActualHeight()
                    - mClipBottomAmount, top), mMinimumHeightForClipping);
            mClipRect.set(Integer.MIN_VALUE, top, Integer.MAX_VALUE, bottom);
            setClipBounds(mClipRect);
        } else {
            setClipBounds(null);
        }
    }

    public void setMinimumHeightForClipping(int minimumHeightForClipping) {
        mMinimumHeightForClipping = minimumHeightForClipping;
        updateClipping();
    }

    public void setExtraWidthForClipping(float extraWidthForClipping) {
        mExtraWidthForClipping = extraWidthForClipping;
    }

    public float getHeaderVisibleAmount() {
        return 1.0f;
    }

    protected boolean shouldClipToActualHeight() {
        return true;
    }

    public void setClipToActualHeight(boolean clipToActualHeight) {
        mClipToActualHeight = clipToActualHeight;
        updateClipping();
    }

    public boolean willBeGone() {
        return mWillBeGone;
    }

    public void setWillBeGone(boolean willBeGone) {
        mWillBeGone = willBeGone;
    }

    @Override
    public void setLayerType(int layerType, Paint paint) {
        // Allow resetting the layerType to NONE regardless of overlappingRendering
        if (layerType == LAYER_TYPE_NONE || hasOverlappingRendering()) {
            super.setLayerType(layerType, paint);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Otherwise it will be clipped
        return super.hasOverlappingRendering() && getActualHeight() <= getHeight();
    }

    public boolean mustStayOnScreen() {
        return false;
    }

    public void setFakeShadowIntensity(float shadowIntensity, float outlineAlpha, int shadowYEnd,
            int outlineTranslation) {
    }

    public float getOutlineAlpha() {
        return 0.0f;
    }

    public int getOutlineTranslation() {
        return 0;
    }

    public void setChangingPosition(boolean changingPosition) {
        mChangingPosition = changingPosition;
    }

    public boolean isChangingPosition() {
        return mChangingPosition;
    }

    /**
     * Called when removing a view from its transient container, such as at the end of an animation.
     * Generally, when operating on ExpandableView instances, this should be used rather than
     * {@link ExpandableView#removeTransientView(View)} to ensure that the
     * {@link #getTransientContainer() transient container} is correctly reset.
     */
    public void removeFromTransientContainer() {
        final ViewGroup transientContainer = getTransientContainer();
        if (transientContainer == null) {
            return;
        }
        final ViewParent parent = getParent();
        if (parent != transientContainer) {
            Log.w(TAG, "Expandable view " + this
                    + " has transient container " + transientContainer
                    + " but different parent " + parent);
            setTransientContainer(null);
            return;
        }
        transientContainer.removeTransientView(this);
        setTransientContainer(null);
    }

    /**
     * Called before adding this view to a group, which would always throw an exception if this view
     * has a different parent, so clean up the transient container and throw an exception if the
     * parent isn't a transient container.  Provide as much detail as possible in the crash.
     */
    public void removeFromTransientContainerForAdditionTo(ViewGroup newParent) {
        final ViewParent parent = getParent();
        final ViewGroup transientContainer = getTransientContainer();
        if (parent == null || parent == newParent) {
            // If this view's current parent is null or the same as the new parent, the add will
            // succeed as long as it's a true child, so just make sure the view isn't transient.
            removeFromTransientContainer();
            return;
        }
        if (transientContainer == null) {
            throw new IllegalStateException("Can't add view " + this + " to container " + newParent
                    + "; current parent " + parent + " is not a transient container");
        }
        if (transientContainer != parent) {
            // Crash with details before addView() crashes without any; the view is being added
            // to a different parent, and the transient container isn't the parent, so we can't
            // even (safely) clean that up.
            throw new IllegalStateException("Expandable view " + this
                    + " has transient container " + transientContainer
                    + " but different parent " + parent);
        }
        Log.w(TAG, "Removing view " + this + " from transient container "
                + transientContainer + " in preparation for moving to parent " + newParent);
        transientContainer.removeTransientView(this);
        setTransientContainer(null);
    }

    public void setTransientContainer(ViewGroup transientContainer) {
        mTransientContainer = transientContainer;
    }

    public ViewGroup getTransientContainer() {
        return mTransientContainer;
    }

    /**
     * Add the view to a transient container.
     */
    public void addToTransientContainer(ViewGroup container, int index) {
        container.addTransientView(this, index);
        setTransientContainer(container);
    }

    /**
     * @return If the view is in a process of removal animation.
     */
    public boolean inRemovalAnimation() {
        return mInRemovalAnimation;
    }

    public void setInRemovalAnimation(boolean inRemovalAnimation) {
        mInRemovalAnimation = inRemovalAnimation;
    }

    /**
     * @return true if the group's expansion state is changing, false otherwise.
     */
    public boolean isGroupExpansionChanging() {
        return false;
    }

    public boolean isGroupExpanded() {
        return false;
    }

    /**
     * Called, when the notification has been seen by the user in the heads up state.
     */
    public void markHeadsUpSeen() {
    }

    public boolean showingPulsing() {
        return false;
    }

    public boolean isHeadsUpState() {
        return false;
    }

    public boolean isChildInGroup() {
        return false;
    }

    public void setActualHeightAnimating(boolean animating) {}

    @NonNull
    protected ExpandableViewState createExpandableViewState() {
        return new ExpandableViewState();
    }

    /** Sets {@link ExpandableViewState} to default state. */
    public ExpandableViewState resetViewState() {
        // initialize with the default values of the view
        mViewState.height = getIntrinsicHeight();
        mViewState.gone = getVisibility() == View.GONE;
        mViewState.setAlpha(1f);
        mViewState.notGoneIndex = -1;
        mViewState.setXTranslation(getTranslationX());
        mViewState.hidden = false;
        mViewState.setScaleX(getScaleX());
        mViewState.setScaleY(getScaleY());
        mViewState.inShelf = false;
        mViewState.headsUpIsVisible = false;

        // handling reset for child notifications
        if (this instanceof ExpandableNotificationRow row) {
            List<ExpandableNotificationRow> children = row.getAttachedChildren();
            if (row.isSummaryWithChildren() && children != null) {
                for (ExpandableNotificationRow childRow : children) {
                    childRow.resetViewState();
                }
            }
        }

        return mViewState;
    }

    /**
     * Get the {@link ExpandableViewState} associated with the view.
     *
     * @return the ExpandableView's view state.
     */
    @NonNull public ExpandableViewState getViewState() {
        return mViewState;
    }

    /** Applies internal {@link ExpandableViewState} to this view. */
    public void applyViewState() {
        if (!mViewState.gone) {
            mViewState.applyToView(this);
        }
    }

    /**
     * @return whether the current view doesn't add height to the overall content. This means that
     * if it is added to a list of items, its content will still have the same height.
     * An example is the notification shelf, that is always placed on top of another view.
     */
    public boolean hasNoContentHeight() {
        return false;
    }

    /**
     * @param inShelf whether the view is currently fully in the notification shelf.
     */
    public void setInShelf(boolean inShelf) {
        mInShelf = inShelf;
    }

    /**
     * @return true if the view is currently fully in the notification shelf.
     */
    public boolean isInShelf() {
        return mInShelf;
    }

    public @Nullable StatusBarIconView getShelfIcon() {
        return null;
    }

    /**
     * @return get the transformation target of the shelf, which usually is the icon
     */
    public View getShelfTransformationTarget() {
        return null;
    }

    /**
     * Get the relative top padding of a view relative to this view. This recursively walks up the
     * hierarchy and does the corresponding measuring.
     *
     * @param view the view to get the padding for. The requested view has to be a child of this
     *             notification.
     * @return the toppadding
     */
    public int getRelativeTopPadding(View view) {
        int topPadding = 0;
        while (view.getParent() instanceof ViewGroup) {
            topPadding += view.getTop();
            view = (View) view.getParent();
            if (view == this) {
                return topPadding;
            }
        }
        return topPadding;
    }


    /**
     * Get the relative start padding of a view relative to this view. This recursively walks up the
     * hierarchy and does the corresponding measuring.
     *
     * @param view the view to get the padding for. The requested view has to be a child of this
     *             notification.
     * @return the start padding
     */
    public int getRelativeStartPadding(View view) {
        boolean isRtl = isLayoutRtl();
        int startPadding = 0;
        while (view.getParent() instanceof ViewGroup) {
            View parent = (View) view.getParent();
            startPadding += isRtl ? parent.getWidth() - view.getRight() : view.getLeft();
            view = parent;
            if (view == this) {
                return startPadding;
            }
        }
        return startPadding;
    }

    /**
     * Set how much this notification is transformed into the shelf.
     *
     * @param contentTransformationAmount A value from 0 to 1 indicating how much we are transformed
     *                                 to the content away
     * @param isLastChild is this the last child in the list. If true, then the transformation is
     *                    different since its content fades out.
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
     * Update the content representation based on the amount we are transformed into the shelf.
     */
    protected void updateContentTransformation() {
        float translationY = -mContentTransformationAmount * getContentTransformationShift();
        float contentAlpha = 1.0f - mContentTransformationAmount;
        contentAlpha = Math.min(contentAlpha / 0.5f, 1.0f);
        contentAlpha = Interpolators.ALPHA_OUT.getInterpolation(contentAlpha);
        if (mIsLastChild) {
            translationY *= 0.4f;
        }
        mContentTranslation = translationY;
        applyContentTransformation(contentAlpha, translationY);
    }

    /**
     * @return how much the content shifts up when going into the shelf
     */
    protected float getContentTransformationShift() {
        return mContentShift;
    }

    /**
     * Apply the contentTransformation when going into the shelf.
     *
     * @param contentAlpha The alpha that should be applied
     * @param translationY the translationY that should be applied
     */
    protected void applyContentTransformation(float contentAlpha, float translationY) {
    }

    /**
     * @param transformingInShelf whether the view is currently transforming into the shelf in an
     *                            animated way
     */
    public void setTransformingInShelf(boolean transformingInShelf) {
        mTransformingInShelf = transformingInShelf;
    }

    public boolean isTransformingIntoShelf() {
        return mTransformingInShelf;
    }

    public boolean isAboveShelf() {
        return false;
    }

    public boolean hasExpandingChild() {
        return false;
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        pw.println(getClass().getSimpleName());
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            ExpandableViewState viewState = getViewState();
            if (viewState == null) {
                pw.println("no viewState!!!");
            } else {
                viewState.dump(pw, args);
                pw.println();
            }
            if (DUMP_VERBOSE) {
                dumpClipping(pw, args);
            }
        });
    }

    protected void dumpClipping(IndentingPrintWriter pw, String[] args) {
        pw.print("Clipping: ");
        pw.print("mInRemovalAnimation", mInRemovalAnimation);
        pw.print("mClipTopAmount", mClipTopAmount);
        pw.print("mClipBottomAmount", mClipBottomAmount);
        pw.print("mClipToActualHeight", mClipToActualHeight);
        pw.print("mExtraWidthForClipping", mExtraWidthForClipping);
        pw.print("mMinimumHeightForClipping", mMinimumHeightForClipping);
        pw.print("getClipBounds()", getClipBounds());
        pw.println();
    }

    /**
     * return the amount that the content is translated
     */
    public float getContentTranslation() {
        return mContentTranslation;
    }

    /** Sets whether this view is the first notification in a section. */
    public void setFirstInSection(boolean firstInSection) {
        mFirstInSection = firstInSection;
    }

    /** Sets whether this view is the last notification in a section. */
    public void setLastInSection(boolean lastInSection) {
        mLastInSection = lastInSection;
    }

    public boolean isLastInSection() {
        return mLastInSection;
    }

    public boolean isFirstInSection() {
        return mFirstInSection;
    }

    public int getHeadsUpHeightWithoutHeader() {
        return getHeight();
    }

    /**
     * A listener notifying when {@link #getActualHeight} changes.
     */
    public interface OnHeightChangedListener {

        /**
         * @param view the view for which the height changed, or {@code null} if just the top
         *             padding or the padding between the elements changed
         * @param needsAnimation whether the view height needs to be animated
         */
        void onHeightChanged(ExpandableView view, boolean needsAnimation);

        /**
         * Called when the view is reset and therefore the height will change abruptly
         *
         * @param view The view which was reset.
         */
        void onReset(ExpandableView view);
    }
}
