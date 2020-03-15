/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import android.annotation.Nullable;
import android.graphics.Point;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.InsetsState.InternalInsetsType;

import java.util.function.Consumer;

/**
 * Represents a parcelable object to allow controlling a single {@link InsetsSource}.
 * @hide
 */
public class InsetsSourceControl implements Parcelable {

    private final @InternalInsetsType int mType;
    private final @Nullable SurfaceControl mLeash;
    private final Point mSurfacePosition;

    public InsetsSourceControl(@InternalInsetsType int type, @Nullable SurfaceControl leash,
            Point surfacePosition) {
        mType = type;
        mLeash = leash;
        mSurfacePosition = surfacePosition;
    }

    public InsetsSourceControl(InsetsSourceControl other) {
        mType = other.mType;
        if (other.mLeash != null) {
            mLeash = new SurfaceControl();
            mLeash.copyFrom(other.mLeash);
        } else {
            mLeash = null;
        }
        mSurfacePosition = new Point(other.mSurfacePosition);
    }

    public int getType() {
        return mType;
    }

    /**
     * Gets the leash for controlling insets source. If the system is controlling the insets source,
     * for example, transient bars, the client will receive fake controls without leash in it.
     *
     * @return the leash.
     */
    public @Nullable SurfaceControl getLeash() {
        return mLeash;
    }

    public InsetsSourceControl(Parcel in) {
        mType = in.readInt();
        mLeash = in.readParcelable(null /* loader */);
        mSurfacePosition = in.readParcelable(null /* loader */);
    }

    public boolean setSurfacePosition(int left, int top) {
        if (mSurfacePosition.equals(left, top)) {
            return false;
        }
        mSurfacePosition.set(left, top);
        return true;
    }

    public Point getSurfacePosition() {
        return mSurfacePosition;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeParcelable(mLeash, 0 /* flags*/);
        dest.writeParcelable(mSurfacePosition, 0 /* flags*/);
    }

    public void release(Consumer<SurfaceControl> surfaceReleaseConsumer) {
        if (mLeash != null) {
            surfaceReleaseConsumer.accept(mLeash);
        }
    }

    public static final @android.annotation.NonNull Creator<InsetsSourceControl> CREATOR
            = new Creator<InsetsSourceControl>() {
        public InsetsSourceControl createFromParcel(Parcel in) {
            return new InsetsSourceControl(in);
        }

        public InsetsSourceControl[] newArray(int size) {
            return new InsetsSourceControl[size];
        }
    };
}
