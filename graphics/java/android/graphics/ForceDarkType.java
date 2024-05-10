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

package android.graphics;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The style of force dark to use in {@link HardwareRenderer}.
 *
 * You must keep this in sync with the C++ enum ForceDarkType in
 * frameworks/base/libs/hwui/utils/ForceDark.h
 *
 * @hide
 */
public class ForceDarkType {
    /**
     * Force dark disabled: normal, default operation.
     *
     * @hide
     */
    public static final int NONE = 0;

    /**
     * Use force dark
     * @hide
     */
    public static final int FORCE_DARK = 1;

    /**
     * Force force-dark. {@see Settings.Secure.ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED}
     * @hide */
    public static final int FORCE_INVERT_COLOR_DARK = 2;

    /** @hide */
    @IntDef({
        NONE,
        FORCE_DARK,
        FORCE_INVERT_COLOR_DARK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForceDarkTypeDef {}

}
