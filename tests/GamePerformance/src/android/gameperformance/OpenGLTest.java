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

import javax.microedition.khronos.opengles.GL;
import javax.microedition.khronos.opengles.GL10;

import android.annotation.NonNull;
import android.gameperformance.CustomOpenGLView.FrameDrawer;

/**
 * Base class for all OpenGL based tests.
 */
public abstract class OpenGLTest extends BaseTest {
    public OpenGLTest(@NonNull GamePerformanceActivity activity) {
        super(activity);
    }

    @NonNull
    public CustomOpenGLView getView() {
        return getActivity().getOpenGLView();
    }

    // Performs test drawing.
    protected abstract void draw(GL gl);

    // Initializes the test on first draw call.
    private class ParamFrameDrawer implements FrameDrawer {
        private final double mUnitCount;
        private boolean mInited;

        public ParamFrameDrawer(double unitCount) {
            mUnitCount = unitCount;
            mInited = false;
        }

        @Override
        public void drawFrame(GL10 gl) {
            if (!mInited) {
                initUnits(mUnitCount);
                mInited = true;
            }
            draw(gl);
        }
    }

    @Override
    protected void initProbePass(int probe) {
        try {
            getActivity().attachOpenGLView();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        getView().waitRenderReady();
        getView().setFrameDrawer(new ParamFrameDrawer(probe * getUnitScale()));
    }

    @Override
    protected void freeProbePass() {
        getView().setFrameDrawer(null);
    }
}