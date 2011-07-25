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

package android.service.textservice;

import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import java.lang.ref.WeakReference;

/**
 * SpellCheckerService provides an abstract base class for a spell checker.
 * This class combines a service to the system with the spell checker service interface that
 * spell checker must implement.
 */
public abstract class SpellCheckerService extends Service {
    private static final String TAG = SpellCheckerService.class.getSimpleName();
    public static final String SERVICE_INTERFACE =
            "android.service.textservice.SpellCheckerService";

    private final SpellCheckerServiceBinder mBinder = new SpellCheckerServiceBinder(this);

    /**
     * Get suggestions for specified text in TextInfo.
     * This function will run on the incoming IPC thread. So, this is not called on the main thread,
     * but will be called in series on another thread.
     * @param textInfo the text metadata
     * @param suggestionsLimit the number of limit of suggestions returned
     * @param locale the locale for getting suggestions
     * @return SuggestionInfo which contains suggestions for textInfo
     */
    public abstract SuggestionsInfo getSuggestions(
            TextInfo textInfo, int suggestionsLimit, String locale);

    /**
     * A batch process of onGetSuggestions.
     * This function will run on the incoming IPC thread. So, this is not called on the main thread,
     * but will be called in series on another thread.
     * @param textInfos an array of the text metadata
     * @param locale the locale for getting suggestions
     * @param suggestionsLimit the number of limit of suggestions returned
     * @param sequentialWords true if textInfos can be treated as sequential words.
     * @return an array of SuggestionInfo of onGetSuggestions
     */
    public SuggestionsInfo[] getSuggestionsMultiple(
            TextInfo[] textInfos, String locale, int suggestionsLimit, boolean sequentialWords) {
        final int length = textInfos.length;
        final SuggestionsInfo[] retval = new SuggestionsInfo[length];
        for (int i = 0; i < length; ++i) {
            retval[i] = getSuggestions(textInfos[i], suggestionsLimit, locale);
            retval[i].setCookieAndSequence(textInfos[i].getCookie(), textInfos[i].getSequence());
        }
        return retval;
    }

    /**
     * Request to abort all tasks executed in SpellChecker.
     * This function will run on the incoming IPC thread. So, this is not called on the main thread,
     * but will be called in series on another thread.
     */
    public void cancel() {}

    /**
     * Implement to return the implementation of the internal spell checker
     * service interface. Subclasses should not override.
     */
    @Override
    public final IBinder onBind(final Intent intent) {
        return mBinder;
    }

    private static class SpellCheckerSessionImpl extends ISpellCheckerSession.Stub {
        private final WeakReference<SpellCheckerService> mInternalServiceRef;
        private final String mLocale;
        private final ISpellCheckerSessionListener mListener;

        public SpellCheckerSessionImpl(
                SpellCheckerService service, String locale, ISpellCheckerSessionListener listener) {
            mInternalServiceRef = new WeakReference<SpellCheckerService>(service);
            mLocale = locale;
            mListener = listener;
        }

        @Override
        public void getSuggestionsMultiple(
                TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
            final SpellCheckerService service = mInternalServiceRef.get();
            if (service == null) return;
            try {
                mListener.onGetSuggestions(
                        service.getSuggestionsMultiple(textInfos, mLocale,
                                suggestionsLimit, sequentialWords));
            } catch (RemoteException e) {
            }
        }

        @Override
        public void cancel() {
            final SpellCheckerService service = mInternalServiceRef.get();
            if (service == null) return;
            service.cancel();
        }
    }

    private static class SpellCheckerServiceBinder extends ISpellCheckerService.Stub {
        private final WeakReference<SpellCheckerService> mInternalServiceRef;

        public SpellCheckerServiceBinder(SpellCheckerService service) {
            mInternalServiceRef = new WeakReference<SpellCheckerService>(service);
        }

        @Override
        public ISpellCheckerSession getISpellCheckerSession(
                String locale, ISpellCheckerSessionListener listener) {
            final SpellCheckerService service = mInternalServiceRef.get();
            if (service == null) return null;
            return new SpellCheckerSessionImpl(service, locale, listener);
        }
    }
}
