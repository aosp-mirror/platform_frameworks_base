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

import android.annotation.NonNull;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * In memory model enrollment database for testing purposes.
 * @hide
 */
public class TestModelEnrollmentDatabase implements IEnrolledModelDb {

    // Record representing the primary key used in the real model database.
    private static final class EnrollmentKey {
        private final int mKeyphraseId;
        private final List<Integer> mUserIds;
        private final String mLocale;

        EnrollmentKey(int keyphraseId,
                @NonNull List<Integer> userIds, @NonNull String locale) {
            mKeyphraseId = keyphraseId;
            mUserIds = Objects.requireNonNull(userIds);
            mLocale = Objects.requireNonNull(locale);
        }

        int keyphraseId() {
            return mKeyphraseId;
        }

        List<Integer> userIds() {
            return mUserIds;
        }

        String locale() {
            return mLocale;
        }

        @Override
        public String toString() {
            StringJoiner sj = new StringJoiner(", ", "{", "}");
            sj.add("keyphraseId: " + mKeyphraseId);
            sj.add("userIds: " + mUserIds.toString());
            sj.add("locale: " + mLocale.toString());
            return "EnrollmentKey: " + sj.toString();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int res = 1;
            res = prime * res + mKeyphraseId;
            res = prime * res + mUserIds.hashCode();
            res = prime * res + mLocale.hashCode();
            return res;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null) return false;
            if (!(other instanceof EnrollmentKey)) return false;
            EnrollmentKey that = (EnrollmentKey) other;
            if (mKeyphraseId != that.mKeyphraseId) return false;
            if (!mUserIds.equals(that.mUserIds)) return false;
            if (!mLocale.equals(that.mLocale)) return false;
            return true;
        }

    }

    private final Map<EnrollmentKey, KeyphraseSoundModel> mModelMap = new HashMap<>();

    @Override
    public boolean updateKeyphraseSoundModel(KeyphraseSoundModel soundModel) {
        final Keyphrase keyphrase = soundModel.getKeyphrases()[0];
        mModelMap.put(new EnrollmentKey(keyphrase.getId(),
                        Arrays.stream(keyphrase.getUsers()).boxed().toList(),
                        keyphrase.getLocale().toLanguageTag()),
                    soundModel);
        return true;
    }

    @Override
    public boolean deleteKeyphraseSoundModel(int keyphraseId, int userHandle, String bcp47Locale) {
        return mModelMap.keySet().removeIf(key -> (key.keyphraseId() == keyphraseId)
                && key.locale().equals(bcp47Locale)
                && key.userIds().contains(userHandle));
    }

    @Override
    public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, int userHandle,
            String bcp47Locale) {
        return mModelMap.entrySet()
                .stream()
                .filter((entry) -> (entry.getKey().keyphraseId() == keyphraseId)
                        && entry.getKey().locale().equals(bcp47Locale)
                        && entry.getKey().userIds().contains(userHandle))
                .findFirst()
                .map((entry) -> entry.getValue())
                .orElse(null);
    }

    @Override
    public KeyphraseSoundModel getKeyphraseSoundModel(String keyphrase, int userHandle,
            String bcp47Locale) {
        return mModelMap.entrySet()
                .stream()
                .filter((entry) -> (entry.getValue().getKeyphrases()[0].getText().equals(keyphrase)
                        && entry.getKey().locale().equals(bcp47Locale)
                        && entry.getKey().userIds().contains(userHandle)))
                .findFirst()
                .map((entry) -> entry.getValue())
                .orElse(null);
    }


    /**
     * Dumps contents of database for dumpsys
     */
    public void dump(PrintWriter pw) {
        pw.println("Using test enrollment database, with enrolled models:");
        pw.println(mModelMap);
    }
}
