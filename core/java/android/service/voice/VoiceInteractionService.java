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

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.Service;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.permission.Identity;
import android.media.voice.KeyphraseModelManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SharedMemory;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.voice.flags.Flags;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

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
    static final String TAG = VoiceInteractionService.class.getSimpleName();

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

    /**
     * For apps targeting Build.VERSION_CODES.UPSIDE_DOWN_CAKE and above, implementors of this
     * service can create multiple AlwaysOnHotwordDetector instances in parallel. They will
     * also e ale to create a single SoftwareHotwordDetector in parallel with any other
     * active AlwaysOnHotwordDetector instances.
     *
     * <p>Requirements when this change is enabled:
     * <ul>
     *     <li>
     *         Any number of AlwaysOnHotwordDetector instances can be created in parallel
     *         as long as they are unique to any other active AlwaysOnHotwordDetector.
     *     </li>
     *     <li>
     *         Only a single instance of SoftwareHotwordDetector can be active at a given
     *         time. It can be active at the same time as any number of
     *         AlwaysOnHotwordDetector instances.
     *     </li>
     *     <li>
     *         To release that reference and any resources associated with that reference,
     *         HotwordDetector#destroy() must be called. An attempt to create an
     *         HotwordDetector equal to an active HotwordDetector will be rejected
     *         until HotwordDetector#destroy() is called on the active instance.
     *     </li>
     * </ul>
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final long MULTIPLE_ACTIVE_HOTWORD_DETECTORS = 193232191L;

    private static final boolean SYSPROP_VISUAL_QUERY_SERVICE_ENABLED =
            SystemProperties.getBoolean("ro.hotword.visual_query_service_enabled", false);

    IVoiceInteractionService mInterface = new IVoiceInteractionService.Stub() {
        @Override
        public void ready() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onReady, VoiceInteractionService.this));
        }

        @Override
        public void shutdown() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onShutdownInternal, VoiceInteractionService.this));
        }

        @Override
        public void soundModelsChanged() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onSoundModelsChangedInternal,
                    VoiceInteractionService.this));
        }

        @Override
        public void launchVoiceAssistFromKeyguard() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onLaunchVoiceAssistFromKeyguard,
                    VoiceInteractionService.this));
        }

        @Override
        public void getActiveServiceSupportedActions(List<String> voiceActions,
                IVoiceActionCheckCallback callback) {
            Handler.getMain().executeOrSendMessage(
                    PooledLambda.obtainMessage(VoiceInteractionService::onHandleVoiceActionCheck,
                            VoiceInteractionService.this,
                            voiceActions,
                            callback));
        }

        @Override
        public void prepareToShowSession(Bundle args, int flags) {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onPrepareToShowSession,
                    VoiceInteractionService.this, args, flags));
        }

        @Override
        public void showSessionFailed(@NonNull Bundle args) {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onShowSessionFailed,
                    VoiceInteractionService.this, args));
        }

        @Override
        public void detectorRemoteExceptionOccurred(@NonNull IBinder token, int detectorType) {
            Log.d(TAG, "detectorRemoteExceptionOccurred");
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onDetectorRemoteException,
                    VoiceInteractionService.this, token, detectorType));
        }
    };

    IVoiceInteractionManagerService mSystemService;

    private VisualQueryDetector mActiveVisualQueryDetector;

    private final Object mLock = new Object();

    private KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;

    private final Set<HotwordDetector> mActiveDetectors = new ArraySet<>();

    // True if any of the createAOHD methods should use the test ST module.
    @GuardedBy("mLock")
    private boolean mTestModuleForAlwaysOnHotwordDetectorEnabled = false;

    private void onDetectorRemoteException(@NonNull IBinder token, int detectorType) {
        Log.d(TAG, "onDetectorRemoteException for " + HotwordDetector.detectorTypeToString(
                detectorType));
        mActiveDetectors.forEach(detector -> {
            // TODO: handle normal detector, VQD
            if (detectorType == HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP
                    && detector instanceof AlwaysOnHotwordDetector) {
                AlwaysOnHotwordDetector alwaysOnDetector = (AlwaysOnHotwordDetector) detector;
                if (alwaysOnDetector.isSameToken(token)) {
                    alwaysOnDetector.onDetectorRemoteException();
                }
            } else if (detectorType == HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE
                    && detector instanceof SoftwareHotwordDetector) {
                SoftwareHotwordDetector softwareDetector = (SoftwareHotwordDetector) detector;
                if (softwareDetector.isSameToken(token)) {
                    softwareDetector.onDetectorRemoteException();
                }
            }
        });
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
     * Notify the interactor when the system prepares to show session. The system is going to
     * bind the session service.
     *
     * @param args  The arguments that were supplied to {@link #showSession(Bundle, int)}.
     *              It always includes {@link VoiceInteractionSession#KEY_SHOW_SESSION_ID}.
     * @param flags The show flags originally provided to {@link #showSession(Bundle, int)}.
     * @see #showSession(Bundle, int)
     * @see #onShowSessionFailed(Bundle)
     * @see VoiceInteractionSession#onShow(Bundle, int)
     * @see VoiceInteractionSession#show(Bundle, int)
     */
    public void onPrepareToShowSession(@NonNull Bundle args, int flags) {
    }

    /**
     * Called when the show session failed. E.g. When the system bound the session service failed.
     *
     * @param args Additional info about the show session attempt that failed. For now, includes
     *             {@link VoiceInteractionSession#KEY_SHOW_SESSION_ID}.
     * @see #showSession(Bundle, int)
     * @see #onPrepareToShowSession(Bundle, int)
     * @see VoiceInteractionSession#onShow(Bundle, int)
     * @see VoiceInteractionSession#show(Bundle, int)
     */
    public void onShowSessionFailed(@NonNull Bundle args) {
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
            mSystemService.showSession(args, flags, getAttributionTag());
        } catch (RemoteException e) {
        }
    }

    /**
     * Request to query for what extended voice actions this service supports. This method will
     * be called when the system checks the supported actions of this
     * {@link VoiceInteractionService}. Supported actions may be delivered to
     * {@link VoiceInteractionSession} later to request a session to perform an action.
     *
     * <p>Voice actions are defined in support libraries and could vary based on platform context.
     * For example, car related voice actions will be defined in car support libraries.
     *
     * @param voiceActions A set of checked voice actions.
     * @return Returns a subset of checked voice actions. Additional voice actions in the
     * returned set will be ignored. Returns empty set if no actions are supported.
     */
    @NonNull
    public Set<String> onGetSupportedVoiceActions(@NonNull Set<String> voiceActions) {
        return Collections.emptySet();
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
     * rather than in {@link #onCreate}. Methods such as {@link #showSession} will
     * not be operational until this point.
     */
    public void onReady() {
        mSystemService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
        Objects.requireNonNull(mSystemService);
        try {
            mSystemService.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.wtf(TAG, "unable to link to death with system service");
        }
        mKeyphraseEnrollmentInfo = new KeyphraseEnrollmentInfo(getPackageManager());
    }

    private IBinder.DeathRecipient mDeathRecipient = () -> {
        Log.e(TAG, "system service binder died shutting down");
        Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                VoiceInteractionService::onShutdownInternal, VoiceInteractionService.this));
    };

    private void onShutdownInternal() {
        onShutdown();
        // Stop any active recognitions when shutting down.
        // This ensures that if implementations forget to stop any active recognition,
        // It's still guaranteed to have been stopped.
        // This helps with cases where the voice interaction implementation is changed
        // by the user.
        safelyShutdownAllHotwordDetectors(true);
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
            // TODO: Stop recognition if a sound model that was being recognized gets deleted.
            mActiveDetectors.forEach(detector -> {
                if (detector instanceof AlwaysOnHotwordDetector) {
                    ((AlwaysOnHotwordDetector) detector).onSoundModelsChanged();
                }
            });
        }
    }

    private void onHandleVoiceActionCheck(List<String> voiceActions,
            IVoiceActionCheckCallback callback) {
        if (callback != null) {
            try {
                Set<String> voiceActionsSet = new ArraySet<>(voiceActions);
                Set<String> resultSet = onGetSupportedVoiceActions(voiceActionsSet);
                callback.onComplete(new ArrayList<>(resultSet));
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * List available ST modules to attach to for test purposes.
     * @hide
     */
    @TestApi
    @NonNull
    public final List<SoundTrigger.ModuleProperties> listModuleProperties() {
        Identity identity = new Identity();
        identity.packageName = ActivityThread.currentOpPackageName();
        try {
            return mSystemService.listModuleProperties(identity);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Reset hotword training data egressed count.
     *  @hide */
    @TestApi
    @FlaggedApi(Flags.FLAG_ALLOW_TRAINING_DATA_EGRESS_FROM_HDS)
    @RequiresPermission(Manifest.permission.RESET_HOTWORD_TRAINING_DATA_EGRESS_COUNT)
    public final void resetHotwordTrainingDataEgressCountForTest() {
        Log.i(TAG, "Resetting hotword training data egress count for test.");
        try {
            mSystemService.resetHotwordTrainingDataEgressCountForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates an {@link AlwaysOnHotwordDetector} for the given keyphrase and locale.
     * This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * <p>Note: If there are any active detectors that are created by using
     * {@link #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * AlwaysOnHotwordDetector.Callback)} or {@link #createAlwaysOnHotwordDetector(String, Locale,
     * PersistableBundle, SharedMemory, Executor, AlwaysOnHotwordDetector.Callback)} or
     * {@link #createHotwordDetector(PersistableBundle, SharedMemory, HotwordDetector.Callback)} or
     * {@link #createHotwordDetector(PersistableBundle, SharedMemory, Executor,
     * HotwordDetector.Callback)}, call this will throw an {@link IllegalStateException}.
     *
     * <p>Note that the callback will be executed on the current thread. If the current thread
     * doesn't have a looper, it will throw a {@link RuntimeException}. To specify the execution
     * thread, use {@link #createAlwaysOnHotwordDetector(String, Locale, Executor,
     * AlwaysOnHotwordDetector.Callback)}.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     *
     * @throws SecurityException if the caller does not hold required permissions
     * @throws IllegalStateException if there is no DSP hardware support when a caller has a
     * target SDK of API level 34 or above.
     *
     * @deprecated Use {@link #createAlwaysOnHotwordDetector(String, Locale, Executor,
     *             AlwaysOnHotwordDetector.Callback)} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            @SuppressLint("MissingNullability") String keyphrase,  // TODO: nullability properly
            @SuppressLint({"MissingNullability", "UseIcu"}) Locale locale,
            @SuppressLint("MissingNullability") AlwaysOnHotwordDetector.Callback callback) {
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ false, /* options= */ null,
                /* sharedMemory= */ null, /* moduleProperties */ null,
                /* executor= */ null, callback);
    }

    /**
     * Creates an {@link AlwaysOnHotwordDetector} for the given keyphrase and locale.
     * This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * <p>Note: If there are any active detectors that are created by using
     * {@link #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * AlwaysOnHotwordDetector.Callback)} or {@link #createAlwaysOnHotwordDetector(String, Locale,
     * PersistableBundle, SharedMemory, Executor, AlwaysOnHotwordDetector.Callback)} or
     * {@link #createHotwordDetector(PersistableBundle, SharedMemory, HotwordDetector.Callback)} or
     * {@link #createHotwordDetector(PersistableBundle, SharedMemory, Executor,
     * HotwordDetector.Callback)}, call this will throw an {@link IllegalStateException}.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param executor The executor on which to run the callback.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     *
     * @throws SecurityException if the caller does not hold required permissions
     * @throws IllegalStateException if there is no DSP hardware support when a caller has a
     * target SDK of API level 34 or above.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            @NonNull String keyphrase, @SuppressLint("UseIcu") @NonNull Locale locale,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AlwaysOnHotwordDetector.Callback callback) {
        // TODO(b/269080850): Resolve AndroidFrameworkRequiresPermission lint warning

        Objects.requireNonNull(keyphrase);
        Objects.requireNonNull(locale);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ false, /* options= */ null,
                /* sharedMemory= */ null, /* moduleProperties= */ null, executor, callback);
    }

    /**
     * Same as {@link createAlwaysOnHotwordDetector(String, Locale, Executor,
     * AlwaysOnHotwordDetector.Callback)}, but allow explicit selection of the underlying ST
     * module to attach to.
     * Use {@link #listModuleProperties()} to get available modules to attach to.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetectorForTest(
            @NonNull String keyphrase, @SuppressLint("UseIcu") @NonNull Locale locale,
            @NonNull SoundTrigger.ModuleProperties moduleProperties,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AlwaysOnHotwordDetector.Callback callback) {
        // TODO(b/305787465): Remove the MANAGE_HOTWORD_DETECTION permission enforcement on the
        // {@link #createAlwaysOnHotwordDetectorForTest(String, Locale,
        // SoundTrigger.ModuleProperties, AlwaysOnHotwordDetector.Callback)} and replace with the
        // permission RECEIVE_SANDBOX_TRIGGER_AUDIO when it is fully launched.

        Objects.requireNonNull(keyphrase);
        Objects.requireNonNull(locale);
        Objects.requireNonNull(moduleProperties);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ false, /* options= */ null,
                /* sharedMemory= */ null, moduleProperties, executor, callback);
    }


    /**
     * Create an {@link AlwaysOnHotwordDetector} and trigger a {@link HotwordDetectionService}
     * service, then it will also pass the read-only data to hotword detection service.
     *
     * Like {@link #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)
     * }. Before calling this function, you should set a valid hotword detection service with
     * android:hotwordDetectionService in an android.voice_interaction metadata file and set
     * android:isolatedProcess="true" in the AndroidManifest.xml of hotword detection service.
     * Otherwise it will throw IllegalStateException. After calling this function, the system will
     * also trigger a hotword detection service and pass the read-only data back to it.
     *
     * <p>Note: The system will trigger hotword detection service after calling this function when
     * all conditions meet the requirements.
     *
     * <p>Note: If there are any active detectors that are created by using
     * {@link #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)} or
     * {@link #createAlwaysOnHotwordDetector(String, Locale, Executor,
     * AlwaysOnHotwordDetector.Callback)}, call this will throw an {@link IllegalStateException}.
     *
     * <p>Note that the callback will be executed on the current thread. If the current thread
     * doesn't have a looper, it will throw a {@link RuntimeException}. To specify the execution
     * thread, use {@link #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle,
     * SharedMemory, Executor, AlwaysOnHotwordDetector.Callback)}.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param options Application configuration data provided by the
     * {@link VoiceInteractionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob provided by the
     * {@link VoiceInteractionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     *
     * @throws SecurityException if the caller does not hold required permissions
     * @throws IllegalStateException if the hotword detection service is not set, isolated process
     * is not set, or there is no DSP hardware support when a caller has a target SDK of API
     * level 34 or above.
     *
     * @deprecated Use {@link #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle,
     *             SharedMemory, Executor, AlwaysOnHotwordDetector.Callback)} instead.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @Deprecated
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            @SuppressLint("MissingNullability") String keyphrase,  // TODO: nullability properly
            @SuppressLint({"MissingNullability", "UseIcu"}) Locale locale,
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @SuppressLint("MissingNullability") AlwaysOnHotwordDetector.Callback callback) {
        // TODO(b/305787465): Remove the MANAGE_HOTWORD_DETECTION permission enforcement on the
        // {@link #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
        // AlwaysOnHotwordDetector.Callback)} and replace with the permission
        // RECEIVE_SANDBOX_TRIGGER_AUDIO when it is fully launched.

        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ true, options, sharedMemory,
                /* modulProperties */ null, /* executor= */ null, callback);
    }

    /**
     * Create an {@link AlwaysOnHotwordDetector} and trigger a {@link HotwordDetectionService}
     * service, then it will also pass the read-only data to hotword detection service.
     *
     * Like {@link #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)
     * }. Before calling this function, you should set a valid hotword detection service with
     * android:hotwordDetectionService in an android.voice_interaction metadata file and set
     * android:isolatedProcess="true" in the AndroidManifest.xml of hotword detection service.
     * Otherwise it will throw IllegalStateException. After calling this function, the system will
     * also trigger a hotword detection service and pass the read-only data back to it.
     *
     * <p>Note: The system will trigger hotword detection service after calling this function when
     * all conditions meet the requirements.
     *
     * <p>Note: If there are any active detectors that are created by using
     * {@link #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)} or
     * {@link #createAlwaysOnHotwordDetector(String, Locale, Executor,
     * AlwaysOnHotwordDetector.Callback)}, call this will throw an {@link IllegalStateException}.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param options Application configuration data provided by the
     * {@link VoiceInteractionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob provided by the
     * {@link VoiceInteractionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     * @param executor The executor on which to run the callback.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     *
     * @throws SecurityException if the caller does not hold required permissions
     * @throws IllegalStateException if the hotword detection service is not set, isolated process
     * is not set, or there is no DSP hardware support when a caller has a target SDK of API level
     * 34 or above.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            @NonNull String keyphrase, @SuppressLint("UseIcu") @NonNull Locale locale,
            @Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AlwaysOnHotwordDetector.Callback callback) {
        // TODO(b/269080850): Resolve AndroidFrameworkRequiresPermission lint warning
        // TODO(b/305787465): Remove the MANAGE_HOTWORD_DETECTION permission enforcement on the
        // {@link #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
        // Executor, AlwaysOnHotwordDetector.Callback)} and replace with the permission
        // RECEIVE_SANDBOX_TRIGGER_AUDIO when it is fully launched.

        Objects.requireNonNull(keyphrase);
        Objects.requireNonNull(locale);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ true, options, sharedMemory,
                /* moduleProperties= */ null, executor, callback);
    }

    /**
     * Same as {@link createAlwaysOnHotwordDetector(String, Locale,
     * PersistableBundle, SharedMemory, Executor, AlwaysOnHotwordDetector.Callback)},
     * but allow explicit selection of the underlying ST module to attach to.
     * Use {@link #listModuleProperties()} to get available modules to attach to.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetectorForTest(
            @NonNull String keyphrase, @SuppressLint("UseIcu") @NonNull Locale locale,
            @Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory,
            @NonNull SoundTrigger.ModuleProperties moduleProperties,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AlwaysOnHotwordDetector.Callback callback) {
        // TODO(b/305787465): Remove the MANAGE_HOTWORD_DETECTION permission enforcement on the
        // {@link #createAlwaysOnHotwordDetectorForTest(String, Locale, PersistableBundle,
        // SharedMemory, SoundTrigger.ModuleProperties, Executor, AlwaysOnHotwordDetector.Callback)}
        // and replace with the permission RECEIVE_SANDBOX_TRIGGER_AUDIO when it is fully launched.

        Objects.requireNonNull(keyphrase);
        Objects.requireNonNull(locale);
        Objects.requireNonNull(moduleProperties);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ true, options, sharedMemory,
                moduleProperties, executor, callback);
    }



    private AlwaysOnHotwordDetector createAlwaysOnHotwordDetectorInternal(
            @SuppressLint("MissingNullability") String keyphrase,  // TODO: nullability properly
            @SuppressLint({"MissingNullability", "UseIcu"}) Locale locale,
            boolean supportHotwordDetectionService,
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @Nullable SoundTrigger.ModuleProperties moduleProperties,
            @Nullable @CallbackExecutor Executor executor,
            @SuppressLint("MissingNullability") AlwaysOnHotwordDetector.Callback callback) {

        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            if (!CompatChanges.isChangeEnabled(MULTIPLE_ACTIVE_HOTWORD_DETECTORS)) {
                // Allow only one concurrent recognition via the APIs.
                safelyShutdownAllHotwordDetectors(false);
            } else {
                for (HotwordDetector detector : mActiveDetectors) {
                    if (detector.isUsingSandboxedDetectionService()
                            != supportHotwordDetectionService) {
                        throw new IllegalStateException(
                                "It disallows to create trusted and non-trusted detectors "
                                        + "at the same time.");
                    } else if (detector instanceof AlwaysOnHotwordDetector) {
                        throw new IllegalStateException(
                                "There is already an active AlwaysOnHotwordDetector. "
                                        + "It must be destroyed to create a new one.");
                    }
                }
            }

            AlwaysOnHotwordDetector dspDetector = new AlwaysOnHotwordDetector(keyphrase, locale,
                    executor, callback, mKeyphraseEnrollmentInfo, mSystemService,
                    getApplicationContext().getApplicationInfo().targetSdkVersion,
                    supportHotwordDetectionService, getAttributionTag());
            mActiveDetectors.add(dspDetector);

            try {
                dspDetector.registerOnDestroyListener(this::onHotwordDetectorDestroyed);
                // Check if we are currently overridden, and should use the test module.
                if (mTestModuleForAlwaysOnHotwordDetectorEnabled) {
                    moduleProperties = getTestModuleProperties();
                }
                // If moduleProperties is null, the default STModule is used.
                dspDetector.initialize(options, sharedMemory, moduleProperties);
            } catch (Exception e) {
                mActiveDetectors.remove(dspDetector);
                dspDetector.destroy();
                throw e;
            }
            return dspDetector;
        }
    }

    /**
     * Creates a {@link HotwordDetector} and initializes the application's
     * {@link HotwordDetectionService} using {@code options} and {code sharedMemory}.
     *
     * <p>To be able to call this, you need to set android:hotwordDetectionService in the
     * android.voice_interaction metadata file to a valid hotword detection service, and set
     * android:isolatedProcess="true" in the hotword detection service's declaration. Otherwise,
     * this throws an {@link IllegalStateException}.
     *
     * <p>This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * <p>Using this has a noticeable impact on battery, since the microphone is kept open
     * for the lifetime of the recognition {@link HotwordDetector#startRecognition() session}. On
     * devices where hardware filtering is available (such as through a DSP), it's highly
     * recommended to use {@link #createAlwaysOnHotwordDetector} instead.
     *
     * <p>Note: If there are any active detectors that are created by using
     * {@link #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)} or
     * {@link #createAlwaysOnHotwordDetector(String, Locale, Executor,
     * AlwaysOnHotwordDetector.Callback)}, call this will throw an {@link IllegalStateException}.
     *
     * <p>Note that the callback will be executed on the main thread. To specify the execution
     * thread, use {@link #createHotwordDetector(PersistableBundle, SharedMemory, Executor,
     * HotwordDetector.Callback)}.
     *
     * @param options Application configuration data to be provided to the
     * {@link HotwordDetectionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to be provided to the
     * {@link HotwordDetectionService}. Use this to provide hotword models or other such data to the
     * sandboxed process.
     * @param callback The callback to notify of detection events.
     * @return A hotword detector for the given audio format.
     *
     * @see #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * AlwaysOnHotwordDetector.Callback)
     *
     * @see #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * Executor, AlwaysOnHotwordDetector.Callback)
     *
     * @deprecated Use {@link #createHotwordDetector(PersistableBundle, SharedMemory, Executor,
     *             HotwordDetector.Callback)} instead.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @Deprecated
    @NonNull
    public final HotwordDetector createHotwordDetector(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull HotwordDetector.Callback callback) {
        return createHotwordDetectorInternal(options, sharedMemory, /* executor= */ null, callback);
    }

    /**
     * Creates a {@link HotwordDetector} and initializes the application's
     * {@link HotwordDetectionService} using {@code options} and {code sharedMemory}.
     *
     * <p>To be able to call this, you need to set android:hotwordDetectionService in the
     * android.voice_interaction metadata file to a valid hotword detection service, and set
     * android:isolatedProcess="true" in the hotword detection service's declaration. Otherwise,
     * this throws an {@link IllegalStateException}.
     *
     * <p>This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * <p>Using this has a noticeable impact on battery, since the microphone is kept open
     * for the lifetime of the recognition {@link HotwordDetector#startRecognition() session}. On
     * devices where hardware filtering is available (such as through a DSP), it's highly
     * recommended to use {@link #createAlwaysOnHotwordDetector} instead.
     *
     * <p>Note: If there are any active detectors that are created by using
     * {@link #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)} or
     * {@link #createAlwaysOnHotwordDetector(String, Locale, Executor,
     * AlwaysOnHotwordDetector.Callback)}, call this will throw an {@link IllegalStateException}.
     *
     * @param options Application configuration data to be provided to the
     * {@link HotwordDetectionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to be provided to the
     * {@link HotwordDetectionService}. Use this to provide hotword models or other such data to the
     * sandboxed process.
     * @param executor The executor on which to run the callback.
     * @param callback The callback to notify of detection events.
     * @return A hotword detector for the given audio format.
     *
     * @see #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * AlwaysOnHotwordDetector.Callback)
     *
     * @see #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * Executor, AlwaysOnHotwordDetector.Callback)
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final HotwordDetector createHotwordDetector(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull HotwordDetector.Callback callback) {
        // TODO (b/269080850): Resolve AndroidFrameworkRequiresPermission lint warning

        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        return createHotwordDetectorInternal(options, sharedMemory, executor, callback);
    }

    private HotwordDetector createHotwordDetectorInternal(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @Nullable @CallbackExecutor Executor executor,
            @NonNull HotwordDetector.Callback callback) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            if (!CompatChanges.isChangeEnabled(MULTIPLE_ACTIVE_HOTWORD_DETECTORS)) {
                // Allow only one concurrent recognition via the APIs.
                safelyShutdownAllHotwordDetectors(false);
            } else {
                for (HotwordDetector detector : mActiveDetectors) {
                    if (!detector.isUsingSandboxedDetectionService()) {
                        throw new IllegalStateException(
                                "It disallows to create trusted and non-trusted detectors "
                                        + "at the same time.");
                    } else if (detector instanceof SoftwareHotwordDetector) {
                        throw new IllegalStateException(
                                "There is already an active SoftwareHotwordDetector. "
                                        + "It must be destroyed to create a new one.");
                    }
                }
            }

            SoftwareHotwordDetector softwareHotwordDetector =
                    new SoftwareHotwordDetector(mSystemService, /* audioFormat= */ null,
                            executor, callback, getAttributionTag());
            mActiveDetectors.add(softwareHotwordDetector);

            try {
                softwareHotwordDetector.registerOnDestroyListener(
                        this::onHotwordDetectorDestroyed);
                softwareHotwordDetector.initialize(options, sharedMemory);
            } catch (Exception e) {
                mActiveDetectors.remove(softwareHotwordDetector);
                softwareHotwordDetector.destroy();
                throw e;
            }
            return softwareHotwordDetector;
        }
    }

    /**
     * Creates a {@link VisualQueryDetector} and initializes the application's
     * {@link VisualQueryDetectionService} using {@code options} and {@code sharedMemory}.
     *
     * <p>To be able to call this, you need to set android:visualQueryDetectionService in the
     * android.voice_interaction metadata file to a valid visual query detection service, and set
     * android:isolatedProcess="true" in the service's declaration. Otherwise, this throws an
     * {@link IllegalStateException}.
     *
     * <p>Using this has a noticeable impact on battery, since the microphone is kept open
     * for the lifetime of the recognition {@link VisualQueryDetector#startRecognition() session}.
     *
     * @param options Application configuration data to be provided to the
     * {@link VisualQueryDetectionService}. PersistableBundle does not allow any remotable objects
     * or other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to be provided to the
     * {@link VisualQueryDetectionService}. Use this to provide models or other such data to the
     * sandboxed process.
     * @param callback The callback to notify of detection events.
     * @return An instanece of {@link VisualQueryDetector}.
     * @throws IllegalStateException when there is an existing {@link VisualQueryDetector}, or when
     * there is a non-trusted hotword detector running.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final VisualQueryDetector createVisualQueryDetector(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull VisualQueryDetector.Callback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (!SYSPROP_VISUAL_QUERY_SERVICE_ENABLED) {
            throw new IllegalStateException("VisualQueryDetectionService is not enabled on this "
                    + "system. Please set ro.hotword.visual_query_service_enabled to true.");
        }
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            if (mActiveVisualQueryDetector != null) {
                throw new IllegalStateException(
                            "There is already an active VisualQueryDetector. "
                                    + "It must be destroyed to create a new one.");
            }
            for (HotwordDetector detector : mActiveDetectors) {
                if (!detector.isUsingSandboxedDetectionService()) {
                    throw new IllegalStateException(
                            "It disallows to create trusted and non-trusted detectors "
                                    + "at the same time.");
                }
            }

            VisualQueryDetector visualQueryDetector =
                    new VisualQueryDetector(mSystemService, executor, callback, this,
                            getAttributionTag());
            HotwordDetector visualQueryDetectorInitializationDelegate =
                    visualQueryDetector.getInitializationDelegate();
            mActiveDetectors.add(visualQueryDetectorInitializationDelegate);

            try {
                visualQueryDetector.registerOnDestroyListener(this::onHotwordDetectorDestroyed);
                visualQueryDetector.initialize(options, sharedMemory);
            } catch (Exception e) {
                mActiveDetectors.remove(visualQueryDetectorInitializationDelegate);
                visualQueryDetector.destroy();
                throw e;
            }
            mActiveVisualQueryDetector = visualQueryDetector;
            return visualQueryDetector;
        }
    }

    /**
     * Allow/disallow receiving training data from trusted process.
     *
     * <p> This method can be called by a preinstalled assistant to receive/stop receiving
     * training data via {@link HotwordDetector.Callback#onTrainingData(HotwordTrainingData)}.
     * These training data events are produced during sandboxed detection (in trusted process).
     *
     * @param allowed whether to allow/disallow receiving training data produced during
     *                sandboxed detection (from trusted process).
     * @throws SecurityException if caller is not a preinstalled assistant or if caller is not the
     * active assistant.
     *
     * @hide
     */
    //TODO(b/315053245): Add mitigations to make API no-op once user has modified setting.
    @SystemApi
    @FlaggedApi(Flags.FLAG_ALLOW_TRAINING_DATA_EGRESS_FROM_HDS)
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    public void setShouldReceiveSandboxedTrainingData(boolean allowed) {
        Log.i(TAG, "setShouldReceiveSandboxedTrainingData to " + allowed);
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        try {
            mSystemService.setShouldReceiveSandboxedTrainingData(allowed);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates an {@link KeyphraseModelManager} to use for enrolling voice models outside of the
     * pre-bundled system voice models.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
    @NonNull
    public final KeyphraseModelManager createKeyphraseModelManager() {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            return new KeyphraseModelManager(mSystemService);
        }
    }

    /**
     * @return Details of keyphrases available for enrollment.
     * @hide
     */
    @VisibleForTesting
    protected final KeyphraseEnrollmentInfo getKeyphraseEnrollmentInfo() {
        return mKeyphraseEnrollmentInfo;
    }


    /**
     * Configure {@link createAlwaysOnHotwordDetector(String, Locale,
     * SoundTrigger.ModuleProperties, Executor, AlwaysOnHotwordDetector.Callback)}
     * and similar overloads to utilize the test SoundTrigger module instead of the
     * actual DSP module.
     * @param isEnabled - {@code true} if subsequently created {@link AlwaysOnHotwordDetector}
     * objects should attach to a test module. {@code false} if subsequently created
     * {@link AlwaysOnHotwordDetector} should attach to the actual DSP module.
     * @hide
     */
    @TestApi
    public final void setTestModuleForAlwaysOnHotwordDetectorEnabled(boolean isEnabled) {
        synchronized (mLock) {
            mTestModuleForAlwaysOnHotwordDetectorEnabled = isEnabled;
        }
    }

    /**
     * Get the {@link SoundTrigger.ModuleProperties} representing the fake
     * STHAL to attach to via {@link createAlwaysOnHotwordDetector(String, Locale,
     * SoundTrigger.ModuleProperties, Executor, AlwaysOnHotwordDetector.Callback)} and
     * similar overloads for test purposes.
     * @return ModuleProperties to use for test purposes.
     */
    private final @NonNull SoundTrigger.ModuleProperties getTestModuleProperties() {
        var moduleProps = listModuleProperties()
                .stream()
                .filter((SoundTrigger.ModuleProperties prop)
                        -> prop.getSupportedModelArch().equals(SoundTrigger.FAKE_HAL_ARCH))
                .findFirst()
                .orElse(null);
        if (moduleProps == null) {
            throw new IllegalStateException("Fake ST HAL should always be available");
        }
        return moduleProps;
    }

    /**
     * Checks if a given keyphrase and locale are supported to create an
     * {@link AlwaysOnHotwordDetector}.
     *
     * @return true if the keyphrase and locale combination is supported, false otherwise.
     * @hide
     */
    @UnsupportedAppUsage
    public final boolean isKeyphraseAndLocaleSupportedForHotword(String keyphrase, Locale locale) {
        if (mKeyphraseEnrollmentInfo == null) {
            return false;
        }
        return mKeyphraseEnrollmentInfo.getKeyphraseMetadata(keyphrase, locale) != null;
    }

    private void safelyShutdownAllHotwordDetectors(boolean shouldShutDownVisualQueryDetector) {
        synchronized (mLock) {
            mActiveDetectors.forEach(detector -> {
                try {
                    // Skip destroying VisualQueryDetector if HotwordDetectors are created
                    if (!(mActiveVisualQueryDetector != null
                            && detector == mActiveVisualQueryDetector.getInitializationDelegate())
                            || shouldShutDownVisualQueryDetector) {
                        detector.destroy();
                    }
                } catch (Exception ex) {
                    Log.i(TAG, "exception destroying HotwordDetector", ex);
                }
            });
        }
    }

    private void onHotwordDetectorDestroyed(@NonNull HotwordDetector detector) {
        synchronized (mLock) {
            if (mActiveVisualQueryDetector != null
                    && detector == mActiveVisualQueryDetector.getInitializationDelegate()) {
                mActiveVisualQueryDetector = null;
            }
            mActiveDetectors.remove(detector);
        }
    }

    /**
     * Provide hints to be reflected in the system UI.
     *
     * @param hints Arguments used to show UI.
     */
    public final void setUiHints(@NonNull Bundle hints) {
        if (hints == null) {
            throw new IllegalArgumentException("Hints must be non-null");
        }

        try {
            mSystemService.setUiHints(hints);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("VOICE INTERACTION");
        synchronized (mLock) {
            pw.println("  Sandboxed Detector(s):");
            if (mActiveDetectors.size() == 0) {
                pw.println("    No detector.");
            } else {
                mActiveDetectors.forEach(detector -> {
                    pw.print("  Using sandboxed detection service=");
                    pw.println(detector.isUsingSandboxedDetectionService());
                    detector.dump("    ", pw);
                    pw.println();
                });
            }
            pw.println("Available Model Enrollment Applications:");
            pw.println("  " + mKeyphraseEnrollmentInfo);
        }
    }
}
