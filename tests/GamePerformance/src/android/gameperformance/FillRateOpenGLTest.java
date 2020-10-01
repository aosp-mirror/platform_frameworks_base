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

import javax.microedition.khronos.opengles.GL;

import android.annotation.NonNull;
import android.opengl.GLES20;

/**
 * Tests that verifies maximum fill rate per frame can be used to keep FPS close to the device
 * refresh rate. It works in two modes, blend disabled and blend enabled. This uses few big simple
 * quad patches.
 */
public class FillRateOpenGLTest extends RenderPatchOpenGLTest  {
    private final float[] BLEND_COLOR = new float[] { 1.0f, 1.0f, 1.0f, 0.2f };

    private final boolean mTestBlend;

    public FillRateOpenGLTest(@NonNull GamePerformanceActivity activity, boolean testBlend) {
        super(activity);
        mTestBlend = testBlend;
    }

    @Override
    public String getName() {
        return mTestBlend ? "blend_rate" : "fill_rate";
    }

    @Override
    public String getUnitName() {
        return "screens";
    }

    @Override
    public double getUnitScale() {
        return 0.2;
    }

    @Override
    public void initUnits(double screens) {
        final CustomOpenGLView view = getView();
        final int pixelRate = (int)Math.round(screens * view.getHeight() * view.getWidth());
        final int maxPerPath = view.getHeight() * view.getHeight();

        final int patchCount = (int)(pixelRate + maxPerPath -1) / maxPerPath;
        final float patchDimension =
                (float)((Math.sqrt(2.0f) * pixelRate / patchCount) / maxPerPath);

        final List<RenderPatchAnimation> renderPatches = new ArrayList<>();
        final RenderPatch renderPatch = new RenderPatch(2 /* triangleCount for quad */,
                                                        patchDimension,
                                                        RenderPatch.TESSELLATION_BASE);
        for (int i = 0; i < patchCount; ++i) {
            renderPatches.add(new RenderPatchAnimation(renderPatch, getView().getRenderRatio()));
        }
        setRenderPatches(renderPatches);
    }

    @Override
    public float[] getColor() {
        return BLEND_COLOR;
    }

    @Override
    public void onBeforeDraw(GL gl) {
        if (!mTestBlend) {
            return;
        }

        // Enable blend if needed.
        GLES20.glEnable(GLES20.GL_BLEND);
        OpenGLUtils.checkGlError("disableBlend");
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        OpenGLUtils.checkGlError("blendFunction");
    }
}