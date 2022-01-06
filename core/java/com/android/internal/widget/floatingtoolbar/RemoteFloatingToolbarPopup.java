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

import android.content.Context;
import android.graphics.Rect;
import android.view.MenuItem;
import android.view.View;
import android.view.selectiontoolbar.SelectionToolbarManager;
import android.widget.PopupWindow;

import java.util.List;
import java.util.Objects;

/**
 * A popup window used by the floating toolbar to render menu items in the remote system process.
 *
 * It holds 2 panels (i.e. main panel and overflow panel) and an overflow button
 * to transition between panels.
 */
public final class RemoteFloatingToolbarPopup implements FloatingToolbarPopup {

    private final SelectionToolbarManager mSelectionToolbarManager;
    // Parent for the popup window.
    private final View mParent;

    public RemoteFloatingToolbarPopup(Context context, View parent) {
        // TODO: implement it
        mParent = Objects.requireNonNull(parent);
        mSelectionToolbarManager = context.getSystemService(SelectionToolbarManager.class);
    }

    @Override
    public void show(List<MenuItem> menuItems,
            MenuItem.OnMenuItemClickListener menuItemClickListener, Rect contentRect) {
        // TODO: implement it
    }

    @Override
    public void hide() {
        // TODO: implement it
    }

    @Override
    public void setSuggestedWidth(int suggestedWidth) {
        // TODO: implement it
    }

    @Override
    public void setWidthChanged(boolean widthChanged) {
        // no-op
    }

    @Override
    public void dismiss() {
        // TODO: implement it
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public boolean isShowing() {
        return false;
    }

    @Override
    public boolean setOutsideTouchable(boolean outsideTouchable,
            PopupWindow.OnDismissListener onDismiss) {
        return false;
    }
}
