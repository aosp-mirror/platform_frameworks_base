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
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import java.io.IOException;

/** 
 * A Drawable that manages an array of other Drawables. These are drawn in array
 * order, so the element with the largest index will be drawn on top.
 * <p>
 * It can be defined in an XML file with the <code>&lt;layer-list></code> element.
 * Each Drawable in the layer is defined in a nested <code>&lt;item></code>. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#LayerDrawableItem_left
 * @attr ref android.R.styleable#LayerDrawableItem_top
 * @attr ref android.R.styleable#LayerDrawableItem_right
 * @attr ref android.R.styleable#LayerDrawableItem_bottom
 * @attr ref android.R.styleable#LayerDrawableItem_drawable
 * @attr ref android.R.styleable#LayerDrawableItem_id
*/
public class LayerDrawable extends Drawable implements Drawable.Callback {
    LayerState mLayerState;

    private int[] mPaddingL;
    private int[] mPaddingT;
    private int[] mPaddingR;
    private int[] mPaddingB;

    private final Rect mTmpRect = new Rect();
    private boolean mMutated;

    /**
     * Create a new layer drawable with the list of specified layers.
     *
     * @param layers A list of drawables to use as layers in this new drawable.
     */
    public LayerDrawable(Drawable[] layers) {
        this(layers, null);
    }

    /**
     * Create a new layer drawable with the specified list of layers and the specified
     * constant state.
     *
     * @param layers The list of layers to add to this drawable.
     * @param state The constant drawable state.
     */
    LayerDrawable(Drawable[] layers, LayerState state) {
        this(state, null);
        int length = layers.length;
        ChildDrawable[] r = new ChildDrawable[length];

        for (int i = 0; i < length; i++) {
            r[i] = new ChildDrawable();
            r[i].mDrawable = layers[i];
            layers[i].setCallback(this);
            mLayerState.mChildrenChangingConfigurations |= layers[i].getChangingConfigurations();
        }
        mLayerState.mNum = length;
        mLayerState.mChildren = r;

        ensurePadding();
    }
    
    LayerDrawable() {
        this((LayerState) null, null);
    }

    LayerDrawable(LayerState state, Resources res) {
        LayerState as = createConstantState(state, res);
        mLayerState = as;
        if (as.mNum > 0) {
            ensurePadding();
        }
    }

    LayerState createConstantState(LayerState state, Resources res) {
        return new LayerState(state, this, res);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        int type;

        final int innerDepth = parser.getDepth() + 1;
        int depth;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth || !parser.getName().equals("item")) {
                continue;
            }

            TypedArray a = r.obtainAttributes(attrs,
                    com.android.internal.R.styleable.LayerDrawableItem);

            int left = a.getDimensionPixelOffset(
                    com.android.internal.R.styleable.LayerDrawableItem_left, 0);
            int top = a.getDimensionPixelOffset(
                    com.android.internal.R.styleable.LayerDrawableItem_top, 0);
            int right = a.getDimensionPixelOffset(
                    com.android.internal.R.styleable.LayerDrawableItem_right, 0);
            int bottom = a.getDimensionPixelOffset(
                    com.android.internal.R.styleable.LayerDrawableItem_bottom, 0);
            int drawableRes = a.getResourceId(
                    com.android.internal.R.styleable.LayerDrawableItem_drawable, 0);
            int id = a.getResourceId(com.android.internal.R.styleable.LayerDrawableItem_id,
                    View.NO_ID);

            a.recycle();

            Drawable dr;
            if (drawableRes != 0) {
                dr = r.getDrawable(drawableRes);
            } else {
                while ((type = parser.next()) == XmlPullParser.TEXT) {
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new XmlPullParserException(parser.getPositionDescription()
                            + ": <item> tag requires a 'drawable' attribute or "
                            + "child tag defining a drawable");
                }
                dr = Drawable.createFromXmlInner(r, parser, attrs);
            }

            addLayer(dr, id, left, top, right, bottom);
        }

        ensurePadding();
        onStateChange(getState());
    }

    /**
     * Add a new layer to this drawable. The new layer is identified by an id.
     *
     * @param layer The drawable to add as a layer.
     * @param id The id of the new layer.
     * @param left The left padding of the new layer.
     * @param top The top padding of the new layer.
     * @param right The right padding of the new layer.
     * @param bottom The bottom padding of the new layer.
     */
    private void addLayer(Drawable layer, int id, int left, int top, int right, int bottom) {
        final LayerState st = mLayerState;
        int N = st.mChildren != null ? st.mChildren.length : 0;
        int i = st.mNum;
        if (i >= N) {
            ChildDrawable[] nu = new ChildDrawable[N + 10];
            if (i > 0) {
                System.arraycopy(st.mChildren, 0, nu, 0, i);
            }
            st.mChildren = nu;
        }

        mLayerState.mChildrenChangingConfigurations |= layer.getChangingConfigurations();
        
        ChildDrawable childDrawable = new ChildDrawable();
        st.mChildren[i] = childDrawable;
        childDrawable.mId = id;
        childDrawable.mDrawable = layer;
        childDrawable.mInsetL = left;
        childDrawable.mInsetT = top;
        childDrawable.mInsetR = right;
        childDrawable.mInsetB = bottom;
        st.mNum++;

        layer.setCallback(this);
    }

    /**
     * Look for a layer with the given id, and returns its {@link Drawable}.
     *
     * @param id The layer ID to search for.
     * @return The {@link Drawable} of the layer that has the given id in the hierarchy or null.
     */
    public Drawable findDrawableByLayerId(int id) {
        final ChildDrawable[] layers = mLayerState.mChildren;
        
        for (int i = mLayerState.mNum - 1; i >= 0; i--) {
            if (layers[i].mId == id) {
                return layers[i].mDrawable;
            }
        }
        
        return null;
    }
    
    /**
     * Sets the ID of a layer.
     * 
     * @param index The index of the layer which will received the ID. 
     * @param id The ID to assign to the layer.
     */
    public void setId(int index, int id) {
        mLayerState.mChildren[index].mId = id;
    }
    
    /**
     * Returns the number of layers contained within this.
     * @return The number of layers.
     */
    public int getNumberOfLayers() {
        return mLayerState.mNum;
    }

    /**
     * Returns the drawable at the specified layer index.
     *
     * @param index The layer index of the drawable to retrieve.
     *
     * @return The {@link android.graphics.drawable.Drawable} at the specified layer index.
     */
    public Drawable getDrawable(int index) {
        return mLayerState.mChildren[index].mDrawable;
    }

    /**
     * Returns the id of the specified layer.
     *
     * @param index The index of the layer.
     *
     * @return The id of the layer or {@link android.view.View#NO_ID} if the layer has no id. 
     */
    public int getId(int index) {
        return mLayerState.mChildren[index].mId;
    }
    
    /**
     * Sets (or replaces) the {@link Drawable} for the layer with the given id.
     * 
     * @param id The layer ID to search for.
     * @param drawable The replacement {@link Drawable}.
     * @return Whether the {@link Drawable} was replaced (could return false if
     *         the id was not found).
     */
    public boolean setDrawableByLayerId(int id, Drawable drawable) {
        final ChildDrawable[] layers = mLayerState.mChildren;
        
        for (int i = mLayerState.mNum - 1; i >= 0; i--) {
            if (layers[i].mId == id) {
                layers[i].mDrawable = drawable;
                return true;
            }
        }
        
        return false;
    }
    
    /** Specify modifiers to the bounds for the drawable[index].
        left += l
        top += t;
        right -= r;
        bottom -= b;
    */
    public void setLayerInset(int index, int l, int t, int r, int b) {
        ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetL = l;
        childDrawable.mInsetT = t;
        childDrawable.mInsetR = r;
        childDrawable.mInsetB = b;
    }

    // overrides from Drawable.Callback

    public void invalidateDrawable(Drawable who) {
        if (mCallback != null) {
            mCallback.invalidateDrawable(this);
        }
    }

    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        if (mCallback != null) {
            mCallback.scheduleDrawable(this, what, when);
        }
    }

    public void unscheduleDrawable(Drawable who, Runnable what) {
        if (mCallback != null) {
            mCallback.unscheduleDrawable(this, what);
        }
    }

    // overrides from Drawable

    @Override
    public void draw(Canvas canvas) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i=0; i<N; i++) {
            array[i].mDrawable.draw(canvas);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mLayerState.mChangingConfigurations
                | mLayerState.mChildrenChangingConfigurations;
    }
    
    @Override
    public boolean getPadding(Rect padding) {
        // Arbitrarily get the padding from the first image.
        // Technically we should maybe do something more intelligent,
        // like take the max padding of all the images.
        padding.left = 0;
        padding.top = 0;
        padding.right = 0;
        padding.bottom = 0;
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i=0; i<N; i++) {
            reapplyPadding(i, array[i]);
            padding.left += mPaddingL[i];
            padding.top += mPaddingT[i];
            padding.right += mPaddingR[i];
            padding.bottom += mPaddingB[i];
        }
        return true;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i=0; i<N; i++) {
            array[i].mDrawable.setVisible(visible, restart);
        }
        return changed;
    }

    @Override
    public void setDither(boolean dither) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i=0; i<N; i++) {
            array[i].mDrawable.setDither(dither);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i=0; i<N; i++) {
            array[i].mDrawable.setAlpha(alpha);
        }
    }
    
    @Override
    public void setColorFilter(ColorFilter cf) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i=0; i<N; i++) {
            array[i].mDrawable.setColorFilter(cf);
        }
    }
    
    @Override
    public int getOpacity() {
        return mLayerState.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return mLayerState.isStateful();
    }
    
    @Override
    protected boolean onStateChange(int[] state) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        boolean paddingChanged = false;
        boolean changed = false;
        for (int i=0; i<N; i++) {
            final ChildDrawable r = array[i];
            if (r.mDrawable.setState(state)) {
                changed = true;
            }
            if (reapplyPadding(i, r)) {
                paddingChanged = true;
            }
        }
        if (paddingChanged) {
            onBoundsChange(getBounds());
        }
        return changed;
    }
    
    @Override
    protected boolean onLevelChange(int level) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        boolean paddingChanged = false;
        boolean changed = false;
        for (int i=0; i<N; i++) {
            final ChildDrawable r = array[i];
            if (r.mDrawable.setLevel(level)) {
                changed = true;
            }
            if (reapplyPadding(i, r)) {
                paddingChanged = true;
            }
        }
        if (paddingChanged) {
            onBoundsChange(getBounds());
        }
        return changed;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        int padL=0, padT=0, padR=0, padB=0;
        for (int i=0; i<N; i++) {
            final ChildDrawable r = array[i];
            r.mDrawable.setBounds(bounds.left + r.mInsetL + padL,
                                  bounds.top + r.mInsetT + padT,
                                  bounds.right - r.mInsetR - padR,
                                  bounds.bottom - r.mInsetB - padB);
            padL += mPaddingL[i];
            padR += mPaddingR[i];
            padT += mPaddingT[i];
            padB += mPaddingB[i];
        }
    }

    @Override
    public int getIntrinsicWidth() {
        int width = -1;
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        int padL=0, padR=0;
        for (int i=0; i<N; i++) {
            final ChildDrawable r = array[i];
            int w = r.mDrawable.getIntrinsicWidth()
                  + r.mInsetL + r.mInsetR + padL + padR;
            if (w > width) {
                width = w;
            }
            padL += mPaddingL[i];
            padR += mPaddingR[i];
        }
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        int height = -1;
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        int padT=0, padB=0;
        for (int i=0; i<N; i++) {
            final ChildDrawable r = array[i];
            int h = r.mDrawable.getIntrinsicHeight() + r.mInsetT + r.mInsetB + + padT + padB;
            if (h > height) {
                height = h;
            }
            padT += mPaddingT[i];
            padB += mPaddingB[i];
        }
        return height;
    }

    private boolean reapplyPadding(int i, ChildDrawable r) {
        final Rect rect = mTmpRect;
        r.mDrawable.getPadding(rect);
        if (rect.left != mPaddingL[i] || rect.top != mPaddingT[i] ||
                rect.right != mPaddingR[i] || rect.bottom != mPaddingB[i]) {
            mPaddingL[i] = rect.left;
            mPaddingT[i] = rect.top;
            mPaddingR[i] = rect.right;
            mPaddingB[i] = rect.bottom;
            return true;
        }
        return false;
    }

    private void ensurePadding() {
        final int N = mLayerState.mNum;
        if (mPaddingL != null && mPaddingL.length >= N) {
            return;
        }
        mPaddingL = new int[N];
        mPaddingT = new int[N];
        mPaddingR = new int[N];
        mPaddingB = new int[N];
    }

    @Override
    public ConstantState getConstantState() {
        if (mLayerState.canConstantState()) {
            mLayerState.mChangingConfigurations = getChangingConfigurations();
            return mLayerState;
        }
        return null;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            final ChildDrawable[] array = mLayerState.mChildren;
            final int N = mLayerState.mNum;
            for (int i = 0; i < N; i++) {
                array[i].mDrawable.mutate();
            }
            mMutated = true;
        }
        return this;
    }

    static class ChildDrawable {
        public Drawable mDrawable;
        public int mInsetL, mInsetT, mInsetR, mInsetB;
        public int mId;
    }

    static class LayerState extends ConstantState {
        int mNum;
        ChildDrawable[] mChildren;

        int mChangingConfigurations;
        int mChildrenChangingConfigurations;
        
        private boolean mHaveOpacity = false;
        private int mOpacity;

        private boolean mHaveStateful = false;
        private boolean mStateful;

        private boolean mCheckedConstantState;
        private boolean mCanConstantState;

        LayerState(LayerState orig, LayerDrawable owner, Resources res) {
            if (orig != null) {
                final ChildDrawable[] origChildDrawable = orig.mChildren;
                final int N = orig.mNum;

                mNum = N;
                mChildren = new ChildDrawable[N];

                mChangingConfigurations = orig.mChangingConfigurations;
                mChildrenChangingConfigurations = orig.mChildrenChangingConfigurations;
                
                for (int i = 0; i < N; i++) {
                    final ChildDrawable r = mChildren[i] = new ChildDrawable();
                    final ChildDrawable or = origChildDrawable[i];
                    if (res != null) {
                        r.mDrawable = or.mDrawable.getConstantState().newDrawable(res);
                    } else {
                        r.mDrawable = or.mDrawable.getConstantState().newDrawable();
                    }
                    r.mDrawable.setCallback(owner);
                    r.mInsetL = or.mInsetL;
                    r.mInsetT = or.mInsetT;
                    r.mInsetR = or.mInsetR;
                    r.mInsetB = or.mInsetB;
                    r.mId = or.mId;
                }

                mHaveOpacity = orig.mHaveOpacity;
                mOpacity = orig.mOpacity;
                mHaveStateful = orig.mHaveStateful;
                mStateful = orig.mStateful;
                mCheckedConstantState = mCanConstantState = true;
            } else {
                mNum = 0;
                mChildren = null;
            }
        }

        @Override
        public Drawable newDrawable() {
            return new LayerDrawable(this, null);
        }
        
        @Override
        public Drawable newDrawable(Resources res) {
            return new LayerDrawable(this, res);
        }
        
        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        public final int getOpacity() {
            if (mHaveOpacity) {
                return mOpacity;
            }

            final int N = mNum;
            int op = N > 0 ? mChildren[0].mDrawable.getOpacity() : PixelFormat.TRANSPARENT;
            for (int i = 1; i < N; i++) {
                op = Drawable.resolveOpacity(op, mChildren[i].mDrawable.getOpacity());
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
            final int N = mNum;
            for (int i = 0; i < N; i++) {
                if (mChildren[i].mDrawable.isStateful()) {
                    stateful = true;
                    break;
                }
            }
            
            mStateful = stateful;
            mHaveStateful = true;
            return stateful;
        }

        public synchronized boolean canConstantState() {
            if (!mCheckedConstantState && mChildren != null) {
                mCanConstantState = true;
                final int N = mNum;
                for (int i=0; i<N; i++) {
                    if (mChildren[i].mDrawable.getConstantState() == null) {
                        mCanConstantState = false;
                        break;
                    }
                }
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }
    }
}

