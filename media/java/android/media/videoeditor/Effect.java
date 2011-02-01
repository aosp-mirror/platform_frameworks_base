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

/**
 * This is the super class for all effects. An effect can only be applied to a
 * single media item.
 * {@hide}
 */
public abstract class Effect {
    /**
     *  Instance variables
     */
    private final String mUniqueId;
    /**
     *  The effect owner
     */
    private final MediaItem mMediaItem;

    protected long mDurationMs;
    /**
     *  The start time of the effect relative to the beginning
     *  of the media item
     */
    protected long mStartTimeMs;

    /**
     * Default constructor
     */
    @SuppressWarnings("unused")
    private Effect() {
        mMediaItem = null;
        mUniqueId = null;
        mStartTimeMs = 0;
        mDurationMs = 0;
    }

    /**
     * Constructor
     *
     * @param mediaItem The media item owner
     * @param effectId The effect id
     * @param startTimeMs The start time relative to the media item to which it
     *            is applied
     * @param durationMs The effect duration in milliseconds
     */
    public Effect(MediaItem mediaItem, String effectId, long startTimeMs,
                  long durationMs) {
        if (mediaItem == null) {
            throw new IllegalArgumentException("Media item cannot be null");
        }

        if ((startTimeMs < 0) || (durationMs < 0)) {
             throw new IllegalArgumentException("Invalid start time Or/And Duration");
        }
        if (startTimeMs + durationMs > mediaItem.getDuration()) {
            throw new IllegalArgumentException("Invalid start time and duration");
        }

        mMediaItem = mediaItem;
        mUniqueId = effectId;
        mStartTimeMs = startTimeMs;
        mDurationMs = durationMs;
    }

    /**
     * Get the id of the effect.
     *
     * @return The id of the effect
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * Set the duration of the effect. If a preview or export is in progress,
     * then this change is effective for next preview or export session.
     *
     * @param durationMs of the effect in milliseconds
     */
    public void setDuration(long durationMs) {
        if (durationMs <0) {
            throw new IllegalArgumentException("Invalid duration");
        }

        if (mStartTimeMs + durationMs > mMediaItem.getDuration()) {
            throw new IllegalArgumentException("Duration is too large");
        }

        getMediaItem().getNativeContext().setGeneratePreview(true);

        final long oldDurationMs = mDurationMs;
        mDurationMs = durationMs;

        mMediaItem.invalidateTransitions(mStartTimeMs, oldDurationMs, mStartTimeMs, mDurationMs);
    }

    /**
     * Get the duration of the effect
     *
     * @return The duration of the effect in milliseconds
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * Set start time of the effect. If a preview or export is in progress, then
     * this change is effective for next preview or export session.
     *
     * @param startTimeMs The start time of the effect relative to the beginning
     *            of the media item in milliseconds
     */
    public void setStartTime(long startTimeMs) {
        if (startTimeMs + mDurationMs > mMediaItem.getDuration()) {
            throw new IllegalArgumentException("Start time is too large");
        }

        getMediaItem().getNativeContext().setGeneratePreview(true);
        final long oldStartTimeMs = mStartTimeMs;
        mStartTimeMs = startTimeMs;

        mMediaItem.invalidateTransitions(oldStartTimeMs, mDurationMs, mStartTimeMs, mDurationMs);
    }

    /**
     * Get the start time of the effect
     *
     * @return The start time in milliseconds
     */
    public long getStartTime() {
        return mStartTimeMs;
    }

    /**
     * Set the start time and duration
     *
     * @param startTimeMs start time in milliseconds
     * @param durationMs The duration in milliseconds
     */
    public void setStartTimeAndDuration(long startTimeMs, long durationMs) {
        if (startTimeMs + durationMs > mMediaItem.getDuration()) {
            throw new IllegalArgumentException("Invalid start time or duration");
        }

        getMediaItem().getNativeContext().setGeneratePreview(true);
        final long oldStartTimeMs = mStartTimeMs;
        final long oldDurationMs = mDurationMs;

        mStartTimeMs = startTimeMs;
        mDurationMs = durationMs;

        mMediaItem.invalidateTransitions(oldStartTimeMs, oldDurationMs, mStartTimeMs, mDurationMs);
    }

    /**
     * Get the media item owner.
     *
     * @return The media item owner
     */
    public MediaItem getMediaItem() {
        return mMediaItem;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Effect)) {
            return false;
        }
        return mUniqueId.equals(((Effect)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }
}
