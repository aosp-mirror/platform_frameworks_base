/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.effectstest;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View.OnClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ToggleButton;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.media.AudioManager;
import android.media.MediaPlayer;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SimplePlayer implements OnClickListener {

    private final static String TAG = "SimplePlayer";

    int mPlayPauseButtonId;
    int mStopButtonId;
    Context mContext;
    ImageView mPlayPauseButton;
    int mPlayImageResource;
    int mPauseImageResource;
    String mFileName;
    int mFileResId;
    MediaPlayer mMediaPlayer;
    int mStreamType;
    int mSession;
    float mSendLevel = (float)0.5;
    int mEffectId = 0;
    TextView mSessionText;

    SimplePlayer(Context context, int playPausebuttonId, ImageView playPausebutton,
                int stopButtonId, ImageView stopButton, TextView sessionText, String fileName, int stream, int session)
    {
        set(context, playPausebuttonId, playPausebutton, stopButtonId, stopButton, sessionText, stream, session);
        mFileName = fileName;
    }

    SimplePlayer(Context context, int playPausebuttonId, ImageView playPausebutton,
            int stopButtonId, ImageView stopButton, TextView sessionText, int fileResId, int stream, int session) {
        set(context, playPausebuttonId, playPausebutton, stopButtonId, stopButton, sessionText, stream, session);
        mFileResId = fileResId;
        mFileName = "";
    }

    public void set(Context context, int playPausebuttonId, ImageView playPausebutton,
            int stopButtonId, ImageView stopButton, TextView sessionText, int stream, int session) {
        mContext = context;
        mPlayPauseButtonId = playPausebuttonId;
        mStopButtonId = stopButtonId;
        mPlayPauseButton = (ImageButton) playPausebutton;
        ImageButton stop = (ImageButton) stopButton;

        mPlayPauseButton.setOnClickListener(this);
        mPlayPauseButton.requestFocus();
        stop.setOnClickListener(this);

        mPlayImageResource = android.R.drawable.ic_media_play;
        mPauseImageResource = android.R.drawable.ic_media_pause;
        mStreamType = stream;
        mSession = session;
        mSessionText = sessionText;
    }


    public void onClick(View v) {
        if (v.getId() == mPlayPauseButtonId) {
            playOrPause();
        } else if (v.getId() == mStopButtonId) {
            stop();
        }
    }

    public void playOrPause() {
        if (mMediaPlayer == null || !mMediaPlayer.isPlaying()){
              if (mMediaPlayer == null) {
                  try {
                      mMediaPlayer = new MediaPlayer();
                      if (mSession != 0) {
                          mMediaPlayer.setAudioSessionId(mSession);
                          Log.d(TAG, "mMediaPlayer.setAudioSessionId(): "+ mSession);
                      }

                      if (mFileName.equals("")) {
                          Log.d(TAG, "Playing from resource");
                          AssetFileDescriptor afd = mContext.getResources().openRawResourceFd(mFileResId);
                          mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                          afd.close();
                      } else {
                          Log.d(TAG, "Playing file: "+mFileName);
                          mMediaPlayer.setDataSource(mFileName);
                      }
                      mMediaPlayer.setAudioStreamType(mStreamType);
                      mMediaPlayer.prepare();
                      mMediaPlayer.setLooping(true);
                  } catch (IOException ex) {
                      Log.e(TAG, "mMediaPlayercreate failed:", ex);
                      mMediaPlayer = null;
                  } catch (IllegalArgumentException ex) {
                      Log.e(TAG, "mMediaPlayercreate failed:", ex);
                      mMediaPlayer = null;
                  } catch (SecurityException ex) {
                      Log.e(TAG, "mMediaPlayercreate failed:", ex);
                      mMediaPlayer = null;
                  }

                  if (mMediaPlayer != null) {
                      mMediaPlayer.setAuxEffectSendLevel(mSendLevel);
                      mMediaPlayer.attachAuxEffect(mEffectId);
                      mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                          public void onCompletion(MediaPlayer mp) {
                              updatePlayPauseButton();
                          }
                      });
                      mSessionText.setText("Session: "+Integer.toString(mMediaPlayer.getAudioSessionId()));
                  }
              }
              if (mMediaPlayer != null) {
                  mMediaPlayer.start();
              }
          } else {
              mMediaPlayer.pause();
          }
          updatePlayPauseButton();
    }

    public void stop() {
      if (mMediaPlayer != null) {
          mMediaPlayer.stop();
          mMediaPlayer.release();
          mMediaPlayer = null;
      }
      updatePlayPauseButton();
    }

    public boolean isPlaying() {
        if (mMediaPlayer != null) {
            return mMediaPlayer.isPlaying();
        } else {
            return false;
        }
    }

    public void updatePlayPauseButton() {
        mPlayPauseButton.setImageResource(isPlaying() ? mPauseImageResource : mPlayImageResource);
    }

    public void attachAuxEffect(int effectId) {
        mEffectId = effectId;
        if (mMediaPlayer != null) {
            Log.d(TAG,"attach effect: "+effectId);
            mMediaPlayer.attachAuxEffect(effectId);
        }
    }
    public void setAuxEffectSendLevel(float level) {
        mSendLevel = level;
        if (mMediaPlayer != null) {
            mMediaPlayer.setAuxEffectSendLevel(level);
        }
    }

    public void setContext(Context context) {
        mContext = context;
    }
}
