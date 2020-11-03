/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.annotation.ColorInt;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * View for a chip with an icon and text.
 */
public class ScreenshotActionChip extends FrameLayout {

    private static final String TAG = "ScreenshotActionChip";

    private ImageView mIcon;
    private TextView mText;
    private @ColorInt int mIconColor;

    public ScreenshotActionChip(Context context) {
        this(context, null);
    }

    public ScreenshotActionChip(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScreenshotActionChip(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ScreenshotActionChip(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mIconColor = context.getColor(R.color.global_screenshot_button_icon);
    }

    @Override
    protected void onFinishInflate() {
        mIcon = findViewById(R.id.screenshot_action_chip_icon);
        mText = findViewById(R.id.screenshot_action_chip_text);
    }

    void setIcon(Icon icon, boolean tint) {
        mIcon.setImageIcon(icon);
        if (!tint) {
            mIcon.setImageTintList(null);
        }
    }

    void setText(CharSequence text) {
        mText.setText(text);
    }

    void setPendingIntent(PendingIntent intent, Runnable finisher) {
        setOnClickListener(v -> {
            try {
                intent.send();
                finisher.run();
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Intent cancelled", e);
            }
        });
    }
}
