/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.graphics.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Xfermode;

/**
 * @hide
 */
public class BoundaryPatch {
    private Paint   mPaint;
    private Bitmap  mTexture;
    private int     mRows;
    private int     mCols;
    private float[] mCubicPoints;
    private boolean mDirty;
    // these are the computed output of the native code
    private float[] mVerts;
    private short[] mIndices;

    public BoundaryPatch() {
        mRows = mCols = 2;  // default minimum
        mCubicPoints = new float[24];
        mPaint = new Paint();
        mPaint.setDither(true);
        mPaint.setFilterBitmap(true);
        mDirty = true;
    }

    /**
     * Set the boundary to be 4 cubics. This takes a single array of floats,
     * and picks up the 12 pairs starting at offset, and treats them as
     * the x,y coordinates of the cubic control points. The points wrap around
     * a patch, as follows. For documentation purposes, pts[i] will mean the
     * x,y pair of floats, as if pts[] were an array of "points".
     *
     * Top: pts[0..3]
     * Right: pts[3..6]
     * Bottom: pts[6..9]
     * Right: pts[9..11], pts[0]
     *
     * The coordinates are copied from the input array, so subsequent changes
     * to pts[] will not be reflected in the boundary.
     *
     * @param pts The src array of x,y pairs for the boundary cubics
     * @param offset The index into pts of the first pair
     * @param rows The number of points across to approximate the boundary.
     *             Must be >= 2, though very large values may slow down drawing
     * @param cols The number of points down to approximate the boundary.
     *             Must be >= 2, though very large values may slow down drawing
     */
    public void setCubicBoundary(float[] pts, int offset, int rows, int cols) {
        if (rows < 2 || cols < 2) {
            throw new RuntimeException("rows and cols must be >= 2");
        }
        System.arraycopy(pts, offset, mCubicPoints, 0, 24);
        if (mRows != rows || mCols != cols) {
            mRows = rows;
            mCols = cols;
        }
        mDirty = true;
    }

    /**
     * Reference a bitmap texture to be mapped onto the patch.
     */
    public void setTexture(Bitmap texture) {
        if (mTexture != texture) {
            if (mTexture == null ||
                    mTexture.getWidth() != texture.getWidth() ||
                    mTexture.getHeight() != texture.getHeight()) {
                // need to recompute texture coordinates
                mDirty = true;
            }
            mTexture = texture;
            mPaint.setShader(new BitmapShader(texture,
                                              Shader.TileMode.CLAMP,
                                              Shader.TileMode.CLAMP));
        }
    }

    /**
     * Return the paint flags for the patch
     */
    public int getPaintFlags() {
        return mPaint.getFlags();
    }

    /**
     * Set the paint flags for the patch
     */
    public void setPaintFlags(int flags) {
        mPaint.setFlags(flags);
    }

    /**
     * Set the xfermode for the patch
     */
    public void setXfermode(Xfermode mode) {
        mPaint.setXfermode(mode);
    }

    /**
     * Set the alpha for the patch
     */
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    /**
     * Draw the patch onto the canvas.
     *
     * setCubicBoundary() and setTexture() must be called before drawing.
     */
    public void draw(Canvas canvas) {
        if (mDirty) {
            buildCache();
            mDirty = false;
        }

        // cut the count in half, since mVerts.length is really the length of
        // the verts[] and tex[] arrays combined
        // (tex[] are stored after verts[])
        int vertCount = mVerts.length >> 1;
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertCount,
                            mVerts, 0, mVerts, vertCount, null, 0,
                            mIndices, 0, mIndices.length,
                            mPaint);
    }

    private void buildCache() {
        // we need mRows * mCols points, for verts and another set for textures
        // so *2 for going from points -> floats, and *2 for verts and textures
        int vertCount = mRows * mCols * 4;
        if (mVerts == null || mVerts.length != vertCount) {
            mVerts = new float[vertCount];
        }

        int indexCount = (mRows - 1) * (mCols - 1) * 6;
        if (mIndices == null || mIndices.length != indexCount) {
            mIndices = new short[indexCount];
        }

        nativeComputeCubicPatch(mCubicPoints,
                                mTexture.getWidth(), mTexture.getHeight(),
                                mRows, mCols, mVerts, mIndices);
    }

    private static native
    void nativeComputeCubicPatch(float[] cubicPoints,
                                 int texW, int texH, int rows, int cols,
                                 float[] verts, short[] indices);
}

