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
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.StateSet;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Arrays;

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
    private static final String TAG = "StateListDrawable";

    private static final boolean DEBUG = false;

    private StateListState mStateListState;
    private boolean mMutated;

    public StateListDrawable() {
        this(null, null);
    }

    /**
     * Add a new image/string ID to the set of images.
     *
     * @param stateSet An array of resource Ids to associate with the image.
     *                 Switch to this image by calling setState().
     * @param drawable The image to show. Note this must be a unique Drawable that is not shared
     *                 between any other View or Drawable otherwise the results are
     *                 undefined and can lead to unexpected rendering behavior
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

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        return mStateListState.hasFocusStateSpecified();
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final boolean changed = super.onStateChange(stateSet);

        int idx = mStateListState.indexOfStateSet(stateSet);
        if (DEBUG) android.util.Log.i(TAG, "onStateChange " + this + " states "
                + Arrays.toString(stateSet) + " found " + idx);
        if (idx < 0) {
            idx = mStateListState.indexOfStateSet(StateSet.WILD_CARD);
        }

        return selectDrawable(idx) || changed;
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.StateListDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.StateListDrawable_visible);
        updateStateFromTypedArray(a);
        updateDensity(r);
        a.recycle();

        inflateChildElements(r, parser, attrs, theme);

        onStateChange(getState());
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) {
        final StateListState state = mStateListState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mVariablePadding = a.getBoolean(
                R.styleable.StateListDrawable_variablePadding, state.mVariablePadding);
        state.mConstantSize = a.getBoolean(
                R.styleable.StateListDrawable_constantSize, state.mConstantSize);
        state.mEnterFadeDuration = a.getInt(
                R.styleable.StateListDrawable_enterFadeDuration, state.mEnterFadeDuration);
        state.mExitFadeDuration = a.getInt(
                R.styleable.StateListDrawable_exitFadeDuration, state.mExitFadeDuration);
        state.mDither = a.getBoolean(
                R.styleable.StateListDrawable_dither, state.mDither);
        state.mAutoMirrored = a.getBoolean(
                R.styleable.StateListDrawable_autoMirrored, state.mAutoMirrored);
    }

    /**
     * Inflates child elements from XML.
     */
    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        final StateListState state = mStateListState;
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

            // This allows state list drawable item elements to be themed at
            // inflation time but does NOT make them work for Zygote preload.
            final TypedArray a = obtainAttributes(r, theme, attrs,
                    R.styleable.StateListDrawableItem);
            Drawable dr = a.getDrawable(R.styleable.StateListDrawableItem_drawable);
            a.recycle();

            final int[] states = extractStateSet(attrs);

            // Loading child elements modifies the state of the AttributeSet's
            // underlying parser, so it needs to happen after obtaining
            // attributes and extracting states.
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

            state.addStateSet(states, dr);
        }
    }

    /**
     * Extracts state_ attributes from an attribute set.
     *
     * @param attrs The attribute set.
     * @return An array of state_ attributes.
     */
    int[] extractStateSet(AttributeSet attrs) {
        int j = 0;
        final int numAttrs = attrs.getAttributeCount();
        int[] states = new int[numAttrs];
        for (int i = 0; i < numAttrs; i++) {
            final int stateResId = attrs.getAttributeNameResource(i);
            switch (stateResId) {
                case 0:
                    break;
                case R.attr.drawable:
                case R.attr.id:
                    // Ignore attributes from StateListDrawableItem and
                    // AnimatedStateListDrawableItem.
                    continue;
                default:
                    states[j++] = attrs.getAttributeBooleanValue(i, false)
                            ? stateResId : -stateResId;
            }
        }
        states = StateSet.trimStateSet(states, j);
        return states;
    }

    StateListState getStateListState() {
        return mStateListState;
    }

    /**
     * Gets the number of states contained in this drawable.
     *
     * @return The number of states contained in this drawable.
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
     * @see #getStateCount()
     * @see #getStateSet(int)
     */
    public Drawable getStateDrawable(int index) {
        return mStateListState.getChild(index);
    }

    /**
     * Gets the index of the drawable with the provided state set.
     *
     * @param stateSet the state set to look up
     * @return the index of the provided state set, or -1 if not found
     * @see #getStateDrawable(int)
     * @see #getStateSet(int)
     */
    public int getStateDrawableIndex(int[] stateSet) {
        return mStateListState.indexOfStateSet(stateSet);
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mStateListState.mutate();
            mMutated = true;
        }
        return this;
    }

    @Override
    StateListState cloneConstantState() {
        return new StateListState(mStateListState, this, null);
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    static class StateListState extends DrawableContainerState {
        int[] mThemeAttrs;
        int[][] mStateSets;

        StateListState(StateListState orig, StateListDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                // Perform a shallow copy and rely on mutate() to deep-copy.
                mThemeAttrs = orig.mThemeAttrs;
                mStateSets = orig.mStateSets;
            } else {
                mThemeAttrs = null;
                mStateSets = new int[getCapacity()][];
            }
        }

        void mutate() {
            mThemeAttrs = mThemeAttrs != null ? mThemeAttrs.clone() : null;

            final int[][] stateSets = new int[mStateSets.length][];
            for (int i = mStateSets.length - 1; i >= 0; i--) {
                stateSets[i] = mStateSets[i] != null ? mStateSets[i].clone() : null;
            }
            mStateSets = stateSets;
        }

        int addStateSet(int[] stateSet, Drawable drawable) {
            final int pos = addChild(drawable);
            mStateSets[pos] = stateSet;
            return pos;
        }

        int indexOfStateSet(int[] stateSet) {
            final int[][] stateSets = mStateSets;
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (StateSet.stateSetMatches(stateSets[i], stateSet)) {
                    return i;
                }
            }
            return -1;
        }

        boolean hasFocusStateSpecified() {
            return StateSet.containsAttribute(mStateSets, R.attr.state_focused);
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
        public boolean canApplyTheme() {
            return mThemeAttrs != null || super.canApplyTheme();
        }

        @Override
        public void growArray(int oldSize, int newSize) {
            super.growArray(oldSize, newSize);
            final int[][] newStateSets = new int[newSize][];
            System.arraycopy(mStateSets, 0, newStateSets, 0, oldSize);
            mStateSets = newStateSets;
        }
    }

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);

        onStateChange(getState());
    }

    protected void setConstantState(@NonNull DrawableContainerState state) {
        super.setConstantState(state);

        if (state instanceof StateListState) {
            mStateListState = (StateListState) state;
        }
    }

    private StateListDrawable(StateListState state, Resources res) {
        // Every state list drawable has its own constant state.
        final StateListState newState = new StateListState(state, this, res);
        setConstantState(newState);
        onStateChange(getState());
    }

    /**
     * This constructor exists so subclasses can avoid calling the default
     * constructor and setting up a StateListDrawable-specific constant state.
     */
    StateListDrawable(@Nullable StateListState state) {
        if (state != null) {
            setConstantState(state);
        }
    }
}

