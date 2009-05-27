/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.tts;

import android.tts.ITts.Stub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @hide Synthesizes speech from text. This is implemented as a service so that
 *       other applications can call the TTS without needing to bundle the TTS
 *       in the build.
 *
 */
public class TtsService extends Service implements OnCompletionListener {

  private class SpeechItem {
    public static final int SPEECH = 0;
    public static final int EARCON = 1;
    public static final int SILENCE = 2;
    public String mText = null;
    public ArrayList<String> mParams = null;
    public int mType = SPEECH;
    public long mDuration = 0;

    public SpeechItem(String text, ArrayList<String> params, int itemType) {
      mText = text;
      mParams = params;
      mType = itemType;
    }

    public SpeechItem(long silenceTime) {
      mDuration = silenceTime;
    }
  }

  /**
   * Contains the information needed to access a sound resource; the name of
   * the package that contains the resource and the resID of the resource
   * within that package.
   */
  private class SoundResource {
    public String mSourcePackageName = null;
    public int mResId = -1;
    public String mFilename = null;

    public SoundResource(String packageName, int id) {
      mSourcePackageName = packageName;
      mResId = id;
      mFilename = null;
    }

    public SoundResource(String file) {
      mSourcePackageName = null;
      mResId = -1;
      mFilename = file;
    }
  }

  private static final String ACTION = "android.intent.action.USE_TTS";
  private static final String CATEGORY = "android.intent.category.TTS";
  private static final String PKGNAME = "android.tts";

  final RemoteCallbackList<ITtsCallback> mCallbacks = new RemoteCallbackList<ITtsCallback>();

  private Boolean isSpeaking;
  private ArrayList<SpeechItem> speechQueue;
  private HashMap<String, SoundResource> earcons;
  private HashMap<String, SoundResource> utterances;
  private MediaPlayer player;
  private TtsService self;

  private SharedPreferences prefs;

  private final ReentrantLock speechQueueLock = new ReentrantLock();
  private final ReentrantLock synthesizerLock = new ReentrantLock();

  // TODO support multiple SpeechSynthesis objects
  private SynthProxy nativeSynth;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.i("TTS", "TTS starting");


    // TODO: Make this work when the settings are done in the main Settings
    // app.
    prefs = PreferenceManager.getDefaultSharedPreferences(this);

    // TODO: This should be changed to work by requesting the path
    // from the default engine.
    nativeSynth = new SynthProxy(prefs.getString("engine_pref", ""));


    self = this;
    isSpeaking = false;

    earcons = new HashMap<String, SoundResource>();
    utterances = new HashMap<String, SoundResource>();

    speechQueue = new ArrayList<SpeechItem>();
    player = null;

    setLanguage(prefs.getString("lang_pref", "en-rUS"));
    setSpeechRate(Integer.parseInt(prefs.getString("rate_pref", "140")));
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    // Don't hog the media player
    cleanUpPlayer();

    nativeSynth.shutdown();

    // Unregister all callbacks.
    mCallbacks.kill();
  }

  private void setSpeechRate(int rate) {
    if (prefs.getBoolean("override_pref", false)) {
      // This is set to the default here so that the preview in the prefs
      // activity will show the change without a restart, even if apps are
      // not allowed to change the defaults.
      rate = Integer.parseInt(prefs.getString("rate_pref", "140"));
    }
    nativeSynth.setSpeechRate(rate);
  }

  private void setLanguage(String lang) {
    if (prefs.getBoolean("override_pref", false)) {
      // This is set to the default here so that the preview in the prefs
      // activity will show the change without a restart, even if apps are
      // not
      // allowed to change the defaults.
      lang = prefs.getString("lang_pref", "en-rUS");
    }
    nativeSynth.setLanguage(lang);
  }

  private void setEngine(String engineName, String[] requestedLanguages,
      int strictness) {
    // TODO: Implement engine selection code here.
    Intent engineIntent = new Intent(
        "android.intent.action.START_TTS_ENGINE");
    if (engineName != null) {
      engineIntent.addCategory("android.intent.action.tts_engine."
          + engineName);
    }
    for (int i = 0; i < requestedLanguages.length; i++) {
      engineIntent.addCategory("android.intent.action.tts_lang."
          + requestedLanguages[i]);
    }
    ResolveInfo[] enginesArray = new ResolveInfo[0];
    PackageManager pm = getPackageManager();
    enginesArray = pm.queryIntentActivities(engineIntent, 0).toArray(
        enginesArray);
  }

  private void setEngine(Intent engineIntent) {
    // TODO: Implement engine selection code here.
  }

  private int getEngineStatus() {
    // TODO: Proposal - add a sanity check method that
    // TTS engine plugins must implement.
    return 0;
  }

  /**
   * Adds a sound resource to the TTS.
   *
   * @param text
   *            The text that should be associated with the sound resource
   * @param packageName
   *            The name of the package which has the sound resource
   * @param resId
   *            The resource ID of the sound within its package
   */
  private void addSpeech(String text, String packageName, int resId) {
    utterances.put(text, new SoundResource(packageName, resId));
  }

  /**
   * Adds a sound resource to the TTS.
   *
   * @param text
   *            The text that should be associated with the sound resource
   * @param filename
   *            The filename of the sound resource. This must be a complete
   *            path like: (/sdcard/mysounds/mysoundbite.mp3).
   */
  private void addSpeech(String text, String filename) {
    utterances.put(text, new SoundResource(filename));
  }

  /**
   * Adds a sound resource to the TTS as an earcon.
   *
   * @param earcon
   *            The text that should be associated with the sound resource
   * @param packageName
   *            The name of the package which has the sound resource
   * @param resId
   *            The resource ID of the sound within its package
   */
  private void addEarcon(String earcon, String packageName, int resId) {
    earcons.put(earcon, new SoundResource(packageName, resId));
  }

  /**
   * Adds a sound resource to the TTS as an earcon.
   *
   * @param earcon
   *            The text that should be associated with the sound resource
   * @param filename
   *            The filename of the sound resource. This must be a complete
   *            path like: (/sdcard/mysounds/mysoundbite.mp3).
   */
  private void addEarcon(String earcon, String filename) {
    earcons.put(earcon, new SoundResource(filename));
  }

  /**
   * Speaks the given text using the specified queueing mode and parameters.
   *
   * @param text
   *            The text that should be spoken
   * @param queueMode
   *            0 for no queue (interrupts all previous utterances), 1 for
   *            queued
   * @param params
   *            An ArrayList of parameters. This is not implemented for all
   *            engines.
   */
  private void speak(String text, int queueMode, ArrayList<String> params) {
    if (queueMode == 0) {
      stop();
    }
    speechQueue.add(new SpeechItem(text, params, SpeechItem.SPEECH));
    if (!isSpeaking) {
      processSpeechQueue();
    }
  }

  /**
   * Plays the earcon using the specified queueing mode and parameters.
   *
   * @param earcon
   *            The earcon that should be played
   * @param queueMode
   *            0 for no queue (interrupts all previous utterances), 1 for
   *            queued
   * @param params
   *            An ArrayList of parameters. This is not implemented for all
   *            engines.
   */
  private void playEarcon(String earcon, int queueMode,
      ArrayList<String> params) {
    if (queueMode == 0) {
      stop();
    }
    speechQueue.add(new SpeechItem(earcon, params, SpeechItem.EARCON));
    if (!isSpeaking) {
      processSpeechQueue();
    }
  }

  /**
   * Stops all speech output and removes any utterances still in the queue.
   */
  private void stop() {
    Log.i("TTS", "Stopping");
    speechQueue.clear();

    nativeSynth.stop();
    isSpeaking = false;
    if (player != null) {
      try {
        player.stop();
      } catch (IllegalStateException e) {
        // Do nothing, the player is already stopped.
      }
    }
    Log.i("TTS", "Stopped");
  }

  public void onCompletion(MediaPlayer arg0) {
    processSpeechQueue();
  }

  private void playSilence(long duration, int queueMode,
      ArrayList<String> params) {
    if (queueMode == 0) {
      stop();
    }
    speechQueue.add(new SpeechItem(duration));
    if (!isSpeaking) {
      processSpeechQueue();
    }
  }

  private void silence(final long duration) {
    class SilenceThread implements Runnable {
      public void run() {
        try {
          Thread.sleep(duration);
        } catch (InterruptedException e) {
          e.printStackTrace();
        } finally {
          processSpeechQueue();
        }
      }
    }
    Thread slnc = (new Thread(new SilenceThread()));
    slnc.setPriority(Thread.MIN_PRIORITY);
    slnc.start();
  }

  private void speakInternalOnly(final String text,
      final ArrayList<String> params) {
    class SynthThread implements Runnable {
      public void run() {
        boolean synthAvailable = false;
        try {
          synthAvailable = synthesizerLock.tryLock();
          if (!synthAvailable) {
            Thread.sleep(100);
            Thread synth = (new Thread(new SynthThread()));
            synth.setPriority(Thread.MIN_PRIORITY);
            synth.start();
            return;
          }
          nativeSynth.speak(text);
        } catch (InterruptedException e) {
          e.printStackTrace();
        } finally {
          // This check is needed because finally will always run;
          // even if the
          // method returns somewhere in the try block.
          if (synthAvailable) {
            synthesizerLock.unlock();
          }
        }
      }
    }
    Thread synth = (new Thread(new SynthThread()));
    synth.setPriority(Thread.MIN_PRIORITY);
    synth.start();
  }

  private SoundResource getSoundResource(SpeechItem speechItem) {
    SoundResource sr = null;
    String text = speechItem.mText;
    if (speechItem.mType == SpeechItem.SILENCE) {
      // Do nothing if this is just silence
    } else if (speechItem.mType == SpeechItem.EARCON) {
      sr = earcons.get(text);
    } else {
      sr = utterances.get(text);
    }
    return sr;
  }

  private void dispatchSpeechCompletedCallbacks(String mark) {
    Log.i("TTS callback", "dispatch started");
    // Broadcast to all clients the new value.
    final int N = mCallbacks.beginBroadcast();
    for (int i = 0; i < N; i++) {
      try {
        mCallbacks.getBroadcastItem(i).markReached(mark);
      } catch (RemoteException e) {
        // The RemoteCallbackList will take care of removing
        // the dead object for us.
      }
    }
    mCallbacks.finishBroadcast();
    Log.i("TTS callback", "dispatch completed to " + N);
  }

  private void processSpeechQueue() {
    boolean speechQueueAvailable = false;
    try {
      speechQueueAvailable = speechQueueLock.tryLock();
      if (!speechQueueAvailable) {
        return;
      }
      if (speechQueue.size() < 1) {
        isSpeaking = false;
        // Dispatch a completion here as this is the
        // only place where speech completes normally.
        // Nothing left to say in the queue is a special case
        // that is always a "mark" - associated text is null.
        dispatchSpeechCompletedCallbacks("");
        return;
      }

      SpeechItem currentSpeechItem = speechQueue.get(0);
      isSpeaking = true;
      SoundResource sr = getSoundResource(currentSpeechItem);
      // Synth speech as needed - synthesizer should call
      // processSpeechQueue to continue running the queue
      Log.i("TTS processing: ", currentSpeechItem.mText);
      if (sr == null) {
        if (currentSpeechItem.mType == SpeechItem.SPEECH) {
          // TODO: Split text up into smaller chunks before accepting
          // them
          // for processing.
          speakInternalOnly(currentSpeechItem.mText,
              currentSpeechItem.mParams);
        } else {
          // This is either silence or an earcon that was missing
          silence(currentSpeechItem.mDuration);
        }
      } else {
        cleanUpPlayer();
        if (sr.mSourcePackageName == PKGNAME) {
          // Utterance is part of the TTS library
          player = MediaPlayer.create(this, sr.mResId);
        } else if (sr.mSourcePackageName != null) {
          // Utterance is part of the app calling the library
          Context ctx;
          try {
            ctx = this.createPackageContext(sr.mSourcePackageName,
                0);
          } catch (NameNotFoundException e) {
            e.printStackTrace();
            speechQueue.remove(0); // Remove it from the queue and
            // move on
            isSpeaking = false;
            return;
          }
          player = MediaPlayer.create(ctx, sr.mResId);
        } else {
          // Utterance is coming from a file
          player = MediaPlayer.create(this, Uri.parse(sr.mFilename));
        }

        // Check if Media Server is dead; if it is, clear the queue and
        // give up for now - hopefully, it will recover itself.
        if (player == null) {
          speechQueue.clear();
          isSpeaking = false;
          return;
        }
        player.setOnCompletionListener(this);
        try {
          player.start();
        } catch (IllegalStateException e) {
          speechQueue.clear();
          isSpeaking = false;
          cleanUpPlayer();
          return;
        }
      }
      if (speechQueue.size() > 0) {
        speechQueue.remove(0);
      }
    } finally {
      // This check is needed because finally will always run; even if the
      // method returns somewhere in the try block.
      if (speechQueueAvailable) {
        speechQueueLock.unlock();
      }
    }
  }

  private void cleanUpPlayer() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  /**
   * Synthesizes the given text using the specified queuing mode and
   * parameters.
   *
   * @param text
   *            The String of text that should be synthesized
   * @param params
   *            An ArrayList of parameters. The first element of this array
   *            controls the type of voice to use.
   * @param filename
   *            The string that gives the full output filename; it should be
   *            something like "/sdcard/myappsounds/mysound.wav".
   * @return A boolean that indicates if the synthesis succeeded
   */
  private boolean synthesizeToFile(String text, ArrayList<String> params,
      String filename, boolean calledFromApi) {
    // Only stop everything if this is a call made by an outside app trying
    // to
    // use the API. Do NOT stop if this is a call from within the service as
    // clearing the speech queue here would be a mistake.
    if (calledFromApi) {
      stop();
    }
    Log.i("TTS", "Synthesizing to " + filename);
    boolean synthAvailable = false;
    try {
      synthAvailable = synthesizerLock.tryLock();
      if (!synthAvailable) {
        return false;
      }
      // Don't allow a filename that is too long
      // TODO use platform constant
      if (filename.length() > 250) {
        return false;
      }
      nativeSynth.synthesizeToFile(text, filename);
    } finally {
      // This check is needed because finally will always run; even if the
      // method returns somewhere in the try block.
      if (synthAvailable) {
        synthesizerLock.unlock();
      }
    }
    Log.i("TTS", "Completed synthesis for " + filename);
    return true;
  }

  @Override
  public IBinder onBind(Intent intent) {
    if (ACTION.equals(intent.getAction())) {
      for (String category : intent.getCategories()) {
        if (category.equals(CATEGORY)) {
          return mBinder;
        }
      }
    }
    return null;
  }

  private final ITts.Stub mBinder = new Stub() {

    public void registerCallback(ITtsCallback cb) {
      if (cb != null)
        mCallbacks.register(cb);
    }

    public void unregisterCallback(ITtsCallback cb) {
      if (cb != null)
        mCallbacks.unregister(cb);
    }

    /**
     * Gives a hint about the type of engine that is preferred.
     *
     * @param selectedEngine
     *            The TTS engine that should be used
     */
    public void setEngine(String engineName, String[] supportedLanguages,
        int strictness) {
      self.setEngine(engineName, supportedLanguages, strictness);
    }

    /**
     * Specifies exactly what the engine has to support. Will always be
     * considered "strict"; can be used for implementing
     * optional/experimental features that are not supported by all engines.
     *
     * @param engineIntent
     *            An intent that specifies exactly what the engine has to
     *            support.
     */
    public void setEngineWithIntent(Intent engineIntent) {
      self.setEngine(engineIntent);
    }

    /**
     * Speaks the given text using the specified queueing mode and
     * parameters.
     *
     * @param text
     *            The text that should be spoken
     * @param queueMode
     *            0 for no queue (interrupts all previous utterances), 1 for
     *            queued
     * @param params
     *            An ArrayList of parameters. The first element of this
     *            array controls the type of voice to use.
     */
    public void speak(String text, int queueMode, String[] params) {
      ArrayList<String> speakingParams = new ArrayList<String>();
      if (params != null) {
        speakingParams = new ArrayList<String>(Arrays.asList(params));
      }
      self.speak(text, queueMode, speakingParams);
    }

    /**
     * Plays the earcon using the specified queueing mode and parameters.
     *
     * @param earcon
     *            The earcon that should be played
     * @param queueMode
     *            0 for no queue (interrupts all previous utterances), 1 for
     *            queued
     * @param params
     *            An ArrayList of parameters.
     */
    public void playEarcon(String earcon, int queueMode, String[] params) {
      ArrayList<String> speakingParams = new ArrayList<String>();
      if (params != null) {
        speakingParams = new ArrayList<String>(Arrays.asList(params));
      }
      self.playEarcon(earcon, queueMode, speakingParams);
    }

    /**
     * Plays the silence using the specified queueing mode and parameters.
     *
     * @param duration
     *            The duration of the silence that should be played
     * @param queueMode
     *            0 for no queue (interrupts all previous utterances), 1 for
     *            queued
     * @param params
     *            An ArrayList of parameters.
     */
    public void playSilence(long duration, int queueMode, String[] params) {
      ArrayList<String> speakingParams = new ArrayList<String>();
      if (params != null) {
        speakingParams = new ArrayList<String>(Arrays.asList(params));
      }
      self.playSilence(duration, queueMode, speakingParams);
    }


    /**
     * Stops all speech output and removes any utterances still in the
     * queue.
     */
    public void stop() {
      self.stop();
    }

    /**
     * Returns whether or not the TTS is speaking.
     *
     * @return Boolean to indicate whether or not the TTS is speaking
     */
    public boolean isSpeaking() {
      return (self.isSpeaking && (speechQueue.size() < 1));
    }

    /**
     * Adds a sound resource to the TTS.
     *
     * @param text
     *            The text that should be associated with the sound resource
     * @param packageName
     *            The name of the package which has the sound resource
     * @param resId
     *            The resource ID of the sound within its package
     */
    public void addSpeech(String text, String packageName, int resId) {
      self.addSpeech(text, packageName, resId);
    }

    /**
     * Adds a sound resource to the TTS.
     *
     * @param text
     *            The text that should be associated with the sound resource
     * @param filename
     *            The filename of the sound resource. This must be a
     *            complete path like: (/sdcard/mysounds/mysoundbite.mp3).
     */
    public void addSpeechFile(String text, String filename) {
      self.addSpeech(text, filename);
    }

    /**
     * Adds a sound resource to the TTS as an earcon.
     *
     * @param earcon
     *            The text that should be associated with the sound resource
     * @param packageName
     *            The name of the package which has the sound resource
     * @param resId
     *            The resource ID of the sound within its package
     */
    public void addEarcon(String earcon, String packageName, int resId) {
      self.addEarcon(earcon, packageName, resId);
    }

    /**
     * Adds a sound resource to the TTS as an earcon.
     *
     * @param earcon
     *            The text that should be associated with the sound resource
     * @param filename
     *            The filename of the sound resource. This must be a
     *            complete path like: (/sdcard/mysounds/mysoundbite.mp3).
     */
    public void addEarconFile(String earcon, String filename) {
      self.addEarcon(earcon, filename);
    }

    /**
     * Sets the speech rate for the TTS. Note that this will only have an
     * effect on synthesized speech; it will not affect pre-recorded speech.
     *
     * @param speechRate
     *            The speech rate that should be used
     */
    public void setSpeechRate(int speechRate) {
      self.setSpeechRate(speechRate);
    }

    // TODO: Fix comment about language
    /**
     * Sets the speech rate for the TTS. Note that this will only have an
     * effect on synthesized speech; it will not affect pre-recorded speech.
     *
     * @param language
     *            The language to be used. The languages are specified by
     *            their IETF language tags as defined by BCP 47. This is the
     *            same standard used for the lang attribute in HTML. See:
     *            http://en.wikipedia.org/wiki/IETF_language_tag
     */
    public void setLanguage(String language) {
      self.setLanguage(language);
    }

    /**
     * Speaks the given text using the specified queueing mode and
     * parameters.
     *
     * @param text
     *            The String of text that should be synthesized
     * @param params
     *            An ArrayList of parameters. The first element of this
     *            array controls the type of voice to use.
     * @param filename
     *            The string that gives the full output filename; it should
     *            be something like "/sdcard/myappsounds/mysound.wav".
     * @return A boolean that indicates if the synthesis succeeded
     */
    public boolean synthesizeToFile(String text, String[] params,
        String filename) {
      ArrayList<String> speakingParams = new ArrayList<String>();
      if (params != null) {
        speakingParams = new ArrayList<String>(Arrays.asList(params));
      }
      return self.synthesizeToFile(text, speakingParams, filename, true);
    }
  };

}
