/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import android.annotation.NonNull;
import android.graphics.Region;

/**
 * Processor class for AccessibilityService connection to control magnification on the specified
 * display. This wraps the function of magnification controller.
 *
 * @see MagnificationController
 * @see FullScreenMagnificationController
 */
public class MagnificationProcessor {

    private final MagnificationController mController;

    public MagnificationProcessor(MagnificationController controller) {
        mController = controller;
    }

    /**
     * {@link FullScreenMagnificationController#getScale(int)}
     */
    public float getScale(int displayId) {
        return mController.getFullScreenMagnificationController().getScale(displayId);
    }

    /**
     * {@link FullScreenMagnificationController#getCenterX(int)}
     */
    public float getCenterX(int displayId, boolean canControlMagnification) {
        boolean registeredJustForThisCall = registerMagnificationIfNeeded(displayId,
                canControlMagnification);
        try {
            return mController.getFullScreenMagnificationController().getCenterX(displayId);
        } finally {
            if (registeredJustForThisCall) {
                unregister(displayId);
            }
        }
    }

    /**
     * {@link FullScreenMagnificationController#getCenterY(int)}
     */
    public float getCenterY(int displayId, boolean canControlMagnification) {
        boolean registeredJustForThisCall = registerMagnificationIfNeeded(displayId,
                canControlMagnification);
        try {
            return mController.getFullScreenMagnificationController().getCenterY(displayId);
        } finally {
            if (registeredJustForThisCall) {
                unregister(displayId);
            }
        }
    }

    /**
     * {@link FullScreenMagnificationController#getMagnificationRegion(int, Region)}
     */
    public Region getMagnificationRegion(int displayId, @NonNull Region outRegion,
            boolean canControlMagnification) {
        boolean registeredJustForThisCall = registerMagnificationIfNeeded(displayId,
                canControlMagnification);
        try {
            mController.getFullScreenMagnificationController().getMagnificationRegion(displayId,
                    outRegion);
            return outRegion;
        } finally {
            if (registeredJustForThisCall) {
                unregister(displayId);
            }
        }
    }

    /**
     * {@link FullScreenMagnificationController#setScaleAndCenter(int, float, float, float, boolean,
     * int)}
     */
    public boolean setScaleAndCenter(int displayId, float scale, float centerX, float centerY,
            boolean animate, int id) {
        if (!isRegistered(displayId)) {
            register(displayId);
        }
        return mController.getFullScreenMagnificationController().setScaleAndCenter(displayId,
                scale,
                centerX, centerY, animate, id);
    }

    /**
     * {@link FullScreenMagnificationController#reset(int, boolean)}
     */
    public boolean reset(int displayId, boolean animate) {
        return mController.getFullScreenMagnificationController().reset(displayId, animate);
    }

    /**
     * {@link FullScreenMagnificationController#resetIfNeeded(int, boolean)}
     */
    public void resetAllIfNeeded(int connectionId) {
        mController.getFullScreenMagnificationController().resetAllIfNeeded(connectionId);
    }

    /**
     * {@link FullScreenMagnificationController#register(int)}
     */
    public void register(int displayId) {
        mController.getFullScreenMagnificationController().register(displayId);
    }

    /**
     * {@link FullScreenMagnificationController#unregister(int)} (int)}
     */
    public void unregister(int displayId) {
        mController.getFullScreenMagnificationController().unregister(displayId);
    }

    /**
     * {@link FullScreenMagnificationController#isMagnifying(int)}
     */
    public boolean isMagnifying(int displayId) {
        return mController.getFullScreenMagnificationController().isMagnifying(displayId);
    }

    /**
     * {@link FullScreenMagnificationController#isRegistered(int)}
     */
    public boolean isRegistered(int displayId) {
        return mController.getFullScreenMagnificationController().isRegistered(displayId);
    }

    private boolean registerMagnificationIfNeeded(int displayId, boolean canControlMagnification) {
        if (!isRegistered(displayId) && canControlMagnification) {
            register(displayId);
            return true;
        }
        return false;
    }
}
