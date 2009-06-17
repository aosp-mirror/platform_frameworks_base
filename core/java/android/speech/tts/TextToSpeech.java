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
package android.speech.tts;

import android.speech.tts.ITts;
import android.speech.tts.ITtsCallback;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

/**
 *
 * Synthesizes speech from text.
 *
 * {@hide}
 */
//TODO #TTS# review + complete javadoc + add links to constants
public class TextToSpeech {

    /**
     * Denotes a successful operation.
     */
    public static final int TTS_SUCCESS                = 0;
    /**
     * Denotes a generic operation failure.
     */
    public static final int TTS_ERROR                  = -1;
    /**
     * Denotes a failure due to a missing resource.
     */
    public static final int TTS_ERROR_MISSING_RESOURCE = -2;

    /**
     * Queue mode where all entries in the playback queue (media to be played
     * and text to be synthesized) are dropped and replaced by the new entry.
     */
    public static final int TTS_QUEUE_FLUSH = 0;
    /**
     * Queue mode where the new entry is added at the end of the playback queue.
     */
    public static final int TTS_QUEUE_ADD = 1;

    /**
     * Called when the TTS has initialized.
     *
     * The InitListener must implement the onInit function. onInit is passed a
     * status code indicating the result of the TTS initialization.
     */
    public interface OnInitListener {
        public void onInit(int status);
    }

    /**
     * Called when the TTS has finished speaking by itself (speaking
     * finished without being canceled).
     *
     */
    public interface OnSpeechCompletedListener {
        public void onSpeechCompleted();
    }

    /**
     * Connection needed for the TTS.
     */
    private ServiceConnection mServiceConnection;

    private ITts mITts = null;
    private Context mContext = null;
    private OnInitListener mInitListener = null;
    private boolean mStarted = false;
    private final Object mStartLock = new Object();
    private ITtsCallback mITtsCallback;
    private OnSpeechCompletedListener mSpeechCompListener = null;
    private final Object mSpeechCompListenerLock = new Object();



    /**
     * The constructor for the TTS.
     *
     * @param context
     *            The context
     * @param listener
     *            The InitListener that will be called when the TTS has
     *            initialized successfully.
     */
    public TextToSpeech(Context context, OnInitListener listener) {
        mContext = context;
        mInitListener = listener;
        initTts();
    }


    public void setOnSpeechCompletedListener(final OnSpeechCompletedListener listener) {
        synchronized(mSpeechCompListenerLock) {
            mSpeechCompListener = listener;
        }
    }


    private boolean dataFilesCheck() {
        // TODO #TTS# config manager will be in settings
        Log.i("TTS_FIXME", "FIXME in Tts: config manager will be in settings");
        // TODO #TTS# implement checking of the correct installation of
        //             the data files.

        return true;
    }


    private void initTts() {
        mStarted = false;

        // Initialize the TTS, run the callback after the binding is successful
        mServiceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized(mStartLock) {
                    mITts = ITts.Stub.asInterface(service);
                    try {
                        mITtsCallback = new ITtsCallback.Stub() {
                            public void markReached(String mark)
                            throws RemoteException {
                                // call the listener of that event, but not
                                // while locked.
                                OnSpeechCompletedListener listener = null;
                                synchronized(mSpeechCompListenerLock) {
                                    listener = mSpeechCompListener;
                                }
                                if (listener != null) {
                                    listener.onSpeechCompleted();
                                }
                            }
                        };
                        mITts.registerCallback(mITtsCallback);

                    } catch (RemoteException e) {
                        initTts();
                        return;
                    }

                    mStarted = true;
                    // The callback can become null if the Android OS decides to
                    // restart the TTS process as well as whatever is using it.
                    // In such cases, do nothing - the error handling from the
                    // speaking calls will kick in and force a proper restart of
                    // the TTS.
                    if (mInitListener != null) {
                        // TODO manage failures and missing resources
                        mInitListener.onInit(TTS_SUCCESS);
                    }
                }
            }

            public void onServiceDisconnected(ComponentName name) {
                synchronized(mStartLock) {
                    mITts = null;
                    mInitListener = null;
                    mStarted = false;
                }
            }
        };

        Intent intent = new Intent("android.intent.action.USE_TTS");
        intent.addCategory("android.intent.category.TTS");
        mContext.bindService(intent, mServiceConnection,
                Context.BIND_AUTO_CREATE);
        // TODO handle case where the binding works (should always work) but
        //      the plugin fails
    }


    /**
     * Shuts down the TTS. It is good practice to call this in the onDestroy
     * method of the Activity that is using the TTS so that the TTS is stopped
     * cleanly.
     */
    public void shutdown() {
        try {
            mContext.unbindService(mServiceConnection);
        } catch (IllegalArgumentException e) {
            // Do nothing and fail silently since an error here indicates that
            // binding never succeeded in the first place.
        }
    }


    /**
     * Adds a mapping between a string of text and a sound resource in a
     * package.
     *
     * @see #TTS.speak(String text, int queueMode, String[] params)
     *
     * @param text
     *            Example: <b><code>"south_south_east"</code></b><br/>
     *
     * @param packagename
     *            Pass the packagename of the application that contains the
     *            resource. If the resource is in your own application (this is
     *            the most common case), then put the packagename of your
     *            application here.<br/>
     *            Example: <b>"com.google.marvin.compass"</b><br/>
     *            The packagename can be found in the AndroidManifest.xml of
     *            your application.
     *            <p>
     *            <code>&lt;manifest xmlns:android=&quot;...&quot;
     *      package=&quot;<b>com.google.marvin.compass</b>&quot;&gt;</code>
     *            </p>
     *
     * @param resourceId
     *            Example: <b><code>R.raw.south_south_east</code></b>
     */
    public void addSpeech(String text, String packagename, int resourceId) {
        synchronized(mStartLock) {
            if (!mStarted) {
                return;
            }
            try {
                mITts.addSpeech(text, packagename, resourceId);
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }


    /**
     * Adds a mapping between a string of text and a sound file. Using this, it
     * is possible to add custom pronounciations for text.
     *
     * @param text
     *            The string of text
     * @param filename
     *            The full path to the sound file (for example:
     *            "/sdcard/mysounds/hello.wav")
     */
    public void addSpeech(String text, String filename) {
        synchronized (mStartLock) {
            if (!mStarted) {
                return;
            }
            try {
                mITts.addSpeechFile(text, filename);
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }


    /**
     * Speaks the string using the specified queuing strategy and speech
     * parameters. Note that the speech parameters are not universally supported
     * by all engines and will be treated as a hint. The TTS library will try to
     * fulfill these parameters as much as possible, but there is no guarantee
     * that the voice used will have the properties specified.
     *
     * @param text
     *            The string of text to be spoken.
     * @param queueMode
     *            The queuing strategy to use.
     *            See TTS_QUEUE_ADD and TTS_QUEUE_FLUSH.
     * @param params
     *            The hashmap of speech parameters to be used.
     */
    public void speak(String text, int queueMode, HashMap<String,String> params)
    {
        synchronized (mStartLock) {
            Log.i("TTS received: ", text);
            if (!mStarted) {
                return;
            }
            try {
                // TODO support extra parameters, passing null for the moment
                mITts.speak(text, queueMode, null);
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }


    /**
     * Plays the earcon using the specified queueing mode and parameters.
     *
     * @param earcon
     *            The earcon that should be played
     * @param queueMode
     *            See TTS_QUEUE_ADD and TTS_QUEUE_FLUSH.
     * @param params
     *            The hashmap of parameters to be used.
     */
    public void playEarcon(String earcon, int queueMode,
            HashMap<String,String> params) {
        synchronized (mStartLock) {
            if (!mStarted) {
                return;
            }
            try {
                // TODO support extra parameters, passing null for the moment
                mITts.playEarcon(earcon, queueMode, null);
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }


    public void playSilence(long durationInMs, int queueMode) {
        // TODO implement, already present in TTS service
    }


    /**
     * Returns whether or not the TTS is busy speaking.
     *
     * @return Whether or not the TTS is busy speaking.
     */
    public boolean isSpeaking() {
        synchronized (mStartLock) {
            if (!mStarted) {
                return false;
            }
            try {
                return mITts.isSpeaking();
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
            return false;
        }
    }


    /**
     * Stops speech from the TTS.
     */
    public void stop() {
        synchronized (mStartLock) {
            if (!mStarted) {
                return;
            }
            try {
                mITts.stop();
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }



    /**
     * Sets the speech rate for the TTS engine.
     *
     * Note that the speech rate is not universally supported by all engines and
     * will be treated as a hint. The TTS library will try to use the specified
     * speech rate, but there is no guarantee.
     * This has no effect on any pre-recorded speech.
     *
     * @param speechRate
     *            The speech rate for the TTS engine. 1 is the normal speed,
     *            lower values slow down the speech (0.5 is half the normal speech rate),
     *            greater values accelerate it (2 is twice the normal speech rate).
     */
    public void setSpeechRate(float speechRate) {
        synchronized (mStartLock) {
            if (!mStarted) {
                return;
            }
            try {
                if (speechRate > 0) {
                    mITts.setSpeechRate((int)(speechRate*100));
                }
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }


    /**
     * Sets the language for the TTS engine.
     *
     * Note that the language is not universally supported by all engines and
     * will be treated as a hint. The TTS library will try to use the specified
     * language as represented by the Locale, but there is no guarantee.
     *
     * @param loc
     *            The locale describing the language to be used.
     */
    public void setLanguage(Locale loc) {
        synchronized (mStartLock) {
            if (!mStarted) {
                return;
            }
            try {
                mITts.setLanguage(loc.getISO3Language(), loc.getISO3Country(), loc.getVariant());
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
        }
    }


    /**
     * Speaks the given text using the specified queueing mode and parameters.
     *
     * @param text
     *            The String of text that should be synthesized
     * @param params
     *            A hashmap of parameters.
     * @param filename
     *            The string that gives the full output filename; it should be
     *            something like "/sdcard/myappsounds/mysound.wav".
     * @return A boolean that indicates if the synthesis succeeded
     */
    public boolean synthesizeToFile(String text, HashMap<String,String> params,
            String filename) {
        synchronized (mStartLock) {
            if (!mStarted) {
                return false;
            }
            try {
                // TODO support extra parameters, passing null for the moment
                return mITts.synthesizeToFile(text, null, filename);
            } catch (RemoteException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (NullPointerException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            } catch (IllegalStateException e) {
                // TTS died; restart it.
                mStarted = false;
                initTts();
            }
            return false;
        }
    }


}
