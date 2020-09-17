/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.voice;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.Locale;
import java.util.Objects;

/**
 * This class provides management of voice based sound recognition models. Usage of this class is
 * restricted to system or signature applications only. This allows OEMs to write apps that can
 * manage voice based sound trigger models.
 * Callers of this class are expected to have whitelist manifest permission MANAGE_VOICE_KEYPHRASES.
 * Callers of this class are expected to be the designated voice interaction service via
 * {@link Settings.Secure.VOICE_INTERACTION_SERVICE} or a bundled voice model enrollment application
 * detected by {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
 * @hide
 */
@SystemApi
public final class KeyphraseModelManager {
    private static final boolean DBG = false;
    private static final String TAG = "KeyphraseModelManager";

    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

    /**
     * @hide
     */
    public KeyphraseModelManager(
            IVoiceInteractionManagerService voiceInteractionManagerService) {
        if (DBG) {
            Slog.i(TAG, "KeyphraseModelManager created.");
        }
        mVoiceInteractionManagerService = voiceInteractionManagerService;
    }


    /**
     * Gets the registered sound model for keyphrase detection for the current user.
     * The keyphraseId and locale passed must match a supported model passed in via
     * {@link #updateKeyphraseSoundModel}.
     * If the active voice interaction service changes from the current user, all requests will be
     * rejected, and any registered models will be unregistered.
     * Caller must either be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}, or the caller must be a voice model
     * enrollment application detected by
     * {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param locale The locale language tag supported by the desired model.
     * @return Registered keyphrase sound model matching the keyphrase ID and locale. May be null if
     * no matching sound model exists.
     * @throws SecurityException Thrown when caller does not have MANAGE_VOICE_KEYPHRASES permission
     *                           or if the caller is not the active voice interaction service.
     */
    @RequiresPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
    @Nullable
    public SoundTrigger.KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId,
            @NonNull Locale locale) {
        Objects.requireNonNull(locale);
        try {
            return mVoiceInteractionManagerService.getKeyphraseSoundModel(keyphraseId,
                    locale.toLanguageTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add or update the given keyphrase sound model to the registered models pool for the current
     * user.
     * If a model exists with the same Keyphrase ID, locale, and user list. The registered model
     * will be overwritten with the new model.
     * If the active voice interaction service changes from the current user, all requests will be
     * rejected, and any registered models will be unregistered.
     * Caller must either be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}, or the caller must be a voice model
     * enrollment application detected by
     * {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
     *
     * @param model Keyphrase sound model to be updated.
     * @throws ServiceSpecificException Thrown with error code if failed to update the keyphrase
     *                           sound model.
     * @throws SecurityException Thrown when caller does not have MANAGE_VOICE_KEYPHRASES permission
     *                           or if the caller is not the active voice interaction service.
     */
    @RequiresPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
    public void updateKeyphraseSoundModel(@NonNull SoundTrigger.KeyphraseSoundModel model) {
        Objects.requireNonNull(model);
        try {
            int status = mVoiceInteractionManagerService.updateKeyphraseSoundModel(model);
            if (status != SoundTrigger.STATUS_OK) {
                throw new ServiceSpecificException(status);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete keyphrase sound model from the registered models pool for the current user matching\
     * the keyphrase ID and locale.
     * The keyphraseId and locale passed must match a supported model passed in via
     * {@link #updateKeyphraseSoundModel}.
     * If the active voice interaction service changes from the current user, all requests will be
     * rejected, and any registered models will be unregistered.
     * Caller must either be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}, or the caller must be a voice model
     * enrollment application detected by
     * {@link android.hardware.soundtrigger.KeyphraseEnrollmentInfo}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param locale The locale language tag supported by the desired model.
     * @throws ServiceSpecificException Thrown with error code if failed to delete the keyphrase
     *                           sound model.
     * @throws SecurityException Thrown when caller does not have MANAGE_VOICE_KEYPHRASES permission
     *                           or if the caller is not the active voice interaction service.
     */
    @RequiresPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
    public void deleteKeyphraseSoundModel(int keyphraseId, @NonNull Locale locale) {
        Objects.requireNonNull(locale);
        try {
            int status = mVoiceInteractionManagerService.deleteKeyphraseSoundModel(keyphraseId,
                    locale.toLanguageTag());
            if (status != SoundTrigger.STATUS_OK) {
                throw new ServiceSpecificException(status);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
