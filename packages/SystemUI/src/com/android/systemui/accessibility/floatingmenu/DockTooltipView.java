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

package com.android.systemui.accessibility.floatingmenu;

import android.content.Context;

import com.android.systemui.R;

/**
 * Dock tooltip view that shows the info about moving the Accessibility button to the edge to hide.
 */
class DockTooltipView extends BaseTooltipView {
    private final AccessibilityFloatingMenuView mAnchorView;

    DockTooltipView(Context context, AccessibilityFloatingMenuView anchorView) {
        super(context, anchorView);
        mAnchorView = anchorView;

        setDescription(
                getContext().getText(R.string.accessibility_floating_button_docking_tooltip));
    }

    @Override
    void hide() {
        super.hide();

        mAnchorView.stopTranslateXAnimation();
    }

    @Override
    void show() {
        super.show();

        mAnchorView.startTranslateXAnimation();
    }
}
