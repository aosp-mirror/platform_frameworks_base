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
 * This class allows to create sliding transitions
 * {@hide}
 */
public class TransitionSliding extends Transition {

    /** Video 1 is pushed to the right while video 2 is coming from left */
    public final static int DIRECTION_RIGHT_OUT_LEFT_IN = 0;
    /** Video 1 is pushed to the left while video 2 is coming from right */
    public static final int DIRECTION_LEFT_OUT_RIGHT_IN = 1;
    /** Video 1 is pushed to the top while video 2 is coming from bottom */
    public static final int DIRECTION_TOP_OUT_BOTTOM_IN = 2;
    /** Video 1 is pushed to the bottom while video 2 is coming from top */
    public static final int DIRECTION_BOTTOM_OUT_TOP_IN = 3;

    // The sliding transitions
    private final int mSlidingDirection;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private TransitionSliding() {
        this(null, null, null, 0, 0, 0);
    }

    /**
     * Constructor
     *
     * @param transitionId The transition id
     * @param afterMediaItem The transition is applied to the end of this
     *      media item
     * @param beforeMediaItem The transition is applied to the beginning of
     *      this media item
     * @param durationMs duration of the transition in milliseconds
     * @param behavior behavior is one of the behavior defined in Transition
     *            class
     * @param direction direction shall be one of the supported directions like
     *            RIGHT_OUT_LEFT_IN
     *
     * @throws IllegalArgumentException if behavior is not supported.
     */
    public TransitionSliding(String transitionId, MediaItem afterMediaItem,
            MediaItem beforeMediaItem, long durationMs, int behavior,
            int direction) {
        super(transitionId, afterMediaItem, beforeMediaItem, durationMs, behavior);
        switch (direction) {
            case DIRECTION_RIGHT_OUT_LEFT_IN:
            case DIRECTION_LEFT_OUT_RIGHT_IN:
            case DIRECTION_TOP_OUT_BOTTOM_IN:
            case DIRECTION_BOTTOM_OUT_TOP_IN:
                break;

            default:
                throw new IllegalArgumentException("Invalid direction");
        }
        mSlidingDirection = direction;
    }

    /**
     * Get the sliding direction.
     *
     * @return The sliding direction
     */
    public int getDirection() {
        return mSlidingDirection;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void generate() {
        super.generate();
    }
}
