/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.graphics.cam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CamTest {
    static final int BLACK = 0xff000000;
    static final int WHITE = 0xffffffff;
    static final int MIDGRAY = 0xff777777;

    static final int RED = 0xffff0000;
    static final int GREEN = 0xff00ff00;
    static final int BLUE = 0xff0000ff;

    @Test
    public void camFromIntToInt() {
        Cam cam = Cam.fromInt(RED);
        int color = cam.viewed(Frame.DEFAULT);
        assertEquals(color, RED);
    }

    @Test
    public void yFromMidgray() {
        assertEquals(18.418f, CamUtils.yFromLstar(50.0f), 0.001);
    }

    @Test
    public void yFromBlack() {
        assertEquals(0.0f, CamUtils.yFromLstar(0.0f), 0.001);
    }

    @Test
    public void yFromWhite() {
        assertEquals(100.0f, CamUtils.yFromLstar(100.0f), 0.001);
    }

    @Test
    public void camFromRed() {
        Cam cam = Cam.fromInt(RED);
        assertEquals(46.445f, cam.getJ(), 0.001f);
        assertEquals(113.357f, cam.getChroma(), 0.001f);
        assertEquals(27.408f, cam.getHue(), 0.001f);
        assertEquals(89.494f, cam.getM(), 0.001f);
        assertEquals(91.889f, cam.getS(), 0.001f);
        assertEquals(105.988f, cam.getQ(), 0.001f);
    }

    @Test
    public void camFromGreen() {
        Cam cam = Cam.fromInt(GREEN);
        assertEquals(79.331f, cam.getJ(), 0.001f);
        assertEquals(108.409f, cam.getChroma(), 0.001f);
        assertEquals(142.139f, cam.getHue(), 0.001f);
        assertEquals(85.587f, cam.getM(), 0.001f);
        assertEquals(78.604f, cam.getS(), 0.001f);
        assertEquals(138.520, cam.getQ(), 0.001f);
    }

    @Test
    public void camFromBlue() {
        Cam cam = Cam.fromInt(BLUE);
        assertEquals(25.465f, cam.getJ(), 0.001f);
        assertEquals(87.230f, cam.getChroma(), 0.001f);
        assertEquals(282.788f, cam.getHue(), 0.001f);
        assertEquals(68.867f, cam.getM(), 0.001f);
        assertEquals(93.674f, cam.getS(), 0.001f);
        assertEquals(78.481f, cam.getQ(), 0.001f);
    }

    @Test
    public void camFromBlack() {
        Cam cam = Cam.fromInt(BLACK);
        assertEquals(0.0f, cam.getJ(), 0.001f);
        assertEquals(0.0f, cam.getChroma(), 0.001f);
        assertEquals(0.0f, cam.getHue(), 0.001f);
        assertEquals(0.0f, cam.getM(), 0.001f);
        assertEquals(0.0f, cam.getS(), 0.001f);
        assertEquals(0.0f, cam.getQ(), 0.001f);
    }

    @Test
    public void camFromWhite() {
        Cam cam = Cam.fromInt(WHITE);
        assertEquals(100.0f, cam.getJ(), 0.001f);
        assertEquals(2.869f, cam.getChroma(), 0.001f);
        assertEquals(209.492f, cam.getHue(), 0.001f);
        assertEquals(2.265f, cam.getM(), 0.001f);
        assertEquals(12.068f, cam.getS(), 0.001f);
        assertEquals(155.521, cam.getQ(), 0.001f);
    }

    @Test
    public void getRedFromGamutMap() {
        int colorToTest = RED;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void getGreenFromGamutMap() {
        int colorToTest = GREEN;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void getBlueFromGamutMap() {
        int colorToTest = BLUE;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void getWhiteFromGamutMap() {
        int colorToTest = WHITE;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void getBlackFromGamutMap() {
        int colorToTest = BLACK;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void getMidgrayFromGamutMap() {
        int colorToTest = MIDGRAY;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void getRandomGreenFromGamutMap() {
        int colorToTest = 0xff009200;
        Cam cam = Cam.fromInt(colorToTest);
        int color = Cam.getInt(cam.getHue(), cam.getChroma(), CamUtils.lstarFromInt(colorToTest));
        assertEquals(colorToTest, color);
    }

    @Test
    public void gamutMapArbitraryHCL() {
        int color = Cam.getInt(309.0f, 40.0f, 70.0f);
        Cam cam = Cam.fromInt(color);

        assertEquals(308.759f, cam.getHue(), 0.001);
        assertEquals(40.148f, cam.getChroma(), 0.001);
        assertEquals(70.029f, CamUtils.lstarFromInt(color), 0.001f);
    }

    @Test
    public void ucsCoordinates() {
        Cam cam = Cam.fromInt(RED);

        assertEquals(59.584f, cam.getJstar(), 0.001f);
        assertEquals(43.297f, cam.getAstar(), 0.001f);
        assertEquals(22.451f, cam.getBstar(), 0.001f);
    }

    @Test
    public void deltaEWhiteToBlack() {
        assertEquals(25.661f, Cam.fromInt(WHITE).distance(Cam.fromInt(BLACK)), 0.001f);
    }

    @Test
    public void deltaERedToBlue() {
        assertEquals(21.415f, Cam.fromInt(RED).distance(Cam.fromInt(BLUE)), 0.001f);
    }
}
