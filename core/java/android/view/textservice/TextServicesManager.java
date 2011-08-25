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
     * @param locale the locale for the spell checker
     * @param listener a spell checker session lister for getting results from a spell checker.
     * @param referToSpellCheckerLanguageSettings if true, the session for one of enabled
     * languages in settings will be returned.
     * @return the spell checker session of the spell checker
     */
    // TODO: Add a method to get enabled spell checkers.
    // TODO: Handle referToSpellCheckerLanguageSettings
    public SpellCheckerSession newSpellCheckerSession(Bundle bundle, Locale locale,
            SpellCheckerSessionListener listener, boolean referToSpellCheckerLanguageSettings) {
        if (listener == null) {
            throw new NullPointerException();
        }
        // TODO: set a proper locale instead of the dummy locale
        final String localeString = locale == null ? "en" : locale.toString();
        final SpellCheckerInfo sci;
        try {
            sci = sService.getCurrentSpellChecker(localeString);
        } catch (RemoteException e) {
            return null;
        }
        if (sci == null) {
            return null;
        }
        final SpellCheckerSession session = new SpellCheckerSession(sci, sService, listener);
        try {
            sService.getSpellCheckerService(sci.getId(), localeString,
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
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype() {
        try {
            // Passing null as a locale for ICS
            return sService.getCurrentSpellCheckerSubtype(null);
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
            if (subtype == null) {
                throw new NullPointerException("SpellCheckerSubtype is null.");
            }
            sService.setCurrentSpellCheckerSubtype(null, subtype.hashCode());
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
            Log.e(TAG, "Error in setSpellCheckerSubtype:" + e);
        }
    }

    /**
     * @hide
     */
    public boolean isSpellCheckerEnabled() {
        try {
            return sService.isSpellCheckerEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setSpellCheckerSubtype:" + e);
            return false;
        }
    }
}
