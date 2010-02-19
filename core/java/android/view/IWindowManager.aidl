/* //device/java/android/android/view/IWindowManager.aidl
**
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

import android.content.res.Configuration;
import android.view.IApplicationToken;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindowSession;
import android.view.KeyEvent;
import android.view.MotionEvent;

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
    
    // These can only be called when injecting events to your own window,
    // or by holding the INJECT_EVENTS permission.
    boolean injectKeyEvent(in KeyEvent ev, boolean sync);
    boolean injectPointerEvent(in MotionEvent ev, boolean sync);
    boolean injectTrackballEvent(in MotionEvent ev, boolean sync);
    
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
    void prepareAppTransition(int transit);
    int getPendingAppTransition();
    void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim);
    void executeAppTransition();
    void setAppStartingWindow(IBinder token, String pkg, int theme,
            CharSequence nonLocalizedLabel, int labelRes,
            int icon, IBinder transferFrom, boolean createIfNeeded);
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
    boolean inKeyguardRestrictedInputMode();

    void closeSystemDialogs(String reason);
    
    // These can only be called with the SET_ANIMATON_SCALE permission.
    float getAnimationScale(int which);
    float[] getAnimationScales();
    void setAnimationScale(int which, float scale);
    void setAnimationScales(in float[] scales);
    
    // These require the READ_INPUT_STATE permission.
    int getSwitchState(int sw);
    int getSwitchStateForDevice(int devid, int sw);
    int getScancodeState(int sw);
    int getScancodeStateForDevice(int devid, int sw);
    int getTrackballScancodeState(int sw);
    int getDPadScancodeState(int sw);
    int getKeycodeState(int sw);
    int getKeycodeStateForDevice(int devid, int sw);
    int getTrackballKeycodeState(int sw);
    int getDPadKeycodeState(int sw);
    
    // Report whether the hardware supports the given keys; returns true if successful
    boolean hasKeys(in int[] keycodes, inout boolean[] keyExists);
    
    // For testing
    void setInTouchMode(boolean showFocus);
    
    // These can only be called with the SET_ORIENTATION permission.
    /**
     * Change the current screen rotation, constants as per
     * {@link android.view.Surface}.
     * @param rotation the intended rotation.
     * @param alwaysSendConfiguration Flag to force a new configuration to
     * be evaluated.  This can be used when there are other parameters in
     * configuration that are changing.
     * @param animFlags Animation flags as per {@link android.view.Surface}.
     */
    void setRotation(int rotation, boolean alwaysSendConfiguration, int animFlags);

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
}
