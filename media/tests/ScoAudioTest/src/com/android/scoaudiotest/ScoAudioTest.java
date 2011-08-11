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

package com.android.scoaudiotest;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class ScoAudioTest extends Activity {

    final static String TAG = "ScoAudioTest";
    
    AudioManager mAudioManager;
    AudioManager mAudioManager2;
    boolean mForceScoOn;
    ToggleButton mScoButton;
    ToggleButton mVoiceDialerButton;
    boolean mVoiceDialerOn;
    String mLastRecordedFile;
    SimpleMediaController mMediaControllers[] = new SimpleMediaController[2];
    private TextToSpeech mTts;
    private HashMap<String, String> mTtsParams;
    private int mOriginalVoiceVolume;
    EditText mSpeakText;
    boolean mTtsInited;
    private Handler mHandler;
    private static final String UTTERANCE = "utterance";
    private static Intent sVoiceCommandIntent;
    private File mSampleFile;
    ToggleButton mTtsToFileButton;
    private boolean mTtsToFile;
    private int mCurrentMode;
    Spinner mModeSpinner;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothDevice mBluetoothHeadsetDevice;
    TextView mScoStateTxt;
    TextView mVdStateTxt;
    
    private final BroadcastReceiver mReceiver = new ScoBroadcastReceiver();

    public ScoAudioTest() {
        Log.e(TAG, "contructor");
    }
        
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        setContentView(R.layout.scoaudiotest);

        mScoStateTxt = (TextView) findViewById(R.id.scoStateTxt);
        mVdStateTxt = (TextView) findViewById(R.id.vdStateTxt);

        IntentFilter intentFilter =
            new IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        registerReceiver(mReceiver, intentFilter);

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mAudioManager2 = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        mHandler = new Handler();
        
        mMediaControllers[0] = new SimplePlayerController(this, R.id.playPause1, R.id.stop1,
                R.raw.sine440_mo_16b_16k, AudioManager.STREAM_BLUETOOTH_SCO);
        TextView name = (TextView) findViewById(R.id.playPause1Text);
        name.setText("VOICE_CALL stream");
        
        mScoButton = (ToggleButton)findViewById(R.id.ForceScoButton);
        mScoButton.setOnCheckedChangeListener(mForceScoChanged);
        mForceScoOn = false;
        mScoButton.setChecked(mForceScoOn);

        mVoiceDialerButton = (ToggleButton)findViewById(R.id.VoiceDialerButton);
        mVoiceDialerButton.setOnCheckedChangeListener(mVoiceDialerChanged);
        mVoiceDialerOn = false;
        mVoiceDialerButton.setChecked(mVoiceDialerOn);

        
        mMediaControllers[1] = new SimpleRecordController(this, R.id.recStop1, 0, "Sco_record_");
        mTtsInited = false;
        mTts = new TextToSpeech(this, new TtsInitListener());
        mTtsParams = new HashMap<String, String>();
        mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                String.valueOf(AudioManager.STREAM_BLUETOOTH_SCO));
        mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                UTTERANCE);

        mSpeakText = (EditText) findViewById(R.id.speakTextEdit);        
        mSpeakText.setOnKeyListener(mSpeakKeyListener);
        mSpeakText.setText("sco audio test sentence");
        mTtsToFileButton = (ToggleButton)findViewById(R.id.TtsToFileButton);
        mTtsToFileButton.setOnCheckedChangeListener(mTtsToFileChanged);
        mTtsToFile = true;
        mTtsToFileButton.setChecked(mTtsToFile);

        mModeSpinner = (Spinner) findViewById(R.id.modeSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mModeStrings);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mModeSpinner.setAdapter(adapter);
        mModeSpinner.setOnItemSelectedListener(mModeChanged);
        mCurrentMode = mAudioManager.getMode();
        mModeSpinner.setSelection(mCurrentMode);

        mBluetoothHeadsetDevice = null;
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            btAdapter.getProfileProxy(this, mBluetoothProfileServiceListener,
                                    BluetoothProfile.HEADSET);
        }

        sVoiceCommandIntent = new Intent(Intent.ACTION_VOICE_COMMAND);
        sVoiceCommandIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTts.shutdown();
        unregisterReceiver(mReceiver);
        if (mBluetoothHeadset != null) {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter != null) {
                btAdapter.closeProfileProxy(BluetoothProfile.HEADSET, mBluetoothHeadset);
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
//        mForceScoOn = false;
//        mScoButton.setChecked(mForceScoOn);
        mMediaControllers[0].stop();        
        mMediaControllers[1].stop();
        mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO,
                mOriginalVoiceVolume, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLastRecordedFile = "";
        mMediaControllers[0].mFileName = "";
        mOriginalVoiceVolume = mAudioManager.getStreamVolume(
                AudioManager.STREAM_BLUETOOTH_SCO);
        setVolumeControlStream(AudioManager.STREAM_BLUETOOTH_SCO);
        mCurrentMode = mAudioManager.getMode();
        mModeSpinner.setSelection(mCurrentMode);
    }

    private OnCheckedChangeListener mForceScoChanged
    = new OnCheckedChangeListener(){
        @Override
        public void onCheckedChanged(CompoundButton buttonView,
                boolean isChecked) {
            if (mForceScoOn != isChecked) {
                mForceScoOn = isChecked;
                AudioManager mngr = mAudioManager;
                CheckBox box = (CheckBox) findViewById(R.id.useSecondAudioManager);
                if (box.isChecked()) {
                    Log.i(TAG, "Using 2nd audio manager");
                    mngr = mAudioManager2;
                }

                if (mForceScoOn) {
                    Log.e(TAG, "startBluetoothSco() IN");
                    mngr.startBluetoothSco();
                    Log.e(TAG, "startBluetoothSco() OUT");
                } else {
                    Log.e(TAG, "stopBluetoothSco() IN");
                    mngr.stopBluetoothSco();
                    Log.e(TAG, "stopBluetoothSco() OUT");
                }
            }
        }
    };

    private OnCheckedChangeListener mVoiceDialerChanged
    = new OnCheckedChangeListener(){
        @Override
        public void onCheckedChanged(CompoundButton buttonView,
                boolean isChecked) {
            if (mVoiceDialerOn != isChecked) {
                mVoiceDialerOn = isChecked;
                if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null) {
                    if (mVoiceDialerOn) {
                        mBluetoothHeadset.startVoiceRecognition(mBluetoothHeadsetDevice);
                    } else {
                        mBluetoothHeadset.stopVoiceRecognition(mBluetoothHeadsetDevice);                        
                    }
                }
            }
        }
    };

    private OnCheckedChangeListener mTtsToFileChanged
    = new OnCheckedChangeListener(){
        @Override
        public void onCheckedChanged(CompoundButton buttonView,
                boolean isChecked) {
            mTtsToFile = isChecked;
        }
    };

    private class SimpleMediaController implements OnClickListener {
        int mPlayPauseButtonId;
        int mStopButtonId;
        Context mContext;
        ImageView mPlayPauseButton;
        int mPlayImageResource;
        int mPauseImageResource;
        String mFileNameBase;
        String mFileName;
        int mFileResId;
        
        SimpleMediaController(Context context, int playPausebuttonId, int stopButtonId, String fileName) {
            mContext = context;
            mPlayPauseButtonId = playPausebuttonId;
            mStopButtonId = stopButtonId;
            mFileNameBase = fileName;
            mPlayPauseButton = (ImageButton) findViewById(playPausebuttonId);
            ImageButton stop = (ImageButton) findViewById(stopButtonId);

            mPlayPauseButton.setOnClickListener(this);
            mPlayPauseButton.requestFocus();
            if (stop != null) {
                stop.setOnClickListener(this);
            }
        }

        SimpleMediaController(Context context, int playPausebuttonId, int stopButtonId, int fileResId) {
            mContext = context;
            mPlayPauseButtonId = playPausebuttonId;
            mStopButtonId = stopButtonId;
            mFileNameBase = "";
            mFileResId = fileResId;
            mPlayPauseButton = (ImageButton) findViewById(playPausebuttonId);
            ImageButton stop = (ImageButton) findViewById(stopButtonId);

            mPlayPauseButton.setOnClickListener(this);
            mPlayPauseButton.requestFocus();
            if (stop != null) {
                stop.setOnClickListener(this);
            }
        }

        @Override
        public void onClick(View v) {
            if (v.getId() == mPlayPauseButtonId) {
                playOrPause();
            } else if (v.getId() == mStopButtonId) {
                stop();
            }
        }
        
        public void playOrPause() {
        }
        
        public void stop() {
        }

        public boolean isPlaying() {
            return false;
        }

        public void updatePlayPauseButton() {
            mPlayPauseButton.setImageResource(isPlaying() ? mPauseImageResource : mPlayImageResource);
        }
    }
    
    private class SimplePlayerController extends SimpleMediaController {
        private MediaPlayer mMediaPlayer;
        private int mStreamType;
        SimplePlayerController(Context context, int playPausebuttonId, int stopButtonId, String fileName, int stream) {
            super(context, playPausebuttonId, stopButtonId, fileName);
            
            mPlayImageResource = android.R.drawable.ic_media_play;
            mPauseImageResource = android.R.drawable.ic_media_pause;
            mStreamType = stream;
            mFileName = Environment.getExternalStorageDirectory().toString() + "/music/" +
                        mFileNameBase + "_" + ".wav";
        }

        SimplePlayerController(Context context, int playPausebuttonId, int stopButtonId, int fileResId, int stream) {
            super(context, playPausebuttonId, stopButtonId, fileResId);
            
            mPlayImageResource = android.R.drawable.ic_media_play;
            mPauseImageResource = android.R.drawable.ic_media_pause;
            mStreamType = stream;
            mFileName = "";
        }

        @Override
        public void playOrPause() {
            Log.e(TAG, "playOrPause playing: "+((mMediaPlayer == null)?false:!mMediaPlayer.isPlaying())+
                    " mMediaPlayer: "+mMediaPlayer+
                    " mFileName: "+mFileName+
                    " mLastRecordedFile: "+mLastRecordedFile);
            if (mMediaPlayer == null || !mMediaPlayer.isPlaying()){
                if (mMediaPlayer == null) {
                    if (mFileName != mLastRecordedFile) {
                        mFileName = mLastRecordedFile;
                        Log.e(TAG, "new recorded file: "+mFileName);
                    }
                    try {
                        mMediaPlayer = new MediaPlayer();
                        if (mFileName.equals("")) {
                            Log.e(TAG, "Playing from resource");
                            AssetFileDescriptor afd = mContext.getResources().openRawResourceFd(mFileResId);
                            mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                            afd.close();
                        } else {
                            Log.e(TAG, "Playing file: "+mFileName);
                            mMediaPlayer.setDataSource(mFileName);
                        }
                        mMediaPlayer.setAudioStreamType(mStreamType);
                        mMediaPlayer.prepare();
                        mMediaPlayer.setLooping(true);
                    } catch (Exception ex) {
                        Log.e(TAG, "mMediaPlayercreate failed:", ex);
                        mMediaPlayer.release();
                        mMediaPlayer = null;
                    }

                    if (mMediaPlayer != null) {
                        mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer mp) {
                                updatePlayPauseButton();
                            }
                        });
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
        @Override
        public void stop() {
            if (mMediaPlayer != null) {
                mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            updatePlayPauseButton();
        }
        
        @Override
        public boolean isPlaying() {
            if (mMediaPlayer != null) {
                return mMediaPlayer.isPlaying();
            } else {
                return false;                
            }
        }
    }
    
    private class SimpleRecordController extends SimpleMediaController {
        private MediaRecorder mMediaRecorder;
        private int mFileCount = 0;
        private int mState = 0;
        SimpleRecordController(Context context, int playPausebuttonId, int stopButtonId, String fileName) {
            super(context, playPausebuttonId, stopButtonId, fileName);
            Log.e(TAG, "SimpleRecordController cstor");
            mPlayImageResource = R.drawable.record;
            mPauseImageResource = R.drawable.stop;
        }
       
        @Override
        public void playOrPause() {
            if (mState == 0) {
                setup();
                try {
                    mMediaRecorder.start();
                    mState = 1;
                } catch (Exception e) {
                    Log.e(TAG, "Could start MediaRecorder: " + e.toString());
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                    mState = 0;
                }
            } else {
                try {
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                } catch (Exception e) {
                    Log.e(TAG, "Could not stop MediaRecorder: " + e.toString());
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                } finally {
                    mState = 0;
                }
            }
            updatePlayPauseButton();
        }

        public void setup() {
            Log.e(TAG, "SimpleRecordController setup()");
            if (mMediaRecorder == null) {
                mMediaRecorder = new MediaRecorder();
            }
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mFileName = Environment.getExternalStorageDirectory().toString() + "/music/" +
                        mFileNameBase + "_" + ++mFileCount + ".amr";
            mLastRecordedFile = mFileName;
            Log.e(TAG, "recording to file: "+mLastRecordedFile);
            mMediaRecorder.setOutputFile(mFileName);
            try {
                mMediaRecorder.prepare();
            }
            catch (Exception e) {
                Log.e(TAG, "Could not prepare MediaRecorder: " + e.toString());
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        }
        
        @Override
        public void stop() {
            if (mMediaRecorder != null) {
                mMediaRecorder.stop();
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
            updatePlayPauseButton();
        }

        @Override
        public boolean isPlaying() {
            if (mState == 1) {
                return true;
            } else {
                return false;                
            }
        }
    }
    
    class TtsInitListener implements TextToSpeech.OnInitListener {
        @Override
        public void onInit(int status) {
            // status can be either TextToSpeech.SUCCESS or TextToSpeech.ERROR.
            Log.e(TAG, "onInit for tts");
            if (status != TextToSpeech.SUCCESS) {
                // Initialization failed.
                Log.e(TAG, "Could not initialize TextToSpeech.");
                return;
            }

            if (mTts == null) {
                Log.e(TAG, "null tts");
                return;
            }

            int result = mTts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
               // Lanuage data is missing or the language is not supported.
                Log.e(TAG, "Language is not available.");
                return;
            }
            mTts.setOnUtteranceCompletedListener(new MyUtteranceCompletedListener(UTTERANCE));
            mTtsInited = true;
         }
    }

    class MyUtteranceCompletedListener implements OnUtteranceCompletedListener {
        private final String mExpectedUtterance;
        
        public MyUtteranceCompletedListener(String expectedUtteranceId) {
            mExpectedUtterance = expectedUtteranceId;
        }
        
        @Override
        public void onUtteranceCompleted(String utteranceId) {
            Log.e(TAG, "onUtteranceCompleted " + utteranceId);
            if (mTtsToFile) {
                if (mSampleFile != null && mSampleFile.exists()) {
                    MediaPlayer mediaPlayer = new MediaPlayer();
                    try {
                        mediaPlayer.setDataSource(mSampleFile.getPath());
                        mediaPlayer.setAudioStreamType(AudioManager.STREAM_BLUETOOTH_SCO);
                        mediaPlayer.prepare();
                    } catch (Exception ex) {
                        Log.e(TAG, "mMediaPlayercreate failed:", ex);
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
    
                    if (mediaPlayer != null) {
                        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer mp) {
                                mp.release();
                                if (mSampleFile != null && mSampleFile.exists()) {
                                    mSampleFile.delete();
                                    mSampleFile = null;
                                }
                              mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO,
                              mOriginalVoiceVolume, 0);
//                              Debug.stopMethodTracing();
                            }
                        });
                        mediaPlayer.start();
                    }
                } else {
                    Log.e(TAG, "synthesizeToFile did not create file");
                }
            } else {
                mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO,
                        mOriginalVoiceVolume, 0);
//                Debug.stopMethodTracing();
            }
            
            Log.e(TAG, "end speak, volume: "+mOriginalVoiceVolume);
        }
    }

    
    private View.OnKeyListener mSpeakKeyListener
    = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (!mTtsInited) {
                            Log.e(TAG, "Tts not inited ");
                            return false;
                        }
                        mOriginalVoiceVolume = mAudioManager.getStreamVolume(
                                AudioManager.STREAM_BLUETOOTH_SCO);
                        Log.e(TAG, "start speak, volume: "+mOriginalVoiceVolume);
                        mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO,
                                mOriginalVoiceVolume/2, 0);

                        // we now have SCO connection and TTS, so we can start.
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
//                                Debug.startMethodTracing("tts");

                                if (mTtsToFile) {
                                    if (mSampleFile != null && mSampleFile.exists()) {
                                        mSampleFile.delete();
                                        mSampleFile = null;
                                    }
                                    mSampleFile = new File(Environment.getExternalStorageDirectory(), "mytts.wav");
                                    mTts.synthesizeToFile(mSpeakText.getText().toString(), mTtsParams, mSampleFile.getPath());
                                } else {
                                    mTts.speak(mSpeakText.getText().toString(),
                                        TextToSpeech.QUEUE_FLUSH,
                                        mTtsParams);
                                }
                            }
                        });
                        return true;
                }
            }
            return false;
        }
    };
    
    private static final String[] mModeStrings = {
        "NORMAL", "RINGTONE", "IN_CALL", "IN_COMMUNICATION"
    };
    
    private Spinner.OnItemSelectedListener mModeChanged
        = new Spinner.OnItemSelectedListener() {
        @Override
        public void onItemSelected(android.widget.AdapterView av, View v,
                    int position, long id) {
            if (mCurrentMode != position) {
                mCurrentMode = position;
                mAudioManager.setMode(mCurrentMode);
            }
        }
        
        @Override
        public void onNothingSelected(android.widget.AdapterView av) {
        }
    };

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothHeadset = (BluetoothHeadset) proxy;
            List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();
            if (deviceList.size() > 0) {
                mBluetoothHeadsetDevice = deviceList.get(0);
            } else {
                mBluetoothHeadsetDevice = null;
            }
        }
        @Override
        public void onServiceDisconnected(int profile) {
            if (mBluetoothHeadset != null) {
                List<BluetoothDevice> devices = mBluetoothHeadset.getConnectedDevices();
                if (devices.size() == 0) {
                    mBluetoothHeadsetDevice = null;
                }
                mBluetoothHeadset = null;
            }
        }
    };

    private int mChangedState = -1;
    private int mUpdatedState = -1;
    private int mUpdatedPrevState = -1;
    
    private class ScoBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                mVdStateTxt.setText(Integer.toString(state));
                Log.e(TAG, "BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED: "+state);
            } else if (action.equals(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED)) {
                mChangedState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                Log.e(TAG, "ACTION_SCO_AUDIO_STATE_CHANGED: "+mChangedState);
                mScoStateTxt.setText("changed: "+Integer.toString(mChangedState)+ 
                        " updated: "+Integer.toString(mUpdatedState)+
                        " prev updated: "+Integer.toString(mUpdatedPrevState));
            } else if (action.equals(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) {
                mUpdatedState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                mUpdatedPrevState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1);
                Log.e(TAG, "ACTION_SCO_AUDIO_STATE_UPDATED, state: "+mUpdatedState+" prev state: "+mUpdatedPrevState);
                mScoStateTxt.setText("changed: "+Integer.toString(mChangedState)+ 
                        " updated: "+Integer.toString(mUpdatedState)+
                        " prev updated: "+Integer.toString(mUpdatedPrevState));
                if (mForceScoOn && mUpdatedState == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    mForceScoOn = false;
                    mScoButton.setChecked(mForceScoOn);
                    mAudioManager.stopBluetoothSco();
                }
            }
        }
    }

}
