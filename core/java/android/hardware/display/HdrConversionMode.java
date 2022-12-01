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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes the HDR conversion mode for a device.
 *
 * This class is used when user changes the HDR conversion mode of the device via
 * {@link DisplayManager#setHdrConversionMode(HdrConversionMode)}.
 * <p>
 * HDR conversion mode has a conversionMode and preferredHdrOutputType. </p><p>
 * The conversionMode can be one of:
 * HDR_CONVERSION_PASSSTHROUGH : HDR conversion is disabled. The output HDR type will change
 * dynamically to match the content. In this mode, preferredHdrOutputType should not be set.
 * HDR_CONVERSION_AUTO: The output HDR type is selected by the implementation. In this mode,
 * preferredHdrOutputType should not be set.
 * HDR_CONVERSION_FORCE : The implementation converts all content to this HDR type, when possible.
 * In this mode, preferredHdrOutputType should be set.
 * </p>
 * @hide
 */
@TestApi
public final class HdrConversionMode implements Parcelable {
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

    public HdrConversionMode(int conversionMode, int preferredHdrOutputType) {
        if (conversionMode != HdrConversionMode.HDR_CONVERSION_FORCE
                && preferredHdrOutputType != -1) {
            throw new IllegalArgumentException("preferredHdrOutputType must not be set if"
                    + " the conversion mode is not HDR_CONVERSION_FORCE");
        }

        mConversionMode = conversionMode;
        mPreferredHdrOutputType = preferredHdrOutputType;
    }

    public HdrConversionMode(int conversionMode) {
        mConversionMode = conversionMode;
        mPreferredHdrOutputType = Display.HdrCapabilities.HDR_TYPE_INVALID;
    }

    private HdrConversionMode(Parcel source) {
        this(source.readInt(), source.readInt());
    }

    public int getConversionMode() {
        return mConversionMode;
    }

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
}
