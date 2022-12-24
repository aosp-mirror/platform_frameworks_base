/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import android.annotation.SystemService;
import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This class allow applications to control granular grammatical inflection settings (such as
 * per-app grammatical gender).
 */
@SystemService(Context.GRAMMATICAL_INFLECTION_SERVICE)
public class GrammaticalInflectionManager {
    private static final Set<Integer> VALID_GENDER_VALUES = new HashSet<>(Arrays.asList(
            Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED,
            Configuration.GRAMMATICAL_GENDER_NEUTRAL,
            Configuration.GRAMMATICAL_GENDER_FEMININE,
            Configuration.GRAMMATICAL_GENDER_MASCULINE));

    private final Context mContext;
    private final IGrammaticalInflectionManager mService;

    /** @hide Instantiated by ContextImpl */
    public GrammaticalInflectionManager(Context context, IGrammaticalInflectionManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns the current grammatical gender for the calling app. A new value can be requested via
     * {@link #setRequestedApplicationGrammaticalGender(int)} and will be updated with a new
     * configuration change. The method always returns the value received with the last received
     * configuration change.
     *
     * @return the value of grammatical gender
     * @see Configuration#getGrammaticalGender
     */
    @Configuration.GrammaticalGender
    public int getApplicationGrammaticalGender() {
        return mContext.getApplicationContext()
                .getResources()
                .getConfiguration()
                .getGrammaticalGender();
    }

    /**
     * Sets the current grammatical gender for the calling app (keyed by package name and user ID
     * retrieved from the calling pid).
     *
     * <p><b>Note:</b> Changes to app grammatical gender will result in a configuration change (and
     * potentially an Activity re-creation) being applied to the specified application. For more
     * information, see the <a
     * href="https://developer.android.com/guide/topics/resources/runtime-changes">section on
     * handling configuration changes</a>. The set grammatical gender are persisted across
     * application restarts; they are backed up if the user has enabled Backup & Restore.`
     *
     * @param grammaticalGender the terms of address the user preferred in an application.
     * @see Configuration#getGrammaticalGender
     */
    public void setRequestedApplicationGrammaticalGender(
            @Configuration.GrammaticalGender int grammaticalGender) {
        if (!VALID_GENDER_VALUES.contains(grammaticalGender)) {
            throw new IllegalArgumentException("Unknown grammatical gender");
        }

        try {
            mService.setRequestedApplicationGrammaticalGender(
                    mContext.getPackageName(), mContext.getUserId(), grammaticalGender);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
