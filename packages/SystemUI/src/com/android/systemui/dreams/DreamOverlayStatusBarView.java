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

package com.android.systemui.dreams;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link DreamOverlayStatusBarView} is the view responsible for displaying the status bar in a
 * dream. The status bar displays conditional status icons such as "priority mode" and "no wifi".
 */
public class DreamOverlayStatusBarView extends ConstraintLayout {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATUS_ICON_" }, value = {
            STATUS_ICON_NOTIFICATIONS,
            STATUS_ICON_WIFI_UNAVAILABLE,
            STATUS_ICON_ALARM_SET,
            STATUS_ICON_MIC_CAMERA_DISABLED,
            STATUS_ICON_PRIORITY_MODE_ON
    })
    public @interface StatusIconType {}
    public static final int STATUS_ICON_NOTIFICATIONS = 0;
    public static final int STATUS_ICON_WIFI_UNAVAILABLE = 1;
    public static final int STATUS_ICON_ALARM_SET = 2;
    public static final int STATUS_ICON_MIC_CAMERA_DISABLED = 3;
    public static final int STATUS_ICON_PRIORITY_MODE_ON = 4;

    private final Map<Integer, View> mStatusIcons = new HashMap<>();

    public DreamOverlayStatusBarView(Context context) {
        this(context, null);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DreamOverlayStatusBarView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mStatusIcons.put(STATUS_ICON_WIFI_UNAVAILABLE,
                fetchStatusIconForResId(R.id.dream_overlay_wifi_status));
        mStatusIcons.put(STATUS_ICON_ALARM_SET,
                fetchStatusIconForResId(R.id.dream_overlay_alarm_set));
        mStatusIcons.put(STATUS_ICON_MIC_CAMERA_DISABLED,
                fetchStatusIconForResId(R.id.dream_overlay_camera_mic_off));
        mStatusIcons.put(STATUS_ICON_NOTIFICATIONS,
                fetchStatusIconForResId(R.id.dream_overlay_notification_indicator));
        mStatusIcons.put(STATUS_ICON_PRIORITY_MODE_ON,
                fetchStatusIconForResId(R.id.dream_overlay_priority_mode));
    }

    void showIcon(@StatusIconType int iconType, boolean show, @Nullable String contentDescription) {
        View icon = mStatusIcons.get(iconType);
        if (icon == null) {
            return;
        }
        if (show && contentDescription != null) {
            icon.setContentDescription(contentDescription);
        }
        icon.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View fetchStatusIconForResId(int resId) {
        final View statusIcon = findViewById(resId);
        return Objects.requireNonNull(statusIcon);
    }
}
