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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.IWindow;

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

    List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId);

    List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType, int userId);

    int addAccessibilityInteractionConnection(IWindow windowToken,
            in IAccessibilityInteractionConnection connection,
            String packageName, int userId);

    void removeAccessibilityInteractionConnection(IWindow windowToken);

    void setPictureInPictureActionReplacingConnection(
            in IAccessibilityInteractionConnection connection);

    void registerUiTestAutomationService(IBinder owner, IAccessibilityServiceClient client,
        in AccessibilityServiceInfo info, int flags);

    void unregisterUiTestAutomationService(IAccessibilityServiceClient client);

    void temporaryEnableAccessibilityStateUntilKeyguardRemoved(in ComponentName service,
            boolean touchExplorationEnabled);

    IBinder getWindowToken(int windowId, int userId);

    void notifyAccessibilityButtonClicked(int displayId);

    void notifyAccessibilityButtonVisibilityChanged(boolean available);

    // Requires Manifest.permission.MANAGE_ACCESSIBILITY
    void performAccessibilityShortcut();

    // Requires Manifest.permission.MANAGE_ACCESSIBILITY
    String getAccessibilityShortcutService();

    // System process only
    boolean sendFingerprintGesture(int gestureKeyCode);

    // System process only
    int getAccessibilityWindowId(IBinder windowToken);

    long getRecommendedTimeoutMillis();
}
