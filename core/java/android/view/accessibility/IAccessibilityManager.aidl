/*
** Copyright 2009, The Android Open Source Project
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

package android.view.accessibility;

import android.app.RemoteAction;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.accessibility.AccessibilityWindowAttributes;
import android.view.accessibility.IMagnificationConnection;
import android.view.InputEvent;
import android.view.IWindow;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;

/**
 * Interface implemented by the AccessibilityManagerService called by
 * the AccessibilityManagers.
 *
 * @hide
 */
interface IAccessibilityManager {

    oneway void interrupt(int userId);

    oneway void sendAccessibilityEvent(in AccessibilityEvent uiEvent, int userId);

    long addClient(IAccessibilityManagerClient client, int userId);

    boolean removeClient(IAccessibilityManagerClient client, int userId);

    ParceledListSlice<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType, int userId);

    int addAccessibilityInteractionConnection(IWindow windowToken, IBinder leashToken,
            in IAccessibilityInteractionConnection connection,
            String packageName, int userId);

    void removeAccessibilityInteractionConnection(IWindow windowToken);

    void setPictureInPictureActionReplacingConnection(
            in IAccessibilityInteractionConnection connection);

    void registerUiTestAutomationService(IBinder owner, IAccessibilityServiceClient client,
        in AccessibilityServiceInfo info, int userId, int flags);

    void unregisterUiTestAutomationService(IAccessibilityServiceClient client);

    // Used by UiAutomation
    IBinder getWindowToken(int windowId, int userId);

    void notifyAccessibilityButtonClicked(int displayId, String targetName);

    void notifyAccessibilityButtonVisibilityChanged(boolean available);

    // Requires Manifest.permission.MANAGE_ACCESSIBILITY
    void performAccessibilityShortcut(String targetName);

    // Requires Manifest.permission.MANAGE_ACCESSIBILITY
    List<String> getAccessibilityShortcutTargets(int shortcutType);

    // System process only
    boolean sendFingerprintGesture(int gestureKeyCode);

    // System process only
    int getAccessibilityWindowId(IBinder windowToken);

    long getRecommendedTimeoutMillis();

    oneway void registerSystemAction(in RemoteAction action, int actionId);
    oneway void unregisterSystemAction(int actionId);
    oneway void setMagnificationConnection(in IMagnificationConnection connection);

    void associateEmbeddedHierarchy(IBinder host, IBinder embedded);

    void disassociateEmbeddedHierarchy(IBinder token);

    int getFocusStrokeWidth();

    int getFocusColor();

    boolean isAudioDescriptionByDefaultEnabled();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_SYSTEM_AUDIO_CAPTION)")
    void setSystemAudioCaptioningEnabled(boolean isEnabled, int userId);

    boolean isSystemAudioCaptioningUiEnabled(int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_SYSTEM_AUDIO_CAPTION)")
    void setSystemAudioCaptioningUiEnabled(boolean isEnabled, int userId);

    oneway void setAccessibilityWindowAttributes(int displayId, int windowId, int userId, in AccessibilityWindowAttributes attributes);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_ACCESSIBILITY)")
    boolean registerProxyForDisplay(IAccessibilityServiceClient proxy, int displayId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_ACCESSIBILITY)")
    boolean unregisterProxyForDisplay(int displayId);

    // Used by UiAutomation for tests on the InputFilter
    void injectInputEventToInputFilter(in InputEvent event);

    boolean startFlashNotificationSequence(String opPkg, int reason, IBinder token);
    boolean stopFlashNotificationSequence(String opPkg);
    boolean startFlashNotificationEvent(String opPkg, int reason, String reasonPkg);

    boolean isAccessibilityTargetAllowed(String packageName, int uid, int userId);
    boolean sendRestrictedDialogIntent(String packageName, int uid, int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_ACCESSIBILITY)")
    boolean isAccessibilityServiceWarningRequired(in AccessibilityServiceInfo info);

    parcelable WindowTransformationSpec {
        float[] transformationMatrix;
        MagnificationSpec magnificationSpec;
    }
    WindowTransformationSpec getWindowTransformationSpec(int windowId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW)")
    void attachAccessibilityOverlayToDisplay(int displayId, in SurfaceControl surfaceControl);
}
