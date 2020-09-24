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

package com.android.server.display.color;

import static com.google.common.truth.Truth.assertThat;

import android.opengl.Matrix;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GlobalSaturationTintControllerTest {

    @Test
    public void setAndGetMatrix() {
        final GlobalSaturationTintController tintController = new GlobalSaturationTintController();
        tintController.setMatrix(50);
        assertThat(tintController.getMatrix()).usingTolerance(0.00001f)
                .containsExactly(
                        0.6155f, 0.1155f, 0.1155f, 0.0f, 0.3575f, 0.85749996f, 0.3575f,
                        0.0f, 0.036f, 0.036f, 0.536f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                .inOrder();
    }

    @Test
    public void resetMatrix() {
        final GlobalSaturationTintController tintController = new GlobalSaturationTintController();
        tintController.setMatrix(100);
        final float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);
        assertThat(tintController.getMatrix()).usingTolerance(0.00001f)
                .containsExactly(matrix).inOrder();
    }
}
