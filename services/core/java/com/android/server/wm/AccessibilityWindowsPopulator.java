/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

import static com.android.server.wm.utils.RegionUtils.forEachRect;

import android.annotation.NonNull;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IWindow;
import android.view.InputWindowHandle;
import android.view.MagnificationSpec;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.window.WindowInfosListener;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is the accessibility windows population adapter.
 */
public final class AccessibilityWindowsPopulator extends WindowInfosListener {

    private static final String TAG = AccessibilityWindowsPopulator.class.getSimpleName();
    // If the surface flinger callback is not coming within in 2 frames time, i.e. about
    // 35ms, then assuming the windows become stable.
    private static final int SURFACE_FLINGER_CALLBACK_WINDOWS_STABLE_TIMES_MS = 35;
    // To avoid the surface flinger callbacks always comes within in 2 frames, then no windows
    // are reported to the A11y framework, and the animation duration time is 500ms, so setting
    // this value as the max timeout value to force computing changed windows.
    private static final int WINDOWS_CHANGED_NOTIFICATION_MAX_DURATION_TIMES_MS = 500;

    private static final float[] sTempFloats = new float[9];

    private final WindowManagerService mService;
    private final AccessibilityController mAccessibilityController;
    @GuardedBy("mLock")
    private final SparseArray<List<InputWindowHandle>> mInputWindowHandlesOnDisplays =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<Matrix> mMagnificationSpecInverseMatrix = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<DisplayInfo> mDisplayInfos = new SparseArray<>();
    @GuardedBy("mLock")
    private final List<InputWindowHandle> mVisibleWindows = new ArrayList<>();
    @GuardedBy("mLock")
    private boolean mWindowsNotificationEnabled = false;
    private final Object mLock = new Object();
    private final Handler mHandler;

    AccessibilityWindowsPopulator(WindowManagerService service,
            AccessibilityController accessibilityController) {
        mService = service;
        mAccessibilityController = accessibilityController;
        mHandler = new MyHandler(mService.mH.getLooper());

        register();
    }

    /**
     * Gets the visible windows list with the window layer on the specified display.
     *
     * @param displayId The display.
     * @param outWindows The visible windows list. The z-order of each window in the list
     *                   is from the top to bottom.
     */
    public void populateVisibleWindowsOnScreenLocked(int displayId,
            List<AccessibilityWindow> outWindows) {
        List<InputWindowHandle> inputWindowHandles;
        final Matrix inverseMatrix = new Matrix();
        final Matrix displayMatrix = new Matrix();

        synchronized (mLock) {
            inputWindowHandles = mInputWindowHandlesOnDisplays.get(displayId);
            if (inputWindowHandles == null) {
                outWindows.clear();

                return;
            }
            inverseMatrix.set(mMagnificationSpecInverseMatrix.get(displayId));

            final DisplayInfo displayInfo = mDisplayInfos.get(displayId);
            if (displayInfo != null) {
                displayMatrix.set(displayInfo.mTransform);
            } else {
                Slog.w(TAG, "The displayInfo of this displayId (" + displayId + ") called "
                        + "back from the surface fligner is null");
            }
        }

        final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
        final ShellRoot shellroot = dc.mShellRoots.get(WindowManager.SHELL_ROOT_LAYER_PIP);
        final IBinder pipMenuIBinder =
                shellroot != null ? shellroot.getAccessibilityWindowToken() : null;

        for (final InputWindowHandle windowHandle : inputWindowHandles) {
            final AccessibilityWindow accessibilityWindow =
                    AccessibilityWindow.initializeData(mService, windowHandle, inverseMatrix,
                            pipMenuIBinder, displayMatrix);

            outWindows.add(accessibilityWindow);
        }
    }

    @Override
    public void onWindowInfosChanged(InputWindowHandle[] windowHandles,
            DisplayInfo[] displayInfos) {
        synchronized (mLock) {
            mVisibleWindows.clear();
            for (InputWindowHandle window : windowHandles) {
                if (window.visible && window.getWindow() != null) {
                    mVisibleWindows.add(window);
                }
            }

            mDisplayInfos.clear();
            for (final DisplayInfo displayInfo : displayInfos) {
                mDisplayInfos.put(displayInfo.mDisplayId, displayInfo);
            }

            if (mWindowsNotificationEnabled) {
                if (!mHandler.hasMessages(
                        MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_TIMEOUT)) {
                    mHandler.sendEmptyMessageDelayed(
                            MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_TIMEOUT,
                            WINDOWS_CHANGED_NOTIFICATION_MAX_DURATION_TIMES_MS);
                }
                populateVisibleWindowHandlesAndNotifyWindowsChangeIfNeededLocked();
            }
        }
    }

    /**
     * Sets to notify the accessibilityController to compute changed windows on
     * the display after populating the visible windows if the windows reported
     * from the surface flinger changes.
     *
     * @param register {@code true} means starting windows population.
     */
    public void setWindowsNotification(boolean register) {
        synchronized (mLock) {
            if (mWindowsNotificationEnabled == register) {
                return;
            }
            mWindowsNotificationEnabled = register;
            if (mWindowsNotificationEnabled) {
                populateVisibleWindowHandlesAndNotifyWindowsChangeIfNeededLocked();
            } else {
                releaseResources();
            }
        }
    }

    private void populateVisibleWindowHandlesAndNotifyWindowsChangeIfNeededLocked() {
        final SparseArray<List<InputWindowHandle>> tempWindowHandleList = new SparseArray<>();

        for (final InputWindowHandle windowHandle : mVisibleWindows) {
            List<InputWindowHandle> inputWindowHandles = tempWindowHandleList.get(
                    windowHandle.displayId);

            if (inputWindowHandles == null) {
                inputWindowHandles = new ArrayList<>();
                tempWindowHandleList.put(windowHandle.displayId, inputWindowHandles);
                generateMagnificationSpecInverseMatrixLocked(windowHandle.displayId);
            }
            inputWindowHandles.add(windowHandle);
        }

        final List<Integer> displayIdsForWindowsChanged = new ArrayList<>();

        getDisplaysForWindowsChangedLocked(displayIdsForWindowsChanged, tempWindowHandleList,
                mInputWindowHandlesOnDisplays);
        // Clones all windows from the callback of the surface flinger.
        mInputWindowHandlesOnDisplays.clear();
        for (int i = 0; i < tempWindowHandleList.size(); i++) {
            final int displayId = tempWindowHandleList.keyAt(i);
            mInputWindowHandlesOnDisplays.put(displayId, tempWindowHandleList.get(displayId));
        }

        if (displayIdsForWindowsChanged.size() > 0) {
            if (!mHandler.hasMessages(MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED)) {
                mHandler.obtainMessage(MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED,
                        displayIdsForWindowsChanged).sendToTarget();
            }

            return;
        }
        mHandler.removeMessages(MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_UI_STABLE);
        mHandler.sendEmptyMessageDelayed(MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_UI_STABLE,
                SURFACE_FLINGER_CALLBACK_WINDOWS_STABLE_TIMES_MS);
    }

    private void getDisplaysForWindowsChangedLocked(List<Integer> outDisplayIdsForWindowsChanged,
            SparseArray<List<InputWindowHandle>> newWindowsList,
            SparseArray<List<InputWindowHandle>> oldWindowsList) {
        for (int i = 0; i < newWindowsList.size(); i++) {
            final int displayId = newWindowsList.keyAt(i);
            final List<InputWindowHandle> newWindows = newWindowsList.get(displayId);
            final List<InputWindowHandle> oldWindows = oldWindowsList.get(displayId);

            if (hasWindowsChangedLocked(newWindows, oldWindows)) {
                outDisplayIdsForWindowsChanged.add(displayId);
            }
        }
    }

    private boolean hasWindowsChangedLocked(List<InputWindowHandle> newWindows,
            List<InputWindowHandle> oldWindows) {
        if (oldWindows == null || oldWindows.size() != newWindows.size()) {
            return true;
        }

        final int windowsCount = newWindows.size();
        // Since we always traverse windows from high to low layer,
        // the old and new windows at the same index should be the
        // same, otherwise something changed.
        for (int i = 0; i < windowsCount; i++) {
            final InputWindowHandle newWindow = newWindows.get(i);
            final InputWindowHandle oldWindow = oldWindows.get(i);

            if (!newWindow.getWindow().asBinder().equals(oldWindow.getWindow().asBinder())) {
                return true;
            }
        }

        return false;
    }

    private void generateMagnificationSpecInverseMatrixLocked(int displayId) {
        MagnificationSpec spec = new MagnificationSpec();
        if (!mAccessibilityController.getMagnificationSpecForDisplay(displayId, spec)) {
            mMagnificationSpecInverseMatrix.remove(displayId);
            return;
        }
        sTempFloats[Matrix.MSCALE_X] = spec.scale;
        sTempFloats[Matrix.MSKEW_Y] = 0;
        sTempFloats[Matrix.MSKEW_X] = 0;
        sTempFloats[Matrix.MSCALE_Y] = spec.scale;
        sTempFloats[Matrix.MTRANS_X] = spec.offsetX;
        sTempFloats[Matrix.MTRANS_Y] = spec.offsetY;
        sTempFloats[Matrix.MPERSP_0] = 0;
        sTempFloats[Matrix.MPERSP_1] = 0;
        sTempFloats[Matrix.MPERSP_2] = 1;

        final Matrix tempMatrix = new Matrix();
        tempMatrix.setValues(sTempFloats);

        final Matrix inverseMatrix = new Matrix();
        final boolean result = tempMatrix.invert(inverseMatrix);

        if (!result) {
            Slog.e(TAG, "Can't inverse the magnification spec matrix with the "
                    + "magnification spec = " + spec + " on the displayId = " + displayId);
            return;
        }
        mMagnificationSpecInverseMatrix.set(displayId, inverseMatrix);
    }

    private void notifyWindowsChanged(@NonNull List<Integer> displayIdsForWindowsChanged) {
        mHandler.removeMessages(MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_TIMEOUT);

        for (int i = 0; i < displayIdsForWindowsChanged.size(); i++) {
            mAccessibilityController.performComputeChangedWindowsNot(
                    displayIdsForWindowsChanged.get(i), false);
        }
    }

    private void forceUpdateWindows() {
        final List<Integer> displayIdsForWindowsChanged = new ArrayList<>();

        synchronized (mLock) {
            for (int i = 0; i < mInputWindowHandlesOnDisplays.size(); i++) {
                final int displayId = mInputWindowHandlesOnDisplays.keyAt(i);
                displayIdsForWindowsChanged.add(displayId);
            }
        }
        notifyWindowsChanged(displayIdsForWindowsChanged);
    }

    @GuardedBy("mLock")
    private void releaseResources() {
        mInputWindowHandlesOnDisplays.clear();
        mMagnificationSpecInverseMatrix.clear();
        mVisibleWindows.clear();
        mDisplayInfos.clear();
        mWindowsNotificationEnabled = false;
        mHandler.removeCallbacksAndMessages(null);
    }

    private class MyHandler extends Handler {
        public static final int MESSAGE_NOTIFY_WINDOWS_CHANGED = 1;
        public static final int MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_UI_STABLE = 2;
        public static final int MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_TIMEOUT = 3;

        MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MESSAGE_NOTIFY_WINDOWS_CHANGED: {
                    final List<Integer> displayIdsForWindowsChanged = (List<Integer>) message.obj;
                    notifyWindowsChanged(displayIdsForWindowsChanged);
                } break;

                case MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_UI_STABLE: {
                    forceUpdateWindows();
                } break;

                case MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_TIMEOUT: {
                    Slog.w(TAG, "Windows change within in 2 frames continuously over 500 ms "
                            + "and notify windows changed immediately");
                    mHandler.removeMessages(
                            MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED_BY_UI_STABLE);

                    forceUpdateWindows();
                } break;
            }
        }
    }

    /**
     * This class represents information about a window from the
     * surface flinger to the accessibility framework.
     */
    public static class AccessibilityWindow {
        private static final Region TEMP_REGION = new Region();
        private static final RectF TEMP_RECTF = new RectF();
        // Data
        private IWindow mWindow;
        private int mDisplayId;
        private int mFlags;
        private int mType;
        private int mPrivateFlags;
        private boolean mIsPIPMenu;
        private boolean mIsFocused;
        private boolean mShouldMagnify;
        private boolean mIgnoreDuetoRecentsAnimation;
        private boolean mIsTrustedOverlay;
        private final Region mTouchableRegionInScreen = new Region();
        private final Region mTouchableRegionInWindow = new Region();
        private final Region mLetterBoxBounds = new Region();
        private WindowInfo mWindowInfo;

        /**
         * Returns the instance after initializing the internal data.
         * @param service The window manager service.
         * @param inputWindowHandle The window from the surface flinger.
         * @param inverseMatrix The magnification spec inverse matrix.
         */
        public static AccessibilityWindow initializeData(WindowManagerService service,
                InputWindowHandle inputWindowHandle, Matrix inverseMatrix, IBinder pipIBinder,
                Matrix displayMatrix) {
            final IWindow window = inputWindowHandle.getWindow();
            final WindowState windowState = window != null ? service.mWindowMap.get(
                    window.asBinder()) : null;

            final AccessibilityWindow instance = new AccessibilityWindow();

            instance.mWindow = inputWindowHandle.getWindow();
            instance.mDisplayId = inputWindowHandle.displayId;
            instance.mFlags = inputWindowHandle.layoutParamsFlags;
            instance.mType = inputWindowHandle.layoutParamsType;
            instance.mIsPIPMenu = inputWindowHandle.getWindow().asBinder().equals(pipIBinder);

            // TODO (b/199357848): gets the private flag of the window from other way.
            instance.mPrivateFlags = windowState != null ? windowState.mAttrs.privateFlags : 0;
            // TODO (b/199358208) : using new way to implement the focused window.
            instance.mIsFocused = windowState != null && windowState.isFocused();
            instance.mShouldMagnify = windowState == null || windowState.shouldMagnify();

            final RecentsAnimationController controller = service.getRecentsAnimationController();
            instance.mIgnoreDuetoRecentsAnimation = windowState != null && controller != null
                    && controller.shouldIgnoreForAccessibility(windowState);
            instance.mIsTrustedOverlay = inputWindowHandle.trustedOverlay;

            // TODO (b/199358388) : gets the letterbox bounds of the window from other way.
            if (windowState != null && windowState.areAppWindowBoundsLetterboxed()) {
                getLetterBoxBounds(windowState, instance.mLetterBoxBounds);
            }

            final Rect windowFrame = new Rect(inputWindowHandle.frameLeft,
                    inputWindowHandle.frameTop, inputWindowHandle.frameRight,
                    inputWindowHandle.frameBottom);
            getTouchableRegionInWindow(instance.mShouldMagnify, inputWindowHandle.touchableRegion,
                    instance.mTouchableRegionInWindow, windowFrame, inverseMatrix, displayMatrix);
            getUnMagnifiedTouchableRegion(instance.mShouldMagnify,
                    inputWindowHandle.touchableRegion, instance.mTouchableRegionInScreen,
                    inverseMatrix, displayMatrix);
            instance.mWindowInfo = windowState != null
                    ? windowState.getWindowInfo() : getWindowInfoForWindowlessWindows(instance);

            return instance;
        }

        /**
         * Returns the touchable region in the screen.
         * @param outRegion The touchable region.
         */
        public void getTouchableRegionInScreen(Region outRegion) {
            outRegion.set(mTouchableRegionInScreen);
        }

        /**
         * Returns the touchable region in the window.
         * @param outRegion The touchable region.
         */
        public void getTouchableRegionInWindow(Region outRegion) {
            outRegion.set(mTouchableRegionInWindow);
        }

        /**
         * @return the layout parameter flag {@link android.view.WindowManager.LayoutParams#flags}.
         */
        public int getFlags() {
            return mFlags;
        }

        /**
         * @return the layout parameter type {@link android.view.WindowManager.LayoutParams#type}.
         */
        public int getType() {
            return mType;
        }

        /**
         * @return the layout parameter private flag
         * {@link android.view.WindowManager.LayoutParams#privateFlags}.
         */
        public int getPrivateFlag() {
            return mPrivateFlags;
        }

        /**
         * @return the windowInfo {@link WindowInfo}.
         */
        public WindowInfo getWindowInfo() {
            return mWindowInfo;
        }

        /**
         * Gets the letter box bounds if activity bounds are letterboxed
         * or letterboxed for display cutout.
         *
         * @return {@code true} there's a letter box bounds.
         */
        public Boolean setLetterBoxBoundsIfNeeded(Region outBounds) {
            if (mLetterBoxBounds.isEmpty()) {
                return false;
            }

            outBounds.set(mLetterBoxBounds);
            return true;
        }

        /**
         * @return true if this window should be magnified.
         */
        public boolean shouldMagnify() {
            return mShouldMagnify;
        }

        /**
         * @return true if this window is focused.
         */
        public boolean isFocused() {
            return mIsFocused;
        }

        /**
         * @return true if it's running the recent animation but not the target app.
         */
        public boolean ignoreRecentsAnimationForAccessibility() {
            return mIgnoreDuetoRecentsAnimation;
        }

        /**
         * @return true if this window is the trusted overlay.
         */
        public boolean isTrustedOverlay() {
            return mIsTrustedOverlay;
        }

        /**
         * @return true if this window is the navigation bar with the gesture mode.
         */
        public boolean isUntouchableNavigationBar() {
            if (mType != WindowManager.LayoutParams.TYPE_NAVIGATION_BAR) {
                return false;
            }

            return mTouchableRegionInScreen.isEmpty();
        }

        /**
         * @return true if this window is PIP menu.
         */
        public boolean isPIPMenu() {
            return mIsPIPMenu;
        }

        private static void getTouchableRegionInWindow(boolean shouldMagnify, Region inRegion,
                Region outRegion, Rect frame, Matrix inverseMatrix, Matrix displayMatrix) {
            // Some modal windows, like the activity with Theme.dialog, has the full screen
            // as its touchable region, but its window frame is smaller than the touchable
            // region. The region we report should be the touchable area in the window frame
            // for the consistency and match developers expectation.
            // So we need to make the intersection between the frame and touchable region to
            // obtain the real touch region in the screen.
            Region touchRegion = TEMP_REGION;
            touchRegion.set(inRegion);
            touchRegion.op(frame, Region.Op.INTERSECT);

            getUnMagnifiedTouchableRegion(shouldMagnify, touchRegion, outRegion, inverseMatrix,
                    displayMatrix);
        }

        /**
         * Gets the un-magnified touchable region. If this window can be magnified and magnifying,
         * we will transform the input touchable region by applying the inverse matrix of the
         * magnification spec to get the un-magnified touchable region.
         * @param shouldMagnify The window can be magnified.
         * @param inRegion The touchable region of this window.
         * @param outRegion The un-magnified touchable region of this window.
         * @param inverseMatrix The inverse matrix of the magnification spec.
         * @param displayMatrix The display transform matrix which takes display coordinates to
         *                      logical display coordinates.
         */
        private static void getUnMagnifiedTouchableRegion(boolean shouldMagnify, Region inRegion,
                Region outRegion, Matrix inverseMatrix, Matrix displayMatrix) {
            if ((!shouldMagnify || inverseMatrix.isIdentity()) && displayMatrix.isIdentity()) {
                outRegion.set(inRegion);
                return;
            }

            forEachRect(inRegion, rect -> {
                // Move to origin as all transforms are captured by the matrix.
                RectF windowFrame = TEMP_RECTF;
                windowFrame.set(rect);

                inverseMatrix.mapRect(windowFrame);
                displayMatrix.mapRect(windowFrame);
                // Union all rects.
                outRegion.union(new Rect((int) windowFrame.left, (int) windowFrame.top,
                        (int) windowFrame.right, (int) windowFrame.bottom));
            });
        }

        private static WindowInfo getWindowInfoForWindowlessWindows(AccessibilityWindow window) {
            WindowInfo windowInfo = WindowInfo.obtain();
            windowInfo.displayId = window.mDisplayId;
            windowInfo.type = window.mType;
            windowInfo.token = window.mWindow.asBinder();
            windowInfo.hasFlagWatchOutsideTouch = (window.mFlags
                    & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0;
            windowInfo.inPictureInPicture = false;

            // There only are two windowless windows now, one is split window, and the other
            // one is PIP.
            if (windowInfo.type == TYPE_DOCK_DIVIDER) {
                windowInfo.title = "Splitscreen Divider";
            } else if (window.mIsPIPMenu) {
                windowInfo.title = "Picture-in-Picture menu";
            }
            return windowInfo;
        }

        private static void getLetterBoxBounds(WindowState windowState, Region outRegion) {
            final Rect letterboxInsets = windowState.mActivityRecord.getLetterboxInsets();
            final Rect nonLetterboxRect = windowState.getBounds();

            nonLetterboxRect.inset(letterboxInsets);
            outRegion.set(windowState.getBounds());
            outRegion.op(nonLetterboxRect, Region.Op.DIFFERENCE);
        }

        @Override
        public String toString() {
            String builder = "A11yWindow=[" + mWindow.asBinder()
                    + ", displayId=" + mDisplayId
                    + ", flag=0x" + Integer.toHexString(mFlags)
                    + ", type=" + mType
                    + ", privateFlag=0x" + Integer.toHexString(mPrivateFlags)
                    + ", focused=" + mIsFocused
                    + ", shouldMagnify=" + mShouldMagnify
                    + ", ignoreDuetoRecentsAnimation=" + mIgnoreDuetoRecentsAnimation
                    + ", isTrustedOverlay=" + mIsTrustedOverlay
                    + ", regionInScreen=" + mTouchableRegionInScreen
                    + ", touchableRegion=" + mTouchableRegionInWindow
                    + ", letterBoxBounds=" + mLetterBoxBounds
                    + ", isPIPMenu=" + mIsPIPMenu
                    + ", windowInfo=" + mWindowInfo
                    + "]";

            return builder;
        }
    }
}
