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

package android.view.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.view.IWindow;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * System level service that serves as an event dispatch for {@link AccessibilityEvent}s.
 * Such events are generated when something notable happens in the user interface,
 * for example an {@link android.app.Activity} starts, the focus or selection of a
 * {@link android.view.View} changes etc. Parties interested in handling accessibility
 * events implement and register an accessibility service which extends
 * {@link android.accessibilityservice.AccessibilityService}.
 *
 * @see AccessibilityEvent
 * @see android.accessibilityservice.AccessibilityService
 * @see android.content.Context#getSystemService
 */
public final class AccessibilityManager {
    private static AccessibilityManager sInstance = new AccessibilityManager();

    /**
     * Listener for the accessibility state.
     */
    public interface AccessibilityStateChangeListener {

        /**
         * Called back on change in the accessibility state.
         *
         * @param enabled Whether accessibility is enabled.
         */
        public void onAccessibilityStateChanged(boolean enabled);
    }

    /**
     * Get an AccessibilityManager instance (create one if necessary).
     *
     * @hide
     */
    public static AccessibilityManager getInstance(Context context) {
        return sInstance;
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     */
    private AccessibilityManager() {
    }

    /**
     * Returns if the {@link AccessibilityManager} is enabled.
     *
     * @return True if this {@link AccessibilityManager} is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return false;
    }

    /**
     * Sends an {@link AccessibilityEvent}. If this {@link AccessibilityManager} is not
     * enabled the call is a NOOP.
     *
     * @param event The {@link AccessibilityEvent}.
     *
     * @throws IllegalStateException if a client tries to send an {@link AccessibilityEvent}
     *         while accessibility is not enabled.
     */
    public void sendAccessibilityEvent(AccessibilityEvent event) {
    }

    /**
     * Requests interruption of the accessibility feedback from all accessibility services.
     */
    public void interrupt() {
    }

    /**
     * Returns the {@link ServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link ServiceInfo}s.
     */
    public List<ServiceInfo> getAccessibilityServiceList() {
        // normal implementation does this in some case, so let's do the same
        // (unmodifiableList wrapped around null).
        List<ServiceInfo> services = null;
        return Collections.unmodifiableList(services);
    }

    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList() {
        // normal implementation does this in some case, so let's do the same
        // (unmodifiableList wrapped around null).
        List<AccessibilityServiceInfo> services = null;
        return Collections.unmodifiableList(services);
    }

    public boolean addAccessibilityStateChangeListener(
            AccessibilityStateChangeListener listener) {
        return true;
    }

    public boolean removeAccessibilityStateChangeListener(
            AccessibilityStateChangeListener listener) {
        return true;
    }

    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection) {
        return View.NO_ID;
    }

    public void removeAccessibilityInteractionConnection(IWindow windowToken) {
    }

}
