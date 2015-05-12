/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.provider.Settings.Secure;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.qs.UsageTracker;

/** Quick settings tile: Invert colors **/
public class ColorInversionTile extends QSTile<QSTile.BooleanState> {

    private final AnimationIcon mEnable
            = new AnimationIcon(R.drawable.ic_invert_colors_enable_animation);
    private final AnimationIcon mDisable
            = new AnimationIcon(R.drawable.ic_invert_colors_disable_animation);
    private final SecureSetting mSetting;
    private final UsageTracker mUsageTracker;

    private boolean mListening;

    public ColorInversionTile(Host host) {
        super(host);

        mSetting = new SecureSetting(mContext, mHandler,
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                if (value != 0 || observedChange) {
                    mUsageTracker.trackUsage();
                }
                if (mListening) {
                    handleRefreshState(value);
                }
            }
        };
        mUsageTracker = new UsageTracker(host.getContext(),
                Prefs.Key.COLOR_INVERSION_TILE_LAST_USED, ColorInversionTile.class,
                R.integer.days_to_show_color_inversion_tile);
        if (mSetting.getValue() != 0 && !mUsageTracker.isRecentlyUsed()) {
            mUsageTracker.trackUsage();
        }
        mUsageTracker.setListening(true);
        mSetting.setListening(true);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mUsageTracker.setListening(false);
        mSetting.setListening(false);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        mListening = listening;
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        handleRefreshState(mSetting.getValue());
    }

    @Override
    protected void handleClick() {
        MetricsLogger.action(mContext, getMetricsCategory(), !mState.value);
        mSetting.setValue(mState.value ? 0 : 1);
        mEnable.setAllowAnimation(true);
        mDisable.setAllowAnimation(true);
    }

    @Override
    protected void handleLongClick() {
        if (mState.value) return;  // don't allow usage reset if inversion is active
        final String title = mContext.getString(R.string.quick_settings_reset_confirmation_title,
                mState.label);
        mUsageTracker.showResetConfirmation(title, new Runnable() {
            @Override
            public void run() {
                refreshState();
            }
        });
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enabled = value != 0;
        state.visible = enabled || mUsageTracker.isRecentlyUsed();
        state.value = enabled;
        state.label = mContext.getString(R.string.quick_settings_inversion_label);
        state.icon = enabled ? mEnable : mDisable;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_COLORINVERSION;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_color_inversion_changed_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_color_inversion_changed_off);
        }
    }
}
