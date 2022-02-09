/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterboxedu;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.android.wm.shell.R;

/**
 * Container for Letterbox Education Dialog and background dim.
 *
 * <p>This layout should fill the entire task and the background around the dialog acts as the
 * background dim which dismisses the dialog when clicked.
 */
// TODO(b/215316431): Add tests
class LetterboxEduDialogLayout extends FrameLayout {

    // The alpha of a background is a number between 0 (fully transparent) to 255 (fully opaque).
    // 204 is simply 255 * 0.8.
    private static final int BACKGROUND_DIM_ALPHA = 204;

    private LetterboxEduWindowManager mWindowManager;

    public LetterboxEduDialogLayout(Context context) {
        this(context, null);
    }

    public LetterboxEduDialogLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LetterboxEduDialogLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public LetterboxEduDialogLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    void inject(LetterboxEduWindowManager windowManager) {
        mWindowManager = windowManager;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Need to relayout after visibility changes since they affect size.
        mWindowManager.relayout();
    }

    /**
     * Register a callback for the dismiss button and background dim.
     *
     * @param callback The callback to register
     */
    void setDismissOnClickListener(Runnable callback) {
        findViewById(R.id.letterbox_education_dialog_dismiss_button).setOnClickListener(
                view -> callback.run());
        // Clicks on the background dim should also dismiss the dialog.
        setOnClickListener(view -> callback.run());
        // We add a no-op on-click listener to the dialog container so that clicks on it won't
        // propagate to the listener of the layout (which represents the background dim).
        findViewById(R.id.letterbox_education_dialog_container).setOnClickListener(view -> {});
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        getBackground().mutate().setAlpha(BACKGROUND_DIM_ALPHA);
    }
}
