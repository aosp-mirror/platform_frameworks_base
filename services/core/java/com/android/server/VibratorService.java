/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.hardware.vibrator.V1_0.Constants.EffectStrength;
import android.media.AudioManager;
import android.os.PowerSaveState;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.IVibratorService;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.IBinder;
import android.os.Binder;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.DebugUtils;
import android.util.Slog;
import android.view.InputDevice;
import android.media.AudioAttributes;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.power.BatterySaverPolicy.ServiceType;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;

public class VibratorService extends IVibratorService.Stub
        implements InputManager.InputDeviceListener {
    private static final String TAG = "VibratorService";
    private static final boolean DEBUG = false;
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private final LinkedList<VibrationInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private final boolean mAllowPriorityVibrationsInLowPowerMode;
    private final boolean mSupportsAmplitudeControl;
    private final int mDefaultVibrationAmplitude;
    private final VibrationEffect[] mFallbackEffects;
    private final WorkSource mTmpWorkSource = new WorkSource();
    private final Handler mH = new Handler();
    private final Object mLock = new Object();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final IAppOpsService mAppOpsService;
    private final IBatteryStats mBatteryStatsService;
    private PowerManagerInternal mPowerManagerInternal;
    private InputManager mIm;

    private volatile VibrateThread mThread;

    // mInputDeviceVibrators lock should be acquired after mLock, if both are
    // to be acquired
    private final ArrayList<Vibrator> mInputDeviceVibrators = new ArrayList<Vibrator>();
    private boolean mVibrateInputDevicesSetting; // guarded by mInputDeviceVibrators
    private boolean mInputDeviceListenerRegistered; // guarded by mInputDeviceVibrators

    private Vibration mCurrentVibration;
    private int mCurVibUid = -1;
    private boolean mLowPowerMode;
    private SettingsObserver mSettingObserver;

    native static boolean vibratorExists();
    native static void vibratorInit();
    native static void vibratorOn(long milliseconds);
    native static void vibratorOff();
    native static boolean vibratorSupportsAmplitudeControl();
    native static void vibratorSetAmplitude(int amplitude);
    native static long vibratorPerformEffect(long effect, long strength);

    private class Vibration implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final VibrationEffect mEffect;
        private final long mStartTime;
        private final int mUsageHint;
        private final int mUid;
        private final String mOpPkg;

        private Vibration(IBinder token, VibrationEffect effect,
                int usageHint, int uid, String opPkg) {
            mToken = token;
            mEffect = effect;
            mStartTime = SystemClock.uptimeMillis();
            mUsageHint = usageHint;
            mUid = uid;
            mOpPkg = opPkg;
        }

        public void binderDied() {
            synchronized (mLock) {
                if (this == mCurrentVibration) {
                    doCancelVibrateLocked();
                }
            }
        }

        public boolean hasLongerTimeout(long millis) {
            // If the current effect is a one shot vibration that will end after the given timeout
            // for the new one shot vibration, then just let the current vibration finish. All
            // other effect types will get pre-empted.
            if (mEffect instanceof VibrationEffect.OneShot) {
                VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) mEffect;
                return mStartTime + oneShot.getTiming() > SystemClock.uptimeMillis() + millis;
            }
            return false;
        }

        public boolean isSystemHapticFeedback() {
            boolean repeating = false;
            if (mEffect instanceof VibrationEffect.Waveform) {
                VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) mEffect;
                repeating = (waveform.getRepeatIndex() < 0);
            }
            return (mUid == Process.SYSTEM_UID || mUid == 0 || SYSTEM_UI_PACKAGE.equals(mOpPkg))
                    && !repeating;
        }
    }

    private static class VibrationInfo {
        private final long mStartTime;
        private final VibrationEffect mEffect;
        private final int mUsageHint;
        private final int mUid;
        private final String mOpPkg;

        public VibrationInfo(long startTime, VibrationEffect effect,
                int usageHint, int uid, String opPkg) {
            mStartTime = startTime;
            mEffect = effect;
            mUsageHint = usageHint;
            mUid = uid;
            mOpPkg = opPkg;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append(", startTime: ")
                    .append(mStartTime)
                    .append(", effect: ")
                    .append(mEffect)
                    .append(", usageHint: ")
                    .append(mUsageHint)
                    .append(", uid: ")
                    .append(mUid)
                    .append(", opPkg: ")
                    .append(mOpPkg)
                    .toString();
        }
    }

    VibratorService(Context context) {
        vibratorInit();
        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        vibratorOff();

        mSupportsAmplitudeControl = vibratorSupportsAmplitudeControl();

        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);

        mAppOpsService =
            IAppOpsService.Stub.asInterface(ServiceManager.getService(Context.APP_OPS_SERVICE));
        mBatteryStatsService = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        mPreviousVibrationsLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpLimit);

        mDefaultVibrationAmplitude = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultVibrationAmplitude);

        mAllowPriorityVibrationsInLowPowerMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowPriorityVibrationsInLowPowerMode);

        mPreviousVibrations = new LinkedList<>();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);

        long[] clickEffectTimings = getLongIntArray(context.getResources(),
                com.android.internal.R.array.config_virtualKeyVibePattern);
        VibrationEffect clickEffect = createEffect(clickEffectTimings);
        VibrationEffect doubleClickEffect = VibrationEffect.createWaveform(
                new long[] {0, 30, 100, 30} /*timings*/, -1);
        long[] tickEffectTimings = getLongIntArray(context.getResources(),
                com.android.internal.R.array.config_clockTickVibePattern);
        VibrationEffect tickEffect = createEffect(tickEffectTimings);

        mFallbackEffects = new VibrationEffect[] { clickEffect, doubleClickEffect, tickEffect };
    }

    private static VibrationEffect createEffect(long[] timings) {
        if (timings == null || timings.length == 0) {
            return null;
        } else if (timings.length == 1) {
            return VibrationEffect.createOneShot(timings[0], VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            return VibrationEffect.createWaveform(timings, -1);
        }
    }

    public void systemReady() {
        mIm = mContext.getSystemService(InputManager.class);
        mSettingObserver = new SettingsObserver(mH);

        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mPowerManagerInternal.registerLowPowerModeObserver(
                new PowerManagerInternal.LowPowerModeListener() {
                    @Override
                    public int getServiceType() {
                        return ServiceType.VIBRATION;
                    }

                    @Override
                    public void onLowPowerModeChanged(PowerSaveState result) {
                        updateVibrators();
                    }
        });

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.VIBRATE_INPUT_DEVICES),
                true, mSettingObserver, UserHandle.USER_ALL);

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateVibrators();
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mH);

        updateVibrators();
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean SelfChange) {
            updateVibrators();
        }
    }

    @Override // Binder call
    public boolean hasVibrator() {
        return doVibratorExists();
    }

    @Override // Binder call
    public boolean hasAmplitudeControl() {
        synchronized (mInputDeviceVibrators) {
            // Input device vibrators don't support amplitude controls yet, but are still used over
            // the system vibrator when connected.
            return mSupportsAmplitudeControl && mInputDeviceVibrators.isEmpty();
        }
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    /**
     * Validate the incoming VibrationEffect.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the VibrationEffect is valid
     */
    private static boolean verifyVibrationEffect(VibrationEffect effect) {
        if (effect == null) {
            // Effect must not be null.
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying VibrationEffect.", e);
            return false;
        }
        return true;
    }

    private static long[] getLongIntArray(Resources r, int resid) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return null;
        }
        long[] out = new long[ar.length];
        for (int i = 0; i < ar.length; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    @Override // Binder call
    public void vibrate(int uid, String opPkg, VibrationEffect effect, int usageHint,
            IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires VIBRATE permission");
        }
        if (token == null) {
            Slog.e(TAG, "token must not be null");
            return;
        }
        verifyIncomingUid(uid);
        if (!verifyVibrationEffect(effect)) {
            return;
        }

        // If our current vibration is longer than the new vibration and is the same amplitude,
        // then just let the current one finish.
        if (effect instanceof VibrationEffect.OneShot
                && mCurrentVibration != null
                && mCurrentVibration.mEffect instanceof VibrationEffect.OneShot) {
            VibrationEffect.OneShot newOneShot = (VibrationEffect.OneShot) effect;
            VibrationEffect.OneShot currentOneShot =
                    (VibrationEffect.OneShot) mCurrentVibration.mEffect;
            if (mCurrentVibration.hasLongerTimeout(newOneShot.getTiming())
                    && newOneShot.getAmplitude() == currentOneShot.getAmplitude()) {
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of current vibration");
                }
                return;
            }
        }

        // If the current vibration is repeating and the incoming one is non-repeating, then ignore
        // the non-repeating vibration. This is so that we don't cancel vibrations that are meant
        // to grab the attention of the user, like ringtones and alarms, in favor of one-shot
        // vibrations that are likely quite short.
        if (!isRepeatingVibration(effect)
                && mCurrentVibration != null && isRepeatingVibration(mCurrentVibration.mEffect)) {
            if (DEBUG) {
                Slog.d(TAG, "Ignoring incoming vibration in favor of alarm vibration");
            }
            return;
        }

        Vibration vib = new Vibration(token, effect, usageHint, uid, opPkg);

        // Only link against waveforms since they potentially don't have a finish if
        // they're repeating. Let other effects just play out until they're done.
        if (effect instanceof VibrationEffect.Waveform) {
            try {
                token.linkToDeath(vib, 0);
            } catch (RemoteException e) {
                return;
            }
        }


        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                doCancelVibrateLocked();
                startVibrationLocked(vib);
                addToPreviousVibrationsLocked(vib);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static boolean isRepeatingVibration(VibrationEffect effect) {
        if (effect instanceof VibrationEffect.Waveform) {
            final VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) effect;
            if (waveform.getRepeatIndex() >= 0) {
                return true;
            }
        }
        return false;
    }

    private void addToPreviousVibrationsLocked(Vibration vib) {
        if (mPreviousVibrations.size() > mPreviousVibrationsLimit) {
            mPreviousVibrations.removeFirst();
        }
        mPreviousVibrations.addLast(new VibrationInfo(
                    vib.mStartTime, vib.mEffect, vib.mUsageHint, vib.mUid, vib.mOpPkg));
    }

    @Override // Binder call
    public void cancelVibrate(IBinder token) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.VIBRATE,
                "cancelVibrate");

        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.mToken == token) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration.");
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    doCancelVibrateLocked();
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    private final Runnable mVibrationEndRunnable = new Runnable() {
        @Override
        public void run() {
            onVibrationFinished();
        }
    };

    private void doCancelVibrateLocked() {
        mH.removeCallbacks(mVibrationEndRunnable);
        if (mThread != null) {
            mThread.cancel();
            mThread = null;
        }
        doVibratorOff();
        reportFinishVibrationLocked();
    }

    // Callback for whenever the current vibration has finished played out
    public void onVibrationFinished() {
        if (DEBUG) {
            Slog.e(TAG, "Vibration finished, cleaning up");
        }
        synchronized (mLock) {
            // Make sure the vibration is really done. This also reports that the vibration is
            // finished.
            doCancelVibrateLocked();
        }
    }

    private void startVibrationLocked(final Vibration vib) {
        if (!isAllowedToVibrate(vib)) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, low power mode");
            }
            return;
        }

        if (vib.mUsageHint == AudioAttributes.USAGE_NOTIFICATION_RINGTONE &&
                !shouldVibrateForRingtone()) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
            }
            return;
        }

        final int mode = getAppOpMode(vib);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually want
                // to throw a SecurityException here.
                Slog.w(TAG, "Would be an error: vibrate from uid " + vib.mUid);
            }
            return;
        }
        startVibrationInnerLocked(vib);
    }

    private void startVibrationInnerLocked(Vibration vib) {
        mCurrentVibration = vib;
        if (vib.mEffect instanceof VibrationEffect.OneShot) {
            VibrationEffect.OneShot oneShot = (VibrationEffect.OneShot) vib.mEffect;
            doVibratorOn(oneShot.getTiming(), oneShot.getAmplitude(), vib.mUid, vib.mUsageHint);
            mH.postDelayed(mVibrationEndRunnable, oneShot.getTiming());
        } else if (vib.mEffect instanceof VibrationEffect.Waveform) {
            // mThread better be null here. doCancelVibrate should always be
            // called before startNextVibrationLocked or startVibrationLocked.
            VibrationEffect.Waveform waveform = (VibrationEffect.Waveform) vib.mEffect;
            mThread = new VibrateThread(waveform, vib.mUid, vib.mUsageHint);
            mThread.start();
        } else if (vib.mEffect instanceof VibrationEffect.Prebaked) {
            long timeout = doVibratorPrebakedEffectLocked(vib);
            if (timeout > 0) {
                mH.postDelayed(mVibrationEndRunnable, timeout);
            }
        } else {
            Slog.e(TAG, "Unknown vibration type, ignoring");
        }
    }

    private boolean isAllowedToVibrate(Vibration vib) {
        if (!mLowPowerMode) {
            return true;
        }
        if (vib.mUsageHint == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
            return true;
        }
        if (!mAllowPriorityVibrationsInLowPowerMode) {
            return false;
        }
        if (vib.mUsageHint == AudioAttributes.USAGE_ALARM ||
            vib.mUsageHint == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY ||
            vib.mUsageHint == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST) {

            return true;
        }

        return false;
    }

    private boolean shouldVibrateForRingtone() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerModeInternal();
        // "Also vibrate for calls" Setting in Sound
        if (Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.VIBRATE_WHEN_RINGING, 0) != 0) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    private int getAppOpMode(Vibration vib) {
        int mode;
        try {
            mode = mAppOpsService.checkAudioOperation(AppOpsManager.OP_VIBRATE,
                    vib.mUsageHint, vib.mUid, vib.mOpPkg);
            if (mode == AppOpsManager.MODE_ALLOWED) {
                mode = mAppOpsService.startOperation(AppOpsManager.getToken(mAppOpsService),
                    AppOpsManager.OP_VIBRATE, vib.mUid, vib.mOpPkg);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get appop mode for vibration!", e);
            mode = AppOpsManager.MODE_IGNORED;
        }
        return mode;
    }

    private void reportFinishVibrationLocked() {
        if (mCurrentVibration != null) {
            try {
                mAppOpsService.finishOperation(AppOpsManager.getToken(mAppOpsService),
                        AppOpsManager.OP_VIBRATE, mCurrentVibration.mUid,
                        mCurrentVibration.mOpPkg);
            } catch (RemoteException e) { }
            mCurrentVibration = null;
        }
    }

    private void unlinkVibration(Vibration vib) {
        if (vib.mEffect instanceof VibrationEffect.Waveform) {
            vib.mToken.unlinkToDeath(vib, 0);
        }
    }

    private void updateVibrators() {
        synchronized (mLock) {
            boolean devicesUpdated = updateInputDeviceVibratorsLocked();
            boolean lowPowerModeUpdated = updateLowPowerModeLocked();

            if (devicesUpdated || lowPowerModeUpdated) {
                // If the state changes out from under us then just reset.
                doCancelVibrateLocked();
            }
        }
    }

    private boolean updateInputDeviceVibratorsLocked() {
        boolean changed = false;
        boolean vibrateInputDevices = false;
        try {
            vibrateInputDevices = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.VIBRATE_INPUT_DEVICES, UserHandle.USER_CURRENT) > 0;
        } catch (SettingNotFoundException snfe) {
        }
        if (vibrateInputDevices != mVibrateInputDevicesSetting) {
            changed = true;
            mVibrateInputDevicesSetting = vibrateInputDevices;
        }

        if (mVibrateInputDevicesSetting) {
            if (!mInputDeviceListenerRegistered) {
                mInputDeviceListenerRegistered = true;
                mIm.registerInputDeviceListener(this, mH);
            }
        } else {
            if (mInputDeviceListenerRegistered) {
                mInputDeviceListenerRegistered = false;
                mIm.unregisterInputDeviceListener(this);
            }
        }

        mInputDeviceVibrators.clear();
        if (mVibrateInputDevicesSetting) {
            int[] ids = mIm.getInputDeviceIds();
            for (int i = 0; i < ids.length; i++) {
                InputDevice device = mIm.getInputDevice(ids[i]);
                Vibrator vibrator = device.getVibrator();
                if (vibrator.hasVibrator()) {
                    mInputDeviceVibrators.add(vibrator);
                }
            }
            return true;
        }
        return changed;
    }

    private boolean updateLowPowerModeLocked() {
        boolean lowPowerMode = mPowerManagerInternal
                .getLowPowerState(ServiceType.VIBRATION).batterySaverEnabled;
        if (lowPowerMode != mLowPowerMode) {
            mLowPowerMode = lowPowerMode;
            return true;
        }
        return false;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateVibrators();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateVibrators();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateVibrators();
    }

    private boolean doVibratorExists() {
        // For now, we choose to ignore the presence of input devices that have vibrators
        // when reporting whether the device has a vibrator.  Applications often use this
        // information to decide whether to enable certain features so they expect the
        // result of hasVibrator() to be constant.  For now, just report whether
        // the device has a built-in vibrator.
        //synchronized (mInputDeviceVibrators) {
        //    return !mInputDeviceVibrators.isEmpty() || vibratorExists();
        //}
        return vibratorExists();
    }

    private void doVibratorOn(long millis, int amplitude, int uid, int usageHint) {
        synchronized (mInputDeviceVibrators) {
            if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                amplitude = mDefaultVibrationAmplitude;
            }
            if (DEBUG) {
                Slog.d(TAG, "Turning vibrator on for " + millis + " ms" +
                        " with amplitude " + amplitude + ".");
            }
            noteVibratorOnLocked(uid, millis);
            final int vibratorCount = mInputDeviceVibrators.size();
            if (vibratorCount != 0) {
                final AudioAttributes attributes =
                        new AudioAttributes.Builder().setUsage(usageHint).build();
                for (int i = 0; i < vibratorCount; i++) {
                    mInputDeviceVibrators.get(i).vibrate(millis, attributes);
                }
            } else {
                // Note: ordering is important here! Many haptic drivers will reset their amplitude
                // when enabled, so we always have to enable frst, then set the amplitude.
                vibratorOn(millis);
                doVibratorSetAmplitude(amplitude);
            }
        }
    }

    private void doVibratorSetAmplitude(int amplitude) {
        if (mSupportsAmplitudeControl) {
            vibratorSetAmplitude(amplitude);
        }
    }

    private void doVibratorOff() {
        synchronized (mInputDeviceVibrators) {
            if (DEBUG) {
                Slog.d(TAG, "Turning vibrator off.");
            }
            noteVibratorOffLocked();
            final int vibratorCount = mInputDeviceVibrators.size();
            if (vibratorCount != 0) {
                for (int i = 0; i < vibratorCount; i++) {
                    mInputDeviceVibrators.get(i).cancel();
                }
            } else {
                vibratorOff();
            }
        }
    }

    private long doVibratorPrebakedEffectLocked(Vibration vib) {
        synchronized (mInputDeviceVibrators) {
            VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) vib.mEffect;
            // Input devices don't support prebaked effect, so skip trying it with them.
            final int vibratorCount = mInputDeviceVibrators.size();
            if (vibratorCount == 0) {
                long timeout = vibratorPerformEffect(prebaked.getId(), EffectStrength.MEDIUM);
                if (timeout > 0) {
                    noteVibratorOnLocked(vib.mUid, timeout);
                    return timeout;
                }
            }
            if (!prebaked.shouldFallback()) {
                return 0;
            }
            final int id = prebaked.getId();
            if (id < 0 || id >= mFallbackEffects.length || mFallbackEffects[id] == null) {
                Slog.w(TAG, "Failed to play prebaked effect, no fallback");
                return 0;
            }
            VibrationEffect effect = mFallbackEffects[id];
            Vibration fallbackVib =
                    new Vibration(vib.mToken, effect, vib.mUsageHint, vib.mUid, vib.mOpPkg);
            startVibrationInnerLocked(fallbackVib);
        }
        return 0;
    }

    private void noteVibratorOnLocked(int uid, long millis) {
        try {
            mBatteryStatsService.noteVibratorOn(uid, millis);
            mCurVibUid = uid;
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOffLocked() {
        if (mCurVibUid >= 0) {
            try {
                mBatteryStatsService.noteVibratorOff(mCurVibUid);
            } catch (RemoteException e) { }
            mCurVibUid = -1;
        }
    }

    private class VibrateThread extends Thread {
        private final VibrationEffect.Waveform mWaveform;
        private final int mUid;
        private final int mUsageHint;

        private boolean mForceStop;

        VibrateThread(VibrationEffect.Waveform waveform, int uid, int usageHint) {
            mWaveform = waveform;
            mUid = uid;
            mUsageHint = usageHint;
            mTmpWorkSource.set(uid);
            mWakeLock.setWorkSource(mTmpWorkSource);
        }

        private long delayLocked(long duration) {
            long durationRemaining = duration;
            if (duration > 0) {
                final long bedtime = duration + SystemClock.uptimeMillis();
                do {
                    try {
                        this.wait(durationRemaining);
                    }
                    catch (InterruptedException e) { }
                    if (mForceStop) {
                        break;
                    }
                    durationRemaining = bedtime - SystemClock.uptimeMillis();
                } while (durationRemaining > 0);
                return duration - durationRemaining;
            }
            return 0;
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            mWakeLock.acquire();
            try {
                boolean finished = playWaveform();
                if (finished) {
                    onVibrationFinished();
                }
            } finally {
                mWakeLock.release();
            }
        }

        /**
         * Play the waveform.
         *
         * @return true if it finished naturally, false otherwise (e.g. it was canceled).
         */
        public boolean playWaveform() {
            synchronized (this) {
                final long[] timings = mWaveform.getTimings();
                final int[] amplitudes = mWaveform.getAmplitudes();
                final int len = timings.length;
                final int repeat = mWaveform.getRepeatIndex();

                int index = 0;
                long onDuration = 0;
                while (!mForceStop) {
                    if (index < len) {
                        final int amplitude = amplitudes[index];
                        final long duration = timings[index++];
                        if (duration <= 0) {
                            continue;
                        }
                        if (amplitude != 0) {
                            if (onDuration <= 0) {
                                // Telling the vibrator to start multiple times usually causes
                                // effects to feel "choppy" because the motor resets at every on
                                // command.  Instead we figure out how long our next "on" period is
                                // going to be, tell the motor to stay on for the full duration,
                                // and then wake up to change the amplitude at the appropriate
                                // intervals.
                                onDuration =
                                        getTotalOnDuration(timings, amplitudes, index - 1, repeat);
                                doVibratorOn(onDuration, amplitude, mUid, mUsageHint);
                            } else {
                                doVibratorSetAmplitude(amplitude);
                            }
                        }

                        long waitTime = delayLocked(duration);
                        if (amplitude != 0) {
                            onDuration -= waitTime;
                        }
                    } else if (repeat < 0) {
                        break;
                    } else {
                        index = repeat;
                    }
                }
                return !mForceStop;
            }
        }

        public void cancel() {
            synchronized (this) {
                mThread.mForceStop = true;
                mThread.notify();
            }
        }

        /**
         * Get the duration the vibrator will be on starting at startIndex until the next time it's
         * off.
         */
        private long getTotalOnDuration(
                long[] timings, int[] amplitudes, int startIndex, int repeatIndex) {
            int i = startIndex;
            long timing = 0;
            while(amplitudes[i] != 0) {
                timing += timings[i++];
                if (i >= timings.length) {
                    if (repeatIndex >= 0) {
                        i = repeatIndex;
                    } else {
                        break;
                    }
                }
                if (i == startIndex) {
                    return 1000;
                }
            }
            return timing;
        }
    }

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                synchronized (mLock) {
                    // When the system is entering a non-interactive state, we want
                    // to cancel vibrations in case a misbehaving app has abandoned
                    // them.  However it may happen that the system is currently playing
                    // haptic feedback as part of the transition.  So we don't cancel
                    // system vibrations.
                    if (mCurrentVibration != null
                            && !mCurrentVibration.isSystemHapticFeedback()) {
                        doCancelVibrateLocked();
                    }
                }
            }
        }
    };

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("Previous vibrations:");
        synchronized (mLock) {
            for (VibrationInfo info : mPreviousVibrations) {
                pw.print("  ");
                pw.println(info.toString());
            }
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver)
            throws RemoteException {
        new VibratorShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    private final class VibratorShellCommand extends ShellCommand {

        private static final long MAX_VIBRATION_MS = 200;

        private final IBinder mToken;

        private VibratorShellCommand(IBinder token) {
            mToken = token;
        }

        @Override
        public int onCommand(String cmd) {
            if ("vibrate".equals(cmd)) {
                return runVibrate();
            }
            return handleDefaultCommands(cmd);
        }

        private int runVibrate() {
            try {
                final int zenMode = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.ZEN_MODE);
                if (zenMode != Settings.Global.ZEN_MODE_OFF) {
                    try (PrintWriter pw = getOutPrintWriter();) {
                        pw.print("Ignoring because device is on DND mode ");
                        pw.println(DebugUtils.flagsToString(Settings.Global.class, "ZEN_MODE_",
                                zenMode));
                        return 0;
                    }
                }
            } catch (SettingNotFoundException e) {
                // ignore
            }

            final long duration = Long.parseLong(getNextArgRequired());
            if (duration > MAX_VIBRATION_MS) {
                throw new IllegalArgumentException("maximum duration is " + MAX_VIBRATION_MS);
            }
            String description = getNextArg();
            if (description == null) {
                description = "Shell command";
            }

            VibrationEffect effect =
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrate(Binder.getCallingUid(), description, effect, AudioAttributes.USAGE_UNKNOWN,
                    mToken);
            return 0;
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  vibrate duration [description]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode.");
                pw.println("");
            }
        }
    }

}
