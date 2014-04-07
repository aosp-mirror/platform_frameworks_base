/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.transition;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Property;

/**
 * Used in MoveImage to mock an ImageView as a Drawable to be scaled in the scene root Overlay.
 * @hide
 */
class MatrixClippedDrawable extends Drawable implements Drawable.Callback {
    private static final String TAG = "MatrixClippedDrawable";

    private ClippedMatrixState mClippedMatrixState;

    public static final Property<MatrixClippedDrawable, Rect> CLIP_PROPERTY
            = new Property<MatrixClippedDrawable, Rect>(Rect.class, "clipRect") {

        @Override
        public Rect get(MatrixClippedDrawable object) {
            return object.getClipRect();
        }

        @Override
        public void set(MatrixClippedDrawable object, Rect value) {
            object.setClipRect(value);
        }
    };

    public static final Property<MatrixClippedDrawable, Matrix> MATRIX_PROPERTY
            = new Property<MatrixClippedDrawable, Matrix>(Matrix.class, "matrix") {
        @Override
        public void set(MatrixClippedDrawable object, Matrix value) {
            object.setMatrix(value);
        }

        @Override
        public Matrix get(MatrixClippedDrawable object) {
            return object.getMatrix();
        }
    };

    public MatrixClippedDrawable(Drawable drawable) {
        this(null, null);

        mClippedMatrixState.mDrawable = drawable;

        if (drawable != null) {
            drawable.setCallback(this);
        }
    }

    public void setMatrix(Matrix matrix) {
        if (matrix == null) {
            mClippedMatrixState.mMatrix = null;
        } else {
            if (mClippedMatrixState.mMatrix == null) {
                mClippedMatrixState.mMatrix = new Matrix();
            }
            mClippedMatrixState.mMatrix.set(matrix);
        }
        invalidateSelf();
    }

    public Matrix getMatrix() {
        return mClippedMatrixState.mMatrix;
    }

    public Rect getClipRect() {
        return mClippedMatrixState.mClipRect;
    }

    public void setClipRect(Rect clipRect) {
        if (clipRect == null) {
            if (mClippedMatrixState.mClipRect != null) {
                mClippedMatrixState.mClipRect = null;
                invalidateSelf();
            }
        } else {
            if (mClippedMatrixState.mClipRect == null) {
                mClippedMatrixState.mClipRect = new Rect(clipRect);
            } else {
                mClippedMatrixState.mClipRect.set(clipRect);
            }
            invalidateSelf();
        }
    }

    // overrides from Drawable.Callback

    public void invalidateDrawable(Drawable who) {
        final Drawable.Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateDrawable(this);
        }
    }

    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        final Drawable.Callback callback = getCallback();
        if (callback != null) {
            callback.scheduleDrawable(this, what, when);
        }
    }

    public void unscheduleDrawable(Drawable who, Runnable what) {
        final Drawable.Callback callback = getCallback();
        if (callback != null) {
            callback.unscheduleDrawable(this, what);
        }
    }

    // overrides from Drawable

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mClippedMatrixState.mChangingConfigurations
                | mClippedMatrixState.mDrawable.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(Rect padding) {
        // XXX need to adjust padding!
        return mClippedMatrixState.mDrawable.getPadding(padding);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mClippedMatrixState.mDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public void setAlpha(int alpha) {
        mClippedMatrixState.mDrawable.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mClippedMatrixState.mDrawable.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mClippedMatrixState.mDrawable.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return mClippedMatrixState.mDrawable.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return mClippedMatrixState.mDrawable.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return mClippedMatrixState.mDrawable.setState(state);
    }

    @Override
    protected boolean onLevelChange(int level) {
        mClippedMatrixState.mDrawable.setLevel(level);
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.setBounds(bounds);
        if (mClippedMatrixState.mMatrix == null) {
            mClippedMatrixState.mDrawable.setBounds(bounds);
        } else {
            int drawableWidth = mClippedMatrixState.mDrawable.getIntrinsicWidth();
            int drawableHeight = mClippedMatrixState.mDrawable.getIntrinsicHeight();
            mClippedMatrixState.mDrawable.setBounds(bounds.left, bounds.top,
                    drawableWidth + bounds.left, drawableHeight + bounds.top);
        }
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int left = bounds.left;
        int top = bounds.top;
        int saveCount = canvas.getSaveCount();
        canvas.save();
        if (mClippedMatrixState.mClipRect != null) {
            canvas.clipRect(mClippedMatrixState.mClipRect);
        } else {
            canvas.clipRect(bounds);
        }

        if (mClippedMatrixState != null && !mClippedMatrixState.mMatrix.isIdentity()) {
            canvas.translate(left, top);
            canvas.concat(mClippedMatrixState.mMatrix);
            canvas.translate(-left, -top);
        }
        mClippedMatrixState.mDrawable.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    @Override
    public int getIntrinsicWidth() {
        return mClippedMatrixState.mDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mClippedMatrixState.mDrawable.getIntrinsicHeight();
    }

    @Override
    public Drawable.ConstantState getConstantState() {
        if (mClippedMatrixState.canConstantState()) {
            mClippedMatrixState.mChangingConfigurations = getChangingConfigurations();
            return mClippedMatrixState;
        }
        return null;
    }

    final static class ClippedMatrixState extends Drawable.ConstantState {
        Drawable mDrawable;
        Matrix mMatrix;
        Rect mClipRect;

        private boolean mCheckedConstantState;
        private boolean mCanConstantState;
        int mChangingConfigurations;

        ClippedMatrixState(ClippedMatrixState orig, MatrixClippedDrawable owner, Resources res) {
            if (orig != null) {
                if (res != null) {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable(res);
                } else {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable();
                }
                mDrawable.setCallback(owner);
                mCheckedConstantState = mCanConstantState = true;
                if (orig.mMatrix != null) {
                    mMatrix = new Matrix(orig.mMatrix);
                }
                if (orig.mClipRect != null) {
                    mClipRect = new Rect(orig.mClipRect);
                }
            }
        }

        @Override
        public Drawable newDrawable() {
            return new MatrixClippedDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new MatrixClippedDrawable(this, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        boolean canConstantState() {
            if (!mCheckedConstantState) {
                mCanConstantState = mDrawable.getConstantState() != null;
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }
    }

    private MatrixClippedDrawable(ClippedMatrixState state, Resources res) {
        mClippedMatrixState = new ClippedMatrixState(state, this, res);
    }

}
