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

package android.view.textservice;

import android.os.Bundle;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;

import java.util.Locale;

/**
 * A stub class of TextServicesManager for Layout-Lib.
 */
public final class TextServicesManager {
    private static final TextServicesManager sInstance = new TextServicesManager();
    private static final SpellCheckerInfo[] EMPTY_SPELL_CHECKER_INFO = new SpellCheckerInfo[0];

    /**
     * Retrieve the global TextServicesManager instance, creating it if it doesn't already exist.
     * @hide
     */
    public static TextServicesManager getInstance() {
        return sInstance;
    }

    public SpellCheckerSession newSpellCheckerSession(Bundle bundle, Locale locale,
            SpellCheckerSessionListener listener, boolean referToSpellCheckerLanguageSettings) {
        return null;
    }

    /**
     * @hide
     */
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        return EMPTY_SPELL_CHECKER_INFO;
    }

    /**
     * @hide
     */
    public SpellCheckerInfo getCurrentSpellChecker() {
        return null;
    }

    /**
     * @hide
     */
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            boolean allowImplicitlySelectedSubtype) {
        return null;
    }

    /**
     * @hide
     */
    public boolean isSpellCheckerEnabled() {
        return false;
    }
}
