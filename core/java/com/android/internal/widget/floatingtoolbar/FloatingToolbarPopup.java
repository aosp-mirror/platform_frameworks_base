/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.widget.floatingtoolbar;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupWindow;

import java.util.List;

/**
 * A popup window used by the {@link FloatingToolbar} to render menu items.
 *
 */
public interface FloatingToolbarPopup {

    /**
     * Sets the suggested dp width of this floating toolbar.
     * The actual width will be about this size but there are no guarantees that it will be exactly
     * the suggested width.
     */
    void setSuggestedWidth(int suggestedWidth);

    /**
     * Sets if the floating toolbar width changed.
     */
    void setWidthChanged(boolean widthChanged);

    /**
     * Shows this popup at the specified coordinates.
     * The specified coordinates may be adjusted to make sure the popup is entirely on-screen.
     */
    void show(List<MenuItem> menuItems, MenuItem.OnMenuItemClickListener menuItemClickListener,
            Rect contentRect);

    /**
     * Gets rid of this popup. If the popup isn't currently showing, this will be a no-op.
     */
    void dismiss();

    /**
     * Hides this popup. This is a no-op if this popup is not showing.
     * Use {@link #isHidden()} to distinguish between a hidden and a dismissed popup.
     */
    void hide();

    /**
     * Returns {@code true} if this popup is currently showing. {@code false} otherwise.
     */
    boolean isShowing();

    /**
     * Returns {@code true} if this popup is currently hidden. {@code false} otherwise.
     */
    boolean isHidden();

    /**
     * Makes this toolbar "outside touchable" and sets the onDismissListener.
     *
     * @param outsideTouchable if true, the popup will be made "outside touchable" and
     *      "non focusable". The reverse will happen if false.
     * @param onDismiss
     *
     * @return true if the "outsideTouchable" setting was modified. Otherwise returns false
     *
     * @see PopupWindow#setOutsideTouchable(boolean)
     * @see PopupWindow#setFocusable(boolean)
     * @see PopupWindow.OnDismissListener
     */
    boolean setOutsideTouchable(boolean outsideTouchable,
            @Nullable PopupWindow.OnDismissListener onDismiss);

    /**
     * Returns {@link LocalFloatingToolbarPopup} implementation.
     */
    static FloatingToolbarPopup createInstance(Context context, View parent) {
        return new LocalFloatingToolbarPopup(context, parent);
    }

}
