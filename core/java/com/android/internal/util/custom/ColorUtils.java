/*
 * Copyright (c) 2011-2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.util.custom;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.MathUtils;

import com.android.internal.util.custom.palette.Palette;

import java.util.Collections;
import java.util.Comparator;

/**
 * Helper class for colorspace conversions, and color-related
 * algorithms which may be generally useful.
 */
public class ColorUtils {

    private static int[] SOLID_COLORS = new int[] {
        Color.RED, 0xFFFFA500, Color.YELLOW, Color.GREEN, Color.CYAN,
        Color.BLUE, Color.MAGENTA, Color.WHITE, Color.BLACK
    };

    /**
     * Drop the alpha component from an RGBA packed int and return
     * a non sign-extended RGB int.
     *
     * @param rgba
     * @return rgb
     */
    public static int dropAlpha(int rgba) {
        return rgba & 0x00FFFFFF;
    }

    /**
     * Converts an RGB packed int into L*a*b space, which is well-suited for finding
     * perceptual differences in color
     *
     * @param rgb A 32-bit value of packed RGB ints
     * @return array of Lab values of size 3
     */
    public static float[] convertRGBtoLAB(int rgb) {
        float[] lab = new float[3];
        float fx, fy, fz;
        float eps = 216.f / 24389.f;
        float k = 24389.f / 27.f;

        float Xr = 0.964221f;  // reference white D50
        float Yr = 1.0f;
        float Zr = 0.825211f;

        // RGB to XYZ
        float r = Color.red(rgb) / 255.f; //R 0..1
        float g = Color.green(rgb) / 255.f; //G 0..1
        float b = Color.blue(rgb) / 255.f; //B 0..1

        // assuming sRGB (D65)
        if (r <= 0.04045)
            r = r / 12;
        else
            r = (float) Math.pow((r + 0.055) / 1.055, 2.4);

        if (g <= 0.04045)
            g = g / 12;
        else
            g = (float) Math.pow((g + 0.055) / 1.055, 2.4);

        if (b <= 0.04045)
            b = b / 12;
        else
            b = (float) Math.pow((b + 0.055) / 1.055, 2.4);

        float X = 0.436052025f * r + 0.385081593f * g + 0.143087414f * b;
        float Y = 0.222491598f * r + 0.71688606f * g + 0.060621486f * b;
        float Z = 0.013929122f * r + 0.097097002f * g + 0.71418547f * b;

        // XYZ to Lab
        float xr = X / Xr;
        float yr = Y / Yr;
        float zr = Z / Zr;

        if (xr > eps)
            fx = (float) Math.pow(xr, 1 / 3.);
        else
            fx = (float) ((k * xr + 16.) / 116.);

        if (yr > eps)
            fy = (float) Math.pow(yr, 1 / 3.);
        else
            fy = (float) ((k * yr + 16.) / 116.);

        if (zr > eps)
            fz = (float) Math.pow(zr, 1 / 3.);
        else
            fz = (float) ((k * zr + 16.) / 116);

        float Ls = (116 * fy) - 16;
        float as = 500 * (fx - fy);
        float bs = 200 * (fy - fz);

        lab[0] = (2.55f * Ls + .5f);
        lab[1] = (as + .5f);
        lab[2] = (bs + .5f);

        return lab;
    }

    /**
     * Calculate the colour difference value between two colours in lab space.
     * This code is from OpenIMAJ under BSD License
     *
     * @param L1 first colour's L component
     * @param a1 first colour's a component
     * @param b1 first colour's b component
     * @param L2 second colour's L component
     * @param a2 second colour's a component
     * @param b2 second colour's b component
     * @return the CIE 2000 colour difference
     */
    public static double calculateDeltaE(double L1, double a1, double b1,
            double L2, double a2, double b2) {
        double Lmean = (L1 + L2) / 2.0;
        double C1 = Math.sqrt(a1 * a1 + b1 * b1);
        double C2 = Math.sqrt(a2 * a2 + b2 * b2);
        double Cmean = (C1 + C2) / 2.0;

        double G = (1 - Math.sqrt(Math.pow(Cmean, 7) / (Math.pow(Cmean, 7) + Math.pow(25, 7)))) / 2;
        double a1prime = a1 * (1 + G);
        double a2prime = a2 * (1 + G);

        double C1prime = Math.sqrt(a1prime * a1prime + b1 * b1);
        double C2prime = Math.sqrt(a2prime * a2prime + b2 * b2);
        double Cmeanprime = (C1prime + C2prime) / 2;

        double h1prime = Math.atan2(b1, a1prime)
                + 2 * Math.PI * (Math.atan2(b1, a1prime) < 0 ? 1 : 0);
        double h2prime = Math.atan2(b2, a2prime)
                + 2 * Math.PI * (Math.atan2(b2, a2prime) < 0 ? 1 : 0);
        double Hmeanprime = ((Math.abs(h1prime - h2prime) > Math.PI)
                ? (h1prime + h2prime + 2 * Math.PI) / 2 : (h1prime + h2prime) / 2);

        double T = 1.0 - 0.17 * Math.cos(Hmeanprime - Math.PI / 6.0)
                + 0.24 * Math.cos(2 * Hmeanprime) + 0.32 * Math.cos(3 * Hmeanprime + Math.PI / 30)
                - 0.2 * Math.cos(4 * Hmeanprime - 21 * Math.PI / 60);

        double deltahprime = ((Math.abs(h1prime - h2prime) <= Math.PI) ? h2prime - h1prime
                : (h2prime <= h1prime) ? h2prime - h1prime + 2 * Math.PI
                        : h2prime - h1prime - 2 * Math.PI);

        double deltaLprime = L2 - L1;
        double deltaCprime = C2prime - C1prime;
        double deltaHprime = 2.0 * Math.sqrt(C1prime * C2prime) * Math.sin(deltahprime / 2.0);
        double SL = 1.0 + ((0.015 * (Lmean - 50) * (Lmean - 50))
                / (Math.sqrt(20 + (Lmean - 50) * (Lmean - 50))));
        double SC = 1.0 + 0.045 * Cmeanprime;
        double SH = 1.0 + 0.015 * Cmeanprime * T;

        double deltaTheta = (30 * Math.PI / 180)
                * Math.exp(-((180 / Math.PI * Hmeanprime - 275) / 25)
                        * ((180 / Math.PI * Hmeanprime - 275) / 25));
        double RC = (2
                * Math.sqrt(Math.pow(Cmeanprime, 7) / (Math.pow(Cmeanprime, 7) + Math.pow(25, 7))));
        double RT = (-RC * Math.sin(2 * deltaTheta));

        double KL = 1;
        double KC = 1;
        double KH = 1;

        double deltaE = Math.sqrt(
                ((deltaLprime / (KL * SL)) * (deltaLprime / (KL * SL))) +
                        ((deltaCprime / (KC * SC)) * (deltaCprime / (KC * SC))) +
                        ((deltaHprime / (KH * SH)) * (deltaHprime / (KH * SH))) +
                        (RT * (deltaCprime / (KC * SC)) * (deltaHprime / (KH * SH))));

        return deltaE;
    }

    /**
     * Finds the "perceptually nearest" color from a list of colors to
     * the given RGB value. This is done by converting to
     * L*a*b colorspace and using the CIE2000 deltaE algorithm.
     *
     * @param rgb The original color to start with
     * @param colors An array of colors to test
     * @return RGB packed int of nearest color in the list
     */
    public static int findPerceptuallyNearestColor(int rgb, int[] colors) {
        int nearestColor = 0;
        double closest = Double.MAX_VALUE;

        float[] original = convertRGBtoLAB(rgb);

        for (int i = 0; i < colors.length; i++) {
            float[] cl = convertRGBtoLAB(colors[i]);
            double deltaE = calculateDeltaE(original[0], original[1], original[2],
                                            cl[0], cl[1], cl[2]);
            if (deltaE < closest) {
                nearestColor = colors[i];
                closest = deltaE;
            }
        }
        return nearestColor;
    }

    /**
     * Convenience method to find the nearest "solid" color (having RGB components
     * of either 0 or 255) to the given color. This is useful for cases such as
     * LED notification lights which may not be able to display the full range
     * of colors due to hardware limitations.
     *
     * @param rgb
     * @return the perceptually nearest color in RGB
     */
    public static int findPerceptuallyNearestSolidColor(int rgb) {
        return findPerceptuallyNearestColor(rgb, SOLID_COLORS);
    }

    /**
     * Given a Palette, pick out the dominant swatch based on population
     *
     * @param palette
     * @return the dominant Swatch
     */
    public static Palette.Swatch getDominantSwatch(Palette palette) {
        if (palette == null || palette.getSwatches().size() == 0) {
            return null;
        }
        // find most-represented swatch based on population
        return Collections.max(palette.getSwatches(), new Comparator<Palette.Swatch>() {
            @Override
            public int compare(Palette.Swatch sw1, Palette.Swatch sw2) {
                return Integer.compare(sw1.getPopulation(), sw2.getPopulation());
            }
        });
    }

    /**
     * Takes a drawable and uses Palette to generate a suitable "alert"
     * color which can be used for an external notification mechanism
     * such as an RGB LED. This will always pick a solid color having
     * RGB components of 255 or 0.
     *
     * @param drawable The drawable to generate a color for
     * @return a suitable solid color which corresponds to the image
     */
    public static int generateAlertColorFromDrawable(Drawable drawable) {
        int alertColor = Color.BLACK;
        Bitmap bitmap = null;

        if (drawable == null) {
            return alertColor;
        }

        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            bitmap = Bitmap.createBitmap(Math.max(1, width),
                                         Math.max(1, height),
                                         Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }

        if (bitmap != null) {
            Palette p = Palette.from(bitmap).generate();
            if (p == null) {
                return alertColor;
            }

            // First try the dominant color
            final Palette.Swatch dominantSwatch = getDominantSwatch(p);
            int iconColor = alertColor;
            if (dominantSwatch != null) {
                iconColor = dominantSwatch.getRgb();
                alertColor = findPerceptuallyNearestSolidColor(iconColor);
            }

            // Try the most saturated color if we got white or black (boring)
            if (alertColor == Color.BLACK || alertColor == Color.WHITE) {
                iconColor = p.getVibrantColor(Color.WHITE);
                alertColor = findPerceptuallyNearestSolidColor(iconColor);
            }

            if (!(drawable instanceof BitmapDrawable)) {
                bitmap.recycle();
            }
        }

        return alertColor;
    }

    /**
     * Convert a color temperature value (in Kelvin) to a RGB units as floats.
     * This can be used in a transform matrix or hardware gamma control.
     *
     * @param degreesK
     * @return array of floats representing rgb values 0->1
     */
    public static float[] temperatureToRGB(int degreesK) {
        int k = MathUtils.constrain(degreesK, 1000, 20000);
        float a = (k % 100) / 100.0f;
        int i = ((k - 1000)/ 100) * 3;

        return new float[] { interp(i, a), interp(i+1, a), interp(i+2, a) };
    }

    private static float interp(int i, float a) {
        return MathUtils.lerp((float)sColorTable[i], (float)sColorTable[i+3], a);
    }

    /**
     * This table is a modified version of the original blackbody chart, found here:
     * http://www.vendian.org/mncharity/dir3/blackbody/UnstableURLs/bbr_color.html
     *
     * Created by Ingo Thiel.
     */
    private static final double[] sColorTable = new double[] {
            1.00000000, 0.18172716, 0.00000000,
            1.00000000, 0.25503671, 0.00000000,
            1.00000000, 0.30942099, 0.00000000,
            1.00000000, 0.35357379, 0.00000000,
            1.00000000, 0.39091524, 0.00000000,
            1.00000000, 0.42322816, 0.00000000,
            1.00000000, 0.45159884, 0.00000000,
            1.00000000, 0.47675916, 0.00000000,
            1.00000000, 0.49923747, 0.00000000,
            1.00000000, 0.51943421, 0.00000000,
            1.00000000, 0.54360078, 0.08679949,
            1.00000000, 0.56618736, 0.14065513,
            1.00000000, 0.58734976, 0.18362641,
            1.00000000, 0.60724493, 0.22137978,
            1.00000000, 0.62600248, 0.25591950,
            1.00000000, 0.64373109, 0.28819679,
            1.00000000, 0.66052319, 0.31873863,
            1.00000000, 0.67645822, 0.34786758,
            1.00000000, 0.69160518, 0.37579588,
            1.00000000, 0.70602449, 0.40267128,
            1.00000000, 0.71976951, 0.42860152,
            1.00000000, 0.73288760, 0.45366838,
            1.00000000, 0.74542112, 0.47793608,
            1.00000000, 0.75740814, 0.50145662,
            1.00000000, 0.76888303, 0.52427322,
            1.00000000, 0.77987699, 0.54642268,
            1.00000000, 0.79041843, 0.56793692,
            1.00000000, 0.80053332, 0.58884417,
            1.00000000, 0.81024551, 0.60916971,
            1.00000000, 0.81957693, 0.62893653,
            1.00000000, 0.82854786, 0.64816570,
            1.00000000, 0.83717703, 0.66687674,
            1.00000000, 0.84548188, 0.68508786,
            1.00000000, 0.85347859, 0.70281616,
            1.00000000, 0.86118227, 0.72007777,
            1.00000000, 0.86860704, 0.73688797,
            1.00000000, 0.87576611, 0.75326132,
            1.00000000, 0.88267187, 0.76921169,
            1.00000000, 0.88933596, 0.78475236,
            1.00000000, 0.89576933, 0.79989606,
            1.00000000, 0.90198230, 0.81465502,
            1.00000000, 0.90963069, 0.82838210,
            1.00000000, 0.91710889, 0.84190889,
            1.00000000, 0.92441842, 0.85523742,
            1.00000000, 0.93156127, 0.86836903,
            1.00000000, 0.93853986, 0.88130458,
            1.00000000, 0.94535695, 0.89404470,
            1.00000000, 0.95201559, 0.90658983,
            1.00000000, 0.95851906, 0.91894041,
            1.00000000, 0.96487079, 0.93109690,
            1.00000000, 0.97107439, 0.94305985,
            1.00000000, 0.97713351, 0.95482993,
            1.00000000, 0.98305189, 0.96640795,
            1.00000000, 0.98883326, 0.97779486,
            1.00000000, 0.99448139, 0.98899179,
            1.00000000, 1.00000000, 1.00000000,
            0.98947904, 0.99348723, 1.00000000,
            0.97940448, 0.98722715, 1.00000000,
            0.96975025, 0.98120637, 1.00000000,
            0.96049223, 0.97541240, 1.00000000,
            0.95160805, 0.96983355, 1.00000000,
            0.94303638, 0.96443333, 1.00000000,
            0.93480451, 0.95923080, 1.00000000,
            0.92689056, 0.95421394, 1.00000000,
            0.91927697, 0.94937330, 1.00000000,
            0.91194747, 0.94470005, 1.00000000,
            0.90488690, 0.94018594, 1.00000000,
            0.89808115, 0.93582323, 1.00000000,
            0.89151710, 0.93160469, 1.00000000,
            0.88518247, 0.92752354, 1.00000000,
            0.87906581, 0.92357340, 1.00000000,
            0.87315640, 0.91974827, 1.00000000,
            0.86744421, 0.91604254, 1.00000000,
            0.86191983, 0.91245088, 1.00000000,
            0.85657444, 0.90896831, 1.00000000,
            0.85139976, 0.90559011, 1.00000000,
            0.84638799, 0.90231183, 1.00000000,
            0.84153180, 0.89912926, 1.00000000,
            0.83682430, 0.89603843, 1.00000000,
            0.83225897, 0.89303558, 1.00000000,
            0.82782969, 0.89011714, 1.00000000,
            0.82353066, 0.88727974, 1.00000000,
            0.81935641, 0.88452017, 1.00000000,
            0.81530175, 0.88183541, 1.00000000,
            0.81136180, 0.87922257, 1.00000000,
            0.80753191, 0.87667891, 1.00000000,
            0.80380769, 0.87420182, 1.00000000,
            0.80018497, 0.87178882, 1.00000000,
            0.79665980, 0.86943756, 1.00000000,
            0.79322843, 0.86714579, 1.00000000,
            0.78988728, 0.86491137, 1.00000000,
            0.78663296, 0.86273225, 1.00000000,
            0.78346225, 0.86060650, 1.00000000,
            0.78037207, 0.85853224, 1.00000000,
            0.77735950, 0.85650771, 1.00000000,
            0.77442176, 0.85453121, 1.00000000,
            0.77155617, 0.85260112, 1.00000000,
            0.76876022, 0.85071588, 1.00000000,
            0.76603147, 0.84887402, 1.00000000,
            0.76336762, 0.84707411, 1.00000000,
            0.76076645, 0.84531479, 1.00000000,
            0.75822586, 0.84359476, 1.00000000,
            0.75574383, 0.84191277, 1.00000000,
            0.75331843, 0.84026762, 1.00000000,
            0.75094780, 0.83865816, 1.00000000,
            0.74863017, 0.83708329, 1.00000000,
            0.74636386, 0.83554194, 1.00000000,
            0.74414722, 0.83403311, 1.00000000,
            0.74197871, 0.83255582, 1.00000000,
            0.73985682, 0.83110912, 1.00000000,
            0.73778012, 0.82969211, 1.00000000,
            0.73574723, 0.82830393, 1.00000000,
            0.73375683, 0.82694373, 1.00000000,
            0.73180765, 0.82561071, 1.00000000,
            0.72989845, 0.82430410, 1.00000000,
            0.72802807, 0.82302316, 1.00000000,
            0.72619537, 0.82176715, 1.00000000,
            0.72439927, 0.82053539, 1.00000000,
            0.72263872, 0.81932722, 1.00000000,
            0.72091270, 0.81814197, 1.00000000,
            0.71922025, 0.81697905, 1.00000000,
            0.71756043, 0.81583783, 1.00000000,
            0.71593234, 0.81471775, 1.00000000,
            0.71433510, 0.81361825, 1.00000000,
            0.71276788, 0.81253878, 1.00000000,
            0.71122987, 0.81147883, 1.00000000,
            0.70972029, 0.81043789, 1.00000000,
            0.70823838, 0.80941546, 1.00000000,
            0.70678342, 0.80841109, 1.00000000,
            0.70535469, 0.80742432, 1.00000000,
            0.70395153, 0.80645469, 1.00000000,
            0.70257327, 0.80550180, 1.00000000,
            0.70121928, 0.80456522, 1.00000000,
            0.69988894, 0.80364455, 1.00000000,
            0.69858167, 0.80273941, 1.00000000,
            0.69729688, 0.80184943, 1.00000000,
            0.69603402, 0.80097423, 1.00000000,
            0.69479255, 0.80011347, 1.00000000,
            0.69357196, 0.79926681, 1.00000000,
            0.69237173, 0.79843391, 1.00000000,
            0.69119138, 0.79761446, 1.00000000,
            0.69003044, 0.79680814, 1.00000000,
            0.68888844, 0.79601466, 1.00000000,
            0.68776494, 0.79523371, 1.00000000,
            0.68665951, 0.79446502, 1.00000000,
            0.68557173, 0.79370830, 1.00000000,
            0.68450119, 0.79296330, 1.00000000,
            0.68344751, 0.79222975, 1.00000000,
            0.68241029, 0.79150740, 1.00000000,
            0.68138918, 0.79079600, 1.00000000,
            0.68038380, 0.79009531, 1.00000000,
            0.67939381, 0.78940511, 1.00000000,
            0.67841888, 0.78872517, 1.00000000,
            0.67745866, 0.78805526, 1.00000000,
            0.67651284, 0.78739518, 1.00000000,
            0.67558112, 0.78674472, 1.00000000,
            0.67466317, 0.78610368, 1.00000000,
            0.67375872, 0.78547186, 1.00000000,
            0.67286748, 0.78484907, 1.00000000,
            0.67198916, 0.78423512, 1.00000000,
            0.67112350, 0.78362984, 1.00000000,
            0.67027024, 0.78303305, 1.00000000,
            0.66942911, 0.78244457, 1.00000000,
            0.66859988, 0.78186425, 1.00000000,
            0.66778228, 0.78129191, 1.00000000,
            0.66697610, 0.78072740, 1.00000000,
            0.66618110, 0.78017057, 1.00000000,
            0.66539706, 0.77962127, 1.00000000,
            0.66462376, 0.77907934, 1.00000000,
            0.66386098, 0.77854465, 1.00000000,
            0.66310852, 0.77801705, 1.00000000,
            0.66236618, 0.77749642, 1.00000000,
            0.66163375, 0.77698261, 1.00000000,
            0.66091106, 0.77647551, 1.00000000,
            0.66019791, 0.77597498, 1.00000000,
            0.65949412, 0.77548090, 1.00000000,
            0.65879952, 0.77499315, 1.00000000,
            0.65811392, 0.77451161, 1.00000000,
            0.65743716, 0.77403618, 1.00000000,
            0.65676908, 0.77356673, 1.00000000,
            0.65610952, 0.77310316, 1.00000000,
            0.65545831, 0.77264537, 1.00000000,
            0.65481530, 0.77219324, 1.00000000,
            0.65418036, 0.77174669, 1.00000000,
            0.65355332, 0.77130560, 1.00000000,
            0.65293404, 0.77086988, 1.00000000,
            0.65232240, 0.77043944, 1.00000000,
            0.65171824, 0.77001419, 1.00000000,
            0.65112144, 0.76959404, 1.00000000,
            0.65053187, 0.76917889, 1.00000000,
            0.64994941, 0.76876866, 1.00000000,
            0.64937392, 0.76836326, 1.00000000
    };

}
