/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.videoeditor;

import java.io.File;

/**
 * This class is super class for all transitions. Transitions (with the
 * exception of TransitionAtStart and TransitioAtEnd) can only be inserted
 * between media items.
 *
 * Adding a transition between MediaItems makes the
 * duration of the storyboard shorter by the duration of the Transition itself.
 * As a result, if the duration of the transition is larger than the smaller
 * duration of the two MediaItems associated with the Transition, an exception
 * will be thrown.
 *
 * During a transition, the audio track are cross-fading
 * automatically. {@hide}
 */
public abstract class Transition {
    // The transition behavior
    private static final int BEHAVIOR_MIN_VALUE = 0;
    /** The transition starts slowly and speed up */
    public static final int BEHAVIOR_SPEED_UP = 0;
    /** The transition start fast and speed down */
    public static final int BEHAVIOR_SPEED_DOWN = 1;
    /** The transition speed is constant */
    public static final int BEHAVIOR_LINEAR = 2;
    /** The transition starts fast and ends fast with a slow middle */
    public static final int BEHAVIOR_MIDDLE_SLOW = 3;
    /** The transition starts slowly and ends slowly with a fast middle */
    public static final int BEHAVIOR_MIDDLE_FAST = 4;

    private static final int BEHAVIOR_MAX_VALUE = 4;

    // The unique id of the transition
    private final String mUniqueId;

    // The transition is applied at the end of this media item
    private final MediaItem mAfterMediaItem;
    // The transition is applied at the beginning of this media item
    private final MediaItem mBeforeMediaItem;

    // The transition behavior
    protected final int mBehavior;

    // The transition duration
    protected long mDurationMs;

    // The transition filename
    protected String mFilename;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private Transition() {
        this(null, null, null, 0, 0);
    }

    /**
     * Constructor
     *
     * @param transitionId The transition id
     * @param afterMediaItem The transition is applied to the end of this
     *      media item
     * @param beforeMediaItem The transition is applied to the beginning of
     *      this media item
     * @param durationMs The duration of the transition in milliseconds
     * @param behavior The transition behavior
     */
    protected Transition(String transitionId, MediaItem afterMediaItem, MediaItem beforeMediaItem,
            long durationMs, int behavior) {
        if (behavior < BEHAVIOR_MIN_VALUE || behavior > BEHAVIOR_MAX_VALUE) {
            throw new IllegalArgumentException("Invalid behavior: " + behavior);
        }
        mUniqueId = transitionId;
        mAfterMediaItem = afterMediaItem;
        mBeforeMediaItem = beforeMediaItem;
        mDurationMs = durationMs;
        mBehavior = behavior;
    }

    /**
     * @return The of the transition
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * @return The media item at the end of which the transition is applied
     */
    public MediaItem getAfterMediaItem() {
        return mAfterMediaItem;
    }

    /**
     * @return The media item at the beginning of which the transition is applied
     */
    public MediaItem getBeforeMediaItem() {
        return mBeforeMediaItem;
    }

    /**
     * Set the duration of the transition.
     *
     * @param durationMs the duration of the transition in milliseconds
     */
    public void setDuration(long durationMs) {
        mDurationMs = durationMs;
    }

    /**
     * @return the duration of the transition in milliseconds
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * @return The behavior
     */
    public int getBehavior() {
        return mBehavior;
    }

    /**
     * Generate the video clip for the specified transition.
     * This method may block for a significant amount of time.
     *
     * Before the method completes execution it sets the mFilename to
     * the name of the newly generated transition video clip file.
     */
    abstract void generate();

    /**
     * Remove any resources associated with this transition
     */
    void invalidate() {
        if (mFilename != null) {
            new File(mFilename).delete();
            mFilename = null;
        }
    }

    /**
     * @return true if the transition is generated
     */
    boolean isGenerated() {
        return (mFilename != null);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Transition)) {
            return false;
        }
        return mUniqueId.equals(((Transition)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }
}
