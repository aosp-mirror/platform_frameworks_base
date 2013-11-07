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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.LayoutDirection;
import android.util.SparseArray;

/**
 * A helper class that contains several {@link Drawable}s and selects which one to use.
 *
 * You can subclass it to create your own DrawableContainers or directly use one its child classes.
 */
public class DrawableContainer extends Drawable implements Drawable.Callback {
    private static final boolean DEBUG = false;
    private static final String TAG = "DrawableContainer";

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

    // Animations.
    private Runnable mAnimationRunnable;
    private long mEnterAnimationEnd;
    private long mExitAnimationEnd;
    private Drawable mLastDrawable;

    private Insets mInsets = Insets.NONE;

    // overrides from Drawable

    @Override
    public void draw(Canvas canvas) {
        if (mCurrDrawable != null) {
            mCurrDrawable.draw(canvas);
        }
        if (mLastDrawable != null) {
            mLastDrawable.draw(canvas);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mDrawableContainerState.mChangingConfigurations
                | mDrawableContainerState.mChildrenChangingConfigurations;
    }

    private boolean needsMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    @Override
    public boolean getPadding(Rect padding) {
        final Rect r = mDrawableContainerState.getConstantPadding();
        boolean result;
        if (r != null) {
            padding.set(r);
            result = (r.left | r.top | r.bottom | r.right) != 0;
        } else {
            if (mCurrDrawable != null) {
                result = mCurrDrawable.getPadding(padding);
            } else {
                result = super.getPadding(padding);
            }
        }
        if (needsMirroring()) {
            final int left = padding.left;
            final int right = padding.right;
            padding.left = right;
            padding.right = left;
        }
        return result;
    }

    /**
     * @hide
     */
    @Override
    public Insets getOpticalInsets() {
        return mInsets;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            if (mCurrDrawable != null) {
                if (mEnterAnimationEnd == 0) {
                    mCurrDrawable.mutate().setAlpha(alpha);
                } else {
                    animate(false);
                }
            }
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setDither(boolean dither) {
        if (mDrawableContainerState.mDither != dither) {
            mDrawableContainerState.mDither = dither;
            if (mCurrDrawable != null) {
                mCurrDrawable.mutate().setDither(mDrawableContainerState.mDither);
            }
        }
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (mColorFilter != cf) {
            mColorFilter = cf;
            if (mCurrDrawable != null) {
                mCurrDrawable.mutate().setColorFilter(cf);
            }
        }
    }

    /**
     * Change the global fade duration when a new drawable is entering
     * the scene.
     * @param ms The amount of time to fade in milliseconds.
     */
    public void setEnterFadeDuration(int ms) {
        mDrawableContainerState.mEnterFadeDuration = ms;
    }

    /**
     * Change the global fade duration when a new drawable is leaving
     * the scene.
     * @param ms The amount of time to fade in milliseconds.
     */
    public void setExitFadeDuration(int ms) {
        mDrawableContainerState.mExitFadeDuration = ms;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (mLastDrawable != null) {
            mLastDrawable.setBounds(bounds);
        }
        if (mCurrDrawable != null) {
            mCurrDrawable.setBounds(bounds);
        }
    }

    @Override
    public boolean isStateful() {
        return mDrawableContainerState.isStateful();
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        mDrawableContainerState.mAutoMirrored = mirrored;
        if (mCurrDrawable != null) {
            mCurrDrawable.mutate().setAutoMirrored(mDrawableContainerState.mAutoMirrored);
        }
    }

    @Override
    public boolean isAutoMirrored() {
        return mDrawableContainerState.mAutoMirrored;
    }

    @Override
    public void jumpToCurrentState() {
        boolean changed = false;
        if (mLastDrawable != null) {
            mLastDrawable.jumpToCurrentState();
            mLastDrawable = null;
            changed = true;
        }
        if (mCurrDrawable != null) {
            mCurrDrawable.jumpToCurrentState();
            mCurrDrawable.mutate().setAlpha(mAlpha);
        }
        if (mExitAnimationEnd != 0) {
            mExitAnimationEnd = 0;
            changed = true;
        }
        if (mEnterAnimationEnd != 0) {
            mEnterAnimationEnd = 0;
            changed = true;
        }
        if (changed) {
            invalidateSelf();
        }
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mLastDrawable != null) {
            return mLastDrawable.setState(state);
        }
        if (mCurrDrawable != null) {
            return mCurrDrawable.setState(state);
        }
        return false;
    }

    @Override
    protected boolean onLevelChange(int level) {
        if (mLastDrawable != null) {
            return mLastDrawable.setLevel(level);
        }
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

    @Override
    public void invalidateDrawable(Drawable who) {
        if (who == mCurrDrawable && getCallback() != null) {
            getCallback().invalidateDrawable(this);
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        if (who == mCurrDrawable && getCallback() != null) {
            getCallback().scheduleDrawable(this, what, when);
        }
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        if (who == mCurrDrawable && getCallback() != null) {
            getCallback().unscheduleDrawable(this, what);
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (mLastDrawable != null) {
            mLastDrawable.setVisible(visible, restart);
        }
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

    public boolean selectDrawable(int idx) {
        if (idx == mCurIndex) {
            return false;
        }

        final long now = SystemClock.uptimeMillis();

        if (DEBUG) android.util.Log.i(TAG, toString() + " from " + mCurIndex + " to " + idx
                + ": exit=" + mDrawableContainerState.mExitFadeDuration
                + " enter=" + mDrawableContainerState.mEnterFadeDuration);

        if (mDrawableContainerState.mExitFadeDuration > 0) {
            if (mLastDrawable != null) {
                mLastDrawable.setVisible(false, false);
            }
            if (mCurrDrawable != null) {
                mLastDrawable = mCurrDrawable;
                mExitAnimationEnd = now + mDrawableContainerState.mExitFadeDuration;
            } else {
                mLastDrawable = null;
                mExitAnimationEnd = 0;
            }
        } else if (mCurrDrawable != null) {
            mCurrDrawable.setVisible(false, false);
        }

        if (idx >= 0 && idx < mDrawableContainerState.mNumChildren) {
            final Drawable d = mDrawableContainerState.getChild(idx);
            mCurrDrawable = d;
            mCurIndex = idx;
            if (d != null) {
                mInsets = d.getOpticalInsets();
                d.mutate();
                if (mDrawableContainerState.mEnterFadeDuration > 0) {
                    mEnterAnimationEnd = now + mDrawableContainerState.mEnterFadeDuration;
                } else {
                    d.setAlpha(mAlpha);
                }
                d.setVisible(isVisible(), true);
                d.setDither(mDrawableContainerState.mDither);
                d.setColorFilter(mColorFilter);
                d.setState(getState());
                d.setLevel(getLevel());
                d.setBounds(getBounds());
                d.setLayoutDirection(getLayoutDirection());
                d.setAutoMirrored(mDrawableContainerState.mAutoMirrored);
            } else {
                mInsets = Insets.NONE;
            }
        } else {
            mCurrDrawable = null;
            mInsets = Insets.NONE;
            mCurIndex = -1;
        }

        if (mEnterAnimationEnd != 0 || mExitAnimationEnd != 0) {
            if (mAnimationRunnable == null) {
                mAnimationRunnable = new Runnable() {
                    @Override public void run() {
                        animate(true);
                        invalidateSelf();
                    }
                };
            } else {
                unscheduleSelf(mAnimationRunnable);
            }
            // Compute first frame and schedule next animation.
            animate(true);
        }

        invalidateSelf();

        return true;
    }

    void animate(boolean schedule) {
        final long now = SystemClock.uptimeMillis();
        boolean animating = false;
        if (mCurrDrawable != null) {
            if (mEnterAnimationEnd != 0) {
                if (mEnterAnimationEnd <= now) {
                    mCurrDrawable.mutate().setAlpha(mAlpha);
                    mEnterAnimationEnd = 0;
                } else {
                    int animAlpha = (int)((mEnterAnimationEnd-now)*255)
                            / mDrawableContainerState.mEnterFadeDuration;
                    if (DEBUG) android.util.Log.i(TAG, toString() + " cur alpha " + animAlpha);
                    mCurrDrawable.mutate().setAlpha(((255-animAlpha)*mAlpha)/255);
                    animating = true;
                }
            }
        } else {
            mEnterAnimationEnd = 0;
        }
        if (mLastDrawable != null) {
            if (mExitAnimationEnd != 0) {
                if (mExitAnimationEnd <= now) {
                    mLastDrawable.setVisible(false, false);
                    mLastDrawable = null;
                    mExitAnimationEnd = 0;
                } else {
                    int animAlpha = (int)((mExitAnimationEnd-now)*255)
                            / mDrawableContainerState.mExitFadeDuration;
                    if (DEBUG) android.util.Log.i(TAG, toString() + " last alpha " + animAlpha);
                    mLastDrawable.mutate().setAlpha((animAlpha*mAlpha)/255);
                    animating = true;
                }
            }
        } else {
            mExitAnimationEnd = 0;
        }

        if (schedule && animating) {
            scheduleSelf(mAnimationRunnable, now + 1000/60);
        }
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
            mDrawableContainerState.mutate();
            mMutated = true;
        }
        return this;
    }

    /**
     * A ConstantState that can contain several {@link Drawable}s.
     *
     * This class was made public to enable testing, and its visibility may change in a future
     * release.
     */
    public abstract static class DrawableContainerState extends ConstantState {
        final DrawableContainer mOwner;
        final Resources mRes;

        SparseArray<ConstantStateFuture> mDrawableFutures;

        int mChangingConfigurations;
        int mChildrenChangingConfigurations;

        Drawable[] mDrawables;
        int mNumChildren;

        boolean mVariablePadding;
        boolean mPaddingChecked;
        Rect mConstantPadding;

        boolean mConstantSize;
        boolean mComputedConstantSize;
        int mConstantWidth;
        int mConstantHeight;
        int mConstantMinimumWidth;
        int mConstantMinimumHeight;

        boolean mCheckedOpacity;
        int mOpacity;

        boolean mCheckedStateful;
        boolean mStateful;

        boolean mCheckedConstantState;
        boolean mCanConstantState;

        boolean mDither = DEFAULT_DITHER;

        boolean mMutated;
        int mLayoutDirection;

        int mEnterFadeDuration;
        int mExitFadeDuration;

        boolean mAutoMirrored;

        DrawableContainerState(DrawableContainerState orig, DrawableContainer owner,
                Resources res) {
            mOwner = owner;
            mRes = res;

            if (orig != null) {
                mChangingConfigurations = orig.mChangingConfigurations;
                mChildrenChangingConfigurations = orig.mChildrenChangingConfigurations;

                mCheckedConstantState = true;
                mCanConstantState = true;

                mVariablePadding = orig.mVariablePadding;
                mConstantSize = orig.mConstantSize;
                mDither = orig.mDither;
                mMutated = orig.mMutated;
                mLayoutDirection = orig.mLayoutDirection;
                mEnterFadeDuration = orig.mEnterFadeDuration;
                mExitFadeDuration = orig.mExitFadeDuration;
                mAutoMirrored = orig.mAutoMirrored;

                // Cloning the following values may require creating futures.
                mConstantPadding = orig.getConstantPadding();
                mPaddingChecked = true;

                mConstantWidth = orig.getConstantWidth();
                mConstantHeight = orig.getConstantHeight();
                mConstantMinimumWidth = orig.getConstantMinimumWidth();
                mConstantMinimumHeight = orig.getConstantMinimumHeight();
                mComputedConstantSize = true;

                mOpacity = orig.getOpacity();
                mCheckedOpacity = true;

                mStateful = orig.isStateful();
                mCheckedStateful = true;

                // Postpone cloning children and futures until we're absolutely
                // sure that we're done computing values for the original state.
                final Drawable[] origDr = orig.mDrawables;
                mDrawables = new Drawable[origDr.length];
                mNumChildren = orig.mNumChildren;

                final SparseArray<ConstantStateFuture> origDf = orig.mDrawableFutures;
                if (origDf != null) {
                    mDrawableFutures = origDf.clone();
                } else {
                    mDrawableFutures = new SparseArray<ConstantStateFuture>(mNumChildren);
                }

                final int N = mNumChildren;
                for (int i = 0; i < N; i++) {
                    if (origDr[i] != null) {
                        mDrawableFutures.put(i, new ConstantStateFuture(origDr[i]));
                    }
                }
            } else {
                mDrawables = new Drawable[10];
                mNumChildren = 0;
            }
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations | mChildrenChangingConfigurations;
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
            mCheckedStateful = false;
            mCheckedOpacity = false;

            mConstantPadding = null;
            mPaddingChecked = false;
            mComputedConstantSize = false;

            return pos;
        }

        final int getCapacity() {
            return mDrawables.length;
        }

        private final void createAllFutures() {
            if (mDrawableFutures != null) {
                final int futureCount = mDrawableFutures.size();
                for (int keyIndex = 0; keyIndex < futureCount; keyIndex++) {
                    final int index = mDrawableFutures.keyAt(keyIndex);
                    mDrawables[index] = mDrawableFutures.valueAt(keyIndex).get(this);
                }

                mDrawableFutures = null;
            }
        }

        public final int getChildCount() {
            return mNumChildren;
        }

        /*
         * @deprecated Use {@link #getChild} instead.
         */
        public final Drawable[] getChildren() {
            // Create all futures for backwards compatibility.
            createAllFutures();

            return mDrawables;
        }

        public final Drawable getChild(int index) {
            final Drawable result = mDrawables[index];
            if (result != null) {
                return result;
            }

            // Prepare future drawable if necessary.
            if (mDrawableFutures != null) {
                final int keyIndex = mDrawableFutures.indexOfKey(index);
                if (keyIndex >= 0) {
                    final Drawable prepared = mDrawableFutures.valueAt(keyIndex).get(this);
                    mDrawables[index] = prepared;
                    mDrawableFutures.removeAt(keyIndex);
                    return prepared;
                }
            }

            return null;
        }

        final void setLayoutDirection(int layoutDirection) {
            // No need to call createAllFutures, since future drawables will
            // change layout direction when they are prepared.
            final int N = mNumChildren;
            final Drawable[] drawables = mDrawables;
            for (int i = 0; i < N; i++) {
                if (drawables[i] != null) {
                    drawables[i].setLayoutDirection(layoutDirection);
                }
            }

            mLayoutDirection = layoutDirection;
        }

        final void mutate() {
            // No need to call createAllFutures, since future drawables will
            // mutate when they are prepared.
            final int N = mNumChildren;
            final Drawable[] drawables = mDrawables;
            for (int i = 0; i < N; i++) {
                if (drawables[i] != null) {
                    drawables[i].mutate();
                }
            }

            mMutated = true;
        }

        /**
         * A boolean value indicating whether to use the maximum padding value
         * of all frames in the set (false), or to use the padding value of the
         * frame being shown (true). Default value is false.
         */
        public final void setVariablePadding(boolean variable) {
            mVariablePadding = variable;
        }

        public final Rect getConstantPadding() {
            if (mVariablePadding) {
                return null;
            }

            if ((mConstantPadding != null) || mPaddingChecked) {
                return mConstantPadding;
            }

            createAllFutures();

            Rect r = null;
            final Rect t = new Rect();
            final int N = mNumChildren;
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

        protected void computeConstantSize() {
            mComputedConstantSize = true;

            createAllFutures();

            final int N = mNumChildren;
            final Drawable[] drawables = mDrawables;
            mConstantWidth = mConstantHeight = -1;
            mConstantMinimumWidth = mConstantMinimumHeight = 0;
            for (int i = 0; i < N; i++) {
                final Drawable dr = drawables[i];
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

        public final void setEnterFadeDuration(int duration) {
            mEnterFadeDuration = duration;
        }

        public final int getEnterFadeDuration() {
            return mEnterFadeDuration;
        }

        public final void setExitFadeDuration(int duration) {
            mExitFadeDuration = duration;
        }

        public final int getExitFadeDuration() {
            return mExitFadeDuration;
        }

        public final int getOpacity() {
            if (mCheckedOpacity) {
                return mOpacity;
            }

            createAllFutures();

            mCheckedOpacity = true;

            final int N = mNumChildren;
            final Drawable[] drawables = mDrawables;
            int op = (N > 0) ? drawables[0].getOpacity() : PixelFormat.TRANSPARENT;
            for (int i = 1; i < N; i++) {
                op = Drawable.resolveOpacity(op, drawables[i].getOpacity());
            }

            mOpacity = op;
            return op;
        }

        public final boolean isStateful() {
            if (mCheckedStateful) {
                return mStateful;
            }

            createAllFutures();

            mCheckedStateful = true;

            final int N = mNumChildren;
            final Drawable[] drawables = mDrawables;
            for (int i = 0; i < N; i++) {
                if (drawables[i].isStateful()) {
                    mStateful = true;
                    return true;
                }
            }

            mStateful = false;
            return false;
        }

        public void growArray(int oldSize, int newSize) {
            Drawable[] newDrawables = new Drawable[newSize];
            System.arraycopy(mDrawables, 0, newDrawables, 0, oldSize);
            mDrawables = newDrawables;
        }

        public synchronized boolean canConstantState() {
            if (mCheckedConstantState) {
                return mCanConstantState;
            }

            createAllFutures();

            mCheckedConstantState = true;

            final int N = mNumChildren;
            final Drawable[] drawables = mDrawables;
            for (int i = 0; i < N; i++) {
                if (drawables[i].getConstantState() == null) {
                    mCanConstantState = false;
                    return false;
                }
            }

            mCanConstantState = true;
            return true;
        }

        /**
         * Class capable of cloning a Drawable from another Drawable's
         * ConstantState.
         */
        private static class ConstantStateFuture {
            private final ConstantState mConstantState;

            private ConstantStateFuture(Drawable source) {
                mConstantState = source.getConstantState();
            }

            /**
             * Obtains and prepares the Drawable represented by this future.
             *
             * @param state the container into which this future will be placed
             * @return a prepared Drawable
             */
            public Drawable get(DrawableContainerState state) {
                final Drawable result = (state.mRes == null) ?
                        mConstantState.newDrawable() : mConstantState.newDrawable(state.mRes);
                result.setLayoutDirection(state.mLayoutDirection);
                result.setCallback(state.mOwner);

                if (state.mMutated) {
                    result.mutate();
                }

                return result;
            }
        }
    }

    protected void setConstantState(DrawableContainerState state) {
        mDrawableContainerState = state;
    }
}
