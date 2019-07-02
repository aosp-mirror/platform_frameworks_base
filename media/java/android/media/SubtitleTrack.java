/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media;

import android.annotation.UnsupportedAppUsage;
import android.graphics.Canvas;
import android.media.MediaPlayer.TrackInfo;
import android.os.Handler;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;

/**
 * A subtitle track abstract base class that is responsible for parsing and displaying
 * an instance of a particular type of subtitle.
 *
 * @hide
 */
public abstract class SubtitleTrack implements MediaTimeProvider.OnMediaTimeListener {
    private static final String TAG = "SubtitleTrack";
    private long mLastUpdateTimeMs;
    private long mLastTimeMs;

    private Runnable mRunnable;

    /** @hide TODO private */
    final protected LongSparseArray<Run> mRunsByEndTime = new LongSparseArray<Run>();
    /** @hide TODO private */
    final protected LongSparseArray<Run> mRunsByID = new LongSparseArray<Run>();

    /** @hide TODO private */
    protected CueList mCues;
    /** @hide TODO private */
    final protected Vector<Cue> mActiveCues = new Vector<Cue>();
    /** @hide */
    protected boolean mVisible;

    /** @hide */
    public boolean DEBUG = false;

    /** @hide */
    protected Handler mHandler = new Handler();

    private MediaFormat mFormat;

    public SubtitleTrack(MediaFormat format) {
        mFormat = format;
        mCues = new CueList();
        clearActiveCues();
        mLastTimeMs = -1;
    }

    /** @hide */
    public final MediaFormat getFormat() {
        return mFormat;
    }

    private long mNextScheduledTimeMs = -1;

    protected void onData(SubtitleData data) {
        long runID = data.getStartTimeUs() + 1;
        onData(data.getData(), true /* eos */, runID);
        setRunDiscardTimeMs(
                runID,
                (data.getStartTimeUs() + data.getDurationUs()) / 1000);
    }

    /**
     * Called when there is input data for the subtitle track.  The
     * complete subtitle for a track can include multiple whole units
     * (runs).  Each of these units can have multiple sections.  The
     * contents of a run are submitted in sequential order, with eos
     * indicating the last section of the run.  Calls from different
     * runs must not be intermixed.
     *
     * @param data subtitle data byte buffer
     * @param eos true if this is the last section of the run.
     * @param runID mostly-unique ID for this run of data.  Subtitle cues
     *              with runID of 0 are discarded immediately after
     *              display.  Cues with runID of ~0 are discarded
     *              only at the deletion of the track object.  Cues
     *              with other runID-s are discarded at the end of the
     *              run, which defaults to the latest timestamp of
     *              any of its cues (with this runID).
     */
    public abstract void onData(byte[] data, boolean eos, long runID);

    /**
     * Called when adding the subtitle rendering widget to the view hierarchy,
     * as well as when showing or hiding the subtitle track, or when the video
     * surface position has changed.
     *
     * @return the widget that renders this subtitle track. For most renderers
     *         there should be a single shared instance that is used for all
     *         tracks supported by that renderer, as at most one subtitle track
     *         is visible at one time.
     */
    public abstract RenderingWidget getRenderingWidget();

    /**
     * Called when the active cues have changed, and the contents of the subtitle
     * view should be updated.
     *
     * @hide
     */
    public abstract void updateView(Vector<Cue> activeCues);

    /** @hide */
    protected synchronized void updateActiveCues(boolean rebuild, long timeMs) {
        // out-of-order times mean seeking or new active cues being added
        // (during their own timespan)
        if (rebuild || mLastUpdateTimeMs > timeMs) {
            clearActiveCues();
        }

        for(Iterator<Pair<Long, Cue> > it =
                mCues.entriesBetween(mLastUpdateTimeMs, timeMs).iterator(); it.hasNext(); ) {
            Pair<Long, Cue> event = it.next();
            Cue cue = event.second;

            if (cue.mEndTimeMs == event.first) {
                // remove past cues
                if (DEBUG) Log.v(TAG, "Removing " + cue);
                mActiveCues.remove(cue);
                if (cue.mRunID == 0) {
                    it.remove();
                }
            } else if (cue.mStartTimeMs == event.first) {
                // add new cues
                // TRICKY: this will happen in start order
                if (DEBUG) Log.v(TAG, "Adding " + cue);
                if (cue.mInnerTimesMs != null) {
                    cue.onTime(timeMs);
                }
                mActiveCues.add(cue);
            } else if (cue.mInnerTimesMs != null) {
                // cue is modified
                cue.onTime(timeMs);
            }
        }

        /* complete any runs */
        while (mRunsByEndTime.size() > 0 &&
               mRunsByEndTime.keyAt(0) <= timeMs) {
            removeRunsByEndTimeIndex(0); // removes element
        }
        mLastUpdateTimeMs = timeMs;
    }

    private void removeRunsByEndTimeIndex(int ix) {
        Run run = mRunsByEndTime.valueAt(ix);
        while (run != null) {
            Cue cue = run.mFirstCue;
            while (cue != null) {
                mCues.remove(cue);
                Cue nextCue = cue.mNextInRun;
                cue.mNextInRun = null;
                cue = nextCue;
            }
            mRunsByID.remove(run.mRunID);
            Run nextRun = run.mNextRunAtEndTimeMs;
            run.mPrevRunAtEndTimeMs = null;
            run.mNextRunAtEndTimeMs = null;
            run = nextRun;
        }
        mRunsByEndTime.removeAt(ix);
    }

    @Override
    protected void finalize() throws Throwable {
        /* remove all cues (untangle all cross-links) */
        int size = mRunsByEndTime.size();
        for(int ix = size - 1; ix >= 0; ix--) {
            removeRunsByEndTimeIndex(ix);
        }

        super.finalize();
    }

    private synchronized void takeTime(long timeMs) {
        mLastTimeMs = timeMs;
    }

    /** @hide */
    protected synchronized void clearActiveCues() {
        if (DEBUG) Log.v(TAG, "Clearing " + mActiveCues.size() + " active cues");
        mActiveCues.clear();
        mLastUpdateTimeMs = -1;
    }

    /** @hide */
    protected void scheduleTimedEvents() {
        /* get times for the next event */
        if (mTimeProvider != null) {
            mNextScheduledTimeMs = mCues.nextTimeAfter(mLastTimeMs);
            if (DEBUG) Log.d(TAG, "sched @" + mNextScheduledTimeMs + " after " + mLastTimeMs);
            mTimeProvider.notifyAt(
                    mNextScheduledTimeMs >= 0 ?
                        (mNextScheduledTimeMs * 1000) : MediaTimeProvider.NO_TIME,
                    this);
        }
    }

    /**
     * @hide
     */
    @Override
    public void onTimedEvent(long timeUs) {
        if (DEBUG) Log.d(TAG, "onTimedEvent " + timeUs);
        synchronized (this) {
            long timeMs = timeUs / 1000;
            updateActiveCues(false, timeMs);
            takeTime(timeMs);
        }
        updateView(mActiveCues);
        scheduleTimedEvents();
    }

    /**
     * @hide
     */
    @Override
    public void onSeek(long timeUs) {
        if (DEBUG) Log.d(TAG, "onSeek " + timeUs);
        synchronized (this) {
            long timeMs = timeUs / 1000;
            updateActiveCues(true, timeMs);
            takeTime(timeMs);
        }
        updateView(mActiveCues);
        scheduleTimedEvents();
    }

    /**
     * @hide
     */
    @Override
    public void onStop() {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "onStop");
            clearActiveCues();
            mLastTimeMs = -1;
        }
        updateView(mActiveCues);
        mNextScheduledTimeMs = -1;
        if (mTimeProvider != null) {
            mTimeProvider.notifyAt(MediaTimeProvider.NO_TIME, this);
        }
    }

    /** @hide */
    protected MediaTimeProvider mTimeProvider;

    /** @hide */
    public void show() {
        if (mVisible) {
            return;
        }

        mVisible = true;
        RenderingWidget renderingWidget = getRenderingWidget();
        if (renderingWidget != null) {
            renderingWidget.setVisible(true);
        }
        if (mTimeProvider != null) {
            mTimeProvider.scheduleUpdate(this);
        }
    }

    /** @hide */
    public void hide() {
        if (!mVisible) {
            return;
        }

        if (mTimeProvider != null) {
            mTimeProvider.cancelNotifications(this);
        }
        RenderingWidget renderingWidget = getRenderingWidget();
        if (renderingWidget != null) {
            renderingWidget.setVisible(false);
        }
        mVisible = false;
    }

    /** @hide */
    protected synchronized boolean addCue(Cue cue) {
        mCues.add(cue);

        if (cue.mRunID != 0) {
            Run run = mRunsByID.get(cue.mRunID);
            if (run == null) {
                run = new Run();
                mRunsByID.put(cue.mRunID, run);
                run.mEndTimeMs = cue.mEndTimeMs;
            } else if (run.mEndTimeMs < cue.mEndTimeMs) {
                run.mEndTimeMs = cue.mEndTimeMs;
            }

            // link-up cues in the same run
            cue.mNextInRun = run.mFirstCue;
            run.mFirstCue = cue;
        }

        // if a cue is added that should be visible, need to refresh view
        long nowMs = -1;
        if (mTimeProvider != null) {
            try {
                nowMs = mTimeProvider.getCurrentTimeUs(
                        false /* precise */, true /* monotonic */) / 1000;
            } catch (IllegalStateException e) {
                // handle as it we are not playing
            }
        }

        if (DEBUG) Log.v(TAG, "mVisible=" + mVisible + ", " +
                cue.mStartTimeMs + " <= " + nowMs + ", " +
                cue.mEndTimeMs + " >= " + mLastTimeMs);

        if (mVisible &&
                cue.mStartTimeMs <= nowMs &&
                // we don't trust nowMs, so check any cue since last callback
                cue.mEndTimeMs >= mLastTimeMs) {
            if (mRunnable != null) {
                mHandler.removeCallbacks(mRunnable);
            }
            final SubtitleTrack track = this;
            final long thenMs = nowMs;
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    // even with synchronized, it is possible that we are going
                    // to do multiple updates as the runnable could be already
                    // running.
                    synchronized (track) {
                        mRunnable = null;
                        updateActiveCues(true, thenMs);
                        updateView(mActiveCues);
                    }
                }
            };
            // delay update so we don't update view on every cue.  TODO why 10?
            if (mHandler.postDelayed(mRunnable, 10 /* delay */)) {
                if (DEBUG) Log.v(TAG, "scheduling update");
            } else {
                if (DEBUG) Log.w(TAG, "failed to schedule subtitle view update");
            }
            return true;
        }

        if (mVisible &&
                cue.mEndTimeMs >= mLastTimeMs &&
                (cue.mStartTimeMs < mNextScheduledTimeMs ||
                 mNextScheduledTimeMs < 0)) {
            scheduleTimedEvents();
        }

        return false;
    }

    /** @hide */
    public synchronized void setTimeProvider(MediaTimeProvider timeProvider) {
        if (mTimeProvider == timeProvider) {
            return;
        }
        if (mTimeProvider != null) {
            mTimeProvider.cancelNotifications(this);
        }
        mTimeProvider = timeProvider;
        if (mTimeProvider != null) {
            mTimeProvider.scheduleUpdate(this);
        }
    }


    /** @hide */
    static class CueList {
        private static final String TAG = "CueList";
        // simplistic, inefficient implementation
        private SortedMap<Long, Vector<Cue> > mCues;
        public boolean DEBUG = false;

        private boolean addEvent(Cue cue, long timeMs) {
            Vector<Cue> cues = mCues.get(timeMs);
            if (cues == null) {
                cues = new Vector<Cue>(2);
                mCues.put(timeMs, cues);
            } else if (cues.contains(cue)) {
                // do not duplicate cues
                return false;
            }

            cues.add(cue);
            return true;
        }

        private void removeEvent(Cue cue, long timeMs) {
            Vector<Cue> cues = mCues.get(timeMs);
            if (cues != null) {
                cues.remove(cue);
                if (cues.size() == 0) {
                    mCues.remove(timeMs);
                }
            }
        }

        public void add(Cue cue) {
            // ignore non-positive-duration cues
            if (cue.mStartTimeMs >= cue.mEndTimeMs)
                return;

            if (!addEvent(cue, cue.mStartTimeMs)) {
                return;
            }

            long lastTimeMs = cue.mStartTimeMs;
            if (cue.mInnerTimesMs != null) {
                for (long timeMs: cue.mInnerTimesMs) {
                    if (timeMs > lastTimeMs && timeMs < cue.mEndTimeMs) {
                        addEvent(cue, timeMs);
                        lastTimeMs = timeMs;
                    }
                }
            }

            addEvent(cue, cue.mEndTimeMs);
        }

        public void remove(Cue cue) {
            removeEvent(cue, cue.mStartTimeMs);
            if (cue.mInnerTimesMs != null) {
                for (long timeMs: cue.mInnerTimesMs) {
                    removeEvent(cue, timeMs);
                }
            }
            removeEvent(cue, cue.mEndTimeMs);
        }

        public Iterable<Pair<Long, Cue>> entriesBetween(
                final long lastTimeMs, final long timeMs) {
            return new Iterable<Pair<Long, Cue> >() {
                @Override
                public Iterator<Pair<Long, Cue> > iterator() {
                    if (DEBUG) Log.d(TAG, "slice (" + lastTimeMs + ", " + timeMs + "]=");
                    try {
                        return new EntryIterator(
                                mCues.subMap(lastTimeMs + 1, timeMs + 1));
                    } catch(IllegalArgumentException e) {
                        return new EntryIterator(null);
                    }
                }
            };
        }

        public long nextTimeAfter(long timeMs) {
            SortedMap<Long, Vector<Cue>> tail = null;
            try {
                tail = mCues.tailMap(timeMs + 1);
                if (tail != null) {
                    return tail.firstKey();
                } else {
                    return -1;
                }
            } catch(IllegalArgumentException e) {
                return -1;
            } catch(NoSuchElementException e) {
                return -1;
            }
        }

        class EntryIterator implements Iterator<Pair<Long, Cue> > {
            @Override
            public boolean hasNext() {
                return !mDone;
            }

            @Override
            public Pair<Long, Cue> next() {
                if (mDone) {
                    throw new NoSuchElementException("");
                }
                mLastEntry = new Pair<Long, Cue>(
                        mCurrentTimeMs, mListIterator.next());
                mLastListIterator = mListIterator;
                if (!mListIterator.hasNext()) {
                    nextKey();
                }
                return mLastEntry;
            }

            @Override
            public void remove() {
                // only allow removing end tags
                if (mLastListIterator == null ||
                        mLastEntry.second.mEndTimeMs != mLastEntry.first) {
                    throw new IllegalStateException("");
                }

                // remove end-cue
                mLastListIterator.remove();
                mLastListIterator = null;
                if (mCues.get(mLastEntry.first).size() == 0) {
                    mCues.remove(mLastEntry.first);
                }

                // remove rest of the cues
                Cue cue = mLastEntry.second;
                removeEvent(cue, cue.mStartTimeMs);
                if (cue.mInnerTimesMs != null) {
                    for (long timeMs: cue.mInnerTimesMs) {
                        removeEvent(cue, timeMs);
                    }
                }
            }

            public EntryIterator(SortedMap<Long, Vector<Cue> > cues) {
                if (DEBUG) Log.v(TAG, cues + "");
                mRemainingCues = cues;
                mLastListIterator = null;
                nextKey();
            }

            private void nextKey() {
                do {
                    try {
                        if (mRemainingCues == null) {
                            throw new NoSuchElementException("");
                        }
                        mCurrentTimeMs = mRemainingCues.firstKey();
                        mListIterator =
                            mRemainingCues.get(mCurrentTimeMs).iterator();
                        try {
                            mRemainingCues =
                                mRemainingCues.tailMap(mCurrentTimeMs + 1);
                        } catch (IllegalArgumentException e) {
                            mRemainingCues = null;
                        }
                        mDone = false;
                    } catch (NoSuchElementException e) {
                        mDone = true;
                        mRemainingCues = null;
                        mListIterator = null;
                        return;
                    }
                } while (!mListIterator.hasNext());
            }

            private long mCurrentTimeMs;
            private Iterator<Cue> mListIterator;
            private boolean mDone;
            private SortedMap<Long, Vector<Cue> > mRemainingCues;
            private Iterator<Cue> mLastListIterator;
            private Pair<Long,Cue> mLastEntry;
        }

        CueList() {
            mCues = new TreeMap<Long, Vector<Cue>>();
        }
    }

    /** @hide */
    public static class Cue {
        public long mStartTimeMs;
        public long mEndTimeMs;
        public long[] mInnerTimesMs;
        public long mRunID;

        /** @hide */
        public Cue mNextInRun;

        public void onTime(long timeMs) { }
    }

    /** @hide update mRunsByEndTime (with default end time) */
    protected void finishedRun(long runID) {
        if (runID != 0 && runID != ~0) {
            Run run = mRunsByID.get(runID);
            if (run != null) {
                run.storeByEndTimeMs(mRunsByEndTime);
            }
        }
    }

    /** @hide update mRunsByEndTime with given end time */
    public void setRunDiscardTimeMs(long runID, long timeMs) {
        if (runID != 0 && runID != ~0) {
            Run run = mRunsByID.get(runID);
            if (run != null) {
                run.mEndTimeMs = timeMs;
                run.storeByEndTimeMs(mRunsByEndTime);
            }
        }
    }

    /** @hide whether this is a text track who fires events instead getting rendered */
    public int getTrackType() {
        return getRenderingWidget() == null
                ? TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                : TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE;
    }


    /** @hide */
    private static class Run {
        public Cue mFirstCue;
        public Run mNextRunAtEndTimeMs;
        public Run mPrevRunAtEndTimeMs;
        public long mEndTimeMs = -1;
        public long mRunID = 0;
        private long mStoredEndTimeMs = -1;

        public void storeByEndTimeMs(LongSparseArray<Run> runsByEndTime) {
            // remove old value if any
            int ix = runsByEndTime.indexOfKey(mStoredEndTimeMs);
            if (ix >= 0) {
                if (mPrevRunAtEndTimeMs == null) {
                    assert(this == runsByEndTime.valueAt(ix));
                    if (mNextRunAtEndTimeMs == null) {
                        runsByEndTime.removeAt(ix);
                    } else {
                        runsByEndTime.setValueAt(ix, mNextRunAtEndTimeMs);
                    }
                }
                removeAtEndTimeMs();
            }

            // add new value
            if (mEndTimeMs >= 0) {
                mPrevRunAtEndTimeMs = null;
                mNextRunAtEndTimeMs = runsByEndTime.get(mEndTimeMs);
                if (mNextRunAtEndTimeMs != null) {
                    mNextRunAtEndTimeMs.mPrevRunAtEndTimeMs = this;
                }
                runsByEndTime.put(mEndTimeMs, this);
                mStoredEndTimeMs = mEndTimeMs;
            }
        }

        public void removeAtEndTimeMs() {
            Run prev = mPrevRunAtEndTimeMs;

            if (mPrevRunAtEndTimeMs != null) {
                mPrevRunAtEndTimeMs.mNextRunAtEndTimeMs = mNextRunAtEndTimeMs;
                mPrevRunAtEndTimeMs = null;
            }
            if (mNextRunAtEndTimeMs != null) {
                mNextRunAtEndTimeMs.mPrevRunAtEndTimeMs = prev;
                mNextRunAtEndTimeMs = null;
            }
        }
    }

    /**
     * Interface for rendering subtitles onto a Canvas.
     */
    public interface RenderingWidget {
        /**
         * Sets the widget's callback, which is used to send updates when the
         * rendered data has changed.
         *
         * @param callback update callback
         */
        @UnsupportedAppUsage
        public void setOnChangedListener(OnChangedListener callback);

        /**
         * Sets the widget's size.
         *
         * @param width width in pixels
         * @param height height in pixels
         */
        @UnsupportedAppUsage
        public void setSize(int width, int height);

        /**
         * Sets whether the widget should draw subtitles.
         *
         * @param visible true if subtitles should be drawn, false otherwise
         */
        public void setVisible(boolean visible);

        /**
         * Renders subtitles onto a {@link Canvas}.
         *
         * @param c canvas on which to render subtitles
         */
        @UnsupportedAppUsage
        public void draw(Canvas c);

        /**
         * Called when the widget is attached to a window.
         */
        @UnsupportedAppUsage
        public void onAttachedToWindow();

        /**
         * Called when the widget is detached from a window.
         */
        @UnsupportedAppUsage
        public void onDetachedFromWindow();

        /**
         * Callback used to send updates about changes to rendering data.
         */
        public interface OnChangedListener {
            /**
             * Called when the rendering data has changed.
             *
             * @param renderingWidget the widget whose data has changed
             */
            public void onChanged(RenderingWidget renderingWidget);
        }
    }
}
