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
import android.os.IBinder;
import android.view.DisplayInfo;
import android.view.Surface.Rotation;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.server.wm.utils.CoordinateTransforms;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Helper class for seamless rotation.
 *
 * Works by transforming the {@link WindowState} back into the old display rotation.
 */
public class SeamlessRotator {

    private final Matrix mTransform = new Matrix();
    private final float[] mFloat9 = new float[9];
    private final int mOldRotation;
    private final int mNewRotation;
    /* If the seamless rotator is used to rotate part of the hierarchy, then provide a transform
     * hint based on the display orientation if the entire display was rotated. When the display
     * orientation matches the hierarchy orientation, the fixed transform hint will be removed.
     * This will prevent allocating different buffer sizes by the graphic producers when the
     * orientation of a layer changes.
     */
    private final boolean mApplyFixedTransformHint;
    private final int mFixedTransformHint;


    public SeamlessRotator(@Rotation int oldRotation, @Rotation int newRotation, DisplayInfo info,
            boolean applyFixedTransformationHint) {
        mOldRotation = oldRotation;
        mNewRotation = newRotation;
        mApplyFixedTransformHint = applyFixedTransformationHint;
        mFixedTransformHint = oldRotation;
        final boolean flipped = info.rotation == ROTATION_90 || info.rotation == ROTATION_270;
        final int pH = flipped ? info.logicalWidth : info.logicalHeight;
        final int pW = flipped ? info.logicalHeight : info.logicalWidth;
        // Initialize transform matrix by physical size.
        final Matrix tmp = new Matrix();
        CoordinateTransforms.transformLogicalToPhysicalCoordinates(oldRotation, pW, pH, mTransform);
        CoordinateTransforms.transformPhysicalToLogicalCoordinates(newRotation, pW, pH, tmp);
        mTransform.postConcat(tmp);
    }

    /**
     * Applies a transform to the {@link WindowContainer} surface that undoes the effect of the
     * global display rotation.
     */
    public void unrotate(Transaction transaction, WindowContainer win) {
        transaction.setMatrix(win.getSurfaceControl(), mTransform, mFloat9);
        // WindowState sets the position of the window so transform the position and update it.
        final float[] winSurfacePos = {win.mLastSurfacePosition.x, win.mLastSurfacePosition.y};
        mTransform.mapPoints(winSurfacePos);
        transaction.setPosition(win.getSurfaceControl(), winSurfacePos[0], winSurfacePos[1]);
        if (mApplyFixedTransformHint) {
            transaction.setFixedTransformHint(win.mSurfaceControl, mFixedTransformHint);
        }
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
     * Removes the transform and sets the previously known surface position for {@link WindowState}
     * surface that undoes the effect of the global display rotation.
     *
     * Removing the transform and the result of the {@link WindowState} layout are both tied to the
     * {@link WindowState} next frame, such that they apply at the same time the client draws the
     * window in the new orientation.
     */
    void finish(Transaction t, WindowContainer win) {
        if (win.mSurfaceControl == null || !win.mSurfaceControl.isValid()) {
            return;
        }

        mTransform.reset();
        t.setMatrix(win.mSurfaceControl, mTransform, mFloat9);
        t.setPosition(win.mSurfaceControl, win.mLastSurfacePosition.x, win.mLastSurfacePosition.y);
        if (mApplyFixedTransformHint) {
            t.unsetFixedTransformHint(win.mSurfaceControl);
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
