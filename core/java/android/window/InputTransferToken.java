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

package android.window;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControlViewHost;

import java.util.Objects;

/**
 * A token that can be used to request focus on or to transfer touch gesture to a
 * {@link SurfaceControlViewHost} or {@link android.view.SurfaceControl} that has an input channel.
 * @hide
 */
public final class InputTransferToken implements Parcelable {
    /**
     * @hide
     */
    @NonNull
    public final IBinder mToken;

    /**
     * @hide
     */
    public InputTransferToken(@NonNull IBinder token) {
        mToken = token;
    }

    /**
     * @hide
     */
    public InputTransferToken() {
        mToken = new Binder();
    }

    private InputTransferToken(Parcel in) {
        mToken = in.readStrongBinder();
    }

    /**
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
    }

    public static final @NonNull Creator<InputTransferToken> CREATOR = new Creator<>() {
        public InputTransferToken createFromParcel(Parcel in) {
            return new InputTransferToken(in);
        }

        public InputTransferToken[] newArray(int size) {
            return new InputTransferToken[size];
        }
    };


    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mToken);
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InputTransferToken other = (InputTransferToken) obj;
        return other.mToken == mToken;
    }

}
