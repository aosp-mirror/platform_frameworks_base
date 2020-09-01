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
 * Test that measures maximum amount of triangles can be rasterized keeping FPS close to the device
 * refresh rate. It is has very few devices call and each call contains big amount of triangles.
 * Total filling area is around one screen.
 */
public class TriangleCountOpenGLTest extends RenderPatchOpenGLTest  {
    // Based on index buffer of short values.
    private final static int MAX_TRIANGLES_IN_PATCH = 32000;

    public TriangleCountOpenGLTest(@NonNull GamePerformanceActivity activity) {
        super(activity);
    }

    @Override
    public String getName() {
        return "triangle_count";
    }

    @Override
    public String getUnitName() {
        return "ktriangles";
    }

    @Override
    public double getUnitScale() {
        return 2.0;
    }

    @Override
    public void initUnits(double trianlgeCountD) {
        final int triangleCount = (int)Math.round(trianlgeCountD * 1000.0);
        final List<RenderPatchAnimation> renderPatches = new ArrayList<>();
        final int patchCount =
                (triangleCount + MAX_TRIANGLES_IN_PATCH - 1) / MAX_TRIANGLES_IN_PATCH;
        final int patchTriangleCount = triangleCount / patchCount;
        for (int i = 0; i < patchCount; ++i) {
            final RenderPatch renderPatch = new RenderPatch(patchTriangleCount,
                                                            0.5f /* dimension */,
                                                            RenderPatch.TESSELLATION_TO_CENTER);
            renderPatches.add(new RenderPatchAnimation(renderPatch, getView().getRenderRatio()));
        }
        setRenderPatches(renderPatches);
    }
}