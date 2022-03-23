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

package com.android.wm.shell.sizecompatui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;

/** Popup to show the hint about the {@link SizeCompatRestartButton}. */
public class SizeCompatHintPopup extends FrameLayout implements View.OnClickListener {

    private SizeCompatUILayout mLayout;

    public SizeCompatHintPopup(Context context) {
        super(context);
    }

    public SizeCompatHintPopup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SizeCompatHintPopup(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SizeCompatHintPopup(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    void inject(SizeCompatUILayout layout) {
        mLayout = layout;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        final Button gotItButton = findViewById(R.id.got_it);
        gotItButton.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.LTGRAY),
                null /* content */, null /* mask */));
        gotItButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        mLayout.dismissHint();
    }
}
