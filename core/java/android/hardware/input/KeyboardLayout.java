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

import java.util.Locale;

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
    private final int mPriority;
    private final Locale[] mLocales;
    private final int mVendorId;
    private final int mProductId;

    public static final Parcelable.Creator<KeyboardLayout> CREATOR =
            new Parcelable.Creator<KeyboardLayout>() {
        public KeyboardLayout createFromParcel(Parcel source) {
            return new KeyboardLayout(source);
        }
        public KeyboardLayout[] newArray(int size) {
            return new KeyboardLayout[size];
        }
    };

    public KeyboardLayout(String descriptor, String label, String collection, int priority,
            Locale[] locales, int vid, int pid) {
        mDescriptor = descriptor;
        mLabel = label;
        mCollection = collection;
        mPriority = priority;
        if (locales != null) {
            mLocales = locales;
        } else {
            mLocales = new Locale[0];
        }
        mVendorId = vid;
        mProductId = pid;
    }

    private KeyboardLayout(Parcel source) {
        mDescriptor = source.readString();
        mLabel = source.readString();
        mCollection = source.readString();
        mPriority = source.readInt();
        int N = source.readInt();
        mLocales = new Locale[N];
        for (int i = 0; i < N; i++) {
            mLocales[i] = Locale.forLanguageTag(source.readString());
        }
        mVendorId = source.readInt();
        mProductId = source.readInt();
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

    /**
     * Gets the locales that this keyboard layout is intended for.
     * This may be empty if a locale has not been assigned to this keyboard layout.
     * @return The keyboard layout's intended locale.
     */
    public Locale[] getLocales() {
        return mLocales;
    }

    /**
     * Gets the vendor ID of the hardware device this keyboard layout is intended for.
     * Returns -1 if this is not specific to any piece of hardware.
     * @return The hardware vendor ID of the keyboard layout's intended device.
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * Gets the product ID of the hardware device this keyboard layout is intended for.
     * Returns -1 if this is not specific to any piece of hardware.
     * @return The hardware product ID of the keyboard layout's intended device.
     */
    public int getProductId() {
        return mProductId;
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
        dest.writeInt(mPriority);
        if (mLocales != null) {
            dest.writeInt(mLocales.length);
            for (Locale l : mLocales) {
                dest.writeString(l.toLanguageTag());
            }
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mVendorId);
        dest.writeInt(mProductId);
    }

    @Override
    public int compareTo(KeyboardLayout another) {
        // Note that these arguments are intentionally flipped since you want higher priority
        // keyboards to be listed before lower priority keyboards.
        int result = Integer.compare(another.mPriority, mPriority);
        if (result == 0) {
            result = mLabel.compareToIgnoreCase(another.mLabel);
        }
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
