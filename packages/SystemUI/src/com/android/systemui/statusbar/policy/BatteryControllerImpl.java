/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.os.BatteryManager.EXTRA_PRESENT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.power.EnhancedEstimates;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Default implementation of a {@link BatteryController}. This controller monitors for battery
 * level change events that are broadcasted by the system.
 */
@Singleton
public class BatteryControllerImpl extends BroadcastReceiver implements BatteryController {
    private static final String TAG = "BatteryController";

    private static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final EnhancedEstimates mEstimates;
    protected final BroadcastDispatcher mBroadcastDispatcher;
    protected final ArrayList<BatteryController.BatteryStateChangeCallback>
            mChangeCallbacks = new ArrayList<>();
    private final ArrayList<EstimateFetchCompletion> mFetchCallbacks = new ArrayList<>();
    private final PowerManager mPowerManager;
    private final Handler mMainHandler;
    private final Handler mBgHandler;
    protected final Context mContext;

    protected int mLevel;
    protected boolean mPluggedIn;
    protected boolean mCharging;
    private boolean mStateUnknown = false;
    private boolean mCharged;
    protected boolean mPowerSave;
    private boolean mAodPowerSave;
    protected boolean mWirelessCharging;
    private boolean mTestmode = false;
    @VisibleForTesting
    boolean mHasReceivedBattery = false;
    private Estimate mEstimate;
    private boolean mFetchingEstimate = false;

    @VisibleForTesting
    @Inject
    public BatteryControllerImpl(Context context, EnhancedEstimates enhancedEstimates,
            PowerManager powerManager, BroadcastDispatcher broadcastDispatcher,
            @Main Handler mainHandler, @Background Handler bgHandler) {
        mContext = context;
        mMainHandler = mainHandler;
        mBgHandler = bgHandler;
        mPowerManager = powerManager;
        mEstimates = enhancedEstimates;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(ACTION_LEVEL_TEST);
        mBroadcastDispatcher.registerReceiver(this, filter);
    }

    @Override
    public void init() {
        registerReceiver();
        if (!mHasReceivedBattery) {
            // Get initial state. Relying on Sticky behavior until API for getting info.
            Intent intent = mContext.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );
            if (intent != null && !mHasReceivedBattery) {
                onReceive(mContext, intent);
            }
        }
        updatePowerSave();
        updateEstimate();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BatteryController state:");
        pw.print("  mLevel="); pw.println(mLevel);
        pw.print("  mPluggedIn="); pw.println(mPluggedIn);
        pw.print("  mCharging="); pw.println(mCharging);
        pw.print("  mCharged="); pw.println(mCharged);
        pw.print("  mPowerSave="); pw.println(mPowerSave);
        pw.print("  mStateUnknown="); pw.println(mStateUnknown);
    }

    @Override
    public void setPowerSaveMode(boolean powerSave) {
        BatterySaverUtils.setPowerSaveMode(mContext, powerSave, /*needFirstTimeWarning*/ true);
    }

    @Override
    public void addCallback(BatteryController.BatteryStateChangeCallback cb) {
        synchronized (mChangeCallbacks) {
            mChangeCallbacks.add(cb);
        }
        if (!mHasReceivedBattery) return;

        // Make sure new callbacks get the correct initial state
        cb.onBatteryLevelChanged(mLevel, mPluggedIn, mCharging);
        cb.onPowerSaveChanged(mPowerSave);
        cb.onBatteryUnknownStateChanged(mStateUnknown);
    }

    @Override
    public void removeCallback(BatteryController.BatteryStateChangeCallback cb) {
        synchronized (mChangeCallbacks) {
            mChangeCallbacks.remove(cb);
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
            if (mTestmode && !intent.getBooleanExtra("testmode", false)) return;
            mHasReceivedBattery = true;
            mLevel = (int)(100f
                    * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            mPluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            mCharged = status == BatteryManager.BATTERY_STATUS_FULL;
            mCharging = mCharged || status == BatteryManager.BATTERY_STATUS_CHARGING;
            mWirelessCharging = mCharging && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    == BatteryManager.BATTERY_PLUGGED_WIRELESS;

            boolean present = intent.getBooleanExtra(EXTRA_PRESENT, true);
            boolean unknown = !present;
            if (unknown != mStateUnknown) {
                mStateUnknown = unknown;
                fireBatteryUnknownStateChanged();
            }

            fireBatteryLevelChanged();
        } else if (action.equals(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) {
            updatePowerSave();
        } else if (action.equals(ACTION_LEVEL_TEST)) {
            mTestmode = true;
            mMainHandler.post(new Runnable() {
                int curLevel = 0;
                int incr = 1;
                int saveLevel = mLevel;
                boolean savePlugged = mPluggedIn;
                Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);
                @Override
                public void run() {
                    if (curLevel < 0) {
                        mTestmode = false;
                        dummy.putExtra("level", saveLevel);
                        dummy.putExtra("plugged", savePlugged);
                        dummy.putExtra("testmode", false);
                    } else {
                        dummy.putExtra("level", curLevel);
                        dummy.putExtra("plugged", incr > 0 ? BatteryManager.BATTERY_PLUGGED_AC
                                : 0);
                        dummy.putExtra("testmode", true);
                    }
                    context.sendBroadcast(dummy);

                    if (!mTestmode) return;

                    curLevel += incr;
                    if (curLevel == 100) {
                        incr *= -1;
                    }
                    mMainHandler.postDelayed(this, 200);
                }
            });
        }
    }

    @Override
    public boolean isPluggedIn() {
        return mPluggedIn;
    }

    @Override
    public boolean isPowerSave() {
        return mPowerSave;
    }

    @Override
    public boolean isAodPowerSave() {
        return mAodPowerSave;
    }

    @Override
    public boolean isWirelessCharging() {
        return mWirelessCharging;
    }

    @Override
    public void getEstimatedTimeRemainingString(EstimateFetchCompletion completion) {
        // Need to fetch or refresh the estimate, but it may involve binder calls so offload the
        // work
        synchronized (mFetchCallbacks) {
            mFetchCallbacks.add(completion);
        }
        updateEstimateInBackground();
    }

    @Nullable
    private String generateTimeRemainingString() {
        synchronized (mFetchCallbacks) {
            if (mEstimate == null) {
                return null;
            }

            return PowerUtil.getBatteryRemainingShortStringFormatted(
                    mContext, mEstimate.getEstimateMillis());
        }
    }

    private void updateEstimateInBackground() {
        if (mFetchingEstimate) {
            // Already dispatched a fetch. It will notify all listeners when finished
            return;
        }

        mFetchingEstimate = true;
        mBgHandler.post(() -> {
            // Only fetch the estimate if they are enabled
            synchronized (mFetchCallbacks) {
                mEstimate = null;
                if (mEstimates.isHybridNotificationEnabled()) {
                    updateEstimate();
                }
            }
            mFetchingEstimate = false;
            mMainHandler.post(this::notifyEstimateFetchCallbacks);
        });
    }

    private void notifyEstimateFetchCallbacks() {
        synchronized (mFetchCallbacks) {
            String estimate = generateTimeRemainingString();
            for (EstimateFetchCompletion completion : mFetchCallbacks) {
                completion.onBatteryRemainingEstimateRetrieved(estimate);
            }

            mFetchCallbacks.clear();
        }
    }

    private void updateEstimate() {
        // if the estimate has been cached we can just use that, otherwise get a new one and
        // throw it in the cache.
        mEstimate = Estimate.getCachedEstimateIfAvailable(mContext);
        if (mEstimate == null) {
            mEstimate = mEstimates.getEstimate();
            if (mEstimate != null) {
                Estimate.storeCachedEstimate(mContext, mEstimate);
            }
        }
    }

    private void updatePowerSave() {
        setPowerSave(mPowerManager.isPowerSaveMode());
    }

    private void setPowerSave(boolean powerSave) {
        if (powerSave == mPowerSave) return;
        mPowerSave = powerSave;

        // AOD power saving setting might be different from PowerManager power saving mode.
        PowerSaveState state = mPowerManager.getPowerSaveState(PowerManager.ServiceType.AOD);
        mAodPowerSave = state.batterySaverEnabled;

        if (DEBUG) Log.d(TAG, "Power save is " + (mPowerSave ? "on" : "off"));
        firePowerSaveChanged();
    }

    protected void fireBatteryLevelChanged() {
        synchronized (mChangeCallbacks) {
            final int N = mChangeCallbacks.size();
            for (int i = 0; i < N; i++) {
                mChangeCallbacks.get(i).onBatteryLevelChanged(mLevel, mPluggedIn, mCharging);
            }
        }
    }

    private void fireBatteryUnknownStateChanged() {
        synchronized (mChangeCallbacks) {
            final int n = mChangeCallbacks.size();
            for (int i = 0; i < n; i++) {
                mChangeCallbacks.get(i).onBatteryUnknownStateChanged(mStateUnknown);
            }
        }
    }

    private void firePowerSaveChanged() {
        synchronized (mChangeCallbacks) {
            final int N = mChangeCallbacks.size();
            for (int i = 0; i < N; i++) {
                mChangeCallbacks.get(i).onPowerSaveChanged(mPowerSave);
            }
        }
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
            mBroadcastDispatcher.unregisterReceiver(this);
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            registerReceiver();
            updatePowerSave();
        } else if (mDemoMode && command.equals(COMMAND_BATTERY)) {
            String level = args.getString("level");
            String plugged = args.getString("plugged");
            String powerSave = args.getString("powersave");
            String present = args.getString("present");
            if (level != null) {
                mLevel = Math.min(Math.max(Integer.parseInt(level), 0), 100);
            }
            if (plugged != null) {
                mPluggedIn = Boolean.parseBoolean(plugged);
            }
            if (powerSave != null) {
                mPowerSave = powerSave.equals("true");
                firePowerSaveChanged();
            }
            if (present != null) {
                mStateUnknown = !present.equals("true");
                fireBatteryUnknownStateChanged();
            }
            fireBatteryLevelChanged();
        }
    }
}
