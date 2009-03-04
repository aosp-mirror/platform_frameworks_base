/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.view;

import android.graphics.Rect;

/**
 * A helper class that allows unit tests to access FocusFinder methods.
 * @hide
 */
public class FocusFinderHelper {
    
    private FocusFinder mFocusFinder;

    /**
     * Wrap the FocusFinder object
     */
    public FocusFinderHelper(FocusFinder focusFinder) {
        mFocusFinder = focusFinder;
    }
    
    public boolean isBetterCandidate(int direction, Rect source, Rect rect1, Rect rect2) {
        return mFocusFinder.isBetterCandidate(direction, source, rect1, rect2);
    }
    
    public boolean beamBeats(int direction, Rect source, Rect rect1, Rect rect2) {
        return mFocusFinder.beamBeats(direction, source, rect1, rect2);
    }

    public boolean isCandidate(Rect srcRect, Rect destRect, int direction) {
        return mFocusFinder.isCandidate(srcRect, destRect, direction);
    }
    
    public boolean beamsOverlap(int direction, Rect rect1, Rect rect2) {
        return mFocusFinder.beamsOverlap(direction, rect1, rect2);
    }
    
    public static int majorAxisDistance(int direction, Rect source, Rect dest) {
        return FocusFinder.majorAxisDistance(direction, source, dest);
    }
    
    public static int majorAxisDistanceToFarEdge(int direction, Rect source, Rect dest) {
        return FocusFinder.majorAxisDistanceToFarEdge(direction, source, dest);
    }
}
