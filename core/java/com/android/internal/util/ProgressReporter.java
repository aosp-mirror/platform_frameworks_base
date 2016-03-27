/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.Nullable;
import android.content.Intent;
import android.os.Bundle;
import android.os.IProgressListener;
import android.os.RemoteException;
import android.util.MathUtils;

/**
 * Tracks and reports progress of a single task to a {@link IProgressListener}.
 * The reported progress of a task ranges from 0-100, but the task can be
 * segmented into smaller pieces using {@link #startSegment(int)} and
 * {@link #endSegment(int[])}, and segments can be nested.
 * <p>
 * Here's an example in action; when finished the overall task progress will be
 * at 60.
 *
 * <pre>
 * prog.setProgress(20);
 * {
 *     final int restore = prog.startSegment(40);
 *     for (int i = 0; i < N; i++) {
 *         prog.setProgress(i, N);
 *         ...
 *     }
 *     prog.endSegment(restore);
 * }
 * </pre>
 *
 * This class is not thread safe.
 *
 * @hide
 */
public class ProgressReporter {
    public static final ProgressReporter NO_OP = new ProgressReporter(0, null);

    private final int mId;
    private final IProgressListener mListener;

    private Bundle mExtras = new Bundle();

    private int mProgress = 0;

    /**
     * Current segment range: first element is starting progress of this
     * segment, second element is length of segment.
     */
    private int[] mSegmentRange = new int[] { 0, 100 };

    /**
     * Create a new task with the given identifier whose progress will be
     * reported to the given listener.
     */
    public ProgressReporter(int id, @Nullable IProgressListener listener) {
        mId = id;
        mListener = listener;
    }

    /**
     * Set the progress of the currently active segment.
     *
     * @param progress Segment progress between 0-100.
     */
    public void setProgress(int progress) {
        setProgress(progress, 100, null);
    }

    /**
     * Set the progress of the currently active segment.
     *
     * @param progress Segment progress between 0-100.
     */
    public void setProgress(int progress, @Nullable CharSequence title) {
        setProgress(progress, 100, title);
    }

    /**
     * Set the fractional progress of the currently active segment.
     */
    public void setProgress(int n, int m) {
        setProgress(n, m, null);
    }

    /**
     * Set the fractional progress of the currently active segment.
     */
    public void setProgress(int n, int m, @Nullable CharSequence title) {
        mProgress = mSegmentRange[0]
                + MathUtils.constrain((n * mSegmentRange[1]) / m, 0, mSegmentRange[1]);
        if (title != null) {
            mExtras.putCharSequence(Intent.EXTRA_TITLE, title);
        }
        notifyProgress(mId, mProgress, mExtras);
    }

    /**
     * Start a new inner segment that will contribute the given range towards
     * the currently active segment. You must pass the returned value to
     * {@link #endSegment(int[])} when finished.
     */
    public int[] startSegment(int size) {
        final int[] lastRange = mSegmentRange;
        mSegmentRange = new int[] { mProgress, (size * mSegmentRange[1] / 100) };
        return lastRange;
    }

    /**
     * End the current segment.
     */
    public void endSegment(int[] lastRange) {
        mProgress = mSegmentRange[0] + mSegmentRange[1];
        mSegmentRange = lastRange;
    }

    int getProgress() {
        return mProgress;
    }

    int[] getSegmentRange() {
        return mSegmentRange;
    }

    /**
     * Report this entire task as being finished.
     */
    public void finish() {
        notifyFinished(mId, null);
    }

    private void notifyProgress(int id, int progress, Bundle extras) {
        if (mListener != null) {
            try {
                mListener.onProgress(id, progress, extras);
            } catch (RemoteException ignored) {
            }
        }
    }

    public void notifyFinished(int id, Bundle extras) {
        if (mListener != null) {
            try {
                mListener.onFinished(id, extras);
            } catch (RemoteException ignored) {
            }
        }
    }
}
