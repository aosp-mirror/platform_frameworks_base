/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import java.util.ArrayList;

/**
 * An implementation of display list for OpenGL ES 2.0.
 */
class GLES20DisplayList extends DisplayList {
    // These lists ensure that any Bitmaps recorded by a DisplayList are kept alive as long
    // as the DisplayList is alive.  The Bitmaps are populated by the GLES20RecordingCanvas.
    final ArrayList<Bitmap> mBitmaps = new ArrayList<Bitmap>(5);

    private GLES20RecordingCanvas mCanvas;
    private boolean mValid;

    // Used for debugging
    private final String mName;

    // The native display list will be destroyed when this object dies.
    // DO NOT overwrite this reference once it is set.
    private DisplayListFinalizer mFinalizer;

    GLES20DisplayList(String name) {
        mName = name;
    }

    boolean hasNativeDisplayList() {
        return mValid && mFinalizer != null;
    }

    int getNativeDisplayList() {
        if (!mValid || mFinalizer == null) {
            throw new IllegalStateException("The display list is not valid.");
        }
        return mFinalizer.mNativeDisplayList;
    }

    @Override
    public HardwareCanvas start() {
        if (mCanvas != null) {
            throw new IllegalStateException("Recording has already started");
        }

        mValid = false;
        mCanvas = GLES20RecordingCanvas.obtain(this);
        mCanvas.start();
        return mCanvas;
    }

    @Override
    public void invalidate() {
        if (mCanvas != null) {
            mCanvas.recycle();
            mCanvas = null;
        }
        mValid = false;
    }

    @Override
    public void clear() {
        if (!mValid) {
            mBitmaps.clear();
        }
    }

    @Override
    public boolean isValid() {
        return mValid;
    }

    @Override
    public void end() {
        if (mCanvas != null) {
            if (mFinalizer != null) {
                mCanvas.end(mFinalizer.mNativeDisplayList);
            } else {
                mFinalizer = new DisplayListFinalizer(mCanvas.end(0));
                GLES20Canvas.setDisplayListName(mFinalizer.mNativeDisplayList, mName);
            }
            mCanvas.recycle();
            mCanvas = null;
            mValid = true;
        }
    }

    @Override
    public int getSize() {
        if (mFinalizer == null) return 0;
        return GLES20Canvas.getDisplayListSize(mFinalizer.mNativeDisplayList);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Native View Properties
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setCaching(boolean caching) {
        if (hasNativeDisplayList()) {
            nSetCaching(mFinalizer.mNativeDisplayList, caching);
        }
    }

    @Override
    public void setClipChildren(boolean clipChildren) {
        if (hasNativeDisplayList()) {
            nSetClipChildren(mFinalizer.mNativeDisplayList, clipChildren);
        }
    }

    @Override
    public void setStaticMatrix(Matrix matrix) {
        if (hasNativeDisplayList()) {
            nSetStaticMatrix(mFinalizer.mNativeDisplayList, matrix.native_instance);
        }
    }

    @Override
    public void setAnimationMatrix(Matrix matrix) {
        if (hasNativeDisplayList()) {
            nSetAnimationMatrix(mFinalizer.mNativeDisplayList,
                    (matrix != null) ? matrix.native_instance : 0);
        }
    }

    @Override
    public void setAlpha(float alpha) {
        if (hasNativeDisplayList()) {
            nSetAlpha(mFinalizer.mNativeDisplayList, alpha);
        }
    }

    @Override
    public void setHasOverlappingRendering(boolean hasOverlappingRendering) {
        if (hasNativeDisplayList()) {
            nSetHasOverlappingRendering(mFinalizer.mNativeDisplayList, hasOverlappingRendering);
        }
    }

    @Override
    public void setTranslationX(float translationX) {
        if (hasNativeDisplayList()) {
            nSetTranslationX(mFinalizer.mNativeDisplayList, translationX);
        }
    }

    @Override
    public void setTranslationY(float translationY) {
        if (hasNativeDisplayList()) {
            nSetTranslationY(mFinalizer.mNativeDisplayList, translationY);
        }
    }

    @Override
    public void setRotation(float rotation) {
        if (hasNativeDisplayList()) {
            nSetRotation(mFinalizer.mNativeDisplayList, rotation);
        }
    }

    @Override
    public void setRotationX(float rotationX) {
        if (hasNativeDisplayList()) {
            nSetRotationX(mFinalizer.mNativeDisplayList, rotationX);
        }
    }

    @Override
    public void setRotationY(float rotationY) {
        if (hasNativeDisplayList()) {
            nSetRotationY(mFinalizer.mNativeDisplayList, rotationY);
        }
    }

    @Override
    public void setScaleX(float scaleX) {
        if (hasNativeDisplayList()) {
            nSetScaleX(mFinalizer.mNativeDisplayList, scaleX);
        }
    }

    @Override
    public void setScaleY(float scaleY) {
        if (hasNativeDisplayList()) {
            nSetScaleY(mFinalizer.mNativeDisplayList, scaleY);
        }
    }

    @Override
    public void setTransformationInfo(float alpha, float translationX, float translationY,
            float rotation, float rotationX, float rotationY, float scaleX, float scaleY) {
        if (hasNativeDisplayList()) {
            nSetTransformationInfo(mFinalizer.mNativeDisplayList, alpha, translationX, translationY,
                    rotation, rotationX, rotationY, scaleX, scaleY);
        }
    }

    @Override
    public void setPivotX(float pivotX) {
        if (hasNativeDisplayList()) {
            nSetPivotX(mFinalizer.mNativeDisplayList, pivotX);
        }
    }

    @Override
    public void setPivotY(float pivotY) {
        if (hasNativeDisplayList()) {
            nSetPivotY(mFinalizer.mNativeDisplayList, pivotY);
        }
    }

    @Override
    public void setCameraDistance(float distance) {
        if (hasNativeDisplayList()) {
            nSetCameraDistance(mFinalizer.mNativeDisplayList, distance);
        }
    }

    @Override
    public void setLeft(int left) {
        if (hasNativeDisplayList()) {
            nSetLeft(mFinalizer.mNativeDisplayList, left);
        }
    }

    @Override
    public void setTop(int top) {
        if (hasNativeDisplayList()) {
            nSetTop(mFinalizer.mNativeDisplayList, top);
        }
    }

    @Override
    public void setRight(int right) {
        if (hasNativeDisplayList()) {
            nSetRight(mFinalizer.mNativeDisplayList, right);
        }
    }

    @Override
    public void setBottom(int bottom) {
        if (hasNativeDisplayList()) {
            nSetBottom(mFinalizer.mNativeDisplayList, bottom);
        }
    }

    @Override
    public void setLeftTop(int left, int top) {
        if (hasNativeDisplayList()) {
            nSetLeftTop(mFinalizer.mNativeDisplayList, left, top);
        }
    }

    @Override
    public void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (hasNativeDisplayList()) {
            nSetLeftTopRightBottom(mFinalizer.mNativeDisplayList, left, top, right, bottom);
        }
    }

    @Override
    public void offsetLeftRight(int offset) {
        if (hasNativeDisplayList()) {
            nOffsetLeftRight(mFinalizer.mNativeDisplayList, offset);
        }
    }

    @Override
    public void offsetTopBottom(int offset) {
        if (hasNativeDisplayList()) {
            nOffsetTopBottom(mFinalizer.mNativeDisplayList, offset);
        }
    }

    private static native void nOffsetTopBottom(int displayList, int offset);
    private static native void nOffsetLeftRight(int displayList, int offset);
    private static native void nSetLeftTopRightBottom(int displayList, int left, int top,
            int right, int bottom);
    private static native void nSetLeftTop(int displayList, int left, int top);
    private static native void nSetBottom(int displayList, int bottom);
    private static native void nSetRight(int displayList, int right);
    private static native void nSetTop(int displayList, int top);
    private static native void nSetLeft(int displayList, int left);
    private static native void nSetCameraDistance(int displayList, float distance);
    private static native void nSetPivotY(int displayList, float pivotY);
    private static native void nSetPivotX(int displayList, float pivotX);
    private static native void nSetCaching(int displayList, boolean caching);
    private static native void nSetClipChildren(int displayList, boolean clipChildren);
    private static native void nSetAlpha(int displayList, float alpha);
    private static native void nSetHasOverlappingRendering(int displayList,
            boolean hasOverlappingRendering);
    private static native void nSetTranslationX(int displayList, float translationX);
    private static native void nSetTranslationY(int displayList, float translationY);
    private static native void nSetRotation(int displayList, float rotation);
    private static native void nSetRotationX(int displayList, float rotationX);
    private static native void nSetRotationY(int displayList, float rotationY);
    private static native void nSetScaleX(int displayList, float scaleX);
    private static native void nSetScaleY(int displayList, float scaleY);
    private static native void nSetTransformationInfo(int displayList, float alpha,
            float translationX, float translationY, float rotation, float rotationX,
            float rotationY, float scaleX, float scaleY);
    private static native void nSetStaticMatrix(int displayList, int nativeMatrix);
    private static native void nSetAnimationMatrix(int displayList, int animationMatrix);


    ///////////////////////////////////////////////////////////////////////////
    // Finalization
    ///////////////////////////////////////////////////////////////////////////

    private static class DisplayListFinalizer {
        final int mNativeDisplayList;

        public DisplayListFinalizer(int nativeDisplayList) {
            mNativeDisplayList = nativeDisplayList;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                GLES20Canvas.destroyDisplayList(mNativeDisplayList);
            } finally {
                super.finalize();
            }
        }
    }
}
