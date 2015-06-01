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
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.Area;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
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
@SuppressWarnings("deprecation")
public class ParameterUtils {
    /** Upper/left minimal point of a normalized rectangle */
    public static final int NORMALIZED_RECTANGLE_MIN = -1000;
    /** Lower/right maximal point of a normalized rectangle */
    public static final int NORMALIZED_RECTANGLE_MAX = 1000;
    /** The default normalized rectangle spans the entire size of the preview viewport */
    public static final Rect NORMALIZED_RECTANGLE_DEFAULT = new Rect(
            NORMALIZED_RECTANGLE_MIN,
            NORMALIZED_RECTANGLE_MIN,
            NORMALIZED_RECTANGLE_MAX,
            NORMALIZED_RECTANGLE_MAX);
    /** The default normalized area uses the default normalized rectangle with a weight=1 */
    public static final Camera.Area CAMERA_AREA_DEFAULT =
            new Camera.Area(new Rect(NORMALIZED_RECTANGLE_DEFAULT),
                            /*weight*/1);
    /** Empty rectangle {@code 0x0+0,0} */
    public static final Rect RECTANGLE_EMPTY =
            new Rect(/*left*/0, /*top*/0, /*right*/0, /*bottom*/0);

    private static final double ASPECT_RATIO_TOLERANCE = 0.05f;

    /**
     * Calculate effective/reported zoom data from a user-specified crop region.
     */
    public static class ZoomData {
        /** Zoom index used by {@link Camera.Parameters#setZoom} */
        public final int zoomIndex;
        /** Effective crop-region given the zoom index, coordinates relative to active-array */
        public final Rect previewCrop;
        /** Reported crop-region given the zoom index, coordinates relative to active-array */
        public final Rect reportedCrop;

        public ZoomData(int zoomIndex, Rect previewCrop, Rect reportedCrop) {
            this.zoomIndex = zoomIndex;
            this.previewCrop = previewCrop;
            this.reportedCrop = reportedCrop;
        }
    }

    /**
     * Calculate effective/reported metering data from a user-specified metering region.
     */
    public static class MeteringData {
        /**
         * The metering area scaled to the range of [-1000, 1000].
         * <p>Values outside of this range are clipped to be within the range.</p>
         */
        public final Camera.Area meteringArea;
        /**
         * Effective preview metering region, coordinates relative to active-array.
         *
         * <p>Clipped to fit inside of the (effective) preview crop region.</p>
         */
        public final Rect previewMetering;
        /**
         * Reported metering region, coordinates relative to active-array.
         *
         * <p>Clipped to fit inside of the (reported) resulting crop region.</p>
         */
        public final Rect reportedMetering;

        public MeteringData(Area meteringArea, Rect previewMetering, Rect reportedMetering) {
            this.meteringArea = meteringArea;
            this.previewMetering = previewMetering;
            this.reportedMetering = reportedMetering;
        }
    }

    /**
     * A weighted rectangle is an arbitrary rectangle (the coordinate system is unknown) with an
     * arbitrary weight.
     *
     * <p>The user of this class must know what the coordinate system ahead of time; it's
     * then possible to convert to a more concrete type such as a metering rectangle or a face.
     * </p>
     *
     * <p>When converting to a more concrete type, out-of-range values are clipped; this prevents
     * possible illegal argument exceptions being thrown at runtime.</p>
     */
    public static class WeightedRectangle {
        /** Arbitrary rectangle (the range is user-defined); never {@code null}. */
        public final Rect rect;
        /** Arbitrary weight (the range is user-defined). */
        public final int weight;

        /**
         * Create a new weighted-rectangle from a non-{@code null} rectangle; the {@code weight}
         * can be unbounded.
         */
        public WeightedRectangle(Rect rect, int weight) {
            this.rect = checkNotNull(rect, "rect must not be null");
            this.weight = weight;
        }

        /**
         * Convert to a metering rectangle, clipping any of the values to stay within range.
         *
         * <p>If values are clipped, a warning is printed to logcat.</p>
         *
         * @return a new metering rectangle
         */
        public MeteringRectangle toMetering() {
            int weight = clip(this.weight,
                    MeteringRectangle.METERING_WEIGHT_MIN,
                    MeteringRectangle.METERING_WEIGHT_MAX,
                    rect,
                    "weight");

            int x = clipLower(rect.left, /*lo*/0, rect, "left");
            int y = clipLower(rect.top, /*lo*/0, rect, "top");
            int w = clipLower(rect.width(), /*lo*/0, rect, "width");
            int h = clipLower(rect.height(), /*lo*/0, rect, "height");

            return new MeteringRectangle(x, y, w, h, weight);
        }

        /**
         * Convert to a face; the rect is considered to be the bounds, and the weight
         * is considered to be the score.
         *
         * <p>If the score is out of range of {@value Face#SCORE_MIN}, {@value Face#SCORE_MAX},
         * the score is clipped first and a warning is printed to logcat.</p>
         *
         * <p>If the id is negative, the id is changed to 0 and a warning is printed to
         * logcat.</p>
         *
         * <p>All other parameters are passed-through as-is.</p>
         *
         * @return a new face with the optional features set
         */
        public Face toFace(
                int id, Point leftEyePosition, Point rightEyePosition, Point mouthPosition) {
            int idSafe = clipLower(id, /*lo*/0, rect, "id");
            int score = clip(weight,
                    Face.SCORE_MIN,
                    Face.SCORE_MAX,
                    rect,
                    "score");

            return new Face(rect, score, idSafe, leftEyePosition, rightEyePosition, mouthPosition);
        }

        /**
         * Convert to a face; the rect is considered to be the bounds, and the weight
         * is considered to be the score.
         *
         * <p>If the score is out of range of {@value Face#SCORE_MIN}, {@value Face#SCORE_MAX},
         * the score is clipped first and a warning is printed to logcat.</p>
         *
         * <p>All other parameters are passed-through as-is.</p>
         *
         * @return a new face without the optional features
         */
        public Face toFace() {
            int score = clip(weight,
                    Face.SCORE_MIN,
                    Face.SCORE_MAX,
                    rect,
                    "score");

            return new Face(rect, score);
        }

        private static int clipLower(int value, int lo, Rect rect, String name) {
            return clip(value, lo, /*hi*/Integer.MAX_VALUE, rect, name);
        }

        private static int clip(int value, int lo, int hi, Rect rect, String name) {
            if (value < lo) {
                Log.w(TAG, "toMetering - Rectangle " + rect + " "
                        + name + " too small, clip to " + lo);
                value = lo;
            } else if (value > hi) {
                Log.w(TAG, "toMetering - Rectangle " + rect + " "
                        + name + " too small, clip to " + hi);
                value = hi;
            }

            return value;
        }
    }

    private static final String TAG = "ParameterUtils";
    private static final boolean DEBUG = false;

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
     * Convert a camera API1 list of sizes into an array of sizes
     */
    public static Size[] convertSizeListToArray(List<Camera.Size> sizeList) {
        checkNotNull(sizeList, "sizeList must not be null");

        Size[] array = new Size[sizeList.size()];
        int ctr = 0;
        for (Camera.Size s : sizeList) {
            array[ctr++] = new Size(s.width, s.height);
        }
        return array;
    }

    /**
     * Check if the camera API1 list of sizes contains a size with the given dimens.
     */
    public static boolean containsSize(List<Camera.Size> sizeList, int width, int height) {
        checkNotNull(sizeList, "sizeList must not be null");
        for (Camera.Size s : sizeList) {
            if (s.height == height && s.width == width) {
                return true;
            }
        }
        return false;
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
     * Convert a camera area list into a human-readable string
     * @param areaList a list of areas (null is ok)
     */
    public static String stringFromAreaList(List<Camera.Area> areaList) {
        StringBuilder sb = new StringBuilder();

        if (areaList == null) {
            return null;
        }

        int i = 0;
        for (Camera.Area area : areaList) {
            if (area == null) {
                sb.append("null");
            } else {
                sb.append(stringFromArea(area));
            }

            if (i != areaList.size() - 1) {
                sb.append(", ");
            }

            i++;
        }

        return sb.toString();
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

        if (DEBUG) {
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

        if (DEBUG) {
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
        if (Math.abs(aspectRatioPreview - aspectRatioArray) < ASPECT_RATIO_TOLERANCE) {
            cropH = activeArray.height();
            cropW = activeArray.width();
        } else if (aspectRatioPreview < aspectRatioArray) {
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

    /**
     * Convert the user-specified crop region into zoom data; which can be used
     * to set the parameters to a specific zoom index, or to report back to the user what the
     * actual zoom was, or for other calculations requiring the current preview crop region.
     *
     * <p>None of the parameters are mutated.</p>
     *
     * @param activeArraySize active array size of the sensor (e.g. max jpeg size)
     * @param cropRegion the user-specified crop region
     * @param previewSize the current preview size (in pixels)
     * @param params the current camera parameters (not mutated)
     *
     * @return the zoom index, and the effective/reported crop regions (relative to active array)
     */
    public static ZoomData convertScalerCropRegion(Rect activeArraySize, Rect
            cropRegion, Size previewSize, Camera.Parameters params) {
        Rect activeArraySizeOnly = new Rect(
                /*left*/0, /*top*/0,
                activeArraySize.width(), activeArraySize.height());

        Rect userCropRegion = cropRegion;

        if (userCropRegion == null) {
            userCropRegion = activeArraySizeOnly;
        }

        if (DEBUG) {
            Log.v(TAG, "convertScalerCropRegion - user crop region was " + userCropRegion);
        }

        final Rect reportedCropRegion = new Rect();
        final Rect previewCropRegion = new Rect();
        final int zoomIdx = ParameterUtils.getClosestAvailableZoomCrop(params, activeArraySizeOnly,
                previewSize, userCropRegion,
                /*out*/reportedCropRegion, /*out*/previewCropRegion);

        if (DEBUG) {
            Log.v(TAG, "convertScalerCropRegion - zoom calculated to: " +
                    "zoomIndex = " + zoomIdx +
                    ", reported crop region = " + reportedCropRegion +
                    ", preview crop region = " + previewCropRegion);
        }

        return new ZoomData(zoomIdx, previewCropRegion, reportedCropRegion);
    }

    /**
     * Calculate the actual/effective/reported normalized rectangle data from a metering
     * rectangle.
     *
     * <p>If any of the rectangles are out-of-range of their intended bounding box,
     * the {@link #RECTANGLE_EMPTY empty rectangle} is substituted instead
     * (with a weight of {@code 0}).</p>
     *
     * <p>The metering rectangle is bound by the crop region (effective/reported respectively).
     * The metering {@link Camera.Area area} is bound by {@code [-1000, 1000]}.</p>
     *
     * <p>No parameters are mutated; returns the new metering data.</p>
     *
     * @param activeArraySize active array size of the sensor (e.g. max jpeg size)
     * @param meteringRect the user-specified metering rectangle
     * @param zoomData the calculated zoom data corresponding to this request
     *
     * @return the metering area, the reported/effective metering rectangles
     */
    public static MeteringData convertMeteringRectangleToLegacy(
            Rect activeArray, MeteringRectangle meteringRect, ZoomData zoomData) {
        Rect previewCrop = zoomData.previewCrop;

        float scaleW = (NORMALIZED_RECTANGLE_MAX - NORMALIZED_RECTANGLE_MIN) * 1.0f /
                previewCrop.width();
        float scaleH = (NORMALIZED_RECTANGLE_MAX - NORMALIZED_RECTANGLE_MIN) * 1.0f /
                previewCrop.height();

        Matrix transform = new Matrix();
        // Move the preview crop so that top,left is at (0,0), otherwise after scaling
        // the corner bounds will be outside of [-1000, 1000]
        transform.setTranslate(-previewCrop.left, -previewCrop.top);
        // Scale into [0, 2000] range about the center of the preview
        transform.postScale(scaleW, scaleH);
        // Move so that top left of a typical rect is at [-1000, -1000]
        transform.postTranslate(/*dx*/NORMALIZED_RECTANGLE_MIN, /*dy*/NORMALIZED_RECTANGLE_MIN);

        /*
         * Calculate the preview metering region (effective), and the camera1 api
         * normalized metering region.
         */
        Rect normalizedRegionUnbounded = ParamsUtils.mapRect(transform, meteringRect.getRect());

        /*
         * Try to intersect normalized area with [-1000, 1000] rectangle; otherwise
         * it's completely out of range
         */
        Rect normalizedIntersected = new Rect(normalizedRegionUnbounded);

        Camera.Area meteringArea;
        if (!normalizedIntersected.intersect(NORMALIZED_RECTANGLE_DEFAULT)) {
            Log.w(TAG,
                    "convertMeteringRectangleToLegacy - metering rectangle too small, " +
                    "no metering will be done");
            normalizedIntersected.set(RECTANGLE_EMPTY);
            meteringArea = new Camera.Area(RECTANGLE_EMPTY,
                    MeteringRectangle.METERING_WEIGHT_DONT_CARE);
        } else {
            meteringArea = new Camera.Area(normalizedIntersected,
                    meteringRect.getMeteringWeight());
        }

        /*
         * Calculate effective preview metering region
         */
        Rect previewMetering = meteringRect.getRect();
        if (!previewMetering.intersect(previewCrop)) {
            previewMetering.set(RECTANGLE_EMPTY);
        }

        /*
         * Calculate effective reported metering region
         * - Transform the calculated metering area back into active array space
         * - Clip it to be a subset of the reported crop region
         */
        Rect reportedMetering;
        {
            Camera.Area normalizedAreaUnbounded = new Camera.Area(
                    normalizedRegionUnbounded, meteringRect.getMeteringWeight());
            WeightedRectangle reportedMeteringRect = convertCameraAreaToActiveArrayRectangle(
                    activeArray, zoomData, normalizedAreaUnbounded, /*usePreviewCrop*/false);
            reportedMetering = reportedMeteringRect.rect;
        }

        if (DEBUG) {
            Log.v(TAG, String.format(
                    "convertMeteringRectangleToLegacy - activeArray = %s, meteringRect = %s, " +
                    "previewCrop = %s, meteringArea = %s, previewMetering = %s, " +
                    "reportedMetering = %s, normalizedRegionUnbounded = %s",
                    activeArray, meteringRect,
                    previewCrop, stringFromArea(meteringArea), previewMetering,
                    reportedMetering, normalizedRegionUnbounded));
        }

        return new MeteringData(meteringArea, previewMetering, reportedMetering);
    }

    /**
     * Convert the normalized camera area from [-1000, 1000] coordinate space
     * into the active array-based coordinate space.
     *
     * <p>Values out of range are clipped to be within the resulting (reported) crop
     * region. It is possible to have values larger than the preview crop.</p>
     *
     * <p>Weights out of range of [0, 1000] are clipped to be within the range.</p>
     *
     * @param activeArraySize active array size of the sensor (e.g. max jpeg size)
     * @param zoomData the calculated zoom data corresponding to this request
     * @param area the normalized camera area
     *
     * @return the weighed rectangle in active array coordinate space, with the weight
     */
    public static WeightedRectangle convertCameraAreaToActiveArrayRectangle(
            Rect activeArray, ZoomData zoomData, Camera.Area area) {
        return convertCameraAreaToActiveArrayRectangle(activeArray, zoomData, area,
                /*usePreviewCrop*/true);
    }

    /**
     * Convert an api1 face into an active-array based api2 face.
     *
     * <p>Out-of-ranges scores and ids will be clipped to be within range (with a warning).</p>
     *
     * @param face a non-{@code null} api1 face
     * @param activeArraySize active array size of the sensor (e.g. max jpeg size)
     * @param zoomData the calculated zoom data corresponding to this request
     *
     * @return a non-{@code null} api2 face
     *
     * @throws NullPointerException if the {@code face} was {@code null}
     */
    public static Face convertFaceFromLegacy(Camera.Face face, Rect activeArray,
            ZoomData zoomData) {
        checkNotNull(face, "face must not be null");

        Face api2Face;

        Camera.Area fakeArea = new Camera.Area(face.rect, /*weight*/1);

        WeightedRectangle faceRect =
                convertCameraAreaToActiveArrayRectangle(activeArray, zoomData, fakeArea);

        Point leftEye = face.leftEye, rightEye = face.rightEye, mouth = face.mouth;
        if (leftEye != null && rightEye != null && mouth != null && leftEye.x != -2000 &&
                leftEye.y != -2000 && rightEye.x != -2000 && rightEye.y != -2000 &&
                mouth.x != -2000 && mouth.y != -2000) {
            leftEye = convertCameraPointToActiveArrayPoint(activeArray, zoomData,
                    leftEye, /*usePreviewCrop*/true);
            rightEye = convertCameraPointToActiveArrayPoint(activeArray, zoomData,
                    leftEye, /*usePreviewCrop*/true);
            mouth = convertCameraPointToActiveArrayPoint(activeArray, zoomData,
                    leftEye, /*usePreviewCrop*/true);

            api2Face = faceRect.toFace(face.id, leftEye, rightEye, mouth);
        } else {
            api2Face = faceRect.toFace();
        }

        return api2Face;
    }

    private static Point convertCameraPointToActiveArrayPoint(
            Rect activeArray, ZoomData zoomData, Point point, boolean usePreviewCrop) {
        Rect pointedRect = new Rect(point.x, point.y, point.x, point.y);
        Camera.Area pointedArea = new Area(pointedRect, /*weight*/1);

        WeightedRectangle adjustedRect =
                convertCameraAreaToActiveArrayRectangle(activeArray,
                        zoomData, pointedArea, usePreviewCrop);

        Point transformedPoint = new Point(adjustedRect.rect.left, adjustedRect.rect.top);

        return transformedPoint;
    }

    private static WeightedRectangle convertCameraAreaToActiveArrayRectangle(
            Rect activeArray, ZoomData zoomData, Camera.Area area, boolean usePreviewCrop) {
        Rect previewCrop = zoomData.previewCrop;
        Rect reportedCrop = zoomData.reportedCrop;

        float scaleW = previewCrop.width() * 1.0f /
                (NORMALIZED_RECTANGLE_MAX - NORMALIZED_RECTANGLE_MIN);
        float scaleH = previewCrop.height() * 1.0f /
                (NORMALIZED_RECTANGLE_MAX - NORMALIZED_RECTANGLE_MIN);

        /*
         * Calculate the reported metering region from the non-intersected normalized region
         * by scaling and translating back into active array-relative coordinates.
         */
        Matrix transform = new Matrix();

        // Move top left from (-1000, -1000) to (0, 0)
        transform.setTranslate(/*dx*/NORMALIZED_RECTANGLE_MAX, /*dy*/NORMALIZED_RECTANGLE_MAX);

        // Scale from [0, 2000] back into the preview rectangle
        transform.postScale(scaleW, scaleH);

        // Move the rect so that the [-1000,-1000] point ends up at the preview [left, top]
        transform.postTranslate(previewCrop.left, previewCrop.top);

        Rect cropToIntersectAgainst = usePreviewCrop ? previewCrop : reportedCrop;

        // Now apply the transformation backwards to get the reported metering region
        Rect reportedMetering = ParamsUtils.mapRect(transform, area.rect);
        // Intersect it with the crop region, to avoid reporting out-of-bounds
        // metering regions
        if (!reportedMetering.intersect(cropToIntersectAgainst)) {
            reportedMetering.set(RECTANGLE_EMPTY);
        }

        int weight = area.weight;
        if (weight < MeteringRectangle.METERING_WEIGHT_MIN) {
            Log.w(TAG,
                    "convertCameraAreaToMeteringRectangle - rectangle "
                            + stringFromArea(area) + " has too small weight, clip to 0");
            weight = 0;
        }

        return new WeightedRectangle(reportedMetering, area.weight);
    }


    private ParameterUtils() {
        throw new AssertionError();
    }
}
