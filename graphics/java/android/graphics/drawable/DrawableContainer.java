/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics.drawable;

import android.content.res.Resources;
import android.graphics.*;

public class DrawableContainer extends Drawable implements Drawable.Callback {

    /**
     * To be proper, we should have a getter for dither (and alpha, etc.)
     * so that proxy classes like this can save/restore their delegates'
     * values, but we don't have getters. Since we do have setters
     * (e.g. setDither), which this proxy forwards on, we have to have some
     * default/initial setting.
     *
     * The initial setting for dither is now true, since it almost always seems
     * to improve the quality at negligible cost.
     */
    private static final boolean DEFAULT_DITHER = true;
    private DrawableContainerState mDrawableContainerState;
    private Drawable mCurrDrawable;
    private int mAlpha = 0xFF;
    private ColorFilter mColorFilter;

    private int mCurIndex = -1;
    private boolean mMutated;

    // overrides from Drawable

    @Override
    public void draw(Canvas canvas) {
        if (mCurrDrawable != null) {
            mCurrDrawable.draw(canvas);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mDrawableContainerState.mChangingConfigurations
                | mDrawableContainerState.mChildrenChangingConfigurations;
    }
    
    @Override
    public boolean getPadding(Rect padding) {
        final Rect r = mDrawableContainerState.getConstantPadding();
        if (r != null) {
            padding.set(r);
            return true;
        }
        if (mCurrDrawable != null) {
            return mCurrDrawable.getPadding(padding);
        } else {
            return super.getPadding(padding);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            if (mCurrDrawable != null) {
                mCurrDrawable.setAlpha(alpha);
            }
        }
    }

    @Override
    public void setDither(boolean dither) {
        if (mDrawableContainerState.mDither != dither) {
            mDrawableContainerState.mDither = dither;
            if (mCurrDrawable != null) {
                mCurrDrawable.setDither(mDrawableContainerState.mDither);
            }
        }
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (mColorFilter != cf) {
            mColorFilter = cf;
            if (mCurrDrawable != null) {
                mCurrDrawable.setColorFilter(cf);
            }
        }
    }
    
    @Override
    protected void onBoundsChange(Rect bounds) {
        if (mCurrDrawable != null) {
            mCurrDrawable.setBounds(bounds);
        }
    }
    
    @Override
    public boolean isStateful() {
        return mDrawableContainerState.isStateful();
    }
    
    @Override
    protected boolean onStateChange(int[] state) {
        if (mCurrDrawable != null) {
            return mCurrDrawable.setState(state);
        }
        return false;
    }

    @Override
    protected boolean onLevelChange(int level) {
        if (mCurrDrawable != null) {
            return mCurrDrawable.setLevel(level);
        }
        return false;
    }

    @Override
    public int getIntrinsicWidth() {
        if (mDrawableContainerState.isConstantSize()) {
            return mDrawableContainerState.getConstantWidth();
        }
        return mCurrDrawable != null ? mCurrDrawable.getIntrinsicWidth() : -1;
    }

    @Override
    public int getIntrinsicHeight() {
        if (mDrawableContainerState.isConstantSize()) {
            return mDrawableContainerState.getConstantHeight();
        }
        return mCurrDrawable != null ? mCurrDrawable.getIntrinsicHeight() : -1;
    }
    
    @Override
    public int getMinimumWidth() {
        if (mDrawableContainerState.isConstantSize()) {
            return mDrawableContainerState.getConstantMinimumWidth();
        }
        return mCurrDrawable != null ? mCurrDrawable.getMinimumWidth() : 0;
    }

    @Override
    public int getMinimumHeight() {
        if (mDrawableContainerState.isConstantSize()) {
            return mDrawableContainerState.getConstantMinimumHeight();
        }
        return mCurrDrawable != null ? mCurrDrawable.getMinimumHeight() : 0;
    }

    public void invalidateDrawable(Drawable who)
    {
        if (who == mCurrDrawable && mCallback != null) {
            mCallback.invalidateDrawable(this);
        }
    }

    public void scheduleDrawable(Drawable who, Runnable what, long when)
    {
        if (who == mCurrDrawable && mCallback != null) {
            mCallback.scheduleDrawable(this, what, when);
        }
    }

    public void unscheduleDrawable(Drawable who, Runnable what)
    {
        if (who == mCurrDrawable && mCallback != null) {
            mCallback.unscheduleDrawable(this, what);
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (mCurrDrawable != null) {
            mCurrDrawable.setVisible(visible, restart);
        }
        return changed;
    }

    @Override
    public int getOpacity() {
        return mCurrDrawable == null || !mCurrDrawable.isVisible() ? PixelFormat.TRANSPARENT :
                mDrawableContainerState.getOpacity();
    }

    public boolean selectDrawable(int idx)
    {
        if (idx == mCurIndex) {
            return false;
        }
        if (idx >= 0 && idx < mDrawableContainerState.mNumChildren) {
            Drawable d = mDrawableContainerState.mDrawables[idx];
            if (mCurrDrawable != null) {
                mCurrDrawable.setVisible(false, false);
            }
            mCurrDrawable = d;
            mCurIndex = idx;
            if (d != null) {
                d.setVisible(isVisible(), true);
                d.setAlpha(mAlpha);
                d.setDither(mDrawableContainerState.mDither);
                d.setColorFilter(mColorFilter);
                d.setState(getState());
                d.setLevel(getLevel());
                d.setBounds(getBounds());
            }
        } else {
            if (mCurrDrawable != null) {
                mCurrDrawable.setVisible(false, false);
            }
            mCurrDrawable = null;
            mCurIndex = -1;
        }
        invalidateSelf();
        return true;
    }
    
    @Override
    public Drawable getCurrent() {
        return mCurrDrawable;
    }

    @Override
    public ConstantState getConstantState() {
        if (mDrawableContainerState.canConstantState()) {
            mDrawableContainerState.mChangingConfigurations = getChangingConfigurations();
            return mDrawableContainerState;
        }
        return null;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            final int N = mDrawableContainerState.getChildCount();
            final Drawable[] drawables = mDrawableContainerState.getChildren();
            for (int i = 0; i < N; i++) {
                if (drawables[i] != null) drawables[i].mutate();
            }
            mMutated = true;
        }
        return this;
    }

    public abstract static class DrawableContainerState extends ConstantState {
        final DrawableContainer mOwner;

        int         mChangingConfigurations;
        int         mChildrenChangingConfigurations;
        
        Drawable[]  mDrawables;
        int         mNumChildren;

        boolean     mVariablePadding = false;
        Rect        mConstantPadding = null;

        boolean     mConstantSize = false;
        boolean     mComputedConstantSize = false;
        int         mConstantWidth;
        int         mConstantHeight;
        int         mConstantMinimumWidth;
        int         mConstantMinimumHeight;

        boolean     mHaveOpacity = false;
        int         mOpacity;

        boolean     mHaveStateful = false;
        boolean     mStateful;

        boolean     mCheckedConstantState;
        boolean     mCanConstantState;

        boolean     mPaddingChecked = false;
        
        boolean     mDither = DEFAULT_DITHER;        

        DrawableContainerState(DrawableContainerState orig, DrawableContainer owner,
                Resources res) {
            mOwner = owner;

            if (orig != null) {
                mChangingConfigurations = orig.mChangingConfigurations;
                mChildrenChangingConfigurations = orig.mChildrenChangingConfigurations;
                
                final Drawable[] origDr = orig.mDrawables;

                mDrawables = new Drawable[origDr.length];
                mNumChildren = orig.mNumChildren;

                final int N = mNumChildren;
                for (int i=0; i<N; i++) {
                    if (res != null) {
                        mDrawables[i] = origDr[i].getConstantState().newDrawable(res);
                    } else {
                        mDrawables[i] = origDr[i].getConstantState().newDrawable();
                    }
                    mDrawables[i].setCallback(owner);
                }

                mCheckedConstantState = mCanConstantState = true;
                mVariablePadding = orig.mVariablePadding;
                if (orig.mConstantPadding != null) {
                    mConstantPadding = new Rect(orig.mConstantPadding);
                }
                mConstantSize = orig.mConstantSize;
                mComputedConstantSize = orig.mComputedConstantSize;
                mConstantWidth = orig.mConstantWidth;
                mConstantHeight = orig.mConstantHeight;
                
                mHaveOpacity = orig.mHaveOpacity;
                mOpacity = orig.mOpacity;
                mHaveStateful = orig.mHaveStateful;
                mStateful = orig.mStateful;
                
                mDither = orig.mDither;

            } else {
                mDrawables = new Drawable[10];
                mNumChildren = 0;
                mCheckedConstantState = mCanConstantState = false;
            }
        }
        
        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        public final int addChild(Drawable dr) {
            final int pos = mNumChildren;

            if (pos >= mDrawables.length) {
                growArray(pos, pos+10);
            }

            dr.setVisible(false, true);
            dr.setCallback(mOwner);

            mDrawables[pos] = dr;
            mNumChildren++;
            mChildrenChangingConfigurations |= dr.getChangingConfigurations();
            mHaveOpacity = false;
            mHaveStateful = false;

            mConstantPadding = null;
            mPaddingChecked = false;
            mComputedConstantSize = false;

            return pos;
        }

        public final int getChildCount() {
            return mNumChildren;
        }

        public final Drawable[] getChildren() {
            return mDrawables;
        }

        /** A boolean value indicating whether to use the maximum padding value of 
          * all frames in the set (false), or to use the padding value of the frame 
          * being shown (true). Default value is false. 
          */
        public final void setVariablePadding(boolean variable) {
            mVariablePadding = variable;
        }

        public final Rect getConstantPadding() {
            if (mVariablePadding) {
                return null;
            }
            if (mConstantPadding != null || mPaddingChecked) {
                return mConstantPadding;
            }

            Rect r = null;
            final Rect t = new Rect();
            final int N = getChildCount();
            final Drawable[] drawables = mDrawables;
            for (int i = 0; i < N; i++) {
                if (drawables[i].getPadding(t)) {
                    if (r == null) r = new Rect(0, 0, 0, 0);
                    if (t.left > r.left) r.left = t.left;
                    if (t.top > r.top) r.top = t.top;
                    if (t.right > r.right) r.right = t.right;
                    if (t.bottom > r.bottom) r.bottom = t.bottom;
                }
            }
            mPaddingChecked = true;
            return (mConstantPadding = r);
        }

        public final void setConstantSize(boolean constant) {
            mConstantSize = constant;
        }

        public final boolean isConstantSize() {
            return mConstantSize;
        }

        public final int getConstantWidth() {
            if (!mComputedConstantSize) {
                computeConstantSize();
            }

            return mConstantWidth;
        }

        public final int getConstantHeight() {
            if (!mComputedConstantSize) {
                computeConstantSize();
            }

            return mConstantHeight;
        }

        public final int getConstantMinimumWidth() {
            if (!mComputedConstantSize) {
                computeConstantSize();
            }

            return mConstantMinimumWidth;
        }

        public final int getConstantMinimumHeight() {
            if (!mComputedConstantSize) {
                computeConstantSize();
            }

            return mConstantMinimumHeight;
        }

        private void computeConstantSize() {
            mComputedConstantSize = true;

            final int N = getChildCount();
            final Drawable[] drawables = mDrawables;
            mConstantWidth = mConstantHeight = 0;
            mConstantMinimumWidth = mConstantMinimumHeight = 0;
            for (int i = 0; i < N; i++) {
                Drawable dr = drawables[i];
                int s = dr.getIntrinsicWidth();
                if (s > mConstantWidth) mConstantWidth = s;
                s = dr.getIntrinsicHeight();
                if (s > mConstantHeight) mConstantHeight = s;
                s = dr.getMinimumWidth();
                if (s > mConstantMinimumWidth) mConstantMinimumWidth = s;
                s = dr.getMinimumHeight();
                if (s > mConstantMinimumHeight) mConstantMinimumHeight = s;
            }
        }

        public final int getOpacity() {
            if (mHaveOpacity) {
                return mOpacity;
            }

            final int N = getChildCount();
            final Drawable[] drawables = mDrawables;
            int op = N > 0 ? drawables[0].getOpacity() : PixelFormat.TRANSPARENT;
            for (int i = 1; i < N; i++) {
                op = Drawable.resolveOpacity(op, drawables[i].getOpacity());
            }
            mOpacity = op;
            mHaveOpacity = true;
            return op;
        }

        public final boolean isStateful() {
            if (mHaveStateful) {
                return mStateful;
            }
            
            boolean stateful = false;
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (mDrawables[i].isStateful()) {
                    stateful = true;
                    break;
                }
            }
            
            mStateful = stateful;
            mHaveStateful = true;
            return stateful;
        }

        public void growArray(int oldSize, int newSize) {
            Drawable[] newDrawables = new Drawable[newSize];
            System.arraycopy(mDrawables, 0, newDrawables, 0, oldSize);
            mDrawables = newDrawables;
        }

        public synchronized boolean canConstantState() {
            if (!mCheckedConstantState) {
                mCanConstantState = true;
                final int N = mNumChildren;
                for (int i=0; i<N; i++) {
                    if (mDrawables[i].getConstantState() == null) {
                        mCanConstantState = false;
                        break;
                    }
                }
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }
    }

    protected void setConstantState(DrawableContainerState state)
    {
        mDrawableContainerState = state;
    }
}
