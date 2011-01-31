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

import java.util.HashMap;
import java.util.Map;

/**
 * This is the super class for all Overlay classes.
 * {@hide}
 */
public abstract class Overlay {
    /**
     *  Instance variables
     */
    private final String mUniqueId;
    /**
     *  The overlay owner
     */
    private final MediaItem mMediaItem;
    /**
     *  user attributes
     */
    private final Map<String, String> mUserAttributes;

    protected long mStartTimeMs;
    protected long mDurationMs;

    /**
     * Default constructor
     */
    @SuppressWarnings("unused")
    private Overlay() {
        this(null, null, 0, 0);
    }

    /**
     * Constructor
     *
     * @param mediaItem The media item owner
     * @param overlayId The overlay id
     * @param startTimeMs The start time relative to the media item start time
     * @param durationMs The duration
     *
     * @throws IllegalArgumentException if the file type is not PNG or the
     *      startTimeMs and durationMs are incorrect.
     */
    public Overlay(MediaItem mediaItem, String overlayId, long startTimeMs,
           long durationMs) {
        if (mediaItem == null) {
            throw new IllegalArgumentException("Media item cannot be null");
        }

        if ((startTimeMs<0) || (durationMs<0) ) {
            throw new IllegalArgumentException("Invalid start time and/OR duration");
        }

        if (startTimeMs + durationMs > mediaItem.getDuration()) {
            throw new IllegalArgumentException("Invalid start time and duration");
        }

        mMediaItem = mediaItem;
        mUniqueId = overlayId;
        mStartTimeMs = startTimeMs;
        mDurationMs = durationMs;
        mUserAttributes = new HashMap<String, String>();
    }

    /**
     * Get the overlay ID.
     *
     * @return The of the overlay
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * Get the duration of overlay.
     *
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
        if (durationMs < 0) {
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
     * Get the start time of overlay.
     *
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
        if (startTimeMs + mDurationMs > mMediaItem.getDuration()) {
            throw new IllegalArgumentException("Start time is too large");
        }

        getMediaItem().getNativeContext().setGeneratePreview(true);

        final long oldStartTimeMs = mStartTimeMs;
        mStartTimeMs = startTimeMs;

        mMediaItem.invalidateTransitions(oldStartTimeMs, mDurationMs, mStartTimeMs, mDurationMs);
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
     * @return The media item owner.
     */
    public MediaItem getMediaItem() {
        return mMediaItem;
    }

    /**
     * Set a user attribute
     *
     * @param name The attribute name
     * @param value The attribute value
     */
    public void setUserAttribute(String name, String value) {
        mUserAttributes.put(name, value);
    }

    /**
     * Get the current user attributes set.
     *
     * @return The user attributes
     */
    public Map<String, String> getUserAttributes() {
        return mUserAttributes;
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
