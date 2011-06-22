/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.textservice;

import com.android.internal.textservice.ITextServicesManager;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.textservice.SpellCheckerInfo;
import android.service.textservice.SpellCheckerSession;
import android.service.textservice.SpellCheckerSession.SpellCheckerSessionListener;

import java.util.Locale;

/**
 * System API to the overall text services, which arbitrates interaction between applications
 * and text services. You can retrieve an instance of this interface with
 * {@link Context#getSystemService(String) Context.getSystemService()}.
 *
 * The user can change the current text services in Settings. And also applications can specify
 * the target text services.
 */
public final class TextServicesManager {
    private static final String TAG = TextServicesManager.class.getSimpleName();

    private static TextServicesManager sInstance;
    private static ITextServicesManager sService;

    private TextServicesManager() {
        if (sService == null) {
            IBinder b = ServiceManager.getService(Context.TEXT_SERVICES_MANAGER_SERVICE);
            sService = ITextServicesManager.Stub.asInterface(b);
        }
    }

    /**
     * Retrieve the global TextServicesManager instance, creating it if it doesn't already exist.
     * @hide
     */
    public static TextServicesManager getInstance() {
        synchronized (TextServicesManager.class) {
            if (sInstance != null) {
                return sInstance;
            }
            sInstance = new TextServicesManager();
        }
        return sInstance;
    }


    /**
     * Get the current spell checker service info for the specified locale.
     * @param locale locale of a spell checker
     * @return SpellCheckerInfo for the specified locale.
     */
    // TODO: Add a method to get enabled spell checkers.
    public SpellCheckerInfo getCurrentSpellChecker(Locale locale) {
        if (locale == null) {
            throw new NullPointerException("locale is null");
        }
        try {
            return sService.getCurrentSpellChecker(locale.toString());
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get a spell checker session for a specified spell checker
     * @param info SpellCheckerInfo of the spell checker
     * @param locale the locale for the spell checker
     * @param listener a spell checker session lister for getting results from a spell checker.
     * @return the spell checker session of the spell checker
     */
    public SpellCheckerSession newSpellCheckerSession(
            SpellCheckerInfo info, Locale locale, SpellCheckerSessionListener listener) {
        if (info == null || locale == null || listener == null) {
            throw new NullPointerException();
        }
        final SpellCheckerSession session = new SpellCheckerSession(sService, listener);
        try {
            sService.getSpellCheckerService(
                    info, locale.toString(), session.getTextServicesSessionListener(),
                    session.getSpellCheckerSessionListener());
        } catch (RemoteException e) {
            return null;
        }
        return session;
    }
}
