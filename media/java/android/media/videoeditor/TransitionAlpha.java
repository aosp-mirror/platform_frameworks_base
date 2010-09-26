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


/**
 * This class allows to render an "alpha blending" transition according to a
 * bitmap mask. The mask shows the shape of the transition all along the
 * duration of the transition: just before the transition, video 1 is fully
 * displayed. When the transition starts, as the time goes on, pixels of video 2
 * replace pixels of video 1 according to the gray scale pixel value of the
 * mask.
 * {@hide}
 */
public class TransitionAlpha extends Transition {
    /** This is the input JPEG file for the mask */
    private final String mMaskFilename;

    /**
     * This is percentage (between 0 and 100) of blending between video 1 and
     * video 2 if this value equals 0, then the mask is strictly applied if this
     * value equals 100, then the mask is not at all applied (no transition
     * effect)
     */
    private final int mBlendingPercent;

    /**
     * If true, this value inverts the direction of the mask: white pixels of
     * the mask show video 2 pixels first black pixels of the mask show video 2
     * pixels last.
     */
    private final boolean mIsInvert;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private TransitionAlpha() {
        this(null, null, null, 0, 0, null, 0, false);
    }

    /**
     * Constructor
     *
     * @param transitionId The transition id
     * @param afterMediaItem The transition is applied to the end of this media
     *            item
     * @param beforeMediaItem The transition is applied to the beginning of this
     *            media item
     * @param durationMs duration of the transition in milliseconds
     * @param behavior behavior is one of the behavior defined in Transition
     *            class
     * @param maskFilename JPEG file name. The dimension of the image
     *           corresponds to 720p (16:9 aspect ratio). Mask files are
     *           shared between video editors and can be created in the
     *           projects folder (the parent folder for all projects).
     * @param blendingPercent The blending percent applied
     * @param invert true to invert the direction of the alpha blending
     * @throws IllegalArgumentException if behavior is not supported, or if
     *             direction are not supported.
     */
    public TransitionAlpha(String transitionId, MediaItem afterMediaItem,
            MediaItem beforeMediaItem, long durationMs, int behavior, String maskFilename,
            int blendingPercent, boolean invert) {
        super(transitionId, afterMediaItem, beforeMediaItem, durationMs, behavior);

        mMaskFilename = maskFilename;
        mBlendingPercent = blendingPercent;
        mIsInvert = invert;
    }

    /**
     * @return The blending percentage
     */
    public int getBlendingPercent() {
        return mBlendingPercent;
    }

    /**
     * @return The mask filename
     */
    public String getMaskFilename() {
        return mMaskFilename;
    }

    /**
     * @return true if the direction of the alpha blending is inverted
     */
    public boolean isInvert() {
        return mIsInvert;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void generate() {
    }
}
