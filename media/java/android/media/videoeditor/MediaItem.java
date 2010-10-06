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

/**
 * This abstract class describes the base class for any MediaItem. Objects are
 * defined with a file path as a source data.
 * {@hide}
s */
public abstract class MediaItem {
    // A constant which can be used to specify the end of the file (instead of
    // providing the actual duration of the media item).
    public final static int END_OF_FILE = -1;

    // Rendering modes
    /**
     * When using the RENDERING_MODE_BLACK_BORDER rendering mode video frames
     * are resized by preserving the aspect ratio until the movie matches one of
     * the dimensions of the output movie. The areas outside the resized video
     * clip are rendered black.
     */
    public static final int RENDERING_MODE_BLACK_BORDER = 0;
    /**
     * When using the RENDERING_MODE_STRETCH rendering mode video frames are
     * stretched horizontally or vertically to match the current aspect ratio of
     * the movie.
     */
    public static final int RENDERING_MODE_STRETCH = 1;


    // The unique id of the MediaItem
    private final String mUniqueId;

    // The name of the file associated with the MediaItem
    protected final String mFilename;

    // List of effects
    private final List<Effect> mEffects;

    // List of overlays
    private final List<Overlay> mOverlays;

    // The rendering mode
    private int mRenderingMode;

    // Beginning and end transitions
    protected Transition mBeginTransition;
    protected Transition mEndTransition;

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param mediaItemId The MediaItem id
     * @param filename name of the media file.
     * @param renderingMode The rendering mode
     *
     * @throws IOException if file is not found
     * @throws IllegalArgumentException if a capability such as file format is not
     *             supported the exception object contains the unsupported
     *             capability
     */
    protected MediaItem(VideoEditor editor, String mediaItemId, String filename,
            int renderingMode) throws IOException {
        mUniqueId = mediaItemId;
        mFilename = filename;
        mRenderingMode = renderingMode;
        mEffects = new ArrayList<Effect>();
        mOverlays = new ArrayList<Overlay>();
        mBeginTransition = null;
        mEndTransition = null;
    }

    /**
     * @return The id of the media item
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * @return The media source file name
     */
    public String getFilename() {
        return mFilename;
    }

    /**
     * If aspect ratio of the MediaItem is different from the aspect ratio of
     * the editor then this API controls the rendering mode.
     *
     * @param renderingMode rendering mode. It is one of:
     *            {@link #RENDERING_MODE_BLACK_BORDER},
     *            {@link #RENDERING_MODE_STRETCH}
     */
    public void setRenderingMode(int renderingMode) {
        mRenderingMode = renderingMode;
        if (mBeginTransition != null) {
            mBeginTransition.invalidate();
        }

        if (mEndTransition != null) {
            mEndTransition.invalidate();
        }
    }

    /**
     * @return The rendering mode
     */
    public int getRenderingMode() {
        return mRenderingMode;
    }

    /**
     * @param transition The beginning transition
     */
    void setBeginTransition(Transition transition) {
        mBeginTransition = transition;
    }

    /**
     * @return The begin transition
     */
    public Transition getBeginTransition() {
        return mBeginTransition;
    }

    /**
     * @param transition The end transition
     */
    void setEndTransition(Transition transition) {
        mEndTransition = transition;
    }

    /**
     * @return The end transition
     */
    public Transition getEndTransition() {
        return mEndTransition;
    }

    /**
     * @return The timeline duration. This is the actual duration in the
     *      timeline (trimmed duration)
     */
    public abstract long getTimelineDuration();

    /**
     * @return The source file type
     */
    public abstract int getFileType();

    /**
     * @return Get the native width of the media item
     */
    public abstract int getWidth();

    /**
     * @return Get the native height of the media item
     */
    public abstract int getHeight();

    /**
     * Get aspect ratio of the source media item.
     *
     * @return the aspect ratio as described in MediaProperties.
     *  MediaProperties.ASPECT_RATIO_UNDEFINED if aspect ratio is not
     *  supported as in MediaProperties
     */
    public abstract int getAspectRatio();

    /**
     * Add the specified effect to this media item.
     *
     * Note that certain types of effects cannot be applied to video and to
     * image media items. For example in certain implementation a Ken Burns
     * implementation cannot be applied to video media item.
     *
     * This method invalidates transition video clips if the
     * effect overlaps with the beginning and/or the end transition.
     *
     * @param effect The effect to apply
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if the effect start and/or duration are
     *      invalid or if the effect cannot be applied to this type of media
     *      item or if the effect id is not unique across all the Effects
     *      added.
     */
    public void addEffect(Effect effect) {
        if (effect.getMediaItem() != this) {
            throw new IllegalArgumentException("Media item mismatch");
        }

        if (mEffects.contains(effect)) {
            throw new IllegalArgumentException("Effect already exists: " + effect.getId());
        }

        if (effect.getStartTime() + effect.getDuration() > getTimelineDuration()) {
            throw new IllegalArgumentException(
                    "Effect start time + effect duration > media clip duration");
        }

        mEffects.add(effect);
        invalidateTransitions(effect);
    }

    /**
     * Remove the effect with the specified id.
     *
     * This method invalidates a transition video clip if the effect overlaps
     * with a transition.
     *
     * @param effectId The id of the effect to be removed
     *
     * @return The effect that was removed
     * @throws IllegalStateException if a preview or an export is in progress
     */
    public Effect removeEffect(String effectId) {
        for (Effect effect : mEffects) {
            if (effect.getId().equals(effectId)) {
                mEffects.remove(effect);
                invalidateTransitions(effect);
                return effect;
            }
        }

        return null;
    }

    /**
     * Find the effect with the specified id
     *
     * @param effectId The effect id
     *
     * @return The effect with the specified id (null if it does not exist)
     */
    public Effect getEffect(String effectId) {
        for (Effect effect : mEffects) {
            if (effect.getId().equals(effectId)) {
                return effect;
            }
        }

        return null;
    }

    /**
     * Get the list of effects.
     *
     * @return the effects list. If no effects exist an empty list will be returned.
     */
    public List<Effect> getAllEffects() {
        return mEffects;
    }

    /**
     * Add an overlay to the storyboard. This method invalidates a transition
     * video clip if the overlay overlaps with a transition.
     *
     * @param overlay The overlay to add
     * @throws IllegalStateException if a preview or an export is in progress or
     *             if the overlay id is not unique across all the overlays
     *             added or if the bitmap is not specified or if the dimensions of
     *             the bitmap do not match the dimensions of the media item
     */
    public void addOverlay(Overlay overlay) {
        if (overlay.getMediaItem() != this) {
            throw new IllegalArgumentException("Media item mismatch");
        }

        if (mOverlays.contains(overlay)) {
            throw new IllegalArgumentException("Overlay already exists: " + overlay.getId());
        }

        if (overlay.getStartTime() + overlay.getDuration() > getTimelineDuration()) {
            throw new IllegalArgumentException(
                    "Overlay start time + overlay duration > media clip duration");
        }

        if (overlay instanceof OverlayFrame) {
            final OverlayFrame frame = (OverlayFrame)overlay;
            final Bitmap bitmap = frame.getBitmap();
            if (bitmap == null) {
                throw new IllegalArgumentException("Overlay bitmap not specified");
            }

            final int scaledWidth, scaledHeight;
            if (this instanceof MediaVideoItem) {
                scaledWidth = getWidth();
                scaledHeight = getHeight();
            } else {
                scaledWidth = ((MediaImageItem)this).getScaledWidth();
                scaledHeight = ((MediaImageItem)this).getScaledHeight();
            }

            // The dimensions of the overlay bitmap must be the same as the
            // media item dimensions
            if (bitmap.getWidth() != scaledWidth || bitmap.getHeight() != scaledHeight) {
                throw new IllegalArgumentException(
                        "Bitmap dimensions must match media item dimensions");
            }
        } else {
            throw new IllegalArgumentException("Overlay not supported");
        }

        mOverlays.add(overlay);
        invalidateTransitions(overlay);
    }

    /**
     * Remove the overlay with the specified id.
     *
     * This method invalidates a transition video clip if the overlay overlaps
     * with a transition.
     *
     * @param overlayId The id of the overlay to be removed
     *
     * @return The overlay that was removed
     * @throws IllegalStateException if a preview or an export is in progress
     */
    public Overlay removeOverlay(String overlayId) {
        for (Overlay overlay : mOverlays) {
            if (overlay.getId().equals(overlayId)) {
                mOverlays.remove(overlay);
                if (overlay instanceof OverlayFrame) {
                    ((OverlayFrame)overlay).invalidate();
                }
                invalidateTransitions(overlay);
                return overlay;
            }
        }

        return null;
    }

    /**
     * Find the overlay with the specified id
     *
     * @param overlayId The overlay id
     *
     * @return The overlay with the specified id (null if it does not exist)
     */
    public Overlay getOverlay(String overlayId) {
        for (Overlay overlay : mOverlays) {
            if (overlay.getId().equals(overlayId)) {
                return overlay;
            }
        }

        return null;
    }

    /**
     * Get the list of overlays associated with this media item
     *
     * Note that if any overlay source files are not accessible anymore,
     * this method will still provide the full list of overlays.
     *
     * @return The list of overlays. If no overlays exist an empty list will
     *  be returned.
     */
    public List<Overlay> getAllOverlays() {
        return mOverlays;
    }

    /**
     * Create a thumbnail at specified time in a video stream in Bitmap format
     *
     * @param width width of the thumbnail in pixels
     * @param height height of the thumbnail in pixels
     * @param timeMs The time in the source video file at which the thumbnail is
     *            requested (even if trimmed).
     *
     * @return The thumbnail as a Bitmap.
     *
     * @throws IOException if a file error occurs
     * @throws IllegalArgumentException if time is out of video duration
     */
    public abstract Bitmap getThumbnail(int width, int height, long timeMs) throws IOException;

    /**
     * Get the array of Bitmap thumbnails between start and end.
     *
     * @param width width of the thumbnail in pixels
     * @param height height of the thumbnail in pixels
     * @param startMs The start of time range in milliseconds
     * @param endMs The end of the time range in milliseconds
     * @param thumbnailCount The thumbnail count
     *
     * @return The array of Bitmaps
     *
     * @throws IOException if a file error occurs
     */
    public abstract Bitmap[] getThumbnailList(int width, int height, long startMs, long endMs,
            int thumbnailCount) throws IOException;

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof MediaItem)) {
            return false;
        }
        return mUniqueId.equals(((MediaItem)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }

    /**
     * Invalidate the start and end transitions if necessary
     *
     * @param effect The effect that was added or removed
     */
    void invalidateTransitions(Effect effect) {
        // Check if the effect overlaps with the beginning and end transitions
        if (mBeginTransition != null) {
            if (effect.getStartTime() < mBeginTransition.getDuration()) {
                mBeginTransition.invalidate();
            }
        }

        if (mEndTransition != null) {
            if (effect.getStartTime() + effect.getDuration() > getTimelineDuration()
                    - mEndTransition.getDuration()) {
                mEndTransition.invalidate();
            }
        }
    }

    /**
     * Invalidate the start and end transitions if necessary
     *
     * @param overlay The effect that was added or removed
     */
    void invalidateTransitions(Overlay overlay) {
        // Check if the overlay overlaps with the beginning and end transitions
        if (mBeginTransition != null) {
            if (overlay.getStartTime() < mBeginTransition.getDuration()) {
                mBeginTransition.invalidate();
            }
        }

        if (mEndTransition != null) {
            if (overlay.getStartTime() + overlay.getDuration() > getTimelineDuration()
                    - mEndTransition.getDuration()) {
                mEndTransition.invalidate();
            }
        }
    }

    /**
     * Adjust the duration of effects, overlays and transitions.
     * This method will be called after a media item duration is changed.
     */
    protected void adjustElementsDuration() {
        // Check if the duration of transitions need to be adjusted
        if (mBeginTransition != null) {
            final long maxDurationMs = mBeginTransition.getMaximumDuration();
            if (mBeginTransition.getDuration() > maxDurationMs) {
                mBeginTransition.setDuration(maxDurationMs);
            }
        }

        if (mEndTransition != null) {
            final long maxDurationMs = mEndTransition.getMaximumDuration();
            if (mEndTransition.getDuration() > maxDurationMs) {
                mEndTransition.setDuration(maxDurationMs);
            }
        }

        final List<Overlay> overlays = getAllOverlays();
        for (Overlay overlay : overlays) {
            // Adjust the start time if necessary
            final long overlayStartTimeMs;
            if (overlay.getStartTime() > getTimelineDuration()) {
                overlayStartTimeMs = 0;
            } else {
                overlayStartTimeMs = overlay.getStartTime();
            }

            // Adjust the duration if necessary
            final long overlayDurationMs;
            if (overlayStartTimeMs + overlay.getDuration() > getTimelineDuration()) {
                overlayDurationMs = getTimelineDuration() - overlayStartTimeMs;
            } else {
                overlayDurationMs = overlay.getDuration();
            }

            if (overlayStartTimeMs != overlay.getStartTime() ||
                    overlayDurationMs != overlay.getDuration()) {
                overlay.setStartTimeAndDuration(overlayStartTimeMs, overlayDurationMs);
            }
        }

        final List<Effect> effects = getAllEffects();
        for (Effect effect : effects) {
            // Adjust the start time if necessary
            final long effectStartTimeMs;
            if (effect.getStartTime() > getTimelineDuration()) {
                effectStartTimeMs = 0;
            } else {
                effectStartTimeMs = effect.getStartTime();
            }

            // Adjust the duration if necessary
            final long effectDurationMs;
            if (effectStartTimeMs + effect.getDuration() > getTimelineDuration()) {
                effectDurationMs = getTimelineDuration() - effectStartTimeMs;
            } else {
                effectDurationMs = effect.getDuration();
            }

            if (effectStartTimeMs != effect.getStartTime() ||
                    effectDurationMs != effect.getDuration()) {
                effect.setStartTimeAndDuration(effectStartTimeMs, effectDurationMs);
            }
        }
    }
}
