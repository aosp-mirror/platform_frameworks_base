/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.test.soundtrigger;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.soundtrigger.SoundTriggerDetector;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

public class SoundTriggerTestService extends Service {
    private static final String TAG = "SoundTriggerTestSrv";
    private static final String INTENT_ACTION = "com.android.intent.action.MANAGE_SOUND_TRIGGER";

    // Binder given to clients.
    private final IBinder mBinder;
    private final Map<UUID, ModelInfo> mModelInfoMap;
    private SoundTriggerUtil mSoundTriggerUtil;
    private Random mRandom;
    private UserActivity mUserActivity;

    public interface UserActivity {
        void addModel(UUID modelUuid, String state);
        void setModelState(UUID modelUuid, String state);
        void showMessage(String msg, boolean showToast);
        void handleDetection(UUID modelUuid);
    }

    public SoundTriggerTestService() {
        super();
        mRandom = new Random();
        mModelInfoMap = new HashMap();
        mBinder = new SoundTriggerTestBinder();
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (mModelInfoMap.isEmpty()) {
            mSoundTriggerUtil = new SoundTriggerUtil(this);
            loadModelsInDataDir();
        }

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_ACTION);
        registerReceiver(mBroadcastReceiver, filter);

        // Make sure the data directory exists, and we're the owner of it.
        try {
            getFilesDir().mkdir();
        } catch (Exception e) {
            // Don't care - we either made it, or it already exists.
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAllRecognitions();
        unregisterReceiver(mBroadcastReceiver);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && INTENT_ACTION.equals(intent.getAction())) {
                String command = intent.getStringExtra("command");
                if (command == null) {
                    Log.e(TAG, "No 'command' specified in " + INTENT_ACTION);
                } else {
                    try {
                        if (command.equals("load")) {
                            loadModel(getModelUuidFromIntent(intent));
                        } else if (command.equals("unload")) {
                            unloadModel(getModelUuidFromIntent(intent));
                        } else if (command.equals("start")) {
                            startRecognition(getModelUuidFromIntent(intent));
                        } else if (command.equals("stop")) {
                            stopRecognition(getModelUuidFromIntent(intent));
                        } else if (command.equals("play_trigger")) {
                            playTriggerAudio(getModelUuidFromIntent(intent));
                        } else if (command.equals("play_captured")) {
                            playCapturedAudio(getModelUuidFromIntent(intent));
                        } else if (command.equals("set_capture")) {
                            setCaptureAudio(getModelUuidFromIntent(intent),
                                    intent.getBooleanExtra("enabled", true));
                        } else if (command.equals("set_capture_timeout")) {
                            setCaptureAudioTimeout(getModelUuidFromIntent(intent),
                                    intent.getIntExtra("timeout", 5000));
                        } else {
                            Log.e(TAG, "Unknown command '" + command + "'");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to process " + command, e);
                    }
                }
            }
        }
    };

    private UUID getModelUuidFromIntent(Intent intent) {
        // First, see if the specified the UUID straight up.
        String value = intent.getStringExtra("modelUuid");
        if (value != null) {
            return UUID.fromString(value);
        }

        // If they specified a name, use that to iterate through the map of models and find it.
        value = intent.getStringExtra("name");
        if (value != null) {
            for (ModelInfo modelInfo : mModelInfoMap.values()) {
                if (value.equals(modelInfo.name)) {
                    return modelInfo.modelUuid;
                }
            }
            Log.e(TAG, "Failed to find a matching model with name '" + value + "'");
        }

        // We couldn't figure out what they were asking for.
        throw new RuntimeException("Failed to get model from intent - specify either " +
                "'modelUuid' or 'name'");
    }

    /**
     * Will be called when the service is killed (through swipe aways, not if we're force killed).
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopAllRecognitions();
        stopSelf();
    }

    @Override
    public synchronized IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class SoundTriggerTestBinder extends Binder {
        SoundTriggerTestService getService() {
            // Return instance of our parent so clients can call public methods.
            return SoundTriggerTestService.this;
        }
    }

    public synchronized void setUserActivity(UserActivity activity) {
        mUserActivity = activity;
        if (mUserActivity != null) {
            for (Map.Entry<UUID, ModelInfo> entry : mModelInfoMap.entrySet()) {
                mUserActivity.addModel(entry.getKey(), entry.getValue().name);
                mUserActivity.setModelState(entry.getKey(), entry.getValue().state);
            }
        }
    }

    private synchronized void stopAllRecognitions() {
        for (ModelInfo modelInfo : mModelInfoMap.values()) {
            if (modelInfo.detector != null) {
                Log.i(TAG, "Stopping recognition for " + modelInfo.name);
                try {
                    modelInfo.detector.stopRecognition();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to stop recognition", e);
                }
            }
        }
    }

    // Helper struct for holding information about a model.
    public static class ModelInfo {
        public String name;
        public String state;
        public UUID modelUuid;
        public UUID vendorUuid;
        public MediaPlayer triggerAudioPlayer;
        public SoundTriggerDetector detector;
        public byte modelData[];
        public boolean captureAudio;
        public int captureAudioMs;
        public AudioTrack captureAudioTrack;
    }

    private GenericSoundModel createNewSoundModel(ModelInfo modelInfo) {
        return new GenericSoundModel(modelInfo.modelUuid, modelInfo.vendorUuid,
                modelInfo.modelData);
    }

    public synchronized void loadModel(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }

        postMessage("Loading model: " + modelInfo.name);

        GenericSoundModel soundModel = createNewSoundModel(modelInfo);

        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(soundModel);
        if (status) {
            postToast("Successfully loaded " + modelInfo.name + ", UUID=" + soundModel.uuid);
            setModelState(modelInfo, "Loaded");
        } else {
            postErrorToast("Failed to load " + modelInfo.name + ", UUID=" + soundModel.uuid + "!");
            setModelState(modelInfo, "Failed to load");
        }
    }

    public synchronized void unloadModel(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }

        postMessage("Unloading model: " + modelInfo.name);

        GenericSoundModel soundModel = mSoundTriggerUtil.getSoundModel(modelUuid);
        if (soundModel == null) {
            postErrorToast("Sound model not found for " + modelInfo.name + "!");
            return;
        }
        modelInfo.detector = null;
        boolean status = mSoundTriggerUtil.deleteSoundModel(modelUuid);
        if (status) {
            postToast("Successfully unloaded " + modelInfo.name + ", UUID=" + soundModel.uuid);
            setModelState(modelInfo, "Unloaded");
        } else {
            postErrorToast("Failed to unload " +
                    modelInfo.name + ", UUID=" + soundModel.uuid + "!");
            setModelState(modelInfo, "Failed to unload");
        }
    }

    public synchronized void reloadModel(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }
        postMessage("Reloading model: " + modelInfo.name);
        GenericSoundModel soundModel = mSoundTriggerUtil.getSoundModel(modelUuid);
        if (soundModel == null) {
            postErrorToast("Sound model not found for " + modelInfo.name + "!");
            return;
        }
        GenericSoundModel updated = createNewSoundModel(modelInfo);
        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(updated);
        if (status) {
            postToast("Successfully reloaded " + modelInfo.name + ", UUID=" + modelInfo.modelUuid);
            setModelState(modelInfo, "Reloaded");
        } else {
            postErrorToast("Failed to reload "
                    + modelInfo.name + ", UUID=" + modelInfo.modelUuid + "!");
            setModelState(modelInfo, "Failed to reload");
        }
    }

    public synchronized void startRecognition(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }

        if (modelInfo.detector == null) {
            postMessage("Creating SoundTriggerDetector for " + modelInfo.name);
            modelInfo.detector = mSoundTriggerUtil.createSoundTriggerDetector(
                    modelUuid, new DetectorCallback(modelInfo));
        }

        postMessage("Starting recognition for " + modelInfo.name + ", UUID=" + modelInfo.modelUuid);
        if (modelInfo.detector.startRecognition(modelInfo.captureAudio ?
                SoundTriggerDetector.RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO :
                SoundTriggerDetector.RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS)) {
            setModelState(modelInfo, "Started");
        } else {
            postErrorToast("Fast failure attempting to start recognition for " +
                    modelInfo.name + ", UUID=" + modelInfo.modelUuid);
            setModelState(modelInfo, "Failed to start");
        }
    }

    public synchronized void stopRecognition(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }

        if (modelInfo.detector == null) {
            postErrorToast("Stop called on null detector for " +
                    modelInfo.name + ", UUID=" + modelInfo.modelUuid);
            return;
        }
        postMessage("Triggering stop recognition for " +
                modelInfo.name + ", UUID=" + modelInfo.modelUuid);
        if (modelInfo.detector.stopRecognition()) {
            setModelState(modelInfo, "Stopped");
        } else {
            postErrorToast("Fast failure attempting to stop recognition for " +
                    modelInfo.name + ", UUID=" + modelInfo.modelUuid);
            setModelState(modelInfo, "Failed to stop");
        }
    }

    public synchronized void playTriggerAudio(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }
        if (modelInfo.triggerAudioPlayer != null) {
            postMessage("Playing trigger audio for " + modelInfo.name);
            modelInfo.triggerAudioPlayer.start();
        } else {
            postMessage("No trigger audio for " + modelInfo.name);
        }
    }

    public synchronized void playCapturedAudio(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }
        if (modelInfo.captureAudioTrack != null) {
            postMessage("Playing captured audio for " + modelInfo.name);
            modelInfo.captureAudioTrack.stop();
            modelInfo.captureAudioTrack.reloadStaticData();
            modelInfo.captureAudioTrack.play();
        } else {
            postMessage("No captured audio for " + modelInfo.name);
        }
    }

    public synchronized void setCaptureAudioTimeout(UUID modelUuid, int captureTimeoutMs) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }
        modelInfo.captureAudioMs = captureTimeoutMs;
        Log.i(TAG, "Set " + modelInfo.name + " capture audio timeout to " +
                captureTimeoutMs + "ms");
    }

    public synchronized void setCaptureAudio(UUID modelUuid, boolean captureAudio) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        if (modelInfo == null) {
            postError("Could not find model for: " + modelUuid.toString());
            return;
        }
        modelInfo.captureAudio = captureAudio;
        Log.i(TAG, "Set " + modelInfo.name + " capture audio to " + captureAudio);
    }

    public synchronized boolean hasMicrophonePermission() {
        return getBaseContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public synchronized boolean modelHasTriggerAudio(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        return modelInfo != null && modelInfo.triggerAudioPlayer != null;
    }

    public synchronized boolean modelWillCaptureTriggerAudio(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        return modelInfo != null && modelInfo.captureAudio;
    }

    public synchronized boolean modelHasCapturedAudio(UUID modelUuid) {
        ModelInfo modelInfo = mModelInfoMap.get(modelUuid);
        return modelInfo != null && modelInfo.captureAudioTrack != null;
    }

    private void loadModelsInDataDir() {
        // Load all the models in the data dir.
        boolean loadedModel = false;
        for (File file : getFilesDir().listFiles()) {
            // Find meta-data in .properties files, ignore everything else.
            if (!file.getName().endsWith(".properties")) {
                continue;
            }
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(file));
                createModelInfo(properties);
                loadedModel = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load properties file " + file.getName());
            }
        }

        // Create a few dummy models if we didn't load anything.
        if (!loadedModel) {
            Properties dummyModelProperties = new Properties();
            for (String name : new String[]{"1", "2", "3"}) {
                dummyModelProperties.setProperty("name", "Model " + name);
                createModelInfo(dummyModelProperties);
            }
        }
    }

    /** Parses a Properties collection to generate a sound model.
     *
     * Missing keys are filled in with default/random values.
     * @param properties Has the required 'name' property, but the remaining 'modelUuid',
     *                   'vendorUuid', 'triggerAudio', and 'dataFile' optional properties.
     *
     */
    private synchronized void createModelInfo(Properties properties) {
        try {
            ModelInfo modelInfo = new ModelInfo();

            if (!properties.containsKey("name")) {
                throw new RuntimeException("must have a 'name' property");
            }
            modelInfo.name = properties.getProperty("name");

            if (properties.containsKey("modelUuid")) {
                modelInfo.modelUuid = UUID.fromString(properties.getProperty("modelUuid"));
            } else {
                modelInfo.modelUuid = UUID.randomUUID();
            }

            if (properties.containsKey("vendorUuid")) {
                modelInfo.vendorUuid = UUID.fromString(properties.getProperty("vendorUuid"));
            } else {
                modelInfo.vendorUuid = UUID.randomUUID();
            }

            if (properties.containsKey("triggerAudio")) {
                modelInfo.triggerAudioPlayer = MediaPlayer.create(this, Uri.parse(
                        getFilesDir().getPath() + "/" + properties.getProperty("triggerAudio")));
                if (modelInfo.triggerAudioPlayer.getDuration() == 0) {
                    modelInfo.triggerAudioPlayer.release();
                    modelInfo.triggerAudioPlayer = null;
                }
            }

            if (properties.containsKey("dataFile")) {
                File modelDataFile = new File(
                        getFilesDir().getPath() + "/" + properties.getProperty("dataFile"));
                modelInfo.modelData = new byte[(int) modelDataFile.length()];
                FileInputStream input = new FileInputStream(modelDataFile);
                input.read(modelInfo.modelData, 0, modelInfo.modelData.length);
            } else {
                modelInfo.modelData = new byte[1024];
                mRandom.nextBytes(modelInfo.modelData);
            }

            modelInfo.captureAudioMs = Integer.parseInt((String) properties.getOrDefault(
                    "captureAudioDurationMs", "5000"));

            // TODO: Add property support for keyphrase models when they're exposed by the
            // service.

            // Update our maps containing the button -> id and id -> modelInfo.
            mModelInfoMap.put(modelInfo.modelUuid, modelInfo);
            if (mUserActivity != null) {
                mUserActivity.addModel(modelInfo.modelUuid, modelInfo.name);
                mUserActivity.setModelState(modelInfo.modelUuid, modelInfo.state);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error parsing properties for " + properties.getProperty("name"), e);
        }
    }

    private class CaptureAudioRecorder implements Runnable {
        private final ModelInfo mModelInfo;
        private final SoundTriggerDetector.EventPayload mEvent;

        public CaptureAudioRecorder(ModelInfo modelInfo, SoundTriggerDetector.EventPayload event) {
            mModelInfo = modelInfo;
            mEvent = event;
        }

        @Override
        public void run() {
            AudioFormat format = mEvent.getCaptureAudioFormat();
            if (format == null) {
                postErrorToast("No audio format in recognition event.");
                return;
            }

            AudioRecord audioRecord = null;
            AudioTrack playbackTrack = null;
            try {
                // Inform the audio flinger that we really do want the stream from the soundtrigger.
                AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
                attributesBuilder.setInternalCapturePreset(1999);
                AudioAttributes attributes = attributesBuilder.build();

                // Make sure we understand this kind of playback so we know how many bytes to read.
                String encoding;
                int bytesPerSample;
                switch (format.getEncoding()) {
                    case AudioFormat.ENCODING_PCM_8BIT:
                        encoding = "8bit";
                        bytesPerSample = 1;
                        break;
                    case AudioFormat.ENCODING_PCM_16BIT:
                        encoding = "16bit";
                        bytesPerSample = 2;
                        break;
                    case AudioFormat.ENCODING_PCM_FLOAT:
                        encoding = "float";
                        bytesPerSample = 4;
                        break;
                    default:
                        throw new RuntimeException("Unhandled audio format in event");
                }

                int bytesRequired = format.getSampleRate() * format.getChannelCount() *
                        bytesPerSample * mModelInfo.captureAudioMs / 1000;
                int minBufferSize = AudioRecord.getMinBufferSize(
                        format.getSampleRate(), format.getChannelMask(), format.getEncoding());
                if (minBufferSize > bytesRequired) {
                    bytesRequired = minBufferSize;
                }

                // Make an AudioTrack so we can play the data back out after it's finished
                // recording.
                try {
                    int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                    if (format.getChannelCount() == 2) {
                        channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                    } else if (format.getChannelCount() >= 3) {
                        throw new RuntimeException(
                                "Too many channels in captured audio for playback");
                    }

                    playbackTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                            format.getSampleRate(), channelConfig, format.getEncoding(),
                            bytesRequired, AudioTrack.MODE_STATIC);
                } catch (Exception e) {
                    Log.e(TAG, "Exception creating playback track", e);
                    postErrorToast("Failed to create playback track: " + e.getMessage());
                }

                audioRecord = new AudioRecord(attributes, format, bytesRequired,
                        mEvent.getCaptureSession());

                byte[] buffer = new byte[bytesRequired];

                // Create a file so we can save the output data there for analysis later.
                FileOutputStream fos  = null;
                try {
                    fos = new FileOutputStream( new File(
                            getFilesDir() + File.separator + mModelInfo.name.replace(' ', '_') +
                                    "_capture_" + format.getChannelCount() + "ch_" +
                                    format.getSampleRate() + "hz_" + encoding + ".pcm"));
                } catch (IOException e) {
                    Log.e(TAG, "Failed to open output for saving PCM data", e);
                    postErrorToast("Failed to open output for saving PCM data: " + e.getMessage());
                }

                // Inform the user we're recording.
                setModelState(mModelInfo, "Recording");
                audioRecord.startRecording();
                while (bytesRequired > 0) {
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead == -1) {
                        break;
                    }
                    if (fos != null) {
                        fos.write(buffer, 0, bytesRead);
                    }
                    if (playbackTrack != null) {
                        playbackTrack.write(buffer, 0, bytesRead);
                    }
                    bytesRequired -= bytesRead;
                }
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error recording trigger audio", e);
                postErrorToast("Error recording trigger audio: " + e.getMessage());
            } finally {
                if (audioRecord != null) {
                    audioRecord.release();
                }
                synchronized (SoundTriggerTestService.this) {
                    if (mModelInfo.captureAudioTrack != null) {
                        mModelInfo.captureAudioTrack.release();
                    }
                    mModelInfo.captureAudioTrack = playbackTrack;
                }
                setModelState(mModelInfo, "Recording finished");
            }
        }
    }

    // Implementation of SoundTriggerDetector.Callback.
    private class DetectorCallback extends SoundTriggerDetector.Callback {
        private final ModelInfo mModelInfo;

        public DetectorCallback(ModelInfo modelInfo) {
            mModelInfo = modelInfo;
        }

        public void onAvailabilityChanged(int status) {
            postMessage(mModelInfo.name + " availability changed to: " + status);
        }

        public void onDetected(SoundTriggerDetector.EventPayload event) {
            postMessage(mModelInfo.name + " onDetected(): " + eventPayloadToString(event));
            synchronized (SoundTriggerTestService.this) {
                if (mUserActivity != null) {
                    mUserActivity.handleDetection(mModelInfo.modelUuid);
                }
                if (mModelInfo.captureAudio) {
                    new Thread(new CaptureAudioRecorder(mModelInfo, event)).start();
                }
            }
        }

        public void onError() {
            postMessage(mModelInfo.name + " onError()");
            setModelState(mModelInfo, "Error");
        }

        public void onRecognitionPaused() {
            postMessage(mModelInfo.name + " onRecognitionPaused()");
            setModelState(mModelInfo, "Paused");
        }

        public void onRecognitionResumed() {
            postMessage(mModelInfo.name + " onRecognitionResumed()");
            setModelState(mModelInfo, "Resumed");
        }
    }

    private String eventPayloadToString(SoundTriggerDetector.EventPayload event) {
        String result = "EventPayload(";
        AudioFormat format =  event.getCaptureAudioFormat();
        result = result + "AudioFormat: " + ((format == null) ? "null" : format.toString());
        byte[] triggerAudio = event.getTriggerAudio();
        result = result + "TriggerAudio: " + (triggerAudio == null ? "null" : triggerAudio.length);
        result = result + "CaptureSession: " + event.getCaptureSession();
        result += " )";
        return result;
    }

    private void postMessage(String msg) {
        showMessage(msg, Log.INFO, false);
    }

    private void postError(String msg) {
        showMessage(msg, Log.ERROR, false);
    }

    private void postToast(String msg) {
        showMessage(msg, Log.INFO, true);
    }

    private void postErrorToast(String msg) {
        showMessage(msg, Log.ERROR, true);
    }

    /** Logs the message at the specified level, then forwards it to the activity if present. */
    private synchronized void showMessage(String msg, int logLevel, boolean showToast) {
        Log.println(logLevel, TAG, msg);
        if (mUserActivity != null) {
            mUserActivity.showMessage(msg, showToast);
        }
    }

    private synchronized void setModelState(ModelInfo modelInfo, String state) {
        modelInfo.state = state;
        if (mUserActivity != null) {
            mUserActivity.setModelState(modelInfo.modelUuid, modelInfo.state);
        }
    }
}
