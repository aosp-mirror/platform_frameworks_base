/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;

import com.android.internal.annotations.Immutable;

/**
 * @hide Private interface to the VoiceInteractionManagerService for use within system_server.
 */
public abstract class VoiceInteractionManagerInternal {

    /**
     * Start a new voice interaction session when requested from within an activity
     * by Activity.startLocalVoiceInteraction()
     * @param callingActivity The binder token representing the calling activity.
     * @param attributionTag The attribution tag of the calling context or {@code null} for default
     *                       attribution
     * @param options A Bundle of private arguments to the current voice interaction service
     */
    public abstract void startLocalVoiceInteraction(@NonNull IBinder callingActivity,
            @Nullable String attributionTag, @Nullable Bundle options);

    /**
     * Returns whether the currently selected voice interaction service supports local voice
     * interaction for launching from an Activity.
     */
    public abstract boolean supportsLocalVoiceInteraction();

    public abstract void stopLocalVoiceInteraction(IBinder callingActivity);

    /**
     * Returns whether the given package is currently in an active session
     */
    public abstract boolean hasActiveSession(String packageName);

    /**
     * Returns the package name of the active session.
     *
     * @param callingVoiceInteractor the voice interactor binder from the calling VoiceInteractor.
     */
    public abstract String getVoiceInteractorPackageName(IBinder callingVoiceInteractor);

    /**
     * Gets the identity of the currently active HotwordDetectionService.
     *
     * @see HotwordDetectionServiceIdentity
     */
    @Nullable
    public abstract HotwordDetectionServiceIdentity getHotwordDetectionServiceIdentity();

    /**
     * Called by {@code UMS.convertPreCreatedUserIfPossible()} when a new user is not created from
     * scratched, but converted from the pool of existing pre-created users.
     */
    // TODO(b/226201975): remove method once RoleService supports pre-created users
    public abstract void onPreCreatedUserConversion(@UserIdInt int userId);

    /**
     * Called by {@link com.android.server.wearable.WearableSensingManagerPerUserService} when a
     * wearable starts sending audio data for hotword detection.
     *
     * @param audioStream The audio data.
     * @param audioFormat The format of the audio data.
     * @param options Options supporting hotword detection.
     * @param targetVisComponentName The target VoiceInteractionService ComponentName
     * @param userId The user ID of the calling wearable service
     * @param callback The callback to notify the caller of the hotword detection result.
     */
    public abstract void startListeningFromWearable(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            PersistableBundle options,
            ComponentName targetVisComponentName,
            int userId,
            WearableHotwordDetectionCallback callback);

    /**
     * Provides the uids of the currently active
     * {@link android.service.voice.HotwordDetectionService} and its owning package. The
     * HotwordDetectionService is an isolated service, so it has a separate uid.
     */
    @Immutable
    public static class HotwordDetectionServiceIdentity {
        private final int mIsolatedUid;
        private final int mOwnerUid;

        public HotwordDetectionServiceIdentity(int isolatedUid, int ownerUid) {
            mIsolatedUid = isolatedUid;
            mOwnerUid = ownerUid;
        }

        /** Gets the uid of the currently active isolated process hosting the service. */
        public int getIsolatedUid() {
            return mIsolatedUid;
        }

        /** Gets the uid of the package that provides the HotwordDetectionService. */
        public int getOwnerUid() {
            return mOwnerUid;
        }
    }

    /**
     * Callback for returning the detected hotword result to the wearable.
     *
     * @hide
     */
    public interface WearableHotwordDetectionCallback {
        /** Called when hotword is detected. */
        void onDetected();

        /** Called when hotword is not detected. */
        void onRejected();

        /** Called when an unexpected error occurs. */
        void onError(String errorMessage);
    }
}
