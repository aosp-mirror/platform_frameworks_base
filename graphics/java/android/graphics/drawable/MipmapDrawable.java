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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;

import java.io.IOException;

/**
 * @hide -- we are probably moving to do MipMaps in another way (more integrated
 * with the resource system).
 *
 * A resource that manages a number of alternate Drawables, and which actually draws the one which
 * size matches the most closely the drawing bounds. Providing several pre-scaled version of the
 * drawable helps minimizing the aliasing artifacts that can be introduced by the scaling.
 *
 * <p>
 * Use {@link #addDrawable(Drawable)} to define the different Drawables that will represent the
 * mipmap levels of this MipmapDrawable. The mipmap Drawable that will actually be used when this
 * MipmapDrawable is drawn is the one which has the smallest intrinsic height greater or equal than
 * the bounds' height. This selection ensures that the best available mipmap level is scaled down to
 * draw this MipmapDrawable.
 * </p>
 *
 * If the bounds' height is larger than the largest mipmap, the largest mipmap will be scaled up.
 * Note that Drawables without intrinsic height (i.e. with a negative value, such as Color) will
 * only be used if no other mipmap Drawable are provided. The Drawables' intrinsic heights should
 * not be changed after the Drawable has been added to this MipmapDrawable.
 *
 * <p>
 * The different mipmaps' parameters (opacity, padding, color filter, gravity...) should typically
 * be similar to ensure a continuous visual appearance when the MipmapDrawable is scaled. The aspect
 * ratio of the different mipmaps should especially be equal.
 * </p>
 *
 * A typical example use of a MipmapDrawable would be for an image which is intended to be scaled at
 * various sizes, and for which one wants to provide pre-scaled versions to precisely control its
 * appearance.
 *
 * <p>
 * The intrinsic size of a MipmapDrawable are inferred from those of the largest mipmap (in terms of
 * {@link Drawable#getIntrinsicHeight()}). On the opposite, its minimum
 * size is defined by the smallest provided mipmap.
 * </p>

 * It can be defined in an XML file with the <code>&lt;mipmap></code> element.
 * Each mipmap Drawable is defined in a nested <code>&lt;item></code>. For example:
 * <pre>
 * &lt;mipmap xmlns:android="http://schemas.android.com/apk/res/android">
 *  &lt;item android:drawable="@drawable/my_image_8" />
 *  &lt;item android:drawable="@drawable/my_image_32" />
 *  &lt;item android:drawable="@drawable/my_image_128" />
 * &lt;/mipmap>
 *</pre>
 * <p>
 * With this XML saved into the res/drawable/ folder of the project, it can be referenced as
 * the drawable for an {@link android.widget.ImageView}. Assuming that the heights of the provided
 * drawables are respectively 8, 32 and 128 pixels, the first one will be scaled down when the
 * bounds' height is lower or equal than 8 pixels. The second drawable will then be used up to a
 * height of 32 pixels and the largest drawable will be used for greater heights.
 * </p>
 * @attr ref android.R.styleable#MipmapDrawableItem_drawable
 */
public class MipmapDrawable extends DrawableContainer {
    private final MipmapContainerState mMipmapContainerState;
    private boolean mMutated;

    public MipmapDrawable() {
        this(null, null);
    }

    /**
     * Adds a Drawable to the list of available mipmap Drawables. The Drawable actually used when
     * this MipmapDrawable is drawn is determined from its bounds.
     *
     * This method has no effect if drawable is null.
     *
     * @param drawable The Drawable that will be added to list of available mipmap Drawables.
     */

    public void addDrawable(Drawable drawable) {
        if (drawable != null) {
            mMipmapContainerState.addDrawable(drawable);
            onDrawableAdded();
        }
    }

    private void onDrawableAdded() {
        // selectDrawable assumes that the container content does not change.
        // When a Drawable is added, the same index can correspond to a new Drawable, and since
        // selectDrawable has a fast exit case when oldIndex==newIndex, the new drawable could end
        // up not being used in place of the previous one if they happen to share the same index.
        // This make sure the new computed index can actually replace the previous one.
        selectDrawable(-1);
        onBoundsChange(getBounds());
    }

    // overrides from Drawable

    @Override
    protected void onBoundsChange(Rect bounds) {
        final int index = mMipmapContainerState.indexForBounds(bounds);

        // Will call invalidateSelf() if needed
        selectDrawable(index);

        super.onBoundsChange(bounds);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
    throws XmlPullParserException, IOException {

        super.inflate(r, parser, attrs);

        int type;

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

            TypedArray a = r.obtainAttributes(attrs,
                    com.android.internal.R.styleable.MipmapDrawableItem);

            int drawableRes = a.getResourceId(
                    com.android.internal.R.styleable.MipmapDrawableItem_drawable, 0);

            a.recycle();

            Drawable dr;
            if (drawableRes != 0) {
                dr = r.getDrawable(drawableRes);
            } else {
                while ((type = parser.next()) == XmlPullParser.TEXT) {
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new XmlPullParserException(
                            parser.getPositionDescription()
                            + ": <item> tag requires a 'drawable' attribute or "
                            + "child tag defining a drawable");
                }
                dr = Drawable.createFromXmlInner(r, parser, attrs);
            }

            mMipmapContainerState.addDrawable(dr);
        }

        onDrawableAdded();
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mMipmapContainerState.mMipmapHeights = mMipmapContainerState.mMipmapHeights.clone();
            mMutated = true;
        }
        return this;
    }

    private final static class MipmapContainerState extends DrawableContainerState {
        private int[] mMipmapHeights;

        MipmapContainerState(MipmapContainerState orig, MipmapDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                mMipmapHeights = orig.mMipmapHeights;
            } else {
                mMipmapHeights = new int[getChildren().length];
            }

            // Change the default value
            setConstantSize(true);
        }

        /**
         * Returns the index of the child mipmap drawable that will best fit the provided bounds.
         * This index is determined by comparing bounds' height and children intrinsic heights.
         * The returned mipmap index is the smallest mipmap which height is greater or equal than
         * the bounds' height. If the bounds' height is larger than the largest mipmap, the largest
         * mipmap index is returned.
         *
         * @param bounds The bounds of the MipMapDrawable.
         * @return The index of the child Drawable that will best fit these bounds, or -1 if there
         * are no children mipmaps.
         */
        public int indexForBounds(Rect bounds) {
            final int boundsHeight = bounds.height();
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (boundsHeight <= mMipmapHeights[i]) {
                    return i;
                }
            }

            // No mipmap larger than bounds found. Use largest one which will be scaled up.
            if (N > 0) {
                return N - 1;
            }
            // No Drawable mipmap at all
            return -1;
        }

        /**
         * Adds a Drawable to the list of available mipmap Drawables. This list can be retrieved
         * using {@link DrawableContainer.DrawableContainerState#getChildren()} and this method
         * ensures that it is always sorted by increasing {@link Drawable#getIntrinsicHeight()}.
         *
         * @param drawable The Drawable that will be added to children list
         */
        public void addDrawable(Drawable drawable) {
            // Insert drawable in last position, correctly resetting cached values and
            // especially mComputedConstantSize
            int pos = addChild(drawable);

            // Bubble sort the last drawable to restore the sort by intrinsic height
            final int drawableHeight = drawable.getIntrinsicHeight();

            while (pos > 0) {
                final Drawable previousDrawable = mDrawables[pos-1];
                final int previousIntrinsicHeight = previousDrawable.getIntrinsicHeight();

                if (drawableHeight < previousIntrinsicHeight) {
                    mDrawables[pos] = previousDrawable;
                    mMipmapHeights[pos] = previousIntrinsicHeight;

                    mDrawables[pos-1] = drawable;
                    mMipmapHeights[pos-1] = drawableHeight;
                    pos--;
                } else {
                    break;
                }
            }
        }

        /**
         * Intrinsic sizes are those of the largest available mipmap.
         * Minimum sizes are those of the smallest available mipmap.
         */
        @Override
        protected void computeConstantSize() {
            final int N = getChildCount();
            if (N > 0) {
                final Drawable smallestDrawable = mDrawables[0];
                mConstantMinimumWidth = smallestDrawable.getMinimumWidth();
                mConstantMinimumHeight = smallestDrawable.getMinimumHeight();

                final Drawable largestDrawable = mDrawables[N-1];
                mConstantWidth = largestDrawable.getIntrinsicWidth();
                mConstantHeight = largestDrawable.getIntrinsicHeight();
            } else {
                mConstantWidth = mConstantHeight = -1;
                mConstantMinimumWidth = mConstantMinimumHeight = 0;
            }
            mComputedConstantSize = true;
        }

        @Override
        public Drawable newDrawable() {
            return new MipmapDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new MipmapDrawable(this, res);
        }

        @Override
        public void growArray(int oldSize, int newSize) {
            super.growArray(oldSize, newSize);
            int[] newInts = new int[newSize];
            System.arraycopy(mMipmapHeights, 0, newInts, 0, oldSize);
            mMipmapHeights = newInts;
        }
    }

    private MipmapDrawable(MipmapContainerState state, Resources res) {
        MipmapContainerState as = new MipmapContainerState(state, this, res);
        mMipmapContainerState = as;
        setConstantState(as);
        onDrawableAdded();
    }
}
