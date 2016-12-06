/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.graphics.drawable;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.util.AttributeSet;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * An internal DrawableContainer class, used to draw different things depending on animation scale.
 * i.e: animation scale can be 0 in battery saver mode.
 * This class contains 2 drawable, one is animatable, the other is static. When animation scale is
 * not 0, the animatable drawable will the drawn. Otherwise, the static drawable will be drawn.
 * <p>This class implements Animatable since ProgressBar can pick this up similarly as an
 * AnimatedVectorDrawable.
 * <p>It can be defined in an XML file with the {@code <AnimationScaleListDrawable>}
 * element.
 */
public class AnimationScaleListDrawable extends DrawableContainer implements Animatable {
    private static final String TAG = "AnimationScaleListDrawable";
    private AnimationScaleListState mAnimationScaleListState;
    private boolean mMutated;

    public AnimationScaleListDrawable() {
        this(null, null);
    }

    private AnimationScaleListDrawable(@Nullable AnimationScaleListState state,
            @Nullable Resources res) {
        // Every scale list drawable has its own constant state.
        final AnimationScaleListState newState = new AnimationScaleListState(state, this, res);
        setConstantState(newState);
        onStateChange(getState());
    }

    /**
     * Set the current drawable according to the animation scale. If scale is 0, then pick the
     * static drawable, otherwise, pick the animatable drawable.
     */
    @Override
    protected boolean onStateChange(int[] stateSet) {
        final boolean changed = super.onStateChange(stateSet);
        int idx = mAnimationScaleListState.getCurrentDrawableIndexBasedOnScale();
        return selectDrawable(idx) || changed;
    }


    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs,
                R.styleable.AnimationScaleListDrawable);
        updateDensity(r);
        a.recycle();

        inflateChildElements(r, parser, attrs, theme);

        onStateChange(getState());
    }

    /**
     * Inflates child elements from XML.
     */
    private void inflateChildElements(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final AnimationScaleListState state = mAnimationScaleListState;
        final int innerDepth = parser.getDepth() + 1;
        int type;
        int depth;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth
                || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth || !parser.getName().equals("item")) {
                continue;
            }

            // Either pick up the android:drawable attribute.
            final TypedArray a = obtainAttributes(r, theme, attrs,
                    R.styleable.AnimationScaleListDrawableItem);
            Drawable dr = a.getDrawable(R.styleable.AnimationScaleListDrawableItem_drawable);
            a.recycle();

            // Or parse the child element under <item>.
            if (dr == null) {
                while ((type = parser.next()) == XmlPullParser.TEXT) {
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new XmlPullParserException(
                            parser.getPositionDescription()
                                    + ": <item> tag requires a 'drawable' attribute or "
                                    + "child tag defining a drawable");
                }
                dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
            }

            state.addDrawable(dr);
        }
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mAnimationScaleListState.mutate();
            mMutated = true;
        }
        return this;
    }

    @Override
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    @Override
    public void start() {
        Drawable dr = getCurrent();
        if (dr != null && dr instanceof Animatable) {
            ((Animatable) dr).start();
        }
    }

    @Override
    public void stop() {
        Drawable dr = getCurrent();
        if (dr != null && dr instanceof Animatable) {
            ((Animatable) dr).stop();
        }
    }

    @Override
    public boolean isRunning() {
        boolean result = false;
        Drawable dr = getCurrent();
        if (dr != null && dr instanceof Animatable) {
            result = ((Animatable) dr).isRunning();
        }
        return result;
    }

    static class AnimationScaleListState extends DrawableContainerState {
        int[] mThemeAttrs = null;
        // The index of the last static drawable.
        int mStaticDrawableIndex = -1;
        // The index of the last animatable drawable.
        int mAnimatableDrawableIndex = -1;

        AnimationScaleListState(AnimationScaleListState orig, AnimationScaleListDrawable owner,
                Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                // Perform a shallow copy and rely on mutate() to deep-copy.
                mThemeAttrs = orig.mThemeAttrs;

                mStaticDrawableIndex = orig.mStaticDrawableIndex;
                mAnimatableDrawableIndex = orig.mAnimatableDrawableIndex;
            }

        }

        void mutate() {
            mThemeAttrs = mThemeAttrs != null ? mThemeAttrs.clone() : null;
        }

        /**
         * Add the drawable into the container.
         * This class only keep track one animatable drawable, and one static. If there are multiple
         * defined in the XML, then pick the last one.
         */
        int addDrawable(Drawable drawable) {
            final int pos = addChild(drawable);
            if (drawable instanceof Animatable) {
                mAnimatableDrawableIndex = pos;
            } else {
                mStaticDrawableIndex = pos;
            }
            return pos;
        }

        @Override
        public Drawable newDrawable() {
            return new AnimationScaleListDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new AnimationScaleListDrawable(this, res);
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null || super.canApplyTheme();
        }

        public int getCurrentDrawableIndexBasedOnScale() {
            if (ValueAnimator.getDurationScale() == 0) {
                return mStaticDrawableIndex;
            }
            return mAnimatableDrawableIndex;
        }
    }

    @Override
    public void applyTheme(@NonNull Theme theme) {
        super.applyTheme(theme);

        onStateChange(getState());
    }

    @Override
    protected void setConstantState(@NonNull DrawableContainerState state) {
        super.setConstantState(state);

        if (state instanceof AnimationScaleListState) {
            mAnimationScaleListState = (AnimationScaleListState) state;
        }
    }
}

