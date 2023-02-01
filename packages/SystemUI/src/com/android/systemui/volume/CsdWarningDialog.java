/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.StringRes;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.statusbar.phone.SystemUIDialog;

/**
 * A class that implements the four Computed Sound Dose-related warnings defined in {@link AudioManager}:
 * <ul>
 *     <li>{@link AudioManager#CSD_WARNING_DOSE_REACHED_1X}</li>
 *     <li>{@link AudioManager#CSD_WARNING_DOSE_REPEATED_5X}</li>
 *     <li>{@link AudioManager#CSD_WARNING_ACCUMULATION_START}</li>
 *     <li>{@link AudioManager#CSD_WARNING_MOMENTARY_EXPOSURE}</li>
 * </ul>
 * Rather than basing volume safety messages on a fixed volume index, the CSD feature derives its
 * warnings from the computation of the "sound dose". The dose computation is based on a
 * frequency-dependent analysis of the audio signal which estimates how loud and potentially harmful
 * the signal content is. This is combined with the volume attenuation/amplification applied to it
 * and integrated over time to derive the dose exposure over a 7 day rolling window.
 * <p>The UI behaviors implemented in this class are defined in IEC 62368 in "Safeguards against
 * acoustic energy sources". The events that trigger those warnings originate in SoundDoseHelper
 * which runs in the "audio" system_server service (see
 * frameworks/base/services/core/java/com/android/server/audio/AudioService.java for the
 * communication between the audio framework and the volume controller, and
 * frameworks/base/services/core/java/com/android/server/audio/SoundDoseHelper.java for the
 * communication between the native audio framework that implements the dose computation and the
 * audio service.
 */
public abstract class CsdWarningDialog extends SystemUIDialog
        implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener {

    private static final String TAG = Util.logTag(CsdWarningDialog.class);

    private static final int KEY_CONFIRM_ALLOWED_AFTER_MS = 1000; // milliseconds
    // time after which action is taken when the user hasn't ack'd or dismissed the dialog
    private static final int NO_ACTION_TIMEOUT_MS = 5000;

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final @AudioManager.CsdWarning int mCsdWarning;
    private final Object mTimerLock = new Object();
    /**
     * Timer to keep track of how long the user has before an action (here volume reduction) is
     * taken on their behalf.
     */
    @GuardedBy("mTimerLock")
    private final CountDownTimer mNoUserActionTimer;

    private long mShowTime;

    public CsdWarningDialog(@AudioManager.CsdWarning int csdWarning, Context context,
            AudioManager audioManager) {
        super(context);
        mCsdWarning = csdWarning;
        mContext = context;
        mAudioManager = audioManager;
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        setShowForAllUsers(true);
        setMessage(mContext.getString(getStringForWarning(csdWarning)));
        setButton(DialogInterface.BUTTON_POSITIVE,
                mContext.getString(com.android.internal.R.string.yes), this);
        setButton(DialogInterface.BUTTON_NEGATIVE,
                mContext.getString(com.android.internal.R.string.no), this);
        setOnDismissListener(this);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mReceiver, filter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        if (csdWarning == AudioManager.CSD_WARNING_DOSE_REACHED_1X) {
            mNoUserActionTimer = new CountDownTimer(NO_ACTION_TIMEOUT_MS, NO_ACTION_TIMEOUT_MS) {
                @Override
                public void onTick(long millisUntilFinished) { }

                @Override
                public void onFinish() {
                    if (mCsdWarning == AudioManager.CSD_WARNING_DOSE_REACHED_1X) {
                        // unlike on the 5x dose repeat, level is only reduced to RS1
                        // when the warning is not acknowledged quick enough
                        mAudioManager.lowerVolumeToRs1();
                    }
                }
            };
        } else {
            mNoUserActionTimer = null;
        }
    }

    protected abstract void cleanUp();

    // NOT overriding onKeyDown as we're not allowing a dismissal on any key other than
    // VOLUME_DOWN, and for this, we don't need to track if it's the start of a new
    // key down -> up sequence
    //@Override
    //public boolean onKeyDown(int keyCode, KeyEvent event) {
    //    return super.onKeyDown(keyCode, event);
    //}

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // never allow to raise volume
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        // VOLUME_DOWN will dismiss the dialog
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                && (System.currentTimeMillis() - mShowTime) > KEY_CONFIRM_ALLOWED_AFTER_MS) {
            Log.i(TAG, "Confirmed CSD exposure warning via VOLUME_DOWN");
            dismiss();
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            Log.d(TAG, "OK pressed for CSD warning " + mCsdWarning);
            dismiss();

        }
        if (D.BUG) Log.d(TAG, "on click " + which);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mShowTime = System.currentTimeMillis();
        synchronized (mTimerLock) {
            if (mNoUserActionTimer != null) {
                new Thread(() -> {
                    synchronized (mTimerLock) {
                        mNoUserActionTimer.start();
                    }
                }).start();
            }
        }
    }

    @Override
    protected void onStop() {
        synchronized (mTimerLock) {
            if (mNoUserActionTimer != null) {
                mNoUserActionTimer.cancel();
            }
        }
    }

    @Override
    public void onDismiss(DialogInterface unused) {
        if (mCsdWarning == AudioManager.CSD_WARNING_DOSE_REPEATED_5X) {
            // level is always reduced to RS1 beyond the 5x dose
            mAudioManager.lowerVolumeToRs1();
        }
        try {
            mContext.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            // Don't crash if the receiver has already been unregistered.
            Log.e(TAG, "Error unregistering broadcast receiver", e);
        }
        cleanUp();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                if (D.BUG) Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                cancel();
                cleanUp();
            }
        }
    };

    private @StringRes int getStringForWarning(@AudioManager.CsdWarning int csdWarning) {
        switch (csdWarning) {
            case AudioManager.CSD_WARNING_DOSE_REACHED_1X:
                return com.android.internal.R.string.csd_dose_reached_warning;
            case AudioManager.CSD_WARNING_DOSE_REPEATED_5X:
                return com.android.internal.R.string.csd_dose_repeat_warning;
            case AudioManager.CSD_WARNING_MOMENTARY_EXPOSURE:
                return com.android.internal.R.string.csd_momentary_exposure_warning;
            case AudioManager.CSD_WARNING_ACCUMULATION_START:
                return com.android.internal.R.string.csd_entering_RS2_warning;
        }
        Log.e(TAG, "Invalid CSD warning event " + csdWarning, new Exception());
        return com.android.internal.R.string.csd_dose_reached_warning;
    }
}
