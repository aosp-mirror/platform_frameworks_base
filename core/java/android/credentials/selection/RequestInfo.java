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

package android.credentials.selection;

import static android.credentials.flags.Flags.FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCredentialRequest;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains information about the request that initiated this UX flow.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class RequestInfo implements Parcelable {

    /**
     * The intent extra key for the {@code RequestInfo} object when launching the UX
     * activities.
     *
     * @hide
     */
    @NonNull
    public static final String EXTRA_REQUEST_INFO =
            "android.credentials.selection.extra.REQUEST_INFO";

    /**
     * Type value for any request that does not require UI.
     */
    @NonNull
    public static final String TYPE_UNDEFINED = "android.credentials.selection.TYPE_UNDEFINED";
    /**
     * Type value for a getCredential request.
     */
    @NonNull
    public static final String TYPE_GET = "android.credentials.selection.TYPE_GET";
    /**
     * Type value for a getCredential request that utilizes the credential registry.
     *
     * @hide
     */
    @NonNull
    public static final String TYPE_GET_VIA_REGISTRY =
            "android.credentials.selection.TYPE_GET_VIA_REGISTRY";
    /**
     * Type value for a createCredential request.
     */
    @NonNull
    public static final String TYPE_CREATE = "android.credentials.selection.TYPE_CREATE";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(value = {TYPE_GET, TYPE_CREATE, TYPE_UNDEFINED})
    public @interface RequestType {
    }

    @NonNull
    private final IBinder mToken;

    @Nullable
    private final CreateCredentialRequest mCreateCredentialRequest;

    @NonNull
    private final List<String> mDefaultProviderIds;

    @NonNull
    private final List<String> mRegistryProviderIds;

    @Nullable
    private final GetCredentialRequest mGetCredentialRequest;

    @NonNull
    @RequestType
    private final String mType;

    @NonNull
    private final String mAppPackageName;

    private final boolean mHasPermissionToOverrideDefault;

    private final boolean mIsShowAllOptionsRequested;

    /**
     * Creates new {@code RequestInfo} for a create-credential flow.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
    @NonNull
    public static RequestInfo newCreateRequestInfo(
            @NonNull IBinder token, @NonNull CreateCredentialRequest createCredentialRequest,
            @NonNull String appPackageName, boolean hasPermissionToOverrideDefault,
            @NonNull List<String> defaultProviderIds, boolean isShowAllOptionsRequested) {
        return new RequestInfo(
                token, TYPE_CREATE, appPackageName, createCredentialRequest, null,
                hasPermissionToOverrideDefault, defaultProviderIds, isShowAllOptionsRequested);
    }

    /**
     * Creates new {@code RequestInfo} for a get-credential flow.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
    @NonNull
    public static RequestInfo newGetRequestInfo(
            @NonNull IBinder token, @NonNull GetCredentialRequest getCredentialRequest,
            @NonNull String appPackageName, boolean hasPermissionToOverrideDefault,
            boolean isShowAllOptionsRequested) {
        return new RequestInfo(
                token, TYPE_GET, appPackageName, null, getCredentialRequest,
                hasPermissionToOverrideDefault,
                /*defaultProviderIds=*/ new ArrayList<>(), isShowAllOptionsRequested);
    }


    /** Returns whether the calling package has the permission. */
    public boolean hasPermissionToOverrideDefault() {
        return mHasPermissionToOverrideDefault;
    }

    /**
     * Returns the request token matching the user request.
     *
     * @hide
     */
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

    /** Returns the display name of the app that made this request. */
    @NonNull
    public String getAppPackageName() {
        return mAppPackageName;
    }

    /**
     * Returns the non-null CreateCredentialRequest when the type of the request is {@link
     * #TYPE_CREATE}, or null otherwise.
     */
    @Nullable
    public CreateCredentialRequest getCreateCredentialRequest() {
        return mCreateCredentialRequest;
    }

    /** Returns the request token matching the app request that should be cancelled. */
    @NonNull
    public RequestToken getRequestToken() {
        return new RequestToken(mToken);
    }

    /**
     * Returns default provider identifiers (component or package name) configured from the user
     * settings.
     *
     * Will only be possibly non-empty for the create use case. Not meaningful for the sign-in use
     * case.
     */
    @NonNull
    public List<String> getDefaultProviderIds() {
        return mDefaultProviderIds;
    }

    /**
     * Returns provider identifiers (component or package name) that have been validated to provide
     * registry entries.
     */
    @NonNull
    public List<String> getRegistryProviderIds() {
        return mRegistryProviderIds;
    }

    /**
     * Returns the non-null GetCredentialRequest when the type of the request is {@link
     * #TYPE_GET}, or null otherwise.
     */
    @Nullable
    public GetCredentialRequest getGetCredentialRequest() {
        return mGetCredentialRequest;
    }

    /**
     * Returns true if all options should be immediately displayed in the UI, and false otherwise.
     *
     * Normally this bit is set to false, upon which the selection UI should first display a
     * condensed view of popular, deduplicated options that is determined based on signals like
     * last-used timestamps, credential type priorities, and preferred providers configured from the
     * user settings {@link #getDefaultProviderIds()}; at the same time, the UI should offer an
     * option (button) that navigates the user to viewing all options from this condensed view.
     *
     * In some special occasions, e.g. when a request is initiated from the autofill drop-down
     * suggestion, this bit will be set to true to indicate that the selection UI should immediately
     * render the all option UI. This means that the request initiator has collected a user signal
     * to confirm that the user wants to view all the available options at once.
     */
    public boolean isShowAllOptionsRequested() {
        return mIsShowAllOptionsRequested;
    }

    private RequestInfo(@NonNull IBinder token, @NonNull @RequestType String type,
            @NonNull String appPackageName,
            @Nullable CreateCredentialRequest createCredentialRequest,
            @Nullable GetCredentialRequest getCredentialRequest,
            boolean hasPermissionToOverrideDefault,
            @NonNull List<String> defaultProviderIds,
            boolean isShowAllOptionsRequested) {
        mToken = token;
        mType = type;
        mAppPackageName = appPackageName;
        mCreateCredentialRequest = createCredentialRequest;
        mGetCredentialRequest = getCredentialRequest;
        mHasPermissionToOverrideDefault = hasPermissionToOverrideDefault;
        mDefaultProviderIds = defaultProviderIds == null ? new ArrayList<>() : defaultProviderIds;
        mRegistryProviderIds = new ArrayList<>();
        mIsShowAllOptionsRequested = isShowAllOptionsRequested;
    }

    private RequestInfo(@NonNull Parcel in) {
        IBinder token = in.readStrongBinder();
        String type = in.readString8();
        String appPackageName = in.readString8();
        CreateCredentialRequest createCredentialRequest =
                in.readTypedObject(CreateCredentialRequest.CREATOR);
        GetCredentialRequest getCredentialRequest =
                in.readTypedObject(GetCredentialRequest.CREATOR);

        mToken = token;
        AnnotationValidations.validate(NonNull.class, null, mToken);
        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mAppPackageName = appPackageName;
        AnnotationValidations.validate(NonNull.class, null, mAppPackageName);
        mCreateCredentialRequest = createCredentialRequest;
        mGetCredentialRequest = getCredentialRequest;
        mHasPermissionToOverrideDefault = in.readBoolean();
        mDefaultProviderIds = in.createStringArrayList();
        mRegistryProviderIds = in.createStringArrayList();
        mIsShowAllOptionsRequested = in.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeString8(mType);
        dest.writeString8(mAppPackageName);
        dest.writeTypedObject(mCreateCredentialRequest, flags);
        dest.writeTypedObject(mGetCredentialRequest, flags);
        dest.writeBoolean(mHasPermissionToOverrideDefault);
        dest.writeStringList(mDefaultProviderIds);
        dest.writeStringList(mRegistryProviderIds);
        dest.writeBoolean(mIsShowAllOptionsRequested);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<RequestInfo> CREATOR = new Creator<>() {
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
