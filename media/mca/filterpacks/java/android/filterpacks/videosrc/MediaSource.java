/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterpacks.videosrc;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.GenerateFinalPort;
import android.filterfw.core.GLFrame;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.Matrix;
import android.view.Surface;

import java.io.IOException;
import java.lang.IllegalArgumentException;

import android.util.Log;

/**
 * @hide
 */
public class MediaSource extends Filter {

    /** User-visible parameters */

    /** The source URL for the media source. Can be an http: link to a remote
     * resource, or a file: link to a local media file
     */
    @GenerateFieldPort(name = "sourceUrl", hasDefault = true)
    private String mSourceUrl = "";

    /** An open asset file descriptor to a local media source. Default is null */
    @GenerateFieldPort(name = "sourceAsset", hasDefault = true)
    private AssetFileDescriptor mSourceAsset = null;

    /** The context for the MediaPlayer to resolve the sourceUrl.
     * Make sure this is set before the sourceUrl to avoid unexpected result.
     * If the sourceUrl is not a content URI, it is OK to keep this as null. */
    @GenerateFieldPort(name = "context", hasDefault = true)
    private Context mContext = null;

    /** Whether the media source is a URL or an asset file descriptor. Defaults
     * to false.
     */
    @GenerateFieldPort(name = "sourceIsUrl", hasDefault = true)
    private boolean mSelectedIsUrl = false;

    /** Whether the filter will always wait for a new video frame, or whether it
     * will output an old frame again if a new frame isn't available. Defaults
     * to true.
     */
    @GenerateFinalPort(name = "waitForNewFrame", hasDefault = true)
    private boolean mWaitForNewFrame = true;

    /** Whether the media source should loop automatically or not. Defaults to
     * true.
     */
    @GenerateFieldPort(name = "loop", hasDefault = true)
    private boolean mLooping = true;

    /** Volume control. Currently sound is piped directly to the speakers, so
     * this defaults to mute.
     */
    @GenerateFieldPort(name = "volume", hasDefault = true)
    private float mVolume = 0.f;

    /** Orientation. This controls the output orientation of the video. Valid
     * values are 0, 90, 180, 270
     */
    @GenerateFieldPort(name = "orientation", hasDefault = true)
    private int mOrientation = 0;

    private MediaPlayer mMediaPlayer;
    private GLFrame mMediaFrame;
    private SurfaceTexture mSurfaceTexture;
    private ShaderProgram mFrameExtractor;
    private MutableFrameFormat mOutputFormat;
    private int mWidth, mHeight;

    // Total timeouts will be PREP_TIMEOUT*PREP_TIMEOUT_REPEAT
    private static final int PREP_TIMEOUT = 100; // ms
    private static final int PREP_TIMEOUT_REPEAT = 100;
    private static final int NEWFRAME_TIMEOUT = 100; //ms
    private static final int NEWFRAME_TIMEOUT_REPEAT = 10;

    // This is an identity shader; not using the default identity
    // shader because reading from a SurfaceTexture requires the
    // GL_OES_EGL_image_external extension.
    private final String mFrameShader =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
            "}\n";

    // The following transforms enable rotation of the decoded source.
    // These are multiplied with the transform obtained from the
    // SurfaceTexture to get the final transform to be set on the media source.
    // Currently, given a device orientation, the MediaSource rotates in such a way
    // that the source is displayed upright. A particular use case
    // is "Background Replacement" feature in the Camera app
    // where the MediaSource rotates the source to align with the camera feed and pass it
    // on to the backdropper filter. The backdropper only does the blending
    // and does not have to do any rotation
    // (except for mirroring in case of front camera).
    // TODO: Currently the rotations are spread over a bunch of stages in the
    // pipeline. A cleaner design
    // could be to cast away all the rotation in a separate filter or attach a transform
    // to the frame so that MediaSource itself need not know about any rotation.
    private static final float[] mSourceCoords_0 = { 1, 1, 0, 1,
                                                     0, 1, 0, 1,
                                                     1, 0, 0, 1,
                                                     0, 0, 0, 1 };
    private static final float[] mSourceCoords_270 = { 0, 1, 0, 1,
                                                      0, 0, 0, 1,
                                                      1, 1, 0, 1,
                                                      1, 0, 0, 1 };
    private static final float[] mSourceCoords_180 = { 0, 0, 0, 1,
                                                       1, 0, 0, 1,
                                                       0, 1, 0, 1,
                                                       1, 1, 0, 1 };
    private static final float[] mSourceCoords_90 = { 1, 0, 0, 1,
                                                       1, 1, 0, 1,
                                                       0, 0, 0, 1,
                                                       0, 1, 0, 1 };

    private boolean mGotSize;
    private boolean mPrepared;
    private boolean mPlaying;
    private boolean mNewFrameAvailable;
    private boolean mOrientationUpdated;
    private boolean mPaused;
    private boolean mCompleted;

    private final boolean mLogVerbose;
    private static final String TAG = "MediaSource";

    public MediaSource(String name) {
        super(name);
        mNewFrameAvailable = false;

        mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    }

    @Override
    public void setupPorts() {
        // Add input port
        addOutputPort("video", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                  FrameFormat.TARGET_GPU));
    }

    private void createFormats() {
        mOutputFormat = ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                           FrameFormat.TARGET_GPU);
    }

    @Override
    protected void prepare(FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "Preparing MediaSource");

        mFrameExtractor = new ShaderProgram(context, mFrameShader);
        // SurfaceTexture defines (0,0) to be bottom-left. The filter framework
        // defines (0,0) as top-left, so do the flip here.
        mFrameExtractor.setSourceRect(0, 1, 1, -1);

        createFormats();
    }

    @Override
    public void open(FilterContext context) {
        if (mLogVerbose) {
            Log.v(TAG, "Opening MediaSource");
            if (mSelectedIsUrl) {
                Log.v(TAG, "Current URL is " + mSourceUrl);
            } else {
                Log.v(TAG, "Current source is Asset!");
            }
        }

        mMediaFrame = (GLFrame)context.getFrameManager().newBoundFrame(
                mOutputFormat,
                GLFrame.EXTERNAL_TEXTURE,
                0);

        mSurfaceTexture = new SurfaceTexture(mMediaFrame.getTextureId());

        if (!setupMediaPlayer(mSelectedIsUrl)) {
          throw new RuntimeException("Error setting up MediaPlayer!");
        }
    }

    @Override
    public void process(FilterContext context) {
        // Note: process is synchronized by its caller in the Filter base class
        if (mLogVerbose) Log.v(TAG, "Processing new frame");

        if (mMediaPlayer == null) {
            // Something went wrong in initialization or parameter updates
            throw new NullPointerException("Unexpected null media player!");
        }

        if (mCompleted) {
            // Video playback is done, so close us down
            closeOutputPort("video");
            return;
        }

        if (!mPlaying) {
            int waitCount = 0;
            if (mLogVerbose) Log.v(TAG, "Waiting for preparation to complete");
            while (!mGotSize || !mPrepared) {
                try {
                    this.wait(PREP_TIMEOUT);
                } catch (InterruptedException e) {
                    // ignoring
                }
                if (mCompleted) {
                    // Video playback is done, so close us down
                    closeOutputPort("video");
                    return;
                }
                waitCount++;
                if (waitCount == PREP_TIMEOUT_REPEAT) {
                    mMediaPlayer.release();
                    throw new RuntimeException("MediaPlayer timed out while preparing!");
                }
            }
            if (mLogVerbose) Log.v(TAG, "Starting playback");
            mMediaPlayer.start();
        }

        // Use last frame if paused, unless just starting playback, in which case
        // we want at least one valid frame before pausing
        if (!mPaused || !mPlaying) {
            if (mWaitForNewFrame) {
                if (mLogVerbose) Log.v(TAG, "Waiting for new frame");

                int waitCount = 0;
                while (!mNewFrameAvailable) {
                    if (waitCount == NEWFRAME_TIMEOUT_REPEAT) {
                        if (mCompleted) {
                            // Video playback is done, so close us down
                            closeOutputPort("video");
                            return;
                        } else {
                            throw new RuntimeException("Timeout waiting for new frame!");
                        }
                    }
                    try {
                        this.wait(NEWFRAME_TIMEOUT);
                    } catch (InterruptedException e) {
                        if (mLogVerbose) Log.v(TAG, "interrupted");
                        // ignoring
                    }
                    waitCount++;
                }
                mNewFrameAvailable = false;
                if (mLogVerbose) Log.v(TAG, "Got new frame");
            }

            mSurfaceTexture.updateTexImage();
            mOrientationUpdated = true;
        }
        if (mOrientationUpdated) {
            float[] surfaceTransform = new float[16];
            mSurfaceTexture.getTransformMatrix(surfaceTransform);

            float[] sourceCoords = new float[16];
            switch (mOrientation) {
                default:
                case 0:
                    Matrix.multiplyMM(sourceCoords, 0,
                                      surfaceTransform, 0,
                                      mSourceCoords_0, 0);
                    break;
                case 90:
                    Matrix.multiplyMM(sourceCoords, 0,
                                      surfaceTransform, 0,
                                      mSourceCoords_90, 0);
                    break;
                case 180:
                    Matrix.multiplyMM(sourceCoords, 0,
                                      surfaceTransform, 0,
                                      mSourceCoords_180, 0);
                    break;
                case 270:
                    Matrix.multiplyMM(sourceCoords, 0,
                                      surfaceTransform, 0,
                                      mSourceCoords_270, 0);
                    break;
            }
            if (mLogVerbose) {
                Log.v(TAG, "OrientationHint = " + mOrientation);
                String temp = String.format("SetSourceRegion: %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f",
                        sourceCoords[4], sourceCoords[5],sourceCoords[0], sourceCoords[1],
                        sourceCoords[12], sourceCoords[13],sourceCoords[8], sourceCoords[9]);
                Log.v(TAG, temp);
            }
            mFrameExtractor.setSourceRegion(sourceCoords[4], sourceCoords[5],
                    sourceCoords[0], sourceCoords[1],
                    sourceCoords[12], sourceCoords[13],
                    sourceCoords[8], sourceCoords[9]);
            mOrientationUpdated = false;
        }

        Frame output = context.getFrameManager().newFrame(mOutputFormat);
        mFrameExtractor.process(mMediaFrame, output);

        long timestamp = mSurfaceTexture.getTimestamp();
        if (mLogVerbose) Log.v(TAG, "Timestamp: " + (timestamp / 1000000000.0) + " s");
        output.setTimestamp(timestamp);

        pushOutput("video", output);
        output.release();

        mPlaying = true;
    }

    @Override
    public void close(FilterContext context) {
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
        }
        mPrepared = false;
        mGotSize = false;
        mPlaying = false;
        mPaused = false;
        mCompleted = false;
        mNewFrameAvailable = false;

        mMediaPlayer.release();
        mMediaPlayer = null;
        mSurfaceTexture.release();
        mSurfaceTexture = null;
        if (mLogVerbose) Log.v(TAG, "MediaSource closed");
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mMediaFrame != null) {
            mMediaFrame.release();
        }
    }

    // When updating the port values of the filter, users can update sourceIsUrl to switch
    //   between using URL objects or Assets.
    // If updating only sourceUrl/sourceAsset, MediaPlayer gets reset if the current player
    //   uses Url objects/Asset.
    // Otherwise the new sourceUrl/sourceAsset is stored and will be used when users switch
    //   sourceIsUrl next time.
    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        if (mLogVerbose) Log.v(TAG, "Parameter update");
        if (name.equals("sourceUrl")) {
           if (isOpen()) {
                if (mLogVerbose) Log.v(TAG, "Opening new source URL");
                if (mSelectedIsUrl) {
                    setupMediaPlayer(mSelectedIsUrl);
                }
            }
        } else if (name.equals("sourceAsset") ) {
            if (isOpen()) {
                if (mLogVerbose) Log.v(TAG, "Opening new source FD");
                if (!mSelectedIsUrl) {
                    setupMediaPlayer(mSelectedIsUrl);
                }
            }
        } else if (name.equals("loop")) {
            if (isOpen()) {
                mMediaPlayer.setLooping(mLooping);
            }
        } else if (name.equals("sourceIsUrl")) {
            if (isOpen()){
                if (mSelectedIsUrl){
                    if (mLogVerbose) Log.v(TAG, "Opening new source URL");
                } else {
                    if (mLogVerbose) Log.v(TAG, "Opening new source Asset");
                }
                setupMediaPlayer(mSelectedIsUrl);
            }
        } else if (name.equals("volume")) {
            if (isOpen()) {
                mMediaPlayer.setVolume(mVolume, mVolume);
            }
        } else if (name.equals("orientation") && mGotSize) {
            if (mOrientation == 0 || mOrientation == 180) {
                mOutputFormat.setDimensions(mWidth, mHeight);
            } else {
                mOutputFormat.setDimensions(mHeight, mWidth);
            }
            mOrientationUpdated = true;
        }
    }

    synchronized public void pauseVideo(boolean pauseState) {
        if (isOpen()) {
            if (pauseState && !mPaused) {
                mMediaPlayer.pause();
            } else if (!pauseState && mPaused) {
                mMediaPlayer.start();
            }
        }
        mPaused = pauseState;
    }

    /** Creates a media player, sets it up, and calls prepare */
    synchronized private boolean setupMediaPlayer(boolean useUrl) {
        mPrepared = false;
        mGotSize = false;
        mPlaying = false;
        mPaused = false;
        mCompleted = false;
        mNewFrameAvailable = false;

        if (mLogVerbose) Log.v(TAG, "Setting up playback.");

        if (mMediaPlayer != null) {
            // Clean up existing media players
            if (mLogVerbose) Log.v(TAG, "Resetting existing MediaPlayer.");
            mMediaPlayer.reset();
        } else {
            // Create new media player
            if (mLogVerbose) Log.v(TAG, "Creating new MediaPlayer.");
            mMediaPlayer = new MediaPlayer();
        }

        if (mMediaPlayer == null) {
            throw new RuntimeException("Unable to create a MediaPlayer!");
        }

        // Set up data sources, etc
        try {
            if (useUrl) {
                if (mLogVerbose) Log.v(TAG, "Setting MediaPlayer source to URI " + mSourceUrl);
                if (mContext == null) {
                    mMediaPlayer.setDataSource(mSourceUrl);
                } else {
                    mMediaPlayer.setDataSource(mContext, Uri.parse(mSourceUrl.toString()));
                }
            } else {
                if (mLogVerbose) Log.v(TAG, "Setting MediaPlayer source to asset " + mSourceAsset);
                mMediaPlayer.setDataSource(mSourceAsset.getFileDescriptor(), mSourceAsset.getStartOffset(), mSourceAsset.getLength());
            }
        } catch(IOException e) {
            mMediaPlayer.release();
            mMediaPlayer = null;
            if (useUrl) {
                throw new RuntimeException(String.format("Unable to set MediaPlayer to URL %s!", mSourceUrl), e);
            } else {
                throw new RuntimeException(String.format("Unable to set MediaPlayer to asset %s!", mSourceAsset), e);
            }
        } catch(IllegalArgumentException e) {
            mMediaPlayer.release();
            mMediaPlayer = null;
            if (useUrl) {
                throw new RuntimeException(String.format("Unable to set MediaPlayer to URL %s!", mSourceUrl), e);
            } else {
                throw new RuntimeException(String.format("Unable to set MediaPlayer to asset %s!", mSourceAsset), e);
            }
        }

        mMediaPlayer.setLooping(mLooping);
        mMediaPlayer.setVolume(mVolume, mVolume);

        // Bind it to our media frame
        Surface surface = new Surface(mSurfaceTexture);
        mMediaPlayer.setSurface(surface);
        surface.release();

        // Connect Media Player to callbacks

        mMediaPlayer.setOnVideoSizeChangedListener(onVideoSizeChangedListener);
        mMediaPlayer.setOnPreparedListener(onPreparedListener);
        mMediaPlayer.setOnCompletionListener(onCompletionListener);

        // Connect SurfaceTexture to callback
        mSurfaceTexture.setOnFrameAvailableListener(onMediaFrameAvailableListener);

        if (mLogVerbose) Log.v(TAG, "Preparing MediaPlayer.");
        mMediaPlayer.prepareAsync();

        return true;
    }

    private MediaPlayer.OnVideoSizeChangedListener onVideoSizeChangedListener =
            new MediaPlayer.OnVideoSizeChangedListener() {
        public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
            if (mLogVerbose) Log.v(TAG, "MediaPlayer sent dimensions: " + width + " x " + height);
            if (!mGotSize) {
                if (mOrientation == 0 || mOrientation == 180) {
                    mOutputFormat.setDimensions(width, height);
                } else {
                    mOutputFormat.setDimensions(height, width);
                }
                mWidth = width;
                mHeight = height;
            } else {
                if (mOutputFormat.getWidth() != width ||
                    mOutputFormat.getHeight() != height) {
                    Log.e(TAG, "Multiple video size change events received!");
                }
            }
            synchronized(MediaSource.this) {
                mGotSize = true;
                MediaSource.this.notify();
            }
        }
    };

    private MediaPlayer.OnPreparedListener onPreparedListener =
            new MediaPlayer.OnPreparedListener() {
        public void onPrepared(MediaPlayer mp) {
            if (mLogVerbose) Log.v(TAG, "MediaPlayer is prepared");
            synchronized(MediaSource.this) {
                mPrepared = true;
                MediaSource.this.notify();
            }
        }
    };

    private MediaPlayer.OnCompletionListener onCompletionListener =
            new MediaPlayer.OnCompletionListener() {
        public void onCompletion(MediaPlayer mp) {
            if (mLogVerbose) Log.v(TAG, "MediaPlayer has completed playback");
            synchronized(MediaSource.this) {
                mCompleted = true;
            }
        }
    };

    private SurfaceTexture.OnFrameAvailableListener onMediaFrameAvailableListener =
            new SurfaceTexture.OnFrameAvailableListener() {
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            if (mLogVerbose) Log.v(TAG, "New frame from media player");
            synchronized(MediaSource.this) {
                if (mLogVerbose) Log.v(TAG, "New frame: notify");
                mNewFrameAvailable = true;
                MediaSource.this.notify();
                if (mLogVerbose) Log.v(TAG, "New frame: notify done");
            }
        }
    };

}
