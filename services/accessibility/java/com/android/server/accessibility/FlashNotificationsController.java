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

package com.android.server.accessibility;

import static android.view.accessibility.AccessibilityManager.FLASH_REASON_ALARM;
import static android.view.accessibility.AccessibilityManager.FLASH_REASON_PREVIEW;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.animation.ObjectAnimator;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager.FlashNotificationReason;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

class FlashNotificationsController {
    private static final String LOG_TAG = "FlashNotifController";
    private static final boolean DEBUG = true;

    private static final String WAKE_LOCK_TAG = "a11y:FlashNotificationsController";

    /** The tag for flash notification which is triggered by short/long preview. */
    private static final String TAG_PREVIEW = "preview";
    /** The tag for flash notification which is triggered by alarm. */
    private static final String TAG_ALARM = "alarm";

    /** The default flashing type: triggered by an event. It'll flash 2 times in a short period. */
    private static final int TYPE_DEFAULT = 1;
    /**
     * The sequence flashing type: usually triggered by call/alarm. It'll flash infinitely until the
     * call/alarm ends.
     */
    private static final int TYPE_SEQUENCE = 2;
    /**
     * The long preview flashing type: it's only for screen flash preview. It'll flash only 1 time
     * with a long period to show the screen flash effect more clearly.
     */
    private static final int TYPE_LONG_PREVIEW = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_DEFAULT,
            TYPE_SEQUENCE,
            TYPE_LONG_PREVIEW
    })
    @interface FlashNotificationType {}

    private static final int TYPE_DEFAULT_ON_MS = 350;
    private static final int TYPE_DEFAULT_OFF_MS = 250;
    private static final int TYPE_SEQUENCE_ON_MS = 700;
    private static final int TYPE_SEQUENCE_OFF_MS = 700;
    private static final int TYPE_LONG_PREVIEW_ON_MS = 5000;
    private static final int TYPE_LONG_PREVIEW_OFF_MS = 1000;
    private static final int TYPE_DEFAULT_SCREEN_DELAY_MS = 300;

    private static final int SCREEN_FADE_DURATION_MS = 200;
    private static final int SCREEN_FADE_OUT_TIMEOUT_MS = 10;

    @ColorInt
    private static final int SCREEN_DEFAULT_COLOR = 0x00FFFF00;
    @ColorInt
    private static final int SCREEN_DEFAULT_ALPHA = 0x66000000;
    @ColorInt
    private static final int SCREEN_DEFAULT_COLOR_WITH_ALPHA =
            SCREEN_DEFAULT_COLOR | SCREEN_DEFAULT_ALPHA;

    @VisibleForTesting
    static final String ACTION_FLASH_NOTIFICATION_START_PREVIEW =
            "com.android.internal.intent.action.FLASH_NOTIFICATION_START_PREVIEW";
    @VisibleForTesting
    static final String ACTION_FLASH_NOTIFICATION_STOP_PREVIEW =
            "com.android.internal.intent.action.FLASH_NOTIFICATION_STOP_PREVIEW";
    @VisibleForTesting
    static final String EXTRA_FLASH_NOTIFICATION_PREVIEW_COLOR =
            "com.android.internal.intent.extra.FLASH_NOTIFICATION_PREVIEW_COLOR";
    @VisibleForTesting
    static final String EXTRA_FLASH_NOTIFICATION_PREVIEW_TYPE =
            "com.android.internal.intent.extra.FLASH_NOTIFICATION_PREVIEW_TYPE";

    @VisibleForTesting
    static final int PREVIEW_TYPE_SHORT = 0;
    @VisibleForTesting
    static final int PREVIEW_TYPE_LONG = 1;

    @VisibleForTesting
    static final String SETTING_KEY_CAMERA_FLASH_NOTIFICATION = "camera_flash_notification";
    @VisibleForTesting
    static final String SETTING_KEY_SCREEN_FLASH_NOTIFICATION = "screen_flash_notification";
    @VisibleForTesting
    static final String SETTING_KEY_SCREEN_FLASH_NOTIFICATION_COLOR =
            "screen_flash_notification_color_global";

    /**
     * Timeout of the wake lock (5 minutes). It should normally never triggered, the wakelock
     * should be released after the flashing notification is completed.
     */
    private static final long WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final PowerManager.WakeLock mWakeLock;
    @GuardedBy("mFlashNotifications")
    private final LinkedList<FlashNotification> mFlashNotifications = new LinkedList<>();
    private final Handler mMainHandler;
    private final Handler mCallbackHandler;
    private boolean mIsTorchTouched = false;
    private boolean mIsTorchOn = false;
    private boolean mIsCameraFlashNotificationEnabled = false;
    private boolean mIsScreenFlashNotificationEnabled = false;
    private boolean mIsAlarming = false;
    private int mDisplayState = Display.STATE_OFF;
    private boolean mIsCameraOpened = false;
    private CameraManager mCameraManager;
    private String mCameraId = null;

    private final CameraManager.TorchCallback mTorchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (mCameraId != null && mCameraId.equals(cameraId)) {
                mIsTorchOn = enabled;
                if (DEBUG) Log.d(LOG_TAG, "onTorchModeChanged, set mIsTorchOn=" + enabled);
            }
        }
    };
    @VisibleForTesting
    final CameraManager.AvailabilityCallback mTorchAvailabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraOpened(@NonNull String cameraId, @NonNull String packageId) {
                    if (mCameraId != null && mCameraId.equals(cameraId)) {
                        mIsCameraOpened = true;
                    }
                }

                @Override
                public void onCameraClosed(@NonNull String cameraId) {
                    if (mCameraId != null && mCameraId.equals(cameraId)) {
                        mIsCameraOpened = false;
                    }
                }
            };
    private View mScreenFlashNotificationOverlayView;
    private FlashNotification mCurrentFlashNotification;

    private final AudioManager.AudioPlaybackCallback mAudioPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    boolean isAlarmActive = false;
                    if (configs != null) {
                        isAlarmActive = configs.stream()
                                .anyMatch(config -> config.isActive()
                                        && config.getAudioAttributes().getUsage()
                                        == AudioAttributes.USAGE_ALARM);
                    }

                    if (mIsAlarming != isAlarmActive) {
                        if (DEBUG) Log.d(LOG_TAG, "alarm state changed: " + isAlarmActive);
                        if (isAlarmActive) {
                            startFlashNotificationSequenceForAlarm();
                        } else {
                            stopFlashNotificationSequenceForAlarm();
                        }
                        mIsAlarming = isAlarmActive;
                    }
                }
            };
    private volatile FlashNotificationThread mThread;
    private final Handler mFlashNotificationHandler;
    @VisibleForTesting
    final FlashBroadcastReceiver mFlashBroadcastReceiver;


    FlashNotificationsController(Context context) {
        this(context, getStartedHandler("FlashNotificationThread"), getStartedHandler(LOG_TAG));
    }

    @VisibleForTesting
    FlashNotificationsController(Context context, Handler flashNotificationHandler,
            Handler callbackHandler) {
        mContext = context;
        mMainHandler = new Handler(mContext.getMainLooper());
        mFlashNotificationHandler = flashNotificationHandler;
        mCallbackHandler = callbackHandler;

        new FlashContentObserver(mMainHandler).register(mContext.getContentResolver());

        final IntentFilter broadcastFilter = new IntentFilter();
        broadcastFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        broadcastFilter.addAction(ACTION_FLASH_NOTIFICATION_START_PREVIEW);
        broadcastFilter.addAction(ACTION_FLASH_NOTIFICATION_STOP_PREVIEW);
        mFlashBroadcastReceiver = new FlashBroadcastReceiver();
        mContext.registerReceiver(
                mFlashBroadcastReceiver, broadcastFilter, Context.RECEIVER_NOT_EXPORTED);

        final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);

        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        final DisplayManager.DisplayListener displayListener =
                new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                        if (mDisplayManager != null) {
                            Display display = mDisplayManager.getDisplay(displayId);
                            if (display != null) {
                                mDisplayState = display.getState();
                            }
                        }
                    }
                };

        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(displayListener, null);
        }
    }

    private static Handler getStartedHandler(String tag) {
        HandlerThread handlerThread = new HandlerThread(tag);
        handlerThread.start();
        return handlerThread.getThreadHandler();
    }


    boolean startFlashNotificationSequence(String opPkg,
            @FlashNotificationReason int reason, IBinder token) {
        final FlashNotification flashNotification = new FlashNotification(opPkg, TYPE_SEQUENCE,
                getScreenFlashColorPreference(reason),
                token, () -> stopFlashNotification(opPkg));

        if (!flashNotification.tryLinkToDeath()) return false;

        requestStartFlashNotification(flashNotification);
        return true;
    }

    boolean stopFlashNotificationSequence(String opPkg) {
        stopFlashNotification(opPkg);
        return true;
    }

    boolean startFlashNotificationEvent(String opPkg, int reason, String reasonPkg) {
        requestStartFlashNotification(new FlashNotification(opPkg, TYPE_DEFAULT,
                getScreenFlashColorPreference(reason, reasonPkg)));
        return true;
    }

    private void startFlashNotificationShortPreview() {
        requestStartFlashNotification(new FlashNotification(TAG_PREVIEW, TYPE_DEFAULT,
                getScreenFlashColorPreference(FLASH_REASON_PREVIEW)));
    }

    private void startFlashNotificationLongPreview(@ColorInt int color) {
        requestStartFlashNotification(new FlashNotification(TAG_PREVIEW, TYPE_LONG_PREVIEW,
                color));
    }

    private void stopFlashNotificationLongPreview() {
        stopFlashNotification(TAG_PREVIEW);
    }

    private void startFlashNotificationSequenceForAlarm() {
        requestStartFlashNotification(new FlashNotification(TAG_ALARM, TYPE_SEQUENCE,
                getScreenFlashColorPreference(FLASH_REASON_ALARM)));
    }

    private void stopFlashNotificationSequenceForAlarm() {
        stopFlashNotification(TAG_ALARM);
    }

    private void requestStartFlashNotification(FlashNotification flashNotification) {
        if (DEBUG) Log.d(LOG_TAG, "requestStartFlashNotification");

        boolean isFeatureOn = FeatureFlagUtils.isEnabled(mContext,
                FeatureFlagUtils.SETTINGS_FLASH_NOTIFICATIONS);
        mIsCameraFlashNotificationEnabled = isFeatureOn && Settings.System.getIntForUser(
                mContext.getContentResolver(), SETTING_KEY_CAMERA_FLASH_NOTIFICATION, 0,
                UserHandle.USER_CURRENT) != 0;
        mIsScreenFlashNotificationEnabled = isFeatureOn && Settings.System.getIntForUser(
                mContext.getContentResolver(), SETTING_KEY_SCREEN_FLASH_NOTIFICATION, 0,
                UserHandle.USER_CURRENT) != 0;

        // To prevent unexpectedly screen flash when screen is off, delays the TYPE_DEFAULT screen
        // flash since mDisplayState is not refreshed to STATE_OFF immediately after screen is
        // turned off. No need to delay TYPE_SEQUENCE screen flash as calls and alarms will always
        // wake up the screen.
        // TODO(b/267121704) refactor the logic to remove delay workaround
        if (flashNotification.mType == TYPE_DEFAULT && mIsScreenFlashNotificationEnabled) {
            mMainHandler.sendMessageDelayed(
                    obtainMessage(FlashNotificationsController::startFlashNotification, this,
                            flashNotification), TYPE_DEFAULT_SCREEN_DELAY_MS);
            if (DEBUG) Log.i(LOG_TAG, "give some delay for flash notification");
        } else {
            startFlashNotification(flashNotification);
        }
    }

    private void stopFlashNotification(String tag) {
        if (DEBUG) Log.i(LOG_TAG, "stopFlashNotification: tag=" + tag);
        synchronized (mFlashNotifications) {
            final FlashNotification notification = removeFlashNotificationLocked(tag);
            if (mCurrentFlashNotification != null && notification == mCurrentFlashNotification) {
                stopFlashNotificationLocked();
                startNextFlashNotificationLocked();
            }
        }
    }

    private void prepareForCameraFlashNotification() {
        mCameraManager = mContext.getSystemService(CameraManager.class);

        if (mCameraManager != null) {
            try {
                mCameraId = getCameraId();
            } catch (CameraAccessException e) {
                Log.e(LOG_TAG, "CameraAccessException", e);
            }
            mCameraManager.registerTorchCallback(mTorchCallback, null);
        }
    }

    private String getCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();

        for (String id : ids) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null && lensFacing != null
                    && flashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                if (DEBUG) Log.d(LOG_TAG, "Found valid camera, cameraId=" + id);
                return id;
            }
        }
        return null;
    }

    private void showScreenNotificationOverlayView(@ColorInt int color) {
        mMainHandler.sendMessage(obtainMessage(
                FlashNotificationsController::showScreenNotificationOverlayViewMainThread,
                this, color));
    }

    private void hideScreenNotificationOverlayView() {
        mMainHandler.sendMessage(obtainMessage(
                FlashNotificationsController::fadeOutScreenNotificationOverlayViewMainThread,
                this));
        mMainHandler.sendMessageDelayed(obtainMessage(
                FlashNotificationsController::hideScreenNotificationOverlayViewMainThread,
                this), SCREEN_FADE_DURATION_MS + SCREEN_FADE_OUT_TIMEOUT_MS);
    }

    private void showScreenNotificationOverlayViewMainThread(@ColorInt int color) {
        if (DEBUG) Log.d(LOG_TAG, "showScreenNotificationOverlayViewMainThread");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;

        // Main display
        if (mScreenFlashNotificationOverlayView == null) {
            mScreenFlashNotificationOverlayView = getScreenNotificationOverlayView(color);
            mContext.getSystemService(WindowManager.class).addView(
                    mScreenFlashNotificationOverlayView, params);
            fadeScreenNotificationOverlayViewMainThread(mScreenFlashNotificationOverlayView, true);
        }
    }

    private void fadeOutScreenNotificationOverlayViewMainThread() {
        if (DEBUG) Log.d(LOG_TAG, "fadeOutScreenNotificationOverlayViewMainThread");
        if (mScreenFlashNotificationOverlayView != null) {
            fadeScreenNotificationOverlayViewMainThread(mScreenFlashNotificationOverlayView, false);
        }
    }

    private void fadeScreenNotificationOverlayViewMainThread(View view, boolean in) {
        ObjectAnimator fade = ObjectAnimator.ofFloat(view, "alpha", in ? 0.0f : 1.0f,
                in ? 1.0f : 0.0f);
        fade.setInterpolator(new AccelerateInterpolator());
        fade.setAutoCancel(true);
        fade.setDuration(SCREEN_FADE_DURATION_MS);
        fade.start();
    }

    private void hideScreenNotificationOverlayViewMainThread() {
        if (DEBUG) Log.d(LOG_TAG, "hideScreenNotificationOverlayViewMainThread");
        if (mScreenFlashNotificationOverlayView != null) {
            mScreenFlashNotificationOverlayView.setVisibility(View.GONE);
            mContext.getSystemService(WindowManager.class).removeView(
                    mScreenFlashNotificationOverlayView);
            mScreenFlashNotificationOverlayView = null;
        }
    }

    private View getScreenNotificationOverlayView(@ColorInt int color) {
        View screenNotificationOverlayView = new FrameLayout(mContext);
        screenNotificationOverlayView.setBackgroundColor(color);
        screenNotificationOverlayView.setAlpha(0.0f);
        return screenNotificationOverlayView;
    }

    @ColorInt
    private int getScreenFlashColorPreference(@FlashNotificationReason int reason,
            String reasonPkg) {
        // TODO(b/267121466) implement getting color per reason, reasonPkg basis
        return getScreenFlashColorPreference();
    }

    @ColorInt
    private int getScreenFlashColorPreference(@FlashNotificationReason int reason) {
        // TODO(b/267121466) implement getting color per reason basis
        return getScreenFlashColorPreference();
    }

    @ColorInt
    private int getScreenFlashColorPreference() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                SETTING_KEY_SCREEN_FLASH_NOTIFICATION_COLOR, SCREEN_DEFAULT_COLOR_WITH_ALPHA,
                UserHandle.USER_CURRENT);
    }

    private void startFlashNotification(@NonNull FlashNotification flashNotification) {
        final int type = flashNotification.mType;
        final String tag = flashNotification.mTag;
        if (DEBUG) Log.i(LOG_TAG, "startFlashNotification: type=" + type + ", tag=" + tag);

        if (!(mIsCameraFlashNotificationEnabled
                || mIsScreenFlashNotificationEnabled
                || flashNotification.mForceStartScreenFlash)) {
            if (DEBUG) Log.d(LOG_TAG, "Flash notification is disabled");
            return;
        }

        if (mIsCameraOpened) {
            if (DEBUG) Log.d(LOG_TAG, "Since camera for torch is opened, block notification.");
            return;
        }

        if (mIsCameraFlashNotificationEnabled && mCameraId == null) {
            prepareForCameraFlashNotification();
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mFlashNotifications) {
                if (type == TYPE_DEFAULT || type == TYPE_LONG_PREVIEW) {
                    if (mCurrentFlashNotification != null) {
                        if (DEBUG) {
                            Log.i(LOG_TAG,
                                    "Default type of flash notification can not work because "
                                            + "previous flash notification is working");
                        }
                    } else {
                        startFlashNotificationLocked(flashNotification);
                    }
                } else if (type == TYPE_SEQUENCE) {
                    if (mCurrentFlashNotification != null) {
                        removeFlashNotificationLocked(tag);
                        stopFlashNotificationLocked();
                    }
                    mFlashNotifications.addFirst(flashNotification);
                    startNextFlashNotificationLocked();
                } else {
                    Log.e(LOG_TAG, "Unavailable flash notification type");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mFlashNotifications")
    private FlashNotification removeFlashNotificationLocked(String tag) {
        ListIterator<FlashNotification> iterator = mFlashNotifications.listIterator(0);
        while (iterator.hasNext()) {
            FlashNotification notification = iterator.next();
            if (notification != null && notification.mTag.equals(tag)) {
                iterator.remove();
                notification.tryUnlinkToDeath();
                if (DEBUG) {
                    Log.i(LOG_TAG,
                            "removeFlashNotificationLocked: tag=" + notification.mTag);
                }
                return notification;
            }
        }
        if (mCurrentFlashNotification != null && mCurrentFlashNotification.mTag.equals(tag)) {
            mCurrentFlashNotification.tryUnlinkToDeath();
            return mCurrentFlashNotification;
        }
        return null;
    }

    @GuardedBy("mFlashNotifications")
    private void stopFlashNotificationLocked() {
        if (mThread != null) {
            if (DEBUG) {
                Log.i(LOG_TAG,
                        "stopFlashNotificationLocked: tag=" + mThread.mFlashNotification.mTag);
            }
            mThread.cancel();
            mThread = null;
        }
        doCameraFlashNotificationOff();
        doScreenFlashNotificationOff();
    }

    @GuardedBy("mFlashNotifications")
    private void startNextFlashNotificationLocked() {
        if (DEBUG) Log.i(LOG_TAG, "startNextFlashNotificationLocked");
        if (mFlashNotifications.size() <= 0) {
            mCurrentFlashNotification = null;
            return;
        }
        startFlashNotificationLocked(mFlashNotifications.getFirst());
    }

    @GuardedBy("mFlashNotifications")
    private void startFlashNotificationLocked(@NonNull final FlashNotification notification) {
        if (DEBUG) {
            Log.i(LOG_TAG, "startFlashNotificationLocked: type=" + notification.mType + ", tag="
                    + notification.mTag);
        }
        mCurrentFlashNotification = notification;
        mThread = new FlashNotificationThread(notification);
        mFlashNotificationHandler.post(mThread);
    }

    private boolean isDozeMode() {
        return mDisplayState == Display.STATE_DOZE || mDisplayState == Display.STATE_DOZE_SUSPEND;
    }

    private void doCameraFlashNotificationOn() {
        if (mIsCameraFlashNotificationEnabled && !mIsTorchOn) {
            doCameraFlashNotification(true);
        }
        if (DEBUG) {
            Log.i(LOG_TAG, "doCameraFlashNotificationOn: "
                    + "isCameraFlashNotificationEnabled=" + mIsCameraFlashNotificationEnabled
                    + ", isTorchOn=" + mIsTorchOn
                    + ", isTorchTouched=" + mIsTorchTouched);
        }
    }

    private void doCameraFlashNotificationOff() {
        if (mIsTorchTouched) {
            doCameraFlashNotification(false);
        }
        if (DEBUG) {
            Log.i(LOG_TAG, "doCameraFlashNotificationOff: "
                    + "isCameraFlashNotificationEnabled=" + mIsCameraFlashNotificationEnabled
                    + ", isTorchOn=" + mIsTorchOn
                    + ", isTorchTouched=" + mIsTorchTouched);
        }
    }

    private void doScreenFlashNotificationOn(@ColorInt int color, boolean forceStartScreenFlash) {
        final boolean isDoze = isDozeMode();
        if ((mIsScreenFlashNotificationEnabled || forceStartScreenFlash) && !isDoze) {
            showScreenNotificationOverlayView(color);
        }
        if (DEBUG) {
            Log.i(LOG_TAG, "doScreenFlashNotificationOn: "
                    + "isScreenFlashNotificationEnabled=" + mIsScreenFlashNotificationEnabled
                    + ", isDozeMode=" + isDoze
                    + ", color=" + Integer.toHexString(color));
        }
    }

    private void doScreenFlashNotificationOff() {
        hideScreenNotificationOverlayView();
        if (DEBUG) {
            Log.i(LOG_TAG, "doScreenFlashNotificationOff: "
                    + "isScreenFlashNotificationEnabled=" + mIsScreenFlashNotificationEnabled);
        }
    }

    private void doCameraFlashNotification(boolean on) {
        if (DEBUG) Log.d(LOG_TAG, "doCameraFlashNotification: " + on + " mCameraId : " + mCameraId);
        if (mCameraManager != null && mCameraId != null) {
            try {
                mCameraManager.setTorchMode(mCameraId, on);
                mIsTorchTouched = on;
            } catch (CameraAccessException e) {
                Log.e(LOG_TAG, "Failed to setTorchMode: " + e);
            } catch (IllegalArgumentException  e) {
                Log.e(LOG_TAG, "Failed to setTorchMode: " + e);
            }
        } else {
            Log.e(LOG_TAG, "Can not use camera flash notification, please check CameraManager!");
        }
    }

    private static class FlashNotification {
        // Tag could be the requesting package name or constants like TAG_PREVIEW and TAG_ALARM.
        private final String mTag;
        @FlashNotificationType
        private final int mType;
        private final int mOnDuration;
        private final int mOffDuration;
        @ColorInt
        private final int mColor;
        private int mRepeat;
        @Nullable
        private final IBinder mToken;
        @Nullable
        private final IBinder.DeathRecipient mDeathRecipient;
        private final boolean mForceStartScreenFlash;

        private FlashNotification(String tag, @FlashNotificationType int type,
                @ColorInt int color) {
            this(tag, type, color, null, null);
        }

        private FlashNotification(String tag, @FlashNotificationType int type, @ColorInt int color,
                IBinder token, IBinder.DeathRecipient deathRecipient) {
            mType = type;
            mTag = tag;
            mColor = color;
            mToken = token;
            mDeathRecipient = deathRecipient;

            switch (type) {
                case TYPE_SEQUENCE:
                    mOnDuration = TYPE_SEQUENCE_ON_MS;
                    mOffDuration = TYPE_SEQUENCE_OFF_MS;
                    mRepeat = 0; // indefinite
                    mForceStartScreenFlash = false;
                    break;
                case TYPE_LONG_PREVIEW:
                    mOnDuration = TYPE_LONG_PREVIEW_ON_MS;
                    mOffDuration = TYPE_LONG_PREVIEW_OFF_MS;
                    mRepeat = 1;
                    mForceStartScreenFlash = true;
                    break;
                case TYPE_DEFAULT:
                default:
                    mOnDuration = TYPE_DEFAULT_ON_MS;
                    mOffDuration = TYPE_DEFAULT_OFF_MS;
                    mRepeat = 2;
                    mForceStartScreenFlash = false;
                    break;
            }
        }

        boolean tryLinkToDeath() {
            if (mToken == null || mDeathRecipient == null) return false;

            try {
                mToken.linkToDeath(mDeathRecipient, 0);
                return true;
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "RemoteException", e);
                return false;
            }
        }

        boolean tryUnlinkToDeath() {
            if (mToken == null || mDeathRecipient == null) return false;
            try {
                mToken.unlinkToDeath(mDeathRecipient, 0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private class FlashNotificationThread extends Thread {
        private final FlashNotification mFlashNotification;
        private boolean mForceStop;
        @ColorInt
        private int mColor = Color.TRANSPARENT;
        private boolean mShouldDoScreenFlash = false;
        private boolean mShouldDoCameraFlash = false;

        private FlashNotificationThread(@NonNull FlashNotification flashNotification) {
            mFlashNotification = flashNotification;
            mForceStop = false;
        }

        @Override
        public void run() {
            if (DEBUG) Log.d(LOG_TAG, "run started: " + mFlashNotification.mTag);
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            mColor = mFlashNotification.mColor;
            mShouldDoScreenFlash = (Color.alpha(mColor) != Color.TRANSPARENT)
                    || mFlashNotification.mForceStartScreenFlash;
            mShouldDoCameraFlash = mFlashNotification.mType != TYPE_LONG_PREVIEW;
            synchronized (this) {
                mWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
                try {
                    startFlashNotification();
                } finally {
                    doScreenFlashNotificationOff();
                    doCameraFlashNotificationOff();
                    try {
                        mWakeLock.release();
                    } catch (RuntimeException e) {
                        Log.e(LOG_TAG, "Error while releasing FlashNotificationsController"
                                + " wakelock (already released by the system?)");
                    }
                }
            }
            synchronized (mFlashNotifications) {
                if (mThread == this) {
                    mThread = null;
                }
                // Unlink to death recipient for not interrupted flash notification. For flash
                // notification interrupted and stopped by stopFlashNotification(), unlink to
                // death is already handled in stopFlashNotification().
                if (!mForceStop) {
                    mFlashNotification.tryUnlinkToDeath();
                    mCurrentFlashNotification = null;
                }
            }
            if (DEBUG) Log.d(LOG_TAG, "run finished: " + mFlashNotification.mTag);
        }

        private void startFlashNotification() {
            synchronized (this) {
                while (!mForceStop) {
                    if (mFlashNotification.mType != TYPE_SEQUENCE
                            && mFlashNotification.mRepeat >= 0) {
                        if (mFlashNotification.mRepeat-- == 0) {
                            break;
                        }
                    }
                    if (mShouldDoScreenFlash) {
                        doScreenFlashNotificationOn(mColor,
                                mFlashNotification.mForceStartScreenFlash);
                    }
                    if (mShouldDoCameraFlash) {
                        doCameraFlashNotificationOn();
                    }
                    delay(mFlashNotification.mOnDuration);
                    doScreenFlashNotificationOff();
                    doCameraFlashNotificationOff();
                    if (mForceStop) {
                        break;
                    }
                    delay(mFlashNotification.mOffDuration);
                }
            }
        }

        void cancel() {
            if (DEBUG) Log.d(LOG_TAG, "run canceled: " + mFlashNotification.mTag);
            synchronized (this) {
                mThread.mForceStop = true;
                mThread.notify();
            }
        }

        private void delay(long duration) {
            if (duration > 0) {
                long bedtime = duration + SystemClock.uptimeMillis();
                do {
                    try {
                        this.wait(duration);
                    } catch (InterruptedException ignored) {
                    }
                    if (mForceStop) {
                        break;
                    }
                    duration = bedtime - SystemClock.uptimeMillis();
                } while (duration > 0);
            }
        }
    }

    @VisibleForTesting
    class FlashBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            // Some system services not properly initiated before boot complete. Should do the
            // initialization after receiving ACTION_BOOT_COMPLETED.
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                if (UserHandle.myUserId() != ActivityManager.getCurrentUser()) {
                    return;
                }

                mIsCameraFlashNotificationEnabled = Settings.System.getIntForUser(
                        mContext.getContentResolver(), SETTING_KEY_CAMERA_FLASH_NOTIFICATION, 0,
                        UserHandle.USER_CURRENT) != 0;
                if (mIsCameraFlashNotificationEnabled) {
                    prepareForCameraFlashNotification();
                } else {
                    if (mCameraManager != null) {
                        mCameraManager.unregisterTorchCallback(mTorchCallback);
                    }
                }

                final AudioManager audioManager = mContext.getSystemService(AudioManager.class);
                if (audioManager != null) {
                    audioManager.registerAudioPlaybackCallback(mAudioPlaybackCallback,
                            mCallbackHandler);
                }

                mCameraManager = mContext.getSystemService(CameraManager.class);
                mCameraManager.registerAvailabilityCallback(mTorchAvailabilityCallback,
                        mCallbackHandler);

            } else if (ACTION_FLASH_NOTIFICATION_START_PREVIEW.equals(intent.getAction())) {
                if (DEBUG) Log.i(LOG_TAG, "ACTION_FLASH_NOTIFICATION_START_PREVIEW");
                final int color = intent.getIntExtra(EXTRA_FLASH_NOTIFICATION_PREVIEW_COLOR,
                        Color.TRANSPARENT);
                final int type = intent.getIntExtra(EXTRA_FLASH_NOTIFICATION_PREVIEW_TYPE,
                        PREVIEW_TYPE_SHORT);
                if (type == PREVIEW_TYPE_LONG) {
                    startFlashNotificationLongPreview(color);
                } else if (type == PREVIEW_TYPE_SHORT) {
                    startFlashNotificationShortPreview();
                }
            } else if (ACTION_FLASH_NOTIFICATION_STOP_PREVIEW.equals(intent.getAction())) {
                if (DEBUG) Log.i(LOG_TAG, "ACTION_FLASH_NOTIFICATION_STOP_PREVIEW");
                stopFlashNotificationLongPreview();
            }
        }
    }

    private final class FlashContentObserver extends ContentObserver {
        private final Uri mCameraFlashNotificationUri = Settings.System.getUriFor(
                SETTING_KEY_CAMERA_FLASH_NOTIFICATION);
        private final Uri mScreenFlashNotificationUri = Settings.System.getUriFor(
                SETTING_KEY_SCREEN_FLASH_NOTIFICATION);

        FlashContentObserver(Handler handler) {
            super(handler);
        }

        void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mCameraFlashNotificationUri, false, this,
                    UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mScreenFlashNotificationUri, false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mCameraFlashNotificationUri.equals(uri)) {
                mIsCameraFlashNotificationEnabled = Settings.System.getIntForUser(
                        mContext.getContentResolver(), SETTING_KEY_CAMERA_FLASH_NOTIFICATION, 0,
                        UserHandle.USER_CURRENT) != 0;
                if (mIsCameraFlashNotificationEnabled) {
                    prepareForCameraFlashNotification();
                } else {
                    mIsTorchOn = false;
                    if (mCameraManager != null) {
                        mCameraManager.unregisterTorchCallback(mTorchCallback);
                    }
                }
            } else if (mScreenFlashNotificationUri.equals(uri)) {
                mIsScreenFlashNotificationEnabled = Settings.System.getIntForUser(
                        mContext.getContentResolver(), SETTING_KEY_SCREEN_FLASH_NOTIFICATION, 0,
                        UserHandle.USER_CURRENT) != 0;
            }
        }
    }
}
