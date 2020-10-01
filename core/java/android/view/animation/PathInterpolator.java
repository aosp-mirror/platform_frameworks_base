/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.view.animation;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Path;
import android.graphics.animation.HasNativeInterpolator;
import android.graphics.animation.NativeInterpolator;
import android.graphics.animation.NativeInterpolatorFactory;
import android.util.AttributeSet;
import android.util.PathParser;
import android.view.InflateException;

import com.android.internal.R;

/**
 * An interpolator that can traverse a Path that extends from <code>Point</code>
 * <code>(0, 0)</code> to <code>(1, 1)</code>. The x coordinate along the <code>Path</code>
 * is the input value and the output is the y coordinate of the line at that point.
 * This means that the Path must conform to a function <code>y = f(x)</code>.
 *
 * <p>The <code>Path</code> must not have gaps in the x direction and must not
 * loop back on itself such that there can be two points sharing the same x coordinate.
 * It is alright to have a disjoint line in the vertical direction:</p>
 * <p><blockquote><pre>
 *     Path path = new Path();
 *     path.lineTo(0.25f, 0.25f);
 *     path.moveTo(0.25f, 0.5f);
 *     path.lineTo(1f, 1f);
 * </pre></blockquote></p>
 */
@HasNativeInterpolator
public class PathInterpolator extends BaseInterpolator implements NativeInterpolator {

    // This governs how accurate the approximation of the Path is.
    private static final float PRECISION = 0.002f;

    private float[] mX; // x coordinates in the line

    private float[] mY; // y coordinates in the line

    /**
     * Create an interpolator for an arbitrary <code>Path</code>. The <code>Path</code>
     * must begin at <code>(0, 0)</code> and end at <code>(1, 1)</code>.
     *
     * @param path The <code>Path</code> to use to make the line representing the interpolator.
     */
    public PathInterpolator(Path path) {
        initPath(path);
    }

    /**
     * Create an interpolator for a quadratic Bezier curve. The end points
     * <code>(0, 0)</code> and <code>(1, 1)</code> are assumed.
     *
     * @param controlX The x coordinate of the quadratic Bezier control point.
     * @param controlY The y coordinate of the quadratic Bezier control point.
     */
    public PathInterpolator(float controlX, float controlY) {
        initQuad(controlX, controlY);
    }

    /**
     * Create an interpolator for a cubic Bezier curve.  The end points
     * <code>(0, 0)</code> and <code>(1, 1)</code> are assumed.
     *
     * @param controlX1 The x coordinate of the first control point of the cubic Bezier.
     * @param controlY1 The y coordinate of the first control point of the cubic Bezier.
     * @param controlX2 The x coordinate of the second control point of the cubic Bezier.
     * @param controlY2 The y coordinate of the second control point of the cubic Bezier.
     */
    public PathInterpolator(float controlX1, float controlY1, float controlX2, float controlY2) {
        initCubic(controlX1, controlY1, controlX2, controlY2);
    }

    public PathInterpolator(Context context, AttributeSet attrs) {
        this(context.getResources(), context.getTheme(), attrs);
    }

    /** @hide */
    public PathInterpolator(Resources res, Theme theme, AttributeSet attrs) {
        TypedArray a;
        if (theme != null) {
            a = theme.obtainStyledAttributes(attrs, R.styleable.PathInterpolator, 0, 0);
        } else {
            a = res.obtainAttributes(attrs, R.styleable.PathInterpolator);
        }
        parseInterpolatorFromTypeArray(a);
        setChangingConfiguration(a.getChangingConfigurations());
        a.recycle();
    }

    private void parseInterpolatorFromTypeArray(TypedArray a) {
        // If there is pathData defined in the xml file, then the controls points
        // will be all coming from pathData.
        if (a.hasValue(R.styleable.PathInterpolator_pathData)) {
            String pathData = a.getString(R.styleable.PathInterpolator_pathData);
            Path path = PathParser.createPathFromPathData(pathData);
            if (path == null) {
                throw new InflateException("The path is null, which is created"
                        + " from " + pathData);
            }
            initPath(path);
        } else {
            if (!a.hasValue(R.styleable.PathInterpolator_controlX1)) {
                throw new InflateException("pathInterpolator requires the controlX1 attribute");
            } else if (!a.hasValue(R.styleable.PathInterpolator_controlY1)) {
                throw new InflateException("pathInterpolator requires the controlY1 attribute");
            }
            float x1 = a.getFloat(R.styleable.PathInterpolator_controlX1, 0);
            float y1 = a.getFloat(R.styleable.PathInterpolator_controlY1, 0);

            boolean hasX2 = a.hasValue(R.styleable.PathInterpolator_controlX2);
            boolean hasY2 = a.hasValue(R.styleable.PathInterpolator_controlY2);

            if (hasX2 != hasY2) {
                throw new InflateException(
                        "pathInterpolator requires both controlX2 and controlY2 for cubic Beziers.");
            }

            if (!hasX2) {
                initQuad(x1, y1);
            } else {
                float x2 = a.getFloat(R.styleable.PathInterpolator_controlX2, 0);
                float y2 = a.getFloat(R.styleable.PathInterpolator_controlY2, 0);
                initCubic(x1, y1, x2, y2);
            }
        }
    }

    private void initQuad(float controlX, float controlY) {
        Path path = new Path();
        path.moveTo(0, 0);
        path.quadTo(controlX, controlY, 1f, 1f);
        initPath(path);
    }

    private void initCubic(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(0, 0);
        path.cubicTo(x1, y1, x2, y2, 1f, 1f);
        initPath(path);
    }

    private void initPath(Path path) {
        float[] pointComponents = path.approximate(PRECISION);

        int numPoints = pointComponents.length / 3;
        if (pointComponents[1] != 0 || pointComponents[2] != 0
                || pointComponents[pointComponents.length - 2] != 1
                || pointComponents[pointComponents.length - 1] != 1) {
            throw new IllegalArgumentException("The Path must start at (0,0) and end at (1,1)");
        }

        mX = new float[numPoints];
        mY = new float[numPoints];
        float prevX = 0;
        float prevFraction = 0;
        int componentIndex = 0;
        for (int i = 0; i < numPoints; i++) {
            float fraction = pointComponents[componentIndex++];
            float x = pointComponents[componentIndex++];
            float y = pointComponents[componentIndex++];
            if (fraction == prevFraction && x != prevX) {
                throw new IllegalArgumentException(
                        "The Path cannot have discontinuity in the X axis.");
            }
            if (x < prevX) {
                throw new IllegalArgumentException("The Path cannot loop back on itself.");
            }
            mX[i] = x;
            mY[i] = y;
            prevX = x;
            prevFraction = fraction;
        }
    }

    /**
     * Using the line in the Path in this interpolator that can be described as
     * <code>y = f(x)</code>, finds the y coordinate of the line given <code>t</code>
     * as the x coordinate. Values less than 0 will always return 0 and values greater
     * than 1 will always return 1.
     *
     * @param t Treated as the x coordinate along the line.
     * @return The y coordinate of the Path along the line where x = <code>t</code>.
     * @see Interpolator#getInterpolation(float)
     */
    @Override
    public float getInterpolation(float t) {
        if (t <= 0) {
            return 0;
        } else if (t >= 1) {
            return 1;
        }
        // Do a binary search for the correct x to interpolate between.
        int startIndex = 0;
        int endIndex = mX.length - 1;

        while (endIndex - startIndex > 1) {
            int midIndex = (startIndex + endIndex) / 2;
            if (t < mX[midIndex]) {
                endIndex = midIndex;
            } else {
                startIndex = midIndex;
            }
        }

        float xRange = mX[endIndex] - mX[startIndex];
        if (xRange == 0) {
            return mY[startIndex];
        }

        float tInRange = t - mX[startIndex];
        float fraction = tInRange / xRange;

        float startY = mY[startIndex];
        float endY = mY[endIndex];
        return startY + (fraction * (endY - startY));
    }

    /** @hide **/
    @Override
    public long createNativeInterpolator() {
        return NativeInterpolatorFactory.createPathInterpolator(mX, mY);
    }

}
