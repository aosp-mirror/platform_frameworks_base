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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Describe the state of VPN.
 */
public final class VpnProfileState implements Parcelable {
    /** The VPN has not been started, or some other VPN is active. */
    public static final int STATE_DISCONNECTED = 0;
    /** The VPN is attempting to connect, potentially after a failure. */
    public static final int STATE_CONNECTING = 1;
    /** The VPN was established successfully. */
    public static final int STATE_CONNECTED = 2;
    /** A non-recoverable error has occurred, and will not be retried. */
    public static final int STATE_FAILED = 3;
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_CONNECTED,
            STATE_CONNECTING,
            STATE_DISCONNECTED,
            STATE_FAILED,
    })
    public @interface State {}

    @State private final int mState;
    private final String mSessionKey;
    private final boolean mAlwaysOn;
    private final boolean mLockdown;

    public VpnProfileState(@State int state, @Nullable String sessionKey, boolean alwaysOn,
            boolean lockdown) {
        mState = state;
        mSessionKey = sessionKey;
        mAlwaysOn = alwaysOn;
        mLockdown = lockdown;
    }

    /**
     * Returns the state of the Platform VPN
     *
     * <p>This state represents the internal connection state of the VPN. This state may diverge
     * from the VPN Network's state during error and recovery handling.
     */
    @State public int getState() {
        return mState;
    }

    /**
     * Retrieves the Session Key
     *
     * <p>The session key is an ephemeral key uniquely identifying the session for a Platform VPN.
     * The lifetime of this key is tied to the lifetime of the VPN session. In other words,
     * reprovisioning of the VPN profile, restarting of the device, or manually restarting the
     * platform VPN session will result in a new VPN session, and a new key.
     *
     * @return the unique key for the platform VPN session, or null if it is not running.
     */
    @Nullable
    public String getSessionId() {
        return mSessionKey;
    }

    /**
     * Returns the always-on status of the PlatformVpnProfile.
     *
     * <p>If the PlatformVpnProfile is set to be running in always-on mode, the system will ensure
     * that the profile is always started, and restarting it when necessary (e.g. after reboot).
     *
     * <p>Always-on can be set by an appropriately privileged user via the Settings VPN menus, or by
     * the Device Policy Manager app programmatically.
     *
     * See DevicePolicyManager#setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)
     */
    public boolean isAlwaysOn() {
        return mAlwaysOn;
    }

    /**
     * Returns the lockdown mode status of the PlatformVpnProfile.
     *
     * <p>In lockdown mode, the system will ensure that apps are not allowed to bypass the VPN,
     * including during startup or failure of the VPN.
     *
     * <p>Lockdown mode can be set by an appropriately privileged user via the Settings VPN menus,
     * or by the Device Policy Manager app programmatically.
     *
     * See DevicePolicyManager#setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)
     */
    public boolean isLockdownEnabled() {
        return mLockdown;
    }

    /**
     * Implement the Parcelable interface
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface
     */
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mState);
        out.writeString(mSessionKey);
        out.writeBoolean(mAlwaysOn);
        out.writeBoolean(mLockdown);
    }

    @NonNull
    public static final Parcelable.Creator<VpnProfileState> CREATOR =
            new Parcelable.Creator<VpnProfileState>() {
                public VpnProfileState createFromParcel(Parcel in) {
                    return new VpnProfileState(in);
                }

                public VpnProfileState[] newArray(int size) {
                    return new VpnProfileState[size];
                }
            };

    private VpnProfileState(Parcel in) {
        mState = in.readInt();
        mSessionKey = in.readString();
        mAlwaysOn = in.readBoolean();
        mLockdown = in.readBoolean();
    }

    private String convertStateToString(@State int state) {
        switch (state) {
            case STATE_CONNECTED:
                return "CONNECTED";
            case STATE_CONNECTING:
                return "CONNECTING";
            case STATE_DISCONNECTED:
                return "DISCONNECTED";
            case STATE_FAILED:
                return "FAILED";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        final StringJoiner resultJoiner = new StringJoiner(", ", "{", "}");
        resultJoiner.add("State: " + convertStateToString(getState()));
        resultJoiner.add("SessionId: " + getSessionId());
        resultJoiner.add("Always-on: " + isAlwaysOn());
        resultJoiner.add("Lockdown: " + isLockdownEnabled());
        return resultJoiner.toString();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof VpnProfileState)) return false;
        final VpnProfileState that = (VpnProfileState) obj;
        return (getState() == that.getState()
                && Objects.equals(getSessionId(), that.getSessionId())
                && isAlwaysOn() == that.isAlwaysOn()
                && isLockdownEnabled() == that.isLockdownEnabled());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getState(), getSessionId(), isAlwaysOn(), isLockdownEnabled());
    }
}
