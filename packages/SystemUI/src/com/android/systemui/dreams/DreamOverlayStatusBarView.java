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
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.res.R;
import com.android.systemui.shared.shadow.DoubleShadowIconDrawable;
import com.android.systemui.shared.shadow.DoubleShadowTextHelper.ShadowInfo;
import com.android.systemui.statusbar.AlphaOptimizedImageView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.List;
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
            STATUS_ICON_CAMERA_DISABLED,
            STATUS_ICON_MIC_DISABLED,
            STATUS_ICON_MIC_CAMERA_DISABLED,
            STATUS_ICON_PRIORITY_MODE_ON,
            STATUS_ICON_ASSISTANT_ATTENTION_ACTIVE,
    })
    public @interface StatusIconType {}
    public static final int STATUS_ICON_NOTIFICATIONS = 0;
    public static final int STATUS_ICON_WIFI_UNAVAILABLE = 1;
    public static final int STATUS_ICON_ALARM_SET = 2;
    public static final int STATUS_ICON_CAMERA_DISABLED = 3;
    public static final int STATUS_ICON_MIC_DISABLED = 4;
    public static final int STATUS_ICON_MIC_CAMERA_DISABLED = 5;
    public static final int STATUS_ICON_PRIORITY_MODE_ON = 6;
    public static final int STATUS_ICON_ASSISTANT_ATTENTION_ACTIVE = 7;

    private final Map<Integer, View> mStatusIcons = new HashMap<>();
    private Context mContext;
    private ViewGroup mSystemStatusViewGroup;
    private ViewGroup mExtraSystemStatusViewGroup;
    private ShadowInfo mKeyShadowInfo;
    private ShadowInfo mAmbientShadowInfo;
    private int mDrawableSize;
    private int mDrawableInsetSize;
    private static final float KEY_SHADOW_ALPHA = 0.35f;
    private static final float AMBIENT_SHADOW_ALPHA = 0.4f;

    public DreamOverlayStatusBarView(Context context) {
        this(context, null);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
        mContext = context;
    }

    public DreamOverlayStatusBarView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mKeyShadowInfo = createShadowInfo(
            R.dimen.dream_overlay_status_bar_key_text_shadow_radius,
            R.dimen.dream_overlay_status_bar_key_text_shadow_dx,
            R.dimen.dream_overlay_status_bar_key_text_shadow_dy,
            KEY_SHADOW_ALPHA
        );

        mAmbientShadowInfo = createShadowInfo(
            R.dimen.dream_overlay_status_bar_ambient_text_shadow_radius,
            R.dimen.dream_overlay_status_bar_ambient_text_shadow_dx,
            R.dimen.dream_overlay_status_bar_ambient_text_shadow_dy,
            AMBIENT_SHADOW_ALPHA
        );

        mDrawableSize = mContext
                        .getResources()
                        .getDimensionPixelSize(R.dimen.dream_overlay_status_bar_icon_size);
        mDrawableInsetSize = mContext
                             .getResources()
                             .getDimensionPixelSize(R.dimen.dream_overlay_icon_inset_dimen);

        mStatusIcons.put(STATUS_ICON_WIFI_UNAVAILABLE,
                addDoubleShadow(fetchStatusIconForResId(R.id.dream_overlay_wifi_status)));
        mStatusIcons.put(STATUS_ICON_ALARM_SET,
                addDoubleShadow(fetchStatusIconForResId(R.id.dream_overlay_alarm_set)));
        mStatusIcons.put(STATUS_ICON_CAMERA_DISABLED,
                fetchStatusIconForResId(R.id.dream_overlay_camera_off));
        mStatusIcons.put(STATUS_ICON_MIC_DISABLED,
                fetchStatusIconForResId(R.id.dream_overlay_mic_off));
        mStatusIcons.put(STATUS_ICON_MIC_CAMERA_DISABLED,
                fetchStatusIconForResId(R.id.dream_overlay_camera_mic_off));
        mStatusIcons.put(STATUS_ICON_NOTIFICATIONS,
                fetchStatusIconForResId(R.id.dream_overlay_notification_indicator));
        mStatusIcons.put(STATUS_ICON_PRIORITY_MODE_ON,
                addDoubleShadow(fetchStatusIconForResId(R.id.dream_overlay_priority_mode)));
        mStatusIcons.put(STATUS_ICON_ASSISTANT_ATTENTION_ACTIVE,
                fetchStatusIconForResId(R.id.dream_overlay_assistant_attention_indicator));

        mSystemStatusViewGroup = findViewById(R.id.dream_overlay_system_status);
        mExtraSystemStatusViewGroup = findViewById(R.id.dream_overlay_extra_items);
    }

    protected static String getLoggableStatusIconType(@StatusIconType int type) {
        return switch (type) {
            case STATUS_ICON_NOTIFICATIONS -> "notifications";
            case STATUS_ICON_WIFI_UNAVAILABLE -> "wifi_unavailable";
            case STATUS_ICON_ALARM_SET -> "alarm_set";
            case STATUS_ICON_CAMERA_DISABLED -> "camera_disabled";
            case STATUS_ICON_MIC_DISABLED -> "mic_disabled";
            case STATUS_ICON_MIC_CAMERA_DISABLED -> "mic_camera_disabled";
            case STATUS_ICON_PRIORITY_MODE_ON -> "priority_mode_on";
            case STATUS_ICON_ASSISTANT_ATTENTION_ACTIVE -> "assistant_attention_active";
            default -> type + "(unknown)";
        };
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
        mSystemStatusViewGroup.setVisibility(areAnyStatusIconsVisible() ? View.VISIBLE : View.GONE);
    }

    void setExtraStatusBarItemViews(List<View> views) {
        removeAllExtraStatusBarItemViews();
        views.forEach(view -> mExtraSystemStatusViewGroup.addView(view));
    }

    private View fetchStatusIconForResId(int resId) {
        final View statusIcon = findViewById(resId);
        return Objects.requireNonNull(statusIcon);
    }

    void removeAllExtraStatusBarItemViews() {
        mExtraSystemStatusViewGroup.removeAllViews();
    }

    private boolean areAnyStatusIconsVisible() {
        for (int i = 0; i < mSystemStatusViewGroup.getChildCount(); i++) {
            if (mSystemStatusViewGroup.getChildAt(i).getVisibility() == View.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private View addDoubleShadow(View icon) {
        if (icon instanceof AlphaOptimizedImageView) {
            AlphaOptimizedImageView i = (AlphaOptimizedImageView) icon;
            Drawable drawableIcon = i.getDrawable();
            i.setImageDrawable(new DoubleShadowIconDrawable(
                    mKeyShadowInfo,
                    mAmbientShadowInfo,
                    drawableIcon,
                    mDrawableSize,
                    mDrawableInsetSize
            ));
        }
        return icon;
    }

    private ShadowInfo createShadowInfo(int blurId, int offsetXId, int offsetYId, float alpha) {
        return new ShadowInfo(
            fetchDimensionForResId(blurId),
            fetchDimensionForResId(offsetXId),
            fetchDimensionForResId(offsetYId),
            alpha
        );
    }

    private Float fetchDimensionForResId(int resId) {
        return mContext
               .getResources()
               .getDimension(resId);
    }
}
