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

import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Stores information about a particular {@link com.android.server.wm.DisplayArea}. This object will
 * be sent to registered {@link DisplayAreaOrganizer} to provide information when the DisplayArea
 * is added, removed, or changed.
 *
 * @hide
 */
@TestApi
public final class DisplayAreaInfo implements Parcelable {

    @NonNull
    public final WindowContainerToken token;

    @NonNull
    public final Configuration configuration = new Configuration();

    /**
     * The id of the display this display area is associated with.
     */
    public final int displayId;

    /**
     * The feature id of this display area.
     */
    public final int featureId;

    /**
     * The feature id of the root display area this display area is associated with.
     * @hide
     */
    public int rootDisplayAreaId = FEATURE_UNDEFINED;

    public DisplayAreaInfo(@NonNull WindowContainerToken token, int displayId, int featureId) {
        this.token = token;
        this.displayId = displayId;
        this.featureId = featureId;
    }

    private DisplayAreaInfo(Parcel in) {
        token = WindowContainerToken.CREATOR.createFromParcel(in);
        configuration.readFromParcel(in);
        displayId = in.readInt();
        featureId = in.readInt();
        rootDisplayAreaId = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        token.writeToParcel(dest, flags);
        configuration.writeToParcel(dest, flags);
        dest.writeInt(displayId);
        dest.writeInt(featureId);
        dest.writeInt(rootDisplayAreaId);
    }

    @NonNull
    public static final Creator<DisplayAreaInfo> CREATOR = new Creator<DisplayAreaInfo>() {
        @Override
        public DisplayAreaInfo createFromParcel(Parcel in) {
            return new DisplayAreaInfo(in);
        }

        @Override
        public DisplayAreaInfo[] newArray(int size) {
            return new DisplayAreaInfo[size];
        }
    };

    @Override
    public String toString() {
        return "DisplayAreaInfo{token=" + token
                + " config=" + configuration + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
