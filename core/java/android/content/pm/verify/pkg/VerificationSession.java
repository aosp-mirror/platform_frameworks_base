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

package android.content.pm.verify.pkg;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.pm.Flags;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;

/**
 * This class is used by the system to describe the details about a verification request sent to the
 * verification agent, aka the verifier. It includes the interfaces for the verifier to communicate
 * back to the system.
 * @hide
 */
@FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE)
@SystemApi
public final class VerificationSession implements Parcelable {
    /**
     * The verification cannot be completed because of unknown reasons.
     */
    public static final int VERIFICATION_INCOMPLETE_UNKNOWN = 0;
    /**
     * The verification cannot be completed because the network is unavailable.
     */
    public static final int VERIFICATION_INCOMPLETE_NETWORK_UNAVAILABLE = 1;
    /**
     * The verification cannot be completed because the network is limited.
     */
    public static final int VERIFICATION_INCOMPLETE_NETWORK_LIMITED = 2;

    /**
     * @hide
     */
    @IntDef(prefix = {"VERIFICATION_INCOMPLETE_"}, value = {
            VERIFICATION_INCOMPLETE_NETWORK_UNAVAILABLE,
            VERIFICATION_INCOMPLETE_NETWORK_LIMITED,
            VERIFICATION_INCOMPLETE_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VerificationIncompleteReason {
    }

    private final int mId;
    private final int mInstallSessionId;
    @NonNull
    private final String mPackageName;
    @NonNull
    private final Uri mStagedPackageUri;
    @NonNull
    private final SigningInfo mSigningInfo;
    @NonNull
    private final List<SharedLibraryInfo> mDeclaredLibraries;
    @NonNull
    private final PersistableBundle mExtensionParams;
    @NonNull
    private final IVerificationSessionInterface mSession;
    @NonNull
    private final IVerificationSessionCallback mCallback;

    /**
     * Constructor used by the system to describe the details of a verification session.
     * @hide
     */
    public VerificationSession(int id, int installSessionId, @NonNull String packageName,
            @NonNull Uri stagedPackageUri, @NonNull SigningInfo signingInfo,
            @NonNull List<SharedLibraryInfo> declaredLibraries,
            @NonNull PersistableBundle extensionParams,
            @NonNull IVerificationSessionInterface session,
            @NonNull IVerificationSessionCallback callback) {
        mId = id;
        mInstallSessionId = installSessionId;
        mPackageName = packageName;
        mStagedPackageUri = stagedPackageUri;
        mSigningInfo = signingInfo;
        mDeclaredLibraries = declaredLibraries;
        mExtensionParams = extensionParams;
        mSession = session;
        mCallback = callback;
    }

    /**
     * A unique identifier tied to this specific verification session.
     */
    public int getId() {
        return mId;
    }

    /**
     * The package name of the app that is to be verified.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * The id of the installation session associated with the verification.
     */
    public int getInstallSessionId() {
        return mInstallSessionId;
    }

    /**
     * The Uri of the path where the package's code files are located.
     */
    public @NonNull Uri getStagedPackageUri() {
        return mStagedPackageUri;
    }

    /**
     * Signing info of the package to be verified.
     */
    public @NonNull SigningInfo getSigningInfo() {
        return mSigningInfo;
    }

    /**
     * Returns a mapping of any shared libraries declared in the manifest
     * to the {@link SharedLibraryInfo#Type} that is declared. This will be an empty
     * map if no shared libraries are declared by the package.
     */
    @NonNull
    public List<SharedLibraryInfo> getDeclaredLibraries() {
        return Collections.unmodifiableList(mDeclaredLibraries);
    }

    /**
     * Returns any extension params associated with the verification request.
     */
    @NonNull
    public PersistableBundle getExtensionParams() {
        return mExtensionParams;
    }

    /**
     * Get the value of Clock.elapsedRealtime() at which time this verification
     * will timeout as incomplete if no other verification response is provided.
     */
    @RequiresPermission(android.Manifest.permission.VERIFICATION_AGENT)
    public long getTimeoutTime() {
        try {
            return mSession.getTimeoutTime(mId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Extend the timeout for this session by the provided additionalMs to
     * fetch relevant information over the network or wait for the network.
     * This may be called multiple times. If the request would bypass any max
     * duration by the system, the method will return a lower value than the
     * requested amount that indicates how much the time was extended.
     */
    @RequiresPermission(android.Manifest.permission.VERIFICATION_AGENT)
    public long extendTimeRemaining(long additionalMs) {
        try {
            return mSession.extendTimeRemaining(mId, additionalMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report to the system that verification could not be completed along
     * with an approximate reason to pass on to the installer.
     */
    @RequiresPermission(android.Manifest.permission.VERIFICATION_AGENT)
    public void reportVerificationIncomplete(@VerificationIncompleteReason int reason) {
        try {
            mCallback.reportVerificationIncomplete(mId, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report to the system that the verification has completed and the
     * install process may act on that status to either block in the case
     * of failure or continue to process the install in the case of success.
     */
    @RequiresPermission(android.Manifest.permission.VERIFICATION_AGENT)
    public void reportVerificationComplete(@NonNull VerificationStatus status) {
        try {
            mCallback.reportVerificationComplete(mId, status);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Same as {@link #reportVerificationComplete(VerificationStatus)}, but also provide
     * a result to the extension params provided in the request, which will be passed to the
     * installer in the installation result.
     */
    @RequiresPermission(android.Manifest.permission.VERIFICATION_AGENT)
    public void reportVerificationComplete(@NonNull VerificationStatus status,
            @NonNull PersistableBundle response) {
        try {
            mCallback.reportVerificationCompleteWithExtensionResponse(mId, status, response);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private VerificationSession(@NonNull Parcel in) {
        mId = in.readInt();
        mInstallSessionId = in.readInt();
        mPackageName = in.readString8();
        mStagedPackageUri = Uri.CREATOR.createFromParcel(in);
        mSigningInfo = SigningInfo.CREATOR.createFromParcel(in);
        mDeclaredLibraries = in.createTypedArrayList(SharedLibraryInfo.CREATOR);
        mExtensionParams = in.readPersistableBundle(getClass().getClassLoader());
        mSession = IVerificationSessionInterface.Stub.asInterface(in.readStrongBinder());
        mCallback = IVerificationSessionCallback.Stub.asInterface(in.readStrongBinder());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mInstallSessionId);
        dest.writeString8(mPackageName);
        Uri.writeToParcel(dest, mStagedPackageUri);
        mSigningInfo.writeToParcel(dest, flags);
        dest.writeTypedList(mDeclaredLibraries);
        dest.writePersistableBundle(mExtensionParams);
        dest.writeStrongBinder(mSession.asBinder());
        dest.writeStrongBinder(mCallback.asBinder());
    }

    @NonNull
    public static final Creator<VerificationSession> CREATOR = new Creator<>() {
        @Override
        public VerificationSession createFromParcel(@NonNull Parcel in) {
            return new VerificationSession(in);
        }

        @Override
        public VerificationSession[] newArray(int size) {
            return new VerificationSession[size];
        }
    };
}
