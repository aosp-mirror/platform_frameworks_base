/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.servertransaction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.view.SurfaceControl;
import android.window.SplashScreenView.SplashScreenViewParcelable;

import java.util.Objects;

/**
 * Transfer a splash screen view to an Activity.
 *
 * @hide
 */
public class TransferSplashScreenViewStateItem extends ActivityTransactionItem {

    @Nullable
    private final SplashScreenViewParcelable mSplashScreenViewParcelable;

    @Nullable
    private final SurfaceControl mStartingWindowLeash;

    public TransferSplashScreenViewStateItem(@NonNull IBinder activityToken,
            @Nullable SplashScreenViewParcelable parcelable,
            @Nullable SurfaceControl startingWindowLeash) {
        super(activityToken);
        mSplashScreenViewParcelable = parcelable;
        mStartingWindowLeash = startingWindowLeash;
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull ActivityThread.ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        client.handleAttachSplashScreenView(r, mSplashScreenViewParcelable, mStartingWindowLeash);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedObject(mSplashScreenViewParcelable, flags);
        dest.writeTypedObject(mStartingWindowLeash, flags);
    }

    /** Reads from Parcel. */
    private TransferSplashScreenViewStateItem(@NonNull Parcel in) {
        super(in);
        mSplashScreenViewParcelable = in.readTypedObject(SplashScreenViewParcelable.CREATOR);
        mStartingWindowLeash = in.readTypedObject(SurfaceControl.CREATOR);
    }

    public static final @NonNull Creator<TransferSplashScreenViewStateItem> CREATOR =
            new Creator<>() {
                public TransferSplashScreenViewStateItem createFromParcel(@NonNull Parcel in) {
                    return new TransferSplashScreenViewStateItem(in);
                }

                public TransferSplashScreenViewStateItem[] newArray(int size) {
                    return new TransferSplashScreenViewStateItem[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final TransferSplashScreenViewStateItem other = (TransferSplashScreenViewStateItem) o;
        return Objects.equals(mSplashScreenViewParcelable, other.mSplashScreenViewParcelable)
                && Objects.equals(mStartingWindowLeash, other.mStartingWindowLeash);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mSplashScreenViewParcelable);
        result = 31 * result + Objects.hashCode(mStartingWindowLeash);
        return result;
    }

    @Override
    public String toString() {
        return "TransferSplashScreenViewStateItem{" + super.toString()
                + ",splashScreenViewParcelable=" + mSplashScreenViewParcelable
                + ",startingWindowLeash=" + mStartingWindowLeash + "}";
    }
}
