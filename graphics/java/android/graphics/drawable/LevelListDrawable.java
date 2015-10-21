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

import java.io.IOException;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;

/**
 * A resource that manages a number of alternate Drawables, each assigned a maximum numerical value.
 * Setting the level value of the object with {@link #setLevel(int)} will load the image with the next
 * greater or equal value assigned to its max attribute.
 * A good example use of
 * a LevelListDrawable would be a battery level indicator icon, with different images to indicate the current
 * battery level.
 * <p>
 * It can be defined in an XML file with the <code>&lt;level-list></code> element.
 * Each Drawable level is defined in a nested <code>&lt;item></code>. For example:
 * </p>
 * <pre>
 * &lt;level-list xmlns:android="http://schemas.android.com/apk/res/android">
 *  &lt;item android:maxLevel="0" android:drawable="@drawable/ic_wifi_signal_1" />
 *  &lt;item android:maxLevel="1" android:drawable="@drawable/ic_wifi_signal_2" />
 *  &lt;item android:maxLevel="2" android:drawable="@drawable/ic_wifi_signal_3" />
 *  &lt;item android:maxLevel="3" android:drawable="@drawable/ic_wifi_signal_4" />
 * &lt;/level-list>
 *</pre>
 * <p>With this XML saved into the res/drawable/ folder of the project, it can be referenced as
 * the drawable for an {@link android.widget.ImageView}. The default image is the first in the list.
 * It can then be changed to one of the other levels with
 * {@link android.widget.ImageView#setImageLevel(int)}. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#LevelListDrawableItem_minLevel
 * @attr ref android.R.styleable#LevelListDrawableItem_maxLevel
 * @attr ref android.R.styleable#LevelListDrawableItem_drawable
 */
public class LevelListDrawable extends DrawableContainer {
    private LevelListState mLevelListState;
    private boolean mMutated;

    public LevelListDrawable() {
        this(null, null);
    }

    public void addLevel(int low, int high, Drawable drawable) {
        if (drawable != null) {
            mLevelListState.addLevel(low, high, drawable);
            // in case the new state matches our current state...
            onLevelChange(getLevel());
        }
    }

    // overrides from Drawable

    @Override
    protected boolean onLevelChange(int level) {
        int idx = mLevelListState.indexOfLevel(level);
        if (selectDrawable(idx)) {
            return true;
        }
        return super.onLevelChange(level);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);
        updateDensity(r);

        inflateChildElements(r, parser, attrs, theme);
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        int type;

        int low = 0;

        final int innerDepth = parser.getDepth() + 1;
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

            TypedArray a = obtainAttributes(r, theme, attrs,
                    com.android.internal.R.styleable.LevelListDrawableItem);

            low = a.getInt(
                    com.android.internal.R.styleable.LevelListDrawableItem_minLevel, 0);
            int high = a.getInt(
                    com.android.internal.R.styleable.LevelListDrawableItem_maxLevel, 0);
            int drawableRes = a.getResourceId(
                    com.android.internal.R.styleable.LevelListDrawableItem_drawable, 0);

            a.recycle();

            if (high < 0) {
                throw new XmlPullParserException(parser.getPositionDescription()
                        + ": <item> tag requires a 'maxLevel' attribute");
            }

            Drawable dr;
            if (drawableRes != 0) {
                dr = r.getDrawable(drawableRes, theme);
            } else {
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

            mLevelListState.addLevel(low, high, dr);
        }

        onLevelChange(getLevel());
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mLevelListState.mutate();
            mMutated = true;
        }
        return this;
    }

    @Override
    LevelListState cloneConstantState() {
        return new LevelListState(mLevelListState, this, null);
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    private final static class LevelListState extends DrawableContainerState {
        private int[] mLows;
        private int[] mHighs;

        LevelListState(LevelListState orig, LevelListDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                // Perform a shallow copy and rely on mutate() to deep-copy.
                mLows = orig.mLows;
                mHighs = orig.mHighs;
            } else {
                mLows = new int[getCapacity()];
                mHighs = new int[getCapacity()];
            }
        }

        private void mutate() {
            mLows = mLows.clone();
            mHighs = mHighs.clone();
        }

        public void addLevel(int low, int high, Drawable drawable) {
            int pos = addChild(drawable);
            mLows[pos] = low;
            mHighs[pos] = high;
        }

        public int indexOfLevel(int level) {
            final int[] lows = mLows;
            final int[] highs = mHighs;
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (level >= lows[i] && level <= highs[i]) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Drawable newDrawable() {
            return new LevelListDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new LevelListDrawable(this, res);
        }

        @Override
        public void growArray(int oldSize, int newSize) {
            super.growArray(oldSize, newSize);
            int[] newInts = new int[newSize];
            System.arraycopy(mLows, 0, newInts, 0, oldSize);
            mLows = newInts;
            newInts = new int[newSize];
            System.arraycopy(mHighs, 0, newInts, 0, oldSize);
            mHighs = newInts;
        }
    }

    @Override
    protected void setConstantState(@NonNull DrawableContainerState state) {
        super.setConstantState(state);

        if (state instanceof LevelListState) {
            mLevelListState = (LevelListState) state;
        }
    }

    private LevelListDrawable(LevelListState state, Resources res) {
        final LevelListState as = new LevelListState(state, this, res);
        setConstantState(as);
        onLevelChange(getLevel());
    }
}

