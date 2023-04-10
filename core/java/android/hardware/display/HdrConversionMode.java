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

package android.hardware.display;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes the HDR conversion mode for a device.
 *
 * HDR conversion mode has a conversionMode and preferredHdrOutputType. </p><p>
 * The conversionMode can be one of:
 * {@link HdrConversionMode#HDR_CONVERSION_UNSUPPORTED} : HDR conversion is unsupported. In this
 * mode, preferredHdrOutputType should not be set.
 * {@link HdrConversionMode#HDR_CONVERSION_PASSTHROUGH} : HDR conversion is disabled. The output HDR
 * type will change dynamically to match the content. In this mode, preferredHdrOutputType should
 * not be set.
 * {@link HdrConversionMode#HDR_CONVERSION_SYSTEM}: The output HDR type is selected by the
 * implementation. In this mode, preferredHdrOutputType will be the mode preferred by the system
 * when querying. However, it should be set to HDR_TYPE_INVALID when setting the mode.
 * {@link HdrConversionMode#HDR_CONVERSION_FORCE}: The implementation converts all content to this
 * HDR type, when possible.
 * In this mode, preferredHdrOutputType should be set.
 * </p>
 */
public final class HdrConversionMode implements Parcelable {
    /** HDR output conversion is unsupported */
    public static final int HDR_CONVERSION_UNSUPPORTED = 0;
    /** HDR output conversion is disabled */
    public static final int HDR_CONVERSION_PASSTHROUGH = 1;
    /** HDR output conversion is managed by the device manufacturer's implementation. */
    public static final int HDR_CONVERSION_SYSTEM = 2;
    /**
     * HDR output conversion is set by the user. The preferred output type must be
     * set in this case.
     */
    public static final int HDR_CONVERSION_FORCE = 3;

    /** @hide */
    @IntDef(prefix = {"HDR_CONVERSION"}, value = {
            HDR_CONVERSION_PASSTHROUGH,
            HDR_CONVERSION_SYSTEM,
            HDR_CONVERSION_FORCE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConversionMode {}

    public static final @NonNull
            Parcelable.Creator<HdrConversionMode> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public HdrConversionMode createFromParcel(Parcel source) {
                    return new HdrConversionMode(source);
                }

                @Override
                public HdrConversionMode[] newArray(int size) {
                    return new HdrConversionMode[size];
                }
            };

    private final @ConversionMode int mConversionMode;
    private @Display.HdrCapabilities.HdrType int mPreferredHdrOutputType;

    public HdrConversionMode(@ConversionMode int conversionMode,
            @Display.HdrCapabilities.HdrType int preferredHdrOutputType) {
        if ((conversionMode == HdrConversionMode.HDR_CONVERSION_PASSTHROUGH
                || conversionMode == HDR_CONVERSION_UNSUPPORTED)
                && preferredHdrOutputType != Display.HdrCapabilities.HDR_TYPE_INVALID) {
            throw new IllegalArgumentException("preferredHdrOutputType must not be set if"
                    + " the conversion mode is " + hdrConversionModeString(conversionMode));
        }

        mConversionMode = conversionMode;
        mPreferredHdrOutputType = preferredHdrOutputType;
    }

    public HdrConversionMode(@ConversionMode int conversionMode) {
        mConversionMode = conversionMode;
        mPreferredHdrOutputType = Display.HdrCapabilities.HDR_TYPE_INVALID;
    }

    private HdrConversionMode(Parcel source) {
        this(source.readInt(), source.readInt());
    }

    @ConversionMode
    public int getConversionMode() {
        return mConversionMode;
    }

    @Display.HdrCapabilities.HdrType
    public int getPreferredHdrOutputType() {
        return mPreferredHdrOutputType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mConversionMode);
        dest.writeInt(mPreferredHdrOutputType);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof HdrConversionMode && equals((HdrConversionMode) o);
    }

    @Override
    public int hashCode() {
        return 0; // don't care
    }

    @Override
    public String toString() {
        return "HdrConversionMode{ConversionMode=" + hdrConversionModeString(getConversionMode())
                + ", PreferredHdrOutputType="
                + Display.HdrCapabilities.hdrTypeToString(getPreferredHdrOutputType()) + "}";
    }

    private boolean equals(HdrConversionMode other) {
        return other != null
                && mConversionMode == other.getConversionMode()
                && mPreferredHdrOutputType == other.getPreferredHdrOutputType();
    }

    private static String hdrConversionModeString(@ConversionMode int hdrConversionMode) {
        switch (hdrConversionMode) {
            case HDR_CONVERSION_PASSTHROUGH:
                return "HDR_CONVERSION_PASSTHROUGH";
            case HDR_CONVERSION_SYSTEM:
                return "HDR_CONVERSION_SYSTEM";
            case HDR_CONVERSION_FORCE:
                return "HDR_CONVERSION_FORCE";
            default:
                return "HDR_CONVERSION_UNSUPPORTED";
        }
    }
}