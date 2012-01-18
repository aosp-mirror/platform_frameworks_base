/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;


/**
 * <p>A layout that arranges its children horizontally. A TableRow should
 * always be used as a child of a {@link android.widget.TableLayout}. If a
 * TableRow's parent is not a TableLayout, the TableRow will behave as
 * an horizontal {@link android.widget.LinearLayout}.</p>
 *
 * <p>The children of a TableRow do not need to specify the
 * <code>layout_width</code> and <code>layout_height</code> attributes in the
 * XML file. TableRow always enforces those values to be respectively
 * {@link android.widget.TableLayout.LayoutParams#MATCH_PARENT} and
 * {@link android.widget.TableLayout.LayoutParams#WRAP_CONTENT}.</p>
 *
 * <p>
 * Also see {@link TableRow.LayoutParams android.widget.TableRow.LayoutParams}
 * for layout attributes </p>
 */
public class TableRow extends LinearLayout {
    private int mNumColumns = 0;
    private int[] mColumnWidths;
    private int[] mConstrainedColumnWidths;
    private SparseIntArray mColumnToChildIndex;

    private ChildrenTracker mChildrenTracker;

    /**
     * <p>Creates a new TableRow for the given context.</p>
     *
     * @param context the application environment
     */
    public TableRow(Context context) {
        super(context);
        initTableRow();
    }

    /**
     * <p>Creates a new TableRow for the given context and with the
     * specified set attributes.</p>
     *
     * @param context the application environment
     * @param attrs a collection of attributes
     */
    public TableRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        initTableRow();
    }

    private void initTableRow() {
        OnHierarchyChangeListener oldListener = mOnHierarchyChangeListener;
        mChildrenTracker = new ChildrenTracker();
        if (oldListener != null) {
            mChildrenTracker.setOnHierarchyChangeListener(oldListener);
        }
        super.setOnHierarchyChangeListener(mChildrenTracker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
        mChildrenTracker.setOnHierarchyChangeListener(listener);
    }

    /**
     * <p>Collapses or restores a given column.</p>
     *
     * @param columnIndex the index of the column
     * @param collapsed true if the column must be collapsed, false otherwise
     * {@hide}
     */
    void setColumnCollapsed(int columnIndex, boolean collapsed) {
        View child = getVirtualChildAt(columnIndex);
        if (child != null) {
            child.setVisibility(collapsed ? GONE : VISIBLE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // enforce horizontal layout
        measureHorizontal(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // enforce horizontal layout
        layoutHorizontal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View getVirtualChildAt(int i) {
        if (mColumnToChildIndex == null) {
            mapIndexAndColumns();
        }

        final int deflectedIndex = mColumnToChildIndex.get(i, -1);
        if (deflectedIndex != -1) {
            return getChildAt(deflectedIndex);
        }

        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVirtualChildCount() {
        if (mColumnToChildIndex == null) {
            mapIndexAndColumns();
        }
        return mNumColumns;
    }

    private void mapIndexAndColumns() {
        if (mColumnToChildIndex == null) {
            int virtualCount = 0;
            final int count = getChildCount();

            mColumnToChildIndex = new SparseIntArray();
            final SparseIntArray columnToChild = mColumnToChildIndex;

            for (int i = 0; i < count; i++) {
                final View child = getChildAt(i);
                final LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();

                if (layoutParams.column >= virtualCount) {
                    virtualCount = layoutParams.column;
                }

                for (int j = 0; j < layoutParams.span; j++) {
                    columnToChild.put(virtualCount++, i);
                }
            }

            mNumColumns = virtualCount;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int measureNullChild(int childIndex) {
        return mConstrainedColumnWidths[childIndex];
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void measureChildBeforeLayout(View child, int childIndex,
            int widthMeasureSpec, int totalWidth,
            int heightMeasureSpec, int totalHeight) {
        if (mConstrainedColumnWidths != null) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            int measureMode = MeasureSpec.EXACTLY;
            int columnWidth = 0;

            final int span = lp.span;
            final int[] constrainedColumnWidths = mConstrainedColumnWidths;
            for (int i = 0; i < span; i++) {
                columnWidth += constrainedColumnWidths[childIndex + i];
            }

            final int gravity = lp.gravity;
            final boolean isHorizontalGravity = Gravity.isHorizontal(gravity);

            if (isHorizontalGravity) {
                measureMode = MeasureSpec.AT_MOST;
            }

            // no need to care about padding here,
            // ViewGroup.getChildMeasureSpec() would get rid of it anyway
            // because of the EXACTLY measure spec we use
            int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                    Math.max(0, columnWidth - lp.leftMargin - lp.rightMargin), measureMode
            );
            int childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                    mPaddingTop + mPaddingBottom + lp.topMargin +
                    lp .bottomMargin + totalHeight, lp.height);

            child.measure(childWidthMeasureSpec, childHeightMeasureSpec);

            if (isHorizontalGravity) {
                final int childWidth = child.getMeasuredWidth();
                lp.mOffset[LayoutParams.LOCATION_NEXT] = columnWidth - childWidth;

                final int layoutDirection = getResolvedLayoutDirection();
                final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
                switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.LEFT:
                        // don't offset on X axis
                        break;
                    case Gravity.RIGHT:
                        lp.mOffset[LayoutParams.LOCATION] = lp.mOffset[LayoutParams.LOCATION_NEXT];
                        break;
                    case Gravity.CENTER_HORIZONTAL:
                        lp.mOffset[LayoutParams.LOCATION] = lp.mOffset[LayoutParams.LOCATION_NEXT] / 2;
                        break;
                }
            } else {
                lp.mOffset[LayoutParams.LOCATION] = lp.mOffset[LayoutParams.LOCATION_NEXT] = 0;
            }
        } else {
            // fail silently when column widths are not available
            super.measureChildBeforeLayout(child, childIndex, widthMeasureSpec,
                    totalWidth, heightMeasureSpec, totalHeight);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int getChildrenSkipCount(View child, int index) {
        LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();

        // when the span is 1 (default), we need to skip 0 child
        return layoutParams.span - 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int getLocationOffset(View child) {
        return ((TableRow.LayoutParams) child.getLayoutParams()).mOffset[LayoutParams.LOCATION];
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int getNextLocationOffset(View child) {
        return ((TableRow.LayoutParams) child.getLayoutParams()).mOffset[LayoutParams.LOCATION_NEXT];
    }

    /**
     * <p>Measures the preferred width of each child, including its margins.</p>
     *
     * @param widthMeasureSpec the width constraint imposed by our parent
     *
     * @return an array of integers corresponding to the width of each cell, or
     *         column, in this row
     * {@hide}
     */
    int[] getColumnsWidths(int widthMeasureSpec) {
        final int numColumns = getVirtualChildCount();
        if (mColumnWidths == null || numColumns != mColumnWidths.length) {
            mColumnWidths = new int[numColumns];
        }

        final int[] columnWidths = mColumnWidths;

        for (int i = 0; i < numColumns; i++) {
            final View child = getVirtualChildAt(i);
            if (child != null && child.getVisibility() != GONE) {
                final LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                if (layoutParams.span == 1) {
                    int spec;
                    switch (layoutParams.width) {
                        case LayoutParams.WRAP_CONTENT:
                            spec = getChildMeasureSpec(widthMeasureSpec, 0, LayoutParams.WRAP_CONTENT);
                            break;
                        case LayoutParams.MATCH_PARENT:
                            spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                            break;
                        default:
                            spec = MeasureSpec.makeMeasureSpec(layoutParams.width, MeasureSpec.EXACTLY);
                    }
                    child.measure(spec, spec);

                    final int width = child.getMeasuredWidth() + layoutParams.leftMargin +
                            layoutParams.rightMargin;
                    columnWidths[i] = width;
                } else {
                    columnWidths[i] = 0;
                }
            } else {
                columnWidths[i] = 0;
            }
        }

        return columnWidths;
    }

    /**
     * <p>Sets the width of all of the columns in this row. At layout time,
     * this row sets a fixed width, as defined by <code>columnWidths</code>,
     * on each child (or cell, or column.)</p>
     *
     * @param columnWidths the fixed width of each column that this row must
     *                     honor
     * @throws IllegalArgumentException when columnWidths' length is smaller
     *         than the number of children in this row
     * {@hide}
     */
    void setColumnsWidthConstraints(int[] columnWidths) {
        if (columnWidths == null || columnWidths.length < getVirtualChildCount()) {
            throw new IllegalArgumentException(
                    "columnWidths should be >= getVirtualChildCount()");
        }

        mConstrainedColumnWidths = columnWidths;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new TableRow.LayoutParams(getContext(), attrs);
    }

    /**
     * Returns a set of layout parameters with a width of
     * {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT},
     * a height of {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} and no spanning.
     */
    @Override
    protected LinearLayout.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof TableRow.LayoutParams;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected LinearLayout.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(TableRow.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(TableRow.class.getName());
    }

    /**
     * <p>Set of layout parameters used in table rows.</p>
     *
     * @see android.widget.TableLayout.LayoutParams
     * 
     * @attr ref android.R.styleable#TableRow_Cell_layout_column
     * @attr ref android.R.styleable#TableRow_Cell_layout_span
     */
    public static class LayoutParams extends LinearLayout.LayoutParams {
        /**
         * <p>The column index of the cell represented by the widget.</p>
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int column;

        /**
         * <p>The number of columns the widgets spans over.</p>
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int span;

        private static final int LOCATION = 0;
        private static final int LOCATION_NEXT = 1;

        private int[] mOffset = new int[2];

        /**
         * {@inheritDoc}
         */
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            TypedArray a =
                    c.obtainStyledAttributes(attrs,
                            com.android.internal.R.styleable.TableRow_Cell);

            column = a.getInt(com.android.internal.R.styleable.TableRow_Cell_layout_column, -1);
            span = a.getInt(com.android.internal.R.styleable.TableRow_Cell_layout_span, 1);
            if (span <= 1) {
                span = 1;
            }

            a.recycle();
        }

        /**
         * <p>Sets the child width and the child height.</p>
         *
         * @param w the desired width
         * @param h the desired height
         */
        public LayoutParams(int w, int h) {
            super(w, h);
            column = -1;
            span = 1;
        }

        /**
         * <p>Sets the child width, height and weight.</p>
         *
         * @param w the desired width
         * @param h the desired height
         * @param initWeight the desired weight
         */
        public LayoutParams(int w, int h, float initWeight) {
            super(w, h, initWeight);
            column = -1;
            span = 1;
        }

        /**
         * <p>Sets the child width to {@link android.view.ViewGroup.LayoutParams}
         * and the child height to
         * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}.</p>
         */
        public LayoutParams() {
            super(MATCH_PARENT, WRAP_CONTENT);
            column = -1;
            span = 1;
        }

        /**
         * <p>Puts the view in the specified column.</p>
         *
         * <p>Sets the child width to {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
         * and the child height to
         * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}.</p>
         *
         * @param column the column index for the view
         */
        public LayoutParams(int column) {
            this();
            this.column = column;
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(ViewGroup.LayoutParams p) {
            super(p);
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        @Override
        protected void setBaseAttributes(TypedArray a, int widthAttr, int heightAttr) {
            // We don't want to force users to specify a layout_width
            if (a.hasValue(widthAttr)) {
                width = a.getLayoutDimension(widthAttr, "layout_width");
            } else {
                width = MATCH_PARENT;
            }

            // We don't want to force users to specify a layout_height
            if (a.hasValue(heightAttr)) {
                height = a.getLayoutDimension(heightAttr, "layout_height");
            } else {
                height = WRAP_CONTENT;
            }
        }
    }

    // special transparent hierarchy change listener
    private class ChildrenTracker implements OnHierarchyChangeListener {
        private OnHierarchyChangeListener listener;

        private void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
            this.listener = listener;
        }

        public void onChildViewAdded(View parent, View child) {
            // dirties the index to column map
            mColumnToChildIndex = null;

            if (this.listener != null) {
                this.listener.onChildViewAdded(parent, child);
            }
        }

        public void onChildViewRemoved(View parent, View child) {
            // dirties the index to column map
            mColumnToChildIndex = null;

            if (this.listener != null) {
                this.listener.onChildViewRemoved(parent, child);
            }
        }
    }
}
