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

import android.graphics.Rect;

/**
 * This class represents a Ken Burns effect.
 * {@hide}
 */
public class EffectKenBurns extends Effect {
    /**
     *  Instance variables
     */
    private Rect mStartRect;
    private Rect mEndRect;

    /**
     * Objects of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private EffectKenBurns() {
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
    public EffectKenBurns(MediaItem mediaItem, String effectId, Rect startRect,
                         Rect endRect, long startTimeMs, long durationMs) {
        super(mediaItem, effectId, startTimeMs, durationMs);

        if ( (startRect.width() <= 0) || (startRect.height() <= 0) ) {
            throw new IllegalArgumentException("Invalid Start rectangle");
        }
        if ( (endRect.width() <= 0) || (endRect.height() <= 0) ) {
            throw new IllegalArgumentException("Invalid End rectangle");
        }

        mStartRect = startRect;
        mEndRect = endRect;
    }


    /**
     * Get the start rectangle.
     *
     * @return The start rectangle
     */
    public Rect getStartRect() {
        return mStartRect;
    }


    /**
     * Get the end rectangle.
     *
     * @return The end rectangle
     */
    public Rect getEndRect() {
        return mEndRect;
    }

    /**
     * Get the KenBurn effect start and end rectangle coordinates
     * @param start The rect object to be populated with start
     * rectangle coordinates
     *
     * @param end The rect object to be populated with end
     * rectangle coordinates
     */
    void getKenBurnsSettings(Rect start, Rect end) {
        start.left = getStartRect().left;
        start.top = getStartRect().top;
        start.right = getStartRect().right;
        start.bottom = getStartRect().bottom;
        end.left = getEndRect().left;
        end.top = getEndRect().top;
        end.right = getEndRect().right;
        end.bottom = getEndRect().bottom;
    }
}
