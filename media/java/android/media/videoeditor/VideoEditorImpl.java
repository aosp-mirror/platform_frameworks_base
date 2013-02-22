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
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.videoeditor.MediaImageItem;
import android.media.videoeditor.MediaItem;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.util.Xml;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.os.Debug;
import android.os.SystemProperties;
import android.os.Environment;

/**
 * The VideoEditor implementation {@hide}
 */
public class VideoEditorImpl implements VideoEditor {
    /*
     *  Logging
     */
    private static final String TAG = "VideoEditorImpl";

    /*
     *  The project filename
     */
    private static final String PROJECT_FILENAME = "videoeditor.xml";

    /*
     *  XML tags
     */
    private static final String TAG_PROJECT = "project";
    private static final String TAG_MEDIA_ITEMS = "media_items";
    private static final String TAG_MEDIA_ITEM = "media_item";
    private static final String TAG_TRANSITIONS = "transitions";
    private static final String TAG_TRANSITION = "transition";
    private static final String TAG_OVERLAYS = "overlays";
    private static final String TAG_OVERLAY = "overlay";
    private static final String TAG_OVERLAY_USER_ATTRIBUTES = "overlay_user_attributes";
    private static final String TAG_EFFECTS = "effects";
    private static final String TAG_EFFECT = "effect";
    private static final String TAG_AUDIO_TRACKS = "audio_tracks";
    private static final String TAG_AUDIO_TRACK = "audio_track";

    private static final String ATTR_ID = "id";
    private static final String ATTR_FILENAME = "filename";
    private static final String ATTR_AUDIO_WAVEFORM_FILENAME = "waveform";
    private static final String ATTR_RENDERING_MODE = "rendering_mode";
    private static final String ATTR_ASPECT_RATIO = "aspect_ratio";
    private static final String ATTR_REGENERATE_PCM = "regeneratePCMFlag";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_DURATION = "duration";
    private static final String ATTR_START_TIME = "start_time";
    private static final String ATTR_BEGIN_TIME = "begin_time";
    private static final String ATTR_END_TIME = "end_time";
    private static final String ATTR_VOLUME = "volume";
    private static final String ATTR_BEHAVIOR = "behavior";
    private static final String ATTR_DIRECTION = "direction";
    private static final String ATTR_BLENDING = "blending";
    private static final String ATTR_INVERT = "invert";
    private static final String ATTR_MASK = "mask";
    private static final String ATTR_BEFORE_MEDIA_ITEM_ID = "before_media_item";
    private static final String ATTR_AFTER_MEDIA_ITEM_ID = "after_media_item";
    private static final String ATTR_COLOR_EFFECT_TYPE = "color_type";
    private static final String ATTR_COLOR_EFFECT_VALUE = "color_value";
    private static final String ATTR_START_RECT_LEFT = "start_l";
    private static final String ATTR_START_RECT_TOP = "start_t";
    private static final String ATTR_START_RECT_RIGHT = "start_r";
    private static final String ATTR_START_RECT_BOTTOM = "start_b";
    private static final String ATTR_END_RECT_LEFT = "end_l";
    private static final String ATTR_END_RECT_TOP = "end_t";
    private static final String ATTR_END_RECT_RIGHT = "end_r";
    private static final String ATTR_END_RECT_BOTTOM = "end_b";
    private static final String ATTR_LOOP = "loop";
    private static final String ATTR_MUTED = "muted";
    private static final String ATTR_DUCK_ENABLED = "ducking_enabled";
    private static final String ATTR_DUCK_THRESHOLD = "ducking_threshold";
    private static final String ATTR_DUCKED_TRACK_VOLUME = "ducking_volume";
    private static final String ATTR_GENERATED_IMAGE_CLIP = "generated_image_clip";
    private static final String ATTR_IS_IMAGE_CLIP_GENERATED = "is_image_clip_generated";
    private static final String ATTR_GENERATED_TRANSITION_CLIP = "generated_transition_clip";
    private static final String ATTR_IS_TRANSITION_GENERATED = "is_transition_generated";
    private static final String ATTR_OVERLAY_RGB_FILENAME = "overlay_rgb_filename";
    private static final String ATTR_OVERLAY_FRAME_WIDTH = "overlay_frame_width";
    private static final String ATTR_OVERLAY_FRAME_HEIGHT = "overlay_frame_height";
    private static final String ATTR_OVERLAY_RESIZED_RGB_FRAME_WIDTH = "resized_RGBframe_width";
    private static final String ATTR_OVERLAY_RESIZED_RGB_FRAME_HEIGHT = "resized_RGBframe_height";
    private static final int ENGINE_ACCESS_MAX_TIMEOUT_MS = 500;
    /*
     *  Instance variables
     */
    private final Semaphore mLock;
    private final String mProjectPath;
    private final List<MediaItem> mMediaItems = new ArrayList<MediaItem>();
    private final List<AudioTrack> mAudioTracks = new ArrayList<AudioTrack>();
    private final List<Transition> mTransitions = new ArrayList<Transition>();
    private long mDurationMs;
    private int mAspectRatio;

    /*
     * Private Object for calling native Methods via MediaArtistNativeHelper
     */
    private MediaArtistNativeHelper mMANativeHelper;
    private boolean mPreviewInProgress = false;
    private final boolean mMallocDebug;

    /**
     * Constructor
     *
     * @param projectPath - The path where the VideoEditor stores all files
     *        related to the project
     */
    public VideoEditorImpl(String projectPath) throws IOException {
        String s;
        s = SystemProperties.get("libc.debug.malloc");
        if (s.equals("1")) {
            mMallocDebug = true;
            try {
                dumpHeap("HeapAtStart");
            } catch (Exception ex) {
                Log.e(TAG, "dumpHeap returned error in constructor");
            }
        } else {
            mMallocDebug = false;
        }
        mLock = new Semaphore(1, true);
        mMANativeHelper = new MediaArtistNativeHelper(projectPath, mLock, this);
        mProjectPath = projectPath;
        final File projectXml = new File(projectPath, PROJECT_FILENAME);
        if (projectXml.exists()) {
            try {
                load();
            } catch (Exception ex) {
                ex.printStackTrace();
                throw new IOException(ex.toString());
            }
        } else {
            mAspectRatio = MediaProperties.ASPECT_RATIO_16_9;
            mDurationMs = 0;
        }
    }

    /*
     * @return The MediaArtistNativeHelper object
     */
    MediaArtistNativeHelper getNativeContext() {
        return mMANativeHelper;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void addAudioTrack(AudioTrack audioTrack) {
        if (audioTrack == null) {
            throw new IllegalArgumentException("Audio Track is null");
        }

        if (mAudioTracks.size() == 1) {
            throw new IllegalArgumentException("No more tracks can be added");
        }

        mMANativeHelper.setGeneratePreview(true);

        /*
         * Add the audio track to AudioTrack list
         */
        mAudioTracks.add(audioTrack);

        /*
         * Form the audio PCM file path
         */
        final String audioTrackPCMFilePath = String.format(mProjectPath + "/"
                    + "AudioPcm" + audioTrack.getId() + ".pcm");

        /*
         * Create PCM only if not generated in previous session
         */
        if (new File(audioTrackPCMFilePath).exists()) {
            mMANativeHelper.setAudioflag(false);
        }

    }

    /*
     * {@inheritDoc}
     */
    public synchronized void addMediaItem(MediaItem mediaItem) {
        /*
         * Validate Media Item
         */
        if (mediaItem == null) {
            throw new IllegalArgumentException("Media item is null");
        }
        /*
         * Add the Media item to MediaItem list
         */
        if (mMediaItems.contains(mediaItem)) {
            throw new IllegalArgumentException("Media item already exists: " + mediaItem.getId());
        }

        mMANativeHelper.setGeneratePreview(true);

        /*
         *  Invalidate the end transition if necessary
         */
        final int mediaItemsCount = mMediaItems.size();
        if (mediaItemsCount > 0) {
            removeTransitionAfter(mediaItemsCount - 1);
        }

        /*
         *  Add the new media item
         */
        mMediaItems.add(mediaItem);

        computeTimelineDuration();

        /*
         *  Generate project thumbnail only from first media Item on storyboard
         */
        if (mMediaItems.size() == 1) {
            generateProjectThumbnail();
        }
    }


    /*
     * {@inheritDoc}
     */
    public synchronized void addTransition(Transition transition) {
        if (transition == null) {
            throw new IllegalArgumentException("Null Transition");
        }

        final MediaItem beforeMediaItem = transition.getBeforeMediaItem();
        final MediaItem afterMediaItem = transition.getAfterMediaItem();
        /*
         * Check if the MediaItems are in sequence
         */
        if (mMediaItems == null) {
            throw new IllegalArgumentException("No media items are added");
        }

        if ((afterMediaItem != null) &&  (beforeMediaItem != null)) {
            final int afterMediaItemIndex = mMediaItems.indexOf(afterMediaItem);
            final int beforeMediaItemIndex = mMediaItems.indexOf(beforeMediaItem);

            if ((afterMediaItemIndex == -1) || (beforeMediaItemIndex == -1)) {
                throw new IllegalArgumentException
                    ("Either of the mediaItem is not found in the list");
            }

            if (afterMediaItemIndex != (beforeMediaItemIndex - 1) ) {
                throw new IllegalArgumentException("MediaItems are not in sequence");
            }
        }

        mMANativeHelper.setGeneratePreview(true);

        mTransitions.add(transition);
        /*
         *  Cross reference the transitions
         */
        if (afterMediaItem != null) {
            /*
             *  If a transition already exists at the specified position then
             *  invalidate it.
             */
            if (afterMediaItem.getEndTransition() != null) {
                afterMediaItem.getEndTransition().invalidate();
                mTransitions.remove(afterMediaItem.getEndTransition());
            }
            afterMediaItem.setEndTransition(transition);
        }

        if (beforeMediaItem != null) {
            /*
             *  If a transition already exists at the specified position then
             *  invalidate it.
             */
            if (beforeMediaItem.getBeginTransition() != null) {
                beforeMediaItem.getBeginTransition().invalidate();
                mTransitions.remove(beforeMediaItem.getBeginTransition());
            }
            beforeMediaItem.setBeginTransition(transition);
        }

        computeTimelineDuration();
    }

    /*
     * {@inheritDoc}
     */
    public void cancelExport(String filename) {
        if (mMANativeHelper != null && filename != null) {
            mMANativeHelper.stop(filename);
        }
    }

    /*
     * {@inheritDoc}
     */
    public void export(String filename, int height, int bitrate,
                       int audioCodec, int videoCodec,
                       ExportProgressListener listener)
                       throws IOException {
        int audcodec = 0;
        int vidcodec = 0;
        if (filename == null) {
            throw new IllegalArgumentException("export: filename is null");
        }

        final File tempPathFile = new File(filename);
        if (tempPathFile == null) {
            throw new IOException(filename + "can not be created");
        }

        if (mMediaItems.size() == 0) {
            throw new IllegalStateException("No MediaItems added");
        }

        switch (height) {
            case MediaProperties.HEIGHT_144:
                break;
            case MediaProperties.HEIGHT_288:
                break;
            case MediaProperties.HEIGHT_360:
                break;
            case MediaProperties.HEIGHT_480:
                break;
            case MediaProperties.HEIGHT_720:
                break;
            case MediaProperties.HEIGHT_1080:
                break;

            default: {
                String message = "Unsupported height value " + height;
                throw new IllegalArgumentException(message);
            }
        }

        switch (bitrate) {
            case MediaProperties.BITRATE_28K:
                break;
            case MediaProperties.BITRATE_40K:
                break;
            case MediaProperties.BITRATE_64K:
                break;
            case MediaProperties.BITRATE_96K:
                break;
            case MediaProperties.BITRATE_128K:
                break;
            case MediaProperties.BITRATE_192K:
                break;
            case MediaProperties.BITRATE_256K:
                break;
            case MediaProperties.BITRATE_384K:
                break;
            case MediaProperties.BITRATE_512K:
                break;
            case MediaProperties.BITRATE_800K:
                break;
            case MediaProperties.BITRATE_2M:
                break;
            case MediaProperties.BITRATE_5M:
                break;
            case MediaProperties.BITRATE_8M:
                break;

            default: {
                final String message = "Unsupported bitrate value " + bitrate;
                throw new IllegalArgumentException(message);
            }
        }
        computeTimelineDuration();
        final long audioBitrate = MediaArtistNativeHelper.Bitrate.BR_96_KBPS;
        final long fileSize = (mDurationMs * (bitrate + audioBitrate)) / 8000;
        if (MAX_SUPPORTED_FILE_SIZE <= fileSize) {
            throw new IllegalStateException("Export Size is more than 2GB");
        }
        switch (audioCodec) {
            case MediaProperties.ACODEC_AAC_LC:
                audcodec = MediaArtistNativeHelper.AudioFormat.AAC;
                break;
            case MediaProperties.ACODEC_AMRNB:
                audcodec = MediaArtistNativeHelper.AudioFormat.AMR_NB;
                break;

            default: {
                String message = "Unsupported audio codec type " + audioCodec;
                throw new IllegalArgumentException(message);
            }
        }

        switch (videoCodec) {
            case MediaProperties.VCODEC_H263:
                vidcodec = MediaArtistNativeHelper.VideoFormat.H263;
                break;
            case MediaProperties.VCODEC_H264:
                vidcodec = MediaArtistNativeHelper.VideoFormat.H264;
                break;
            case MediaProperties.VCODEC_MPEG4:
                vidcodec = MediaArtistNativeHelper.VideoFormat.MPEG4;
                break;

            default: {
                String message = "Unsupported video codec type " + videoCodec;
                throw new IllegalArgumentException(message);
            }
        }

        boolean semAcquireDone = false;
        try {
            lock();
            semAcquireDone = true;

            if (mMANativeHelper == null) {
                throw new IllegalStateException("The video editor is not initialized");
            }
            mMANativeHelper.setAudioCodec(audcodec);
            mMANativeHelper.setVideoCodec(vidcodec);
            mMANativeHelper.export(filename, mProjectPath, height,bitrate,
                               mMediaItems, mTransitions, mAudioTracks, listener);
        } catch (InterruptedException  ex) {
            Log.e(TAG, "Sem acquire NOT successful in export");
        } finally {
            if (semAcquireDone) {
                unlock();
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    public void export(String filename, int height, int bitrate,
                       ExportProgressListener listener)
                       throws IOException {
        int defaultAudiocodec = MediaArtistNativeHelper.AudioFormat.AAC;
        int defaultVideocodec = MediaArtistNativeHelper.VideoFormat.H264;

        export(filename, height, bitrate, defaultAudiocodec,
                defaultVideocodec, listener);
    }

    /*
     * {@inheritDoc}
     */
    public void generatePreview(MediaProcessingProgressListener listener) {
        boolean semAcquireDone = false;
        try {
            lock();
            semAcquireDone = true;

            if (mMANativeHelper == null) {
                throw new IllegalStateException("The video editor is not initialized");
            }

            if ((mMediaItems.size() > 0) || (mAudioTracks.size() > 0)) {
                mMANativeHelper.previewStoryBoard(mMediaItems, mTransitions, mAudioTracks,
                        listener);
            }
        } catch (InterruptedException  ex) {
            Log.e(TAG, "Sem acquire NOT successful in previewStoryBoard");
        } finally {
            if (semAcquireDone) {
                unlock();
            }
        }
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
    public List<MediaItem> getAllMediaItems() {
        return mMediaItems;
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
    public int getAspectRatio() {
        return mAspectRatio;
    }

    /*
     * {@inheritDoc}
     */
    public AudioTrack getAudioTrack(String audioTrackId) {
        for (AudioTrack at : mAudioTracks) {
            if (at.getId().equals(audioTrackId)) {
                return at;
            }
        }
        return null;
    }

    /*
     * {@inheritDoc}
     */
    public long getDuration() {
        /**
         *  Since MediaImageItem can change duration we need to compute the
         *  duration here
         */
        computeTimelineDuration();
        return mDurationMs;
    }

    /*
     * Force updates the timeline duration
     */
    void updateTimelineDuration() {
        computeTimelineDuration();
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
    public String getPath() {
        return mProjectPath;
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
    public synchronized void insertAudioTrack(AudioTrack audioTrack,
                                              String afterAudioTrackId) {
        if (mAudioTracks.size() == 1) {
            throw new IllegalArgumentException("No more tracks can be added");
        }

        if (afterAudioTrackId == null) {
            mMANativeHelper.setGeneratePreview(true);
            mAudioTracks.add(0, audioTrack);
        } else {
            final int audioTrackCount = mAudioTracks.size();
            for (int i = 0; i < audioTrackCount; i++) {
                AudioTrack at = mAudioTracks.get(i);
                if (at.getId().equals(afterAudioTrackId)) {
                    mMANativeHelper.setGeneratePreview(true);
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
    public synchronized void insertMediaItem(MediaItem mediaItem, String afterMediaItemId) {
        if (mMediaItems.contains(mediaItem)) {
            throw new IllegalArgumentException("Media item already exists: " + mediaItem.getId());
        }

        if (afterMediaItemId == null) {
            mMANativeHelper.setGeneratePreview(true);
            if (mMediaItems.size() > 0) {
                /**
                 *  Invalidate the transition at the beginning of the timeline
                 */
                removeTransitionBefore(0);
            }

            mMediaItems.add(0, mediaItem);
            computeTimelineDuration();
            generateProjectThumbnail();
        } else {
            final int mediaItemCount = mMediaItems.size();
            for (int i = 0; i < mediaItemCount; i++) {
                final MediaItem mi = mMediaItems.get(i);
                if (mi.getId().equals(afterMediaItemId)) {
                    mMANativeHelper.setGeneratePreview(true);
                    /**
                     *  Invalidate the transition at this position
                     */
                    removeTransitionAfter(i);
                    /**
                     *  Insert the new media item
                     */
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
    public synchronized void moveAudioTrack(String audioTrackId, String afterAudioTrackId) {
        throw new IllegalStateException("Not supported");
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void moveMediaItem(String mediaItemId, String afterMediaItemId) {
        final MediaItem moveMediaItem = removeMediaItem(mediaItemId,true);
        if (moveMediaItem == null) {
            throw new IllegalArgumentException("Target MediaItem not found: " + mediaItemId);
        }

        if (afterMediaItemId == null) {
            if (mMediaItems.size() > 0) {
                mMANativeHelper.setGeneratePreview(true);

                /**
                 *  Invalidate adjacent transitions at the insertion point
                 */
                removeTransitionBefore(0);

                /**
                 *  Insert the media item at the new position
                 */
                mMediaItems.add(0, moveMediaItem);
                computeTimelineDuration();

                generateProjectThumbnail();
            } else {
                throw new IllegalStateException("Cannot move media item (it is the only item)");
            }
        } else {
            final int mediaItemCount = mMediaItems.size();
            for (int i = 0; i < mediaItemCount; i++) {
                final MediaItem mi = mMediaItems.get(i);
                if (mi.getId().equals(afterMediaItemId)) {
                    mMANativeHelper.setGeneratePreview(true);
                    /**
                     *  Invalidate adjacent transitions at the insertion point
                     */
                    removeTransitionAfter(i);
                    /**
                     *  Insert the media item at the new position
                     */
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
    public void release() {
        stopPreview();

        boolean semAcquireDone = false;
        try {
            lock();
            semAcquireDone = true;

            if (mMANativeHelper != null) {
                mMediaItems.clear();
                mAudioTracks.clear();
                mTransitions.clear();
                mMANativeHelper.releaseNativeHelper();
                mMANativeHelper = null;
            }
        } catch (Exception  ex) {
            Log.e(TAG, "Sem acquire NOT successful in export", ex);
        } finally {
            if (semAcquireDone) {
                unlock();
            }
        }
        if (mMallocDebug) {
            try {
                dumpHeap("HeapAtEnd");
            } catch (Exception ex) {
                Log.e(TAG, "dumpHeap returned error in release");
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    public synchronized void removeAllMediaItems() {
        mMANativeHelper.setGeneratePreview(true);

        mMediaItems.clear();

        /**
         *  Invalidate all transitions
         */
        for (Transition transition : mTransitions) {
            transition.invalidate();
        }
        mTransitions.clear();

        mDurationMs = 0;
        /**
         * If a thumbnail already exists, then delete it
         */
        if ((new File(mProjectPath + "/" + THUMBNAIL_FILENAME)).exists()) {
            (new File(mProjectPath + "/" + THUMBNAIL_FILENAME)).delete();
        }

    }

    /*
     * {@inheritDoc}
     */
    public synchronized AudioTrack removeAudioTrack(String audioTrackId) {
        final AudioTrack audioTrack = getAudioTrack(audioTrackId);
        if (audioTrack != null) {
            mMANativeHelper.setGeneratePreview(true);
            mAudioTracks.remove(audioTrack);
            audioTrack.invalidate();
            mMANativeHelper.invalidatePcmFile();
            mMANativeHelper.setAudioflag(true);
        } else {
            throw new IllegalArgumentException(" No more audio tracks");
        }
        return audioTrack;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized MediaItem removeMediaItem(String mediaItemId) {
        final String firstItemString = mMediaItems.get(0).getId();
        final MediaItem mediaItem = getMediaItem(mediaItemId);
        if (mediaItem != null) {
            mMANativeHelper.setGeneratePreview(true);
            /**
             *  Remove the media item
             */
            mMediaItems.remove(mediaItem);
            if (mediaItem instanceof MediaImageItem) {
                ((MediaImageItem)mediaItem).invalidate();
            }
            final List<Overlay> overlays = mediaItem.getAllOverlays();
            if (overlays.size() > 0) {
                for (Overlay overlay : overlays) {
                    if (overlay instanceof OverlayFrame) {
                        final OverlayFrame overlayFrame = (OverlayFrame)overlay;
                        overlayFrame.invalidate();
                    }
                }
            }

            /**
             *  Remove the adjacent transitions
             */
            removeAdjacentTransitions(mediaItem);
            computeTimelineDuration();
        }

        /**
         * If string equals first mediaItem, then
         * generate Project thumbnail
         */
        if (firstItemString.equals(mediaItemId)) {
            generateProjectThumbnail();
        }

        if (mediaItem instanceof MediaVideoItem) {
            /**
             * Delete the graph file
             */
            ((MediaVideoItem)mediaItem).invalidate();
        }
        return mediaItem;
    }

    private synchronized MediaItem removeMediaItem(String mediaItemId, boolean flag) {
        final String firstItemString = mMediaItems.get(0).getId();

        final MediaItem mediaItem = getMediaItem(mediaItemId);
        if (mediaItem != null) {
            mMANativeHelper.setGeneratePreview(true);
            /**
             *  Remove the media item
             */
            mMediaItems.remove(mediaItem);
            /**
             *  Remove the adjacent transitions
             */
            removeAdjacentTransitions(mediaItem);
            computeTimelineDuration();
        }

        /**
         * If string equals first mediaItem, then
         * generate Project thumbail
         */
        if (firstItemString.equals(mediaItemId)) {
            generateProjectThumbnail();
        }
        return mediaItem;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized Transition removeTransition(String transitionId) {
        final Transition transition = getTransition(transitionId);
        if (transition == null) {
            throw new IllegalStateException("Transition not found: " + transitionId);
        }

        mMANativeHelper.setGeneratePreview(true);

        /**
         *  Remove the transition references
         */
        final MediaItem afterMediaItem = transition.getAfterMediaItem();
        if (afterMediaItem != null) {
            afterMediaItem.setEndTransition(null);
        }

        final MediaItem beforeMediaItem = transition.getBeforeMediaItem();
        if (beforeMediaItem != null) {
            beforeMediaItem.setBeginTransition(null);
        }

        mTransitions.remove(transition);
        transition.invalidate();
        computeTimelineDuration();
        return transition;
    }

    /*
     * {@inheritDoc}
     */
    public long renderPreviewFrame(SurfaceHolder surfaceHolder, long timeMs,
                                    OverlayData overlayData) {
        if (surfaceHolder == null) {
            throw new IllegalArgumentException("Surface Holder is null");
        }

        final Surface surface = surfaceHolder.getSurface();
        if (surface == null) {
            throw new IllegalArgumentException("Surface could not be retrieved from Surface holder");
        }

        if (surface.isValid() == false) {
            throw new IllegalStateException("Surface is not valid");
        }

        if (timeMs < 0) {
            throw new IllegalArgumentException("requested time not correct");
        } else if (timeMs > mDurationMs) {
            throw new IllegalArgumentException("requested time more than duration");
        }
        long result = 0;

        boolean semAcquireDone = false;
        try {
            semAcquireDone = lock(ENGINE_ACCESS_MAX_TIMEOUT_MS);
            if (semAcquireDone == false) {
                throw new IllegalStateException("Timeout waiting for semaphore");
            }

            if (mMANativeHelper == null) {
                throw new IllegalStateException("The video editor is not initialized");
            }

            if (mMediaItems.size() > 0) {
                final Rect frame = surfaceHolder.getSurfaceFrame();
                result = mMANativeHelper.renderPreviewFrame(surface,
                        timeMs, frame.width(), frame.height(), overlayData);
            } else {
                result = 0;
            }
        } catch (InterruptedException ex) {
            Log.w(TAG, "The thread was interrupted", new Throwable());
            throw new IllegalStateException("The thread was interrupted");
        } finally {
            if (semAcquireDone) {
                unlock();
            }
        }
        return result;
    }

    /**
     *  the project form XML
     */
    private void load() throws FileNotFoundException, XmlPullParserException, IOException {
        final File file = new File(mProjectPath, PROJECT_FILENAME);
        /**
         *  Load the metadata
         */
        final FileInputStream fis = new FileInputStream(file);
        try {
            final List<String> ignoredMediaItems = new ArrayList<String>();

            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, "UTF-8");
            int eventType = parser.getEventType();
            String name;
            MediaItem currentMediaItem = null;
            Overlay currentOverlay = null;
            boolean regenerateProjectThumbnail = false;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG: {
                        name = parser.getName();
                        if (TAG_PROJECT.equals(name)) {
                            mAspectRatio = Integer.parseInt(parser.getAttributeValue("",
                                   ATTR_ASPECT_RATIO));

                            final boolean mRegenPCM =
                                Boolean.parseBoolean(parser.getAttributeValue("",
                                    ATTR_REGENERATE_PCM));
                            mMANativeHelper.setAudioflag(mRegenPCM);
                        } else if (TAG_MEDIA_ITEM.equals(name)) {
                            final String mediaItemId = parser.getAttributeValue("", ATTR_ID);
                            try {
                                currentMediaItem = parseMediaItem(parser);
                                mMediaItems.add(currentMediaItem);
                            } catch (Exception ex) {
                                Log.w(TAG, "Cannot load media item: " + mediaItemId, ex);
                                currentMediaItem = null;

                                // First media item is invalid, mark for project thumbnail removal
                                if (mMediaItems.size() == 0) {
                                    regenerateProjectThumbnail = true;
                                }
                                // Ignore the media item
                                ignoredMediaItems.add(mediaItemId);
                            }
                        } else if (TAG_TRANSITION.equals(name)) {
                            try {
                                final Transition transition = parseTransition(parser,
                                        ignoredMediaItems);
                                // The transition will be null if the bounding
                                // media items are ignored
                                if (transition != null) {
                                    mTransitions.add(transition);
                                }
                            } catch (Exception ex) {
                                Log.w(TAG, "Cannot load transition", ex);
                            }
                        } else if (TAG_OVERLAY.equals(name)) {
                            if (currentMediaItem != null) {
                                try {
                                    currentOverlay = parseOverlay(parser, currentMediaItem);
                                    currentMediaItem.addOverlay(currentOverlay);
                                } catch (Exception ex) {
                                    Log.w(TAG, "Cannot load overlay", ex);
                                }
                            }
                        } else if (TAG_OVERLAY_USER_ATTRIBUTES.equals(name)) {
                            if (currentOverlay != null) {
                                final int attributesCount = parser.getAttributeCount();
                                for (int i = 0; i < attributesCount; i++) {
                                    currentOverlay.setUserAttribute(parser.getAttributeName(i),
                                            parser.getAttributeValue(i));
                                }
                            }
                        } else if (TAG_EFFECT.equals(name)) {
                            if (currentMediaItem != null) {
                                try {
                                    final Effect effect = parseEffect(parser, currentMediaItem);
                                    currentMediaItem.addEffect(effect);

                                    if (effect instanceof EffectKenBurns) {
                                        final boolean isImageClipGenerated =
                                               Boolean.parseBoolean(parser.getAttributeValue("",
                                                                  ATTR_IS_IMAGE_CLIP_GENERATED));
                                        if(isImageClipGenerated) {
                                            final String filename = parser.getAttributeValue("",
                                                                  ATTR_GENERATED_IMAGE_CLIP);
                                            if (new File(filename).exists() == true) {
                                                ((MediaImageItem)currentMediaItem).
                                                            setGeneratedImageClip(filename);
                                                ((MediaImageItem)currentMediaItem).
                                                             setRegenerateClip(false);
                                             } else {
                                               ((MediaImageItem)currentMediaItem).
                                                             setGeneratedImageClip(null);
                                               ((MediaImageItem)currentMediaItem).
                                                             setRegenerateClip(true);
                                             }
                                        } else {
                                            ((MediaImageItem)currentMediaItem).
                                                             setGeneratedImageClip(null);
                                            ((MediaImageItem)currentMediaItem).
                                                            setRegenerateClip(true);
                                        }
                                    }
                                } catch (Exception ex) {
                                    Log.w(TAG, "Cannot load effect", ex);
                                }
                            }
                        } else if (TAG_AUDIO_TRACK.equals(name)) {
                            try {
                                final AudioTrack audioTrack = parseAudioTrack(parser);
                                addAudioTrack(audioTrack);
                            } catch (Exception ex) {
                                Log.w(TAG, "Cannot load audio track", ex);
                            }
                        }
                        break;
                    }

                    case XmlPullParser.END_TAG: {
                        name = parser.getName();
                        if (TAG_MEDIA_ITEM.equals(name)) {
                            currentMediaItem = null;
                        } else if (TAG_OVERLAY.equals(name)) {
                            currentOverlay = null;
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
            // Regenerate project thumbnail
            if (regenerateProjectThumbnail) {
                generateProjectThumbnail();
                regenerateProjectThumbnail = false;
            }
        } finally {
            if (fis != null) {
                fis.close();
            }
        }
    }

    /**
     * Parse the media item
     *
     * @param parser The parser
     * @return The media item
     */
    private MediaItem parseMediaItem(XmlPullParser parser) throws IOException {
        final String mediaItemId = parser.getAttributeValue("", ATTR_ID);
        final String type = parser.getAttributeValue("", ATTR_TYPE);
        final String filename = parser.getAttributeValue("", ATTR_FILENAME);
        final int renderingMode = Integer.parseInt(parser.getAttributeValue("",
                ATTR_RENDERING_MODE));

        final MediaItem currentMediaItem;
        if (MediaImageItem.class.getSimpleName().equals(type)) {
            final long durationMs = Long.parseLong(parser.getAttributeValue("", ATTR_DURATION));
            currentMediaItem = new MediaImageItem(this, mediaItemId, filename,
                    durationMs, renderingMode);
        } else if (MediaVideoItem.class.getSimpleName().equals(type)) {
            final long beginMs = Long.parseLong(parser.getAttributeValue("", ATTR_BEGIN_TIME));
            final long endMs = Long.parseLong(parser.getAttributeValue("", ATTR_END_TIME));
            final int volume = Integer.parseInt(parser.getAttributeValue("", ATTR_VOLUME));
            final boolean muted = Boolean.parseBoolean(parser.getAttributeValue("", ATTR_MUTED));
            final String audioWaveformFilename = parser.getAttributeValue("",
                    ATTR_AUDIO_WAVEFORM_FILENAME);
            currentMediaItem = new MediaVideoItem(this, mediaItemId, filename,
                    renderingMode, beginMs, endMs, volume, muted, audioWaveformFilename);

            final long beginTimeMs = Long.parseLong(parser.getAttributeValue("", ATTR_BEGIN_TIME));
            final long endTimeMs = Long.parseLong(parser.getAttributeValue("", ATTR_END_TIME));
            ((MediaVideoItem)currentMediaItem).setExtractBoundaries(beginTimeMs, endTimeMs);

            final int volumePercent = Integer.parseInt(parser.getAttributeValue("", ATTR_VOLUME));
            ((MediaVideoItem)currentMediaItem).setVolume(volumePercent);
        } else {
            throw new IllegalArgumentException("Unknown media item type: " + type);
        }

        return currentMediaItem;
    }

    /**
     * Parse the transition
     *
     * @param parser The parser
     * @param ignoredMediaItems The list of ignored media items
     *
     * @return The transition
     */
    private Transition parseTransition(XmlPullParser parser, List<String> ignoredMediaItems) {
        final String transitionId = parser.getAttributeValue("", ATTR_ID);
        final String type = parser.getAttributeValue("", ATTR_TYPE);
        final long durationMs = Long.parseLong(parser.getAttributeValue("", ATTR_DURATION));
        final int behavior = Integer.parseInt(parser.getAttributeValue("", ATTR_BEHAVIOR));

        final String beforeMediaItemId = parser.getAttributeValue("", ATTR_BEFORE_MEDIA_ITEM_ID);
        final MediaItem beforeMediaItem;
        if (beforeMediaItemId != null) {
            if (ignoredMediaItems.contains(beforeMediaItemId)) {
                // This transition is ignored
                return null;
            }

            beforeMediaItem = getMediaItem(beforeMediaItemId);
        } else {
            beforeMediaItem = null;
        }

        final String afterMediaItemId = parser.getAttributeValue("", ATTR_AFTER_MEDIA_ITEM_ID);
        final MediaItem afterMediaItem;
        if (afterMediaItemId != null) {
            if (ignoredMediaItems.contains(afterMediaItemId)) {
                // This transition is ignored
                return null;
            }

            afterMediaItem = getMediaItem(afterMediaItemId);
        } else {
            afterMediaItem = null;
        }

        final Transition transition;
        if (TransitionAlpha.class.getSimpleName().equals(type)) {
            final int blending = Integer.parseInt(parser.getAttributeValue("", ATTR_BLENDING));
            final String maskFilename = parser.getAttributeValue("", ATTR_MASK);
            final boolean invert = Boolean.getBoolean(parser.getAttributeValue("", ATTR_INVERT));
            transition = new TransitionAlpha(transitionId, afterMediaItem, beforeMediaItem,
                    durationMs, behavior, maskFilename, blending, invert);
        } else if (TransitionCrossfade.class.getSimpleName().equals(type)) {
            transition = new TransitionCrossfade(transitionId, afterMediaItem, beforeMediaItem,
                    durationMs, behavior);
        } else if (TransitionSliding.class.getSimpleName().equals(type)) {
            final int direction = Integer.parseInt(parser.getAttributeValue("", ATTR_DIRECTION));
            transition = new TransitionSliding(transitionId, afterMediaItem, beforeMediaItem,
                    durationMs, behavior, direction);
        } else if (TransitionFadeBlack.class.getSimpleName().equals(type)) {
            transition = new TransitionFadeBlack(transitionId, afterMediaItem, beforeMediaItem,
                    durationMs, behavior);
        } else {
            throw new IllegalArgumentException("Invalid transition type: " + type);
        }

        final boolean isTransitionGenerated = Boolean.parseBoolean(parser.getAttributeValue("",
                                                 ATTR_IS_TRANSITION_GENERATED));
        if (isTransitionGenerated == true) {
            final String transitionFile = parser.getAttributeValue("",
                                                ATTR_GENERATED_TRANSITION_CLIP);

            if (new File(transitionFile).exists()) {
                transition.setFilename(transitionFile);
            } else {
                transition.setFilename(null);
            }
        }

        // Use the transition
        if (beforeMediaItem != null) {
            beforeMediaItem.setBeginTransition(transition);
        }

        if (afterMediaItem != null) {
            afterMediaItem.setEndTransition(transition);
        }

        return transition;
    }

    /**
     * Parse the overlay
     *
     * @param parser The parser
     * @param mediaItem The media item owner
     *
     * @return The overlay
     */
    private Overlay parseOverlay(XmlPullParser parser, MediaItem mediaItem) {
        final String overlayId = parser.getAttributeValue("", ATTR_ID);
        final String type = parser.getAttributeValue("", ATTR_TYPE);
        final long durationMs = Long.parseLong(parser.getAttributeValue("", ATTR_DURATION));
        final long startTimeMs = Long.parseLong(parser.getAttributeValue("", ATTR_BEGIN_TIME));

        final Overlay overlay;
        if (OverlayFrame.class.getSimpleName().equals(type)) {
            final String filename = parser.getAttributeValue("", ATTR_FILENAME);
            overlay = new OverlayFrame(mediaItem, overlayId, filename, startTimeMs, durationMs);
        } else {
            throw new IllegalArgumentException("Invalid overlay type: " + type);
        }

        final String overlayRgbFileName = parser.getAttributeValue("", ATTR_OVERLAY_RGB_FILENAME);
        if (overlayRgbFileName != null) {
            ((OverlayFrame)overlay).setFilename(overlayRgbFileName);

            final int overlayFrameWidth = Integer.parseInt(parser.getAttributeValue("",
                                   ATTR_OVERLAY_FRAME_WIDTH));
            final int overlayFrameHeight = Integer.parseInt(parser.getAttributeValue("",
                                   ATTR_OVERLAY_FRAME_HEIGHT));

            ((OverlayFrame)overlay).setOverlayFrameWidth(overlayFrameWidth);
            ((OverlayFrame)overlay).setOverlayFrameHeight(overlayFrameHeight);

            final int resizedRGBFrameWidth = Integer.parseInt(parser.getAttributeValue("",
                                   ATTR_OVERLAY_RESIZED_RGB_FRAME_WIDTH));
            final int resizedRGBFrameHeight = Integer.parseInt(parser.getAttributeValue("",
                                   ATTR_OVERLAY_RESIZED_RGB_FRAME_HEIGHT));

            ((OverlayFrame)overlay).setResizedRGBSize(resizedRGBFrameWidth, resizedRGBFrameHeight);
        }

        return overlay;
    }

    /**
     * Parse the effect
     *
     * @param parser The parser
     * @param mediaItem The media item owner
     *
     * @return The effect
     */
    private Effect parseEffect(XmlPullParser parser, MediaItem mediaItem) {
        final String effectId = parser.getAttributeValue("", ATTR_ID);
        final String type = parser.getAttributeValue("", ATTR_TYPE);
        final long durationMs = Long.parseLong(parser.getAttributeValue("", ATTR_DURATION));
        final long startTimeMs = Long.parseLong(parser.getAttributeValue("", ATTR_BEGIN_TIME));

        final Effect effect;
        if (EffectColor.class.getSimpleName().equals(type)) {
            final int colorEffectType = Integer.parseInt(parser.getAttributeValue("",
                                                       ATTR_COLOR_EFFECT_TYPE));
            final int color;
            if (colorEffectType == EffectColor.TYPE_COLOR
                    || colorEffectType == EffectColor.TYPE_GRADIENT) {
                color = Integer.parseInt(parser.getAttributeValue("", ATTR_COLOR_EFFECT_VALUE));
            } else {
                color = 0;
            }
            effect = new EffectColor(mediaItem, effectId, startTimeMs,
                    durationMs, colorEffectType, color);
        } else if (EffectKenBurns.class.getSimpleName().equals(type)) {
            final Rect startRect = new Rect(
                    Integer.parseInt(parser.getAttributeValue("", ATTR_START_RECT_LEFT)),
                    Integer.parseInt(parser.getAttributeValue("", ATTR_START_RECT_TOP)),
                    Integer.parseInt(parser.getAttributeValue("", ATTR_START_RECT_RIGHT)),
                    Integer.parseInt(parser.getAttributeValue("", ATTR_START_RECT_BOTTOM)));
            final Rect endRect = new Rect(
                    Integer.parseInt(parser.getAttributeValue("", ATTR_END_RECT_LEFT)),
                    Integer.parseInt(parser.getAttributeValue("", ATTR_END_RECT_TOP)),
                    Integer.parseInt(parser.getAttributeValue("", ATTR_END_RECT_RIGHT)),
                    Integer.parseInt(parser.getAttributeValue("", ATTR_END_RECT_BOTTOM)));
            effect = new EffectKenBurns(mediaItem, effectId, startRect, endRect,
                                        startTimeMs, durationMs);
        } else {
            throw new IllegalArgumentException("Invalid effect type: " + type);
        }

        return effect;
    }

    /**
     * Parse the audio track
     *
     * @param parser The parser
     *
     * @return The audio track
     */
    private AudioTrack parseAudioTrack(XmlPullParser parser) throws IOException {
        final String audioTrackId = parser.getAttributeValue("", ATTR_ID);
        final String filename = parser.getAttributeValue("", ATTR_FILENAME);
        final long startTimeMs = Long.parseLong(parser.getAttributeValue("", ATTR_START_TIME));
        final long beginMs = Long.parseLong(parser.getAttributeValue("", ATTR_BEGIN_TIME));
        final long endMs = Long.parseLong(parser.getAttributeValue("", ATTR_END_TIME));
        final int volume = Integer.parseInt(parser.getAttributeValue("", ATTR_VOLUME));
        final boolean muted = Boolean.parseBoolean(parser.getAttributeValue("", ATTR_MUTED));
        final boolean loop = Boolean.parseBoolean(parser.getAttributeValue("", ATTR_LOOP));
        final boolean duckingEnabled = Boolean.parseBoolean(
                parser.getAttributeValue("", ATTR_DUCK_ENABLED));
        final int duckThreshold = Integer.parseInt(
                parser.getAttributeValue("", ATTR_DUCK_THRESHOLD));
        final int duckedTrackVolume = Integer.parseInt(parser.getAttributeValue("",
                                                     ATTR_DUCKED_TRACK_VOLUME));

        final String waveformFilename = parser.getAttributeValue("", ATTR_AUDIO_WAVEFORM_FILENAME);
        final AudioTrack audioTrack = new AudioTrack(this, audioTrackId,
                                                     filename, startTimeMs,
                                                     beginMs, endMs, loop,
                                                     volume, muted,
                                                     duckingEnabled,
                                                     duckThreshold,
                                                     duckedTrackVolume,
                                                     waveformFilename);

        return audioTrack;
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
        serializer.attribute("",
                             ATTR_ASPECT_RATIO, Integer.toString(mAspectRatio));

        serializer.attribute("", ATTR_REGENERATE_PCM,
                        Boolean.toString(mMANativeHelper.getAudioflag()));

        serializer.startTag("", TAG_MEDIA_ITEMS);
        for (MediaItem mediaItem : mMediaItems) {
            serializer.startTag("", TAG_MEDIA_ITEM);
            serializer.attribute("", ATTR_ID, mediaItem.getId());
            serializer.attribute("", ATTR_TYPE,
                                          mediaItem.getClass().getSimpleName());
            serializer.attribute("", ATTR_FILENAME, mediaItem.getFilename());
            serializer.attribute("", ATTR_RENDERING_MODE, Integer.toString(
                    mediaItem.getRenderingMode()));
            if (mediaItem instanceof MediaVideoItem) {
                final MediaVideoItem mvi = (MediaVideoItem)mediaItem;
                serializer
                .attribute("", ATTR_BEGIN_TIME,
                                     Long.toString(mvi.getBoundaryBeginTime()));
                serializer.attribute("", ATTR_END_TIME,
                                       Long.toString(mvi.getBoundaryEndTime()));
                serializer.attribute("", ATTR_VOLUME,
                                             Integer.toString(mvi.getVolume()));
                serializer.attribute("", ATTR_MUTED,
                                               Boolean.toString(mvi.isMuted()));
                if (mvi.getAudioWaveformFilename() != null) {
                    serializer.attribute("", ATTR_AUDIO_WAVEFORM_FILENAME,
                            mvi.getAudioWaveformFilename());
                }
            } else if (mediaItem instanceof MediaImageItem) {
                serializer.attribute("", ATTR_DURATION,
                        Long.toString(mediaItem.getTimelineDuration()));
            }

            final List<Overlay> overlays = mediaItem.getAllOverlays();
            if (overlays.size() > 0) {
                serializer.startTag("", TAG_OVERLAYS);
                for (Overlay overlay : overlays) {
                    serializer.startTag("", TAG_OVERLAY);
                    serializer.attribute("", ATTR_ID, overlay.getId());
                    serializer.attribute("",
                                 ATTR_TYPE, overlay.getClass().getSimpleName());
                    serializer.attribute("", ATTR_BEGIN_TIME,
                                         Long.toString(overlay.getStartTime()));
                    serializer.attribute("", ATTR_DURATION,
                                          Long.toString(overlay.getDuration()));
                    if (overlay instanceof OverlayFrame) {
                        final OverlayFrame overlayFrame = (OverlayFrame)overlay;
                        overlayFrame.save(getPath());
                        if (overlayFrame.getBitmapImageFileName() != null) {
                            serializer.attribute("", ATTR_FILENAME,
                                         overlayFrame.getBitmapImageFileName());
                        }

                        if (overlayFrame.getFilename() != null) {
                            serializer.attribute("",
                                                 ATTR_OVERLAY_RGB_FILENAME,
                                                 overlayFrame.getFilename());
                            serializer.attribute("", ATTR_OVERLAY_FRAME_WIDTH,
                                                 Integer.toString(overlayFrame.getOverlayFrameWidth()));
                            serializer.attribute("", ATTR_OVERLAY_FRAME_HEIGHT,
                                                 Integer.toString(overlayFrame.getOverlayFrameHeight()));
                            serializer.attribute("", ATTR_OVERLAY_RESIZED_RGB_FRAME_WIDTH,
                                                 Integer.toString(overlayFrame.getResizedRGBSizeWidth()));
                            serializer.attribute("", ATTR_OVERLAY_RESIZED_RGB_FRAME_HEIGHT,
                                                 Integer.toString(overlayFrame.getResizedRGBSizeHeight()));

                        }

                    }

                    /**
                     *  Save the user attributes
                     */
                    serializer.startTag("", TAG_OVERLAY_USER_ATTRIBUTES);
                    final Map<String, String> userAttributes = overlay.getUserAttributes();
                    for (String name : userAttributes.keySet()) {
                        final String value = userAttributes.get(name);
                        if (value != null) {
                            serializer.attribute("", name, value);
                        }
                    }
                    serializer.endTag("", TAG_OVERLAY_USER_ATTRIBUTES);

                    serializer.endTag("", TAG_OVERLAY);
                }
                serializer.endTag("", TAG_OVERLAYS);
            }

            final List<Effect> effects = mediaItem.getAllEffects();
            if (effects.size() > 0) {
                serializer.startTag("", TAG_EFFECTS);
                for (Effect effect : effects) {
                    serializer.startTag("", TAG_EFFECT);
                    serializer.attribute("", ATTR_ID, effect.getId());
                    serializer.attribute("",
                                  ATTR_TYPE, effect.getClass().getSimpleName());
                    serializer.attribute("", ATTR_BEGIN_TIME,
                            Long.toString(effect.getStartTime()));
                    serializer.attribute("", ATTR_DURATION,
                                           Long.toString(effect.getDuration()));
                    if (effect instanceof EffectColor) {
                        final EffectColor colorEffect = (EffectColor)effect;
                        serializer.attribute("", ATTR_COLOR_EFFECT_TYPE,
                                Integer.toString(colorEffect.getType()));
                        if (colorEffect.getType() == EffectColor.TYPE_COLOR ||
                                colorEffect.getType() == EffectColor.TYPE_GRADIENT) {
                            serializer.attribute("", ATTR_COLOR_EFFECT_VALUE,
                                    Integer.toString(colorEffect.getColor()));
                        }
                    } else if (effect instanceof EffectKenBurns) {
                        final Rect startRect = ((EffectKenBurns)effect).getStartRect();
                        serializer.attribute("", ATTR_START_RECT_LEFT,
                                Integer.toString(startRect.left));
                        serializer.attribute("", ATTR_START_RECT_TOP,
                                Integer.toString(startRect.top));
                        serializer.attribute("", ATTR_START_RECT_RIGHT,
                                Integer.toString(startRect.right));
                        serializer.attribute("", ATTR_START_RECT_BOTTOM,
                                Integer.toString(startRect.bottom));

                        final Rect endRect = ((EffectKenBurns)effect).getEndRect();
                        serializer.attribute("", ATTR_END_RECT_LEFT,
                                                Integer.toString(endRect.left));
                        serializer.attribute("", ATTR_END_RECT_TOP,
                                                 Integer.toString(endRect.top));
                        serializer.attribute("", ATTR_END_RECT_RIGHT,
                                               Integer.toString(endRect.right));
                        serializer.attribute("", ATTR_END_RECT_BOTTOM,
                                Integer.toString(endRect.bottom));
                        final MediaItem mItem = effect.getMediaItem();
                           if(((MediaImageItem)mItem).getGeneratedImageClip() != null) {
                               serializer.attribute("", ATTR_IS_IMAGE_CLIP_GENERATED,
                                       Boolean.toString(true));
                               serializer.attribute("", ATTR_GENERATED_IMAGE_CLIP,
                                     ((MediaImageItem)mItem).getGeneratedImageClip());
                            } else {
                                serializer.attribute("", ATTR_IS_IMAGE_CLIP_GENERATED,
                                     Boolean.toString(false));
                         }
                    }

                    serializer.endTag("", TAG_EFFECT);
                }
                serializer.endTag("", TAG_EFFECTS);
            }

            serializer.endTag("", TAG_MEDIA_ITEM);
        }
        serializer.endTag("", TAG_MEDIA_ITEMS);

        serializer.startTag("", TAG_TRANSITIONS);

        for (Transition transition : mTransitions) {
            serializer.startTag("", TAG_TRANSITION);
            serializer.attribute("", ATTR_ID, transition.getId());
            serializer.attribute("", ATTR_TYPE, transition.getClass().getSimpleName());
            serializer.attribute("", ATTR_DURATION, Long.toString(transition.getDuration()));
            serializer.attribute("", ATTR_BEHAVIOR, Integer.toString(transition.getBehavior()));
            serializer.attribute("", ATTR_IS_TRANSITION_GENERATED,
                                    Boolean.toString(transition.isGenerated()));
            if (transition.isGenerated() == true) {
                serializer.attribute("", ATTR_GENERATED_TRANSITION_CLIP, transition.mFilename);
            }
            final MediaItem afterMediaItem = transition.getAfterMediaItem();
            if (afterMediaItem != null) {
                serializer.attribute("", ATTR_AFTER_MEDIA_ITEM_ID, afterMediaItem.getId());
            }

            final MediaItem beforeMediaItem = transition.getBeforeMediaItem();
            if (beforeMediaItem != null) {
                serializer.attribute("", ATTR_BEFORE_MEDIA_ITEM_ID, beforeMediaItem.getId());
            }

            if (transition instanceof TransitionSliding) {
                serializer.attribute("", ATTR_DIRECTION,
                        Integer.toString(((TransitionSliding)transition).getDirection()));
            } else if (transition instanceof TransitionAlpha) {
                TransitionAlpha ta = (TransitionAlpha)transition;
                serializer.attribute("", ATTR_BLENDING,
                                     Integer.toString(ta.getBlendingPercent()));
                serializer.attribute("", ATTR_INVERT,
                                               Boolean.toString(ta.isInvert()));
                if (ta.getMaskFilename() != null) {
                    serializer.attribute("", ATTR_MASK, ta.getMaskFilename());
                }
            }
            serializer.endTag("", TAG_TRANSITION);
        }
        serializer.endTag("", TAG_TRANSITIONS);
        serializer.startTag("", TAG_AUDIO_TRACKS);
        for (AudioTrack at : mAudioTracks) {
            serializer.startTag("", TAG_AUDIO_TRACK);
            serializer.attribute("", ATTR_ID, at.getId());
            serializer.attribute("", ATTR_FILENAME, at.getFilename());
            serializer.attribute("", ATTR_START_TIME, Long.toString(at.getStartTime()));
            serializer.attribute("", ATTR_BEGIN_TIME, Long.toString(at.getBoundaryBeginTime()));
            serializer.attribute("", ATTR_END_TIME, Long.toString(at.getBoundaryEndTime()));
            serializer.attribute("", ATTR_VOLUME, Integer.toString(at.getVolume()));
            serializer.attribute("", ATTR_DUCK_ENABLED,
                                       Boolean.toString(at.isDuckingEnabled()));
            serializer.attribute("", ATTR_DUCKED_TRACK_VOLUME,
                                   Integer.toString(at.getDuckedTrackVolume()));
            serializer.attribute("", ATTR_DUCK_THRESHOLD,
                                   Integer.toString(at.getDuckingThreshhold()));
            serializer.attribute("", ATTR_MUTED, Boolean.toString(at.isMuted()));
            serializer.attribute("", ATTR_LOOP, Boolean.toString(at.isLooping()));
            if (at.getAudioWaveformFilename() != null) {
                serializer.attribute("", ATTR_AUDIO_WAVEFORM_FILENAME,
                        at.getAudioWaveformFilename());
            }

            serializer.endTag("", TAG_AUDIO_TRACK);
        }
        serializer.endTag("", TAG_AUDIO_TRACKS);

        serializer.endTag("", TAG_PROJECT);
        serializer.endDocument();

        /**
         *  Save the metadata XML file
         */
        final FileOutputStream out = new FileOutputStream(new File(getPath(),
                                                          PROJECT_FILENAME));
        out.write(writer.toString().getBytes());
        out.flush();
        out.close();
    }

    /*
     * {@inheritDoc}
     */
    public void setAspectRatio(int aspectRatio) {
        mAspectRatio = aspectRatio;
        /**
         *  Invalidate all transitions
         */
        mMANativeHelper.setGeneratePreview(true);

        for (Transition transition : mTransitions) {
            transition.invalidate();
        }

        final Iterator<MediaItem> it = mMediaItems.iterator();

        while (it.hasNext()) {
            final MediaItem t = it.next();
            List<Overlay> overlayList = t.getAllOverlays();
            for (Overlay overlay : overlayList) {

                ((OverlayFrame)overlay).invalidateGeneratedFiles();
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    public void startPreview(SurfaceHolder surfaceHolder, long fromMs, long toMs,
                             boolean loop, int callbackAfterFrameCount,
                             PreviewProgressListener listener) {

        if (surfaceHolder == null) {
            throw new IllegalArgumentException();
        }

        final Surface surface = surfaceHolder.getSurface();
        if (surface == null) {
            throw new IllegalArgumentException("Surface could not be retrieved from surface holder");
        }

        if (surface.isValid() == false) {
            throw new IllegalStateException("Surface is not valid");
        }

        if (listener == null) {
            throw new IllegalArgumentException();
        }

        if (fromMs >= mDurationMs) {
            throw new IllegalArgumentException("Requested time not correct");
        }

        if (fromMs < 0) {
            throw new IllegalArgumentException("Requested time not correct");
        }

        boolean semAcquireDone = false;
        if (!mPreviewInProgress) {
            try{
                semAcquireDone = lock(ENGINE_ACCESS_MAX_TIMEOUT_MS);
                if (semAcquireDone == false) {
                    throw new IllegalStateException("Timeout waiting for semaphore");
                }

                if (mMANativeHelper == null) {
                    throw new IllegalStateException("The video editor is not initialized");
                }

                if (mMediaItems.size() > 0) {
                    mPreviewInProgress = true;
                    mMANativeHelper.previewStoryBoard(mMediaItems, mTransitions,
                                                      mAudioTracks, null);
                    mMANativeHelper.doPreview(surface, fromMs, toMs, loop,
                                     callbackAfterFrameCount, listener);
                }
                /**
                 *  Release The lock on complete by calling stopPreview
                 */
            } catch (InterruptedException ex) {
                Log.w(TAG, "The thread was interrupted", new Throwable());
                throw new IllegalStateException("The thread was interrupted");
            }
         } else {
            throw new IllegalStateException("Preview already in progress");
        }
    }

    /*
     * {@inheritDoc}
     */
    public long stopPreview() {
        long result = 0;
        if (mPreviewInProgress) {
            try {
                result = mMANativeHelper.stopPreview();
                /**
                 *  release on complete by calling stopPreview
                 */
                } finally {
                    mPreviewInProgress = false;
                    unlock();
                }
            return result;
        }
        else {
            return 0;
        }
    }

    /*
     * Remove transitions associated with the specified media item
     *
     * @param mediaItem The media item
     */
    private void removeAdjacentTransitions(MediaItem mediaItem) {
        final Transition beginTransition = mediaItem.getBeginTransition();
        if (beginTransition != null) {
            if (beginTransition.getAfterMediaItem() != null) {
                beginTransition.getAfterMediaItem().setEndTransition(null);
            }
            beginTransition.invalidate();
            mTransitions.remove(beginTransition);
        }

        final Transition endTransition = mediaItem.getEndTransition();
        if (endTransition != null) {
            if (endTransition.getBeforeMediaItem() != null) {
                endTransition.getBeforeMediaItem().setBeginTransition(null);
            }
            endTransition.invalidate();
            mTransitions.remove(endTransition);
        }

        mediaItem.setBeginTransition(null);
        mediaItem.setEndTransition(null);
    }

    /**
     * Remove the transition before this media item
     *
     * @param index The media item index
     */
    private void removeTransitionBefore(int index) {
        final MediaItem mediaItem = mMediaItems.get(index);
        final Iterator<Transition> it = mTransitions.iterator();
        while (it.hasNext()) {
            Transition t = it.next();
            if (t.getBeforeMediaItem() == mediaItem) {
                mMANativeHelper.setGeneratePreview(true);
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
     * @param mediaItem The media item
     */
    private void removeTransitionAfter(int index) {
        final MediaItem mediaItem = mMediaItems.get(index);
        final Iterator<Transition> it = mTransitions.iterator();
        while (it.hasNext()) {
            Transition t = it.next();
            if (t.getAfterMediaItem() == mediaItem) {
                mMANativeHelper.setGeneratePreview(true);
                it.remove();
                t.invalidate();
                mediaItem.setEndTransition(null);
                /**
                 *  Invalidate the reference in the next media item
                 */
                if (index < mMediaItems.size() - 1) {
                    mMediaItems.get(index + 1).setBeginTransition(null);
                }
                break;
            }
        }
    }

    /**
     * Compute the duration
     */
    private void computeTimelineDuration() {
        mDurationMs = 0;
        final int mediaItemsCount = mMediaItems.size();
        for (int i = 0; i < mediaItemsCount; i++) {
            final MediaItem mediaItem = mMediaItems.get(i);
            mDurationMs += mediaItem.getTimelineDuration();
            if (mediaItem.getEndTransition() != null) {
                if (i < mediaItemsCount - 1) {
                    mDurationMs -= mediaItem.getEndTransition().getDuration();
                }
            }
        }
    }

    /*
     * Generate the project thumbnail
     */
    private void generateProjectThumbnail() {
        /*
         * If a thumbnail already exists, then delete it first
         */
        if ((new File(mProjectPath + "/" + THUMBNAIL_FILENAME)).exists()) {
            (new File(mProjectPath + "/" + THUMBNAIL_FILENAME)).delete();
        }
        /*
         * Generate a new thumbnail for the project from first media Item
         */
        if (mMediaItems.size() > 0) {
            MediaItem mI = mMediaItems.get(0);
            /*
             * Keep aspect ratio of the image
             */
            int height = 480;
            int width = mI.getWidth() * height / mI.getHeight();

            Bitmap projectBitmap = null;
            String filename = mI.getFilename();
            if (mI instanceof MediaVideoItem) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                Bitmap bitmap = null;
                try {
                    retriever.setDataSource(filename);
                    bitmap = retriever.getFrameAtTime();
                } catch (RuntimeException ex) {
                    // Ignore failures while cleaning up.
                } finally {
                    try {
                        retriever.release();
                    } catch (RuntimeException ex) {
                        // Ignore failures while cleaning up.
                    }
                }

                if (bitmap == null) {
                    String msg = "Thumbnail extraction from " +
                                    filename + " failed";
                    throw new IllegalArgumentException(msg);
                }
                // Resize the thumbnail to the target size
                projectBitmap =
                    Bitmap.createScaledBitmap(bitmap, width, height, true);
            } else {
                try {
                    projectBitmap = mI.getThumbnail(width, height, 500);
                } catch (IllegalArgumentException e) {
                    String msg = "Project thumbnail extraction from " +
                                    filename + " failed";
                    throw new IllegalArgumentException(msg);
                } catch (IOException e) {
                    String msg = "IO Error creating project thumbnail";
                    throw new IllegalArgumentException(msg);
                }
            }

            try {
                FileOutputStream stream = new FileOutputStream(mProjectPath + "/"
                                                          + THUMBNAIL_FILENAME);
                projectBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                stream.flush();
                stream.close();
            } catch (IOException e) {
                throw new IllegalArgumentException ("Error creating project thumbnail");
            } finally {
                projectBitmap.recycle();
            }
        }
    }

    /**
     * Clears the preview surface
     *
     * @param surfaceHolder SurfaceHolder where the preview is rendered
     * and needs to be cleared.
     */
    public void clearSurface(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalArgumentException("Invalid surface holder");
        }

        final Surface surface = surfaceHolder.getSurface();
        if (surface == null) {
            throw new IllegalArgumentException("Surface could not be retrieved from surface holder");
        }

        if (surface.isValid() == false) {
            throw new IllegalStateException("Surface is not valid");
        }

        if (mMANativeHelper != null) {
            mMANativeHelper.clearPreviewSurface(surface);
        } else {
            Log.w(TAG, "Native helper was not ready!");
        }
    }

    /**
     * Grab the semaphore which arbitrates access to the editor
     *
     * @throws InterruptedException
     */
    private void lock() throws InterruptedException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "lock: grabbing semaphore", new Throwable());
        }
        mLock.acquire();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "lock: grabbed semaphore");
        }
    }

    /**
     * Tries to grab the semaphore with a specified time out which arbitrates access to the editor
     *
     * @param timeoutMs time out in ms.
     *
     * @return true if the semaphore is acquired, false otherwise
     * @throws InterruptedException
     */
    private boolean lock(long timeoutMs) throws InterruptedException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "lock: grabbing semaphore with timeout " + timeoutMs, new Throwable());
        }

        boolean acquireSem = mLock.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "lock: grabbed semaphore status " + acquireSem);
        }

        return acquireSem;
    }

    /**
     * Release the semaphore which arbitrates access to the editor
     */
    private void unlock() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "unlock: releasing semaphore");
        }
        mLock.release();
    }

    /**
     * Dumps the heap memory usage information to file
     */
    private static void dumpHeap (String filename) throws Exception {
        /* Cleanup as much as possible before dump
         */
        System.gc();
        System.runFinalization();
        Thread.sleep(1000);
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            String extDir =
             Environment.getExternalStorageDirectory().toString();

            /* If dump file already exists, then delete it first
            */
            if ((new File(extDir + "/" + filename + ".dump")).exists()) {
                (new File(extDir + "/" + filename + ".dump")).delete();
            }
            /* Dump native heap
            */
            FileOutputStream ost =
             new FileOutputStream(extDir + "/" + filename + ".dump");
            Debug.dumpNativeHeap(ost.getFD());
            ost.close();
        }
    }
}
