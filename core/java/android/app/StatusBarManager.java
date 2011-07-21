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

import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.ServiceManager;
import android.view.View;

import com.android.internal.statusbar.IStatusBarService;

/**
 * Allows an app to control the status bar.
 *
 * @hide
 */
public class StatusBarManager {

    public static final int DISABLE_EXPAND = View.STATUS_BAR_DISABLE_EXPAND;
    public static final int DISABLE_NOTIFICATION_ICONS = View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
    public static final int DISABLE_NOTIFICATION_ALERTS
            = View.STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;
    public static final int DISABLE_NOTIFICATION_TICKER
            = View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
    public static final int DISABLE_SYSTEM_INFO = View.STATUS_BAR_DISABLE_SYSTEM_INFO;
    public static final int DISABLE_NAVIGATION = View.STATUS_BAR_DISABLE_NAVIGATION;
    public static final int DISABLE_BACK = View.STATUS_BAR_DISABLE_BACK;
    public static final int DISABLE_CLOCK = View.STATUS_BAR_DISABLE_CLOCK;

    public static final int DISABLE_NONE = 0x00000000;

    public static final int DISABLE_MASK = DISABLE_EXPAND | DISABLE_NOTIFICATION_ICONS
            | DISABLE_NOTIFICATION_ALERTS | DISABLE_NOTIFICATION_TICKER
            | DISABLE_SYSTEM_INFO| DISABLE_NAVIGATION | DISABLE_BACK | DISABLE_CLOCK;

    private Context mContext;
    private IStatusBarService mService;
    private IBinder mToken = new Binder();

    StatusBarManager(Context context) {
        mContext = context;
        mService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    /**
     * Disable some features in the status bar.  Pass the bitwise-or of the DISABLE_* flags.
     * To re-enable everything, pass {@link #DISABLE_NONE}.
     */
    public void disable(int what) {
        try {
            mService.disable(what, mToken, mContext.getPackageName());
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * Expand the status bar.
     */
    public void expand() {
        try {
            mService.expand();
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * Collapse the status bar.
     */
    public void collapse() {
        try {
            mService.collapse();
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }

    public void setIcon(String slot, int iconId, int iconLevel, String contentDescription) {
        try {
            mService.setIcon(slot, mContext.getPackageName(), iconId, iconLevel,
                    contentDescription);
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }

    public void removeIcon(String slot) {
        try {
            mService.removeIcon(slot);
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }

    public void setIconVisibility(String slot, boolean visible) {
        try {
            mService.setIconVisibility(slot, visible);
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }
}
