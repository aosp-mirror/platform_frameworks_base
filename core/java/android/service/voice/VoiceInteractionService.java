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
        @Override
        public void launchVoiceAssistFromKeyguard() throws RemoteException {
            mHandler.sendEmptyMessage(MSG_LAUNCH_VOICE_ASSIST_FROM_KEYGUARD);
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
    static final int MSG_LAUNCH_VOICE_ASSIST_FROM_KEYGUARD = 4;

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
                case MSG_LAUNCH_VOICE_ASSIST_FROM_KEYGUARD:
                    onLaunchVoiceAssistFromKeyguard();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Called when a user has activated an affordance to launch voice assist from the Keyguard.
     *
     * <p>This method will only be called if the VoiceInteractionService has set
     * {@link android.R.attr#supportsLaunchVoiceAssistFromKeyguard} and the Keyguard is showing.</p>
     *
     * <p>A valid implementation must start a new activity that should use {@link
     * android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED} to display
     * on top of the lock screen.</p>
     */
    public void onLaunchVoiceAssistFromKeyguard() {
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
     * Set contextual options you would always like to have disabled when a session
     * is shown.  The flags may be any combination of
     * {@link VoiceInteractionSession#SHOW_WITH_ASSIST VoiceInteractionSession.SHOW_WITH_ASSIST} and
     * {@link VoiceInteractionSession#SHOW_WITH_SCREENSHOT
     * VoiceInteractionSession.SHOW_WITH_SCREENSHOT}.
     */
    public void setDisabledShowContext(int flags) {
        try {
            mSystemService.setDisabledShowContext(flags);
        } catch (RemoteException e) {
        }
    }

    /**
     * Return the value set by {@link #setDisabledShowContext}.
     */
    public int getDisabledShowContext() {
        try {
            return mSystemService.getDisabledShowContext();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * Request that the associated {@link android.service.voice.VoiceInteractionSession} be
     * shown to the user, starting it if necessary.
     * @param args Arbitrary arguments that will be propagated to the session.
     * @param flags Indicates additional optional behavior that should be performed.  May
     * be any combination of
     * {@link VoiceInteractionSession#SHOW_WITH_ASSIST VoiceInteractionSession.SHOW_WITH_ASSIST} and
     * {@link VoiceInteractionSession#SHOW_WITH_SCREENSHOT
      * VoiceInteractionSession.SHOW_WITH_SCREENSHOT}
     * to request that the system generate and deliver assist data on the current foreground
     * app as part of showing the session UI.
     */
    public void showSession(Bundle args, int flags) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        try {
            mSystemService.showSession(mInterface, args, flags);
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
     * rather than in {@link #onCreate}. Methods such as {@link #showSession} and
     * {@link #createAlwaysOnHotwordDetector}
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
