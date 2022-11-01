/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCredentialRequest;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains information about the request that initiated this UX flow.
 *
 * @hide
 */
public class RequestInfo implements Parcelable {

    /**
     * The intent extra key for the {@code RequestInfo} object when launching the UX
     * activities.
     */
    public static final @NonNull String EXTRA_REQUEST_INFO =
            "android.credentials.ui.extra.REQUEST_INFO";

    /** Type value for an executeGetCredential request. */
    public static final @NonNull String TYPE_GET = "android.credentials.ui.TYPE_GET";
    /** Type value for an executeCreateCredential request. */
    public static final @NonNull String TYPE_CREATE = "android.credentials.ui.TYPE_CREATE";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(value = { TYPE_GET, TYPE_CREATE })
    public @interface RequestType {}

    @NonNull
    private final IBinder mToken;

    @Nullable
    private final CreateCredentialRequest mCreateCredentialRequest;

    @Nullable
    private final GetCredentialRequest mGetCredentialRequest;

    @NonNull
    @RequestType
    private final String mType;

    private final boolean mIsFirstUsage;

    // TODO: change to package name
    @NonNull
    private final String mAppDisplayName;

    /** Creates new {@code RequestInfo} for a create-credential flow. */
    public static RequestInfo newCreateRequestInfo(
            @NonNull IBinder token, @NonNull CreateCredentialRequest createCredentialRequest,
            boolean isFirstUsage, @NonNull String appDisplayName) {
        return new RequestInfo(
                token, TYPE_CREATE, isFirstUsage, appDisplayName,
                createCredentialRequest, null);
    }

    /** Creates new {@code RequestInfo} for a get-credential flow. */
    public static RequestInfo newGetRequestInfo(
            @NonNull IBinder token, @NonNull GetCredentialRequest getCredentialRequest,
            boolean isFirstUsage, @NonNull String appDisplayName) {
        return new RequestInfo(
                token, TYPE_GET, isFirstUsage, appDisplayName,
                null, getCredentialRequest);
    }

    /** Returns the request token matching the user request. */
    @NonNull
    public IBinder getToken() {
        return mToken;
    }

    /** Returns the request type. */
    @NonNull
    @RequestType
    public String getType() {
        return mType;
    }

    /**
     * Returns whether this is the first Credential Manager usage for this user on the device.
     *
     * If true, the user will be prompted for a provider-centric dialog first to confirm their
     * provider choices.
     */
    public boolean isFirstUsage() {
        return mIsFirstUsage;
    }

    /** Returns the display name of the app that made this request. */
    @NonNull
    public String getAppDisplayName() {
        return mAppDisplayName;
    }

    /**
     * Returns the non-null CreateCredentialRequest when the type of the request is {@link
     * #TYPE_CREATE}, or null otherwise.
     */
    @Nullable
    public CreateCredentialRequest getCreateCredentialRequest() {
        return mCreateCredentialRequest;
    }

    /**
     * Returns the non-null GetCredentialRequest when the type of the request is {@link
     * #TYPE_GET}, or null otherwise.
     */
    @Nullable
    public GetCredentialRequest getGetCredentialRequest() {
        return mGetCredentialRequest;
    }

    private RequestInfo(@NonNull IBinder token, @NonNull @RequestType String type,
            boolean isFirstUsage, @NonNull String appDisplayName,
            @Nullable CreateCredentialRequest createCredentialRequest,
            @Nullable GetCredentialRequest getCredentialRequest) {
        mToken = token;
        mType = type;
        mIsFirstUsage = isFirstUsage;
        mAppDisplayName = appDisplayName;
        mCreateCredentialRequest = createCredentialRequest;
        mGetCredentialRequest = getCredentialRequest;
    }

    protected RequestInfo(@NonNull Parcel in) {
        IBinder token = in.readStrongBinder();
        String type = in.readString8();
        boolean isFirstUsage = in.readBoolean();
        String appDisplayName = in.readString8();
        CreateCredentialRequest createCredentialRequest =
                in.readTypedObject(CreateCredentialRequest.CREATOR);
        GetCredentialRequest getCredentialRequest =
                in.readTypedObject(GetCredentialRequest.CREATOR);

        mToken = token;
        AnnotationValidations.validate(NonNull.class, null, mToken);
        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mIsFirstUsage = isFirstUsage;
        mAppDisplayName = appDisplayName;
        AnnotationValidations.validate(NonNull.class, null, mAppDisplayName);
        mCreateCredentialRequest = createCredentialRequest;
        mGetCredentialRequest = getCredentialRequest;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeString8(mType);
        dest.writeBoolean(mIsFirstUsage);
        dest.writeString8(mAppDisplayName);
        dest.writeTypedObject(mCreateCredentialRequest, flags);
        dest.writeTypedObject(mGetCredentialRequest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<RequestInfo> CREATOR = new Creator<RequestInfo>() {
        @Override
        public RequestInfo createFromParcel(@NonNull Parcel in) {
            return new RequestInfo(in);
        }

        @Override
        public RequestInfo[] newArray(int size) {
            return new RequestInfo[size];
        }
    };
}
