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
package com.android.systemui.surfaceeffects.turbulencenoise

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE_FRACTAL
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class TurbulenceNoiseShaderTest : SysuiTestCase() {

    private lateinit var turbulenceNoiseShader: TurbulenceNoiseShader

    @Test
    fun compilesSimplexNoise() {
        turbulenceNoiseShader = TurbulenceNoiseShader(baseType = SIMPLEX_NOISE)
    }

    @Test
    fun compilesFractalNoise() {
        turbulenceNoiseShader = TurbulenceNoiseShader(baseType = SIMPLEX_NOISE_FRACTAL)
    }
}
