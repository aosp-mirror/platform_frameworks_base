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

import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;

import android.graphics.Rect;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.function.Supplier;

/**
 * Four black surfaces put together to make a black frame.
 */
public class BlackFrame {
    static class BlackSurface {
        final int left;
        final int top;
        final int layer;
        final SurfaceControl surface;

        BlackSurface(SurfaceControl.Transaction transaction, int layer,
                int l, int t, int r, int b, DisplayContent dc,
                SurfaceControl surfaceControl) throws OutOfResourcesException {
            left = l;
            top = t;
            this.layer = layer;
            int w = r-l;
            int h = b-t;

            surface = dc.makeOverlay()
                    .setName("BlackSurface")
                    .setColorLayer()
                    .setParent(surfaceControl)
                    .setCallsite("BlackSurface")
                    .build();
            transaction.setWindowCrop(surface, w, h);
            transaction.setAlpha(surface, 1);
            transaction.setLayer(surface, layer);
            transaction.setPosition(surface, left, top);
            transaction.show(surface);
            ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                    "  BLACK %s: CREATE layer=%d", surface, layer);
        }
    }

    private final Rect mOuterRect;
    private final Rect mInnerRect;
    private final BlackSurface[] mBlackSurfaces = new BlackSurface[4];

    private final Supplier<SurfaceControl.Transaction> mTransactionFactory;

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

    public BlackFrame(Supplier<SurfaceControl.Transaction> factory, SurfaceControl.Transaction t,
            Rect outer, Rect inner, int layer, DisplayContent dc, boolean forceDefaultOrientation,
            SurfaceControl surfaceControl)
            throws OutOfResourcesException {
        boolean success = false;

        mTransactionFactory = factory;

        // TODO: Why do we use 4 surfaces instead of just one big one behind the screenshot?
        // b/68253229
        mOuterRect = new Rect(outer);
        mInnerRect = new Rect(inner);
        try {
            if (outer.top < inner.top) {
                mBlackSurfaces[0] = new BlackSurface(t, layer,
                        outer.left, outer.top, inner.right, inner.top, dc, surfaceControl);
            }
            if (outer.left < inner.left) {
                mBlackSurfaces[1] = new BlackSurface(t, layer,
                        outer.left, inner.top, inner.left, outer.bottom, dc, surfaceControl);
            }
            if (outer.bottom > inner.bottom) {
                mBlackSurfaces[2] = new BlackSurface(t, layer,
                        inner.left, inner.bottom, outer.right, outer.bottom, dc,
                        surfaceControl);
            }
            if (outer.right > inner.right) {
                mBlackSurfaces[3] = new BlackSurface(t, layer,
                        inner.right, outer.top, outer.right, inner.bottom, dc, surfaceControl);
            }
            success = true;
        } finally {
            if (!success) {
                kill();
            }
        }
    }

    public void kill() {
        SurfaceControl.Transaction t = mTransactionFactory.get();
        for (int i = 0; i < mBlackSurfaces.length; i++) {
            if (mBlackSurfaces[i] != null) {
                ProtoLog.i(WM_SHOW_SURFACE_ALLOC, "  BLACK %s: DESTROY", mBlackSurfaces[i].surface);
                t.remove(mBlackSurfaces[i].surface);
                mBlackSurfaces[i] = null;
            }
        }
        t.apply();
    }
}
