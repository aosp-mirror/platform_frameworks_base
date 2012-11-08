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

package android.app.admin;

import org.xmlpull.v1.XmlPullParserException;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

/**
 * Public interface for managing policies enforced on a device.  Most clients
 * of this class must have published a {@link DeviceAdminReceiver} that the user
 * has currently enabled.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about managing policies for device adminstration, read the
 * <a href="{@docRoot}guide/topics/admin/device-admin.html">Device Administration</a>
 * developer guide.</p>
 * </div>
 */
public class DevicePolicyManager {
    private static String TAG = "DevicePolicyManager";

    private final Context mContext;
    private final IDevicePolicyManager mService;

    private DevicePolicyManager(Context context, Handler handler) {
        mContext = context;
        mService = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
    }

    /** @hide */
    public static DevicePolicyManager create(Context context, Handler handler) {
        DevicePolicyManager me = new DevicePolicyManager(context, handler);
        return me.mService != null ? me : null;
    }

    /**
     * Activity action: ask the user to add a new device administrator to the system.
     * The desired policy is the ComponentName of the policy in the
     * {@link #EXTRA_DEVICE_ADMIN} extra field.  This will invoke a UI to
     * bring the user through adding the device administrator to the system (or
     * allowing them to reject it).
     *
     * <p>You can optionally include the {@link #EXTRA_ADD_EXPLANATION}
     * field to provide the user with additional explanation (in addition
     * to your component's description) about what is being added.
     *
     * <p>If your administrator is already active, this will ordinarily return immediately (without
     * user intervention).  However, if your administrator has been updated and is requesting
     * additional uses-policy flags, the user will be presented with the new list.  New policies
     * will not be available to the updated administrator until the user has accepted the new list.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ADD_DEVICE_ADMIN
            = "android.app.action.ADD_DEVICE_ADMIN";

    /**
     * Activity action: send when any policy admin changes a policy.
     * This is generally used to find out when a new policy is in effect.
     *
     * @hide
     */
    public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
            = "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED";

    /**
     * The ComponentName of the administrator component.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_DEVICE_ADMIN = "android.app.extra.DEVICE_ADMIN";

    /**
     * An optional CharSequence providing additional explanation for why the
     * admin is being added.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_ADD_EXPLANATION = "android.app.extra.ADD_EXPLANATION";

    /**
     * Activity action: have the user enter a new password. This activity should
     * be launched after using {@link #setPasswordQuality(ComponentName, int)},
     * or {@link #setPasswordMinimumLength(ComponentName, int)} to have the user
     * enter a new password that meets the current requirements. You can use
     * {@link #isActivePasswordSufficient()} to determine whether you need to
     * have the user select a new password in order to meet the current
     * constraints. Upon being resumed from this activity, you can check the new
     * password characteristics to see if they are sufficient.
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
                return mService.isAdminActive(who, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Return a list of all currently active device administrator's component
     * names.  Note that if there are no administrators than null may be
     * returned.
     */
    public List<ComponentName> getActiveAdmins() {
        if (mService != null) {
            try {
                return mService.getActiveAdmins(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    public boolean packageHasActiveAdmins(String packageName) {
        if (mService != null) {
            try {
                return mService.packageHasActiveAdmins(packageName, UserHandle.myUserId());
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
                mService.removeActiveAdmin(who, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns true if an administrator has been granted a particular device policy.  This can
     * be used to check if the administrator was activated under an earlier set of policies,
     * but requires additional policies after an upgrade.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  Must be
     * an active administrator, or an exception will be thrown.
     * @param usesPolicy Which uses-policy to check, as defined in {@link DeviceAdminInfo}.
     */
    public boolean hasGrantedPolicy(ComponentName admin, int usesPolicy) {
        if (mService != null) {
            try {
                return mService.hasGrantedPolicy(admin, usesPolicy, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Constant for {@link #setPasswordQuality}: the policy has no requirements
     * for the password.  Note that quality constants are ordered so that higher
     * values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_UNSPECIFIED = 0;

    /**
     * Constant for {@link #setPasswordQuality}: the policy allows for low-security biometric
     * recognition technology.  This implies technologies that can recognize the identity of
     * an individual to about a 3 digit PIN (false detection is less than 1 in 1,000).
     * Note that quality constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_BIOMETRIC_WEAK = 0x8000;

    /**
     * Constant for {@link #setPasswordQuality}: the policy requires some kind
     * of password, but doesn't care what it is.  Note that quality constants
     * are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_SOMETHING = 0x10000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least numeric characters.  Note that quality
     * constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_NUMERIC = 0x20000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least alphabetic (or other symbol) characters.
     * Note that quality constants are ordered so that higher values are more
     * restrictive.
     */
    public static final int PASSWORD_QUALITY_ALPHABETIC = 0x40000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least <em>both></em> numeric <em>and</em>
     * alphabetic (or other symbol) characters.  Note that quality constants are
     * ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_ALPHANUMERIC = 0x50000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least a letter, a numerical digit and a special
     * symbol, by default. With this password quality, passwords can be
     * restricted to contain various sets of characters, like at least an
     * uppercase letter, etc. These are specified using various methods,
     * like {@link #setPasswordMinimumLowerCase(ComponentName, int)}. Note
     * that quality constants are ordered so that higher values are more
     * restrictive.
     */
    public static final int PASSWORD_QUALITY_COMPLEX = 0x60000;

    /**
     * Called by an application that is administering the device to set the
     * password restrictions it is imposing.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
     *
     * <p>Quality constants are ordered so that higher values are more restrictive;
     * thus the highest requested quality constant (between the policy set here,
     * the user's preference, and any other considerations) is the one that
     * is in effect.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param quality The new desired quality.  One of
     * {@link #PASSWORD_QUALITY_UNSPECIFIED}, {@link #PASSWORD_QUALITY_SOMETHING},
     * {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_ALPHABETIC},
     * {@link #PASSWORD_QUALITY_ALPHANUMERIC} or {@link #PASSWORD_QUALITY_COMPLEX}.
     */
    public void setPasswordQuality(ComponentName admin, int quality) {
        if (mService != null) {
            try {
                mService.setPasswordQuality(admin, quality, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current minimum password quality for all admins
     * or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public int getPasswordQuality(ComponentName admin) {
        return getPasswordQuality(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordQuality(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordQuality(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return PASSWORD_QUALITY_UNSPECIFIED;
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
     * {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_ALPHABETIC}
     * {@link #PASSWORD_QUALITY_ALPHANUMERIC}, or {@link #PASSWORD_QUALITY_COMPLEX}
     * with {@link #setPasswordQuality}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum password length.  A value of 0
     * means there is no restriction.
     */
    public void setPasswordMinimumLength(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLength(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current minimum password length for all admins
     * or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public int getPasswordMinimumLength(ComponentName admin) {
        return getPasswordMinimumLength(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLength(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLength(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of upper case letters required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of upper case letters
     *            required in the password. A value of 0 means there is no
     *            restriction.
     */
    public void setPasswordMinimumUpperCase(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumUpperCase(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of upper case letters required in the
     * password for all admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumUpperCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of upper case letters required in the
     *         password.
     */
    public int getPasswordMinimumUpperCase(ComponentName admin) {
        return getPasswordMinimumUpperCase(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumUpperCase(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumUpperCase(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of lower case letters required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of lower case letters
     *            required in the password. A value of 0 means there is no
     *            restriction.
     */
    public void setPasswordMinimumLowerCase(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLowerCase(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of lower case letters required in the
     * password for all admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumLowerCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of lower case letters required in the
     *         password.
     */
    public int getPasswordMinimumLowerCase(ComponentName admin) {
        return getPasswordMinimumLowerCase(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLowerCase(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLowerCase(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of letters required in the password. After setting this,
     * the user will not be able to enter a new password that is not at least as
     * restrictive as what has been set. Note that the current password will
     * remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of letters required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumLetters(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLetters(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of letters required in the password for all
     * admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumLetters(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumLetters(ComponentName admin) {
        return getPasswordMinimumLetters(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLetters(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLetters(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of numerical digits required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of numerical digits required
     *            in the password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumNumeric(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNumeric(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of numerical digits required in the password
     * for all admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumNumeric(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of numerical digits required in the password.
     */
    public int getPasswordMinimumNumeric(ComponentName admin) {
        return getPasswordMinimumNumeric(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNumeric(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNumeric(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of symbols required in the password. After setting this,
     * the user will not be able to enter a new password that is not at least as
     * restrictive as what has been set. Note that the current password will
     * remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of symbols required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumSymbols(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumSymbols(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of symbols required in the password for all
     * admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumSymbols(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of symbols required in the password.
     */
    public int getPasswordMinimumSymbols(ComponentName admin) {
        return getPasswordMinimumSymbols(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumSymbols(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumSymbols(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of non-letter characters (numerical digits or symbols)
     * required in the password. After setting this, the user will not be able
     * to enter a new password that is not at least as restrictive as what has
     * been set. Note that the current password will remain until the user has
     * set a new one, so the change does not take place immediately. To prompt
     * the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator
     * has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. The default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of letters required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumNonLetter(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNonLetter(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of non-letter characters required in the
     * password for all admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumNonLetter(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumNonLetter(ComponentName admin) {
        return getPasswordMinimumNonLetter(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNonLetter(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNonLetter(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

  /**
   * Called by an application that is administering the device to set the length
   * of the password history. After setting this, the user will not be able to
   * enter a new password that is the same as any password in the history. Note
   * that the current password will remain until the user has set a new one, so
   * the change does not take place immediately. To prompt the user for a new
   * password, use {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
   * This constraint is only imposed if the administrator has also requested
   * either {@link #PASSWORD_QUALITY_NUMERIC},
   * {@link #PASSWORD_QUALITY_ALPHABETIC}, or
   * {@link #PASSWORD_QUALITY_ALPHANUMERIC} with {@link #setPasswordQuality}.
   *
   * <p>
   * The calling device admin must have requested
   * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this
   * method; if it has not, a security exception will be thrown.
   *
   * @param admin Which {@link DeviceAdminReceiver} this request is associated
   *        with.
   * @param length The new desired length of password history. A value of 0
   *        means there is no restriction.
   */
    public void setPasswordHistoryLength(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordHistoryLength(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a device admin to set the password expiration timeout. Calling this method
     * will restart the countdown for password expiration for the given admin, as will changing
     * the device password (for all admins).
     *
     * <p>The provided timeout is the time delta in ms and will be added to the current time.
     * For example, to have the password expire 5 days from now, timeout would be
     * 5 * 86400 * 1000 = 432000000 ms for timeout.
     *
     * <p>To disable password expiration, a value of 0 may be used for timeout.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to be able to call this
     * method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeout The limit (in ms) that a password can remain in effect. A value of 0
     *        means there is no restriction (unlimited).
     */
    public void setPasswordExpirationTimeout(ComponentName admin, long timeout) {
        if (mService != null) {
            try {
                mService.setPasswordExpirationTimeout(admin, timeout, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Get the password expiration timeout for the given admin. The expiration timeout is the
     * recurring expiration timeout provided in the call to
     * {@link #setPasswordExpirationTimeout(ComponentName, long)} for the given admin or the
     * aggregate of all policy administrators if admin is null.
     *
     * @param admin The name of the admin component to check, or null to aggregate all admins.
     * @return The timeout for the given admin or the minimum of all timeouts
     */
    public long getPasswordExpirationTimeout(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpirationTimeout(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Get the current password expiration time for the given admin or an aggregate of
     * all admins if admin is null. If the password is expired, this will return the time since
     * the password expired as a negative number.  If admin is null, then a composite of all
     * expiration timeouts is returned - which will be the minimum of all timeouts.
     *
     * @param admin The name of the admin component to check, or null to aggregate all admins.
     * @return The password expiration time, in ms.
     */
    public long getPasswordExpiration(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpiration(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Retrieve the current password history length for all admins
     * or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     * @return The length of the password history
     */
    public int getPasswordHistoryLength(ComponentName admin) {
        return getPasswordHistoryLength(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordHistoryLength(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordHistoryLength(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Return the maximum password length that the device supports for a
     * particular password quality.
     * @param quality The quality being interrogated.
     * @return Returns the maximum length that the user can enter.
     */
    public int getPasswordMaximumLength(int quality) {
        // Kind-of arbitrary.
        return 16;
    }

    /**
     * Determine whether the current password the user has set is sufficient
     * to meet the policy requirements (quality, minimum length) that have been
     * requested.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @return Returns true if the password meets the current requirements,
     * else false.
     */
    public boolean isActivePasswordSufficient() {
        if (mService != null) {
            try {
                return mService.isActivePasswordSufficient(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Retrieve the number of times the user has failed at entering a
     * password since that last successful password entry.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to be able to call
     * this method; if it has not, a security exception will be thrown.
     */
    public int getCurrentFailedPasswordAttempts() {
        if (mService != null) {
            try {
                return mService.getCurrentFailedPasswordAttempts(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return -1;
    }

    /**
     * Setting this to a value greater than zero enables a built-in policy
     * that will perform a device wipe after too many incorrect
     * device-unlock passwords have been entered.  This built-in policy combines
     * watching for failed passwords and wiping the device, and requires
     * that you request both {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}}.
     *
     * <p>To implement any other policy (e.g. wiping data for a particular
     * application only, erasing or revoking credentials, or reporting the
     * failure to a server), you should implement
     * {@link DeviceAdminReceiver#onPasswordFailed(Context, android.content.Intent)}
     * instead.  Do not use this API, because if the maximum count is reached,
     * the device will be wiped immediately, and your callback will not be invoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param num The number of failed password attempts at which point the
     * device will wipe its data.
     */
    public void setMaximumFailedPasswordsForWipe(ComponentName admin, int num) {
        if (mService != null) {
            try {
                mService.setMaximumFailedPasswordsForWipe(admin, num, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current maximum number of login attempts that are allowed
     * before the device wipes itself, for all admins
     * or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public int getMaximumFailedPasswordsForWipe(ComponentName admin) {
        return getMaximumFailedPasswordsForWipe(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getMaximumFailedPasswordsForWipe(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumFailedPasswordsForWipe(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Flag for {@link #resetPassword}: don't allow other admins to change
     * the password again until the user has entered it.
     */
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;

    /**
     * Force a new device unlock password (the password needed to access the
     * entire device, not for individual accounts) on the user.  This takes
     * effect immediately.
     * The given password must be sufficient for the
     * current password quality and length constraints as returned by
     * {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet
     * these constraints, then it will be rejected and false returned.  Note
     * that the password may be a stronger quality (containing alphanumeric
     * characters when the requested quality is only numeric), in which case
     * the currently active quality will be increased to match.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param password The new password for the user.
     * @param flags May be 0 or {@link #RESET_PASSWORD_REQUIRE_ENTRY}.
     * @return Returns true if the password was applied, or false if it is
     * not acceptable for the current constraints.
     */
    public boolean resetPassword(String password, int flags) {
        if (mService != null) {
            try {
                return mService.resetPassword(password, flags, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to set the
     * maximum time for user activity until the device will lock.  This limits
     * the length that the user can set.  It takes effect immediately.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeMs The new desired maximum time to lock in milliseconds.
     * A value of 0 means there is no restriction.
     */
    public void setMaximumTimeToLock(ComponentName admin, long timeMs) {
        if (mService != null) {
            try {
                mService.setMaximumTimeToLock(admin, timeMs, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current maximum time to unlock for all admins
     * or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public long getMaximumTimeToLock(ComponentName admin) {
        return getMaximumTimeToLock(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public long getMaximumTimeToLock(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLock(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Make the device lock immediately, as if the lock screen timeout has
     * expired at the point of this call.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} to be able to call
     * this method; if it has not, a security exception will be thrown.
     */
    public void lockNow() {
        if (mService != null) {
            try {
                mService.lockNow();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Flag for {@link #wipeData(int)}: also erase the device's external
     * storage.
     */
    public static final int WIPE_EXTERNAL_STORAGE = 0x0001;

    /**
     * Ask the user date be wiped.  This will cause the device to reboot,
     * erasing all user data while next booting up.  External storage such
     * as SD cards will be also erased if the flag {@link #WIPE_EXTERNAL_STORAGE}
     * is set.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param flags Bit mask of additional options: currently 0 and
     *              {@link #WIPE_EXTERNAL_STORAGE} are supported.
     */
    public void wipeData(int flags) {
        if (mService != null) {
            try {
                mService.wipeData(flags, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by an application that is administering the device to set the
     * global proxy and exclusion list.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_SETS_GLOBAL_PROXY} to be able to call
     * this method; if it has not, a security exception will be thrown.
     * Only the first device admin can set the proxy. If a second admin attempts
     * to set the proxy, the {@link ComponentName} of the admin originally setting the
     * proxy will be returned. If successful in setting the proxy, null will
     * be returned.
     * The method can be called repeatedly by the device admin alrady setting the
     * proxy to update the proxy and exclusion list.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param proxySpec the global proxy desired. Must be an HTTP Proxy.
     *            Pass Proxy.NO_PROXY to reset the proxy.
     * @param exclusionList a list of domains to be excluded from the global proxy.
     * @return returns null if the proxy was successfully set, or a {@link ComponentName}
     *            of the device admin that sets thew proxy otherwise.
     * @hide
     */
    public ComponentName setGlobalProxy(ComponentName admin, Proxy proxySpec,
            List<String> exclusionList ) {
        if (proxySpec == null) {
            throw new NullPointerException();
        }
        if (mService != null) {
            try {
                String hostSpec;
                String exclSpec;
                if (proxySpec.equals(Proxy.NO_PROXY)) {
                    hostSpec = null;
                    exclSpec = null;
                } else {
                    if (!proxySpec.type().equals(Proxy.Type.HTTP)) {
                        throw new IllegalArgumentException();
                    }
                    InetSocketAddress sa = (InetSocketAddress)proxySpec.address();
                    String hostName = sa.getHostName();
                    int port = sa.getPort();
                    StringBuilder hostBuilder = new StringBuilder();
                    hostSpec = hostBuilder.append(hostName)
                        .append(":").append(Integer.toString(port)).toString();
                    if (exclusionList == null) {
                        exclSpec = "";
                    } else {
                        StringBuilder listBuilder = new StringBuilder();
                        boolean firstDomain = true;
                        for (String exclDomain : exclusionList) {
                            if (!firstDomain) {
                                listBuilder = listBuilder.append(",");
                            } else {
                                firstDomain = false;
                            }
                            listBuilder = listBuilder.append(exclDomain.trim());
                        }
                        exclSpec = listBuilder.toString();
                    }
                    android.net.Proxy.validate(hostName, Integer.toString(port), exclSpec);
                }
                return mService.setGlobalProxy(admin, hostSpec, exclSpec, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Returns the component name setting the global proxy.
     * @return ComponentName object of the device admin that set the global proxy, or
     *            null if no admin has set the proxy.
     * @hide
     */
    public ComponentName getGlobalProxyAdmin() {
        if (mService != null) {
            try {
                return mService.getGlobalProxyAdmin(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is not supported.
     */
    public static final int ENCRYPTION_STATUS_UNSUPPORTED = 0;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is supported, but is not currently active.
     */
    public static final int ENCRYPTION_STATUS_INACTIVE = 1;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is not currently active, but is currently
     * being activated.  This is only reported by devices that support
     * encryption of data and only when the storage is currently
     * undergoing a process of becoming encrypted.  A device that must reboot and/or wipe data
     * to become encrypted will never return this value.
     */
    public static final int ENCRYPTION_STATUS_ACTIVATING = 2;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE = 3;

    /**
     * Activity action: begin the process of encrypting data on the device.  This activity should
     * be launched after using {@link #setStorageEncryption} to request encryption be activated.
     * After resuming from this activity, use {@link #getStorageEncryption}
     * to check encryption status.  However, on some devices this activity may never return, as
     * it may trigger a reboot and in some cases a complete data wipe of the device.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_START_ENCRYPTION
            = "android.app.action.START_ENCRYPTION";

    /**
     * Widgets are enabled in keyguard
     */
    public static final int KEYGUARD_DISABLE_FEATURES_NONE = 0;

    /**
     * Disable all keyguard widgets
     */
    public static final int KEYGUARD_DISABLE_WIDGETS_ALL = 1 << 0;

    /**
     * Disable the camera on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_SECURE_CAMERA = 1 << 1;

    /**
     * Disable all current and future keyguard customizations.
     */
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;

    /**
     * Called by an application that is administering the device to
     * request that the storage system be encrypted.
     *
     * <p>When multiple device administrators attempt to control device
     * encryption, the most secure, supported setting will always be
     * used.  If any device administrator requests device encryption,
     * it will be enabled;  Conversely, if a device administrator
     * attempts to disable device encryption while another
     * device administrator has enabled it, the call to disable will
     * fail (most commonly returning {@link #ENCRYPTION_STATUS_ACTIVE}).
     *
     * <p>This policy controls encryption of the secure (application data) storage area.  Data
     * written to other storage areas may or may not be encrypted, and this policy does not require
     * or control the encryption of any other storage areas.
     * There is one exception:  If {@link android.os.Environment#isExternalStorageEmulated()} is
     * {@code true}, then the directory returned by
     * {@link android.os.Environment#getExternalStorageDirectory()} must be written to disk
     * within the encrypted storage area.
     *
     * <p>Important Note:  On some devices, it is possible to encrypt storage without requiring
     * the user to create a device PIN or Password.  In this case, the storage is encrypted, but
     * the encryption key may not be fully secured.  For maximum security, the administrator should
     * also require (and check for) a pattern, PIN, or password.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param encrypt true to request encryption, false to release any previous request
     * @return the new request status (for all active admins) - will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE}, or
     * {@link #ENCRYPTION_STATUS_ACTIVE}.  This is the value of the requests;  Use
     * {@link #getStorageEncryptionStatus()} to query the actual device state.
     */
    public int setStorageEncryption(ComponentName admin, boolean encrypt) {
        if (mService != null) {
            try {
                return mService.setStorageEncryption(admin, encrypt, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Called by an application that is administering the device to
     * determine the requested setting for secure storage.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  If null,
     * this will return the requested encryption setting as an aggregate of all active
     * administrators.
     * @return true if the admin(s) are requesting encryption, false if not.
     */
    public boolean getStorageEncryption(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getStorageEncryption(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to
     * determine the current encryption status of the device.
     *
     * Depending on the returned status code, the caller may proceed in different
     * ways.  If the result is {@link #ENCRYPTION_STATUS_UNSUPPORTED}, the
     * storage system does not support encryption.  If the
     * result is {@link #ENCRYPTION_STATUS_INACTIVE}, use {@link
     * #ACTION_START_ENCRYPTION} to begin the process of encrypting or decrypting the
     * storage.  If the result is {@link #ENCRYPTION_STATUS_ACTIVATING} or
     * {@link #ENCRYPTION_STATUS_ACTIVE}, no further action is required.
     *
     * @return current status of encryption.  The value will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE},
     * {@link #ENCRYPTION_STATUS_ACTIVATING}, or{@link #ENCRYPTION_STATUS_ACTIVE}.
     */
    public int getStorageEncryptionStatus() {
        return getStorageEncryptionStatus(UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getStorageEncryptionStatus(int userHandle) {
        if (mService != null) {
            try {
                return mService.getStorageEncryptionStatus(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Called by an application that is administering the device to disable all cameras
     * on the device.  After setting this, no applications will be able to access any cameras
     * on the device.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether or not the camera should be disabled.
     */
    public void setCameraDisabled(ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setCameraDisabled(admin, disabled, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not the device's cameras have been disabled either by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or null to check if any admins
     * have disabled the camera
     */
    public boolean getCameraDisabled(ComponentName admin) {
        return getCameraDisabled(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public boolean getCameraDisabled(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getCameraDisabled(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to disable keyguard customizations,
     * such as widgets. After setting this, keyguard features will be disabled according to the
     * provided feature list.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param which {@link #KEYGUARD_DISABLE_FEATURES_NONE} (default),
     * {@link #KEYGUARD_DISABLE_WIDGETS_ALL}, {@link #KEYGUARD_DISABLE_SECURE_CAMERA},
     * {@link #KEYGUARD_DISABLE_FEATURES_ALL}
     */
    public void setKeyguardDisabledFeatures(ComponentName admin, int which) {
        if (mService != null) {
            try {
                mService.setKeyguardDisabledFeatures(admin, which, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not features have been disabled in keyguard either by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or null to check if any admins
     * have disabled features in keyguard.
     * @return bitfield of flags. See {@link #setKeyguardDisabledFeatures(ComponentName, int)}
     * for a list.
     */
    public int getKeyguardDisabledFeatures(ComponentName admin) {
        return getKeyguardDisabledFeatures(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getKeyguardDisabledFeatures(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getKeyguardDisabledFeatures(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return KEYGUARD_DISABLE_FEATURES_NONE;
    }

    /**
     * @hide
     */
    public void setActiveAdmin(ComponentName policyReceiver, boolean refreshing) {
        if (mService != null) {
            try {
                mService.setActiveAdmin(policyReceiver, refreshing, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns the DeviceAdminInfo as defined by the administrator's package info & meta-data
     * @hide
     */
    public DeviceAdminInfo getAdminInfo(ComponentName cn) {
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
    public void getRemoveWarning(ComponentName admin, RemoteCallback result) {
        if (mService != null) {
            try {
                mService.getRemoveWarning(admin, result, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void setActivePasswordState(int quality, int length, int letters, int uppercase,
            int lowercase, int numbers, int symbols, int nonletter, int userHandle) {
        if (mService != null) {
            try {
                mService.setActivePasswordState(quality, length, letters, uppercase, lowercase,
                        numbers, symbols, nonletter, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void reportFailedPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }
}
