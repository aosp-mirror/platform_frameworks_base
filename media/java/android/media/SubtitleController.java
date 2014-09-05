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

import java.util.Locale;
import java.util.Vector;

import android.content.Context;
import android.media.SubtitleTrack.RenderingWidget;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.accessibility.CaptioningManager;

/**
 * The subtitle controller provides the architecture to display subtitles for a
 * media source.  It allows specifying which tracks to display, on which anchor
 * to display them, and also allows adding external, out-of-band subtitle tracks.
 *
 * @hide
 */
public class SubtitleController {
    private MediaTimeProvider mTimeProvider;
    private Vector<Renderer> mRenderers;
    private Vector<SubtitleTrack> mTracks;
    private SubtitleTrack mSelectedTrack;
    private boolean mShowing;
    private CaptioningManager mCaptioningManager;
    private Handler mHandler;

    private static final int WHAT_SHOW = 1;
    private static final int WHAT_HIDE = 2;
    private static final int WHAT_SELECT_TRACK = 3;
    private static final int WHAT_SELECT_DEFAULT_TRACK = 4;

    private final Handler.Callback mCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
            case WHAT_SHOW:
                doShow();
                return true;
            case WHAT_HIDE:
                doHide();
                return true;
            case WHAT_SELECT_TRACK:
                doSelectTrack((SubtitleTrack)msg.obj);
                return true;
            case WHAT_SELECT_DEFAULT_TRACK:
                doSelectDefaultTrack();
                return true;
            default:
                return false;
            }
        }
    };

    private CaptioningManager.CaptioningChangeListener mCaptioningChangeListener =
        new CaptioningManager.CaptioningChangeListener() {
            /** @hide */
            @Override
            public void onEnabledChanged(boolean enabled) {
                selectDefaultTrack();
            }

            /** @hide */
            @Override
            public void onLocaleChanged(Locale locale) {
                selectDefaultTrack();
            }
        };

    /**
     * Creates a subtitle controller for a media playback object that implements
     * the MediaTimeProvider interface.
     *
     * @param timeProvider
     */
    public SubtitleController(
            Context context,
            MediaTimeProvider timeProvider,
            Listener listener) {
        mTimeProvider = timeProvider;
        mListener = listener;

        mRenderers = new Vector<Renderer>();
        mShowing = false;
        mTracks = new Vector<SubtitleTrack>();
        mCaptioningManager =
            (CaptioningManager)context.getSystemService(Context.CAPTIONING_SERVICE);
    }

    @Override
    protected void finalize() throws Throwable {
        mCaptioningManager.removeCaptioningChangeListener(
                mCaptioningChangeListener);
        super.finalize();
    }

    /**
     * @return the available subtitle tracks for this media. These include
     * the tracks found by {@link MediaPlayer} as well as any tracks added
     * manually via {@link #addTrack}.
     */
    public SubtitleTrack[] getTracks() {
        synchronized(mTracks) {
            SubtitleTrack[] tracks = new SubtitleTrack[mTracks.size()];
            mTracks.toArray(tracks);
            return tracks;
        }
    }

    /**
     * @return the currently selected subtitle track
     */
    public SubtitleTrack getSelectedTrack() {
        return mSelectedTrack;
    }

    private RenderingWidget getRenderingWidget() {
        if (mSelectedTrack == null) {
            return null;
        }
        return mSelectedTrack.getRenderingWidget();
    }

    /**
     * Selects a subtitle track.  As a result, this track will receive
     * in-band data from the {@link MediaPlayer}.  However, this does
     * not change the subtitle visibility.
     *
     * Should be called from the anchor's (UI) thread. {@see #Anchor.getSubtitleLooper}
     *
     * @param track The subtitle track to select.  This must be one of the
     *              tracks in {@link #getTracks}.
     * @return true if the track was successfully selected.
     */
    public boolean selectTrack(SubtitleTrack track) {
        if (track != null && !mTracks.contains(track)) {
            return false;
        }

        processOnAnchor(mHandler.obtainMessage(WHAT_SELECT_TRACK, track));
        return true;
    }

    private void doSelectTrack(SubtitleTrack track) {
        mTrackIsExplicit = true;
        if (mSelectedTrack == track) {
            return;
        }

        if (mSelectedTrack != null) {
            mSelectedTrack.hide();
            mSelectedTrack.setTimeProvider(null);
        }

        mSelectedTrack = track;
        if (mAnchor != null) {
            mAnchor.setSubtitleWidget(getRenderingWidget());
        }

        if (mSelectedTrack != null) {
            mSelectedTrack.setTimeProvider(mTimeProvider);
            mSelectedTrack.show();
        }

        if (mListener != null) {
            mListener.onSubtitleTrackSelected(track);
        }
    }

    /**
     * @return the default subtitle track based on system preferences, or null,
     * if no such track exists in this manager.
     *
     * Supports HLS-flags: AUTOSELECT, FORCED & DEFAULT.
     *
     * 1. If captioning is disabled, only consider FORCED tracks. Otherwise,
     * consider all tracks, but prefer non-FORCED ones.
     * 2. If user selected "Default" caption language:
     *   a. If there is a considered track with DEFAULT=yes, returns that track
     *      (favor the first one in the current language if there are more than
     *      one default tracks, or the first in general if none of them are in
     *      the current language).
     *   b. Otherwise, if there is a track with AUTOSELECT=yes in the current
     *      language, return that one.
     *   c. If there are no default tracks, and no autoselectable tracks in the
     *      current language, return null.
     * 3. If there is a track with the caption language, select that one.  Prefer
     * the one with AUTOSELECT=no.
     *
     * The default values for these flags are DEFAULT=no, AUTOSELECT=yes
     * and FORCED=no.
     */
    public SubtitleTrack getDefaultTrack() {
        SubtitleTrack bestTrack = null;
        int bestScore = -1;

        Locale selectedLocale = mCaptioningManager.getLocale();
        Locale locale = selectedLocale;
        if (locale == null) {
            locale = Locale.getDefault();
        }
        boolean selectForced = !mCaptioningManager.isEnabled();

        synchronized(mTracks) {
            for (SubtitleTrack track: mTracks) {
                MediaFormat format = track.getFormat();
                String language = format.getString(MediaFormat.KEY_LANGUAGE);
                boolean forced =
                    format.getInteger(MediaFormat.KEY_IS_FORCED_SUBTITLE, 0) != 0;
                boolean autoselect =
                    format.getInteger(MediaFormat.KEY_IS_AUTOSELECT, 1) != 0;
                boolean is_default =
                    format.getInteger(MediaFormat.KEY_IS_DEFAULT, 0) != 0;

                boolean languageMatches =
                    (locale == null ||
                    locale.getLanguage().equals("") ||
                    locale.getISO3Language().equals(language) ||
                    locale.getLanguage().equals(language));
                // is_default is meaningless unless caption language is 'default'
                int score = (forced ? 0 : 8) +
                    (((selectedLocale == null) && is_default) ? 4 : 0) +
                    (autoselect ? 0 : 2) + (languageMatches ? 1 : 0);

                if (selectForced && !forced) {
                    continue;
                }

                // we treat null locale/language as matching any language
                if ((selectedLocale == null && is_default) ||
                    (languageMatches &&
                     (autoselect || forced || selectedLocale != null))) {
                    if (score > bestScore) {
                        bestScore = score;
                        bestTrack = track;
                    }
                }
            }
        }
        return bestTrack;
    }

    private boolean mTrackIsExplicit = false;
    private boolean mVisibilityIsExplicit = false;

    /** @hide - should be called from anchor thread */
    public void selectDefaultTrack() {
        processOnAnchor(mHandler.obtainMessage(WHAT_SELECT_DEFAULT_TRACK));
    }

    private void doSelectDefaultTrack() {
        if (mTrackIsExplicit) {
            // If track selection is explicit, but visibility
            // is not, it falls back to the captioning setting
            if (!mVisibilityIsExplicit) {
                if (mCaptioningManager.isEnabled() ||
                    (mSelectedTrack != null &&
                     mSelectedTrack.getFormat().getInteger(
                            MediaFormat.KEY_IS_FORCED_SUBTITLE, 0) != 0)) {
                    show();
                } else if (mSelectedTrack != null && !mSelectedTrack.isTimedText()) {
                    hide();
                }
                mVisibilityIsExplicit = false;
            }
            return;
        }

        // We can have a default (forced) track even if captioning
        // is not enabled.  This is handled by getDefaultTrack().
        // Show this track unless subtitles were explicitly hidden.
        SubtitleTrack track = getDefaultTrack();
        if (track != null) {
            selectTrack(track);
            mTrackIsExplicit = false;
            if (!mVisibilityIsExplicit) {
                show();
                mVisibilityIsExplicit = false;
            }
        }
    }

    /** @hide - must be called from anchor thread */
    public void reset() {
        checkAnchorLooper();
        hide();
        selectTrack(null);
        mTracks.clear();
        mTrackIsExplicit = false;
        mVisibilityIsExplicit = false;
        mCaptioningManager.removeCaptioningChangeListener(
                mCaptioningChangeListener);
    }

    /**
     * Adds a new, external subtitle track to the manager.
     *
     * @param format the format of the track that will include at least
     *               the MIME type {@link MediaFormat@KEY_MIME}.
     * @return the created {@link SubtitleTrack} object
     */
    public SubtitleTrack addTrack(MediaFormat format) {
        synchronized(mRenderers) {
            for (Renderer renderer: mRenderers) {
                if (renderer.supports(format)) {
                    SubtitleTrack track = renderer.createTrack(format);
                    if (track != null) {
                        synchronized(mTracks) {
                            if (mTracks.size() == 0) {
                                mCaptioningManager.addCaptioningChangeListener(
                                        mCaptioningChangeListener);
                            }
                            mTracks.add(track);
                        }
                        return track;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Show the selected (or default) subtitle track.
     *
     * Should be called from the anchor's (UI) thread. {@see #Anchor.getSubtitleLooper}
     */
    public void show() {
        processOnAnchor(mHandler.obtainMessage(WHAT_SHOW));
    }

    private void doShow() {
        mShowing = true;
        mVisibilityIsExplicit = true;
        if (mSelectedTrack != null) {
            mSelectedTrack.show();
        }
    }

    /**
     * Hide the selected (or default) subtitle track.
     *
     * Should be called from the anchor's (UI) thread. {@see #Anchor.getSubtitleLooper}
     */
    public void hide() {
        processOnAnchor(mHandler.obtainMessage(WHAT_HIDE));
    }

    private void doHide() {
        mVisibilityIsExplicit = true;
        if (mSelectedTrack != null) {
            mSelectedTrack.hide();
        }
        mShowing = false;
    }

    /**
     * Interface for supporting a single or multiple subtitle types in {@link
     * MediaPlayer}.
     */
    public abstract static class Renderer {
        /**
         * Called by {@link MediaPlayer}'s {@link SubtitleController} when a new
         * subtitle track is detected, to see if it should use this object to
         * parse and display this subtitle track.
         *
         * @param format the format of the track that will include at least
         *               the MIME type {@link MediaFormat@KEY_MIME}.
         *
         * @return true if and only if the track format is supported by this
         * renderer
         */
        public abstract boolean supports(MediaFormat format);

        /**
         * Called by {@link MediaPlayer}'s {@link SubtitleController} for each
         * subtitle track that was detected and is supported by this object to
         * create a {@link SubtitleTrack} object.  This object will be created
         * for each track that was found.  If the track is selected for display,
         * this object will be used to parse and display the track data.
         *
         * @param format the format of the track that will include at least
         *               the MIME type {@link MediaFormat@KEY_MIME}.
         * @return a {@link SubtitleTrack} object that will be used to parse
         * and render the subtitle track.
         */
        public abstract SubtitleTrack createTrack(MediaFormat format);
    }

    /**
     * Add support for a subtitle format in {@link MediaPlayer}.
     *
     * @param renderer a {@link SubtitleController.Renderer} object that adds
     *                 support for a subtitle format.
     */
    public void registerRenderer(Renderer renderer) {
        synchronized(mRenderers) {
            // TODO how to get available renderers in the system
            if (!mRenderers.contains(renderer)) {
                // TODO should added renderers override existing ones (to allow replacing?)
                mRenderers.add(renderer);
            }
        }
    }

    /** @hide */
    public boolean hasRendererFor(MediaFormat format) {
        synchronized(mRenderers) {
            // TODO how to get available renderers in the system
            for (Renderer renderer: mRenderers) {
                if (renderer.supports(format)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Subtitle anchor, an object that is able to display a subtitle renderer,
     * e.g. a VideoView.
     */
    public interface Anchor {
        /**
         * Anchor should use the supplied subtitle rendering widget, or
         * none if it is null.
         * @hide
         */
        public void setSubtitleWidget(RenderingWidget subtitleWidget);

        /**
         * Anchors provide the looper on which all track visibility changes
         * (track.show/hide, setSubtitleWidget) will take place.
         * @hide
         */
        public Looper getSubtitleLooper();
    }

    private Anchor mAnchor;

    /**
     *  @hide - called from anchor's looper (if any, both when unsetting and
     *  setting)
     */
    public void setAnchor(Anchor anchor) {
        if (mAnchor == anchor) {
            return;
        }

        if (mAnchor != null) {
            checkAnchorLooper();
            mAnchor.setSubtitleWidget(null);
        }
        mAnchor = anchor;
        mHandler = null;
        if (mAnchor != null) {
            mHandler = new Handler(mAnchor.getSubtitleLooper(), mCallback);
            checkAnchorLooper();
            mAnchor.setSubtitleWidget(getRenderingWidget());
        }
    }

    private void checkAnchorLooper() {
        assert mHandler != null : "Should have a looper already";
        assert Looper.myLooper() == mHandler.getLooper() : "Must be called from the anchor's looper";
    }

    private void processOnAnchor(Message m) {
        assert mHandler != null : "Should have a looper already";
        if (Looper.myLooper() == mHandler.getLooper()) {
            mHandler.dispatchMessage(m);
        } else {
            mHandler.sendMessage(m);
        }
    }

    public interface Listener {
        /**
         * Called when a subtitle track has been selected.
         *
         * @param track selected subtitle track or null
         * @hide
         */
        public void onSubtitleTrackSelected(SubtitleTrack track);
    }

    private Listener mListener;
}
