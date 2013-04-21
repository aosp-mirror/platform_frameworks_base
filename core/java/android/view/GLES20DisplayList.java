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
    // These lists ensure that any Bitmaps and DisplayLists recorded by a DisplayList are kept
    // alive as long as the DisplayList is alive.  The Bitmap and DisplayList lists
    // are populated by the GLES20RecordingCanvas during appropriate drawing calls and are
    // cleared at the start of a new drawing frame or when the view is detached from the window.
    final ArrayList<Bitmap> mBitmaps = new ArrayList<Bitmap>(5);
    final ArrayList<DisplayList> mChildDisplayLists = new ArrayList<DisplayList>();

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
    public HardwareCanvas start(int width, int height) {
        if (mCanvas != null) {
            throw new IllegalStateException("Recording has already started");
        }

        mValid = false;
        mCanvas = GLES20RecordingCanvas.obtain(this);
        mCanvas.start();

        mCanvas.setViewport(width, height);
        // The dirty rect should always be null for a display list
        mCanvas.onPreDraw(null);

        return mCanvas;
    }
    @Override
    public void clear() {
        clearDirty();

        if (mCanvas != null) {
            mCanvas.recycle();
            mCanvas = null;
        }
        mValid = false;

        mBitmaps.clear();
        mChildDisplayLists.clear();
    }

    @Override
    public void reset() {
        if (hasNativeDisplayList()) {
            nReset(mFinalizer.mNativeDisplayList);
        }
    }

    @Override
    public boolean isValid() {
        return mValid;
    }

    @Override
    public void end() {
        if (mCanvas != null) {
            mCanvas.onPostDraw();
            if (mFinalizer != null) {
                mCanvas.end(mFinalizer.mNativeDisplayList);
            } else {
                mFinalizer = new DisplayListFinalizer(mCanvas.end(0));
                nSetDisplayListName(mFinalizer.mNativeDisplayList, mName);
            }
            mCanvas.recycle();
            mCanvas = null;
            mValid = true;
        }
    }

    @Override
    public int getSize() {
        if (mFinalizer == null) return 0;
        return nGetDisplayListSize(mFinalizer.mNativeDisplayList);
    }

    private static native void nDestroyDisplayList(int displayList);
    private static native int nGetDisplayListSize(int displayList);
    private static native void nSetDisplayListName(int displayList, String name);

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
    public void setClipToBounds(boolean clipToBounds) {
        if (hasNativeDisplayList()) {
            nSetClipToBounds(mFinalizer.mNativeDisplayList, clipToBounds);
        }
    }

    @Override
    public void setMatrix(Matrix matrix) {
        if (hasNativeDisplayList()) {
            nSetStaticMatrix(mFinalizer.mNativeDisplayList, matrix.native_instance);
        }
    }

    @Override
    public Matrix getMatrix(Matrix matrix) {
        if (hasNativeDisplayList()) {
            nGetMatrix(mFinalizer.mNativeDisplayList, matrix.native_instance);
        }
        return matrix;
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
    public float getAlpha() {
        if (hasNativeDisplayList()) {
            return nGetAlpha(mFinalizer.mNativeDisplayList);
        }
        return 1.0f;
    }

    @Override
    public void setHasOverlappingRendering(boolean hasOverlappingRendering) {
        if (hasNativeDisplayList()) {
            nSetHasOverlappingRendering(mFinalizer.mNativeDisplayList, hasOverlappingRendering);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        //noinspection SimplifiableIfStatement
        if (hasNativeDisplayList()) {
            return nHasOverlappingRendering(mFinalizer.mNativeDisplayList);
        }
        return true;
    }

    @Override
    public void setTranslationX(float translationX) {
        if (hasNativeDisplayList()) {
            nSetTranslationX(mFinalizer.mNativeDisplayList, translationX);
        }
    }

    @Override
    public float getTranslationX() {
        if (hasNativeDisplayList()) {
            return nGetTranslationX(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setTranslationY(float translationY) {
        if (hasNativeDisplayList()) {
            nSetTranslationY(mFinalizer.mNativeDisplayList, translationY);
        }
    }

    @Override
    public float getTranslationY() {
        if (hasNativeDisplayList()) {
            return nGetTranslationY(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setRotation(float rotation) {
        if (hasNativeDisplayList()) {
            nSetRotation(mFinalizer.mNativeDisplayList, rotation);
        }
    }

    @Override
    public float getRotation() {
        if (hasNativeDisplayList()) {
            return nGetRotation(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setRotationX(float rotationX) {
        if (hasNativeDisplayList()) {
            nSetRotationX(mFinalizer.mNativeDisplayList, rotationX);
        }
    }

    @Override
    public float getRotationX() {
        if (hasNativeDisplayList()) {
            return nGetRotationX(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setRotationY(float rotationY) {
        if (hasNativeDisplayList()) {
            nSetRotationY(mFinalizer.mNativeDisplayList, rotationY);
        }
    }

    @Override
    public float getRotationY() {
        if (hasNativeDisplayList()) {
            return nGetRotationY(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setScaleX(float scaleX) {
        if (hasNativeDisplayList()) {
            nSetScaleX(mFinalizer.mNativeDisplayList, scaleX);
        }
    }

    @Override
    public float getScaleX() {
        if (hasNativeDisplayList()) {
            return nGetScaleX(mFinalizer.mNativeDisplayList);
        }
        return 1.0f;
    }

    @Override
    public void setScaleY(float scaleY) {
        if (hasNativeDisplayList()) {
            nSetScaleY(mFinalizer.mNativeDisplayList, scaleY);
        }
    }

    @Override
    public float getScaleY() {
        if (hasNativeDisplayList()) {
            return nGetScaleY(mFinalizer.mNativeDisplayList);
        }
        return 1.0f;
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
    public float getPivotX() {
        if (hasNativeDisplayList()) {
            return nGetPivotX(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setPivotY(float pivotY) {
        if (hasNativeDisplayList()) {
            nSetPivotY(mFinalizer.mNativeDisplayList, pivotY);
        }
    }

    @Override
    public float getPivotY() {
        if (hasNativeDisplayList()) {
            return nGetPivotY(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setCameraDistance(float distance) {
        if (hasNativeDisplayList()) {
            nSetCameraDistance(mFinalizer.mNativeDisplayList, distance);
        }
    }

    @Override
    public float getCameraDistance() {
        if (hasNativeDisplayList()) {
            return nGetCameraDistance(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setLeft(int left) {
        if (hasNativeDisplayList()) {
            nSetLeft(mFinalizer.mNativeDisplayList, left);
        }
    }

    @Override
    public float getLeft() {
        if (hasNativeDisplayList()) {
            return nGetLeft(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setTop(int top) {
        if (hasNativeDisplayList()) {
            nSetTop(mFinalizer.mNativeDisplayList, top);
        }
    }

    @Override
    public float getTop() {
        if (hasNativeDisplayList()) {
            return nGetTop(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setRight(int right) {
        if (hasNativeDisplayList()) {
            nSetRight(mFinalizer.mNativeDisplayList, right);
        }
    }

    @Override
    public float getRight() {
        if (hasNativeDisplayList()) {
            return nGetRight(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setBottom(int bottom) {
        if (hasNativeDisplayList()) {
            nSetBottom(mFinalizer.mNativeDisplayList, bottom);
        }
    }

    @Override
    public float getBottom() {
        if (hasNativeDisplayList()) {
            return nGetBottom(mFinalizer.mNativeDisplayList);
        }
        return 0.0f;
    }

    @Override
    public void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (hasNativeDisplayList()) {
            nSetLeftTopRightBottom(mFinalizer.mNativeDisplayList, left, top, right, bottom);
        }
    }

    @Override
    public void offsetLeftAndRight(float offset) {
        if (hasNativeDisplayList()) {
            nOffsetLeftAndRight(mFinalizer.mNativeDisplayList, offset);
        }
    }

    @Override
    public void offsetTopAndBottom(float offset) {
        if (hasNativeDisplayList()) {
            nOffsetTopAndBottom(mFinalizer.mNativeDisplayList, offset);
        }
    }

    private static native void nReset(int displayList);
    private static native void nOffsetTopAndBottom(int displayList, float offset);
    private static native void nOffsetLeftAndRight(int displayList, float offset);
    private static native void nSetLeftTopRightBottom(int displayList, int left, int top,
            int right, int bottom);
    private static native void nSetBottom(int displayList, int bottom);
    private static native void nSetRight(int displayList, int right);
    private static native void nSetTop(int displayList, int top);
    private static native void nSetLeft(int displayList, int left);
    private static native void nSetCameraDistance(int displayList, float distance);
    private static native void nSetPivotY(int displayList, float pivotY);
    private static native void nSetPivotX(int displayList, float pivotX);
    private static native void nSetCaching(int displayList, boolean caching);
    private static native void nSetClipToBounds(int displayList, boolean clipToBounds);
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

    private static native boolean nHasOverlappingRendering(int displayList);
    private static native void nGetMatrix(int displayList, int matrix);
    private static native float nGetAlpha(int displayList);
    private static native float nGetLeft(int displayList);
    private static native float nGetTop(int displayList);
    private static native float nGetRight(int displayList);
    private static native float nGetBottom(int displayList);
    private static native float nGetCameraDistance(int displayList);
    private static native float nGetScaleX(int displayList);
    private static native float nGetScaleY(int displayList);
    private static native float nGetTranslationX(int displayList);
    private static native float nGetTranslationY(int displayList);
    private static native float nGetRotation(int displayList);
    private static native float nGetRotationX(int displayList);
    private static native float nGetRotationY(int displayList);
    private static native float nGetPivotX(int displayList);
    private static native float nGetPivotY(int displayList);

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
                nDestroyDisplayList(mNativeDisplayList);
            } finally {
                super.finalize();
            }
        }
    }
}
