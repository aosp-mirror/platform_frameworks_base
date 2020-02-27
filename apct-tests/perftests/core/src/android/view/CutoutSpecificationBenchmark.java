/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.PathParser;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class CutoutSpecificationBenchmark {
    private static final String TAG = "CutoutSpecificationBenchmark";

    private static final String BOTTOM_MARKER = "@bottom";
    private static final String DP_MARKER = "@dp";
    private static final String RIGHT_MARKER = "@right";
    private static final String LEFT_MARKER = "@left";

    private static final String DOUBLE_CUTOUT_SPEC = "M 0,0\n"
            + "L -72, 0\n"
            + "L -69.9940446283, 20.0595537175\n"
            + "C -69.1582133885, 28.4178661152 -65.2, 32.0 -56.8, 32.0\n"
            + "L 56.8, 32.0\n"
            + "C 65.2, 32.0 69.1582133885, 28.4178661152 69.9940446283, 20.0595537175\n"
            + "L 72, 0\n"
            + "Z\n"
            + "@bottom\n"
            + "M 0,0\n"
            + "L -72, 0\n"
            + "L -69.9940446283, -20.0595537175\n"
            + "C -69.1582133885, -28.4178661152 -65.2, -32.0 -56.8, -32.0\n"
            + "L 56.8, -32.0\n"
            + "C 65.2, -32.0 69.1582133885, -28.4178661152 69.9940446283, -20.0595537175\n"
            + "L 72, 0\n"
            + "Z\n"
            + "@dp";
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Context mContext;
    private DisplayMetrics mDisplayMetrics;

    /**
     * Setup the necessary member field used by test methods.
     */
    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mDisplayMetrics = new DisplayMetrics();
        mContext.getDisplay().getRealMetrics(mDisplayMetrics);
    }


    private static void toRectAndAddToRegion(Path p, Region inoutRegion, Rect inoutRect) {
        final RectF rectF = new RectF();
        p.computeBounds(rectF, false /* unused */);
        rectF.round(inoutRect);
        inoutRegion.op(inoutRect, Region.Op.UNION);
    }

    private static void oldMethodParsingSpec(String spec, int displayWidth, int displayHeight,
            float density) {
        Path p = null;
        Rect boundTop = null;
        Rect boundBottom = null;
        Rect safeInset = new Rect();
        String bottomSpec = null;
        if (!TextUtils.isEmpty(spec)) {
            spec = spec.trim();
            final float offsetX;
            if (spec.endsWith(RIGHT_MARKER)) {
                offsetX = displayWidth;
                spec = spec.substring(0, spec.length() - RIGHT_MARKER.length()).trim();
            } else if (spec.endsWith(LEFT_MARKER)) {
                offsetX = 0;
                spec = spec.substring(0, spec.length() - LEFT_MARKER.length()).trim();
            } else {
                offsetX = displayWidth / 2f;
            }
            final boolean inDp = spec.endsWith(DP_MARKER);
            if (inDp) {
                spec = spec.substring(0, spec.length() - DP_MARKER.length());
            }

            if (spec.contains(BOTTOM_MARKER)) {
                String[] splits = spec.split(BOTTOM_MARKER, 2);
                spec = splits[0].trim();
                bottomSpec = splits[1].trim();
            }

            final Matrix m = new Matrix();
            final Region r = Region.obtain();
            if (!spec.isEmpty()) {
                try {
                    p = PathParser.createPathFromPathData(spec);
                } catch (Throwable e) {
                    Log.wtf(TAG, "Could not inflate cutout: ", e);
                }

                if (p != null) {
                    if (inDp) {
                        m.postScale(density, density);
                    }
                    m.postTranslate(offsetX, 0);
                    p.transform(m);

                    boundTop = new Rect();
                    toRectAndAddToRegion(p, r, boundTop);
                    safeInset.top = boundTop.bottom;
                }
            }

            if (bottomSpec != null) {
                int bottomInset = 0;
                Path bottomPath = null;
                try {
                    bottomPath = PathParser.createPathFromPathData(bottomSpec);
                } catch (Throwable e) {
                    Log.wtf(TAG, "Could not inflate bottom cutout: ", e);
                }

                if (bottomPath != null) {
                    // Keep top transform
                    m.postTranslate(0, displayHeight);
                    bottomPath.transform(m);
                    p.addPath(bottomPath);
                    boundBottom = new Rect();
                    toRectAndAddToRegion(bottomPath, r, boundBottom);
                    bottomInset = displayHeight - boundBottom.top;
                }
                safeInset.bottom = bottomInset;
            }
        }
    }

    @Test
    public void parseByOldMethodForDoubleCutout() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            oldMethodParsingSpec(DOUBLE_CUTOUT_SPEC, mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels, mDisplayMetrics.density);
        }
    }

    @Test
    public void parseByNewMethodForDoubleCutout() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new CutoutSpecification.Parser(mDisplayMetrics.density,
                    mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels)
                    .parse(DOUBLE_CUTOUT_SPEC);
        }
    }

    @Test
    public void parseLongEdgeCutout() {
        final String spec = "M 0,0\n"
                + "H 48\n"
                + "V 48\n"
                + "H -48\n"
                + "Z\n"
                + "@left\n"
                + "@center_vertical\n"
                + "M 0,0\n"
                + "H 48\n"
                + "V 48\n"
                + "H -48\n"
                + "Z\n"
                + "@left\n"
                + "@center_vertical\n"
                + "M 0,0\n"
                + "H -48\n"
                + "V 48\n"
                + "H 48\n"
                + "Z\n"
                + "@right\n"
                + "@dp";

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new CutoutSpecification.Parser(mDisplayMetrics.density,
                    mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels).parse(spec);
        }
    }

    @Test
    public void parseShortEdgeCutout() {
        final String spec = "M 0,0\n"
                + "H 48\n"
                + "V 48\n"
                + "H -48\n"
                + "Z\n"
                + "@bottom\n"
                + "M 0,0\n"
                + "H 48\n"
                + "V -48\n"
                + "H -48\n"
                + "Z\n"
                + "@dp";

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new CutoutSpecification.Parser(mDisplayMetrics.density,
                    mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels).parse(spec);
        }
    }
}
