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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Pair;
import android.util.Slog;
import android.view.View;

import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
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
    // LINT.ThenChange(frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/DisableFlagsLogger.kt)

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
    public static final int DEFAULT_SETUP_DISABLE2_FLAGS = DISABLE2_ROTATE_SUGGESTIONS;

    /**
     * disable flags to be applied when the device is sim-locked.
     */
    private static final int DEFAULT_SIM_LOCKED_DISABLED_FLAGS = DISABLE_EXPAND;

    /** @hide */
    public static final int NAVIGATION_HINT_BACK_ALT      = 1 << 0;
    /** @hide */
    public static final int NAVIGATION_HINT_IME_SHOWN     = 1 << 1;

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

    /** @hide */
    @IntDef(prefix = {"TILE_ADD_REQUEST_RESULT_"}, value = {
            TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
            TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
            TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestResult {}

    /**
     * Indicates that the request was sent successfully. Does not indicate that the user
     * has accepted the request.
     */
    public static final int TILE_ADD_REQUEST_ANSWER_SUCCESS = 0;
    /**
     * Indicates that this package does not match that of the
     * {@link android.service.quicksettings.TileService}.
     */
    public static final int TILE_ADD_REQUEST_ANSWER_FAILED_MISMATCHED_PACKAGE = 1;
    /**
     * Indicates that there's a request in progress for this package.
     */
    public static final int TILE_ADD_REQUEST_ANSWER_FAILED_REQUEST_IN_PROGRESS = 2;
    /**
     * Indicates that the component does not match an enabled
     * {@link android.service.quicksettings.TileService} for the current user.
     */
    public static final int TILE_ADD_REQUEST_ANSWER_FAILED_BAD_COMPONENT = 3;
    /**
     * Indicates that the user is not the current user.
     */
    public static final int TILE_ADD_REQUEST_ANSWER_FAILED_NOT_CURRENT_USER = 4;
    /**
     * The request could not be processed due to an unkonwn reason.
     */
    public static final int TILE_ADD_REQUEST_ANSWER_FAILED_UNKNOWN_REASON = 5;

    /** @hide */
    @IntDef(prefix = {"TILE_ADD_REQUEST_ANSWER_"}, value = {
            TILE_ADD_REQUEST_ANSWER_SUCCESS,
            TILE_ADD_REQUEST_ANSWER_FAILED_MISMATCHED_PACKAGE,
            TILE_ADD_REQUEST_ANSWER_FAILED_REQUEST_IN_PROGRESS,
            TILE_ADD_REQUEST_ANSWER_FAILED_BAD_COMPONENT,
            TILE_ADD_REQUEST_ANSWER_FAILED_NOT_CURRENT_USER,
            TILE_ADD_REQUEST_ANSWER_FAILED_UNKNOWN_REASON
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestAnswer {}

    @UnsupportedAppUsage
    private Context mContext;
    private IStatusBarService mService;
    @UnsupportedAppUsage
    private IBinder mToken = new Binder();

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
                Slog.w("StatusBarManager", "warning: no STATUS_BAR_SERVICE");
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
    public void handleSystemKey(int key) {
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
     * this service, and must match the current user.
     *
     * @param tileServiceComponentName {@link ComponentName} of the
     *        {@link android.service.quicksettings.TileService} for the request.
     * @param tileLabel label of the tile to show to the user.
     * @param icon icon to use in the tile shown to the user.
     * @param resultExecutor an executor to run the callback on
     * @param resultCallback callback to indicate the {@link RequestResult}.
     * @return whether the request was successfully sent.
     *
     * @see android.service.quicksettings.TileService
     */
    @RequestAnswer
    public int requestAddTileService(
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
            return TILE_ADD_REQUEST_ANSWER_FAILED_MISMATCHED_PACKAGE;
        }
        int userId = mContext.getUserId();
        RequestResultCallback callbackProxy = new RequestResultCallback(resultExecutor,
                resultCallback);
        IStatusBarService svc = getService();
        try {
            return svc.requestAddTile(
                    tileServiceComponentName,
                    tileLabel,
                    icon,
                    userId,
                    callbackProxy
            );
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
        return TILE_ADD_REQUEST_ANSWER_FAILED_UNKNOWN_REASON;
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
         * @return {@code true} if no components are disabled (default state)
         *
         * @hide
         */
        @SystemApi
        public boolean areAllComponentsEnabled() {
            return !mStatusBarExpansion && !mNavigateHome && !mNotificationPeeking && !mRecents
                    && !mSearch && !mSystemIcons && !mClock && !mNotificationIcons;
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
        }

        /**
         * @return {@code true} if all status bar components are disabled
         *
         * @hide
         */
        public boolean areAllComponentsDisabled() {
            return mStatusBarExpansion && mNavigateHome && mNotificationPeeking
                    && mRecents && mSearch && mSystemIcons && mClock && mNotificationIcons;
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
}
