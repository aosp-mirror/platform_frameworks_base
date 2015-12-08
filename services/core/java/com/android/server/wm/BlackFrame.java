/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.graphics.PixelFormat.OPAQUE;
import static android.view.SurfaceControl.FX_SURFACE_DIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SURFACE_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import java.io.PrintWriter;

import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

/**
 * Four black surfaces put together to make a black frame.
 */
public class BlackFrame {
    class BlackSurface {
        final int left;
        final int top;
        final int layer;
        final SurfaceControl surface;

        BlackSurface(SurfaceSession session, int layer, int l, int t, int r, int b, int layerStack)
                throws OutOfResourcesException {
            left = l;
            top = t;
            this.layer = layer;
            int w = r-l;
            int h = b-t;

            if (DEBUG_SURFACE_TRACE) {
                surface = new WindowSurfaceController.SurfaceTrace(session, "BlackSurface("
                        + l + ", " + t + ")",
                        w, h, OPAQUE, FX_SURFACE_DIM | SurfaceControl.HIDDEN);
            } else {
                surface = new SurfaceControl(session, "BlackSurface",
                        w, h, OPAQUE, FX_SURFACE_DIM | SurfaceControl.HIDDEN);
            }

            surface.setAlpha(1);
            surface.setLayerStack(layerStack);
            surface.setLayer(layer);
            surface.show();
            if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) Slog.i(TAG_WM,
                            "  BLACK " + surface + ": CREATE layer=" + layer);
        }

        void setAlpha(float alpha) {
            surface.setAlpha(alpha);
        }

        void setMatrix(Matrix matrix) {
            mTmpMatrix.setTranslate(left, top);
            mTmpMatrix.postConcat(matrix);
            mTmpMatrix.getValues(mTmpFloats);
            surface.setPosition(mTmpFloats[Matrix.MTRANS_X],
                    mTmpFloats[Matrix.MTRANS_Y]);
            surface.setMatrix(
                    mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                    mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);
            if (false) {
                Slog.i(TAG_WM, "Black Surface @ (" + left + "," + top + "): ("
                        + mTmpFloats[Matrix.MTRANS_X] + ","
                        + mTmpFloats[Matrix.MTRANS_Y] + ") matrix=["
                        + mTmpFloats[Matrix.MSCALE_X] + ","
                        + mTmpFloats[Matrix.MSCALE_Y] + "]["
                        + mTmpFloats[Matrix.MSKEW_X] + ","
                        + mTmpFloats[Matrix.MSKEW_Y] + "]");
            }
        }

        void clearMatrix() {
            surface.setMatrix(1, 0, 0, 1);
        }
    }

    final Rect mOuterRect;
    final Rect mInnerRect;
    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpFloats = new float[9];
    final BlackSurface[] mBlackSurfaces = new BlackSurface[4];

    final boolean mForceDefaultOrientation;

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("Outer: "); mOuterRect.printShortString(pw);
                pw.print(" / Inner: "); mInnerRect.printShortString(pw);
                pw.println();
        for (int i=0; i<mBlackSurfaces.length; i++) {
            BlackSurface bs = mBlackSurfaces[i];
            pw.print(prefix); pw.print("#"); pw.print(i);
                    pw.print(": "); pw.print(bs.surface);
                    pw.print(" left="); pw.print(bs.left);
                    pw.print(" top="); pw.println(bs.top);
        }
    }

    public BlackFrame(SurfaceSession session, Rect outer, Rect inner, int layer, int layerStack,
            boolean forceDefaultOrientation) throws OutOfResourcesException {
        boolean success = false;

        mForceDefaultOrientation = forceDefaultOrientation;

        mOuterRect = new Rect(outer);
        mInnerRect = new Rect(inner);
        try {
            if (outer.top < inner.top) {
                mBlackSurfaces[0] = new BlackSurface(session, layer,
                        outer.left, outer.top, inner.right, inner.top, layerStack);
            }
            if (outer.left < inner.left) {
                mBlackSurfaces[1] = new BlackSurface(session, layer,
                        outer.left, inner.top, inner.left, outer.bottom, layerStack);
            }
            if (outer.bottom > inner.bottom) {
                mBlackSurfaces[2] = new BlackSurface(session, layer,
                        inner.left, inner.bottom, outer.right, outer.bottom, layerStack);
            }
            if (outer.right > inner.right) {
                mBlackSurfaces[3] = new BlackSurface(session, layer,
                        inner.right, outer.top, outer.right, inner.bottom, layerStack);
            }
            success = true;
        } finally {
            if (!success) {
                kill();
            }
        }
    }

    public void kill() {
        if (mBlackSurfaces != null) {
            for (int i=0; i<mBlackSurfaces.length; i++) {
                if (mBlackSurfaces[i] != null) {
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) Slog.i(TAG_WM,
                            "  BLACK " + mBlackSurfaces[i].surface + ": DESTROY");
                    mBlackSurfaces[i].surface.destroy();
                    mBlackSurfaces[i] = null;
                }
            }
        }
    }

    public void hide() {
        if (mBlackSurfaces != null) {
            for (int i=0; i<mBlackSurfaces.length; i++) {
                if (mBlackSurfaces[i] != null) {
                    mBlackSurfaces[i].surface.hide();
                }
            }
        }
    }

    public void setAlpha(float alpha) {
        for (int i=0; i<mBlackSurfaces.length; i++) {
            if (mBlackSurfaces[i] != null) {
                mBlackSurfaces[i].setAlpha(alpha);
            }
        }
    }

    public void setMatrix(Matrix matrix) {
        for (int i=0; i<mBlackSurfaces.length; i++) {
            if (mBlackSurfaces[i] != null) {
                mBlackSurfaces[i].setMatrix(matrix);
            }
        }
    }

    public void clearMatrix() {
        for (int i=0; i<mBlackSurfaces.length; i++) {
            if (mBlackSurfaces[i] != null) {
                mBlackSurfaces[i].clearMatrix();
            }
        }
    }
}
