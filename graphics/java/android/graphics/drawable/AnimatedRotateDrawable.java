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

package android.graphics.drawable;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.os.SystemClock;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

import com.android.internal.R;

/**
 * @hide
 */
public class AnimatedRotateDrawable extends DrawableWrapper implements Runnable, Animatable {
    private AnimatedRotateState mState;

    private float mCurrentDegrees;
    private float mIncrement;
    private boolean mRunning;

    public AnimatedRotateDrawable() {
        this(new AnimatedRotateState(null), null);
    }

    @Override
    public void draw(Canvas canvas) {
        int saveCount = canvas.save();

        final AnimatedRotateState st = mState;
        final Drawable drawable = st.mDrawable;
        final Rect bounds = drawable.getBounds();

        int w = bounds.right - bounds.left;
        int h = bounds.bottom - bounds.top;

        float px = st.mPivotXRel ? (w * st.mPivotX) : st.mPivotX;
        float py = st.mPivotYRel ? (h * st.mPivotY) : st.mPivotY;

        canvas.rotate(mCurrentDegrees, px + bounds.left, py + bounds.top);

        drawable.draw(canvas);

        canvas.restoreToCount(saveCount);
    }

    @Override
    public void start() {
        if (!mRunning) {
            mRunning = true;
            nextFrame();
        }
    }

    @Override
    public void stop() {
        mRunning = false;
        unscheduleSelf(this);
    }

    @Override
    public boolean isRunning() {
        return mRunning;
    }

    private void nextFrame() {
        unscheduleSelf(this);
        scheduleSelf(this, SystemClock.uptimeMillis() + mState.mFrameDuration);
    }

    @Override
    public void run() {
        // TODO: This should be computed in draw(Canvas), based on the amount
        // of time since the last frame drawn
        mCurrentDegrees += mIncrement;
        if (mCurrentDegrees > (360.0f - mIncrement)) {
            mCurrentDegrees = 0.0f;
        }
        invalidateSelf();
        nextFrame();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        if (visible) {
            if (changed || restart) {
                mCurrentDegrees = 0.0f;
                nextFrame();
            }
        } else {
            unscheduleSelf(this);
        }
        return changed;
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.AnimatedRotateDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.AnimatedRotateDrawable_visible);
        updateStateFromTypedArray(a);
        verifyRequiredAttributes(a);
        a.recycle();

        updateLocalState();
    }

    private void verifyRequiredAttributes(TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        if (getDrawable() == null && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.AnimatedRotateDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <animated-rotate> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    @Override
    void updateStateFromTypedArray(TypedArray a) {
        super.updateStateFromTypedArray(a);

        final AnimatedRotateState state = mState;

        if (a.hasValue(R.styleable.AnimatedRotateDrawable_pivotX)) {
            final TypedValue tv = a.peekValue(R.styleable.AnimatedRotateDrawable_pivotX);
            state.mPivotXRel = tv.type == TypedValue.TYPE_FRACTION;
            state.mPivotX = state.mPivotXRel ? tv.getFraction(1.0f, 1.0f) : tv.getFloat();
        }

        if (a.hasValue(R.styleable.AnimatedRotateDrawable_pivotY)) {
            final TypedValue tv = a.peekValue(R.styleable.AnimatedRotateDrawable_pivotY);
            state.mPivotYRel = tv.type == TypedValue.TYPE_FRACTION;
            state.mPivotY = state.mPivotYRel ? tv.getFraction(1.0f, 1.0f) : tv.getFloat();
        }

        setFramesCount(a.getInt(
                R.styleable.AnimatedRotateDrawable_framesCount, state.mFramesCount));
        setFramesDuration(a.getInt(
                R.styleable.AnimatedRotateDrawable_frameDuration, state.mFrameDuration));

        final Drawable dr = a.getDrawable(R.styleable.AnimatedRotateDrawable_drawable);
        if (dr != null) {
            setDrawable(dr);
        }
    }

    @Override
    public void applyTheme(@Nullable Theme t) {
        final AnimatedRotateState state = mState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrs, R.styleable.AnimatedRotateDrawable);
            try {
                updateStateFromTypedArray(a);
                verifyRequiredAttributes(a);
            } catch (XmlPullParserException e) {
                throw new RuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        // The drawable may have changed as a result of applying the theme, so
        // apply the theme to the wrapped drawable last.
        super.applyTheme(t);

        updateLocalState();
    }

    public void setFramesCount(int framesCount) {
        mState.mFramesCount = framesCount;
        mIncrement = 360.0f / mState.mFramesCount;
    }

    public void setFramesDuration(int framesDuration) {
        mState.mFrameDuration = framesDuration;
    }

    static final class AnimatedRotateState extends DrawableWrapper.DrawableWrapperState {
        Drawable mDrawable;
        int[] mThemeAttrs;

        int mChangingConfigurations;

        boolean mPivotXRel = false;
        float mPivotX = 0;
        boolean mPivotYRel = false;
        float mPivotY = 0;
        int mFrameDuration = 150;
        int mFramesCount = 12;

        private boolean mCanConstantState;
        private boolean mCheckedConstantState;

        public AnimatedRotateState(AnimatedRotateState orig) {
            super(orig);

            if (orig != null) {
                mPivotXRel = orig.mPivotXRel;
                mPivotX = orig.mPivotX;
                mPivotYRel = orig.mPivotYRel;
                mPivotY = orig.mPivotY;
                mFramesCount = orig.mFramesCount;
                mFrameDuration = orig.mFrameDuration;
            }
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new AnimatedRotateDrawable(this, res);
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null || (mDrawable != null && mDrawable.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        public boolean canConstantState() {
            if (!mCheckedConstantState) {
                mCanConstantState = mDrawable.getConstantState() != null;
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }
    }

    private AnimatedRotateDrawable(AnimatedRotateState state, Resources res) {
        super(state, res);

        mState = state;

        updateLocalState();
    }

    private void updateLocalState() {
        final AnimatedRotateState state = mState;
        mIncrement = 360.0f / state.mFramesCount;

        // Force the wrapped drawable to use filtering and AA, if applicable,
        // so that it looks smooth when rotated.
        final Drawable drawable = state.mDrawable;
        if (drawable != null) {
            drawable.setFilterBitmap(true);
            if (drawable instanceof BitmapDrawable) {
                ((BitmapDrawable) drawable).setAntiAlias(true);
            }
        }
    }
}
