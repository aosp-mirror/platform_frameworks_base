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

package android.opengl.perftests;

import android.opengl.Matrix;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;

import java.util.Random;

@LargeTest
public class MatrixPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public float[] array = new float[48];

    @Rule
    public float[] bigArray = new float[16 * 1024 * 1024];


    @Test
    public void testMultiplyMM() {
        Random rng = new Random();
        for (int i = 0; i < 32; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMM(array, 32, array, 16, array, 0);
        }
    }

    @Test
    public void testMultiplyMMLeftOverlapResult() {
        Random rng = new Random();
        for (int i = 0; i < 32; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMM(array, 16, array, 16, array, 0);
        }
    }

    @Test
    public void testMultiplyMMRightOverlapResult() {
        Random rng = new Random();
        for (int i = 0; i < 32; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMM(array, 0, array, 16, array, 0);
        }
    }

    @Test
    public void testMultiplyMMAllOverlap() {
        Random rng = new Random();
        for (int i = 0; i < 16; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMM(array, 0, array, 0, array, 0);
        }
    }

    @Test
    public void testMultiplyMMOutputBigArray() {
        Random rng = new Random();
        for (int i = 0; i < 32; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMM(bigArray, 1024, array, 16, array, 0);
        }
    }

    @Test
    public void testMultiplyMMAllBigArray() {
        Random rng = new Random();
        for (int i = 0; i < 32; i++) {
            bigArray[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMM(bigArray, 1024, bigArray, 16, bigArray, 0);
        }
    }

    @Test
    public void testMultiplyMV() {
        Random rng = new Random();
        for (int i = 0; i < 20; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMV(array, 20, array, 4, array, 0);
        }
    }

    @Test
    public void testMultiplyMVLeftOverlapResult() {
        Random rng = new Random();
        for (int i = 0; i < 20; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMV(array, 4, array, 4, array, 0);
        }
    }

    @Test
    public void testMultiplyMVRightOverlapResult() {
        Random rng = new Random();
        for (int i = 0; i < 32; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMV(array, 0, array, 16, array, 0);
        }
    }

    @Test
    public void testMultiplyMVAllOverlap() {
        Random rng = new Random();
        for (int i = 0; i < 16; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMV(array, 0, array, 0, array, 0);
        }
    }

    @Test
    public void testMultiplyMVOutputBigArray() {
        Random rng = new Random();
        for (int i = 0; i < 20; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMV(bigArray, 1024, array, 16, array, 0);
        }
    }

    @Test
    public void testMultiplyMVAllBigArray() {
        Random rng = new Random();
        for (int i = 0; i < 20; i++) {
            bigArray[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.multiplyMV(bigArray, 1024, bigArray, 16, bigArray, 0);
        }
    }

    @Test
    public void testTransposeM() {
        Random rng = new Random();
        for (int i = 0; i < 16; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.transposeM(array, 16, array, 0);
        }
    }

    @Test
    public void testInvertM() {
        // non-singular matrix
        array[ 0] =  0.814f;
        array[ 1] =  4.976f;
        array[ 2] = -3.858f;
        array[ 3] =  7.206f;
        array[ 4] =  5.112f;
        array[ 5] = -2.420f;
        array[ 6] =  8.791f;
        array[ 7] =  6.426f;
        array[ 8] =  2.945f;
        array[ 9] =  1.801f;
        array[10] = -2.594f;
        array[11] =  2.663f;
        array[12] = -5.003f;
        array[13] = -4.188f;
        array[14] =  3.340f;
        array[15] = -1.235f;

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.invertM(array, 16, array, 0);
        }
    }

    @Test
    public void testOrthoM() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.orthoM(array, 0, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        }
    }

    @Test
    public void testFrustumM() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.frustumM(array, 0, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        }
    }

    @Test
    public void testPerspectiveM() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.perspectiveM(array, 0, 45.0f, 1.0f, 1.0f, 100.0f);
        }
    }

    @Test
    public void testLength() {
        Random rng = new Random();
        for (int i = 0; i < 3; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.length(array[0], array[1], array[2]);
        }
    }

    @Test
    public void testSetIdentityM() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.setIdentityM(array, 0);
        }
    }

    @Test
    public void testScaleM() {
        Random rng = new Random();
        for (int i = 0; i < 19; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.scaleM(array, 19, array, 0, array[16], array[17], array[18]);
        }
    }

    @Test
    public void testScaleMInPlace() {
        Random rng = new Random();
        for (int i = 0; i < 19; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.scaleM(array, 0, array[16], array[17], array[18]);
        }
    }

    @Test
    public void testTranslateM() {
        Random rng = new Random();
        for (int i = 0; i < 19; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.translateM(array, 19, array, 0, array[16], array[17], array[18]);
        }
    }

    @Test
    public void testTranslateMInPlace() {
        Random rng = new Random();
        for (int i = 0; i < 19; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.translateM(array, 0, array[16], array[17], array[18]);
        }
    }

    @Test
    public void testRotateM() {
        Random rng = new Random();
        for (int i = 0; i < 20; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.rotateM(array, 20, array, 0, array[16], array[17], array[18], array[19]);
        }
    }

    @Test
    public void testRotateMInPlace() {
        Random rng = new Random();
        for (int i = 0; i < 20; i++) {
            array[i] = rng.nextFloat();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.rotateM(array, 0, array[16], array[17], array[18], array[19]);
        }
    }

    @Test
    public void testSetRotateM() {
        Random rng = new Random();
        array[0] = rng.nextFloat() * 90.0f;
        array[1] = rng.nextFloat() + 0.5f;
        array[2] = rng.nextFloat() + 0.5f;
        array[3] = rng.nextFloat() + 0.5f;

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.setRotateM(array, 4, array[0], array[1], array[2], array[3]);
        }
    }

    @Test
    public void testSetRotateEulerM() {
        Random rng = new Random();
        array[0] = rng.nextFloat() * 90.0f;
        array[1] = rng.nextFloat() * 90.0f;
        array[2] = rng.nextFloat() * 90.0f;

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.setRotateEulerM(array, 3, array[0], array[1], array[2]);
        }
    }

    @Test
    public void testSetLookAtM() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Matrix.setLookAtM(array, 9,
                    1.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 1.0f,
                    0.0f, 1.0f, 0.0f);
        }
    }
}
