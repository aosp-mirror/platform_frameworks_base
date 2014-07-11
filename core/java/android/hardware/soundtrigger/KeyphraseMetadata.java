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

package android.hardware.soundtrigger;

import android.util.ArraySet;

/**
 * A Voice Keyphrase metadata read from the enrollment application.
 *
 * @hide
 */
public class KeyphraseMetadata {
    public final int id;
    public final String keyphrase;
    public final ArraySet<String> supportedLocales;

    public KeyphraseMetadata(int id, String keyphrase, String[] supportedLocales) {
        this.id = id;
        this.keyphrase = keyphrase;
        this.supportedLocales = new ArraySet<String>(supportedLocales.length);
        for (String locale : supportedLocales) {
            this.supportedLocales.add(locale);
        }
    }

    @Override
    public String toString() {
        return "id=" + id + ", keyphrase=" + keyphrase + ", supported-locales=" + supportedLocales;
    }

    /**
     * @return Indicates if we support the given phrase.
     */
    public boolean supportsPhrase(String phrase) {
        // TODO(sansid): Come up with a scheme for custom keyphrases that should always match.
        return keyphrase.equalsIgnoreCase(phrase);
    }

    /**
     * @return Indicates if we support the given locale.
     */
    public boolean supportsLocale(String locale) {
        // TODO(sansid): Come up with a scheme for keyphrases that are available in all locales.
        return supportedLocales.contains(locale);
    }
}
