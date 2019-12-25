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

package com.android.systemui.assist.ui;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import androidx.core.math.MathUtils;

/**
 * PerimeterPathGuide establishes a coordinate system for drawing paths along the perimeter of the
 * screen. All positions around the perimeter have a coordinate [0, 1). The origin is the bottom
 * left corner of the screen, to the right of the curved corner, if any. Coordinates increase
 * counter-clockwise around the screen.
 *
 * Non-square screens require PerimeterPathGuide to be notified when the rotation changes, such that
 * it can recompute the edge lengths for the coordinate system.
 */
public class PerimeterPathGuide {

    private static final String TAG = "PerimeterPathGuide";

    /**
     * For convenience, labels sections of the device perimeter.
     *
     * Must be listed in CCW order.
     */
    public enum Region {
        BOTTOM,
        BOTTOM_RIGHT,
        RIGHT,
        TOP_RIGHT,
        TOP,
        TOP_LEFT,
        LEFT,
        BOTTOM_LEFT
    }

    private final int mDeviceWidthPx;
    private final int mDeviceHeightPx;
    private final int mTopCornerRadiusPx;
    private final int mBottomCornerRadiusPx;

    private class RegionAttributes {
        public float absoluteLength;
        public float normalizedLength;
        public float endCoordinate;
        public Path path;
    }

    // Allocate a Path and PathMeasure for use by intermediate operations that would otherwise have
    // to allocate. reset() must be called before using this path, this ensures state from previous
    // operations is cleared.
    private final Path mScratchPath = new Path();
    private final CornerPathRenderer mCornerPathRenderer;
    private final PathMeasure mScratchPathMeasure = new PathMeasure(mScratchPath, false);
    private RegionAttributes[] mRegions;
    private final int mEdgeInset;
    private int mRotation = ROTATION_0;

    public PerimeterPathGuide(Context context, CornerPathRenderer cornerPathRenderer,
            int edgeInset, int screenWidth, int screenHeight) {
        mCornerPathRenderer = cornerPathRenderer;
        mDeviceWidthPx = screenWidth;
        mDeviceHeightPx = screenHeight;
        mTopCornerRadiusPx = DisplayUtils.getCornerRadiusTop(context);
        mBottomCornerRadiusPx = DisplayUtils.getCornerRadiusBottom(context);
        mEdgeInset = edgeInset;

        mRegions = new RegionAttributes[8];
        for (int i = 0; i < mRegions.length; i++) {
            mRegions[i] = new RegionAttributes();
        }
        computeRegions();
    }

    /**
     * Sets the rotation.
     *
     * @param rotation one of Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180,
     *                 Surface.ROTATION_270
     */
    public void setRotation(int rotation) {
        if (rotation != mRotation) {
            switch (rotation) {
                case ROTATION_0:
                case ROTATION_90:
                case ROTATION_180:
                case ROTATION_270:
                    mRotation = rotation;
                    computeRegions();
                    break;
                default:
                    Log.e(TAG, "Invalid rotation provided: " + rotation);
            }
        }
    }

    /**
     * Sets path to the section of the perimeter between startCoord and endCoord (measured
     * counter-clockwise from the bottom left).
     */
    public void strokeSegment(Path path, float startCoord, float endCoord) {
        path.reset();

        startCoord = ((startCoord % 1) + 1) % 1;  // Wrap to the range [0, 1).
        endCoord = ((endCoord % 1) + 1) % 1;  // Wrap to the range [0, 1).
        boolean outOfOrder = startCoord > endCoord;

        if (outOfOrder) {
            strokeSegmentInternal(path, startCoord, 1f);
            startCoord = 0;
        }
        strokeSegmentInternal(path, startCoord, endCoord);
    }

    /**
     * Returns the device perimeter in pixels.
     */
    public float getPerimeterPx() {
        float total = 0;
        for (RegionAttributes region : mRegions) {
            total += region.absoluteLength;
        }
        return total;
    }

    /**
     * Returns the bottom corner radius in pixels.
     */
    public float getBottomCornerRadiusPx() {
        return mBottomCornerRadiusPx;
    }

    /**
     * Given a region and a progress value [0,1] indicating the counter-clockwise progress within
     * that region, compute the global [0,1) coordinate.
     */
    public float getCoord(Region region, float progress) {
        RegionAttributes regionAttributes = mRegions[region.ordinal()];
        progress = MathUtils.clamp(progress, 0, 1);
        return regionAttributes.endCoordinate - (1 - progress) * regionAttributes.normalizedLength;
    }

    /**
     * Returns the center of the provided region, relative to the entire perimeter.
     */
    public float getRegionCenter(Region region) {
        return getCoord(region, 0.5f);
    }

    /**
     * Returns the width of the provided region, in units relative to the entire perimeter.
     */
    public float getRegionWidth(Region region) {
        return mRegions[region.ordinal()].normalizedLength;
    }

    /**
     * Points are expressed in terms of their relative position on the perimeter of the display,
     * moving counter-clockwise. This method converts a point to clockwise, assisting use cases
     * such as animating to a point clockwise instead of counter-clockwise.
     *
     * @param point A point in the range from 0 to 1.
     * @return A point in the range of -1 to 0 that represents the same location as {@code point}.
     */
    public static float makeClockwise(float point) {
        return point - 1;
    }

    private int getPhysicalCornerRadius(CircularCornerPathRenderer.Corner corner) {
        if (corner == CircularCornerPathRenderer.Corner.BOTTOM_LEFT
                || corner == CircularCornerPathRenderer.Corner.BOTTOM_RIGHT) {
            return mBottomCornerRadiusPx;
        }
        return mTopCornerRadiusPx;
    }

    // Populate mRegions based upon the current rotation value.
    private void computeRegions() {
        int screenWidth = mDeviceWidthPx;
        int screenHeight = mDeviceHeightPx;

        int rotateMatrix = 0;

        switch (mRotation) {
            case ROTATION_90:
                rotateMatrix = -90;
                break;
            case ROTATION_180:
                rotateMatrix = -180;
                break;
            case Surface.ROTATION_270:
                rotateMatrix = -270;
                break;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotateMatrix, mDeviceWidthPx / 2, mDeviceHeightPx / 2);

        if (mRotation == ROTATION_90 || mRotation == Surface.ROTATION_270) {
            screenHeight = mDeviceWidthPx;
            screenWidth = mDeviceHeightPx;
            matrix.postTranslate((mDeviceHeightPx
                    - mDeviceWidthPx) / 2, (mDeviceWidthPx - mDeviceHeightPx) / 2);
        }

        CornerPathRenderer.Corner screenBottomLeft = getRotatedCorner(
                CornerPathRenderer.Corner.BOTTOM_LEFT);
        CornerPathRenderer.Corner screenBottomRight = getRotatedCorner(
                CornerPathRenderer.Corner.BOTTOM_RIGHT);
        CornerPathRenderer.Corner screenTopLeft = getRotatedCorner(
                CornerPathRenderer.Corner.TOP_LEFT);
        CornerPathRenderer.Corner screenTopRight = getRotatedCorner(
                CornerPathRenderer.Corner.TOP_RIGHT);

        mRegions[Region.BOTTOM_LEFT.ordinal()].path =
                mCornerPathRenderer.getInsetPath(screenBottomLeft, mEdgeInset);
        mRegions[Region.BOTTOM_RIGHT.ordinal()].path =
                mCornerPathRenderer.getInsetPath(screenBottomRight, mEdgeInset);
        mRegions[Region.TOP_RIGHT.ordinal()].path =
                mCornerPathRenderer.getInsetPath(screenTopRight, mEdgeInset);
        mRegions[Region.TOP_LEFT.ordinal()].path =
                mCornerPathRenderer.getInsetPath(screenTopLeft, mEdgeInset);

        mRegions[Region.BOTTOM_LEFT.ordinal()].path.transform(matrix);
        mRegions[Region.BOTTOM_RIGHT.ordinal()].path.transform(matrix);
        mRegions[Region.TOP_RIGHT.ordinal()].path.transform(matrix);
        mRegions[Region.TOP_LEFT.ordinal()].path.transform(matrix);


        Path bottomPath = new Path();
        bottomPath.moveTo(getPhysicalCornerRadius(screenBottomLeft), screenHeight - mEdgeInset);
        bottomPath.lineTo(screenWidth - getPhysicalCornerRadius(screenBottomRight),
                screenHeight - mEdgeInset);
        mRegions[Region.BOTTOM.ordinal()].path = bottomPath;

        Path topPath = new Path();
        topPath.moveTo(screenWidth - getPhysicalCornerRadius(screenTopRight), mEdgeInset);
        topPath.lineTo(getPhysicalCornerRadius(screenTopLeft), mEdgeInset);
        mRegions[Region.TOP.ordinal()].path = topPath;

        Path rightPath = new Path();
        rightPath.moveTo(screenWidth - mEdgeInset,
                screenHeight - getPhysicalCornerRadius(screenBottomRight));
        rightPath.lineTo(screenWidth - mEdgeInset, getPhysicalCornerRadius(screenTopRight));
        mRegions[Region.RIGHT.ordinal()].path = rightPath;

        Path leftPath = new Path();
        leftPath.moveTo(mEdgeInset,
                getPhysicalCornerRadius(screenTopLeft));
        leftPath.lineTo(mEdgeInset, screenHeight - getPhysicalCornerRadius(screenBottomLeft));
        mRegions[Region.LEFT.ordinal()].path = leftPath;

        float perimeterLength = 0;
        PathMeasure pathMeasure = new PathMeasure();
        for (int i = 0; i < mRegions.length; i++) {
            pathMeasure.setPath(mRegions[i].path, false);
            mRegions[i].absoluteLength = pathMeasure.getLength();
            perimeterLength += mRegions[i].absoluteLength;
        }

        float accum = 0;
        for (int i = 0; i < mRegions.length; i++) {
            mRegions[i].normalizedLength = mRegions[i].absoluteLength / perimeterLength;
            accum += mRegions[i].absoluteLength;
            mRegions[i].endCoordinate = accum / perimeterLength;
        }
        // Ensure that the last coordinate is 1. Setting it explicitly to avoid floating point
        // error.
        mRegions[mRegions.length - 1].endCoordinate = 1f;
    }

    private CircularCornerPathRenderer.Corner getRotatedCorner(
            CircularCornerPathRenderer.Corner screenCorner) {
        int corner = screenCorner.ordinal();
        switch (mRotation) {
            case ROTATION_90:
                corner += 3;
                break;
            case ROTATION_180:
                corner += 2;
                break;
            case Surface.ROTATION_270:
                corner += 1;
                break;
        }
        return CircularCornerPathRenderer.Corner.values()[corner % 4];
    }

    private void strokeSegmentInternal(Path path, float startCoord, float endCoord) {
        Pair<Region, Float> startPoint = placePoint(startCoord);
        Pair<Region, Float> endPoint = placePoint(endCoord);

        if (startPoint.first.equals(endPoint.first)) {
            strokeRegion(path, startPoint.first, startPoint.second, endPoint.second);
        } else {
            strokeRegion(path, startPoint.first, startPoint.second, 1f);
            boolean hitStart = false;
            for (Region r : Region.values()) {
                if (r.equals(startPoint.first)) {
                    hitStart = true;
                    continue;
                }
                if (hitStart) {
                    if (!r.equals(endPoint.first)) {
                        strokeRegion(path, r, 0f, 1f);
                    } else {
                        strokeRegion(path, r, 0f, endPoint.second);
                        break;
                    }
                }
            }
        }
    }

    private void strokeRegion(Path path, Region r, float relativeStart, float relativeEnd) {
        if (relativeStart == relativeEnd) {
            return;
        }

        mScratchPathMeasure.setPath(mRegions[r.ordinal()].path, false);
        mScratchPathMeasure.getSegment(relativeStart * mScratchPathMeasure.getLength(),
                relativeEnd * mScratchPathMeasure.getLength(), path, true);
    }

    /**
     * Return the Region where the point is located, and its relative position within that region
     * (from 0 to 1).
     * Note that we move counterclockwise around the perimeter; for example, a relative position of
     * 0 in
     * the BOTTOM region is on the left side of the screen, but in the TOP region it’s on the
     * right.
     */
    private Pair<Region, Float> placePoint(float coord) {
        if (0 > coord || coord > 1) {
            coord = ((coord % 1) + 1)
                    % 1;  // Wrap to the range [0, 1). Inputs of exactly 1 are preserved.
        }

        Region r = getRegionForPoint(coord);
        if (r.equals(Region.BOTTOM)) {
            return Pair.create(r, coord / mRegions[r.ordinal()].normalizedLength);
        } else {
            float coordOffsetInRegion = coord - mRegions[r.ordinal() - 1].endCoordinate;
            float coordRelativeToRegion =
                    coordOffsetInRegion / mRegions[r.ordinal()].normalizedLength;
            return Pair.create(r, coordRelativeToRegion);
        }
    }

    private Region getRegionForPoint(float coord) {
        // If coord is outside of [0,1], wrap to [0,1).
        if (coord < 0 || coord > 1) {
            coord = ((coord % 1) + 1) % 1;
        }

        for (Region region : Region.values()) {
            if (coord <= mRegions[region.ordinal()].endCoordinate) {
                return region;
            }
        }

        // Should never happen.
        Log.e(TAG, "Fell out of getRegionForPoint");
        return Region.BOTTOM;
    }
}
