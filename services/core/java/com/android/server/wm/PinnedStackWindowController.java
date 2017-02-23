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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;

import android.app.RemoteAction;
import android.graphics.Rect;

import com.android.server.UiThread;

import java.util.List;

/**
 * Controller for the pinned stack container. See {@link StackWindowController}.
 */
public class PinnedStackWindowController extends StackWindowController {

    private Rect mTmpBoundsRect = new Rect();

    public PinnedStackWindowController(int stackId, StackWindowListener listener, int displayId,
            boolean onTop, Rect outBounds) {
        super(stackId, listener, displayId, onTop, outBounds, WindowManagerService.getInstance());
    }

    /**
     * Animates the pinned stack.
     */
    public void animateResizePinnedStack(Rect bounds, int animationDuration) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("Pinned stack container not found :(");
            }

            // Get non-null fullscreen bounds if the bounds are null
            final boolean moveToFullscreen = bounds == null;
            bounds = getPinnedStackAnimationBounds(bounds);

            // If the bounds are truly null, then there was no fullscreen stack at this time, so
            // animate this to the full display bounds
            final Rect toBounds;
            if (bounds == null) {
                toBounds = new Rect();
                mContainer.getDisplayContent().getLogicalDisplayRect(toBounds);
            } else {
                toBounds = bounds;
            }

            final Rect originalBounds = new Rect();
            mContainer.getBounds(originalBounds);
            mContainer.setAnimatingBounds(toBounds);
            UiThread.getHandler().post(() -> {
                mService.mBoundsAnimationController.animateBounds(mContainer, originalBounds,
                        toBounds, animationDuration, moveToFullscreen);
            });
        }
    }

    /**
     * Sets the current picture-in-picture aspect ratio.
     */
    public void setPictureInPictureAspectRatio(float aspectRatio) {
        synchronized (mWindowMap) {
            if (!mService.mSupportsPictureInPicture || mContainer == null) {
                return;
            }

            final int displayId = mContainer.getDisplayContent().getDisplayId();
            final Rect toBounds = mService.getPictureInPictureBounds(displayId, aspectRatio);
            final Rect targetBounds = new Rect();
            mContainer.getAnimatingBounds(targetBounds);
            if (!toBounds.equals(targetBounds)) {
                animateResizePinnedStack(toBounds, -1 /* duration */);
            }

            final PinnedStackController pinnedStackController =
                    mContainer.getDisplayContent().getPinnedStackController();
            pinnedStackController.setAspectRatio(
                    pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)
                            ? aspectRatio : -1f);
        }
    }

    /**
     * Sets the current picture-in-picture actions.
     */
    public void setPictureInPictureActions(List<RemoteAction> actions) {
        synchronized (mWindowMap) {
            if (!mService.mSupportsPictureInPicture || mContainer == null) {
                return;
            }

            mContainer.getDisplayContent().getPinnedStackController().setActions(actions);
        }
    }

    /**
     * Checks the {@param bounds} and retirms non-null fullscreen bounds for the pinned stack
     * animation if necessary.
     */
    private Rect getPinnedStackAnimationBounds(Rect bounds) {
        mService.getStackBounds(FULLSCREEN_WORKSPACE_STACK_ID, mTmpBoundsRect);
        if (bounds == null && !mTmpBoundsRect.isEmpty()) {
            bounds = new Rect(mTmpBoundsRect);
        }
        return bounds;
    }
}
