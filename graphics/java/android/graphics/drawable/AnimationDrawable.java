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

import java.io.IOException;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.os.SystemClock;
import android.util.AttributeSet;

/**
 * An object used to create frame-by-frame animations, defined by a series of Drawable objects,
 * which can be used as a View object's background.
 * <p>
 * The simplest way to create a frame-by-frame animation is to define the animation in an XML
 * file, placed in the res/drawable/ folder, and set it as the background to a View object. Then, call
 * {@link #start()} to run the animation.
 * <p>
 * An AnimationDrawable defined in XML consists of a single <code>&lt;animation-list></code> element,
 * and a series of nested <code>&lt;item></code> tags. Each item defines a frame of the animation.
 * See the example below.
 * </p>
 * <p>spin_animation.xml file in res/drawable/ folder:</p>
 * <pre>&lt;!-- Animation frames are wheel0.png -- wheel5.png files inside the
 * res/drawable/ folder --&gt;
 * &lt;animation-list android:id=&quot;@+id/selected&quot; android:oneshot=&quot;false&quot;&gt;
 *    &lt;item android:drawable=&quot;@drawable/wheel0&quot; android:duration=&quot;50&quot; /&gt;
 *    &lt;item android:drawable=&quot;@drawable/wheel1&quot; android:duration=&quot;50&quot; /&gt;
 *    &lt;item android:drawable=&quot;@drawable/wheel2&quot; android:duration=&quot;50&quot; /&gt;
 *    &lt;item android:drawable=&quot;@drawable/wheel3&quot; android:duration=&quot;50&quot; /&gt;
 *    &lt;item android:drawable=&quot;@drawable/wheel4&quot; android:duration=&quot;50&quot; /&gt;
 *    &lt;item android:drawable=&quot;@drawable/wheel5&quot; android:duration=&quot;50&quot; /&gt;
 * &lt;/animation-list&gt;</pre>
 *
 * <p>Here is the code to load and play this animation.</p>
 * <pre>
 * // Load the ImageView that will host the animation and
 * // set its background to our AnimationDrawable XML resource.
 * ImageView img = (ImageView)findViewById(R.id.spinning_wheel_image);
 * img.setBackgroundResource(R.drawable.spin_animation);
 *
 * // Get the background, which has been compiled to an AnimationDrawable object.
 * AnimationDrawable frameAnimation = (AnimationDrawable) img.getBackground();
 *
 * // Start the animation (looped playback by default).
 * frameAnimation.start();
 * </pre>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about animating with {@code AnimationDrawable}, read the
 * <a href="{@docRoot}guide/topics/graphics/drawable-animation.html">Drawable Animation</a>
 * developer guide.</p>
 * </div>
 *
 * @attr ref android.R.styleable#AnimationDrawable_visible
 * @attr ref android.R.styleable#AnimationDrawable_variablePadding
 * @attr ref android.R.styleable#AnimationDrawable_oneshot
 * @attr ref android.R.styleable#AnimationDrawableItem_duration
 * @attr ref android.R.styleable#AnimationDrawableItem_drawable
 */
public class AnimationDrawable extends DrawableContainer implements Runnable, Animatable {
    private AnimationState mAnimationState;

    /** The current frame, may be -1 when not animating. */
    private int mCurFrame = -1;

    /** Whether the drawable has an animation callback posted. */
    private boolean mRunning;

    /** Whether the drawable should animate when visible. */
    private boolean mAnimating;

    private boolean mMutated;

    public AnimationDrawable() {
        this(null, null);
    }

    /**
     * Sets whether this AnimationDrawable is visible.
     * <p>
     * When the drawable becomes invisible, it will pause its animation. A
     * subsequent change to visible with <code>restart</code> set to true will
     * restart the animation from the first frame. If <code>restart</code> is
     * false, the animation will resume from the most recent frame.
     *
     * @param visible true if visible, false otherwise
     * @param restart when visible, true to force the animation to restart
     *                from the first frame
     * @return true if the new visibility is different than its previous state
     */
    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        if (visible) {
            if (restart || changed) {
                boolean startFromZero = restart || mCurFrame < 0 ||
                        mCurFrame >= mAnimationState.getChildCount();
                setFrame(startFromZero ? 0 : mCurFrame, true, mAnimating);
            }
        } else {
            unscheduleSelf(this);
        }
        return changed;
    }

    /**
     * <p>Starts the animation, looping if necessary. This method has no effect
     * if the animation is running. Do not call this in the {@link android.app.Activity#onCreate}
     * method of your activity, because the {@link android.graphics.drawable.AnimationDrawable} is
     * not yet fully attached to the window. If you want to play
     * the animation immediately, without requiring interaction, then you might want to call it
     * from the {@link android.app.Activity#onWindowFocusChanged} method in your activity,
     * which will get called when Android brings your window into focus.</p>
     *
     * @see #isRunning()
     * @see #stop()
     */
    @Override
    public void start() {
        mAnimating = true;

        if (!isRunning()) {
            run();
        }
    }

    /**
     * <p>Stops the animation. This method has no effect if the animation is
     * not running.</p>
     *
     * @see #isRunning()
     * @see #start()
     */
    @Override
    public void stop() {
        mAnimating = false;

        if (isRunning()) {
            unscheduleSelf(this);
        }
    }

    /**
     * <p>Indicates whether the animation is currently running or not.</p>
     *
     * @return true if the animation is running, false otherwise
     */
    @Override
    public boolean isRunning() {
        return mRunning;
    }

    /**
     * <p>This method exists for implementation purpose only and should not be
     * called directly. Invoke {@link #start()} instead.</p>
     *
     * @see #start()
     */
    @Override
    public void run() {
        nextFrame(false);
    }

    @Override
    public void unscheduleSelf(Runnable what) {
        mCurFrame = -1;
        mRunning = false;
        super.unscheduleSelf(what);
    }

    /**
     * @return The number of frames in the animation
     */
    public int getNumberOfFrames() {
        return mAnimationState.getChildCount();
    }

    /**
     * @return The Drawable at the specified frame index
     */
    public Drawable getFrame(int index) {
        return mAnimationState.getChild(index);
    }

    /**
     * @return The duration in milliseconds of the frame at the
     * specified index
     */
    public int getDuration(int i) {
        return mAnimationState.mDurations[i];
    }

    /**
     * @return True of the animation will play once, false otherwise
     */
    public boolean isOneShot() {
        return mAnimationState.mOneShot;
    }

    /**
     * Sets whether the animation should play once or repeat.
     *
     * @param oneShot Pass true if the animation should only play once
     */
    public void setOneShot(boolean oneShot) {
        mAnimationState.mOneShot = oneShot;
    }

    /**
     * Add a frame to the animation
     *
     * @param frame The frame to add
     * @param duration How long in milliseconds the frame should appear
     */
    public void addFrame(Drawable frame, int duration) {
        mAnimationState.addFrame(frame, duration);
        if (mCurFrame < 0) {
            setFrame(0, true, false);
        }
    }

    private void nextFrame(boolean unschedule) {
        int next = mCurFrame+1;
        final int N = mAnimationState.getChildCount();
        if (next >= N) {
            next = 0;
        }

        setFrame(next, unschedule, !mAnimationState.mOneShot || next < (N - 1));
    }

    private void setFrame(int frame, boolean unschedule, boolean animate) {
        if (frame >= mAnimationState.getChildCount()) {
            return;
        }
        mAnimating = animate;
        mCurFrame = frame;
        selectDrawable(frame);
        if (unschedule || animate) {
            unscheduleSelf(this);
        }
        if (animate) {
            // Unscheduling may have clobbered these values; restore them
            mCurFrame = frame;
            mRunning = true;
            scheduleSelf(this, SystemClock.uptimeMillis() + mAnimationState.mDurations[frame]);
        }
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.AnimationDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.AnimationDrawable_visible);
        updateStateFromTypedArray(a);
        a.recycle();

        inflateChildElements(r, parser, attrs, theme);

        setFrame(0, true, false);
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        int type;

        final int innerDepth = parser.getDepth()+1;
        int depth;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth || !parser.getName().equals("item")) {
                continue;
            }

            final TypedArray a = obtainAttributes(r, theme, attrs,
                    R.styleable.AnimationDrawableItem);

            final int duration = a.getInt(R.styleable.AnimationDrawableItem_duration, -1);
            if (duration < 0) {
                throw new XmlPullParserException(parser.getPositionDescription()
                        + ": <item> tag requires a 'duration' attribute");
            }

            Drawable dr = a.getDrawable(R.styleable.AnimationDrawableItem_drawable);

            a.recycle();

            if (dr == null) {
                while ((type=parser.next()) == XmlPullParser.TEXT) {
                    // Empty
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new XmlPullParserException(parser.getPositionDescription()
                            + ": <item> tag requires a 'drawable' attribute or child tag"
                            + " defining a drawable");
                }
                dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
            }

            mAnimationState.addFrame(dr, duration);
            if (dr != null) {
                dr.setCallback(this);
            }
        }
    }

    private void updateStateFromTypedArray(TypedArray a) {
        mAnimationState.mVariablePadding = a.getBoolean(
                R.styleable.AnimationDrawable_variablePadding, mAnimationState.mVariablePadding);

        mAnimationState.mOneShot = a.getBoolean(
                R.styleable.AnimationDrawable_oneshot, mAnimationState.mOneShot);
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mAnimationState.mutate();
            mMutated = true;
        }
        return this;
    }

    @Override
    AnimationState cloneConstantState() {
        return new AnimationState(mAnimationState, this, null);
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    private final static class AnimationState extends DrawableContainerState {
        private int[] mDurations;
        private boolean mOneShot = false;

        AnimationState(AnimationState orig, AnimationDrawable owner,
                Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                mDurations = orig.mDurations;
                mOneShot = orig.mOneShot;
            } else {
                mDurations = new int[getCapacity()];
                mOneShot = false;
            }
        }

        private void mutate() {
            mDurations = mDurations.clone();
        }

        @Override
        public Drawable newDrawable() {
            return new AnimationDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new AnimationDrawable(this, res);
        }

        public void addFrame(Drawable dr, int dur) {
            // Do not combine the following. The array index must be evaluated before
            // the array is accessed because super.addChild(dr) has a side effect on mDurations.
            int pos = super.addChild(dr);
            mDurations[pos] = dur;
        }

        @Override
        public void growArray(int oldSize, int newSize) {
            super.growArray(oldSize, newSize);
            int[] newDurations = new int[newSize];
            System.arraycopy(mDurations, 0, newDurations, 0, oldSize);
            mDurations = newDurations;
        }
    }

    @Override
    protected void setConstantState(@NonNull DrawableContainerState state) {
        super.setConstantState(state);

        if (state instanceof AnimationState) {
            mAnimationState = (AnimationState) state;
        }
    }

    private AnimationDrawable(AnimationState state, Resources res) {
        final AnimationState as = new AnimationState(state, this, res);
        setConstantState(as);
        if (state != null) {
            setFrame(0, true, false);
        }
    }
}

