/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.graphics.Rect;
import android.util.Rational;

import java.util.ArrayList;
import java.util.List;

/**
 * TO BE REMOVED
 */
@Deprecated
public final class PictureInPictureArgs extends PictureInPictureParams {

    /**
     * Creates a new set of picture-in-picture arguments.
     *
     * TODO: Remove once we remove PictureInPictureArgs.
     */
    @Deprecated
    public PictureInPictureArgs() {
        // Empty constructor
    }

    /**
     * Creates a new set of picture-in-picture arguments from the given {@param aspectRatio} and
     * {@param actions}.
     *
     * TODO: Remove once we remove PictureInPictureArgs.
     */
    @Deprecated
    public PictureInPictureArgs(float aspectRatio, List<RemoteAction> actions) {
        setAspectRatio(aspectRatio);
        if (actions != null) {
            setActions(actions);
        }
    }

    /**
     * Sets the aspect ratio.
     *
     * @param aspectRatio the new aspect ratio for picture-in-picture, must be within 2.39:1 and
     *                    1:2.39.
     *
     * TODO: Remove once we remove PictureInPictureArgs.
     */
    @Deprecated
    public void setAspectRatio(float aspectRatio) {
        // Temporary workaround
        mAspectRatio = new Rational((int) (aspectRatio * 1000000000), 1000000000);
    }

    /**
     * Sets the user actions.  If there are more than
     * {@link ActivityManager#getMaxNumPictureInPictureActions()} actions, then the input will be
     * truncated to that number.
     *
     * @param actions the new actions to show in the picture-in-picture menu.
     *
     * @see RemoteAction
     *
     * TODO: Remove once we remove PictureInPictureArgs.
     */
    @Deprecated
    public void setActions(List<RemoteAction> actions) {
        if (mUserActions != null) {
            mUserActions = null;
        }
        if (actions != null) {
            mUserActions = new ArrayList<>(actions);
        }
    }

    /**
     * Sets the source bounds hint. These bounds are only used when an activity first enters
     * picture-in-picture, and describe the bounds in window coordinates of activity entering
     * picture-in-picture that will be visible following the transition. For the best effect, these
     * bounds should also match the aspect ratio in the arguments.
     *
     * TODO: Remove once we remove PictureInPictureArgs.
     */
    @Deprecated
    public void setSourceRectHint(Rect launchBounds) {
        if (launchBounds == null) {
            mSourceRectHint = null;
        } else {
            mSourceRectHint = new Rect(launchBounds);
        }
    }
}