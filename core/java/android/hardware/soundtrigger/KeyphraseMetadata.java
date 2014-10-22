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

import java.util.Locale;

/**
 * A Voice Keyphrase metadata read from the enrollment application.
 *
 * @hide
 */
public class KeyphraseMetadata {
    public final int id;
    public final String keyphrase;
    public final ArraySet<Locale> supportedLocales;
    public final int recognitionModeFlags;

    public KeyphraseMetadata(int id, String keyphrase, ArraySet<Locale> supportedLocales,
            int recognitionModeFlags) {
        this.id = id;
        this.keyphrase = keyphrase;
        this.supportedLocales = supportedLocales;
        this.recognitionModeFlags = recognitionModeFlags;
    }

    @Override
    public String toString() {
        return "id=" + id + ", keyphrase=" + keyphrase + ", supported-locales=" + supportedLocales
                + ", recognition-modes=" + recognitionModeFlags;
    }

    /**
     * @return Indicates if we support the given phrase.
     */
    public boolean supportsPhrase(String phrase) {
        return keyphrase.isEmpty() || keyphrase.equalsIgnoreCase(phrase);
    }

    /**
     * @return Indicates if we support the given locale.
     */
    public boolean supportsLocale(Locale locale) {
        return supportedLocales.isEmpty() || supportedLocales.contains(locale);
    }
}
