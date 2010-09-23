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
 * single media item. If one wants to apply the same effect to multiple media
 * items, multiple @{MediaItem.addEffect(Effect)} call must be invoked on each
 * of the MediaItem objects.
 * {@hide}
 */
public abstract class Effect {
    // Instance variables
    private final String mUniqueId;
    protected long mDurationMs;
    // The start time of the effect relative to the media item timeline
    protected long mStartTimeMs;

    /**
     * Default constructor
     */
    @SuppressWarnings("unused")
    private Effect() {
        mUniqueId = null;
        mStartTimeMs = 0;
        mDurationMs = 0;
    }

    /**
     * Constructor
     *
     * @param effectId The effect id
     * @param startTimeMs The start time relative to the media item to which it
     *            is applied
     * @param durationMs The effect duration in milliseconds
     */
    public Effect(String effectId, long startTimeMs, long durationMs) {
        mUniqueId = effectId;
        mStartTimeMs = startTimeMs;
        mDurationMs = durationMs;
    }

    /**
     * @return The id of the effect
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * Set the duration of the effect. If a preview or export is in progress,
     * then this change is effective for next preview or export session. s
     *
     * @param durationMs of the effect in milliseconds
     */
    public void setDuration(long durationMs) {
        mDurationMs = durationMs;
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
        mStartTimeMs = startTimeMs;
    }

    /**
     * @return The start time in milliseconds
     */
    public long getStartTime() {
        return mStartTimeMs;
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
