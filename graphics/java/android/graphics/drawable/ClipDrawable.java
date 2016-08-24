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

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.graphics.*;
import android.view.Gravity;
import android.util.AttributeSet;

import java.io.IOException;

/**
 * A Drawable that clips another Drawable based on this Drawable's current
 * level value.  You can control how much the child Drawable gets clipped in width
 * and height based on the level, as well as a gravity to control where it is
 * placed in its overall container.  Most often used to implement things like
 * progress bars, by increasing the drawable's level with {@link
 * android.graphics.drawable.Drawable#setLevel(int) setLevel()}.
 * <p class="note"><strong>Note:</strong> The drawable is clipped completely and not visible when
 * the level is 0 and fully revealed when the level is 10,000.</p>
 *
 * <p>It can be defined in an XML file with the <code>&lt;clip></code> element.  For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#ClipDrawable_clipOrientation
 * @attr ref android.R.styleable#ClipDrawable_gravity
 * @attr ref android.R.styleable#ClipDrawable_drawable
 */
public class ClipDrawable extends DrawableWrapper {
    public static final int HORIZONTAL = 1;
    public static final int VERTICAL = 2;

    private static final int MAX_LEVEL = 10000;

    private final Rect mTmpRect = new Rect();

    private ClipState mState;

    ClipDrawable() {
        this(new ClipState(null, null), null);
    }

    /**
     * Creates a new clip drawable with the specified gravity and orientation.
     *
     * @param drawable the drawable to clip
     * @param gravity gravity constant (see {@link Gravity} used to position
     *                the clipped drawable within the parent container
     * @param orientation bitwise-or of {@link #HORIZONTAL} and/or
     *                   {@link #VERTICAL}
     */
    public ClipDrawable(Drawable drawable, int gravity, int orientation) {
        this(new ClipState(null, null), null);

        mState.mGravity = gravity;
        mState.mOrientation = orientation;

        setDrawable(drawable);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.ClipDrawable);

        // Inflation will advance the XmlPullParser and AttributeSet.
        super.inflate(r, parser, attrs, theme);

        updateStateFromTypedArray(a);
        verifyRequiredAttributes(a);
        a.recycle();
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final ClipState state = mState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.ClipDrawable);
            try {
                updateStateFromTypedArray(a);
                verifyRequiredAttributes(a);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }
    }

    private void verifyRequiredAttributes(@NonNull TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        if (getDrawable() == null && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.ClipDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <clip> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    private void updateStateFromTypedArray(@NonNull TypedArray a) {
        final ClipState state = mState;
        if (state == null) {
            return;
        }

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mOrientation = a.getInt(
                R.styleable.ClipDrawable_clipOrientation, state.mOrientation);
        state.mGravity = a.getInt(
                R.styleable.ClipDrawable_gravity, state.mGravity);
    }

    @Override
    protected boolean onLevelChange(int level) {
        super.onLevelChange(level);
        invalidateSelf();
        return true;
    }

    @Override
    public int getOpacity() {
        final Drawable dr = getDrawable();
        final int opacity = dr.getOpacity();
        if (opacity == PixelFormat.TRANSPARENT || dr.getLevel() == 0) {
            return PixelFormat.TRANSPARENT;
        }

        final int level = getLevel();
        if (level >= MAX_LEVEL) {
            return dr.getOpacity();
        }

        // Some portion of non-transparent drawable is showing.
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void draw(Canvas canvas) {
        final Drawable dr = getDrawable();
        if (dr.getLevel() == 0) {
            return;
        }

        final Rect r = mTmpRect;
        final Rect bounds = getBounds();
        final int level = getLevel();

        int w = bounds.width();
        final int iw = 0; //mState.mDrawable.getIntrinsicWidth();
        if ((mState.mOrientation & HORIZONTAL) != 0) {
            w -= (w - iw) * (MAX_LEVEL - level) / MAX_LEVEL;
        }

        int h = bounds.height();
        final int ih = 0; //mState.mDrawable.getIntrinsicHeight();
        if ((mState.mOrientation & VERTICAL) != 0) {
            h -= (h - ih) * (MAX_LEVEL - level) / MAX_LEVEL;
        }

        final int layoutDirection = getLayoutDirection();
        Gravity.apply(mState.mGravity, w, h, bounds, r, layoutDirection);

        if (w > 0 && h > 0) {
            canvas.save();
            canvas.clipRect(r);
            dr.draw(canvas);
            canvas.restore();
        }
    }

    @Override
    DrawableWrapperState mutateConstantState() {
        mState = new ClipState(mState, null);
        return mState;
    }

    static final class ClipState extends DrawableWrapper.DrawableWrapperState {
        private int[] mThemeAttrs;

        int mOrientation = HORIZONTAL;
        int mGravity = Gravity.LEFT;

        ClipState(ClipState orig, Resources res) {
            super(orig, res);

            if (orig != null) {
                mOrientation = orig.mOrientation;
                mGravity = orig.mGravity;
            }
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ClipDrawable(this, res);
        }
    }

    private ClipDrawable(ClipState state, Resources res) {
        super(state, res);

        mState = state;
    }
}

