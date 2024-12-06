/**
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

package android.content.pm.dependencyinstaller;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.Flags;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * Callbacks for {@link DependencyInstallerService}. The implementation of
 * DependencyInstallerService uses this interface to indicate completion of the session creation
 * request given by the system server.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SDK_DEPENDENCY_INSTALLER)
public final class DependencyInstallerCallback implements Parcelable {
    private final IBinder mBinder;
    private final IDependencyInstallerCallback mCallback;

    /** @hide */
    public DependencyInstallerCallback(IBinder binder) {
        mBinder = binder;
        mCallback = IDependencyInstallerCallback.Stub.asInterface(binder);
    }

    private DependencyInstallerCallback(Parcel in) {
        mBinder = in.readStrongBinder();
        mCallback = IDependencyInstallerCallback.Stub.asInterface(mBinder);
    }

    /**
     * Callback to indicate that all the requested dependencies have been resolved and their
     * sessions created. See {@link  DependencyInstallerService#onDependenciesRequired}.
     *
     * The system will wait for the sessions to be installed before resuming the original session
     * which requested dependency installation.
     *
     * If any of the session fails to install, the system may fail the original session. The caller
     * is expected to handle clean up of any other pending sessions remanining.
     *
     * @param sessionIds the install session IDs for all requested dependencies
     * @throws IllegalArgumentException if session id doesn't exist or has already failed.
     */
    public void onAllDependenciesResolved(@NonNull int[] sessionIds) {
        try {
            mCallback.onAllDependenciesResolved(sessionIds);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback to indicate that at least one of the required dependencies could not be resolved
     * and any associated sessions have been abandoned.
     */
    public void onFailureToResolveAllDependencies() {
        try {
            mCallback.onFailureToResolveAllDependencies();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeStrongBinder(mBinder);
    }

    public static final @NonNull Creator<DependencyInstallerCallback> CREATOR =
            new Creator<>() {
                @Override
                public DependencyInstallerCallback createFromParcel(Parcel in) {
                    return new DependencyInstallerCallback(in);
                }

                @Override
                public DependencyInstallerCallback[] newArray(int size) {
                    return new DependencyInstallerCallback[size];
                }
            };
}
