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

package android.service.games;


import android.annotation.Hide;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControlViewHost;

/**
 * Internal result object that contains the successful creation of a game session.
 *
 * @see IGameSessionService#create(CreateGameSessionRequest, GameSessionViewHostConfiguration,
 * com.android.internal.infra.AndroidFuture)
 * @hide
 */
@Hide
public final class CreateGameSessionResult implements Parcelable {

    @NonNull
    public static final Parcelable.Creator<CreateGameSessionResult> CREATOR =
            new Parcelable.Creator<CreateGameSessionResult>() {
                @Override
                public CreateGameSessionResult createFromParcel(Parcel source) {
                    return new CreateGameSessionResult(
                            IGameSession.Stub.asInterface(source.readStrongBinder()),
                            source.readParcelable(
                                    SurfaceControlViewHost.SurfacePackage.class.getClassLoader(),
                                    SurfaceControlViewHost.SurfacePackage.class));
                }

                @Override
                public CreateGameSessionResult[] newArray(int size) {
                    return new CreateGameSessionResult[0];
                }
            };

    private final IGameSession mGameSession;
    private final SurfaceControlViewHost.SurfacePackage mSurfacePackage;

    public CreateGameSessionResult(
            @NonNull IGameSession gameSession,
            @NonNull SurfaceControlViewHost.SurfacePackage surfacePackage) {
        mGameSession = gameSession;
        mSurfacePackage = surfacePackage;
    }

    @NonNull
    public IGameSession getGameSession() {
        return mGameSession;
    }

    @NonNull
    public SurfaceControlViewHost.SurfacePackage getSurfacePackage() {
        return mSurfacePackage;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mGameSession.asBinder());
        dest.writeParcelable(mSurfacePackage, flags);
    }
}
