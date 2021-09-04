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

package com.android.internal.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.internal.R;
import com.android.internal.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays and detects the user's unlock attempt, which is a drag of a finger
 * across 9 regions of the screen.
 *
 * Is also capable of displaying a static pattern in "in progress", "wrong" or
 * "correct" states.
 */
public class LockPatternView extends View {
    // Aspect to use when rendering this view
    private static final int ASPECT_SQUARE = 0; // View will be the minimum of width/height
    private static final int ASPECT_LOCK_WIDTH = 1; // Fixed width; height will be minimum of (w,h)
    private static final int ASPECT_LOCK_HEIGHT = 2; // Fixed height; width will be minimum of (w,h)

    private static final boolean PROFILE_DRAWING = false;
    private static final int LINE_END_ANIMATION_DURATION_MILLIS = 50;
    private static final int LINE_FADE_OUT_DURATION_MILLIS = 500;
    private static final int LINE_FADE_OUT_DELAY_MILLIS = 150;
    private static final int DOT_ACTIVATION_DURATION_MILLIS = 50;
    private static final int DOT_RADIUS_INCREASE_DURATION_MILLIS = 96;
    private static final int DOT_RADIUS_DECREASE_DURATION_MILLIS = 192;
    private final CellState[][] mCellStates;

    private final int mDotSize;
    private final int mDotSizeActivated;
    private final int mPathWidth;

    private boolean mDrawingProfilingStarted = false;

    @UnsupportedAppUsage
    private final Paint mPaint = new Paint();
    @UnsupportedAppUsage
    private final Paint mPathPaint = new Paint();

    /**
     * How many milliseconds we spend animating each circle of a lock pattern
     * if the animating mode is set.  The entire animation should take this
     * constant * the length of the pattern to complete.
     */
    private static final int MILLIS_PER_CIRCLE_ANIMATING = 700;

    /**
     * This can be used to avoid updating the display for very small motions or noisy panels.
     * It didn't seem to have much impact on the devices tested, so currently set to 0.
     */
    private static final float DRAG_THRESHHOLD = 0.0f;
    public static final int VIRTUAL_BASE_VIEW_ID = 1;
    public static final boolean DEBUG_A11Y = false;
    private static final String TAG = "LockPatternView";

    private OnPatternListener mOnPatternListener;
    @UnsupportedAppUsage
    private final ArrayList<Cell> mPattern = new ArrayList<Cell>(9);

    /**
     * Lookup table for the circles of the pattern we are currently drawing.
     * This will be the cells of the complete pattern unless we are animating,
     * in which case we use this to hold the cells we are drawing for the in
     * progress animation.
     */
    private final boolean[][] mPatternDrawLookup = new boolean[3][3];

    /**
     * the in progress point:
     * - during interaction: where the user's finger is
     * - during animation: the current tip of the animating line
     */
    private float mInProgressX = -1;
    private float mInProgressY = -1;

    private long mAnimatingPeriodStart;
    private long[] mLineFadeStart = new long[9];

    @UnsupportedAppUsage
    private DisplayMode mPatternDisplayMode = DisplayMode.Correct;
    private boolean mInputEnabled = true;
    @UnsupportedAppUsage
    private boolean mInStealthMode = false;
    private boolean mEnableHapticFeedback = true;
    @UnsupportedAppUsage
    private boolean mPatternInProgress = false;
    private boolean mFadePattern = true;

    private float mHitFactor = 0.6f;

    @UnsupportedAppUsage
    private float mSquareWidth;
    @UnsupportedAppUsage
    private float mSquareHeight;
    private final LinearGradient mFadeOutGradientShader;

    private final Path mCurrentPath = new Path();
    private final Rect mInvalidate = new Rect();
    private final Rect mTmpInvalidateRect = new Rect();

    private int mAspect;
    private int mRegularColor;
    private int mErrorColor;
    private int mSuccessColor;
    private int mDotColor;
    private int mDotActivatedColor;

    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private PatternExploreByTouchHelper mExploreByTouchHelper;
    private AudioManager mAudioManager;

    private Drawable mSelectedDrawable;
    private Drawable mNotSelectedDrawable;
    private boolean mUseLockPatternDrawable;

    /**
     * Represents a cell in the 3 X 3 matrix of the unlock pattern view.
     */
    public static final class Cell {
        @UnsupportedAppUsage
        final int row;
        @UnsupportedAppUsage
        final int column;

        // keep # objects limited to 9
        private static final Cell[][] sCells = createCells();

        private static Cell[][] createCells() {
            Cell[][] res = new Cell[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    res[i][j] = new Cell(i, j);
                }
            }
            return res;
        }

        /**
         * @param row The row of the cell.
         * @param column The column of the cell.
         */
        private Cell(int row, int column) {
            checkRange(row, column);
            this.row = row;
            this.column = column;
        }

        public int getRow() {
            return row;
        }

        public int getColumn() {
            return column;
        }

        public static Cell of(int row, int column) {
            checkRange(row, column);
            return sCells[row][column];
        }

        private static void checkRange(int row, int column) {
            if (row < 0 || row > 2) {
                throw new IllegalArgumentException("row must be in range 0-2");
            }
            if (column < 0 || column > 2) {
                throw new IllegalArgumentException("column must be in range 0-2");
            }
        }

        @Override
        public String toString() {
            return "(row=" + row + ",clmn=" + column + ")";
        }
    }

    public static class CellState {
        int row;
        int col;
        boolean hwAnimating;
        CanvasProperty<Float> hwRadius;
        CanvasProperty<Float> hwCenterX;
        CanvasProperty<Float> hwCenterY;
        CanvasProperty<Paint> hwPaint;
        float radius;
        float translationY;
        float alpha = 1f;
        float activationAnimationProgress;
        public float lineEndX = Float.MIN_VALUE;
        public float lineEndY = Float.MIN_VALUE;
        @Nullable
        Animator activationAnimator;
     }

    /**
     * How to display the current pattern.
     */
    public enum DisplayMode {

        /**
         * The pattern drawn is correct (i.e draw it in a friendly color)
         */
        @UnsupportedAppUsage
        Correct,

        /**
         * Animate the pattern (for demo, and help).
         */
        @UnsupportedAppUsage
        Animate,

        /**
         * The pattern is wrong (i.e draw a foreboding color)
         */
        @UnsupportedAppUsage
        Wrong
    }

    /**
     * The call back interface for detecting patterns entered by the user.
     */
    public static interface OnPatternListener {

        /**
         * A new pattern has begun.
         */
        void onPatternStart();

        /**
         * The pattern was cleared.
         */
        void onPatternCleared();

        /**
         * The user extended the pattern currently being drawn by one cell.
         * @param pattern The pattern with newly added cell.
         */
        void onPatternCellAdded(List<Cell> pattern);

        /**
         * A pattern was detected from the user.
         * @param pattern The pattern.
         */
        void onPatternDetected(List<Cell> pattern);
    }

    public LockPatternView(Context context) {
        this(context, null);
    }

    @UnsupportedAppUsage
    public LockPatternView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LockPatternView,
                R.attr.lockPatternStyle, R.style.Widget_LockPatternView);

        final String aspect = a.getString(R.styleable.LockPatternView_aspect);

        if ("square".equals(aspect)) {
            mAspect = ASPECT_SQUARE;
        } else if ("lock_width".equals(aspect)) {
            mAspect = ASPECT_LOCK_WIDTH;
        } else if ("lock_height".equals(aspect)) {
            mAspect = ASPECT_LOCK_HEIGHT;
        } else {
            mAspect = ASPECT_SQUARE;
        }

        setClickable(true);


        mPathPaint.setAntiAlias(true);
        mPathPaint.setDither(true);

        mRegularColor = a.getColor(R.styleable.LockPatternView_regularColor, 0);
        mErrorColor = a.getColor(R.styleable.LockPatternView_errorColor, 0);
        mSuccessColor = a.getColor(R.styleable.LockPatternView_successColor, 0);
        mDotColor = a.getColor(R.styleable.LockPatternView_dotColor, mRegularColor);
        mDotActivatedColor = a.getColor(R.styleable.LockPatternView_dotActivatedColor, mDotColor);

        int pathColor = a.getColor(R.styleable.LockPatternView_pathColor, mRegularColor);
        mPathPaint.setColor(pathColor);

        mPathPaint.setStyle(Paint.Style.STROKE);
        mPathPaint.setStrokeJoin(Paint.Join.ROUND);
        mPathPaint.setStrokeCap(Paint.Cap.ROUND);

        mPathWidth = getResources().getDimensionPixelSize(R.dimen.lock_pattern_dot_line_width);
        mPathPaint.setStrokeWidth(mPathWidth);

        mDotSize = getResources().getDimensionPixelSize(R.dimen.lock_pattern_dot_size);
        mDotSizeActivated = getResources().getDimensionPixelSize(
                R.dimen.lock_pattern_dot_size_activated);

        mUseLockPatternDrawable = getResources().getBoolean(R.bool.use_lock_pattern_drawable);
        if (mUseLockPatternDrawable) {
            mSelectedDrawable = getResources().getDrawable(R.drawable.lockscreen_selected);
            mNotSelectedDrawable = getResources().getDrawable(R.drawable.lockscreen_notselected);
        }

        mPaint.setAntiAlias(true);
        mPaint.setDither(true);

        mCellStates = new CellState[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mCellStates[i][j] = new CellState();
                mCellStates[i][j].radius = mDotSize/2;
                mCellStates[i][j].row = i;
                mCellStates[i][j].col = j;
            }
        }

        mFastOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in);
        mExploreByTouchHelper = new PatternExploreByTouchHelper(this);
        setAccessibilityDelegate(mExploreByTouchHelper);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        int fadeAwayGradientWidth = getResources().getDimensionPixelSize(
                R.dimen.lock_pattern_fade_away_gradient_width);
        // Set up gradient shader with the middle in point (0, 0).
        mFadeOutGradientShader = new LinearGradient(/* x0= */ -fadeAwayGradientWidth / 2f,
                /* y0= */ 0,/* x1= */ fadeAwayGradientWidth / 2f, /* y1= */ 0,
                Color.TRANSPARENT, pathColor, Shader.TileMode.CLAMP);

        a.recycle();
    }

    @UnsupportedAppUsage
    public CellState[][] getCellStates() {
        return mCellStates;
    }

    /**
     * @return Whether the view is in stealth mode.
     */
    public boolean isInStealthMode() {
        return mInStealthMode;
    }

    /**
     * @return Whether the view has tactile feedback enabled.
     */
    public boolean isTactileFeedbackEnabled() {
        return mEnableHapticFeedback;
    }

    /**
     * Set whether the view is in stealth mode.  If true, there will be no
     * visible feedback as the user enters the pattern.
     *
     * @param inStealthMode Whether in stealth mode.
     */
    @UnsupportedAppUsage
    public void setInStealthMode(boolean inStealthMode) {
        mInStealthMode = inStealthMode;
    }

    /**
     * Set whether the pattern should fade as it's being drawn. If
     * true, each segment of the pattern fades over time.
     */
    public void setFadePattern(boolean fadePattern) {
        mFadePattern = fadePattern;
    }

    /**
     * Set whether the view will use tactile feedback.  If true, there will be
     * tactile feedback as the user enters the pattern.
     *
     * @param tactileFeedbackEnabled Whether tactile feedback is enabled
     */
    @UnsupportedAppUsage
    public void setTactileFeedbackEnabled(boolean tactileFeedbackEnabled) {
        mEnableHapticFeedback = tactileFeedbackEnabled;
    }

    /**
     * Set the call back for pattern detection.
     * @param onPatternListener The call back.
     */
    @UnsupportedAppUsage
    public void setOnPatternListener(
            OnPatternListener onPatternListener) {
        mOnPatternListener = onPatternListener;
    }

    /**
     * Set the pattern explicitely (rather than waiting for the user to input
     * a pattern).
     * @param displayMode How to display the pattern.
     * @param pattern The pattern.
     */
    public void setPattern(DisplayMode displayMode, List<Cell> pattern) {
        mPattern.clear();
        mPattern.addAll(pattern);
        clearPatternDrawLookup();
        for (Cell cell : pattern) {
            mPatternDrawLookup[cell.getRow()][cell.getColumn()] = true;
        }

        setDisplayMode(displayMode);
    }

    /**
     * Set the display mode of the current pattern.  This can be useful, for
     * instance, after detecting a pattern to tell this view whether change the
     * in progress result to correct or wrong.
     * @param displayMode The display mode.
     */
    @UnsupportedAppUsage
    public void setDisplayMode(DisplayMode displayMode) {
        mPatternDisplayMode = displayMode;
        if (displayMode == DisplayMode.Animate) {
            if (mPattern.size() == 0) {
                throw new IllegalStateException("you must have a pattern to "
                        + "animate if you want to set the display mode to animate");
            }
            mAnimatingPeriodStart = SystemClock.elapsedRealtime();
            final Cell first = mPattern.get(0);
            mInProgressX = getCenterXForColumn(first.getColumn());
            mInProgressY = getCenterYForRow(first.getRow());
            clearPatternDrawLookup();
        }
        invalidate();
    }

    public void startCellStateAnimation(CellState cellState, float startAlpha, float endAlpha,
            float startTranslationY, float endTranslationY, float startScale, float endScale,
            long delay, long duration,
            Interpolator interpolator, Runnable finishRunnable) {
        if (isHardwareAccelerated()) {
            startCellStateAnimationHw(cellState, startAlpha, endAlpha, startTranslationY,
                    endTranslationY, startScale, endScale, delay, duration, interpolator,
                    finishRunnable);
        } else {
            startCellStateAnimationSw(cellState, startAlpha, endAlpha, startTranslationY,
                    endTranslationY, startScale, endScale, delay, duration, interpolator,
                    finishRunnable);
        }
    }

    private void startCellStateAnimationSw(final CellState cellState,
            final float startAlpha, final float endAlpha,
            final float startTranslationY, final float endTranslationY,
            final float startScale, final float endScale,
            long delay, long duration, Interpolator interpolator, final Runnable finishRunnable) {
        cellState.alpha = startAlpha;
        cellState.translationY = startTranslationY;
        cellState.radius = mDotSize/2 * startScale;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                cellState.alpha = (1 - t) * startAlpha + t * endAlpha;
                cellState.translationY = (1 - t) * startTranslationY + t * endTranslationY;
                cellState.radius = mDotSize/2 * ((1 - t) * startScale + t * endScale);
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (finishRunnable != null) {
                    finishRunnable.run();
                }
            }
        });
        animator.start();
    }

    private void startCellStateAnimationHw(final CellState cellState,
            float startAlpha, float endAlpha,
            float startTranslationY, float endTranslationY,
            float startScale, float endScale,
            long delay, long duration, Interpolator interpolator, final Runnable finishRunnable) {
        cellState.alpha = endAlpha;
        cellState.translationY = endTranslationY;
        cellState.radius = mDotSize/2 * endScale;
        cellState.hwAnimating = true;
        cellState.hwCenterY = CanvasProperty.createFloat(
                getCenterYForRow(cellState.row) + startTranslationY);
        cellState.hwCenterX = CanvasProperty.createFloat(getCenterXForColumn(cellState.col));
        cellState.hwRadius = CanvasProperty.createFloat(mDotSize/2 * startScale);
        mPaint.setColor(getDotColor());
        mPaint.setAlpha((int) (startAlpha * 255));
        cellState.hwPaint = CanvasProperty.createPaint(new Paint(mPaint));

        startRtFloatAnimation(cellState.hwCenterY,
                getCenterYForRow(cellState.row) + endTranslationY, delay, duration, interpolator);
        startRtFloatAnimation(cellState.hwRadius, mDotSize/2 * endScale, delay, duration,
                interpolator);
        startRtAlphaAnimation(cellState, endAlpha, delay, duration, interpolator,
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        cellState.hwAnimating = false;
                        if (finishRunnable != null) {
                            finishRunnable.run();
                        }
                    }
                });

        invalidate();
    }

    private void startRtAlphaAnimation(CellState cellState, float endAlpha,
            long delay, long duration, Interpolator interpolator,
            Animator.AnimatorListener listener) {
        RenderNodeAnimator animator = new RenderNodeAnimator(cellState.hwPaint,
                RenderNodeAnimator.PAINT_ALPHA, (int) (endAlpha * 255));
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.setInterpolator(interpolator);
        animator.setTarget(this);
        animator.addListener(listener);
        animator.start();
    }

    private void startRtFloatAnimation(CanvasProperty<Float> property, float endValue,
            long delay, long duration, Interpolator interpolator) {
        RenderNodeAnimator animator = new RenderNodeAnimator(property, endValue);
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.setInterpolator(interpolator);
        animator.setTarget(this);
        animator.start();
    }

    private void notifyCellAdded() {
        // sendAccessEvent(R.string.lockscreen_access_pattern_cell_added);
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternCellAdded(mPattern);
        }
        // Disable used cells for accessibility as they get added
        if (DEBUG_A11Y) Log.v(TAG, "ivnalidating root because cell was added.");
        mExploreByTouchHelper.invalidateRoot();
    }

    private void notifyPatternStarted() {
        sendAccessEvent(R.string.lockscreen_access_pattern_start);
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternStart();
        }
    }

    @UnsupportedAppUsage
    private void notifyPatternDetected() {
        sendAccessEvent(R.string.lockscreen_access_pattern_detected);
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternDetected(mPattern);
        }
    }

    private void notifyPatternCleared() {
        sendAccessEvent(R.string.lockscreen_access_pattern_cleared);
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternCleared();
        }
    }

    /**
     * Clear the pattern.
     */
    @UnsupportedAppUsage
    public void clearPattern() {
        resetPattern();
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        // Dispatch to onHoverEvent first so mPatternInProgress is up to date when the
        // helper gets the event.
        boolean handled = super.dispatchHoverEvent(event);
        handled |= mExploreByTouchHelper.dispatchHoverEvent(event);
        return handled;
    }

    /**
     * Reset all pattern state.
     */
    private void resetPattern() {
        mPattern.clear();
        clearPatternDrawLookup();
        mPatternDisplayMode = DisplayMode.Correct;
        invalidate();
    }

    /**
     * If there are any cells being drawn.
     */
    public boolean isEmpty() {
        return mPattern.isEmpty();
    }

    /**
     * Clear the pattern lookup table. Also reset the line fade start times for
     * the next attempt.
     */
    private void clearPatternDrawLookup() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mPatternDrawLookup[i][j] = false;
                mLineFadeStart[i+j*3] = 0;
            }
        }
    }

    /**
     * Disable input (for instance when displaying a message that will
     * timeout so user doesn't get view into messy state).
     */
    @UnsupportedAppUsage
    public void disableInput() {
        mInputEnabled = false;
    }

    /**
     * Enable input.
     */
    @UnsupportedAppUsage
    public void enableInput() {
        mInputEnabled = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        final int width = w - mPaddingLeft - mPaddingRight;
        mSquareWidth = width / 3.0f;

        if (DEBUG_A11Y) Log.v(TAG, "onSizeChanged(" + w + "," + h + ")");
        final int height = h - mPaddingTop - mPaddingBottom;
        mSquareHeight = height / 3.0f;
        mExploreByTouchHelper.invalidateRoot();

        if (mUseLockPatternDrawable) {
            mNotSelectedDrawable.setBounds(mPaddingLeft, mPaddingTop, width, height);
            mSelectedDrawable.setBounds(mPaddingLeft, mPaddingTop, width, height);
        }
    }

    private int resolveMeasured(int measureSpec, int desired)
    {
        int result = 0;
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (MeasureSpec.getMode(measureSpec)) {
            case MeasureSpec.UNSPECIFIED:
                result = desired;
                break;
            case MeasureSpec.AT_MOST:
                result = Math.max(specSize, desired);
                break;
            case MeasureSpec.EXACTLY:
            default:
                result = specSize;
        }
        return result;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int minimumWidth = getSuggestedMinimumWidth();
        final int minimumHeight = getSuggestedMinimumHeight();
        int viewWidth = resolveMeasured(widthMeasureSpec, minimumWidth);
        int viewHeight = resolveMeasured(heightMeasureSpec, minimumHeight);

        switch (mAspect) {
            case ASPECT_SQUARE:
                viewWidth = viewHeight = Math.min(viewWidth, viewHeight);
                break;
            case ASPECT_LOCK_WIDTH:
                viewHeight = Math.min(viewWidth, viewHeight);
                break;
            case ASPECT_LOCK_HEIGHT:
                viewWidth = Math.min(viewWidth, viewHeight);
                break;
        }
        // Log.v(TAG, "LockPatternView dimensions: " + viewWidth + "x" + viewHeight);
        setMeasuredDimension(viewWidth, viewHeight);
    }

    /**
     * Determines whether the point x, y will add a new point to the current
     * pattern (in addition to finding the cell, also makes heuristic choices
     * such as filling in gaps based on current pattern).
     * @param x The x coordinate.
     * @param y The y coordinate.
     */
    private Cell detectAndAddHit(float x, float y) {
        final Cell cell = checkForNewHit(x, y);
        if (cell != null) {

            // check for gaps in existing pattern
            Cell fillInGapCell = null;
            final ArrayList<Cell> pattern = mPattern;
            if (!pattern.isEmpty()) {
                final Cell lastCell = pattern.get(pattern.size() - 1);
                int dRow = cell.row - lastCell.row;
                int dColumn = cell.column - lastCell.column;

                int fillInRow = lastCell.row;
                int fillInColumn = lastCell.column;

                if (Math.abs(dRow) == 2 && Math.abs(dColumn) != 1) {
                    fillInRow = lastCell.row + ((dRow > 0) ? 1 : -1);
                }

                if (Math.abs(dColumn) == 2 && Math.abs(dRow) != 1) {
                    fillInColumn = lastCell.column + ((dColumn > 0) ? 1 : -1);
                }

                fillInGapCell = Cell.of(fillInRow, fillInColumn);
            }

            if (fillInGapCell != null &&
                    !mPatternDrawLookup[fillInGapCell.row][fillInGapCell.column]) {
                addCellToPattern(fillInGapCell);
            }
            addCellToPattern(cell);
            if (mEnableHapticFeedback) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                        | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
            return cell;
        }
        return null;
    }

    private void addCellToPattern(Cell newCell) {
        mPatternDrawLookup[newCell.getRow()][newCell.getColumn()] = true;
        mPattern.add(newCell);
        if (!mInStealthMode) {
            startCellActivatedAnimation(newCell);
        }
        notifyCellAdded();
    }

    private void startCellActivatedAnimation(Cell cell) {
        final CellState cellState = mCellStates[cell.row][cell.column];

        if (cellState.activationAnimator != null) {
            cellState.activationAnimator.cancel();
        }
        AnimatorSet animatorSet = new AnimatorSet();
        AnimatorSet.Builder animatorSetBuilder = animatorSet
                .play(createLineDisappearingAnimation())
                .with(createLineEndAnimation(cellState, mInProgressX, mInProgressY,
                        getCenterXForColumn(cell.column), getCenterYForRow(cell.row)));
        if (mDotSize != mDotSizeActivated) {
            animatorSetBuilder.with(createDotRadiusAnimation(cellState));
        }
        if (mDotColor != mDotActivatedColor) {
            animatorSetBuilder.with(createDotActivationColorAnimation(cellState));
        }

        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                cellState.activationAnimator = null;
                invalidate();
            }
        });
        cellState.activationAnimator = animatorSet;
        animatorSet.start();
    }

    private Animator createDotActivationColorAnimation(CellState cellState) {
        ValueAnimator.AnimatorUpdateListener updateListener =
                valueAnimator -> {
                    cellState.activationAnimationProgress =
                            (float) valueAnimator.getAnimatedValue();
                    invalidate();
                };
        ValueAnimator activateAnimator = ValueAnimator.ofFloat(0f, 1f);
        ValueAnimator deactivateAnimator = ValueAnimator.ofFloat(1f, 0f);
        activateAnimator.addUpdateListener(updateListener);
        deactivateAnimator.addUpdateListener(updateListener);
        activateAnimator.setInterpolator(mFastOutSlowInInterpolator);
        deactivateAnimator.setInterpolator(mLinearOutSlowInInterpolator);

        // Align dot animation duration with line fade out animation.
        activateAnimator.setDuration(DOT_ACTIVATION_DURATION_MILLIS);
        deactivateAnimator.setDuration(DOT_ACTIVATION_DURATION_MILLIS);
        AnimatorSet set = new AnimatorSet();
        set.play(deactivateAnimator)
                .after(LINE_FADE_OUT_DELAY_MILLIS + LINE_FADE_OUT_DURATION_MILLIS
                        - DOT_ACTIVATION_DURATION_MILLIS * 2)
                .after(activateAnimator);
        return set;
    }

    /**
     * On the last frame before cell activates the end point of in progress line is not aligned
     * with dot center so we execute a short animation moving the end point to exact dot center.
     */
    private Animator createLineEndAnimation(final CellState state,
            final float startX, final float startY, final float targetX, final float targetY) {
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1);
        valueAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            state.lineEndX = (1 - t) * startX + t * targetX;
            state.lineEndY = (1 - t) * startY + t * targetY;
            invalidate();
        });
        valueAnimator.setInterpolator(mFastOutSlowInInterpolator);
        valueAnimator.setDuration(LINE_END_ANIMATION_DURATION_MILLIS);
        return valueAnimator;
    }

    /**
     * Starts animator to fade out a line segment. It does only invalidate because all the
     * transitions are applied in {@code onDraw} method.
     */
    private Animator createLineDisappearingAnimation() {
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1);
        valueAnimator.addUpdateListener(animation -> invalidate());
        valueAnimator.setStartDelay(LINE_FADE_OUT_DELAY_MILLIS);
        valueAnimator.setDuration(LINE_FADE_OUT_DURATION_MILLIS);
        return valueAnimator;
    }

    private Animator createDotRadiusAnimation(CellState state) {
        float defaultRadius = mDotSize / 2f;
        float activatedRadius = mDotSizeActivated / 2f;

        ValueAnimator.AnimatorUpdateListener animatorUpdateListener =
                animation -> {
                    state.radius = (float) animation.getAnimatedValue();
                    invalidate();
                };

        ValueAnimator activationAnimator = ValueAnimator.ofFloat(defaultRadius, activatedRadius);
        activationAnimator.addUpdateListener(animatorUpdateListener);
        activationAnimator.setInterpolator(mLinearOutSlowInInterpolator);
        activationAnimator.setDuration(DOT_RADIUS_INCREASE_DURATION_MILLIS);

        ValueAnimator deactivationAnimator = ValueAnimator.ofFloat(activatedRadius, defaultRadius);
        deactivationAnimator.addUpdateListener(animatorUpdateListener);
        deactivationAnimator.setInterpolator(mFastOutSlowInInterpolator);
        deactivationAnimator.setDuration(DOT_RADIUS_DECREASE_DURATION_MILLIS);

        AnimatorSet set = new AnimatorSet();
        set.playSequentially(activationAnimator, deactivationAnimator);
        return set;
    }

    // helper method to find which cell a point maps to
    private Cell checkForNewHit(float x, float y) {

        final int rowHit = getRowHit(y);
        if (rowHit < 0) {
            return null;
        }
        final int columnHit = getColumnHit(x);
        if (columnHit < 0) {
            return null;
        }

        if (mPatternDrawLookup[rowHit][columnHit]) {
            return null;
        }
        return Cell.of(rowHit, columnHit);
    }

    /**
     * Helper method to find the row that y falls into.
     * @param y The y coordinate
     * @return The row that y falls in, or -1 if it falls in no row.
     */
    private int getRowHit(float y) {

        final float squareHeight = mSquareHeight;
        float hitSize = squareHeight * mHitFactor;

        float offset = mPaddingTop + (squareHeight - hitSize) / 2f;
        for (int i = 0; i < 3; i++) {

            final float hitTop = offset + squareHeight * i;
            if (y >= hitTop && y <= hitTop + hitSize) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Helper method to find the column x fallis into.
     * @param x The x coordinate.
     * @return The column that x falls in, or -1 if it falls in no column.
     */
    private int getColumnHit(float x) {
        final float squareWidth = mSquareWidth;
        float hitSize = squareWidth * mHitFactor;

        float offset = mPaddingLeft + (squareWidth - hitSize) / 2f;
        for (int i = 0; i < 3; i++) {

            final float hitLeft = offset + squareWidth * i;
            if (x >= hitLeft && x <= hitLeft + hitSize) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (AccessibilityManager.getInstance(mContext).isTouchExplorationEnabled()) {
            final int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    event.setAction(MotionEvent.ACTION_DOWN);
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    event.setAction(MotionEvent.ACTION_MOVE);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    event.setAction(MotionEvent.ACTION_UP);
                    break;
            }
            onTouchEvent(event);
            event.setAction(action);
        }
        return super.onHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mInputEnabled || !isEnabled()) {
            return false;
        }

        switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event);
                return true;
            case MotionEvent.ACTION_UP:
                handleActionUp();
                return true;
            case MotionEvent.ACTION_MOVE:
                handleActionMove(event);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mPatternInProgress) {
                    setPatternInProgress(false);
                    resetPattern();
                    notifyPatternCleared();
                }
                if (PROFILE_DRAWING) {
                    if (mDrawingProfilingStarted) {
                        Debug.stopMethodTracing();
                        mDrawingProfilingStarted = false;
                    }
                }
                return true;
        }
        return false;
    }

    private void setPatternInProgress(boolean progress) {
        mPatternInProgress = progress;
        mExploreByTouchHelper.invalidateRoot();
    }

    private void handleActionMove(MotionEvent event) {
        // Handle all recent motion events so we don't skip any cells even when the device
        // is busy...
        final float radius = mPathWidth;
        final int historySize = event.getHistorySize();
        mTmpInvalidateRect.setEmpty();
        boolean invalidateNow = false;
        for (int i = 0; i < historySize + 1; i++) {
            final float x = i < historySize ? event.getHistoricalX(i) : event.getX();
            final float y = i < historySize ? event.getHistoricalY(i) : event.getY();
            Cell hitCell = detectAndAddHit(x, y);
            final int patternSize = mPattern.size();
            if (hitCell != null && patternSize == 1) {
                setPatternInProgress(true);
                notifyPatternStarted();
            }
            // note current x and y for rubber banding of in progress patterns
            final float dx = Math.abs(x - mInProgressX);
            final float dy = Math.abs(y - mInProgressY);
            if (dx > DRAG_THRESHHOLD || dy > DRAG_THRESHHOLD) {
                invalidateNow = true;
            }

            if (mPatternInProgress && patternSize > 0) {
                final ArrayList<Cell> pattern = mPattern;
                final Cell lastCell = pattern.get(patternSize - 1);
                float lastCellCenterX = getCenterXForColumn(lastCell.column);
                float lastCellCenterY = getCenterYForRow(lastCell.row);

                // Adjust for drawn segment from last cell to (x,y). Radius accounts for line width.
                float left = Math.min(lastCellCenterX, x) - radius;
                float right = Math.max(lastCellCenterX, x) + radius;
                float top = Math.min(lastCellCenterY, y) - radius;
                float bottom = Math.max(lastCellCenterY, y) + radius;

                // Invalidate between the pattern's new cell and the pattern's previous cell
                if (hitCell != null) {
                    final float width = mSquareWidth * 0.5f;
                    final float height = mSquareHeight * 0.5f;
                    final float hitCellCenterX = getCenterXForColumn(hitCell.column);
                    final float hitCellCenterY = getCenterYForRow(hitCell.row);

                    left = Math.min(hitCellCenterX - width, left);
                    right = Math.max(hitCellCenterX + width, right);
                    top = Math.min(hitCellCenterY - height, top);
                    bottom = Math.max(hitCellCenterY + height, bottom);
                }

                // Invalidate between the pattern's last cell and the previous location
                mTmpInvalidateRect.union(Math.round(left), Math.round(top),
                        Math.round(right), Math.round(bottom));
            }
        }
        mInProgressX = event.getX();
        mInProgressY = event.getY();

        // To save updates, we only invalidate if the user moved beyond a certain amount.
        if (invalidateNow) {
            mInvalidate.union(mTmpInvalidateRect);
            invalidate(mInvalidate);
            mInvalidate.set(mTmpInvalidateRect);
        }
    }

    private void sendAccessEvent(int resId) {
        announceForAccessibility(mContext.getString(resId));
    }

    private void handleActionUp() {
        // report pattern detected
        if (!mPattern.isEmpty()) {
            setPatternInProgress(false);
            cancelLineAnimations();
            notifyPatternDetected();
            // Also clear pattern if fading is enabled
            if (mFadePattern) {
                clearPatternDrawLookup();
                mPatternDisplayMode = DisplayMode.Correct;
            }
            invalidate();
        }
        if (PROFILE_DRAWING) {
            if (mDrawingProfilingStarted) {
                Debug.stopMethodTracing();
                mDrawingProfilingStarted = false;
            }
        }
    }

    private void cancelLineAnimations() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                CellState state = mCellStates[i][j];
                if (state.activationAnimator != null) {
                    state.activationAnimator.cancel();
                    state.activationAnimator = null;
                    state.radius = mDotSize / 2f;
                    state.activationAnimationProgress = 0f;
                    state.lineEndX = Float.MIN_VALUE;
                    state.lineEndY = Float.MIN_VALUE;
                }
            }
        }
    }
    private void handleActionDown(MotionEvent event) {
        resetPattern();
        final float x = event.getX();
        final float y = event.getY();
        final Cell hitCell = detectAndAddHit(x, y);
        if (hitCell != null) {
            setPatternInProgress(true);
            mPatternDisplayMode = DisplayMode.Correct;
            notifyPatternStarted();
        } else if (mPatternInProgress) {
            setPatternInProgress(false);
            notifyPatternCleared();
        }
        if (hitCell != null) {
            final float startX = getCenterXForColumn(hitCell.column);
            final float startY = getCenterYForRow(hitCell.row);

            final float widthOffset = mSquareWidth / 2f;
            final float heightOffset = mSquareHeight / 2f;

            invalidate((int) (startX - widthOffset), (int) (startY - heightOffset),
                    (int) (startX + widthOffset), (int) (startY + heightOffset));
        }
        mInProgressX = x;
        mInProgressY = y;
        if (PROFILE_DRAWING) {
            if (!mDrawingProfilingStarted) {
                Debug.startMethodTracing("LockPatternDrawing");
                mDrawingProfilingStarted = true;
            }
        }
    }

    /**
     * Change theme colors
     * @param regularColor The dot color
     * @param successColor Color used when pattern is correct
     * @param errorColor Color used when authentication fails
     */
    public void setColors(int regularColor, int successColor, int errorColor) {
        mRegularColor = regularColor;
        mErrorColor = errorColor;
        mSuccessColor = successColor;
        mPathPaint.setColor(regularColor);
        invalidate();
    }

    private float getCenterXForColumn(int column) {
        return mPaddingLeft + column * mSquareWidth + mSquareWidth / 2f;
    }

    private float getCenterYForRow(int row) {
        return mPaddingTop + row * mSquareHeight + mSquareHeight / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final ArrayList<Cell> pattern = mPattern;
        final int count = pattern.size();
        final boolean[][] drawLookup = mPatternDrawLookup;

        if (mPatternDisplayMode == DisplayMode.Animate) {

            // figure out which circles to draw

            // + 1 so we pause on complete pattern
            final int oneCycle = (count + 1) * MILLIS_PER_CIRCLE_ANIMATING;
            final int spotInCycle = (int) (SystemClock.elapsedRealtime() -
                    mAnimatingPeriodStart) % oneCycle;
            final int numCircles = spotInCycle / MILLIS_PER_CIRCLE_ANIMATING;

            clearPatternDrawLookup();
            for (int i = 0; i < numCircles; i++) {
                final Cell cell = pattern.get(i);
                drawLookup[cell.getRow()][cell.getColumn()] = true;
            }

            // figure out in progress portion of ghosting line

            final boolean needToUpdateInProgressPoint = numCircles > 0
                    && numCircles < count;

            if (needToUpdateInProgressPoint) {
                final float percentageOfNextCircle =
                        ((float) (spotInCycle % MILLIS_PER_CIRCLE_ANIMATING)) /
                                MILLIS_PER_CIRCLE_ANIMATING;

                final Cell currentCell = pattern.get(numCircles - 1);
                final float centerX = getCenterXForColumn(currentCell.column);
                final float centerY = getCenterYForRow(currentCell.row);

                final Cell nextCell = pattern.get(numCircles);
                final float dx = percentageOfNextCircle *
                        (getCenterXForColumn(nextCell.column) - centerX);
                final float dy = percentageOfNextCircle *
                        (getCenterYForRow(nextCell.row) - centerY);
                mInProgressX = centerX + dx;
                mInProgressY = centerY + dy;
            }
            // TODO: Infinite loop here...
            invalidate();
        }

        final Path currentPath = mCurrentPath;
        currentPath.rewind();

        // TODO: the path should be created and cached every time we hit-detect a cell
        // only the last segment of the path should be computed here
        // draw the path of the pattern (unless we are in stealth mode)
        final boolean drawPath = !mInStealthMode;

        if (drawPath) {
            mPathPaint.setColor(getCurrentColor(true /* partOfPattern */));

            boolean anyCircles = false;
            float lastX = 0f;
            float lastY = 0f;
            long elapsedRealtime = SystemClock.elapsedRealtime();
           for (int i = 0; i < count; i++) {
                Cell cell = pattern.get(i);

                // only draw the part of the pattern stored in
                // the lookup table (this is only different in the case
                // of animation).
                if (!drawLookup[cell.row][cell.column]) {
                    break;
                }
                anyCircles = true;

                if (mLineFadeStart[i] == 0) {
                  mLineFadeStart[i] = SystemClock.elapsedRealtime();
                }

                float centerX = getCenterXForColumn(cell.column);
                float centerY = getCenterYForRow(cell.row);
                if (i != 0) {
                    CellState state = mCellStates[cell.row][cell.column];
                    currentPath.rewind();
                    float endX;
                    float endY;
                    if (state.lineEndX != Float.MIN_VALUE && state.lineEndY != Float.MIN_VALUE) {
                        endX = state.lineEndX;
                        endY = state.lineEndY;
                    } else {
                        endX = centerX;
                        endY = centerY;
                    }
                    drawLineSegment(canvas, /* startX = */ lastX, /* startY = */ lastY, endX, endY,
                            mLineFadeStart[i], elapsedRealtime);
                }
                lastX = centerX;
                lastY = centerY;
            }

            // draw last in progress section
            if ((mPatternInProgress || mPatternDisplayMode == DisplayMode.Animate)
                    && anyCircles) {
                currentPath.rewind();
                currentPath.moveTo(lastX, lastY);
                currentPath.lineTo(mInProgressX, mInProgressY);

                mPathPaint.setAlpha((int) (calculateLastSegmentAlpha(
                        mInProgressX, mInProgressY, lastX, lastY) * 255f));
                canvas.drawPath(currentPath, mPathPaint);
            }
        }

        // draw the circles
        for (int i = 0; i < 3; i++) {
            float centerY = getCenterYForRow(i);
            for (int j = 0; j < 3; j++) {
                CellState cellState = mCellStates[i][j];
                float centerX = getCenterXForColumn(j);
                float translationY = cellState.translationY;

                if (mUseLockPatternDrawable) {
                    drawCellDrawable(canvas, i, j, cellState.radius, drawLookup[i][j]);
                } else {
                    if (isHardwareAccelerated() && cellState.hwAnimating) {
                        RecordingCanvas recordingCanvas = (RecordingCanvas) canvas;
                        recordingCanvas.drawCircle(cellState.hwCenterX, cellState.hwCenterY,
                                cellState.hwRadius, cellState.hwPaint);
                    } else {
                        drawCircle(canvas, (int) centerX, (int) centerY + translationY,
                                cellState.radius, drawLookup[i][j], cellState.alpha,
                                cellState.activationAnimationProgress);
                    }
                }
            }
        }
    }

    private void drawLineSegment(Canvas canvas, float startX, float startY, float endX, float endY,
            long lineFadeStart, long elapsedRealtime) {
        float fadeAwayProgress;
        if (mFadePattern) {
            if (elapsedRealtime - lineFadeStart
                    >= LINE_FADE_OUT_DELAY_MILLIS + LINE_FADE_OUT_DURATION_MILLIS) {
                // Time for this segment animation is out so we don't need to draw it.
                return;
            }
            // Set this line segment to fade away animated.
            fadeAwayProgress = Math.max(
                    ((float) (elapsedRealtime - lineFadeStart - LINE_FADE_OUT_DELAY_MILLIS))
                            / LINE_FADE_OUT_DURATION_MILLIS, 0f);
            drawFadingAwayLineSegment(canvas, startX, startY, endX, endY, fadeAwayProgress);
        } else {
            mPathPaint.setAlpha(255);
            canvas.drawLine(startX, startY, endX, endY, mPathPaint);
        }
    }

    private void drawFadingAwayLineSegment(Canvas canvas, float startX, float startY, float endX,
            float endY, float fadeAwayProgress) {
        mPathPaint.setAlpha((int) (255 * (1 - fadeAwayProgress)));

        // To draw gradient segment we use mFadeOutGradientShader which has immutable coordinates
        // thus we will need to translate and rotate the canvas.
        mPathPaint.setShader(mFadeOutGradientShader);
        canvas.save();

        // First translate canvas to gradient middle point.
        float gradientMidX = endX * fadeAwayProgress + startX * (1 - fadeAwayProgress);
        float gradientMidY = endY * fadeAwayProgress + startY * (1 - fadeAwayProgress);
        canvas.translate(gradientMidX, gradientMidY);

        // Then rotate it to the direction of the segment.
        double segmentAngleRad = Math.atan((endY - startY) / (endX - startX));
        float segmentAngleDegrees = (float) Math.toDegrees(segmentAngleRad);
        if (endX - startX < 0) {
            // Arc tangent gives us angle degrees [-90; 90] thus to cover [90; 270] degrees we
            // need this hack.
            segmentAngleDegrees += 180f;
        }
        canvas.rotate(segmentAngleDegrees);

        // Pythagoras theorem.
        float segmentLength = (float) Math.hypot(endX - startX, endY - startY);

        // Draw the segment in coordinates aligned with shader coordinates.
        canvas.drawLine(/* startX= */ -segmentLength * fadeAwayProgress, /* startY= */
                0,/* stopX= */ segmentLength * (1 - fadeAwayProgress), /* stopY= */ 0, mPathPaint);

        canvas.restore();
        mPathPaint.setShader(null);
    }

    private float calculateLastSegmentAlpha(float x, float y, float lastX, float lastY) {
        float diffX = x - lastX;
        float diffY = y - lastY;
        float dist = (float) Math.sqrt(diffX*diffX + diffY*diffY);
        float frac = dist/mSquareWidth;
        return Math.min(1f, Math.max(0f, (frac - 0.3f) * 4f));
    }

    private int getDotColor() {
        if (mInStealthMode) {
            // Always use the default color in this case
            return mDotColor;
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            // the pattern is wrong
            return mErrorColor;
        }
        return mDotColor;
    }

    private int getCurrentColor(boolean partOfPattern) {
        if (!partOfPattern || mInStealthMode || mPatternInProgress) {
            // unselected circle
            return mRegularColor;
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            // the pattern is wrong
            return mErrorColor;
        } else if (mPatternDisplayMode == DisplayMode.Correct ||
                mPatternDisplayMode == DisplayMode.Animate) {
            return mSuccessColor;
        } else {
            throw new IllegalStateException("unknown display mode " + mPatternDisplayMode);
        }
    }

    /**
     * @param partOfPattern Whether this circle is part of the pattern.
     */
    private void drawCircle(Canvas canvas, float centerX, float centerY, float radius,
            boolean partOfPattern, float alpha, float activationAnimationProgress) {
        if (mFadePattern && !mInStealthMode) {
            int resultColor = ColorUtils.blendARGB(mDotColor, mDotActivatedColor,
                    /* ratio= */ activationAnimationProgress);
            mPaint.setColor(resultColor);
        } else {
            mPaint.setColor(getDotColor());
        }
        mPaint.setAlpha((int) (alpha * 255));
        canvas.drawCircle(centerX, centerY, radius, mPaint);
    }

    /**
     * @param partOfPattern Whether this circle is part of the pattern.
     */
    private void drawCellDrawable(Canvas canvas, int i, int j, float radius,
            boolean partOfPattern) {
        Rect dst = new Rect(
            (int) (mPaddingLeft + j * mSquareWidth),
            (int) (mPaddingTop + i * mSquareHeight),
            (int) (mPaddingLeft + (j + 1) * mSquareWidth),
            (int) (mPaddingTop + (i + 1) * mSquareHeight));
        float scale = radius / (mDotSize / 2);

        // Only draw on this square with the appropriate scale.
        canvas.save();
        canvas.clipRect(dst);
        canvas.scale(scale, scale, dst.centerX(), dst.centerY());
        if (!partOfPattern || scale > 1) {
            mNotSelectedDrawable.draw(canvas);
        } else {
            mSelectedDrawable.draw(canvas);
        }
        canvas.restore();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        byte[] patternBytes = LockPatternUtils.patternToByteArray(mPattern);
        String patternString = patternBytes != null ? new String(patternBytes) : null;
        return new SavedState(superState,
                patternString,
                mPatternDisplayMode.ordinal(),
                mInputEnabled, mInStealthMode, mEnableHapticFeedback);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        final SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        setPattern(
                DisplayMode.Correct,
                LockPatternUtils.byteArrayToPattern(ss.getSerializedPattern().getBytes()));
        mPatternDisplayMode = DisplayMode.values()[ss.getDisplayMode()];
        mInputEnabled = ss.isInputEnabled();
        mInStealthMode = ss.isInStealthMode();
        mEnableHapticFeedback = ss.isTactileFeedbackEnabled();
    }

    /**
     * The parecelable for saving and restoring a lock pattern view.
     */
    private static class SavedState extends BaseSavedState {

        private final String mSerializedPattern;
        private final int mDisplayMode;
        private final boolean mInputEnabled;
        private final boolean mInStealthMode;
        private final boolean mTactileFeedbackEnabled;

        /**
         * Constructor called from {@link LockPatternView#onSaveInstanceState()}
         */
        @UnsupportedAppUsage
        private SavedState(Parcelable superState, String serializedPattern, int displayMode,
                boolean inputEnabled, boolean inStealthMode, boolean tactileFeedbackEnabled) {
            super(superState);
            mSerializedPattern = serializedPattern;
            mDisplayMode = displayMode;
            mInputEnabled = inputEnabled;
            mInStealthMode = inStealthMode;
            mTactileFeedbackEnabled = tactileFeedbackEnabled;
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        @UnsupportedAppUsage
        private SavedState(Parcel in) {
            super(in);
            mSerializedPattern = in.readString();
            mDisplayMode = in.readInt();
            mInputEnabled = (Boolean) in.readValue(null);
            mInStealthMode = (Boolean) in.readValue(null);
            mTactileFeedbackEnabled = (Boolean) in.readValue(null);
        }

        public String getSerializedPattern() {
            return mSerializedPattern;
        }

        public int getDisplayMode() {
            return mDisplayMode;
        }

        public boolean isInputEnabled() {
            return mInputEnabled;
        }

        public boolean isInStealthMode() {
            return mInStealthMode;
        }

        public boolean isTactileFeedbackEnabled(){
            return mTactileFeedbackEnabled;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(mSerializedPattern);
            dest.writeInt(mDisplayMode);
            dest.writeValue(mInputEnabled);
            dest.writeValue(mInStealthMode);
            dest.writeValue(mTactileFeedbackEnabled);
        }

        @SuppressWarnings({ "unused", "hiding" }) // Found using reflection
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private final class PatternExploreByTouchHelper extends ExploreByTouchHelper {
        private Rect mTempRect = new Rect();
        private final SparseArray<VirtualViewContainer> mItems = new SparseArray<>();

        class VirtualViewContainer {
            public VirtualViewContainer(CharSequence description) {
                this.description = description;
            }
            CharSequence description;
        };

        public PatternExploreByTouchHelper(View forView) {
            super(forView);
            for (int i = VIRTUAL_BASE_VIEW_ID; i < VIRTUAL_BASE_VIEW_ID + 9; i++) {
                mItems.put(i, new VirtualViewContainer(getTextForVirtualView(i)));
            }
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            // This must use the same hit logic for the screen to ensure consistency whether
            // accessibility is on or off.
            int id = getVirtualViewIdForHit(x, y);
            return id;
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            if (DEBUG_A11Y) Log.v(TAG, "getVisibleVirtualViews(len=" + virtualViewIds.size() + ")");
            if (!mPatternInProgress) {
                return;
            }
            for (int i = VIRTUAL_BASE_VIEW_ID; i < VIRTUAL_BASE_VIEW_ID + 9; i++) {
                // Add all views. As views are added to the pattern, we remove them
                // from notification by making them non-clickable below.
                virtualViewIds.add(i);
            }
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            if (DEBUG_A11Y) Log.v(TAG, "onPopulateEventForVirtualView(" + virtualViewId + ")");
            // Announce this view
            VirtualViewContainer container = mItems.get(virtualViewId);
            if (container != null) {
                event.getText().add(container.description);
            }
        }

        @Override
        public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onPopulateAccessibilityEvent(host, event);
            if (!mPatternInProgress) {
                CharSequence contentDescription = getContext().getText(
                        com.android.internal.R.string.lockscreen_access_pattern_area);
                event.setContentDescription(contentDescription);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId, AccessibilityNodeInfo node) {
            if (DEBUG_A11Y) Log.v(TAG, "onPopulateNodeForVirtualView(view=" + virtualViewId + ")");

            // Node and event text and content descriptions are usually
            // identical, so we'll use the exact same string as before.
            node.setText(getTextForVirtualView(virtualViewId));
            node.setContentDescription(getTextForVirtualView(virtualViewId));

            if (mPatternInProgress) {
                node.setFocusable(true);

                if (isClickable(virtualViewId)) {
                    // Mark this node of interest by making it clickable.
                    node.addAction(AccessibilityAction.ACTION_CLICK);
                    node.setClickable(isClickable(virtualViewId));
                }
            }

            // Compute bounds for this object
            final Rect bounds = getBoundsForVirtualView(virtualViewId);
            if (DEBUG_A11Y) Log.v(TAG, "bounds:" + bounds.toString());
            node.setBoundsInParent(bounds);
        }

        private boolean isClickable(int virtualViewId) {
            // Dots are clickable if they're not part of the current pattern.
            if (virtualViewId != ExploreByTouchHelper.INVALID_ID) {
                int row = (virtualViewId - VIRTUAL_BASE_VIEW_ID) / 3;
                int col = (virtualViewId - VIRTUAL_BASE_VIEW_ID) % 3;
                if (row < 3) {
                    return !mPatternDrawLookup[row][col];
                }
            }
            return false;
        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action,
                Bundle arguments) {
            if (DEBUG_A11Y) Log.v(TAG, "onPerformActionForVirtualView(id=" + virtualViewId
                    + ", action=" + action);
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    // Click handling should be consistent with
                    // onTouchEvent(). This ensures that the view works the
                    // same whether accessibility is turned on or off.
                    return onItemClicked(virtualViewId);
                default:
                    if (DEBUG_A11Y) Log.v(TAG, "*** action not handled in "
                            + "onPerformActionForVirtualView(viewId="
                            + virtualViewId + "action=" + action + ")");
            }
            return false;
        }

        boolean onItemClicked(int index) {
            if (DEBUG_A11Y) Log.v(TAG, "onItemClicked(" + index + ")");

            // Since the item's checked state is exposed to accessibility
            // services through its AccessibilityNodeInfo, we need to invalidate
            // the item's virtual view. At some point in the future, the
            // framework will obtain an updated version of the virtual view.
            invalidateVirtualView(index);

            // We need to let the framework know what type of event
            // happened. Accessibility services may use this event to provide
            // appropriate feedback to the user.
            sendEventForVirtualView(index, AccessibilityEvent.TYPE_VIEW_CLICKED);

            return true;
        }

        private Rect getBoundsForVirtualView(int virtualViewId) {
            int ordinal = virtualViewId - VIRTUAL_BASE_VIEW_ID;
            final Rect bounds = mTempRect;
            final int row = ordinal / 3;
            final int col = ordinal % 3;
            float centerX = getCenterXForColumn(col);
            float centerY = getCenterYForRow(row);
            float cellheight = mSquareHeight * mHitFactor * 0.5f;
            float cellwidth = mSquareWidth * mHitFactor * 0.5f;
            bounds.left = (int) (centerX - cellwidth);
            bounds.right = (int) (centerX + cellwidth);
            bounds.top = (int) (centerY - cellheight);
            bounds.bottom = (int) (centerY + cellheight);
            return bounds;
        }

        private CharSequence getTextForVirtualView(int virtualViewId) {
            final Resources res = getResources();
            return res.getString(R.string.lockscreen_access_pattern_cell_added_verbose,
                    virtualViewId);
        }

        /**
         * Helper method to find which cell a point maps to
         *
         * if there's no hit.
         * @param x touch position x
         * @param y touch position y
         * @return VIRTUAL_BASE_VIEW_ID+id or 0 if no view was hit
         */
        private int getVirtualViewIdForHit(float x, float y) {
            final int rowHit = getRowHit(y);
            if (rowHit < 0) {
                return ExploreByTouchHelper.INVALID_ID;
            }
            final int columnHit = getColumnHit(x);
            if (columnHit < 0) {
                return ExploreByTouchHelper.INVALID_ID;
            }
            boolean dotAvailable = mPatternDrawLookup[rowHit][columnHit];
            int dotId = (rowHit * 3 + columnHit) + VIRTUAL_BASE_VIEW_ID;
            int view = dotAvailable ? dotId : ExploreByTouchHelper.INVALID_ID;
            if (DEBUG_A11Y) Log.v(TAG, "getVirtualViewIdForHit(" + x + "," + y + ") => "
                    + view + "avail =" + dotAvailable);
            return view;
        }
    }
}
