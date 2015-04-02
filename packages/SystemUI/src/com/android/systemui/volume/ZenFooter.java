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
import android.content.res.Resources;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;

/**
 * Switch bar + zen mode panel (conditions) attached to the bottom of the volume dialog.
 */
public class ZenFooter extends LinearLayout {
    private static final String TAG = Util.logTag(ZenFooter.class);

    private final Context mContext;
    private final float mSecondaryAlpha;
    private final LayoutTransition mLayoutTransition;

    private ZenModeController mController;
    private Switch mSwitch;
    private ZenModePanel mZenModePanel;
    private View mZenModePanelButtons;
    private View mZenModePanelMoreButton;
    private View mZenModePanelDoneButton;
    private View mSwitchBar;
    private View mSwitchBarIcon;
    private View mSummary;
    private TextView mSummaryLine1;
    private TextView mSummaryLine2;
    private boolean mFooterExpanded;
    private int mZen = -1;
    private Callback mCallback;

    public ZenFooter(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mSecondaryAlpha = getFloat(context.getResources(), R.dimen.volume_secondary_alpha);
        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.setDuration(new ValueAnimator().getDuration() / 2);
        mLayoutTransition.disableTransitionType(LayoutTransition.DISAPPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
    }

    private static float getFloat(Resources r, int resId) {
        final TypedValue tv = new TypedValue();
        r.getValue(resId, tv, true);
        return tv.getFloat();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSwitchBar = findViewById(R.id.volume_zen_switch_bar);
        mSwitchBarIcon = findViewById(R.id.volume_zen_switch_bar_icon);
        mSwitch = (Switch) findViewById(R.id.volume_zen_switch);
        mZenModePanel = (ZenModePanel) findViewById(R.id.zen_mode_panel);
        mZenModePanelButtons = findViewById(R.id.volume_zen_mode_panel_buttons);
        mZenModePanelMoreButton = findViewById(R.id.volume_zen_mode_panel_more);
        mZenModePanelDoneButton = findViewById(R.id.volume_zen_mode_panel_done);
        mSummary = findViewById(R.id.volume_zen_panel_summary);
        mSummaryLine1 = (TextView) findViewById(R.id.volume_zen_panel_summary_line_1);
        mSummaryLine2 = (TextView) findViewById(R.id.volume_zen_panel_summary_line_2);
    }

    public void init(ZenModeController controller, Callback callback) {
        mCallback = callback;
        mController = controller;
        mZenModePanel.init(controller);
        mZenModePanel.setEmbedded(true);
        mSwitch.setOnCheckedChangeListener(mCheckedListener);
        mController.addCallback(new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                setZen(zen);
            }
            @Override
            public void onExitConditionChanged(Condition exitCondition) {
                update();
            }
        });
        mSwitchBar.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mSwitch.setChecked(!mSwitch.isChecked());
            }
        });
        mZenModePanelMoreButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCallback != null) {
                    mCallback.onSettingsClicked();
                }
            }
        });
        mZenModePanelDoneButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCallback != null) {
                    mCallback.onDoneClicked();
                }
            }
        });
        mZen = mController.getZen();
        update();
    }

    private void setZen(int zen) {
        if (mZen == zen) return;
        mZen = zen;
        update();
    }

    public boolean isZen() {
        return isZenPriority() || isZenNone();
    }

    private boolean isZenPriority() {
        return mZen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
    }

    private boolean isZenNone() {
        return mZen == Global.ZEN_MODE_NO_INTERRUPTIONS;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setLayoutTransition(null);
        setFooterExpanded(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setLayoutTransition(mLayoutTransition);
    }

    private boolean setFooterExpanded(boolean expanded) {
        if (mFooterExpanded == expanded) return false;
        mFooterExpanded = expanded;
        update();
        if (mCallback != null) {
            mCallback.onFooterExpanded();
        }
        return true;
    }

    public boolean isFooterExpanded() {
        return mFooterExpanded;
    }

    public void update() {
        final boolean isZen = isZen();
        mSwitch.setOnCheckedChangeListener(null);
        mSwitch.setChecked(isZen);
        mSwitch.setOnCheckedChangeListener(mCheckedListener);
        Util.setVisOrGone(mZenModePanel, isZen && mFooterExpanded);
        Util.setVisOrGone(mZenModePanelButtons, isZen && mFooterExpanded);
        Util.setVisOrGone(mSummary, isZen && !mFooterExpanded);
        mSwitchBarIcon.setAlpha(isZen ? 1 : mSecondaryAlpha);
        final String line1 =
                isZenPriority() ? mContext.getString(R.string.interruption_level_priority)
                : isZenNone() ? mContext.getString(R.string.interruption_level_none)
                : null;
        Util.setText(mSummaryLine1, line1);
        Util.setText(mSummaryLine2, mZenModePanel.getExitConditionText());
    }

    private final OnCheckedChangeListener mCheckedListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (D.BUG) Log.d(TAG, "onCheckedChanged " + isChecked);
            if (isChecked != isZen()) {
                final int newZen = isChecked ? Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        : Global.ZEN_MODE_OFF;
                mZen = newZen;  // this one's optimistic
                setFooterExpanded(isChecked);
                mController.setZen(newZen);
            }
        }
    };

    public interface Callback {
        void onFooterExpanded();
        void onSettingsClicked();
        void onDoneClicked();
    }
}
