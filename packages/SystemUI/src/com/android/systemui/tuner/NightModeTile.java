/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.tuner;

import android.content.Intent;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.NightModeController;


public class NightModeTile extends QSTile<QSTile.State> implements NightModeController.Listener {

    public static final String NIGHT_MODE_SPEC = "night";

    private final NightModeController mNightModeController;

    private int mIndex;
    private String mCurrentValue;

    private boolean mCustomEnabled;
    private String[] mValues;
    private CharSequence[] mValueTitles;

    public NightModeTile(Host host) {
        super(host);
        mNightModeController = host.getNightModeController();
    }

    @Override
    public boolean isAvailable() {
        return Prefs.getBoolean(mContext, Key.QS_NIGHT_ADDED, false)
                && TunerService.isTunerEnabled(mContext);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mNightModeController.addListener(this);
            refreshState();
        } else {
            mNightModeController.removeListener(this);
        }
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(mContext, TunerActivity.class)
                .putExtra(NightModeFragment.EXTRA_SHOW_NIGHT_MODE, true);
    }

    @Override
    protected void handleClick() {
        mNightModeController.setNightMode(!mNightModeController.isEnabled());
        refreshState();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.night_mode);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        // TODO: Right now this is just a dropper, needs an actual night icon.
        boolean enabled = mNightModeController.isEnabled();
        state.icon = ResourceIcon.get(enabled ? R.drawable.ic_night_mode
                : R.drawable.ic_night_mode_disabled);
        state.label = mContext.getString(R.string.night_mode);
        state.contentDescription = mContext.getString(R.string.night_mode);
    }

    @Override
    public void onNightModeChanged() {
        refreshState();
    }

    @Override
    public void onTwilightAutoChanged() {
        // Don't care.
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_COLOR_MATRIX;
    }
}
