/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_OPEN;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.IntDef;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.power.V1_0.PowerHint;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayCutout;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowOrientationListener;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines the mapping between orientation and rotation of a display.
 * Non-public methods are assumed to run inside WM lock.
 */
public class DisplayRotation {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayRotation" : TAG_WM;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mDisplayPolicy;
    private final DisplayWindowSettings mDisplayWindowSettings;
    private final Context mContext;
    private final Object mLock;

    public final boolean isDefaultDisplay;
    private final boolean mSupportAutoRotation;
    private final int mLidOpenRotation;
    private final int mCarDockRotation;
    private final int mDeskDockRotation;
    private final int mUndockedHdmiRotation;

    private final float mCloseToSquareMaxAspectRatio;

    private OrientationListener mOrientationListener;
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private SettingsObserver mSettingsObserver;

    private int mCurrentAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    @VisibleForTesting
    int mLandscapeRotation;  // default landscape
    @VisibleForTesting
    int mSeascapeRotation;   // "other" landscape, 180 degrees from mLandscapeRotation
    @VisibleForTesting
    int mPortraitRotation;   // default portrait
    @VisibleForTesting
    int mUpsideDownRotation; // "other" portrait

    // Behavior of rotation suggestions. (See Settings.Secure.SHOW_ROTATION_SUGGESTION)
    private int mShowRotationSuggestions;

    private int mAllowAllRotations = -1;
    private int mUserRotationMode = WindowManagerPolicy.USER_ROTATION_FREE;
    private int mUserRotation = Surface.ROTATION_0;

    /**
     * Flag that indicates this is a display that may run better when fixed to user rotation.
     */
    private boolean mDefaultFixedToUserRotation;

    /**
     * No overridden behavior is provided in terms of fixing rotation to user rotation. Use other
     * flags to derive the default behavior, such as {@link WindowManagerService#mIsPc} and
     * {@link WindowManagerService#mForceDesktopModeOnExternalDisplays}.
     */
    static final int FIXED_TO_USER_ROTATION_DEFAULT = 0;
    /**
     * Don't fix display rotation to {@link #mUserRotation} only. Always allow other factors to play
     * a role in deciding display rotation.
     */
    static final int FIXED_TO_USER_ROTATION_DISABLED = 1;
    /**
     * Only use {@link #mUserRotation} as the display rotation.
     */
    static final int FIXED_TO_USER_ROTATION_ENABLED = 2;
    @IntDef({ FIXED_TO_USER_ROTATION_DEFAULT, FIXED_TO_USER_ROTATION_DISABLED,
            FIXED_TO_USER_ROTATION_ENABLED })
    @Retention(RetentionPolicy.SOURCE)
    @interface FixedToUserRotation {}

    /**
     * A flag to indicate if the display rotation should be fixed to user specified rotation
     * regardless of all other states (including app requrested orientation). {@code true} the
     * display rotation should be fixed to user specified rotation, {@code false} otherwise.
     */
    private int mFixedToUserRotation = FIXED_TO_USER_ROTATION_DEFAULT;

    private int mDemoHdmiRotation;
    private int mDemoRotation;
    private boolean mDemoHdmiRotationLock;
    private boolean mDemoRotationLock;

    DisplayRotation(WindowManagerService service, DisplayContent displayContent) {
        this(service, displayContent, displayContent.getDisplayPolicy(),
                service.mDisplayWindowSettings, service.mContext, service.getWindowManagerLock());
    }

    @VisibleForTesting
    DisplayRotation(WindowManagerService service, DisplayContent displayContent,
            DisplayPolicy displayPolicy, DisplayWindowSettings displayWindowSettings,
            Context context, Object lock) {
        mService = service;
        mDisplayContent = displayContent;
        mDisplayPolicy = displayPolicy;
        mDisplayWindowSettings = displayWindowSettings;
        mContext = context;
        mLock = lock;
        isDefaultDisplay = displayContent.isDefaultDisplay;

        mSupportAutoRotation = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_supportAutoRotation);
        mLidOpenRotation = readRotation(
                com.android.internal.R.integer.config_lidOpenRotation);
        mCarDockRotation = readRotation(
                com.android.internal.R.integer.config_carDockRotation);
        mDeskDockRotation = readRotation(
                com.android.internal.R.integer.config_deskDockRotation);
        mUndockedHdmiRotation = readRotation(
                com.android.internal.R.integer.config_undockedHdmiRotation);

        mCloseToSquareMaxAspectRatio = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_closeToSquareDisplayMaxAspectRatio);

        if (isDefaultDisplay) {
            final Handler uiHandler = UiThread.getHandler();
            mOrientationListener = new OrientationListener(mContext, uiHandler);
            mOrientationListener.setCurrentRotation(displayContent.getRotation());
            mSettingsObserver = new SettingsObserver(uiHandler);
            mSettingsObserver.observe();
        }
    }

    private int readRotation(int resID) {
        try {
            final int rotation = mContext.getResources().getInteger(resID);
            switch (rotation) {
                case 0:
                    return Surface.ROTATION_0;
                case 90:
                    return Surface.ROTATION_90;
                case 180:
                    return Surface.ROTATION_180;
                case 270:
                    return Surface.ROTATION_270;
            }
        } catch (Resources.NotFoundException e) {
            // fall through
        }
        return -1;
    }

    void configure(int width, int height, int shortSizeDp, int longSizeDp) {
        final Resources res = mContext.getResources();
        if (width > height) {
            mLandscapeRotation = Surface.ROTATION_0;
            mSeascapeRotation = Surface.ROTATION_180;
            if (res.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)) {
                mPortraitRotation = Surface.ROTATION_90;
                mUpsideDownRotation = Surface.ROTATION_270;
            } else {
                mPortraitRotation = Surface.ROTATION_270;
                mUpsideDownRotation = Surface.ROTATION_90;
            }
        } else {
            mPortraitRotation = Surface.ROTATION_0;
            mUpsideDownRotation = Surface.ROTATION_180;
            if (res.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)) {
                mLandscapeRotation = Surface.ROTATION_270;
                mSeascapeRotation = Surface.ROTATION_90;
            } else {
                mLandscapeRotation = Surface.ROTATION_90;
                mSeascapeRotation = Surface.ROTATION_270;
            }
        }

        // For demo purposes, allow the rotation of the HDMI display to be controlled.
        // By default, HDMI locks rotation to landscape.
        if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
            mDemoHdmiRotation = mPortraitRotation;
        } else {
            mDemoHdmiRotation = mLandscapeRotation;
        }
        mDemoHdmiRotationLock = SystemProperties.getBoolean("persist.demo.hdmirotationlock", false);

        // For demo purposes, allow the rotation of the remote display to be controlled.
        // By default, remote display locks rotation to landscape.
        if ("portrait".equals(SystemProperties.get("persist.demo.remoterotation"))) {
            mDemoRotation = mPortraitRotation;
        } else {
            mDemoRotation = mLandscapeRotation;
        }
        mDemoRotationLock = SystemProperties.getBoolean("persist.demo.rotationlock", false);

        // Only force the default orientation if the screen is xlarge, at least 960dp x 720dp, per
        // http://developer.android.com/guide/practices/screens_support.html#range
        // For car, ignore the dp limitation. It's physically impossible to rotate the car's screen
        // so if the orientation is forced, we need to respect that no matter what.
        final boolean isCar = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
        // For TV, it's usually 960dp x 540dp, ignore the size limitation.
        // so if the orientation is forced, we need to respect that no matter what.
        final boolean isTv = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);
        final boolean isCloseToSquare =
                isNonDecorDisplayCloseToSquare(Surface.ROTATION_0, width, height);
        final boolean forceDesktopMode =
                mService.mForceDesktopModeOnExternalDisplays && !isDefaultDisplay;
        mDefaultFixedToUserRotation =
                (isCar || isTv || mService.mIsPc || forceDesktopMode || isCloseToSquare)
                // For debug purposes the next line turns this feature off with:
                // $ adb shell setprop config.override_forced_orient true
                // $ adb shell wm size reset
                && !"true".equals(SystemProperties.get("config.override_forced_orient"));
    }

    private boolean isNonDecorDisplayCloseToSquare(int rotation, int width, int height) {
        final DisplayCutout displayCutout =
                mDisplayContent.calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
        final int uiMode = mService.mPolicy.getUiMode();
        final int w = mDisplayPolicy.getNonDecorDisplayWidth(
                width, height, rotation, uiMode, displayCutout);
        final int h = mDisplayPolicy.getNonDecorDisplayHeight(
                width, height, rotation, uiMode, displayCutout);
        final float aspectRatio = Math.max(w, h) / (float) Math.min(w, h);
        return aspectRatio <= mCloseToSquareMaxAspectRatio;
    }

    void setRotation(int rotation) {
        if (mOrientationListener != null) {
            mOrientationListener.setCurrentRotation(rotation);
        }
    }

    void setCurrentOrientation(int newOrientation) {
        if (newOrientation != mCurrentAppOrientation) {
            mCurrentAppOrientation = newOrientation;
            if (isDefaultDisplay) {
                updateOrientationListenerLw();
            }
        }
    }

    void restoreSettings(int userRotationMode, int userRotation,
            @FixedToUserRotation int fixedToUserRotation) {
        mFixedToUserRotation = fixedToUserRotation;

        // We will retrieve user rotation and user rotation mode from settings for default display.
        if (isDefaultDisplay) {
            return;
        }
        if (userRotationMode != WindowManagerPolicy.USER_ROTATION_FREE
                && userRotationMode != WindowManagerPolicy.USER_ROTATION_LOCKED) {
            Slog.w(TAG, "Trying to restore an invalid user rotation mode " + userRotationMode
                    + " for " + mDisplayContent);
            userRotationMode = WindowManagerPolicy.USER_ROTATION_FREE;
        }
        if (userRotation < Surface.ROTATION_0 || userRotation > Surface.ROTATION_270) {
            Slog.w(TAG, "Trying to restore an invalid user rotation " + userRotation
                    + " for " + mDisplayContent);
            userRotation = Surface.ROTATION_0;
        }
        mUserRotationMode = userRotationMode;
        mUserRotation = userRotation;
    }

    void setFixedToUserRotation(@FixedToUserRotation int fixedToUserRotation) {
        if (mFixedToUserRotation == fixedToUserRotation) {
            return;
        }

        mFixedToUserRotation = fixedToUserRotation;
        mDisplayWindowSettings.setFixedToUserRotation(mDisplayContent, fixedToUserRotation);
        mService.updateRotation(true /* alwaysSendConfiguration */,
                false /* forceRelayout */);
    }

    private void setUserRotation(int userRotationMode, int userRotation) {
        if (isDefaultDisplay) {
            // We'll be notified via settings listener, so we don't need to update internal values.
            final ContentResolver res = mContext.getContentResolver();
            final int accelerometerRotation =
                    userRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED ? 0 : 1;
            Settings.System.putIntForUser(res, Settings.System.ACCELEROMETER_ROTATION,
                    accelerometerRotation, UserHandle.USER_CURRENT);
            Settings.System.putIntForUser(res, Settings.System.USER_ROTATION, userRotation,
                    UserHandle.USER_CURRENT);
            return;
        }

        boolean changed = false;
        if (mUserRotationMode != userRotationMode) {
            mUserRotationMode = userRotationMode;
            changed = true;
        }
        if (mUserRotation != userRotation) {
            mUserRotation = userRotation;
            changed = true;
        }
        mDisplayWindowSettings.setUserRotation(mDisplayContent, userRotationMode,
                userRotation);
        if (changed) {
            mService.updateRotation(true /* alwaysSendConfiguration */,
                    false /* forceRelayout */);
        }
    }

    void freezeRotation(int rotation) {
        rotation = (rotation == -1) ? mDisplayContent.getRotation() : rotation;
        setUserRotation(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation);
    }

    void thawRotation() {
        setUserRotation(WindowManagerPolicy.USER_ROTATION_FREE, mUserRotation);
    }

    boolean isRotationFrozen() {
        if (!isDefaultDisplay) {
            return mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED;
        }

        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) == 0;
    }

    boolean isFixedToUserRotation() {
        switch (mFixedToUserRotation) {
            case FIXED_TO_USER_ROTATION_DISABLED:
                return false;
            case FIXED_TO_USER_ROTATION_ENABLED:
                return true;
            default:
                return mDefaultFixedToUserRotation;
        }
    }

    /**
     * Returns {@code true} if this display rotation takes app requested orientation into
     * consideration; {@code false} otherwise. For the time being the only case where this is {@code
     * false} is when {@link #isFixedToUserRotation()} is {@code true}.
     */
    boolean respectAppRequestedOrientation() {
        return !isFixedToUserRotation();
    }

    public int getLandscapeRotation() {
        return mLandscapeRotation;
    }

    public int getSeascapeRotation() {
        return mSeascapeRotation;
    }

    public int getPortraitRotation() {
        return mPortraitRotation;
    }

    public int getUpsideDownRotation() {
        return mUpsideDownRotation;
    }

    public int getCurrentAppOrientation() {
        return mCurrentAppOrientation;
    }

    public DisplayPolicy getDisplayPolicy() {
        return mDisplayPolicy;
    }

    public WindowOrientationListener getOrientationListener() {
        return mOrientationListener;
    }

    public int getUserRotation() {
        return mUserRotation;
    }

    public int getUserRotationMode() {
        return mUserRotationMode;
    }

    public void updateOrientationListener() {
        synchronized (mLock) {
            updateOrientationListenerLw();
        }
    }

    /**
     * Various use cases for invoking this function:
     * <li>Screen turning off, should always disable listeners if already enabled.</li>
     * <li>Screen turned on and current app has sensor based orientation, enable listeners
     *     if not already enabled.</li>
     * <li>Screen turned on and current app does not have sensor orientation, disable listeners
     *     if already enabled.</li>
     * <li>Screen turning on and current app has sensor based orientation, enable listeners
     *     if needed.</li>
     * <li>screen turning on and current app has nosensor based orientation, do nothing.</li>
     */
    private void updateOrientationListenerLw() {
        if (mOrientationListener == null || !mOrientationListener.canDetectOrientation()) {
            // If sensor is turned off or nonexistent for some reason.
            return;
        }

        final boolean screenOnEarly = mDisplayPolicy.isScreenOnEarly();
        final boolean awake = mDisplayPolicy.isAwake();
        final boolean keyguardDrawComplete = mDisplayPolicy.isKeyguardDrawComplete();
        final boolean windowManagerDrawComplete = mDisplayPolicy.isWindowManagerDrawComplete();

        // Could have been invoked due to screen turning on or off or
        // change of the currently visible window's orientation.
        if (DEBUG_ORIENTATION) Slog.v(TAG, "screenOnEarly=" + screenOnEarly
                + ", awake=" + awake + ", currentAppOrientation=" + mCurrentAppOrientation
                + ", orientationSensorEnabled=" + mOrientationListener.mEnabled
                + ", keyguardDrawComplete=" + keyguardDrawComplete
                + ", windowManagerDrawComplete=" + windowManagerDrawComplete);

        boolean disable = true;
        // Note: We postpone the rotating of the screen until the keyguard as well as the
        // window manager have reported a draw complete or the keyguard is going away in dismiss
        // mode.
        if (screenOnEarly && awake && ((keyguardDrawComplete && windowManagerDrawComplete))) {
            if (needSensorRunning()) {
                disable = false;
                // Enable listener if not already enabled.
                if (!mOrientationListener.mEnabled) {
                    // Don't clear the current sensor orientation if the keyguard is going away in
                    // dismiss mode. This allows window manager to use the last sensor reading to
                    // determine the orientation vs. falling back to the last known orientation if
                    // the sensor reading was cleared which can cause it to relaunch the app that
                    // will show in the wrong orientation first before correcting leading to app
                    // launch delays.
                    mOrientationListener.enable(true /* clearCurrentRotation */);
                }
            }
        }
        // Check if sensors need to be disabled.
        if (disable && mOrientationListener.mEnabled) {
            mOrientationListener.disable();
        }
    }

    /**
     * We always let the sensor be switched on by default except when
     * the user has explicitly disabled sensor based rotation or when the
     * screen is switched off.
     */
    private boolean needSensorRunning() {
        if (isFixedToUserRotation()) {
            // We are sure we only respect user rotation settings, so we are sure we will not
            // support sensor rotation.
            return false;
        }

        if (mSupportAutoRotation) {
            if (mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                // If the application has explicitly requested to follow the
                // orientation, then we need to turn the sensor on.
                return true;
            }
        }

        final int dockMode = mDisplayPolicy.getDockMode();
        if ((mDisplayPolicy.isCarDockEnablesAccelerometer()
                && dockMode == Intent.EXTRA_DOCK_STATE_CAR)
                || (mDisplayPolicy.isDeskDockEnablesAccelerometer()
                        && (dockMode == Intent.EXTRA_DOCK_STATE_DESK
                                || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK
                                || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK))) {
            // Enable accelerometer if we are docked in a dock that enables accelerometer
            // orientation management.
            return true;
        }

        if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
            // If the setting for using the sensor by default is enabled, then
            // we will always leave it on.  Note that the user could go to
            // a window that forces an orientation that does not use the
            // sensor and in theory we could turn it off... however, when next
            // turning it on we won't have a good value for the current
            // orientation for a little bit, which can cause orientation
            // changes to lag, so we'd like to keep it always on.  (It will
            // still be turned off when the screen is off.)

            // When locked we can provide rotation suggestions users can approve to change the
            // current screen rotation. To do this the sensor needs to be running.
            return mSupportAutoRotation &&
                    mShowRotationSuggestions == Settings.Secure.SHOW_ROTATION_SUGGESTIONS_ENABLED;
        }
        return mSupportAutoRotation;
    }

    /**
     * Given an orientation constant, returns the appropriate surface rotation,
     * taking into account sensors, docking mode, rotation lock, and other factors.
     *
     * @param orientation An orientation constant, such as
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE}.
     * @param lastRotation The most recently used rotation.
     * @return The surface rotation to use.
     */
    int rotationForOrientation(int orientation, int lastRotation) {
        if (DEBUG_ORIENTATION) {
            Slog.v(TAG, "rotationForOrientation(orient="
                        + orientation + ", last=" + lastRotation
                        + "); user=" + mUserRotation + " "
                        + (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED
                            ? "USER_ROTATION_LOCKED" : "")
                        );
        }

        if (isFixedToUserRotation()) {
            return mUserRotation;
        }

        int sensorRotation = mOrientationListener != null
                ? mOrientationListener.getProposedRotation() // may be -1
                : -1;
        if (sensorRotation < 0) {
            sensorRotation = lastRotation;
        }

        final int lidState = mDisplayPolicy.getLidState();
        final int dockMode = mDisplayPolicy.getDockMode();
        final boolean hdmiPlugged = mDisplayPolicy.isHdmiPlugged();
        final boolean carDockEnablesAccelerometer =
                mDisplayPolicy.isCarDockEnablesAccelerometer();
        final boolean deskDockEnablesAccelerometer =
                mDisplayPolicy.isDeskDockEnablesAccelerometer();

        final int preferredRotation;
        if (!isDefaultDisplay) {
            // For secondary displays we ignore things like displays sensors, docking mode and
            // rotation lock, and always prefer user rotation.
            preferredRotation = mUserRotation;
        } else if (lidState == LID_OPEN && mLidOpenRotation >= 0) {
            // Ignore sensor when lid switch is open and rotation is forced.
            preferredRotation = mLidOpenRotation;
        } else if (dockMode == Intent.EXTRA_DOCK_STATE_CAR
                && (carDockEnablesAccelerometer || mCarDockRotation >= 0)) {
            // Ignore sensor when in car dock unless explicitly enabled.
            // This case can override the behavior of NOSENSOR, and can also
            // enable 180 degree rotation while docked.
            preferredRotation = carDockEnablesAccelerometer ? sensorRotation : mCarDockRotation;
        } else if ((dockMode == Intent.EXTRA_DOCK_STATE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK)
                && (deskDockEnablesAccelerometer || mDeskDockRotation >= 0)) {
            // Ignore sensor when in desk dock unless explicitly enabled.
            // This case can override the behavior of NOSENSOR, and can also
            // enable 180 degree rotation while docked.
            preferredRotation = deskDockEnablesAccelerometer ? sensorRotation : mDeskDockRotation;
        } else if (hdmiPlugged && mDemoHdmiRotationLock) {
            // Ignore sensor when plugged into HDMI when demo HDMI rotation lock enabled.
            // Note that the dock orientation overrides the HDMI orientation.
            preferredRotation = mDemoHdmiRotation;
        } else if (hdmiPlugged && dockMode == Intent.EXTRA_DOCK_STATE_UNDOCKED
                && mUndockedHdmiRotation >= 0) {
            // Ignore sensor when plugged into HDMI and an undocked orientation has
            // been specified in the configuration (only for legacy devices without
            // full multi-display support).
            // Note that the dock orientation overrides the HDMI orientation.
            preferredRotation = mUndockedHdmiRotation;
        } else if (mDemoRotationLock) {
            // Ignore sensor when demo rotation lock is enabled.
            // Note that the dock orientation and HDMI rotation lock override this.
            preferredRotation = mDemoRotation;
        } else if (mDisplayPolicy.isPersistentVrModeEnabled()) {
            // While in VR, apps always prefer a portrait rotation. This does not change
            // any apps that explicitly set landscape, but does cause sensors be ignored,
            // and ignored any orientation lock that the user has set (this conditional
            // should remain above the ORIENTATION_LOCKED conditional below).
            preferredRotation = mPortraitRotation;
        } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            // Application just wants to remain locked in the last rotation.
            preferredRotation = lastRotation;
        } else if (!mSupportAutoRotation) {
            // If we don't support auto-rotation then bail out here and ignore
            // the sensor and any rotation lock settings.
            preferredRotation = -1;
        } else if ((mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                        && (orientation == ActivityInfo.SCREEN_ORIENTATION_USER
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER))
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
            // Otherwise, use sensor only if requested by the application or enabled
            // by default for USER or UNSPECIFIED modes.  Does not apply to NOSENSOR.
            if (mAllowAllRotations < 0) {
                // Can't read this during init() because the context doesn't
                // have display metrics at that time so we cannot determine
                // tablet vs. phone then.
                mAllowAllRotations = mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_allowAllRotations) ? 1 : 0;
            }
            if (sensorRotation != Surface.ROTATION_180
                    || mAllowAllRotations == 1
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
                preferredRotation = sensorRotation;
            } else {
                preferredRotation = lastRotation;
            }
        } else if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED
                && orientation != ActivityInfo.SCREEN_ORIENTATION_NOSENSOR) {
            // Apply rotation lock.  Does not apply to NOSENSOR.
            // The idea is that the user rotation expresses a weak preference for the direction
            // of gravity and as NOSENSOR is never affected by gravity, then neither should
            // NOSENSOR be affected by rotation lock (although it will be affected by docks).
            preferredRotation = mUserRotation;
        } else {
            // No overriding preference.
            // We will do exactly what the application asked us to do.
            preferredRotation = -1;
        }

        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                // Return portrait unless overridden.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                return mPortraitRotation;

            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                // Return landscape unless overridden.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                return mLandscapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                // Return reverse portrait unless overridden.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                return mUpsideDownRotation;

            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                // Return seascape unless overridden.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                return mSeascapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                // Return either landscape rotation.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                if (isLandscapeOrSeascape(lastRotation)) {
                    return lastRotation;
                }
                return mLandscapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // Return either portrait rotation.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                if (isAnyPortrait(lastRotation)) {
                    return lastRotation;
                }
                return mPortraitRotation;

            default:
                // For USER, UNSPECIFIED, NOSENSOR, SENSOR and FULL_SENSOR,
                // just return the preferred orientation we already calculated.
                if (preferredRotation >= 0) {
                    return preferredRotation;
                }
                return Surface.ROTATION_0;
        }
    }

    private boolean isLandscapeOrSeascape(int rotation) {
        return rotation == mLandscapeRotation || rotation == mSeascapeRotation;
    }

    private boolean isAnyPortrait(int rotation) {
        return rotation == mPortraitRotation || rotation == mUpsideDownRotation;
    }

    private boolean isValidRotationChoice(final int preferredRotation) {
        // Determine if the given app orientation is compatible with the provided rotation choice.
        switch (mCurrentAppOrientation) {
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                // Works with any of the 4 rotations.
                return preferredRotation >= 0;

            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // It's possible for the user pref to be set at 180 because of FULL_USER. This would
                // make switching to USER_PORTRAIT appear at 180. Provide choice to back to portrait
                // but never to go to 180.
                return preferredRotation == mPortraitRotation;

            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                // Works landscape or seascape.
                return isLandscapeOrSeascape(preferredRotation);

            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
                // Works with any rotation except upside down.
                return (preferredRotation >= 0) && (preferredRotation != mUpsideDownRotation);
        }

        return false;
    }

    private boolean isRotationChoicePossible(int orientation) {
        // Rotation choice is only shown when the user is in locked mode.
        if (mUserRotationMode != WindowManagerPolicy.USER_ROTATION_LOCKED) return false;

        // We should only enable rotation choice if the rotation isn't forced by the lid, dock,
        // demo, hdmi, vr, etc mode.

        // Determine if the rotation is currently forced.
        if (isFixedToUserRotation()) {
            return false; // Rotation is forced to user settings.
        }

        final int lidState = mDisplayPolicy.getLidState();
        if (lidState == LID_OPEN && mLidOpenRotation >= 0) {
            return false; // Rotation is forced mLidOpenRotation.
        }

        final int dockMode = mDisplayPolicy.getDockMode();
        final boolean carDockEnablesAccelerometer = false;
        if (dockMode == Intent.EXTRA_DOCK_STATE_CAR && !carDockEnablesAccelerometer) {
            return false; // Rotation forced to mCarDockRotation.
        }

        final boolean deskDockEnablesAccelerometer =
                mDisplayPolicy.isDeskDockEnablesAccelerometer();
        if ((dockMode == Intent.EXTRA_DOCK_STATE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK)
                && !deskDockEnablesAccelerometer) {
            return false; // Rotation forced to mDeskDockRotation.
        }

        final boolean hdmiPlugged = mDisplayPolicy.isHdmiPlugged();
        if (hdmiPlugged && mDemoHdmiRotationLock) {
            return false; // Rotation forced to mDemoHdmiRotation.

        } else if (hdmiPlugged && dockMode == Intent.EXTRA_DOCK_STATE_UNDOCKED
                && mUndockedHdmiRotation >= 0) {
            return false; // Rotation forced to mUndockedHdmiRotation.

        } else if (mDemoRotationLock) {
            return false; // Rotation forced to mDemoRotation.

        } else if (mDisplayPolicy.isPersistentVrModeEnabled()) {
            return false; // Rotation forced to mPortraitRotation.

        } else if (!mSupportAutoRotation) {
            return false;
        }

        // Ensure that some rotation choice is possible for the given orientation.
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // NOSENSOR description is ambiguous, in reality WM ignores user choice.
                return true;
        }

        // Rotation is forced, should be controlled by system.
        return false;
    }

    /** Notify the StatusBar that system rotation suggestion has changed. */
    private void sendProposedRotationChangeToStatusBarInternal(int rotation, boolean isValid) {
        if (mStatusBarManagerInternal == null) {
            mStatusBarManagerInternal = LocalServices.getService(StatusBarManagerInternal.class);
        }
        if (mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.onProposedRotationChanged(rotation, isValid);
        }
    }

    private static String allowAllRotationsToString(int allowAll) {
        switch (allowAll) {
            case -1:
                return "unknown";
            case 0:
                return "false";
            case 1:
                return "true";
            default:
                return Integer.toString(allowAll);
        }
    }

    public void onUserSwitch() {
        if (mSettingsObserver != null) {
            mSettingsObserver.onChange(false);
        }
    }

    /** Return whether the rotation settings has changed. */
    private boolean updateSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        boolean shouldUpdateRotation = false;

        synchronized (mLock) {
            boolean shouldUpdateOrientationListener = false;

            // Configure rotation suggestions.
            final int showRotationSuggestions =
                    ActivityManager.isLowRamDeviceStatic()
                            ? Settings.Secure.SHOW_ROTATION_SUGGESTIONS_DISABLED
                            : Settings.Secure.getIntForUser(resolver,
                            Settings.Secure.SHOW_ROTATION_SUGGESTIONS,
                            Settings.Secure.SHOW_ROTATION_SUGGESTIONS_DEFAULT,
                            UserHandle.USER_CURRENT);
            if (mShowRotationSuggestions != showRotationSuggestions) {
                mShowRotationSuggestions = showRotationSuggestions;
                shouldUpdateOrientationListener = true;
            }

            // Configure rotation lock.
            final int userRotation = Settings.System.getIntForUser(resolver,
                    Settings.System.USER_ROTATION, Surface.ROTATION_0,
                    UserHandle.USER_CURRENT);
            if (mUserRotation != userRotation) {
                mUserRotation = userRotation;
                shouldUpdateRotation = true;
            }

            final int userRotationMode = Settings.System.getIntForUser(resolver,
                    Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) != 0
                            ? WindowManagerPolicy.USER_ROTATION_FREE
                            : WindowManagerPolicy.USER_ROTATION_LOCKED;
            if (mUserRotationMode != userRotationMode) {
                mUserRotationMode = userRotationMode;
                shouldUpdateOrientationListener = true;
                shouldUpdateRotation = true;
            }

            if (shouldUpdateOrientationListener) {
                updateOrientationListenerLw(); // Enable or disable the orientation listener.
            }
        }

        return shouldUpdateRotation;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayRotation");
        pw.println(prefix + "  mCurrentAppOrientation="
                + ActivityInfo.screenOrientationToString(mCurrentAppOrientation));
        pw.print(prefix + "  mLandscapeRotation=" + Surface.rotationToString(mLandscapeRotation));
        pw.println(" mSeascapeRotation=" + Surface.rotationToString(mSeascapeRotation));
        pw.print(prefix + "  mPortraitRotation=" + Surface.rotationToString(mPortraitRotation));
        pw.println(" mUpsideDownRotation=" + Surface.rotationToString(mUpsideDownRotation));

        pw.println(prefix + "  mSupportAutoRotation=" + mSupportAutoRotation);
        if (mOrientationListener != null) {
            mOrientationListener.dump(pw, prefix + "  ");
        }
        pw.println();

        pw.print(prefix + "  mCarDockRotation=" + Surface.rotationToString(mCarDockRotation));
        pw.println(" mDeskDockRotation=" + Surface.rotationToString(mDeskDockRotation));
        pw.print(prefix + "  mUserRotationMode="
                + WindowManagerPolicy.userRotationModeToString(mUserRotationMode));
        pw.print(" mUserRotation=" + Surface.rotationToString(mUserRotation));
        pw.println(" mAllowAllRotations=" + allowAllRotationsToString(mAllowAllRotations));

        pw.print(prefix + "  mDemoHdmiRotation=" + Surface.rotationToString(mDemoHdmiRotation));
        pw.print(" mDemoHdmiRotationLock=" + mDemoHdmiRotationLock);
        pw.println(" mUndockedHdmiRotation=" + Surface.rotationToString(mUndockedHdmiRotation));
        pw.println(prefix + "  mLidOpenRotation=" + Surface.rotationToString(mLidOpenRotation));
        pw.println(prefix + "  mFixedToUserRotation=" + isFixedToUserRotation());
    }

    private class OrientationListener extends WindowOrientationListener {
        final SparseArray<Runnable> mRunnableCache = new SparseArray<>(5);
        boolean mEnabled;

        OrientationListener(Context context, Handler handler) {
            super(context, handler);
        }

        private class UpdateRunnable implements Runnable {
            final int mRotation;

            UpdateRunnable(int rotation) {
                mRotation = rotation;
            }

            @Override
            public void run() {
                // Send interaction hint to improve redraw performance.
                mService.mPowerManagerInternal.powerHint(PowerHint.INTERACTION, 0);
                if (isRotationChoicePossible(mCurrentAppOrientation)) {
                    final boolean isValid = isValidRotationChoice(mRotation);
                    sendProposedRotationChangeToStatusBarInternal(mRotation, isValid);
                } else {
                    mService.updateRotation(false /* alwaysSendConfiguration */,
                            false /* forceRelayout */);
                }
            }
        }

        @Override
        public void onProposedRotationChanged(int rotation) {
            if (DEBUG_ORIENTATION) Slog.v(TAG, "onProposedRotationChanged, rotation=" + rotation);
            Runnable r = mRunnableCache.get(rotation, null);
            if (r == null) {
                r = new UpdateRunnable(rotation);
                mRunnableCache.put(rotation, r);
            }
            getHandler().post(r);
        }

        @Override
        public void enable(boolean clearCurrentRotation) {
            super.enable(clearCurrentRotation);
            mEnabled = true;
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Enabling listeners");
        }

        @Override
        public void disable() {
            super.disable();
            mEnabled = false;
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Disabling listeners");
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SHOW_ROTATION_SUGGESTIONS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACCELEROMETER_ROTATION), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.USER_ROTATION), false, this,
                    UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            if (updateSettings()) {
                mService.updateRotation(true /* alwaysSendConfiguration */,
                        false /* forceRelayout */);
            }
        }
    }

    @VisibleForTesting
    interface ContentObserverRegister {
        void registerContentObserver(Uri uri, boolean notifyForDescendants,
                ContentObserver observer, @UserIdInt int userHandle);
    }
}
