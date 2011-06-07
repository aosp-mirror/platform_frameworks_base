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

package com.android.mediaframeworktest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Downloads;
import android.util.Log;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View.OnClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.MediaController;
import android.widget.VideoView;
import com.android.mediaframeworktest.MediaNames;

import android.graphics.Bitmap;
import android.widget.ImageView;

import java.io.File;
import java.io.FileDescriptor;
import java.net.InetAddress;

 
public class MediaFrameworkTest extends Activity implements SurfaceHolder.Callback {
    
    //public static Surface video_sf;
    public static SurfaceView mSurfaceView;
    private MediaController mMediaController;
    private String urlpath;
    private MediaPlayer mpmidi;
    private MediaPlayer mpmp3;
    private String testfilepath = "/sdcard/awb.awb";
    
    public static AssetFileDescriptor midiafd;
    public static AssetFileDescriptor mp3afd;
    
    public static Bitmap mDestBitmap;
    public static ImageView mOverlayView;
    private SurfaceHolder mSurfaceHolder = null;
    private String TAG = "MediaFrameworkTest";
    private PowerManager.WakeLock mWakeLock = null;

    public MediaFrameworkTest() {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.surface_view);
        mSurfaceView = (SurfaceView)findViewById(R.id.surface_view);
        mOverlayView = (ImageView)findViewById(R.id.overlay_layer);
        ViewGroup.LayoutParams lp = mSurfaceView.getLayoutParams();
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceHolder.addCallback(this);
        
        //Get the midi fd
        midiafd = this.getResources().openRawResourceFd(R.raw.testmidi);
        
        //Get the mp3 fd
        mp3afd = this.getResources().openRawResourceFd(R.raw.testmp3);
        mOverlayView.setLayoutParams(lp);
        mDestBitmap = Bitmap.createBitmap((int)640, (int)480, Bitmap.Config.ARGB_8888);
        mOverlayView.setImageBitmap(mDestBitmap);

        //Acquire the full wake lock to keep the device up
        PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "MediaFrameworkTest");
        mWakeLock.acquire();
    }

    public void onStop(Bundle icicle) {
        mWakeLock.release();
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        //Can do nothing in here. The test case will fail if the surface destroyed.
        Log.v(TAG, "Test application surface destroyed");
        mSurfaceHolder = null;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        //Do nothing in here. Just print out the log
        Log.v(TAG, "Test application surface changed");
    }

    public void surfaceCreated(SurfaceHolder holder) {
    }

    public void startPlayback(String filename){
      String mimetype = "audio/mpeg";
      Uri path = Uri.parse(filename);
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(path, mimetype);
      startActivity(intent);
    }

  public static boolean checkStreamingServer() throws Exception {
      InetAddress address = InetAddress.getByAddress(MediaNames.STREAM_SERVER);
      return address.isReachable(10000);
  }

  public static void testInvalidateOverlay() {
      mOverlayView.invalidate();
  }

}
