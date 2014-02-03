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
 * limitations under the License.
 */


package android.view;

import android.graphics.Rect;

/**
 * Describes a set of insets for window content.
 *
 * <p>WindowInsets are immutable and may be expanded to include more inset types in the future.
 * To adjust insets, use one of the supplied clone methods to obtain a new WindowInsets instance
 * with the adjusted properties.</p>
 *
 * @see View.OnApplyWindowInsetsListener
 * @see View#onApplyWindowInsets(WindowInsets)
 */
public class WindowInsets {
    private Rect mSystemWindowInsets;
    private Rect mWindowDecorInsets;
    private Rect mTempRect;

    private static final Rect EMPTY_RECT = new Rect(0, 0, 0, 0);

    /**
     * Since new insets may be added in the future that existing apps couldn't
     * know about, this fully empty constant shouldn't be made available to apps
     * since it would allow them to inadvertently consume unknown insets by returning it.
     * @hide
     */
    public static final WindowInsets EMPTY = new WindowInsets(EMPTY_RECT, EMPTY_RECT);

    /** @hide */
    public WindowInsets(Rect systemWindowInsets, Rect windowDecorInsets) {
        mSystemWindowInsets = systemWindowInsets;
        mWindowDecorInsets = windowDecorInsets;
    }

    /**
     * Construct a new WindowInsets, copying all values from a source WindowInsets.
     *
     * @param src Source to copy insets from
     */
    public WindowInsets(WindowInsets src) {
        mSystemWindowInsets = src.mSystemWindowInsets;
        mWindowDecorInsets = src.mWindowDecorInsets;
    }

    /** @hide */
    public WindowInsets(Rect systemWindowInsets) {
        mSystemWindowInsets = systemWindowInsets;
        mWindowDecorInsets = EMPTY_RECT;
    }

    /**
     * Used to provide a safe copy of the system window insets to pass through
     * to the existing fitSystemWindows method and other similar internals.
     * @hide
     */
    public Rect getSystemWindowInsets() {
        if (mTempRect == null) {
            mTempRect = new Rect();
        }
        mTempRect.set(mSystemWindowInsets);
        return mTempRect;
    }

    /**
     * Returns the left system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The left system window inset
     */
    public int getSystemWindowInsetLeft() {
        return mSystemWindowInsets.left;
    }

    /**
     * Returns the top system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The top system window inset
     */
    public int getSystemWindowInsetTop() {
        return mSystemWindowInsets.top;
    }

    /**
     * Returns the right system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The right system window inset
     */
    public int getSystemWindowInsetRight() {
        return mSystemWindowInsets.right;
    }

    /**
     * Returns the bottom system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The bottom system window inset
     */
    public int getSystemWindowInsetBottom() {
        return mSystemWindowInsets.bottom;
    }

    /**
     * Returns the left window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The left window decor inset
     */
    public int getWindowDecorInsetLeft() {
        return mWindowDecorInsets.left;
    }

    /**
     * Returns the top window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The top window decor inset
     */
    public int getWindowDecorInsetTop() {
        return mWindowDecorInsets.top;
    }

    /**
     * Returns the right window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The right window decor inset
     */
    public int getWindowDecorInsetRight() {
        return mWindowDecorInsets.right;
    }

    /**
     * Returns the bottom window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The bottom window decor inset
     */
    public int getWindowDecorInsetBottom() {
        return mWindowDecorInsets.bottom;
    }

    /**
     * Returns true if this WindowInsets has nonzero system window insets.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return true if any of the system window inset values are nonzero
     */
    public boolean hasSystemWindowInsets() {
        return mSystemWindowInsets.left != 0 || mSystemWindowInsets.top != 0 ||
                mSystemWindowInsets.right != 0 || mSystemWindowInsets.bottom != 0;
    }

    /**
     * Returns true if this WindowInsets has nonzero window decor insets.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return true if any of the window decor inset values are nonzero
     */
    public boolean hasWindowDecorInsets() {
        return mWindowDecorInsets.left != 0 || mWindowDecorInsets.top != 0 ||
                mWindowDecorInsets.right != 0 || mWindowDecorInsets.bottom != 0;
    }

    /**
     * Returns true if this WindowInsets has any nonzero insets.
     *
     * @return true if any inset values are nonzero
     */
    public boolean hasInsets() {
        return hasSystemWindowInsets() || hasWindowDecorInsets();
    }

    public WindowInsets cloneWithSystemWindowInsetsConsumed() {
        final WindowInsets result = new WindowInsets(this);
        result.mSystemWindowInsets = new Rect(0, 0, 0, 0);
        return result;
    }

    public WindowInsets cloneWithSystemWindowInsetsConsumed(boolean left, boolean top,
            boolean right, boolean bottom) {
        if (left || top || right || bottom) {
            final WindowInsets result = new WindowInsets(this);
            result.mSystemWindowInsets = new Rect(left ? 0 : mSystemWindowInsets.left,
                    top ? 0 : mSystemWindowInsets.top,
                    right ? 0 : mSystemWindowInsets.right,
                    bottom ? 0 : mSystemWindowInsets.bottom);
            return result;
        }
        return this;
    }

    public WindowInsets cloneWithSystemWindowInsets(int left, int top, int right, int bottom) {
        final WindowInsets result = new WindowInsets(this);
        result.mSystemWindowInsets = new Rect(left, top, right, bottom);
        return result;
    }

    public WindowInsets cloneWithWindowDecorInsetsConsumed() {
        final WindowInsets result = new WindowInsets(this);
        result.mWindowDecorInsets.set(0, 0, 0, 0);
        return result;
    }

    public WindowInsets cloneWithWindowDecorInsetsConsumed(boolean left, boolean top,
            boolean right, boolean bottom) {
        if (left || top || right || bottom) {
            final WindowInsets result = new WindowInsets(this);
            result.mWindowDecorInsets = new Rect(left ? 0 : mWindowDecorInsets.left,
                    top ? 0 : mWindowDecorInsets.top,
                    right ? 0 : mWindowDecorInsets.right,
                    bottom ? 0 : mWindowDecorInsets.bottom);
            return result;
        }
        return this;
    }

    public WindowInsets cloneWithWindowDecorInsets(int left, int top, int right, int bottom) {
        final WindowInsets result = new WindowInsets(this);
        result.mWindowDecorInsets = new Rect(left, top, right, bottom);
        return result;
    }

    @Override
    public String toString() {
        return "WindowInsets{systemWindowInsets=" + mSystemWindowInsets + " windowDecorInsets=" +
                mWindowDecorInsets + "}";
    }
}
