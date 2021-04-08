/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.onehanded;

import android.content.res.Configuration;

import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.onehanded.OneHandedGestureHandler.OneHandedGestureEventCallback;

/**
 * Interface to engage one handed feature.
 */
@ExternalThread
public interface OneHanded {

    /**
     * Returns a binder that can be passed to an external process to manipulate OneHanded.
     */
    default IOneHanded createExternalInterface() {
        return null;
    }

    /**
     * Return one handed settings enabled or not.
     */
    boolean isOneHandedEnabled();

    /**
     * Return swipe to notification settings enabled or not.
     */
    boolean isSwipeToNotificationEnabled();

    /**
     * Enters one handed mode.
     */
    void startOneHanded();

    /**
     * Exits one handed mode.
     */
    void stopOneHanded();

    /**
     * Exits one handed mode with {@link OneHandedUiEventLogger}.
     */
    void stopOneHanded(int uiEvent);

    /**
     * Sets navigation 3 button mode enabled or disabled by users.
     */
    void setThreeButtonModeEnabled(boolean enabled);

    /**
     * Sets one handed feature temporary locked in enabled or disabled state, this won't change
     * settings configuration.
     *
     * @param locked locked function in disabled(can not trigger) or enabled state.
     * @param enabled function in disabled(can not trigger) or enabled state.
     */
    void setLockedDisabled(boolean locked, boolean enabled);

    /**
     * Registers callback to be notified after {@link OneHandedDisplayAreaOrganizer}
     * transition start or finish
     */
    void registerTransitionCallback(OneHandedTransitionCallback callback);

    /**
     * Registers callback for one handed gesture, this gesture callback will be activated on
     * 3 button navigation mode only
     */
    void registerGestureCallback(OneHandedGestureEventCallback callback);

    /**
     * Receive onConfigurationChanged() events
     */
    void onConfigChanged(Configuration newConfig);

    /**
     * Notifies when user switch complete
     */
    void onUserSwitch(int userId);
}
