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

import android.annotation.StringRes;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.wm.shell.R;

/**
 * Custom layout for Letterbox Education dialog action.
 */
// TODO(b/215316431): Add tests
class LetterboxEduDialogActionLayout extends FrameLayout {
    private final ImageView mIcon;
    private final TextView mText;

    LetterboxEduDialogActionLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray styledAttributes =
                context.getTheme().obtainStyledAttributes(
                        attrs,
                        R.styleable.LetterboxEduDialogActionLayout,
                        /* defStyleAttr= */ 0,
                        /* defStyleRes= */ 0);
        int iconId = styledAttributes.getResourceId(
                R.styleable.LetterboxEduDialogActionLayout_icon, 0);
        String optionalText = styledAttributes.getString(
                R.styleable.LetterboxEduDialogActionLayout_text);
        styledAttributes.recycle();

        View rootView = inflate(getContext(), R.layout.letterbox_education_dialog_action_layout,
                this);

        mIcon = rootView.findViewById(R.id.letterbox_education_dialog_action_icon);
        mIcon.setImageResource(iconId);
        mText = rootView.findViewById(R.id.letterbox_education_dialog_action_text);
        if (optionalText != null) {
            mText.setText(optionalText);
        }
    }

    void setText(@StringRes int id) {
        mText.setText(getResources().getString(id));
    }

    void setIconRotation(float rotation) {
        mIcon.setRotation(rotation);
    }
}
