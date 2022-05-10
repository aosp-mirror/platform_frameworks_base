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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.UserHandle;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionParams;

import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

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
     * @deprecated Do not use. Just kept because of {@link UnsupportedAppUsage} in
     * {@link #getInstance()}.
     */
    @Deprecated
    private static TextServicesManager sInstance;

    private final ITextServicesManager mService;

    @UserIdInt
    private final int mUserId;

    @Nullable
    private final InputMethodManager mInputMethodManager;

    private TextServicesManager(@UserIdInt int userId,
            @Nullable InputMethodManager inputMethodManager) throws ServiceNotFoundException {
        mService = ITextServicesManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TEXT_SERVICES_MANAGER_SERVICE));
        mUserId = userId;
        mInputMethodManager = inputMethodManager;
    }

    /**
     * The factory method of {@link TextServicesManager}.
     *
     * @param context {@link Context} from which {@link TextServicesManager} should be instantiated.
     * @return {@link TextServicesManager} that is associated with {@link Context#getUserId()}.
     * @throws ServiceNotFoundException When {@link TextServicesManager} is not available.
     * @hide
     */
    @UserHandleAware
    @NonNull
    public static TextServicesManager createInstance(@NonNull Context context)
            throws ServiceNotFoundException {
        return new TextServicesManager(context.getUserId(), context.getSystemService(
                InputMethodManager.class));
    }

    /**
     * @deprecated Do not use. Just kept because of {@link UnsupportedAppUsage} in
     * {@link #getInstance()}.
     * @hide
     */
    @UnsupportedAppUsage
    public static TextServicesManager getInstance() {
        synchronized (TextServicesManager.class) {
            if (sInstance == null) {
                try {
                    sInstance = new TextServicesManager(UserHandle.myUserId(), null);
                } catch (ServiceNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return sInstance;
        }
    }

    /** @hide */
    @Nullable
    public InputMethodManager getInputMethodManager() {
        return mInputMethodManager;
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
     * Get a spell checker session from the spell checker.
     *
     * <p>{@link SuggestionsInfo#RESULT_ATTR_IN_THE_DICTIONARY},
     * {@link SuggestionsInfo#RESULT_ATTR_LOOKS_LIKE_TYPO}, and
     * {@link SuggestionsInfo#RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS} will be passed to the spell
     * checker as supported attributes.
     *
     * @param locale the locale for the spell checker. If {@code locale} is null and
     * referToSpellCheckerLanguageSettings is true, the locale specified in Settings will be
     * returned. If {@code locale} is not null and referToSpellCheckerLanguageSettings is true,
     * the locale specified in Settings will be returned only when it is same as {@code locale}.
     * Exceptionally, when referToSpellCheckerLanguageSettings is true and {@code locale} is
     * only language (e.g. "en"), the specified locale in Settings (e.g. "en_US") will be
     * selected.
     * @param listener a spell checker session lister for getting results from the spell checker.
     * @param referToSpellCheckerLanguageSettings if true, the session for one of enabled
     * languages in settings will be returned.
     * @return a spell checker session of the spell checker
     */
    @UserHandleAware
    @Nullable
    public SpellCheckerSession newSpellCheckerSession(@Nullable Bundle bundle,
            @Nullable Locale locale,
            @NonNull SpellCheckerSessionListener listener,
            boolean referToSpellCheckerLanguageSettings) {
        // Attributes existed before {@link #newSpellCheckerSession(Locale, boolean, int, Bundle,
        // Executor, SpellCheckerSessionListener)} was introduced.
        int supportedAttributes = SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY
                | SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                | SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS;
        SpellCheckerSessionParams.Builder paramsBuilder = new SpellCheckerSessionParams.Builder()
                .setLocale(locale)
                .setShouldReferToSpellCheckerLanguageSettings(referToSpellCheckerLanguageSettings)
                .setSupportedAttributes(supportedAttributes);
        if (bundle != null) {
            paramsBuilder.setExtras(bundle);
        }
        // Using the implicit looper to preserve the old behavior.
        Executor executor = new HandlerExecutor(new Handler());
        return newSpellCheckerSession(paramsBuilder.build(), executor, listener);
    }

    /**
     * Get a spell checker session from the spell checker.
     *
     * @param params The parameters passed to the spell checker.
     * @param executor The executor on which {@code listener} will be called back.
     * @param listener a spell checker session lister for getting results from the spell checker.
     * @return The spell checker session of the spell checker.
     */
    @UserHandleAware
    @Nullable
    public SpellCheckerSession newSpellCheckerSession(
            @NonNull SpellCheckerSessionParams params,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SpellCheckerSessionListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        Locale locale = params.getLocale();
        if (!params.shouldReferToSpellCheckerLanguageSettings() && locale == null) {
            throw new IllegalArgumentException("Locale should not be null if you don't refer"
                    + " settings.");
        }

        if (params.shouldReferToSpellCheckerLanguageSettings() && !isSpellCheckerEnabled()) {
            return null;
        }

        final SpellCheckerInfo sci;
        try {
            sci = mService.getCurrentSpellChecker(mUserId, null);
        } catch (RemoteException e) {
            return null;
        }
        if (sci == null) {
            return null;
        }
        SpellCheckerSubtype subtypeInUse = null;
        if (params.shouldReferToSpellCheckerLanguageSettings()) {
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
        final SpellCheckerSession session = new SpellCheckerSession(sci, this, listener, executor);
        try {
            mService.getSpellCheckerService(mUserId, sci.getId(), subtypeInUse.getLocale(),
                    session.getTextServicesSessionListener(),
                    session.getSpellCheckerSessionListener(),
                    params.getExtras(), params.getSupportedAttributes());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return session;
    }

    /**
     * Deprecated. Use {@link #getEnabledSpellCheckerInfos()} instead.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553,
            publicAlternatives = "Use {@link #getEnabledSpellCheckerInfos()} instead.")
    @UserHandleAware
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        try {
            final SpellCheckerInfo[] retval = mService.getEnabledSpellCheckers(mUserId);
            if (DBG) {
                Log.d(TAG, "getEnabledSpellCheckers: " + (retval != null ? retval.length : "null"));
            }
            return retval;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve the list of currently enabled spell checkers.
     *
     * @return The list of currently enabled spell checkers.
     */
    @UserHandleAware
    @NonNull
    public List<SpellCheckerInfo> getEnabledSpellCheckerInfos() {
        final SpellCheckerInfo[] enabledSpellCheckers = getEnabledSpellCheckers();
        return enabledSpellCheckers != null
                ? Arrays.asList(enabledSpellCheckers) : Collections.emptyList();
    }

    /**
     * Retrieve the currently active spell checker, or null if there is none.
     *
     * @return The current active spell checker info.
     */
    @UserHandleAware
    @Nullable
    public SpellCheckerInfo getCurrentSpellCheckerInfo() {
        try {
            // Passing null as a locale for ICS
            return mService.getCurrentSpellChecker(mUserId, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deprecated. Use {@link #getCurrentSpellCheckerInfo()} instead.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@link #getCurrentSpellCheckerInfo()} instead.")
    @UserHandleAware
    @Nullable
    public SpellCheckerInfo getCurrentSpellChecker() {
        return getCurrentSpellCheckerInfo();
    }

    /**
     * Retrieve the selected subtype of the selected spell checker, or null if there is none.
     *
     * @param allowImplicitlySelectedSubtype {@code true} to return the default language matching
     * system locale if there's no subtype selected explicitly, otherwise, returns null.
     * @return The meta information of the selected subtype of the selected spell checker.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @UserHandleAware
    @Nullable
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            boolean allowImplicitlySelectedSubtype) {
        try {
            return mService.getCurrentSpellCheckerSubtype(mUserId, allowImplicitlySelectedSubtype);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the spell checker is enabled or not.
     *
     * @return {@code true} if spell checker is enabled, {@code false} otherwise.
     */
    @UserHandleAware
    public boolean isSpellCheckerEnabled() {
        try {
            return mService.isSpellCheckerEnabled(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @UserHandleAware
    void finishSpellCheckerService(ISpellCheckerSessionListener listener) {
        try {
            mService.finishSpellCheckerService(mUserId, listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
