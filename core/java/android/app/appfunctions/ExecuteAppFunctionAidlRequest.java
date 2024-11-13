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
package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * An internal request to execute an app function.
 *
 * @hide
 */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public final class ExecuteAppFunctionAidlRequest implements Parcelable {

    public static final Creator<ExecuteAppFunctionAidlRequest> CREATOR =
            new Creator<ExecuteAppFunctionAidlRequest>() {
                @Override
                public ExecuteAppFunctionAidlRequest createFromParcel(Parcel in) {
                    ExecuteAppFunctionRequest clientRequest =
                            ExecuteAppFunctionRequest.CREATOR.createFromParcel(in);
                    UserHandle userHandle = UserHandle.CREATOR.createFromParcel(in);
                    String callingPackage = in.readString8();
                    return new ExecuteAppFunctionAidlRequest(
                            clientRequest, userHandle, callingPackage);
                }

                @Override
                public ExecuteAppFunctionAidlRequest[] newArray(int size) {
                    return new ExecuteAppFunctionAidlRequest[size];
                }
            };

    /** The client request to execute an app function. */
    private final ExecuteAppFunctionRequest mClientRequest;

    /** The user handle of the user to execute the app function. */
    private final UserHandle mUserHandle;

    /** The package name of the app that is requesting to execute the app function. */
    private final String mCallingPackage;

    public ExecuteAppFunctionAidlRequest(
            ExecuteAppFunctionRequest clientRequest, UserHandle userHandle, String callingPackage) {
        this.mClientRequest = Objects.requireNonNull(clientRequest);
        this.mUserHandle = Objects.requireNonNull(userHandle);
        this.mCallingPackage = Objects.requireNonNull(callingPackage);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mClientRequest.writeToParcel(dest, flags);
        mUserHandle.writeToParcel(dest, flags);
        dest.writeString8(mCallingPackage);
    }

    /** Returns the client request to execute an app function. */
    @NonNull
    public ExecuteAppFunctionRequest getClientRequest() {
        return mClientRequest;
    }

    /** Returns the user handle of the user to execute the app function. */
    @NonNull
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    /** Returns the package name of the app that is requesting to execute the app function. */
    @NonNull
    public String getCallingPackage() {
        return mCallingPackage;
    }
}
