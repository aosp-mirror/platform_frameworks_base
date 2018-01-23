/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.app.StatusBarManager.DISABLE_NONE;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.provider.AlarmClock;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextClock;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.id;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;

public class QuickStatusBarHeader extends RelativeLayout
        implements CommandQueue.Callbacks, View.OnClickListener {

    private ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;

    private View mDate;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Resources res = getResources();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mDate = findViewById(R.id.date);
        mDate.setOnClickListener(this);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view

        updateResources();

        // Set light text on the header icons because they will always be on a black background
        int colorForeground = Utils.getColorAttr(getContext(), android.R.attr.colorForeground);
        Rect tintArea = new Rect(0, 0, 0, 0);
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        BatteryMeterView battery = findViewById(R.id.battery);
        battery.setFillColor(Color.WHITE);
        battery.setForceShowPercent(true);

        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
    }

    public int getCollapsedHeight() {
        return getHeight();
    }

    public int getExpandedHeight() {
        return getHeight();
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    public void setExpansion(float headerExpansionFraction) {
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        final int rawHeight = (int) getResources().getDimension(R.dimen.status_bar_header_height);
        getLayoutParams().height = disabled ? (rawHeight - mHeaderQsPanel.getHeight()) : rawHeight;
    }

    @Override
    public void onAttachedToWindow() {
        SysUiServiceProvider.getComponent(getContext(), CommandQueue.class).addCallbacks(this);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        SysUiServiceProvider.getComponent(getContext(), CommandQueue.class).removeCallbacks(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onClick(View v) {
        if (v == mDate) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        }
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
    }

    public void updateEverything() {
        post(() -> setClickable(false));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }
}
