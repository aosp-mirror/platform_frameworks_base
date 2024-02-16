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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;

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

    /**
     * @hide
     */
    public ComponentCaller(@Nullable IBinder activityToken, @Nullable IBinder callerToken) {
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
        return ActivityClient.getInstance().getActivityCallerUid(mActivityToken, mCallerToken);
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
        return ActivityClient.getInstance().getActivityCallerPackage(mActivityToken, mCallerToken);
    }

    /**
     * Determines whether this component caller had access to a specific content URI at launch time.
     * Apps can use this API to validate content URIs coming from other apps.
     *
     * <p><b>Note</b>, in {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} only
     * {@link Activity} has access to {@link ComponentCaller} instances.
     *
     * <p>Before using this method, note the following:
     * <ul>
     *     <li>You must have access to the supplied URI, otherwise it will throw a
     *     {@link SecurityException}.
     *     <li>This is not a real time check, i.e. the permissions have been computed at launch
     *     time.
     *     <li>This method will return the correct result for content URIs passed at launch time,
     *     specifically the ones from {@link Intent#getData()}, {@link Intent#EXTRA_STREAM}, and
     *     {@link Intent#getClipData()} in the intent of {@code startActivity(intent)}. For others,
     *     it will throw an {@link IllegalArgumentException}.
     * </ul>
     *
     * @param uri The content uri that is being checked
     * @param modeFlags The access modes to check
     * @return {@link PackageManager#PERMISSION_GRANTED} if this activity caller is allowed to
     *         access that uri, or {@link PackageManager#PERMISSION_DENIED} if it is not
     * @throws IllegalArgumentException if uri is a non-content URI or it wasn't passed at launch in
     *                                  {@link Intent#getData()}, {@link Intent#EXTRA_STREAM}, and
     *                                  {@link Intent#getClipData()}
     * @throws SecurityException if you don't have access to uri
     *
     * @see android.content.Context#checkContentUriPermissionFull(Uri, int, int, int)
     */
    @PackageManager.PermissionResult
    public int checkContentUriPermission(@NonNull Uri uri, @Intent.AccessUriMode int modeFlags) {
        return ActivityClient.getInstance().checkActivityCallerContentUriPermission(mActivityToken,
                mCallerToken, uri, modeFlags);
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
