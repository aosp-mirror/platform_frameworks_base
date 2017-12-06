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

import static com.android.server.wm.BoundsAnimationController.NO_PIP_MODE_CHANGED_CALLBACKS;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_END;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_START;
import static com.android.server.wm.BoundsAnimationController.SchedulePipModeChangedState;

import android.app.RemoteAction;
import android.graphics.Rect;

import com.android.server.UiThread;

import java.util.List;

/**
 * Controller for the pinned stack container. See {@link StackWindowController}.
 */
public class PinnedStackWindowController extends StackWindowController {

    private Rect mTmpFromBounds = new Rect();
    private Rect mTmpToBounds = new Rect();

    public PinnedStackWindowController(int stackId, PinnedStackWindowListener listener,
            int displayId, boolean onTop, Rect outBounds) {
        super(stackId, listener, displayId, onTop, outBounds, WindowManagerService.getInstance());
    }

    /**
     * @return the {@param currentStackBounds} transformed to the give {@param aspectRatio}.  If
     *         {@param currentStackBounds} is null, then the {@param aspectRatio} is applied to the
     *         default bounds.
     */
    public Rect getPictureInPictureBounds(float aspectRatio, Rect stackBounds) {
        synchronized (mWindowMap) {
            if (!mService.mSupportsPictureInPicture || mContainer == null) {
                return null;
            }

            final DisplayContent displayContent = mContainer.getDisplayContent();
            if (displayContent == null) {
                return null;
            }

            final PinnedStackController pinnedStackController =
                    displayContent.getPinnedStackController();
            if (stackBounds == null) {
                // Calculate the aspect ratio bounds from the default bounds
                stackBounds = pinnedStackController.getDefaultBounds();
            }

            if (pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)) {
                return pinnedStackController.transformBoundsToAspectRatio(stackBounds, aspectRatio,
                        true /* useCurrentMinEdgeSize */);
            } else {
                return stackBounds;
            }
        }
    }

    /**
     * Animates the pinned stack.
     */
    public void animateResizePinnedStack(Rect toBounds, Rect sourceHintBounds,
            int animationDuration, boolean fromFullscreen) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("Pinned stack container not found :(");
            }

            // Get the from-bounds
            final Rect fromBounds = new Rect();
            mContainer.getBounds(fromBounds);

            // Get non-null fullscreen to-bounds for animating if the bounds are null
            @SchedulePipModeChangedState int schedulePipModeChangedState =
                NO_PIP_MODE_CHANGED_CALLBACKS;
            final boolean toFullscreen = toBounds == null;
            if (toFullscreen) {
                if (fromFullscreen) {
                    throw new IllegalArgumentException("Should not defer scheduling PiP mode"
                            + " change on animation to fullscreen.");
                }
                schedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_START;

                mService.getStackBounds(FULLSCREEN_WORKSPACE_STACK_ID, mTmpToBounds);
                if (!mTmpToBounds.isEmpty()) {
                    // If there is a fullscreen bounds, use that
                    toBounds = new Rect(mTmpToBounds);
                } else {
                    // Otherwise, use the display bounds
                    toBounds = new Rect();
                    mContainer.getDisplayContent().getLogicalDisplayRect(toBounds);
                }
            } else if (fromFullscreen) {
                schedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_END;
            }

            mContainer.setAnimationFinalBounds(sourceHintBounds, toBounds, toFullscreen);

            final Rect finalToBounds = toBounds;
            final @SchedulePipModeChangedState int finalSchedulePipModeChangedState =
                schedulePipModeChangedState;
            mService.mBoundsAnimationController.getHandler().post(() -> {
                if (mContainer == null) {
                    return;
                }
                mService.mBoundsAnimationController.animateBounds(mContainer, fromBounds,
                        finalToBounds, animationDuration, finalSchedulePipModeChangedState,
                        fromFullscreen, toFullscreen);
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

            final PinnedStackController pinnedStackController =
                    mContainer.getDisplayContent().getPinnedStackController();

            if (Float.compare(aspectRatio, pinnedStackController.getAspectRatio()) != 0) {
                mContainer.getAnimationOrCurrentBounds(mTmpFromBounds);
                mTmpToBounds.set(mTmpFromBounds);
                getPictureInPictureBounds(aspectRatio, mTmpToBounds);
                if (!mTmpToBounds.equals(mTmpFromBounds)) {
                    animateResizePinnedStack(mTmpToBounds, null /* sourceHintBounds */,
                            -1 /* duration */, false /* fromFullscreen */);
                }
                pinnedStackController.setAspectRatio(
                        pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)
                                ? aspectRatio : -1f);
            }
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
     * @return whether the multi-window mode change should be deferred as a part of a transition
     * from fullscreen to non-fullscreen bounds.
     */
    public boolean deferScheduleMultiWindowModeChanged() {
        synchronized(mWindowMap) {
            return mContainer.deferScheduleMultiWindowModeChanged();
        }
    }

    /**
     * @return whether the bounds are currently animating to fullscreen.
     */
    public boolean isAnimatingBoundsToFullscreen() {
        synchronized (mWindowMap) {
            return mContainer.isAnimatingBoundsToFullscreen();
        }
    }

    /**
     * @return whether the stack can be resized from the bounds animation.
     */
    public boolean pinnedStackResizeDisallowed() {
        synchronized (mWindowMap) {
            return mContainer.pinnedStackResizeDisallowed();
        }
    }

    /**
     * The following calls are made from WM to AM.
     */

    /** Calls directly into activity manager so window manager lock shouldn't held. */
    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds,
            boolean forceUpdate) {
        if (mListener != null) {
            PinnedStackWindowListener listener = (PinnedStackWindowListener) mListener;
            listener.updatePictureInPictureModeForPinnedStackAnimation(targetStackBounds,
                    forceUpdate);
        }
    }
}
