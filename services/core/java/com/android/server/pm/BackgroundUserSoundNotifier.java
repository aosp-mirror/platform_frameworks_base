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

import android.annotation.Nullable;
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
import android.content.pm.UserInfo;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiopolicy.AudioPolicy;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

public class BackgroundUserSoundNotifier {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = BackgroundUserSoundNotifier.class.getSimpleName();
    private static final String BUSN_CHANNEL_ID = "bg_user_sound_channel";
    private static final String BUSN_CHANNEL_NAME = "BackgroundUserSound";
    public static final String ACTION_MUTE_SOUND = "com.android.server.ACTION_MUTE_BG_USER";
    private static final String ACTION_SWITCH_USER = "com.android.server.ACTION_SWITCH_TO_USER";
    private static final String ACTION_DISMISS_NOTIFICATION =
            "com.android.server.ACTION_DISMISS_NOTIFICATION";
    /**
     * The clientUid from the AudioFocusInfo of the background user,
     * for which an active notification is currently displayed.
     * Set to -1 if no notification is being shown.
     * TODO: b/367615180 - add support for multiple simultaneous alarms
     */
    @VisibleForTesting
    int mNotificationClientUid = -1;
    @VisibleForTesting
    AudioPolicy mFocusControlAudioPolicy;
    @VisibleForTesting
    BackgroundUserListener mBgUserListener;
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
        createNotificationChannel();
        setupFocusControlAudioPolicy();
    }

    /**
     * Creates a dedicated channel for background user related notifications.
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(BUSN_CHANNEL_ID, BUSN_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void setupFocusControlAudioPolicy() {
        // Used to configure our audio policy to handle focus events.
        // This gives us the ability to decide which audio focus requests to accept and bypasses
        // the framework ducking logic.
        ActivityManager am = mSystemUserContext.getSystemService(ActivityManager.class);

        registerReceiver(am);
        mBgUserListener = new BackgroundUserListener(mSystemUserContext);
        AudioPolicy.Builder focusControlPolicyBuilder = new AudioPolicy.Builder(mSystemUserContext);
        focusControlPolicyBuilder.setLooper(Looper.getMainLooper());

        focusControlPolicyBuilder.setAudioPolicyFocusListener(mBgUserListener);

        mFocusControlAudioPolicy = focusControlPolicyBuilder.build();
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
            BackgroundUserSoundNotifier.this.dismissNotificationIfNecessary();
        }
    }

    @VisibleForTesting
    BackgroundUserListener getAudioPolicyFocusListener() {
        return  mBgUserListener;
    }

    /**
     * Registers a BroadcastReceiver for actions related to background user sound notifications.
     *  When ACTION_MUTE_SOUND is received, it mutes a background user's alarm sound.
     *  When ACTION_SWITCH_USER is received, a switch to the background user with alarm is started.
     */
    private void registerReceiver(ActivityManager activityManager) {
        BroadcastReceiver backgroundUserNotificationBroadcastReceiver = new BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mNotificationClientUid == -1) {
                    return;
                }
                dismissNotification();

                if (DEBUG) {
                    final int actionIndex = intent.getAction().lastIndexOf(".") + 1;
                    final String action = intent.getAction().substring(actionIndex);
                    Log.d(LOG_TAG, "Action requested: " + action + ", by userId "
                            + ActivityManager.getCurrentUser() + " for alarm on user "
                            + UserHandle.getUserHandleForUid(mNotificationClientUid));
                }

                if (ACTION_MUTE_SOUND.equals(intent.getAction())) {
                    muteAlarmSounds(mSystemUserContext);
                } else if (ACTION_SWITCH_USER.equals(intent.getAction())) {
                    activityManager.switchUser(UserHandle.getUserId(mNotificationClientUid));
                }

                mNotificationClientUid = -1;
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MUTE_SOUND);
        filter.addAction(ACTION_SWITCH_USER);
        filter.addAction(ACTION_DISMISS_NOTIFICATION);
        mSystemUserContext.registerReceiver(backgroundUserNotificationBroadcastReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Stop player proxy for the ongoing alarm and drop focus for its AudioFocusInfo.
     */
    @SuppressLint("MissingPermission")
    @VisibleForTesting
    void muteAlarmSounds(Context context) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager != null) {
            for (AudioPlaybackConfiguration apc : audioManager.getActivePlaybackConfigurations()) {
                if (apc.getClientUid() == mNotificationClientUid && apc.getPlayerProxy() != null) {
                    apc.getPlayerProxy().stop();
                }
            }
        }

        AudioFocusInfo currentAfi = getAudioFocusInfoForNotification();
        if (currentAfi != null) {
            mFocusControlAudioPolicy.sendFocusLossAndUpdate(currentAfi);
        }
    }

    /**
     * Check if sound is coming from background user and show notification is required.
     */
    @VisibleForTesting
    void notifyForegroundUserAboutSoundIfNecessary(AudioFocusInfo afi, Context foregroundContext)
            throws RemoteException {
        final int userId = UserHandle.getUserId(afi.getClientUid());
        final int usage = afi.getAttributes().getUsage();
        UserInfo userInfo = mUserManager.getUserInfo(userId);
        // Only show notification if the sound is coming from background user and the notification
        // is not already shown.
        if (userInfo != null && userId != foregroundContext.getUserId()
                && mNotificationClientUid == -1) {
            //TODO: b/349138482 - Add handling of cases when usage == USAGE_NOTIFICATION_RINGTONE
            if (usage == USAGE_ALARM) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Alarm ringing on background user " + userId
                            + ", displaying notification for current user "
                            + foregroundContext.getUserId());
                }

                mNotificationClientUid = afi.getClientUid();

                mNotificationManager.notifyAsUser(LOG_TAG, mNotificationClientUid,
                        createNotification(userInfo.name, foregroundContext),
                        foregroundContext.getUser());
            }
        }
    }

    /**
     * Dismisses notification if the associated focus has been removed from the focus stack.
     * Notification remains if the focus is temporarily lost due to another client taking over the
     * focus ownership.
     */
    @VisibleForTesting
    void dismissNotificationIfNecessary() {
        if (getAudioFocusInfoForNotification() == null && mNotificationClientUid >= 0) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Alarm ringing on background user "
                        + UserHandle.getUserHandleForUid(mNotificationClientUid).getIdentifier()
                        + " left focus stack, dismissing notification");
            }
            dismissNotification();
            mNotificationClientUid = -1;
        }
    }

    /**
     * Dismisses notification for all users in case user switch occurred after notification was
     * shown.
     */
    @SuppressLint("MissingPermission")
    private void dismissNotification() {
        mNotificationManager.cancelAsUser(LOG_TAG, mNotificationClientUid, UserHandle.ALL);
    }

    /**
     * Returns AudioFocusInfo associated with the current notification.
     */
    @SuppressLint("MissingPermission")
    @VisibleForTesting
    @Nullable
    AudioFocusInfo getAudioFocusInfoForNotification() {
        if (mNotificationClientUid >= 0) {
            List<AudioFocusInfo> stack = mFocusControlAudioPolicy.getFocusStack();
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i).getClientUid() == mNotificationClientUid) {
                    return stack.get(i);
                }
            }
        }
        return null;
    }

    private PendingIntent createPendingIntent(String intentAction) {
        final Intent intent = new Intent(intentAction);
        PendingIntent resultPI =  PendingIntent.getBroadcast(mSystemUserContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return resultPI;
    }

    @VisibleForTesting
    Notification createNotification(String userName, Context fgContext) {
        final String title = fgContext.getString(R.string.bg_user_sound_notification_title_alarm,
                userName);
        final int icon = R.drawable.ic_audio_alarm;

        PendingIntent mutePI = createPendingIntent(ACTION_MUTE_SOUND);
        PendingIntent switchPI = createPendingIntent(ACTION_SWITCH_USER);
        PendingIntent dismissNotificationPI = createPendingIntent(ACTION_DISMISS_NOTIFICATION);

        final Notification.Action mute = new Notification.Action.Builder(null,
                fgContext.getString(R.string.bg_user_sound_notification_button_mute),
                mutePI).build();
        final Notification.Action switchUser = new Notification.Action.Builder(null,
                fgContext.getString(R.string.bg_user_sound_notification_button_switch_user),
                switchPI).build();

        Notification.Builder notificationBuilder = new Notification.Builder(mSystemUserContext,
                BUSN_CHANNEL_ID)
                .setSmallIcon(icon)
                .setTicker(title)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(0)
                .setOngoing(true)
                .setColor(fgContext.getColor(R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentIntent(mutePI)
                .setAutoCancel(true)
                .setDeleteIntent(dismissNotificationPI)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (mUserManager.isUserSwitcherEnabled() && (mUserManager.getUserSwitchability(
                fgContext.getUser()) == UserManager.SWITCHABILITY_STATUS_OK)) {
            notificationBuilder.setActions(mute, switchUser);
        } else {
            notificationBuilder.setActions(mute);
        }

        return notificationBuilder.build();
    }
}
