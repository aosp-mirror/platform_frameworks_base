/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.accessibility.ProxyManager.PROXY_COMPONENT_CLASS_NAME;
import static com.android.server.accessibility.ProxyManager.PROXY_COMPONENT_PACKAGE_NAME;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.MagnificationConfig;
import android.annotation.NonNull;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresNoPermission;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Region;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityDisplayProxy;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import com.android.internal.R;
import com.android.internal.util.DumpUtils;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents the system connection to an {@link AccessibilityDisplayProxy}.
 *
 * <p>Most methods are no-ops since this connection does not need to capture input or listen to
 * hardware-related changes.
 *
 * TODO(241429275): Initialize this when a proxy is registered.
 */
public class ProxyAccessibilityServiceConnection extends AccessibilityServiceConnection {
    private static final String LOG_TAG = "ProxyAccessibilityServiceConnection";

    private int mDeviceId;
    private int mDisplayId;
    private List<AccessibilityServiceInfo> mInstalledAndEnabledServices;

    /** The stroke width of the focus rectangle in pixels */
    private int mFocusStrokeWidth;
    /** The color of the focus rectangle */
    private int mFocusColor;

    private int mInteractiveTimeout;
    private int mNonInteractiveTimeout;

    ProxyAccessibilityServiceConnection(
            Context context,
            ComponentName componentName,
            AccessibilityServiceInfo accessibilityServiceInfo, int id,
            Handler mainHandler, Object lock,
            AccessibilitySecurityPolicy securityPolicy,
            SystemSupport systemSupport, AccessibilityTrace trace,
            WindowManagerInternal windowManagerInternal,
            AccessibilityWindowManager awm, int displayId, int deviceId) {
        super(/* userState= */null, context, componentName, accessibilityServiceInfo, id,
                mainHandler, lock, securityPolicy, systemSupport, trace, windowManagerInternal,
                /* systemActionPerformer= */ null, awm, /* activityTaskManagerService= */ null);
        mDisplayId = displayId;
        setDisplayTypes(DISPLAY_TYPE_PROXY);
        mFocusStrokeWidth = mContext.getResources().getDimensionPixelSize(
                R.dimen.accessibility_focus_highlight_stroke_width);
        mFocusColor = mContext.getResources().getColor(
                R.color.accessibility_focus_highlight_color);
        mDeviceId = deviceId;
    }

    int getDisplayId() {
        return mDisplayId;
    }
    int getDeviceId() {
        return mDeviceId;
    }

    /** Called when the proxy is registered. */
    void initializeClient(IAccessibilityServiceClient client) throws RemoteException {
        mClient = client;
        mClientBinder = client.asBinder();
        mClient.init(this, mId, this.mOverlayWindowTokens.get(mDisplayId));
    }

    /**
     * Keeps mAccessibilityServiceInfo in sync with the proxy's list of AccessibilityServiceInfos.
     *
     * <p>This also sets the properties that are assumed to be populated by installed packages.
     *
     * @param infos the list of enabled and installed services.
     */
    @RequiresNoPermission
    @Override
    public void setInstalledAndEnabledServices(@NonNull List<AccessibilityServiceInfo> infos) {
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                mInstalledAndEnabledServices = infos;
                final AccessibilityServiceInfo proxyInfo = mAccessibilityServiceInfo;
                // Reset values. mAccessibilityServiceInfo is not completely reset since it is final
                proxyInfo.flags = 0;
                proxyInfo.eventTypes = 0;
                proxyInfo.notificationTimeout = 0;
                final Set<String> packageNames = new HashSet<>();
                boolean hasNullPackagesNames = false;
                boolean isAccessibilityTool = false;
                int interactiveUiTimeout = 0;
                int nonInteractiveUiTimeout = 0;

                // Go through and set properties that are relevant to the proxy. This bypasses
                // A11yServiceInfo.updateDynamicallyConfigurableProperties since the proxy has
                // higher security privileges as a SystemAPI and has to set values at runtime.
                for (AccessibilityServiceInfo info : infos) {
                    isAccessibilityTool = isAccessibilityTool | info.isAccessibilityTool();
                    if (info.packageNames == null || info.packageNames.length == 0) {
                        hasNullPackagesNames = true;
                    } else if (!hasNullPackagesNames) {
                        packageNames.addAll(Arrays.asList(info.packageNames));
                    }
                    interactiveUiTimeout = Math.max(interactiveUiTimeout,
                            info.getInteractiveUiTimeoutMillis());
                    nonInteractiveUiTimeout = Math.max(nonInteractiveUiTimeout,
                                    info.getNonInteractiveUiTimeoutMillis());
                    proxyInfo.notificationTimeout = Math.max(proxyInfo.notificationTimeout,
                            info.notificationTimeout);
                    proxyInfo.eventTypes |= info.eventTypes;
                    proxyInfo.feedbackType |= info.feedbackType;
                    proxyInfo.flags |= info.flags;
                    // For each info, populate default properties like ResolveInfo.
                    setDefaultPropertiesIfNullLocked(info);
                }

                proxyInfo.setAccessibilityTool(isAccessibilityTool);
                proxyInfo.setInteractiveUiTimeoutMillis(interactiveUiTimeout);
                proxyInfo.setNonInteractiveUiTimeoutMillis(nonInteractiveUiTimeout);
                mInteractiveTimeout = interactiveUiTimeout;
                mNonInteractiveTimeout = nonInteractiveUiTimeout;

                // If any one service info doesn't set package names, i.e. if it's interested in all
                // apps, the proxy shouldn't filter by package name even if some infos specify this.
                if (hasNullPackagesNames) {
                    proxyInfo.packageNames = null;
                } else {
                    proxyInfo.packageNames = packageNames.toArray(new String[0]);
                }

                // Update connection with mAccessibilityServiceInfo values.
                setDynamicallyConfigurableProperties(proxyInfo);
                // Notify manager service.
                mSystemSupport.onProxyChanged(mDeviceId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void setDefaultPropertiesIfNullLocked(AccessibilityServiceInfo info) {
        final String componentClassDisplayName = PROXY_COMPONENT_CLASS_NAME + mDisplayId;
        // Populate the properties that can't be null, since this may cause crashes in apps that
        // assume these are populated by an installed package.
        if (info.getResolveInfo() == null) {
            final ResolveInfo resolveInfo = new ResolveInfo();
            final ServiceInfo serviceInfo = new ServiceInfo();
            final ApplicationInfo applicationInfo = new ApplicationInfo();

            serviceInfo.packageName = PROXY_COMPONENT_PACKAGE_NAME;
            serviceInfo.name = componentClassDisplayName;

            applicationInfo.processName = PROXY_COMPONENT_PACKAGE_NAME;
            applicationInfo.className = componentClassDisplayName;

            resolveInfo.serviceInfo = serviceInfo;
            serviceInfo.applicationInfo = applicationInfo;
            info.setResolveInfo(resolveInfo);
        }

        if (info.getComponentName() == null) {
            info.setComponentName(new ComponentName(PROXY_COMPONENT_PACKAGE_NAME,
                            componentClassDisplayName));
        }
    }

    @RequiresNoPermission
    @Override
    @NonNull
    public List<AccessibilityServiceInfo> getInstalledAndEnabledServices() {
        synchronized (mLock) {
            return mInstalledAndEnabledServices != null
                    ? mInstalledAndEnabledServices : Collections.emptyList();
        }
    }

    @RequiresNoPermission
    @Override
    public AccessibilityWindowInfo.WindowListSparseArray getWindows() {
        final AccessibilityWindowInfo.WindowListSparseArray allWindows = super.getWindows();
        AccessibilityWindowInfo.WindowListSparseArray displayWindows = new
                AccessibilityWindowInfo.WindowListSparseArray();
        // Filter here so A11yInteractionClient will not cache all the windows belonging to other
        // proxy connections.
        displayWindows.put(mDisplayId, allWindows.get(mDisplayId, Collections.emptyList()));
        return displayWindows;
    }

    @RequiresNoPermission
    @Override
    public void setFocusAppearance(int strokeWidth, int color) {
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return;
            }

            if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
                return;
            }

            if (getFocusStrokeWidthLocked() == strokeWidth && getFocusColorLocked() == color) {
                return;
            }

            mFocusStrokeWidth = strokeWidth;
            mFocusColor = color;
            mSystemSupport.onProxyChanged(mDeviceId);
        }
    }

    /**
     * Gets the stroke width of the focus rectangle.
     * @return The stroke width.
     */
    public int getFocusStrokeWidthLocked() {
        return mFocusStrokeWidth;
    }

    /**
     * Gets the color of the focus rectangle.
     * @return The color.
     */
    public int getFocusColorLocked() {
        return mFocusColor;
    }

    @RequiresNoPermission
    @Override
    int resolveAccessibilityWindowIdForFindFocusLocked(int windowId, int focusType) {
        if (windowId == AccessibilityWindowInfo.ANY_WINDOW_ID) {
            final int focusedWindowId = mA11yWindowManager.getFocusedWindowId(focusType,
                    mDisplayId);
            // If the caller is a proxy and the found window doesn't belong to a proxy display
            // (or vice versa), then return null. This doesn't work if there are multiple active
            // proxys, but in the future this code shouldn't be needed if input focus
            // properly split. (so we will deal with the issues if we see them).
            //TODO(254545943): Remove this when there is user and proxy separation of input
            if (!mA11yWindowManager.windowIdBelongsToDisplayType(focusedWindowId, mDisplayTypes)) {
                return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }
            return focusedWindowId;
        }
        return windowId;
    }

    @Override
    public void binderDied() {
    }

    @Override
    protected boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
        // Don't need to check for earlier APIs.
        return true;
    }

    @Override
    protected boolean hasRightsToCurrentUserLocked() {
        // TODO(250929565): Proxy access is not currently determined by user. Adjust in refactoring.
        return true;
    }

    /** @throws UnsupportedOperationException since a proxy does not need key events */
    @Override
    public boolean onKeyEvent(KeyEvent keyEvent, int sequenceNumber)
            throws UnsupportedOperationException  {
        throw new UnsupportedOperationException("onKeyEvent is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need fingerprint hardware */
    @RequiresNoPermission
    @Override
    public boolean isCapturingFingerprintGestures() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("isCapturingFingerprintGestures is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need fingerprint hardware */
    @RequiresNoPermission
    @Override
    public void onFingerprintGestureDetectionActiveChanged(boolean active)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("onFingerprintGestureDetectionActiveChanged is not"
                + " supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need fingerprint hardware */
    @RequiresNoPermission
    @Override
    public void onFingerprintGesture(int gesture) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("onFingerprintGesture is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need fingerprint hardware */
    @RequiresNoPermission
    @Override
    public boolean isFingerprintGestureDetectionAvailable() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("isFingerprintGestureDetectionAvailable is not"
                + " supported");
    }

    /** @throws UnsupportedOperationException since a proxy is not a Service */
    @RequiresNoPermission
    @Override
    public void onServiceConnected(ComponentName name, IBinder service)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("onServiceConnected is not supported");

    }

    /** @throws UnsupportedOperationException since a proxy is not a Service */
    @RequiresNoPermission
    @Override
    public void onServiceDisconnected(ComponentName name)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("onServiceDisconnected is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy should use
     * setInstalledAndEnabledServices*/
    @RequiresNoPermission
    @Override
    public void setServiceInfo(AccessibilityServiceInfo info)
            throws UnsupportedOperationException  {
        // TODO(241429275): Ensure getServiceInfo is called appropriately for a proxy or is a no-op.
        throw new UnsupportedOperationException("setServiceInfo is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy should use A11yManager#unregister */
    @RequiresNoPermission
    @Override
    public void disableSelf() throws UnsupportedOperationException {
        // A proxy uses A11yManager#unregister to turn itself off.
        throw new UnsupportedOperationException("disableSelf is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not have global system access */
    @RequiresNoPermission
    @Override
    public boolean performGlobalAction(int action) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("performGlobalAction is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need key events */
    @RequiresNoPermission
    @Override
    public void setOnKeyEventResult(boolean handled, int sequence)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setOnKeyEventResult is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not have global system access */
    @RequiresNoPermission
    @Override
    public @NonNull List<AccessibilityNodeInfo.AccessibilityAction> getSystemActions()
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getSystemActions is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @Nullable
    @RequiresNoPermission
    @Override
    public MagnificationConfig getMagnificationConfig(int displayId)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getMagnificationConfig is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public float getMagnificationScale(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getMagnificationScale is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public float getMagnificationCenterX(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getMagnificationCenterX is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public float getMagnificationCenterY(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getMagnificationCenterY is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public Region getMagnificationRegion(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getMagnificationRegion is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public Region getCurrentMagnificationRegion(int displayId)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getCurrentMagnificationRegion is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public boolean resetMagnification(int displayId, boolean animate)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("resetMagnification is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public boolean resetCurrentMagnification(int displayId, boolean animate)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("resetCurrentMagnification is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public boolean setMagnificationConfig(int displayId,
            @androidx.annotation.NonNull MagnificationConfig config, boolean animate)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setMagnificationConfig is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public void setMagnificationCallbackEnabled(int displayId, boolean enabled)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setMagnificationCallbackEnabled is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need magnification */
    @RequiresNoPermission
    @Override
    public boolean isMagnificationCallbackEnabled(int displayId) {
        throw new UnsupportedOperationException("isMagnificationCallbackEnabled is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need IME access*/
    @RequiresNoPermission
    @Override
    public boolean setSoftKeyboardShowMode(int showMode) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setSoftKeyboardShowMode is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need IME access */
    @RequiresNoPermission
    @Override
    public int getSoftKeyboardShowMode() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("getSoftKeyboardShowMode is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need IME access */
    @RequiresNoPermission
    @Override
    public void setSoftKeyboardCallbackEnabled(boolean enabled)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setSoftKeyboardCallbackEnabled is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need IME access */
    @RequiresNoPermission
    @Override
    public boolean switchToInputMethod(String imeId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("switchToInputMethod is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need IME access */
    @RequiresNoPermission
    @Override
    public int setInputMethodEnabled(String imeId, boolean enabled)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setInputMethodEnabled is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need access to the shortcut */
    @RequiresNoPermission
    @Override
    public boolean isAccessibilityButtonAvailable() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("isAccessibilityButtonAvailable is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need gestures/input access */
    @RequiresNoPermission
    @Override
    public void sendGesture(int sequence, ParceledListSlice gestureSteps)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("sendGesture is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need gestures/input access */
    @RequiresNoPermission
    @Override
    public void dispatchGesture(int sequence, ParceledListSlice gestureSteps, int displayId)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("dispatchGesture is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need access to screenshots */
    @RequiresNoPermission
    @Override
    public void takeScreenshot(int displayId, RemoteCallback callback)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("takeScreenshot is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need gestures/input access */
    @RequiresNoPermission
    @Override
    public void setGestureDetectionPassthroughRegion(int displayId, Region region)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setGestureDetectionPassthroughRegion is not"
                + " supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need gestures/input access */
    @RequiresNoPermission
    @Override
    public void setTouchExplorationPassthroughRegion(int displayId, Region region)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setTouchExplorationPassthroughRegion is not"
                + " supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need gestures/input access */
    @RequiresNoPermission
    @Override
    public void setServiceDetectsGesturesEnabled(int displayId, boolean mode)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setServiceDetectsGesturesEnabled is not"
                + " supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need touch input access */
    @RequiresNoPermission
    @Override
    public void requestTouchExploration(int displayId) throws UnsupportedOperationException  {
        throw new UnsupportedOperationException("requestTouchExploration is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need touch input access */
    @RequiresNoPermission
    @Override
    public void requestDragging(int displayId, int pointerId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("requestDragging is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need touch input access */
    @RequiresNoPermission
    @Override
    public void requestDelegating(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("requestDelegating is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need touch input access */
    @RequiresNoPermission
    @Override
    public void onDoubleTap(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("onDoubleTap is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need touch input access */
    @RequiresNoPermission
    @Override
    public void onDoubleTapAndHold(int displayId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("onDoubleTapAndHold is not supported");
    }

    /** @throws UnsupportedOperationException since a proxy does not need touch input access */
    @RequiresNoPermission
    @Override
    public void setAnimationScale(float scale) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setAnimationScale is not supported");
    }

    public int getInteractiveTimeout() {
        return mInteractiveTimeout;
    }

    public int getNonInteractiveTimeout() {
        return mNonInteractiveTimeout;
    }

    /**
     * Returns true if a timeout was updated.
     */
    public boolean updateTimeouts(int nonInteractiveUiTimeout, int interactiveUiTimeout) {
        final int newInteractiveUiTimeout = interactiveUiTimeout != 0
                ? interactiveUiTimeout
                : mAccessibilityServiceInfo.getInteractiveUiTimeoutMillis();
        final int newNonInteractiveUiTimeout = nonInteractiveUiTimeout != 0
                ? nonInteractiveUiTimeout
                : mAccessibilityServiceInfo.getNonInteractiveUiTimeoutMillis();
        boolean updated = false;

        if (mInteractiveTimeout != newInteractiveUiTimeout) {
            mInteractiveTimeout =  newInteractiveUiTimeout;
            updated = true;
        }
        if (mNonInteractiveTimeout != newNonInteractiveUiTimeout) {
            mNonInteractiveTimeout = newNonInteractiveUiTimeout;
            updated = true;
        }
        return updated;
    }

    @PermissionManuallyEnforced
    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.append("Proxy[displayId=" + mDisplayId);
            pw.append(", deviceId=" + mDeviceId);
            pw.append(", feedbackType"
                    + AccessibilityServiceInfo.feedbackTypeToString(mFeedbackType));
            pw.append(", capabilities=" + mAccessibilityServiceInfo.getCapabilities());
            pw.append(", eventTypes="
                    + AccessibilityEvent.eventTypeToString(mEventTypes));
            pw.append(", notificationTimeout=" + mNotificationTimeout);
            pw.append(", nonInteractiveUiTimeout=").append(String.valueOf(mNonInteractiveTimeout));
            pw.append(", interactiveUiTimeout=").append(String.valueOf(mInteractiveTimeout));
            pw.append(", focusStrokeWidth=").append(String.valueOf(mFocusStrokeWidth));
            pw.append(", focusColor=").append(String.valueOf(mFocusColor));
            pw.append(", installedAndEnabledServiceCount=").append(String.valueOf(
                    mInstalledAndEnabledServices.size()));
            pw.append(", installedAndEnabledServices=").append(
                    mInstalledAndEnabledServices.toString());
            pw.append("]");
        }
    }
}
