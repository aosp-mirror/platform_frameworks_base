/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accessibilityservice;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IBrailleDisplayController;
import android.accessibilityservice.MagnificationConfig;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Region;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import android.window.ScreenCapture;

/**
 * Interface given to an AccessibilitySerivce to talk to the AccessibilityManagerService.
 *
 * @hide
 */
interface IAccessibilityServiceConnection {

    @RequiresNoPermission
    void setServiceInfo(in AccessibilityServiceInfo info);

    @RequiresNoPermission
    void setAttributionTag(in String attributionTag);

    @RequiresNoPermission
    String[] findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
        long accessibilityNodeId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, long threadId,
        in Bundle arguments);

    @RequiresNoPermission
    String[] findAccessibilityNodeInfosByText(int accessibilityWindowId, long accessibilityNodeId,
        String text, int interactionId, IAccessibilityInteractionConnectionCallback callback,
        long threadId);

    @RequiresNoPermission
    String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
        long accessibilityNodeId, String viewId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    @RequiresNoPermission
    String[] findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    @RequiresNoPermission
    String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId, int direction,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    @RequiresNoPermission
    boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId,
        int action, in Bundle arguments, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    @RequiresNoPermission
    AccessibilityWindowInfo getWindow(int windowId);

    @RequiresNoPermission
    AccessibilityWindowInfo.WindowListSparseArray getWindows();

    @RequiresNoPermission
    AccessibilityServiceInfo getServiceInfo();

    @RequiresNoPermission
    boolean performGlobalAction(int action);

    @RequiresNoPermission
    List<AccessibilityNodeInfo.AccessibilityAction> getSystemActions();

    @RequiresNoPermission
    void disableSelf();

    @RequiresNoPermission
    oneway void setOnKeyEventResult(boolean handled, int sequence);

    @RequiresNoPermission
    MagnificationConfig getMagnificationConfig(int displayId);

    @RequiresNoPermission
    float getMagnificationScale(int displayId);

    @RequiresNoPermission
    float getMagnificationCenterX(int displayId);

    @RequiresNoPermission
    float getMagnificationCenterY(int displayId);

    @RequiresNoPermission
    Region getMagnificationRegion(int displayId);

    @RequiresNoPermission
    Region getCurrentMagnificationRegion(int displayId);

    @RequiresNoPermission
    boolean resetMagnification(int displayId, boolean animate);

    @RequiresNoPermission
    boolean resetCurrentMagnification(int displayId, boolean animate);

    @RequiresNoPermission
    boolean setMagnificationConfig(int displayId, in MagnificationConfig config, boolean animate);

    @RequiresNoPermission
    void setMagnificationCallbackEnabled(int displayId, boolean enabled);

    @RequiresNoPermission
    boolean isMagnificationSystemUIConnected();

    @RequiresNoPermission
    boolean setSoftKeyboardShowMode(int showMode);

    @RequiresNoPermission
    int getSoftKeyboardShowMode();

    @RequiresNoPermission
    void setSoftKeyboardCallbackEnabled(boolean enabled);

    @RequiresNoPermission
        boolean switchToInputMethod(String imeId);

    @RequiresNoPermission
        int setInputMethodEnabled(String imeId, boolean enabled);

    @RequiresNoPermission
    boolean isAccessibilityButtonAvailable();

    @RequiresNoPermission
    void sendGesture(int sequence, in ParceledListSlice gestureSteps);

    @RequiresNoPermission
    void dispatchGesture(int sequence, in ParceledListSlice gestureSteps, int displayId);

    @RequiresNoPermission
    boolean isFingerprintGestureDetectionAvailable();

    @RequiresNoPermission
    IBinder getOverlayWindowToken(int displayid);

    @RequiresNoPermission
    int getWindowIdForLeashToken(IBinder token);

    @RequiresNoPermission
    void takeScreenshot(int displayId, in RemoteCallback callback);

    @RequiresNoPermission
    void takeScreenshotOfWindow(int accessibilityWindowId, int interactionId,
        in ScreenCapture.ScreenCaptureListener listener,
        IAccessibilityInteractionConnectionCallback callback);

    @RequiresNoPermission
    void setGestureDetectionPassthroughRegion(int displayId, in Region region);

    @RequiresNoPermission
    void setTouchExplorationPassthroughRegion(int displayId, in Region region);

    @RequiresNoPermission
    void setFocusAppearance(int strokeWidth, int color);

    @RequiresNoPermission
    void setCacheEnabled(boolean enabled);

    @RequiresNoPermission
    oneway void logTrace(long timestamp, String where, long loggingTypes, String callingParams,
        int processId, long threadId, int callingUid, in Bundle serializedCallingStackInBundle);

    @RequiresNoPermission
    void setServiceDetectsGesturesEnabled(int displayId, boolean mode);

    @RequiresNoPermission
    void requestTouchExploration(int displayId);

    @RequiresNoPermission
    void requestDragging(int displayId, int pointerId);

    @RequiresNoPermission
    void requestDelegating(int displayId);

    @RequiresNoPermission
    void onDoubleTap(int displayId);

    @RequiresNoPermission
    void onDoubleTapAndHold(int displayId);

    @RequiresNoPermission
    void setAnimationScale(float scale);

    @RequiresNoPermission
    void setInstalledAndEnabledServices(in List<AccessibilityServiceInfo> infos);

    @RequiresNoPermission
        List<AccessibilityServiceInfo> getInstalledAndEnabledServices();

    @RequiresNoPermission
    void attachAccessibilityOverlayToDisplay(int interactionId, int displayId, in SurfaceControl sc, IAccessibilityInteractionConnectionCallback callback);

    @RequiresNoPermission
    void attachAccessibilityOverlayToWindow(int interactionId, int accessibilityWindowId, in SurfaceControl sc, IAccessibilityInteractionConnectionCallback callback);

    @EnforcePermission("BLUETOOTH_CONNECT")
    void connectBluetoothBrailleDisplay(in String bluetoothAddress, in IBrailleDisplayController controller);


    @RequiresNoPermission
    void connectUsbBrailleDisplay(in UsbDevice usbDevice, in IBrailleDisplayController controller);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    void setTestBrailleDisplayData(in List<Bundle> brailleDisplays);
}