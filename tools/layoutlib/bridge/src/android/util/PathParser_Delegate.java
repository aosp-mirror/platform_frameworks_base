/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.util;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.graphics.Path_Delegate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delegate that provides implementation for native methods in {@link android.util.PathParser}
 * <p/>
 * Through the layoutlib_create tool, selected methods of PathParser have been replaced by calls to
 * methods of the same name in this delegate class.
 *
 * Most of the code has been taken from the implementation in
 * {@code tools/base/sdk-common/src/main/java/com/android/ide/common/vectordrawable/PathParser.java}
 * revision be6fe89a3b686db5a75e7e692a148699973957f3
 */
public class PathParser_Delegate {

    private static final Logger LOGGER = Logger.getLogger("PathParser");

    // ---- Builder delegate manager ----
    private static final DelegateManager<PathParser_Delegate> sManager =
            new DelegateManager<PathParser_Delegate>(PathParser_Delegate.class);

    // ---- delegate data ----
    @NonNull
    private PathDataNode[] mPathDataNodes;

    public static PathParser_Delegate getDelegate(long nativePtr) {
        return sManager.getDelegate(nativePtr);
    }

    private PathParser_Delegate(@NonNull PathDataNode[] nodes) {
        mPathDataNodes = nodes;
    }

    public PathDataNode[] getPathDataNodes() {
        return mPathDataNodes;
    }

    @LayoutlibDelegate
    /*package*/ static void nParseStringForPath(long pathPtr, @NonNull String pathString, int
            stringLength) {
        Path_Delegate path_delegate = Path_Delegate.getDelegate(pathPtr);
        if (path_delegate == null) {
            return;
        }
        assert pathString.length() == stringLength;
        PathDataNode.nodesToPath(createNodesFromPathData(pathString), path_delegate);
    }

    @LayoutlibDelegate
    /*package*/ static void nCreatePathFromPathData(long outPathPtr, long pathData) {
        Path_Delegate path_delegate = Path_Delegate.getDelegate(outPathPtr);
        PathParser_Delegate source = sManager.getDelegate(outPathPtr);
        if (source == null || path_delegate == null) {
            return;
        }
        PathDataNode.nodesToPath(source.mPathDataNodes, path_delegate);
    }

    @LayoutlibDelegate
    /*package*/ static long nCreateEmptyPathData() {
        PathParser_Delegate newDelegate = new PathParser_Delegate(new PathDataNode[0]);
        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static long nCreatePathData(long nativePtr) {
        PathParser_Delegate source = sManager.getDelegate(nativePtr);
        if (source == null) {
            return 0;
        }
        PathParser_Delegate dest = new PathParser_Delegate(deepCopyNodes(source.mPathDataNodes));
        return sManager.addNewDelegate(dest);
    }

    @LayoutlibDelegate
    /*package*/ static long nCreatePathDataFromString(@NonNull String pathString,
            int stringLength) {
        assert pathString.length() == stringLength : "Inconsistent path string length.";
        PathDataNode[] nodes = createNodesFromPathData(pathString);
        PathParser_Delegate delegate = new PathParser_Delegate(nodes);
        return sManager.addNewDelegate(delegate);

    }

    @LayoutlibDelegate
    /*package*/ static boolean nInterpolatePathData(long outDataPtr, long fromDataPtr,
            long toDataPtr, float fraction) {
        PathParser_Delegate out = sManager.getDelegate(outDataPtr);
        PathParser_Delegate from = sManager.getDelegate(fromDataPtr);
        PathParser_Delegate to = sManager.getDelegate(toDataPtr);
        if (out == null || from == null || to == null) {
            return false;
        }
        int length = from.mPathDataNodes.length;
        if (length != to.mPathDataNodes.length) {
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Cannot interpolate path data with different lengths (from " + length + " to " +
                            to.mPathDataNodes.length + ").", null);
            return false;
        }
        if (out.mPathDataNodes.length != length) {
            out.mPathDataNodes = new PathDataNode[length];
        }
        for (int i = 0; i < length; i++) {
            if (out.mPathDataNodes[i] == null) {
                out.mPathDataNodes[i] = new PathDataNode(from.mPathDataNodes[i]);
            }
            out.mPathDataNodes[i].interpolatePathDataNode(from.mPathDataNodes[i],
                        to.mPathDataNodes[i], fraction);
        }
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static void nFinalize(long nativePtr) {
        sManager.removeJavaReferenceFor(nativePtr);
    }

    @LayoutlibDelegate
    /*package*/ static boolean nCanMorph(long fromDataPtr, long toDataPtr) {
        PathParser_Delegate fromPath = PathParser_Delegate.getDelegate(fromDataPtr);
        PathParser_Delegate toPath = PathParser_Delegate.getDelegate(toDataPtr);
        if (fromPath == null || toPath == null || fromPath.getPathDataNodes() == null || toPath
                .getPathDataNodes() == null) {
            return true;
        }
        return PathParser_Delegate.canMorph(fromPath.getPathDataNodes(), toPath.getPathDataNodes());
    }

    @LayoutlibDelegate
    /*package*/ static void nSetPathData(long outDataPtr, long fromDataPtr) {
        PathParser_Delegate out = sManager.getDelegate(outDataPtr);
        PathParser_Delegate from = sManager.getDelegate(fromDataPtr);
        if (from == null || out == null) {
            return;
        }
        out.mPathDataNodes = deepCopyNodes(from.mPathDataNodes);
    }

    /**
     * @param pathData The string representing a path, the same as "d" string in svg file.
     *
     * @return an array of the PathDataNode.
     */
    @NonNull
    public static PathDataNode[] createNodesFromPathData(@NonNull String pathData) {
        int start = 0;
        int end = 1;

        ArrayList<PathDataNode> list = new ArrayList<PathDataNode>();
        while (end < pathData.length()) {
            end = nextStart(pathData, end);
            String s = pathData.substring(start, end).trim();
            if (s.length() > 0) {
                float[] val = getFloats(s);
                addNode(list, s.charAt(0), val);
            }

            start = end;
            end++;
        }
        if ((end - start) == 1 && start < pathData.length()) {
            addNode(list, pathData.charAt(start), new float[0]);
        }
        return list.toArray(new PathDataNode[list.size()]);
    }

    /**
     * @param source The array of PathDataNode to be duplicated.
     *
     * @return a deep copy of the <code>source</code>.
     */
    @NonNull
    public static PathDataNode[] deepCopyNodes(@NonNull PathDataNode[] source) {
        PathDataNode[] copy = new PathDataNode[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = new PathDataNode(source[i]);
        }
        return copy;
    }

    /**
     * @param nodesFrom The source path represented in an array of PathDataNode
     * @param nodesTo The target path represented in an array of PathDataNode
     * @return whether the <code>nodesFrom</code> can morph into <code>nodesTo</code>
     */
    public static boolean canMorph(PathDataNode[] nodesFrom, PathDataNode[] nodesTo) {
        if (nodesFrom == null || nodesTo == null) {
            return false;
        }

        if (nodesFrom.length != nodesTo.length) {
            return false;
        }

        for (int i = 0; i < nodesFrom.length; i ++) {
            if (nodesFrom[i].mType != nodesTo[i].mType
                    || nodesFrom[i].mParams.length != nodesTo[i].mParams.length) {
                return false;
            }
        }
        return true;
    }

    /**
     * Update the target's data to match the source.
     * Before calling this, make sure canMorph(target, source) is true.
     *
     * @param target The target path represented in an array of PathDataNode
     * @param source The source path represented in an array of PathDataNode
     */
    public static void updateNodes(PathDataNode[] target, PathDataNode[] source) {
        for (int i = 0; i < source.length; i ++) {
            target[i].mType = source[i].mType;
            for (int j = 0; j < source[i].mParams.length; j ++) {
                target[i].mParams[j] = source[i].mParams[j];
            }
        }
    }

    private static int nextStart(@NonNull String s, int end) {
        char c;

        while (end < s.length()) {
            c = s.charAt(end);
            // Note that 'e' or 'E' are not valid path commands, but could be
            // used for floating point numbers' scientific notation.
            // Therefore, when searching for next command, we should ignore 'e'
            // and 'E'.
            if ((((c - 'A') * (c - 'Z') <= 0) || ((c - 'a') * (c - 'z') <= 0))
                    && c != 'e' && c != 'E') {
                return end;
            }
            end++;
        }
        return end;
    }

    /**
     * Calculate the position of the next comma or space or negative sign
     *
     * @param s the string to search
     * @param start the position to start searching
     * @param result the result of the extraction, including the position of the the starting
     * position of next number, whether it is ending with a '-'.
     */
    private static void extract(@NonNull String s, int start, @NonNull ExtractFloatResult result) {
        // Now looking for ' ', ',', '.' or '-' from the start.
        int currentIndex = start;
        boolean foundSeparator = false;
        result.mEndWithNegOrDot = false;
        boolean secondDot = false;
        boolean isExponential = false;
        for (; currentIndex < s.length(); currentIndex++) {
            boolean isPrevExponential = isExponential;
            isExponential = false;
            char currentChar = s.charAt(currentIndex);
            switch (currentChar) {
                case ' ':
                case ',':
                    foundSeparator = true;
                    break;
                case '-':
                    // The negative sign following a 'e' or 'E' is not a separator.
                    if (currentIndex != start && !isPrevExponential) {
                        foundSeparator = true;
                        result.mEndWithNegOrDot = true;
                    }
                    break;
                case '.':
                    if (!secondDot) {
                        secondDot = true;
                    } else {
                        // This is the second dot, and it is considered as a separator.
                        foundSeparator = true;
                        result.mEndWithNegOrDot = true;
                    }
                    break;
                case 'e':
                case 'E':
                    isExponential = true;
                    break;
            }
            if (foundSeparator) {
                break;
            }
        }
        // When there is nothing found, then we put the end position to the end
        // of the string.
        result.mEndPosition = currentIndex;
    }

    /**
     * Parse the floats in the string. This is an optimized version of
     * parseFloat(s.split(",|\\s"));
     *
     * @param s the string containing a command and list of floats
     *
     * @return array of floats
     */
    @NonNull
    private static float[] getFloats(@NonNull String s) {
        if (s.charAt(0) == 'z' || s.charAt(0) == 'Z') {
            return new float[0];
        }
        try {
            float[] results = new float[s.length()];
            int count = 0;
            int startPosition = 1;
            int endPosition;

            ExtractFloatResult result = new ExtractFloatResult();
            int totalLength = s.length();

            // The startPosition should always be the first character of the
            // current number, and endPosition is the character after the current
            // number.
            while (startPosition < totalLength) {
                extract(s, startPosition, result);
                endPosition = result.mEndPosition;

                if (startPosition < endPosition) {
                    results[count++] = Float.parseFloat(
                            s.substring(startPosition, endPosition));
                }

                if (result.mEndWithNegOrDot) {
                    // Keep the '-' or '.' sign with next number.
                    startPosition = endPosition;
                } else {
                    startPosition = endPosition + 1;
                }
            }
            return Arrays.copyOf(results, count);
        } catch (NumberFormatException e) {
            throw new RuntimeException("error in parsing \"" + s + "\"", e);
        }
    }


    private static void addNode(@NonNull ArrayList<PathDataNode> list, char cmd,
            @NonNull float[] val) {
        list.add(new PathDataNode(cmd, val));
    }

    private static class ExtractFloatResult {
        // We need to return the position of the next separator and whether the
        // next float starts with a '-' or a '.'.
        private int mEndPosition;
        private boolean mEndWithNegOrDot;
    }

    /**
     * Each PathDataNode represents one command in the "d" attribute of the svg file. An array of
     * PathDataNode can represent the whole "d" attribute.
     */
    public static class PathDataNode {
        private char mType;
        @NonNull
        private float[] mParams;

        private PathDataNode(char type, @NonNull float[] params) {
            mType = type;
            mParams = params;
        }

        public char getType() {
            return mType;
        }

        @NonNull
        public float[] getParams() {
            return mParams;
        }

        private PathDataNode(@NonNull PathDataNode n) {
            mType = n.mType;
            mParams = Arrays.copyOf(n.mParams, n.mParams.length);
        }

        /**
         * Convert an array of PathDataNode to Path. Reset the passed path as needed before
         * calling this method.
         *
         * @param node The source array of PathDataNode.
         * @param path The target Path object.
         */
        public static void nodesToPath(@NonNull PathDataNode[] node, @NonNull Path_Delegate path) {
            float[] current = new float[6];
            char previousCommand = 'm';
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < node.length; i++) {
                addCommand(path, current, previousCommand, node[i].mType, node[i].mParams);
                previousCommand = node[i].mType;
            }
        }

        /**
         * The current PathDataNode will be interpolated between the <code>nodeFrom</code> and
         * <code>nodeTo</code> according to the <code>fraction</code>.
         *
         * @param nodeFrom The start value as a PathDataNode.
         * @param nodeTo The end value as a PathDataNode
         * @param fraction The fraction to interpolate.
         */
        private void interpolatePathDataNode(@NonNull PathDataNode nodeFrom,
                @NonNull PathDataNode nodeTo, float fraction) {
            for (int i = 0; i < nodeFrom.mParams.length; i++) {
                mParams[i] = nodeFrom.mParams[i] * (1 - fraction)
                        + nodeTo.mParams[i] * fraction;
            }
        }

        @SuppressWarnings("PointlessArithmeticExpression")
        private static void addCommand(@NonNull Path_Delegate path, float[] current,
                char previousCmd, char cmd, @NonNull float[] val) {

            int incr = 2;
            float currentX = current[0];
            float currentY = current[1];
            float ctrlPointX = current[2];
            float ctrlPointY = current[3];
            float currentSegmentStartX = current[4];
            float currentSegmentStartY = current[5];
            float reflectiveCtrlPointX;
            float reflectiveCtrlPointY;

            switch (cmd) {
                case 'z':
                case 'Z':
                    path.close();
                    // Path is closed here, but we need to move the pen to the
                    // closed position. So we cache the segment's starting position,
                    // and restore it here.
                    currentX = currentSegmentStartX;
                    currentY = currentSegmentStartY;
                    ctrlPointX = currentSegmentStartX;
                    ctrlPointY = currentSegmentStartY;
                    path.moveTo(currentX, currentY);
                    break;
                case 'm':
                case 'M':
                case 'l':
                case 'L':
                case 't':
                case 'T':
                    incr = 2;
                    break;
                case 'h':
                case 'H':
                case 'v':
                case 'V':
                    incr = 1;
                    break;
                case 'c':
                case 'C':
                    incr = 6;
                    break;
                case 's':
                case 'S':
                case 'q':
                case 'Q':
                    incr = 4;
                    break;
                case 'a':
                case 'A':
                    incr = 7;
                    break;
            }

            for (int k = 0; k < val.length; k += incr) {
                switch (cmd) {
                    case 'm': // moveto - Start a new sub-path (relative)
                        currentX += val[k + 0];
                        currentY += val[k + 1];

                        if (k > 0) {
                            // According to the spec, if a moveto is followed by multiple
                            // pairs of coordinates, the subsequent pairs are treated as
                            // implicit lineto commands.
                            path.rLineTo(val[k + 0], val[k + 1]);
                        } else {
                            path.rMoveTo(val[k + 0], val[k + 1]);
                            currentSegmentStartX = currentX;
                            currentSegmentStartY = currentY;
                        }
                        break;
                    case 'M': // moveto - Start a new sub-path
                        currentX = val[k + 0];
                        currentY = val[k + 1];

                        if (k > 0) {
                            // According to the spec, if a moveto is followed by multiple
                            // pairs of coordinates, the subsequent pairs are treated as
                            // implicit lineto commands.
                            path.lineTo(val[k + 0], val[k + 1]);
                        } else {
                            path.moveTo(val[k + 0], val[k + 1]);
                            currentSegmentStartX = currentX;
                            currentSegmentStartY = currentY;
                        }
                        break;
                    case 'l': // lineto - Draw a line from the current point (relative)
                        path.rLineTo(val[k + 0], val[k + 1]);
                        currentX += val[k + 0];
                        currentY += val[k + 1];
                        break;
                    case 'L': // lineto - Draw a line from the current point
                        path.lineTo(val[k + 0], val[k + 1]);
                        currentX = val[k + 0];
                        currentY = val[k + 1];
                        break;
                    case 'h': // horizontal lineto - Draws a horizontal line (relative)
                        path.rLineTo(val[k + 0], 0);
                        currentX += val[k + 0];
                        break;
                    case 'H': // horizontal lineto - Draws a horizontal line
                        path.lineTo(val[k + 0], currentY);
                        currentX = val[k + 0];
                        break;
                    case 'v': // vertical lineto - Draws a vertical line from the current point (r)
                        path.rLineTo(0, val[k + 0]);
                        currentY += val[k + 0];
                        break;
                    case 'V': // vertical lineto - Draws a vertical line from the current point
                        path.lineTo(currentX, val[k + 0]);
                        currentY = val[k + 0];
                        break;
                    case 'c': // curveto - Draws a cubic Bézier curve (relative)
                        path.rCubicTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3],
                                val[k + 4], val[k + 5]);

                        ctrlPointX = currentX + val[k + 2];
                        ctrlPointY = currentY + val[k + 3];
                        currentX += val[k + 4];
                        currentY += val[k + 5];

                        break;
                    case 'C': // curveto - Draws a cubic Bézier curve
                        path.cubicTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3],
                                val[k + 4], val[k + 5]);
                        currentX = val[k + 4];
                        currentY = val[k + 5];
                        ctrlPointX = val[k + 2];
                        ctrlPointY = val[k + 3];
                        break;
                    case 's': // smooth curveto - Draws a cubic Bézier curve (reflective cp)
                        reflectiveCtrlPointX = 0;
                        reflectiveCtrlPointY = 0;
                        if (previousCmd == 'c' || previousCmd == 's'
                                || previousCmd == 'C' || previousCmd == 'S') {
                            reflectiveCtrlPointX = currentX - ctrlPointX;
                            reflectiveCtrlPointY = currentY - ctrlPointY;
                        }
                        path.rCubicTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1],
                                val[k + 2], val[k + 3]);

                        ctrlPointX = currentX + val[k + 0];
                        ctrlPointY = currentY + val[k + 1];
                        currentX += val[k + 2];
                        currentY += val[k + 3];
                        break;
                    case 'S': // shorthand/smooth curveto Draws a cubic Bézier curve(reflective cp)
                        reflectiveCtrlPointX = currentX;
                        reflectiveCtrlPointY = currentY;
                        if (previousCmd == 'c' || previousCmd == 's'
                                || previousCmd == 'C' || previousCmd == 'S') {
                            reflectiveCtrlPointX = 2 * currentX - ctrlPointX;
                            reflectiveCtrlPointY = 2 * currentY - ctrlPointY;
                        }
                        path.cubicTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        currentX = val[k + 2];
                        currentY = val[k + 3];
                        break;
                    case 'q': // Draws a quadratic Bézier (relative)
                        path.rQuadTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        ctrlPointX = currentX + val[k + 0];
                        ctrlPointY = currentY + val[k + 1];
                        currentX += val[k + 2];
                        currentY += val[k + 3];
                        break;
                    case 'Q': // Draws a quadratic Bézier
                        path.quadTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        currentX = val[k + 2];
                        currentY = val[k + 3];
                        break;
                    case 't': // Draws a quadratic Bézier curve(reflective control point)(relative)
                        reflectiveCtrlPointX = 0;
                        reflectiveCtrlPointY = 0;
                        if (previousCmd == 'q' || previousCmd == 't'
                                || previousCmd == 'Q' || previousCmd == 'T') {
                            reflectiveCtrlPointX = currentX - ctrlPointX;
                            reflectiveCtrlPointY = currentY - ctrlPointY;
                        }
                        path.rQuadTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1]);
                        ctrlPointX = currentX + reflectiveCtrlPointX;
                        ctrlPointY = currentY + reflectiveCtrlPointY;
                        currentX += val[k + 0];
                        currentY += val[k + 1];
                        break;
                    case 'T': // Draws a quadratic Bézier curve (reflective control point)
                        reflectiveCtrlPointX = currentX;
                        reflectiveCtrlPointY = currentY;
                        if (previousCmd == 'q' || previousCmd == 't'
                                || previousCmd == 'Q' || previousCmd == 'T') {
                            reflectiveCtrlPointX = 2 * currentX - ctrlPointX;
                            reflectiveCtrlPointY = 2 * currentY - ctrlPointY;
                        }
                        path.quadTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1]);
                        ctrlPointX = reflectiveCtrlPointX;
                        ctrlPointY = reflectiveCtrlPointY;
                        currentX = val[k + 0];
                        currentY = val[k + 1];
                        break;
                    case 'a': // Draws an elliptical arc
                        // (rx ry x-axis-rotation large-arc-flag sweep-flag x y)
                        drawArc(path,
                                currentX,
                                currentY,
                                val[k + 5] + currentX,
                                val[k + 6] + currentY,
                                val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3] != 0,
                                val[k + 4] != 0);
                        currentX += val[k + 5];
                        currentY += val[k + 6];
                        ctrlPointX = currentX;
                        ctrlPointY = currentY;
                        break;
                    case 'A': // Draws an elliptical arc
                        drawArc(path,
                                currentX,
                                currentY,
                                val[k + 5],
                                val[k + 6],
                                val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3] != 0,
                                val[k + 4] != 0);
                        currentX = val[k + 5];
                        currentY = val[k + 6];
                        ctrlPointX = currentX;
                        ctrlPointY = currentY;
                        break;
                }
                previousCmd = cmd;
            }
            current[0] = currentX;
            current[1] = currentY;
            current[2] = ctrlPointX;
            current[3] = ctrlPointY;
            current[4] = currentSegmentStartX;
            current[5] = currentSegmentStartY;
        }

        private static void drawArc(@NonNull Path_Delegate p, float x0, float y0, float x1,
                float y1, float a, float b, float theta, boolean isMoreThanHalf,
                boolean isPositiveArc) {

            LOGGER.log(Level.FINE, "(" + x0 + "," + y0 + ")-(" + x1 + "," + y1
                    + ") {" + a + " " + b + "}");
        /* Convert rotation angle from degrees to radians */
            double thetaD = theta * Math.PI / 180.0f;
        /* Pre-compute rotation matrix entries */
            double cosTheta = Math.cos(thetaD);
            double sinTheta = Math.sin(thetaD);
        /* Transform (x0, y0) and (x1, y1) into unit space */
        /* using (inverse) rotation, followed by (inverse) scale */
            double x0p = (x0 * cosTheta + y0 * sinTheta) / a;
            double y0p = (-x0 * sinTheta + y0 * cosTheta) / b;
            double x1p = (x1 * cosTheta + y1 * sinTheta) / a;
            double y1p = (-x1 * sinTheta + y1 * cosTheta) / b;
            LOGGER.log(Level.FINE, "unit space (" + x0p + "," + y0p + ")-(" + x1p
                    + "," + y1p + ")");
        /* Compute differences and averages */
            double dx = x0p - x1p;
            double dy = y0p - y1p;
            double xm = (x0p + x1p) / 2;
            double ym = (y0p + y1p) / 2;
        /* Solve for intersecting unit circles */
            double dsq = dx * dx + dy * dy;
            if (dsq == 0.0) {
                LOGGER.log(Level.FINE, " Points are coincident");
                return; /* Points are coincident */
            }
            double disc = 1.0 / dsq - 1.0 / 4.0;
            if (disc < 0.0) {
                LOGGER.log(Level.FINE, "Points are too far apart " + dsq);
                float adjust = (float) (Math.sqrt(dsq) / 1.99999);
                drawArc(p, x0, y0, x1, y1, a * adjust, b * adjust, theta,
                        isMoreThanHalf, isPositiveArc);
                return; /* Points are too far apart */
            }
            double s = Math.sqrt(disc);
            double sdx = s * dx;
            double sdy = s * dy;
            double cx;
            double cy;
            if (isMoreThanHalf == isPositiveArc) {
                cx = xm - sdy;
                cy = ym + sdx;
            } else {
                cx = xm + sdy;
                cy = ym - sdx;
            }

            double eta0 = Math.atan2((y0p - cy), (x0p - cx));
            LOGGER.log(Level.FINE, "eta0 = Math.atan2( " + (y0p - cy) + " , "
                    + (x0p - cx) + ") = " + Math.toDegrees(eta0));

            double eta1 = Math.atan2((y1p - cy), (x1p - cx));
            LOGGER.log(Level.FINE, "eta1 = Math.atan2( " + (y1p - cy) + " , "
                    + (x1p - cx) + ") = " + Math.toDegrees(eta1));
            double sweep = (eta1 - eta0);
            if (isPositiveArc != (sweep >= 0)) {
                if (sweep > 0) {
                    sweep -= 2 * Math.PI;
                } else {
                    sweep += 2 * Math.PI;
                }
            }

            cx *= a;
            cy *= b;
            double tcx = cx;
            cx = cx * cosTheta - cy * sinTheta;
            cy = tcx * sinTheta + cy * cosTheta;
            LOGGER.log(
                    Level.FINE,
                    "cx, cy, a, b, x0, y0, thetaD, eta0, sweep = " + cx + " , "
                            + cy + " , " + a + " , " + b + " , " + x0 + " , " + y0
                            + " , " + Math.toDegrees(thetaD) + " , "
                            + Math.toDegrees(eta0) + " , " + Math.toDegrees(sweep));

            arcToBezier(p, cx, cy, a, b, x0, y0, thetaD, eta0, sweep);
        }

        /**
         * Converts an arc to cubic Bezier segments and records them in p.
         *
         * @param p The target for the cubic Bezier segments
         * @param cx The x coordinate center of the ellipse
         * @param cy The y coordinate center of the ellipse
         * @param a The radius of the ellipse in the horizontal direction
         * @param b The radius of the ellipse in the vertical direction
         * @param e1x E(eta1) x coordinate of the starting point of the arc
         * @param e1y E(eta2) y coordinate of the starting point of the arc
         * @param theta The angle that the ellipse bounding rectangle makes with the horizontal
         * plane
         * @param start The start angle of the arc on the ellipse
         * @param sweep The angle (positive or negative) of the sweep of the arc on the ellipse
         */
        private static void arcToBezier(@NonNull Path_Delegate p, double cx, double cy, double a,
                double b, double e1x, double e1y, double theta, double start,
                double sweep) {
            // Taken from equations at:
            // http://spaceroots.org/documents/ellipse/node8.html
            // and http://www.spaceroots.org/documents/ellipse/node22.html
            // Maximum of 45 degrees per cubic Bezier segment
            int numSegments = (int) Math.ceil(Math.abs(sweep * 4 / Math.PI));


            double eta1 = start;
            double cosTheta = Math.cos(theta);
            double sinTheta = Math.sin(theta);
            double cosEta1 = Math.cos(eta1);
            double sinEta1 = Math.sin(eta1);
            double ep1x = (-a * cosTheta * sinEta1) - (b * sinTheta * cosEta1);
            double ep1y = (-a * sinTheta * sinEta1) + (b * cosTheta * cosEta1);

            double anglePerSegment = sweep / numSegments;
            for (int i = 0; i < numSegments; i++) {
                double eta2 = eta1 + anglePerSegment;
                double sinEta2 = Math.sin(eta2);
                double cosEta2 = Math.cos(eta2);
                double e2x = cx + (a * cosTheta * cosEta2)
                        - (b * sinTheta * sinEta2);
                double e2y = cy + (a * sinTheta * cosEta2)
                        + (b * cosTheta * sinEta2);
                double ep2x = -a * cosTheta * sinEta2 - b * sinTheta * cosEta2;
                double ep2y = -a * sinTheta * sinEta2 + b * cosTheta * cosEta2;
                double tanDiff2 = Math.tan((eta2 - eta1) / 2);
                double alpha = Math.sin(eta2 - eta1)
                        * (Math.sqrt(4 + (3 * tanDiff2 * tanDiff2)) - 1) / 3;
                double q1x = e1x + alpha * ep1x;
                double q1y = e1y + alpha * ep1y;
                double q2x = e2x - alpha * ep2x;
                double q2y = e2y - alpha * ep2y;

                p.cubicTo((float) q1x,
                        (float) q1y,
                        (float) q2x,
                        (float) q2y,
                        (float) e2x,
                        (float) e2y);
                eta1 = eta2;
                e1x = e2x;
                e1y = e2y;
                ep1x = ep2x;
                ep1y = ep2y;
            }
        }
    }
}
