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
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The window frame container class used by client side for layout.
 * @hide
 */
public class ClientWindowFrames implements Parcelable {
    /** The actual window bounds. */
    public final @NonNull Rect frame = new Rect();

    /**
     * The container frame that is usually the same as display size. It may exclude the area of
     * insets if the window layout parameter has specified fit-insets-sides.
     */
    public final @NonNull Rect displayFrame = new Rect();

    /**
     * The frame to be referenced while applying gravity and MATCH_PARENT.
     */
    public final @NonNull Rect parentFrame = new Rect();

    public boolean isParentFrameClippedByDisplayCutout;

    public ClientWindowFrames() {
    }

    public ClientWindowFrames(ClientWindowFrames other) {
        frame.set(other.frame);
        displayFrame.set(other.displayFrame);
        parentFrame.set(other.parentFrame);
        isParentFrameClippedByDisplayCutout = other.isParentFrameClippedByDisplayCutout;
    }

    private ClientWindowFrames(Parcel in) {
        readFromParcel(in);
    }

    /** Needed for AIDL out parameters. */
    public void readFromParcel(Parcel in) {
        frame.readFromParcel(in);
        displayFrame.readFromParcel(in);
        parentFrame.readFromParcel(in);
        isParentFrameClippedByDisplayCutout = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        frame.writeToParcel(dest, flags);
        displayFrame.writeToParcel(dest, flags);
        parentFrame.writeToParcel(dest, flags);
        dest.writeBoolean(isParentFrameClippedByDisplayCutout);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(32);
        return "ClientWindowFrames{frame=" + frame.toShortString(sb)
                + " display=" + displayFrame.toShortString(sb)
                + " parentFrame=" + parentFrame.toShortString(sb)
                + " parentClippedByDisplayCutout=" + isParentFrameClippedByDisplayCutout + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClientWindowFrames> CREATOR = new Creator<ClientWindowFrames>() {
        public ClientWindowFrames createFromParcel(Parcel in) {
            return new ClientWindowFrames(in);
        }

        public ClientWindowFrames[] newArray(int size) {
            return new ClientWindowFrames[size];
        }
    };
}
