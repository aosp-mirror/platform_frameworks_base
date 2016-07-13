/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.volume;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.Objects;

/**
 * Zen mode information (and end button) attached to the bottom of the volume dialog.
 */
public class ZenFooter extends LinearLayout {
    private static final String TAG = Util.logTag(ZenFooter.class);

    private final Context mContext;
    private final SpTexts mSpTexts;

    private ImageView mIcon;
    private TextView mSummaryLine1;
    private TextView mSummaryLine2;
    private TextView mEndNowButton;
    private int mZen = -1;
    private ZenModeConfig mConfig;
    private ZenModeController mController;

    public ZenFooter(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mSpTexts = new SpTexts(mContext);
        final LayoutTransition layoutTransition = new LayoutTransition();
        layoutTransition.setDuration(new ValueAnimator().getDuration() / 2);
        setLayoutTransition(layoutTransition);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIcon = (ImageView) findViewById(R.id.volume_zen_icon);
        mSummaryLine1 = (TextView) findViewById(R.id.volume_zen_summary_line_1);
        mSummaryLine2 = (TextView) findViewById(R.id.volume_zen_summary_line_2);
        mEndNowButton = (TextView) findViewById(R.id.volume_zen_end_now);
        mSpTexts.add(mSummaryLine1);
        mSpTexts.add(mSummaryLine2);
        mSpTexts.add(mEndNowButton);
    }

    public void init(final ZenModeController controller) {
        mEndNowButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                controller.setZen(Global.ZEN_MODE_OFF, null, TAG);
            }
        });
        mZen = controller.getZen();
        mConfig = controller.getConfig();
        mController = controller;
        mController.addCallback(mZenCallback);
        update();
    }

    public void cleanup() {
        mController.removeCallback(mZenCallback);
    }

    private void setZen(int zen) {
        if (mZen == zen) return;
        mZen = zen;
        update();
    }

    private void setConfig(ZenModeConfig config) {
        if (Objects.equals(mConfig, config)) return;
        mConfig = config;
        update();
    }

    public boolean isZen() {
        return isZenPriority() || isZenAlarms() || isZenNone();
    }

    private boolean isZenPriority() {
        return mZen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
    }

    private boolean isZenAlarms() {
        return mZen == Global.ZEN_MODE_ALARMS;
    }

    private boolean isZenNone() {
        return mZen == Global.ZEN_MODE_NO_INTERRUPTIONS;
    }

    public void update() {
        mIcon.setImageResource(isZenNone() ? R.drawable.ic_dnd_total_silence : R.drawable.ic_dnd);
        final String line1 =
                isZenPriority() ? mContext.getString(R.string.interruption_level_priority)
                : isZenAlarms() ? mContext.getString(R.string.interruption_level_alarms)
                : isZenNone() ? mContext.getString(R.string.interruption_level_none)
                : null;
        Util.setText(mSummaryLine1, line1);

        final CharSequence line2 = ZenModeConfig.getConditionSummary(mContext, mConfig,
                                mController.getCurrentUser(), true /*shortVersion*/);
        Util.setText(mSummaryLine2, line2);
    }

    public void onConfigurationChanged() {
        Util.setText(mEndNowButton, mContext.getString(R.string.volume_zen_end_now));
        mSpTexts.update();
    }

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(int zen) {
            setZen(zen);
        }
        @Override
        public void onConfigChanged(ZenModeConfig config) {
            setConfig(config);
        }
    };
}
