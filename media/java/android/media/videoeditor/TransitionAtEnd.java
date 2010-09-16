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
 * TransitionAtEnd is a class useful to manage a predefined transition at the
 * end of the movie.
 * {@hide}
 */
public class TransitionAtEnd extends Transition {
    /**
     * This transition fades to black frame using fade out in a certain provided
     * duration. This transition is always applied at the end of the movie.
     */
    public static final int TYPE_FADE_TO_BLACK = 0;

    /**
     * This transition fades to black frame using curtain closing: A black image is
     * moved from top to bottom to cover the video. This transition is always
     * applied at the end of the movie.
     */
    public static final int TYPE_CURTAIN_CLOSING = 1;

    // The transition type
    private final int mType;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private TransitionAtEnd() {
        this(null, null, 0, 0);
    }

    /**
     * Constructor.
     *
     * @param transitionId The transition id
     * @param afterMediaItem The transition is applied to the end of this
     *      media item
     * @param durationMs duration of the transition in milliseconds
     * @param type type of the transition to apply.
     */
    public TransitionAtEnd(String transitionId, MediaItem afterMediaItem, long duration,
            int type) {
        super(transitionId, afterMediaItem, null, duration, Transition.BEHAVIOR_LINEAR);
        mType = type;
    }

    /**
     * Get the type of this transition
     *
     * @return The type of the transition
     */
    public int getType() {
        return mType;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void generate() {
    }
}
