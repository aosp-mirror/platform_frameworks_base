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

/**
 * Allows an app to control the status bar.
 *
 * @hide
 */
public class StatusBarManager {
    /**
     * Flag for {@link #disable} to make the status bar not expandable.  Unless you also
     * set {@link #DISABLE_NOTIFICATION_ICONS}, new notifications will continue to show.
     */
    public static final int DISABLE_EXPAND = 0x00000001;

    /**
     * Flag for {@link #disable} to hide notification icons and scrolling ticker text.
     */
    public static final int DISABLE_NOTIFICATION_ICONS = 0x00000002;

    /**
     * Flag for {@link #disable} to disable incoming notification alerts.  This will not block
     * icons, but it will block sound, vibrating and other visual or aural notifications.
     */
    public static final int DISABLE_NOTIFICATION_ALERTS = 0x00000004;

    /**
     * Flag for {@link #disable} to hide only the scrolling ticker.  Note that
     * {@link #DISABLE_NOTIFICATION_ICONS} implies {@link #DISABLE_NOTIFICATION_TICKER}.
     */
    public static final int DISABLE_NOTIFICATION_TICKER = 0x00000008;

    /**
     * Re-enable all of the status bar features that you've disabled.
     */
    public static final int DISABLE_NONE = 0x00000000;

    private Context mContext;
    private IStatusBar mService;
    private IBinder mToken = new Binder();

    StatusBarManager(Context context) {
        mContext = context;
        mService = IStatusBar.Stub.asInterface(
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
            mService.activate();
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
            mService.deactivate();
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * Toggle the status bar.
     */
    public void toggle() {
        try {
            mService.toggle();
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }

    public IBinder addIcon(String slot, int iconId, int iconLevel) {
        try {
            return mService.addIcon(slot, mContext.getPackageName(), iconId, iconLevel);
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }

    public void updateIcon(IBinder key, String slot, int iconId, int iconLevel) {
        try {
            mService.updateIcon(key, slot, mContext.getPackageName(), iconId, iconLevel);
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }

    public void removeIcon(IBinder key) {
        try {
            mService.removeIcon(key);
        } catch (RemoteException ex) {
            // system process is dead anyway.
            throw new RuntimeException(ex);
        }
    }
}
