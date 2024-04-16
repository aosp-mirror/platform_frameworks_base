/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.LoggingOnly;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.INearbyMediaDevicesProvider;
import android.media.INearbyMediaDevicesUpdateCallback;
import android.media.MediaRoute2Info;
import android.media.NearbyDevice;
import android.media.NearbyMediaDevicesProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.View;

import com.android.internal.compat.IPlatformCompat;
import com.android.internal.statusbar.AppClipsServiceConnector;
import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.IUndoMediaTransferCallback;
import com.android.internal.statusbar.NotificationVisibility;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Allows an app to control the status bar.
 */
@SystemService(Context.STATUS_BAR_SERVICE)
public class StatusBarManager {
    // LINT.IfChange
    /** @hide */
    public static final int DISABLE_EXPAND = View.STATUS_BAR_DISABLE_EXPAND;
    /** @hide */
    public static final int DISABLE_NOTIFICATION_ICONS = View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
    /** @hide */
    public static final int DISABLE_NOTIFICATION_ALERTS
            = View.STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;

    /** @hide */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int DISABLE_NOTIFICATION_TICKER
            = View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
    /** @hide */
    public static final int DISABLE_SYSTEM_INFO = View.STATUS_BAR_DISABLE_SYSTEM_INFO;
    /** @hide */
    public static final int DISABLE_HOME = View.STATUS_BAR_DISABLE_HOME;
    /** @hide */
    public static final int DISABLE_RECENT = View.STATUS_BAR_DISABLE_RECENT;
    /** @hide */
    public static final int DISABLE_BACK = View.STATUS_BAR_DISABLE_BACK;
    /** @hide */
    public static final int DISABLE_CLOCK = View.STATUS_BAR_DISABLE_CLOCK;
    /** @hide */
    public static final int DISABLE_SEARCH = View.STATUS_BAR_DISABLE_SEARCH;

    /** @hide */
    public static final int DISABLE_ONGOING_CALL_CHIP = View.STATUS_BAR_DISABLE_ONGOING_CALL_CHIP;

    /** @hide */
    @Deprecated
    public static final int DISABLE_NAVIGATION =
            View.STATUS_BAR_DISABLE_HOME | View.STATUS_BAR_DISABLE_RECENT;

    /** @hide */
    public static final int DISABLE_NONE = 0x00000000;

    /** @hide */
    public static final int DISABLE_MASK = DISABLE_EXPAND | DISABLE_NOTIFICATION_ICONS
            | DISABLE_NOTIFICATION_ALERTS | DISABLE_NOTIFICATION_TICKER
            | DISABLE_SYSTEM_INFO | DISABLE_RECENT | DISABLE_HOME | DISABLE_BACK | DISABLE_CLOCK
            | DISABLE_SEARCH | DISABLE_ONGOING_CALL_CHIP;

    /** @hide */
    @IntDef(flag = true, prefix = {"DISABLE_"}, value = {
            DISABLE_NONE,
            DISABLE_EXPAND,
            DISABLE_NOTIFICATION_ICONS,
            DISABLE_NOTIFICATION_ALERTS,
            DISABLE_NOTIFICATION_TICKER,
            DISABLE_SYSTEM_INFO,
            DISABLE_HOME,
            DISABLE_RECENT,
            DISABLE_BACK,
            DISABLE_CLOCK,
            DISABLE_SEARCH,
            DISABLE_ONGOING_CALL_CHIP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisableFlags {}

    /**
     * Flag to disable quick settings.
     *
     * Setting this flag disables quick settings completely, but does not disable expanding the
     * notification shade.
     */
    /** @hide */
    public static final int DISABLE2_QUICK_SETTINGS = 1;
    /** @hide */
    public static final int DISABLE2_SYSTEM_ICONS = 1 << 1;
    /** @hide */
    public static final int DISABLE2_NOTIFICATION_SHADE = 1 << 2;
    /** @hide */
    public static final int DISABLE2_GLOBAL_ACTIONS = 1 << 3;
    /** @hide */
    public static final int DISABLE2_ROTATE_SUGGESTIONS = 1 << 4;

    /** @hide */
    public static final int DISABLE2_NONE = 0x00000000;

    /** @hide */
    public static final int DISABLE2_MASK = DISABLE2_QUICK_SETTINGS | DISABLE2_SYSTEM_ICONS
            | DISABLE2_NOTIFICATION_SHADE | DISABLE2_GLOBAL_ACTIONS | DISABLE2_ROTATE_SUGGESTIONS;

    /** @hide */
    @IntDef(flag = true, prefix = { "DISABLE2_" }, value = {
            DISABLE2_NONE,
            DISABLE2_MASK,
            DISABLE2_QUICK_SETTINGS,
            DISABLE2_SYSTEM_ICONS,
            DISABLE2_NOTIFICATION_SHADE,
            DISABLE2_GLOBAL_ACTIONS,
            DISABLE2_ROTATE_SUGGESTIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Disable2Flags {}
    // LINT.ThenChange(frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/disableflags/DisableFlagsLogger.kt)

    private static final String TAG = "StatusBarManager";

    /**
     * Default disable flags for setup
     *
     * @hide
     */
    public static final int DEFAULT_SETUP_DISABLE_FLAGS = DISABLE_NOTIFICATION_ALERTS
            | DISABLE_HOME | DISABLE_EXPAND | DISABLE_RECENT | DISABLE_CLOCK | DISABLE_SEARCH;

    /**
     * Default disable2 flags for setup
     *
     * @hide
     */
    public static final int DEFAULT_SETUP_DISABLE2_FLAGS = DISABLE2_NONE;

    /**
     * disable flags to be applied when the device is sim-locked.
     */
    private static final int DEFAULT_SIM_LOCKED_DISABLED_FLAGS = DISABLE_EXPAND;

    /** @hide */
    public static final int NAVIGATION_HINT_BACK_ALT      = 1 << 0;
    /** @hide */
    public static final int NAVIGATION_HINT_IME_SHOWN     = 1 << 1;
    /** @hide */
    public static final int NAVIGATION_HINT_IME_SWITCHER_SHOWN = 1 << 2;

    /** @hide */
    public static final int WINDOW_STATUS_BAR = 1;
    /** @hide */
    public static final int WINDOW_NAVIGATION_BAR = 2;

    /** @hide */
    @IntDef(flag = true, prefix = { "WINDOW_" }, value = {
        WINDOW_STATUS_BAR,
        WINDOW_NAVIGATION_BAR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WindowType {}

    /** @hide */
    public static final int WINDOW_STATE_SHOWING = 0;
    /** @hide */
    public static final int WINDOW_STATE_HIDING = 1;
    /** @hide */
    public static final int WINDOW_STATE_HIDDEN = 2;

    /** @hide */
    @IntDef(flag = true, prefix = { "WINDOW_STATE_" }, value = {
            WINDOW_STATE_SHOWING,
            WINDOW_STATE_HIDING,
            WINDOW_STATE_HIDDEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WindowVisibleState {}

    /** @hide */
    public static final int CAMERA_LAUNCH_SOURCE_WIGGLE = 0;
    /** @hide */
    public static final int CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = 1;
    /** @hide */
    public static final int CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER = 2;
    /** @hide */
    public static final int CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE = 3;

    /**
     * Broadcast action: sent to apps that hold the status bar permission when
     * KeyguardManager#setPrivateNotificationsAllowed() is changed.
     *
     * Extras: #EXTRA_KM_PRIVATE_NOTIFS_ALLOWED
     * @hide
     */
    public static final String ACTION_KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED
            = "android.app.action.KEYGUARD_PRIVATE_NOTIFICATIONS_CHANGED";

    /**
     * Boolean, the latest value of KeyguardManager#getPrivateNotificationsAllowed()
     * @hide
     */
    public static final String EXTRA_KM_PRIVATE_NOTIFS_ALLOWED
            = "android.app.extra.KM_PRIVATE_NOTIFS_ALLOWED";

    /**
     * Session flag for {@link #registerSessionListener} indicating the listener
     * is interested in sessions on the keygaurd.
     * Keyguard Session Boundaries:
     *     START_SESSION: device starts going to sleep OR the keyguard is newly shown
     *     END_SESSION: device starts going to sleep OR keyguard is no longer showing
     * @hide
     */
    public static final int SESSION_KEYGUARD = 1 << 0;

    /**
     * Session flag for {@link #registerSessionListener} indicating the current session
     * is interested in session on the biometric prompt.
     * @hide
     */
    public static final int SESSION_BIOMETRIC_PROMPT = 1 << 1;

    /** @hide */
    public static final Set<Integer> ALL_SESSIONS = Set.of(
            SESSION_KEYGUARD,
            SESSION_BIOMETRIC_PROMPT
    );

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "SESSION_KEYGUARD" }, value = {
            SESSION_KEYGUARD,
            SESSION_BIOMETRIC_PROMPT,
    })
    public @interface SessionFlags {}

    /**
     * Response indicating that the tile was not added.
     */
    public static final int TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED = 0;
    /**
     * Response indicating that the tile was already added and the user was not prompted.
     */
    public static final int TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED = 1;
    /**
     * Response indicating that the tile was added.
     */
    public static final int TILE_ADD_REQUEST_RESULT_TILE_ADDED = 2;
    /** @hide */
    public static final int TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED = 3;

    /**
     * Values greater or equal to this value indicate an error in the request.
     */
    private static final int TILE_ADD_REQUEST_FIRST_ERROR_CODE = 1000;

    /**
     * Indicates that this package does not match that of the
     * {@link android.service.quicksettings.TileService}.
     */
    public static final int TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE =
            TILE_ADD_REQUEST_FIRST_ERROR_CODE;
    /**
     * Indicates that there's a request in progress for this package.
     */
    public static final int TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS =
            TILE_ADD_REQUEST_FIRST_ERROR_CODE + 1;
    /**
     * Indicates that the component does not match an enabled exported
     * {@link android.service.quicksettings.TileService} for the current user.
     */
    public static final int TILE_ADD_REQUEST_ERROR_BAD_COMPONENT =
            TILE_ADD_REQUEST_FIRST_ERROR_CODE + 2;
    /**
     * Indicates that the user is not the current user.
     */
    public static final int TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER =
            TILE_ADD_REQUEST_FIRST_ERROR_CODE + 3;
    /**
     * Indicates that the requesting application is not in the foreground.
     */
    public static final int TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND =
            TILE_ADD_REQUEST_FIRST_ERROR_CODE + 4;
    /**
     * The request could not be processed because no fulfilling service was found. This could be
     * a temporary issue (for example, SystemUI has crashed).
     */
    public static final int TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE =
            TILE_ADD_REQUEST_FIRST_ERROR_CODE + 5;

    /** @hide */
    @IntDef(prefix = {"TILE_ADD_REQUEST"}, value = {
            TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
            TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
            TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
            TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
            TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
            TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
            TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
            TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestResult {}

    /**
     * Constant for {@link #setNavBarMode(int)} indicating the default navbar mode.
     *
     * @hide
     */
    @SystemApi
    public static final int NAV_BAR_MODE_DEFAULT = 0;

    /**
     * Constant for {@link #setNavBarMode(int)} indicating kids navbar mode.
     *
     * <p>When used, back and home icons will change drawables and layout, recents will be hidden,
     * and enables the setting to force navbar visible, even when apps are in immersive mode.
     *
     * @hide
     */
    @SystemApi
    public static final int NAV_BAR_MODE_KIDS = 1;

    /** @hide */
    @IntDef(prefix = {"NAV_BAR_MODE_"}, value = {
            NAV_BAR_MODE_DEFAULT,
            NAV_BAR_MODE_KIDS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NavBarMode {}

    /**
     * State indicating that this sender device is close to a receiver device, so the user can
     * potentially *start* a cast to the receiver device if the user moves their device a bit
     * closer.
     * <p>
     * Important notes:
     * <ul>
     *     <li>This state represents that the device is close enough to inform the user that
     *     transferring is an option, but the device is *not* close enough to actually initiate a
     *     transfer yet.</li>
     *     <li>This state is for *starting* a cast. It should be used when this device is currently
     *     playing media locally and the media should be transferred to be played on the receiver
     *     device instead.</li>
     * </ul>
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST = 0;

    /**
     * State indicating that this sender device is close to a receiver device, so the user can
     * potentially *end* a cast on the receiver device if the user moves this device a bit closer.
     * <p>
     * Important notes:
     * <ul>
     *     <li>This state represents that the device is close enough to inform the user that
     *     transferring is an option, but the device is *not* close enough to actually initiate a
     *     transfer yet.</li>
     *     <li>This state is for *ending* a cast. It should be used when media is currently being
     *     played on the receiver device and the media should be transferred to play locally
     *     instead.</li>
     * </ul>
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST = 1;

    /**
     * State indicating that a media transfer from this sender device to a receiver device has been
     * started.
     * <p>
     * Important note: This state is for *starting* a cast. It should be used when this device is
     * currently playing media locally and the media has started being transferred to the receiver
     * device instead.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED = 2;

    /**
     * State indicating that a media transfer from the receiver and back to this sender device
     * has been started.
     * <p>
     * Important note: This state is for *ending* a cast. It should be used when media is currently
     * being played on the receiver device and the media has started being transferred to play
     * locally instead.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED = 3;

    /**
     * State indicating that a media transfer from this sender device to a receiver device has
     * finished successfully.
     * <p>
     * Important note: This state is for *starting* a cast. It should be used when this device had
     * previously been playing media locally and the media has successfully been transferred to the
     * receiver device instead.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED = 4;

    /**
     * State indicating that a media transfer from the receiver and back to this sender device has
     * finished successfully.
     * <p>
     * Important note: This state is for *ending* a cast. It should be used when media was
     * previously being played on the receiver device and has been successfully transferred to play
     * locally on this device instead.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED = 5;

    /**
     * State indicating that the attempted transfer to the receiver device has failed.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED = 6;

    /**
     * State indicating that the attempted transfer back to this device has failed.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED = 7;

    /**
     * State indicating that this sender device is no longer close to the receiver device.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER = 8;

    /** @hide */
    @IntDef(prefix = {"MEDIA_TRANSFER_SENDER_STATE_"}, value = {
            MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
            MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaTransferSenderState {}

    /**
     * State indicating that this receiver device is close to a sender device, so the user can
     * potentially start or end a cast to the receiver device if the user moves the sender device a
     * bit closer.
     * <p>
     * Important note: This state represents that the device is close enough to inform the user that
     * transferring is an option, but the device is *not* close enough to actually initiate a
     * transfer yet.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER = 0;

    /**
     * State indicating that this receiver device is no longer close to the sender device.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER = 1;

    /**
     * State indicating that media transfer to this receiver device is succeeded.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED = 2;

    /**
     * State indicating that media transfer to this receiver device is failed.
     *
     * @hide
     */
    @SystemApi
    public static final int MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_FAILED = 3;

    /** @hide */
    @IntDef(prefix = {"MEDIA_TRANSFER_RECEIVER_STATE_"}, value = {
            MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
            MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_FAILED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaTransferReceiverState {}

    /**
     * A map from a provider registered in
     * {@link #registerNearbyMediaDevicesProvider(NearbyMediaDevicesProvider)} to the wrapper
     * around the provider that was created internally. We need the wrapper to make the provider
     * binder-compatible, and we need to store a reference to the wrapper so that when the provider
     * is un-registered, we un-register the saved wrapper instance.
     */
    private final Map<NearbyMediaDevicesProvider, NearbyMediaDevicesProviderWrapper>
            nearbyMediaDevicesProviderMap = new HashMap<>();

    /**
     * Media controls based on {@link android.app.Notification.MediaStyle} notifications will have
     * actions based on the media session's {@link android.media.session.PlaybackState}, rather than
     * the notification's actions.
     *
     * These actions will be:
     * - Play/Pause (depending on whether the current state is a playing state)
     * - Previous (if declared), or a custom action if the slot is not reserved with
     *   {@code SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV}
     * - Next (if declared), or a custom action if the slot is not reserved with
     *   {@code SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT}
     * - Custom action
     * - Custom action
     *
     * @see androidx.media.utils.MediaConstants#SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV
     * @see androidx.media.utils.MediaConstants#SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    private static final long MEDIA_CONTROL_SESSION_ACTIONS = 203800354L;

    /**
     * Media controls based on {@link android.app.Notification.MediaStyle} notifications should
     * include a non-empty title, either in the {@link android.media.MediaMetadata} or
     * notification title.
     */
    @ChangeId
    @LoggingOnly
    private static final long MEDIA_CONTROL_BLANK_TITLE = 274775190L;

    @UnsupportedAppUsage
    private Context mContext;
    private IStatusBarService mService;
    @UnsupportedAppUsage
    private IBinder mToken = new Binder();

    private final IPlatformCompat mPlatformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

    @UnsupportedAppUsage
    StatusBarManager(Context context) {
        mContext = context;
    }

    @UnsupportedAppUsage
    private synchronized IStatusBarService getService() {
        if (mService == null) {
            mService = IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));
            if (mService == null) {
                Slog.w(TAG, "warning: no STATUS_BAR_SERVICE");
            }
        }
        return mService;
    }

    /**
     * Disable some features in the status bar.  Pass the bitwise-or of the DISABLE_* flags.
     * To re-enable everything, pass {@link #DISABLE_NONE}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void disable(int what) {
        try {
            final int userId = Binder.getCallingUserHandle().getIdentifier();
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.disableForUser(what, mToken, mContext.getPackageName(), userId);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Disable additional status bar features. Pass the bitwise-or of the DISABLE2_* flags.
     * To re-enable everything, pass {@link #DISABLE_NONE}.
     *
     * Warning: Only pass DISABLE2_* flags into this function, do not use DISABLE_* flags.
     *
     * @hide
     */
    public void disable2(@Disable2Flags int what) {
        try {
            final int userId = Binder.getCallingUserHandle().getIdentifier();
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.disable2ForUser(what, mToken, mContext.getPackageName(), userId);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Simulate notification click for testing
     *
     * @hide
     */
    @TestApi
    public void clickNotification(@Nullable String key, int rank, int count, boolean visible) {
        clickNotificationInternal(key, rank, count, visible);
    }

    private void clickNotificationInternal(String key, int rank, int count, boolean visible) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.onNotificationClick(key,
                        NotificationVisibility.obtain(key, rank, count, visible));
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Simulate notification feedback for testing
     *
     * @hide
     */
    @TestApi
    public void sendNotificationFeedback(@Nullable String key, @Nullable Bundle feedback) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.onNotificationFeedbackReceived(key, feedback);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Expand the notifications panel.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public void expandNotificationsPanel() {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.expandNotificationsPanel();
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Collapse the notifications and settings panels.
     *
     * Starting in Android {@link Build.VERSION_CODES.S}, apps targeting SDK level {@link
     * Build.VERSION_CODES.S} or higher will need {@link android.Manifest.permission.STATUS_BAR}
     * permission to call this API.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "This operation"
            + " is not allowed anymore, please see {@link android.content"
            + ".Intent#ACTION_CLOSE_SYSTEM_DIALOGS} for more details.")
    @TestApi
    public void collapsePanels() {

        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.collapsePanels();
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Toggles the notification panel.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    @TestApi
    public void togglePanel() {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.togglePanel();
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sends system keys to the status bar.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    @TestApi
    public void handleSystemKey(@NonNull KeyEvent key) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.handleSystemKey(key);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the last handled system key. A system key is a KeyEvent that the
     * {@link com.android.server.policy.PhoneWindowManager} sends directly to the
     * status bar, rather than forwarding to apps. If a key has never been sent to the
     * status bar, will return -1.
     *
     * @return the keycode of the last KeyEvent that has been sent to the system.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    @TestApi
    public int getLastSystemKey() {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                return svc.getLastSystemKey();
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return -1;
    }

    /**
     * Expand the settings panel.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void expandSettingsPanel() {
        expandSettingsPanel(null);
    }

    /**
     * Expand the settings panel and open a subPanel. If the subpanel is null or does not have a
     * corresponding tile, the QS panel is simply expanded
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void expandSettingsPanel(@Nullable String subPanel) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.expandSettingsPanel(subPanel);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIcon(String slot, int iconId, int iconLevel, String contentDescription) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.setIcon(slot, mContext.getPackageName(), iconId, iconLevel,
                    contentDescription);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void removeIcon(String slot) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.removeIcon(slot);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIconVisibility(String slot, boolean visible) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.setIconVisibility(slot, visible);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Enable or disable status bar elements (notifications, clock) which are inappropriate during
     * device setup.
     *
     * @param disabled whether to apply or remove the disabled flags
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    public void setDisabledForSetup(boolean disabled) {
        try {
            final int userId = Binder.getCallingUserHandle().getIdentifier();
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.disableForUser(disabled ? DEFAULT_SETUP_DISABLE_FLAGS : DISABLE_NONE,
                        mToken, mContext.getPackageName(), userId);
                svc.disable2ForUser(disabled ? DEFAULT_SETUP_DISABLE2_FLAGS : DISABLE2_NONE,
                        mToken, mContext.getPackageName(), userId);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Enable or disable expansion of the status bar. When the device is SIM-locked, the status
     * bar should not be expandable.
     *
     * @param disabled If {@code true}, the status bar will be set to non-expandable. If
     *                 {@code false}, re-enables expansion of the status bar.
     * @hide
     */
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    public void setExpansionDisabledForSimNetworkLock(boolean disabled) {
        try {
            final int userId = Binder.getCallingUserHandle().getIdentifier();
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.disableForUser(disabled ? DEFAULT_SIM_LOCKED_DISABLED_FLAGS : DISABLE_NONE,
                        mToken, mContext.getPackageName(), userId);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get this app's currently requested disabled components
     *
     * @return a new DisableInfo
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    @NonNull
    public DisableInfo getDisableInfo() {
        try {
            final int userId = Binder.getCallingUserHandle().getIdentifier();
            final IStatusBarService svc = getService();
            int[] flags = new int[] {0, 0};
            if (svc != null) {
                flags = svc.getDisableFlags(mToken, userId);
            }

            return new DisableInfo(flags[0], flags[1]);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets an active {@link android.service.quicksettings.TileService} to listening state
     *
     * The {@code componentName}'s package must match the calling package.
     *
     * @param componentName the tile to set into listening state
     * @see android.service.quicksettings.TileService#requestListeningState
     * @hide
     */
    public void requestTileServiceListeningState(@NonNull ComponentName componentName) {
        Objects.requireNonNull(componentName);
        try {
            getService().requestTileServiceListeningState(componentName, mContext.getUserId());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to the user to add a {@link android.service.quicksettings.TileService}
     * to the set of current QS tiles.
     * <p>
     * Calling this will prompt the user to decide whether they want to add the shown
     * {@link android.service.quicksettings.TileService} to their current tiles. The user can
     * deny the request and the system can stop processing requests for a given
     * {@link ComponentName} after a number of requests.
     * <p>
     * The request will show to the user information about the tile:
     * <ul>
     *     <li>Application name</li>
     *     <li>Label for the tile</li>
     *     <li>Icon for the tile</li>
     * </ul>
     * <p>
     * The user for which this will be added is determined from the {@link Context} used to retrieve
     * this service, and must match the current user. The requesting application must be in the
     * foreground ({@link ActivityManager.RunningAppProcessInfo#IMPORTANCE_FOREGROUND}
     * and the {@link android.service.quicksettings.TileService} must be exported.
     *
     * Note: the system can choose to auto-deny a request if the user has denied that specific
     * request (user, ComponentName) enough times before.
     *
     * @param tileServiceComponentName {@link ComponentName} of the
     *        {@link android.service.quicksettings.TileService} for the request.
     * @param tileLabel label of the tile to show to the user.
     * @param icon icon to use in the tile shown to the user.
     * @param resultExecutor an executor to run the callback on
     * @param resultCallback callback to indicate the result of the request.
     *
     * @see android.service.quicksettings.TileService
     */
    public void requestAddTileService(
            @NonNull ComponentName tileServiceComponentName,
            @NonNull CharSequence tileLabel,
            @NonNull Icon icon,
            @NonNull Executor resultExecutor,
            @NonNull Consumer<Integer> resultCallback
    ) {
        Objects.requireNonNull(tileServiceComponentName);
        Objects.requireNonNull(tileLabel);
        Objects.requireNonNull(icon);
        Objects.requireNonNull(resultExecutor);
        Objects.requireNonNull(resultCallback);
        if (!tileServiceComponentName.getPackageName().equals(mContext.getPackageName())) {
            resultExecutor.execute(
                    () -> resultCallback.accept(TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE));
            return;
        }
        int userId = mContext.getUserId();
        RequestResultCallback callbackProxy = new RequestResultCallback(resultExecutor,
                resultCallback);
        IStatusBarService svc = getService();
        try {
            svc.requestAddTile(
                    tileServiceComponentName,
                    tileLabel,
                    icon,
                    userId,
                    callbackProxy
            );
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @param packageName
     */
    @TestApi
    public void cancelRequestAddTile(@NonNull String packageName) {
        Objects.requireNonNull(packageName);
        IStatusBarService svc = getService();
        try {
            svc.cancelRequestAddTile(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets or removes the navigation bar mode.
     *
     * @param navBarMode the mode of the navigation bar to be set.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    public void setNavBarMode(@NavBarMode int navBarMode) {
        if (navBarMode != NAV_BAR_MODE_DEFAULT && navBarMode != NAV_BAR_MODE_KIDS) {
            throw new IllegalArgumentException("Supplied navBarMode not supported: " + navBarMode);
        }

        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.setNavBarMode(navBarMode);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the navigation bar mode. Returns default value if no mode is set.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR)
    public @NavBarMode int getNavBarMode() {
        int navBarMode = NAV_BAR_MODE_DEFAULT;
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                navBarMode = svc.getNavBarMode();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return navBarMode;
    }

    /**
     * Notifies the system of a new media tap-to-transfer state for the <b>sender</b> device.
     *
     * <p>The callback should only be provided for the {@link
     * MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED} or {@link
     * MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED} states, since those are the
     * only states where an action can be un-done.
     *
     * @param displayState the new state for media tap-to-transfer.
     * @param routeInfo the media route information for the media being transferred.
     * @param undoExecutor an executor to run the callback on and must be provided if the
     *                     callback is non-null.
     * @param undoCallback a callback that will be triggered if the user elects to undo a media
     *                     transfer.
     *
     * @throws IllegalArgumentException if an undo callback is provided for states that are not a
     *   succeeded state.
     * @throws IllegalArgumentException if an executor is not provided when a callback is.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void updateMediaTapToTransferSenderDisplay(
            @MediaTransferSenderState int displayState,
            @NonNull MediaRoute2Info routeInfo,
            @Nullable Executor undoExecutor,
            @Nullable Runnable undoCallback
    ) {
        Objects.requireNonNull(routeInfo);
        if (displayState != MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED
                && displayState != MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED
                && undoCallback != null) {
            throw new IllegalArgumentException(
                    "The undoCallback should only be provided when the state is a "
                            + "transfer succeeded state");
        }
        if (undoCallback != null && undoExecutor == null) {
            throw new IllegalArgumentException(
                    "You must pass an executor when you pass an undo callback");
        }
        IStatusBarService svc = getService();
        try {
            UndoCallback callbackProxy = null;
            if (undoExecutor != null) {
                callbackProxy = new UndoCallback(undoExecutor, undoCallback);
            }
            svc.updateMediaTapToTransferSenderDisplay(displayState, routeInfo, callbackProxy);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the system of a new media tap-to-transfer state for the <b>receiver</b> device.
     *
     * @param displayState the new state for media tap-to-transfer.
     * @param routeInfo the media route information for the media being transferred.
     * @param appIcon the icon of the app playing the media.
     * @param appName the name of the app playing the media.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void updateMediaTapToTransferReceiverDisplay(
            @MediaTransferReceiverState int displayState,
            @NonNull MediaRoute2Info routeInfo,
            @Nullable Icon appIcon,
            @Nullable CharSequence appName) {
        Objects.requireNonNull(routeInfo);
        IStatusBarService svc = getService();
        try {
            svc.updateMediaTapToTransferReceiverDisplay(displayState, routeInfo, appIcon, appName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a provider that notifies callbacks about the status of nearby devices that are able
     * to play media.
     * <p>
     * If multiple providers are registered, all the providers will be used for nearby device
     * information.
     * <p>
     * @param provider the nearby device information provider to register
     * <p>
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void registerNearbyMediaDevicesProvider(
            @NonNull NearbyMediaDevicesProvider provider
    ) {
        Objects.requireNonNull(provider);
        if (nearbyMediaDevicesProviderMap.containsKey(provider)) {
            return;
        }
        try {
            final IStatusBarService svc = getService();
            NearbyMediaDevicesProviderWrapper providerWrapper =
                    new NearbyMediaDevicesProviderWrapper(provider);
            nearbyMediaDevicesProviderMap.put(provider, providerWrapper);
            svc.registerNearbyMediaDevicesProvider(providerWrapper);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

   /**
     * Unregisters a provider that gives information about nearby devices that are able to play
     * media.
     * <p>
     * See {@link registerNearbyMediaDevicesProvider}.
     * <p>
     * @param provider the nearby device information provider to unregister
     * <p>
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void unregisterNearbyMediaDevicesProvider(
            @NonNull NearbyMediaDevicesProvider provider
    ) {
        Objects.requireNonNull(provider);
        if (!nearbyMediaDevicesProviderMap.containsKey(provider)) {
            return;
        }
        try {
            final IStatusBarService svc = getService();
            NearbyMediaDevicesProviderWrapper providerWrapper =
                    nearbyMediaDevicesProviderMap.get(provider);
            nearbyMediaDevicesProviderMap.remove(provider);
            svc.unregisterNearbyMediaDevicesProvider(providerWrapper);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the given package should use session-based actions for its media controls.
     *
     * @param packageName App posting media controls
     * @param user Current user handle
     * @return true if the app supports session actions
     *
     * @hide
     */
    @RequiresPermission(allOf = {android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
            android.Manifest.permission.LOG_COMPAT_CHANGE})
    public static boolean useMediaSessionActionsForApp(String packageName, UserHandle user) {
        return CompatChanges.isChangeEnabled(MEDIA_CONTROL_SESSION_ACTIONS, packageName, user);
    }

    /**
     * Log that the given package has posted media controls with a blank title
     *
     * @param packageName App posting media controls
     * @param userId Current user ID
     * @throws RuntimeException if there is an error reporting the change
     *
     * @hide
     */
    public void logBlankMediaTitle(String packageName, @UserIdInt int userId)
            throws RuntimeException {
        try {
            mPlatformCompat.reportChangeByPackageName(MEDIA_CONTROL_BLANK_TITLE, packageName,
                        userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the supplied activity can {@link Activity#startActivityForResult(Intent, int)}
     * a system activity that captures content on the screen to take a screenshot.
     *
     * <p>Note: The result should not be cached.
     *
     * <p>The system activity displays an editing tool that allows user to edit the screenshot, save
     * it on device, and return the edited screenshot as {@link android.net.Uri} to the calling
     * activity. User interaction is required to return the edited screenshot to the calling
     * activity.
     *
     * <p>When {@code true}, callers can use {@link Activity#startActivityForResult(Intent, int)}
     * to start start the content capture activity using
     * {@link Intent#ACTION_LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE}.
     *
     * @param activity Calling activity
     * @return true if the activity supports launching the capture content activity for note.
     *
     * @see Intent#ACTION_LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE
     * @see Manifest.permission#LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE
     * @see android.app.role.RoleManager#ROLE_NOTES
     */
    @RequiresPermission(Manifest.permission.LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE)
    public boolean canLaunchCaptureContentActivityForNote(@NonNull Activity activity) {
        Objects.requireNonNull(activity);
        IBinder activityToken = activity.getActivityToken();
        int taskId = ActivityClient.getInstance().getTaskForActivity(activityToken, false);
        return new AppClipsServiceConnector(mContext)
                .canLaunchCaptureContentActivityForNote(taskId);
    }

    /** @hide */
    public static String windowStateToString(int state) {
        if (state == WINDOW_STATE_HIDING) return "WINDOW_STATE_HIDING";
        if (state == WINDOW_STATE_HIDDEN) return "WINDOW_STATE_HIDDEN";
        if (state == WINDOW_STATE_SHOWING) return "WINDOW_STATE_SHOWING";
        return "WINDOW_STATE_UNKNOWN";
    }

    /**
     * DisableInfo describes this app's requested state of the StatusBar with regards to which
     * components are enabled/disabled
     *
     * @hide
     */
    @SystemApi
    public static final class DisableInfo {

        private boolean mStatusBarExpansion;
        private boolean mNavigateHome;
        private boolean mNotificationPeeking;
        private boolean mRecents;
        private boolean mSearch;
        private boolean mSystemIcons;
        private boolean mClock;
        private boolean mNotificationIcons;
        private boolean mRotationSuggestion;

        /** @hide */
        public DisableInfo(int flags1, int flags2) {
            mStatusBarExpansion = (flags1 & DISABLE_EXPAND) != 0;
            mNavigateHome = (flags1 & DISABLE_HOME) != 0;
            mNotificationPeeking = (flags1 & DISABLE_NOTIFICATION_ALERTS) != 0;
            mRecents = (flags1 & DISABLE_RECENT) != 0;
            mSearch = (flags1 & DISABLE_SEARCH) != 0;
            mSystemIcons = (flags1 & DISABLE_SYSTEM_INFO) != 0;
            mClock = (flags1 & DISABLE_CLOCK) != 0;
            mNotificationIcons = (flags1 & DISABLE_NOTIFICATION_ICONS) != 0;
            mRotationSuggestion = (flags2 & DISABLE2_ROTATE_SUGGESTIONS) != 0;
        }

        /** @hide */
        public DisableInfo() {}

        /**
         * @return {@code true} if expanding the notification shade is disabled
         *
         * @hide
         */
        @SystemApi
        public boolean isStatusBarExpansionDisabled() {
            return mStatusBarExpansion;
        }

        /** * @hide */
        public void setStatusBarExpansionDisabled(boolean disabled) {
            mStatusBarExpansion = disabled;
        }

        /**
         * @return {@code true} if navigation home is disabled
         *
         * @hide
         */
        @SystemApi
        public boolean isNavigateToHomeDisabled() {
            return mNavigateHome;
        }

        /** * @hide */
        public void setNagivationHomeDisabled(boolean disabled) {
            mNavigateHome = disabled;
        }

        /**
         * @return {@code true} if notification peeking (heads-up notification) is disabled
         *
         * @hide
         */
        @SystemApi
        public boolean isNotificationPeekingDisabled() {
            return mNotificationPeeking;
        }

        /** @hide */
        public void setNotificationPeekingDisabled(boolean disabled) {
            mNotificationPeeking = disabled;
        }

        /**
         * @return {@code true} if mRecents/overview is disabled
         *
         * @hide
         */
        @SystemApi
        public boolean isRecentsDisabled() {
            return mRecents;
        }

        /**  @hide */
        public void setRecentsDisabled(boolean disabled) {
            mRecents = disabled;
        }

        /**
         * @return {@code true} if mSearch is disabled
         *
         * @hide
         */
        @SystemApi
        public boolean isSearchDisabled() {
            return mSearch;
        }

        /** @hide */
        public void setSearchDisabled(boolean disabled) {
            mSearch = disabled;
        }

        /**
         * @return {@code true} if system icons are disabled
         *
         * @hide
         */
        public boolean areSystemIconsDisabled() {
            return mSystemIcons;
        }

        /** * @hide */
        public void setSystemIconsDisabled(boolean disabled) {
            mSystemIcons = disabled;
        }

        /**
         * @return {@code true} if the clock icon is disabled
         *
         * @hide
         */
        public boolean isClockDisabled() {
            return mClock;
        }

        /** * @hide */
        public void setClockDisabled(boolean disabled) {
            mClock = disabled;
        }

        /**
         * @return {@code true} if notification icons are disabled
         *
         * @hide
         */
        public boolean areNotificationIconsDisabled() {
            return mNotificationIcons;
        }

        /** * @hide */
        public void setNotificationIconsDisabled(boolean disabled) {
            mNotificationIcons = disabled;
        }

        /**
         * Returns whether the rotation suggestion is disabled.
         *
         * @hide
         */
        @TestApi
        public boolean isRotationSuggestionDisabled() {
            return mRotationSuggestion;
        }

        /**
         * @return {@code true} if no components are disabled (default state)
         * @hide
         */
        @SystemApi
        public boolean areAllComponentsEnabled() {
            return !mStatusBarExpansion && !mNavigateHome && !mNotificationPeeking && !mRecents
                    && !mSearch && !mSystemIcons && !mClock && !mNotificationIcons
                    && !mRotationSuggestion;
        }

        /** @hide */
        public void setEnableAll() {
            mStatusBarExpansion = false;
            mNavigateHome = false;
            mNotificationPeeking = false;
            mRecents = false;
            mSearch = false;
            mSystemIcons = false;
            mClock = false;
            mNotificationIcons = false;
            mRotationSuggestion = false;
        }

        /**
         * @return {@code true} if all status bar components are disabled
         *
         * @hide
         */
        public boolean areAllComponentsDisabled() {
            return mStatusBarExpansion && mNavigateHome && mNotificationPeeking
                    && mRecents && mSearch && mSystemIcons && mClock && mNotificationIcons
                    && mRotationSuggestion;
        }

        /** @hide */
        public void setDisableAll() {
            mStatusBarExpansion = true;
            mNavigateHome = true;
            mNotificationPeeking = true;
            mRecents = true;
            mSearch = true;
            mSystemIcons = true;
            mClock = true;
            mNotificationIcons = true;
            mRotationSuggestion = true;
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DisableInfo: ");
            sb.append(" mStatusBarExpansion=").append(mStatusBarExpansion ? "disabled" : "enabled");
            sb.append(" mNavigateHome=").append(mNavigateHome ? "disabled" : "enabled");
            sb.append(" mNotificationPeeking=")
                    .append(mNotificationPeeking ? "disabled" : "enabled");
            sb.append(" mRecents=").append(mRecents ? "disabled" : "enabled");
            sb.append(" mSearch=").append(mSearch ? "disabled" : "enabled");
            sb.append(" mSystemIcons=").append(mSystemIcons ? "disabled" : "enabled");
            sb.append(" mClock=").append(mClock ? "disabled" : "enabled");
            sb.append(" mNotificationIcons=").append(mNotificationIcons ? "disabled" : "enabled");
            sb.append(" mRotationSuggestion=").append(mRotationSuggestion ? "disabled" : "enabled");

            return sb.toString();

        }

        /**
         * Convert a DisableInfo to equivalent flags
         * @return a pair of equivalent disable flags
         *
         * @hide
         */
        public Pair<Integer, Integer> toFlags() {
            int disable1 = DISABLE_NONE;
            int disable2 = DISABLE2_NONE;

            if (mStatusBarExpansion) disable1 |= DISABLE_EXPAND;
            if (mNavigateHome) disable1 |= DISABLE_HOME;
            if (mNotificationPeeking) disable1 |= DISABLE_NOTIFICATION_ALERTS;
            if (mRecents) disable1 |= DISABLE_RECENT;
            if (mSearch) disable1 |= DISABLE_SEARCH;
            if (mSystemIcons) disable1 |= DISABLE_SYSTEM_INFO;
            if (mClock) disable1 |= DISABLE_CLOCK;
            if (mNotificationIcons) disable1 |= DISABLE_NOTIFICATION_ICONS;
            if (mRotationSuggestion) disable2 |= DISABLE2_ROTATE_SUGGESTIONS;

            return new Pair<Integer, Integer>(disable1, disable2);
        }
    }

    /**
     * @hide
     */
    static final class RequestResultCallback extends IAddTileResultCallback.Stub {

        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final Consumer<Integer> mCallback;

        RequestResultCallback(@NonNull Executor executor, @NonNull Consumer<Integer> callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onTileRequest(int userResponse) {
            mExecutor.execute(() -> mCallback.accept(userResponse));
        }
    }

    /**
     * @hide
     */
    static final class UndoCallback extends IUndoMediaTransferCallback.Stub {
        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final Runnable mCallback;

        UndoCallback(@NonNull Executor executor, @NonNull Runnable callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onUndoTriggered() {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(mCallback);
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }
    }

    /**
     * @hide
     */
    static final class NearbyMediaDevicesProviderWrapper extends INearbyMediaDevicesProvider.Stub {
        @NonNull
        private final NearbyMediaDevicesProvider mProvider;
        // Because we're wrapping a {@link NearbyMediaDevicesProvider} in a binder-compatible
        // interface, we also need to wrap the callbacks that the provider receives. We use
        // this map to keep track of the original callback and the wrapper callback so that
        // unregistering the callback works correctly.
        @NonNull
        private final Map<INearbyMediaDevicesUpdateCallback, Consumer<List<NearbyDevice>>>
                mRegisteredCallbacks = new HashMap<>();

        NearbyMediaDevicesProviderWrapper(@NonNull NearbyMediaDevicesProvider provider) {
            mProvider = provider;
        }

        @Override
        public void registerNearbyDevicesCallback(
                @NonNull INearbyMediaDevicesUpdateCallback callback) {
            Consumer<List<NearbyDevice>> callbackAsConsumer = nearbyDevices -> {
                try {
                    callback.onDevicesUpdated(nearbyDevices);
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            };

            mRegisteredCallbacks.put(callback, callbackAsConsumer);
            mProvider.registerNearbyDevicesCallback(callbackAsConsumer);
        }

        @Override
        public void unregisterNearbyDevicesCallback(
                @NonNull INearbyMediaDevicesUpdateCallback callback) {
            if (!mRegisteredCallbacks.containsKey(callback)) {
                return;
            }
            mProvider.unregisterNearbyDevicesCallback(mRegisteredCallbacks.get(callback));
            mRegisteredCallbacks.remove(callback);
        }
    }
}
