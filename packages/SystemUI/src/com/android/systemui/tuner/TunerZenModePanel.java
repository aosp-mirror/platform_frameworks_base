/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tuner;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.ZenModePanel;
import com.android.systemui.volume.ZenModePanel.Callback;

public class TunerZenModePanel extends LinearLayout implements OnClickListener {
    private static final String TAG = "TunerZenModePanel";

    private Callback mCallback;
    private ZenModePanel mZenModePanel;
    private View mHeaderSwitch;
    private int mZenMode;
    private ZenModeController mController;
    private View mButtons;
    private View mMoreSettings;
    private View mDone;
    private OnClickListener mDoneListener;
    private boolean mEditing;

    public TunerZenModePanel(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void init(ZenModeController zenModeController) {
        mController = zenModeController;
        mHeaderSwitch = findViewById(R.id.tuner_zen_switch);
        mHeaderSwitch.setVisibility(View.VISIBLE);
        mHeaderSwitch.setOnClickListener(this);
        mHeaderSwitch.findViewById(com.android.internal.R.id.up).setVisibility(View.GONE);
        ((TextView) mHeaderSwitch.findViewById(android.R.id.title)).setText(
                R.string.quick_settings_dnd_label);
        mZenModePanel = (ZenModePanel) findViewById(R.id.zen_mode_panel);
        mZenModePanel.init(zenModeController);
        mButtons = findViewById(R.id.tuner_zen_buttons);
        mMoreSettings = mButtons.findViewById(android.R.id.button2);
        mMoreSettings.setOnClickListener(this);
        ((TextView) mMoreSettings).setText(R.string.quick_settings_more_settings);
        mDone = mButtons.findViewById(android.R.id.button1);
        mDone.setOnClickListener(this);
        ((TextView) mDone).setText(R.string.quick_settings_done);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mEditing = false;
    }

    public void setCallback(Callback zenPanelCallback) {
        mCallback = zenPanelCallback;
        mZenModePanel.setCallback(zenPanelCallback);
    }

    @Override
    public void onClick(View v) {
        if (v == mHeaderSwitch) {
            mEditing = true;
            if (mZenMode == Global.ZEN_MODE_OFF) {
                mZenMode = Prefs.getInt(mContext, Prefs.Key.DND_FAVORITE_ZEN,
                        Global.ZEN_MODE_ALARMS);
                mController.setZen(mZenMode, null, TAG);
                postUpdatePanel();
            } else {
                mZenMode = Global.ZEN_MODE_OFF;
                mController.setZen(Global.ZEN_MODE_OFF, null, TAG);
                postUpdatePanel();
            }
        } else if (v == mMoreSettings) {
            Intent intent = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } else if (v == mDone) {
            mEditing = false;
            setVisibility(View.GONE);
            mDoneListener.onClick(v);
        }
    }

    public boolean isEditing() {
        return mEditing;
    }

    public void setZenState(int zenMode) {
        mZenMode = zenMode;
        postUpdatePanel();
    }

    private void postUpdatePanel() {
        // The complicated structure from reusing the same ZenPanel has resulted in some
        // unstableness/flickering from callbacks coming in quickly. To solve this just
        // post the UI updates a little bit.
        removeCallbacks(mUpdate);
        postDelayed(mUpdate, 40);
    }

    public void setDoneListener(OnClickListener onClickListener) {
        mDoneListener = onClickListener;
    }

    private void updatePanel() {
        boolean zenOn = mZenMode != Global.ZEN_MODE_OFF;
        ((Checkable) mHeaderSwitch.findViewById(android.R.id.toggle)).setChecked(zenOn);
        mZenModePanel.setVisibility(zenOn ? View.VISIBLE : View.GONE);
        mButtons.setVisibility(zenOn ? View.VISIBLE : View.GONE);
    }

    private final Runnable mUpdate = new Runnable() {
        @Override
        public void run() {
            updatePanel();
        }
    };
}
