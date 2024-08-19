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

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import android.annotation.StringRes;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import androidx.annotation.VisibleForTesting;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.Flags;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.concurrency.DelayableExecutor;

import com.google.common.collect.ImmutableList;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.Optional;

/**
 * A class that implements the three Computed Sound Dose-related warnings defined in
 * {@link AudioManager}:
 * <ul>
 *     <li>{@link AudioManager#CSD_WARNING_DOSE_REACHED_1X}</li>
 *     <li>{@link AudioManager#CSD_WARNING_DOSE_REPEATED_5X}</li>
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
public class CsdWarningDialog extends SystemUIDialog
        implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener {

    private static final String TAG = Util.logTag(CsdWarningDialog.class);

    private static final int KEY_CONFIRM_ALLOWED_AFTER_MS = 1000; // milliseconds
    // time after which action is taken when the user hasn't ack'd or dismissed the dialog
    public static final int NO_ACTION_TIMEOUT_MS = 5000;
    // Notification dismiss intent
    private static final String DISMISS_CSD_NOTIFICATION =
            "com.android.systemui.volume.DISMISS_CSD_NOTIFICATION";

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final @AudioManager.CsdWarning int mCsdWarning;
    private final Object mTimerLock = new Object();

    /**
     * Timer to keep track of how long the user has before an action (here volume reduction) is
     * taken on their behalf.
     */
    @GuardedBy("mTimerLock")
    private Runnable mNoUserActionRunnable;
    private Runnable mCancelScheduledNoUserActionRunnable = null;

    private final DelayableExecutor mDelayableExecutor;
    private NotificationManager mNotificationManager;
    private Runnable mOnCleanup;

    private long mShowTime;

    @VisibleForTesting public int mCachedMediaStreamVolume;
    private Optional<ImmutableList<CsdWarningAction>> mActionIntents;
    private final BroadcastDispatcher mBroadcastDispatcher;

    /**
     * To inject dependencies and allow for easier testing
     */
    @AssistedFactory
    public interface Factory {
        /** Create a dialog object */
        CsdWarningDialog create(
                int csdWarning,
                Runnable onCleanup,
                Optional<ImmutableList<CsdWarningAction>> actionIntents);
    }

    @AssistedInject
    public CsdWarningDialog(
            @Assisted @AudioManager.CsdWarning int csdWarning,
            Context context,
            AudioManager audioManager,
            NotificationManager notificationManager,
            @Background DelayableExecutor delayableExecutor,
            @Assisted Runnable onCleanup,
            @Assisted Optional<ImmutableList<CsdWarningAction>> actionIntents,
            BroadcastDispatcher broadcastDispatcher) {
        super(context);
        mCsdWarning = csdWarning;
        mContext = context;
        mAudioManager = audioManager;
        mNotificationManager = notificationManager;
        mOnCleanup = onCleanup;

        mDelayableExecutor = delayableExecutor;
        mActionIntents = actionIntents;
        mBroadcastDispatcher = broadcastDispatcher;
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        setShowForAllUsers(true);
        setMessage(mContext.getString(getStringForWarning(csdWarning)));
        setButton(DialogInterface.BUTTON_POSITIVE,
                mContext.getString(R.string.csd_button_keep_listening), this);
        setButton(DialogInterface.BUTTON_NEGATIVE,
                mContext.getString(R.string.csd_button_lower_volume), this);
        setOnDismissListener(this);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mReceiver, filter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        if (csdWarning == AudioManager.CSD_WARNING_DOSE_REACHED_1X) {
            mNoUserActionRunnable =
                    () -> {
                        if (mCsdWarning == AudioManager.CSD_WARNING_DOSE_REACHED_1X) {
                            // unlike on the 5x dose repeat, level is only reduced to RS1 when the
                            // warning is not acknowledged quickly enough
                            mCachedMediaStreamVolume =
                                    mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                            mAudioManager.lowerVolumeToRs1();
                            sendNotification(/* for5XCsd= */ false);
                        }
                    };
        } else {
            mNoUserActionRunnable = null;
        }
    }

    private void cleanUp() {
        if (mOnCleanup != null) {
            mOnCleanup.run();
        }
    }

    @Override
    public void show() {
        if (mCsdWarning == AudioManager.CSD_WARNING_DOSE_REPEATED_5X) {
            // only show a notification in case we reached 500% of dose
            show5XNotification();
            dismissCsdDialog();
            return;
        }
        super.show();
    }

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
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            Log.d(TAG, "Lower volume pressed for CSD warning " + mCsdWarning);
            mAudioManager.lowerVolumeToRs1();
            dismiss();
        }
        if (D.BUG) Log.d(TAG, "on click " + which);
    }

    @Override
    protected void start() {
        mShowTime = System.currentTimeMillis();
        synchronized (mTimerLock) {
            if (mNoUserActionRunnable != null) {
                mCancelScheduledNoUserActionRunnable = mDelayableExecutor.executeDelayed(
                        mNoUserActionRunnable, NO_ACTION_TIMEOUT_MS);
            }
        }
    }

    @Override
    protected void stop() {
        synchronized (mTimerLock) {
            if (mCancelScheduledNoUserActionRunnable != null) {
                mCancelScheduledNoUserActionRunnable.run();
            }
        }
    }

    @Override
    public void onDismiss(DialogInterface unused) {
        dismissCsdDialog();
    }

    private void dismissCsdDialog() {
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

    @VisibleForTesting
    public final BroadcastReceiver mReceiverUndo =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Flags.sounddoseCustomization()
                            && VolumeDialog.ACTION_VOLUME_UNDO.equals(intent.getAction())) {
                        if (D.BUG) Log.d(TAG, "Received ACTION_VOLUME_UNDO");
                        mAudioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                mCachedMediaStreamVolume,
                                AudioManager.FLAG_SHOW_UI);
                        mNotificationManager.cancel(
                                SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO);
                        destroy();
                    }
                }
            };

    @VisibleForTesting
    public final BroadcastReceiver mReceiverDismissNotification =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Flags.sounddoseCustomization()
                            && DISMISS_CSD_NOTIFICATION.equals(intent.getAction())) {
                        if (D.BUG) Log.d(TAG, "Received DISMISS_CSD_NOTIFICATION");
                        destroy();
                    }
                }
            };

    private @StringRes int getStringForWarning(@AudioManager.CsdWarning int csdWarning) {
        switch (csdWarning) {
            case AudioManager.CSD_WARNING_DOSE_REACHED_1X:
                return com.android.internal.R.string.csd_dose_reached_warning;
            case AudioManager.CSD_WARNING_MOMENTARY_EXPOSURE:
                return com.android.internal.R.string.csd_momentary_exposure_warning;
        }
        Log.e(TAG, "Invalid CSD warning event " + csdWarning, new Exception());
        return com.android.internal.R.string.csd_dose_reached_warning;
    }

    /** When 5X CSD is reached we lower the volume and show a notification. **/
    private void show5XNotification() {
        if (mCsdWarning != AudioManager.CSD_WARNING_DOSE_REPEATED_5X) {
            Log.w(TAG, "Notification dose repeat 5x is not shown for " + mCsdWarning);
            return;
        }
        mCachedMediaStreamVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        mAudioManager.lowerVolumeToRs1();
        sendNotification(/*for5XCsd=*/true);
    }

    /**
     * In case user did not respond to the dialog, they still need to know volume was lowered.
     */
    private void sendNotification(boolean for5XCsd) {
        Intent intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent,
                FLAG_IMMUTABLE);

        String text = for5XCsd ? mContext.getString(R.string.csd_500_system_lowered_text)
                : mContext.getString(R.string.csd_system_lowered_text);
        String title = mContext.getString(R.string.csd_lowered_title);

        Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setSmallIcon(R.drawable.hearing)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(pendingIntent)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_SYSTEM);

        if (Flags.sounddoseCustomization()
                && mActionIntents.isPresent()
                && !mActionIntents.get().isEmpty()) {
            ImmutableList<CsdWarningAction> actionIntentsList = mActionIntents.get();
            for (CsdWarningAction action : actionIntentsList) {
                if (action.getLabel() == null || action.getIntent() == null) {
                    Log.w(TAG, "Null action intent received. Skipping addition to notification");
                    continue;
                }
                PendingIntent pendingActionIntent = action.toPendingIntent(mContext);
                if (pendingActionIntent == null) {
                    Log.w(TAG, "Null pending intent received. Skipping addition to notification");
                    continue;
                }
                builder.addAction(0, action.getLabel(), pendingActionIntent);

                // Register receiver to undo volume only when
                // notification conaining the undo action would be sent.
                if (action.getLabel().equals(mContext.getString(R.string.volume_undo_action))) {
                    final IntentFilter filterUndo = new IntentFilter(
                            VolumeDialog.ACTION_VOLUME_UNDO);
                    mBroadcastDispatcher.registerReceiver(mReceiverUndo,
                            filterUndo,
                            /* executor = default */ null,
                            /* user = default */ null,
                            Context.RECEIVER_NOT_EXPORTED,
                            /* permission = default */ null);

                    // Register receiver to learn if notification has been dismissed.
                    // This is required to unregister receivers to prevent leak.
                    Intent dismissIntent = new Intent(DISMISS_CSD_NOTIFICATION)
                            .setPackage(mContext.getPackageName());
                    PendingIntent pendingDismissIntent = PendingIntent.getBroadcast(
                            mContext,
                            0, dismissIntent, FLAG_IMMUTABLE);
                    mBroadcastDispatcher.registerReceiver(mReceiverDismissNotification,
                            new IntentFilter(DISMISS_CSD_NOTIFICATION),
                            /* executor = default */ null,
                            /* user = default */ null,
                            Context.RECEIVER_NOT_EXPORTED,
                            /* permission = default */ null);
                    builder.setDeleteIntent(pendingDismissIntent);
                }
            }
        }

        mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO,
                builder.build());
    }

    /**
     * Unregister the Undo action receiver when the notification is dismissed.
     * Unregister cannot happen when CsdWarning dialog is dismissed
     * as the notification lifecycle would be longer.
     */
    @VisibleForTesting
    public void destroy() {
        if (Flags.sounddoseCustomization()
                && mActionIntents.isPresent()
                && !mActionIntents.get().isEmpty()) {
            mBroadcastDispatcher.unregisterReceiver(mReceiverUndo);
            mBroadcastDispatcher.unregisterReceiver(mReceiverDismissNotification);
        }
    }
}
