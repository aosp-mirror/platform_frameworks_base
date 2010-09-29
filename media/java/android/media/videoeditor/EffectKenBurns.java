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

import android.graphics.Rect;

/**
 * This class represents a Ken Burns effect.
 * {@hide}
 */
public class EffectKenBurns extends Effect {
    // Instance variables
    private Rect mStartRect;
    private Rect mEndRect;

    /**
     * Objects of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private EffectKenBurns() throws IOException {
        this(null, null, null, null, 0, 0);
    }

    /**
     * Constructor
     *
     * @param mediaItem The media item owner
     * @param effectId The effect id
     * @param startRect The start rectangle
     * @param endRect The end rectangle
     * @param startTimeMs The start time
     * @param durationMs The duration of the Ken Burns effect in milliseconds
     */
    public EffectKenBurns(MediaItem mediaItem, String effectId, Rect startRect, Rect endRect,
            long startTime, long durationMs)
            throws IOException {
        super(mediaItem, effectId, startTime, durationMs);

        mStartRect = startRect;
        mEndRect = endRect;
    }

    /**
     * @param startRect The start rectangle
     *
     * @throws IllegalArgumentException if start rectangle is incorrectly set.
     */
    public void setStartRect(Rect startRect) {
        mStartRect = startRect;
    }

    /**
     * @return The start rectangle
     */
    public Rect getStartRect() {
        return mStartRect;
    }

    /**
     * @param endRect The end rectangle
     *
     * @throws IllegalArgumentException if end rectangle is incorrectly set.
     */
    public void setEndRect(Rect endRect) {
        mEndRect = endRect;
    }

    /**
     * @return The end rectangle
     */
    public Rect getEndRect() {
        return mEndRect;
    }
}
