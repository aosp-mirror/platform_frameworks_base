/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.graphics.Matrix;
import android.view.DisplayInfo;
import android.view.Surface.Rotation;

import com.android.server.wm.utils.CoordinateTransforms;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Helper class for forced seamless rotation.
 *
 * Works by transforming the window token back into the old display rotation.
 *
 * Uses deferTransactionUntil instead of latching on the buffer size to allow for seamless 180
 * degree rotations.
 */
public class ForcedSeamlessRotator {

    private final Matrix mTransform = new Matrix();
    private final float[] mFloat9 = new float[9];
    private final int mOldRotation;
    private final int mNewRotation;

    public ForcedSeamlessRotator(int oldRotation, int newRotation, DisplayInfo info) {
        mOldRotation = oldRotation;
        mNewRotation = newRotation;

        final boolean flipped = info.rotation == ROTATION_90 || info.rotation == ROTATION_270;
        final int h = flipped ? info.logicalWidth : info.logicalHeight;
        final int w = flipped ? info.logicalHeight : info.logicalWidth;

        final Matrix tmp = new Matrix();
        CoordinateTransforms.transformLogicalToPhysicalCoordinates(oldRotation, w, h, mTransform);
        CoordinateTransforms.transformPhysicalToLogicalCoordinates(newRotation, w, h, tmp);
        mTransform.postConcat(tmp);
    }

    /**
     * Applies a transform to the window token's surface that undoes the effect of the global
     * display rotation.
     */
    public void unrotate(WindowToken token) {
        token.getPendingTransaction().setMatrix(token.getSurfaceControl(), mTransform, mFloat9);
    }

    /**
     * Returns the rotation of the display before it started rotating.
     *
     * @return the old rotation of the display
     */
    @Rotation
    public int getOldRotation() {
        return mOldRotation;
    }

    /**
     * Removes the transform to the window token's surface that undoes the effect of the global
     * display rotation.
     *
     * Removing the transform and the result of the WindowState's layout are both tied to the
     * WindowState's next frame, such that they apply at the same time the client draws the
     * window in the new orientation.
     */
    public void finish(WindowToken token, WindowState win) {
        mTransform.reset();
        token.getPendingTransaction().setMatrix(token.mSurfaceControl, mTransform, mFloat9);
        if (win.mWinAnimator.mSurfaceController != null) {
            token.getPendingTransaction().deferTransactionUntil(token.mSurfaceControl,
                    win.mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(),
                    win.getFrameNumber());
            win.getPendingTransaction().deferTransactionUntil(win.mSurfaceControl,
                    win.mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(),
                    win.getFrameNumber());
        }
    }

    public void dump(PrintWriter pw) {
        pw.print("{old="); pw.print(mOldRotation); pw.print(", new="); pw.print(mNewRotation);
        pw.print("}");
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        dump(new PrintWriter(sw));
        return "ForcedSeamlessRotator" + sw.toString();
    }
}
