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

import com.android.internal.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Pool;
import android.util.Poolable;
import android.util.PoolableManager;
import android.util.Pools;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.RemoteViews.RemoteView;

import static android.util.Log.d;

/**
 * A Layout where the positions of the children can be described in relation to each other or to the
 * parent.
 *
 * <p>
 * Note that you cannot have a circular dependency between the size of the RelativeLayout and the
 * position of its children. For example, you cannot have a RelativeLayout whose height is set to
 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT WRAP_CONTENT} and a child set to
 * {@link #ALIGN_PARENT_BOTTOM}.
 * </p>
 *
 * <p>See the <a href="{@docRoot}guide/topics/ui/layout/relative.html">Relative
 * Layout</a> guide.</p>
 *
 * <p>
 * Also see {@link android.widget.RelativeLayout.LayoutParams RelativeLayout.LayoutParams} for
 * layout attributes
 * </p>
 *
 * @attr ref android.R.styleable#RelativeLayout_gravity
 * @attr ref android.R.styleable#RelativeLayout_ignoreGravity
 */
@RemoteView
public class RelativeLayout extends ViewGroup {
    private static final String LOG_TAG = "RelativeLayout";

    private static final boolean DEBUG_GRAPH = false;

    public static final int TRUE = -1;

    /**
     * Rule that aligns a child's right edge with another child's left edge.
     */
    public static final int LEFT_OF                  = 0;
    /**
     * Rule that aligns a child's left edge with another child's right edge.
     */
    public static final int RIGHT_OF                 = 1;
    /**
     * Rule that aligns a child's bottom edge with another child's top edge.
     */
    public static final int ABOVE                    = 2;
    /**
     * Rule that aligns a child's top edge with another child's bottom edge.
     */
    public static final int BELOW                    = 3;

    /**
     * Rule that aligns a child's baseline with another child's baseline.
     */
    public static final int ALIGN_BASELINE           = 4;
    /**
     * Rule that aligns a child's left edge with another child's left edge.
     */
    public static final int ALIGN_LEFT               = 5;
    /**
     * Rule that aligns a child's top edge with another child's top edge.
     */
    public static final int ALIGN_TOP                = 6;
    /**
     * Rule that aligns a child's right edge with another child's right edge.
     */
    public static final int ALIGN_RIGHT              = 7;
    /**
     * Rule that aligns a child's bottom edge with another child's bottom edge.
     */
    public static final int ALIGN_BOTTOM             = 8;

    /**
     * Rule that aligns the child's left edge with its RelativeLayout
     * parent's left edge.
     */
    public static final int ALIGN_PARENT_LEFT        = 9;
    /**
     * Rule that aligns the child's top edge with its RelativeLayout
     * parent's top edge.
     */
    public static final int ALIGN_PARENT_TOP         = 10;
    /**
     * Rule that aligns the child's right edge with its RelativeLayout
     * parent's right edge.
     */
    public static final int ALIGN_PARENT_RIGHT       = 11;
    /**
     * Rule that aligns the child's bottom edge with its RelativeLayout
     * parent's bottom edge.
     */
    public static final int ALIGN_PARENT_BOTTOM      = 12;

    /**
     * Rule that centers the child with respect to the bounds of its
     * RelativeLayout parent.
     */
    public static final int CENTER_IN_PARENT         = 13;
    /**
     * Rule that centers the child horizontally with respect to the
     * bounds of its RelativeLayout parent.
     */
    public static final int CENTER_HORIZONTAL        = 14;
    /**
     * Rule that centers the child vertically with respect to the
     * bounds of its RelativeLayout parent.
     */
    public static final int CENTER_VERTICAL          = 15;
    /**
     * Rule that aligns a child's end edge with another child's start edge.
     */
    public static final int START_OF                 = 16;
    /**
     * Rule that aligns a child's start edge with another child's end edge.
     */
    public static final int END_OF                   = 17;
    /**
     * Rule that aligns a child's start edge with another child's start edge.
     */
    public static final int ALIGN_START              = 18;
    /**
     * Rule that aligns a child's end edge with another child's end edge.
     */
    public static final int ALIGN_END                = 19;
    /**
     * Rule that aligns the child's start edge with its RelativeLayout
     * parent's start edge.
     */
    public static final int ALIGN_PARENT_START       = 20;
    /**
     * Rule that aligns the child's end edge with its RelativeLayout
     * parent's end edge.
     */
    public static final int ALIGN_PARENT_END         = 21;

    private static final int VERB_COUNT              = 22;


    private static final int[] RULES_VERTICAL = {
            ABOVE, BELOW, ALIGN_BASELINE, ALIGN_TOP, ALIGN_BOTTOM
    };

    private static final int[] RULES_HORIZONTAL = {
            LEFT_OF, RIGHT_OF, ALIGN_LEFT, ALIGN_RIGHT, START_OF, END_OF, ALIGN_START, ALIGN_END
    };

    private View mBaselineView = null;
    private boolean mHasBaselineAlignedChild;

    private int mGravity = Gravity.START | Gravity.TOP;
    private final Rect mContentBounds = new Rect();
    private final Rect mSelfBounds = new Rect();
    private int mIgnoreGravity;

    private SortedSet<View> mTopToBottomLeftToRightSet = null;
    
    private boolean mDirtyHierarchy;
    private View[] mSortedHorizontalChildren = new View[0];
    private View[] mSortedVerticalChildren = new View[0];
    private final DependencyGraph mGraph = new DependencyGraph();

    public RelativeLayout(Context context) {
        super(context);
    }

    public RelativeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initFromAttributes(context, attrs);
    }

    public RelativeLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initFromAttributes(context, attrs);
    }

    private void initFromAttributes(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RelativeLayout);
        mIgnoreGravity = a.getResourceId(R.styleable.RelativeLayout_ignoreGravity, View.NO_ID);
        mGravity = a.getInt(R.styleable.RelativeLayout_gravity, mGravity);
        a.recycle();
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    /**
     * Defines which View is ignored when the gravity is applied. This setting has no
     * effect if the gravity is <code>Gravity.START | Gravity.TOP</code>.
     *
     * @param viewId The id of the View to be ignored by gravity, or 0 if no View
     *        should be ignored.
     *
     * @see #setGravity(int)
     *
     * @attr ref android.R.styleable#RelativeLayout_ignoreGravity
     */
    @android.view.RemotableViewMethod
    public void setIgnoreGravity(int viewId) {
        mIgnoreGravity = viewId;
    }

    /**
     * Describes how the child views are positioned.
     *
     * @return the gravity.
     *
     * @see #setGravity(int)
     * @see android.view.Gravity
     *
     * @attr ref android.R.styleable#RelativeLayout_gravity
     */
    public int getGravity() {
        return mGravity;
    }

    /**
     * Describes how the child views are positioned. Defaults to
     * <code>Gravity.START | Gravity.TOP</code>.
     *
     * <p>Note that since RelativeLayout considers the positioning of each child
     * relative to one another to be significant, setting gravity will affect
     * the positioning of all children as a single unit within the parent.
     * This happens after children have been relatively positioned.</p>
     *
     * @param gravity See {@link android.view.Gravity}
     *
     * @see #setHorizontalGravity(int)
     * @see #setVerticalGravity(int)
     *
     * @attr ref android.R.styleable#RelativeLayout_gravity
     */
    @android.view.RemotableViewMethod
    public void setGravity(int gravity) {
        if (mGravity != gravity) {
            if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
                gravity |= Gravity.START;
            }

            if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
                gravity |= Gravity.TOP;
            }

            mGravity = gravity;
            requestLayout();
        }
    }

    @android.view.RemotableViewMethod
    public void setHorizontalGravity(int horizontalGravity) {
        final int gravity = horizontalGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        if ((mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) != gravity) {
            mGravity = (mGravity & ~Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) | gravity;
            requestLayout();
        }
    }

    @android.view.RemotableViewMethod
    public void setVerticalGravity(int verticalGravity) {
        final int gravity = verticalGravity & Gravity.VERTICAL_GRAVITY_MASK;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != gravity) {
            mGravity = (mGravity & ~Gravity.VERTICAL_GRAVITY_MASK) | gravity;
            requestLayout();
        }
    }

    @Override
    public int getBaseline() {
        return mBaselineView != null ? mBaselineView.getBaseline() : super.getBaseline();
    }

    @Override
    public void requestLayout() {
        super.requestLayout();
        mDirtyHierarchy = true;
    }

    private void sortChildren() {
        int count = getChildCount();
        if (mSortedVerticalChildren.length != count) mSortedVerticalChildren = new View[count];
        if (mSortedHorizontalChildren.length != count) mSortedHorizontalChildren = new View[count];

        final DependencyGraph graph = mGraph;
        graph.clear();

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            graph.add(child);
        }

        if (DEBUG_GRAPH) {
            d(LOG_TAG, "=== Sorted vertical children");
            graph.log(getResources(), RULES_VERTICAL);
            d(LOG_TAG, "=== Sorted horizontal children");
            graph.log(getResources(), RULES_HORIZONTAL);
        }

        graph.getSortedViews(mSortedVerticalChildren, RULES_VERTICAL);
        graph.getSortedViews(mSortedHorizontalChildren, RULES_HORIZONTAL);

        if (DEBUG_GRAPH) {
            d(LOG_TAG, "=== Ordered list of vertical children");
            for (View view : mSortedVerticalChildren) {
                DependencyGraph.printViewId(getResources(), view);
            }
            d(LOG_TAG, "=== Ordered list of horizontal children");
            for (View view : mSortedHorizontalChildren) {
                DependencyGraph.printViewId(getResources(), view);
            }
        }        
    }

    // TODO: we need to find another way to implement RelativeLayout
    // This implementation cannot handle every case
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mDirtyHierarchy) {
            mDirtyHierarchy = false;
            sortChildren();
        }

        int myWidth = -1;
        int myHeight = -1;

        int width = 0;
        int height = 0;

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // Record our dimensions if they are known;
        if (widthMode != MeasureSpec.UNSPECIFIED) {
            myWidth = widthSize;
        }

        if (heightMode != MeasureSpec.UNSPECIFIED) {
            myHeight = heightSize;
        }

        if (widthMode == MeasureSpec.EXACTLY) {
            width = myWidth;
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            height = myHeight;
        }

        mHasBaselineAlignedChild = false;

        View ignore = null;
        int gravity = mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        final boolean horizontalGravity = gravity != Gravity.START && gravity != 0;
        gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;
        final boolean verticalGravity = gravity != Gravity.TOP && gravity != 0;

        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;

        boolean offsetHorizontalAxis = false;
        boolean offsetVerticalAxis = false;

        if ((horizontalGravity || verticalGravity) && mIgnoreGravity != View.NO_ID) {
            ignore = findViewById(mIgnoreGravity);
        }

        final boolean isWrapContentWidth = widthMode != MeasureSpec.EXACTLY;
        final boolean isWrapContentHeight = heightMode != MeasureSpec.EXACTLY;

        View[] views = mSortedHorizontalChildren;
        int count = views.length;

        // We need to know our size for doing the correct computation of positioning in RTL mode
        if (isLayoutRtl() && (myWidth == -1 || isWrapContentWidth)) {
            int w = getPaddingStart() + getPaddingEnd();
            final int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            for (int i = 0; i < count; i++) {
                View child = views[i];
                if (child.getVisibility() != GONE) {
                    LayoutParams params = (LayoutParams) child.getLayoutParams();
                    // Would be similar to a call to measureChildHorizontal(child, params, -1, myHeight)
                    // but we cannot change for now the behavior of measureChildHorizontal() for
                    // taking care or a "-1" for "mywidth" so use here our own version of that code.
                    int childHeightMeasureSpec;
                    if (params.width == LayoutParams.MATCH_PARENT) {
                        childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.EXACTLY);
                    } else {
                        childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.AT_MOST);
                    }
                    child.measure(childWidthMeasureSpec, childHeightMeasureSpec);

                    w += child.getMeasuredWidth();
                    w += params.leftMargin + params.rightMargin;
                }
            }
            if (myWidth == -1) {
                // Easy case: "myWidth" was undefined before so use the width we have just computed
                myWidth = w;
            } else {
                // "myWidth" was defined before, so take the min of it and the computed width if it
                // is a non null one
                if (w > 0) {
                    myWidth = Math.min(myWidth, w);
                }
            }
        }

        for (int i = 0; i < count; i++) {
            View child = views[i];
            if (child.getVisibility() != GONE) {
                LayoutParams params = (LayoutParams) child.getLayoutParams();

                applyHorizontalSizeRules(params, myWidth);
                measureChildHorizontal(child, params, myWidth, myHeight);
                if (positionChildHorizontal(child, params, myWidth, isWrapContentWidth)) {
                    offsetHorizontalAxis = true;
                }
            }
        }

        views = mSortedVerticalChildren;
        count = views.length;

        for (int i = 0; i < count; i++) {
            View child = views[i];
            if (child.getVisibility() != GONE) {
                LayoutParams params = (LayoutParams) child.getLayoutParams();
                
                applyVerticalSizeRules(params, myHeight);
                measureChild(child, params, myWidth, myHeight);
                if (positionChildVertical(child, params, myHeight, isWrapContentHeight)) {
                    offsetVerticalAxis = true;
                }

                if (isWrapContentWidth) {
                    width = Math.max(width, params.mRight);
                }

                if (isWrapContentHeight) {
                    height = Math.max(height, params.mBottom);
                }

                if (child != ignore || verticalGravity) {
                    left = Math.min(left, params.mLeft - params.leftMargin);
                    top = Math.min(top, params.mTop - params.topMargin);
                }

                if (child != ignore || horizontalGravity) {
                    right = Math.max(right, params.mRight + params.rightMargin);
                    bottom = Math.max(bottom, params.mBottom + params.bottomMargin);
                }
            }
        }

        if (mHasBaselineAlignedChild) {
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    LayoutParams params = (LayoutParams) child.getLayoutParams();
                    alignBaseline(child, params);

                    if (child != ignore || verticalGravity) {
                        left = Math.min(left, params.mLeft - params.leftMargin);
                        top = Math.min(top, params.mTop - params.topMargin);
                    }

                    if (child != ignore || horizontalGravity) {
                        right = Math.max(right, params.mRight + params.rightMargin);
                        bottom = Math.max(bottom, params.mBottom + params.bottomMargin);
                    }
                }
            }
        }

        final int layoutDirection = getLayoutDirection();

        if (isWrapContentWidth) {
            // Width already has left padding in it since it was calculated by looking at
            // the right of each child view
            width += mPaddingRight;

            if (mLayoutParams.width >= 0) {
                width = Math.max(width, mLayoutParams.width);
            }

            width = Math.max(width, getSuggestedMinimumWidth());
            width = resolveSize(width, widthMeasureSpec);

            if (offsetHorizontalAxis) {
                for (int i = 0; i < count; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        LayoutParams params = (LayoutParams) child.getLayoutParams();
                        final int[] rules = params.getRules(layoutDirection);
                        if (rules[CENTER_IN_PARENT] != 0 || rules[CENTER_HORIZONTAL] != 0) {
                            centerHorizontal(child, params, width);
                        } else if (rules[ALIGN_PARENT_RIGHT] != 0) {
                            final int childWidth = child.getMeasuredWidth();
                            params.mLeft = width - mPaddingRight - childWidth;
                            params.mRight = params.mLeft + childWidth;
                        }
                    }
                }
            }
        }

        if (isWrapContentHeight) {
            // Height already has top padding in it since it was calculated by looking at
            // the bottom of each child view
            height += mPaddingBottom;

            if (mLayoutParams.height >= 0) {
                height = Math.max(height, mLayoutParams.height);
            }

            height = Math.max(height, getSuggestedMinimumHeight());
            height = resolveSize(height, heightMeasureSpec);

            if (offsetVerticalAxis) {
                for (int i = 0; i < count; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        LayoutParams params = (LayoutParams) child.getLayoutParams();
                        final int[] rules = params.getRules(layoutDirection);
                        if (rules[CENTER_IN_PARENT] != 0 || rules[CENTER_VERTICAL] != 0) {
                            centerVertical(child, params, height);
                        } else if (rules[ALIGN_PARENT_BOTTOM] != 0) {
                            final int childHeight = child.getMeasuredHeight();
                            params.mTop = height - mPaddingBottom - childHeight;
                            params.mBottom = params.mTop + childHeight;
                        }
                    }
                }
            }
        }

        if (horizontalGravity || verticalGravity) {
            final Rect selfBounds = mSelfBounds;
            selfBounds.set(mPaddingLeft, mPaddingTop, width - mPaddingRight,
                    height - mPaddingBottom);

            final Rect contentBounds = mContentBounds;
            Gravity.apply(mGravity, right - left, bottom - top, selfBounds, contentBounds,
                    layoutDirection);

            final int horizontalOffset = contentBounds.left - left;
            final int verticalOffset = contentBounds.top - top;
            if (horizontalOffset != 0 || verticalOffset != 0) {
                for (int i = 0; i < count; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() != GONE && child != ignore) {
                        LayoutParams params = (LayoutParams) child.getLayoutParams();
                        if (horizontalGravity) {
                            params.mLeft += horizontalOffset;
                            params.mRight += horizontalOffset;
                        }
                        if (verticalGravity) {
                            params.mTop += verticalOffset;
                            params.mBottom += verticalOffset;
                        }
                    }
                }
            }
        }

        setMeasuredDimension(width, height);
    }

    private void alignBaseline(View child, LayoutParams params) {
        final int layoutDirection = getLayoutDirection();
        int[] rules = params.getRules(layoutDirection);
        int anchorBaseline = getRelatedViewBaseline(rules, ALIGN_BASELINE);

        if (anchorBaseline != -1) {
            LayoutParams anchorParams = getRelatedViewParams(rules, ALIGN_BASELINE);
            if (anchorParams != null) {
                int offset = anchorParams.mTop + anchorBaseline;
                int baseline = child.getBaseline();
                if (baseline != -1) {
                    offset -= baseline;
                }
                int height = params.mBottom - params.mTop;
                params.mTop = offset;
                params.mBottom = params.mTop + height;
            }
        }

        if (mBaselineView == null) {
            mBaselineView = child;
        } else {
            LayoutParams lp = (LayoutParams) mBaselineView.getLayoutParams();
            if (params.mTop < lp.mTop || (params.mTop == lp.mTop && params.mLeft < lp.mLeft)) {
                mBaselineView = child;
            }
        }
    }

    /**
     * Measure a child. The child should have left, top, right and bottom information
     * stored in its LayoutParams. If any of these values is -1 it means that the view
     * can extend up to the corresponding edge.
     *
     * @param child Child to measure
     * @param params LayoutParams associated with child
     * @param myWidth Width of the the RelativeLayout
     * @param myHeight Height of the RelativeLayout
     */
    private void measureChild(View child, LayoutParams params, int myWidth, int myHeight) {
        int childWidthMeasureSpec = getChildMeasureSpec(params.mLeft,
                params.mRight, params.width,
                params.leftMargin, params.rightMargin,
                mPaddingLeft, mPaddingRight,
                myWidth);
        int childHeightMeasureSpec = getChildMeasureSpec(params.mTop,
                params.mBottom, params.height,
                params.topMargin, params.bottomMargin,
                mPaddingTop, mPaddingBottom,
                myHeight);
        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    private void measureChildHorizontal(View child, LayoutParams params, int myWidth, int myHeight) {
        int childWidthMeasureSpec = getChildMeasureSpec(params.mLeft,
                params.mRight, params.width,
                params.leftMargin, params.rightMargin,
                mPaddingLeft, mPaddingRight,
                myWidth);
        int childHeightMeasureSpec;
        if (params.width == LayoutParams.MATCH_PARENT) {
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.EXACTLY);
        } else {
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.AT_MOST);
        }
        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    /**
     * Get a measure spec that accounts for all of the constraints on this view.
     * This includes size constraints imposed by the RelativeLayout as well as
     * the View's desired dimension.
     *
     * @param childStart The left or top field of the child's layout params
     * @param childEnd The right or bottom field of the child's layout params
     * @param childSize The child's desired size (the width or height field of
     *        the child's layout params)
     * @param startMargin The left or top margin
     * @param endMargin The right or bottom margin
     * @param startPadding mPaddingLeft or mPaddingTop
     * @param endPadding mPaddingRight or mPaddingBottom
     * @param mySize The width or height of this view (the RelativeLayout)
     * @return MeasureSpec for the child
     */
    private int getChildMeasureSpec(int childStart, int childEnd,
            int childSize, int startMargin, int endMargin, int startPadding,
            int endPadding, int mySize) {
        int childSpecMode = 0;
        int childSpecSize = 0;

        // Figure out start and end bounds.
        int tempStart = childStart;
        int tempEnd = childEnd;

        // If the view did not express a layout constraint for an edge, use
        // view's margins and our padding
        if (tempStart < 0) {
            tempStart = startPadding + startMargin;
        }
        if (tempEnd < 0) {
            tempEnd = mySize - endPadding - endMargin;
        }

        // Figure out maximum size available to this view
        int maxAvailable = tempEnd - tempStart;

        if (childStart >= 0 && childEnd >= 0) {
            // Constraints fixed both edges, so child must be an exact size
            childSpecMode = MeasureSpec.EXACTLY;
            childSpecSize = maxAvailable;
        } else {
            if (childSize >= 0) {
                // Child wanted an exact size. Give as much as possible
                childSpecMode = MeasureSpec.EXACTLY;

                if (maxAvailable >= 0) {
                    // We have a maxmum size in this dimension.
                    childSpecSize = Math.min(maxAvailable, childSize);
                } else {
                    // We can grow in this dimension.
                    childSpecSize = childSize;
                }
            } else if (childSize == LayoutParams.MATCH_PARENT) {
                // Child wanted to be as big as possible. Give all available
                // space
                childSpecMode = MeasureSpec.EXACTLY;
                childSpecSize = maxAvailable;
            } else if (childSize == LayoutParams.WRAP_CONTENT) {
                // Child wants to wrap content. Use AT_MOST
                // to communicate available space if we know
                // our max size
                if (maxAvailable >= 0) {
                    // We have a maximum size in this dimension.
                    childSpecMode = MeasureSpec.AT_MOST;
                    childSpecSize = maxAvailable;
                } else {
                    // We can grow in this dimension. Child can be as big as it
                    // wants
                    childSpecMode = MeasureSpec.UNSPECIFIED;
                    childSpecSize = 0;
                }
            }
        }

        return MeasureSpec.makeMeasureSpec(childSpecSize, childSpecMode);
    }

    private boolean positionChildHorizontal(View child, LayoutParams params, int myWidth,
            boolean wrapContent) {

        final int layoutDirection = getLayoutDirection();
        int[] rules = params.getRules(layoutDirection);

        if (params.mLeft < 0 && params.mRight >= 0) {
            // Right is fixed, but left varies
            params.mLeft = params.mRight - child.getMeasuredWidth();
        } else if (params.mLeft >= 0 && params.mRight < 0) {
            // Left is fixed, but right varies
            params.mRight = params.mLeft + child.getMeasuredWidth();
        } else if (params.mLeft < 0 && params.mRight < 0) {
            // Both left and right vary
            if (rules[CENTER_IN_PARENT] != 0 || rules[CENTER_HORIZONTAL] != 0) {
                if (!wrapContent) {
                    centerHorizontal(child, params, myWidth);
                } else {
                    params.mLeft = mPaddingLeft + params.leftMargin;
                    params.mRight = params.mLeft + child.getMeasuredWidth();
                }
                return true;
            } else {
                // This is the default case. For RTL we start from the right and for LTR we start
                // from the left. This will give LEFT/TOP for LTR and RIGHT/TOP for RTL.
                if (isLayoutRtl()) {
                    params.mRight = myWidth - mPaddingRight- params.rightMargin;
                    params.mLeft = params.mRight - child.getMeasuredWidth();
                } else {
                    params.mLeft = mPaddingLeft + params.leftMargin;
                    params.mRight = params.mLeft + child.getMeasuredWidth();
                }
            }
        }
        return rules[ALIGN_PARENT_END] != 0;
    }

    private boolean positionChildVertical(View child, LayoutParams params, int myHeight,
            boolean wrapContent) {

        int[] rules = params.getRules();

        if (params.mTop < 0 && params.mBottom >= 0) {
            // Bottom is fixed, but top varies
            params.mTop = params.mBottom - child.getMeasuredHeight();
        } else if (params.mTop >= 0 && params.mBottom < 0) {
            // Top is fixed, but bottom varies
            params.mBottom = params.mTop + child.getMeasuredHeight();
        } else if (params.mTop < 0 && params.mBottom < 0) {
            // Both top and bottom vary
            if (rules[CENTER_IN_PARENT] != 0 || rules[CENTER_VERTICAL] != 0) {
                if (!wrapContent) {
                    centerVertical(child, params, myHeight);
                } else {
                    params.mTop = mPaddingTop + params.topMargin;
                    params.mBottom = params.mTop + child.getMeasuredHeight();
                }
                return true;
            } else {
                params.mTop = mPaddingTop + params.topMargin;
                params.mBottom = params.mTop + child.getMeasuredHeight();
            }
        }
        return rules[ALIGN_PARENT_BOTTOM] != 0;
    }

    private void applyHorizontalSizeRules(LayoutParams childParams, int myWidth) {
        final int layoutDirection = getLayoutDirection();
        int[] rules = childParams.getRules(layoutDirection);
        RelativeLayout.LayoutParams anchorParams;

        // -1 indicated a "soft requirement" in that direction. For example:
        // left=10, right=-1 means the view must start at 10, but can go as far as it wants to the right
        // left =-1, right=10 means the view must end at 10, but can go as far as it wants to the left
        // left=10, right=20 means the left and right ends are both fixed
        childParams.mLeft = -1;
        childParams.mRight = -1;

        anchorParams = getRelatedViewParams(rules, LEFT_OF);
        if (anchorParams != null) {
            childParams.mRight = anchorParams.mLeft - (anchorParams.leftMargin +
                    childParams.rightMargin);
        } else if (childParams.alignWithParent && rules[LEFT_OF] != 0) {
            if (myWidth >= 0) {
                childParams.mRight = myWidth - mPaddingRight - childParams.rightMargin;
            } else {
                // FIXME uh oh...
            }
        }

        anchorParams = getRelatedViewParams(rules, RIGHT_OF);
        if (anchorParams != null) {
            childParams.mLeft = anchorParams.mRight + (anchorParams.rightMargin +
                    childParams.leftMargin);
        } else if (childParams.alignWithParent && rules[RIGHT_OF] != 0) {
            childParams.mLeft = mPaddingLeft + childParams.leftMargin;
        }

        anchorParams = getRelatedViewParams(rules, ALIGN_LEFT);
        if (anchorParams != null) {
            childParams.mLeft = anchorParams.mLeft + childParams.leftMargin;
        } else if (childParams.alignWithParent && rules[ALIGN_LEFT] != 0) {
            childParams.mLeft = mPaddingLeft + childParams.leftMargin;
        }

        anchorParams = getRelatedViewParams(rules, ALIGN_RIGHT);
        if (anchorParams != null) {
            childParams.mRight = anchorParams.mRight - childParams.rightMargin;
        } else if (childParams.alignWithParent && rules[ALIGN_RIGHT] != 0) {
            if (myWidth >= 0) {
                childParams.mRight = myWidth - mPaddingRight - childParams.rightMargin;
            } else {
                // FIXME uh oh...
            }
        }

        if (0 != rules[ALIGN_PARENT_LEFT]) {
            childParams.mLeft = mPaddingLeft + childParams.leftMargin;
        }

        if (0 != rules[ALIGN_PARENT_RIGHT]) {
            if (myWidth >= 0) {
                childParams.mRight = myWidth - mPaddingRight - childParams.rightMargin;
            } else {
                // FIXME uh oh...
            }
        }
    }

    private void applyVerticalSizeRules(LayoutParams childParams, int myHeight) {
        int[] rules = childParams.getRules();
        RelativeLayout.LayoutParams anchorParams;

        childParams.mTop = -1;
        childParams.mBottom = -1;

        anchorParams = getRelatedViewParams(rules, ABOVE);
        if (anchorParams != null) {
            childParams.mBottom = anchorParams.mTop - (anchorParams.topMargin +
                    childParams.bottomMargin);
        } else if (childParams.alignWithParent && rules[ABOVE] != 0) {
            if (myHeight >= 0) {
                childParams.mBottom = myHeight - mPaddingBottom - childParams.bottomMargin;
            } else {
                // FIXME uh oh...
            }
        }

        anchorParams = getRelatedViewParams(rules, BELOW);
        if (anchorParams != null) {
            childParams.mTop = anchorParams.mBottom + (anchorParams.bottomMargin +
                    childParams.topMargin);
        } else if (childParams.alignWithParent && rules[BELOW] != 0) {
            childParams.mTop = mPaddingTop + childParams.topMargin;
        }

        anchorParams = getRelatedViewParams(rules, ALIGN_TOP);
        if (anchorParams != null) {
            childParams.mTop = anchorParams.mTop + childParams.topMargin;
        } else if (childParams.alignWithParent && rules[ALIGN_TOP] != 0) {
            childParams.mTop = mPaddingTop + childParams.topMargin;
        }

        anchorParams = getRelatedViewParams(rules, ALIGN_BOTTOM);
        if (anchorParams != null) {
            childParams.mBottom = anchorParams.mBottom - childParams.bottomMargin;
        } else if (childParams.alignWithParent && rules[ALIGN_BOTTOM] != 0) {
            if (myHeight >= 0) {
                childParams.mBottom = myHeight - mPaddingBottom - childParams.bottomMargin;
            } else {
                // FIXME uh oh...
            }
        }

        if (0 != rules[ALIGN_PARENT_TOP]) {
            childParams.mTop = mPaddingTop + childParams.topMargin;
        }

        if (0 != rules[ALIGN_PARENT_BOTTOM]) {
            if (myHeight >= 0) {
                childParams.mBottom = myHeight - mPaddingBottom - childParams.bottomMargin;
            } else {
                // FIXME uh oh...
            }
        }

        if (rules[ALIGN_BASELINE] != 0) {
            mHasBaselineAlignedChild = true;
        }
    }

    private View getRelatedView(int[] rules, int relation) {
        int id = rules[relation];
        if (id != 0) {
            DependencyGraph.Node node = mGraph.mKeyNodes.get(id);
            if (node == null) return null;
            View v = node.view;

            // Find the first non-GONE view up the chain
            while (v.getVisibility() == View.GONE) {
                rules = ((LayoutParams) v.getLayoutParams()).getRules(v.getLayoutDirection());
                node = mGraph.mKeyNodes.get((rules[relation]));
                if (node == null) return null;
                v = node.view;
            }

            return v;
        }

        return null;
    }

    private LayoutParams getRelatedViewParams(int[] rules, int relation) {
        View v = getRelatedView(rules, relation);
        if (v != null) {
            ViewGroup.LayoutParams params = v.getLayoutParams();
            if (params instanceof LayoutParams) {
                return (LayoutParams) v.getLayoutParams();
            }
        }
        return null;
    }

    private int getRelatedViewBaseline(int[] rules, int relation) {
        View v = getRelatedView(rules, relation);
        if (v != null) {
            return v.getBaseline();
        }
        return -1;
    }

    private void centerHorizontal(View child, LayoutParams params, int myWidth) {
        int childWidth = child.getMeasuredWidth();
        int left = (myWidth - childWidth) / 2;

        params.mLeft = left;
        params.mRight = left + childWidth;
    }

    private void centerVertical(View child, LayoutParams params, int myHeight) {
        int childHeight = child.getMeasuredHeight();
        int top = (myHeight - childHeight) / 2;

        params.mTop = top;
        params.mBottom = top + childHeight;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        //  The layout has actually already been performed and the positions
        //  cached.  Apply the cached values to the children.
        final int count = getChildCount();

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                RelativeLayout.LayoutParams st =
                        (RelativeLayout.LayoutParams) child.getLayoutParams();
                child.layout(st.mLeft, st.mTop, st.mRight, st.mBottom);
            }
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new RelativeLayout.LayoutParams(getContext(), attrs);
    }

    /**
     * Returns a set of layout parameters with a width of
     * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT},
     * a height of {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} and no spanning.
     */
    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    // Override to allow type-checking of LayoutParams.
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof RelativeLayout.LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mTopToBottomLeftToRightSet == null) {
            mTopToBottomLeftToRightSet = new TreeSet<View>(new TopToBottomLeftToRightComparator());
        }

        // sort children top-to-bottom and left-to-right
        for (int i = 0, count = getChildCount(); i < count; i++) {
            mTopToBottomLeftToRightSet.add(getChildAt(i));
        }

        for (View view : mTopToBottomLeftToRightSet) {
            if (view.getVisibility() == View.VISIBLE
                    && view.dispatchPopulateAccessibilityEvent(event)) {
                mTopToBottomLeftToRightSet.clear();
                return true;
            }
        }

        mTopToBottomLeftToRightSet.clear();
        return false;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(RelativeLayout.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(RelativeLayout.class.getName());
    }

    /**
     * Compares two views in left-to-right and top-to-bottom fashion.
     */
     private class TopToBottomLeftToRightComparator implements Comparator<View> {
        public int compare(View first, View second) {
            // top - bottom
            int topDifference = first.getTop() - second.getTop();
            if (topDifference != 0) {
                return topDifference;
            }
            // left - right
            int leftDifference = first.getLeft() - second.getLeft();
            if (leftDifference != 0) {
                return leftDifference;
            }
            // break tie by height
            int heightDiference = first.getHeight() - second.getHeight();
            if (heightDiference != 0) {
                return heightDiference;
            }
            // break tie by width
            int widthDiference = first.getWidth() - second.getWidth();
            if (widthDiference != 0) {
                return widthDiference;
            }
            return 0;
        }
    }

    /**
     * Per-child layout information associated with RelativeLayout.
     *
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignWithParentIfMissing
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_toLeftOf
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_toRightOf
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_above
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_below
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignBaseline
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignLeft
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignTop
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignRight
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignBottom
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignParentLeft
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignParentTop
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignParentRight
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignParentBottom
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_centerInParent
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_centerHorizontal
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_centerVertical
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_toStartOf
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_toEndOf
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignStart
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignEnd
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignParentStart
     * @attr ref android.R.styleable#RelativeLayout_Layout_layout_alignParentEnd
     */
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        @ViewDebug.ExportedProperty(category = "layout", resolveId = true, indexMapping = {
            @ViewDebug.IntToString(from = ABOVE,               to = "above"),
            @ViewDebug.IntToString(from = ALIGN_BASELINE,      to = "alignBaseline"),
            @ViewDebug.IntToString(from = ALIGN_BOTTOM,        to = "alignBottom"),
            @ViewDebug.IntToString(from = ALIGN_LEFT,          to = "alignLeft"),
            @ViewDebug.IntToString(from = ALIGN_PARENT_BOTTOM, to = "alignParentBottom"),
            @ViewDebug.IntToString(from = ALIGN_PARENT_LEFT,   to = "alignParentLeft"),
            @ViewDebug.IntToString(from = ALIGN_PARENT_RIGHT,  to = "alignParentRight"),
            @ViewDebug.IntToString(from = ALIGN_PARENT_TOP,    to = "alignParentTop"),
            @ViewDebug.IntToString(from = ALIGN_RIGHT,         to = "alignRight"),
            @ViewDebug.IntToString(from = ALIGN_TOP,           to = "alignTop"),
            @ViewDebug.IntToString(from = BELOW,               to = "below"),
            @ViewDebug.IntToString(from = CENTER_HORIZONTAL,   to = "centerHorizontal"),
            @ViewDebug.IntToString(from = CENTER_IN_PARENT,    to = "center"),
            @ViewDebug.IntToString(from = CENTER_VERTICAL,     to = "centerVertical"),
            @ViewDebug.IntToString(from = LEFT_OF,             to = "leftOf"),
            @ViewDebug.IntToString(from = RIGHT_OF,            to = "rightOf"),
            @ViewDebug.IntToString(from = ALIGN_START,         to = "alignStart"),
            @ViewDebug.IntToString(from = ALIGN_END,           to = "alignEnd"),
            @ViewDebug.IntToString(from = ALIGN_PARENT_START,  to = "alignParentStart"),
            @ViewDebug.IntToString(from = ALIGN_PARENT_END,    to = "alignParentEnd"),
            @ViewDebug.IntToString(from = START_OF,            to = "startOf"),
            @ViewDebug.IntToString(from = END_OF,              to = "endOf")
        }, mapping = {
            @ViewDebug.IntToString(from = TRUE, to = "true"),
            @ViewDebug.IntToString(from = 0,    to = "false/NO_ID")
        })

        private int[] mRules = new int[VERB_COUNT];
        private int[] mInitialRules = new int[VERB_COUNT];

        private int mLeft, mTop, mRight, mBottom;

        private int mStart = DEFAULT_RELATIVE;
        private int mEnd = DEFAULT_RELATIVE;

        private boolean mRulesChanged = false;

        /**
         * When true, uses the parent as the anchor if the anchor doesn't exist or if
         * the anchor's visibility is GONE.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public boolean alignWithParent;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            TypedArray a = c.obtainStyledAttributes(attrs,
                    com.android.internal.R.styleable.RelativeLayout_Layout);

            final int[] rules = mRules;
            final int[] initialRules = mInitialRules;

            final int N = a.getIndexCount();
            for (int i = 0; i < N; i++) {
                int attr = a.getIndex(i);
                switch (attr) {
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignWithParentIfMissing:
                        alignWithParent = a.getBoolean(attr, false);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_toLeftOf:
                        rules[LEFT_OF] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_toRightOf:
                        rules[RIGHT_OF] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_above:
                        rules[ABOVE] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_below:
                        rules[BELOW] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignBaseline:
                        rules[ALIGN_BASELINE] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignLeft:
                        rules[ALIGN_LEFT] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignTop:
                        rules[ALIGN_TOP] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignRight:
                        rules[ALIGN_RIGHT] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignBottom:
                        rules[ALIGN_BOTTOM] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignParentLeft:
                        rules[ALIGN_PARENT_LEFT] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignParentTop:
                        rules[ALIGN_PARENT_TOP] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignParentRight:
                        rules[ALIGN_PARENT_RIGHT] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignParentBottom:
                        rules[ALIGN_PARENT_BOTTOM] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_centerInParent:
                        rules[CENTER_IN_PARENT] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_centerHorizontal:
                        rules[CENTER_HORIZONTAL] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_centerVertical:
                        rules[CENTER_VERTICAL] = a.getBoolean(attr, false) ? TRUE : 0;
                       break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_toStartOf:
                        rules[START_OF] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_toEndOf:
                        rules[END_OF] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignStart:
                        rules[ALIGN_START] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignEnd:
                        rules[ALIGN_END] = a.getResourceId(attr, 0);
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignParentStart:
                        rules[ALIGN_PARENT_START] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                    case com.android.internal.R.styleable.RelativeLayout_Layout_layout_alignParentEnd:
                        rules[ALIGN_PARENT_END] = a.getBoolean(attr, false) ? TRUE : 0;
                        break;
                }
            }

            for (int n = LEFT_OF; n < VERB_COUNT; n++) {
                initialRules[n] = rules[n];
            }

            a.recycle();
        }

        public LayoutParams(int w, int h) {
            super(w, h);
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

        @Override
        public String debug(String output) {
            return output + "ViewGroup.LayoutParams={ width=" + sizeToString(width) +
                    ", height=" + sizeToString(height) + " }";
        }

        /**
         * Adds a layout rule to be interpreted by the RelativeLayout. This
         * method should only be used for constraints that don't refer to another sibling
         * (e.g., CENTER_IN_PARENT) or take a boolean value ({@link RelativeLayout#TRUE}
         * for true or 0 for false). To specify a verb that takes a subject, use
         * {@link #addRule(int, int)} instead.
         *
         * @param verb One of the verbs defined by
         *        {@link android.widget.RelativeLayout RelativeLayout}, such as
         *        ALIGN_WITH_PARENT_LEFT.
         * @see #addRule(int, int)
         */
        public void addRule(int verb) {
            mRules[verb] = TRUE;
            mInitialRules[verb] = TRUE;
            mRulesChanged = true;
        }

        /**
         * Adds a layout rule to be interpreted by the RelativeLayout. Use this for
         * verbs that take a target, such as a sibling (ALIGN_RIGHT) or a boolean
         * value (VISIBLE).
         *
         * @param verb One of the verbs defined by
         *        {@link android.widget.RelativeLayout RelativeLayout}, such as
         *         ALIGN_WITH_PARENT_LEFT.
         * @param anchor The id of another view to use as an anchor,
         *        or a boolean value(represented as {@link RelativeLayout#TRUE})
         *        for true or 0 for false).  For verbs that don't refer to another sibling
         *        (for example, ALIGN_WITH_PARENT_BOTTOM) just use -1.
         * @see #addRule(int)
         */
        public void addRule(int verb, int anchor) {
            mRules[verb] = anchor;
            mInitialRules[verb] = anchor;
            mRulesChanged = true;
        }

        /**
         * Removes a layout rule to be interpreted by the RelativeLayout.
         *
         * @param verb One of the verbs defined by
         *        {@link android.widget.RelativeLayout RelativeLayout}, such as
         *         ALIGN_WITH_PARENT_LEFT.
         * @see #addRule(int)
         * @see #addRule(int, int)
         */
        public void removeRule(int verb) {
            mRules[verb] = 0;
            mInitialRules[verb] = 0;
            mRulesChanged = true;
        }

        private boolean hasRelativeRules() {
            return (mInitialRules[START_OF] != 0 || mInitialRules[END_OF] != 0 ||
                    mInitialRules[ALIGN_START] != 0 || mInitialRules[ALIGN_END] != 0 ||
                    mInitialRules[ALIGN_PARENT_START] != 0 || mInitialRules[ALIGN_PARENT_END] != 0);
        }

        private void resolveRules(int layoutDirection) {
            final boolean isLayoutRtl = (layoutDirection == View.LAYOUT_DIRECTION_RTL);
            // Reset to initial state
            for (int n = LEFT_OF; n < VERB_COUNT; n++) {
                mRules[n] = mInitialRules[n];
            }
            // Apply rules depending on direction
            if (mRules[ALIGN_START] != 0) {
                mRules[isLayoutRtl ? ALIGN_RIGHT : ALIGN_LEFT] = mRules[ALIGN_START];
            }
            if (mRules[ALIGN_END] != 0) {
                mRules[isLayoutRtl ? ALIGN_LEFT : ALIGN_RIGHT] = mRules[ALIGN_END];
            }
            if (mRules[START_OF] != 0) {
                mRules[isLayoutRtl ? RIGHT_OF : LEFT_OF] = mRules[START_OF];
            }
            if (mRules[END_OF] != 0) {
                mRules[isLayoutRtl ? LEFT_OF : RIGHT_OF] = mRules[END_OF];
            }
            if (mRules[ALIGN_PARENT_START] != 0) {
                mRules[isLayoutRtl ? ALIGN_PARENT_RIGHT : ALIGN_PARENT_LEFT] = mRules[ALIGN_PARENT_START];
            }
            if (mRules[ALIGN_PARENT_END] != 0) {
                mRules[isLayoutRtl ? ALIGN_PARENT_LEFT : ALIGN_PARENT_RIGHT] = mRules[ALIGN_PARENT_END];
            }
            mRulesChanged = false;
        }

        /**
         * Retrieves a complete list of all supported rules, where the index is the rule
         * verb, and the element value is the value specified, or "false" if it was never
         * set. If there are relative rules defined (*_START / *_END), they will be resolved
         * depending on the layout direction.
         *
         * @param layoutDirection the direction of the layout.
         *                        Should be either {@link View#LAYOUT_DIRECTION_LTR}
         *                        or {@link View#LAYOUT_DIRECTION_RTL}
         * @return the supported rules
         * @see #addRule(int, int)
         *
         * @hide
         */
        public int[] getRules(int layoutDirection) {
            if (hasRelativeRules() &&
                    (mRulesChanged || layoutDirection != getLayoutDirection())) {
                resolveRules(layoutDirection);
                if (layoutDirection != getLayoutDirection()) {
                    setLayoutDirection(layoutDirection);
                }
            }
            return mRules;
        }

        /**
         * Retrieves a complete list of all supported rules, where the index is the rule
         * verb, and the element value is the value specified, or "false" if it was never
         * set. There will be no resolution of relative rules done.
         *
         * @return the supported rules
         * @see #addRule(int, int)
         */
        public int[] getRules() {
            return mRules;
        }

        @Override
        public void resolveLayoutDirection(int layoutDirection) {
            final boolean isLayoutRtl = isLayoutRtl();
            if (isLayoutRtl) {
                if (mStart != DEFAULT_RELATIVE) mRight = mStart;
                if (mEnd != DEFAULT_RELATIVE) mLeft = mEnd;
            } else {
                if (mStart != DEFAULT_RELATIVE) mLeft = mStart;
                if (mEnd != DEFAULT_RELATIVE) mRight = mEnd;
            }

            if (hasRelativeRules() && layoutDirection != getLayoutDirection()) {
                resolveRules(layoutDirection);
            }
            // This will set the layout direction
            super.resolveLayoutDirection(layoutDirection);
        }
    }

    private static class DependencyGraph {
        /**
         * List of all views in the graph.
         */
        private ArrayList<Node> mNodes = new ArrayList<Node>();

        /**
         * List of nodes in the graph. Each node is identified by its
         * view id (see View#getId()).
         */
        private SparseArray<Node> mKeyNodes = new SparseArray<Node>();

        /**
         * Temporary data structure used to build the list of roots
         * for this graph.
         */
        private ArrayDeque<Node> mRoots = new ArrayDeque<Node>();

        /**
         * Clears the graph.
         */
        void clear() {
            final ArrayList<Node> nodes = mNodes;
            final int count = nodes.size();

            for (int i = 0; i < count; i++) {
                nodes.get(i).release();
            }
            nodes.clear();

            mKeyNodes.clear();
            mRoots.clear();
        }

        /**
         * Adds a view to the graph.
         *
         * @param view The view to be added as a node to the graph.
         */
        void add(View view) {
            final int id = view.getId();
            final Node node = Node.acquire(view);

            if (id != View.NO_ID) {
                mKeyNodes.put(id, node);
            }

            mNodes.add(node);
        }

        /**
         * Builds a sorted list of views. The sorting order depends on the dependencies
         * between the view. For instance, if view C needs view A to be processed first
         * and view A needs view B to be processed first, the dependency graph
         * is: B -> A -> C. The sorted array will contain views B, A and C in this order.
         *
         * @param sorted The sorted list of views. The length of this array must
         *        be equal to getChildCount().
         * @param rules The list of rules to take into account.
         */
        void getSortedViews(View[] sorted, int... rules) {
            final ArrayDeque<Node> roots = findRoots(rules);
            int index = 0;

            Node node;
            while ((node = roots.pollLast()) != null) {
                final View view = node.view;
                final int key = view.getId();

                sorted[index++] = view;

                final HashMap<Node, DependencyGraph> dependents = node.dependents;
                for (Node dependent : dependents.keySet()) {
                    final SparseArray<Node> dependencies = dependent.dependencies;

                    dependencies.remove(key);
                    if (dependencies.size() == 0) {
                        roots.add(dependent);
                    }
                }
            }

            if (index < sorted.length) {
                throw new IllegalStateException("Circular dependencies cannot exist"
                        + " in RelativeLayout");
            }
        }

        /**
         * Finds the roots of the graph. A root is a node with no dependency and
         * with [0..n] dependents.
         *
         * @param rulesFilter The list of rules to consider when building the
         *        dependencies
         *
         * @return A list of node, each being a root of the graph
         */
        private ArrayDeque<Node> findRoots(int[] rulesFilter) {
            final SparseArray<Node> keyNodes = mKeyNodes;
            final ArrayList<Node> nodes = mNodes;
            final int count = nodes.size();

            // Find roots can be invoked several times, so make sure to clear
            // all dependents and dependencies before running the algorithm
            for (int i = 0; i < count; i++) {
                final Node node = nodes.get(i);
                node.dependents.clear();
                node.dependencies.clear();
            }

            // Builds up the dependents and dependencies for each node of the graph
            for (int i = 0; i < count; i++) {
                final Node node = nodes.get(i);

                final LayoutParams layoutParams = (LayoutParams) node.view.getLayoutParams();
                final int[] rules = layoutParams.mRules;
                final int rulesCount = rulesFilter.length;

                // Look only the the rules passed in parameter, this way we build only the
                // dependencies for a specific set of rules
                for (int j = 0; j < rulesCount; j++) {
                    final int rule = rules[rulesFilter[j]];
                    if (rule > 0) {
                        // The node this node depends on
                        final Node dependency = keyNodes.get(rule);
                        // Skip unknowns and self dependencies
                        if (dependency == null || dependency == node) {
                            continue;
                        }
                        // Add the current node as a dependent
                        dependency.dependents.put(node, this);
                        // Add a dependency to the current node
                        node.dependencies.put(rule, dependency);
                    }
                }
            }

            final ArrayDeque<Node> roots = mRoots;
            roots.clear();

            // Finds all the roots in the graph: all nodes with no dependencies
            for (int i = 0; i < count; i++) {
                final Node node = nodes.get(i);
                if (node.dependencies.size() == 0) roots.addLast(node);
            }

            return roots;
        }

        /**
         * Prints the dependency graph for the specified rules.
         *
         * @param resources The context's resources to print the ids.
         * @param rules The list of rules to take into account.
         */
        void log(Resources resources, int... rules) {
            final ArrayDeque<Node> roots = findRoots(rules);
            for (Node node : roots) {
                printNode(resources, node);
            }
        }

        static void printViewId(Resources resources, View view) {
            if (view.getId() != View.NO_ID) {
                d(LOG_TAG, resources.getResourceEntryName(view.getId()));
            } else {
                d(LOG_TAG, "NO_ID");
            }
        }

        private static void appendViewId(Resources resources, Node node, StringBuilder buffer) {
            if (node.view.getId() != View.NO_ID) {
                buffer.append(resources.getResourceEntryName(node.view.getId()));
            } else {
                buffer.append("NO_ID");
            }
        }

        private static void printNode(Resources resources, Node node) {
            if (node.dependents.size() == 0) {
                printViewId(resources, node.view);
            } else {
                for (Node dependent : node.dependents.keySet()) {
                    StringBuilder buffer = new StringBuilder();
                    appendViewId(resources, node, buffer);
                    printdependents(resources, dependent, buffer);
                }
            }
        }

        private static void printdependents(Resources resources, Node node, StringBuilder buffer) {
            buffer.append(" -> ");
            appendViewId(resources, node, buffer);

            if (node.dependents.size() == 0) {
                d(LOG_TAG, buffer.toString());
            } else {
                for (Node dependent : node.dependents.keySet()) {
                    StringBuilder subBuffer = new StringBuilder(buffer);
                    printdependents(resources, dependent, subBuffer);
                }
            }
        }

        /**
         * A node in the dependency graph. A node is a view, its list of dependencies
         * and its list of dependents.
         *
         * A node with no dependent is considered a root of the graph.
         */
        static class Node implements Poolable<Node> {
            /**
             * The view representing this node in the layout.
             */
            View view;

            /**
             * The list of dependents for this node; a dependent is a node
             * that needs this node to be processed first.
             */
            final HashMap<Node, DependencyGraph> dependents = new HashMap<Node, DependencyGraph>();

            /**
             * The list of dependencies for this node.
             */
            final SparseArray<Node> dependencies = new SparseArray<Node>();

            /*
             * START POOL IMPLEMENTATION
             */
            // The pool is static, so all nodes instances are shared across
            // activities, that's why we give it a rather high limit
            private static final int POOL_LIMIT = 100;
            private static final Pool<Node> sPool = Pools.synchronizedPool(
                    Pools.finitePool(new PoolableManager<Node>() {
                        public Node newInstance() {
                            return new Node();
                        }

                        public void onAcquired(Node element) {
                        }

                        public void onReleased(Node element) {
                        }
                    }, POOL_LIMIT)
            );

            private Node mNext;
            private boolean mIsPooled;

            public void setNextPoolable(Node element) {
                mNext = element;
            }

            public Node getNextPoolable() {
                return mNext;
            }

            public boolean isPooled() {
                return mIsPooled;
            }

            public void setPooled(boolean isPooled) {
                mIsPooled = isPooled;
            }

            static Node acquire(View view) {
                final Node node = sPool.acquire();
                node.view = view;

                return node;
            }

            void release() {
                view = null;
                dependents.clear();
                dependencies.clear();

                sPool.release(this);
            }
            /*
             * END POOL IMPLEMENTATION
             */
        }
    }
}
