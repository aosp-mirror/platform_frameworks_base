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
import android.accessibilityservice.MagnificationConfig;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Region;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/**
 * Interface given to an AccessibilitySerivce to talk to the AccessibilityManagerService.
 *
 * @hide
 */
interface IAccessibilityServiceConnection {

    void setServiceInfo(in AccessibilityServiceInfo info);

    void setAttributionTag(in String attributionTag);

    String[] findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId,
        long accessibilityNodeId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, int flags, long threadId,
        in Bundle arguments);

    String[] findAccessibilityNodeInfosByText(int accessibilityWindowId, long accessibilityNodeId,
        String text, int interactionId, IAccessibilityInteractionConnectionCallback callback,
        long threadId);

    String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
        long accessibilityNodeId, String viewId, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    String[] findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId, int direction,
        int interactionId, IAccessibilityInteractionConnectionCallback callback, long threadId);

    boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId,
        int action, in Bundle arguments, int interactionId,
        IAccessibilityInteractionConnectionCallback callback, long threadId);

    AccessibilityWindowInfo getWindow(int windowId);

    AccessibilityWindowInfo.WindowListSparseArray getWindows();

    AccessibilityServiceInfo getServiceInfo();

    boolean performGlobalAction(int action);
    List<AccessibilityNodeInfo.AccessibilityAction> getSystemActions();

    void disableSelf();

    oneway void setOnKeyEventResult(boolean handled, int sequence);

    MagnificationConfig getMagnificationConfig(int displayId);

    float getMagnificationScale(int displayId);

    float getMagnificationCenterX(int displayId);

    float getMagnificationCenterY(int displayId);

    Region getMagnificationRegion(int displayId);

    Region getCurrentMagnificationRegion(int displayId);

    boolean resetMagnification(int displayId, boolean animate);

    boolean resetCurrentMagnification(int displayId, boolean animate);

    boolean setMagnificationConfig(int displayId, in MagnificationConfig config, boolean animate);

    void setMagnificationCallbackEnabled(int displayId, boolean enabled);

    boolean setSoftKeyboardShowMode(int showMode);

    int getSoftKeyboardShowMode();

    void setSoftKeyboardCallbackEnabled(boolean enabled);

    boolean switchToInputMethod(String imeId);

    int setInputMethodEnabled(String imeId, boolean enabled);

    boolean isAccessibilityButtonAvailable();

    void sendGesture(int sequence, in ParceledListSlice gestureSteps);

    void dispatchGesture(int sequence, in ParceledListSlice gestureSteps, int displayId);

    boolean isFingerprintGestureDetectionAvailable();

    IBinder getOverlayWindowToken(int displayid);

    int getWindowIdForLeashToken(IBinder token);

    void takeScreenshot(int displayId, in RemoteCallback callback);

    void setGestureDetectionPassthroughRegion(int displayId, in Region region);

    void setTouchExplorationPassthroughRegion(int displayId, in Region region);

    void setFocusAppearance(int strokeWidth, int color);

    void setCacheEnabled(boolean enabled);

    oneway void logTrace(long timestamp, String where, long loggingTypes, String callingParams,
        int processId, long threadId, int callingUid, in Bundle serializedCallingStackInBundle);

    void setServiceDetectsGesturesEnabled(int displayId, boolean mode);

    void requestTouchExploration(int displayId);

    void requestDragging(int displayId, int pointerId);

    void requestDelegating(int displayId);

    void onDoubleTap(int displayId);

    void onDoubleTapAndHold(int displayId);

    void setAnimationScale(float scale);
}
