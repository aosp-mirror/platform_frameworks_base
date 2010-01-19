/*
 * Copyright (C) 2010 The Android Open Source Project
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

import org.xmlpull.v1.XmlPullParserException;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;

/**
 * Public interface for managing policies enforced on a device.  Most clients
 * of this class must have published a {@link DeviceAdmin} that the user
 * has currently enabled.
 */
public class DevicePolicyManager {
    private static String TAG = "DevicePolicyManager";
    private static boolean DEBUG = false;
    private static boolean localLOGV = DEBUG || android.util.Config.LOGV;

    private final Context mContext;
    private final Handler mHandler;
    private final IDevicePolicyManager mService;

    /*package*/ DevicePolicyManager(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mService = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
    }

    /**
     * Activity action: ask the user to add a new device administrator to the system.
     * The desired policy is the ComponentName of the policy in the
     * {@link #EXTRA_DEVICE_ADMIN} extra field.  This will invoke a UI to
     * bring the user through adding the device administrator to the system (or
     * allowing them to reject it).
     * 
     * <p>Note: the current platform can only have one device administrator
     * active at a time.  If you make this request while there is already
     * an active administrator, this new request will be canceled automatically.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ADD_DEVICE_ADMIN
            = "android.app.action.ADD_DEVICE_ADMIN";
    
    /**
     * The ComponentName of the administrator component.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_DEVICE_ADMIN = "android.app.extra.DEVICE_ADMIN";
    
    /**
     * Activity action: have the user enter a new password.  This activity
     * should be launched after using {@link #setPasswordMode(ComponentName, int)}
     * or {@link #setMinimumPasswordLength(ComponentName, int)} to have the
     * user enter a new password that meets the current requirements.  If the
     * current password is sufficient, the activity will exit immediately without
     * being displayed to the user.  Upon receiving a result from this activity,
     * you can check the new password characteristics to see if they are
     * sufficient.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PASSWORD
            = "android.app.action.SET_NEW_PASSWORD";
    
    /**
     * Return true if the given administrator component is currently
     * active (enabled) in the system.
     */
    public boolean isAdminActive(ComponentName who) {
        if (mService != null) {
            try {
                return who.equals(mService.getActiveAdmin());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }
    
    /**
     * Remove a current administration component.  This can only be called
     * by the application that owns the administration component; if you
     * try to remove someone else's component, a security exception will be
     * thrown.
     */
    public void removeActiveAdmin(ComponentName who) {
        if (mService != null) {
            try {
                mService.removeActiveAdmin(who);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * Constant for {@link #setPasswordMode}: the policy has no requirements
     * for the password.
     */
    public static final int PASSWORD_MODE_UNSPECIFIED = 0;
    
    /**
     * Constant for {@link #setPasswordMode}: the user must have at least a
     * numeric password.
     */
    public static final int PASSWORD_MODE_NUMERIC = 1000;
    
    /**
     * Constant for {@link #setPasswordMode}: the user must have at least an
     * alphanumeric password.
     */
    public static final int PASSWORD_MODE_ALPHANUMERIC = 2000;
    
    /**
     * Called by an application that is administering the device to set the
     * password restrictions it is imposing.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
     * 
     * @param admin Which {@link DeviceAdmin} this request is associated with.
     * @param mode The new desired mode.  One of
     * {@link #PASSWORD_MODE_UNSPECIFIED}, {@link #PASSWORD_MODE_NUMERIC},
     * or {@link #PASSWORD_MODE_ALPHANUMERIC}.
     */
    public void setPasswordMode(ComponentName admin, int mode) {
        if (mService != null) {
            try {
                mService.setPasswordMode(admin, mode);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * Retrieve the current password mode that is in effect due to all
     * device admins.
     */
    public int getPasswordMode() {
        if (mService != null) {
            try {
                return mService.getPasswordMode();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return PASSWORD_MODE_UNSPECIFIED;
    }
    
    /**
     * Retrieve the password mode associated with the last password the
     * user selected.
     */
    public int getActivePasswordMode() {
        if (mService != null) {
            try {
                return mService.getActivePasswordMode();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return PASSWORD_MODE_UNSPECIFIED;
    }
    
    /**
     * Called by an application that is administering the device to set the
     * minimum allowed password length.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.  This
     * constraint is only imposed if the administrator has also requested either
     * {@link #PASSWORD_MODE_NUMERIC} or {@link #PASSWORD_MODE_ALPHANUMERIC}
     * with {@link #setPasswordMode}.
     * 
     * @param admin Which {@link DeviceAdmin} this request is associated with.
     * @param length The new desired minimum password length.  A value of 0
     * means there is no restriction.
     */
    public void setMinimumPasswordLength(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setMinimumPasswordLength(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * Retrieve the current minimum password length that is in effect due to all
     * device admins.
     */
    public int getMinimumPasswordLength() {
        if (mService != null) {
            try {
                return mService.getMinimumPasswordLength();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }
    
    /**
     * Retrieve the password length associated with the last password the
     * user selected.
     */
    public int getActiveMinimumPasswordLength() {
        if (mService != null) {
            try {
                return mService.getActiveMinimumPasswordLength();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }
    
    /**
     * Retrieve the number of times the user has failed at entering a
     * password since that last successful password entry.
     */
    public int getCurrentFailedPasswordAttempts() {
        if (mService != null) {
            try {
                return mService.getCurrentFailedPasswordAttempts();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return -1;
    }
    
    /**
     * Called by an application that is administering the device to set the
     * maximum time for user activity until the device will lock.  This limits
     * the length that the user can set.  It takes effect immediately.
     * 
     * @param admin Which {@link DeviceAdmin} this request is associated with.
     * @param timeMs The new desired maximum time to lock in milliseconds.
     * A value of 0 means there is no restriction.
     */
    public void setMaximumTimeToLock(ComponentName admin, long timeMs) {
        if (mService != null) {
            try {
                mService.setMaximumTimeToLock(admin, timeMs);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * Retrieve the current maximum time to lock that is in effect due to all
     * device admins.  Returns 0 if no maximum is set.
     */
    public long getMaximumTimeToLock() {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLock();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }
    
    /**
     * Constant for {@link #wipeData}: perform a low-level format of data
     * storage.
     */
    public static final int WIPE_LOW_LEVEL_FORMAT = 0x0001;
    
    /**
     * Constant for {@link #wipeData}: also wipe any external storage.
     */
    public static final int WIPE_EXTERNAL_STORAGE = 0x0002;
    
    /**
     * Ask the user date be wiped.  This will cause the device to reboot,
     * erasing all user data while next booting up.
     * 
     * @param flags Bit mask of additional options: currently
     * {@link #WIPE_LOW_LEVEL_FORMAT} and {@link #WIPE_EXTERNAL_STORAGE}.
     */
    public void wipeData(int flags) {
        if (mService != null) {
            try {
                mService.wipeData(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * @hide
     */
    public void setActiveAdmin(ComponentName policyReceiver) {
        if (mService != null) {
            try {
                mService.setActiveAdmin(policyReceiver);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * @hide
     */
    public ComponentName getActiveAdmin() {
        if (mService != null) {
            try {
                return mService.getActiveAdmin();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }
    
    /**
     * @hide
     */
    public DeviceAdminInfo getActiveAdminInfo() {
        ComponentName cn = getActiveAdmin();
        if (cn == null) {
            return null;
        }
        
        ActivityInfo ai;
        try {
            ai = mContext.getPackageManager().getReceiverInfo(cn,
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to retrieve device policy " + cn, e);
            return null;
        }
        
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = ai;
        
        try {
            return new DeviceAdminInfo(mContext, ri);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Unable to parse device policy " + cn, e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Unable to parse device policy " + cn, e);
            return null;
        }
    }
    
    /**
     * @hide
     */
    public void setActivePasswordState(int mode, int length) {
        if (mService != null) {
            try {
                mService.setActivePasswordState(mode, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * @hide
     */
    public void reportFailedPasswordAttempt() {
        if (mService != null) {
            try {
                mService.reportFailedPasswordAttempt();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
    
    /**
     * @hide
     */
    public void reportSuccessfulPasswordAttempt() {
        if (mService != null) {
            try {
                mService.reportSuccessfulPasswordAttempt();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
}
