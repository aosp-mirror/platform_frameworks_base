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
 * This class allows to render a crossfade (dissolve) effect transition between
 * two videos
 * {@hide}
 */
public class TransitionCrossfade extends Transition {
    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private TransitionCrossfade() {
        this(null, null, null, 0, 0);
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
     *      class
     *
     * @throws IllegalArgumentException if behavior is not supported.
     */
    public TransitionCrossfade(String transitionId, MediaItem afterMediaItem,
            MediaItem beforeMediaItem, long durationMs, int behavior) {
        super(transitionId, afterMediaItem, beforeMediaItem, durationMs, behavior);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void generate() {
        super.generate();
    }
}
