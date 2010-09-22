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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.util.Log;
import android.util.Xml;
import android.view.SurfaceHolder;

/**
 * The VideoEditor implementation {@hide}
 */
public class VideoEditorTestImpl implements VideoEditor {
    // Logging
    private static final String TAG = "VideoEditorImpl";

    // The project filename
    private static final String PROJECT_FILENAME = "videoeditor.xml";

    // XML tags
    private static final String TAG_PROJECT = "project";
    private static final String TAG_MEDIA_ITEMS = "media_items";
    private static final String TAG_MEDIA_ITEM = "media_item";
    private static final String TAG_BEGIN_TRANSITION = "begin_transition";
    private static final String TAG_END_TRANSITION = "end_transition";
    private static final String ATTR_ID = "id";
    private static final String ATTR_FILENAME = "filename";
    private static final String ATTR_AUDIO_WAVEFORM_FILENAME = "wavefoem";
    private static final String ATTR_RENDERING_MODE = "rendering_mode";
    private static final String ATTR_ASPECT_RATIO = "aspect_ratio";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_DURATION = "duration";
    private static final String ATTR_BEGIN_TIME = "start_time";
    private static final String ATTR_END_TIME = "end_time";
    private static final String ATTR_VOLUME = "volume";
    private static final String ATTR_BEHAVIOR = "behavior";
    private static final String ATTR_DIRECTION = "direction";
    private static final String ATTR_BLENDING = "blending";
    private static final String ATTR_INVERT = "invert";
    private static final String ATTR_MASK = "mask";

    // Instance variables
    private long mDurationMs;
    private final String mProjectPath;
    private final List<MediaItem> mMediaItems = new ArrayList<MediaItem>();
    private final List<AudioTrack> mAudioTracks = new ArrayList<AudioTrack>();
    private final List<Transition> mTransitions = new ArrayList<Transition>();
    private PreviewThread mPreviewThread;
    private int mAspectRatio;

    /**
     * The preview thread
     */
    private class PreviewThread extends Thread {
        // Instance variables
        private final static long FRAME_DURATION = 33;

        // Instance variables
        private final PreviewProgressListener mListener;
        private final int mCallbackAfterFrameCount;
        private final long mFromMs, mToMs;
        private boolean mRun, mLoop;
        private long mPositionMs;

        /**
         * Constructor
         *
         * @param fromMs Start preview at this position
         * @param toMs The time (relative to the timeline) at which the preview
         *            will stop. Use -1 to play to the end of the timeline
         * @param callbackAfterFrameCount The listener interface should be
         *            invoked after the number of frames specified by this
         *            parameter.
         * @param loop true if the preview should be looped once it reaches the
         *            end
         * @param listener The listener
         */
        public PreviewThread(long fromMs, long toMs, boolean loop, int callbackAfterFrameCount,
                PreviewProgressListener listener) {
            mPositionMs = mFromMs = fromMs;
            if (toMs < 0) {
                mToMs = mDurationMs;
            } else {
                mToMs = toMs;
            }
            mLoop = loop;
            mCallbackAfterFrameCount = callbackAfterFrameCount;
            mListener = listener;
            mRun = true;
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void run() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "===> PreviewThread.run enter");
            }
            int frameCount = 0;
            while (mRun) {
                try {
                    sleep(FRAME_DURATION);
                } catch (InterruptedException ex) {
                    break;
                }
                frameCount++;
                mPositionMs += FRAME_DURATION;

                if (mPositionMs >= mToMs) {
                    if (!mLoop) {
                        if (mListener != null) {
                            mListener.onProgress(VideoEditorTestImpl.this, mPositionMs, true);
                        }
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "PreviewThread.run playback complete");
                        }
                        break;
                    } else {
                        // Fire a notification for the end of the clip
                        if (mListener != null) {
                            mListener.onProgress(VideoEditorTestImpl.this, mToMs, false);
                        }

                        // Rewind
                        mPositionMs = mFromMs;
                        if (mListener != null) {
                            mListener.onProgress(VideoEditorTestImpl.this, mPositionMs, false);
                        }
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "PreviewThread.run playback complete");
                        }
                        frameCount = 0;
                    }
                } else {
                    if (frameCount == mCallbackAfterFrameCount) {
                        if (mListener != null) {
                            mListener.onProgress(VideoEditorTestImpl.this, mPositionMs, false);
                        }
                        frameCount = 0;
                    }
                }
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "===> PreviewThread.run exit");
            }
        }

        /**
         * Stop the preview
         *
         * @return The stop position
         */
        public long stopPreview() {
            mRun = false;
            try {
                join();
            } catch (InterruptedException ex) {
            }
            return mPositionMs;
        }
    };

    /**
     * Constructor
     *
     * @param projectPath
     */
    public VideoEditorTestImpl(String projectPath) throws IOException {
        mProjectPath = projectPath;
        final File projectXml = new File(projectPath, PROJECT_FILENAME);
        if (projectXml.exists()) {
            try {
                load();
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        } else {
            mAspectRatio = MediaProperties.ASPECT_RATIO_16_9;
            mDurationMs = 0;
        }
    }

    /*
     * {@inheritDoc}
     */
    public String getPath() {
        return mProjectPath;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void addMediaItem(MediaItem mediaItem) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        if (mMediaItems.contains(mediaItem)) {
            throw new IllegalArgumentException("Media item already exists: " + mediaItem.getId());
        }

        // Invalidate the end transition if necessary
        final int mediaItemsCount = mMediaItems.size();
        if ( mediaItemsCount > 0) {
            removeTransitionAfter(mediaItemsCount - 1);
        }

        // Add the new media item
        mMediaItems.add(mediaItem);

        computeTimelineDuration();
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void insertMediaItem(MediaItem mediaItem, String afterMediaItemId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        if (mMediaItems.contains(mediaItem)) {
            throw new IllegalArgumentException("Media item already exists: " + mediaItem.getId());
        }

        if (afterMediaItemId == null) {
            if (mMediaItems.size() > 0) {
                // Invalidate the transition at the beginning of the timeline
                removeTransitionBefore(0);
            }
            mMediaItems.add(0, mediaItem);
            computeTimelineDuration();
        } else {
            final int mediaItemCount = mMediaItems.size();
            for (int i = 0; i < mediaItemCount; i++) {
                final MediaItem mi = mMediaItems.get(i);
                if (mi.getId().equals(afterMediaItemId)) {
                    // Invalidate the transition at this position
                    removeTransitionAfter(i);
                    // Insert the new media item
                    mMediaItems.add(i + 1, mediaItem);
                    computeTimelineDuration();
                    return;
                }
            }
            throw new IllegalArgumentException("MediaItem not found: " + afterMediaItemId);
        }
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void moveMediaItem(String mediaItemId, String afterMediaItemId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        final MediaItem moveMediaItem = removeMediaItem(mediaItemId);
        if (moveMediaItem == null) {
            throw new IllegalArgumentException("Target MediaItem not found: " + mediaItemId);
        }

        if (afterMediaItemId == null) {
            if (mMediaItems.size() > 0) {
                // Invalidate adjacent transitions at the insertion point
                removeTransitionBefore(0);

                // Insert the media item at the new position
                mMediaItems.add(0, moveMediaItem);
                computeTimelineDuration();
            } else {
                throw new IllegalStateException("Cannot move media item (it is the only item)");
            }
        } else {
            final int mediaItemCount = mMediaItems.size();
            for (int i = 0; i < mediaItemCount; i++) {
                final MediaItem mi = mMediaItems.get(i);
                if (mi.getId().equals(afterMediaItemId)) {
                    // Invalidate adjacent transitions at the insertion point
                    removeTransitionAfter(i);
                    // Insert the media item at the new position
                    mMediaItems.add(i + 1, moveMediaItem);
                    computeTimelineDuration();
                    return;
                }
            }

            throw new IllegalArgumentException("MediaItem not found: " + afterMediaItemId);
        }
    }

    /*
     * {@inheritDoc}
     */
    public synchronized MediaItem removeMediaItem(String mediaItemId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        final MediaItem mediaItem = getMediaItem(mediaItemId);
        if (mediaItem != null) {
            // Remove the media item
            mMediaItems.remove(mediaItem);
            // Remove the adjacent transitions
            removeAdjacentTransitions(mediaItem);
            computeTimelineDuration();
        }

        return mediaItem;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized MediaItem getMediaItem(String mediaItemId) {
        for (MediaItem mediaItem : mMediaItems) {
            if (mediaItem.getId().equals(mediaItemId)) {
                return mediaItem;
            }
        }

        return null;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized List<MediaItem> getAllMediaItems() {
        return mMediaItems;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void removeAllMediaItems() {
        mMediaItems.clear();

        // Invalidate all transitions
        for (Transition transition : mTransitions) {
            transition.invalidate();
        }
        mTransitions.clear();

        mDurationMs = 0;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void addTransition(Transition transition) {
        mTransitions.add(transition);

        final MediaItem beforeMediaItem = transition.getBeforeMediaItem();
        final MediaItem afterMediaItem = transition.getAfterMediaItem();

        // Cross reference the transitions
        if (afterMediaItem != null) {
            // If a transition already exists at the specified position then
            // invalidate it.
            if (afterMediaItem.getEndTransition() != null) {
                afterMediaItem.getEndTransition().invalidate();
            }
            afterMediaItem.setEndTransition(transition);
        }

        if (beforeMediaItem != null) {
            // If a transition already exists at the specified position then
            // invalidate it.
            if (beforeMediaItem.getBeginTransition() != null) {
                beforeMediaItem.getBeginTransition().invalidate();
            }
            beforeMediaItem.setBeginTransition(transition);
        }
        computeTimelineDuration();
    }

    /*
     * {@inheritDoc}
     */
    public synchronized Transition removeTransition(String transitionId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        final Transition transition = getTransition(transitionId);
        if (transition != null) {
            mTransitions.remove(transition);
            transition.invalidate();
            computeTimelineDuration();
        }

        // Cross reference the transitions
        final MediaItem afterMediaItem = transition.getAfterMediaItem();
        if (afterMediaItem != null) {
            afterMediaItem.setEndTransition(null);
        }
        final MediaItem beforeMediaItem = transition.getBeforeMediaItem();
        if (beforeMediaItem != null) {
            beforeMediaItem.setBeginTransition(null);
        }

        return transition;
    }

    /*
     * {@inheritDoc}
     */
    public List<Transition> getAllTransitions() {
        return mTransitions;
    }

    /*
     * {@inheritDoc}
     */
    public Transition getTransition(String transitionId) {
        for (Transition transition : mTransitions) {
            if (transition.getId().equals(transitionId)) {
                return transition;
            }
        }

        return null;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void addAudioTrack(AudioTrack audioTrack) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        mAudioTracks.add(audioTrack);
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void insertAudioTrack(AudioTrack audioTrack, String afterAudioTrackId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        if (afterAudioTrackId == null) {
            mAudioTracks.add(0, audioTrack);
        } else {
            final int audioTrackCount = mAudioTracks.size();
            for (int i = 0; i < audioTrackCount; i++) {
                AudioTrack at = mAudioTracks.get(i);
                if (at.getId().equals(afterAudioTrackId)) {
                    mAudioTracks.add(i + 1, audioTrack);
                    return;
                }
            }

            throw new IllegalArgumentException("AudioTrack not found: " + afterAudioTrackId);
        }
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void moveAudioTrack(String audioTrackId, String afterAudioTrackId) {
        throw new IllegalStateException("Not supported");
    }

    /*
     * {@inheritDoc}
     */
    public synchronized AudioTrack removeAudioTrack(String audioTrackId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        final AudioTrack audioTrack = getAudioTrack(audioTrackId);
        if (audioTrack != null) {
            mAudioTracks.remove(audioTrack);
        }

        return audioTrack;
    }

    /*
     * {@inheritDoc}
     */
    public AudioTrack getAudioTrack(String audioTrackId) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }

        final AudioTrack audioTrack = getAudioTrack(audioTrackId);
        if (audioTrack != null) {
            mAudioTracks.remove(audioTrack);
        }

        return audioTrack;
    }

    /*
     * {@inheritDoc}
     */
    public List<AudioTrack> getAllAudioTracks() {
        return mAudioTracks;
    }

    /*
     * {@inheritDoc}
     */
    public void save() throws IOException {
        final XmlSerializer serializer = Xml.newSerializer();
        final StringWriter writer = new StringWriter();
        serializer.setOutput(writer);
        serializer.startDocument("UTF-8", true);
        serializer.startTag("", TAG_PROJECT);
        serializer.attribute("", ATTR_ASPECT_RATIO, Integer.toString(mAspectRatio));

        boolean firstMediaItem = true;
        serializer.startTag("", TAG_MEDIA_ITEMS);
        for (MediaItem mediaItem : mMediaItems) {
            serializer.startTag("", TAG_MEDIA_ITEM);
            serializer.attribute("", ATTR_ID, mediaItem.getId());
            serializer.attribute("", ATTR_TYPE, mediaItem.getClass().getSimpleName());
            serializer.attribute("", ATTR_FILENAME, mediaItem.getFilename());
            serializer.attribute("", ATTR_RENDERING_MODE, Integer.toString(mediaItem
                    .getRenderingMode()));
            if (mediaItem instanceof MediaVideoItem) {
                final MediaVideoItem mvi = (MediaVideoItem)mediaItem;
                serializer
                        .attribute("", ATTR_BEGIN_TIME, Long.toString(mvi.getBoundaryBeginTime()));
                serializer.attribute("", ATTR_END_TIME, Long.toString(mvi.getBoundaryEndTime()));
                serializer.attribute("", ATTR_VOLUME, Integer.toString(mvi.getVolume()));
                if (mvi.getAudioWaveformFilename() != null) {
                    serializer.attribute("", ATTR_AUDIO_WAVEFORM_FILENAME, mvi
                            .getAudioWaveformFilename());
                }
            } else if (mediaItem instanceof MediaImageItem) {
                serializer.attribute("", ATTR_DURATION, Long.toString(mediaItem.getDuration()));
            }

            if (firstMediaItem) {
                firstMediaItem = false;
                final Transition beginTransition = mediaItem.getBeginTransition();
                if (beginTransition != null) {
                    serializer.startTag("", TAG_BEGIN_TRANSITION);
                    serializer.attribute("", ATTR_ID, beginTransition.getId());
                    serializer.attribute("", ATTR_TYPE, beginTransition.getClass()
                            .getSimpleName());
                    serializer.attribute("", ATTR_DURATION, Long.toString(beginTransition
                            .getDuration()));
                    serializer.attribute("", ATTR_BEHAVIOR, Integer.toString(beginTransition
                            .getBehavior()));
                    serializer.endTag("", TAG_BEGIN_TRANSITION);
                }
            }

            final Transition endTransition = mediaItem.getEndTransition();
            if (endTransition != null) {
                serializer.startTag("", TAG_END_TRANSITION);
                serializer.attribute("", ATTR_ID, endTransition.getId());
                serializer.attribute("", ATTR_TYPE, endTransition.getClass().getSimpleName());
                serializer.attribute("", ATTR_DURATION, Long.toString(endTransition
                        .getDuration()));
                serializer.attribute("", ATTR_BEHAVIOR, Integer.toString(endTransition
                        .getBehavior()));
                if (endTransition instanceof TransitionSliding) {
                    serializer.attribute("", ATTR_DIRECTION, Integer
                            .toString(((TransitionSliding)endTransition).getDirection()));
                } else if (endTransition instanceof TransitionAlpha) {
                    TransitionAlpha ta = (TransitionAlpha)endTransition;
                    serializer.attribute("", ATTR_BLENDING, Integer.toString(ta
                            .getBlendingPercent()));
                    serializer.attribute("", ATTR_INVERT, Boolean.toString(ta.isInvert()));
                    serializer.attribute("", ATTR_MASK, ta.getMaskFilename());
                }
                serializer.endTag("", TAG_END_TRANSITION);
            }

            serializer.endTag("", TAG_MEDIA_ITEM);
        }
        serializer.endTag("", TAG_MEDIA_ITEMS);

        serializer.endTag("", TAG_PROJECT);
        serializer.endDocument();

        // Save the metadata XML file
        final FileOutputStream out = new FileOutputStream(new File(getPath(), PROJECT_FILENAME));
        out.write(writer.toString().getBytes());
        out.flush();
        out.close();
    }

    /**
     * Load the project form XML
     */
    private void load() throws FileNotFoundException, XmlPullParserException, IOException {
        final File file = new File(mProjectPath, PROJECT_FILENAME);
        // Load the metadata
        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new FileInputStream(file), "UTF-8");
        int eventType = parser.getEventType();
        String name;
        MediaItem currentMediaItem = null;
        MediaItem previousMediaItem = null;
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG: {
                    name = parser.getName();
                    if (name.equals(TAG_PROJECT)) {
                        mAspectRatio = Integer.parseInt(parser.getAttributeValue("",
                                ATTR_ASPECT_RATIO));
                    } else if (name.equals(TAG_MEDIA_ITEM)) {
                        final String mediaItemId = parser.getAttributeValue("", ATTR_ID);
                        final String type = parser.getAttributeValue("", ATTR_TYPE);
                        final String filename = parser.getAttributeValue("", ATTR_FILENAME);
                        final int renderingMode = Integer.parseInt(parser.getAttributeValue("",
                                ATTR_RENDERING_MODE));

                        if (MediaImageItem.class.getSimpleName().equals(type)) {
                            final long durationMs = Long.parseLong(parser.getAttributeValue("",
                                    ATTR_DURATION));
                            currentMediaItem = new MediaImageItem(mediaItemId, filename,
                                    durationMs, renderingMode);
                        } else if (MediaVideoItem.class.getSimpleName().equals(type)) {
                            final String audioWaveformFilename = parser.getAttributeValue("",
                                    ATTR_AUDIO_WAVEFORM_FILENAME);
                            currentMediaItem = new MediaVideoItem(mediaItemId, filename,
                                    renderingMode, audioWaveformFilename);

                            final long beginTimeMs = Long.parseLong(parser.getAttributeValue("",
                                    ATTR_BEGIN_TIME));
                            final long endTimeMs = Long.parseLong(parser.getAttributeValue("",
                                    ATTR_END_TIME));
                            ((MediaVideoItem)currentMediaItem).setExtractBoundaries(beginTimeMs,
                                    endTimeMs);

                            final int volumePercent = Integer.parseInt(parser.getAttributeValue("",
                                    ATTR_VOLUME));
                            ((MediaVideoItem)currentMediaItem).setVolume(volumePercent);
                        } else {
                            Log.e(TAG, "Unknown media item type: " + type);
                            currentMediaItem = null;
                        }

                        if (currentMediaItem != null) {
                            if (previousMediaItem != null) {
                                currentMediaItem.setBeginTransition(previousMediaItem
                                        .getEndTransition());
                            }
                            mMediaItems.add(currentMediaItem);
                        }
                    } else if (name.equals(TAG_BEGIN_TRANSITION)) {
                        final Transition transition = parseTransition(parser, currentMediaItem,
                                null);
                        currentMediaItem.setBeginTransition(transition);
                    } else if (name.equals(TAG_END_TRANSITION)) {
                        final Transition transition = parseTransition(parser, previousMediaItem,
                                currentMediaItem);
                        currentMediaItem.setEndTransition(transition);
                    }
                    break;
                }

                case XmlPullParser.END_TAG: {
                    name = parser.getName();
                    if (name.equals(TAG_MEDIA_ITEM)) {
                        previousMediaItem = currentMediaItem;
                        currentMediaItem = null;
                    }
                    break;
                }

                default: {
                    break;
                }
            }
            eventType = parser.next();
        }

        computeTimelineDuration();
    }

    /**
     * Parse the transition
     *
     * @param parser The parser
     * @param afterMediaItem The transition is at the end of this media item
     * @param beforeMediaItem The transition is at the beginning of this media
     *            item
     * @return The transition
     */
    private Transition parseTransition(XmlPullParser parser, MediaItem beforeMediaItem,
            MediaItem afterMediaItem) {
        final String transitionId = parser.getAttributeValue("", ATTR_ID);
        final String type = parser.getAttributeValue("", ATTR_TYPE);
        final long durationMs = Long.parseLong(parser.getAttributeValue("", ATTR_DURATION));
        final int behavior = Integer.parseInt(parser.getAttributeValue("", ATTR_BEHAVIOR));
        if (TransitionStartCurtainOpening.class.getSimpleName().equals(type)) {
            return new TransitionStartCurtainOpening(transitionId, beforeMediaItem, durationMs,
                    behavior);
        } else if (TransitionStartFadeFromBlack.class.getSimpleName().equals(type)) {
            return new TransitionStartFadeFromBlack(transitionId, beforeMediaItem, durationMs,
                    behavior);
        } else if (TransitionAlpha.class.getSimpleName().equals(type)) {
            final int blending = Integer.parseInt(parser.getAttributeValue("", ATTR_BLENDING));
            final String maskFilename = parser.getAttributeValue("", ATTR_MASK);
            final boolean invert = Boolean.getBoolean(parser.getAttributeValue("", ATTR_INVERT));
            return new TransitionAlpha(transitionId, afterMediaItem, beforeMediaItem, durationMs,
                    behavior, maskFilename, blending, invert);
        } else if (TransitionAlpha.class.getSimpleName().equals(type)) {
            return new TransitionCrossfade(transitionId, afterMediaItem, beforeMediaItem,
                    durationMs, behavior);
        } else if (TransitionSliding.class.getSimpleName().equals(type)) {
            final int direction = Integer.parseInt(parser.getAttributeValue("", ATTR_DIRECTION));
            return new TransitionSliding(transitionId, afterMediaItem, beforeMediaItem, durationMs,
                    behavior, direction);
        } else if (TransitionFadeToBlack.class.getSimpleName().equals(type)) {
            return new TransitionFadeToBlack(transitionId, afterMediaItem, beforeMediaItem,
                    durationMs, behavior);
        } else if (TransitionEndCurtainClosing.class.getSimpleName().equals(type)) {
            return new TransitionEndCurtainClosing(transitionId, beforeMediaItem, durationMs,
                    behavior);
        } else if (TransitionEndFadeToBlack.class.getSimpleName().equals(type)) {
            return new TransitionEndFadeToBlack(transitionId, beforeMediaItem, durationMs, behavior);
        }

        return null;
    }

    public void cancelExport(String filename) {
    }

    public void export(String filename, int height, int bitrate, ExportProgressListener listener)
            throws IOException {
    }

    /*
     * {@inheritDoc}
     */
    public void generatePreview() {
        // Generate all the needed transitions
        for (Transition transition : mTransitions) {
            if (!transition.isGenerated()) {
                transition.generate();
            }
        }

        // This is necessary because the user may had called setDuration on
        // MediaImageItems
        computeTimelineDuration();
    }

    /*
     * {@inheritDoc}
     */
    public void release() {
        stopPreview();
    }

    /*
     * {@inheritDoc}
     */
    public long getDuration() {
        // Since MediaImageItem can change duration we need to compute the
        // duration here
        computeTimelineDuration();
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
     */
    public int getAspectRatio() {
        return mAspectRatio;
    }

    /*
     * {@inheritDoc}
     */
    public void setAspectRatio(int aspectRatio) {
        mAspectRatio = aspectRatio;
    }

    /*
     * {@inheritDoc}
     */
    public long renderPreviewFrame(SurfaceHolder surfaceHolder, long timeMs) {
        if (mPreviewThread != null) {
            throw new IllegalStateException("Previewing is in progress");
        }
        return timeMs;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void startPreview(SurfaceHolder surfaceHolder, long fromMs, long toMs,
            boolean loop, int callbackAfterFrameCount, PreviewProgressListener listener) {
        if (fromMs >= mDurationMs) {
            return;
        }
        mPreviewThread = new PreviewThread(fromMs, toMs, loop, callbackAfterFrameCount, listener);
        mPreviewThread.start();
    }

    /*
     * {@inheritDoc}
     */
    public synchronized long stopPreview() {
        final long stopTimeMs;
        if (mPreviewThread != null) {
            stopTimeMs = mPreviewThread.stopPreview();
            mPreviewThread = null;
        } else {
            stopTimeMs = 0;
        }
        return stopTimeMs;
    }

    /**
     * Compute the duration
     */
    private void computeTimelineDuration() {
        mDurationMs = 0;
        for (MediaItem mediaItem : mMediaItems) {
            mDurationMs += mediaItem.getTimelineDuration();
        }

        // Subtract the transition times
        for (Transition transition : mTransitions) {
            if (!(transition instanceof TransitionStartCurtainOpening)
                    && !(transition instanceof TransitionStartFadeFromBlack)
                    && !(transition instanceof TransitionEndFadeToBlack)
                    && !(transition instanceof TransitionEndCurtainClosing)) {
                mDurationMs -= transition.getDuration();
            }
        }
    }

    /**
     * Remove transitions associated with the specified media item
     *
     * @param mediaItem The media item
     */
    private void removeAdjacentTransitions(MediaItem mediaItem) {
        final Iterator<Transition> it = mTransitions.iterator();
        while (it.hasNext()) {
            Transition t = it.next();
            if (t.getAfterMediaItem() == mediaItem || t.getBeforeMediaItem() == mediaItem) {
                it.remove();
                t.invalidate();
                mediaItem.setBeginTransition(null);
                mediaItem.setEndTransition(null);
                break;
            }
        }
    }

    /**
     * Remove the transition before this media item
     *
     * @param index The media item index
     */
    private void removeTransitionBefore(int index) {
        final MediaItem mediaItem = mMediaItems.get(0);
        final Iterator<Transition> it = mTransitions.iterator();
        while (it.hasNext()) {
            Transition t = it.next();
            if (t.getBeforeMediaItem() == mediaItem) {
                it.remove();
                t.invalidate();
                mediaItem.setBeginTransition(null);
                if (index > 0) {
                    mMediaItems.get(index - 1).setEndTransition(null);
                }
                break;
            }
        }
    }

    /**
     * Remove the transition after this media item
     *
     * @param index The media item index
     */
    private void removeTransitionAfter(int index) {
        final MediaItem mediaItem = mMediaItems.get(index);
        final Iterator<Transition> it = mTransitions.iterator();
        while (it.hasNext()) {
            Transition t = it.next();
            if (t.getAfterMediaItem() == mediaItem) {
                it.remove();
                t.invalidate();
                mediaItem.setEndTransition(null);
                // Invalidate the reference in the next media item
                if (index < mMediaItems.size() - 1) {
                    mMediaItems.get(index + 1).setBeginTransition(null);
                }
                break;
            }
        }
    }
}
