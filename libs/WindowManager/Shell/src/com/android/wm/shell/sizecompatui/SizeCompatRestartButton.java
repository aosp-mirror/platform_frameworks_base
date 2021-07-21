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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;

/** Button to restart the size compat activity. */
public class SizeCompatRestartButton extends FrameLayout implements View.OnClickListener,
        View.OnLongClickListener {

    private SizeCompatUILayout mLayout;

    public SizeCompatRestartButton(@NonNull Context context) {
        super(context);
    }

    public SizeCompatRestartButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SizeCompatRestartButton(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SizeCompatRestartButton(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    void inject(SizeCompatUILayout layout) {
        mLayout = layout;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        final ImageButton restartButton = findViewById(R.id.size_compat_restart_button);
        final ColorStateList color = ColorStateList.valueOf(Color.LTGRAY);
        final GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(color);
        restartButton.setBackground(new RippleDrawable(color, null /* content */, mask));
        restartButton.setOnClickListener(this);
        restartButton.setOnLongClickListener(this);
    }

    @Override
    public void onClick(View v) {
        mLayout.onRestartButtonClicked();
    }

    @Override
    public boolean onLongClick(View v) {
        mLayout.onRestartButtonLongClicked();
        return true;
    }
}
