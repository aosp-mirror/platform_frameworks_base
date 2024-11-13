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
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.accessibility.AccessibilityWindowAttributes;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IUserInitializationCompleteCallback;
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

    @RequiresNoPermission
    oneway void interrupt(int userId);

    @RequiresNoPermission
    oneway void sendAccessibilityEvent(in AccessibilityEvent uiEvent, int userId);

    @RequiresNoPermission
    long addClient(IAccessibilityManagerClient client, int userId);

    @RequiresNoPermission
    boolean removeClient(IAccessibilityManagerClient client, int userId);

    @RequiresNoPermission
    ParceledListSlice<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId);

    @RequiresNoPermission
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType, int userId);

    @RequiresNoPermission
    int addAccessibilityInteractionConnection(IWindow windowToken, IBinder leashToken,
            in IAccessibilityInteractionConnection connection,
            String packageName, int userId);

    @RequiresNoPermission
    void removeAccessibilityInteractionConnection(IWindow windowToken);

    @EnforcePermission("MODIFY_ACCESSIBILITY_DATA")
    void setPictureInPictureActionReplacingConnection(
            in IAccessibilityInteractionConnection connection);

    @EnforcePermission("RETRIEVE_WINDOW_CONTENT")
    void registerUiTestAutomationService(IBinder owner, IAccessibilityServiceClient client,
        in AccessibilityServiceInfo info, int userId, int flags);

    @RequiresNoPermission
    void unregisterUiTestAutomationService(IAccessibilityServiceClient client);

    // Used by UiAutomation
    @EnforcePermission("RETRIEVE_WINDOW_CONTENT")
    IBinder getWindowToken(int windowId, int userId);

    @EnforcePermission("STATUS_BAR_SERVICE")
    void notifyAccessibilityButtonClicked(int displayId, String targetName);

    @EnforcePermission("STATUS_BAR_SERVICE")
    void notifyAccessibilityButtonLongClicked(int displayId);

    @EnforcePermission("STATUS_BAR_SERVICE")
    void notifyAccessibilityButtonVisibilityChanged(boolean available);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    void performAccessibilityShortcut(int displayId, int shortcutType, String targetName);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    List<String> getAccessibilityShortcutTargets(int shortcutType);

    // System process only
    @RequiresNoPermission
    boolean sendFingerprintGesture(int gestureKeyCode);

    // System process only
    @RequiresNoPermission
    int getAccessibilityWindowId(IBinder windowToken);

    @RequiresNoPermission
    long getRecommendedTimeoutMillis();

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    oneway void registerSystemAction(in RemoteAction action, int actionId);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    oneway void unregisterSystemAction(int actionId);

    @EnforcePermission("STATUS_BAR_SERVICE")
    oneway void setMagnificationConnection(in IMagnificationConnection connection);

    @RequiresNoPermission
    void associateEmbeddedHierarchy(IBinder host, IBinder embedded);

    @RequiresNoPermission
    void disassociateEmbeddedHierarchy(IBinder token);

    @RequiresNoPermission
    int getFocusStrokeWidth();

    @RequiresNoPermission
    int getFocusColor();

    @RequiresNoPermission
    boolean isAudioDescriptionByDefaultEnabled();

    @EnforcePermission("SET_SYSTEM_AUDIO_CAPTION")
    void setSystemAudioCaptioningEnabled(boolean isEnabled, int userId);

    @RequiresNoPermission
    boolean isSystemAudioCaptioningUiEnabled(int userId);

    @EnforcePermission("SET_SYSTEM_AUDIO_CAPTION")
    void setSystemAudioCaptioningUiEnabled(boolean isEnabled, int userId);

    @RequiresNoPermission
    oneway void setAccessibilityWindowAttributes(int displayId, int windowId, int userId, in AccessibilityWindowAttributes attributes);

    // Requires CREATE_VIRTUAL_DEVICE permission. Also requires either a11y permission or role.
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean registerProxyForDisplay(IAccessibilityServiceClient proxy, int displayId);

    // Requires CREATE_VIRTUAL_DEVICE permission. Also requires either a11y permission or role.
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean unregisterProxyForDisplay(int displayId);

    // Used by UiAutomation for tests on the InputFilter
    @EnforcePermission("INJECT_EVENTS")
    void injectInputEventToInputFilter(in InputEvent event);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    boolean startFlashNotificationSequence(String opPkg, int reason, IBinder token);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    boolean stopFlashNotificationSequence(String opPkg);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    boolean startFlashNotificationEvent(String opPkg, int reason, String reasonPkg);

    @RequiresNoPermission
    boolean isAccessibilityTargetAllowed(String packageName, int uid, int userId);

    @RequiresNoPermission
    boolean sendRestrictedDialogIntent(String packageName, int uid, int userId);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    boolean isAccessibilityServiceWarningRequired(in AccessibilityServiceInfo info);

    parcelable WindowTransformationSpec {
        float[] transformationMatrix;
        MagnificationSpec magnificationSpec;
    }
    @RequiresNoPermission
    WindowTransformationSpec getWindowTransformationSpec(int windowId);

    @EnforcePermission("INTERNAL_SYSTEM_WINDOW")
    void attachAccessibilityOverlayToDisplay(int displayId, in SurfaceControl surfaceControl);

    @EnforcePermission(allOf={"STATUS_BAR_SERVICE","MANAGE_ACCESSIBILITY"})
    oneway void notifyQuickSettingsTilesChanged(int userId, in List<ComponentName> tileComponentNames);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    oneway void enableShortcutsForTargets(boolean enable, int shortcutTypes, in List<String> shortcutTargets, int userId);

    @EnforcePermission("MANAGE_ACCESSIBILITY")
    Bundle getA11yFeatureToTileMap(int userId);

    @RequiresNoPermission
    void registerUserInitializationCompleteCallback(IUserInitializationCompleteCallback callback);

    @RequiresNoPermission
    void unregisterUserInitializationCompleteCallback(IUserInitializationCompleteCallback callback);
}
