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

import android.annotation.NonNull;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a keyboard layout.
 *
 * @hide
 */
public final class KeyboardLayout implements Parcelable, Comparable<KeyboardLayout> {

    /** Undefined keyboard layout */
    public static final String LAYOUT_TYPE_UNDEFINED = "undefined";

    /** Qwerty-based keyboard layout */
    public static final String LAYOUT_TYPE_QWERTY = "qwerty";

    /** Qwertz-based keyboard layout */
    public static final String LAYOUT_TYPE_QWERTZ = "qwertz";

    /** Azerty-based keyboard layout */
    public static final String LAYOUT_TYPE_AZERTY = "azerty";

    /** Dvorak keyboard layout */
    public static final String LAYOUT_TYPE_DVORAK = "dvorak";

    /** Colemak keyboard layout */
    public static final String LAYOUT_TYPE_COLEMAK = "colemak";

    /** Workman keyboard layout */
    public static final String LAYOUT_TYPE_WORKMAN = "workman";

    /** Turkish-F keyboard layout */
    public static final String LAYOUT_TYPE_TURKISH_F = "turkish_f";

    /** Turkish-Q keyboard layout */
    public static final String LAYOUT_TYPE_TURKISH_Q = "turkish_q";

    /** Keyboard layout that has been enhanced with a large number of extra characters */
    public static final String LAYOUT_TYPE_EXTENDED = "extended";

    private final String mDescriptor;
    private final String mLabel;
    private final String mCollection;
    private final int mPriority;
    @NonNull
    private final LocaleList mLocales;
    private final LayoutType mLayoutType;
    private final int mVendorId;
    private final int mProductId;

    /** Currently supported Layout types in the KCM files */
    private enum LayoutType {
        UNDEFINED(0, LAYOUT_TYPE_UNDEFINED),
        QWERTY(1, LAYOUT_TYPE_QWERTY),
        QWERTZ(2, LAYOUT_TYPE_QWERTZ),
        AZERTY(3, LAYOUT_TYPE_AZERTY),
        DVORAK(4, LAYOUT_TYPE_DVORAK),
        COLEMAK(5, LAYOUT_TYPE_COLEMAK),
        WORKMAN(6, LAYOUT_TYPE_WORKMAN),
        TURKISH_F(7, LAYOUT_TYPE_TURKISH_F),
        TURKISH_Q(8, LAYOUT_TYPE_TURKISH_Q),
        EXTENDED(9, LAYOUT_TYPE_EXTENDED);

        private final int mValue;
        private final String mName;
        private static final Map<Integer, LayoutType> VALUE_TO_ENUM_MAP = new HashMap<>();
        static {
            for (LayoutType type : LayoutType.values()) {
                VALUE_TO_ENUM_MAP.put(type.mValue, type);
            }
        }

        private static LayoutType of(int value) {
            return VALUE_TO_ENUM_MAP.getOrDefault(value, UNDEFINED);
        }

        LayoutType(int value, String name) {
            this.mValue = value;
            this.mName = name;
        }

        private int getValue() {
            return mValue;
        }

        private String getName() {
            return mName;
        }
    }

    @NonNull
    public static final Parcelable.Creator<KeyboardLayout> CREATOR = new Parcelable.Creator<>() {
        public KeyboardLayout createFromParcel(Parcel source) {
            return new KeyboardLayout(source);
        }
        public KeyboardLayout[] newArray(int size) {
            return new KeyboardLayout[size];
        }
    };

    public KeyboardLayout(String descriptor, String label, String collection, int priority,
            LocaleList locales, int layoutValue, int vid, int pid) {
        mDescriptor = descriptor;
        mLabel = label;
        mCollection = collection;
        mPriority = priority;
        mLocales = locales;
        mLayoutType = LayoutType.of(layoutValue);
        mVendorId = vid;
        mProductId = pid;
    }

    private KeyboardLayout(Parcel source) {
        mDescriptor = source.readString();
        mLabel = source.readString();
        mCollection = source.readString();
        mPriority = source.readInt();
        mLocales = LocaleList.CREATOR.createFromParcel(source);
        mLayoutType = LayoutType.of(source.readInt());
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
    public LocaleList getLocales() {
        return mLocales;
    }

    /**
     * Gets the layout type that this keyboard layout is intended for.
     * This may be "undefined" if a layoutType has not been assigned to this keyboard layout.
     * @return The keyboard layout's intended layout type.
     */
    public String getLayoutType() {
        return mLayoutType.getName();
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
        mLocales.writeToParcel(dest, 0);
        dest.writeInt(mLayoutType.getValue());
        dest.writeInt(mVendorId);
        dest.writeInt(mProductId);
    }

    @Override
    public int compareTo(KeyboardLayout another) {
        // Note that these arguments are intentionally flipped since you want higher priority
        // keyboards to be listed before lower priority keyboards.
        int result = Integer.compare(another.mPriority, mPriority);
        if (result == 0) {
            result = Integer.compare(mLayoutType.mValue, another.mLayoutType.mValue);
        }
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
        String collectionString = mCollection.isEmpty() ? "" : " - " + mCollection;
        return "KeyboardLayout " + mLabel + collectionString
                + ", descriptor: " + mDescriptor
                + ", priority: " + mPriority
                + ", locales: " + mLocales.toString()
                + ", layout type: " + mLayoutType.getName()
                + ", vendorId: " + mVendorId
                + ", productId: " + mProductId;
    }

    /**
     * Check if the provided layout type is supported/valid.
     *
     * @param layoutName name of layout type
     * @return {@code true} if the provided layout type is supported/valid.
     */
    public static boolean isLayoutTypeValid(@NonNull String layoutName) {
        Objects.requireNonNull(layoutName, "Provided layout name should not be null");
        for (LayoutType layoutType : LayoutType.values()) {
            if (layoutName.equals(layoutType.getName())) {
                return true;
            }
        }
        // Layout doesn't match any supported layout types
        return false;
    }
}
