/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources.Theme;
import android.graphics.Color;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.SparseArray;
import android.util.StateSet;
import android.util.Xml;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 *
 * Lets you map {@link android.view.View} state sets to colors.
 *
 * {@link android.content.res.ColorStateList}s are created from XML resource files defined in the
 * "color" subdirectory directory of an application's resource directory.  The XML file contains
 * a single "selector" element with a number of "item" elements inside.  For example:
 *
 * <pre>
 * &lt;selector xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 *   &lt;item android:state_focused="true" android:color="@color/testcolor1"/&gt;
 *   &lt;item android:state_pressed="true" android:state_enabled="false" android:color="@color/testcolor2" /&gt;
 *   &lt;item android:state_enabled="false" android:color="@color/testcolor3" /&gt;
 *   &lt;item android:color="@color/testcolor5"/&gt;
 * &lt;/selector&gt;
 * </pre>
 *
 * This defines a set of state spec / color pairs where each state spec specifies a set of
 * states that a view must either be in or not be in and the color specifies the color associated
 * with that spec.  The list of state specs will be processed in order of the items in the XML file.
 * An item with no state spec is considered to match any set of states and is generally useful as
 * a final item to be used as a default.  Note that if you have such an item before any other items
 * in the list then any subsequent items will end up being ignored.
 * <p>For more information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/color-list-resource.html">Color State
 * List Resource</a>.</p>
 */
public class ColorStateList implements Parcelable {
    private static final String TAG = "ColorStateList";
    private static final int DEFAULT_COLOR = Color.RED;
    private static final int[][] EMPTY = new int[][] { new int[0] };
    private static final SparseArray<WeakReference<ColorStateList>> sCache =
                            new SparseArray<WeakReference<ColorStateList>>();

    private int[][] mThemeAttrs;
    private int mChangingConfigurations;

    private int[][] mStateSpecs;
    private int[] mColors;
    private int mDefaultColor;
    private boolean mIsOpaque;

    private ColorStateList() {
        // Not publicly instantiable.
    }

    /**
     * Creates a ColorStateList that returns the specified mapping from
     * states to colors.
     */
    public ColorStateList(int[][] states, int[] colors) {
        mStateSpecs = states;
        mColors = colors;

        onColorsChanged();
    }

    /**
     * @return A ColorStateList containing a single color.
     */
    @NonNull
    public static ColorStateList valueOf(int color) {
        synchronized (sCache) {
            final int index = sCache.indexOfKey(color);
            if (index >= 0) {
                final ColorStateList cached = sCache.valueAt(index).get();
                if (cached != null) {
                    return cached;
                }

                // Prune missing entry.
                sCache.removeAt(index);
            }

            // Prune the cache before adding new items.
            final int N = sCache.size();
            for (int i = N - 1; i >= 0; i--) {
                if (sCache.valueAt(i).get() == null) {
                    sCache.removeAt(i);
                }
            }

            final ColorStateList csl = new ColorStateList(EMPTY, new int[] { color });
            sCache.put(color, new WeakReference<ColorStateList>(csl));
            return csl;
        }
    }

    /**
     * Creates a ColorStateList with the same properties as another
     * ColorStateList.
     * <p>
     * The properties of the new ColorStateList can be modified without
     * affecting the source ColorStateList.
     *
     * @param orig the source color state list
     */
    private ColorStateList(ColorStateList orig) {
        if (orig != null) {
            mStateSpecs = orig.mStateSpecs;
            mDefaultColor = orig.mDefaultColor;
            mIsOpaque = orig.mIsOpaque;

            // Deep copy, this may change due to theming.
            mColors = orig.mColors.clone();
        }
    }

    /**
     * Creates a ColorStateList from an XML document.
     *
     * @param r Resources against which the ColorStateList should be inflated.
     * @param parser Parser for the XML document defining the ColorStateList.
     * @return A new color state list.
     *
     * @deprecated Use #createFromXml(Resources, XmlPullParser parser, Theme)
     */
    @NonNull
    @Deprecated
    public static ColorStateList createFromXml(Resources r, XmlPullParser parser)
            throws XmlPullParserException, IOException {
        return createFromXml(r, parser, null);
    }

    /**
     * Creates a ColorStateList from an XML document using given a set of
     * {@link Resources} and a {@link Theme}.
     *
     * @param r Resources against which the ColorStateList should be inflated.
     * @param parser Parser for the XML document defining the ColorStateList.
     * @param theme Optional theme to apply to the color state list, may be
     *              {@code null}.
     * @return A new color state list.
     */
    @NonNull
    public static ColorStateList createFromXml(@NonNull Resources r, @NonNull XmlPullParser parser,
            @Nullable Theme theme) throws XmlPullParserException, IOException {
        final AttributeSet attrs = Xml.asAttributeSet(parser);

        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                   && type != XmlPullParser.END_DOCUMENT) {
            // Seek parser to start tag.
        }

        if (type != XmlPullParser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }

        return createFromXmlInner(r, parser, attrs, theme);
    }

    /**
     * Create from inside an XML document. Called on a parser positioned at a
     * tag in an XML document, tries to create a ColorStateList from that tag.
     *
     * @throws XmlPullParserException if the current tag is not &lt;selector>
     * @return A new color state list for the current tag.
     */
    @NonNull
    private static ColorStateList createFromXmlInner(@NonNull Resources r,
            @NonNull XmlPullParser parser, @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final String name = parser.getName();
        if (!name.equals("selector")) {
            throw new XmlPullParserException(
                    parser.getPositionDescription() + ": invalid color state list tag " + name);
        }

        final ColorStateList colorStateList = new ColorStateList();
        colorStateList.inflate(r, parser, attrs, theme);
        return colorStateList;
    }

    /**
     * Creates a new ColorStateList that has the same states and colors as this
     * one but where each color has the specified alpha value (0-255).
     *
     * @param alpha The new alpha channel value (0-255).
     * @return A new color state list.
     */
    @NonNull
    public ColorStateList withAlpha(int alpha) {
        final int[] colors = new int[mColors.length];
        final int len = colors.length;
        for (int i = 0; i < len; i++) {
            colors[i] = (mColors[i] & 0xFFFFFF) | (alpha << 24);
        }

        return new ColorStateList(mStateSpecs, colors);
    }

    /**
     * Fill in this object based on the contents of an XML "selector" element.
     */
    private void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final int innerDepth = parser.getDepth()+1;
        int depth;
        int type;

        int changingConfigurations = 0;
        int defaultColor = DEFAULT_COLOR;

        boolean hasUnresolvedAttrs = false;

        int[][] stateSpecList = ArrayUtils.newUnpaddedArray(int[].class, 20);
        int[][] themeAttrsList = new int[stateSpecList.length][];
        int[] colorList = new int[stateSpecList.length];
        int listSize = 0;

        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
               && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG || depth > innerDepth
                    || !parser.getName().equals("item")) {
                continue;
            }

            final TypedArray a = Resources.obtainAttributes(r, theme, attrs,
                    R.styleable.ColorStateListItem);
            final int[] themeAttrs = a.extractThemeAttrs();
            final int baseColor = a.getColor(R.styleable.ColorStateListItem_color, 0);
            final float alphaMod = a.getFloat(R.styleable.ColorStateListItem_alpha, 1.0f);

            changingConfigurations |= a.getChangingConfigurations();

            a.recycle();

            // Parse all unrecognized attributes as state specifiers.
            int j = 0;
            final int numAttrs = attrs.getAttributeCount();
            int[] stateSpec = new int[numAttrs];
            for (int i = 0; i < numAttrs; i++) {
                final int stateResId = attrs.getAttributeNameResource(i);
                switch (stateResId) {
                    case R.attr.color:
                    case R.attr.alpha:
                        // Recognized attribute, ignore.
                        break;
                    default:
                        stateSpec[j++] = attrs.getAttributeBooleanValue(i, false)
                                ? stateResId : -stateResId;
                }
            }
            stateSpec = StateSet.trimStateSet(stateSpec, j);

            // Apply alpha modulation.
            final int color = modulateColorAlpha(baseColor, alphaMod);
            if (listSize == 0 || stateSpec.length == 0) {
                defaultColor = color;
            }

            if (themeAttrs != null) {
                hasUnresolvedAttrs = true;
            }

            colorList = GrowingArrayUtils.append(colorList, listSize, color);
            themeAttrsList = GrowingArrayUtils.append(themeAttrsList, listSize, themeAttrs);
            stateSpecList = GrowingArrayUtils.append(stateSpecList, listSize, stateSpec);
            listSize++;
        }

        mChangingConfigurations = changingConfigurations;
        mDefaultColor = defaultColor;

        if (hasUnresolvedAttrs) {
            mThemeAttrs = new int[listSize][];
            System.arraycopy(themeAttrsList, 0, mThemeAttrs, 0, listSize);
        } else {
            mThemeAttrs = null;
        }

        mColors = new int[listSize];
        mStateSpecs = new int[listSize][];
        System.arraycopy(colorList, 0, mColors, 0, listSize);
        System.arraycopy(stateSpecList, 0, mStateSpecs, 0, listSize);

        onColorsChanged();
    }

    /**
     * Returns whether a theme can be applied to this color state list, which
     * usually indicates that the color state list has unresolved theme
     * attributes.
     *
     * @return whether a theme can be applied to this color state list
     */
    public boolean canApplyTheme() {
        return mThemeAttrs != null;
    }

    /**
     * Applies a theme to this color state list.
     *
     * @param t the theme to apply
     */
    public void applyTheme(Theme t) {
        if (mThemeAttrs == null) {
            return;
        }

        boolean hasUnresolvedAttrs = false;

        final int[][] themeAttrsList = mThemeAttrs;
        final int N = themeAttrsList.length;
        for (int i = 0; i < N; i++) {
            if (themeAttrsList[i] != null) {
                final TypedArray a = t.resolveAttributes(themeAttrsList[i],
                        R.styleable.ColorStateListItem);
                final int baseColor = a.getColor(
                        R.styleable.ColorStateListItem_color, mColors[i]);
                final float alphaMod = a.getFloat(
                        R.styleable.ColorStateListItem_alpha, 1.0f);

                mColors[i] = modulateColorAlpha(baseColor, alphaMod);
                mChangingConfigurations |= a.getChangingConfigurations();

                themeAttrsList[i] = a.extractThemeAttrs(themeAttrsList[i]);
                if (themeAttrsList[i] != null) {
                    hasUnresolvedAttrs = true;
                }

                a.recycle();
            }
        }

        if (!hasUnresolvedAttrs) {
            mThemeAttrs = null;
        }

        onColorsChanged();
    }

    private int modulateColorAlpha(int baseColor, float alphaMod) {
        if (alphaMod == 1.0f) {
            return baseColor;
        }

        final int baseAlpha = Color.alpha(baseColor);
        final int alpha = MathUtils.constrain((int) (baseAlpha * alphaMod + 0.5f), 0, 255);
        final int color = (baseColor & 0xFFFFFF) | (alpha << 24);
        return color;
    }

    /**
     * Indicates whether this color state list contains more than one state spec
     * and will change color based on state.
     *
     * @return True if this color state list changes color based on state, false
     *         otherwise.
     * @see #getColorForState(int[], int)
     */
    public boolean isStateful() {
        return mStateSpecs.length > 1;
    }

    /**
     * Indicates whether this color state list is opaque, which means that every
     * color returned from {@link #getColorForState(int[], int)} has an alpha
     * value of 255.
     *
     * @return True if this color state list is opaque.
     */
    public boolean isOpaque() {
        return mIsOpaque;
    }

    /**
     * Return the color associated with the given set of
     * {@link android.view.View} states.
     *
     * @param stateSet an array of {@link android.view.View} states
     * @param defaultColor the color to return if there's no matching state
     *                     spec in this {@link ColorStateList} that matches the
     *                     stateSet.
     *
     * @return the color associated with that set of states in this {@link ColorStateList}.
     */
    public int getColorForState(@Nullable int[] stateSet, int defaultColor) {
        final int setLength = mStateSpecs.length;
        for (int i = 0; i < setLength; i++) {
            final int[] stateSpec = mStateSpecs[i];
            if (StateSet.stateSetMatches(stateSpec, stateSet)) {
                return mColors[i];
            }
        }
        return defaultColor;
    }

    /**
     * Return the default color in this {@link ColorStateList}.
     *
     * @return the default color in this {@link ColorStateList}.
     */
    public int getDefaultColor() {
        return mDefaultColor;
    }

    /**
     * Return the states in this {@link ColorStateList}. The returned array
     * should not be modified.
     *
     * @return the states in this {@link ColorStateList}
     * @hide
     */
    public int[][] getStates() {
        return mStateSpecs;
    }

    /**
     * Return the colors in this {@link ColorStateList}. The returned array
     * should not be modified.
     *
     * @return the colors in this {@link ColorStateList}
     * @hide
     */
    public int[] getColors() {
        return mColors;
    }

    /**
     * If the color state list does not already have an entry matching the
     * specified state, prepends a state set and color pair to a color state
     * list.
     * <p>
     * This is a workaround used in TimePicker and DatePicker until we can
     * add support for theme attributes in ColorStateList.
     *
     * @param colorStateList the source color state list
     * @param state the state to prepend
     * @param color the color to use for the given state
     * @return a new color state list, or the source color state list if there
     *         was already a matching state set
     *
     * @hide Remove when we can support theme attributes.
     */
    public static ColorStateList addFirstIfMissing(
            ColorStateList colorStateList, int state, int color) {
        final int[][] inputStates = colorStateList.getStates();
        for (int i = 0; i < inputStates.length; i++) {
            final int[] inputState = inputStates[i];
            for (int j = 0; j < inputState.length; j++) {
                if (inputState[j] == state) {
                    return colorStateList;
                }
            }
        }

        final int[][] outputStates = new int[inputStates.length + 1][];
        System.arraycopy(inputStates, 0, outputStates, 1, inputStates.length);
        outputStates[0] = new int[] { state };

        final int[] inputColors = colorStateList.getColors();
        final int[] outputColors = new int[inputColors.length + 1];
        System.arraycopy(inputColors, 0, outputColors, 1, inputColors.length);
        outputColors[0] = color;

        return new ColorStateList(outputStates, outputColors);
    }

    @Override
    public String toString() {
        return "ColorStateList{" +
               "mThemeAttrs=" + Arrays.deepToString(mThemeAttrs) +
               "mChangingConfigurations=" + mChangingConfigurations +
               "mStateSpecs=" + Arrays.deepToString(mStateSpecs) +
               "mColors=" + Arrays.toString(mColors) +
               "mDefaultColor=" + mDefaultColor + '}';
    }

    /**
     * Updates the default color and opacity.
     */
    private void onColorsChanged() {
        int defaultColor = DEFAULT_COLOR;
        boolean isOpaque = true;

        final int[][] states = mStateSpecs;
        final int[] colors = mColors;
        final int N = states.length;
        if (N > 0) {
            defaultColor = colors[0];

            for (int i = N - 1; i > 0; i--) {
                if (states[i].length == 0) {
                    defaultColor = colors[i];
                    break;
                }
            }

            for (int i = 0; i < N; i++) {
                if (Color.alpha(colors[i]) != 0xFF) {
                    isOpaque = false;
                    break;
                }
            }
        }

        mDefaultColor = defaultColor;
        mIsOpaque = isOpaque;
    }

    /**
     * @return A factory that can create new instances of this ColorStateList.
     */
    ColorStateListFactory getFactory() {
        return new ColorStateListFactory(this);
    }

    static class ColorStateListFactory extends ConstantState<ColorStateList> {
        final ColorStateList mSrc;

        public ColorStateListFactory(ColorStateList src) {
            mSrc = src;
        }

        @Override
        public int getChangingConfigurations() {
            return mSrc.mChangingConfigurations;
        }

        @Override
        public ColorStateList newInstance() {
            return mSrc;
        }

        @Override
        public ColorStateList newInstance(Resources res, Theme theme) {
            if (theme == null || !mSrc.canApplyTheme()) {
                return mSrc;
            }

            final ColorStateList clone = new ColorStateList(mSrc);
            clone.applyTheme(theme);
            return clone;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (canApplyTheme()) {
            Log.w(TAG, "Wrote partially-resolved ColorStateList to parcel!");
        }
        final int N = mStateSpecs.length;
        dest.writeInt(N);
        for (int i = 0; i < N; i++) {
            dest.writeIntArray(mStateSpecs[i]);
        }
        dest.writeIntArray(mColors);
    }

    public static final Parcelable.Creator<ColorStateList> CREATOR =
            new Parcelable.Creator<ColorStateList>() {
        @Override
        public ColorStateList[] newArray(int size) {
            return new ColorStateList[size];
        }

        @Override
        public ColorStateList createFromParcel(Parcel source) {
            final int N = source.readInt();
            final int[][] stateSpecs = new int[N][];
            for (int i = 0; i < N; i++) {
                stateSpecs[i] = source.createIntArray();
            }
            final int[] colors = source.createIntArray();
            return new ColorStateList(stateSpecs, colors);
        }
    };
}
