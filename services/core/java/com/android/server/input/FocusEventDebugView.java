/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.util.TypedValue.COMPLEX_UNIT_SP;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.LayoutTransition;
import android.annotation.AnyThread;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.AccelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 *  Displays focus events, such as physical keyboard KeyEvents and non-pointer MotionEvents on
 *  the screen.
 */
class FocusEventDebugView extends RelativeLayout {

    private static final String TAG = FocusEventDebugView.class.getSimpleName();

    private static final int KEY_FADEOUT_DURATION_MILLIS = 1000;
    private static final int KEY_TRANSITION_DURATION_MILLIS = 100;

    private static final int OUTER_PADDING_DP = 16;
    private static final int KEY_SEPARATION_MARGIN_DP = 16;
    private static final int KEY_VIEW_SIDE_PADDING_DP = 16;
    private static final int KEY_VIEW_VERTICAL_PADDING_DP = 8;
    private static final int KEY_VIEW_MIN_WIDTH_DP = 32;
    private static final int KEY_VIEW_TEXT_SIZE_SP = 12;
    private static final double ROTATY_GRAPH_HEIGHT_FRACTION = 0.5;

    private final InputManagerService mService;
    private final int mOuterPadding;
    private final DisplayMetrics mDm;

    // Tracks all keys that are currently pressed/down.
    private final Map<Pair<Integer /*deviceId*/, Integer /*scanCode*/>, PressedKeyView>
            mPressedKeys = new HashMap<>();

    @Nullable
    private FocusEventDebugGlobalMonitor mFocusEventDebugGlobalMonitor;
    @Nullable
    private PressedKeyContainer mPressedKeyContainer;
    @Nullable
    private PressedKeyContainer mPressedModifierContainer;
    private final Supplier<RotaryInputValueView> mRotaryInputValueViewFactory;
    @Nullable
    private RotaryInputValueView mRotaryInputValueView;
    private final Supplier<RotaryInputGraphView> mRotaryInputGraphViewFactory;
    @Nullable
    private RotaryInputGraphView mRotaryInputGraphView;

    @VisibleForTesting
    FocusEventDebugView(Context c, InputManagerService service,
            Supplier<RotaryInputValueView> rotaryInputValueViewFactory,
            Supplier<RotaryInputGraphView> rotaryInputGraphViewFactory) {
        super(c);
        setFocusableInTouchMode(true);

        mService = service;
        mRotaryInputValueViewFactory = rotaryInputValueViewFactory;
        mRotaryInputGraphViewFactory = rotaryInputGraphViewFactory;
        mDm = mContext.getResources().getDisplayMetrics();
        mOuterPadding = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, OUTER_PADDING_DP, mDm);
    }

    FocusEventDebugView(Context c, InputManagerService service) {
        this(c, service, () -> new RotaryInputValueView(c), () -> new RotaryInputGraphView(c));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int paddingBottom = 0;

        final RoundedCorner bottomLeft =
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
        if (bottomLeft != null && !insets.isRound()) {
            paddingBottom = bottomLeft.getRadius();
        }

        final RoundedCorner bottomRight =
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
        if (bottomRight != null && !insets.isRound()) {
            paddingBottom = Math.max(paddingBottom, bottomRight.getRadius());
        }

        if (insets.getDisplayCutout() != null) {
            paddingBottom =
                    Math.max(paddingBottom, insets.getDisplayCutout().getSafeInsetBottom());
        }

        setPadding(mOuterPadding, mOuterPadding, mOuterPadding, mOuterPadding + paddingBottom);
        setClipToPadding(false);
        invalidate();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        handleKeyEvent(event);
        return super.dispatchKeyEvent(event);
    }

    @AnyThread
    public void updateShowKeyPresses(boolean enabled) {
        post(() -> handleUpdateShowKeyPresses(enabled));
    }

    @AnyThread
    public void updateShowRotaryInput(boolean enabled) {
        post(() -> handleUpdateShowRotaryInput(enabled));
    }

    private void handleUpdateShowKeyPresses(boolean enabled) {
        if (enabled == showKeyPresses()) {
            return;
        }

        if (!enabled) {
            removeView(mPressedKeyContainer);
            mPressedKeyContainer = null;
            removeView(mPressedModifierContainer);
            mPressedModifierContainer = null;
            return;
        }

        mPressedKeyContainer = new PressedKeyContainer(mContext);
        mPressedKeyContainer.setOrientation(LinearLayout.HORIZONTAL);
        mPressedKeyContainer.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        mPressedKeyContainer.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        final var scroller = new HorizontalScrollView(mContext);
        scroller.addView(mPressedKeyContainer);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> scroller.fullScroll(View.FOCUS_RIGHT));
        scroller.setHorizontalFadingEdgeEnabled(true);
        LayoutParams scrollerLayoutParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        scrollerLayoutParams.addRule(ALIGN_PARENT_BOTTOM);
        scrollerLayoutParams.addRule(ALIGN_PARENT_RIGHT);
        addView(scroller, scrollerLayoutParams);

        mPressedModifierContainer = new PressedKeyContainer(mContext);
        mPressedModifierContainer.setOrientation(LinearLayout.VERTICAL);
        mPressedModifierContainer.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        LayoutParams modifierLayoutParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        modifierLayoutParams.addRule(ALIGN_PARENT_BOTTOM);
        modifierLayoutParams.addRule(ALIGN_PARENT_LEFT);
        modifierLayoutParams.addRule(LEFT_OF, scroller.getId());
        addView(mPressedModifierContainer, modifierLayoutParams);
    }

    @VisibleForTesting
    void handleUpdateShowRotaryInput(boolean enabled) {
        if (enabled == showRotaryInput()) {
            return;
        }

        if (!enabled) {
            mFocusEventDebugGlobalMonitor.dispose();
            mFocusEventDebugGlobalMonitor = null;
            removeView(mRotaryInputValueView);
            mRotaryInputValueView = null;
            removeView(mRotaryInputGraphView);
            mRotaryInputGraphView = null;
            return;
        }

        mFocusEventDebugGlobalMonitor = new FocusEventDebugGlobalMonitor(this, mService);

        mRotaryInputValueView = mRotaryInputValueViewFactory.get();
        LayoutParams valueLayoutParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        valueLayoutParams.addRule(CENTER_HORIZONTAL);
        valueLayoutParams.addRule(ALIGN_PARENT_BOTTOM);
        addView(mRotaryInputValueView, valueLayoutParams);

        mRotaryInputGraphView = mRotaryInputGraphViewFactory.get();
        LayoutParams graphLayoutParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                (int) (ROTATY_GRAPH_HEIGHT_FRACTION * mDm.heightPixels));
        graphLayoutParams.addRule(CENTER_IN_PARENT);
        addView(mRotaryInputGraphView, graphLayoutParams);
    }

    /** Report a key event to the debug view. */
    @AnyThread
    public void reportKeyEvent(KeyEvent event) {
        post(() -> handleKeyEvent(KeyEvent.obtain((KeyEvent) event)));
    }

    /** Report a motion event to the debug view. */
    @AnyThread
    public void reportMotionEvent(MotionEvent event) {
        if (event.getSource() != InputDevice.SOURCE_ROTARY_ENCODER) {
            return;
        }

        post(() -> handleRotaryInput(MotionEvent.obtain((MotionEvent) event)));
    }

    private void handleKeyEvent(KeyEvent keyEvent) {
        if (!showKeyPresses()) {
            return;
        }

        final var identifier = new Pair<>(keyEvent.getDeviceId(), keyEvent.getScanCode());
        final var container = KeyEvent.isModifierKey(keyEvent.getKeyCode())
                ? mPressedModifierContainer
                : mPressedKeyContainer;
        PressedKeyView pressedKeyView = mPressedKeys.get(identifier);
        switch (keyEvent.getAction()) {
            case KeyEvent.ACTION_DOWN: {
                if (pressedKeyView != null) {
                    if (keyEvent.getRepeatCount() == 0) {
                        Slog.w(TAG, "Got key down for "
                                + KeyEvent.keyCodeToString(keyEvent.getKeyCode())
                                + " that was already tracked as being down.");
                        break;
                    }
                    container.handleKeyRepeat(pressedKeyView);
                    break;
                }

                pressedKeyView = new PressedKeyView(mContext, getLabel(keyEvent));
                mPressedKeys.put(identifier, pressedKeyView);
                container.handleKeyPressed(pressedKeyView);
                break;
            }
            case KeyEvent.ACTION_UP: {
                if (pressedKeyView == null) {
                    Slog.w(TAG, "Got key up for " + KeyEvent.keyCodeToString(keyEvent.getKeyCode())
                            + " that was not tracked as being down.");
                    break;
                }
                mPressedKeys.remove(identifier);
                container.handleKeyRelease(pressedKeyView);
                break;
            }
            default:
                break;
        }
        keyEvent.recycle();
    }

    @VisibleForTesting
    void handleRotaryInput(MotionEvent motionEvent) {
        if (!showRotaryInput()) {
            return;
        }

        float scrollAxisValue = motionEvent.getAxisValue(MotionEvent.AXIS_SCROLL);
        mRotaryInputValueView.updateValue(scrollAxisValue);
        mRotaryInputGraphView.addValue(scrollAxisValue, motionEvent.getEventTime());

        motionEvent.recycle();
    }

    private static String getLabel(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_SPACE:
                return "\u2423";
            case KeyEvent.KEYCODE_TAB:
                return "\u21e5";
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return "\u23CE";
            case KeyEvent.KEYCODE_DEL:
                return "\u232B";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "\u2326";
            case KeyEvent.KEYCODE_ESCAPE:
                return "ESC";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "\u2191";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "\u2193";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "\u2190";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "\u2192";
            case KeyEvent.KEYCODE_DPAD_UP_RIGHT:
                return "\u2197";
            case KeyEvent.KEYCODE_DPAD_UP_LEFT:
                return "\u2196";
            case KeyEvent.KEYCODE_DPAD_DOWN_RIGHT:
                return "\u2198";
            case KeyEvent.KEYCODE_DPAD_DOWN_LEFT:
                return "\u2199";
            default:
                break;
        }

        final int unicodeChar = event.getUnicodeChar();
        if (unicodeChar != 0) {
            return new String(Character.toChars(unicodeChar));
        }

        final var label = KeyEvent.keyCodeToString(event.getKeyCode());
        if (label.startsWith("KEYCODE_")) {
            return label.substring(8);
        }
        return label;
    }

    /** Determine whether to show key presses by checking one of the key-related objects. */
    private boolean showKeyPresses() {
        return mPressedKeyContainer != null;
    }

    /** Determine whether to show rotary input by checking one of the rotary-related objects. */
    private boolean showRotaryInput() {
        return mRotaryInputValueView != null;
    }

    /**
     * Converts a dimension in scaled pixel units to integer display pixels.
     */
    private static int applyDimensionSp(int dimensionSp, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_SP, dimensionSp, dm);
    }

    private static class PressedKeyView extends TextView {

        private static final ColorFilter sInvertColors = new ColorMatrixColorFilter(new float[]{
                -1.0f,     0,     0,    0, 255, // red
                0, -1.0f,     0,    0, 255, // green
                0,     0, -1.0f,    0, 255, // blue
                0,     0,     0, 1.0f, 0    // alpha
        });

        PressedKeyView(Context c, String label) {
            super(c);

            final var dm = c.getResources().getDisplayMetrics();
            final int keyViewSidePadding =
                    (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, KEY_VIEW_SIDE_PADDING_DP, dm);
            final int keyViewVerticalPadding =
                    (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, KEY_VIEW_VERTICAL_PADDING_DP,
                            dm);
            final int keyViewMinWidth =
                    (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, KEY_VIEW_MIN_WIDTH_DP, dm);
            final int textSize =
                    (int) TypedValue.applyDimension(COMPLEX_UNIT_SP, KEY_VIEW_TEXT_SIZE_SP, dm);

            setText(label);
            setGravity(Gravity.CENTER);
            setMinimumWidth(keyViewMinWidth);
            setTextSize(textSize);
            setTypeface(Typeface.SANS_SERIF);
            setBackgroundResource(R.drawable.focus_event_pressed_key_background);
            setPaddingRelative(keyViewSidePadding, keyViewVerticalPadding, keyViewSidePadding,
                    keyViewVerticalPadding);

            setHighlighted(true);
        }

        void setHighlighted(boolean isHighlighted) {
            if (isHighlighted) {
                setTextColor(Color.BLACK);
                getBackground().setColorFilter(sInvertColors);
            } else {
                setTextColor(Color.WHITE);
                getBackground().clearColorFilter();
            }
            invalidate();
        }
    }

    private static class PressedKeyContainer extends LinearLayout {

        private final MarginLayoutParams mPressedKeyLayoutParams;

        PressedKeyContainer(Context c) {
            super(c);

            final var dm = c.getResources().getDisplayMetrics();
            final int keySeparationMargin =
                    (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, KEY_SEPARATION_MARGIN_DP, dm);

            final var transition = new LayoutTransition();
            transition.disableTransitionType(LayoutTransition.APPEARING);
            transition.disableTransitionType(LayoutTransition.DISAPPEARING);
            transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            transition.setDuration(KEY_TRANSITION_DURATION_MILLIS);
            setLayoutTransition(transition);

            mPressedKeyLayoutParams = new MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            if (getOrientation() == VERTICAL) {
                mPressedKeyLayoutParams.setMargins(0, keySeparationMargin, 0, 0);
            } else {
                mPressedKeyLayoutParams.setMargins(keySeparationMargin, 0, 0, 0);
            }
        }

        public void handleKeyPressed(PressedKeyView pressedKeyView) {
            addView(pressedKeyView, getChildCount(), mPressedKeyLayoutParams);
            invalidate();
        }

        public void handleKeyRepeat(PressedKeyView repeatedKeyView) {
            // Do nothing for now.
        }

        public void handleKeyRelease(PressedKeyView releasedKeyView) {
            releasedKeyView.setHighlighted(false);
            releasedKeyView.clearAnimation();
            releasedKeyView.animate()
                    .alpha(0)
                    .setDuration(KEY_FADEOUT_DURATION_MILLIS)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(this::cleanUpPressedKeyViews)
                    .start();
        }

        private void cleanUpPressedKeyViews() {
            int numChildrenToRemove = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getAlpha() != 0) {
                    break;
                }
                child.setVisibility(View.GONE);
                child.clearAnimation();
                numChildrenToRemove++;
            }
            removeViews(0, numChildrenToRemove);
            invalidate();
        }
    }

    // TODO(b/286086154): move RotaryInputGraphView and RotaryInputValueView to a subpackage.

    /** Draws the most recent rotary input value and indicates whether the source is active. */
    @VisibleForTesting
    static class RotaryInputValueView extends TextView {

        private static final int INACTIVE_TEXT_COLOR = 0xffff00ff;
        private static final int ACTIVE_TEXT_COLOR = 0xff420f28;
        private static final int TEXT_SIZE_SP = 8;
        private static final int SIDE_PADDING_SP = 4;
        /** Determines how long the active status lasts. */
        private static final int ACTIVE_STATUS_DURATION = 250 /* milliseconds */;
        private static final ColorFilter ACTIVE_BACKGROUND_FILTER =
                new ColorMatrixColorFilter(new float[]{
                        0, 0, 0, 0, 255, // red
                        0, 0, 0, 0,   0, // green
                        0, 0, 0, 0, 255, // blue
                        0, 0, 0, 0, 200  // alpha
                });

        private final Runnable mUpdateActivityStatusCallback = () -> updateActivityStatus(false);
        private final float mScaledVerticalScrollFactor;

        @VisibleForTesting
        RotaryInputValueView(Context c) {
            super(c);

            DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
            mScaledVerticalScrollFactor = ViewConfiguration.get(c).getScaledVerticalScrollFactor();

            setText(getFormattedValue(0));
            setTextColor(INACTIVE_TEXT_COLOR);
            setTextSize(applyDimensionSp(TEXT_SIZE_SP, dm));
            setPaddingRelative(applyDimensionSp(SIDE_PADDING_SP, dm), 0,
                    applyDimensionSp(SIDE_PADDING_SP, dm), 0);
            setTypeface(null, Typeface.BOLD);
            setBackgroundResource(R.drawable.focus_event_rotary_input_background);
        }

        void updateValue(float value) {
            removeCallbacks(mUpdateActivityStatusCallback);

            setText(getFormattedValue(value * mScaledVerticalScrollFactor));

            updateActivityStatus(true);
            postDelayed(mUpdateActivityStatusCallback, ACTIVE_STATUS_DURATION);
        }

        @VisibleForTesting
        void updateActivityStatus(boolean active) {
            if (active) {
                setTextColor(ACTIVE_TEXT_COLOR);
                getBackground().setColorFilter(ACTIVE_BACKGROUND_FILTER);
            } else {
                setTextColor(INACTIVE_TEXT_COLOR);
                getBackground().clearColorFilter();
            }
        }

        private static String getFormattedValue(float value) {
            return String.format("%s%.1f", value < 0 ? "-" : "+", Math.abs(value));
        }
    }

    /**
     * Shows a graph with the rotary input values as a function of time.
     * The graph gets reset if no action is received for a certain amount of time.
     */
    @VisibleForTesting
    static class RotaryInputGraphView extends View {

        private static final int FRAME_COLOR = 0xbf741b47;
        private static final int FRAME_WIDTH_SP = 2;
        private static final int FRAME_BORDER_GAP_SP = 10;
        private static final int FRAME_TEXT_SIZE_SP = 10;
        private static final int FRAME_TEXT_OFFSET_SP = 2;
        private static final int GRAPH_COLOR = 0xffff00ff;
        private static final int GRAPH_LINE_WIDTH_SP = 1;
        private static final int GRAPH_POINT_RADIUS_SP = 4;
        private static final long MAX_SHOWN_TIME_INTERVAL = TimeUnit.SECONDS.toMillis(5);
        private static final float DEFAULT_FRAME_CENTER_POSITION = 0;
        private static final int MAX_GRAPH_VALUES_SIZE = 400;
        /** Maximum time between values so that they are considered part of the same gesture. */
        private static final long MAX_GESTURE_TIME = TimeUnit.SECONDS.toMillis(1);

        private final DisplayMetrics mDm;
        /**
         * Distance in position units (amount scrolled in display pixels) from the center to the
         * top/bottom frame lines.
         */
        private final float mFrameCenterToBorderDistance;
        private final float mScaledVerticalScrollFactor;
        private final Locale mDefaultLocale;
        private final Paint mFramePaint = new Paint();
        private final Paint mFrameTextPaint = new Paint();
        private final Paint mGraphLinePaint = new Paint();
        private final Paint mGraphPointPaint = new Paint();

        private final CyclicBuffer mGraphValues = new CyclicBuffer(MAX_GRAPH_VALUES_SIZE);
        /** Position at which graph values are placed at the center of the graph. */
        private float mFrameCenterPosition = DEFAULT_FRAME_CENTER_POSITION;

        @VisibleForTesting
        RotaryInputGraphView(Context c) {
            super(c);

            mDm = mContext.getResources().getDisplayMetrics();
            // This makes the center-to-border distance equivalent to the display height, meaning
            // that the total height of the graph is equivalent to 2x the display height.
            mFrameCenterToBorderDistance = mDm.heightPixels;
            mScaledVerticalScrollFactor = ViewConfiguration.get(c).getScaledVerticalScrollFactor();
            mDefaultLocale = Locale.getDefault();

            mFramePaint.setColor(FRAME_COLOR);
            mFramePaint.setStrokeWidth(applyDimensionSp(FRAME_WIDTH_SP, mDm));

            mFrameTextPaint.setColor(GRAPH_COLOR);
            mFrameTextPaint.setTextSize(applyDimensionSp(FRAME_TEXT_SIZE_SP, mDm));

            mGraphLinePaint.setColor(GRAPH_COLOR);
            mGraphLinePaint.setStrokeWidth(applyDimensionSp(GRAPH_LINE_WIDTH_SP, mDm));
            mGraphLinePaint.setStrokeCap(Paint.Cap.ROUND);
            mGraphLinePaint.setStrokeJoin(Paint.Join.ROUND);

            mGraphPointPaint.setColor(GRAPH_COLOR);
            mGraphPointPaint.setStrokeWidth(applyDimensionSp(GRAPH_POINT_RADIUS_SP, mDm));
            mGraphPointPaint.setStrokeCap(Paint.Cap.ROUND);
            mGraphPointPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        /**
         * Reads new scroll axis value and updates the list accordingly. Old positions are
         * kept at the front (what you would get with getFirst), while the recent positions are
         * kept at the back (what you would get with getLast). Also updates the frame center
         * position to handle out-of-bounds cases.
         */
        void addValue(float scrollAxisValue, long eventTime) {
            // Remove values that are too old.
            while (mGraphValues.getSize() > 0
                    && (eventTime - mGraphValues.getFirst().mTime) > MAX_SHOWN_TIME_INTERVAL) {
                mGraphValues.removeFirst();
            }

            // If there are no recent values, reset the frame center.
            if (mGraphValues.getSize() == 0) {
                mFrameCenterPosition = DEFAULT_FRAME_CENTER_POSITION;
            }

            // Handle new value. We multiply the scroll axis value by the scaled scroll factor to
            // get the amount of pixels to be scrolled. We also compute the accumulated position
            // by adding the current value to the last one (if not empty).
            final float displacement = scrollAxisValue * mScaledVerticalScrollFactor;
            final float prevPos = (mGraphValues.getSize() == 0 ? 0 : mGraphValues.getLast().mPos);
            final float pos = prevPos + displacement;

            mGraphValues.add(pos, eventTime);

            // The difference between the distance of the most recent position from the center
            // frame (pos - mFrameCenterPosition) and the maximum allowed distance from the center
            // frame (mFrameCenterToBorderDistance).
            final float verticalDiff = Math.abs(pos - mFrameCenterPosition)
                    - mFrameCenterToBorderDistance;
            // If needed, translate frame.
            if (verticalDiff > 0) {
                final int sign = pos - mFrameCenterPosition < 0 ? -1 : 1;
                // Here, we update the center frame position by the exact amount needed for us to
                // stay within the maximum allowed distance from the center frame.
                mFrameCenterPosition += sign * verticalDiff;
            }

            // Redraw canvas.
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // Note: vertical coordinates in Canvas go from top to bottom,
            // that is bottomY > middleY > topY.
            final int verticalMargin = applyDimensionSp(FRAME_BORDER_GAP_SP, mDm);
            final int topY = verticalMargin;
            final int bottomY = getHeight() - verticalMargin;
            final int middleY = (topY + bottomY) / 2;

            // Note: horizontal coordinates in Canvas go from left to right,
            // that is rightX > leftX.
            final int leftX = 0;
            final int rightX = getWidth();

            // Draw the frame, which includes 3 lines that show the maximum,
            // minimum and middle positions of the graph.
            canvas.drawLine(leftX, topY, rightX, topY, mFramePaint);
            canvas.drawLine(leftX, middleY, rightX, middleY, mFramePaint);
            canvas.drawLine(leftX, bottomY, rightX, bottomY, mFramePaint);

            // Draw the position that each frame line corresponds to.
            final int frameTextOffset = applyDimensionSp(FRAME_TEXT_OFFSET_SP, mDm);
            canvas.drawText(
                    String.format(mDefaultLocale, "%.1f",
                            mFrameCenterPosition + mFrameCenterToBorderDistance),
                    leftX,
                    topY - frameTextOffset, mFrameTextPaint
            );
            canvas.drawText(
                    String.format(mDefaultLocale, "%.1f", mFrameCenterPosition),
                    leftX,
                    middleY - frameTextOffset, mFrameTextPaint
            );
            canvas.drawText(
                    String.format(mDefaultLocale, "%.1f",
                            mFrameCenterPosition - mFrameCenterToBorderDistance),
                    leftX,
                    bottomY - frameTextOffset, mFrameTextPaint
            );

            // If there are no graph values to be drawn, stop here.
            if (mGraphValues.getSize() == 0) {
                return;
            }

            // Draw the graph using the times and positions.
            // We start at the most recent value (which should be drawn at the right) and move
            // to the older values (which should be drawn to the left of more recent ones). Negative
            // indices are handled by circuling back to the end of the buffer.
            final long mostRecentTime = mGraphValues.getLast().mTime;
            float prevCoordX = 0;
            float prevCoordY = 0;
            float prevAge = 0;
            for (Iterator<GraphValue> iter = mGraphValues.reverseIterator(); iter.hasNext();) {
                final GraphValue value = iter.next();

                final int age = (int) (mostRecentTime - value.mTime);
                final float pos = value.mPos;

                // We get the horizontal coordinate in time units from left to right with
                // (MAX_SHOWN_TIME_INTERVAL - age). Then, we rescale it to match the canvas
                // units by dividing it by the time-domain length (MAX_SHOWN_TIME_INTERVAL)
                // and by multiplying it by the canvas length (rightX - leftX). Finally, we
                // offset the coordinate by adding it to leftX.
                final float coordX = leftX + ((float) (MAX_SHOWN_TIME_INTERVAL - age)
                        / MAX_SHOWN_TIME_INTERVAL) * (rightX - leftX);

                // We get the vertical coordinate in position units from middle to top with
                // (pos - mFrameCenterPosition). Then, we rescale it to match the canvas
                // units by dividing it by half of the position-domain length
                // (mFrameCenterToBorderDistance) and by multiplying it by half of the canvas
                // length (middleY - topY). Finally, we offset the coordinate by subtracting
                // it from middleY (we can't "add" here because the coordinate grows from top
                // to bottom).
                final float coordY = middleY - ((pos - mFrameCenterPosition)
                        / mFrameCenterToBorderDistance) * (middleY - topY);

                // Draw a point for this value.
                canvas.drawPoint(coordX, coordY, mGraphPointPaint);

                // If this value is part of the same gesture as the previous one, draw a line
                // between them. We ignore the first value (with age = 0).
                if (age != 0 && (age - prevAge) <= MAX_GESTURE_TIME) {
                    canvas.drawLine(prevCoordX, prevCoordY, coordX, coordY, mGraphLinePaint);
                }

                prevCoordX = coordX;
                prevCoordY = coordY;
                prevAge = age;
            }
        }

        @VisibleForTesting
        float getFrameCenterPosition() {
            return mFrameCenterPosition;
        }

        /**
         * Holds data needed to draw each entry in the graph.
         */
        private static class GraphValue {
            /** Position. */
            float mPos;
            /** Time when this value was added. */
            long mTime;

            GraphValue(float pos, long time) {
                this.mPos = pos;
                this.mTime = time;
            }
        }

        /**
         * Holds the graph values as a cyclic buffer. It has a fixed capacity, and it replaces the
         * old values with new ones to avoid creating new objects.
         */
        private static class CyclicBuffer {
            private final GraphValue[] mValues;
            private final int mCapacity;
            private int mSize = 0;
            private int mLastIndex = 0;

            // The iteration index and counter are here to make it easier to reset them.
            /** Determines the value currently pointed by the iterator. */
            private int mIteratorIndex;
            /** Counts how many values have been iterated through. */
            private int mIteratorCount;

            /** Used traverse the values in reverse order. */
            private final Iterator<GraphValue> mReverseIterator = new Iterator<GraphValue>() {
                @Override
                public boolean hasNext() {
                    return mIteratorCount <= mSize;
                }

                @Override
                public GraphValue next() {
                    // Returns the value currently pointed by the iterator and moves the iterator to
                    // the previous one.
                    mIteratorCount++;
                    return mValues[(mIteratorIndex-- + mCapacity) % mCapacity];
                }
            };

            CyclicBuffer(int capacity) {
                mCapacity = capacity;
                mValues = new GraphValue[capacity];
            }

            /**
             * Add new graph value. If there is an existing object, we replace its data with the
             * new one. With this, we re-use old objects instead of creating new ones.
             */
            void add(float pos, long time) {
                mLastIndex = (mLastIndex + 1) % mCapacity;
                if (mValues[mLastIndex] == null) {
                    mValues[mLastIndex] = new GraphValue(pos, time);
                } else {
                    final GraphValue oldValue = mValues[mLastIndex];
                    oldValue.mPos = pos;
                    oldValue.mTime = time;
                }

                // If needed, account for new value in the buffer size.
                if (mSize != mCapacity) {
                    mSize++;
                }
            }

            int getSize() {
                return mSize;
            }

            GraphValue getFirst() {
                final int distanceBetweenLastAndFirst = (mCapacity - mSize) + 1;
                final int firstIndex = (mLastIndex + distanceBetweenLastAndFirst) % mCapacity;
                return mValues[firstIndex];
            }

            GraphValue getLast() {
                return mValues[mLastIndex];
            }

            void removeFirst() {
                mSize--;
            }

            /** Returns an iterator pointing at the last value. */
            Iterator<GraphValue> reverseIterator() {
                mIteratorIndex = mLastIndex;
                mIteratorCount = 1;
                return mReverseIterator;
            }
        }
    }
}
