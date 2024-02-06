/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Represents the app that launched the component. See below for the APIs available on the component
 * caller.
 *
 * <p><b>Note</b>, that in {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} only
 * {@link Activity} has access to {@link ComponentCaller} instances.
 *
 * @see Activity#getInitialCaller()
 */
@FlaggedApi(android.security.Flags.FLAG_CONTENT_URI_PERMISSION_APIS)
public final class ComponentCaller {
    private final IBinder mActivityToken;
    private final IBinder mCallerToken;

    public ComponentCaller(@NonNull IBinder activityToken, @Nullable IBinder callerToken) {
        mActivityToken = activityToken;
        mCallerToken = callerToken;
    }

    /**
     * Returns the uid of this component caller.
     *
     * <p><b>Note</b>, in {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} only
     * {@link Activity} has access to {@link ComponentCaller} instances.
     * <p>
     * <h3>Requirements for {@link Activity} callers</h3>
     *
     * <p>In order to receive the calling app's uid, at least one of the following has to be met:
     * <ul>
     *     <li>The calling app must call {@link ActivityOptions#setShareIdentityEnabled(boolean)}
     *     with a value of {@code true} and launch this activity with the resulting
     *     {@code ActivityOptions}.
     *     <li>The launched activity has the same uid as the calling app.
     *     <li>The launched activity is running in a package that is signed with the same key used
     *     to sign the platform (typically only system packages such as Settings will meet this
     *     requirement).
     * </ul>
     * These are the same requirements for {@link #getPackage()}; if any of these are met, then
     * these methods can be used to obtain the uid and package name of the calling app. If none are
     * met, then {@link Process#INVALID_UID} is returned.
     *
     * <p>Note, even if the above conditions are not met, the calling app's identity may still be
     * available from {@link Activity#getCallingPackage()} if this activity was started with
     * {@code Activity#startActivityForResult} to allow validation of the result's recipient.
     *
     * @return the uid of the calling app or {@link Process#INVALID_UID} if the current component
     * cannot access the identity of the calling app or the caller is invalid
     *
     * @see ActivityOptions#setShareIdentityEnabled(boolean)
     * @see Activity#getLaunchedFromUid()
     */
    public int getUid() {
        return ActivityClient.getInstance().getLaunchedFromUid(mActivityToken);
    }

    /**
     * Returns the package name of this component caller.
     *
     * <p><b>Note</b>, in {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} only
     * {@link Activity} has access to {@link ComponentCaller} instances.
     * <p>
     * <h3>Requirements for {@link Activity} callers</h3>
     *
     * <p>In order to receive the calling app's package name, at least one of the following has to
     * be met:
     * <ul>
     *     <li>The calling app must call {@link ActivityOptions#setShareIdentityEnabled(boolean)}
     *     with a value of {@code true} and launch this activity with the resulting
     *     {@code ActivityOptions}.
     *     <li>The launched activity has the same uid as the calling app.
     *     <li>The launched activity is running in a package that is signed with the same key used
     *     to sign the platform (typically only system packages such as Settings will meet this
     *     meet this requirement).
     * </ul>
     * These are the same requirements for {@link #getUid()}; if any of these are met, then these
     * methods can be used to obtain the uid and package name of the calling app. If none are met,
     * then {@code null} is returned.
     *
     * <p>Note, even if the above conditions are not met, the calling app's identity may still be
     * available from {@link Activity#getCallingPackage()} if this activity was started with
     * {@code Activity#startActivityForResult} to allow validation of the result's recipient.
     *
     * @return the package name of the calling app or null if the current component cannot access
     * the identity of the calling app or the caller is invalid
     *
     * @see ActivityOptions#setShareIdentityEnabled(boolean)
     * @see Activity#getLaunchedFromPackage()
     */
    @Nullable
    public String getPackage() {
        return ActivityClient.getInstance().getLaunchedFromPackage(mActivityToken);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null || !(obj instanceof ComponentCaller other)) {
            return false;
        }
        return this.mActivityToken == other.mActivityToken
                && this.mCallerToken == other.mCallerToken;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mActivityToken);
        result = 31 * result + Objects.hashCode(mCallerToken);
        return result;
    }
}
