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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * A Drawable that manages an array of other Drawables. These are drawn in array
 * order, so the element with the largest index will be drawn on top.
 * <p>
 * It can be defined in an XML file with the <code>&lt;layer-list></code> element.
 * Each Drawable in the layer is defined in a nested <code>&lt;item></code>.
 * <p>
 * For more information, see the guide to
 * <a href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.
 *
 * @attr ref android.R.styleable#LayerDrawable_paddingMode
 * @attr ref android.R.styleable#LayerDrawableItem_left
 * @attr ref android.R.styleable#LayerDrawableItem_top
 * @attr ref android.R.styleable#LayerDrawableItem_right
 * @attr ref android.R.styleable#LayerDrawableItem_bottom
 * @attr ref android.R.styleable#LayerDrawableItem_start
 * @attr ref android.R.styleable#LayerDrawableItem_end
 * @attr ref android.R.styleable#LayerDrawableItem_width
 * @attr ref android.R.styleable#LayerDrawableItem_height
 * @attr ref android.R.styleable#LayerDrawableItem_gravity
 * @attr ref android.R.styleable#LayerDrawableItem_drawable
 * @attr ref android.R.styleable#LayerDrawableItem_id
*/
public class LayerDrawable extends Drawable implements Drawable.Callback {
    private static final String LOG_TAG = "LayerDrawable";

    /**
     * Padding mode used to nest each layer inside the padding of the previous
     * layer.
     *
     * @see #setPaddingMode(int)
     */
    public static final int PADDING_MODE_NEST = 0;

    /**
     * Padding mode used to stack each layer directly atop the previous layer.
     *
     * @see #setPaddingMode(int)
     */
    public static final int PADDING_MODE_STACK = 1;

    /**
     * Value used for undefined start and end insets.
     *
     * @see #getLayerInsetStart(int)
     * @see #getLayerInsetEnd(int)
     */
    public static final int INSET_UNDEFINED = Integer.MIN_VALUE;

    @NonNull
    LayerState mLayerState;

    private int[] mPaddingL;
    private int[] mPaddingT;
    private int[] mPaddingR;
    private int[] mPaddingB;

    private final Rect mTmpRect = new Rect();
    private final Rect mTmpOutRect = new Rect();
    private final Rect mTmpContainer = new Rect();
    private Rect mHotspotBounds;
    private boolean mMutated;

    private boolean mSuspendChildInvalidation;
    private boolean mChildRequestedInvalidation;

    /**
     * Creates a new layer drawable with the list of specified layers.
     *
     * @param layers a list of drawables to use as layers in this new drawable,
     *               must be non-null
     */
    public LayerDrawable(@NonNull Drawable[] layers) {
        this(layers, null);
    }

    /**
     * Creates a new layer drawable with the specified list of layers and the
     * specified constant state.
     *
     * @param layers The list of layers to add to this drawable.
     * @param state The constant drawable state.
     */
    LayerDrawable(@NonNull Drawable[] layers, @Nullable LayerState state) {
        this(state, null);

        if (layers == null) {
            throw new IllegalArgumentException("layers must be non-null");
        }

        final int length = layers.length;
        final ChildDrawable[] r = new ChildDrawable[length];
        for (int i = 0; i < length; i++) {
            r[i] = new ChildDrawable(mLayerState.mDensity);
            r[i].mDrawable = layers[i];
            layers[i].setCallback(this);
            mLayerState.mChildrenChangingConfigurations |= layers[i].getChangingConfigurations();
        }
        mLayerState.mNumChildren = length;
        mLayerState.mChildren = r;

        ensurePadding();
        refreshPadding();
    }

    LayerDrawable() {
        this((LayerState) null, null);
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     */
    LayerDrawable(@Nullable LayerState state, @Nullable Resources res) {
        mLayerState = createConstantState(state, res);
        if (mLayerState.mNumChildren > 0) {
            ensurePadding();
            refreshPadding();
        }
    }

    LayerState createConstantState(@Nullable LayerState state, @Nullable Resources res) {
        return new LayerState(state, this, res);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        // The density may have changed since the last update. This will
        // apply scaling to any existing constant state properties.
        final LayerState state = mLayerState;
        final int density = Drawable.resolveDensity(r, 0);
        state.setDensity(density);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.LayerDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        final ChildDrawable[] array = state.mChildren;
        final int N = state.mNumChildren;
        for (int i = 0; i < N; i++) {
            final ChildDrawable layer = array[i];
            layer.setDensity(density);
        }

        inflateLayers(r, parser, attrs, theme);

        ensurePadding();
        refreshPadding();
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final LayerState state = mLayerState;
        final int density = Drawable.resolveDensity(t.getResources(), 0);
        state.setDensity(density);

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrs, R.styleable.LayerDrawable);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        final ChildDrawable[] array = state.mChildren;
        final int N = state.mNumChildren;
        for (int i = 0; i < N; i++) {
            final ChildDrawable layer = array[i];
            layer.setDensity(density);

            if (layer.mThemeAttrs != null) {
                final TypedArray a = t.resolveAttributes(
                        layer.mThemeAttrs, R.styleable.LayerDrawableItem);
                updateLayerFromTypedArray(layer, a);
                a.recycle();
            }

            final Drawable d = layer.mDrawable;
            if (d != null && d.canApplyTheme()) {
                d.applyTheme(t);

                // Update cached mask of child changing configurations.
                state.mChildrenChangingConfigurations |= d.getChangingConfigurations();
            }
        }
    }

    /**
     * Inflates child layers using the specified parser.
     */
    private void inflateLayers(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final LayerState state = mLayerState;

        final int innerDepth = parser.getDepth() + 1;
        int type;
        int depth;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth || !parser.getName().equals("item")) {
                continue;
            }

            final ChildDrawable layer = new ChildDrawable(state.mDensity);
            final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.LayerDrawableItem);
            updateLayerFromTypedArray(layer, a);
            a.recycle();

            // If the layer doesn't have a drawable or unresolved theme
            // attribute for a drawable, attempt to parse one from the child
            // element. If multiple child elements exist, we'll only use the
            // first one.
            if (layer.mDrawable == null && (layer.mThemeAttrs == null ||
                    layer.mThemeAttrs[R.styleable.LayerDrawableItem_drawable] == 0)) {
                while ((type = parser.next()) == XmlPullParser.TEXT) {
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new XmlPullParserException(parser.getPositionDescription()
                            + ": <item> tag requires a 'drawable' attribute or "
                            + "child tag defining a drawable");
                }

                // We found a child drawable. Take ownership.
                layer.mDrawable = Drawable.createFromXmlInner(r, parser, attrs, theme);
                layer.mDrawable.setCallback(this);
                state.mChildrenChangingConfigurations |=
                        layer.mDrawable.getChangingConfigurations();
            }

            addLayer(layer);
        }
    }

    /**
     * Initializes the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(@NonNull TypedArray a) {
        final LayerState state = mLayerState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            final int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.LayerDrawable_opacity:
                    state.mOpacityOverride = a.getInt(attr, state.mOpacityOverride);
                    break;
                case R.styleable.LayerDrawable_paddingTop:
                    state.mPaddingTop = a.getDimensionPixelOffset(attr, state.mPaddingTop);
                    break;
                case R.styleable.LayerDrawable_paddingBottom:
                    state.mPaddingBottom = a.getDimensionPixelOffset(attr, state.mPaddingBottom);
                    break;
                case R.styleable.LayerDrawable_paddingLeft:
                    state.mPaddingLeft = a.getDimensionPixelOffset(attr, state.mPaddingLeft);
                    break;
                case R.styleable.LayerDrawable_paddingRight:
                    state.mPaddingRight = a.getDimensionPixelOffset(attr, state.mPaddingRight);
                    break;
                case R.styleable.LayerDrawable_paddingStart:
                    state.mPaddingStart = a.getDimensionPixelOffset(attr, state.mPaddingStart);
                    break;
                case R.styleable.LayerDrawable_paddingEnd:
                    state.mPaddingEnd = a.getDimensionPixelOffset(attr, state.mPaddingEnd);
                    break;
                case R.styleable.LayerDrawable_autoMirrored:
                    state.mAutoMirrored = a.getBoolean(attr, state.mAutoMirrored);
                    break;
                case R.styleable.LayerDrawable_paddingMode:
                    state.mPaddingMode = a.getInteger(attr, state.mPaddingMode);
                    break;
            }
        }
    }

    private void updateLayerFromTypedArray(@NonNull ChildDrawable layer, @NonNull TypedArray a) {
        final LayerState state = mLayerState;

        // Account for any configuration changes.
        state.mChildrenChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        layer.mThemeAttrs = a.extractThemeAttrs();

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            final int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.LayerDrawableItem_left:
                    layer.mInsetL = a.getDimensionPixelOffset(attr, layer.mInsetL);
                    break;
                case R.styleable.LayerDrawableItem_top:
                    layer.mInsetT = a.getDimensionPixelOffset(attr, layer.mInsetT);
                    break;
                case R.styleable.LayerDrawableItem_right:
                    layer.mInsetR = a.getDimensionPixelOffset(attr, layer.mInsetR);
                    break;
                case R.styleable.LayerDrawableItem_bottom:
                    layer.mInsetB = a.getDimensionPixelOffset(attr, layer.mInsetB);
                    break;
                case R.styleable.LayerDrawableItem_start:
                    layer.mInsetS = a.getDimensionPixelOffset(attr, layer.mInsetS);
                    break;
                case R.styleable.LayerDrawableItem_end:
                    layer.mInsetE = a.getDimensionPixelOffset(attr, layer.mInsetE);
                    break;
                case R.styleable.LayerDrawableItem_width:
                    layer.mWidth = a.getDimensionPixelSize(attr, layer.mWidth);
                    break;
                case R.styleable.LayerDrawableItem_height:
                    layer.mHeight = a.getDimensionPixelSize(attr, layer.mHeight);
                    break;
                case R.styleable.LayerDrawableItem_gravity:
                    layer.mGravity = a.getInteger(attr, layer.mGravity);
                    break;
                case R.styleable.LayerDrawableItem_id:
                    layer.mId = a.getResourceId(attr, layer.mId);
                    break;
            }
        }

        final Drawable dr = a.getDrawable(R.styleable.LayerDrawableItem_drawable);
        if (dr != null) {
            if (layer.mDrawable != null) {
                // It's possible that a drawable was already set, in which case
                // we should clear the callback. We may have also integrated the
                // drawable's changing configurations, but we don't have enough
                // information to revert that change.
                layer.mDrawable.setCallback(null);
            }

            // Take ownership of the new drawable.
            layer.mDrawable = dr;
            layer.mDrawable.setCallback(this);
            state.mChildrenChangingConfigurations |=
                    layer.mDrawable.getChangingConfigurations();
        }
    }

    @Override
    public boolean canApplyTheme() {
        return mLayerState.canApplyTheme() || super.canApplyTheme();
    }

    /**
     * @hide
     */
    @Override
    public boolean isProjected() {
        if (super.isProjected()) {
            return true;
        }

        final ChildDrawable[] layers = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            if (layers[i].mDrawable.isProjected()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Adds a new layer at the end of list of layers and returns its index.
     *
     * @param layer The layer to add.
     * @return The index of the layer.
     */
    int addLayer(@NonNull ChildDrawable layer) {
        final LayerState st = mLayerState;
        final int N = st.mChildren != null ? st.mChildren.length : 0;
        final int i = st.mNumChildren;
        if (i >= N) {
            final ChildDrawable[] nu = new ChildDrawable[N + 10];
            if (i > 0) {
                System.arraycopy(st.mChildren, 0, nu, 0, i);
            }

            st.mChildren = nu;
        }

        st.mChildren[i] = layer;
        st.mNumChildren++;
        st.invalidateCache();
        return i;
    }

    /**
     * Add a new layer to this drawable. The new layer is identified by an id.
     *
     * @param dr The drawable to add as a layer.
     * @param themeAttrs Theme attributes extracted from the layer.
     * @param id The id of the new layer.
     * @param left The left padding of the new layer.
     * @param top The top padding of the new layer.
     * @param right The right padding of the new layer.
     * @param bottom The bottom padding of the new layer.
     */
    ChildDrawable addLayer(Drawable dr, int[] themeAttrs, int id,
            int left, int top, int right, int bottom) {
        final ChildDrawable childDrawable = createLayer(dr);
        childDrawable.mId = id;
        childDrawable.mThemeAttrs = themeAttrs;
        childDrawable.mDrawable.setAutoMirrored(isAutoMirrored());
        childDrawable.mInsetL = left;
        childDrawable.mInsetT = top;
        childDrawable.mInsetR = right;
        childDrawable.mInsetB = bottom;

        addLayer(childDrawable);

        mLayerState.mChildrenChangingConfigurations |= dr.getChangingConfigurations();
        dr.setCallback(this);

        return childDrawable;
    }

    private ChildDrawable createLayer(Drawable dr) {
        final ChildDrawable layer = new ChildDrawable(mLayerState.mDensity);
        layer.mDrawable = dr;
        return layer;
    }

    /**
     * Adds a new layer containing the specified {@code drawable} to the end of
     * the layer list and returns its index.
     *
     * @param dr The drawable to add as a new layer.
     * @return The index of the new layer.
     */
    public int addLayer(Drawable dr) {
        final ChildDrawable layer = createLayer(dr);
        final int index = addLayer(layer);
        ensurePadding();
        refreshChildPadding(index, layer);
        return index;
    }

    /**
     * Looks for a layer with the given ID and returns its {@link Drawable}.
     * <p>
     * If multiple layers are found for the given ID, returns the
     * {@link Drawable} for the matching layer at the highest index.
     *
     * @param id The layer ID to search for.
     * @return The {@link Drawable} for the highest-indexed layer that has the
     *         given ID, or null if not found.
     */
    public Drawable findDrawableByLayerId(int id) {
        final ChildDrawable[] layers = mLayerState.mChildren;
        for (int i = mLayerState.mNumChildren - 1; i >= 0; i--) {
            if (layers[i].mId == id) {
                return layers[i].mDrawable;
            }
        }

        return null;
    }

    /**
     * Sets the ID of a layer.
     *
     * @param index The index of the layer to modify, must be in the range
     *              {@code 0...getNumberOfLayers()-1}.
     * @param id The id to assign to the layer.
     *
     * @see #getId(int)
     * @attr ref android.R.styleable#LayerDrawableItem_id
     */
    public void setId(int index, int id) {
        mLayerState.mChildren[index].mId = id;
    }

    /**
     * Returns the ID of the specified layer.
     *
     * @param index The index of the layer, must be in the range
     *              {@code 0...getNumberOfLayers()-1}.
     * @return The id of the layer or {@link android.view.View#NO_ID} if the
     *         layer has no id.
     *
     * @see #setId(int, int)
     * @attr ref android.R.styleable#LayerDrawableItem_id
     */
    public int getId(int index) {
        if (index >= mLayerState.mNumChildren) {
            throw new IndexOutOfBoundsException();
        }
        return mLayerState.mChildren[index].mId;
    }

    /**
     * Returns the number of layers contained within this layer drawable.
     *
     * @return The number of layers.
     */
    public int getNumberOfLayers() {
        return mLayerState.mNumChildren;
    }

    /**
     * Replaces the {@link Drawable} for the layer with the given id.
     *
     * @param id The layer ID to search for.
     * @param drawable The replacement {@link Drawable}.
     * @return Whether the {@link Drawable} was replaced (could return false if
     *         the id was not found).
     */
    public boolean setDrawableByLayerId(int id, Drawable drawable) {
        final int index = findIndexByLayerId(id);
        if (index < 0) {
            return false;
        }

        setDrawable(index, drawable);
        return true;
    }

    /**
     * Returns the layer with the specified {@code id}.
     * <p>
     * If multiple layers have the same ID, returns the layer with the lowest
     * index.
     *
     * @param id The ID of the layer to return.
     * @return The index of the layer with the specified ID.
     */
    public int findIndexByLayerId(int id) {
        final ChildDrawable[] layers = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final ChildDrawable childDrawable = layers[i];
            if (childDrawable.mId == id) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Sets the drawable for the layer at the specified index.
     *
     * @param index The index of the layer to modify, must be in the range
     *              {@code 0...getNumberOfLayers()-1}.
     * @param drawable The drawable to set for the layer.
     *
     * @see #getDrawable(int)
     * @attr ref android.R.styleable#LayerDrawableItem_drawable
     */
    public void setDrawable(int index, Drawable drawable) {
        if (index >= mLayerState.mNumChildren) {
            throw new IndexOutOfBoundsException();
        }

        final ChildDrawable[] layers = mLayerState.mChildren;
        final ChildDrawable childDrawable = layers[index];
        if (childDrawable.mDrawable != null) {
            if (drawable != null) {
                final Rect bounds = childDrawable.mDrawable.getBounds();
                drawable.setBounds(bounds);
            }

            childDrawable.mDrawable.setCallback(null);
        }

        if (drawable != null) {
            drawable.setCallback(this);
        }

        childDrawable.mDrawable = drawable;
        mLayerState.invalidateCache();

        refreshChildPadding(index, childDrawable);
    }

    /**
     * Returns the drawable for the layer at the specified index.
     *
     * @param index The index of the layer, must be in the range
     *              {@code 0...getNumberOfLayers()-1}.
     * @return The {@link Drawable} at the specified layer index.
     *
     * @see #setDrawable(int, Drawable)
     * @attr ref android.R.styleable#LayerDrawableItem_drawable
     */
    public Drawable getDrawable(int index) {
        if (index >= mLayerState.mNumChildren) {
            throw new IndexOutOfBoundsException();
        }
        return mLayerState.mChildren[index].mDrawable;
    }

    /**
     * Sets an explicit size for the specified layer.
     * <p>
     * <strong>Note:</strong> Setting an explicit layer size changes the
     * default layer gravity behavior. See {@link #setLayerGravity(int, int)}
     * for more information.
     *
     * @param index the index of the layer to adjust
     * @param w width in pixels, or -1 to use the intrinsic width
     * @param h height in pixels, or -1 to use the intrinsic height
     * @see #getLayerWidth(int)
     * @see #getLayerHeight(int)
     * @attr ref android.R.styleable#LayerDrawableItem_width
     * @attr ref android.R.styleable#LayerDrawableItem_height
     */
    public void setLayerSize(int index, int w, int h) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mWidth = w;
        childDrawable.mHeight = h;
    }

    /**
     * @param index the index of the layer to adjust
     * @param w width in pixels, or -1 to use the intrinsic width
     * @attr ref android.R.styleable#LayerDrawableItem_width
     */
    public void setLayerWidth(int index, int w) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mWidth = w;
    }

    /**
     * @param index the index of the drawable to adjust
     * @return the explicit width of the layer, or -1 if not specified
     * @see #setLayerSize(int, int, int)
     * @attr ref android.R.styleable#LayerDrawableItem_width
     */
    public int getLayerWidth(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mWidth;
    }

    /**
     * @param index the index of the layer to adjust
     * @param h height in pixels, or -1 to use the intrinsic height
     * @attr ref android.R.styleable#LayerDrawableItem_height
     */
    public void setLayerHeight(int index, int h) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mHeight = h;
    }

    /**
     * @param index the index of the drawable to adjust
     * @return the explicit height of the layer, or -1 if not specified
     * @see #setLayerSize(int, int, int)
     * @attr ref android.R.styleable#LayerDrawableItem_height
     */
    public int getLayerHeight(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mHeight;
    }

    /**
     * Sets the gravity used to position or stretch the specified layer within
     * its container. Gravity is applied after any layer insets (see
     * {@link #setLayerInset(int, int, int, int, int)}) or padding (see
     * {@link #setPaddingMode(int)}).
     * <p>
     * If gravity is specified as {@link Gravity#NO_GRAVITY}, the default
     * behavior depends on whether an explicit width or height has been set
     * (see {@link #setLayerSize(int, int, int)}), If a dimension is not set,
     * gravity in that direction defaults to {@link Gravity#FILL_HORIZONTAL} or
     * {@link Gravity#FILL_VERTICAL}; otherwise, gravity in that direction
     * defaults to {@link Gravity#LEFT} or {@link Gravity#TOP}.
     *
     * @param index the index of the drawable to adjust
     * @param gravity the gravity to set for the layer
     *
     * @see #getLayerGravity(int)
     * @attr ref android.R.styleable#LayerDrawableItem_gravity
     */
    public void setLayerGravity(int index, int gravity) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mGravity = gravity;
    }

    /**
     * @param index the index of the layer
     * @return the gravity used to position or stretch the specified layer
     *         within its container
     *
     * @see #setLayerGravity(int, int)
     * @attr ref android.R.styleable#LayerDrawableItem_gravity
     */
    public int getLayerGravity(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mGravity;
    }

    /**
     * Specifies the insets in pixels for the drawable at the specified index.
     *
     * @param index the index of the drawable to adjust
     * @param l number of pixels to add to the left bound
     * @param t number of pixels to add to the top bound
     * @param r number of pixels to subtract from the right bound
     * @param b number of pixels to subtract from the bottom bound
     *
     * @attr ref android.R.styleable#LayerDrawableItem_left
     * @attr ref android.R.styleable#LayerDrawableItem_top
     * @attr ref android.R.styleable#LayerDrawableItem_right
     * @attr ref android.R.styleable#LayerDrawableItem_bottom
     */
    public void setLayerInset(int index, int l, int t, int r, int b) {
        setLayerInsetInternal(index, l, t, r, b, INSET_UNDEFINED, INSET_UNDEFINED);
    }

    /**
     * Specifies the relative insets in pixels for the drawable at the
     * specified index.
     *
     * @param index the index of the layer to adjust
     * @param s number of pixels to inset from the start bound
     * @param t number of pixels to inset from the top bound
     * @param e number of pixels to inset from the end bound
     * @param b number of pixels to inset from the bottom bound
     *
     * @attr ref android.R.styleable#LayerDrawableItem_start
     * @attr ref android.R.styleable#LayerDrawableItem_top
     * @attr ref android.R.styleable#LayerDrawableItem_end
     * @attr ref android.R.styleable#LayerDrawableItem_bottom
     */
    public void setLayerInsetRelative(int index, int s, int t, int e, int b) {
        setLayerInsetInternal(index, 0, t, 0, b, s, e);
    }

    /**
     * @param index the index of the layer to adjust
     * @param l number of pixels to inset from the left bound
     * @attr ref android.R.styleable#LayerDrawableItem_left
     */
    public void setLayerInsetLeft(int index, int l) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetL = l;
    }

    /**
     * @param index the index of the layer
     * @return number of pixels to inset from the left bound
     * @attr ref android.R.styleable#LayerDrawableItem_left
     */
    public int getLayerInsetLeft(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mInsetL;
    }

    /**
     * @param index the index of the layer to adjust
     * @param r number of pixels to inset from the right bound
     * @attr ref android.R.styleable#LayerDrawableItem_right
     */
    public void setLayerInsetRight(int index, int r) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetR = r;
    }

    /**
     * @param index the index of the layer
     * @return number of pixels to inset from the right bound
     * @attr ref android.R.styleable#LayerDrawableItem_right
     */
    public int getLayerInsetRight(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mInsetR;
    }

    /**
     * @param index the index of the layer to adjust
     * @param t number of pixels to inset from the top bound
     * @attr ref android.R.styleable#LayerDrawableItem_top
     */
    public void setLayerInsetTop(int index, int t) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetT = t;
    }

    /**
     * @param index the index of the layer
     * @return number of pixels to inset from the top bound
     * @attr ref android.R.styleable#LayerDrawableItem_top
     */
    public int getLayerInsetTop(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mInsetT;
    }

    /**
     * @param index the index of the layer to adjust
     * @param b number of pixels to inset from the bottom bound
     * @attr ref android.R.styleable#LayerDrawableItem_bottom
     */
    public void setLayerInsetBottom(int index, int b) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetB = b;
    }

    /**
     * @param index the index of the layer
     * @return number of pixels to inset from the bottom bound
     * @attr ref android.R.styleable#LayerDrawableItem_bottom
     */
    public int getLayerInsetBottom(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mInsetB;
    }

    /**
     * @param index the index of the layer to adjust
     * @param s number of pixels to inset from the start bound
     * @attr ref android.R.styleable#LayerDrawableItem_start
     */
    public void setLayerInsetStart(int index, int s) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetS = s;
    }

    /**
     * @param index the index of the layer
     * @return the number of pixels to inset from the start bound, or
     *         {@link #INSET_UNDEFINED} if not specified
     * @attr ref android.R.styleable#LayerDrawableItem_start
     */
    public int getLayerInsetStart(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mInsetS;
    }

    /**
     * @param index the index of the layer to adjust
     * @param e number of pixels to inset from the end bound, or
     *         {@link #INSET_UNDEFINED} if not specified
     * @attr ref android.R.styleable#LayerDrawableItem_end
     */
    public void setLayerInsetEnd(int index, int e) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetE = e;
    }

    /**
     * @param index the index of the layer
     * @return number of pixels to inset from the end bound
     * @attr ref android.R.styleable#LayerDrawableItem_end
     */
    public int getLayerInsetEnd(int index) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        return childDrawable.mInsetE;
    }

    private void setLayerInsetInternal(int index, int l, int t, int r, int b, int s, int e) {
        final ChildDrawable childDrawable = mLayerState.mChildren[index];
        childDrawable.mInsetL = l;
        childDrawable.mInsetT = t;
        childDrawable.mInsetR = r;
        childDrawable.mInsetB = b;
        childDrawable.mInsetS = s;
        childDrawable.mInsetE = e;
    }

    /**
     * Specifies how layer padding should affect the bounds of subsequent
     * layers. The default value is {@link #PADDING_MODE_NEST}.
     *
     * @param mode padding mode, one of:
     *            <ul>
     *            <li>{@link #PADDING_MODE_NEST} to nest each layer inside the
     *            padding of the previous layer
     *            <li>{@link #PADDING_MODE_STACK} to stack each layer directly
     *            atop the previous layer
     *            </ul>
     *
     * @see #getPaddingMode()
     * @attr ref android.R.styleable#LayerDrawable_paddingMode
     */
    public void setPaddingMode(int mode) {
        if (mLayerState.mPaddingMode != mode) {
            mLayerState.mPaddingMode = mode;
        }
    }

    /**
     * @return the current padding mode
     *
     * @see #setPaddingMode(int)
     * @attr ref android.R.styleable#LayerDrawable_paddingMode
     */
    public int getPaddingMode() {
      return mLayerState.mPaddingMode;
    }

    /**
     * Temporarily suspends child invalidation.
     *
     * @see #resumeChildInvalidation()
     */
    private void suspendChildInvalidation() {
        mSuspendChildInvalidation = true;
    }

    /**
     * Resumes child invalidation after suspension, immediately performing an
     * invalidation if one was requested by a child during suspension.
     *
     * @see #suspendChildInvalidation()
     */
    private void resumeChildInvalidation() {
        mSuspendChildInvalidation = false;

        if (mChildRequestedInvalidation) {
            mChildRequestedInvalidation = false;
            invalidateSelf();
        }
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        if (mSuspendChildInvalidation) {
            mChildRequestedInvalidation = true;
        } else {
            // This may have been called as the result of a tint changing, in
            // which case we may need to refresh the cached statefulness or
            // opacity.
            mLayerState.invalidateCache();

            invalidateSelf();
        }
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }

    @Override
    public void draw(Canvas canvas) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.draw(canvas);
            }
        }
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mLayerState.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(Rect padding) {
        final LayerState layerState = mLayerState;
        if (layerState.mPaddingMode == PADDING_MODE_NEST) {
            computeNestedPadding(padding);
        } else {
            computeStackedPadding(padding);
        }

        final int paddingT = layerState.mPaddingTop;
        final int paddingB = layerState.mPaddingBottom;

        // Resolve padding for RTL. Relative padding overrides absolute
        // padding.
        final boolean isLayoutRtl = getLayoutDirection() == LayoutDirection.RTL;
        final int paddingRtlL = isLayoutRtl ? layerState.mPaddingEnd : layerState.mPaddingStart;
        final int paddingRtlR = isLayoutRtl ? layerState.mPaddingStart : layerState.mPaddingEnd;
        final int paddingL = paddingRtlL >= 0 ? paddingRtlL : layerState.mPaddingLeft;
        final int paddingR = paddingRtlR >= 0 ? paddingRtlR : layerState.mPaddingRight;

        // If padding was explicitly specified (e.g. not -1) then override the
        // computed padding in that dimension.
        if (paddingL >= 0) {
            padding.left = paddingL;
        }

        if (paddingT >= 0) {
            padding.top = paddingT;
        }

        if (paddingR >= 0) {
            padding.right = paddingR;
        }

        if (paddingB >= 0) {
            padding.bottom = paddingB;
        }

        return padding.left != 0 || padding.top != 0 || padding.right != 0 || padding.bottom != 0;
    }

    /**
     * Sets the absolute padding.
     * <p>
     * If padding in a dimension is specified as {@code -1}, the resolved
     * padding will use the value computed according to the padding mode (see
     * {@link #setPaddingMode(int)}).
     * <p>
     * Calling this method clears any relative padding values previously set
     * using {@link #setPaddingRelative(int, int, int, int)}.
     *
     * @param left the left padding in pixels, or -1 to use computed padding
     * @param top the top padding in pixels, or -1 to use computed padding
     * @param right the right padding in pixels, or -1 to use computed padding
     * @param bottom the bottom padding in pixels, or -1 to use computed
     *               padding
     * @attr ref android.R.styleable#LayerDrawable_paddingLeft
     * @attr ref android.R.styleable#LayerDrawable_paddingTop
     * @attr ref android.R.styleable#LayerDrawable_paddingRight
     * @attr ref android.R.styleable#LayerDrawable_paddingBottom
     * @see #setPaddingRelative(int, int, int, int)
     */
    public void setPadding(int left, int top, int right, int bottom) {
        final LayerState layerState = mLayerState;
        layerState.mPaddingLeft = left;
        layerState.mPaddingTop = top;
        layerState.mPaddingRight = right;
        layerState.mPaddingBottom = bottom;

        // Clear relative padding values.
        layerState.mPaddingStart = -1;
        layerState.mPaddingEnd = -1;
    }

    /**
     * Sets the relative padding.
     * <p>
     * If padding in a dimension is specified as {@code -1}, the resolved
     * padding will use the value computed according to the padding mode (see
     * {@link #setPaddingMode(int)}).
     * <p>
     * Calling this method clears any absolute padding values previously set
     * using {@link #setPadding(int, int, int, int)}.
     *
     * @param start the start padding in pixels, or -1 to use computed padding
     * @param top the top padding in pixels, or -1 to use computed padding
     * @param end the end padding in pixels, or -1 to use computed padding
     * @param bottom the bottom padding in pixels, or -1 to use computed
     *               padding
     * @attr ref android.R.styleable#LayerDrawable_paddingStart
     * @attr ref android.R.styleable#LayerDrawable_paddingTop
     * @attr ref android.R.styleable#LayerDrawable_paddingEnd
     * @attr ref android.R.styleable#LayerDrawable_paddingBottom
     * @see #setPadding(int, int, int, int)
     */
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        final LayerState layerState = mLayerState;
        layerState.mPaddingStart = start;
        layerState.mPaddingTop = top;
        layerState.mPaddingEnd = end;
        layerState.mPaddingBottom = bottom;

        // Clear absolute padding values.
        layerState.mPaddingLeft = -1;
        layerState.mPaddingRight = -1;
    }

    /**
     * Returns the left padding in pixels.
     * <p>
     * A return value of {@code -1} means there is no explicit padding set for
     * this dimension. As a result, the value for this dimension returned by
     * {@link #getPadding(Rect)} will be computed from the child layers
     * according to the padding mode (see {@link #getPaddingMode()}.
     *
     * @return the left padding in pixels, or -1 if not explicitly specified
     * @see #setPadding(int, int, int, int)
     * @see #getPadding(Rect)
     */
    public int getLeftPadding() {
        return mLayerState.mPaddingLeft;
    }

    /**
     * Returns the right padding in pixels.
     * <p>
     * A return value of {@code -1} means there is no explicit padding set for
     * this dimension. As a result, the value for this dimension returned by
     * {@link #getPadding(Rect)} will be computed from the child layers
     * according to the padding mode (see {@link #getPaddingMode()}.
     *
     * @return the right padding in pixels, or -1 if not explicitly specified
     * @see #setPadding(int, int, int, int)
     * @see #getPadding(Rect)
     */
    public int getRightPadding() {
        return mLayerState.mPaddingRight;
    }

    /**
     * Returns the start padding in pixels.
     * <p>
     * A return value of {@code -1} means there is no explicit padding set for
     * this dimension. As a result, the value for this dimension returned by
     * {@link #getPadding(Rect)} will be computed from the child layers
     * according to the padding mode (see {@link #getPaddingMode()}.
     *
     * @return the start padding in pixels, or -1 if not explicitly specified
     * @see #setPaddingRelative(int, int, int, int)
     * @see #getPadding(Rect)
     */
    public int getStartPadding() {
        return mLayerState.mPaddingStart;
    }

    /**
     * Returns the end padding in pixels.
     * <p>
     * A return value of {@code -1} means there is no explicit padding set for
     * this dimension. As a result, the value for this dimension returned by
     * {@link #getPadding(Rect)} will be computed from the child layers
     * according to the padding mode (see {@link #getPaddingMode()}.
     *
     * @return the end padding in pixels, or -1 if not explicitly specified
     * @see #setPaddingRelative(int, int, int, int)
     * @see #getPadding(Rect)
     */
    public int getEndPadding() {
        return mLayerState.mPaddingEnd;
    }

    /**
     * Returns the top padding in pixels.
     * <p>
     * A return value of {@code -1} means there is no explicit padding set for
     * this dimension. As a result, the value for this dimension returned by
     * {@link #getPadding(Rect)} will be computed from the child layers
     * according to the padding mode (see {@link #getPaddingMode()}.
     *
     * @return the top padding in pixels, or -1 if not explicitly specified
     * @see #setPadding(int, int, int, int)
     * @see #setPaddingRelative(int, int, int, int)
     * @see #getPadding(Rect)
     */
    public int getTopPadding() {
        return mLayerState.mPaddingTop;
    }

    /**
     * Returns the bottom padding in pixels.
     * <p>
     * A return value of {@code -1} means there is no explicit padding set for
     * this dimension. As a result, the value for this dimension returned by
     * {@link #getPadding(Rect)} will be computed from the child layers
     * according to the padding mode (see {@link #getPaddingMode()}.
     *
     * @return the bottom padding in pixels, or -1 if not explicitly specified
     * @see #setPadding(int, int, int, int)
     * @see #setPaddingRelative(int, int, int, int)
     * @see #getPadding(Rect)
     */
    public int getBottomPadding() {
        return mLayerState.mPaddingBottom;
    }

    private void computeNestedPadding(Rect padding) {
        padding.left = 0;
        padding.top = 0;
        padding.right = 0;
        padding.bottom = 0;

        // Add all the padding.
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            refreshChildPadding(i, array[i]);

            padding.left += mPaddingL[i];
            padding.top += mPaddingT[i];
            padding.right += mPaddingR[i];
            padding.bottom += mPaddingB[i];
        }
    }

    private void computeStackedPadding(Rect padding) {
        padding.left = 0;
        padding.top = 0;
        padding.right = 0;
        padding.bottom = 0;

        // Take the max padding.
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            refreshChildPadding(i, array[i]);

            padding.left = Math.max(padding.left, mPaddingL[i]);
            padding.top = Math.max(padding.top, mPaddingT[i]);
            padding.right = Math.max(padding.right, mPaddingR[i]);
            padding.bottom = Math.max(padding.bottom, mPaddingB[i]);
        }
    }

    /**
     * Populates <code>outline</code> with the first available (non-empty) layer outline.
     *
     * @param outline Outline in which to place the first available layer outline
     */
    @Override
    public void getOutline(@NonNull Outline outline) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.getOutline(outline);
                if (!outline.isEmpty()) {
                    return;
                }
            }
        }
    }

    @Override
    public void setHotspot(float x, float y) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setHotspot(x, y);
            }
        }
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setHotspotBounds(left, top, right, bottom);
            }
        }

        if (mHotspotBounds == null) {
            mHotspotBounds = new Rect(left, top, right, bottom);
        } else {
            mHotspotBounds.set(left, top, right, bottom);
        }
    }

    @Override
    public void getHotspotBounds(Rect outRect) {
        if (mHotspotBounds != null) {
            outRect.set(mHotspotBounds);
        } else {
            super.getHotspotBounds(outRect);
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setVisible(visible, restart);
            }
        }

        return changed;
    }

    @Override
    public void setDither(boolean dither) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setDither(dither);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setAlpha(alpha);
            }
        }
    }

    @Override
    public int getAlpha() {
        final Drawable dr = getFirstNonNullDrawable();
        if (dr != null) {
            return dr.getAlpha();
        } else {
            return super.getAlpha();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setColorFilter(colorFilter);
            }
        }
    }

    @Override
    public void setTintList(ColorStateList tint) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setTintList(tint);
            }
        }
    }

    @Override
    public void setTintMode(Mode tintMode) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setTintMode(tintMode);
            }
        }
    }

    private Drawable getFirstNonNullDrawable() {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                return dr;
            }
        }
        return null;
    }

    /**
     * Sets the opacity of this drawable directly instead of collecting the
     * states from the layers.
     *
     * @param opacity The opacity to use, or {@link PixelFormat#UNKNOWN
     *            PixelFormat.UNKNOWN} for the default behavior
     * @see PixelFormat#UNKNOWN
     * @see PixelFormat#TRANSLUCENT
     * @see PixelFormat#TRANSPARENT
     * @see PixelFormat#OPAQUE
     */
    public void setOpacity(int opacity) {
        mLayerState.mOpacityOverride = opacity;
    }

    @Override
    public int getOpacity() {
        if (mLayerState.mOpacityOverride != PixelFormat.UNKNOWN) {
            return mLayerState.mOpacityOverride;
        }
        return mLayerState.getOpacity();
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        mLayerState.mAutoMirrored = mirrored;

        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.setAutoMirrored(mirrored);
            }
        }
    }

    @Override
    public boolean isAutoMirrored() {
        return mLayerState.mAutoMirrored;
    }

    @Override
    public void jumpToCurrentState() {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.jumpToCurrentState();
            }
        }
    }

    @Override
    public boolean isStateful() {
        return mLayerState.isStateful();
    }

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        return mLayerState.hasFocusStateSpecified();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = false;

        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null && dr.isStateful() && dr.setState(state)) {
                refreshChildPadding(i, array[i]);
                changed = true;
            }
        }

        if (changed) {
            updateLayerBounds(getBounds());
        }

        return changed;
    }

    @Override
    protected boolean onLevelChange(int level) {
        boolean changed = false;

        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null && dr.setLevel(level)) {
                refreshChildPadding(i, array[i]);
                changed = true;
            }
        }

        if (changed) {
            updateLayerBounds(getBounds());
        }

        return changed;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        updateLayerBounds(bounds);
    }

    private void updateLayerBounds(Rect bounds) {
        try {
            suspendChildInvalidation();
            updateLayerBoundsInternal(bounds);
        } finally {
            resumeChildInvalidation();
        }
    }

    private void updateLayerBoundsInternal(Rect bounds) {
        int paddingL = 0;
        int paddingT = 0;
        int paddingR = 0;
        int paddingB = 0;

        final Rect outRect = mTmpOutRect;
        final int layoutDirection = getLayoutDirection();
        final boolean isLayoutRtl = layoutDirection == LayoutDirection.RTL;
        final boolean isPaddingNested = mLayerState.mPaddingMode == PADDING_MODE_NEST;
        final ChildDrawable[] array = mLayerState.mChildren;

        for (int i = 0, count = mLayerState.mNumChildren; i < count; i++) {
            final ChildDrawable r = array[i];
            final Drawable d = r.mDrawable;
            if (d == null) {
                continue;
            }

            final int insetT = r.mInsetT;
            final int insetB = r.mInsetB;

            // Resolve insets for RTL. Relative insets override absolute
            // insets.
            final int insetRtlL = isLayoutRtl ? r.mInsetE : r.mInsetS;
            final int insetRtlR = isLayoutRtl ? r.mInsetS : r.mInsetE;
            final int insetL = insetRtlL == INSET_UNDEFINED ? r.mInsetL : insetRtlL;
            final int insetR = insetRtlR == INSET_UNDEFINED ? r.mInsetR : insetRtlR;

            // Establish containing region based on aggregate padding and
            // requested insets for the current layer.
            final Rect container = mTmpContainer;
            container.set(bounds.left + insetL + paddingL, bounds.top + insetT + paddingT,
                    bounds.right - insetR - paddingR, bounds.bottom - insetB - paddingB);

            // Compute a reasonable default gravity based on the intrinsic and
            // explicit dimensions, if specified.
            final int intrinsicW = d.getIntrinsicWidth();
            final int intrinsicH = d.getIntrinsicHeight();
            final int layerW = r.mWidth;
            final int layerH = r.mHeight;
            final int gravity = resolveGravity(r.mGravity, layerW, layerH, intrinsicW, intrinsicH);

            // Explicit dimensions override intrinsic dimensions.
            final int resolvedW = layerW < 0 ? intrinsicW : layerW;
            final int resolvedH = layerH < 0 ? intrinsicH : layerH;
            Gravity.apply(gravity, resolvedW, resolvedH, container, outRect, layoutDirection);
            d.setBounds(outRect);

            if (isPaddingNested) {
                paddingL += mPaddingL[i];
                paddingR += mPaddingR[i];
                paddingT += mPaddingT[i];
                paddingB += mPaddingB[i];
            }
        }
    }

    /**
     * Resolves layer gravity given explicit gravity and dimensions.
     * <p>
     * If the client hasn't specified a gravity but has specified an explicit
     * dimension, defaults to START or TOP. Otherwise, defaults to FILL to
     * preserve legacy behavior.
     *
     * @param gravity layer gravity
     * @param width width of the layer if set, -1 otherwise
     * @param height height of the layer if set, -1 otherwise
     * @return the default gravity for the layer
     */
    private static int resolveGravity(int gravity, int width, int height,
            int intrinsicWidth, int intrinsicHeight) {
        if (!Gravity.isHorizontal(gravity)) {
            if (width < 0) {
                gravity |= Gravity.FILL_HORIZONTAL;
            } else {
                gravity |= Gravity.START;
            }
        }

        if (!Gravity.isVertical(gravity)) {
            if (height < 0) {
                gravity |= Gravity.FILL_VERTICAL;
            } else {
                gravity |= Gravity.TOP;
            }
        }

        // If a dimension if not specified, either implicitly or explicitly,
        // force FILL for that dimension's gravity. This ensures that colors
        // are handled correctly and ensures backward compatibility.
        if (width < 0 && intrinsicWidth < 0) {
            gravity |= Gravity.FILL_HORIZONTAL;
        }

        if (height < 0 && intrinsicHeight < 0) {
            gravity |= Gravity.FILL_VERTICAL;
        }

        return gravity;
    }

    @Override
    public int getIntrinsicWidth() {
        int width = -1;
        int padL = 0;
        int padR = 0;

        final boolean nest = mLayerState.mPaddingMode == PADDING_MODE_NEST;
        final boolean isLayoutRtl = getLayoutDirection() == LayoutDirection.RTL;
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final ChildDrawable r = array[i];
            if (r.mDrawable == null) {
                continue;
            }

            // Take the resolved layout direction into account. If start / end
            // padding are defined, they will be resolved (hence overriding) to
            // left / right or right / left depending on the resolved layout
            // direction. If start / end padding are not defined, use the
            // left / right ones.
            final int insetRtlL = isLayoutRtl ? r.mInsetE : r.mInsetS;
            final int insetRtlR = isLayoutRtl ? r.mInsetS : r.mInsetE;
            final int insetL = insetRtlL == INSET_UNDEFINED ? r.mInsetL : insetRtlL;
            final int insetR = insetRtlR == INSET_UNDEFINED ? r.mInsetR : insetRtlR;

            // Don't apply padding and insets for children that don't have
            // an intrinsic dimension.
            final int minWidth = r.mWidth < 0 ? r.mDrawable.getIntrinsicWidth() : r.mWidth;
            final int w = minWidth < 0 ? -1 : minWidth + insetL + insetR + padL + padR;
            if (w > width) {
                width = w;
            }

            if (nest) {
                padL += mPaddingL[i];
                padR += mPaddingR[i];
            }
        }

        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        int height = -1;
        int padT = 0;
        int padB = 0;

        final boolean nest = mLayerState.mPaddingMode == PADDING_MODE_NEST;
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final ChildDrawable r = array[i];
            if (r.mDrawable == null) {
                continue;
            }

            // Don't apply padding and insets for children that don't have
            // an intrinsic dimension.
            final int minHeight = r.mHeight < 0 ? r.mDrawable.getIntrinsicHeight() : r.mHeight;
            final int h = minHeight < 0 ? -1 : minHeight + r.mInsetT + r.mInsetB + padT + padB;
            if (h > height) {
                height = h;
            }

            if (nest) {
                padT += mPaddingT[i];
                padB += mPaddingB[i];
            }
        }

        return height;
    }

    /**
     * Refreshes the cached padding values for the specified child.
     *
     * @return true if the child's padding has changed
     */
    private boolean refreshChildPadding(int i, ChildDrawable r) {
        if (r.mDrawable != null) {
            final Rect rect = mTmpRect;
            r.mDrawable.getPadding(rect);
            if (rect.left != mPaddingL[i] || rect.top != mPaddingT[i]
                    || rect.right != mPaddingR[i] || rect.bottom != mPaddingB[i]) {
                mPaddingL[i] = rect.left;
                mPaddingT[i] = rect.top;
                mPaddingR[i] = rect.right;
                mPaddingB[i] = rect.bottom;
                return true;
            }
        }
        return false;
    }

    /**
     * Ensures the child padding caches are large enough.
     */
    void ensurePadding() {
        final int N = mLayerState.mNumChildren;
        if (mPaddingL != null && mPaddingL.length >= N) {
            return;
        }

        mPaddingL = new int[N];
        mPaddingT = new int[N];
        mPaddingR = new int[N];
        mPaddingB = new int[N];
    }

    void refreshPadding() {
        final int N = mLayerState.mNumChildren;
        final ChildDrawable[] array = mLayerState.mChildren;
        for (int i = 0; i < N; i++) {
            refreshChildPadding(i, array[i]);
        }
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
            mLayerState = createConstantState(mLayerState, null);
            final ChildDrawable[] array = mLayerState.mChildren;
            final int N = mLayerState.mNumChildren;
            for (int i = 0; i < N; i++) {
                final Drawable dr = array[i].mDrawable;
                if (dr != null) {
                    dr.mutate();
                }
            }
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();

        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                dr.clearMutated();
            }
        }
        mMutated = false;
    }

    @Override
    public boolean onLayoutDirectionChanged(@View.ResolvedLayoutDir int layoutDirection) {
        boolean changed = false;

        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNumChildren;
        for (int i = 0; i < N; i++) {
            final Drawable dr = array[i].mDrawable;
            if (dr != null) {
                changed |= dr.setLayoutDirection(layoutDirection);
            }
        }

        updateLayerBounds(getBounds());
        return changed;
    }

    static class ChildDrawable {
        public Drawable mDrawable;
        public int[] mThemeAttrs;
        public int mDensity = DisplayMetrics.DENSITY_DEFAULT;
        public int mInsetL, mInsetT, mInsetR, mInsetB;
        public int mInsetS = INSET_UNDEFINED;
        public int mInsetE = INSET_UNDEFINED;
        public int mWidth = -1;
        public int mHeight = -1;
        public int mGravity = Gravity.NO_GRAVITY;
        public int mId = View.NO_ID;

        ChildDrawable(int density) {
            mDensity = density;
        }

        ChildDrawable(@NonNull ChildDrawable orig, @NonNull LayerDrawable owner,
                @Nullable Resources res) {
            final Drawable dr = orig.mDrawable;
            final Drawable clone;
            if (dr != null) {
                final ConstantState cs = dr.getConstantState();
                if (cs == null) {
                    clone = dr;
                    if (dr.getCallback() != null) {
                        // This drawable already has an owner.
                        Log.w(LOG_TAG, "Invalid drawable added to LayerDrawable! Drawable already "
                                + "belongs to another owner but does not expose a constant state.",
                                new RuntimeException());
                    }
                } else if (res != null) {
                    clone = cs.newDrawable(res);
                } else {
                    clone = cs.newDrawable();
                }
                clone.setLayoutDirection(dr.getLayoutDirection());
                clone.setBounds(dr.getBounds());
                clone.setLevel(dr.getLevel());

                // Set the callback last to prevent invalidation from
                // propagating before the constant state has been set.
                clone.setCallback(owner);
            } else {
                clone = null;
            }

            mDrawable = clone;
            mThemeAttrs = orig.mThemeAttrs;
            mInsetL = orig.mInsetL;
            mInsetT = orig.mInsetT;
            mInsetR = orig.mInsetR;
            mInsetB = orig.mInsetB;
            mInsetS = orig.mInsetS;
            mInsetE = orig.mInsetE;
            mWidth = orig.mWidth;
            mHeight = orig.mHeight;
            mGravity = orig.mGravity;
            mId = orig.mId;

            mDensity = Drawable.resolveDensity(res, orig.mDensity);
            if (orig.mDensity != mDensity) {
                applyDensityScaling(orig.mDensity, mDensity);
            }
        }

        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mDrawable != null && mDrawable.canApplyTheme());
        }

        public final void setDensity(int targetDensity) {
            if (mDensity != targetDensity) {
                final int sourceDensity = mDensity;
                mDensity = targetDensity;

                applyDensityScaling(sourceDensity, targetDensity);
            }
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            mInsetL = Drawable.scaleFromDensity(mInsetL, sourceDensity, targetDensity, false);
            mInsetT = Drawable.scaleFromDensity(mInsetT, sourceDensity, targetDensity, false);
            mInsetR = Drawable.scaleFromDensity(mInsetR, sourceDensity, targetDensity, false);
            mInsetB = Drawable.scaleFromDensity(mInsetB, sourceDensity, targetDensity, false);
            if (mInsetS != INSET_UNDEFINED) {
                mInsetS = Drawable.scaleFromDensity(mInsetS, sourceDensity, targetDensity, false);
            }
            if (mInsetE != INSET_UNDEFINED) {
                mInsetE = Drawable.scaleFromDensity(mInsetE, sourceDensity, targetDensity, false);
            }
            if (mWidth > 0) {
                mWidth = Drawable.scaleFromDensity(mWidth, sourceDensity, targetDensity, true);
            }
            if (mHeight > 0) {
                mHeight = Drawable.scaleFromDensity(mHeight, sourceDensity, targetDensity, true);
            }
        }
    }

    static class LayerState extends ConstantState {
        private int[] mThemeAttrs;

        int mNumChildren;
        ChildDrawable[] mChildren;

        int mDensity;

        // These values all correspond to mDensity.
        int mPaddingTop = -1;
        int mPaddingBottom = -1;
        int mPaddingLeft = -1;
        int mPaddingRight = -1;
        int mPaddingStart = -1;
        int mPaddingEnd = -1;
        int mOpacityOverride = PixelFormat.UNKNOWN;

        @Config int mChangingConfigurations;
        @Config int mChildrenChangingConfigurations;

        private boolean mCheckedOpacity;
        private int mOpacity;

        private boolean mCheckedStateful;
        private boolean mIsStateful;

        private boolean mAutoMirrored = false;

        private int mPaddingMode = PADDING_MODE_NEST;

        LayerState(@Nullable LayerState orig, @NonNull LayerDrawable owner,
                @Nullable Resources res) {
            mDensity = Drawable.resolveDensity(res, orig != null ? orig.mDensity : 0);

            if (orig != null) {
                final ChildDrawable[] origChildDrawable = orig.mChildren;
                final int N = orig.mNumChildren;

                mNumChildren = N;
                mChildren = new ChildDrawable[N];

                mChangingConfigurations = orig.mChangingConfigurations;
                mChildrenChangingConfigurations = orig.mChildrenChangingConfigurations;

                for (int i = 0; i < N; i++) {
                    final ChildDrawable or = origChildDrawable[i];
                    mChildren[i] = new ChildDrawable(or, owner, res);
                }

                mCheckedOpacity = orig.mCheckedOpacity;
                mOpacity = orig.mOpacity;
                mCheckedStateful = orig.mCheckedStateful;
                mIsStateful = orig.mIsStateful;
                mAutoMirrored = orig.mAutoMirrored;
                mPaddingMode = orig.mPaddingMode;
                mThemeAttrs = orig.mThemeAttrs;
                mPaddingTop = orig.mPaddingTop;
                mPaddingBottom = orig.mPaddingBottom;
                mPaddingLeft = orig.mPaddingLeft;
                mPaddingRight = orig.mPaddingRight;
                mPaddingStart = orig.mPaddingStart;
                mPaddingEnd = orig.mPaddingEnd;
                mOpacityOverride = orig.mOpacityOverride;

                if (orig.mDensity != mDensity) {
                    applyDensityScaling(orig.mDensity, mDensity);
                }
            } else {
                mNumChildren = 0;
                mChildren = null;
            }
        }

        public final void setDensity(int targetDensity) {
            if (mDensity != targetDensity) {
                final int sourceDensity = mDensity;
                mDensity = targetDensity;

                onDensityChanged(sourceDensity, targetDensity);
            }
        }

        protected void onDensityChanged(int sourceDensity, int targetDensity) {
            applyDensityScaling(sourceDensity, targetDensity);
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            if (mPaddingLeft > 0) {
                mPaddingLeft = Drawable.scaleFromDensity(
                        mPaddingLeft, sourceDensity, targetDensity, false);
            }
            if (mPaddingTop > 0) {
                mPaddingTop = Drawable.scaleFromDensity(
                        mPaddingTop, sourceDensity, targetDensity, false);
            }
            if (mPaddingRight > 0) {
                mPaddingRight = Drawable.scaleFromDensity(
                        mPaddingRight, sourceDensity, targetDensity, false);
            }
            if (mPaddingBottom > 0) {
                mPaddingBottom = Drawable.scaleFromDensity(
                        mPaddingBottom, sourceDensity, targetDensity, false);
            }
            if (mPaddingStart > 0) {
                mPaddingStart = Drawable.scaleFromDensity(
                        mPaddingStart, sourceDensity, targetDensity, false);
            }
            if (mPaddingEnd > 0) {
                mPaddingEnd = Drawable.scaleFromDensity(
                        mPaddingEnd, sourceDensity, targetDensity, false);
            }
        }

        @Override
        public boolean canApplyTheme() {
            if (mThemeAttrs != null || super.canApplyTheme()) {
                return true;
            }

            final ChildDrawable[] array = mChildren;
            final int N = mNumChildren;
            for (int i = 0; i < N; i++) {
                final ChildDrawable layer = array[i];
                if (layer.canApplyTheme()) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public Drawable newDrawable() {
            return new LayerDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(@Nullable Resources res) {
            return new LayerDrawable(this, res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | mChildrenChangingConfigurations;
        }

        public final int getOpacity() {
            if (mCheckedOpacity) {
                return mOpacity;
            }

            final int N = mNumChildren;
            final ChildDrawable[] array = mChildren;

            // Seek to the first non-null drawable.
            int firstIndex = -1;
            for (int i = 0; i < N; i++) {
                if (array[i].mDrawable != null) {
                    firstIndex = i;
                    break;
                }
            }

            int op;
            if (firstIndex >= 0) {
                op = array[firstIndex].mDrawable.getOpacity();
            } else {
                op = PixelFormat.TRANSPARENT;
            }

            // Merge all remaining non-null drawables.
            for (int i = firstIndex + 1; i < N; i++) {
                final Drawable dr = array[i].mDrawable;
                if (dr != null) {
                    op = Drawable.resolveOpacity(op, dr.getOpacity());
                }
            }

            mOpacity = op;
            mCheckedOpacity = true;
            return op;
        }

        public final boolean isStateful() {
            if (mCheckedStateful) {
                return mIsStateful;
            }

            final int N = mNumChildren;
            final ChildDrawable[] array = mChildren;
            boolean isStateful = false;
            for (int i = 0; i < N; i++) {
                final Drawable dr = array[i].mDrawable;
                if (dr != null && dr.isStateful()) {
                    isStateful = true;
                    break;
                }
            }

            mIsStateful = isStateful;
            mCheckedStateful = true;
            return isStateful;
        }

        public final boolean hasFocusStateSpecified() {
            final int N = mNumChildren;
            final ChildDrawable[] array = mChildren;
            for (int i = 0; i < N; i++) {
                final Drawable dr = array[i].mDrawable;
                if (dr != null && dr.hasFocusStateSpecified()) {
                    return true;
                }
            }
            return false;
        }

        public final boolean canConstantState() {
            final ChildDrawable[] array = mChildren;
            final int N = mNumChildren;
            for (int i = 0; i < N; i++) {
                final Drawable dr = array[i].mDrawable;
                if (dr != null && dr.getConstantState() == null) {
                    return false;
                }
            }

            // Don't cache the result, this method is not called very often.
            return true;
        }

        /**
         * Invalidates the cached opacity and statefulness.
         */
        void invalidateCache() {
            mCheckedOpacity = false;
            mCheckedStateful = false;
        }

    }
}

