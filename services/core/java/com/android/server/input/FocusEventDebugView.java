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
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
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
import java.util.Map;
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

    private final InputManagerService mService;
    private final int mOuterPadding;

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

    @VisibleForTesting
    FocusEventDebugView(Context c, InputManagerService service,
            Supplier<RotaryInputValueView> rotaryInputValueViewFactory) {
        super(c);
        setFocusableInTouchMode(true);

        mService = service;
        mRotaryInputValueViewFactory = rotaryInputValueViewFactory;
        final var dm = mContext.getResources().getDisplayMetrics();
        mOuterPadding = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, OUTER_PADDING_DP, dm);
    }

    FocusEventDebugView(Context c, InputManagerService service) {
        this(c, service, () -> new RotaryInputValueView(c));
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
            return;
        }

        mFocusEventDebugGlobalMonitor = new FocusEventDebugGlobalMonitor(this, mService);

        mRotaryInputValueView = mRotaryInputValueViewFactory.get();
        LayoutParams valueLayoutParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        valueLayoutParams.addRule(CENTER_HORIZONTAL);
        valueLayoutParams.addRule(ALIGN_PARENT_BOTTOM);
        addView(mRotaryInputValueView, valueLayoutParams);
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
}
