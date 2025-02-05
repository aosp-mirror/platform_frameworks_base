/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.accessibility.autoclick;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.R;

public class AutoclickTypePanel {

    private final String TAG = AutoclickTypePanel.class.getSimpleName();

    private final Context mContext;

    private final View mContentView;

    private final WindowManager mWindowManager;

    // Whether the panel is expanded or not.
    private boolean mExpanded = false;

    private final LinearLayout mLeftClickButton;
    private final LinearLayout mRightClickButton;
    private final LinearLayout mDoubleClickButton;
    private final LinearLayout mDragButton;
    private final LinearLayout mScrollButton;

    public AutoclickTypePanel(Context context, WindowManager windowManager) {
        mContext = context;
        mWindowManager = windowManager;

        mContentView =
                LayoutInflater.from(context)
                        .inflate(R.layout.accessibility_autoclick_type_panel, null);
        mLeftClickButton =
                mContentView.findViewById(R.id.accessibility_autoclick_left_click_layout);
        mRightClickButton =
                mContentView.findViewById(R.id.accessibility_autoclick_right_click_layout);
        mDoubleClickButton =
                mContentView.findViewById(R.id.accessibility_autoclick_double_click_layout);
        mScrollButton = mContentView.findViewById(R.id.accessibility_autoclick_scroll_layout);
        mDragButton = mContentView.findViewById(R.id.accessibility_autoclick_drag_layout);

        initializeButtonState();
    }

    private void initializeButtonState() {
        mLeftClickButton.setOnClickListener(v -> togglePanelExpansion(mLeftClickButton));
        mRightClickButton.setOnClickListener(v -> togglePanelExpansion(mRightClickButton));
        mDoubleClickButton.setOnClickListener(v -> togglePanelExpansion(mDoubleClickButton));
        mScrollButton.setOnClickListener(v -> togglePanelExpansion(mScrollButton));
        mDragButton.setOnClickListener(v -> togglePanelExpansion(mDragButton));

        // Initializes panel as collapsed state and only displays the left click button.
        hideAllClickTypeButtons();
        mLeftClickButton.setVisibility(View.VISIBLE);
    }

    public void show() {
        mWindowManager.addView(mContentView, getLayoutParams());
    }

    public void hide() {
        mWindowManager.removeView(mContentView);
    }

    /** Toggles the panel expanded or collapsed state. */
    private void togglePanelExpansion(LinearLayout button) {
        if (mExpanded) {
            // If the panel is already in expanded state, we should collapse it by hiding all
            // buttons except the one user selected.
            hideAllClickTypeButtons();
            button.setVisibility(View.VISIBLE);
        } else {
            // If the panel is already collapsed, we just need to expand it.
            showAllClickTypeButtons();
        }

        // Toggle the state.
        mExpanded = !mExpanded;
    }

    /** Hide all buttons on the panel except pause and position buttons. */
    private void hideAllClickTypeButtons() {
        mLeftClickButton.setVisibility(View.GONE);
        mRightClickButton.setVisibility(View.GONE);
        mDoubleClickButton.setVisibility(View.GONE);
        mDragButton.setVisibility(View.GONE);
        mScrollButton.setVisibility(View.GONE);
    }

    /** Show all buttons on the panel except pause and position buttons. */
    private void showAllClickTypeButtons() {
        mLeftClickButton.setVisibility(View.VISIBLE);
        mRightClickButton.setVisibility(View.VISIBLE);
        mDoubleClickButton.setVisibility(View.VISIBLE);
        mDragButton.setVisibility(View.VISIBLE);
        mScrollButton.setVisibility(View.VISIBLE);
    }

    @VisibleForTesting
    boolean getExpansionStateForTesting() {
        return mExpanded;
    }

    @VisibleForTesting
    @NonNull
    View getContentViewForTesting() {
        return mContentView;
    }

    /**
     * Retrieves the layout params for AutoclickIndicatorView, used when it's added to the Window
     * Manager.
     */
    @NonNull
    private WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        layoutParams.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        layoutParams.setFitInsetsTypes(WindowInsets.Type.statusBars());
        layoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.setTitle(AutoclickTypePanel.class.getSimpleName());
        layoutParams.accessibilityTitle =
                mContext.getString(R.string.accessibility_autoclick_type_settings_panel_title);
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        // TODO(b/388847771): Compute position based on user interaction.
        layoutParams.x = 15;
        layoutParams.y = 90;
        layoutParams.gravity = Gravity.END | Gravity.BOTTOM;

        return layoutParams;
    }
}
