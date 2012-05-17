/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.input;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes a keyboard layout.
 *
 * @hide
 */
public final class KeyboardLayout implements Parcelable,
        Comparable<KeyboardLayout> {
    private final String mDescriptor;
    private final String mLabel;
    private final String mCollection;

    public static final Parcelable.Creator<KeyboardLayout> CREATOR =
            new Parcelable.Creator<KeyboardLayout>() {
        public KeyboardLayout createFromParcel(Parcel source) {
            return new KeyboardLayout(source);
        }
        public KeyboardLayout[] newArray(int size) {
            return new KeyboardLayout[size];
        }
    };

    public KeyboardLayout(String descriptor, String label, String collection) {
        mDescriptor = descriptor;
        mLabel = label;
        mCollection = collection;
    }

    private KeyboardLayout(Parcel source) {
        mDescriptor = source.readString();
        mLabel = source.readString();
        mCollection = source.readString();
    }

    /**
     * Gets the keyboard layout descriptor, which can be used to retrieve
     * the keyboard layout again later using
     * {@link InputManager#getKeyboardLayout(String)}.
     *
     * @return The keyboard layout descriptor.
     */
    public String getDescriptor() {
        return mDescriptor;
    }

    /**
     * Gets the keyboard layout descriptive label to show in the user interface.
     * @return The keyboard layout descriptive label.
     */
    public String getLabel() {
        return mLabel;
    }

    /**
     * Gets the name of the collection to which the keyboard layout belongs.  This is
     * the label of the broadcast receiver or application that provided the keyboard layout.
     * @return The keyboard layout collection name.
     */
    public String getCollection() {
        return mCollection;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mDescriptor);
        dest.writeString(mLabel);
        dest.writeString(mCollection);
    }

    @Override
    public int compareTo(KeyboardLayout another) {
        int result = mLabel.compareToIgnoreCase(another.mLabel);
        if (result == 0) {
            result = mCollection.compareToIgnoreCase(another.mCollection);
        }
        return result;
    }

    @Override
    public String toString() {
        if (mCollection.isEmpty()) {
            return mLabel;
        }
        return mLabel + " - " + mCollection;
    }
}