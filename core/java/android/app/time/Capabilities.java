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

package android.app.time;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A capability is the ability for the user to configure something or perform an action. This
 * information is exposed so that system apps like SettingsUI can be dynamic, rather than
 * hard-coding knowledge of when configuration or actions are applicable / available to the user.
 *
 * <p>Capabilities have states that users cannot change directly. They may influence some
 * capabilities indirectly by agreeing to certain device-wide behaviors such as location sharing, or
 * by changing the configuration. See the {@code CAPABILITY_} constants for details.
 *
 * <p>Actions have associated methods, see the documentation for each action for details.
 *
 * <p>Note: Capabilities are independent of app permissions required to call the associated APIs.
 *
 * @hide
 */
@SystemApi
public final class Capabilities {

    /** @hide */
    @IntDef({ CAPABILITY_NOT_SUPPORTED, CAPABILITY_NOT_ALLOWED, CAPABILITY_NOT_APPLICABLE,
            CAPABILITY_POSSESSED })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CapabilityState {}

    /**
     * Indicates that a capability is not supported on this device, e.g. because of form factor or
     * hardware. The associated UI should usually not be shown to the user.
     */
    public static final int CAPABILITY_NOT_SUPPORTED = 10;

    /**
     * Indicates that a capability is supported on this device, but not allowed for the user, e.g.
     * if the capability relates to the ability to modify settings the user is not able to.
     * This could be because of the user's type (e.g. maybe it applies to the primary user only) or
     * device policy. Depending on the capability, this could mean the associated UI
     * should be hidden, or displayed but disabled.
     */
    public static final int CAPABILITY_NOT_ALLOWED = 20;

    /**
     * Indicates that a capability is possessed but not currently applicable, e.g. if the
     * capability relates to the ability to modify settings, the user has the ability to modify
     * it, but it is currently rendered irrelevant by other settings or other device state (flags,
     * resource config, etc.). The associated UI may be hidden, disabled, or left visible (but
     * ineffective) depending on requirements.
     */
    public static final int CAPABILITY_NOT_APPLICABLE = 30;

    /** Indicates that a capability is possessed by the user. */
    public static final int CAPABILITY_POSSESSED = 40;

    private Capabilities() {}

}
