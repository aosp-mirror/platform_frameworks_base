/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.letterbox;

import android.content.Context;
import android.view.Gravity;

import com.android.wm.shell.R;

/**
  * Controls access to and overrides of resource config values used by {@link
  * LetterboxTaskOrganizer}.
  */
public final class LetterboxConfigController {

    private final Context mContext;

    /** {@link Gravity} of letterboxed apps in portrait screen orientation. */
    private int mLetterboxPortraitGravity;

    /** {@link Gravity} of letterboxed apps in landscape screen orientation. */
    private int mLetterboxLandscapeGravity;

    public LetterboxConfigController(Context context) {
        mContext = context;
        mLetterboxPortraitGravity =
                mContext.getResources().getInteger(R.integer.config_letterboxPortraitGravity);
        mLetterboxLandscapeGravity =
                mContext.getResources().getInteger(R.integer.config_letterboxLandscapeGravity);
    }

    /**
     * Overrides {@link Gravity} of letterboxed apps in portrait screen orientation.
     *
     * @throws IllegalArgumentException if gravity isn't equal to {@link Gravity#TOP}, {@link
     *         Gravity#CENTER} or {@link Gravity#BOTTOM}.
     */
    public void setPortraitGravity(int gravity) {
        if (gravity != Gravity.TOP && gravity != Gravity.CENTER && gravity != Gravity.BOTTOM) {
            throw new IllegalArgumentException(
                    "Expected Gravity#TOP, Gravity#CENTER or Gravity#BOTTOM but got"
                    + gravity);
        }
        mLetterboxPortraitGravity = gravity;
    }

    /**
     * Resets {@link Gravity} of letterboxed apps in portrait screen orientation to {@link
     * R.integer.config_letterboxPortraitGravity}.
     */
    public void resetPortraitGravity() {
        mLetterboxPortraitGravity =
                mContext.getResources().getInteger(R.integer.config_letterboxPortraitGravity);
    }

    /**
     * Gets {@link Gravity} of letterboxed apps in portrait screen orientation.
     */
    public int getPortraitGravity() {
        return mLetterboxPortraitGravity;
    }

    /**
     * Overrides {@link Gravity} of letterboxed apps in landscape screen orientation.
     *
     * @throws IllegalArgumentException if gravity isn't equal to {@link Gravity#RIGHT}, {@link
     *         Gravity#CENTER} or {@link Gravity#LEFT}.
     */
    public void setLandscapeGravity(int gravity) {
        if (gravity != Gravity.LEFT && gravity != Gravity.CENTER && gravity != Gravity.RIGHT) {
            throw new IllegalArgumentException(
                    "Expected Gravity#LEFT, Gravity#CENTER or Gravity#RIGHT but got"
                    + gravity);
        }
        mLetterboxLandscapeGravity = gravity;
    }

    /**
     * Resets {@link Gravity} of letterboxed apps in landscape screen orientation to {@link
     * R.integer.config_letterboxLandscapeGravity}.
     */
    public void resetLandscapeGravity() {
        mLetterboxLandscapeGravity =
                mContext.getResources().getInteger(R.integer.config_letterboxLandscapeGravity);
    }

    /**
     * Gets {@link Gravity} of letterboxed apps in landscape screen orientation.
     */
    public int getLandscapeGravity() {
        return mLetterboxLandscapeGravity;
    }

}
