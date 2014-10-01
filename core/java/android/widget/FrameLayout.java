/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import java.util.ArrayList;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.RemoteViews.RemoteView;

import com.android.internal.R;


/**
 * FrameLayout is designed to block out an area on the screen to display
 * a single item. Generally, FrameLayout should be used to hold a single child view, because it can
 * be difficult to organize child views in a way that's scalable to different screen sizes without
 * the children overlapping each other. You can, however, add multiple children to a FrameLayout
 * and control their position within the FrameLayout by assigning gravity to each child, using the
 * <a href="FrameLayout.LayoutParams.html#attr_android:layout_gravity">{@code
 * android:layout_gravity}</a> attribute.
 * <p>Child views are drawn in a stack, with the most recently added child on top.
 * The size of the FrameLayout is the size of its largest child (plus padding), visible
 * or not (if the FrameLayout's parent permits). Views that are {@link android.view.View#GONE} are
 * used for sizing
 * only if {@link #setMeasureAllChildren(boolean) setConsiderGoneChildrenWhenMeasuring()}
 * is set to true.
 *
 * @attr ref android.R.styleable#FrameLayout_foreground
 * @attr ref android.R.styleable#FrameLayout_foregroundGravity
 * @attr ref android.R.styleable#FrameLayout_measureAllChildren
 */
@RemoteView
public class FrameLayout extends ViewGroup {
    private static final int DEFAULT_CHILD_GRAVITY = Gravity.TOP | Gravity.START;

    @ViewDebug.ExportedProperty(category = "measurement")
    boolean mMeasureAllChildren = false;

    @ViewDebug.ExportedProperty(category = "drawing")
    private Drawable mForeground;
    private ColorStateList mForegroundTintList = null;
    private PorterDuff.Mode mForegroundTintMode = null;
    private boolean mHasForegroundTint = false;
    private boolean mHasForegroundTintMode = false;

    @ViewDebug.ExportedProperty(category = "padding")
    private int mForegroundPaddingLeft = 0;

    @ViewDebug.ExportedProperty(category = "padding")
    private int mForegroundPaddingTop = 0;

    @ViewDebug.ExportedProperty(category = "padding")
    private int mForegroundPaddingRight = 0;

    @ViewDebug.ExportedProperty(category = "padding")
    private int mForegroundPaddingBottom = 0;

    private final Rect mSelfBounds = new Rect();
    private final Rect mOverlayBounds = new Rect();

    @ViewDebug.ExportedProperty(category = "drawing")
    private int mForegroundGravity = Gravity.FILL;

    /** {@hide} */
    @ViewDebug.ExportedProperty(category = "drawing")
    protected boolean mForegroundInPadding = true;

    boolean mForegroundBoundsChanged = false;
    
    private final ArrayList<View> mMatchParentChildren = new ArrayList<View>(1);
    
    public FrameLayout(Context context) {
        super(context);
    }
    
    public FrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FrameLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.FrameLayout, defStyleAttr, defStyleRes);

        mForegroundGravity = a.getInt(
                com.android.internal.R.styleable.FrameLayout_foregroundGravity, mForegroundGravity);

        final Drawable d = a.getDrawable(com.android.internal.R.styleable.FrameLayout_foreground);
        if (d != null) {
            setForeground(d);
        }
        
        if (a.getBoolean(com.android.internal.R.styleable.FrameLayout_measureAllChildren, false)) {
            setMeasureAllChildren(true);
        }

        if (a.hasValue(R.styleable.FrameLayout_foregroundTintMode)) {
            mForegroundTintMode = Drawable.parseTintMode(a.getInt(
                    R.styleable.FrameLayout_foregroundTintMode, -1), mForegroundTintMode);
            mHasForegroundTintMode = true;
        }

        if (a.hasValue(R.styleable.FrameLayout_foregroundTint)) {
            mForegroundTintList = a.getColorStateList(R.styleable.FrameLayout_foregroundTint);
            mHasForegroundTint = true;
        }

        mForegroundInPadding = a.getBoolean(R.styleable.FrameLayout_foregroundInsidePadding, true);

        a.recycle();

        applyForegroundTint();
    }

    /**
     * Describes how the foreground is positioned.
     *
     * @return foreground gravity.
     *
     * @see #setForegroundGravity(int)
     *
     * @attr ref android.R.styleable#FrameLayout_foregroundGravity
     */
    public int getForegroundGravity() {
        return mForegroundGravity;
    }

    /**
     * Describes how the foreground is positioned. Defaults to START and TOP.
     *
     * @param foregroundGravity See {@link android.view.Gravity}
     *
     * @see #getForegroundGravity()
     *
     * @attr ref android.R.styleable#FrameLayout_foregroundGravity
     */
    @android.view.RemotableViewMethod
    public void setForegroundGravity(int foregroundGravity) {
        if (mForegroundGravity != foregroundGravity) {
            if ((foregroundGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
                foregroundGravity |= Gravity.START;
            }

            if ((foregroundGravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
                foregroundGravity |= Gravity.TOP;
            }

            mForegroundGravity = foregroundGravity;


            if (mForegroundGravity == Gravity.FILL && mForeground != null) {
                Rect padding = new Rect();
                if (mForeground.getPadding(padding)) {
                    mForegroundPaddingLeft = padding.left;
                    mForegroundPaddingTop = padding.top;
                    mForegroundPaddingRight = padding.right;
                    mForegroundPaddingBottom = padding.bottom;
                }
            } else {
                mForegroundPaddingLeft = 0;
                mForegroundPaddingTop = 0;
                mForegroundPaddingRight = 0;
                mForegroundPaddingBottom = 0;
            }

            requestLayout();
        }
    }

    @Override
    @RemotableViewMethod
    public void setVisibility(@Visibility int visibility) {
        super.setVisibility(visibility);
        if (mForeground != null) {
            mForeground.setVisible(visibility == VISIBLE, false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || (who == mForeground);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mForeground != null) mForeground.jumpToCurrentState();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mForeground != null && mForeground.isStateful()) {
            mForeground.setState(getDrawableState());
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mForeground != null) {
            mForeground.setHotspot(x, y);
        }
    }

    /**
     * Returns a set of layout parameters with a width of
     * {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT},
     * and a height of {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}.
     */
    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    /**
     * Supply a Drawable that is to be rendered on top of all of the child
     * views in the frame layout.  Any padding in the Drawable will be taken
     * into account by ensuring that the children are inset to be placed
     * inside of the padding area.
     * 
     * @param d The Drawable to be drawn on top of the children.
     * 
     * @attr ref android.R.styleable#FrameLayout_foreground
     */
    public void setForeground(Drawable d) {
        if (mForeground != d) {
            if (mForeground != null) {
                mForeground.setCallback(null);
                unscheduleDrawable(mForeground);
            }

            mForeground = d;
            mForegroundPaddingLeft = 0;
            mForegroundPaddingTop = 0;
            mForegroundPaddingRight = 0;
            mForegroundPaddingBottom = 0;

            if (d != null) {
                setWillNotDraw(false);
                d.setCallback(this);
                d.setLayoutDirection(getLayoutDirection());
                if (d.isStateful()) {
                    d.setState(getDrawableState());
                }
                applyForegroundTint();
                if (mForegroundGravity == Gravity.FILL) {
                    Rect padding = new Rect();
                    if (d.getPadding(padding)) {
                        mForegroundPaddingLeft = padding.left;
                        mForegroundPaddingTop = padding.top;
                        mForegroundPaddingRight = padding.right;
                        mForegroundPaddingBottom = padding.bottom;
                    }
                }
            }  else {
                setWillNotDraw(true);
            }
            requestLayout();
            invalidate();
        }
    }

    /**
     * Returns the drawable used as the foreground of this FrameLayout. The
     * foreground drawable, if non-null, is always drawn on top of the children.
     *
     * @return A Drawable or null if no foreground was set.
     */
    public Drawable getForeground() {
        return mForeground;
    }

    /**
     * Applies a tint to the foreground drawable. Does not modify the current
     * tint mode, which is {@link PorterDuff.Mode#SRC_IN} by default.
     * <p>
     * Subsequent calls to {@link #setForeground(Drawable)} will automatically
     * mutate the drawable and apply the specified tint and tint mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#FrameLayout_foregroundTint
     * @see #getForegroundTintList()
     * @see Drawable#setTintList(ColorStateList)
     */
    public void setForegroundTintList(@Nullable ColorStateList tint) {
        mForegroundTintList = tint;
        mHasForegroundTint = true;

        applyForegroundTint();
    }

    /**
     * @return the tint applied to the foreground drawable
     * @attr ref android.R.styleable#FrameLayout_foregroundTint
     * @see #setForegroundTintList(ColorStateList)
     */
    @Nullable
    public ColorStateList getForegroundTintList() {
        return mForegroundTintList;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setForegroundTintList(ColorStateList)}} to the foreground drawable.
     * The default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#FrameLayout_foregroundTintMode
     * @see #getForegroundTintMode()
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setForegroundTintMode(@Nullable PorterDuff.Mode tintMode) {
        mForegroundTintMode = tintMode;
        mHasForegroundTintMode = true;

        applyForegroundTint();
    }

    /**
     * @return the blending mode used to apply the tint to the foreground
     *         drawable
     * @attr ref android.R.styleable#FrameLayout_foregroundTintMode
     * @see #setForegroundTintMode(PorterDuff.Mode)
     */
    @Nullable
    public PorterDuff.Mode getForegroundTintMode() {
        return mForegroundTintMode;
    }

    private void applyForegroundTint() {
        if (mForeground != null && (mHasForegroundTint || mHasForegroundTintMode)) {
            mForeground = mForeground.mutate();

            if (mHasForegroundTint) {
                mForeground.setTintList(mForegroundTintList);
            }

            if (mHasForegroundTintMode) {
                mForeground.setTintMode(mForegroundTintMode);
            }
        }
    }

    int getPaddingLeftWithForeground() {
        return mForegroundInPadding ? Math.max(mPaddingLeft, mForegroundPaddingLeft) :
            mPaddingLeft + mForegroundPaddingLeft;
    }

    int getPaddingRightWithForeground() {
        return mForegroundInPadding ? Math.max(mPaddingRight, mForegroundPaddingRight) :
            mPaddingRight + mForegroundPaddingRight;
    }

    private int getPaddingTopWithForeground() {
        return mForegroundInPadding ? Math.max(mPaddingTop, mForegroundPaddingTop) :
            mPaddingTop + mForegroundPaddingTop;
    }

    private int getPaddingBottomWithForeground() {
        return mForegroundInPadding ? Math.max(mPaddingBottom, mForegroundPaddingBottom) :
            mPaddingBottom + mForegroundPaddingBottom;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();

        final boolean measureMatchParentChildren =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY ||
                MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;
        mMatchParentChildren.clear();

        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (mMeasureAllChildren || child.getVisibility() != GONE) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                maxWidth = Math.max(maxWidth,
                        child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
                maxHeight = Math.max(maxHeight,
                        child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                childState = combineMeasuredStates(childState, child.getMeasuredState());
                if (measureMatchParentChildren) {
                    if (lp.width == LayoutParams.MATCH_PARENT ||
                            lp.height == LayoutParams.MATCH_PARENT) {
                        mMatchParentChildren.add(child);
                    }
                }
            }
        }

        // Account for padding too
        maxWidth += getPaddingLeftWithForeground() + getPaddingRightWithForeground();
        maxHeight += getPaddingTopWithForeground() + getPaddingBottomWithForeground();

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        // Check against our foreground's minimum height and width
        final Drawable drawable = getForeground();
        if (drawable != null) {
            maxHeight = Math.max(maxHeight, drawable.getMinimumHeight());
            maxWidth = Math.max(maxWidth, drawable.getMinimumWidth());
        }

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));

        count = mMatchParentChildren.size();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                final View child = mMatchParentChildren.get(i);

                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int childWidthMeasureSpec;
                int childHeightMeasureSpec;
                
                if (lp.width == LayoutParams.MATCH_PARENT) {
                    childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth() -
                            getPaddingLeftWithForeground() - getPaddingRightWithForeground() -
                            lp.leftMargin - lp.rightMargin,
                            MeasureSpec.EXACTLY);
                } else {
                    childWidthMeasureSpec = getChildMeasureSpec(widthMeasureSpec,
                            getPaddingLeftWithForeground() + getPaddingRightWithForeground() +
                            lp.leftMargin + lp.rightMargin,
                            lp.width);
                }
                
                if (lp.height == LayoutParams.MATCH_PARENT) {
                    childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight() -
                            getPaddingTopWithForeground() - getPaddingBottomWithForeground() -
                            lp.topMargin - lp.bottomMargin,
                            MeasureSpec.EXACTLY);
                } else {
                    childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                            getPaddingTopWithForeground() + getPaddingBottomWithForeground() +
                            lp.topMargin + lp.bottomMargin,
                            lp.height);
                }

                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }
 
    /**
     * {@inheritDoc}
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutChildren(left, top, right, bottom, false /* no force left gravity */);
    }

    void layoutChildren(int left, int top, int right, int bottom,
                                  boolean forceLeftGravity) {
        final int count = getChildCount();

        final int parentLeft = getPaddingLeftWithForeground();
        final int parentRight = right - left - getPaddingRightWithForeground();

        final int parentTop = getPaddingTopWithForeground();
        final int parentBottom = bottom - top - getPaddingBottomWithForeground();

        mForegroundBoundsChanged = true;
        
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();

                int childLeft;
                int childTop;

                int gravity = lp.gravity;
                if (gravity == -1) {
                    gravity = DEFAULT_CHILD_GRAVITY;
                }

                final int layoutDirection = getLayoutDirection();
                final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
                final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

                switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft = parentLeft + (parentRight - parentLeft - width) / 2 +
                        lp.leftMargin - lp.rightMargin;
                        break;
                    case Gravity.RIGHT:
                        if (!forceLeftGravity) {
                            childLeft = parentRight - width - lp.rightMargin;
                            break;
                        }
                    case Gravity.LEFT:
                    default:
                        childLeft = parentLeft + lp.leftMargin;
                }

                switch (verticalGravity) {
                    case Gravity.TOP:
                        childTop = parentTop + lp.topMargin;
                        break;
                    case Gravity.CENTER_VERTICAL:
                        childTop = parentTop + (parentBottom - parentTop - height) / 2 +
                        lp.topMargin - lp.bottomMargin;
                        break;
                    case Gravity.BOTTOM:
                        childTop = parentBottom - height - lp.bottomMargin;
                        break;
                    default:
                        childTop = parentTop + lp.topMargin;
                }

                child.layout(childLeft, childTop, childLeft + width, childTop + height);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mForegroundBoundsChanged = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (mForeground != null) {
            final Drawable foreground = mForeground;

            if (mForegroundBoundsChanged) {
                mForegroundBoundsChanged = false;
                final Rect selfBounds = mSelfBounds;
                final Rect overlayBounds = mOverlayBounds;

                final int w = mRight-mLeft;
                final int h = mBottom-mTop;

                if (mForegroundInPadding) {
                    selfBounds.set(0, 0, w, h);
                } else {
                    selfBounds.set(mPaddingLeft, mPaddingTop, w - mPaddingRight, h - mPaddingBottom);
                }

                final int layoutDirection = getLayoutDirection();
                Gravity.apply(mForegroundGravity, foreground.getIntrinsicWidth(),
                        foreground.getIntrinsicHeight(), selfBounds, overlayBounds,
                        layoutDirection);
                foreground.setBounds(overlayBounds);
            }
            
            foreground.draw(canvas);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean gatherTransparentRegion(Region region) {
        boolean opaque = super.gatherTransparentRegion(region);
        if (region != null && mForeground != null) {
            applyDrawableToTransparentRegion(mForeground, region);
        }
        return opaque;
    }

    /**
     * Sets whether to consider all children, or just those in
     * the VISIBLE or INVISIBLE state, when measuring. Defaults to false.
     *
     * @param measureAll true to consider children marked GONE, false otherwise.
     * Default value is false.
     *
     * @attr ref android.R.styleable#FrameLayout_measureAllChildren
     */
    @android.view.RemotableViewMethod
    public void setMeasureAllChildren(boolean measureAll) {
        mMeasureAllChildren = measureAll;
    }

    /**
     * Determines whether all children, or just those in the VISIBLE or
     * INVISIBLE state, are considered when measuring.
     *
     * @return Whether all children are considered when measuring.
     *
     * @deprecated This method is deprecated in favor of
     * {@link #getMeasureAllChildren() getMeasureAllChildren()}, which was
     * renamed for consistency with
     * {@link #setMeasureAllChildren(boolean) setMeasureAllChildren()}.
     */
    @Deprecated
    public boolean getConsiderGoneChildrenWhenMeasuring() {
        return getMeasureAllChildren();
    }

    /**
     * Determines whether all children, or just those in the VISIBLE or
     * INVISIBLE state, are considered when measuring.
     *
     * @return Whether all children are considered when measuring.
     */
    public boolean getMeasureAllChildren() {
        return mMeasureAllChildren;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new FrameLayout.LayoutParams(getContext(), attrs);        
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }


    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(FrameLayout.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(FrameLayout.class.getName());
    }

    /**
     * Per-child layout information for layouts that support margins.
     * See {@link android.R.styleable#FrameLayout_Layout FrameLayout Layout Attributes}
     * for a list of all child view attributes that this class supports.
     * 
     * @attr ref android.R.styleable#FrameLayout_Layout_layout_gravity
     */
    public static class LayoutParams extends MarginLayoutParams {
        /**
         * The gravity to apply with the View to which these layout parameters
         * are associated.
         *
         * @see android.view.Gravity
         * 
         * @attr ref android.R.styleable#FrameLayout_Layout_layout_gravity
         */
        public int gravity = -1;

        /**
         * {@inheritDoc}
         */
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            TypedArray a = c.obtainStyledAttributes(attrs, com.android.internal.R.styleable.FrameLayout_Layout);
            gravity = a.getInt(com.android.internal.R.styleable.FrameLayout_Layout_layout_gravity, -1);
            a.recycle();
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(int width, int height) {
            super(width, height);
        }

        /**
         * Creates a new set of layout parameters with the specified width, height
         * and weight.
         *
         * @param width the width, either {@link #MATCH_PARENT},
         *        {@link #WRAP_CONTENT} or a fixed size in pixels
         * @param height the height, either {@link #MATCH_PARENT},
         *        {@link #WRAP_CONTENT} or a fixed size in pixels
         * @param gravity the gravity
         *
         * @see android.view.Gravity
         */
        public LayoutParams(int width, int height, int gravity) {
            super(width, height);
            this.gravity = gravity;
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(ViewGroup.MarginLayoutParams source) {
            super(source);
        }

        /**
         * Copy constructor. Clones the width, height, margin values, and
         * gravity of the source.
         *
         * @param source The layout params to copy from.
         */
        public LayoutParams(LayoutParams source) {
            super(source);

            this.gravity = source.gravity;
        }
    }
}
