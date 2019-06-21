/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.gameperformance;

import java.util.ArrayList;
import java.util.List;

import android.annotation.NonNull;

/**
 * Tests that verifies maximum number of device calls to render the geometry to keep FPS close to
 * the device refresh rate. This uses trivial one triangle patch that is rendered multiple times.
 */
public class DeviceCallsOpenGLTest extends RenderPatchOpenGLTest  {

    public DeviceCallsOpenGLTest(@NonNull GamePerformanceActivity activity) {
        super(activity);
    }

    @Override
    public String getName() {
        return "device_calls";
    }

    @Override
    public String getUnitName() {
        return "calls";
    }

    @Override
    public double getUnitScale() {
        return 25.0;
    }

    @Override
    public void initUnits(double deviceCallsD) {
        final List<RenderPatchAnimation> renderPatches = new ArrayList<>();
        final RenderPatch renderPatch = new RenderPatch(1 /* triangleCount */,
                                                        0.05f /* dimension */,
                                                        RenderPatch.TESSELLATION_BASE);
        final int deviceCalls = (int)Math.round(deviceCallsD);
        for (int i = 0; i < deviceCalls; ++i) {
            renderPatches.add(new RenderPatchAnimation(renderPatch, getView().getRenderRatio()));
        }
        setRenderPatches(renderPatches);
    }
}