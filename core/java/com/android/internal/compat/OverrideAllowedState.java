/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class contains all the possible override allowed states.
 */
public final class OverrideAllowedState implements Parcelable {
    @IntDef({
            ALLOWED,
            DISABLED_NOT_DEBUGGABLE,
            DISABLED_NON_TARGET_SDK,
            DISABLED_TARGET_SDK_TOO_HIGH,
            PACKAGE_DOES_NOT_EXIST,
            LOGGING_ONLY_CHANGE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    /**
     * Change can be overridden.
     */
    public static final int ALLOWED = 0;
    /**
     * Change cannot be overridden, due to the app not being debuggable.
     */
    public static final int DISABLED_NOT_DEBUGGABLE = 1;
    /**
     * Change cannot be overridden, due to the build being non-debuggable and the change being
     * enabled regardless of targetSdk.
     */
    public static final int DISABLED_NON_TARGET_SDK = 2;
    /**
     * Change cannot be overridden, due to the app's targetSdk being above the change's targetSdk.
     */
    public static final int DISABLED_TARGET_SDK_TOO_HIGH = 3;
    /**
     * Package does not exist.
     */
    public static final int PACKAGE_DOES_NOT_EXIST = 4;
    /**
     * Change is marked as logging only, and cannot be toggled.
     */
    public static final int LOGGING_ONLY_CHANGE = 5;

    @State
    public final int state;
    public final int appTargetSdk;
    public final int changeIdTargetSdk;

    private OverrideAllowedState(Parcel parcel) {
        state = parcel.readInt();
        appTargetSdk = parcel.readInt();
        changeIdTargetSdk = parcel.readInt();
    }

    public OverrideAllowedState(@State int state, int appTargetSdk, int changeIdTargetSdk) {
        this.state = state;
        this.appTargetSdk = appTargetSdk;
        this.changeIdTargetSdk = changeIdTargetSdk;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(state);
        out.writeInt(appTargetSdk);
        out.writeInt(changeIdTargetSdk);
    }

    /**
     * Enforces the policy for overriding compat changes.
     *
     * @param changeId    the change id that was attempted to be overridden.
     * @param packageName the package for which the attempt was made.
     * @throws SecurityException if the policy forbids this operation.
     */
    public void enforce(long changeId, String packageName)
            throws SecurityException {
        switch (state) {
            case ALLOWED:
                return;
            case DISABLED_NOT_DEBUGGABLE:
                throw new SecurityException(
                        "Cannot override a change on a non-debuggable app and user build.");
            case DISABLED_NON_TARGET_SDK:
                throw new SecurityException(
                        "Cannot override a default enabled/disabled change on a user build.");
            case DISABLED_TARGET_SDK_TOO_HIGH:
                throw new SecurityException(String.format(
                        "Cannot override %1$d for %2$s because the app's targetSdk (%3$d) is "
                                + "above the change's targetSdk threshold (%4$d)",
                        changeId, packageName, appTargetSdk, changeIdTargetSdk));
            case PACKAGE_DOES_NOT_EXIST:
                throw new SecurityException(String.format(
                        "Cannot override %1$d for %2$s because the package does not exist, and "
                                + "the change is targetSdk gated.",
                        changeId, packageName));
            case LOGGING_ONLY_CHANGE:
                throw new SecurityException(String.format(
                        "Cannot override %1$d because it is marked as a logging-only change.",
                        changeId));
        }
    }

    public static final @NonNull
            Parcelable.Creator<OverrideAllowedState> CREATOR =
                new Parcelable.Creator<OverrideAllowedState>() {
                public OverrideAllowedState createFromParcel(Parcel parcel) {
                    OverrideAllowedState info = new OverrideAllowedState(parcel);
                    return info;
                }

                public OverrideAllowedState[] newArray(int size) {
                    return new OverrideAllowedState[size];
                }
            };

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof OverrideAllowedState)) {
            return false;
        }
        OverrideAllowedState otherState = (OverrideAllowedState) obj;
        return state == otherState.state
                && appTargetSdk == otherState.appTargetSdk
                && changeIdTargetSdk == otherState.changeIdTargetSdk;
    }

    private String stateName() {
        switch (state) {
            case ALLOWED:
                return "ALLOWED";
            case DISABLED_NOT_DEBUGGABLE:
                return "DISABLED_NOT_DEBUGGABLE";
            case DISABLED_NON_TARGET_SDK:
                return "DISABLED_NON_TARGET_SDK";
            case DISABLED_TARGET_SDK_TOO_HIGH:
                return "DISABLED_TARGET_SDK_TOO_HIGH";
            case PACKAGE_DOES_NOT_EXIST:
                return "PACKAGE_DOES_NOT_EXIST";
            case LOGGING_ONLY_CHANGE:
                return "LOGGING_ONLY_CHANGE";
        }
        return "UNKNOWN";
    }

    @Override
    public String toString() {
        return "OverrideAllowedState(state=" + stateName() + "; appTargetSdk=" + appTargetSdk
                + "; changeIdTargetSdk=" + changeIdTargetSdk + ")";
    }
}
