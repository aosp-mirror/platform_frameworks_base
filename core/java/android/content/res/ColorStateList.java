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

import com.android.internal.util.ArrayUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.util.AttributeSet;
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

    private int[][] mStateSpecs; // must be parallel to mColors
    private int[] mColors;      // must be parallel to mStateSpecs
    private int mDefaultColor = 0xffff0000;

    private static final int[][] EMPTY = new int[][] { new int[0] };
    private static final SparseArray<WeakReference<ColorStateList>> sCache =
                            new SparseArray<WeakReference<ColorStateList>>();

    private ColorStateList() { }

    /**
     * Creates a ColorStateList that returns the specified mapping from
     * states to colors.
     */
    public ColorStateList(int[][] states, int[] colors) {
        mStateSpecs = states;
        mColors = colors;

        if (states.length > 0) {
            mDefaultColor = colors[0];

            for (int i = 0; i < states.length; i++) {
                if (states[i].length == 0) {
                    mDefaultColor = colors[i];
                }
            }
        }
    }

    /**
     * Creates or retrieves a ColorStateList that always returns a single color.
     */
    public static ColorStateList valueOf(int color) {
        // TODO: should we collect these eventually?
        synchronized (sCache) {
            WeakReference<ColorStateList> ref = sCache.get(color);
            ColorStateList csl = ref != null ? ref.get() : null;

            if (csl != null) {
                return csl;
            }

            csl = new ColorStateList(EMPTY, new int[] { color });
            sCache.put(color, new WeakReference<ColorStateList>(csl));
            return csl;
        }
    }

    /**
     * Create a ColorStateList from an XML document, given a set of {@link Resources}.
     */
    public static ColorStateList createFromXml(Resources r, XmlPullParser parser)
            throws XmlPullParserException, IOException {

        AttributeSet attrs = Xml.asAttributeSet(parser);

        int type;
        while ((type=parser.next()) != XmlPullParser.START_TAG
                   && type != XmlPullParser.END_DOCUMENT) {
        }

        if (type != XmlPullParser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }

        return createFromXmlInner(r, parser, attrs);
    }

    /* Create from inside an XML document.  Called on a parser positioned at
     * a tag in an XML document, tries to create a ColorStateList from that tag.
     * Returns null if the tag is not a valid ColorStateList.
     */
    private static ColorStateList createFromXmlInner(Resources r, XmlPullParser parser,
            AttributeSet attrs) throws XmlPullParserException, IOException {

        ColorStateList colorStateList;

        final String name = parser.getName();

        if (name.equals("selector")) {
            colorStateList = new ColorStateList();
        } else {
            throw new XmlPullParserException(
                parser.getPositionDescription() + ": invalid drawable tag " + name);
        }

        colorStateList.inflate(r, parser, attrs);
        return colorStateList;
    }

    /**
     * Creates a new ColorStateList that has the same states and
     * colors as this one but where each color has the specified alpha value
     * (0-255).
     */
    public ColorStateList withAlpha(int alpha) {
        int[] colors = new int[mColors.length];

        int len = colors.length;
        for (int i = 0; i < len; i++) {
            colors[i] = (mColors[i] & 0xFFFFFF) | (alpha << 24);
        }

        return new ColorStateList(mStateSpecs, colors);
    }

    /**
     * Fill in this object based on the contents of an XML "selector" element.
     */
    private void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
        throws XmlPullParserException, IOException {

        int type;

        final int innerDepth = parser.getDepth()+1;
        int depth;

        int listAllocated = 20;
        int listSize = 0;
        int[] colorList = new int[listAllocated];
        int[][] stateSpecList = new int[listAllocated][];

        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && ((depth=parser.getDepth()) >= innerDepth
                   || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth || !parser.getName().equals("item")) {
                continue;
            }

            int colorRes = 0;
            int color = 0xffff0000;
            boolean haveColor = false;

            int i;
            int j = 0;
            final int numAttrs = attrs.getAttributeCount();
            int[] stateSpec = new int[numAttrs];
            for (i = 0; i < numAttrs; i++) {
                final int stateResId = attrs.getAttributeNameResource(i);
                if (stateResId == 0) break;
                if (stateResId == com.android.internal.R.attr.color) {
                    colorRes = attrs.getAttributeResourceValue(i, 0);

                    if (colorRes == 0) {
                        color = attrs.getAttributeIntValue(i, color);
                        haveColor = true;
                    }
                } else {
                    stateSpec[j++] = attrs.getAttributeBooleanValue(i, false)
                                  ? stateResId
                                  : -stateResId;
                }
            }
            stateSpec = StateSet.trimStateSet(stateSpec, j);

            if (colorRes != 0) {
                color = r.getColor(colorRes);
            } else if (!haveColor) {
                throw new XmlPullParserException(
                        parser.getPositionDescription()
                        + ": <item> tag requires a 'android:color' attribute.");
            }

            if (listSize == 0 || stateSpec.length == 0) {
                mDefaultColor = color;
            }
            
            if (listSize + 1 >= listAllocated) {
                listAllocated = ArrayUtils.idealIntArraySize(listSize + 1);

                int[] ncolor = new int[listAllocated];
                System.arraycopy(colorList, 0, ncolor, 0, listSize);

                int[][] nstate = new int[listAllocated][];
                System.arraycopy(stateSpecList, 0, nstate, 0, listSize);

                colorList = ncolor;
                stateSpecList = nstate;
            }

            colorList[listSize] = color;
            stateSpecList[listSize] = stateSpec;
            listSize++;
        }

        mColors = new int[listSize];
        mStateSpecs = new int[listSize][];
        System.arraycopy(colorList, 0, mColors, 0, listSize);
        System.arraycopy(stateSpecList, 0, mStateSpecs, 0, listSize);
    }

    public boolean isStateful() {
        return mStateSpecs.length > 1;
    }
    
    /**
     * Return the color associated with the given set of {@link android.view.View} states.
     *
     * @param stateSet an array of {@link android.view.View} states
     * @param defaultColor the color to return if there's not state spec in this
     * {@link ColorStateList} that matches the stateSet.
     *
     * @return the color associated with that set of states in this {@link ColorStateList}.
     */
    public int getColorForState(int[] stateSet, int defaultColor) {
        final int setLength = mStateSpecs.length;
        for (int i = 0; i < setLength; i++) {
            int[] stateSpec = mStateSpecs[i];
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

    public String toString() {
        return "ColorStateList{" +
               "mStateSpecs=" + Arrays.deepToString(mStateSpecs) +
               "mColors=" + Arrays.toString(mColors) +
               "mDefaultColor=" + mDefaultColor + '}';
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        final int N = mStateSpecs.length;
        dest.writeInt(N);
        for (int i=0; i<N; i++) {
            dest.writeIntArray(mStateSpecs[i]);
        }
        dest.writeIntArray(mColors);
    }

    public static final Parcelable.Creator<ColorStateList> CREATOR =
            new Parcelable.Creator<ColorStateList>() {
        public ColorStateList[] newArray(int size) {
            return new ColorStateList[size];
        }

        public ColorStateList createFromParcel(Parcel source) {
            final int N = source.readInt();
            int[][] stateSpecs = new int[N][];
            for (int i=0; i<N; i++) {
                stateSpecs[i] = source.createIntArray();
            }
            int[] colors = source.createIntArray();
            return new ColorStateList(stateSpecs, colors);
        }
    };
}
