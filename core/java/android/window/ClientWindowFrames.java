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
import android.view.DisplayCutout;

/**
 * The window frame container class used by client side for layout.
 * @hide
 */
public class ClientWindowFrames implements Parcelable {
    /** The actual window bounds. */
    public final @NonNull Rect frame;

    /**
     * The container frame that is usually the same as display size. It may exclude the area of
     * insets if the window layout parameter has specified fit-insets-sides.
     */
    public final @NonNull Rect displayFrame;

    /** The background area while the window is resizing. */
    public final @NonNull Rect backdropFrame;

    /** The area cut from the display. */
    public final @NonNull DisplayCutout.ParcelableWrapper displayCutout;

    // TODO(b/149813814): Remove legacy insets.
    public final Rect contentInsets;
    public final Rect visibleInsets;
    public final Rect stableInsets;

    public ClientWindowFrames() {
        frame = new Rect();
        displayFrame = new Rect();
        backdropFrame = new Rect();
        displayCutout = new DisplayCutout.ParcelableWrapper();
        contentInsets = new Rect();
        visibleInsets = new Rect();
        stableInsets = new Rect();
    }

    public ClientWindowFrames(ClientWindowFrames other) {
        frame = new Rect(other.frame);
        displayFrame = new Rect(other.displayFrame);
        backdropFrame = new Rect(other.backdropFrame);
        displayCutout = new DisplayCutout.ParcelableWrapper(other.displayCutout.get());
        contentInsets = new Rect(other.contentInsets);
        visibleInsets = new Rect(other.visibleInsets);
        stableInsets = new Rect(other.stableInsets);
    }

    private ClientWindowFrames(Parcel in) {
        frame = Rect.CREATOR.createFromParcel(in);
        displayFrame = Rect.CREATOR.createFromParcel(in);
        backdropFrame = Rect.CREATOR.createFromParcel(in);
        displayCutout = DisplayCutout.ParcelableWrapper.CREATOR.createFromParcel(in);
        contentInsets = Rect.CREATOR.createFromParcel(in);
        visibleInsets = Rect.CREATOR.createFromParcel(in);
        stableInsets = Rect.CREATOR.createFromParcel(in);
    }

    /** Needed for AIDL out parameters. */
    public void readFromParcel(Parcel in) {
        frame.set(Rect.CREATOR.createFromParcel(in));
        displayFrame.set(Rect.CREATOR.createFromParcel(in));
        backdropFrame.set(Rect.CREATOR.createFromParcel(in));
        displayCutout.set(DisplayCutout.ParcelableWrapper.CREATOR.createFromParcel(in));
        contentInsets.set(Rect.CREATOR.createFromParcel(in));
        visibleInsets.set(Rect.CREATOR.createFromParcel(in));
        stableInsets.set(Rect.CREATOR.createFromParcel(in));
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        frame.writeToParcel(dest, flags);
        displayFrame.writeToParcel(dest, flags);
        backdropFrame.writeToParcel(dest, flags);
        displayCutout.writeToParcel(dest, flags);
        contentInsets.writeToParcel(dest, flags);
        visibleInsets.writeToParcel(dest, flags);
        stableInsets.writeToParcel(dest, flags);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(32);
        return "ClientWindowFrames{frame=" + frame.toShortString(sb)
                + " display=" + displayFrame.toShortString(sb)
                + " backdrop=" + backdropFrame.toShortString(sb)
                + " cutout=" + displayCutout + "}";
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
