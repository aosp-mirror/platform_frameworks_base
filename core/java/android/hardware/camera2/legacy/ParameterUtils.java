/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.camera2.utils.ListUtils;
import android.hardware.camera2.utils.ParamsUtils;
import android.hardware.camera2.utils.SizeAreaComparator;
import android.util.Size;
import android.util.SizeF;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.internal.util.Preconditions.*;

/**
 * Various utilities for dealing with camera API1 parameters.
 */
public class ParameterUtils {
    private static final String TAG = "ParameterUtils";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    /** getZoomRatios stores zoom ratios in 1/100 increments, e.x. a zoom of 3.2 is 320 */
    private static final int ZOOM_RATIO_MULTIPLIER = 100;

    /**
     * Convert a camera API1 size into a util size
     */
    public static Size convertSize(Camera.Size size) {
        checkNotNull(size, "size must not be null");

        return new Size(size.width, size.height);
    }

    /**
     * Convert a camera API1 list of sizes into a util list of sizes
     */
    public static List<Size> convertSizeList(List<Camera.Size> sizeList) {
        checkNotNull(sizeList, "sizeList must not be null");

        List<Size> sizes = new ArrayList<>(sizeList.size());
        for (Camera.Size s : sizeList) {
            sizes.add(new Size(s.width, s.height));
        }
        return sizes;
    }

    /**
     * Returns the largest supported picture size, as compared by its area.
     */
    public static Size getLargestSupportedJpegSizeByArea(Camera.Parameters params) {
        checkNotNull(params, "params must not be null");

        List<Size> supportedJpegSizes = convertSizeList(params.getSupportedPictureSizes());
        return SizeAreaComparator.findLargestByArea(supportedJpegSizes);
    }

    /**
     * Convert a camera area into a human-readable string.
     */
    public static String stringFromArea(Camera.Area area) {
        if (area == null) {
            return null;
        } else {
            StringBuilder sb = new StringBuilder();
            Rect r = area.rect;

            sb.setLength(0);
            sb.append("(["); sb.append(r.left); sb.append(',');
            sb.append(r.top); sb.append("]["); sb.append(r.right);
            sb.append(','); sb.append(r.bottom); sb.append(']');

            sb.append(',');
            sb.append(area.weight);
            sb.append(')');

            return sb.toString();
        }
    }

    /**
     * Calculate the closest zoom index for the user-requested crop region by rounding
     * up to the closest (largest or equal) possible zoom crop.
     *
     * <p>If the requested crop region exceeds the size of the active array, it is
     * shrunk to fit inside of the active array first.</p>
     *
     * <p>Since all api1 camera devices only support a discrete set of zooms, we have
     * to translate the per-pixel-granularity requested crop region into a per-zoom-index
     * granularity.</p>
     *
     * <p>Furthermore, since the zoom index and zoom levels also depends on the field-of-view
     * of the preview, the current preview {@code streamSize} is also used.</p>
     *
     * <p>The calculated crop regions are then written to in-place to {@code reportedCropRegion}
     * and {@code previewCropRegion}, in coordinates relative to the active array.</p>
     *
     * @param params non-{@code null} camera api1 parameters
     * @param activeArray active array dimensions, in sensor space
     * @param streamSize stream size dimensions, in pixels
     * @param cropRegion user-specified crop region, in active array coordinates
     * @param reportedCropRegion (out parameter) what the result for {@code cropRegion} looks like
     * @param previewCropRegion (out parameter) what the visual preview crop is
     * @return
     *          the zoom index inclusively between 0 and {@code Parameters#getMaxZoom},
     *          where 0 means the camera is not zoomed
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public static int getClosestAvailableZoomCrop(
            Camera.Parameters params, Rect activeArray, Size streamSize, Rect cropRegion,
            /*out*/
            Rect reportedCropRegion,
            Rect previewCropRegion) {
        checkNotNull(params, "params must not be null");
        checkNotNull(activeArray, "activeArray must not be null");
        checkNotNull(streamSize, "streamSize must not be null");
        checkNotNull(reportedCropRegion, "reportedCropRegion must not be null");
        checkNotNull(previewCropRegion, "previewCropRegion must not be null");

        Rect actualCrop = new Rect(cropRegion);

        /*
         * Shrink requested crop region to fit inside of the active array size
         */
        if (!actualCrop.intersect(activeArray)) {
            Log.w(TAG, "getClosestAvailableZoomCrop - Crop region out of range; " +
                    "setting to active array size");
            actualCrop.set(activeArray);
        }

        Rect previewCrop = getPreviewCropRectangleUnzoomed(activeArray, streamSize);

        // Make the user-requested crop region the same aspect ratio as the preview stream size
        Rect cropRegionAsPreview =
                shrinkToSameAspectRatioCentered(previewCrop, actualCrop);

        if (VERBOSE) {
            Log.v(TAG, "getClosestAvailableZoomCrop - actualCrop = " + actualCrop);
            Log.v(TAG,
                    "getClosestAvailableZoomCrop - previewCrop = " + previewCrop);
            Log.v(TAG,
                    "getClosestAvailableZoomCrop - cropRegionAsPreview = " + cropRegionAsPreview);
        }

        /*
         * Iterate all available zoom rectangles and find the closest zoom index
         */
        Rect bestReportedCropRegion = null;
        Rect bestPreviewCropRegion = null;
        int bestZoomIndex = -1;

        List<Rect> availableReportedCropRegions =
                getAvailableZoomCropRectangles(params, activeArray);
        List<Rect> availablePreviewCropRegions =
                getAvailablePreviewZoomCropRectangles(params, activeArray, streamSize);

        if (VERBOSE) {
            Log.v(TAG,
                    "getClosestAvailableZoomCrop - availableReportedCropRegions = " +
                            ListUtils.listToString(availableReportedCropRegions));
            Log.v(TAG,
                    "getClosestAvailableZoomCrop - availablePreviewCropRegions = " +
                            ListUtils.listToString(availablePreviewCropRegions));
        }

        if (availableReportedCropRegions.size() != availablePreviewCropRegions.size()) {
            throw new AssertionError("available reported/preview crop region size mismatch");
        }

        for (int i = 0; i < availableReportedCropRegions.size(); ++i) {
            Rect currentPreviewCropRegion = availablePreviewCropRegions.get(i);
            Rect currentReportedCropRegion = availableReportedCropRegions.get(i);

            boolean isBest;
            if (bestZoomIndex == -1) {
                isBest = true;
            } else if (currentPreviewCropRegion.width() >= cropRegionAsPreview.width() &&
                    currentPreviewCropRegion.height() >= cropRegionAsPreview.height()) {
                isBest = true;
            } else {
                isBest = false;
            }

            // Sizes are sorted largest-to-smallest, so once the available crop is too small,
            // we the rest are too small. Furthermore, this is the final best crop,
            // since its the largest crop that still fits the requested crop
            if (isBest) {
                bestPreviewCropRegion = currentPreviewCropRegion;
                bestReportedCropRegion = currentReportedCropRegion;
                bestZoomIndex = i;
            } else {
                break;
            }
        }

        if (bestZoomIndex == -1) {
            // Even in the worst case, we should always at least return 0 here
            throw new AssertionError("Should've found at least one valid zoom index");
        }

        // Write the rectangles in-place
        reportedCropRegion.set(bestReportedCropRegion);
        previewCropRegion.set(bestPreviewCropRegion);

        return bestZoomIndex;
    }

    /**
     * Calculate the effective crop rectangle for this preview viewport;
     * assumes the preview is centered to the sensor and scaled to fit across one of the dimensions
     * without skewing.
     *
     * <p>The preview size must be a subset of the active array size; the resulting
     * rectangle will also be a subset of the active array rectangle.</p>
     *
     * <p>The unzoomed crop rectangle is calculated only.</p>
     *
     * @param activeArray active array dimensions, in sensor space
     * @param previewSize size of the preview buffer render target, in pixels (not in sensor space)
     * @return a rectangle which serves as the preview stream's effective crop region (unzoomed),
     *         in sensor space
     *
     * @throws NullPointerException
     *          if any of the args were {@code null}
     * @throws IllegalArgumentException
     *          if {@code previewSize} is wider or taller than {@code activeArray}
     */
    private static Rect getPreviewCropRectangleUnzoomed(Rect activeArray, Size previewSize) {
        if (previewSize.getWidth() > activeArray.width()) {
            throw new IllegalArgumentException("previewSize must not be wider than activeArray");
        } else if (previewSize.getHeight() > activeArray.height()) {
            throw new IllegalArgumentException("previewSize must not be taller than activeArray");
        }

        float aspectRatioArray = activeArray.width() * 1.0f / activeArray.height();
        float aspectRatioPreview = previewSize.getWidth() * 1.0f / previewSize.getHeight();

        float cropH, cropW;
        if (aspectRatioPreview < aspectRatioArray) {
            // The new width must be smaller than the height, so scale the width by AR
            cropH = activeArray.height();
            cropW = cropH * aspectRatioPreview;
        } else {
            // The new height must be smaller (or equal) than the width, so scale the height by AR
            cropW = activeArray.width();
            cropH = cropW / aspectRatioPreview;
        }

        Matrix translateMatrix = new Matrix();
        RectF cropRect = new RectF(/*left*/0, /*top*/0, cropW, cropH);

        // Now center the crop rectangle so its center is in the center of the active array
        translateMatrix.setTranslate(activeArray.exactCenterX(), activeArray.exactCenterY());
        translateMatrix.postTranslate(-cropRect.centerX(), -cropRect.centerY());

        translateMatrix.mapRect(/*inout*/cropRect);

        // Round the rect corners towards the nearest integer values
        return ParamsUtils.createRect(cropRect);
    }

    /**
     * Shrink the {@code shrinkTarget} rectangle to snugly fit inside of {@code reference};
     * the aspect ratio of {@code shrinkTarget} will change to be the same aspect ratio as
     * {@code reference}.
     *
     * <p>At most a single dimension will scale (down). Both dimensions will never be scaled.</p>
     *
     * @param reference the rectangle whose aspect ratio will be used as the new aspect ratio
     * @param shrinkTarget the rectangle which will be scaled down to have a new aspect ratio
     *
     * @return a new rectangle, a subset of {@code shrinkTarget},
     *          whose aspect ratio will match that of {@code reference}
     */
    private static Rect shrinkToSameAspectRatioCentered(Rect reference, Rect shrinkTarget) {
        float aspectRatioReference = reference.width() * 1.0f / reference.height();
        float aspectRatioShrinkTarget = shrinkTarget.width() * 1.0f / shrinkTarget.height();

        float cropH, cropW;
        if (aspectRatioShrinkTarget < aspectRatioReference) {
            // The new width must be smaller than the height, so scale the width by AR
            cropH = reference.height();
            cropW = cropH * aspectRatioShrinkTarget;
        } else {
            // The new height must be smaller (or equal) than the width, so scale the height by AR
            cropW = reference.width();
            cropH = cropW / aspectRatioShrinkTarget;
        }

        Matrix translateMatrix = new Matrix();
        RectF shrunkRect = new RectF(shrinkTarget);

        // Scale the rectangle down, but keep its center in the same place as before
        translateMatrix.setScale(cropW / reference.width(), cropH / reference.height(),
                shrinkTarget.exactCenterX(), shrinkTarget.exactCenterY());

        translateMatrix.mapRect(/*inout*/shrunkRect);

        return ParamsUtils.createRect(shrunkRect);
    }

    /**
     * Get the available 'crop' (zoom) rectangles for this camera that will be reported
     * via a {@code CaptureResult} when a zoom is requested.
     *
     * <p>These crops ignores the underlying preview buffer size, and will always be reported
     * the same values regardless of what configuration of outputs is used.</p>
     *
     * <p>When zoom is supported, this will return a list of {@code 1 + #getMaxZoom} size,
     * where each crop rectangle corresponds to a zoom ratio (and is centered at the middle).</p>
     *
     * <p>Each crop rectangle is changed to have the same aspect ratio as {@code streamSize},
     * by shrinking the rectangle if necessary.</p>
     *
     * <p>To get the reported crop region when applying a zoom to the sensor, use {@code streamSize}
     * = {@code activeArray size}.</p>
     *
     * @param params non-{@code null} camera api1 parameters
     * @param activeArray active array dimensions, in sensor space
     * @param streamSize stream size dimensions, in pixels
     *
     * @return a list of available zoom rectangles, sorted from least zoomed to most zoomed
     */
    public static List<Rect> getAvailableZoomCropRectangles(
            Camera.Parameters params, Rect activeArray) {
        checkNotNull(params, "params must not be null");
        checkNotNull(activeArray, "activeArray must not be null");

        return getAvailableCropRectangles(params, activeArray, ParamsUtils.createSize(activeArray));
    }

    /**
     * Get the available 'crop' (zoom) rectangles for this camera.
     *
     * <p>This is the effective (real) crop that is applied by the camera api1 device
     * when projecting the zoom onto the intermediate preview buffer. Use this when
     * deciding which zoom ratio to apply.</p>
     *
     * <p>When zoom is supported, this will return a list of {@code 1 + #getMaxZoom} size,
     * where each crop rectangle corresponds to a zoom ratio (and is centered at the middle).</p>
     *
     * <p>Each crop rectangle is changed to have the same aspect ratio as {@code streamSize},
     * by shrinking the rectangle if necessary.</p>
     *
     * <p>To get the reported crop region when applying a zoom to the sensor, use {@code streamSize}
     * = {@code activeArray size}.</p>
     *
     * @param params non-{@code null} camera api1 parameters
     * @param activeArray active array dimensions, in sensor space
     * @param streamSize stream size dimensions, in pixels
     *
     * @return a list of available zoom rectangles, sorted from least zoomed to most zoomed
     */
    public static List<Rect> getAvailablePreviewZoomCropRectangles(Camera.Parameters params,
            Rect activeArray, Size previewSize) {
        checkNotNull(params, "params must not be null");
        checkNotNull(activeArray, "activeArray must not be null");
        checkNotNull(previewSize, "previewSize must not be null");

        return getAvailableCropRectangles(params, activeArray, previewSize);
    }

    /**
     * Get the available 'crop' (zoom) rectangles for this camera.
     *
     * <p>When zoom is supported, this will return a list of {@code 1 + #getMaxZoom} size,
     * where each crop rectangle corresponds to a zoom ratio (and is centered at the middle).</p>
     *
     * <p>Each crop rectangle is changed to have the same aspect ratio as {@code streamSize},
     * by shrinking the rectangle if necessary.</p>
     *
     * <p>To get the reported crop region when applying a zoom to the sensor, use {@code streamSize}
     * = {@code activeArray size}.</p>
     *
     * @param params non-{@code null} camera api1 parameters
     * @param activeArray active array dimensions, in sensor space
     * @param streamSize stream size dimensions, in pixels
     *
     * @return a list of available zoom rectangles, sorted from least zoomed to most zoomed
     */
    private static List<Rect> getAvailableCropRectangles(Camera.Parameters params,
            Rect activeArray, Size streamSize) {
        checkNotNull(params, "params must not be null");
        checkNotNull(activeArray, "activeArray must not be null");
        checkNotNull(streamSize, "streamSize must not be null");

        // TODO: change all uses of Rect activeArray to Size activeArray,
        // since we want the crop to be active-array relative, not pixel-array relative

        Rect unzoomedStreamCrop = getPreviewCropRectangleUnzoomed(activeArray, streamSize);

        if (!params.isZoomSupported()) {
            // Trivial case: No zoom -> only support the full size as the crop region
            return new ArrayList<>(Arrays.asList(unzoomedStreamCrop));
        }

        List<Rect> zoomCropRectangles = new ArrayList<>(params.getMaxZoom() + 1);
        Matrix scaleMatrix = new Matrix();
        RectF scaledRect = new RectF();

        for (int zoom : params.getZoomRatios()) {
            float shrinkRatio = ZOOM_RATIO_MULTIPLIER * 1.0f / zoom; // normalize to 1.0 and smaller

            // set scaledRect to unzoomedStreamCrop
            ParamsUtils.convertRectF(unzoomedStreamCrop, /*out*/scaledRect);

            scaleMatrix.setScale(
                    shrinkRatio, shrinkRatio,
                    activeArray.exactCenterX(),
                    activeArray.exactCenterY());

            scaleMatrix.mapRect(scaledRect);

            Rect intRect = ParamsUtils.createRect(scaledRect);

            // Round the rect corners towards the nearest integer values
            zoomCropRectangles.add(intRect);
        }

        return zoomCropRectangles;
    }

    /**
     * Get the largest possible zoom ratio (normalized to {@code 1.0f} and higher)
     * that the camera can support.
     *
     * <p>If the camera does not support zoom, it always returns {@code 1.0f}.</p>
     *
     * @param params non-{@code null} camera api1 parameters
     * @return normalized max zoom ratio, at least {@code 1.0f}
     */
    public static float getMaxZoomRatio(Camera.Parameters params) {
        if (!params.isZoomSupported()) {
            return 1.0f; // no zoom
        }

        List<Integer> zoomRatios = params.getZoomRatios(); // sorted smallest->largest
        int zoom = zoomRatios.get(zoomRatios.size() - 1); // largest zoom ratio
        float zoomRatio = zoom * 1.0f / ZOOM_RATIO_MULTIPLIER; // normalize to 1.0 and smaller

        return zoomRatio;
    }

    /**
     * Returns the component-wise zoom ratio (each greater or equal than {@code 1.0});
     * largest values means more zoom.
     *
     * @param activeArraySize active array size of the sensor (e.g. max jpeg size)
     * @param cropSize size of the crop/zoom
     *
     * @return {@link SizeF} with width/height being the component-wise zoom ratio
     *
     * @throws NullPointerException if any of the args were {@code null}
     * @throws IllegalArgumentException if any component of {@code cropSize} was {@code 0}
     */
    private static SizeF getZoomRatio(Size activeArraySize, Size cropSize) {
        checkNotNull(activeArraySize, "activeArraySize must not be null");
        checkNotNull(cropSize, "cropSize must not be null");
        checkArgumentPositive(cropSize.getWidth(), "cropSize.width must be positive");
        checkArgumentPositive(cropSize.getHeight(), "cropSize.height must be positive");

        float zoomRatioWidth = activeArraySize.getWidth() * 1.0f / cropSize.getWidth();
        float zoomRatioHeight = activeArraySize.getHeight() * 1.0f / cropSize.getHeight();

        return new SizeF(zoomRatioWidth, zoomRatioHeight);
    }

    private ParameterUtils() {
        throw new AssertionError();
    }
}
