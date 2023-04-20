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

package com.android.systemui.biometrics.dagger

import com.android.systemui.biometrics.udfps.BoundingBoxOverlapDetector
import com.android.systemui.biometrics.udfps.EllipseOverlapDetector
import com.android.systemui.biometrics.udfps.OverlapDetector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import dagger.Module
import dagger.Provides

/** Dagger module for all things UDFPS. TODO(b/260558624): Move to BiometricsModule. */
@Module
interface UdfpsModule {
    companion object {

        @Provides
        @SysUISingleton
        fun providesOverlapDetector(featureFlags: FeatureFlags): OverlapDetector {
            return if (featureFlags.isEnabled(Flags.UDFPS_ELLIPSE_DETECTION)) {
                EllipseOverlapDetector()
            } else {
                BoundingBoxOverlapDetector()
            }
        }
    }
}
