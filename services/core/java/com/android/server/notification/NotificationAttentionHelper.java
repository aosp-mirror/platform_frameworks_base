/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.Notification.FLAG_INSISTENT;
import static android.app.Notification.FLAG_ONLY_ALERT_ONCE;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.EventLogTags;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * NotificationManagerService helper for handling notification attention effects:
 *  make noise, vibrate, or flash the LED.
 * @hide
 */
public final class NotificationAttentionHelper {
    static final String TAG = "NotifAttentionHelper";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    static final boolean DEBUG_INTERRUPTIVENESS = SystemProperties.getBoolean(
            "debug.notification.interruptiveness", false);

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final TelephonyManager mTelephonyManager;
    private final NotificationManagerPrivate mNMP;
    private final SystemUiSystemPropertiesFlags.FlagResolver mFlagResolver;
    private AccessibilityManager mAccessibilityManager;
    private KeyguardManager mKeyguardManager;
    private AudioManager mAudioManager;
    private final LightsManager mLightsManager;
    private final NotificationUsageStats mUsageStats;
    private final ZenModeHelper mZenModeHelper;

    private VibratorHelper mVibratorHelper;
    // The last key in this list owns the hardware.
    ArrayList<String> mLights = new ArrayList<>();
    private LogicalLight mNotificationLight;
    private LogicalLight mAttentionLight;

    private final boolean mUseAttentionLight;
    boolean mHasLight = true;

    private final SettingsObserver mSettingsObserver;

    private boolean mIsAutomotive;
    private boolean mNotificationEffectsEnabledForAutomotive;
    private boolean mDisableNotificationEffects;
    private int mCallState;
    private String mSoundNotificationKey;
    private String mVibrateNotificationKey;
    private boolean mSystemReady;
    private boolean mInCallStateOffHook = false;
    private boolean mScreenOn = true;
    private boolean mUserPresent = false;
    boolean mNotificationPulseEnabled;
    private final Uri mInCallNotificationUri;
    private final AudioAttributes mInCallNotificationAudioAttributes;
    private final float mInCallNotificationVolume;
    private Binder mCallNotificationToken = null;


    public NotificationAttentionHelper(Context context, LightsManager lightsManager,
            AccessibilityManager accessibilityManager, PackageManager packageManager,
            NotificationUsageStats usageStats,
            NotificationManagerPrivate notificationManagerPrivate,
            ZenModeHelper zenModeHelper, SystemUiSystemPropertiesFlags.FlagResolver flagResolver) {
        mContext = context;
        mPackageManager = packageManager;
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mAccessibilityManager = accessibilityManager;
        mLightsManager = lightsManager;
        mNMP = notificationManagerPrivate;
        mUsageStats = usageStats;
        mZenModeHelper = zenModeHelper;
        mFlagResolver = flagResolver;

        mVibratorHelper = new VibratorHelper(context);

        mNotificationLight = mLightsManager.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = mLightsManager.getLight(LightsManager.LIGHT_ID_ATTENTION);

        Resources resources = context.getResources();
        mUseAttentionLight = resources.getBoolean(R.bool.config_useAttentionLight);
        mHasLight =
                resources.getBoolean(com.android.internal.R.bool.config_intrusiveNotificationLed);

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
            mDisableNotificationEffects = true;
        }

        mInCallNotificationUri = Uri.parse(
                "file://" + resources.getString(R.string.config_inCallNotificationSound));
        mInCallNotificationAudioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        mInCallNotificationVolume = resources.getFloat(R.dimen.config_inCallNotificationVolume);

        mSettingsObserver = new SettingsObserver();
    }

    public void onSystemReady() {
        mSystemReady = true;

        mIsAutomotive = mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0);
        mNotificationEffectsEnabledForAutomotive = mContext.getResources().getBoolean(
                R.bool.config_enableServerNotificationEffectsForAutomotive);

        mAudioManager = mContext.getSystemService(AudioManager.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);

        registerBroadcastListeners();
    }

    private void registerBroadcastListeners() {
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager.listen(new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    if (mCallState == state) return;
                    if (DEBUG) Slog.d(TAG, "Call state changed: " + callStateToString(state));
                    mCallState = state;
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, null);

        mContext.getContentResolver().registerContentObserver(
                SettingsObserver.NOTIFICATION_LIGHT_PULSE_URI, false, mSettingsObserver,
                UserHandle.USER_ALL);
    }

    @VisibleForTesting
    /**
     * Determine whether this notification should attempt to make noise, vibrate, or flash the LED
     * @return buzzBeepBlink - bitfield (buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0) |
     *  (polite_attenuated ? 8 : 0) | (polite_muted ? 16 : 0)
     */
    int buzzBeepBlinkLocked(NotificationRecord record, Signals signals) {
        if (mIsAutomotive && !mNotificationEffectsEnabledForAutomotive) {
            return 0;
        }
        boolean buzz = false;
        boolean beep = false;
        boolean blink = false;

        final String key = record.getKey();

        if (DEBUG) {
            Log.d(TAG, "buzzBeepBlinkLocked " + record);
        }

        // Should this notification make noise, vibe, or use the LED?
        final boolean aboveThreshold =
                mIsAutomotive
                        ? record.getImportance() > NotificationManager.IMPORTANCE_DEFAULT
                        : record.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
        // Remember if this notification already owns the notification channels.
        boolean wasBeep = key != null && key.equals(mSoundNotificationKey);
        boolean wasBuzz = key != null && key.equals(mVibrateNotificationKey);
        // These are set inside the conditional if the notification is allowed to make noise.
        boolean hasValidVibrate = false;
        boolean hasValidSound = false;
        boolean sentAccessibilityEvent = false;

        // If the notification will appear in the status bar, it should send an accessibility event
        final boolean suppressedByDnd = record.isIntercepted()
                && (record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_STATUS_BAR) != 0;
        if (!record.isUpdate
                && record.getImportance() > IMPORTANCE_MIN
                && !suppressedByDnd
                && isNotificationForCurrentUser(record, signals)) {
            sendAccessibilityEvent(record);
            sentAccessibilityEvent = true;
        }

        if (aboveThreshold && isNotificationForCurrentUser(record, signals)) {
            if (mSystemReady && mAudioManager != null) {
                Uri soundUri = record.getSound();
                hasValidSound = soundUri != null && !Uri.EMPTY.equals(soundUri);
                VibrationEffect vibration = record.getVibration();
                // Demote sound to vibration if vibration missing & phone in vibration mode.
                if (vibration == null
                        && hasValidSound
                        && (mAudioManager.getRingerModeInternal()
                        == AudioManager.RINGER_MODE_VIBRATE)
                        && mAudioManager.getStreamVolume(
                        AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) == 0) {
                    boolean insistent = (record.getFlags() & Notification.FLAG_INSISTENT) != 0;
                    vibration = mVibratorHelper.createFallbackVibration(insistent);
                }
                hasValidVibrate = vibration != null;
                boolean hasAudibleAlert = hasValidSound || hasValidVibrate;
                if (hasAudibleAlert && !shouldMuteNotificationLocked(record, signals)) {
                    if (!sentAccessibilityEvent) {
                        sendAccessibilityEvent(record);
                        sentAccessibilityEvent = true;
                    }
                    if (DEBUG) Slog.v(TAG, "Interrupting!");
                    boolean isInsistentUpdate = isInsistentUpdate(record);
                    if (hasValidSound) {
                        if (isInsistentUpdate) {
                            // don't reset insistent sound, it's jarring
                            beep = true;
                        } else {
                            if (isInCall()) {
                                playInCallNotification();
                                beep = true;
                            } else {
                                beep = playSound(record, soundUri);
                            }
                            if (beep) {
                                mSoundNotificationKey = key;
                            }
                        }
                    }

                    final boolean ringerModeSilent =
                            mAudioManager.getRingerModeInternal()
                                    == AudioManager.RINGER_MODE_SILENT;
                    if (!isInCall() && hasValidVibrate && !ringerModeSilent) {
                        if (isInsistentUpdate) {
                            buzz = true;
                        } else {
                            buzz = playVibration(record, vibration, hasValidSound);
                            if (buzz) {
                                mVibrateNotificationKey = key;
                            }
                        }
                    }

                    // Try to start flash notification event whenever an audible and non-suppressed
                    // notification is received
                    mAccessibilityManager.startFlashNotificationEvent(mContext,
                            AccessibilityManager.FLASH_REASON_NOTIFICATION,
                            record.getSbn().getPackageName());

                } else if ((record.getFlags() & Notification.FLAG_INSISTENT) != 0) {
                    hasValidSound = false;
                }
            }
        }
        // If a notification is updated to remove the actively playing sound or vibrate,
        // cancel that feedback now
        if (wasBeep && !hasValidSound) {
            clearSoundLocked();
        }
        if (wasBuzz && !hasValidVibrate) {
            clearVibrateLocked();
        }

        // light
        // release the light
        boolean wasShowLights = mLights.remove(key);
        if (canShowLightsLocked(record, signals, aboveThreshold)) {
            mLights.add(key);
            updateLightsLocked();
            if (mUseAttentionLight && mAttentionLight != null) {
                mAttentionLight.pulse();
            }
            blink = true;
        } else if (wasShowLights) {
            updateLightsLocked();
        }
        final int buzzBeepBlink =
                (buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0);
        if (buzzBeepBlink > 0) {
            // Ignore summary updates because we don't display most of the information.
            if (record.getSbn().isGroup() && record.getSbn().getNotification().isGroupSummary()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is not interruptive: summary");
                }
            } else if (record.canBubble()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is not interruptive: bubble");
                }
            } else {
                record.setInterruptive(true);
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is interruptive: alerted");
                }
            }
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_ALERT)
                    .setType(MetricsEvent.TYPE_OPEN)
                    .setSubtype(buzzBeepBlink));
            EventLogTags.writeNotificationAlert(key, buzz ? 1 : 0, beep ? 1 : 0, blink ? 1 : 0);
        }
        record.setAudiblyAlerted(buzz || beep);

        return buzzBeepBlink;
    }

    boolean isInsistentUpdate(final NotificationRecord record) {
        return (Objects.equals(record.getKey(), mSoundNotificationKey)
                || Objects.equals(record.getKey(), mVibrateNotificationKey))
                && isCurrentlyInsistent();
    }

    boolean isCurrentlyInsistent() {
        return isLoopingRingtoneNotification(mNMP.getNotificationByKey(mSoundNotificationKey))
                || isLoopingRingtoneNotification(
                mNMP.getNotificationByKey(mVibrateNotificationKey));
    }

    boolean shouldMuteNotificationLocked(final NotificationRecord record, final Signals signals) {
        // Suppressed because it's a silent update
        final Notification notification = record.getNotification();
        if (record.isUpdate && (notification.flags & FLAG_ONLY_ALERT_ONCE) != 0) {
            return true;
        }

        // Suppressed because a user manually unsnoozed something (or similar)
        if (record.shouldPostSilently()) {
            return true;
        }

        // muted by listener
        final String disableEffects = disableNotificationEffects(record, signals.listenerHints);
        if (disableEffects != null) {
            ZenLog.traceDisableEffects(record, disableEffects);
            return true;
        }

        // suppressed due to DND
        if (record.isIntercepted()) {
            return true;
        }

        // Suppressed because another notification in its group handles alerting
        if (record.getSbn().isGroup()) {
            if (notification.suppressAlertingDueToGrouping()) {
                return true;
            }
        }

        // Suppressed for being too recently noisy
        final String pkg = record.getSbn().getPackageName();
        if (mUsageStats.isAlertRateLimited(pkg)) {
            Slog.e(TAG, "Muting recently noisy " + record.getKey());
            return true;
        }

        // A different looping ringtone, such as an incoming call is playing
        if (isCurrentlyInsistent() && !isInsistentUpdate(record)) {
            return true;
        }

        // Suppressed since it's a non-interruptive update to a bubble-suppressed notification
        final boolean isBubbleOrOverflowed = record.canBubble() && (record.isFlagBubbleRemoved()
                || record.getNotification().isBubbleNotification());
        if (record.isUpdate && !record.isInterruptive() && isBubbleOrOverflowed
                && record.getNotification().getBubbleMetadata() != null) {
            if (record.getNotification().getBubbleMetadata().isNotificationSuppressed()) {
                return true;
            }
        }

        return false;
    }

    private boolean isLoopingRingtoneNotification(final NotificationRecord playingRecord) {
        if (playingRecord != null) {
            if (playingRecord.getAudioAttributes().getUsage() == USAGE_NOTIFICATION_RINGTONE
                    && (playingRecord.getNotification().flags & FLAG_INSISTENT) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean playSound(final NotificationRecord record, Uri soundUri) {
        boolean looping = (record.getNotification().flags & FLAG_INSISTENT) != 0;
        // play notifications if there is no user of exclusive audio focus
        // and the stream volume is not 0 (non-zero volume implies not silenced by SILENT or
        //   VIBRATE ringer mode)
        if (!mAudioManager.isAudioFocusExclusive()
                && (mAudioManager.getStreamVolume(
                AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) != 0)) {
            final long identity = Binder.clearCallingIdentity();
            try {
                final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                if (player != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "Playing sound " + soundUri + " with attributes "
                                + record.getAudioAttributes());
                    }
                    player.playAsync(soundUri, record.getSbn().getUser(), looping,
                            record.getAudioAttributes());
                    return true;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed playSound: " + e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return false;
    }

    private boolean playVibration(final NotificationRecord record, final VibrationEffect effect,
            boolean delayVibForSound) {
        // Escalate privileges so we can use the vibrator even if the
        // notifying app does not have the VIBRATE permission.
        final long identity = Binder.clearCallingIdentity();
        try {
            if (delayVibForSound) {
                new Thread(() -> {
                    // delay the vibration by the same amount as the notification sound
                    final int waitMs = mAudioManager.getFocusRampTimeMs(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                            record.getAudioAttributes());
                    if (DEBUG) {
                        Slog.v(TAG, "Delaying vibration for notification "
                                + record.getKey() + " by " + waitMs + "ms");
                    }
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) { }
                    // Notifications might be canceled before it actually vibrates due to waitMs,
                    // so need to check that the notification is still valid for vibrate.
                    if (mNMP.getNotificationByKey(record.getKey()) != null) {
                        if (record.getKey().equals(mVibrateNotificationKey)) {
                            vibrate(record, effect, true);
                        } else {
                            if (DEBUG) {
                                Slog.v(TAG, "No vibration for notification "
                                        + record.getKey() + ": a new notification is "
                                        + "vibrating, or effects were cleared while waiting");
                            }
                        }
                    } else {
                        Slog.w(TAG, "No vibration for canceled notification "
                                + record.getKey());
                    }
                }).start();
            } else {
                vibrate(record, effect, false);
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void vibrate(NotificationRecord record, VibrationEffect effect, boolean delayed) {
        // We need to vibrate as "android" so we can breakthrough DND. VibratorManagerService
        // doesn't have a concept of vibrating on an app's behalf, so add the app information
        // to the reason so we can still debug from bugreports
        String reason = "Notification (" + record.getSbn().getOpPkg() + " "
                + record.getSbn().getUid() + ") " + (delayed ? "(Delayed)" : "");
        mVibratorHelper.vibrate(effect, record.getAudioAttributes(), reason);
    }

    void playInCallNotification() {
        // TODO: Should we apply politeness to mInCallNotificationVolume ?
        final ContentResolver cr = mContext.getContentResolver();
        if (mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_NORMAL
                && Settings.Secure.getIntForUser(cr,
                Settings.Secure.IN_CALL_NOTIFICATION_ENABLED, 1, cr.getUserId()) != 0) {
            new Thread() {
                @Override
                public void run() {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            if (mCallNotificationToken != null) {
                                player.stop(mCallNotificationToken);
                            }
                            mCallNotificationToken = new Binder();
                            player.play(mCallNotificationToken, mInCallNotificationUri,
                                    mInCallNotificationAudioAttributes,
                                    mInCallNotificationVolume, false);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed playInCallNotification: " + e);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }.start();
        }
    }

    void clearSoundLocked() {
        mSoundNotificationKey = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
            if (player != null) {
                player.stopAsync();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed clearSoundLocked: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void clearVibrateLocked() {
        mVibrateNotificationKey = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            mVibratorHelper.cancelVibration();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void clearLightsLocked() {
        // light
        mLights.clear();
        updateLightsLocked();
    }

    public void clearEffectsLocked(String key) {
        if (key.equals(mSoundNotificationKey)) {
            clearSoundLocked();
        }
        if (key.equals(mVibrateNotificationKey)) {
            clearVibrateLocked();
        }
        boolean removed = mLights.remove(key);
        if (removed) {
            updateLightsLocked();
        }
    }

    public void clearAttentionEffects() {
        clearSoundLocked();
        clearVibrateLocked();
        clearLightsLocked();
    }

    void updateLightsLocked() {
        if (mNotificationLight == null) {
            return;
        }

        // handle notification lights
        NotificationRecord ledNotification = null;
        while (ledNotification == null && !mLights.isEmpty()) {
            final String owner = mLights.get(mLights.size() - 1);
            ledNotification = mNMP.getNotificationByKey(owner);
            if (ledNotification == null) {
                Slog.wtfStack(TAG, "LED Notification does not exist: " + owner);
                mLights.remove(owner);
            }
        }

        // Don't flash while we are in a call or screen is on
        if (ledNotification == null || isInCall() || mScreenOn) {
            mNotificationLight.turnOff();
        } else {
            NotificationRecord.Light light = ledNotification.getLight();
            if (light != null && mNotificationPulseEnabled) {
                // pulse repeatedly
                mNotificationLight.setFlashing(light.color, LogicalLight.LIGHT_FLASH_TIMED,
                        light.onMs, light.offMs);
            }
        }
    }

    boolean canShowLightsLocked(final NotificationRecord record, final Signals signals,
            boolean aboveThreshold) {
        // device lacks light
        if (!mHasLight) {
            return false;
        }
        // user turned lights off globally
        if (!mNotificationPulseEnabled) {
            return false;
        }
        // the notification/channel has no light
        if (record.getLight() == null) {
            return false;
        }
        // unimportant notification
        if (!aboveThreshold) {
            return false;
        }
        // suppressed due to DND
        if ((record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_LIGHTS) != 0) {
            return false;
        }
        // Suppressed because it's a silent update
        final Notification notification = record.getNotification();
        if (record.isUpdate && (notification.flags & FLAG_ONLY_ALERT_ONCE) != 0) {
            return false;
        }
        // Suppressed because another notification in its group handles alerting
        if (record.getSbn().isGroup() && record.getNotification().suppressAlertingDueToGrouping()) {
            return false;
        }
        // not if in call
        if (isInCall()) {
            return false;
        }
        // check current user
        if (!isNotificationForCurrentUser(record, signals)) {
            return false;
        }
        // Light, but only when the screen is off
        return true;
    }

    private String disableNotificationEffects(NotificationRecord record, int listenerHints) {
        if (mDisableNotificationEffects) {
            return "booleanState";
        }

        if ((listenerHints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            return "listenerHints";
        }
        if (record != null && record.getAudioAttributes() != null) {
            if ((listenerHints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
                if (record.getAudioAttributes().getUsage()
                        != AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                    return "listenerNoti";
                }
            }
            if ((listenerHints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
                if (record.getAudioAttributes().getUsage()
                        == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                    return "listenerCall";
                }
            }
        }
        if (mCallState != TelephonyManager.CALL_STATE_IDLE && !mZenModeHelper.isCall(record)) {
            return "callState";
        }

        return null;
    }

    public void updateDisableNotificationEffectsLocked(int status) {
        mDisableNotificationEffects =
                (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
        //if (disableNotificationEffects(null) != null) {
        if (mDisableNotificationEffects) {
            // cancel whatever is going on
            clearAttentionEffects();
        }
    }

    private boolean isInCall() {
        if (mInCallStateOffHook) {
            return true;
        }
        int audioMode = mAudioManager.getMode();
        if (audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            return true;
        }
        return false;
    }

    private static String callStateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "CALL_STATE_IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "CALL_STATE_RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "CALL_STATE_OFFHOOK";
            default: return "CALL_STATE_UNKNOWN_" + state;
        }
    }

    private boolean isNotificationForCurrentUser(final NotificationRecord record,
            final Signals signals) {
        final int currentUser;
        final long token = Binder.clearCallingIdentity();
        try {
            currentUser = ActivityManager.getCurrentUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return (record.getUserId() == UserHandle.USER_ALL || record.getUserId() == currentUser
                || signals.isCurrentProfile);
    }

    void sendAccessibilityEvent(NotificationRecord record) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        final Notification notification = record.getNotification();
        final CharSequence packageName = record.getSbn().getPackageName();
        final AccessibilityEvent event =
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        final int visibilityOverride = record.getPackageVisibilityOverride();
        final int notifVisibility = visibilityOverride == NotificationManager.VISIBILITY_NO_OVERRIDE
                ? notification.visibility : visibilityOverride;
        final int userId = record.getUser().getIdentifier();
        final boolean needPublic = userId >= 0 && mKeyguardManager.isDeviceLocked(userId);
        if (needPublic && notifVisibility != Notification.VISIBILITY_PUBLIC) {
            // Emit the public version if we're on the lockscreen and this notification isn't
            // publicly visible.
            event.setParcelableData(notification.publicVersion);
        } else {
            event.setParcelableData(notification);
        }
        final CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    public void dump(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter) {
        pw.println("\n  Notification attention state:");
        pw.print(prefix);
        pw.println("  mSoundNotificationKey=" + mSoundNotificationKey);
        pw.print(prefix);
        pw.println("  mVibrateNotificationKey=" + mVibrateNotificationKey);
        pw.print(prefix);
        pw.println("  mDisableNotificationEffects=" + mDisableNotificationEffects);
        pw.print(prefix);
        pw.println("  mCallState=" + callStateToString(mCallState));
        pw.print(prefix);
        pw.println("  mSystemReady=" + mSystemReady);
        pw.print(prefix);
        pw.println("  mNotificationPulseEnabled=" + mNotificationPulseEnabled);

        int N = mLights.size();
        if (N > 0) {
            pw.print(prefix);
            pw.println("  Lights List:");
            for (int i=0; i<N; i++) {
                if (i == N - 1) {
                    pw.print("  > ");
                } else {
                    pw.print("    ");
                }
                pw.println(mLights.get(i));
            }
            pw.println("  ");
        }

    }

    // External signals set from NMS
    public static class Signals {
        private final boolean isCurrentProfile;
        private final int listenerHints;

        public Signals(boolean isCurrentProfile, int listenerHints) {
            this.isCurrentProfile = isCurrentProfile;
            this.listenerHints = listenerHints;
        }
    }

    //======================  Observers  =============================
    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Keep track of screen on/off state, but do not turn off the notification light
                // until user passes through the lock screen or views the notification.
                mScreenOn = true;
                updateLightsLocked();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                mUserPresent = false;
                updateLightsLocked();
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                mInCallStateOffHook = TelephonyManager.EXTRA_STATE_OFFHOOK
                        .equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                updateLightsLocked();
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                mUserPresent = true;
                // turn off LED when user passes through lock screen
                if (mNotificationLight != null) {
                    mNotificationLight.turnOff();
                }
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {

        private static final Uri NOTIFICATION_LIGHT_PULSE_URI = Settings.System.getUriFor(
                Settings.System.NOTIFICATION_LIGHT_PULSE);
        public SettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (NOTIFICATION_LIGHT_PULSE_URI.equals(uri)) {
                boolean pulseEnabled = Settings.System.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.System.NOTIFICATION_LIGHT_PULSE, 0,
                        UserHandle.USER_CURRENT)
                        != 0;
                if (mNotificationPulseEnabled != pulseEnabled) {
                    mNotificationPulseEnabled = pulseEnabled;
                    updateLightsLocked();
                }
            }
        }
    }


    //TODO: cleanup most (all?) of these
    //======================= FOR TESTS =====================
    @VisibleForTesting
    void setIsAutomotive(boolean isAutomotive) {
        mIsAutomotive = isAutomotive;
    }

    @VisibleForTesting
    void setNotificationEffectsEnabledForAutomotive(boolean isEnabled) {
        mNotificationEffectsEnabledForAutomotive = isEnabled;
    }

    @VisibleForTesting
    void setSystemReady(boolean systemReady) {
        mSystemReady = systemReady;
    }

    @VisibleForTesting
    void setKeyguardManager(KeyguardManager keyguardManager) {
        mKeyguardManager = keyguardManager;
    }

    @VisibleForTesting
    void setAccessibilityManager(AccessibilityManager am) {
        mAccessibilityManager = am;
    }

    @VisibleForTesting
    VibratorHelper getVibratorHelper() {
        return mVibratorHelper;
    }

    @VisibleForTesting
    void setVibratorHelper(VibratorHelper helper) {
        mVibratorHelper = helper;
    }

    @VisibleForTesting
    void setScreenOn(boolean on) {
        mScreenOn = on;
    }

    @VisibleForTesting
    void setLights(LogicalLight light) {
        mNotificationLight = light;
        mAttentionLight = light;
        mNotificationPulseEnabled = true;
        mHasLight = true;
    }

    @VisibleForTesting
    void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    @VisibleForTesting
    void setInCallStateOffHook(boolean inCallStateOffHook) {
        mInCallStateOffHook = inCallStateOffHook;
    }

}
