/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.io.File;
import java.io.FileDescriptor;
import java.net.InetAddress;

 
public class MediaFrameworkTest extends Activity {
    
    //public static Surface video_sf;
    public static SurfaceView mSurfaceView;
    private MediaController mMediaController;
    private String urlpath;
    private MediaPlayer mpmidi;
    private MediaPlayer mpmp3;
    private String testfilepath = "/sdcard/awb.awb";
    
    public static AssetFileDescriptor midiafd;
    public static AssetFileDescriptor mp3afd;
    
    
    public MediaFrameworkTest() {
    }

    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.surface_view);
        mSurfaceView = (SurfaceView)findViewById(R.id.surface_view);
        ViewGroup.LayoutParams lp = mSurfaceView.getLayoutParams();
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        //Get the midi fd
        midiafd = this.getResources().openRawResourceFd(R.raw.testmidi);
        
        //Get the mp3 fd
        mp3afd = this.getResources().openRawResourceFd(R.raw.testmp3);
    }
    
    public void startPlayback(String filename){
      String mimetype = "audio/mpeg";
      Uri path = Uri.parse(filename);
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(path, mimetype);
      startActivity(intent);
    }
    
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
      switch (keyCode) {
          case KeyEvent.KEYCODE_0:
            MediaPlayer mp = new MediaPlayer();
            try{
              mp.setDataSource(MediaNames.VIDEO_RTSP3GP);
              Log.v("emily","awb  " + testfilepath);
              mp.setDisplay(mSurfaceView.getHolder());
              mp.prepare();
              mp.start();
            }catch (Exception e){}
              break;
          
          //start the music player intent with the test URL from PV    
          case KeyEvent.KEYCODE_1:
            startPlayback(MediaNames.STREAM_MP3_1);
            break;
          
          case KeyEvent.KEYCODE_2:
            startPlayback(MediaNames.STREAM_MP3_2);
            break;
          
          case KeyEvent.KEYCODE_3:
            startPlayback(MediaNames.STREAM_MP3_3);
            break;
          
          case KeyEvent.KEYCODE_4:
            startPlayback(MediaNames.STREAM_MP3_4);
            break;
          
          case KeyEvent.KEYCODE_5:
            startPlayback(MediaNames.STREAM_MP3_5);
            break;
          
          case KeyEvent.KEYCODE_6:
            startPlayback(MediaNames.STREAM_MP3_6);
            break;
          
          case KeyEvent.KEYCODE_7:
            startPlayback(MediaNames.STREAM_MP3_7);
            break;
          
          case KeyEvent.KEYCODE_8:
            startPlayback(MediaNames.STREAM_MP3_8);
            break;
          
          case KeyEvent.KEYCODE_9:
            startPlayback(MediaNames.STREAM_MP3_9);
            break;
          
              
              
      }
      return super.onKeyDown(keyCode, event);
     
  }  

  public static boolean checkStreamingServer() throws Exception {
      InetAddress address = InetAddress.getByAddress(MediaNames.STREAM_SERVER);
      return address.isReachable(10000);
  }
}
