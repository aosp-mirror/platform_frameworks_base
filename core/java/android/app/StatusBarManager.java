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
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.View;

import com.android.internal.statusbar.IStatusBarService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Allows an app to control the status bar.
 *
 * @hide
 */
@SystemService(Context.STATUS_BAR_SERVICE)
public class StatusBarManager {

    public static final int DISABLE_EXPAND = View.STATUS_BAR_DISABLE_EXPAND;
    public static final int DISABLE_NOTIFICATION_ICONS = View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
    public static final int DISABLE_NOTIFICATION_ALERTS
            = View.STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;
    @Deprecated
    public static final int DISABLE_NOTIFICATION_TICKER
            = View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
    public static final int DISABLE_SYSTEM_INFO = View.STATUS_BAR_DISABLE_SYSTEM_INFO;
    public static final int DISABLE_HOME = View.STATUS_BAR_DISABLE_HOME;
    public static final int DISABLE_RECENT = View.STATUS_BAR_DISABLE_RECENT;
    public static final int DISABLE_BACK = View.STATUS_BAR_DISABLE_BACK;
    public static final int DISABLE_CLOCK = View.STATUS_BAR_DISABLE_CLOCK;
    public static final int DISABLE_SEARCH = View.STATUS_BAR_DISABLE_SEARCH;

    @Deprecated
    public static final int DISABLE_NAVIGATION = 
            View.STATUS_BAR_DISABLE_HOME | View.STATUS_BAR_DISABLE_RECENT;

    public static final int DISABLE_NONE = 0x00000000;

    public static final int DISABLE_MASK = DISABLE_EXPAND | DISABLE_NOTIFICATION_ICONS
            | DISABLE_NOTIFICATION_ALERTS | DISABLE_NOTIFICATION_TICKER
            | DISABLE_SYSTEM_INFO | DISABLE_RECENT | DISABLE_HOME | DISABLE_BACK | DISABLE_CLOCK
            | DISABLE_SEARCH;

    /**
     * Flag to disable quick settings.
     *
     * Setting this flag disables quick settings completely, but does not disable expanding the
     * notification shade.
     */
    public static final int DISABLE2_QUICK_SETTINGS = 0x00000001;

    public static final int DISABLE2_NONE = 0x00000000;

    public static final int DISABLE2_MASK = DISABLE2_QUICK_SETTINGS;

    @IntDef(flag = true,
            value = {DISABLE2_NONE, DISABLE2_MASK, DISABLE2_QUICK_SETTINGS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Disable2Flags {}

    public static final int NAVIGATION_HINT_BACK_ALT      = 1 << 0;
    public static final int NAVIGATION_HINT_IME_SHOWN     = 1 << 1;

    public static final int WINDOW_STATUS_BAR = 1;
    public static final int WINDOW_NAVIGATION_BAR = 2;

    public static final int WINDOW_STATE_SHOWING = 0;
    public static final int WINDOW_STATE_HIDING = 1;
    public static final int WINDOW_STATE_HIDDEN = 2;

    public static final int CAMERA_LAUNCH_SOURCE_WIGGLE = 0;
    public static final int CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = 1;
    public static final int CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER = 2;

    private Context mContext;
    private IStatusBarService mService;
    private IBinder mToken = new Binder();

    StatusBarManager(Context context) {
        mContext = context;
    }

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
     */
    public void disable(int what) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.disable(what, mToken, mContext.getPackageName());
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
     */
    public void disable2(@Disable2Flags int what) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.disable2(what, mToken, mContext.getPackageName());
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Expand the notifications panel.
     */
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
     */
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
     * Expand the settings panel.
     */
    public void expandSettingsPanel() {
        expandSettingsPanel(null);
    }

    /**
     * Expand the settings panel and open a subPanel, pass null to just open the settings panel.
     */
    public void expandSettingsPanel(String subPanel) {
        try {
            final IStatusBarService svc = getService();
            if (svc != null) {
                svc.expandSettingsPanel(subPanel);
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

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

    /** @hide */
    public static String windowStateToString(int state) {
        if (state == WINDOW_STATE_HIDING) return "WINDOW_STATE_HIDING";
        if (state == WINDOW_STATE_HIDDEN) return "WINDOW_STATE_HIDDEN";
        if (state == WINDOW_STATE_SHOWING) return "WINDOW_STATE_SHOWING";
        return "WINDOW_STATE_UNKNOWN";
    }
}
