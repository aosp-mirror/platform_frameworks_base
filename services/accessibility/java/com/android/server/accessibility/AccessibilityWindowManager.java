/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOW_MANAGER_INTERNAL;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.accessibility.AbstractAccessibilityServiceConnection.DISPLAY_TYPE_DEFAULT;
import static com.android.server.accessibility.AbstractAccessibilityServiceConnection.DISPLAY_TYPE_PROXY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Region;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowAttributes;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.AccessibilitySecurityPolicy.AccessibilityUserManager;
import com.android.server.utils.Slogf;
import com.android.server.wm.AccessibilityWindowsPopulator.AccessibilityWindow;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class provides APIs for accessibility manager to manage {@link AccessibilityWindowInfo}s and
 * {@link WindowInfo}s.
 */
public class AccessibilityWindowManager {
    private static final String LOG_TAG = "AccessibilityWindowManager";
    private static final boolean DEBUG = false;
    private static final boolean VERBOSE = false;

    private static int sNextWindowId;

    private final Object mLock;
    private final Handler mHandler;
    private final WindowManagerInternal mWindowManagerInternal;
    private final AccessibilityEventSender mAccessibilityEventSender;
    private final AccessibilitySecurityPolicy mSecurityPolicy;
    private final AccessibilityUserManager mAccessibilityUserManager;
    private final AccessibilityTraceManager mTraceManager;

    // Connections and window tokens for cross-user windows
    private final SparseArray<RemoteAccessibilityConnection>
            mGlobalInteractionConnections = new SparseArray<>();
    private final SparseArray<IBinder> mGlobalWindowTokens = new SparseArray<>();

    // Connections and window tokens for per-user windows, indexed as one sparse array per user
    private final SparseArray<SparseArray<RemoteAccessibilityConnection>>
            mInteractionConnections = new SparseArray<>();
    private final SparseArray<SparseArray<IBinder>> mWindowTokens = new SparseArray<>();

    private RemoteAccessibilityConnection mPictureInPictureActionReplacingConnection;
    // There is only one active window in the system. It is updated when the top focused window
    // of the top focused display changes and when we receive a TYPE_WINDOW_STATE_CHANGED event.
    private int mActiveWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    // There is only one top focused window in the system. It is updated when the window manager
    // updates the window lists.
    private int mTopFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private int mAccessibilityFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private long mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
    // The top focused display and window token updated with the callback of window lists change.
    private int mTopFocusedDisplayId;
    private IBinder mTopFocusedWindowToken;

    // The non-proxy display that most recently had top focus.
    private int mLastNonProxyTopFocusedDisplayId;
    // The display has the accessibility focused window currently.
    private int mAccessibilityFocusedDisplayId = Display.INVALID_DISPLAY;

    private boolean mTouchInteractionInProgress;

    private boolean mHasProxy;

    /** List of Display Windows Observer, mapping from displayId -> DisplayWindowsObserver. */
    private final SparseArray<DisplayWindowsObserver> mDisplayWindowsObservers =
            new SparseArray<>();

    /**
     * Map of host view and embedded hierarchy, mapping from leash token of its ViewRootImpl.
     * The key is the token from embedded hierarchy, and the value is the token from its host.
     */
    private final ArrayMap<IBinder, IBinder> mHostEmbeddedMap = new ArrayMap<>();

    /**
     * Map of window id and view hierarchy.
     * The key is the window id when the ViewRootImpl register to accessibility, and the value is
     * its leash token.
     */
    private final SparseArray<IBinder> mWindowIdMap = new SparseArray<>();

    /**
     * Map of window id and window attribute hierarchy.
     * The key is the window id when the ViewRootImpl register to accessibility, and the value is
     * its window attribute .
     */
    private final SparseArray<AccessibilityWindowAttributes> mWindowAttributes =
            new SparseArray<>();

    /**
     * Sets the {@link AccessibilityWindowAttributes} to the window associated with the given
     * window id.
     *
     * @param displayId The display id of the window.
     * @param windowId The id of the window
     * @param userId The user id.
     * @param attributes The accessibility window attributes.
     */
    public void setAccessibilityWindowAttributes(int displayId, int windowId, int userId,
            AccessibilityWindowAttributes attributes) {
        boolean shouldComputeWindows = false;
        synchronized (mLock) {
            final int resolvedUserId =
                    mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(userId);
            if (getWindowTokenForUserAndWindowIdLocked(resolvedUserId, windowId) == null) {
                return;
            }
            mWindowAttributes.put(windowId, attributes);
            shouldComputeWindows = findWindowInfoByIdLocked(windowId) != null;
        }
        if (shouldComputeWindows) {
            mWindowManagerInternal.computeWindowsForAccessibility(displayId);
        }
    }

    /**
     * Returns {@code true} if the window belongs to a display of {@code displayTypes}.
     */
    public boolean windowIdBelongsToDisplayType(int focusedWindowId, int displayTypes) {
        if (!mHasProxy) {
            return true;
        }
        // UIAutomation wants focus from any display type.
        final int displayTypeMask = DISPLAY_TYPE_PROXY | DISPLAY_TYPE_DEFAULT;
        if ((displayTypes & displayTypeMask) == displayTypeMask) {
            return true;
        }
        synchronized (mLock) {
            final int count = mDisplayWindowsObservers.size();
            for (int i = 0; i < count; i++) {
                final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
                if (observer != null
                        && observer.findA11yWindowInfoByIdLocked(focusedWindowId) != null) {
                    return observer.mIsProxy
                            ? ((displayTypes & DISPLAY_TYPE_PROXY) != 0)
                            : (displayTypes & DISPLAY_TYPE_DEFAULT) != 0;
                }
            }
        }
        return false;
    }

    /**
     * This class implements {@link WindowManagerInternal.WindowsForAccessibilityCallback} to
     * receive {@link WindowInfo}s from window manager when there's an accessibility change in
     * window and holds window lists information per display.
     */
    private final class DisplayWindowsObserver implements
            WindowManagerInternal.WindowsForAccessibilityCallback {

        private final int mDisplayId;
        private final SparseArray<AccessibilityWindowInfo> mA11yWindowInfoById =
                new SparseArray<>();
        private final SparseArray<WindowInfo> mWindowInfoById = new SparseArray<>();
        private final List<WindowInfo> mCachedWindowInfos = new ArrayList<>();
        private List<AccessibilityWindowInfo> mWindows;
        private boolean mTrackingWindows = false;
        private boolean mHasWatchOutsideTouchWindow;
        private int mProxyDisplayAccessibilityFocusedWindow =
                AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        private boolean mIsProxy;

        /**
         * Constructor for DisplayWindowsObserver.
         */
        DisplayWindowsObserver(int displayId) {
            if (DEBUG) {
                Slogf.d(LOG_TAG, "Creating DisplayWindowsObserver for displayId %d", displayId);
            }
            mDisplayId = displayId;
        }

        /**
         * Starts tracking windows changes from window manager by registering callback.
         */
        void startTrackingWindowsLocked() {
            if (!mTrackingWindows) {
                // Turns on the flag before setup the callback.
                // In some cases, onWindowsForAccessibilityChanged will be called immediately in
                // setWindowsForAccessibilityCallback. We'll lost windows if flag is false.
                mTrackingWindows = true;
                if (traceWMEnabled()) {
                    logTraceWM("setWindowsForAccessibilityCallback",
                            "displayId=" + mDisplayId + ";callback=" + this);
                }
                mWindowManagerInternal.setWindowsForAccessibilityCallback(
                        mDisplayId, this);
            }
        }

        /**
         * Stops tracking windows changes from window manager, and clear all windows info.
         */
        void stopTrackingWindowsLocked() {
            if (mTrackingWindows) {
                if (traceWMEnabled()) {
                    logTraceWM("setWindowsForAccessibilityCallback",
                            "displayId=" + mDisplayId + ";callback=null");
                }
                mWindowManagerInternal.setWindowsForAccessibilityCallback(
                        mDisplayId, null);
                mTrackingWindows = false;
                clearWindowsLocked();
            }
        }

        /**
         * Returns true if windows changes tracking.
         *
         * @return true if windows changes tracking
         */
        boolean isTrackingWindowsLocked() {
            return mTrackingWindows;
        }

        /**
         * Returns accessibility windows.
         * @return accessibility windows.
         */
        @Nullable
        List<AccessibilityWindowInfo> getWindowListLocked() {
            return mWindows;
        }

        /**
         * Returns accessibility window info according to given windowId.
         *
         * @param windowId The windowId
         * @return The accessibility window info
         */
        @Nullable
        AccessibilityWindowInfo findA11yWindowInfoByIdLocked(int windowId) {
            return mA11yWindowInfoById.get(windowId);
        }

        /**
         * Returns the window info according to given windowId.
         *
         * @param windowId The windowId
         * @return The window info
         */
        @Nullable
        WindowInfo findWindowInfoByIdLocked(int windowId) {
            return mWindowInfoById.get(windowId);
        }

        /**
         * Returns {@link AccessibilityWindowInfo} of PIP window.
         *
         * @return PIP accessibility window info
         */
        @Nullable
        AccessibilityWindowInfo getPictureInPictureWindowLocked() {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    final AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.isInPictureInPictureMode()) {
                        return window;
                    }
                }
            }
            return null;
        }

        /**
         * Sets the active flag of the window according to given windowId, others set to inactive.
         *
         * @param windowId The windowId
         * @return {@code true} if the window is in this display, {@code false} otherwise.
         */
        boolean setActiveWindowLocked(int windowId) {
            boolean foundWindow = false;
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == windowId) {
                        window.setActive(true);
                        foundWindow = true;
                    } else {
                        window.setActive(false);
                    }
                }
            }
            return foundWindow;
        }

        /**
         * Sets the window accessibility focused according to given windowId, others set
         * unfocused.
         *
         * @param windowId The windowId
         * @return {@code true} if the window is in this display, {@code false} otherwise.
         */
        boolean setAccessibilityFocusedWindowLocked(int windowId) {
            boolean foundWindow = false;
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == windowId) {
                        window.setAccessibilityFocused(true);
                        foundWindow = true;
                    } else {
                        window.setAccessibilityFocused(false);
                    }
                }
            }
            return foundWindow;
        }

        /**
         * Computes partial interactive region of given windowId.
         *
         * @param windowId The windowId
         * @param forceComputeRegion set outRegion when the windowId matches one on the screen even
         *                           though the region is not covered by other windows above it.
         * @param outRegion The output to which to write the bounds.
         * @return {@code true} if outRegion is not empty.
         */
        boolean computePartialInteractiveRegionForWindowLocked(int windowId,
                boolean forceComputeRegion, @NonNull Region outRegion) {
            if (mWindows == null) {
                return false;
            }

            // Windows are ordered in z order so start from the bottom and find
            // the window of interest. After that all windows that cover it should
            // be subtracted from the resulting region. Note that for accessibility
            // we are returning only interactive windows.
            Region windowInteractiveRegion = null;
            boolean windowInteractiveRegionChanged = false;

            final int windowCount = mWindows.size();
            final Region currentWindowRegions = new Region();
            for (int i = windowCount - 1; i >= 0; i--) {
                AccessibilityWindowInfo currentWindow = mWindows.get(i);
                if (windowInteractiveRegion == null) {
                    if (currentWindow.getId() == windowId) {
                        currentWindow.getRegionInScreen(currentWindowRegions);
                        outRegion.set(currentWindowRegions);
                        windowInteractiveRegion = outRegion;
                        if (forceComputeRegion) {
                            windowInteractiveRegionChanged = true;
                        }
                        continue;
                    }
                } else if (currentWindow.getType()
                        != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    currentWindow.getRegionInScreen(currentWindowRegions);
                    if (windowInteractiveRegion.op(currentWindowRegions, Region.Op.DIFFERENCE)) {
                        windowInteractiveRegionChanged = true;
                    }
                }
            }

            return windowInteractiveRegionChanged;
        }

        List<Integer> getWatchOutsideTouchWindowIdLocked(int targetWindowId) {
            final WindowInfo targetWindow = mWindowInfoById.get(targetWindowId);
            if (targetWindow != null && mHasWatchOutsideTouchWindow) {
                final List<Integer> outsideWindowsId = new ArrayList<>();
                for (int i = 0; i < mWindowInfoById.size(); i++) {
                    final WindowInfo window = mWindowInfoById.valueAt(i);
                    if (window != null && window.layer < targetWindow.layer
                            && window.hasFlagWatchOutsideTouch) {
                        outsideWindowsId.add(mWindowInfoById.keyAt(i));
                    }
                }
                return outsideWindowsId;
            }
            return Collections.emptyList();
        }

        /**
         * Callbacks from window manager when there's an accessibility change in windows.
         *
         * @param forceSend Send the windows for accessibility even if they haven't changed.
         * @param topFocusedDisplayId The display Id which has the top focused window.
         * @param topFocusedWindowToken The window token of top focused window.
         * @param windows The windows for accessibility.
         */
        @Override
        public void onWindowsForAccessibilityChanged(boolean forceSend, int topFocusedDisplayId,
                IBinder topFocusedWindowToken, @NonNull List<WindowInfo> windows) {
            synchronized (mLock) {
                updateWindowsByWindowAttributesLocked(windows);
                if (DEBUG) {
                    Slogf.i(LOG_TAG, "mDisplayId=%d, topFocusedDisplayId=%d, currentUserId=%d, "
                                    + "visibleBgUsers=%s", mDisplayId, topFocusedDisplayId,
                            mAccessibilityUserManager.getCurrentUserIdLocked(),
                            mAccessibilityUserManager.getVisibleUserIdsLocked());
                    if (VERBOSE) {
                        Slogf.i(LOG_TAG, "%d windows changed: %s ", windows.size(), windows);
                    } else {
                        List<String> windowsInfo = windows.stream()
                                .map(w -> "{displayId=" + w.displayId + ", title=" + w.title + "}")
                                .collect(Collectors.toList());
                        Slogf.i(LOG_TAG, "%d windows changed: %s", windows.size(), windowsInfo);
                    }
                }
                if (shouldUpdateWindowsLocked(forceSend, windows)) {
                    mTopFocusedDisplayId = topFocusedDisplayId;
                    if (!isProxyed(topFocusedDisplayId)) {
                        mLastNonProxyTopFocusedDisplayId = topFocusedDisplayId;
                    }
                    mTopFocusedWindowToken = topFocusedWindowToken;
                    if (DEBUG) {
                        Slogf.d(LOG_TAG, "onWindowsForAccessibilityChanged(): updating windows for "
                                        + "display %d and token %s",
                                topFocusedDisplayId, topFocusedWindowToken);
                    }
                    cacheWindows(windows);
                    // Lets the policy update the focused and active windows.
                    updateWindowsLocked(mAccessibilityUserManager.getCurrentUserIdLocked(),
                            windows);
                    // Someone may be waiting for the windows - advertise it.
                    mLock.notifyAll();
                }
                else if (DEBUG) {
                    Slogf.d(LOG_TAG, "onWindowsForAccessibilityChanged(): NOT updating windows for "
                                    + "display %d and token %s",
                            topFocusedDisplayId, topFocusedWindowToken);
                }
            }
        }

        /**
         * Called when the windows for accessibility changed. This is called if
         * {@link com.android.server.accessibility.Flags.FLAG_COMPUTE_WINDOW_CHANGES_ON_A11Y} is
         * true.
         *
         * @param forceSend             Send the windows for accessibility even if they haven't
         *                              changed.
         * @param topFocusedDisplayId   The display Id which has the top focused window.
         * @param topFocusedWindowToken The window token of top focused window.
         * @param screenSize            The size of the display that the change happened.
         * @param windows               The windows for accessibility.
         */
        @Override
        public void onAccessibilityWindowsChanged(boolean forceSend, int topFocusedDisplayId,
                @NonNull IBinder topFocusedWindowToken, @NonNull Point screenSize,
                @NonNull List<AccessibilityWindow> windows) {
            // TODO(b/322444245): Get a screenSize from DisplayManager#getDisplay(int)
            //  .getRealSize().
            final List<WindowInfo> windowInfoList = createWindowInfoList(screenSize, windows);
            onWindowsForAccessibilityChanged(forceSend, topFocusedDisplayId,
                    topFocusedWindowToken, windowInfoList);
        }

        private static List<WindowInfo> createWindowInfoList(@NonNull Point screenSize,
                @NonNull List<AccessibilityWindow> visibleWindows) {
            final Set<IBinder> addedWindows = new ArraySet<>();
            final List<WindowInfo> windows = new ArrayList<>();

            // Avoid allocating Region for each window.
            final Region regionInWindow = new Region();
            final Region touchableRegionInScreen = new Region();

            // Iterate until we figure out what is touchable for the entire screen.
            boolean focusedWindowAdded = false;
            final Region unaccountedSpace = new Region(0, 0, screenSize.x, screenSize.y);
            for (final AccessibilityWindow a11yWindow : visibleWindows) {
                a11yWindow.getTouchableRegionInWindow(regionInWindow);
                if (windowMattersToAccessibility(a11yWindow, regionInWindow, unaccountedSpace)) {
                    final WindowInfo window = a11yWindow.getWindowInfo();
                    if (window.token != null) {
                        // Even if token is null, the window will be used in calculating visible
                        // windows, but is excluded from the accessibility window list.
                        // TODO(b/322444245): We can call #updateWindowWithWindowAttributes() here.
                        window.regionInScreen.set(regionInWindow);
                        window.layer = addedWindows.size();
                        windows.add(window);
                        addedWindows.add(window.token);
                    }

                    if (windowMattersToUnaccountedSpaceComputation(a11yWindow)) {
                        // Account for the space this window takes.
                        a11yWindow.getTouchableRegionInScreen(touchableRegionInScreen);
                        unaccountedSpace.op(touchableRegionInScreen, unaccountedSpace,
                                Region.Op.REVERSE_DIFFERENCE);
                    }

                    focusedWindowAdded |= a11yWindow.isFocused();
                } else if (a11yWindow.isUntouchableNavigationBar()
                        && a11yWindow.getSystemBarInsetsFrame() != null) {
                    // If this widow is navigation bar without touchable region, accounting the
                    // region of navigation bar inset because all touch events from this region
                    // would be received by launcher, i.e. this region is a un-touchable one
                    // for the application.
                    unaccountedSpace.op(
                            a11yWindow.getSystemBarInsetsFrame(),
                            unaccountedSpace,
                            Region.Op.REVERSE_DIFFERENCE);
                }

                if (unaccountedSpace.isEmpty() && focusedWindowAdded) {
                    break;
                }
            }

            // Remove child/parent references to windows that were not added.
            for (final WindowInfo window : windows) {
                if (!addedWindows.contains(window.parentToken)) {
                    window.parentToken = null;
                }
                if (window.childTokens != null) {
                    final int childTokenCount = window.childTokens.size();
                    for (int j = childTokenCount - 1; j >= 0; j--) {
                        if (!addedWindows.contains(window.childTokens.get(j))) {
                            window.childTokens.remove(j);
                        }
                    }
                    // Leave the child token list if empty.
                }
            }

            return windows;
        }

        private static boolean windowMattersToAccessibility(AccessibilityWindow a11yWindow,
                Region regionInScreen, Region unaccountedSpace) {
            if (a11yWindow.ignoreRecentsAnimationForAccessibility()) {
                return false;
            }

            if (a11yWindow.isFocused()) {
                return true;
            }

            // Ignore non-touchable windows, except the split-screen divider, which is
            // occasionally non-touchable but still useful for identifying split-screen
            // mode and the PIP menu.
            if (!a11yWindow.isTouchable()
                    && a11yWindow.getType() != TYPE_DOCK_DIVIDER && !a11yWindow.isPIPMenu()) {
                return false;
            }

            // If the window is completely covered by other windows - ignore.
            if (unaccountedSpace.quickReject(regionInScreen)) {
                return false;
            }

            // Add windows of certain types not covered by modal windows.
            if (isReportedWindowType(a11yWindow.getType())) {
                return true;
            }

            return false;
        }

        private static boolean isReportedWindowType(int windowType) {
            return (windowType != WindowManager.LayoutParams.TYPE_WALLPAPER
                    && windowType != WindowManager.LayoutParams.TYPE_BOOT_PROGRESS
                    && windowType != WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_DRAG
                    && windowType != WindowManager.LayoutParams.TYPE_INPUT_CONSUMER
                    && windowType != WindowManager.LayoutParams.TYPE_POINTER
                    && windowType != TYPE_MAGNIFICATION_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        }

        // Some windows should be excluded from unaccounted space computation, though they still
        // should be reported
        private static boolean windowMattersToUnaccountedSpaceComputation(
                AccessibilityWindow a11yWindow) {
            // Do not account space of trusted non-touchable windows, except the split-screen
            // divider.
            // If it's not trusted, touch events are not sent to the windows behind it.
            if (!a11yWindow.isTouchable()
                    && (a11yWindow.getType() != TYPE_DOCK_DIVIDER)
                    && a11yWindow.isTrustedOverlay()) {
                return false;
            }

            if (a11yWindow.getType() == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                return false;
            }
            return true;
        }

        private void updateWindowsByWindowAttributesLocked(List<WindowInfo> windows) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                final WindowInfo windowInfo = windows.get(i);
                final IBinder token = windowInfo.token;
                final int windowId = findWindowIdLocked(
                        mAccessibilityUserManager.getCurrentUserIdLocked(), token);
                updateWindowWithWindowAttributes(windowInfo, mWindowAttributes.get(windowId));
            }
        }

        private void updateWindowWithWindowAttributes(@NonNull WindowInfo windowInfo,
                @Nullable AccessibilityWindowAttributes attributes) {
            if (attributes == null) {
                return;
            }
            windowInfo.title = attributes.getWindowTitle();
            windowInfo.locales = attributes.getLocales();
        }

        private boolean shouldUpdateWindowsLocked(boolean forceSend,
                @NonNull List<WindowInfo> windows) {
            if (forceSend) {
                return true;
            }

            final int windowCount = windows.size();
            if (VERBOSE) {
                Slogf.v(LOG_TAG,
                        "shouldUpdateWindowsLocked(): mDisplayId=%d, windowCount=%d, "
                        + "mCachedWindowInfos.size()=%d, windows.size()=%d", mDisplayId,
                        windowCount, mCachedWindowInfos.size(), windows.size());
            }
            // We computed the windows and if they changed notify the client.
            if (mCachedWindowInfos.size() != windowCount) {
                // Different size means something changed.
                return true;
            } else if (!mCachedWindowInfos.isEmpty() || !windows.isEmpty()) {
                // Since we always traverse windows from high to low layer
                // the old and new windows at the same index should be the
                // same, otherwise something changed.
                for (int i = 0; i < windowCount; i++) {
                    WindowInfo oldWindow = mCachedWindowInfos.get(i);
                    WindowInfo newWindow = windows.get(i);
                    // We do not care for layer changes given the window
                    // order does not change. This brings no new information
                    // to the clients.
                    if (windowChangedNoLayer(oldWindow, newWindow)) {
                        return true;
                    }
                }
            }

            return false;
        }

        private void cacheWindows(List<WindowInfo> windows) {
            final int oldWindowCount = mCachedWindowInfos.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                mCachedWindowInfos.remove(i).recycle();
            }
            final int newWindowCount = windows.size();
            for (int i = 0; i < newWindowCount; i++) {
                WindowInfo newWindow = windows.get(i);
                mCachedWindowInfos.add(WindowInfo.obtain(newWindow));
            }
        }

        private boolean windowChangedNoLayer(WindowInfo oldWindow, WindowInfo newWindow) {
            if (oldWindow == newWindow) {
                return false;
            }
            if (oldWindow == null) {
                return true;
            }
            if (newWindow == null) {
                return true;
            }
            if (oldWindow.type != newWindow.type) {
                return true;
            }
            if (oldWindow.focused != newWindow.focused) {
                return true;
            }
            if (oldWindow.token == null) {
                if (newWindow.token != null) {
                    return true;
                }
            } else if (!oldWindow.token.equals(newWindow.token)) {
                return true;
            }
            if (oldWindow.parentToken == null) {
                if (newWindow.parentToken != null) {
                    return true;
                }
            } else if (!oldWindow.parentToken.equals(newWindow.parentToken)) {
                return true;
            }
            if (oldWindow.activityToken == null) {
                if (newWindow.activityToken != null) {
                    return true;
                }
            } else if (!oldWindow.activityToken.equals(newWindow.activityToken)) {
                return true;
            }
            if (!oldWindow.regionInScreen.equals(newWindow.regionInScreen)) {
                return true;
            }
            if (oldWindow.childTokens != null && newWindow.childTokens != null
                    && !oldWindow.childTokens.equals(newWindow.childTokens)) {
                return true;
            }
            if (!TextUtils.equals(oldWindow.title, newWindow.title)) {
                return true;
            }
            if (oldWindow.accessibilityIdOfAnchor != newWindow.accessibilityIdOfAnchor) {
                return true;
            }
            if (oldWindow.inPictureInPicture != newWindow.inPictureInPicture) {
                return true;
            }
            if (oldWindow.hasFlagWatchOutsideTouch != newWindow.hasFlagWatchOutsideTouch) {
                return true;
            }
            if (oldWindow.displayId != newWindow.displayId) {
                return true;
            }
            if (oldWindow.taskId != newWindow.taskId) {
                return true;
            }
            if (!Arrays.equals(oldWindow.mTransformMatrix, newWindow.mTransformMatrix)) {
                return true;
            }
            return false;
        }

        /**
         * Clears all {@link AccessibilityWindowInfo}s and {@link WindowInfo}s.
         */
        private void clearWindowsLocked() {
            final List<WindowInfo> windows = Collections.emptyList();
            final int activeWindowId = mActiveWindowId;
            // UserId is useless in updateWindowsLocked, when we update a empty window list.
            // Just pass current userId here.
            updateWindowsLocked(mAccessibilityUserManager.getCurrentUserIdLocked(), windows);
            // Do not reset mActiveWindowId here. mActiveWindowId will be clear after accessibility
            // interaction connection removed.
            mActiveWindowId = activeWindowId;
            mWindows = null;
        }

        /**
         * Updates windows info according to specified userId and windows.
         *
         * @param userId The userId to update
         * @param windows The windows to update
         */
        private void updateWindowsLocked(int userId, @NonNull List<WindowInfo> windows) {
            if (mWindows == null) {
                mWindows = new ArrayList<>();
            }

            final List<AccessibilityWindowInfo> oldWindowList = new ArrayList<>(mWindows);
            final SparseArray<AccessibilityWindowInfo> oldWindowsById = mA11yWindowInfoById.clone();
            boolean shouldClearAccessibilityFocus = false;

            mWindows.clear();
            mA11yWindowInfoById.clear();

            for (int i = 0; i < mWindowInfoById.size(); i++) {
                mWindowInfoById.valueAt(i).recycle();
            }
            mWindowInfoById.clear();
            mHasWatchOutsideTouchWindow = false;

            final int windowCount = windows.size();
            final boolean isTopFocusedDisplay = mDisplayId == mTopFocusedDisplayId;
            // A proxy with an a11y-focused window is a11y-focused should use the proxy focus id.
            final boolean isAccessibilityFocusedDisplay =
                    mDisplayId == mAccessibilityFocusedDisplayId
                            || (mIsProxy && mProxyDisplayAccessibilityFocusedWindow
                                    != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID);
            // Modifies the value of top focused window, active window and a11y focused window
            // only if this display is top focused display which has the top focused window.
            if (isTopFocusedDisplay) {
                if (windowCount > 0) {
                    // Sets the top focus window by top focused window token.
                    mTopFocusedWindowId = findWindowIdLocked(userId, mTopFocusedWindowToken);
                } else {
                    // Resets the top focus window when stopping tracking window of this display.
                    mTopFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                }
                // The active window doesn't need to be reset if the touch operation is progressing.
                if (!mTouchInteractionInProgress) {
                    mActiveWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                }
            }

            // If the active window goes away while the user is touch exploring we
            // reset the active window id and wait for the next hover event from
            // under the user's finger to determine which one is the new one. It
            // is possible that the finger is not moving and the input system
            // filters out such events.
            boolean activeWindowGone = true;

            // We'll clear accessibility focus if the window with focus is no longer visible to
            // accessibility services.
            int a11yFocusedWindowId = mIsProxy
                    ? mProxyDisplayAccessibilityFocusedWindow
                    : mAccessibilityFocusedWindowId;
            if (isAccessibilityFocusedDisplay) {
                shouldClearAccessibilityFocus = a11yFocusedWindowId
                        != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }

            boolean hasWindowIgnore = false;
            if (windowCount > 0) {
                for (int i = 0; i < windowCount; i++) {
                    final WindowInfo windowInfo = windows.get(i);
                    final AccessibilityWindowInfo window;
                    if (mTrackingWindows) {
                        window = populateReportedWindowLocked(userId, windowInfo, oldWindowsById);
                        if (window == null) {
                            hasWindowIgnore = true;
                        }
                    } else {
                        window = null;
                    }
                    if (window != null) {

                        // Flip layers in list to be consistent with AccessibilityService#getWindows
                        window.setLayer(windowCount - 1 - window.getLayer());

                        final int windowId = window.getId();
                        if (window.isFocused() && isTopFocusedDisplay) {
                            if (!mTouchInteractionInProgress) {
                                // This display is top one, and sets the focus window
                                // as active window.
                                mActiveWindowId = windowId;
                                window.setActive(true);
                            } else if (windowId == mActiveWindowId) {
                                activeWindowGone = false;
                            }
                        }
                        if (!mHasWatchOutsideTouchWindow && windowInfo.hasFlagWatchOutsideTouch) {
                            mHasWatchOutsideTouchWindow = true;
                        }
                        mWindows.add(window);
                        mA11yWindowInfoById.put(windowId, window);
                        mWindowInfoById.put(windowId, WindowInfo.obtain(windowInfo));
                    }
                }
                final int accessibilityWindowCount = mWindows.size();
                // Re-order the window layer of all windows in the windows list because there's
                // window not been added into the windows list.
                if (hasWindowIgnore) {
                    for (int i = 0; i < accessibilityWindowCount; i++) {
                        mWindows.get(i).setLayer(accessibilityWindowCount - 1 - i);
                    }
                }
                if (isTopFocusedDisplay) {
                    if (mTouchInteractionInProgress && activeWindowGone) {
                        mActiveWindowId = mTopFocusedWindowId;
                    }
                    // Focused window may change the active one, so set the
                    // active window once we decided which it is.
                    for (int i = 0; i < accessibilityWindowCount; i++) {
                        final AccessibilityWindowInfo window = mWindows.get(i);
                        if (window.getId() == mActiveWindowId) {
                            window.setActive(true);
                        }
                    }
                }
                if (isAccessibilityFocusedDisplay) {
                    for (int i = 0; i < accessibilityWindowCount; i++) {
                        final AccessibilityWindowInfo window = mWindows.get(i);
                        if (window.getId() == a11yFocusedWindowId) {
                            window.setAccessibilityFocused(true);
                            shouldClearAccessibilityFocus = false;
                            break;
                        }
                    }
                }
            }

            sendEventsForChangedWindowsLocked(oldWindowList, oldWindowsById);

            final int oldWindowCount = oldWindowList.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                oldWindowList.remove(i).recycle();
            }

            if (shouldClearAccessibilityFocus) {
                clearAccessibilityFocusLocked(a11yFocusedWindowId);
            }
        }

        private void sendEventsForChangedWindowsLocked(List<AccessibilityWindowInfo> oldWindows,
                SparseArray<AccessibilityWindowInfo> oldWindowsById) {
            List<AccessibilityEvent> events = new ArrayList<>();
            // Sends events for all removed windows.
            final int oldWindowsCount = oldWindows.size();
            for (int i = 0; i < oldWindowsCount; i++) {
                final AccessibilityWindowInfo window = oldWindows.get(i);
                if (mA11yWindowInfoById.get(window.getId()) == null) {
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(
                            mDisplayId, window.getId(), AccessibilityEvent.WINDOWS_CHANGE_REMOVED));
                }
            }

            // Looks for other changes.
            final int newWindowCount = mWindows.size();
            for (int i = 0; i < newWindowCount; i++) {
                final AccessibilityWindowInfo newWindow = mWindows.get(i);
                final AccessibilityWindowInfo oldWindow = oldWindowsById.get(newWindow.getId());
                if (oldWindow == null) {
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(mDisplayId,
                            newWindow.getId(), AccessibilityEvent.WINDOWS_CHANGE_ADDED));
                } else {
                    int changes = newWindow.differenceFrom(oldWindow);
                    if (changes !=  0) {
                        events.add(AccessibilityEvent.obtainWindowsChangedEvent(
                                mDisplayId, newWindow.getId(), changes));
                    }
                }
            }

            final int numEvents = events.size();
            for (int i = 0; i < numEvents; i++) {
                mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(events.get(i));
            }
        }

        private AccessibilityWindowInfo populateReportedWindowLocked(int userId,
                WindowInfo window, SparseArray<AccessibilityWindowInfo> oldWindowsById) {
            final int windowId = findWindowIdLocked(userId, window.token);
            if (windowId < 0) {
                return null;
            }

            // Don't need to add the embedded hierarchy windows into the accessibility windows list.
            if (isEmbeddedHierarchyWindowsLocked(windowId)) {
                return null;
            }
            final AccessibilityWindowInfo reportedWindow = AccessibilityWindowInfo.obtain();

            reportedWindow.setId(windowId);
            reportedWindow.setType(getTypeForWindowManagerWindowType(window.type));
            reportedWindow.setLayer(window.layer);
            reportedWindow.setFocused(window.focused);
            reportedWindow.setRegionInScreen(window.regionInScreen);
            reportedWindow.setTitle(window.title);
            reportedWindow.setAnchorId(window.accessibilityIdOfAnchor);
            reportedWindow.setPictureInPicture(window.inPictureInPicture);
            reportedWindow.setDisplayId(window.displayId);
            reportedWindow.setTaskId(window.taskId);
            reportedWindow.setLocales(window.locales);

            final int parentId = findWindowIdLocked(userId, window.parentToken);
            if (parentId >= 0) {
                reportedWindow.setParentId(parentId);
            }

            if (window.childTokens != null) {
                final int childCount = window.childTokens.size();
                for (int i = 0; i < childCount; i++) {
                    final IBinder childToken = window.childTokens.get(i);
                    final int childId = findWindowIdLocked(userId, childToken);
                    if (childId >= 0) {
                        reportedWindow.addChild(childId);
                    }
                }
            }

            final AccessibilityWindowInfo oldWindowInfo = oldWindowsById.get(windowId);
            if (oldWindowInfo == null) {
                reportedWindow.setTransitionTimeMillis(SystemClock.uptimeMillis());
            } else {
                final Region oldTouchRegion = new Region();
                oldWindowInfo.getRegionInScreen(oldTouchRegion);
                if (oldTouchRegion.equals(window.regionInScreen)) {
                    reportedWindow.setTransitionTimeMillis(oldWindowInfo.getTransitionTimeMillis());
                } else {
                    reportedWindow.setTransitionTimeMillis(SystemClock.uptimeMillis());
                }
            }
            return reportedWindow;
        }

        private int getTypeForWindowManagerWindowType(int windowType) {
            switch (windowType) {
                case WindowManager.LayoutParams.TYPE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_STARTING:
                case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_BASE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION:
                case WindowManager.LayoutParams.TYPE_PHONE:
                case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                case WindowManager.LayoutParams.TYPE_TOAST:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG:
                case WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_APPLICATION;
                }

                case WindowManager.LayoutParams.TYPE_INPUT_METHOD: {
                    return AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                }

                case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR:
                case WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                case WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SCREENSHOT: {
                    return AccessibilityWindowInfo.TYPE_SYSTEM;
                }

                case WindowManager.LayoutParams.TYPE_DOCK_DIVIDER: {
                    return AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER;
                }

                case TYPE_ACCESSIBILITY_OVERLAY: {
                    return AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
                }

                case WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY: {
                    return AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY;
                }

                default: {
                    return -1;
                }
            }
        }

        /**
         * Dumps all {@link AccessibilityWindowInfo}s here.
         */
        void dumpLocked(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (mIsProxy) {
                pw.println("Proxy accessibility focused window = "
                        + mProxyDisplayAccessibilityFocusedWindow);
                pw.println();
            }
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int j = 0; j < windowCount; j++) {
                    if (j == 0) {
                        pw.append("Display[");
                        pw.append(Integer.toString(mDisplayId));
                        pw.append("] : ");
                        pw.println();
                    }
                    if (j > 0) {
                        pw.append(',');
                        pw.println();
                    }
                    pw.append("A11yWindow[");
                    AccessibilityWindowInfo window = mWindows.get(j);
                    pw.append(window.toString());
                    pw.append(']');
                    pw.println();
                    final WindowInfo windowInfo = findWindowInfoByIdLocked(window.getId());
                    if (windowInfo != null) {
                        pw.append("WindowInfo[");
                        pw.append(windowInfo.toString());
                        pw.append("]");
                        pw.println();
                    }

                }
                pw.println();
            }
        }

    }
    /**
     * Interface to send {@link AccessibilityEvent}.
     */
    public interface AccessibilityEventSender {
        /**
         * Sends {@link AccessibilityEvent} for current user.
         */
        void sendAccessibilityEventForCurrentUserLocked(AccessibilityEvent event);
    }

    /**
     * Wrapper of accessibility interaction connection for window.
     */
    // In order to avoid using DexmakerShareClassLoaderRule, make this class visible for testing.
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public final class RemoteAccessibilityConnection implements IBinder.DeathRecipient {
        private final int mUid;
        private final String mPackageName;
        private final int mWindowId;
        private final int mUserId;
        private final IAccessibilityInteractionConnection mConnection;

        RemoteAccessibilityConnection(int windowId,
                IAccessibilityInteractionConnection connection,
                String packageName, int uid, int userId) {
            mWindowId = windowId;
            mPackageName = packageName;
            mUid = uid;
            mUserId = userId;
            mConnection = connection;
        }

        int getUid() {
            return  mUid;
        }

        String getPackageName() {
            return mPackageName;
        }

        IAccessibilityInteractionConnection getRemote() {
            return mConnection;
        }

        void linkToDeath() throws RemoteException {
            mConnection.asBinder().linkToDeath(this, 0);
        }

        void unlinkToDeath() {
            mConnection.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            unlinkToDeath();
            synchronized (mLock) {
                removeAccessibilityInteractionConnectionLocked(mWindowId, mUserId);
            }
        }
    }

    /**
     * Constructor for AccessibilityWindowManager.
     */
    public AccessibilityWindowManager(@NonNull Object lock, @NonNull Handler handler,
            @NonNull WindowManagerInternal windowManagerInternal,
            @NonNull AccessibilityEventSender accessibilityEventSender,
            @NonNull AccessibilitySecurityPolicy securityPolicy,
            @NonNull AccessibilityUserManager accessibilityUserManager,
            @NonNull AccessibilityTraceManager traceManager) {
        mLock = lock;
        mHandler = handler;
        mWindowManagerInternal = windowManagerInternal;
        mAccessibilityEventSender = accessibilityEventSender;
        mSecurityPolicy = securityPolicy;
        mAccessibilityUserManager = accessibilityUserManager;
        mTraceManager = traceManager;
    }

    /**
     * Starts tracking windows changes from window manager for specified display.
     *
     * @param displayId The logical display id.
     */
    public void startTrackingWindows(int displayId, boolean proxyed) {
        synchronized (mLock) {
            DisplayWindowsObserver observer = mDisplayWindowsObservers.get(displayId);
            if (observer == null) {
                observer = new DisplayWindowsObserver(displayId);
            }
            if (proxyed && !observer.mIsProxy) {
                observer.mIsProxy = true;
                mHasProxy = true;
            }
            if (observer.isTrackingWindowsLocked()) {
                return;
            }
            observer.startTrackingWindowsLocked();
            mDisplayWindowsObservers.put(displayId, observer);
        }
    }

    /**
     * Stops tracking windows changes from window manager, and clear all windows info for specified
     * display.
     *
     * @param displayId The logical display id.
     */
    public void stopTrackingWindows(int displayId) {
        synchronized (mLock) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.get(displayId);
            if (observer != null) {
                observer.stopTrackingWindowsLocked();
                mDisplayWindowsObservers.remove(displayId);
            }
            resetHasProxyIfNeededLocked();
        }
    }

    /**
     * Stops tracking a display as belonging to a proxy.
     * @param displayId
     */
    public void stopTrackingDisplayProxy(int displayId) {
        synchronized (mLock) {
            final DisplayWindowsObserver proxyObserver = mDisplayWindowsObservers.get(displayId);
            if (proxyObserver != null) {
                proxyObserver.mIsProxy = false;
            }
            resetHasProxyIfNeededLocked();
        }
    }

    private void resetHasProxyIfNeededLocked() {
        boolean hasProxy = false;
        final int count = mDisplayWindowsObservers.size();
        for (int i = 0; i < count; i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
            if (observer != null) {
                if (observer.mIsProxy) {
                    hasProxy = true;
                }
            }
        }
        mHasProxy = hasProxy;
    }

    /**
     * Checks if we are tracking windows on any display.
     *
     * @return {@code true} if the observer is tracking windows on any display,
     * {@code false} otherwise.
     */
    public boolean isTrackingWindowsLocked() {
        final int count = mDisplayWindowsObservers.size();
        if (count > 0) {
            return true;
        }
        return false;
    }

    private boolean isProxyed(int displayId) {
        final DisplayWindowsObserver observer = mDisplayWindowsObservers.get(displayId);
        return (observer != null && observer.mIsProxy);
    }

    void moveNonProxyTopFocusedDisplayToTopIfNeeded() {
        if (mHasProxy
                && (mLastNonProxyTopFocusedDisplayId != mTopFocusedDisplayId)) {
            mWindowManagerInternal.moveDisplayToTopIfAllowed(mLastNonProxyTopFocusedDisplayId);
        }
    }
    int getLastNonProxyTopFocusedDisplayId() {
        return mLastNonProxyTopFocusedDisplayId;
    }

    /**
     * Checks if we are tracking windows on specified display.
     *
     * @param displayId The logical display id.
     * @return {@code true} if the observer is tracking windows on specified display,
     * {@code false} otherwise.
     */
    public boolean isTrackingWindowsLocked(int displayId) {
        final DisplayWindowsObserver observer = mDisplayWindowsObservers.get(displayId);
        if (observer != null) {
            return observer.isTrackingWindowsLocked();
        }
        return false;
    }

    /**
     * Returns accessibility windows for specified display.
     *
     * @param displayId The logical display id.
     * @return accessibility windows for specified display.
     */
    @Nullable
    public List<AccessibilityWindowInfo> getWindowListLocked(int displayId) {
        final DisplayWindowsObserver observer = mDisplayWindowsObservers.get(displayId);
        if (observer != null) {
            return observer.getWindowListLocked();
        }
        return null;
    }

    /**
     * Adds accessibility interaction connection according to given window token, package name and
     * window token.
     *
     * @param window The window token of accessibility interaction connection
     * @param leashToken The leash token of accessibility interaction connection
     * @param connection The accessibility interaction connection
     * @param packageName The package name
     * @param userId The userId
     * @return The windowId of added connection
     * @throws RemoteException
     */
    public int addAccessibilityInteractionConnection(@NonNull IWindow window,
            @NonNull IBinder leashToken, @NonNull IAccessibilityInteractionConnection connection,
            @NonNull String packageName, int userId) throws RemoteException {
        final int windowId;
        boolean shouldComputeWindows = false;
        final IBinder token = window.asBinder();
        if (traceWMEnabled()) {
            logTraceWM("getDisplayIdForWindow", "token=" + token);
        }
        final int displayId = mWindowManagerInternal.getDisplayIdForWindow(token);
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            final int resolvedUid = UserHandle.getUid(resolvedUserId, UserHandle.getCallingAppId());

            // Makes sure the reported package is one the caller has access to.
            packageName = mSecurityPolicy.resolveValidReportedPackageLocked(
                    packageName, UserHandle.getCallingAppId(), resolvedUserId,
                    Binder.getCallingPid());

            windowId = sNextWindowId++;
            // If the window is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, UserHandle.USER_ALL);
                wrapper.linkToDeath();
                mGlobalInteractionConnections.put(windowId, wrapper);
                mGlobalWindowTokens.put(windowId, token);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and token: " + token);
                }
            } else {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, resolvedUserId);
                wrapper.linkToDeath();
                getInteractionConnectionsForUserLocked(resolvedUserId).put(windowId, wrapper);
                getWindowTokensForUserLocked(resolvedUserId).put(windowId, token);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and  token: " + token);
                }
            }

            if (isTrackingWindowsLocked(displayId)) {
                shouldComputeWindows = true;
            }
            registerIdLocked(leashToken, windowId);
        }
        if (shouldComputeWindows) {
            if (traceWMEnabled()) {
                logTraceWM("computeWindowsForAccessibility", "displayId=" + displayId);
            }
            mWindowManagerInternal.computeWindowsForAccessibility(displayId);
        }
        if (traceWMEnabled()) {
            logTraceWM("setAccessibilityIdToSurfaceMetadata",
                    "token=" + token + ";windowId=" + windowId);
        }
        mWindowManagerInternal.setAccessibilityIdToSurfaceMetadata(token, windowId);
        return windowId;
    }

    /**
     * Removes accessibility interaction connection according to given window token.
     *
     * @param window The window token of accessibility interaction connection
     */
    public void removeAccessibilityInteractionConnection(@NonNull IWindow window) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                    UserHandle.getCallingUserId());
            IBinder token = window.asBinder();
            final int removedWindowId = removeAccessibilityInteractionConnectionInternalLocked(
                    token, mGlobalWindowTokens, mGlobalInteractionConnections);
            if (removedWindowId >= 0) {
                onAccessibilityInteractionConnectionRemovedLocked(removedWindowId, token);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Removed global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + removedWindowId + " and token: "
                            + window.asBinder());
                }
                return;
            }
            final int userCount = mWindowTokens.size();
            for (int i = 0; i < userCount; i++) {
                final int userId = mWindowTokens.keyAt(i);
                final int removedWindowIdForUser =
                        removeAccessibilityInteractionConnectionInternalLocked(token,
                                getWindowTokensForUserLocked(userId),
                                getInteractionConnectionsForUserLocked(userId));
                if (removedWindowIdForUser >= 0) {
                    onAccessibilityInteractionConnectionRemovedLocked(
                            removedWindowIdForUser, token);
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Removed user connection for pid:" + Binder.getCallingPid()
                                + " with windowId: " + removedWindowIdForUser + " and userId:"
                                + userId + " and token: " + window.asBinder());
                    }
                    return;
                }
            }
        }
    }

    /**
     * Resolves a connection wrapper for a window id.
     *
     * @param userId The user id for any user-specific windows
     * @param windowId The id of the window of interest
     *
     * @return a connection to the window
     */
    @Nullable
    public RemoteAccessibilityConnection getConnectionLocked(int userId, int windowId) {
        if (VERBOSE) {
            Slog.i(LOG_TAG, "Trying to get interaction connection to windowId: " + windowId);
        }
        RemoteAccessibilityConnection connection = mGlobalInteractionConnections.get(windowId);
        if (connection == null && isValidUserForInteractionConnectionsLocked(userId)) {
            connection = getInteractionConnectionsForUserLocked(userId).get(windowId);
        }
        if (connection != null && connection.getRemote() != null) {
            return connection;
        }
        if (DEBUG) {
            Slog.e(LOG_TAG, "No interaction connection to window: " + windowId);
        }
        return null;
    }

    private int removeAccessibilityInteractionConnectionInternalLocked(IBinder windowToken,
            SparseArray<IBinder> windowTokens, SparseArray<RemoteAccessibilityConnection>
                    interactionConnections) {
        final int count = windowTokens.size();
        for (int i = 0; i < count; i++) {
            if (windowTokens.valueAt(i) == windowToken) {
                final int windowId = windowTokens.keyAt(i);
                windowTokens.removeAt(i);
                RemoteAccessibilityConnection wrapper = interactionConnections.get(windowId);
                wrapper.unlinkToDeath();
                interactionConnections.remove(windowId);
                return windowId;
            }
        }
        return -1;
    }

    /**
     * Removes accessibility interaction connection according to given windowId and userId.
     *
     * @param windowId The windowId of accessibility interaction connection
     * @param userId The userId to remove
     */
    private void removeAccessibilityInteractionConnectionLocked(int windowId, int userId) {
        IBinder window = null;
        if (userId == UserHandle.USER_ALL) {
            window = mGlobalWindowTokens.get(windowId);
            mGlobalWindowTokens.remove(windowId);
            mGlobalInteractionConnections.remove(windowId);
        } else {
            if (isValidUserForWindowTokensLocked(userId)) {
                window = getWindowTokensForUserLocked(userId).get(windowId);
                getWindowTokensForUserLocked(userId).remove(windowId);
            }
            if (isValidUserForInteractionConnectionsLocked(userId)) {
                getInteractionConnectionsForUserLocked(userId).remove(windowId);
            }
        }
        onAccessibilityInteractionConnectionRemovedLocked(windowId, window);
        if (DEBUG) {
            Slog.i(LOG_TAG, "Removing interaction connection to windowId: " + windowId);
        }
    }

    /**
     * Invoked when accessibility interaction connection of window is removed.
     *
     * @param windowId Removed windowId
     * @param binder Removed window token
     */
    private void onAccessibilityInteractionConnectionRemovedLocked(
            int windowId, @Nullable IBinder binder) {
        // Active window will not update, if windows callback is unregistered.
        // Update active window to invalid, when its a11y interaction connection is removed.
        if (!isTrackingWindowsLocked() && windowId >= 0 && mActiveWindowId == windowId) {
            mActiveWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        }
        if (binder != null) {
            if (traceWMEnabled()) {
                logTraceWM("setAccessibilityIdToSurfaceMetadata", "token=" + binder
                        + ";windowId=AccessibilityWindowInfo.UNDEFINED_WINDOW_ID");
            }
            mWindowManagerInternal.setAccessibilityIdToSurfaceMetadata(
                    binder, AccessibilityWindowInfo.UNDEFINED_WINDOW_ID);
        }
        unregisterIdLocked(windowId);
        mWindowAttributes.remove(windowId);
    }

    /**
     * Gets window token according to given userId and windowId.
     *
     * @param userId The userId
     * @param windowId The windowId
     * @return The window token
     */
    @Nullable
    public IBinder getWindowTokenForUserAndWindowIdLocked(int userId, int windowId) {
        IBinder windowToken = mGlobalWindowTokens.get(windowId);
        if (windowToken == null && isValidUserForWindowTokensLocked(userId)) {
            windowToken = getWindowTokensForUserLocked(userId).get(windowId);
        }
        return windowToken;
    }

    /**
     * Returns the userId that owns the given window token, {@link UserHandle#USER_NULL}
     * if not found.
     *
     * @param windowToken The window token
     * @return The userId
     */
    public int getWindowOwnerUserId(@NonNull IBinder windowToken) {
        if (traceWMEnabled()) {
            logTraceWM("getWindowOwnerUserId", "token=" + windowToken);
        }
        return mWindowManagerInternal.getWindowOwnerUserId(windowToken);
    }

    /**
     * Returns windowId of given userId and window token.
     *
     * @param userId The userId
     * @param token The window token
     * @return The windowId
     */
    public int findWindowIdLocked(int userId, @NonNull IBinder token) {
        final int globalIndex = mGlobalWindowTokens.indexOfValue(token);
        if (globalIndex >= 0) {
            return mGlobalWindowTokens.keyAt(globalIndex);
        }
        if (isValidUserForWindowTokensLocked(userId)) {
            final int userIndex = getWindowTokensForUserLocked(userId).indexOfValue(token);
            if (userIndex >= 0) {
                return getWindowTokensForUserLocked(userId).keyAt(userIndex);
            }
        }
        return -1;
    }

    /**
     * Establish the relationship between the host and the embedded view hierarchy.
     *
     * @param host The token of host hierarchy
     * @param embedded The token of the embedded hierarchy
     */
    public void associateEmbeddedHierarchyLocked(@NonNull IBinder host, @NonNull IBinder embedded) {
        // Use embedded window as key, since one host window may have multiple embedded windows.
        associateLocked(embedded, host);
    }

    /**
     * Clear the relationship by given token.
     *
     * @param token The token
     */
    public void disassociateEmbeddedHierarchyLocked(@NonNull IBinder token) {
        disassociateLocked(token);
    }

    /**
     * Gets the parent windowId of the window according to the specified windowId.
     *
     * @param windowId The windowId to check
     * @return The windowId of the parent window, or self if no parent exists
     */
    public int resolveParentWindowIdLocked(int windowId) {
        final IBinder token = getLeashTokenLocked(windowId);
        if (token == null) {
            return windowId;
        }
        final IBinder resolvedToken = resolveTopParentTokenLocked(token);
        final int resolvedWindowId = getWindowIdLocked(resolvedToken);
        return resolvedWindowId != -1 ? resolvedWindowId : windowId;
    }

    private IBinder resolveTopParentTokenLocked(IBinder token) {
        final IBinder hostToken = getHostTokenLocked(token);
        if (hostToken == null) {
            return token;
        }
        return resolveTopParentTokenLocked(hostToken);
    }

    /**
     * Computes partial interactive region of given windowId.
     *
     * @param windowId The windowId
     * @param outRegion The output to which to write the bounds.
     * @return true if outRegion is not empty.
     */
    public boolean computePartialInteractiveRegionForWindowLocked(int windowId,
            @NonNull Region outRegion) {
        final int parentWindowId = resolveParentWindowIdLocked(windowId);
        final DisplayWindowsObserver observer = getDisplayWindowObserverByWindowIdLocked(
                parentWindowId);

        if (observer != null) {
            return observer.computePartialInteractiveRegionForWindowLocked(parentWindowId,
                    parentWindowId != windowId, outRegion);
        }

        return false;
    }

    /**
     * Updates active windowId and accessibility focused windowId according to given accessibility
     * event and action.
     *
     * @param userId The userId
     * @param windowId The windowId of accessibility event
     * @param nodeId The accessibility node id of accessibility event
     * @param eventType The accessibility event type
     * @param eventAction The accessibility event action
     */
    public void updateActiveAndAccessibilityFocusedWindowLocked(int userId, int windowId,
            long nodeId, int eventType, int eventAction) {
        // The active window is either the window that has input focus or
        // the window that the user is currently touching. If the user is
        // touching a window that does not have input focus as soon as the
        // the user stops touching that window the focused window becomes
        // the active one. Here we detect the touched window and make it
        // active. In updateWindowsLocked() we update the focused window
        // and if the user is not touching the screen, we make the focused
        // window the active one.
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                // If no service has the capability to introspect screen,
                // we do not register callback in the window manager for
                // window changes, so we have to ask the window manager
                // what the focused window is to update the active one.
                // The active window also determined events from which
                // windows are delivered.
                synchronized (mLock) {
                    if (!isTrackingWindowsLocked()) {
                        mTopFocusedWindowId = findFocusedWindowId(userId);
                        if (windowId == mTopFocusedWindowId) {
                            mActiveWindowId = windowId;
                        }
                    }
                }
            } break;

            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER: {
                // Do not allow delayed hover events to confuse us
                // which the active window is.
                synchronized (mLock) {
                    if (mTouchInteractionInProgress && mActiveWindowId != windowId) {
                        setActiveWindowLocked(windowId);
                    }
                }
            } break;

            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                synchronized (mLock) {
                    // If window id belongs to a proxy display, then find the display, update the
                    // observer focus and send WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED events.
                    if (mHasProxy && setProxyFocusLocked(windowId)) {
                        return;
                    }
                    if (mAccessibilityFocusedWindowId != windowId) {
                        clearAccessibilityFocusLocked(mAccessibilityFocusedWindowId);
                        setAccessibilityFocusedWindowLocked(windowId);
                    }
                    mAccessibilityFocusNodeId = nodeId;
                }
            } break;

            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                synchronized (mLock) {
                    // If cleared happened on the proxy display, then clear the tracked focus.
                    if (mHasProxy && clearProxyFocusLocked(windowId, eventAction)) {
                        return;
                    }
                    if (mAccessibilityFocusNodeId == nodeId) {
                        mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                    }
                    // Clear the window with focus if it no longer has focus and we aren't
                    // just moving focus from one view to the other in the same window.
                    if ((mAccessibilityFocusNodeId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID)
                            && (mAccessibilityFocusedWindowId == windowId)
                            && (eventAction != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                        mAccessibilityFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                        mAccessibilityFocusedDisplayId = Display.INVALID_DISPLAY;
                    }
                }
            } break;
        }
    }

    /**
     * Callbacks from AccessibilityManagerService when touch explorer turn on and
     * motion down detected.
     */
    public void onTouchInteractionStart() {
        synchronized (mLock) {
            mTouchInteractionInProgress = true;
        }
    }

    /**
     * Callbacks from AccessibilityManagerService when touch explorer turn on and
     * gesture or motion up detected.
     */
    public void onTouchInteractionEnd() {
        synchronized (mLock) {
            mTouchInteractionInProgress = false;
            // We want to set the active window to be current immediately
            // after the user has stopped touching the screen since if the
            // user types with the IME they should get a feedback for the
            // letter typed in the text view which is in the input focused
            // window. Note that we always deliver hover accessibility events
            // (they are a result of user touching the screen) so change of
            // the active window before all hover accessibility events from
            // the touched window are delivered is fine.
            final int oldActiveWindow = mActiveWindowId;
            setActiveWindowLocked(mTopFocusedWindowId);
            if (oldActiveWindow != mActiveWindowId
                    && mAccessibilityFocusedWindowId == oldActiveWindow
                    && accessibilityFocusOnlyInActiveWindowLocked()) {
                clearAccessibilityFocusLocked(oldActiveWindow);
            }
        }
    }

    /**
     * Gets the id of the current active window.
     *
     * @return The userId
     */
    public int getActiveWindowId(int userId) {
        if (mActiveWindowId == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID
                && !mTouchInteractionInProgress) {
            mActiveWindowId = findFocusedWindowId(userId);
        }
        return mActiveWindowId;
    }

    private void setActiveWindowLocked(int windowId) {
        if (mActiveWindowId != windowId) {
            List<AccessibilityEvent> events = new ArrayList<>(2);
            if (mActiveWindowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                final DisplayWindowsObserver observer =
                        getDisplayWindowObserverByWindowIdLocked(mActiveWindowId);
                if (observer != null) {
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(observer.mDisplayId,
                            mActiveWindowId, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE));
                }
            }

            mActiveWindowId = windowId;
            // Goes through all windows for each display.
            final int count = mDisplayWindowsObservers.size();
            for (int i = 0; i < count; i++) {
                final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
                if (observer != null && observer.setActiveWindowLocked(windowId)) {
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(observer.mDisplayId,
                            windowId, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE));
                }
            }

            for (final AccessibilityEvent event : events) {
                mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(event);
            }
        }
    }

    private void setAccessibilityFocusedWindowLocked(int windowId) {
        if (mAccessibilityFocusedWindowId != windowId) {
            List<AccessibilityEvent> events = new ArrayList<>(2);
            if (mAccessibilityFocusedDisplayId != Display.INVALID_DISPLAY
                    && mAccessibilityFocusedWindowId
                    != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                // Previously focused window -> send a focused event for losing focus
                events.add(AccessibilityEvent.obtainWindowsChangedEvent(
                        mAccessibilityFocusedDisplayId, mAccessibilityFocusedWindowId,
                        WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED));
            }

            mAccessibilityFocusedWindowId = windowId;
            // Goes through all windows for each display.
            final int count = mDisplayWindowsObservers.size();
            for (int i = 0; i < count; i++) {
                final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
                if (observer != null && observer.setAccessibilityFocusedWindowLocked(windowId)) {
                    mAccessibilityFocusedDisplayId = observer.mDisplayId;
                    // Newly focused window -> send a focused event for gaining focus
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(observer.mDisplayId,
                            windowId, WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED));
                }
            }

            for (final AccessibilityEvent event : events) {
                mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(event);
            }
        }
    }

    /**
     * Returns accessibility window info according to given windowId.
     *
     * @param windowId The windowId
     * @return The accessibility window info
     */
    @Nullable
    public AccessibilityWindowInfo findA11yWindowInfoByIdLocked(int windowId) {
        windowId = resolveParentWindowIdLocked(windowId);
        final DisplayWindowsObserver observer = getDisplayWindowObserverByWindowIdLocked(windowId);
        if (observer != null) {
            return observer.findA11yWindowInfoByIdLocked(windowId);
        }
        return null;
    }

    /**
     * Returns the window info according to given windowId.
     *
     * @param windowId The windowId
     * @return The window info
     */
    @Nullable
    public WindowInfo findWindowInfoByIdLocked(int windowId) {
        windowId = resolveParentWindowIdLocked(windowId);
        final DisplayWindowsObserver observer = getDisplayWindowObserverByWindowIdLocked(windowId);
        if (observer != null) {
            return observer.findWindowInfoByIdLocked(windowId);
        }
        return null;
    }

    /**
     * Returns focused windowId or accessibility focused windowId according to given focusType.
     *
     * @param focusType {@link AccessibilityNodeInfo#FOCUS_INPUT} or
     * {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}
     * @return The focused windowId
     */
    public int getFocusedWindowId(int focusType) {
        return getFocusedWindowId(focusType, Display.INVALID_DISPLAY);
    }

    /**
     * Returns focused windowId or accessibility focused windowId according to given focusType and
     * display id.
     * @param focusType {@link AccessibilityNodeInfo#FOCUS_INPUT} or
     * {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}
     * @param displayId the display id to check. If this display is proxy-ed, the proxy's a11y focus
     *                  will be returned.
     * @return The focused windowId
     */
    public int getFocusedWindowId(int focusType, int displayId) {
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY
                || !mHasProxy) {
            return getDefaultFocus(focusType);
        }

        final DisplayWindowsObserver observer = mDisplayWindowsObservers.get(displayId);
        if (observer != null && observer.mIsProxy) {
            return getProxyFocus(focusType, observer);
        } else {
            return getDefaultFocus(focusType);
        }
    }

    private int getDefaultFocus(int focusType) {
        if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
            return mTopFocusedWindowId;
        } else if (focusType == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
            return mAccessibilityFocusedWindowId;
        }
        return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    }

    private int getProxyFocus(int focusType, DisplayWindowsObserver observer) {
        if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
            return mTopFocusedWindowId;
        } else if (focusType == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
            return observer.mProxyDisplayAccessibilityFocusedWindow;
        } else {
            return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        }
    }

    /**
     * Returns {@link AccessibilityWindowInfo} of PIP window.
     *
     * @return PIP accessibility window info
     */
    @Nullable
    public AccessibilityWindowInfo getPictureInPictureWindowLocked() {
        AccessibilityWindowInfo windowInfo = null;
        final int count = mDisplayWindowsObservers.size();
        for (int i = 0; i < count; i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
            if (observer != null) {
                if ((windowInfo = observer.getPictureInPictureWindowLocked()) != null) {
                    break;
                }
            }
        }
        return windowInfo;
    }

    /**
     * Sets an IAccessibilityInteractionConnection to replace the actions of a picture-in-picture
     * window.
     */
    public void setPictureInPictureActionReplacingConnection(
            @Nullable IAccessibilityInteractionConnection connection) throws RemoteException {
        synchronized (mLock) {
            if (mPictureInPictureActionReplacingConnection != null) {
                mPictureInPictureActionReplacingConnection.unlinkToDeath();
                mPictureInPictureActionReplacingConnection = null;
            }
            if (connection != null) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID,
                        connection, "foo.bar.baz", Process.SYSTEM_UID, UserHandle.USER_ALL);
                mPictureInPictureActionReplacingConnection = wrapper;
                wrapper.linkToDeath();
            }
        }
    }

    /**
     * Returns accessibility interaction connection for picture-in-picture window.
     */
    @Nullable
    public RemoteAccessibilityConnection getPictureInPictureActionReplacingConnection() {
        return mPictureInPictureActionReplacingConnection;
    }

    /**
     * Invokes {@link IAccessibilityInteractionConnection#notifyOutsideTouch()} for windows that
     * have watch outside touch flag and its layer is upper than target window.
     */
    public void notifyOutsideTouch(int userId, int targetWindowId) {
        final List<Integer> outsideWindowsIds;
        final List<RemoteAccessibilityConnection> connectionList = new ArrayList<>();
        synchronized (mLock) {
            final DisplayWindowsObserver observer =
                    getDisplayWindowObserverByWindowIdLocked(targetWindowId);
            if (observer != null) {
                outsideWindowsIds = observer.getWatchOutsideTouchWindowIdLocked(targetWindowId);
                for (int i = 0; i < outsideWindowsIds.size(); i++) {
                    connectionList.add(getConnectionLocked(userId, outsideWindowsIds.get(i)));
                }
            }
        }
        for (int i = 0; i < connectionList.size(); i++) {
            final RemoteAccessibilityConnection connection = connectionList.get(i);
            if (connection != null) {
                if (traceIntConnEnabled()) {
                    logTraceIntConn("notifyOutsideTouch");
                }

                try {
                    connection.getRemote().notifyOutsideTouch();
                } catch (RemoteException re) {
                    if (DEBUG) {
                        Slog.e(LOG_TAG, "Error calling notifyOutsideTouch()");
                    }
                }
            }
        }
    }

    /**
     * Returns the display ID according to given userId and windowId.
     *
     * @param userId The userId
     * @param windowId The windowId
     * @return The display ID
     */
    public int getDisplayIdByUserIdAndWindowId(int userId, int windowId) {
        final IBinder windowToken;
        synchronized (mLock) {
            windowToken = getWindowTokenForUserAndWindowIdLocked(userId, windowId);
        }
        if (traceWMEnabled()) {
            logTraceWM("getDisplayIdForWindow", "token=" + windowToken);
        }
        final int displayId = mWindowManagerInternal.getDisplayIdForWindow(windowToken);
        return displayId;
    }

    /**
     * Returns the display list including all displays which are tracking windows.
     *
     * @param displayTypes the types of displays to retrieve
     * @return The display list.
     */
    public ArrayList<Integer> getDisplayListLocked(
            @AbstractAccessibilityServiceConnection.DisplayTypes int displayTypes) {
        final ArrayList<Integer> displayList = new ArrayList<>();
        final int count = mDisplayWindowsObservers.size();
        for (int i = 0; i < count; i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
            if (observer != null) {
                if (!observer.mIsProxy && (displayTypes & DISPLAY_TYPE_DEFAULT) != 0) {
                    displayList.add(observer.mDisplayId);
                } else if (observer.mIsProxy && (displayTypes & DISPLAY_TYPE_PROXY) != 0) {
                    displayList.add(observer.mDisplayId);
                }
            }
        }
        return displayList;
    }

    // If there is no service that can operate with interactive windows
    // then a window loses accessibility focus if it is no longer active.
    // This inspection happens when the user interaction is ended.
    // Note that to allow a service to work across windows,
    // we have to allow accessibility focus stay in any of them.
    boolean accessibilityFocusOnlyInActiveWindowLocked() {
        return !isTrackingWindowsLocked();
    }

    /**
     * Gets current input focused window token from window manager, and returns its windowId.
     *
     * @param userId The userId
     * @return The input focused windowId, or -1 if not found
     */
    private int findFocusedWindowId(int userId) {
        if (traceWMEnabled()) {
            logTraceWM("getFocusedWindowToken", "");
        }
        final IBinder token = mWindowManagerInternal.getFocusedWindowTokenFromWindowStates();
        synchronized (mLock) {
            return findWindowIdLocked(userId, token);
        }
    }

    private boolean isValidUserForInteractionConnectionsLocked(int userId) {
        return mInteractionConnections.indexOfKey(userId) >= 0;
    }

    private boolean isValidUserForWindowTokensLocked(int userId) {
        return mWindowTokens.indexOfKey(userId) >= 0;
    }

    private SparseArray<RemoteAccessibilityConnection> getInteractionConnectionsForUserLocked(
            int userId) {
        SparseArray<RemoteAccessibilityConnection> connection = mInteractionConnections.get(
                userId);
        if (connection == null) {
            connection = new SparseArray<>();
            mInteractionConnections.put(userId, connection);
        }
        return connection;
    }

    private SparseArray<IBinder> getWindowTokensForUserLocked(int userId) {
        SparseArray<IBinder> windowTokens = mWindowTokens.get(userId);
        if (windowTokens == null) {
            windowTokens = new SparseArray<>();
            mWindowTokens.put(userId, windowTokens);
        }
        return windowTokens;
    }

    private void clearAccessibilityFocusLocked(int windowId) {
        mHandler.sendMessage(obtainMessage(
                AccessibilityWindowManager::clearAccessibilityFocusMainThread,
                AccessibilityWindowManager.this,
                mAccessibilityUserManager.getCurrentUserIdLocked(), windowId));
    }

    private void clearAccessibilityFocusMainThread(int userId, int windowId) {
        final RemoteAccessibilityConnection connection;
        synchronized (mLock) {
            connection = getConnectionLocked(userId, windowId);
            if (connection == null) {
                return;
            }
        }
        if (traceIntConnEnabled()) {
            logTraceIntConn("notifyOutsideTouch");
        }
        try {
            connection.getRemote().clearAccessibilityFocus();
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling clearAccessibilityFocus()");
            }
        }
    }

    private DisplayWindowsObserver getDisplayWindowObserverByWindowIdLocked(int windowId) {
        final int count = mDisplayWindowsObservers.size();
        for (int i = 0; i < count; i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
            if (observer != null) {
                if (observer.findWindowInfoByIdLocked(windowId) != null) {
                    return mDisplayWindowsObservers.get(observer.mDisplayId);
                }
            }
        }
        return null;
    }

    private boolean traceWMEnabled() {
        return mTraceManager.isA11yTracingEnabledForTypes(FLAGS_WINDOW_MANAGER_INTERNAL);
    }

    private void logTraceWM(String methodName, String params) {
        mTraceManager.logTrace("WindowManagerInternal." + methodName,
                    FLAGS_WINDOW_MANAGER_INTERNAL, params);
    }

    private boolean traceIntConnEnabled() {
        return mTraceManager.isA11yTracingEnabledForTypes(
                FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION);
    }

    private void logTraceIntConn(String methodName) {
        mTraceManager.logTrace(
                    LOG_TAG + "." + methodName, FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION);
    }

    /**
     * Associate the token of the embedded view hierarchy to the host view hierarchy.
     *
     * @param embedded The leash token from the view root of embedded hierarchy
     * @param host The leash token from the view root of host hierarchy
     */
    void associateLocked(IBinder embedded, IBinder host) {
        mHostEmbeddedMap.put(embedded, host);
    }

    /**
     * Clear the relationship of given token.
     *
     * @param token The leash token
     */
    void disassociateLocked(IBinder token) {
        mHostEmbeddedMap.remove(token);
        for (int i = mHostEmbeddedMap.size() - 1; i >= 0; i--) {
            if (mHostEmbeddedMap.valueAt(i).equals(token)) {
                mHostEmbeddedMap.removeAt(i);
            }
        }
    }

    /**
     * Register the leash token with its windowId.
     *
     * @param token The token.
     * @param windowId The windowID.
     */
    void registerIdLocked(IBinder token, int windowId) {
        mWindowIdMap.put(windowId, token);
    }

    /**
     * Unregister the windowId and also disassociate its token.
     *
     * @param windowId The windowID
     */
    void unregisterIdLocked(int windowId) {
        final IBinder token = mWindowIdMap.get(windowId);
        if (token == null) {
            return;
        }
        disassociateLocked(token);
        mWindowIdMap.remove(windowId);
    }

    /**
     * Get the leash token by given windowID.
     *
     * @param windowId The windowID.
     * @return The token, or {@code NULL} if this windowID doesn't exist
     */
    IBinder getLeashTokenLocked(int windowId) {
        return mWindowIdMap.get(windowId);
    }

    /**
     * Get the windowId by given leash token.
     *
     * @param token The token
     * @return The windowID, or -1 if the token doesn't exist
     */
    int getWindowIdLocked(IBinder token) {
        final int index = mWindowIdMap.indexOfValue(token);
        if (index == -1) {
            return index;
        }
        return mWindowIdMap.keyAt(index);
    }

    /**
     * Get the leash token of the host hierarchy by given token.
     *
     * @param token The token
     * @return The token of host hierarchy, or {@code NULL} if no host exists
     */
    IBinder getHostTokenLocked(IBinder token) {
        return mHostEmbeddedMap.get(token);
    }

    /**
     * Checks if the window is embedded into another window so that the window should be excluded
     * from the exposed accessibility windows, and the node tree should be embedded in the host.
     */
    boolean isEmbeddedHierarchyWindowsLocked(int windowId) {
        if (mHostEmbeddedMap.size() == 0) {
            return false;
        }

        final IBinder leashToken = getLeashTokenLocked(windowId);
        if (leashToken == null) {
            return false;
        }

        return mHostEmbeddedMap.containsKey(leashToken);
    }

    /**
     * Checks if the window belongs to a proxy display and if so clears the focused window id.
     * @param focusClearedWindowId the cleared window id.
     * @return true if an observer is proxy-ed and has cleared its focused window id.
     */
    private boolean clearProxyFocusLocked(int focusClearedWindowId, int eventAction) {
        // If we are just moving focus from one view to the other in the same window, do nothing.
        if (eventAction == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            return false;
        }
        for (int i = 0; i < mDisplayWindowsObservers.size(); i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.get(i);
            if (observer != null && observer.mWindows != null && observer.mIsProxy) {
                final int windowCount = observer.mWindows.size();
                for (int j = 0; j < windowCount; j++) {
                    AccessibilityWindowInfo window = observer.mWindows.get(j);
                    if (window.getId() == focusClearedWindowId) {
                        observer.mProxyDisplayAccessibilityFocusedWindow =
                                AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                        // TODO(268754409): Look into sending a WINDOW_FOCUS_CHANGED event since
                        //  window no longer has focus (default window logic doesn't), and
                        //  whether the node id needs to be cached (default window logic does).
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if the window belongs to a proxy display and if so sends
     * WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED for that window and the previously focused window.
     * @param focusedWindowId the focused window id.
     * @return true if an observer is proxy-ed and contains the focused window.
     */
    private boolean setProxyFocusLocked(int focusedWindowId) {
        for (int i = 0; i < mDisplayWindowsObservers.size(); i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
            if (observer != null && observer.mIsProxy
                    && observer.setAccessibilityFocusedWindowLocked(focusedWindowId)) {
                final int previouslyFocusedWindowId =
                        observer.mProxyDisplayAccessibilityFocusedWindow;

                if (previouslyFocusedWindowId == focusedWindowId) {
                    // Don't send a focus event if the window is already focused.
                    return true;
                }

                // Previously focused window -> Clear focus on UI thread and send a focused event
                // for losing focus
                if (previouslyFocusedWindowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                    clearAccessibilityFocusLocked(previouslyFocusedWindowId);
                    mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(
                            AccessibilityEvent.obtainWindowsChangedEvent(
                                    observer.mDisplayId, previouslyFocusedWindowId,
                                    WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED));
                }
                observer.mProxyDisplayAccessibilityFocusedWindow = focusedWindowId;
                // Newly focused window -> send a focused event for it gaining focus
                mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(
                        AccessibilityEvent.obtainWindowsChangedEvent(
                                observer.mDisplayId,
                                observer.mProxyDisplayAccessibilityFocusedWindow,
                                WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED));

                return true;
            }
        }
        return false;
    }

    /**
     * Dumps all {@link AccessibilityWindowInfo}s here.
     */
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        pw.append("Global Info [ ");
        pw.println("Top focused display Id = " + mTopFocusedDisplayId);
        pw.println("     Active Window Id = " + mActiveWindowId);
        pw.println("     Top Focused Window Id = " + mTopFocusedWindowId);
        pw.println("     Accessibility Focused Window Id = " + mAccessibilityFocusedWindowId
                + " ]");
        pw.println();
        final int count = mDisplayWindowsObservers.size();
        for (int i = 0; i < count; i++) {
            final DisplayWindowsObserver observer = mDisplayWindowsObservers.valueAt(i);
            if (observer != null) {
                observer.dumpLocked(fd, pw, args);
            }
        }
        pw.println();
        pw.append("Window attributes:[");
        pw.append(mWindowAttributes.toString());
        pw.append("]");
        pw.println();
    }
}
