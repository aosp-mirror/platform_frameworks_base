/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.view.Gravity.*;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * A layout that places its children in a rectangular <em>grid</em>.
 * <p>
 * The grid is composed of a set of infinitely thin lines that separate the
 * viewing area into <em>cells</em>. Throughout the API, grid lines are referenced
 * by grid <em>indices</em>. A grid with {@code N} columns
 * has {@code N + 1} grid indices that run from {@code 0}
 * through {@code N} inclusive. Regardless of how GridLayout is
 * configured, grid index {@code 0} is fixed to the leading edge of the
 * container and grid index {@code N} is fixed to its trailing edge
 * (after padding is taken into account).
 *
 * <h4>Row and Column Specs</h4>
 *
 * Children occupy one or more contiguous cells, as defined
 * by their {@link GridLayout.LayoutParams#rowSpec rowSpec} and
 * {@link GridLayout.LayoutParams#columnSpec columnSpec} layout parameters.
 * Each spec defines the set of rows or columns that are to be
 * occupied; and how children should be aligned within the resulting group of cells.
 * Although cells do not normally overlap in a GridLayout, GridLayout does
 * not prevent children being defined to occupy the same cell or group of cells.
 * In this case however, there is no guarantee that children will not themselves
 * overlap after the layout operation completes.
 *
 * <h4>Default Cell Assignment</h4>
 *
 * If a child does not specify the row and column indices of the cell it
 * wishes to occupy, GridLayout assigns cell locations automatically using its:
 * {@link GridLayout#setOrientation(int) orientation},
 * {@link GridLayout#setRowCount(int) rowCount} and
 * {@link GridLayout#setColumnCount(int) columnCount} properties.
 *
 * <h4>Space</h4>
 *
 * Space between children may be specified either by using instances of the
 * dedicated {@link Space} view or by setting the
 *
 * {@link ViewGroup.MarginLayoutParams#leftMargin leftMargin},
 * {@link ViewGroup.MarginLayoutParams#topMargin topMargin},
 * {@link ViewGroup.MarginLayoutParams#rightMargin rightMargin} and
 * {@link ViewGroup.MarginLayoutParams#bottomMargin bottomMargin}
 *
 * layout parameters. When the
 * {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins}
 * property is set, default margins around children are automatically
 * allocated based on the prevailing UI style guide for the platform.
 * Each of the margins so defined may be independently overridden by an assignment
 * to the appropriate layout parameter.
 * Default values will generally produce a reasonable spacing between components
 * but values may change between different releases of the platform.
 *
 * <h4>Excess Space Distribution</h4>
 *
 * GridLayout's distribution of excess space is based on <em>priority</em>
 * rather than <em>weight</em>.
 * <p>
 * A child's ability to stretch is inferred from the alignment properties of
 * its row and column groups (which are typically set by setting the
 * {@link LayoutParams#setGravity(int) gravity} property of the child's layout parameters).
 * If alignment was defined along a given axis then the component
 * is taken as <em>flexible</em> in that direction. If no alignment was set,
 * the component is instead assumed to be <em>inflexible</em>.
 * <p>
 * Multiple components in the same row or column group are
 * considered to act in <em>parallel</em>. Such a
 * group is flexible only if <em>all</em> of the components
 * within it are flexible. Row and column groups that sit either side of a common boundary
 * are instead considered to act in <em>series</em>. The composite group made of these two
 * elements is flexible if <em>one</em> of its elements is flexible.
 * <p>
 * To make a column stretch, make sure all of the components inside it define a
 * gravity. To prevent a column from stretching, ensure that one of the components
 * in the column does not define a gravity.
 * <p>
 * When the principle of flexibility does not provide complete disambiguation,
 * GridLayout's algorithms favour rows and columns that are closer to its <em>right</em>
 * and <em>bottom</em> edges.
 *
 * <h5>Limitations</h5>
 *
 * GridLayout does not provide support for the principle of <em>weight</em>, as defined in
 * {@link LinearLayout.LayoutParams#weight}. In general, it is not therefore possible
 * to configure a GridLayout to distribute excess space in non-trivial proportions between
 * multiple rows or columns.
 * <p>
 * Some common use-cases may nevertheless be accommodated as follows.
 * To place equal amounts of space around a component in a cell group;
 * use {@link #CENTER} alignment (or {@link LayoutParams#setGravity(int) gravity}).
 * For complete control over excess space distribution in a row or column;
 * use a {@link LinearLayout} subview to hold the components in the associated cell group.
 * When using either of these techniques, bear in mind that cell groups may be defined to overlap.
 * <p>
 * See {@link GridLayout.LayoutParams} for a full description of the
 * layout parameters used by GridLayout.
 *
 * @attr ref android.R.styleable#GridLayout_orientation
 * @attr ref android.R.styleable#GridLayout_rowCount
 * @attr ref android.R.styleable#GridLayout_columnCount
 * @attr ref android.R.styleable#GridLayout_useDefaultMargins
 * @attr ref android.R.styleable#GridLayout_rowOrderPreserved
 * @attr ref android.R.styleable#GridLayout_columnOrderPreserved
 */
public class GridLayout extends ViewGroup {

    // Public constants

    /**
     * The horizontal orientation.
     */
    public static final int HORIZONTAL = LinearLayout.HORIZONTAL;

    /**
     * The vertical orientation.
     */
    public static final int VERTICAL = LinearLayout.VERTICAL;

    /**
     * The constant used to indicate that a value is undefined.
     * Fields can use this value to indicate that their values
     * have not yet been set. Similarly, methods can return this value
     * to indicate that there is no suitable value that the implementation
     * can return.
     * The value used for the constant (currently {@link Integer#MIN_VALUE}) is
     * intended to avoid confusion between valid values whose sign may not be known.
     */
    public static final int UNDEFINED = Integer.MIN_VALUE;

    /**
     * This constant is an {@link #setAlignmentMode(int) alignmentMode}.
     * When the {@code alignmentMode} is set to {@link #ALIGN_BOUNDS}, alignment
     * is made between the edges of each component's raw
     * view boundary: i.e. the area delimited by the component's:
     * {@link android.view.View#getTop() top},
     * {@link android.view.View#getLeft() left},
     * {@link android.view.View#getBottom() bottom} and
     * {@link android.view.View#getRight() right} properties.
     * <p>
     * For example, when {@code GridLayout} is in {@link #ALIGN_BOUNDS} mode,
     * children that belong to a row group that uses {@link #TOP} alignment will
     * all return the same value when their {@link android.view.View#getTop()}
     * method is called.
     *
     * @see #setAlignmentMode(int)
     */
    public static final int ALIGN_BOUNDS = 0;

    /**
     * This constant is an {@link #setAlignmentMode(int) alignmentMode}.
     * When the {@code alignmentMode} is set to {@link #ALIGN_MARGINS},
     * the bounds of each view are extended outwards, according
     * to their margins, before the edges of the resulting rectangle are aligned.
     * <p>
     * For example, when {@code GridLayout} is in {@link #ALIGN_MARGINS} mode,
     * the quantity {@code top - layoutParams.topMargin} is the same for all children that
     * belong to a row group that uses {@link #TOP} alignment.
     *
     * @see #setAlignmentMode(int)
     */
    public static final int ALIGN_MARGINS = 1;

    // Misc constants

    static final String TAG = GridLayout.class.getName();
    static final boolean DEBUG = false;
    static final int PRF = 1;
    static final int MAX_SIZE = 100000;
    static final int DEFAULT_CONTAINER_MARGIN = 0;

    // Defaults

    private static final int DEFAULT_ORIENTATION = HORIZONTAL;
    private static final int DEFAULT_COUNT = UNDEFINED;
    private static final boolean DEFAULT_USE_DEFAULT_MARGINS = false;
    private static final boolean DEFAULT_ORDER_PRESERVED = true;
    private static final int DEFAULT_ALIGNMENT_MODE = ALIGN_MARGINS;

    // TypedArray indices

    private static final int ORIENTATION = R.styleable.GridLayout_orientation;
    private static final int ROW_COUNT = R.styleable.GridLayout_rowCount;
    private static final int COLUMN_COUNT = R.styleable.GridLayout_columnCount;
    private static final int USE_DEFAULT_MARGINS = R.styleable.GridLayout_useDefaultMargins;
    private static final int ALIGNMENT_MODE = R.styleable.GridLayout_alignmentMode;
    private static final int ROW_ORDER_PRESERVED = R.styleable.GridLayout_rowOrderPreserved;
    private static final int COLUMN_ORDER_PRESERVED = R.styleable.GridLayout_columnOrderPreserved;

    // Instance variables

    final Axis horizontalAxis = new Axis(true);
    final Axis verticalAxis = new Axis(false);
    boolean layoutParamsValid = false;
    int orientation = DEFAULT_ORIENTATION;
    boolean useDefaultMargins = DEFAULT_USE_DEFAULT_MARGINS;
    int alignmentMode = DEFAULT_ALIGNMENT_MODE;
    int defaultGap;

    // Constructors

    /**
     * {@inheritDoc}
     */
    public GridLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (DEBUG) {
            setWillNotDraw(false);
        }
        defaultGap = context.getResources().getDimensionPixelOffset(R.dimen.default_gap);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GridLayout);
        try {
            setRowCount(a.getInt(ROW_COUNT, DEFAULT_COUNT));
            setColumnCount(a.getInt(COLUMN_COUNT, DEFAULT_COUNT));
            setOrientation(a.getInt(ORIENTATION, DEFAULT_ORIENTATION));
            setUseDefaultMargins(a.getBoolean(USE_DEFAULT_MARGINS, DEFAULT_USE_DEFAULT_MARGINS));
            setAlignmentMode(a.getInt(ALIGNMENT_MODE, DEFAULT_ALIGNMENT_MODE));
            setRowOrderPreserved(a.getBoolean(ROW_ORDER_PRESERVED, DEFAULT_ORDER_PRESERVED));
            setColumnOrderPreserved(a.getBoolean(COLUMN_ORDER_PRESERVED, DEFAULT_ORDER_PRESERVED));
        } finally {
            a.recycle();
        }
    }

    /**
     * {@inheritDoc}
     */
    public GridLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * {@inheritDoc}
     */
    public GridLayout(Context context) {
        //noinspection NullableProblems
        this(context, null);
    }

    // Implementation

    /**
     * Returns the current orientation.
     *
     * @return either {@link #HORIZONTAL} or {@link #VERTICAL}
     *
     * @see #setOrientation(int)
     *
     * @attr ref android.R.styleable#GridLayout_orientation
     */
    public int getOrientation() {
        return orientation;
    }

    /**
     * Orientation is used only to generate default row/column indices when
     * they are not specified by a component's layout parameters.
     * <p>
     * The default value of this property is {@link #HORIZONTAL}.
     *
     * @param orientation either {@link #HORIZONTAL} or {@link #VERTICAL}
     *
     * @see #getOrientation()
     *
     * @attr ref android.R.styleable#GridLayout_orientation
     */
    public void setOrientation(int orientation) {
        if (this.orientation != orientation) {
            this.orientation = orientation;
            invalidateStructure();
            requestLayout();
        }
    }

    /**
     * Returns the current number of rows. This is either the last value that was set
     * with {@link #setRowCount(int)} or, if no such value was set, the maximum
     * value of each the upper bounds defined in {@link LayoutParams#rowSpec}.
     *
     * @return the current number of rows
     *
     * @see #setRowCount(int)
     * @see LayoutParams#rowSpec
     *
     * @attr ref android.R.styleable#GridLayout_rowCount
     */
    public int getRowCount() {
        return verticalAxis.getCount();
    }

    /**
     * RowCount is used only to generate default row/column indices when
     * they are not specified by a component's layout parameters.
     *
     * @param rowCount the number of rows
     *
     * @see #getRowCount()
     * @see LayoutParams#rowSpec
     *
     * @attr ref android.R.styleable#GridLayout_rowCount
     */
    public void setRowCount(int rowCount) {
        verticalAxis.setCount(rowCount);
        invalidateStructure();
        requestLayout();
    }

    /**
     * Returns the current number of columns. This is either the last value that was set
     * with {@link #setColumnCount(int)} or, if no such value was set, the maximum
     * value of each the upper bounds defined in {@link LayoutParams#columnSpec}.
     *
     * @return the current number of columns
     *
     * @see #setColumnCount(int)
     * @see LayoutParams#columnSpec
     *
     * @attr ref android.R.styleable#GridLayout_columnCount
     */
    public int getColumnCount() {
        return horizontalAxis.getCount();
    }

    /**
     * ColumnCount is used only to generate default column/column indices when
     * they are not specified by a component's layout parameters.
     *
     * @param columnCount the number of columns.
     *
     * @see #getColumnCount()
     * @see LayoutParams#columnSpec
     *
     * @attr ref android.R.styleable#GridLayout_columnCount
     */
    public void setColumnCount(int columnCount) {
        horizontalAxis.setCount(columnCount);
        invalidateStructure();
        requestLayout();
    }

    /**
     * Returns whether or not this GridLayout will allocate default margins when no
     * corresponding layout parameters are defined.
     *
     * @return {@code true} if default margins should be allocated
     *
     * @see #setUseDefaultMargins(boolean)
     *
     * @attr ref android.R.styleable#GridLayout_useDefaultMargins
     */
    public boolean getUseDefaultMargins() {
        return useDefaultMargins;
    }

    /**
     * When {@code true}, GridLayout allocates default margins around children
     * based on the child's visual characteristics. Each of the
     * margins so defined may be independently overridden by an assignment
     * to the appropriate layout parameter.
     * <p>
     * When {@code false}, the default value of all margins is zero.
     * <p>
     * When setting to {@code true}, consider setting the value of the
     * {@link #setAlignmentMode(int) alignmentMode}
     * property to {@link #ALIGN_BOUNDS}.
     * <p>
     * The default value of this property is {@code false}.
     *
     * @param useDefaultMargins use {@code true} to make GridLayout allocate default margins
     *
     * @see #getUseDefaultMargins()
     * @see #setAlignmentMode(int)
     *
     * @see MarginLayoutParams#leftMargin
     * @see MarginLayoutParams#topMargin
     * @see MarginLayoutParams#rightMargin
     * @see MarginLayoutParams#bottomMargin
     *
     * @attr ref android.R.styleable#GridLayout_useDefaultMargins
     */
    public void setUseDefaultMargins(boolean useDefaultMargins) {
        this.useDefaultMargins = useDefaultMargins;
        requestLayout();
    }

    /**
     * Returns the alignment mode.
     *
     * @return the alignment mode; either {@link #ALIGN_BOUNDS} or {@link #ALIGN_MARGINS}
     *
     * @see #ALIGN_BOUNDS
     * @see #ALIGN_MARGINS
     *
     * @see #setAlignmentMode(int)
     *
     * @attr ref android.R.styleable#GridLayout_alignmentMode
     */
    public int getAlignmentMode() {
        return alignmentMode;
    }

    /**
     * Sets the alignment mode to be used for all of the alignments between the
     * children of this container.
     * <p>
     * The default value of this property is {@link #ALIGN_MARGINS}.
     *
     * @param alignmentMode either {@link #ALIGN_BOUNDS} or {@link #ALIGN_MARGINS}
     *
     * @see #ALIGN_BOUNDS
     * @see #ALIGN_MARGINS
     *
     * @see #getAlignmentMode()
     *
     * @attr ref android.R.styleable#GridLayout_alignmentMode
     */
    public void setAlignmentMode(int alignmentMode) {
        this.alignmentMode = alignmentMode;
        requestLayout();
    }

    /**
     * Returns whether or not row boundaries are ordered by their grid indices.
     *
     * @return {@code true} if row boundaries must appear in the order of their indices,
     *         {@code false} otherwise
     *
     * @see #setRowOrderPreserved(boolean)
     *
     * @attr ref android.R.styleable#GridLayout_rowOrderPreserved
     */
    public boolean isRowOrderPreserved() {
        return verticalAxis.isOrderPreserved();
    }

    /**
     * When this property is {@code true}, GridLayout is forced to place the row boundaries
     * so that their associated grid indices are in ascending order in the view.
     * <p>
     * When this property is {@code false} GridLayout is at liberty to place the vertical row
     * boundaries in whatever order best fits the given constraints.
     * <p>
     * The default value of this property is {@code true}.

     * @param rowOrderPreserved {@code true} to force GridLayout to respect the order
     *        of row boundaries
     *
     * @see #isRowOrderPreserved()
     *
     * @attr ref android.R.styleable#GridLayout_rowOrderPreserved
     */
    public void setRowOrderPreserved(boolean rowOrderPreserved) {
        verticalAxis.setOrderPreserved(rowOrderPreserved);
        invalidateStructure();
        requestLayout();
    }

    /**
     * Returns whether or not column boundaries are ordered by their grid indices.
     *
     * @return {@code true} if column boundaries must appear in the order of their indices,
     *         {@code false} otherwise
     *
     * @see #setColumnOrderPreserved(boolean)
     *
     * @attr ref android.R.styleable#GridLayout_columnOrderPreserved
     */
    public boolean isColumnOrderPreserved() {
        return horizontalAxis.isOrderPreserved();
    }

    /**
     * When this property is {@code true}, GridLayout is forced to place the column boundaries
     * so that their associated grid indices are in ascending order in the view.
     * <p>
     * When this property is {@code false} GridLayout is at liberty to place the horizontal column
     * boundaries in whatever order best fits the given constraints.
     * <p>
     * The default value of this property is {@code true}.
     *
     * @param columnOrderPreserved use {@code true} to force GridLayout to respect the order
     *        of column boundaries.
     *
     * @see #isColumnOrderPreserved()
     *
     * @attr ref android.R.styleable#GridLayout_columnOrderPreserved
     */
    public void setColumnOrderPreserved(boolean columnOrderPreserved) {
        horizontalAxis.setOrderPreserved(columnOrderPreserved);
        invalidateStructure();
        requestLayout();
    }

    // Static utility methods

    static int max2(int[] a, int valueIfEmpty) {
        int result = valueIfEmpty;
        for (int i = 0, N = a.length; i < N; i++) {
            result = Math.max(result, a[i]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static <T> T[] append(T[] a, T[] b) {
        T[] result = (T[]) Array.newInstance(a.getClass().getComponentType(), a.length + b.length);
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    static Alignment getAlignment(int gravity, boolean horizontal) {
        int mask = horizontal ? HORIZONTAL_GRAVITY_MASK : VERTICAL_GRAVITY_MASK;
        int shift = horizontal ? AXIS_X_SHIFT : AXIS_Y_SHIFT;
        int flags = (gravity & mask) >> shift;
        switch (flags) {
            case (AXIS_SPECIFIED | AXIS_PULL_BEFORE):
                return LEADING;
            case (AXIS_SPECIFIED | AXIS_PULL_AFTER):
                return TRAILING;
            case (AXIS_SPECIFIED | AXIS_PULL_BEFORE | AXIS_PULL_AFTER):
                return FILL;
            case AXIS_SPECIFIED:
                return CENTER;
            default:
                return UNDEFINED_ALIGNMENT;
        }
    }

    /** @noinspection UnusedParameters*/
    private int getDefaultMargin(View c, boolean horizontal, boolean leading) {
        if (c.getClass() == Space.class) {
            return 0;
        }
        return defaultGap / 2;
    }

    private int getDefaultMargin(View c, boolean isAtEdge, boolean horizontal, boolean leading) {
        return isAtEdge ? DEFAULT_CONTAINER_MARGIN : getDefaultMargin(c, horizontal, leading);
    }

    private int getDefaultMarginValue(View c, LayoutParams p, boolean horizontal, boolean leading) {
        if (!useDefaultMargins) {
            return 0;
        }
        Spec spec = horizontal ? p.columnSpec : p.rowSpec;
        Axis axis = horizontal ? horizontalAxis : verticalAxis;
        Interval span = spec.span;
        boolean isAtEdge = leading ? (span.min == 0) : (span.max == axis.getCount());

        return getDefaultMargin(c, isAtEdge, horizontal, leading);
    }

    int getMargin1(View view, boolean horizontal, boolean leading) {
        LayoutParams lp = getLayoutParams(view);
        int margin = horizontal ?
                (leading ? lp.leftMargin : lp.rightMargin) :
                (leading ? lp.topMargin : lp.bottomMargin);
        return margin == UNDEFINED ? getDefaultMarginValue(view, lp, horizontal, leading) : margin;
    }

    private int getMargin(View view, boolean horizontal, boolean leading) {
        if (alignmentMode == ALIGN_MARGINS) {
            return getMargin1(view, horizontal, leading);
        } else {
            Axis axis = horizontal ? horizontalAxis : verticalAxis;
            int[] margins = leading ? axis.getLeadingMargins() : axis.getTrailingMargins();
            LayoutParams lp = getLayoutParams(view);
            Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
            int index = leading ? spec.span.min : spec.span.max;
            return margins[index];
        }
    }

    private int getTotalMargin(View child, boolean horizontal) {
        return getMargin(child, horizontal, true) + getMargin(child, horizontal, false);
    }

    private static boolean fits(int[] a, int value, int start, int end) {
        if (end > a.length) {
            return false;
        }
        for (int i = start; i < end; i++) {
            if (a[i] > value) {
                return false;
            }
        }
        return true;
    }

    private static void procrusteanFill(int[] a, int start, int end, int value) {
        int length = a.length;
        Arrays.fill(a, Math.min(start, length), Math.min(end, length), value);
    }

    private static void setCellGroup(LayoutParams lp, int row, int rowSpan, int col, int colSpan) {
        lp.setRowSpecSpan(new Interval(row, row + rowSpan));
        lp.setColumnSpecSpan(new Interval(col, col + colSpan));
    }

    // Logic to avert infinite loops by ensuring that the cells can be placed somewhere.
    private static int clip(Interval minorRange, boolean minorWasDefined, int count) {
        int size = minorRange.size();
        if (count == 0) {
            return size;
        }
        int min = minorWasDefined ? min(minorRange.min, count) : 0;
        return min(size, count - min);
    }

    // install default indices for cells that don't define them
    private void validateLayoutParams() {
        final boolean horizontal = (orientation == HORIZONTAL);
        final Axis axis = horizontal ? horizontalAxis : verticalAxis;
        final int count = (axis.definedCount != UNDEFINED) ? axis.definedCount : 0;

        int major = 0;
        int minor = 0;
        int[] maxSizes = new int[count];

        for (int i = 0, N = getChildCount(); i < N; i++) {
            LayoutParams lp = getLayoutParams1(getChildAt(i));

            final Spec majorSpec = horizontal ? lp.rowSpec : lp.columnSpec;
            final Interval majorRange = majorSpec.span;
            final boolean majorWasDefined = majorSpec.startDefined;
            final int majorSpan = majorRange.size();
            if (majorWasDefined) {
                major = majorRange.min;
            }

            final Spec minorSpec = horizontal ? lp.columnSpec : lp.rowSpec;
            final Interval minorRange = minorSpec.span;
            final boolean minorWasDefined = minorSpec.startDefined;
            final int minorSpan = clip(minorRange, minorWasDefined, count);
            if (minorWasDefined) {
                minor = minorRange.min;
            }

            if (count != 0) {
                // Find suitable row/col values when at least one is undefined.
                if (!majorWasDefined || !minorWasDefined) {
                    while (!fits(maxSizes, major, minor, minor + minorSpan)) {
                        if (minorWasDefined) {
                            major++;
                        } else {
                            if (minor + minorSpan <= count) {
                                minor++;
                            } else {
                                minor = 0;
                                major++;
                            }
                        }
                    }
                }
                procrusteanFill(maxSizes, minor, minor + minorSpan, major + majorSpan);
            }

            if (horizontal) {
                setCellGroup(lp, major, majorSpan, minor, minorSpan);
            } else {
                setCellGroup(lp, minor, minorSpan, major, majorSpan);
            }

            minor = minor + minorSpan;
        }
        invalidateStructure();
    }

    private void invalidateStructure() {
        layoutParamsValid = false;
        horizontalAxis.invalidateStructure();
        verticalAxis.invalidateStructure();
        // This can end up being done twice. Better twice than not at all.
        invalidateValues();
    }

    private void invalidateValues() {
        // Need null check because requestLayout() is called in View's initializer,
        // before we are set up.
        if (horizontalAxis != null && verticalAxis != null) {
            horizontalAxis.invalidateValues();
            verticalAxis.invalidateValues();
        }
    }

    private LayoutParams getLayoutParams1(View c) {
        return (LayoutParams) c.getLayoutParams();
    }

    final LayoutParams getLayoutParams(View c) {
        if (!layoutParamsValid) {
            validateLayoutParams();
            layoutParamsValid = true;
        }
        return getLayoutParams1(c);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    // Draw grid

    private void drawLine(Canvas graphics, int x1, int y1, int x2, int y2, Paint paint) {
        int dx = getPaddingLeft();
        int dy = getPaddingTop();
        graphics.drawLine(dx + x1, dy + y1, dx + x2, dy + y2, paint);
    }

    private static void drawRect(Canvas canvas, int x1, int y1, int x2, int y2, Paint paint) {
        canvas.drawRect(x1, y1, x2 - 1, y2 - 1, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (DEBUG) {
            int height = getHeight() - getPaddingTop() - getPaddingBottom();
            int width = getWidth() - getPaddingLeft() - getPaddingRight();

            Paint paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.argb(50, 255, 255, 255));

            int[] xs = horizontalAxis.locations;
            if (xs != null) {
                for (int i = 0, length = xs.length; i < length; i++) {
                    int x = xs[i];
                    drawLine(canvas, x, 0, x, height - 1, paint);
                }
            }

            int[] ys = verticalAxis.locations;
            if (ys != null) {
                for (int i = 0, length = ys.length; i < length; i++) {
                    int y = ys[i];
                    drawLine(canvas, 0, y, width - 1, y, paint);
                }
            }

            // Draw bounds
            paint.setColor(Color.BLUE);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                drawRect(canvas, c.getLeft(), c.getTop(), c.getRight(), c.getBottom(), paint);
            }

            // Draw margins
            paint.setColor(Color.MAGENTA);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                drawRect(canvas,
                        c.getLeft() - getMargin1(c, true, true),
                        c.getTop() - getMargin1(c, false, true),
                        c.getRight() + getMargin1(c, true, false),
                        c.getBottom() + getMargin1(c, false, false), paint);
            }
        }
    }

    // Add/remove

    /**
     * @hide
     */
    @Override
    protected void onViewAdded(View child) {
        super.onViewAdded(child);
        invalidateStructure();
    }

    /**
     * @hide
     */
    @Override
    protected void onViewRemoved(View child) {
        super.onViewRemoved(child);
        invalidateStructure();
    }

    /**
     * We need to call invalidateStructure() when a child's GONE flag changes state.
     * This implementation is a catch-all, invalidating on any change in the visibility flags.
     *
     * @hide
     */
    @Override
    protected void onChildVisibilityChanged(View child, int oldVisibility, int newVisibility) {
        super.onChildVisibilityChanged(child, oldVisibility, newVisibility);
        if (oldVisibility == GONE || newVisibility == GONE) {
            invalidateStructure();
        }
    }

    // Measurement

    final boolean isGone(View c) {
        return c.getVisibility() == View.GONE;
    }

    private void measureChildWithMargins2(View child, int parentWidthSpec, int parentHeightSpec,
                                          int childWidth, int childHeight) {
        int childWidthSpec = getChildMeasureSpec(parentWidthSpec,
                mPaddingLeft + mPaddingRight + getTotalMargin(child, true), childWidth);
        int childHeightSpec = getChildMeasureSpec(parentHeightSpec,
                mPaddingTop + mPaddingBottom + getTotalMargin(child, false), childHeight);
        child.measure(childWidthSpec, childHeightSpec);
    }

    private void measureChildrenWithMargins(int widthSpec, int heightSpec, boolean firstPass) {
        for (int i = 0, N = getChildCount(); i < N; i++) {
            View c = getChildAt(i);
            if (isGone(c)) continue;
            LayoutParams lp = getLayoutParams(c);
            if (firstPass) {
                measureChildWithMargins2(c, widthSpec, heightSpec, lp.width, lp.height);
            } else {
                boolean horizontal = (orientation == HORIZONTAL);
                Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
                if (spec.alignment == FILL) {
                    Interval span = spec.span;
                    Axis axis = horizontal ? horizontalAxis : verticalAxis;
                    int[] locations = axis.getLocations();
                    int cellSize = locations[span.max] - locations[span.min];
                    int viewSize = cellSize - getTotalMargin(c, horizontal);
                    if (horizontal) {
                        measureChildWithMargins2(c, widthSpec, heightSpec, viewSize, lp.height);
                    } else {
                        measureChildWithMargins2(c, widthSpec, heightSpec, lp.width, viewSize);
                    }
                }
            }
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        /** If we have been called by {@link View#measure(int, int)}, one of width or height
         *  is  likely to have changed. We must invalidate if so. */
        invalidateValues();

        measureChildrenWithMargins(widthSpec, heightSpec, true);

        int width, height;

        // Use the orientation property to decide which axis should be laid out first.
        if (orientation == HORIZONTAL) {
            width = horizontalAxis.getMeasure(widthSpec);
            measureChildrenWithMargins(widthSpec, heightSpec, false);
            height = verticalAxis.getMeasure(heightSpec);
        } else {
            height = verticalAxis.getMeasure(heightSpec);
            measureChildrenWithMargins(widthSpec, heightSpec, false);
            width = horizontalAxis.getMeasure(widthSpec);
        }

        int hPadding = getPaddingLeft() + getPaddingRight();
        int vPadding = getPaddingTop() + getPaddingBottom();

        int measuredWidth = Math.max(hPadding + width, getSuggestedMinimumWidth());
        int measuredHeight = Math.max(vPadding + height, getSuggestedMinimumHeight());

        setMeasuredDimension(
                resolveSizeAndState(measuredWidth, widthSpec, 0),
                resolveSizeAndState(measuredHeight, heightSpec, 0));
    }

    private int protect(int alignment) {
        return (alignment == UNDEFINED) ? 0 : alignment;
    }

    private int getMeasurement(View c, boolean horizontal) {
        return horizontal ? c.getMeasuredWidth() : c.getMeasuredHeight();
    }

    final int getMeasurementIncludingMargin(View c, boolean horizontal) {
        if (isGone(c)) {
            return 0;
        }
        return getMeasurement(c, horizontal) + getTotalMargin(c, horizontal);
    }

    @Override
    public void requestLayout() {
        super.requestLayout();
        invalidateValues();
    }

    final Alignment getAlignment(Alignment alignment, boolean horizontal) {
        return (alignment != UNDEFINED_ALIGNMENT) ? alignment :
                (horizontal ? LEFT : BASELINE);
    }

    // Layout container

    /**
     * {@inheritDoc}
     */
    /*
     The layout operation is implemented by delegating the heavy lifting to the
     to the mHorizontalAxis and mVerticalAxis instances of the internal Axis class.
     Together they compute the locations of the vertical and horizontal lines of
     the grid (respectively!).

     This method is then left with the simpler task of applying margins, gravity
     and sizing to each child view and then placing it in its cell.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int targetWidth = right - left;
        int targetHeight = bottom - top;

        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();

        horizontalAxis.layout(targetWidth - paddingLeft - paddingRight);
        verticalAxis.layout(targetHeight - paddingTop - paddingBottom);

        int[] hLocations = horizontalAxis.getLocations();
        int[] vLocations = verticalAxis.getLocations();

        for (int i = 0, N = getChildCount(); i < N; i++) {
            View c = getChildAt(i);
            if (isGone(c)) continue;
            LayoutParams lp = getLayoutParams(c);
            Spec columnSpec = lp.columnSpec;
            Spec rowSpec = lp.rowSpec;

            Interval colSpan = columnSpec.span;
            Interval rowSpan = rowSpec.span;

            int x1 = hLocations[colSpan.min];
            int y1 = vLocations[rowSpan.min];

            int x2 = hLocations[colSpan.max];
            int y2 = vLocations[rowSpan.max];

            int cellWidth = x2 - x1;
            int cellHeight = y2 - y1;

            int pWidth = getMeasurement(c, true);
            int pHeight = getMeasurement(c, false);

            Alignment hAlign = getAlignment(columnSpec.alignment, true);
            Alignment vAlign = getAlignment(rowSpec.alignment, false);

            int dx, dy;

            Bounds colBounds = horizontalAxis.getGroupBounds().getValue(i);
            Bounds rowBounds = verticalAxis.getGroupBounds().getValue(i);

            // Gravity offsets: the location of the alignment group relative to its cell group.
            //noinspection NullableProblems
            int c2ax = protect(hAlign.getAlignmentValue(null, cellWidth - colBounds.size(true)));
            //noinspection NullableProblems
            int c2ay = protect(vAlign.getAlignmentValue(null, cellHeight - rowBounds.size(true)));

            int leftMargin = getMargin(c, true, true);
            int topMargin = getMargin(c, false, true);
            int rightMargin = getMargin(c, true, false);
            int bottomMargin = getMargin(c, false, false);

            // Same calculation as getMeasurementIncludingMargin()
            int mWidth = leftMargin + pWidth + rightMargin;
            int mHeight = topMargin + pHeight + bottomMargin;

            // Alignment offsets: the location of the view relative to its alignment group.
            int a2vx = colBounds.getOffset(c, hAlign, mWidth);
            int a2vy = rowBounds.getOffset(c, vAlign, mHeight);

            dx = c2ax + a2vx + leftMargin;
            dy = c2ay + a2vy + topMargin;

            cellWidth -= leftMargin + rightMargin;
            cellHeight -= topMargin + bottomMargin;

            int type = PRF;
            int width = hAlign.getSizeInCell(c, pWidth, cellWidth, type);
            int height = vAlign.getSizeInCell(c, pHeight, cellHeight, type);

            int cx = paddingLeft + x1 + dx;
            int cy = paddingTop + y1 + dy;
            if (width != c.getMeasuredWidth() || height != c.getMeasuredHeight()) {
                c.measure(makeMeasureSpec(width, EXACTLY), makeMeasureSpec(height, EXACTLY));
            }
            c.layout(cx, cy, cx + width, cy + height);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(GridLayout.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(GridLayout.class.getName());
    }

    // Inner classes

    /*
     This internal class houses the algorithm for computing the locations of grid lines;
     along either the horizontal or vertical axis. A GridLayout uses two instances of this class -
     distinguished by the "horizontal" flag which is true for the horizontal axis and false
     for the vertical one.
     */
    final class Axis {
        private static final int NEW = 0;
        private static final int PENDING = 1;
        private static final int COMPLETE = 2;

        public final boolean horizontal;

        public int definedCount = UNDEFINED;
        private int maxIndex = UNDEFINED;

        PackedMap<Spec, Bounds> groupBounds;
        public boolean groupBoundsValid = false;

        PackedMap<Interval, MutableInt> forwardLinks;
        public boolean forwardLinksValid = false;

        PackedMap<Interval, MutableInt> backwardLinks;
        public boolean backwardLinksValid = false;

        public int[] leadingMargins;
        public boolean leadingMarginsValid = false;

        public int[] trailingMargins;
        public boolean trailingMarginsValid = false;

        public Arc[] arcs;
        public boolean arcsValid = false;

        public int[] locations;
        public boolean locationsValid = false;

        boolean orderPreserved = DEFAULT_ORDER_PRESERVED;

        private MutableInt parentMin = new MutableInt(0);
        private MutableInt parentMax = new MutableInt(-MAX_SIZE);

        private Axis(boolean horizontal) {
            this.horizontal = horizontal;
        }

        private int calculateMaxIndex() {
            // the number Integer.MIN_VALUE + 1 comes up in undefined cells
            int result = -1;
            for (int i = 0, N = getChildCount(); i < N; i++) {
                View c = getChildAt(i);
                LayoutParams params = getLayoutParams(c);
                Spec spec = horizontal ? params.columnSpec : params.rowSpec;
                Interval span = spec.span;
                result = max(result, span.min);
                result = max(result, span.max);
            }
            return result == -1 ? UNDEFINED : result;
        }

        private int getMaxIndex() {
            if (maxIndex == UNDEFINED) {
                maxIndex = max(0, calculateMaxIndex()); // use zero when there are no children
            }
            return maxIndex;
        }

        public int getCount() {
            return max(definedCount, getMaxIndex());
        }

        public void setCount(int count) {
            this.definedCount = count;
        }

        public boolean isOrderPreserved() {
            return orderPreserved;
        }

        public void setOrderPreserved(boolean orderPreserved) {
            this.orderPreserved = orderPreserved;
            invalidateStructure();
        }

        private PackedMap<Spec, Bounds> createGroupBounds() {
            Assoc<Spec, Bounds> assoc = Assoc.of(Spec.class, Bounds.class);
            for (int i = 0, N = getChildCount(); i < N; i++) {
                View c = getChildAt(i);
                LayoutParams lp = getLayoutParams(c);
                Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
                Bounds bounds = getAlignment(spec.alignment, horizontal).getBounds();
                assoc.put(spec, bounds);
            }
            return assoc.pack();
        }

        private void computeGroupBounds() {
            Bounds[] values = groupBounds.values;
            for (int i = 0; i < values.length; i++) {
                values[i].reset();
            }
            for (int i = 0, N = getChildCount(); i < N; i++) {
                View c = getChildAt(i);
                LayoutParams lp = getLayoutParams(c);
                Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
                groupBounds.getValue(i).include(c, spec, GridLayout.this, this);
            }
        }

        public PackedMap<Spec, Bounds> getGroupBounds() {
            if (groupBounds == null) {
                groupBounds = createGroupBounds();
            }
            if (!groupBoundsValid) {
                computeGroupBounds();
                groupBoundsValid = true;
            }
            return groupBounds;
        }

        // Add values computed by alignment - taking the max of all alignments in each span
        private PackedMap<Interval, MutableInt> createLinks(boolean min) {
            Assoc<Interval, MutableInt> result = Assoc.of(Interval.class, MutableInt.class);
            Spec[] keys = getGroupBounds().keys;
            for (int i = 0, N = keys.length; i < N; i++) {
                Interval span = min ? keys[i].span : keys[i].span.inverse();
                result.put(span, new MutableInt());
            }
            return result.pack();
        }

        private void computeLinks(PackedMap<Interval, MutableInt> links, boolean min) {
            MutableInt[] spans = links.values;
            for (int i = 0; i < spans.length; i++) {
                spans[i].reset();
            }

            // Use getter to trigger a re-evaluation
            Bounds[] bounds = getGroupBounds().values;
            for (int i = 0; i < bounds.length; i++) {
                int size = bounds[i].size(min);
                MutableInt valueHolder = links.getValue(i);
                // this effectively takes the max() of the minima and the min() of the maxima
                valueHolder.value = max(valueHolder.value, min ? size : -size);
            }
        }

        private PackedMap<Interval, MutableInt> getForwardLinks() {
            if (forwardLinks == null) {
                forwardLinks = createLinks(true);
            }
            if (!forwardLinksValid) {
                computeLinks(forwardLinks, true);
                forwardLinksValid = true;
            }
            return forwardLinks;
        }

        private PackedMap<Interval, MutableInt> getBackwardLinks() {
            if (backwardLinks == null) {
                backwardLinks = createLinks(false);
            }
            if (!backwardLinksValid) {
                computeLinks(backwardLinks, false);
                backwardLinksValid = true;
            }
            return backwardLinks;
        }

        private void include(List<Arc> arcs, Interval key, MutableInt size,
                             boolean ignoreIfAlreadyPresent) {
            /*
            Remove self referential links.
            These appear:
                . as parental constraints when GridLayout has no children
                . when components have been marked as GONE
            */
            if (key.size() == 0) {
                return;
            }
            // this bit below should really be computed outside here -
            // its just to stop default (row/col > 0) constraints obliterating valid entries
            if (ignoreIfAlreadyPresent) {
                for (Arc arc : arcs) {
                    Interval span = arc.span;
                    if (span.equals(key)) {
                        return;
                    }
                }
            }
            arcs.add(new Arc(key, size));
        }

        private void include(List<Arc> arcs, Interval key, MutableInt size) {
            include(arcs, key, size, true);
        }

        // Group arcs by their first vertex, returning an array of arrays.
        // This is linear in the number of arcs.
        Arc[][] groupArcsByFirstVertex(Arc[] arcs) {
            int N = getCount() + 1; // the number of vertices
            Arc[][] result = new Arc[N][];
            int[] sizes = new int[N];
            for (Arc arc : arcs) {
                sizes[arc.span.min]++;
            }
            for (int i = 0; i < sizes.length; i++) {
                result[i] = new Arc[sizes[i]];
            }
            // reuse the sizes array to hold the current last elements as we insert each arc
            Arrays.fill(sizes, 0);
            for (Arc arc : arcs) {
                int i = arc.span.min;
                result[i][sizes[i]++] = arc;
            }

            return result;
        }

        private Arc[] topologicalSort(final Arc[] arcs) {
            return new Object() {
                Arc[] result = new Arc[arcs.length];
                int cursor = result.length - 1;
                Arc[][] arcsByVertex = groupArcsByFirstVertex(arcs);
                int[] visited = new int[getCount() + 1];

                void walk(int loc) {
                    switch (visited[loc]) {
                        case NEW: {
                            visited[loc] = PENDING;
                            for (Arc arc : arcsByVertex[loc]) {
                                walk(arc.span.max);
                                result[cursor--] = arc;
                            }
                            visited[loc] = COMPLETE;
                            break;
                        }
                        case PENDING: {
                            assert false;
                            break;
                        }
                        case COMPLETE: {
                            break;
                        }
                    }
                }

                Arc[] sort() {
                    for (int loc = 0, N = arcsByVertex.length; loc < N; loc++) {
                        walk(loc);
                    }
                    assert cursor == -1;
                    return result;
                }
            }.sort();
        }

        private Arc[] topologicalSort(List<Arc> arcs) {
            return topologicalSort(arcs.toArray(new Arc[arcs.size()]));
        }

        private void addComponentSizes(List<Arc> result, PackedMap<Interval, MutableInt> links) {
            for (int i = 0; i < links.keys.length; i++) {
                Interval key = links.keys[i];
                include(result, key, links.values[i], false);
            }
        }

        private Arc[] createArcs() {
            List<Arc> mins = new ArrayList<Arc>();
            List<Arc> maxs = new ArrayList<Arc>();

            // Add the minimum values from the components.
            addComponentSizes(mins, getForwardLinks());
            // Add the maximum values from the components.
            addComponentSizes(maxs, getBackwardLinks());

            // Add ordering constraints to prevent row/col sizes from going negative
            if (orderPreserved) {
                // Add a constraint for every row/col
                for (int i = 0; i < getCount(); i++) {
                    include(mins, new Interval(i, i + 1), new MutableInt(0));
                }
            }

            // Add the container constraints. Use the version of include that allows
            // duplicate entries in case a child spans the entire grid.
            int N = getCount();
            include(mins, new Interval(0, N), parentMin, false);
            include(maxs, new Interval(N, 0), parentMax, false);

            // Sort
            Arc[] sMins = topologicalSort(mins);
            Arc[] sMaxs = topologicalSort(maxs);

            return append(sMins, sMaxs);
        }

        private void computeArcs() {
            // getting the links validates the values that are shared by the arc list
            getForwardLinks();
            getBackwardLinks();
        }

        public Arc[] getArcs() {
            if (arcs == null) {
                arcs = createArcs();
            }
            if (!arcsValid) {
                computeArcs();
                arcsValid = true;
            }
            return arcs;
        }

        private boolean relax(int[] locations, Arc entry) {
            if (!entry.valid) {
                return false;
            }
            Interval span = entry.span;
            int u = span.min;
            int v = span.max;
            int value = entry.value.value;
            int candidate = locations[u] + value;
            if (candidate > locations[v]) {
                locations[v] = candidate;
                return true;
            }
            return false;
        }

        private void init(int[] locations) {
            Arrays.fill(locations, 0);
        }

        private String arcsToString(List<Arc> arcs) {
            String var = horizontal ? "x" : "y";
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (Arc arc : arcs) {
                if (first) {
                    first = false;
                } else {
                    result = result.append(", ");
                }
                int src = arc.span.min;
                int dst = arc.span.max;
                int value = arc.value.value;
                result.append((src < dst) ?
                        var + dst + " - " + var + src + " > " + value :
                        var + src + " - " + var + dst + " < " + -value);

            }
            return result.toString();
        }

        private void logError(String axisName, Arc[] arcs, boolean[] culprits0) {
            List<Arc> culprits = new ArrayList<Arc>();
            List<Arc> removed = new ArrayList<Arc>();
            for (int c = 0; c < arcs.length; c++) {
                Arc arc = arcs[c];
                if (culprits0[c]) {
                    culprits.add(arc);
                }
                if (!arc.valid) {
                    removed.add(arc);
                }
            }
            Log.d(TAG, axisName + " constraints: " + arcsToString(culprits) + " are inconsistent; "
                    + "permanently removing: " + arcsToString(removed) + ". ");
        }

        /*
        Bellman-Ford variant - modified to reduce typical running time from O(N^2) to O(N)

        GridLayout converts its requirements into a system of linear constraints of the
        form:

        x[i] - x[j] < a[k]

        Where the x[i] are variables and the a[k] are constants.

        For example, if the variables were instead labeled x, y, z we might have:

            x - y < 17
            y - z < 23
            z - x < 42

        This is a special case of the Linear Programming problem that is, in turn,
        equivalent to the single-source shortest paths problem on a digraph, for
        which the O(n^2) Bellman-Ford algorithm the most commonly used general solution.

        Other algorithms are faster in the case where no arcs have negative weights
        but allowing negative weights turns out to be the same as accommodating maximum
        size requirements as well as minimum ones.

        Bellman-Ford works by iteratively 'relaxing' constraints over all nodes (an O(N)
        process) and performing this step N times. Proof of correctness hinges on the
        fact that there can be no negative weight chains of length > N - unless a
        'negative weight loop' exists. The algorithm catches this case in a final
        checking phase that reports failure.

        By topologically sorting the nodes and checking this condition at each step
        typical layout problems complete after the first iteration and the algorithm
        completes in O(N) steps with very low constants.
        */
        private void solve(Arc[] arcs, int[] locations) {
            String axisName = horizontal ? "horizontal" : "vertical";
            int N = getCount() + 1; // The number of vertices is the number of columns/rows + 1.
            boolean[] originalCulprits = null;

            for (int p = 0; p < arcs.length; p++) {
                init(locations);

                // We take one extra pass over traditional Bellman-Ford (and omit their final step)
                for (int i = 0; i < N; i++) {
                    boolean changed = false;
                    for (int j = 0, length = arcs.length; j < length; j++) {
                        changed |= relax(locations, arcs[j]);
                    }
                    if (!changed) {
                        if (originalCulprits != null) {
                            logError(axisName, arcs, originalCulprits);
                        }
                        return;
                    }
                }

                boolean[] culprits = new boolean[arcs.length];
                for (int i = 0; i < N; i++) {
                    for (int j = 0, length = arcs.length; j < length; j++) {
                        culprits[j] |= relax(locations, arcs[j]);
                    }
                }

                if (p == 0) {
                    originalCulprits = culprits;
                }

                for (int i = 0; i < arcs.length; i++) {
                    if (culprits[i]) {
                        Arc arc = arcs[i];
                        // Only remove max values, min values alone cannot be inconsistent
                        if (arc.span.min < arc.span.max) {
                            continue;
                        }
                        arc.valid = false;
                        break;
                    }
                }
            }
        }

        private void computeMargins(boolean leading) {
            int[] margins = leading ? leadingMargins : trailingMargins;
            for (int i = 0, N = getChildCount(); i < N; i++) {
                View c = getChildAt(i);
                if (isGone(c)) continue;
                LayoutParams lp = getLayoutParams(c);
                Spec spec = horizontal ? lp.columnSpec : lp.rowSpec;
                Interval span = spec.span;
                int index = leading ? span.min : span.max;
                margins[index] = max(margins[index], getMargin1(c, horizontal, leading));
            }
        }

        // External entry points

        public int[] getLeadingMargins() {
            if (leadingMargins == null) {
                leadingMargins = new int[getCount() + 1];
            }
            if (!leadingMarginsValid) {
                computeMargins(true);
                leadingMarginsValid = true;
            }
            return leadingMargins;
        }

        public int[] getTrailingMargins() {
            if (trailingMargins == null) {
                trailingMargins = new int[getCount() + 1];
            }
            if (!trailingMarginsValid) {
                computeMargins(false);
                trailingMarginsValid = true;
            }
            return trailingMargins;
        }

        private void computeLocations(int[] a) {
            solve(getArcs(), a);
            if (!orderPreserved) {
                // Solve returns the smallest solution to the constraint system for which all
                // values are positive. One value is therefore zero - though if the row/col
                // order is not preserved this may not be the first vertex. For consistency,
                // translate all the values so that they measure the distance from a[0]; the
                // leading edge of the parent. After this transformation some values may be
                // negative.
                int a0 = a[0];
                for (int i = 0, N = a.length; i < N; i++) {
                    a[i] = a[i] - a0;
                }
            }
        }

        public int[] getLocations() {
            if (locations == null) {
                int N = getCount() + 1;
                locations = new int[N];
            }
            if (!locationsValid) {
                computeLocations(locations);
                locationsValid = true;
            }
            return locations;
        }

        private int size(int[] locations) {
            // The parental edges are attached to vertices 0 and N - even when order is not
            // being preserved and other vertices fall outside this range. Measure the distance
            // between vertices 0 and N, assuming that locations[0] = 0.
            return locations[getCount()];
        }

        private void setParentConstraints(int min, int max) {
            parentMin.value = min;
            parentMax.value = -max;
            locationsValid = false;
        }

        private int getMeasure(int min, int max) {
            setParentConstraints(min, max);
            return size(getLocations());
        }

        public int getMeasure(int measureSpec) {
            int mode = MeasureSpec.getMode(measureSpec);
            int size = MeasureSpec.getSize(measureSpec);
            switch (mode) {
                case MeasureSpec.UNSPECIFIED: {
                    return getMeasure(0, MAX_SIZE);
                }
                case MeasureSpec.EXACTLY: {
                    return getMeasure(size, size);
                }
                case MeasureSpec.AT_MOST: {
                    return getMeasure(0, size);
                }
                default: {
                    assert false;
                    return 0;
                }
            }
        }

        public void layout(int size) {
            setParentConstraints(size, size);
            getLocations();
        }

        public void invalidateStructure() {
            maxIndex = UNDEFINED;

            groupBounds = null;
            forwardLinks = null;
            backwardLinks = null;

            leadingMargins = null;
            trailingMargins = null;
            arcs = null;

            locations = null;

            invalidateValues();
        }

        public void invalidateValues() {
            groupBoundsValid = false;
            forwardLinksValid = false;
            backwardLinksValid = false;

            leadingMarginsValid = false;
            trailingMarginsValid = false;
            arcsValid = false;

            locationsValid = false;
        }
    }

    /**
     * Layout information associated with each of the children of a GridLayout.
     * <p>
     * GridLayout supports both row and column spanning and arbitrary forms of alignment within
     * each cell group. The fundamental parameters associated with each cell group are
     * gathered into their vertical and horizontal components and stored
     * in the {@link #rowSpec} and {@link #columnSpec} layout parameters.
     * {@link android.widget.GridLayout.Spec Specs} are immutable structures
     * and may be shared between the layout parameters of different children.
     * <p>
     * The row and column specs contain the leading and trailing indices along each axis
     * and together specify the four grid indices that delimit the cells of this cell group.
     * <p>
     * The  alignment properties of the row and column specs together specify
     * both aspects of alignment within the cell group. It is also possible to specify a child's
     * alignment within its cell group by using the {@link GridLayout.LayoutParams#setGravity(int)}
     * method.
     *
     * <h4>WRAP_CONTENT and MATCH_PARENT</h4>
     *
     * Because the default values of the {@link #width} and {@link #height}
     * properties are both {@link #WRAP_CONTENT}, this value never needs to be explicitly
     * declared in the layout parameters of GridLayout's children. In addition,
     * GridLayout does not distinguish the special size value {@link #MATCH_PARENT} from
     * {@link #WRAP_CONTENT}. A component's ability to expand to the size of the parent is
     * instead controlled by the principle of <em>flexibility</em>,
     * as discussed in {@link GridLayout}.
     *
     * <h4>Summary</h4>
     *
     * You should not need to use either of the special size values:
     * {@code WRAP_CONTENT} or {@code MATCH_PARENT} when configuring the children of
     * a GridLayout.
     *
     * <h4>Default values</h4>
     *
     * <ul>
     *     <li>{@link #width} = {@link #WRAP_CONTENT}</li>
     *     <li>{@link #height} = {@link #WRAP_CONTENT}</li>
     *     <li>{@link #topMargin} = 0 when
     *          {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
     *          {@code false}; otherwise {@link #UNDEFINED}, to
     *          indicate that a default value should be computed on demand. </li>
     *     <li>{@link #leftMargin} = 0 when
     *          {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
     *          {@code false}; otherwise {@link #UNDEFINED}, to
     *          indicate that a default value should be computed on demand. </li>
     *     <li>{@link #bottomMargin} = 0 when
     *          {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
     *          {@code false}; otherwise {@link #UNDEFINED}, to
     *          indicate that a default value should be computed on demand. </li>
     *     <li>{@link #rightMargin} = 0 when
     *          {@link GridLayout#setUseDefaultMargins(boolean) useDefaultMargins} is
     *          {@code false}; otherwise {@link #UNDEFINED}, to
     *          indicate that a default value should be computed on demand. </li>
     *     <li>{@link #rowSpec}<code>.row</code> = {@link #UNDEFINED} </li>
     *     <li>{@link #rowSpec}<code>.rowSpan</code> = 1 </li>
     *     <li>{@link #rowSpec}<code>.alignment</code> = {@link #BASELINE} </li>
     *     <li>{@link #columnSpec}<code>.column</code> = {@link #UNDEFINED} </li>
     *     <li>{@link #columnSpec}<code>.columnSpan</code> = 1 </li>
     *     <li>{@link #columnSpec}<code>.alignment</code> = {@link #LEFT} </li>
     * </ul>
     *
     * See {@link GridLayout} for a more complete description of the conventions
     * used by GridLayout in the interpretation of the properties of this class.
     *
     * @attr ref android.R.styleable#GridLayout_Layout_layout_row
     * @attr ref android.R.styleable#GridLayout_Layout_layout_rowSpan
     * @attr ref android.R.styleable#GridLayout_Layout_layout_column
     * @attr ref android.R.styleable#GridLayout_Layout_layout_columnSpan
     * @attr ref android.R.styleable#GridLayout_Layout_layout_gravity
     */
    public static class LayoutParams extends MarginLayoutParams {

        // Default values

        private static final int DEFAULT_WIDTH = WRAP_CONTENT;
        private static final int DEFAULT_HEIGHT = WRAP_CONTENT;
        private static final int DEFAULT_MARGIN = UNDEFINED;
        private static final int DEFAULT_ROW = UNDEFINED;
        private static final int DEFAULT_COLUMN = UNDEFINED;
        private static final Interval DEFAULT_SPAN = new Interval(UNDEFINED, UNDEFINED + 1);
        private static final int DEFAULT_SPAN_SIZE = DEFAULT_SPAN.size();

        // TypedArray indices

        private static final int MARGIN = R.styleable.ViewGroup_MarginLayout_layout_margin;
        private static final int LEFT_MARGIN = R.styleable.ViewGroup_MarginLayout_layout_marginLeft;
        private static final int TOP_MARGIN = R.styleable.ViewGroup_MarginLayout_layout_marginTop;
        private static final int RIGHT_MARGIN =
                R.styleable.ViewGroup_MarginLayout_layout_marginRight;
        private static final int BOTTOM_MARGIN =
                R.styleable.ViewGroup_MarginLayout_layout_marginBottom;

        private static final int COLUMN = R.styleable.GridLayout_Layout_layout_column;
        private static final int COLUMN_SPAN = R.styleable.GridLayout_Layout_layout_columnSpan;

        private static final int ROW = R.styleable.GridLayout_Layout_layout_row;
        private static final int ROW_SPAN = R.styleable.GridLayout_Layout_layout_rowSpan;

        private static final int GRAVITY = R.styleable.GridLayout_Layout_layout_gravity;

        // Instance variables

        /**
         * The spec that defines the vertical characteristics of the cell group
         * described by these layout parameters.
         */
        public Spec rowSpec = Spec.UNDEFINED;

        /**
         * The spec that defines the horizontal characteristics of the cell group
         * described by these layout parameters.
         */
        public Spec columnSpec = Spec.UNDEFINED;

        // Constructors

        private LayoutParams(
                int width, int height,
                int left, int top, int right, int bottom,
                Spec rowSpec, Spec columnSpec) {
            super(width, height);
            setMargins(left, top, right, bottom);
            this.rowSpec = rowSpec;
            this.columnSpec = columnSpec;
        }

        /**
         * Constructs a new LayoutParams instance for this <code>rowSpec</code>
         * and <code>columnSpec</code>. All other fields are initialized with
         * default values as defined in {@link LayoutParams}.
         *
         * @param rowSpec    the rowSpec
         * @param columnSpec the columnSpec
         */
        public LayoutParams(Spec rowSpec, Spec columnSpec) {
            this(DEFAULT_WIDTH, DEFAULT_HEIGHT,
                    DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN,
                    rowSpec, columnSpec);
        }

        /**
         * Constructs a new LayoutParams with default values as defined in {@link LayoutParams}.
         */
        public LayoutParams() {
            this(Spec.UNDEFINED, Spec.UNDEFINED);
        }

        // Copying constructors

        /**
         * {@inheritDoc}
         */
        public LayoutParams(ViewGroup.LayoutParams params) {
            super(params);
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(MarginLayoutParams params) {
            super(params);
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(LayoutParams that) {
            super(that);
            this.rowSpec = that.rowSpec;
            this.columnSpec = that.columnSpec;
        }

        // AttributeSet constructors

        /**
         * {@inheritDoc}
         *
         * Values not defined in the attribute set take the default values
         * defined in {@link LayoutParams}.
         */
        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);
            reInitSuper(context, attrs);
            init(context, attrs);
        }

        // Implementation

        // Reinitialise the margins using a different default policy than MarginLayoutParams.
        // Here we use the value UNDEFINED (as distinct from zero) to represent the undefined state
        // so that a layout manager default can be accessed post set up. We need this as, at the
        // point of installation, we do not know how many rows/cols there are and therefore
        // which elements are positioned next to the container's trailing edges. We need to
        // know this as margins around the container's boundary should have different
        // defaults to those between peers.

        // This method could be parametrized and moved into MarginLayout.
        private void reInitSuper(Context context, AttributeSet attrs) {
            TypedArray a =
                    context.obtainStyledAttributes(attrs, R.styleable.ViewGroup_MarginLayout);
            try {
                int margin = a.getDimensionPixelSize(MARGIN, DEFAULT_MARGIN);

                this.leftMargin = a.getDimensionPixelSize(LEFT_MARGIN, margin);
                this.topMargin = a.getDimensionPixelSize(TOP_MARGIN, margin);
                this.rightMargin = a.getDimensionPixelSize(RIGHT_MARGIN, margin);
                this.bottomMargin = a.getDimensionPixelSize(BOTTOM_MARGIN, margin);
            } finally {
                a.recycle();
            }
        }

        private void init(Context context, AttributeSet attrs) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GridLayout_Layout);
            try {
                int gravity = a.getInt(GRAVITY, Gravity.NO_GRAVITY);

                int column = a.getInt(COLUMN, DEFAULT_COLUMN);
                int colSpan = a.getInt(COLUMN_SPAN, DEFAULT_SPAN_SIZE);
                this.columnSpec = spec(column, colSpan, getAlignment(gravity, true));

                int row = a.getInt(ROW, DEFAULT_ROW);
                int rowSpan = a.getInt(ROW_SPAN, DEFAULT_SPAN_SIZE);
                this.rowSpec = spec(row, rowSpan, getAlignment(gravity, false));
            } finally {
                a.recycle();
            }
        }

        /**
         * Describes how the child views are positioned. Default is {@code LEFT | BASELINE}.
         * See {@link android.view.Gravity}.
         *
         * @param gravity the new gravity value
         *
         * @attr ref android.R.styleable#GridLayout_Layout_layout_gravity
         */
        public void setGravity(int gravity) {
            rowSpec = rowSpec.copyWriteAlignment(getAlignment(gravity, false));
            columnSpec = columnSpec.copyWriteAlignment(getAlignment(gravity, true));
        }

        @Override
        protected void setBaseAttributes(TypedArray attributes, int widthAttr, int heightAttr) {
            this.width = attributes.getLayoutDimension(widthAttr, DEFAULT_WIDTH);
            this.height = attributes.getLayoutDimension(heightAttr, DEFAULT_HEIGHT);
        }

        final void setRowSpecSpan(Interval span) {
            rowSpec = rowSpec.copyWriteSpan(span);
        }

        final void setColumnSpecSpan(Interval span) {
            columnSpec = columnSpec.copyWriteSpan(span);
        }
    }

    /*
    In place of a HashMap from span to Int, use an array of key/value pairs - stored in Arcs.
    Add the mutables completesCycle flag to avoid creating another hash table for detecting cycles.
     */
    final static class Arc {
        public final Interval span;
        public final MutableInt value;
        public boolean valid = true;

        public Arc(Interval span, MutableInt value) {
            this.span = span;
            this.value = value;
        }

        @Override
        public String toString() {
            return span + " " + (!valid ? "+>" : "->") + " " + value;
        }
    }

    // A mutable Integer - used to avoid heap allocation during the layout operation

    final static class MutableInt {
        public int value;

        public MutableInt() {
            reset();
        }

        public MutableInt(int value) {
            this.value = value;
        }

        public void reset() {
            value = Integer.MIN_VALUE;
        }

        @Override
        public String toString() {
            return Integer.toString(value);
        }
    }

    final static class Assoc<K, V> extends ArrayList<Pair<K, V>> {
        private final Class<K> keyType;
        private final Class<V> valueType;

        private Assoc(Class<K> keyType, Class<V> valueType) {
            this.keyType = keyType;
            this.valueType = valueType;
        }

        public static <K, V> Assoc<K, V> of(Class<K> keyType, Class<V> valueType) {
            return new Assoc<K, V>(keyType, valueType);
        }

        public void put(K key, V value) {
            add(Pair.create(key, value));
        }

        @SuppressWarnings(value = "unchecked")
        public PackedMap<K, V> pack() {
            int N = size();
            K[] keys = (K[]) Array.newInstance(keyType, N);
            V[] values = (V[]) Array.newInstance(valueType, N);
            for (int i = 0; i < N; i++) {
                keys[i] = get(i).first;
                values[i] = get(i).second;
            }
            return new PackedMap<K, V>(keys, values);
        }
    }

    /*
    This data structure is used in place of a Map where we have an index that refers to the order
    in which each key/value pairs were added to the map. In this case we store keys and values
    in arrays of a length that is equal to the number of unique keys. We also maintain an
    array of indexes from insertion order to the compacted arrays of keys and values.

    Note that behavior differs from that of a LinkedHashMap in that repeated entries
    *do* get added multiples times. So the length of index is equals to the number of
    items added.

    This is useful in the GridLayout class where we can rely on the order of children not
    changing during layout - to use integer-based lookup for our internal structures
    rather than using (and storing) an implementation of Map<Key, ?>.
     */
    @SuppressWarnings(value = "unchecked")
    final static class PackedMap<K, V> {
        public final int[] index;
        public final K[] keys;
        public final V[] values;

        private PackedMap(K[] keys, V[] values) {
            this.index = createIndex(keys);

            this.keys = compact(keys, index);
            this.values = compact(values, index);
        }

        public V getValue(int i) {
            return values[index[i]];
        }

        private static <K> int[] createIndex(K[] keys) {
            int size = keys.length;
            int[] result = new int[size];

            Map<K, Integer> keyToIndex = new HashMap<K, Integer>();
            for (int i = 0; i < size; i++) {
                K key = keys[i];
                Integer index = keyToIndex.get(key);
                if (index == null) {
                    index = keyToIndex.size();
                    keyToIndex.put(key, index);
                }
                result[i] = index;
            }
            return result;
        }

        /*
        Create a compact array of keys or values using the supplied index.
         */
        private static <K> K[] compact(K[] a, int[] index) {
            int size = a.length;
            Class<?> componentType = a.getClass().getComponentType();
            K[] result = (K[]) Array.newInstance(componentType, max2(index, -1) + 1);

            // this overwrite duplicates, retaining the last equivalent entry
            for (int i = 0; i < size; i++) {
                result[index[i]] = a[i];
            }
            return result;
        }
    }

    /*
    For each group (with a given alignment) we need to store the amount of space required
    before the alignment point and the amount of space required after it. One side of this
    calculation is always 0 for LEADING and TRAILING alignments but we don't make use of this.
    For CENTER and BASELINE alignments both sides are needed and in the BASELINE case no
    simple optimisations are possible.

    The general algorithm therefore is to create a Map (actually a PackedMap) from
    group to Bounds and to loop through all Views in the group taking the maximum
    of the values for each View.
    */
    static class Bounds {
        public int before;
        public int after;
        public int flexibility; // we're flexible iff all included specs are flexible

        private Bounds() {
            reset();
        }

        protected void reset() {
            before = Integer.MIN_VALUE;
            after = Integer.MIN_VALUE;
            flexibility = CAN_STRETCH; // from the above, we're flexible when empty
        }

        protected void include(int before, int after) {
            this.before = max(this.before, before);
            this.after = max(this.after, after);
        }

        protected int size(boolean min) {
            if (!min) {
                if (canStretch(flexibility)) {
                    return MAX_SIZE;
                }
            }
            return before + after;
        }

        protected int getOffset(View c, Alignment alignment, int size) {
            return before - alignment.getAlignmentValue(c, size);
        }

        protected final void include(View c, Spec spec, GridLayout gridLayout, Axis axis) {
            this.flexibility &= spec.getFlexibility();
            int size = gridLayout.getMeasurementIncludingMargin(c, axis.horizontal);
            Alignment alignment = gridLayout.getAlignment(spec.alignment, axis.horizontal);
            // todo test this works correctly when the returned value is UNDEFINED
            int before = alignment.getAlignmentValue(c, size);
            include(before, size - before);
        }

        @Override
        public String toString() {
            return "Bounds{" +
                    "before=" + before +
                    ", after=" + after +
                    '}';
        }
    }

    /**
     * An Interval represents a contiguous range of values that lie between
     * the interval's {@link #min} and {@link #max} values.
     * <p>
     * Intervals are immutable so may be passed as values and used as keys in hash tables.
     * It is not necessary to have multiple instances of Intervals which have the same
     * {@link #min} and {@link #max} values.
     * <p>
     * Intervals are often written as {@code [min, max]} and represent the set of values
     * {@code x} such that {@code min <= x < max}.
     */
    final static class Interval {
        /**
         * The minimum value.
         */
        public final int min;

        /**
         * The maximum value.
         */
        public final int max;

        /**
         * Construct a new Interval, {@code interval}, where:
         * <ul>
         *     <li> {@code interval.min = min} </li>
         *     <li> {@code interval.max = max} </li>
         * </ul>
         *
         * @param min the minimum value.
         * @param max the maximum value.
         */
        public Interval(int min, int max) {
            this.min = min;
            this.max = max;
        }

        int size() {
            return max - min;
        }

        Interval inverse() {
            return new Interval(max, min);
        }

        /**
         * Returns {@code true} if the {@link #getClass class},
         * {@link #min} and {@link #max} properties of this Interval and the
         * supplied parameter are pairwise equal; {@code false} otherwise.
         *
         * @param that the object to compare this interval with
         *
         * @return {@code true} if the specified object is equal to this
         *         {@code Interval}, {@code false} otherwise.
         */
        @Override
        public boolean equals(Object that) {
            if (this == that) {
                return true;
            }
            if (that == null || getClass() != that.getClass()) {
                return false;
            }

            Interval interval = (Interval) that;

            if (max != interval.max) {
                return false;
            }
            //noinspection RedundantIfStatement
            if (min != interval.min) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = min;
            result = 31 * result + max;
            return result;
        }

        @Override
        public String toString() {
            return "[" + min + ", " + max + "]";
        }
    }

    /**
     * A Spec defines the horizontal or vertical characteristics of a group of
     * cells. Each spec. defines the <em>grid indices</em> and <em>alignment</em>
     * along the appropriate axis.
     * <p>
     * The <em>grid indices</em> are the leading and trailing edges of this cell group.
     * See {@link GridLayout} for a description of the conventions used by GridLayout
     * for grid indices.
     * <p>
     * The <em>alignment</em> property specifies how cells should be aligned in this group.
     * For row groups, this specifies the vertical alignment.
     * For column groups, this specifies the horizontal alignment.
     * <p>
     * Use the following static methods to create specs:
     * <ul>
     *   <li>{@link #spec(int)}</li>
     *   <li>{@link #spec(int, int)}</li>
     *   <li>{@link #spec(int, Alignment)}</li>
     *   <li>{@link #spec(int, int, Alignment)}</li>
     * </ul>
     *
     */
    public static class Spec {
        static final Spec UNDEFINED = spec(GridLayout.UNDEFINED);

        final boolean startDefined;
        final Interval span;
        final Alignment alignment;

        private Spec(boolean startDefined, Interval span, Alignment alignment) {
            this.startDefined = startDefined;
            this.span = span;
            this.alignment = alignment;
        }

        private Spec(boolean startDefined, int start, int size, Alignment alignment) {
            this(startDefined, new Interval(start, start + size), alignment);
        }

        final Spec copyWriteSpan(Interval span) {
            return new Spec(startDefined, span, alignment);
        }

        final Spec copyWriteAlignment(Alignment alignment) {
            return new Spec(startDefined, span, alignment);
        }

        final int getFlexibility() {
            return (alignment == UNDEFINED_ALIGNMENT) ? INFLEXIBLE : CAN_STRETCH;
        }

        /**
         * Returns {@code true} if the {@code class}, {@code alignment} and {@code span}
         * properties of this Spec and the supplied parameter are pairwise equal,
         * {@code false} otherwise.
         *
         * @param that the object to compare this spec with
         *
         * @return {@code true} if the specified object is equal to this
         *         {@code Spec}; {@code false} otherwise
         */
        @Override
        public boolean equals(Object that) {
            if (this == that) {
                return true;
            }
            if (that == null || getClass() != that.getClass()) {
                return false;
            }

            Spec spec = (Spec) that;

            if (!alignment.equals(spec.alignment)) {
                return false;
            }
            //noinspection RedundantIfStatement
            if (!span.equals(spec.span)) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = span.hashCode();
            result = 31 * result + alignment.hashCode();
            return result;
        }
    }

    /**
     * Return a Spec, {@code spec}, where:
     * <ul>
     *     <li> {@code spec.span = [start, start + size]} </li>
     *     <li> {@code spec.alignment = alignment} </li>
     * </ul>
     *
     * @param start     the start
     * @param size      the size
     * @param alignment the alignment
     */
    public static Spec spec(int start, int size, Alignment alignment) {
        return new Spec(start != UNDEFINED, start, size, alignment);
    }

    /**
     * Return a Spec, {@code spec}, where:
     * <ul>
     *     <li> {@code spec.span = [start, start + 1]} </li>
     *     <li> {@code spec.alignment = alignment} </li>
     * </ul>
     *
     * @param start     the start index
     * @param alignment the alignment
     */
    public static Spec spec(int start, Alignment alignment) {
        return spec(start, 1, alignment);
    }

    /**
     * Return a Spec, {@code spec}, where:
     * <ul>
     *     <li> {@code spec.span = [start, start + size]} </li>
     * </ul>
     *
     * @param start     the start
     * @param size      the size
     */
    public static Spec spec(int start, int size) {
        return spec(start, size, UNDEFINED_ALIGNMENT);
    }

    /**
     * Return a Spec, {@code spec}, where:
     * <ul>
     *     <li> {@code spec.span = [start, start + 1]} </li>
     * </ul>
     *
     * @param start     the start index
     */
    public static Spec spec(int start) {
        return spec(start, 1);
    }

    /**
     * Alignments specify where a view should be placed within a cell group and
     * what size it should be.
     * <p>
     * The {@link LayoutParams} class contains a {@link LayoutParams#rowSpec rowSpec}
     * and a {@link LayoutParams#columnSpec columnSpec} each of which contains an
     * {@code alignment}. Overall placement of the view in the cell
     * group is specified by the two alignments which act along each axis independently.
     * <p>
     *  The GridLayout class defines the most common alignments used in general layout:
     * {@link #TOP}, {@link #LEFT}, {@link #BOTTOM}, {@link #RIGHT}, {@link #CENTER}, {@link
     * #BASELINE} and {@link #FILL}.
     */
    /*
     * An Alignment implementation must define {@link #getAlignmentValue(View, int, int)},
     * to return the appropriate value for the type of alignment being defined.
     * The enclosing algorithms position the children
     * so that the locations defined by the alignment values
     * are the same for all of the views in a group.
     * <p>
     */
    public static abstract class Alignment {
        Alignment() {
        }

        /**
         * Returns an alignment value. In the case of vertical alignments the value
         * returned should indicate the distance from the top of the view to the
         * alignment location.
         * For horizontal alignments measurement is made from the left edge of the component.
         *
         * @param view              the view to which this alignment should be applied
         * @param viewSize          the measured size of the view
         * @return the alignment value
         */
        abstract int getAlignmentValue(View view, int viewSize);

        /**
         * Returns the size of the view specified by this alignment.
         * In the case of vertical alignments this method should return a height; for
         * horizontal alignments this method should return the width.
         * <p>
         * The default implementation returns {@code viewSize}.
         *
         * @param view              the view to which this alignment should be applied
         * @param viewSize          the measured size of the view
         * @param cellSize          the size of the cell into which this view will be placed
         * @param measurementType   This parameter is currently unused as GridLayout only supports
         *                          one type of measurement: {@link View#measure(int, int)}.
         *
         * @return the aligned size
         */
        int getSizeInCell(View view, int viewSize, int cellSize, int measurementType) {
            return viewSize;
        }

        Bounds getBounds() {
            return new Bounds();
        }
    }

    static final Alignment UNDEFINED_ALIGNMENT = new Alignment() {
        public int getAlignmentValue(View view, int viewSize) {
            return UNDEFINED;
        }
    };

    private static final Alignment LEADING = new Alignment() {
        public int getAlignmentValue(View view, int viewSize) {
            return 0;
        }
    };

    private static final Alignment TRAILING = new Alignment() {
        public int getAlignmentValue(View view, int viewSize) {
            return viewSize;
        }
    };

    /**
     * Indicates that a view should be aligned with the <em>top</em>
     * edges of the other views in its cell group.
     */
    public static final Alignment TOP = LEADING;

    /**
     * Indicates that a view should be aligned with the <em>bottom</em>
     * edges of the other views in its cell group.
     */
    public static final Alignment BOTTOM = TRAILING;

    /**
     * Indicates that a view should be aligned with the <em>right</em>
     * edges of the other views in its cell group.
     */
    public static final Alignment RIGHT = TRAILING;

    /**
     * Indicates that a view should be aligned with the <em>left</em>
     * edges of the other views in its cell group.
     */
    public static final Alignment LEFT = LEADING;

    /**
     * Indicates that a view should be <em>centered</em> with the other views in its cell group.
     * This constant may be used in both {@link LayoutParams#rowSpec rowSpecs} and {@link
     * LayoutParams#columnSpec columnSpecs}.
     */
    public static final Alignment CENTER = new Alignment() {
        public int getAlignmentValue(View view, int viewSize) {
            return viewSize >> 1;
        }
    };

    /**
     * Indicates that a view should be aligned with the <em>baselines</em>
     * of the other views in its cell group.
     * This constant may only be used as an alignment in {@link LayoutParams#rowSpec rowSpecs}.
     *
     * @see View#getBaseline()
     */
    public static final Alignment BASELINE = new Alignment() {
        public int getAlignmentValue(View view, int viewSize) {
            if (view == null) {
                return UNDEFINED;
            }
            int baseline = view.getBaseline();
            return (baseline == -1) ? UNDEFINED : baseline;
        }

        @Override
        public Bounds getBounds() {
            return new Bounds() {
                /*
                In a baseline aligned row in which some components define a baseline
                and some don't, we need a third variable to properly account for all
                the sizes. This tracks the maximum size of all the components -
                including those that don't define a baseline.
                */
                private int size;

                @Override
                protected void reset() {
                    super.reset();
                    size = Integer.MIN_VALUE;
                }

                @Override
                protected void include(int before, int after) {
                    super.include(before, after);
                    size = max(size, before + after);
                }

                @Override
                protected int size(boolean min) {
                    return max(super.size(min), size);
                }

                @Override
                protected int getOffset(View c, Alignment alignment, int size) {
                    return max(0, super.getOffset(c, alignment, size));
                }
            };
        }
    };

    /**
     * Indicates that a view should expanded to fit the boundaries of its cell group.
     * This constant may be used in both {@link LayoutParams#rowSpec rowSpecs} and
     * {@link LayoutParams#columnSpec columnSpecs}.
     */
    public static final Alignment FILL = new Alignment() {
        public int getAlignmentValue(View view, int viewSize) {
            return UNDEFINED;
        }

        @Override
        public int getSizeInCell(View view, int viewSize, int cellSize, int measurementType) {
            return cellSize;
        }
    };

    static boolean canStretch(int flexibility) {
        return (flexibility & CAN_STRETCH) != 0;
    }

    private static final int INFLEXIBLE = 0;

    private static final int CAN_STRETCH = 2;
}
