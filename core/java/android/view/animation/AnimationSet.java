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

package android.view.animation;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a group of Animations that should be played together.
 * The transformation of each individual animation are composed 
 * together into a single transform. 
 * If AnimationSet sets any properties that its children also set
 * (for example, duration or fillBefore), the values of AnimationSet
 * override the child values.
 */
public class AnimationSet extends Animation {
    private static final int PROPERTY_FILL_AFTER_MASK         = 0x1;
    private static final int PROPERTY_FILL_BEFORE_MASK        = 0x2;
    private static final int PROPERTY_REPEAT_MODE_MASK        = 0x4;
    private static final int PROPERTY_START_OFFSET_MASK       = 0x8;
    private static final int PROPERTY_SHARE_INTERPOLATOR_MASK = 0x10;
    private static final int PROPERTY_DURATION_MASK           = 0x20;
    private static final int PROPERTY_MORPH_MATRIX_MASK       = 0x40;
    private static final int PROPERTY_CHANGE_BOUNDS_MASK      = 0x80;

    private int mFlags = 0;

    private ArrayList<Animation> mAnimations = new ArrayList<Animation>();

    private Transformation mTempTransformation = new Transformation();

    private long mLastEnd;

    private long[] mStoredOffsets;

    /**
     * Constructor used when an AnimationSet is loaded from a resource. 
     * 
     * @param context Application context to use
     * @param attrs Attribute set from which to read values
     */
    public AnimationSet(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a =
            context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.AnimationSet);
        
        setFlag(PROPERTY_SHARE_INTERPOLATOR_MASK,
                a.getBoolean(com.android.internal.R.styleable.AnimationSet_shareInterpolator, true));
        init();
        
        a.recycle();
    }
    
    
    /**
     * Constructor to use when building an AnimationSet from code
     * 
     * @param shareInterpolator Pass true if all of the animations in this set
     *        should use the interpolator assocciated with this AnimationSet.
     *        Pass false if each animation should use its own interpolator.
     */
    public AnimationSet(boolean shareInterpolator) {
        setFlag(PROPERTY_SHARE_INTERPOLATOR_MASK, shareInterpolator);
        init();
    }

    @Override
    protected AnimationSet clone() throws CloneNotSupportedException {
        final AnimationSet animation = (AnimationSet) super.clone();
        animation.mTempTransformation = new Transformation();
        animation.mAnimations = new ArrayList<Animation>();

        final int count = mAnimations.size();
        final ArrayList<Animation> animations = mAnimations;

        for (int i = 0; i < count; i++) {
            animation.mAnimations.add(animations.get(i).clone());
        }

        return animation;
    }

    private void setFlag(int mask, boolean value) {
        if (value) {
            mFlags |= mask;
        } else {
            mFlags &= ~mask;
        }
    }

    private void init() {
        mStartTime = 0;
        mDuration = 0;
    }

    @Override
    public void setFillAfter(boolean fillAfter) {
        mFlags |= PROPERTY_FILL_AFTER_MASK;
        super.setFillAfter(fillAfter);
    }

    @Override
    public void setFillBefore(boolean fillBefore) {
        mFlags |= PROPERTY_FILL_BEFORE_MASK;
        super.setFillBefore(fillBefore);
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        mFlags |= PROPERTY_REPEAT_MODE_MASK;
        super.setRepeatMode(repeatMode);
    }

    @Override
    public void setStartOffset(long startOffset) {
        mFlags |= PROPERTY_START_OFFSET_MASK;
        super.setStartOffset(startOffset);
    }

    /**
     * <p>Sets the duration of every child animation.</p>
     *
     * @param durationMillis the duration of the animation, in milliseconds, for
     *        every child in this set
     */
    @Override
    public void setDuration(long durationMillis) {
        mFlags |= PROPERTY_DURATION_MASK;
        super.setDuration(durationMillis);
    }

    /**
     * Add a child animation to this animation set.
     * The transforms of the child animations are applied in the order
     * that they were added
     * @param a Animation to add.
     */
    public void addAnimation(Animation a) {
        mAnimations.add(a);

        boolean noMatrix = (mFlags & PROPERTY_MORPH_MATRIX_MASK) == 0;
        if (noMatrix && a.willChangeTransformationMatrix()) {
            mFlags |= PROPERTY_MORPH_MATRIX_MASK;
        }

        boolean changeBounds = (mFlags & PROPERTY_CHANGE_BOUNDS_MASK) == 0;
        if (changeBounds && a.willChangeTransformationMatrix()) {
            mFlags |= PROPERTY_CHANGE_BOUNDS_MASK;
        }

        if (mAnimations.size() == 1) {
            mDuration = a.getStartOffset() + a.getDuration();
            mLastEnd = mStartOffset + mDuration;
        } else {
            mLastEnd = Math.max(mLastEnd, a.getStartOffset() + a.getDuration());
            mDuration = mLastEnd - mStartOffset;
        }
    }
    
    /**
     * Sets the start time of this animation and all child animations
     * 
     * @see android.view.animation.Animation#setStartTime(long)
     */
    @Override
    public void setStartTime(long startTimeMillis) {
        super.setStartTime(startTimeMillis);

        final int count = mAnimations.size();
        final ArrayList<Animation> animations = mAnimations;

        for (int i = 0; i < count; i++) {
            Animation a = animations.get(i);
            a.setStartTime(startTimeMillis);
        }
    }

    @Override
    public long getStartTime() {
        long startTime = Long.MAX_VALUE;

        final int count = mAnimations.size();
        final ArrayList<Animation> animations = mAnimations;

        for (int i = 0; i < count; i++) {
            Animation a = animations.get(i);
            startTime = Math.min(startTime, a.getStartTime());
        }

        return startTime;
    }

    @Override
    public void restrictDuration(long durationMillis) {
        super.restrictDuration(durationMillis);

        final ArrayList<Animation> animations = mAnimations;
        int count = animations.size();

        for (int i = 0; i < count; i++) {
            animations.get(i).restrictDuration(durationMillis);
        }
    }
    
    /**
     * The duration of an AnimationSet is defined to be the 
     * duration of the longest child animation.
     * 
     * @see android.view.animation.Animation#getDuration()
     */
    @Override
    public long getDuration() {
        final ArrayList<Animation> animations = mAnimations;
        final int count = animations.size();
        long duration = 0;

        boolean durationSet = (mFlags & PROPERTY_DURATION_MASK) == PROPERTY_DURATION_MASK;
        if (durationSet) {
            duration = mDuration;
        } else {
            for (int i = 0; i < count; i++) {
                duration = Math.max(duration, animations.get(i).getDuration());
            }
        }

        return duration;
    }

    /**
     * The duration hint of an animation set is the maximum of the duration
     * hints of all of its component animations.
     * 
     * @see android.view.animation.Animation#computeDurationHint
     */
    public long computeDurationHint() {
        long duration = 0;
        final int count = mAnimations.size();
        final ArrayList<Animation> animations = mAnimations;
        for (int i = count - 1; i >= 0; --i) {
            final long d = animations.get(i).computeDurationHint();
            if (d > duration) duration = d;
        }
        return duration;
    }

    /**
     * @hide
     */
    public void initializeInvalidateRegion(int left, int top, int right, int bottom) {
        final RectF region = mPreviousRegion;
        region.set(left, top, right, bottom);
        region.inset(-1.0f, -1.0f);

        if (mFillBefore) {
            final int count = mAnimations.size();
            final ArrayList<Animation> animations = mAnimations;
            final Transformation temp = mTempTransformation;

            final Transformation previousTransformation = mPreviousTransformation;

            for (int i = count - 1; i >= 0; --i) {
                final Animation a = animations.get(i);

                temp.clear();
                final Interpolator interpolator = a.mInterpolator;
                a.applyTransformation(interpolator != null ? interpolator.getInterpolation(0.0f)
                        : 0.0f, temp);
                previousTransformation.compose(temp);
            }
        }
    }

    /**
     * The transformation of an animation set is the concatenation of all of its
     * component animations.
     * 
     * @see android.view.animation.Animation#getTransformation
     */
    @Override
    public boolean getTransformation(long currentTime, Transformation t) {
        final int count = mAnimations.size();
        final ArrayList<Animation> animations = mAnimations;
        final Transformation temp = mTempTransformation;

        boolean more = false;
        boolean started = false;
        boolean ended = true;

        t.clear();

        for (int i = count - 1; i >= 0; --i) {
            final Animation a = animations.get(i);

            temp.clear();
            more = a.getTransformation(currentTime, temp) || more;
            t.compose(temp);

            started = started || a.hasStarted();
            ended = a.hasEnded() && ended;
        }

        if (started && !mStarted) {
            if (mListener != null) {
                mListener.onAnimationStart(this);
            }
            mStarted = true;
        }

        if (ended != mEnded) {
            if (mListener != null) {
                mListener.onAnimationEnd(this);
            }
            mEnded = ended;
        }

        return more;
    }
    
    /**
     * @see android.view.animation.Animation#scaleCurrentDuration(float)
     */
    @Override
    public void scaleCurrentDuration(float scale) {
        final ArrayList<Animation> animations = mAnimations;
        int count = animations.size();
        for (int i = 0; i < count; i++) {
            animations.get(i).scaleCurrentDuration(scale);
        }
    }

    /**
     * @see android.view.animation.Animation#initialize(int, int, int, int)
     */
    @Override
    public void initialize(int width, int height, int parentWidth, int parentHeight) {
        super.initialize(width, height, parentWidth, parentHeight);

        boolean durationSet = (mFlags & PROPERTY_DURATION_MASK) == PROPERTY_DURATION_MASK;
        boolean fillAfterSet = (mFlags & PROPERTY_FILL_AFTER_MASK) == PROPERTY_FILL_AFTER_MASK;
        boolean fillBeforeSet = (mFlags & PROPERTY_FILL_BEFORE_MASK) == PROPERTY_FILL_BEFORE_MASK;
        boolean repeatModeSet = (mFlags & PROPERTY_REPEAT_MODE_MASK) == PROPERTY_REPEAT_MODE_MASK;
        boolean shareInterpolator = (mFlags & PROPERTY_SHARE_INTERPOLATOR_MASK)
                == PROPERTY_SHARE_INTERPOLATOR_MASK;
        boolean startOffsetSet = (mFlags & PROPERTY_START_OFFSET_MASK)
                == PROPERTY_START_OFFSET_MASK;

        if (shareInterpolator) {
            ensureInterpolator();
        }

        final ArrayList<Animation> children = mAnimations;
        final int count = children.size();

        final long duration = mDuration;
        final boolean fillAfter = mFillAfter;
        final boolean fillBefore = mFillBefore;
        final int repeatMode = mRepeatMode;
        final Interpolator interpolator = mInterpolator;
        final long startOffset = mStartOffset;


        long[] storedOffsets = mStoredOffsets;
        if (startOffsetSet) {
            if (storedOffsets == null || storedOffsets.length != count) {
                storedOffsets = mStoredOffsets = new long[count];
            }
        } else if (storedOffsets != null) {
            storedOffsets = mStoredOffsets = null;
        }

        for (int i = 0; i < count; i++) {
            Animation a = children.get(i);
            if (durationSet) {
                a.setDuration(duration);
            }
            if (fillAfterSet) {
                a.setFillAfter(fillAfter);
            }
            if (fillBeforeSet) {
                a.setFillBefore(fillBefore);
            }
            if (repeatModeSet) {
                a.setRepeatMode(repeatMode);
            }
            if (shareInterpolator) {
                a.setInterpolator(interpolator);
            }
            if (startOffsetSet) {
                long offset = a.getStartOffset();
                a.setStartOffset(offset + startOffset);
                storedOffsets[i] = offset;
            }
            a.initialize(width, height, parentWidth, parentHeight);
        }
    }

    @Override
    public void reset() {
        super.reset();
        restoreChildrenStartOffset();
    }

    /**
     * @hide
     */
    void restoreChildrenStartOffset() {
        final long[] offsets = mStoredOffsets;
        if (offsets == null) return;

        final ArrayList<Animation> children = mAnimations;
        final int count = children.size();

        for (int i = 0; i < count; i++) {
            children.get(i).setStartOffset(offsets[i]);
        }
    }

    /**
     * @return All the child animations in this AnimationSet. Note that
     * this may include other AnimationSets, which are not expanded.
     */
    public List<Animation> getAnimations() {
        return mAnimations;
    }

    @Override
    public boolean willChangeTransformationMatrix() {
        return (mFlags & PROPERTY_MORPH_MATRIX_MASK) == PROPERTY_MORPH_MATRIX_MASK;
    }

    @Override
    public boolean willChangeBounds() {
        return (mFlags & PROPERTY_CHANGE_BOUNDS_MASK) == PROPERTY_CHANGE_BOUNDS_MASK;
    }
}
