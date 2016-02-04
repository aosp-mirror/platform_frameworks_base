/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (c) 2017 The LineageOS Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.quicksettings.Tile;

import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.R;

import org.lineageos.internal.logging.LineageMetricsLogger;

import javax.inject.Inject;

/** Quick settings tile: Caffeine **/
public class CaffeineTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_caffeine);

    private final PowerManager.WakeLock mWakeLock;
    private int mSecondsRemaining;
    private int mDuration;
    private static int[] DURATIONS = new int[] {
        5 * 60,   // 5 min
        10 * 60,  // 10 min
        30 * 60,  // 30 min
        -1,       // infinity
    };
    private CountDownTimer mCountdownTimer = null;
    public long mLastClickTime = -1;
    private final Receiver mReceiver = new Receiver();

    @Inject
    public CaffeineTile(QSHost host) {
        super(host);
        mWakeLock = mContext.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.FULL_WAKE_LOCK, "CaffeineTile");
        mReceiver.init();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        stopCountDown();
        mReceiver.destroy();
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    public void handleClick() {
        // If last user clicks < 5 seconds
        // we cycle different duration
        // otherwise toggle on/off
        if (mWakeLock.isHeld() && (mLastClickTime != -1) &&
                (SystemClock.elapsedRealtime() - mLastClickTime < 5000)) {
            // cycle duration
            mDuration++;
            if (mDuration >= DURATIONS.length) {
                // all durations cycled, turn if off
                mDuration = -1;
                stopCountDown();
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            } else {
                // change duration
                startCountDown(DURATIONS[mDuration]);
                if (!mWakeLock.isHeld()) {
                    mWakeLock.acquire();
                }
            }
        } else {
            // toggle
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
                stopCountDown();
            } else {
                mWakeLock.acquire();
                mDuration = 0;
                startCountDown(DURATIONS[mDuration]);
            }
        }
        mLastClickTime = SystemClock.elapsedRealtime();
        refreshState();
    }

    @Override
    protected void handleLongClick() {
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_caffeine_label);
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_CAFFEINE;
    }

    private void startCountDown(long duration) {
        stopCountDown();
        mSecondsRemaining = (int)duration;
        if (duration == -1) {
            // infinity timing, no need to start timer
            return;
        }
        mCountdownTimer = new CountDownTimer(duration * 1000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                mSecondsRemaining = (int) (millisUntilFinished / 1000);
                refreshState();
            }

            @Override
            public void onFinish() {
                if (mWakeLock.isHeld())
                    mWakeLock.release();
                refreshState();
            }

        }.start();
    }

    private void stopCountDown() {
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
    }

    private String formatValueWithRemainingTime() {
        if (mSecondsRemaining == -1) {
            return "\u221E"; // infinity
        }
        return String.format("%02d:%02d",
                        mSecondsRemaining / 60 % 60, mSecondsRemaining % 60);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mWakeLock.isHeld();
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_caffeine_label);
        if (state.value) {
            state.secondaryLabel = formatValueWithRemainingTime();
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = null;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    private final class Receiver extends BroadcastReceiver {
        public void init() {
            // Register for Intent broadcasts for...
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        public void destroy() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // disable caffeine if user force off (power button)
                stopCountDown();
                if (mWakeLock.isHeld())
                    mWakeLock.release();
                refreshState();
            }
        }
    }
}
