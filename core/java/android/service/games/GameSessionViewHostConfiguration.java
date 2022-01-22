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

import java.util.Objects;

/**
 * Represents the configuration of the {@link android.view.SurfaceControlViewHost} used to render
 * the overlay for a game session.
 *
 * @hide
 */
@Hide
public final class GameSessionViewHostConfiguration implements Parcelable {

    @NonNull
    public static final Creator<GameSessionViewHostConfiguration> CREATOR =
            new Creator<GameSessionViewHostConfiguration>() {
                @Override
                public GameSessionViewHostConfiguration createFromParcel(Parcel source) {
                    return new GameSessionViewHostConfiguration(
                            source.readInt(),
                            source.readInt(),
                            source.readInt());
                }

                @Override
                public GameSessionViewHostConfiguration[] newArray(int size) {
                    return new GameSessionViewHostConfiguration[0];
                }
            };

    final int mDisplayId;
    final int mWidthPx;
    final int mHeightPx;

    public GameSessionViewHostConfiguration(int displayId, int widthPx, int heightPx) {
        this.mDisplayId = displayId;
        this.mWidthPx = widthPx;
        this.mHeightPx = heightPx;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDisplayId);
        dest.writeInt(mWidthPx);
        dest.writeInt(mHeightPx);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GameSessionViewHostConfiguration)) return false;
        GameSessionViewHostConfiguration that = (GameSessionViewHostConfiguration) o;
        return mDisplayId == that.mDisplayId && mWidthPx == that.mWidthPx
                && mHeightPx == that.mHeightPx;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayId, mWidthPx, mHeightPx);
    }

    @Override
    public String toString() {
        return "GameSessionViewHostConfiguration{"
                + "mDisplayId=" + mDisplayId
                + ", mWidthPx=" + mWidthPx
                + ", mHeightPx=" + mHeightPx
                + '}';
    }
}
