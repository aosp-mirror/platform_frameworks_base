/*
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.view;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.IRemoteCallback;
import android.view.IApplicationToken;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindowSession;
import android.view.KeyEvent;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.InputChannel;
import android.view.InputDevice;

/**
 * System private interface to the window manager.
 *
 * {@hide}
 */
interface IWindowManager
{
    /**
     * ===== NOTICE =====
     * The first three methods must remain the first three methods. Scripts
     * and tools rely on their transaction number to work properly.
     */
    // This is used for debugging
    boolean startViewServer(int port);   // Transaction #1
    boolean stopViewServer();            // Transaction #2
    boolean isViewServerRunning();       // Transaction #3

    IWindowSession openSession(in IInputMethodClient client,
            in IInputContext inputContext);
    boolean inputMethodClientHasFocus(IInputMethodClient client);
    
    void getDisplaySize(out Point size);
    void getRealDisplaySize(out Point size);
    int getMaximumSizeDimension();
    void getCurrentSizeRange(out Point smallestSize, out Point largestSize);

    void setForcedDisplaySize(int longDimen, int shortDimen);
    void clearForcedDisplaySize();

    // Is the device configured to have a full system bar for larger screens?
    boolean hasSystemNavBar();

    // These can only be called when holding the MANAGE_APP_TOKENS permission.
    void pauseKeyDispatching(IBinder token);
    void resumeKeyDispatching(IBinder token);
    void setEventDispatching(boolean enabled);
    void addWindowToken(IBinder token, int type);
    void removeWindowToken(IBinder token);
    void addAppToken(int addPos, IApplicationToken token,
            int groupId, int requestedOrientation, boolean fullscreen);
    void setAppGroupId(IBinder token, int groupId);
    void setAppOrientation(IApplicationToken token, int requestedOrientation);
    int getAppOrientation(IApplicationToken token);
    void setFocusedApp(IBinder token, boolean moveFocusNow);
    void prepareAppTransition(int transit, boolean alwaysKeepCurrent);
    int getPendingAppTransition();
    void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim,
            IRemoteCallback startedCallback);
    void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight);
    void overridePendingAppTransitionThumb(in Bitmap srcThumb, int startX, int startY,
            IRemoteCallback startedCallback, boolean delayed);
    void executeAppTransition();
    void setAppStartingWindow(IBinder token, String pkg, int theme,
            in CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes,
            int icon, int windowFlags, IBinder transferFrom, boolean createIfNeeded);
    void setAppWillBeHidden(IBinder token);
    void setAppVisibility(IBinder token, boolean visible);
    void startAppFreezingScreen(IBinder token, int configChanges);
    void stopAppFreezingScreen(IBinder token, boolean force);
    void removeAppToken(IBinder token);
    void moveAppToken(int index, IBinder token);
    void moveAppTokensToTop(in List<IBinder> tokens);
    void moveAppTokensToBottom(in List<IBinder> tokens);

    // Re-evaluate the current orientation from the caller's state.
    // If there is a change, the new Configuration is returned and the
    // caller must call setNewConfiguration() sometime later.
    Configuration updateOrientationFromAppTokens(in Configuration currentConfig,
            IBinder freezeThisOneIfNeeded);
    void setNewConfiguration(in Configuration config);
    
    // these require DISABLE_KEYGUARD permission
    void disableKeyguard(IBinder token, String tag);
    void reenableKeyguard(IBinder token);
    void exitKeyguardSecurely(IOnKeyguardExitResult callback);
    boolean isKeyguardLocked();
    boolean isKeyguardSecure();
    boolean inKeyguardRestrictedInputMode();
    void dismissKeyguard();

    void closeSystemDialogs(String reason);
    
    // These can only be called with the SET_ANIMATON_SCALE permission.
    float getAnimationScale(int which);
    float[] getAnimationScales();
    void setAnimationScale(int which, float scale);
    void setAnimationScales(in float[] scales);
    
    // For testing
    void setInTouchMode(boolean showFocus);

    // For StrictMode flashing a red border on violations from the UI
    // thread.  The uid/pid is implicit from the Binder call, and the Window
    // Manager uses that to determine whether or not the red border should
    // actually be shown.  (it will be ignored that pid doesn't have windows
    // on screen)
    void showStrictModeViolation(boolean on);

    // Proxy to set the system property for whether the flashing
    // should be enabled.  The 'enabled' value is null or blank for
    // the system default (differs per build variant) or any valid
    // boolean string as parsed by SystemProperties.getBoolean().
    void setStrictModeVisualIndicatorPreference(String enabled);

    // These can only be called with the SET_ORIENTATION permission.
    /**
     * Update the current screen rotation based on the current state of
     * the world.
     * @param alwaysSendConfiguration Flag to force a new configuration to
     * be evaluated.  This can be used when there are other parameters in
     * configuration that are changing.
     * @param forceRelayout If true, the window manager will always do a relayout
     * of its windows even if the rotation hasn't changed.
     */
    void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout);

    /**
     * Retrieve the current screen orientation, constants as per
     * {@link android.view.Surface}.
     */
    int getRotation();
    
    /**
     * Watch the rotation of the screen.  Returns the current rotation,
     * calls back when it changes.
     */
    int watchRotation(IRotationWatcher watcher);

    /**
     * Determine the preferred edge of the screen to pin the compact options menu against.
     * @return a Gravity value for the options menu panel
     * @hide
     */
    int getPreferredOptionsPanelGravity();

	/**
	 * Lock the device orientation to the specified rotation, or to the
	 * current rotation if -1.  Sensor input will be ignored until
	 * thawRotation() is called.
	 * @hide
	 */
	void freezeRotation(int rotation);

	/**
	 * Release the orientation lock imposed by freezeRotation().
	 * @hide
	 */
	void thawRotation();

	/**
	 * Create a screenshot of the applications currently displayed.
	 */
	Bitmap screenshotApplications(IBinder appToken, int maxWidth, int maxHeight);

    /**
     * Called by the status bar to notify Views of changes to System UI visiblity.
     */
    void statusBarVisibilityChanged(int visibility);

    /**
     * Block until the given window has been drawn to the screen.
     */
    void waitForWindowDrawn(IBinder token, in IRemoteCallback callback);

    /**
     * Device has a software navigation bar (separate from the status bar).
     */
    boolean hasNavigationBar();

    /**
     * Lock the device immediately.
     */
    void lockNow();
}
