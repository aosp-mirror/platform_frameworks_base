/**
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;

import java.io.PrintWriter;

/**
 * Interface for registering and querying the enrolled keyphrase model database for
 * {@link VoiceInteractionManagerService}.
 * This interface only supports one keyphrase per {@link KeyphraseSoundModel}.
 * The non-update methods are uniquely keyed on fields of the first keyphrase
 * {@link KeyphraseSoundModel#getKeyphrases()}.
 * @hide
 */
public interface IEnrolledModelDb {

    //TODO(273286174): We only support one keyphrase currently.
    /**
     * Register the given {@link KeyphraseSoundModel}, or updates it if it already exists.
     *
     * @param soundModel - The sound model to register in the database.
     * Updates the sound model if the keyphrase id, users, locale match an existing entry.
     * Must have one and only one associated {@link Keyphrase}.
     * @return - {@code true} if successful, {@code false} if unsuccessful
     */
    boolean updateKeyphraseSoundModel(KeyphraseSoundModel soundModel);

    /**
     * Deletes the previously registered keyphrase sound model from the database.
     *
     * @param keyphraseId - The (first) keyphrase ID of the KeyphraseSoundModel to delete.
     * @param userHandle - The user handle making this request. Must be included in the user
     *                     list of the registered sound model.
     * @param bcp47Locale - The locale of the (first) keyphrase associated with this model.
     * @return - {@code true} if successful, {@code false} if unsuccessful
     */
    boolean deleteKeyphraseSoundModel(int keyphraseId, int userHandle, String bcp47Locale);

    //TODO(273286174): We only support one keyphrase currently.
    /**
     * Returns the first matching {@link KeyphraseSoundModel} for the keyphrase ID, locale pair,
     * contingent on the userHandle existing in the user list for the model.
     * Returns null if a match isn't found.
     *
     * @param keyphraseId - The (first) keyphrase ID of the KeyphraseSoundModel to query.
     * @param userHandle - The user handle making this request. Must be included in the user
     *                     list of the registered sound model.
     * @param bcp47Locale - The locale of the (first) keyphrase associated with this model.
     * @return - {@code true} if successful, {@code false} if unsuccessful
     */
    KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, int userHandle,
            String bcp47Locale);

    //TODO(273286174): We only support one keyphrase currently.
    /**
     * Returns the first matching {@link KeyphraseSoundModel} for the keyphrase ID, locale pair,
     * contingent on the userHandle existing in the user list for the model.
     * Returns null if a match isn't found.
     *
     * @param keyphrase - The text of (the first) keyphrase of the KeyphraseSoundModel to query.
     * @param userHandle - The user handle making this request. Must be included in the user
     *                     list of the registered sound model.
     * @param bcp47Locale - The locale of the (first) keyphrase associated with this model.
     * @return - {@code true} if successful, {@code false} if unsuccessful
     */
    KeyphraseSoundModel getKeyphraseSoundModel(String keyphrase, int userHandle,
            String bcp47Locale);

    /**
     * Dumps contents of database for dumpsys
     */
    void dump(PrintWriter pw);
}
