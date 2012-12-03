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

package android.view;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IDisplayContentChangeListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.WindowInfo;

import java.util.List;

/**
 * Basic implementation of {@link IWindowManager} so that {@link Display} (and
 * {@link Display_Delegate}) can return a valid instance.
 */
public class IWindowManagerImpl implements IWindowManager {

    private final Configuration mConfig;
    private final DisplayMetrics mMetrics;
    private final int mRotation;
    private final boolean mHasSystemNavBar;
    private final boolean mHasNavigationBar;

    public IWindowManagerImpl(Configuration config, DisplayMetrics metrics, int rotation,
            boolean hasSystemNavBar, boolean hasNavigationBar) {
        mConfig = config;
        mMetrics = metrics;
        mRotation = rotation;
        mHasSystemNavBar = hasSystemNavBar;
        mHasNavigationBar = hasNavigationBar;
    }

    // custom API.

    public DisplayMetrics getMetrics() {
        return mMetrics;
    }

    // ---- implementation of IWindowManager that we care about ----

    @Override
    public int getRotation() throws RemoteException {
        return mRotation;
    }

    @Override
    public boolean hasNavigationBar() {
        return mHasNavigationBar;
    }

    @Override
    public boolean hasSystemNavBar() throws RemoteException {
        return mHasSystemNavBar;
    }

    // ---- unused implementation of IWindowManager ----

    @Override
    public void addAppToken(int arg0, int arg1p5, IApplicationToken arg1, int arg2, int arg3, boolean arg4,
                            boolean arg5)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void addWindowToken(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void clearForcedDisplaySize(int displayId) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void clearForcedDisplayDensity(int displayId) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void closeSystemDialogs(String arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void startFreezingScreen(int exitAnim, int enterAnim) {
        // TODO Auto-generated method stub
    }

    @Override
    public void stopFreezingScreen() {
        // TODO Auto-generated method stub
    }

    @Override
    public void disableKeyguard(IBinder arg0, String arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void executeAppTransition() throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void exitKeyguardSecurely(IOnKeyguardExitResult arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void freezeRotation(int arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public float getAnimationScale(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float[] getAnimationScales() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getAppOrientation(IApplicationToken arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getPendingAppTransition() throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean inKeyguardRestrictedInputMode() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean inputMethodClientHasFocus(IInputMethodClient arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isKeyguardLocked() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isKeyguardSecure() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isViewServerRunning() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void moveAppToken(int arg0, IBinder arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void moveAppTokensToBottom(List<IBinder> arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void moveAppTokensToTop(List<IBinder> arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public IWindowSession openSession(IInputMethodClient arg0, IInputContext arg1)
            throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void overridePendingAppTransition(String arg0, int arg1, int arg2,
            IRemoteCallback startedCallback) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void overridePendingAppTransitionThumb(Bitmap srcThumb, int startX, int startY,
            IRemoteCallback startedCallback, boolean scaleUp) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void pauseKeyDispatching(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void prepareAppTransition(int arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void reenableKeyguard(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void removeAppToken(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void removeWindowToken(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void resumeKeyDispatching(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public Bitmap screenshotApplications(IBinder arg0, int displayId, int arg1, int arg2)
            throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setAnimationScale(int arg0, float arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void setAnimationScales(float[] arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void setAppGroupId(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void setAppOrientation(IApplicationToken arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setAppStartingWindow(IBinder arg0, String arg1, int arg2, CompatibilityInfo arg3,
            CharSequence arg4, int arg5, int arg6, int arg7, IBinder arg8, boolean arg9)
            throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setAppVisibility(IBinder arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public void setAppWillBeHidden(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setEventDispatching(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setFocusedApp(IBinder arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setForcedDisplaySize(int displayId, int arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setForcedDisplayDensity(int displayId, int density) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setInTouchMode(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setNewConfiguration(Configuration arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void updateRotation(boolean arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void setStrictModeVisualIndicatorPreference(String arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void showStrictModeViolation(boolean arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void startAppFreezingScreen(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean startViewServer(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void statusBarVisibilityChanged(int arg0) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void stopAppFreezingScreen(IBinder arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean stopViewServer() throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void thawRotation() throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public Configuration updateOrientationFromAppTokens(Configuration arg0, IBinder arg1)
            throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int watchRotation(IRotationWatcher arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean waitForWindowDrawn(IBinder token, IRemoteCallback callback) {
        return false;
    }

    @Override
    public IBinder asBinder() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getPreferredOptionsPanelGravity() throws RemoteException {
        return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
    }

    @Override
    public void dismissKeyguard() {
    }

    @Override
    public void lockNow(Bundle options) {
        // TODO Auto-generated method stub
    }
    
    @Override
    public boolean isSafeModeEnabled() {
        return false;
    }

    @Override
    public void showAssistant() {

    }

    @Override
    public IBinder getFocusedWindowToken() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public float getWindowCompatibilityScale(IBinder windowToken) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setInputFilter(IInputFilter filter) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void magnifyDisplay(int dipslayId, float scale, float offsetX, float offsetY)
            throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void addDisplayContentChangeListener(int displayId,
            IDisplayContentChangeListener listener) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public void removeDisplayContentChangeListener(int displayId,
            IDisplayContentChangeListener listener) throws RemoteException {
        // TODO Auto-generated method stub
    }

    @Override
    public WindowInfo getWindowInfo(IBinder token) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void getVisibleWindowsForDisplay(int displayId, List<WindowInfo> outInfos)
            throws RemoteException {
        // TODO Auto-generated method stub
    }
}
