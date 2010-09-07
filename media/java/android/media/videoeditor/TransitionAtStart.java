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
 * TransitionAtStart is a class useful to manage a predefined transition at the
 * beginning of the movie.
 * {@hide}
 */
public class TransitionAtStart extends Transition {
    /**
     * This transition fades from black using fade-in in a certain provided
     * duration. This transition is always applied at the beginning of the
     * movie.
     */
    public static final int TYPE_FADE_FROM_BLACK = 0;

    /**
     * This transition fades from black frame using curtain opening: A black
     * image is displayed and moves from bottom to top making the video visible.
     * This transition is always applied at the beginning of the movie.
     */
    public static final int TYPE_CURTAIN_OPENING = 1;

    // The transition type
    private final int mType;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private TransitionAtStart() {
        this(null, null, 0, 0);
    }

    /**
     * Constructor
     *
     * @param transitionId The transition id
     * @param beforeMediaItem The transition is applied to the beginning of
     *      this media item
     * @param durationMs The duration of the transition in milliseconds
     * @param type The type of the transition to apply.
     */
    public TransitionAtStart(String transitionId, MediaItem beforeMediaItem, long durationMs,
            int type) {
        super(transitionId, null, beforeMediaItem, durationMs,
                Transition.BEHAVIOR_LINEAR);
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
    public void generate() {
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidate() {
    }
}
