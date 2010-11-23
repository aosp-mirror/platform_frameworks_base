/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.videoeditor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.util.Pair;

/**
 * This class represents an image item on the storyboard. Note that images are
 * scaled down to the maximum supported resolution by preserving the native
 * aspect ratio. To learn the scaled image dimensions use
 * {@link #getScaledWidth()} and {@link #getScaledHeight()} respectively.
 *
 * {@hide}
 */
public class MediaImageItem extends MediaItem {
    // Logging
    private static final String TAG = "MediaImageItem";

    // The resize paint
    private static final Paint sResizePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Instance variables
    private final int mWidth;
    private final int mHeight;
    private final int mAspectRatio;
    private long mDurationMs;
    private int mScaledWidth, mScaledHeight;

    /**
     * This class cannot be instantiated by using the default constructor
     */
    @SuppressWarnings("unused")
    private MediaImageItem() throws IOException {
        this(null, null, null, 0, RENDERING_MODE_BLACK_BORDER);
    }

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param mediaItemId The media item id
     * @param filename The image file name
     * @param durationMs The duration of the image on the storyboard
     * @param renderingMode The rendering mode
     *
     * @throws IOException
     */
    public MediaImageItem(VideoEditor editor, String mediaItemId, String filename, long durationMs,
            int renderingMode)
            throws IOException {
        super(editor, mediaItemId, filename, renderingMode);

        // Determine the dimensions of the image
        final BitmapFactory.Options dbo = new BitmapFactory.Options();
        dbo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, dbo);

        mWidth = dbo.outWidth;
        mHeight = dbo.outHeight;
        mDurationMs = durationMs;

        // TODO: Determine the aspect ratio from the width and height
        mAspectRatio = MediaProperties.ASPECT_RATIO_4_3;

        // Images are stored in memory scaled to the maximum resolution to
        // save memory.
        final Pair<Integer, Integer>[] resolutions =
            MediaProperties.getSupportedResolutions(mAspectRatio);
        // Get the highest resolution
        final Pair<Integer, Integer> maxResolution = resolutions[resolutions.length - 1];
        if (mHeight > maxResolution.second) {
            // We need to scale the image
            scaleImage(filename, maxResolution.first, maxResolution.second);
            mScaledWidth = maxResolution.first;
            mScaledHeight = maxResolution.second;
        } else {
            mScaledWidth = mWidth;
            mScaledHeight = mHeight;
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getFileType() {
        if (mFilename.endsWith(".jpg") || mFilename.endsWith(".jpeg")) {
            return MediaProperties.FILE_JPEG;
        } else if (mFilename.endsWith(".png")) {
            return MediaProperties.FILE_PNG;
        } else {
            return MediaProperties.FILE_UNSUPPORTED;
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getWidth() {
        return mWidth;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getHeight() {
        return mHeight;
    }

    /**
     * @return The scaled width of the image.
     */
    public int getScaledWidth() {
        return mScaledWidth;
    }

    /**
     * @return The scaled height of the image.
     */
    public int getScaledHeight() {
        return mScaledHeight;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getAspectRatio() {
        return mAspectRatio;
    }

    /**
     * This method will adjust the duration of bounding transitions, effects
     * and overlays if the current duration of the transactions become greater
     * than the maximum allowable duration.
     *
     * @param durationMs The duration of the image in the storyboard timeline
     */
    public void setDuration(long durationMs) {
        if (durationMs == mDurationMs) {
            return;
        }

        // Invalidate the end transitions if necessary.
        // This invalidation is necessary for the case in which an effect or
        // an overlay is overlapping with the end transition
        // (before the duration is changed) and it no longer overlaps with the
        // transition after the duration is increased.

        // The beginning transition does not need to be invalidated at this time
        // because an effect or an overlay overlaps with the beginning
        // transition, the begin transition is unaffected by a media item
        // duration change.
        invalidateEndTransition();

        mDurationMs = durationMs;

        adjustTransitions();
        final List<Overlay> adjustedOverlays = adjustOverlays();
        final List<Effect> adjustedEffects = adjustEffects();

        // Invalidate the beginning and end transitions after adjustments.
        // This invalidation is necessary for the case in which an effect or
        // an overlay was not overlapping with the beginning or end transitions
        // before the setDuration reduces the duration of the media item and
        // causes an overlap of the beginning and/or end transition with the
        // effect.
        invalidateBeginTransition(adjustedEffects, adjustedOverlays);
        invalidateEndTransition();
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public long getDuration() {
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public long getTimelineDuration() {
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap getThumbnail(int width, int height, long timeMs) throws IOException {
        return scaleImage(mFilename, width, height);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap[] getThumbnailList(int width, int height, long startMs, long endMs,
            int thumbnailCount) throws IOException {
        final Bitmap thumbnail = scaleImage(mFilename, width, height);
        final Bitmap[] thumbnailArray = new Bitmap[thumbnailCount];
        for (int i = 0; i < thumbnailCount; i++) {
            thumbnailArray[i] = thumbnail;
        }
        return thumbnailArray;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidateTransitions(long startTimeMs, long durationMs) {
        // Check if the item overlaps with the beginning and end transitions
        if (mBeginTransition != null) {
            if (isOverlapping(startTimeMs, durationMs, 0, mBeginTransition.getDuration())) {
                mBeginTransition.invalidate();
            }
        }

        if (mEndTransition != null) {
            final long transitionDurationMs = mEndTransition.getDuration();
            if (isOverlapping(startTimeMs, durationMs,
                    getDuration() - transitionDurationMs, transitionDurationMs)) {
                mEndTransition.invalidate();
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidateTransitions(long oldStartTimeMs, long oldDurationMs, long newStartTimeMs,
            long newDurationMs) {
        // Check if the item overlaps with the beginning and end transitions
        if (mBeginTransition != null) {
            final long transitionDurationMs = mBeginTransition.getDuration();
            // If the start time has changed and if the old or the new item
            // overlaps with the begin transition, invalidate the transition.
            if (oldStartTimeMs != newStartTimeMs &&
                    (isOverlapping(oldStartTimeMs, oldDurationMs, 0, transitionDurationMs) ||
                    isOverlapping(newStartTimeMs, newDurationMs, 0, transitionDurationMs))) {
                mBeginTransition.invalidate();
            }
        }

        if (mEndTransition != null) {
            final long transitionDurationMs = mEndTransition.getDuration();
            // If the start time + duration has changed and if the old or the new
            // item overlaps the end transition, invalidate the transition/
            if (oldStartTimeMs + oldDurationMs != newStartTimeMs + newDurationMs &&
                    (isOverlapping(oldStartTimeMs, oldDurationMs,
                            mDurationMs - transitionDurationMs, transitionDurationMs) ||
                    isOverlapping(newStartTimeMs, newDurationMs,
                            mDurationMs - transitionDurationMs, transitionDurationMs))) {
                mEndTransition.invalidate();
            }
        }
    }

    /**
     * Invalidate the begin transition if any effects and overlays overlap
     * with the begin transition.
     *
     * @param effects List of effects to check for transition overlap
     * @param overlays List of overlays to check for transition overlap
     */
    private void invalidateBeginTransition(List<Effect> effects, List<Overlay> overlays) {
        if (mBeginTransition != null && mBeginTransition.isGenerated()) {
            final long transitionDurationMs = mBeginTransition.getDuration();

            // The begin transition must be invalidated if it overlaps with
            // an effect.
            for (Effect effect : effects) {
                // Check if the effect overlaps with the begin transition
                if (effect.getStartTime() < transitionDurationMs) {
                    mBeginTransition.invalidate();
                    break;
                }
            }

            if (mBeginTransition.isGenerated()) {
                // The end transition must be invalidated if it overlaps with
                // an overlay.
                for (Overlay overlay : overlays) {
                    // Check if the overlay overlaps with the end transition
                    if (overlay.getStartTime() < transitionDurationMs) {
                        mBeginTransition.invalidate();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Invalidate the end transition if any effects and overlays overlap
     * with the end transition.
     */
    private void invalidateEndTransition() {
        if (mEndTransition != null && mEndTransition.isGenerated()) {
            final long transitionDurationMs = mEndTransition.getDuration();

            // The end transition must be invalidated if it overlaps with
            // an effect.
            final List<Effect> effects = getAllEffects();
            for (Effect effect : effects) {
                // Check if the effect overlaps with the end transition
                if (effect.getStartTime() + effect.getDuration() >
                            mDurationMs - transitionDurationMs) {
                    mEndTransition.invalidate();
                    break;
                }
            }

            if (mEndTransition.isGenerated()) {
                // The end transition must be invalidated if it overlaps with
                // an overlay.
                final List<Overlay> overlays = getAllOverlays();
                for (Overlay overlay : overlays) {
                    // Check if the overlay overlaps with the end transition
                    if (overlay.getStartTime() + overlay.getDuration() >
                                mDurationMs - transitionDurationMs) {
                        mEndTransition.invalidate();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Adjust the start time and/or duration of effects.
     *
     * @return The list of effects which were adjusted
     */
    private List<Effect> adjustEffects() {
        final List<Effect> adjustedEffects = new ArrayList<Effect>();
        final List<Effect> effects = getAllEffects();
        for (Effect effect : effects) {
            // Adjust the start time if necessary
            final long effectStartTimeMs;
            if (effect.getStartTime() > getDuration()) {
                effectStartTimeMs = 0;
            } else {
                effectStartTimeMs = effect.getStartTime();
            }

            // Adjust the duration if necessary
            final long effectDurationMs;
            if (effectStartTimeMs + effect.getDuration() > getDuration()) {
                effectDurationMs = getDuration() - effectStartTimeMs;
            } else {
                effectDurationMs = effect.getDuration();
            }

            if (effectStartTimeMs != effect.getStartTime() ||
                    effectDurationMs != effect.getDuration()) {
                effect.setStartTimeAndDuration(effectStartTimeMs, effectDurationMs);
                adjustedEffects.add(effect);
            }
        }

        return adjustedEffects;
    }

    /**
     * Adjust the start time and/or duration of overlays.
     *
     * @return The list of overlays which were adjusted
     */
    private List<Overlay> adjustOverlays() {
        final List<Overlay> adjustedOverlays = new ArrayList<Overlay>();
        final List<Overlay> overlays = getAllOverlays();
        for (Overlay overlay : overlays) {
            // Adjust the start time if necessary
            final long overlayStartTimeMs;
            if (overlay.getStartTime() > getDuration()) {
                overlayStartTimeMs = 0;
            } else {
                overlayStartTimeMs = overlay.getStartTime();
            }

            // Adjust the duration if necessary
            final long overlayDurationMs;
            if (overlayStartTimeMs + overlay.getDuration() > getDuration()) {
                overlayDurationMs = getDuration() - overlayStartTimeMs;
            } else {
                overlayDurationMs = overlay.getDuration();
            }

            if (overlayStartTimeMs != overlay.getStartTime() ||
                    overlayDurationMs != overlay.getDuration()) {
                overlay.setStartTimeAndDuration(overlayStartTimeMs, overlayDurationMs);
                adjustedOverlays.add(overlay);
            }
        }

        return adjustedOverlays;
    }

    /**
     * Resize a bitmap to the specified width and height
     *
     * @param filename The filename
     * @param width The thumbnail width
     * @param height The thumbnail height
     *
     * @return The resized bitmap
     */
    private Bitmap scaleImage(String filename, int width, int height) throws IOException {
        final BitmapFactory.Options dbo = new BitmapFactory.Options();
        dbo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, dbo);

        final int nativeWidth = dbo.outWidth;
        final int nativeHeight = dbo.outHeight;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "generateThumbnail: Input: " + nativeWidth + "x" + nativeHeight
                    + ", resize to: " + width + "x" + height);
        }

        final Bitmap srcBitmap;
        float bitmapWidth, bitmapHeight;
        if (nativeWidth > width || nativeHeight > height) {
            float dx = ((float)nativeWidth) / ((float)width);
            float dy = ((float)nativeHeight) / ((float)height);
            if (dx > dy) {
                bitmapWidth = width;
                bitmapHeight = nativeHeight / dx;
            } else {
                bitmapWidth = nativeWidth / dy;
                bitmapHeight = height;
            }
            // Create the bitmap from file
            if (nativeWidth / bitmapWidth > 1) {
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = nativeWidth / (int)bitmapWidth;
                srcBitmap = BitmapFactory.decodeFile(filename, options);
            } else {
                srcBitmap = BitmapFactory.decodeFile(filename);
            }
        } else {
            bitmapWidth = width;
            bitmapHeight = height;
            srcBitmap = BitmapFactory.decodeFile(filename);
        }

        if (srcBitmap == null) {
            Log.e(TAG, "generateThumbnail: Cannot decode image bytes");
            throw new IOException("Cannot decode file: " + mFilename);
        }

        // Create the canvas bitmap
        final Bitmap bitmap = Bitmap.createBitmap((int)bitmapWidth, (int)bitmapHeight,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(srcBitmap, new Rect(0, 0, srcBitmap.getWidth(), srcBitmap.getHeight()),
                new Rect(0, 0, (int)bitmapWidth, (int)bitmapHeight), sResizePaint);
        // Release the source bitmap
        srcBitmap.recycle();
        return bitmap;
    }
}
