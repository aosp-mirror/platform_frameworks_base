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
 * This transition fades to black frame using curtain closing: A black image is
 * moved from top to bottom to cover the video. This transition is always
 * applied at the end of the movie. {@hide}
 */
public class TransitionEndCurtainClosing extends Transition {
    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private TransitionEndCurtainClosing() {
        this(null, null, 0);
    }

    /**
     * Constructor.
     *
     * @param transitionId The transition id
     * @param afterMediaItem The transition is applied to the end of this
     *      media item
     * @param durationMs duration of the transition in milliseconds
     */
    public TransitionEndCurtainClosing(String transitionId, MediaItem afterMediaItem,
            long duration) {
        super(transitionId, afterMediaItem, null, duration, Transition.BEHAVIOR_LINEAR);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void generate() {
    }
}
