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

package com.android.server.display.color;

abstract class ColorTemperatureTintController extends TintController {

    abstract int getAppliedCct();

    abstract void setAppliedCct(int cct);

    abstract int getTargetCct();

    abstract void setTargetCct(int cct);

    /**
     * Returns the CCT value most closely associated with the "disabled" (identity) matrix for
     * this device, to use as the target when deactivating this transform.
     */
    abstract int getDisabledCct();

    abstract float[] computeMatrixForCct(int cct);

    abstract CctEvaluator getEvaluator();
}
