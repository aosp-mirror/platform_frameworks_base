package com.android.systemui.shared.recents.utilities;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Surface;

import com.android.systemui.shared.recents.model.ThumbnailData;

/**
 * Utility class to position the thumbnail in the TaskView
 */
public class PreviewPositionHelper {

    public static final float MAX_PCT_BEFORE_ASPECT_RATIOS_CONSIDERED_DIFFERENT = 0.1f;

    // Contains the portion of the thumbnail that is unclipped when fullscreen progress = 1.
    private final RectF mClippedInsets = new RectF();
    private final Matrix mMatrix = new Matrix();
    private boolean mIsOrientationChanged;

    public Matrix getMatrix() {
        return mMatrix;
    }

    public void setOrientationChanged(boolean orientationChanged) {
        mIsOrientationChanged = orientationChanged;
    }

    public boolean isOrientationChanged() {
        return mIsOrientationChanged;
    }

    /**
     * Updates the matrix based on the provided parameters
     */
    public void updateThumbnailMatrix(Rect thumbnailBounds, ThumbnailData thumbnailData,
            int canvasWidth, int canvasHeight, int screenWidthPx, int taskbarSize, boolean isTablet,
            int currentRotation, boolean isRtl) {
        boolean isRotated = false;
        boolean isOrientationDifferent;

        int thumbnailRotation = thumbnailData.rotation;
        int deltaRotate = getRotationDelta(currentRotation, thumbnailRotation);
        RectF thumbnailClipHint = new RectF();
        float canvasScreenRatio = canvasWidth / (float) screenWidthPx;
        float scaledTaskbarSize = taskbarSize * canvasScreenRatio;
        thumbnailClipHint.bottom = isTablet ? scaledTaskbarSize : 0;

        float scale = thumbnailData.scale;
        final float thumbnailScale;

        // Landscape vs portrait change.
        // Note: Disable rotation in grid layout.
        boolean windowingModeSupportsRotation =
                thumbnailData.windowingMode == WINDOWING_MODE_FULLSCREEN && !isTablet;
        isOrientationDifferent = isOrientationChange(deltaRotate)
                && windowingModeSupportsRotation;
        if (canvasWidth == 0 || canvasHeight == 0 || scale == 0) {
            // If we haven't measured , skip the thumbnail drawing and only draw the background
            // color
            thumbnailScale = 0f;
        } else {
            // Rotate the screenshot if not in multi-window mode
            isRotated = deltaRotate > 0 && windowingModeSupportsRotation;

            float surfaceWidth = thumbnailBounds.width() / scale;
            float surfaceHeight = thumbnailBounds.height() / scale;
            float availableWidth = surfaceWidth
                    - (thumbnailClipHint.left + thumbnailClipHint.right);
            float availableHeight = surfaceHeight
                    - (thumbnailClipHint.top + thumbnailClipHint.bottom);

            float canvasAspect = canvasWidth / (float) canvasHeight;
            float availableAspect = isRotated
                    ? availableHeight / availableWidth
                    : availableWidth / availableHeight;
            boolean isAspectLargelyDifferent =
                    Utilities.isRelativePercentDifferenceGreaterThan(canvasAspect,
                            availableAspect, MAX_PCT_BEFORE_ASPECT_RATIOS_CONSIDERED_DIFFERENT);
            if (isRotated && isAspectLargelyDifferent) {
                // Do not rotate thumbnail if it would not improve fit
                isRotated = false;
                isOrientationDifferent = false;
            }

            if (isAspectLargelyDifferent) {
                // Crop letterbox insets if insets isn't already clipped
                thumbnailClipHint.left = thumbnailData.letterboxInsets.left;
                thumbnailClipHint.right = thumbnailData.letterboxInsets.right;
                thumbnailClipHint.top = thumbnailData.letterboxInsets.top;
                thumbnailClipHint.bottom = thumbnailData.letterboxInsets.bottom;
                availableWidth = surfaceWidth
                        - (thumbnailClipHint.left + thumbnailClipHint.right);
                availableHeight = surfaceHeight
                        - (thumbnailClipHint.top + thumbnailClipHint.bottom);
            }

            final float targetW, targetH;
            if (isOrientationDifferent) {
                targetW = canvasHeight;
                targetH = canvasWidth;
            } else {
                targetW = canvasWidth;
                targetH = canvasHeight;
            }
            float targetAspect = targetW / targetH;

            // Update the clipHint such that
            //   > the final clipped position has same aspect ratio as requested by canvas
            //   > first fit the width and crop the extra height
            //   > if that will leave empty space, fit the height and crop the width instead
            float croppedWidth = availableWidth;
            float croppedHeight = croppedWidth / targetAspect;
            if (croppedHeight > availableHeight) {
                croppedHeight = availableHeight;
                if (croppedHeight < targetH) {
                    croppedHeight = Math.min(targetH, surfaceHeight);
                }
                croppedWidth = croppedHeight * targetAspect;

                // One last check in case the task aspect radio messed up something
                if (croppedWidth > surfaceWidth) {
                    croppedWidth = surfaceWidth;
                    croppedHeight = croppedWidth / targetAspect;
                }
            }

            // Update the clip hints. Align to 0,0, crop the remaining.
            if (isRtl) {
                thumbnailClipHint.left += availableWidth - croppedWidth;
                if (thumbnailClipHint.right < 0) {
                    thumbnailClipHint.left += thumbnailClipHint.right;
                    thumbnailClipHint.right = 0;
                }
            } else {
                thumbnailClipHint.right += availableWidth - croppedWidth;
                if (thumbnailClipHint.left < 0) {
                    thumbnailClipHint.right += thumbnailClipHint.left;
                    thumbnailClipHint.left = 0;
                }
            }
            thumbnailClipHint.bottom += availableHeight - croppedHeight;
            if (thumbnailClipHint.top < 0) {
                thumbnailClipHint.bottom += thumbnailClipHint.top;
                thumbnailClipHint.top = 0;
            } else if (thumbnailClipHint.bottom < 0) {
                thumbnailClipHint.top += thumbnailClipHint.bottom;
                thumbnailClipHint.bottom = 0;
            }

            thumbnailScale = targetW / (croppedWidth * scale);
        }

        if (!isRotated) {
            mMatrix.setTranslate(
                    -thumbnailClipHint.left * scale,
                    -thumbnailClipHint.top * scale);
        } else {
            setThumbnailRotation(deltaRotate, thumbnailBounds);
        }

        mClippedInsets.set(0, 0, 0, scaledTaskbarSize);

        mMatrix.postScale(thumbnailScale, thumbnailScale);
        mIsOrientationChanged = isOrientationDifferent;
    }

    private int getRotationDelta(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    /**
     * @param deltaRotation the number of 90 degree turns from the current orientation
     * @return {@code true} if the change in rotation results in a shift from landscape to
     * portrait or vice versa, {@code false} otherwise
     */
    private boolean isOrientationChange(int deltaRotation) {
        return deltaRotation == Surface.ROTATION_90 || deltaRotation == Surface.ROTATION_270;
    }

    private void setThumbnailRotation(int deltaRotate, Rect thumbnailPosition) {
        float translateX = 0;
        float translateY = 0;

        mMatrix.setRotate(90 * deltaRotate);
        switch (deltaRotate) { /* Counter-clockwise */
            case Surface.ROTATION_90:
                translateX = thumbnailPosition.height();
                break;
            case Surface.ROTATION_270:
                translateY = thumbnailPosition.width();
                break;
            case Surface.ROTATION_180:
                translateX = thumbnailPosition.width();
                translateY = thumbnailPosition.height();
                break;
        }
        mMatrix.postTranslate(translateX, translateY);
    }

    public RectF getClippedInsets() {
        return mClippedInsets;
    }
}
