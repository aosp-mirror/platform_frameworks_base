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

import com.android.internal.graphics.color.CieLab;
import com.android.internal.graphics.color.CieXyzAbs;
import com.android.internal.graphics.color.Zcam;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ZcamTest {
    @Test
    public void rgb8ToXyzAbs() {
        CieXyzAbs xyz = new CieXyzAbs(0xf3a177);
        approx(xyz.x, 106.0701075088416);
        approx(xyz.y, 91.7571442950486);
        approx(xyz.z, 47.031214129883175);
    }

    @Test
    public void xyzAbsToRgb8() {
        CieXyzAbs xyz = new CieXyzAbs(0xf3a177);
        int rgb = xyz.toRgb8();
        Assert.assertEquals(0xf3a177, rgb);
    }

    @Test
    public void lstarToZcamJz() {
        double jz = CieLab.lToZcamJz(50.0);
        approx(jz, 49.0802114144443);
    }

    @Test
    public void defaultViewingConditions() {
        Zcam.ViewingConditions cond = Zcam.ViewingConditions.DEFAULT;
        approx(cond.surroundFactor, 0.69);
        approx(cond.adaptingLuminance, 80.0);
        approx(cond.backgroundLuminance, 36.83730370248883);
        approx(cond.referenceWhite.x, 190.0911854103343);
        approx(cond.referenceWhite.y, 200.0);
        approx(cond.referenceWhite.z, 217.8115501519757);

        approx(cond.Iz_coeff, 735.5600123076448);
        approx(cond.ez_coeff, 0.9407449331256349);
        approx(cond.Qz_denom, 0.9034736074478125);
        approx(cond.Mz_denom, 0.4423628063922157);
        approx(cond.Qz_w, 234.01788828747445);
    }

    @Test
    public void zcamRoundTrip() {
        Zcam.ViewingConditions cond = new Zcam.ViewingConditions(
                Zcam.ViewingConditions.SURROUND_AVERAGE,
                264.0,
                100.0,
                new CieXyzAbs(250.92408, 264.0, 287.45112)
        );

        CieXyzAbs sample = new CieXyzAbs(182.232347, 206.57991269, 231.87358528);
        Zcam zcam = new Zcam(sample, cond);

        approx(zcam.lightness, 91.45992645);
        approx(zcam.chroma, 3.00322656);
        approx(zcam.hue, 197.26438822);

        // Now invert it
        CieXyzAbs inverted = zcam.toXyzAbs();
        approx(inverted.x, sample.x);
        approx(inverted.y, sample.y);
        approx(inverted.z, sample.z);
    }

    private static void approx(double actual, double expected) {
        Assert.assertEquals(expected, actual, 0.001);
    }
}

