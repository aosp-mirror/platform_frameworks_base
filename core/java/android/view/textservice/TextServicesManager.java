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
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;

import java.util.Locale;

/**
 * System API to the overall text services, which arbitrates interaction between applications
 * and text services. You can retrieve an instance of this interface with
 * {@link Context#getSystemService(String) Context.getSystemService()}.
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
public final class TextServicesManager {
    private static final String TAG = TextServicesManager.class.getSimpleName();
    private static final boolean DBG = false;

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
     * Get a spell checker session for the specified spell checker
     * @param locale the locale for the spell checker. If {@param locale} is null and
     * referToSpellCheckerLanguageSettings is true, the locale specified in Settings will be
     * returned. If {@param locale} is not null and referToSpellCheckerLanguageSettings is true,
     * the locale specified in Settings will be returned only when it is same as {@param locale}.
     * Exceptionally, when referToSpellCheckerLanguageSettings is true and {@param locale} is
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
            sci = sService.getCurrentSpellChecker(null);
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
                final String inputLocale = locale.toString();
                if (subtypeLocale.length() < 2 || inputLocale.length() < 2
                        || !subtypeLocale.substring(0, 2).equals(inputLocale.substring(0, 2))) {
                    return null;
                }
            }
        } else {
            final String localeStr = locale.toString();
            for (int i = 0; i < sci.getSubtypeCount(); ++i) {
                final SpellCheckerSubtype subtype = sci.getSubtypeAt(i);
                final String tempSubtypeLocale = subtype.getLocale();
                if (tempSubtypeLocale.equals(localeStr)) {
                    subtypeInUse = subtype;
                    break;
                } else if (localeStr.length() >= 2 && tempSubtypeLocale.length() >= 2
                        && localeStr.startsWith(tempSubtypeLocale)) {
                    subtypeInUse = subtype;
                }
            }
        }
        if (subtypeInUse == null) {
            return null;
        }
        final SpellCheckerSession session = new SpellCheckerSession(
                sci, sService, listener, subtypeInUse);
        try {
            sService.getSpellCheckerService(sci.getId(), subtypeInUse.getLocale(),
                    session.getTextServicesSessionListener(),
                    session.getSpellCheckerSessionListener(), bundle);
        } catch (RemoteException e) {
            return null;
        }
        return session;
    }

    /**
     * @hide
     */
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        try {
            final SpellCheckerInfo[] retval = sService.getEnabledSpellCheckers();
            if (DBG) {
                Log.d(TAG, "getEnabledSpellCheckers: " + (retval != null ? retval.length : "null"));
            }
            return retval;
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getEnabledSpellCheckers: " + e);
            return null;
        }
    }

    /**
     * @hide
     */
    public SpellCheckerInfo getCurrentSpellChecker() {
        try {
            // Passing null as a locale for ICS
            return sService.getCurrentSpellChecker(null);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * @hide
     */
    public void setCurrentSpellChecker(SpellCheckerInfo sci) {
        try {
            if (sci == null) {
                throw new NullPointerException("SpellCheckerInfo is null.");
            }
            sService.setCurrentSpellChecker(null, sci.getId());
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setCurrentSpellChecker: " + e);
        }
    }

    /**
     * @hide
     */
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            boolean allowImplicitlySelectedSubtype) {
        try {
            // Passing null as a locale for ICS
            return sService.getCurrentSpellCheckerSubtype(null, allowImplicitlySelectedSubtype);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getCurrentSpellCheckerSubtype: " + e);
            return null;
        }
    }

    /**
     * @hide
     */
    public void setSpellCheckerSubtype(SpellCheckerSubtype subtype) {
        try {
            final int hashCode;
            if (subtype == null) {
                hashCode = 0;
            } else {
                hashCode = subtype.hashCode();
            }
            sService.setCurrentSpellCheckerSubtype(null, hashCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setSpellCheckerSubtype:" + e);
        }
    }

    /**
     * @hide
     */
    public void setSpellCheckerEnabled(boolean enabled) {
        try {
            sService.setSpellCheckerEnabled(enabled);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setSpellCheckerEnabled:" + e);
        }
    }

    /**
     * @hide
     */
    public boolean isSpellCheckerEnabled() {
        try {
            return sService.isSpellCheckerEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Error in isSpellCheckerEnabled:" + e);
            return false;
        }
    }
}
