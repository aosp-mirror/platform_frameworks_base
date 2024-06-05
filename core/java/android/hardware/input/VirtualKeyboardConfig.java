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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.icu.util.ULocale;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;


/**
 * Configurations to create virtual keyboard.
 *
 * @hide
 */
@SystemApi
public final class VirtualKeyboardConfig extends VirtualInputDeviceConfig implements Parcelable {
    /**
     * Default language tag when creating virtual keyboard. Used when the language tag is not set.
     */
    public static final String DEFAULT_LANGUAGE_TAG = "en-Latn-US";
    /** Default layout type when creating virtual keyboard. Used when the layout type is not set. */
    public static final String DEFAULT_LAYOUT_TYPE = "qwerty";

    @NonNull
    private final String mLanguageTag;

    @NonNull
    private final String mLayoutType;

    @NonNull
    public static final Creator<VirtualKeyboardConfig> CREATOR =
            new Creator<VirtualKeyboardConfig>() {
                @Override
                public VirtualKeyboardConfig createFromParcel(Parcel in) {
                    return new VirtualKeyboardConfig(in);
                }

                @Override
                public VirtualKeyboardConfig[] newArray(int size) {
                    return new VirtualKeyboardConfig[size];
                }
            };

    private VirtualKeyboardConfig(@NonNull Builder builder) {
        super(builder);
        mLanguageTag = builder.mLanguageTag;
        mLayoutType = builder.mLayoutType;
    }

    private VirtualKeyboardConfig(@NonNull Parcel in) {
        super(in);
        mLanguageTag = in.readString8();
        mLayoutType = in.readString8();
    }

    /**
     * @see Builder#setLanguageTag().
     */
    @NonNull
    public String getLanguageTag() {
        return mLanguageTag;
    }

    /**
     * @see Builder#setLayoutType().
     */
    @NonNull
    public String getLayoutType() {
        return mLayoutType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString8(mLanguageTag);
        dest.writeString8(mLayoutType);
    }

    @Override
    @NonNull
    String additionalFieldsToString() {
        return " languageTag=" + mLanguageTag + " layoutType=" + mLayoutType;
    }

    /**
     * Builder for creating a {@link VirtualKeyboardConfig}.
     */
    public static final class Builder extends VirtualInputDeviceConfig.Builder<Builder> {
        @NonNull
        private String mLanguageTag = DEFAULT_LANGUAGE_TAG;
        @NonNull
        private String mLayoutType = DEFAULT_LAYOUT_TYPE;

        /**
         * Sets the preferred input language of the virtual keyboard using an IETF
         * <a href="https://tools.ietf.org/html/bcp47">BCP-47</a>  conformant tag.
         *
         * The passed in {@code languageTag} will be canonized using {@link
         * ULocale} and used by the system as a hint to configure the keyboard layout.
         *
         * If {@code languageTag} is not specified, the virtual keyboard will be created with {@link
         * #DEFAULT_LANGUAGE_TAG}.
         *
         * Note that the preferred layout is not guaranteed. If the specified language is
         * well-formed but not supported, the keyboard will be using English US QWERTY layout.
         *
         * In case where the owning Virtual Device has created multiple virtual keyboards, only the
         * {@code languageTag} of the most recent virtual keyboard will be kept to hint the locale
         * of the Virtual Device.
         *
         *  @throws IllegalArgumentException if either of the language or country is not present in
         *  the language tag.
         */
        @NonNull
        public Builder setLanguageTag(@NonNull String languageTag) {
            Objects.requireNonNull(languageTag, "languageTag cannot be null");
            ULocale locale = ULocale.forLanguageTag(languageTag);
            if (locale.getLanguage().isEmpty()) {
                throw new IllegalArgumentException("The language tag is not valid.");
            }
            mLanguageTag = ULocale.createCanonical(locale).toLanguageTag();
            return this;
        }

        /**
         * Sets the preferred layout type of the virtual keyboard. See {@code keyboardLayoutType}
         * attribute in frameworks/base/core/res/res/values/attrs.xml for a list of supported
         * layout types.
         *
         * Note that the preferred layout is not guaranteed. If the specified layout type is
         * well-formed but not supported, the keyboard will be using English US QWERTY layout.
         *
         * If not specified, the virtual keyboard will be created with {@link #DEFAULT_LAYOUT_TYPE}.
         */
        @NonNull
        public Builder setLayoutType(@NonNull String layoutType) {
            Objects.requireNonNull(layoutType, "layoutType cannot be null");
            mLayoutType = layoutType;
            return this;
        }

        /**
         * Builds the {@link VirtualKeyboardConfig} instance.
         */
        @NonNull
        public VirtualKeyboardConfig build() {
            return new VirtualKeyboardConfig(this);
        }
    }
}
