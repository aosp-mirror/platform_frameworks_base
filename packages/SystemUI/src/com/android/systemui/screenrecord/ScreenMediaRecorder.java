/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Recording screen and mic/internal audio
 */
public class ScreenMediaRecorder extends MediaProjection.Callback {
    private static final int TOTAL_NUM_TRACKS = 1;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 6;
    private static final int AUDIO_BIT_RATE = 196000;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int MAX_DURATION_MS = 60 * 60 * 1000;
    private static final long MAX_FILESIZE_BYTES = 5000000000L;
    private static final String TAG = "ScreenMediaRecorder";


    private File mTempVideoFile;
    private File mTempAudioFile;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private int mUid;
    private ScreenRecordingMuxer mMuxer;
    private ScreenInternalAudioRecorder mAudio;
    private ScreenRecordingAudioSource mAudioSource;
    private final MediaProjectionCaptureTarget mCaptureRegion;
    private final Handler mHandler;

    private Context mContext;
    ScreenMediaRecorderListener mListener;

    public ScreenMediaRecorder(Context context, Handler handler,
            int uid, ScreenRecordingAudioSource audioSource,
            MediaProjectionCaptureTarget captureRegion,
            ScreenMediaRecorderListener listener) {
        mContext = context;
        mHandler = handler;
        mUid = uid;
        mCaptureRegion = captureRegion;
        mListener = listener;
        mAudioSource = audioSource;
    }

    private void prepare() throws IOException, RemoteException, RuntimeException {
        //Setup media projection
        IBinder b = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        IMediaProjectionManager mediaService =
                IMediaProjectionManager.Stub.asInterface(b);
        IMediaProjection proj = null;
        proj = mediaService.createProjection(mUid, mContext.getPackageName(),
                    MediaProjectionManager.TYPE_SCREEN_CAPTURE, false);
        IMediaProjection projection = IMediaProjection.Stub.asInterface(proj.asBinder());
        if (mCaptureRegion != null) {
            projection.setLaunchCookie(mCaptureRegion.getLaunchCookie());
            projection.setTaskId(mCaptureRegion.getTaskId());
        }
        mMediaProjection = new MediaProjection(mContext, projection);
        mMediaProjection.registerCallback(this, mHandler);

        File cacheDir = mContext.getCacheDir();
        cacheDir.mkdirs();
        mTempVideoFile = File.createTempFile("temp", ".mp4", cacheDir);

        // Set up media recorder
        mMediaRecorder = new MediaRecorder();

        // Set up audio source
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);


        // Set up video
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int refreshRate = (int) wm.getDefaultDisplay().getRefreshRate();
        int[] dimens = getSupportedSize(metrics.widthPixels, metrics.heightPixels, refreshRate);
        int width = dimens[0];
        int height = dimens[1];
        refreshRate = dimens[2];
        int vidBitRate = width * height * refreshRate / VIDEO_FRAME_RATE
                * VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO;
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setVideoEncodingProfileLevel(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        mMediaRecorder.setVideoSize(width, height);
        mMediaRecorder.setVideoFrameRate(refreshRate);
        mMediaRecorder.setVideoEncodingBitRate(vidBitRate);
        mMediaRecorder.setMaxDuration(MAX_DURATION_MS);
        mMediaRecorder.setMaxFileSize(MAX_FILESIZE_BYTES);

        // Set up audio
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
            mMediaRecorder.setAudioChannels(TOTAL_NUM_TRACKS);
            mMediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mMediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
        }

        mMediaRecorder.setOutputFile(mTempVideoFile);
        mMediaRecorder.prepare();
        // Create surface
        mInputSurface = mMediaRecorder.getSurface();
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "Recording Display",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface,
                new VirtualDisplay.Callback() {
                    @Override
                    public void onStopped() {
                        onStop();
                    }
                },
                mHandler);

        mMediaRecorder.setOnInfoListener((mr, what, extra) -> mListener.onInfo(mr, what, extra));
        if (mAudioSource == INTERNAL ||
                mAudioSource == MIC_AND_INTERNAL) {
            mTempAudioFile = File.createTempFile("temp", ".aac",
                    mContext.getCacheDir());
            mAudio = new ScreenInternalAudioRecorder(mTempAudioFile.getAbsolutePath(),
                    mMediaProjection, mAudioSource == MIC_AND_INTERNAL);
        }

    }

    /**
     * Find the highest supported screen resolution and refresh rate for the given dimensions on
     * this device, up to actual size and given rate.
     * If possible this will return the same values as given, but values may be smaller on some
     * devices.
     *
     * @param screenWidth Actual pixel width of screen
     * @param screenHeight Actual pixel height of screen
     * @param refreshRate Desired refresh rate
     * @return array with supported width, height, and refresh rate
     */
    private int[] getSupportedSize(final int screenWidth, final int screenHeight, int refreshRate)
            throws IOException {
        String videoType = MediaFormat.MIMETYPE_VIDEO_AVC;

        // Get max size from the decoder, to ensure recordings will be playable on device
        MediaCodec decoder = MediaCodec.createDecoderByType(videoType);
        MediaCodecInfo.VideoCapabilities vc = decoder.getCodecInfo()
                .getCapabilitiesForType(videoType).getVideoCapabilities();
        decoder.release();

        // Check if we can support screen size as-is
        int width = vc.getSupportedWidths().getUpper();
        int height = vc.getSupportedHeights().getUpper();

        int screenWidthAligned = screenWidth;
        if (screenWidthAligned % vc.getWidthAlignment() != 0) {
            screenWidthAligned -= (screenWidthAligned % vc.getWidthAlignment());
        }
        int screenHeightAligned = screenHeight;
        if (screenHeightAligned % vc.getHeightAlignment() != 0) {
            screenHeightAligned -= (screenHeightAligned % vc.getHeightAlignment());
        }

        if (width >= screenWidthAligned && height >= screenHeightAligned
                && vc.isSizeSupported(screenWidthAligned, screenHeightAligned)) {
            // Desired size is supported, now get the rate
            int maxRate = vc.getSupportedFrameRatesFor(screenWidthAligned,
                    screenHeightAligned).getUpper().intValue();

            if (maxRate < refreshRate) {
                refreshRate = maxRate;
            }
            Log.d(TAG, "Screen size supported at rate " + refreshRate);
            return new int[]{screenWidthAligned, screenHeightAligned, refreshRate};
        }

        // Otherwise, resize for max supported size
        double scale = Math.min(((double) width / screenWidth),
                ((double) height / screenHeight));

        int scaledWidth = (int) (screenWidth * scale);
        int scaledHeight = (int) (screenHeight * scale);
        if (scaledWidth % vc.getWidthAlignment() != 0) {
            scaledWidth -= (scaledWidth % vc.getWidthAlignment());
        }
        if (scaledHeight % vc.getHeightAlignment() != 0) {
            scaledHeight -= (scaledHeight % vc.getHeightAlignment());
        }

        // Find max supported rate for size
        int maxRate = vc.getSupportedFrameRatesFor(scaledWidth, scaledHeight)
                .getUpper().intValue();
        if (maxRate < refreshRate) {
            refreshRate = maxRate;
        }

        Log.d(TAG, "Resized by " + scale + ": " + scaledWidth + ", " + scaledHeight
                + ", " + refreshRate);
        return new int[]{scaledWidth, scaledHeight, refreshRate};
    }

    /**
    * Start screen recording
    */
    void start() throws IOException, RemoteException, RuntimeException {
        Log.d(TAG, "start recording");
        prepare();
        mMediaRecorder.start();
        recordInternalAudio();
    }

    /**
     * End screen recording, throws an exception if stopping recording failed
     */
    void end() throws IOException {
        Closer closer = new Closer();

        // MediaRecorder might throw RuntimeException if stopped immediately after starting
        // We should remove the recording in this case as it will be invalid
        closer.register(mMediaRecorder::stop);
        closer.register(mMediaRecorder::release);
        closer.register(mInputSurface::release);
        closer.register(mVirtualDisplay::release);
        closer.register(mMediaProjection::stop);
        closer.register(this::stopInternalAudioRecording);

        closer.close();

        mMediaRecorder = null;
        mMediaProjection = null;

        Log.d(TAG, "end recording");
    }

    @Override
    public void onStop() {
        Log.d(TAG, "The system notified about stopping the projection");
        mListener.onStopped();
    }

    private void stopInternalAudioRecording() {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.end();
            mAudio = null;
        }
    }

    private  void recordInternalAudio() throws IllegalStateException {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.start();
        }
    }

    /**
     * Store recorded video
     */
    protected SavedRecording save() throws IOException, IllegalStateException {
        String fileName = new SimpleDateFormat("'screen-'yyyyMMdd-HHmmss'.mp4'")
                .format(new Date());

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());

        ContentResolver resolver = mContext.getContentResolver();
        Uri collectionUri = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collectionUri, values);

        Log.d(TAG, itemUri.toString());
        if (mAudioSource == MIC_AND_INTERNAL || mAudioSource == INTERNAL) {
            try {
                Log.d(TAG, "muxing recording");
                File file = File.createTempFile("temp", ".mp4",
                        mContext.getCacheDir());
                mMuxer = new ScreenRecordingMuxer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        file.getAbsolutePath(),
                        mTempVideoFile.getAbsolutePath(),
                        mTempAudioFile.getAbsolutePath());
                mMuxer.mux();
                mTempVideoFile.delete();
                mTempVideoFile = file;
            } catch (IOException e) {
                Log.e(TAG, "muxing recording " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Add to the mediastore
        OutputStream os = resolver.openOutputStream(itemUri, "w");
        Files.copy(mTempVideoFile.toPath(), os);
        os.close();
        if (mTempAudioFile != null) mTempAudioFile.delete();
        SavedRecording recording = new SavedRecording(
                itemUri, mTempVideoFile, getRequiredThumbnailSize());
        mTempVideoFile.delete();
        return recording;
    }

    /**
     * Returns the required {@code Size} of the thumbnail.
     */
    private Size getRequiredThumbnailSize() {
        boolean isLowRam = ActivityManager.isLowRamDeviceStatic();
        int thumbnailIconHeight = mContext.getResources().getDimensionPixelSize(isLowRam
                ? R.dimen.notification_big_picture_max_height_low_ram
                : R.dimen.notification_big_picture_max_height);
        int thumbnailIconWidth = mContext.getResources().getDimensionPixelSize(isLowRam
                ? R.dimen.notification_big_picture_max_width_low_ram
                : R.dimen.notification_big_picture_max_width);
        return new Size(thumbnailIconWidth, thumbnailIconHeight);
    }

    /**
     * Release the resources without saving the data
     */
    protected void release() {
        if (mTempVideoFile != null) {
            mTempVideoFile.delete();
        }
        if (mTempAudioFile != null) {
            mTempAudioFile.delete();
        }
    }

    /**
    * Object representing the recording
    */
    public class SavedRecording {

        private Uri mUri;
        private Icon mThumbnailIcon;

        protected SavedRecording(Uri uri, File file, Size thumbnailSize) {
            mUri = uri;
            try {
                Bitmap thumbnailBitmap = ThumbnailUtils.createVideoThumbnail(
                        file, thumbnailSize, null);
                mThumbnailIcon = Icon.createWithBitmap(thumbnailBitmap);
            } catch (IOException e) {
                Log.e(TAG, "Error creating thumbnail", e);
            }
        }

        public Uri getUri() {
            return mUri;
        }

        public @Nullable Icon getThumbnail() {
            return mThumbnailIcon;
        }
    }

    interface ScreenMediaRecorderListener {
        /**
         * Called to indicate an info or a warning during recording.
         * See {@link MediaRecorder.OnInfoListener} for the full description.
         */
        void onInfo(MediaRecorder mr, int what, int extra);

        /**
         * Called when the recording stopped by the system.
         * For example, this might happen when doing partial screen sharing of an app
         * and the app that is being captured is closed.
         */
        void onStopped();
    }

    /**
     * Allows to register multiple {@link Closeable} objects and close them all by calling
     * {@link Closer#close}. If there is an exception thrown during closing of one
     * of the registered closeables it will continue trying closing the rest closeables.
     * If there are one or more exceptions thrown they will be re-thrown at the end.
     * In case of multiple exceptions only the first one will be thrown and all the rest
     * will be printed.
     */
    private static class Closer implements Closeable {
        private final List<Closeable> mCloseables = new ArrayList<>();

        void register(Closeable closeable) {
            mCloseables.add(closeable);
        }

        @Override
        public void close() throws IOException {
            Throwable throwable = null;

            for (int i = 0; i < mCloseables.size(); i++) {
                Closeable closeable = mCloseables.get(i);

                try {
                    closeable.close();
                } catch (Throwable e) {
                    if (throwable == null) {
                        throwable = e;
                    } else {
                        e.printStackTrace();
                    }
                }
            }

            if (throwable != null) {
                if (throwable instanceof IOException) {
                    throw (IOException) throwable;
                }

                if (throwable instanceof RuntimeException) {
                    throw (RuntimeException) throwable;
                }

                throw (Error) throwable;
            }
        }
    }
}
