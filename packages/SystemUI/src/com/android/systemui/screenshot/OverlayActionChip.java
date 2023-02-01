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

import static java.util.Objects.requireNonNull;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;


/**
 * View for a chip with an icon and text.
 */
public class OverlayActionChip extends FrameLayout {

    private static final String TAG = "ScreenshotActionChip";

    private ImageView mIconView;
    private TextView mTextView;
    private boolean mIsPending = false;

    public OverlayActionChip(Context context) {
        this(context, null);
    }

    public OverlayActionChip(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OverlayActionChip(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public OverlayActionChip(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        mIconView = requireNonNull(findViewById(R.id.overlay_action_chip_icon));
        mTextView = requireNonNull(findViewById(R.id.overlay_action_chip_text));
        updatePadding(mTextView.getText().length() > 0);
    }

    @Override
    public void setPressed(boolean pressed) {
        // override pressed state to true if there is an action pending
        super.setPressed(mIsPending || pressed);
    }

    /**
     * Set chip icon and whether to tint with theme color
     */
    public void setIcon(Icon icon, boolean tint) {
        mIconView.setImageIcon(icon);
        if (!tint) {
            mIconView.setImageTintList(null);
        }
    }

    /**
     * Set chip text
     */
    public void setText(CharSequence text) {
        mTextView.setText(text);
        updatePadding(text.length() > 0);
    }

    /**
     * Set PendingIntent to be sent and Runnable to be run, when chip is clicked
     */
    public void setPendingIntent(PendingIntent intent, Runnable finisher) {
        setOnClickListener(v -> {
            try {
                intent.send();
                finisher.run();
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Intent cancelled", e);
            }
        });
    }

    /**
     * Set pressed state of chip (to be used when chip is clicked before underlying intent is ready)
     */
    public void setIsPending(boolean isPending) {
        mIsPending = isPending;
        setPressed(mIsPending);
    }

    private void updatePadding(boolean hasText) {
        LinearLayout.LayoutParams iconParams =
                (LinearLayout.LayoutParams) mIconView.getLayoutParams();
        LinearLayout.LayoutParams textParams =
                (LinearLayout.LayoutParams) mTextView.getLayoutParams();
        if (hasText) {
            int paddingHorizontal = mContext.getResources().getDimensionPixelSize(
                    R.dimen.overlay_action_chip_padding_horizontal);
            int spacing = mContext.getResources().getDimensionPixelSize(
                    R.dimen.overlay_action_chip_spacing);
            iconParams.setMarginStart(paddingHorizontal);
            iconParams.setMarginEnd(spacing);
            textParams.setMarginEnd(paddingHorizontal);
        } else {
            int paddingHorizontal = mContext.getResources().getDimensionPixelSize(
                    R.dimen.overlay_action_chip_icon_only_padding_horizontal);
            iconParams.setMarginStart(paddingHorizontal);
            iconParams.setMarginEnd(paddingHorizontal);
        }
        mIconView.setLayoutParams(iconParams);
        mTextView.setLayoutParams(textParams);
    }
}
