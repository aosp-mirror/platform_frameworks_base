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

import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;

import com.android.internal.textservice.ITextServicesManager;

import java.util.Locale;

/**
 * System API to the overall text services, which arbitrates interaction between applications
 * and text services.
 *
 * The user can change the current text services in Settings. And also applications can specify
 * the target text services.
 *
 * <h3>Architecture Overview</h3>
 *
 * <p>There are three primary parties involved in the text services
 * framework (TSF) architecture:</p>
 *
 * <ul>
 * <li> The <strong>text services manager</strong> as expressed by this class
 * is the central point of the system that manages interaction between all
 * other parts.  It is expressed as the client-side API here which exists
 * in each application context and communicates with a global system service
 * that manages the interaction across all processes.
 * <li> A <strong>text service</strong> implements a particular
 * interaction model allowing the client application to retrieve information of text.
 * The system binds to the current text service that is in use, causing it to be created and run.
 * <li> Multiple <strong>client applications</strong> arbitrate with the text service
 * manager for connections to text services.
 * </ul>
 *
 * <h3>Text services sessions</h3>
 * <ul>
 * <li>The <strong>spell checker session</strong> is one of the text services.
 * {@link android.view.textservice.SpellCheckerSession}</li>
 * </ul>
 *
 */
@SystemService(Context.TEXT_SERVICES_MANAGER_SERVICE)
public final class TextServicesManager {
    private static final String TAG = TextServicesManager.class.getSimpleName();
    private static final boolean DBG = false;

    /**
     * A compile time switch to control per-profile spell checker, which is not yet ready.
     * @hide
     */
    public static final boolean DISABLE_PER_PROFILE_SPELL_CHECKER = true;

    private static TextServicesManager sInstance;

    private final ITextServicesManager mService;

    private TextServicesManager() throws ServiceNotFoundException {
        mService = ITextServicesManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TEXT_SERVICES_MANAGER_SERVICE));
    }

    /**
     * Retrieve the global TextServicesManager instance, creating it if it doesn't already exist.
     * @hide
     */
    public static TextServicesManager getInstance() {
        synchronized (TextServicesManager.class) {
            if (sInstance == null) {
                try {
                    sInstance = new TextServicesManager();
                } catch (ServiceNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return sInstance;
        }
    }

    /**
     * Returns the language component of a given locale string.
     */
    private static String parseLanguageFromLocaleString(String locale) {
        final int idx = locale.indexOf('_');
        if (idx < 0) {
            return locale;
        } else {
            return locale.substring(0, idx);
        }
    }

    /**
     * Get a spell checker session for the specified spell checker
     * @param locale the locale for the spell checker. If {@code locale} is null and
     * referToSpellCheckerLanguageSettings is true, the locale specified in Settings will be
     * returned. If {@code locale} is not null and referToSpellCheckerLanguageSettings is true,
     * the locale specified in Settings will be returned only when it is same as {@code locale}.
     * Exceptionally, when referToSpellCheckerLanguageSettings is true and {@code locale} is
     * only language (e.g. "en"), the specified locale in Settings (e.g. "en_US") will be
     * selected.
     * @param listener a spell checker session lister for getting results from a spell checker.
     * @param referToSpellCheckerLanguageSettings if true, the session for one of enabled
     * languages in settings will be returned.
     * @return the spell checker session of the spell checker
     */
    public SpellCheckerSession newSpellCheckerSession(Bundle bundle, Locale locale,
            SpellCheckerSessionListener listener, boolean referToSpellCheckerLanguageSettings) {
        if (listener == null) {
            throw new NullPointerException();
        }
        if (!referToSpellCheckerLanguageSettings && locale == null) {
            throw new IllegalArgumentException("Locale should not be null if you don't refer"
                    + " settings.");
        }

        if (referToSpellCheckerLanguageSettings && !isSpellCheckerEnabled()) {
            return null;
        }

        final SpellCheckerInfo sci;
        try {
            sci = mService.getCurrentSpellChecker(null);
        } catch (RemoteException e) {
            return null;
        }
        if (sci == null) {
            return null;
        }
        SpellCheckerSubtype subtypeInUse = null;
        if (referToSpellCheckerLanguageSettings) {
            subtypeInUse = getCurrentSpellCheckerSubtype(true);
            if (subtypeInUse == null) {
                return null;
            }
            if (locale != null) {
                final String subtypeLocale = subtypeInUse.getLocale();
                final String subtypeLanguage = parseLanguageFromLocaleString(subtypeLocale);
                if (subtypeLanguage.length() < 2 || !locale.getLanguage().equals(subtypeLanguage)) {
                    return null;
                }
            }
        } else {
            final String localeStr = locale.toString();
            for (int i = 0; i < sci.getSubtypeCount(); ++i) {
                final SpellCheckerSubtype subtype = sci.getSubtypeAt(i);
                final String tempSubtypeLocale = subtype.getLocale();
                final String tempSubtypeLanguage = parseLanguageFromLocaleString(tempSubtypeLocale);
                if (tempSubtypeLocale.equals(localeStr)) {
                    subtypeInUse = subtype;
                    break;
                } else if (tempSubtypeLanguage.length() >= 2 &&
                        locale.getLanguage().equals(tempSubtypeLanguage)) {
                    subtypeInUse = subtype;
                }
            }
        }
        if (subtypeInUse == null) {
            return null;
        }
        final SpellCheckerSession session = new SpellCheckerSession(sci, mService, listener);
        try {
            mService.getSpellCheckerService(sci.getId(), subtypeInUse.getLocale(),
                    session.getTextServicesSessionListener(),
                    session.getSpellCheckerSessionListener(), bundle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return session;
    }

    /**
     * @hide
     */
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        try {
            final SpellCheckerInfo[] retval = mService.getEnabledSpellCheckers();
            if (DBG) {
                Log.d(TAG, "getEnabledSpellCheckers: " + (retval != null ? retval.length : "null"));
            }
            return retval;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public SpellCheckerInfo getCurrentSpellChecker() {
        try {
            // Passing null as a locale for ICS
            return mService.getCurrentSpellChecker(null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            boolean allowImplicitlySelectedSubtype) {
        try {
            // Passing null as a locale until we support multiple enabled spell checker subtypes.
            return mService.getCurrentSpellCheckerSubtype(null, allowImplicitlySelectedSubtype);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isSpellCheckerEnabled() {
        try {
            return mService.isSpellCheckerEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
