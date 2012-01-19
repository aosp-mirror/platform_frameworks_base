/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Display_Delegate;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.List;

/**
 * Basic implementation of {@link IWindowManager} so that {@link Display} (and
 * {@link Display_Delegate}) can return a valid instance.
 */
public class BridgeWindowManager implements IWindowManager {

    private final Configuration mConfig;
    private final DisplayMetrics mMetrics;
    private final int mRotation;

    public BridgeWindowManager(Configuration config, DisplayMetrics metrics, int rotation) {
        mConfig = config;
        mMetrics = metrics;
        mRotation = rotation;
    }

    // custom API.

    public DisplayMetrics getMetrics() {
        return mMetrics;
    }

    // ---- implementation of IWindowManager that we care about ----

    public int getRotation() throws RemoteException {
        return mRotation;
    }

    public int getMaximumSizeDimension() throws RemoteException {
        return 0;
    }

    public void getDisplaySize(Point arg0) throws RemoteException {
    }

    public void getRealDisplaySize(Point arg0) throws RemoteException {
    }

    // ---- unused implementation of IWindowManager ----

    public boolean canStatusBarHide() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void addAppToken(int arg0, IApplicationToken arg1, int arg2, int arg3, boolean arg4)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void addWindowToken(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void clearForcedDisplaySize() throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void closeSystemDialogs(String arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void disableKeyguard(IBinder arg0, String arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void executeAppTransition() throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void exitKeyguardSecurely(IOnKeyguardExitResult arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void freezeRotation(int arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public float getAnimationScale(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public float[] getAnimationScales() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public int getAppOrientation(IApplicationToken arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getDPadKeycodeState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getDPadScancodeState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }


    public InputDevice getInputDevice(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public int[] getInputDeviceIds() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public int getKeycodeState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getKeycodeStateForDevice(int arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }


    public int getPendingAppTransition() throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }


    public int getScancodeState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getScancodeStateForDevice(int arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getSwitchState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getSwitchStateForDevice(int arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getTrackballKeycodeState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getTrackballScancodeState(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public boolean hasKeys(int[] arg0, boolean[] arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean inKeyguardRestrictedInputMode() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean injectInputEventNoWait(InputEvent arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean injectKeyEvent(KeyEvent arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean injectPointerEvent(MotionEvent arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean injectTrackballEvent(MotionEvent arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean inputMethodClientHasFocus(IInputMethodClient arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isKeyguardLocked() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isKeyguardSecure() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isViewServerRunning() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public InputChannel monitorInput(String arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public void moveAppToken(int arg0, IBinder arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void moveAppTokensToBottom(List<IBinder> arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void moveAppTokensToTop(List<IBinder> arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public IWindowSession openSession(IInputMethodClient arg0, IInputContext arg1)
            throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public void overridePendingAppTransition(String arg0, int arg1, int arg2)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void pauseKeyDispatching(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void prepareAppTransition(int arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void reenableKeyguard(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void removeAppToken(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void removeWindowToken(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void resumeKeyDispatching(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public Bitmap screenshotApplications(IBinder arg0, int arg1, int arg2) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public void setAnimationScale(int arg0, float arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAnimationScales(float[] arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAppGroupId(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAppOrientation(IApplicationToken arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAppStartingWindow(IBinder arg0, String arg1, int arg2, CompatibilityInfo arg3,
            CharSequence arg4, int arg5, int arg6, int arg7, IBinder arg8, boolean arg9)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAppVisibility(IBinder arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAppWillBeHidden(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setEventDispatching(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setFocusedApp(IBinder arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setForcedDisplaySize(int arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setInTouchMode(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setNewConfiguration(Configuration arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setPointerSpeed(int arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void updateRotation(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setStrictModeVisualIndicatorPreference(String arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void showStrictModeViolation(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void startAppFreezingScreen(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public boolean startViewServer(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void statusBarVisibilityChanged(int arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void stopAppFreezingScreen(IBinder arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public boolean stopViewServer() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void thawRotation() throws RemoteException {
        // TODO Auto-generated method stub

    }

    public Configuration updateOrientationFromAppTokens(Configuration arg0, IBinder arg1)
            throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public int watchRotation(IRotationWatcher arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public void waitForWindowDrawn(IBinder token, IRemoteCallback callback) {
        // TODO Auto-generated method stub
    }
    
    public IBinder asBinder() {
        // TODO Auto-generated method stub
        return null;
    }

    public int getPreferredOptionsPanelGravity() throws RemoteException {
        return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
    }

    public void dismissKeyguard() {
    }

    public boolean hasNavigationBar() {
        return false; // should this return something else?
    }

    public void lockNow() {
        // TODO Auto-generated method stub
    }
}
