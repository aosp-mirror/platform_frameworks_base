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
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.util.Pair;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.AccelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

import java.util.HashMap;
import java.util.Map;

/**
 *  Displays focus events, such as physical keyboard KeyEvents and non-pointer MotionEvents on
 *  the screen.
 */
class FocusEventDebugView extends LinearLayout {

    private static final String TAG = FocusEventDebugView.class.getSimpleName();

    private static final int KEY_FADEOUT_DURATION_MILLIS = 1000;
    private static final int KEY_TRANSITION_DURATION_MILLIS = 100;

    private static final int OUTER_PADDING_DP = 16;
    private static final int KEY_SEPARATION_MARGIN_DP = 16;
    private static final int KEY_VIEW_SIDE_PADDING_DP = 16;
    private static final int KEY_VIEW_VERTICAL_PADDING_DP = 8;
    private static final int KEY_VIEW_MIN_WIDTH_DP = 32;
    private static final int KEY_VIEW_TEXT_SIZE_SP = 12;

    private final int mOuterPadding;

    // Tracks all keys that are currently pressed/down.
    private final Map<Pair<Integer /*deviceId*/, Integer /*scanCode*/>, PressedKeyView>
            mPressedKeys = new HashMap<>();

    private final PressedKeyContainer mPressedKeyContainer;
    private final PressedKeyContainer mPressedModifierContainer;

    FocusEventDebugView(Context c) {
        super(c);
        setFocusableInTouchMode(true);

        final var dm = mContext.getResources().getDisplayMetrics();
        mOuterPadding = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, OUTER_PADDING_DP, dm);

        setOrientation(HORIZONTAL);
        setLayoutDirection(LAYOUT_DIRECTION_RTL);
        setGravity(Gravity.START | Gravity.BOTTOM);

        mPressedKeyContainer = new PressedKeyContainer(mContext);
        mPressedKeyContainer.setOrientation(HORIZONTAL);
        mPressedKeyContainer.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        mPressedKeyContainer.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        final var scroller = new HorizontalScrollView(mContext);
        scroller.addView(mPressedKeyContainer);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> scroller.fullScroll(View.FOCUS_RIGHT));
        scroller.setHorizontalFadingEdgeEnabled(true);
        addView(scroller, new LayoutParams(0, WRAP_CONTENT, 1));

        mPressedModifierContainer = new PressedKeyContainer(mContext);
        mPressedModifierContainer.setOrientation(VERTICAL);
        mPressedModifierContainer.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        addView(mPressedModifierContainer, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int paddingBottom = 0;

        final RoundedCorner bottomLeft =
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
        if (bottomLeft != null) {
            paddingBottom = bottomLeft.getRadius();
        }

        final RoundedCorner bottomRight =
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
        if (bottomRight != null) {
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

    /** Report an input event to the debug view. */
    @AnyThread
    public void reportEvent(InputEvent event) {
        if (!(event instanceof KeyEvent)) {
            // TODO: Support non-pointer MotionEvents.
            return;
        }
        post(() -> handleKeyEvent(KeyEvent.obtain((KeyEvent) event)));
    }

    private void handleKeyEvent(KeyEvent keyEvent) {
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
}
