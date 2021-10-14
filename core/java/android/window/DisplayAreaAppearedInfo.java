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

package android.window;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

/**
 * Data object for the DisplayArea info provided when a DisplayArea is presented to an organizer.
 *
 * @hide
 */
@TestApi
public final class DisplayAreaAppearedInfo implements Parcelable {

    @NonNull
    private final DisplayAreaInfo mDisplayAreaInfo;

    @NonNull
    private final SurfaceControl mLeash;

    @NonNull
    public static final Creator<DisplayAreaAppearedInfo> CREATOR =
            new Creator<DisplayAreaAppearedInfo>() {
        @Override
        public DisplayAreaAppearedInfo createFromParcel(Parcel source) {
            final DisplayAreaInfo displayAreaInfo = source.readTypedObject(DisplayAreaInfo.CREATOR);
            final SurfaceControl leash = source.readTypedObject(SurfaceControl.CREATOR);
            return new DisplayAreaAppearedInfo(displayAreaInfo, leash);
        }

        @Override
        public DisplayAreaAppearedInfo[] newArray(int size) {
            return new DisplayAreaAppearedInfo[size];
        }

    };

    public DisplayAreaAppearedInfo(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        mDisplayAreaInfo = displayAreaInfo;
        mLeash = leash;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mDisplayAreaInfo, flags);
        dest.writeTypedObject(mLeash, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return the DisplayArea info.
     */
    @NonNull
    public DisplayAreaInfo getDisplayAreaInfo() {
        return mDisplayAreaInfo;
    }

    /**
     * @return the leash for the DisplayArea.
     */
    @NonNull
    public SurfaceControl getLeash() {
        return mLeash;
    }
}
