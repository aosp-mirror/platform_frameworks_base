/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;

/** Stub for testing */
public class PackageManager {
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUTO = "automotive";

    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PC = "pc";

    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN = "vulkan";

    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WATCH = "watch";

    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI = "wifi";

    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NOT_IN_RO_FEATURE_API = "not_in_ro_feature_api";

    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String FEATURE_INTENT_CATEGORY = "intent_category_with_feature_name_prefix";

    public static final String FEATURE_NOT_ANNOTATED = "not_annotated";

    public static final String NOT_FEATURE = "not_feature";

    /** @hide */
    public boolean hasSystemFeature(String featureName, int version) {
        return false;
    }

    /** @hide */
    public boolean hasSystemFeature(String featureName) {
        return hasSystemFeature(featureName, 0);
    }
}
