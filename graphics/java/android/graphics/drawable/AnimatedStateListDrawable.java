/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LongSparseLongArray;
import android.util.SparseIntArray;
import android.util.StateSet;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Drawable containing a set of Drawable keyframes where the currently displayed
 * keyframe is chosen based on the current state set. Animations between
 * keyframes may optionally be defined using transition elements.
 * <p>
 * This drawable can be defined in an XML file with the <code>
 * &lt;animated-selector></code> element. Each keyframe Drawable is defined in a
 * nested <code>&lt;item></code> element. Transitions are defined in a nested
 * <code>&lt;transition></code> element.
 *
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
public class AnimatedStateListDrawable extends StateListDrawable {
    private static final String LOGTAG = AnimatedStateListDrawable.class.getSimpleName();

    private static final String ELEMENT_TRANSITION = "transition";
    private static final String ELEMENT_ITEM = "item";

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private AnimatedStateListState mState;

    /** The currently running transition, if any. */
    private Transition mTransition;

    /** Index to be set after the transition ends. */
    private int mTransitionToIndex = -1;

    /** Index away from which we are transitioning. */
    private int mTransitionFromIndex = -1;

    private boolean mMutated;

    public AnimatedStateListDrawable() {
        this(null, null);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);

        if (mTransition != null && (changed || restart)) {
            if (visible) {
                mTransition.start();
            } else {
                // Ensure we're showing the correct state when visible.
                jumpToCurrentState();
            }
        }

        return changed;
    }

    /**
     * Add a new drawable to the set of keyframes.
     *
     * @param stateSet An array of resource IDs to associate with the keyframe
     * @param drawable The drawable to show when in the specified state, may not be null
     * @param id The unique identifier for the keyframe
     */
    public void addState(@NonNull int[] stateSet, @NonNull Drawable drawable, int id) {
        if (drawable == null) {
            throw new IllegalArgumentException("Drawable must not be null");
        }

        mState.addStateSet(stateSet, drawable, id);
        onStateChange(getState());
    }

    /**
     * Adds a new transition between keyframes.
     *
     * @param fromId Unique identifier of the starting keyframe
     * @param toId Unique identifier of the ending keyframe
     * @param transition An {@link Animatable} drawable to use as a transition, may not be null
     * @param reversible Whether the transition can be reversed
     */
    public <T extends Drawable & Animatable> void addTransition(int fromId, int toId,
            @NonNull T transition, boolean reversible) {
        if (transition == null) {
            throw new IllegalArgumentException("Transition drawable must not be null");
        }

        mState.addTransition(fromId, toId, transition, reversible);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        // If we're not already at the target index, either attempt to find a
        // valid transition to it or jump directly there.
        final int targetIndex = mState.indexOfKeyframe(stateSet);
        boolean changed = targetIndex != getCurrentIndex()
                && (selectTransition(targetIndex) || selectDrawable(targetIndex));

        // We need to propagate the state change to the current drawable, but
        // we can't call StateListDrawable.onStateChange() without changing the
        // current drawable.
        final Drawable current = getCurrent();
        if (current != null) {
            changed |= current.setState(stateSet);
        }

        return changed;
    }

    private boolean selectTransition(int toIndex) {
        final int fromIndex;
        final Transition currentTransition = mTransition;
        if (currentTransition != null) {
            if (toIndex == mTransitionToIndex) {
                // Already animating to that keyframe.
                return true;
            } else if (toIndex == mTransitionFromIndex && currentTransition.canReverse()) {
                // Reverse the current animation.
                currentTransition.reverse();
                mTransitionToIndex = mTransitionFromIndex;
                mTransitionFromIndex = toIndex;
                return true;
            }

            // Start the next transition from the end of the current one.
            fromIndex = mTransitionToIndex;

            // Changing animation, end the current animation.
            currentTransition.stop();
        } else {
            fromIndex = getCurrentIndex();
        }

        // Reset state.
        mTransition = null;
        mTransitionFromIndex = -1;
        mTransitionToIndex = -1;

        final AnimatedStateListState state = mState;
        final int fromId = state.getKeyframeIdAt(fromIndex);
        final int toId = state.getKeyframeIdAt(toIndex);
        if (toId == 0 || fromId == 0) {
            // Missing a keyframe ID.
            return false;
        }

        final int transitionIndex = state.indexOfTransition(fromId, toId);
        if (transitionIndex < 0) {
            // Couldn't select a transition.
            return false;
        }

        boolean hasReversibleFlag = state.transitionHasReversibleFlag(fromId, toId);

        // This may fail if we're already on the transition, but that's okay!
        selectDrawable(transitionIndex);

        final Transition transition;
        final Drawable d = getCurrent();
        if (d instanceof AnimationDrawable) {
            final boolean reversed = state.isTransitionReversed(fromId, toId);

            transition = new AnimationDrawableTransition((AnimationDrawable) d,
                    reversed, hasReversibleFlag);
        } else if (d instanceof AnimatedVectorDrawable) {
            final boolean reversed = state.isTransitionReversed(fromId, toId);

            transition = new AnimatedVectorDrawableTransition((AnimatedVectorDrawable) d,
                    reversed, hasReversibleFlag);
        } else if (d instanceof Animatable) {
            transition = new AnimatableTransition((Animatable) d);
        } else {
            // We don't know how to animate this transition.
            return false;
        }

        transition.start();

        mTransition = transition;
        mTransitionFromIndex = fromIndex;
        mTransitionToIndex = toIndex;
        return true;
    }

    private static abstract class Transition {
        public abstract void start();
        public abstract void stop();

        public void reverse() {
            // Not supported by default.
        }

        public boolean canReverse() {
            return false;
        }
    }

    private static class AnimatableTransition  extends Transition {
        private final Animatable mA;

        public AnimatableTransition(Animatable a) {
            mA = a;
        }

        @Override
        public void start() {
            mA.start();
        }

        @Override
        public void stop() {
            mA.stop();
        }
    }


    private static class AnimationDrawableTransition  extends Transition {
        private final ObjectAnimator mAnim;

        // Even AnimationDrawable is always reversible technically, but
        // we should obey the XML's android:reversible flag.
        private final boolean mHasReversibleFlag;

        public AnimationDrawableTransition(AnimationDrawable ad,
                boolean reversed, boolean hasReversibleFlag) {
            final int frameCount = ad.getNumberOfFrames();
            final int fromFrame = reversed ? frameCount - 1 : 0;
            final int toFrame = reversed ? 0 : frameCount - 1;
            final FrameInterpolator interp = new FrameInterpolator(ad, reversed);
            final ObjectAnimator anim = ObjectAnimator.ofInt(ad, "currentIndex", fromFrame, toFrame);
            anim.setAutoCancel(true);
            anim.setDuration(interp.getTotalDuration());
            anim.setInterpolator(interp);
            mHasReversibleFlag = hasReversibleFlag;
            mAnim = anim;
        }

        @Override
        public boolean canReverse() {
            return mHasReversibleFlag;
        }

        @Override
        public void start() {
            mAnim.start();
        }

        @Override
        public void reverse() {
            mAnim.reverse();
        }

        @Override
        public void stop() {
            mAnim.cancel();
        }
    }

    private static class AnimatedVectorDrawableTransition  extends Transition {
        private final AnimatedVectorDrawable mAvd;

        // mReversed is indicating the current transition's direction.
        private final boolean mReversed;

        // mHasReversibleFlag is indicating whether the whole transition has
        // reversible flag set to true.
        // If mHasReversibleFlag is false, then mReversed is always false.
        private final boolean mHasReversibleFlag;

        public AnimatedVectorDrawableTransition(AnimatedVectorDrawable avd,
                boolean reversed, boolean hasReversibleFlag) {
            mAvd = avd;
            mReversed = reversed;
            mHasReversibleFlag = hasReversibleFlag;
        }

        @Override
        public boolean canReverse() {
            // When the transition's XML says it is not reversible, then we obey
            // it, even if the AVD itself is reversible.
            // This will help the single direction transition.
            return mAvd.canReverse() && mHasReversibleFlag;
        }

        @Override
        public void start() {
            if (mReversed) {
                reverse();
            } else {
                mAvd.start();
            }
        }

        @Override
        public void reverse() {
            if (canReverse()) {
                mAvd.reverse();
            } else {
                Log.w(LOGTAG, "Can't reverse, either the reversible is set to false,"
                        + " or the AnimatedVectorDrawable can't reverse");
            }
        }

        @Override
        public void stop() {
            mAvd.stop();
        }
    }


    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();

        if (mTransition != null) {
            mTransition.stop();
            mTransition = null;

            selectDrawable(mTransitionToIndex);
            mTransitionToIndex = -1;
            mTransitionFromIndex = -1;
        }
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(
                r, theme, attrs, R.styleable.AnimatedStateListDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.AnimatedStateListDrawable_visible);
        updateStateFromTypedArray(a);
        updateDensity(r);
        a.recycle();

        inflateChildElements(r, parser, attrs, theme);

        init();
    }

    @Override
    public void applyTheme(@Nullable Theme theme) {
        super.applyTheme(theme);

        final AnimatedStateListState state = mState;
        if (state == null || state.mAnimThemeAttrs == null) {
            return;
        }

        final TypedArray a = theme.resolveAttributes(
                state.mAnimThemeAttrs, R.styleable.AnimatedRotateDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        init();
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final AnimatedStateListState state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mAnimThemeAttrs = a.extractThemeAttrs();

        state.setVariablePadding(a.getBoolean(
                R.styleable.AnimatedStateListDrawable_variablePadding, state.mVariablePadding));
        state.setConstantSize(a.getBoolean(
                R.styleable.AnimatedStateListDrawable_constantSize, state.mConstantSize));
        state.setEnterFadeDuration(a.getInt(
                R.styleable.AnimatedStateListDrawable_enterFadeDuration, state.mEnterFadeDuration));
        state.setExitFadeDuration(a.getInt(
                R.styleable.AnimatedStateListDrawable_exitFadeDuration, state.mExitFadeDuration));

        setDither(a.getBoolean(
                R.styleable.AnimatedStateListDrawable_dither, state.mDither));
        setAutoMirrored(a.getBoolean(
                R.styleable.AnimatedStateListDrawable_autoMirrored, state.mAutoMirrored));
    }

    private void init() {
        onStateChange(getState());
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        int type;

        final int innerDepth = parser.getDepth() + 1;
        int depth;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth
                || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth) {
                continue;
            }

            if (parser.getName().equals(ELEMENT_ITEM)) {
                parseItem(r, parser, attrs, theme);
            } else if (parser.getName().equals(ELEMENT_TRANSITION)) {
                parseTransition(r, parser, attrs, theme);
            }
        }
    }

    private int parseTransition(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        // This allows state list drawable item elements to be themed at
        // inflation time but does NOT make them work for Zygote preload.
        final TypedArray a = obtainAttributes(r, theme, attrs,
                R.styleable.AnimatedStateListDrawableTransition);
        final int fromId = a.getResourceId(
                R.styleable.AnimatedStateListDrawableTransition_fromId, 0);
        final int toId = a.getResourceId(
                R.styleable.AnimatedStateListDrawableTransition_toId, 0);
        final boolean reversible = a.getBoolean(
                R.styleable.AnimatedStateListDrawableTransition_reversible, false);
        Drawable dr = a.getDrawable(
                R.styleable.AnimatedStateListDrawableTransition_drawable);
        a.recycle();

        // Loading child elements modifies the state of the AttributeSet's
        // underlying parser, so it needs to happen after obtaining
        // attributes and extracting states.
        if (dr == null) {
            int type;
            while ((type = parser.next()) == XmlPullParser.TEXT) {
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException(
                        parser.getPositionDescription()
                                + ": <transition> tag requires a 'drawable' attribute or "
                                + "child tag defining a drawable");
            }
            dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
        }

        return mState.addTransition(fromId, toId, dr, reversible);
    }

    private int parseItem(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        // This allows state list drawable item elements to be themed at
        // inflation time but does NOT make them work for Zygote preload.
        final TypedArray a = obtainAttributes(r, theme, attrs,
                R.styleable.AnimatedStateListDrawableItem);
        final int keyframeId = a.getResourceId(R.styleable.AnimatedStateListDrawableItem_id, 0);
        Drawable dr = a.getDrawable(R.styleable.AnimatedStateListDrawableItem_drawable);
        a.recycle();

        final int[] states = extractStateSet(attrs);

        // Loading child elements modifies the state of the AttributeSet's
        // underlying parser, so it needs to happen after obtaining
        // attributes and extracting states.
        if (dr == null) {
            int type;
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

        return mState.addStateSet(states, dr, keyframeId);
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState.mutate();
            mMutated = true;
        }

        return this;
    }

    @Override
    AnimatedStateListState cloneConstantState() {
        return new AnimatedStateListState(mState, this, null);
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    static class AnimatedStateListState extends StateListState {
        // REVERSED_BIT is indicating the current transition's direction.
        private static final long REVERSED_BIT = 0x100000000l;

        // REVERSIBLE_FLAG_BIT is indicating whether the whole transition has
        // reversible flag set to true.
        private static final long REVERSIBLE_FLAG_BIT = 0x200000000l;

        int[] mAnimThemeAttrs;

        @UnsupportedAppUsage
        LongSparseLongArray mTransitions;
        @UnsupportedAppUsage
        SparseIntArray mStateIds;

        AnimatedStateListState(@Nullable AnimatedStateListState orig,
                @NonNull AnimatedStateListDrawable owner, @Nullable Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                // Perform a shallow copy and rely on mutate() to deep-copy.
                mAnimThemeAttrs = orig.mAnimThemeAttrs;
                mTransitions = orig.mTransitions;
                mStateIds = orig.mStateIds;
            } else {
                mTransitions = new LongSparseLongArray();
                mStateIds = new SparseIntArray();
            }
        }

        void mutate() {
            mTransitions = mTransitions.clone();
            mStateIds = mStateIds.clone();
        }

        int addTransition(int fromId, int toId, @NonNull Drawable anim, boolean reversible) {
            final int pos = super.addChild(anim);
            final long keyFromTo = generateTransitionKey(fromId, toId);
            long reversibleBit = 0;
            if (reversible) {
                reversibleBit = REVERSIBLE_FLAG_BIT;
            }
            mTransitions.append(keyFromTo, pos | reversibleBit);

            if (reversible) {
                final long keyToFrom = generateTransitionKey(toId, fromId);
                mTransitions.append(keyToFrom, pos | REVERSED_BIT | reversibleBit);
            }

            return pos;
        }

        int addStateSet(@NonNull int[] stateSet, @NonNull Drawable drawable, int id) {
            final int index = super.addStateSet(stateSet, drawable);
            mStateIds.put(index, id);
            return index;
        }

        int indexOfKeyframe(@NonNull int[] stateSet) {
            final int index = super.indexOfStateSet(stateSet);
            if (index >= 0) {
                return index;
            }

            return super.indexOfStateSet(StateSet.WILD_CARD);
        }

        int getKeyframeIdAt(int index) {
            return index < 0 ? 0 : mStateIds.get(index, 0);
        }

        int indexOfTransition(int fromId, int toId) {
            final long keyFromTo = generateTransitionKey(fromId, toId);
            return (int) mTransitions.get(keyFromTo, -1);
        }

        boolean isTransitionReversed(int fromId, int toId) {
            final long keyFromTo = generateTransitionKey(fromId, toId);
            return (mTransitions.get(keyFromTo, -1) & REVERSED_BIT) != 0;
        }

        boolean transitionHasReversibleFlag(int fromId, int toId) {
            final long keyFromTo = generateTransitionKey(fromId, toId);
            return (mTransitions.get(keyFromTo, -1) & REVERSIBLE_FLAG_BIT) != 0;
        }

        @Override
        public boolean canApplyTheme() {
            return mAnimThemeAttrs != null || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new AnimatedStateListDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new AnimatedStateListDrawable(this, res);
        }

        private static long generateTransitionKey(int fromId, int toId) {
            return (long) fromId << 32 | toId;
        }
    }

    @Override
    protected void setConstantState(@NonNull DrawableContainerState state) {
        super.setConstantState(state);

        if (state instanceof AnimatedStateListState) {
            mState = (AnimatedStateListState) state;
        }
    }

    private AnimatedStateListDrawable(@Nullable AnimatedStateListState state, @Nullable Resources res) {
        super(null);

        // Every animated state list drawable has its own constant state.
        final AnimatedStateListState newState = new AnimatedStateListState(state, this, res);
        setConstantState(newState);
        onStateChange(getState());
        jumpToCurrentState();
    }

    /**
     * Interpolates between frames with respect to their individual durations.
     */
    private static class FrameInterpolator implements TimeInterpolator {
        private int[] mFrameTimes;
        private int mFrames;
        private int mTotalDuration;

        public FrameInterpolator(AnimationDrawable d, boolean reversed) {
            updateFrames(d, reversed);
        }

        public int updateFrames(AnimationDrawable d, boolean reversed) {
            final int N = d.getNumberOfFrames();
            mFrames = N;

            if (mFrameTimes == null || mFrameTimes.length < N) {
                mFrameTimes = new int[N];
            }

            final int[] frameTimes = mFrameTimes;
            int totalDuration = 0;
            for (int i = 0; i < N; i++) {
                final int duration = d.getDuration(reversed ? N - i - 1 : i);
                frameTimes[i] = duration;
                totalDuration += duration;
            }

            mTotalDuration = totalDuration;
            return totalDuration;
        }

        public int getTotalDuration() {
            return mTotalDuration;
        }

        @Override
        public float getInterpolation(float input) {
            final int elapsed = (int) (input * mTotalDuration + 0.5f);
            final int N = mFrames;
            final int[] frameTimes = mFrameTimes;

            // Find the current frame and remaining time within that frame.
            int remaining = elapsed;
            int i = 0;
            while (i < N && remaining >= frameTimes[i]) {
                remaining -= frameTimes[i];
                i++;
            }

            // Remaining time is relative of total duration.
            final float frameElapsed;
            if (i < N) {
                frameElapsed = remaining / (float) mTotalDuration;
            } else {
                frameElapsed = 0;
            }

            return i / (float) N + frameElapsed;
        }
    }
}
