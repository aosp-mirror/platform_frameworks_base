/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pm;

import static android.media.AudioAttributes.USAGE_ALARM;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiopolicy.AudioPolicy;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

public class BackgroundUserSoundNotifier {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = BackgroundUserSoundNotifier.class.getSimpleName();
    public static final String BUSN_CHANNEL_ID = "bg_user_sound_channel";
    public static final String BUSN_CHANNEL_NAME = "BackgroundUserSound";
    private static final String ACTION_MUTE_SOUND = "com.android.server.ACTION_MUTE_BG_USER";
    private static final String EXTRA_NOTIFICATION_ID = "com.android.server.EXTRA_CLIENT_UID";
    private static final String EXTRA_CURRENT_USER_ID = "com.android.server.EXTRA_CURRENT_USER_ID";
    private static final String ACTION_SWITCH_USER = "com.android.server.ACTION_SWITCH_TO_USER";
    /** ID of user with notification displayed, -1 if notification is not showing*/
    private int mUserWithNotification = -1;
    private final Context mSystemUserContext;
    @VisibleForTesting
    final NotificationManager mNotificationManager;
    private final UserManager mUserManager;

    /**
     * Facilitates the display of notifications to current user when there is an alarm or timer
     * going off on background user and allows to manage the sound through actions.
     */
    public BackgroundUserSoundNotifier(Context context) {
        mSystemUserContext = context;
        mNotificationManager =  mSystemUserContext.getSystemService(NotificationManager.class);
        mUserManager = mSystemUserContext.getSystemService(UserManager.class);
        NotificationChannel channel = new NotificationChannel(BUSN_CHANNEL_ID, BUSN_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channel);
        setupFocusControlAudioPolicy();
    }

    private void setupFocusControlAudioPolicy() {
        // Used to configure our audio policy to handle focus events.
        // This gives us the ability to decide which audio focus requests to accept and bypasses
        // the framework ducking logic.
        ActivityManager am = mSystemUserContext.getSystemService(ActivityManager.class);

        registerReceiver(am);
        BackgroundUserListener bgUserListener = new BackgroundUserListener(mSystemUserContext);
        AudioPolicy.Builder focusControlPolicyBuilder = new AudioPolicy.Builder(mSystemUserContext);
        focusControlPolicyBuilder.setLooper(Looper.getMainLooper());

        focusControlPolicyBuilder.setAudioPolicyFocusListener(bgUserListener);

        AudioPolicy mFocusControlAudioPolicy = focusControlPolicyBuilder.build();
        int status = mSystemUserContext.getSystemService(AudioManager.class)
                .registerAudioPolicy(mFocusControlAudioPolicy);
        if (status != AudioManager.SUCCESS) {
            Log.w(LOG_TAG , "Could not register the service's focus"
                    + " control audio policy, error: " + status);
        }
    }

    final class BackgroundUserListener extends AudioPolicy.AudioPolicyFocusListener {

        Context mSystemContext;

        BackgroundUserListener(Context systemContext) {
            mSystemContext = systemContext;
        }

        @SuppressLint("MissingPermission")
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {
            try {
                BackgroundUserSoundNotifier.this.notifyForegroundUserAboutSoundIfNecessary(afi,
                        mSystemContext.createContextAsUser(
                                UserHandle.of(ActivityManager.getCurrentUser()), 0));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressLint("MissingPermission")
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {
            BackgroundUserSoundNotifier.this.dismissNotificationIfNecessary(afi);
        }
    }

    /**
     * Registers a BroadcastReceiver for actions related to background user sound notifications.
     *  When ACTION_MUTE_SOUND is received, it mutes a background user's alarm sound.
     *  When ACTION_SWITCH_USER is received, a switch to the background user with alarm is started.
     */
    private void registerReceiver(ActivityManager service) {
        BroadcastReceiver backgroundUserNotificationBroadcastReceiver = new BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!(intent.hasExtra(EXTRA_NOTIFICATION_ID)
                        && intent.hasExtra(EXTRA_CURRENT_USER_ID)
                        && intent.hasExtra(Intent.EXTRA_USER_ID))) {
                    return;
                }
                final int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);

                if (DEBUG) {
                    Log.d(LOG_TAG,
                            "User with alarm id   " + intent.getIntExtra(Intent.EXTRA_USER_ID,
                                    -1) + "  current user id " + intent.getIntExtra(
                                    EXTRA_CURRENT_USER_ID, -1));
                }
                mNotificationManager.cancelAsUser(LOG_TAG, notificationId,
                        UserHandle.of(intent.getIntExtra(EXTRA_CURRENT_USER_ID, -1)));
                if (ACTION_MUTE_SOUND.equals(intent.getAction())) {
                    final AudioManager audioManager =
                            mSystemUserContext.getSystemService(AudioManager.class);
                    if (audioManager != null) {
                        for (AudioPlaybackConfiguration apc :
                                audioManager.getActivePlaybackConfigurations()) {
                            if (apc.getAudioAttributes().getUsage() == USAGE_ALARM) {
                                if (apc.getPlayerProxy() != null) {
                                    apc.getPlayerProxy().stop();
                                }
                            }
                        }
                    }
                    Vibrator vibrator = mSystemUserContext.getSystemService(Vibrator.class);
                    if (vibrator != null && vibrator.isVibrating()) {
                        vibrator.cancel();
                    }
                } else if (ACTION_SWITCH_USER.equals(intent.getAction())) {
                    service.switchUser(intent.getIntExtra(Intent.EXTRA_USER_ID, -1));
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MUTE_SOUND);
        filter.addAction(ACTION_SWITCH_USER);
        mSystemUserContext.registerReceiver(backgroundUserNotificationBroadcastReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Check if sound is coming from background user and show notification is required.
     */
    @VisibleForTesting
    void notifyForegroundUserAboutSoundIfNecessary(AudioFocusInfo afi, Context
            foregroundContext) throws RemoteException {
        final int userId = UserHandle.getUserId(afi.getClientUid());
        final int usage = afi.getAttributes().getUsage();
        String userName = mUserManager.getUserInfo(userId).name;
        if (userId != foregroundContext.getUserId()) {
            //TODO: b/349138482 - Add handling of cases when usage == USAGE_NOTIFICATION_RINGTONE
            if (usage == USAGE_ALARM) {
                Intent muteIntent = createIntent(ACTION_MUTE_SOUND, afi, foregroundContext, userId);
                PendingIntent mutePI = PendingIntent.getBroadcast(mSystemUserContext, 0,
                        muteIntent, PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
                Intent switchIntent = createIntent(ACTION_SWITCH_USER, afi, foregroundContext,
                        userId);
                PendingIntent switchPI = PendingIntent.getBroadcast(mSystemUserContext, 0,
                        switchIntent, PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

                mUserWithNotification = foregroundContext.getUserId();
                mNotificationManager.notifyAsUser(LOG_TAG, afi.getClientUid(),
                        createNotification(userName, mutePI, switchPI, foregroundContext),
                        foregroundContext.getUser());
            }
        }
    }

    /**
     * If notification is present, dismisses it. To be called when the relevant sound loses focus.
     */
    private void dismissNotificationIfNecessary(AudioFocusInfo afi) {
        if (mUserWithNotification >= 0) {
            mNotificationManager.cancelAsUser(LOG_TAG, afi.getClientUid(),
                    UserHandle.of(mUserWithNotification));
        }
        mUserWithNotification = -1;
    }

    private Intent createIntent(String intentAction, AudioFocusInfo afi, Context fgUserContext,
            int userId) {
        final Intent intent = new Intent(intentAction);
        intent.putExtra(EXTRA_CURRENT_USER_ID, fgUserContext.getUserId());
        intent.putExtra(EXTRA_NOTIFICATION_ID, afi.getClientUid());
        intent.putExtra(Intent.EXTRA_USER_ID, userId);
        return intent;
    }

    private Notification createNotification(String userName, PendingIntent muteIntent,
            PendingIntent switchIntent, Context fgContext) {
        final String title = fgContext.getString(R.string.bg_user_sound_notification_title_alarm,
                userName);
        final int icon = R.drawable.ic_audio_alarm;
        final Notification.Action mute = new Notification.Action.Builder(null,
                fgContext.getString(R.string.bg_user_sound_notification_button_mute),
                muteIntent).build();
        final Notification.Action switchUser = new Notification.Action.Builder(null,
                fgContext.getString(R.string.bg_user_sound_notification_button_switch_user),
                switchIntent).build();
        return new Notification.Builder(mSystemUserContext, BUSN_CHANNEL_ID)
                .setSmallIcon(icon)
                .setTicker(title)
                .setWhen(0)
                .setOngoing(true)
                .setColor(fgContext.getColor(R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentIntent(muteIntent)
                .setAutoCancel(true)
                .setActions(mute, switchUser)
                .setContentText(fgContext.getString(R.string.bg_user_sound_notification_message))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }
}

