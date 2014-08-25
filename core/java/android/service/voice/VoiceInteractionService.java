/**
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

package android.service.voice;

import android.annotation.SdkConstant;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractionManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;


/**
 * Top-level service of the current global voice interactor, which is providing
 * support for hotwording, the back-end of a {@link android.app.VoiceInteractor}, etc.
 * The current VoiceInteractionService that has been selected by the user is kept
 * always running by the system, to allow it to do things like listen for hotwords
 * in the background to instigate voice interactions.
 *
 * <p>Because this service is always running, it should be kept as lightweight as
 * possible.  Heavy-weight operations (including showing UI) should be implemented
 * in the associated {@link android.service.voice.VoiceInteractionSessionService} when
 * an actual voice interaction is taking place, and that service should run in a
 * separate process from this one.
 */
public class VoiceInteractionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_VOICE_INTERACTION} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.VoiceInteractionService";

    /**
     * Name under which a VoiceInteractionService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#VoiceInteractionService voice-interaction-service}&gt;</code> tag.
     */
    public static final String SERVICE_META_DATA = "android.voice_interaction";

    IVoiceInteractionService mInterface = new IVoiceInteractionService.Stub() {
        @Override public void ready() {
            mHandler.sendEmptyMessage(MSG_READY);
        }
        @Override public void shutdown() {
            mHandler.sendEmptyMessage(MSG_SHUTDOWN);
        }
        @Override public void soundModelsChanged() {
            mHandler.sendEmptyMessage(MSG_SOUND_MODELS_CHANGED);
        }
    };

    MyHandler mHandler;

    IVoiceInteractionManagerService mSystemService;

    private final Object mLock = new Object();

    private KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;

    private AlwaysOnHotwordDetector mHotwordDetector;

    static final int MSG_READY = 1;
    static final int MSG_SHUTDOWN = 2;
    static final int MSG_SOUND_MODELS_CHANGED = 3;

    class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_READY:
                    onReady();
                    break;
                case MSG_SHUTDOWN:
                    onShutdownInternal();
                    break;
                case MSG_SOUND_MODELS_CHANGED:
                    onSoundModelsChangedInternal();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Check whether the given service component is the currently active
     * VoiceInteractionService.
     */
    public static boolean isActiveService(Context context, ComponentName service) {
        String cur = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.VOICE_INTERACTION_SERVICE);
        if (cur == null || cur.isEmpty()) {
            return false;
        }
        ComponentName curComp = ComponentName.unflattenFromString(cur);
        if (curComp == null) {
            return false;
        }
        return curComp.equals(service);
    }

    /**
     * Initiate the execution of a new {@link android.service.voice.VoiceInteractionSession}.
     * @param args Arbitrary arguments that will be propagated to the session.
     */
    public void startSession(Bundle args) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        try {
            mSystemService.startSession(mInterface, args);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new MyHandler();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        return null;
    }

    /**
     * Called during service initialization to tell you when the system is ready
     * to receive interaction from it. You should generally do initialization here
     * rather than in {@link #onCreate()}. Methods such as {@link #startSession(Bundle)} and
     * {@link #createAlwaysOnHotwordDetector(String, Locale, android.service.voice.AlwaysOnHotwordDetector.Callback)}
     * will not be operational until this point.
     */
    public void onReady() {
        mSystemService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
        mKeyphraseEnrollmentInfo = new KeyphraseEnrollmentInfo(getPackageManager());
    }

    private void onShutdownInternal() {
        onShutdown();
        // Stop any active recognitions when shutting down.
        // This ensures that if implementations forget to stop any active recognition,
        // It's still guaranteed to have been stopped.
        // This helps with cases where the voice interaction implementation is changed
        // by the user.
        safelyShutdownHotwordDetector();
    }

    /**
     * Called during service de-initialization to tell you when the system is shutting the
     * service down.
     * At this point this service may no longer be the active {@link VoiceInteractionService}.
     */
    public void onShutdown() {
    }

    private void onSoundModelsChangedInternal() {
        synchronized (this) {
            if (mHotwordDetector != null) {
                // TODO: Stop recognition if a sound model that was being recognized gets deleted.
                mHotwordDetector.onSoundModelsChanged();
            }
        }
    }

    /**
     * FIXME: Remove once the prebuilts are updated.
     *
     * @hide
     */
    @Deprecated
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            String keyphrase, String locale, AlwaysOnHotwordDetector.Callback callback) {
        return createAlwaysOnHotwordDetector(keyphrase, Locale.forLanguageTag(locale), callback);
    }

    /**
     * Creates an {@link AlwaysOnHotwordDetector} for the given keyphrase and locale.
     * This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     */
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            String keyphrase, Locale locale, AlwaysOnHotwordDetector.Callback callback) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            // Allow only one concurrent recognition via the APIs.
            safelyShutdownHotwordDetector();
            mHotwordDetector = new AlwaysOnHotwordDetector(keyphrase, locale, callback,
                    mKeyphraseEnrollmentInfo, mInterface, mSystemService);
        }
        return mHotwordDetector;
    }

    /**
     * @return Details of keyphrases available for enrollment.
     * @hide
     */
    @VisibleForTesting
    protected final KeyphraseEnrollmentInfo getKeyphraseEnrollmentInfo() {
        return mKeyphraseEnrollmentInfo;
    }

    private void safelyShutdownHotwordDetector() {
        try {
            synchronized (mLock) {
                if (mHotwordDetector != null) {
                    mHotwordDetector.stopRecognition();
                    mHotwordDetector.invalidate();
                    mHotwordDetector = null;
                }
            }
        } catch (Exception ex) {
            // Ignore.
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("VOICE INTERACTION");
        synchronized (mLock) {
            pw.println("  AlwaysOnHotwordDetector");
            if (mHotwordDetector == null) {
                pw.println("    NULL");
            } else {
                mHotwordDetector.dump("    ", pw);
            }
        }
    }
}
