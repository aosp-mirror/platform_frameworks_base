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
 * This is the super class for all Overlay classes.
 * {@hide}
 */
public abstract class Overlay {
    // Instance variables
    private final String mUniqueId;

    protected long mStartTimeMs;
    protected long mDurationMs;

    /**
     * Default constructor
     */
    @SuppressWarnings("unused")
    private Overlay() {
        mUniqueId = null;
        mStartTimeMs = 0;
        mDurationMs = 0;
    }

    /**
     * Constructor
     *
     * @param overlayId The overlay id
     * @param startTimeMs The start time relative to the media item start time
     * @param durationMs The duration
     *
     * @throws IllegalArgumentException if the file type is not PNG or the
     *      startTimeMs and durationMs are incorrect.
     */
    public Overlay(String overlayId, long startTimeMs, long durationMs) {
        mUniqueId = overlayId;
        mStartTimeMs = startTimeMs;
        mDurationMs = durationMs;
    }

    /**
     * @return The of the overlay
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * @return The duration of the overlay effect
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * If a preview or export is in progress, then this change is effective for
     * next preview or export session.
     *
     * @param durationMs The duration in milliseconds
     */
    public void setDuration(long durationMs) {
        mDurationMs = durationMs;
    }

    /**
     * @return the start time of the overlay
     */
    public long getStartTime() {
        return mStartTimeMs;
    }

    /**
     * Set the start time for the overlay. If a preview or export is in
     * progress, then this change is effective for next preview or export
     * session.
     *
     * @param startTimeMs start time in milliseconds
     */
    public void setStartTime(long startTimeMs) {
        mStartTimeMs = startTimeMs;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Overlay)) {
            return false;
        }
        return mUniqueId.equals(((Overlay)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }
}
