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

import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * This class provides access to the system uimode services.  These services
 * allow applications to control UI modes of the device.
 * It provides functionality to disable the car mode and it gives access to the
 * night mode settings.
 * 
 * <p>These facilities are built on top of the underlying
 * {@link android.content.Intent#ACTION_DOCK_EVENT} broadcasts that are sent when the user
 * physical places the device into and out of a dock.  When that happens,
 * the UiModeManager switches the system {@link android.content.res.Configuration}
 * to the appropriate UI mode, sends broadcasts about the mode switch, and
 * starts the corresponding mode activity if appropriate.  See the
 * broadcasts {@link #ACTION_ENTER_CAR_MODE} and
 * {@link #ACTION_ENTER_DESK_MODE} for more information.
 * 
 * <p>In addition, the user may manually switch the system to car mode without
 * physically being in a dock.  While in car mode -- whether by manual action
 * from the user or being physically placed in a dock -- a notification is
 * displayed allowing the user to exit dock mode.  Thus the dock mode
 * represented here may be different than the current state of the underlying
 * dock event broadcast.
 *
 * <p>You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.UI_MODE_SERVICE)}.
 */
public class UiModeManager {
    private static final String TAG = "UiModeManager";

    /**
     * Broadcast sent when the device's UI has switched to car mode, either
     * by being placed in a car dock or explicit action of the user.  After
     * sending the broadcast, the system will start the intent
     * {@link android.content.Intent#ACTION_MAIN} with category
     * {@link android.content.Intent#CATEGORY_CAR_DOCK}
     * to display the car UI, which typically what an application would
     * implement to provide their own interface.  However, applications can
     * also monitor this Intent in order to be informed of mode changes or
     * prevent the normal car UI from being displayed by setting the result
     * of the broadcast to {@link Activity#RESULT_CANCELED}.
     */
    public static String ACTION_ENTER_CAR_MODE = "android.app.action.ENTER_CAR_MODE";
    
    /**
     * Broadcast sent when the device's UI has switch away from car mode back
     * to normal mode.  Typically used by a car mode app, to dismiss itself
     * when the user exits car mode.
     */
    public static String ACTION_EXIT_CAR_MODE = "android.app.action.EXIT_CAR_MODE";
    
    /**
     * Broadcast sent when the device's UI has switched to desk mode,
     * by being placed in a desk dock.  After
     * sending the broadcast, the system will start the intent
     * {@link android.content.Intent#ACTION_MAIN} with category
     * {@link android.content.Intent#CATEGORY_DESK_DOCK}
     * to display the desk UI, which typically what an application would
     * implement to provide their own interface.  However, applications can
     * also monitor this Intent in order to be informed of mode changes or
     * prevent the normal desk UI from being displayed by setting the result
     * of the broadcast to {@link Activity#RESULT_CANCELED}.
     */
    public static String ACTION_ENTER_DESK_MODE = "android.app.action.ENTER_DESK_MODE";
    
    /**
     * Broadcast sent when the device's UI has switched away from desk mode back
     * to normal mode.  Typically used by a desk mode app, to dismiss itself
     * when the user exits desk mode.
     */
    public static String ACTION_EXIT_DESK_MODE = "android.app.action.EXIT_DESK_MODE";
    
    /** Constant for {@link #setNightMode(int)} and {@link #getNightMode()}:
     * automatically switch night mode on and off based on the time.
     */
    public static final int MODE_NIGHT_AUTO = Configuration.UI_MODE_NIGHT_UNDEFINED >> 4;
    
    /** Constant for {@link #setNightMode(int)} and {@link #getNightMode()}:
     * never run in night mode.
     */
    public static final int MODE_NIGHT_NO = Configuration.UI_MODE_NIGHT_NO >> 4;
    
    /** Constant for {@link #setNightMode(int)} and {@link #getNightMode()}:
     * always run in night mode.
     */
    public static final int MODE_NIGHT_YES = Configuration.UI_MODE_NIGHT_YES >> 4;

    private IUiModeManager mService;

    /*package*/ UiModeManager() {
        mService = IUiModeManager.Stub.asInterface(
                ServiceManager.getService(Context.UI_MODE_SERVICE));
    }

    /**
     * Flag for use with {@link #enableCarMode(int)}: go to the car
     * home activity as part of the enable.  Enabling this way ensures
     * a clean transition between the current activity (in non-car-mode) and
     * the car home activity that will serve as home while in car mode.  This
     * will switch to the car home activity even if we are already in car mode.
     */
    public static final int ENABLE_CAR_MODE_GO_CAR_HOME = 0x0001;
    
    /**
     * Force device into car mode, like it had been placed in the car dock.
     * This will cause the device to switch to the car home UI as part of
     * the mode switch.
     * @param flags Must be 0.
     */
    public void enableCarMode(int flags) {
        if (mService != null) {
            try {
                mService.enableCarMode(flags);
            } catch (RemoteException e) {
                Log.e(TAG, "disableCarMode: RemoteException", e);
            }
        }
    }

    /**
     * Flag for use with {@link #disableCarMode(int)}: go to the normal
     * home activity as part of the disable.  Disabling this way ensures
     * a clean transition between the current activity (in car mode) and
     * the original home activity (which was typically last running without
     * being in car mode).
     */
    public static final int DISABLE_CAR_MODE_GO_HOME = 0x0001;
    
    /**
     * Turn off special mode if currently in car mode.
     * @param flags May be 0 or {@link #DISABLE_CAR_MODE_GO_HOME}.
     */
    public void disableCarMode(int flags) {
        if (mService != null) {
            try {
                mService.disableCarMode(flags);
            } catch (RemoteException e) {
                Log.e(TAG, "disableCarMode: RemoteException", e);
            }
        }
    }

    /**
     * Return the current running mode type.  May be one of
     * {@link Configuration#UI_MODE_TYPE_NORMAL Configuration.UI_MODE_TYPE_NORMAL},
     * {@link Configuration#UI_MODE_TYPE_DESK Configuration.UI_MODE_TYPE_DESK}, or
     * {@link Configuration#UI_MODE_TYPE_CAR Configuration.UI_MODE_TYPE_CAR}, or
     * {@link Configuration#UI_MODE_TYPE_TELEVISION Configuration.UI_MODE_TYPE_TV}.
     */
    public int getCurrentModeType() {
        if (mService != null) {
            try {
                return mService.getCurrentModeType();
            } catch (RemoteException e) {
                Log.e(TAG, "getCurrentModeType: RemoteException", e);
            }
        }
        return Configuration.UI_MODE_TYPE_NORMAL;
    }

    /**
     * Sets the night mode.  Changes to the night mode are only effective when
     * the car or desk mode is enabled on a device.
     *
     * <p>The mode can be one of:
     * <ul>
     *   <li><em>{@link #MODE_NIGHT_NO}<em> - sets the device into notnight
     *       mode.</li>
     *   <li><em>{@link #MODE_NIGHT_YES}</em> - sets the device into night mode.
     *   </li>
     *   <li><em>{@link #MODE_NIGHT_AUTO}</em> - automatic night/notnight switching
     *       depending on the location and certain other sensors.</li>
     * </ul>
     */
    public void setNightMode(int mode) {
        if (mService != null) {
            try {
                mService.setNightMode(mode);
            } catch (RemoteException e) {
                Log.e(TAG, "setNightMode: RemoteException", e);
            }
        }
    }

    /**
     * Returns the currently configured night mode.
     *
     * @return {@link #MODE_NIGHT_NO}, {@link #MODE_NIGHT_YES}, or
     *  {@link #MODE_NIGHT_AUTO}.  When an error occurred -1 is returned.
     */
    public int getNightMode() {
        if (mService != null) {
            try {
                return mService.getNightMode();
            } catch (RemoteException e) {
                Log.e(TAG, "getNightMode: RemoteException", e);
            }
        }
        return -1;
    }
}
