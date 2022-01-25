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
import android.content.res.Configuration;
import android.content.res.Configuration.Orientation;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.wm.shell.R;

/**
 * Container for Letterbox Education Dialog.
 */
// TODO(b/215316431): Add tests
public class LetterboxEduDialogLayout extends FrameLayout {

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

    /**
     * Register a callback for the dismiss button.
     * @param callback The callback to register
     */
    void setDismissOnClickListener(Runnable callback) {
        findViewById(R.id.letterbox_education_dialog_dismiss).setOnClickListener(
                view -> callback.run());
    }

    /**
     * Updates the layout with the given app info.
     * @param appIcon The name of the app
     * @param appIcon The icon of the app
     */
    void updateAppInfo(String appName, Drawable appIcon) {
        ((ImageView) findViewById(R.id.letterbox_education_icon)).setImageDrawable(appIcon);
        ((TextView) findViewById(R.id.letterbox_education_dialog_title)).setText(
                getResources().getString(R.string.letterbox_education_dialog_title, appName));
    }

    /**
     * Updates the layout according to the given orientation.
     * @param orientation The orientation of the display
     */
    void updateDisplayOrientation(@Orientation int orientation) {
        boolean isOrientationPortrait = orientation == Configuration.ORIENTATION_PORTRAIT;
        ((LetterboxEduDialogActionLayout) findViewById(
                R.id.letterbox_education_dialog_screen_rotation_action)).setText(
                isOrientationPortrait
                        ? R.string.letterbox_education_screen_rotation_landscape_text
                        : R.string.letterbox_education_screen_rotation_portrait_text);

        if (isOrientationPortrait) {
            ((LetterboxEduDialogActionLayout) findViewById(
                    R.id.letterbox_education_dialog_split_screen_action)).setIconRotation(90f);
        }

        findViewById(R.id.letterbox_education_dialog_reposition_action).setVisibility(
                isOrientationPortrait ? View.GONE : View.VISIBLE);
    }
}
