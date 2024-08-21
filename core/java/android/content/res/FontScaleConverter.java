/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.res;


import android.annotation.AnyThread;
import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

/**
 * A converter for non-linear font scaling. Converts font sizes given in "sp" dimensions to a
 * "dp" dimension according to a non-linear curve.
 *
 * <p>This is meant to improve readability at larger font scales: larger fonts will scale up more
 * slowly than smaller fonts, so we don't get ridiculously huge fonts that don't fit on the screen.
 *
 * <p>The thinking here is that large fonts are already big enough to read, but we still want to
 * scale them slightly to preserve the visual hierarchy when compared to smaller fonts.
 */
@FlaggedApi(Flags.FLAG_FONT_SCALE_CONVERTER_PUBLIC)
@RavenwoodKeepWholeClass
public interface FontScaleConverter {
    /**
     * Converts a dimension in "sp" to "dp".
     */
    float convertSpToDp(float sp);

    /**
     * Converts a dimension in "dp" back to "sp".
     */
    float convertDpToSp(float dp);

    /**
     * Returns true if non-linear font scaling curves would be in effect for the given scale, false
     * if the scaling would follow a linear curve or for no scaling.
     *
     * <p>Example usage: {@code
     * isNonLinearFontScalingActive(getResources().getConfiguration().fontScale)}
     */
    @AnyThread
    static boolean isNonLinearFontScalingActive(float fontScale) {
        return FontScaleConverterFactory.isNonLinearFontScalingActive(fontScale);
    }

    /**
     * Finds a matching FontScaleConverter for the given fontScale factor.
     *
     * Generally you shouldn't need this; you can use {@link
     * android.util.TypedValue#applyDimension(int, float, DisplayMetrics)} directly and it will do
     * the scaling conversion for you. Dimens and resources loaded from XML will also be
     * automatically converted. But for UI frameworks or other situations where you need to do the
     * conversion without an Android Context, you can use this method.
     *
     * @param fontScale the scale factor, usually from {@link Configuration#fontScale}.
     *
     * @return a converter for the given scale, or null if non-linear scaling should not be used.
     */
    @Nullable
    @AnyThread
    static FontScaleConverter forScale(float fontScale) {
        return FontScaleConverterFactory.forScale(fontScale);
    }
}
