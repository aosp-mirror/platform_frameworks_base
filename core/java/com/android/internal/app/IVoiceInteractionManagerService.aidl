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

package com.android.internal.app;

import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.SharedMemory;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSoundTriggerSession;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.SoundTrigger;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;

interface IVoiceInteractionManagerService {
    void showSession(in Bundle sessionArgs, int flags);
    boolean deliverNewSession(IBinder token, IVoiceInteractionSession session,
            IVoiceInteractor interactor);
    boolean showSessionFromSession(IBinder token, in Bundle sessionArgs, int flags);
    boolean hideSessionFromSession(IBinder token);
    int startVoiceActivity(IBinder token, in Intent intent, String resolvedType,
            String callingFeatureId);
    int startAssistantActivity(IBinder token, in Intent intent, String resolvedType,
            String callingFeatureId);
    void setKeepAwake(IBinder token, boolean keepAwake);
    void closeSystemDialogs(IBinder token);
    void finish(IBinder token);
    void setDisabledShowContext(int flags);
    int getDisabledShowContext();
    int getUserDisabledShowContext();

    /**
     * Gets the registered Sound model for keyphrase detection for the current user.
     * May be null if no matching sound model exists.
     * Caller must either be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}, or the caller must be a voice model
     * enrollment application detected by
     * {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param bcp47Locale The BCP47 language tag  for the keyphrase's locale.
     * @RequiresPermission Manifest.permission.MANAGE_VOICE_KEYPHRASES
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    SoundTrigger.KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, in String bcp47Locale);
    /**
     * Add/Update the given keyphrase sound model for the current user.
     * Caller must either be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}, or the caller must be a voice model
     * enrollment application detected by
     * {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
     *
     * @param model The keyphrase sound model to store peristantly.
     * @RequiresPermission Manifest.permission.MANAGE_VOICE_KEYPHRASES
     */
    int updateKeyphraseSoundModel(in SoundTrigger.KeyphraseSoundModel model);
    /**
     * Deletes the given keyphrase sound model for the current user.
     * Caller must either be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}, or the caller must be a voice model
     * enrollment application detected by
     * {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param bcp47Locale The BCP47 language tag  for the keyphrase's locale.
     * @RequiresPermission Manifest.permission.MANAGE_VOICE_KEYPHRASES
     */
    int deleteKeyphraseSoundModel(int keyphraseId, in String bcp47Locale);
    /**
     * Indicates if there's a keyphrase sound model available for the given keyphrase ID and the
     * user ID of the caller.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param bcp47Locale The BCP47 language tag  for the keyphrase's locale.
     */
    boolean isEnrolledForKeyphrase(int keyphraseId, String bcp47Locale);
    /**
     * Generates KeyphraseMetadata for an enrolled sound model based on keyphrase string, locale,
     * and the user ID of the caller.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     *
     * @param keyphrase Keyphrase text associated with the enrolled model
     * @param bcp47Locale The BCP47 language tag for the keyphrase's locale.
     * @return The metadata for the enrolled voice model bassed on the passed in parameters. Null if
     *         no matching voice model exists.
     */
    KeyphraseMetadata getEnrolledKeyphraseMetadata(String keyphrase, String bcp47Locale);
    /**
     * @return the component name for the currently active voice interaction service
     */
    ComponentName getActiveServiceComponentName();

    /**
     * Shows the session for the currently active service. Used to start a new session from system
     * affordances.
     *
     * @param args the bundle to pass as arguments to the voice interaction session
     * @param sourceFlags flags indicating the source of this show
     * @param showCallback optional callback to be notified when the session was shown
     * @param activityToken optional token of activity that needs to be on top
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    boolean showSessionForActiveService(in Bundle args, int sourceFlags,
            IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken);

    /**
     * Hides the session from the active service, if it is showing.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    void hideCurrentSession();

    /**
     * Notifies the active service that a launch was requested from the Keyguard. This will only
     * be called if {@link #activeServiceSupportsLaunchFromKeyguard()} returns true.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    void launchVoiceAssistFromKeyguard();

    /**
     * Indicates whether there is a voice session running (but not necessarily showing).
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    boolean isSessionRunning();

    /**
     * Indicates whether the currently active voice interaction service is capable of handling the
     * assist gesture.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    boolean activeServiceSupportsAssist();

    /**
     * Indicates whether the currently active voice interaction service is capable of being launched
     * from the lockscreen.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    boolean activeServiceSupportsLaunchFromKeyguard();

    /**
     * Called when the lockscreen got shown.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    void onLockscreenShown();

    /**
     * Register a voice interaction listener.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    void registerVoiceInteractionSessionListener(IVoiceInteractionSessionListener listener);

    /**
     * Checks the availability of a set of voice actions for the current active voice service.
     * Returns all supported voice actions.
     * @RequiresPermission Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE
     */
    void getActiveServiceSupportedActions(in List<String> voiceActions,
     in IVoiceActionCheckCallback callback);

    /**
     * Provide hints for showing UI.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     */
    void setUiHints(in Bundle hints);

    /**
     * Requests a list of supported actions from a specific activity.
     */
    void requestDirectActions(in IBinder token, int taskId, IBinder assistToken,
             in RemoteCallback cancellationCallback, in RemoteCallback callback);

    /**
     * Requests performing an action from a specific activity.
     */
    void performDirectAction(in IBinder token, String actionId, in Bundle arguments, int taskId,
            IBinder assistToken, in RemoteCallback cancellationCallback,
            in RemoteCallback resultCallback);

    /**
     * Temporarily disables voice interaction (for example, on Automotive when the display is off).
     *
     * It will shutdown the service, and only re-enable it after it's called again (or after a
     * system restart).
     *
     * NOTE: it's only effective when the service itself is available / enabled in the device, so
     * calling setDisable(false) would be a no-op when it isn't.
     */
    void setDisabled(boolean disabled);

    /**
     * Creates a session, allowing controlling running sound models on detection hardware.
     * Caller must provide an identity, used for permission tracking purposes.
     * The uid/pid elements of the identity will be ignored by the server and replaced with the ones
     * provided by binder.
     *
     * The client argument is any binder owned by the client, used for tracking is death and
     * cleaning up in this event.
     */
    IVoiceInteractionSoundTriggerSession createSoundTriggerSessionAsOriginator(
            in Identity originatorIdentity,
            IBinder client);

    /**
     * Set configuration and pass read-only data to hotword detection service.
     * Caller must provide an identity, used for permission tracking purposes.
     * The uid/pid elements of the identity will be ignored by the server and replaced with the ones
     * provided by binder.
     *
     * @param options Application configuration data to provide to the
     * {@link HotwordDetectionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the
     * {@link HotwordDetectionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     * @param callback Use this to report {@link HotwordDetectionService} status.
     * @param detectorType Indicate which detector is used.
     */
    void updateState(
            in Identity originatorIdentity,
            in PersistableBundle options,
            in SharedMemory sharedMemory,
            in IHotwordRecognitionStatusCallback callback,
                        int detectorType);

    /**
     * Requests to shutdown hotword detection service.
     */
    void shutdownHotwordDetectionService();

    void startListeningFromMic(
        in AudioFormat audioFormat,
        in IMicrophoneHotwordDetectionVoiceInteractionCallback callback);

    void stopListeningFromMic();

    void startListeningFromExternalSource(
        in ParcelFileDescriptor audioStream,
        in AudioFormat audioFormat,
        in PersistableBundle options,
        in IMicrophoneHotwordDetectionVoiceInteractionCallback callback);

    /**
     * Test API to simulate to trigger hardware recognition event for test.
     */
    void triggerHardwareRecognitionEventForTest(
            in SoundTrigger.KeyphraseRecognitionEvent event,
            in IHotwordRecognitionStatusCallback callback);

    /**
     * Starts to listen the status of visible activity.
     */
    void startListeningVisibleActivityChanged(in IBinder token);

    /**
     * Stops to listen the status of visible activity.
     */
    void stopListeningVisibleActivityChanged(in IBinder token);
}
