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

package com.android.mediadump;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.lang.Integer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;


/**
 * A simple player to display the raw rgb files that are generated from
 * VideDumpView class. It reads the "/sdcard/mediadump/prop.xml" to get
 * the meta data such as width, height, frame rate, and bytes per pixel.
 */
public class RgbPlayerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new RgbView(this));
    }

    private static class RgbView extends View implements MediaPlayerControl {
        private static final String TAG = "RgbView";
        private Bitmap mBitmap;
        private int mStartX = 0;
        private int mStartY = 0;
        private int mWidth = 0;
        private int mHeight = 0;
        private int mBytesPerPixel = 0;
        private int mBytesPerLine = 0;
        private int mBytesPerImage = 0;
        private byte[] mImageBytes;
        private ByteBuffer mFlipBuf;

        private int mFrameRate = 0;

        private MediaController mMediaController;
        private boolean mMediaControllerAttached;
        private boolean mIsPlaying = false;
        private int mImageIndex = 0;
        private List<String> mImageList;
        private Timer mTimer;
        private TimerTask mImageTask = new TimerTask() {
            @Override
            public void run() {
                if (mIsPlaying) {
                    mImageIndex++;
                    LoadImage();
                }
            }
        };
        private Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                invalidate();
            }
        };


        public RgbView(Context context) {
            super(context);

            // read properties
            Properties prop = new Properties();
            try {
                prop.loadFromXML(new FileInputStream("/sdcard/mediadump/prop.xml"));
            } catch (java.io.IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }

            try {
                mStartX = Integer.parseInt(prop.getProperty("startX"));
                mStartY = Integer.parseInt(prop.getProperty("startY"));
                mWidth = Integer.parseInt(prop.getProperty("width"));
                mHeight = Integer.parseInt(prop.getProperty("height"));
                mBytesPerPixel = Integer.parseInt(prop.getProperty("bytesPerPixel"));
                mFrameRate = Integer.parseInt(prop.getProperty("frameRate"));
            } catch (java.lang.NumberFormatException e) {
                Log.e(TAG, e.getMessage(), e);
            }

            mBytesPerLine = mWidth * mBytesPerPixel;
            mBytesPerImage = mHeight * mBytesPerLine;
            mFlipBuf = ByteBuffer.allocate(mBytesPerImage);
            mBitmap = Bitmap.createBitmap(mWidth, mHeight,
                                          mBytesPerPixel == 2
                                          ? Bitmap.Config.RGB_565
                                          : Bitmap.Config.ARGB_8888);

            mImageList = new ArrayList<String>();
            try {
                BufferedReader reader = new BufferedReader(
                    new FileReader("/sdcard/mediadump/images.lst"));
                String line;
                while ((line = reader.readLine()) != null) {
                    mImageList.add(line);
                }
                reader.close();
            } catch (java.io.IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }

            mMediaController = new MediaController(context);
            mTimer = new Timer();
            LoadImage();
        }

        private void attachMediaController() {
            if (mMediaController != null) {
                if (!mMediaControllerAttached) {
                    mMediaController.setMediaPlayer(this);
                    View anchorView = this.getParent() instanceof View ?
                            (View)this.getParent() : this;
                    mMediaController.setAnchorView(anchorView);
                    mMediaController.setEnabled(true);
                    mMediaControllerAttached = true;
                    mTimer.scheduleAtFixedRate(mImageTask, 0, 1000 / mFrameRate);
                }
                mMediaController.show();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            attachMediaController();
            return true;
        }

        private void LoadImage() {
            try {
                if (mImageIndex < 0 || mImageIndex >= mImageList.size()) {
                    mImageIndex = 0;
                    mIsPlaying = false;
                }

                String filename = mImageList.get(mImageIndex);

                FileInputStream in = new FileInputStream(filename);
                mImageBytes = new byte[mBytesPerImage];
                in.read(mImageBytes);
            } catch (Exception e) {
                Log.e("Error reading file", e.toString());
            }

            // Flip the image vertically since the image from MediaDump is
            // upside down.
            for (int i = mHeight - 1; i >= 0; i--) {
                mFlipBuf.put(mImageBytes, i * mBytesPerLine, mBytesPerLine);
            }
            mFlipBuf.rewind();
            mBitmap.copyPixelsFromBuffer(mFlipBuf);
            mFlipBuf.rewind();
            mHandler.sendEmptyMessage(0);
        }

        @Override 
        protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(mBitmap, mStartX, mStartY, null);
        }

        public boolean canPause() {
            return true;
        }

        public boolean canSeekBackward() {
            return true;
        }

        public boolean canSeekForward() {
            return true;
        }

        public int getBufferPercentage() {
            return 1;
        }

        public int getCurrentPosition() {
            return mImageIndex * 1000 / mFrameRate;
        }

        public int getDuration() {
            return mImageList.size() * 1000 / mFrameRate;
        }

        public boolean isPlaying() {
            return mIsPlaying;
        }

        public void pause() {
            mIsPlaying = false;
        }

        public void seekTo(int pos) {
            mImageIndex = pos * mFrameRate / 1000;
        }

        public void start() {
            mIsPlaying = true;
        }
    }

}
