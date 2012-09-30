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

import java.io.IOException;
import java.util.Arrays;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.StateSet;

/**
 * Lets you assign a number of graphic images to a single Drawable and swap out the visible item by a string
 * ID value.
 * <p/>
 * <p>It can be defined in an XML file with the <code>&lt;selector></code> element.
 * Each state Drawable is defined in a nested <code>&lt;item></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#StateListDrawable_visible
 * @attr ref android.R.styleable#StateListDrawable_variablePadding
 * @attr ref android.R.styleable#StateListDrawable_constantSize
 * @attr ref android.R.styleable#DrawableStates_state_focused
 * @attr ref android.R.styleable#DrawableStates_state_window_focused
 * @attr ref android.R.styleable#DrawableStates_state_enabled
 * @attr ref android.R.styleable#DrawableStates_state_checkable
 * @attr ref android.R.styleable#DrawableStates_state_checked
 * @attr ref android.R.styleable#DrawableStates_state_selected
 * @attr ref android.R.styleable#DrawableStates_state_activated
 * @attr ref android.R.styleable#DrawableStates_state_active
 * @attr ref android.R.styleable#DrawableStates_state_single
 * @attr ref android.R.styleable#DrawableStates_state_first
 * @attr ref android.R.styleable#DrawableStates_state_middle
 * @attr ref android.R.styleable#DrawableStates_state_last
 * @attr ref android.R.styleable#DrawableStates_state_pressed
 */
public class StateListDrawable extends DrawableContainer {
    private static final boolean DEBUG = false;
    private static final String TAG = "StateListDrawable";

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
    private final StateListState mStateListState;
    private boolean mMutated;

    public StateListDrawable() {
        this(null, null);
    }

    /**
     * Add a new image/string ID to the set of images.
     *
     * @param stateSet - An array of resource Ids to associate with the image.
     *                 Switch to this image by calling setState().
     * @param drawable -The image to show.
     */
    public void addState(int[] stateSet, Drawable drawable) {
        if (drawable != null) {
            mStateListState.addStateSet(stateSet, drawable);
            // in case the new state matches our current state...
            onStateChange(getState());
        }
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        int idx = mStateListState.indexOfStateSet(stateSet);
        if (DEBUG) android.util.Log.i(TAG, "onStateChange " + this + " states "
                + Arrays.toString(stateSet) + " found " + idx);
        if (idx < 0) {
            idx = mStateListState.indexOfStateSet(StateSet.WILD_CARD);
        }
        if (selectDrawable(idx)) {
            return true;
        }
        return super.onStateChange(stateSet);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser,
            AttributeSet attrs)
            throws XmlPullParserException, IOException {

        TypedArray a = r.obtainAttributes(attrs,
                com.android.internal.R.styleable.StateListDrawable);

        super.inflateWithAttributes(r, parser, a,
                com.android.internal.R.styleable.StateListDrawable_visible);

        mStateListState.setVariablePadding(a.getBoolean(
                com.android.internal.R.styleable.StateListDrawable_variablePadding, false));
        mStateListState.setConstantSize(a.getBoolean(
                com.android.internal.R.styleable.StateListDrawable_constantSize, false));
        mStateListState.setEnterFadeDuration(a.getInt(
                com.android.internal.R.styleable.StateListDrawable_enterFadeDuration, 0));
        mStateListState.setExitFadeDuration(a.getInt(
                com.android.internal.R.styleable.StateListDrawable_exitFadeDuration, 0));

        setDither(a.getBoolean(com.android.internal.R.styleable.StateListDrawable_dither,
                               DEFAULT_DITHER));

        a.recycle();

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

            int drawableRes = 0;

            int i;
            int j = 0;
            final int numAttrs = attrs.getAttributeCount();
            int[] states = new int[numAttrs];
            for (i = 0; i < numAttrs; i++) {
                final int stateResId = attrs.getAttributeNameResource(i);
                if (stateResId == 0) break;
                if (stateResId == com.android.internal.R.attr.drawable) {
                    drawableRes = attrs.getAttributeResourceValue(i, 0);
                } else {
                    states[j++] = attrs.getAttributeBooleanValue(i, false)
                            ? stateResId
                            : -stateResId;
                }
            }
            states = StateSet.trimStateSet(states, j);

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

            mStateListState.addStateSet(states, dr);
        }

        onStateChange(getState());
    }

    StateListState getStateListState() {
        return mStateListState;
    }

    /**
     * Gets the number of states contained in this drawable.
     *
     * @return The number of states contained in this drawable.
     * @hide pending API council
     * @see #getStateSet(int)
     * @see #getStateDrawable(int)
     */
    public int getStateCount() {
        return mStateListState.getChildCount();
    }

    /**
     * Gets the state set at an index.
     *
     * @param index The index of the state set.
     * @return The state set at the index.
     * @hide pending API council
     * @see #getStateCount()
     * @see #getStateDrawable(int)
     */
    public int[] getStateSet(int index) {
        return mStateListState.mStateSets[index];
    }

    /**
     * Gets the drawable at an index.
     *
     * @param index The index of the drawable.
     * @return The drawable at the index.
     * @hide pending API council
     * @see #getStateCount()
     * @see #getStateSet(int)
     */
    public Drawable getStateDrawable(int index) {
        return mStateListState.getChildren()[index];
    }
    
    /**
     * Gets the index of the drawable with the provided state set.
     * 
     * @param stateSet the state set to look up
     * @return the index of the provided state set, or -1 if not found
     * @hide pending API council
     * @see #getStateDrawable(int)
     * @see #getStateSet(int)
     */
    public int getStateDrawableIndex(int[] stateSet) {
        return mStateListState.indexOfStateSet(stateSet);
    }
    
    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            final int[][] sets = mStateListState.mStateSets;
            final int count = sets.length;
            mStateListState.mStateSets = new int[count][];
            for (int i = 0; i < count; i++) {
                final int[] set = sets[i];
                if (set != null) {
                    mStateListState.mStateSets[i] = set.clone();
                }
            }
            mMutated = true;
        }
        return this;
    }

    /** @hide */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        final int numStates = getStateCount();
        for (int i = 0; i < numStates; i++) {
            getStateDrawable(i).setLayoutDirection(layoutDirection);
        }
        super.setLayoutDirection(layoutDirection);
    }

    static final class StateListState extends DrawableContainerState {
        int[][] mStateSets;

        StateListState(StateListState orig, StateListDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                mStateSets = orig.mStateSets;
            } else {
                mStateSets = new int[getChildren().length][];
            }
        }

        int addStateSet(int[] stateSet, Drawable drawable) {
            final int pos = addChild(drawable);
            mStateSets[pos] = stateSet;
            return pos;
        }

        private int indexOfStateSet(int[] stateSet) {
            final int[][] stateSets = mStateSets;
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (StateSet.stateSetMatches(stateSets[i], stateSet)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Drawable newDrawable() {
            return new StateListDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new StateListDrawable(this, res);
        }

        @Override
        public void growArray(int oldSize, int newSize) {
            super.growArray(oldSize, newSize);
            final int[][] newStateSets = new int[newSize][];
            System.arraycopy(mStateSets, 0, newStateSets, 0, oldSize);
            mStateSets = newStateSets;
        }
    }

    private StateListDrawable(StateListState state, Resources res) {
        StateListState as = new StateListState(state, this, res);
        mStateListState = as;
        setConstantState(as);
        onStateChange(getState());
    }
}

