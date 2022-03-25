/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesSessionListener;

import dalvik.system.CloseGuard;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * The SpellCheckerSession interface provides the per client functionality of SpellCheckerService.
 *
 *
 * <a name="Applications"></a>
 * <h3>Applications</h3>
 *
 * <p>In most cases, applications that are using the standard
 * {@link android.widget.TextView} or its subclasses will have little they need
 * to do to work well with spell checker services.  The main things you need to
 * be aware of are:</p>
 *
 * <ul>
 * <li> Properly set the {@link android.R.attr#inputType} in your editable
 * text views, so that the spell checker will have enough context to help the
 * user in editing text in them.
 * </ul>
 *
 * <p>For the rare people amongst us writing client applications that use the spell checker service
 * directly, you will need to use {@link #getSuggestions(TextInfo, int)} or
 * {@link #getSuggestions(TextInfo[], int, boolean)} for obtaining results from the spell checker
 * service by yourself.</p>
 *
 * <h3>Security</h3>
 *
 * <p>There are a lot of security issues associated with spell checkers,
 * since they could monitor all the text being sent to them
 * through, for instance, {@link android.widget.TextView}.
 * The Android spell checker framework also allows
 * arbitrary third party spell checkers, so care must be taken to restrict their
 * selection and interactions.</p>
 *
 * <p>Here are some key points about the security architecture behind the
 * spell checker framework:</p>
 *
 * <ul>
 * <li>Only the system is allowed to directly access a spell checker framework's
 * {@link android.service.textservice.SpellCheckerService} interface, via the
 * {@link android.Manifest.permission#BIND_TEXT_SERVICE} permission.  This is
 * enforced in the system by not binding to a spell checker service that does
 * not require this permission.
 *
 * <li>The user must explicitly enable a new spell checker in settings before
 * they can be enabled, to confirm with the system that they know about it
 * and want to make it available for use.
 * </ul>
 *
 */
public class SpellCheckerSession {
    private static final String TAG = SpellCheckerSession.class.getSimpleName();
    private static final boolean DBG = false;
    /**
     * Name under which a SpellChecker service component publishes information about itself.
     * This meta-data must reference an XML resource.
     **/
    public static final String SERVICE_META_DATA = "android.view.textservice.scs";

    private static final int MSG_ON_GET_SUGGESTION_MULTIPLE = 1;
    private static final int MSG_ON_GET_SUGGESTION_MULTIPLE_FOR_SENTENCE = 2;

    private final InternalListener mInternalListener;
    private final TextServicesManager mTextServicesManager;
    private final SpellCheckerInfo mSpellCheckerInfo;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final SpellCheckerSessionListener mSpellCheckerSessionListener;
    private final SpellCheckerSessionListenerImpl mSpellCheckerSessionListenerImpl;
    private final Executor mExecutor;

    private final CloseGuard mGuard = CloseGuard.get();

    /**
     * Constructor
     * @hide
     */
    public SpellCheckerSession(
            SpellCheckerInfo info, TextServicesManager tsm, SpellCheckerSessionListener listener,
            Executor executor) {
        if (info == null || listener == null || tsm == null) {
            throw new NullPointerException();
        }
        mSpellCheckerInfo = info;
        mSpellCheckerSessionListenerImpl = new SpellCheckerSessionListenerImpl(this);
        mInternalListener = new InternalListener(mSpellCheckerSessionListenerImpl);
        mTextServicesManager = tsm;
        mSpellCheckerSessionListener = listener;
        mExecutor = executor;

        mGuard.open("finishSession");
    }

    /**
     * @return true if the connection to a text service of this session is disconnected and not
     * alive.
     */
    public boolean isSessionDisconnected() {
        return mSpellCheckerSessionListenerImpl.isDisconnected();
    }

    /**
     * Get the spell checker service info this spell checker session has.
     * @return SpellCheckerInfo for the specified locale.
     */
    public SpellCheckerInfo getSpellChecker() {
        return mSpellCheckerInfo;
    }

    /**
     * Cancel pending and running spell check tasks
     */
    public void cancel() {
        mSpellCheckerSessionListenerImpl.cancel();
    }

    /**
     * Finish this session and allow TextServicesManagerService to disconnect the bound spell
     * checker.
     */
    public void close() {
        mGuard.close();
        mSpellCheckerSessionListenerImpl.close();
        mTextServicesManager.finishSpellCheckerService(mSpellCheckerSessionListenerImpl);
    }

    /**
     * Get suggestions from the specified sentences
     * @param textInfos an array of text metadata for a spell checker
     * @param suggestionsLimit the maximum number of suggestions that will be returned
     */
    public void getSentenceSuggestions(TextInfo[] textInfos, int suggestionsLimit) {
        final InputMethodManager imm = mTextServicesManager.getInputMethodManager();
        if (imm != null && imm.isInputMethodSuppressingSpellChecker()) {
            handleOnGetSentenceSuggestionsMultiple(new SentenceSuggestionsInfo[0]);
            return;
        }
        mSpellCheckerSessionListenerImpl.getSentenceSuggestionsMultiple(
                textInfos, suggestionsLimit);
    }

    /**
     * Get candidate strings for a substring of the specified text.
     * @param textInfo text metadata for a spell checker
     * @param suggestionsLimit the maximum number of suggestions that will be returned
     * @deprecated use {@link SpellCheckerSession#getSentenceSuggestions(TextInfo[], int)} instead
     */
    @Deprecated
    public void getSuggestions(TextInfo textInfo, int suggestionsLimit) {
        getSuggestions(new TextInfo[] {textInfo}, suggestionsLimit, false);
    }

    /**
     * A batch process of getSuggestions
     * @param textInfos an array of text metadata for a spell checker
     * @param suggestionsLimit the maximum number of suggestions that will be returned
     * @param sequentialWords true if textInfos can be treated as sequential words.
     * @deprecated use {@link SpellCheckerSession#getSentenceSuggestions(TextInfo[], int)} instead
     */
    @Deprecated
    public void getSuggestions(
            TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
        if (DBG) {
            Log.w(TAG, "getSuggestions from " + mSpellCheckerInfo.getId());
        }
        final InputMethodManager imm = mTextServicesManager.getInputMethodManager();
        if (imm != null && imm.isInputMethodSuppressingSpellChecker()) {
            handleOnGetSuggestionsMultiple(new SuggestionsInfo[0]);
            return;
        }
        mSpellCheckerSessionListenerImpl.getSuggestionsMultiple(
                textInfos, suggestionsLimit, sequentialWords);
    }

    void handleOnGetSuggestionsMultiple(SuggestionsInfo[] suggestionsInfos) {
        mExecutor.execute(() -> mSpellCheckerSessionListener.onGetSuggestions(suggestionsInfos));
    }

    void handleOnGetSentenceSuggestionsMultiple(SentenceSuggestionsInfo[] suggestionsInfos) {
        mExecutor.execute(() ->
                mSpellCheckerSessionListener.onGetSentenceSuggestions(suggestionsInfos));
    }

    private static final class SpellCheckerSessionListenerImpl
            extends ISpellCheckerSessionListener.Stub {
        private static final int TASK_CANCEL = 1;
        private static final int TASK_GET_SUGGESTIONS_MULTIPLE = 2;
        private static final int TASK_CLOSE = 3;
        private static final int TASK_GET_SUGGESTIONS_MULTIPLE_FOR_SENTENCE = 4;
        private static String taskToString(int task) {
            switch (task) {
                case TASK_CANCEL:
                    return "TASK_CANCEL";
                case TASK_GET_SUGGESTIONS_MULTIPLE:
                    return "TASK_GET_SUGGESTIONS_MULTIPLE";
                case TASK_CLOSE:
                    return "TASK_CLOSE";
                case TASK_GET_SUGGESTIONS_MULTIPLE_FOR_SENTENCE:
                    return "TASK_GET_SUGGESTIONS_MULTIPLE_FOR_SENTENCE";
                default:
                    return "Unexpected task=" + task;
            }
        }

        private final Queue<SpellCheckerParams> mPendingTasks = new ArrayDeque<>();
        @GuardedBy("SpellCheckerSessionListenerImpl.this")
        private SpellCheckerSession mSpellCheckerSession;

        private static final int STATE_WAIT_CONNECTION = 0;
        private static final int STATE_CONNECTED = 1;
        private static final int STATE_CLOSED_AFTER_CONNECTION = 2;
        private static final int STATE_CLOSED_BEFORE_CONNECTION = 3;
        private static String stateToString(int state) {
            switch (state) {
                case STATE_WAIT_CONNECTION: return "STATE_WAIT_CONNECTION";
                case STATE_CONNECTED: return "STATE_CONNECTED";
                case STATE_CLOSED_AFTER_CONNECTION: return "STATE_CLOSED_AFTER_CONNECTION";
                case STATE_CLOSED_BEFORE_CONNECTION: return "STATE_CLOSED_BEFORE_CONNECTION";
                default: return "Unexpected state=" + state;
            }
        }
        private int mState = STATE_WAIT_CONNECTION;

        private ISpellCheckerSession mISpellCheckerSession;
        private HandlerThread mThread;
        private Handler mAsyncHandler;

        SpellCheckerSessionListenerImpl(SpellCheckerSession spellCheckerSession) {
            mSpellCheckerSession = spellCheckerSession;
        }

        private static class SpellCheckerParams {
            public final int mWhat;
            public final TextInfo[] mTextInfos;
            public final int mSuggestionsLimit;
            public final boolean mSequentialWords;
            public ISpellCheckerSession mSession;
            public SpellCheckerParams(int what, TextInfo[] textInfos, int suggestionsLimit,
                    boolean sequentialWords) {
                mWhat = what;
                mTextInfos = textInfos;
                mSuggestionsLimit = suggestionsLimit;
                mSequentialWords = sequentialWords;
            }
        }

        private void processTask(ISpellCheckerSession session, SpellCheckerParams scp,
                boolean async) {
            if (DBG) {
                synchronized (this) {
                    Log.d(TAG, "entering processTask:"
                            + " session.hashCode()=#" + Integer.toHexString(session.hashCode())
                            + " scp.mWhat=" + taskToString(scp.mWhat) + " async=" + async
                            + " mAsyncHandler=" + mAsyncHandler
                            + " mState=" + stateToString(mState));
                }
            }
            if (async || mAsyncHandler == null) {
                switch (scp.mWhat) {
                    case TASK_CANCEL:
                        try {
                            session.onCancel();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to cancel " + e);
                        }
                        break;
                    case TASK_GET_SUGGESTIONS_MULTIPLE:
                        try {
                            session.onGetSuggestionsMultiple(scp.mTextInfos,
                                    scp.mSuggestionsLimit, scp.mSequentialWords);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to get suggestions " + e);
                        }
                        break;
                    case TASK_GET_SUGGESTIONS_MULTIPLE_FOR_SENTENCE:
                        try {
                            session.onGetSentenceSuggestionsMultiple(
                                    scp.mTextInfos, scp.mSuggestionsLimit);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to get suggestions " + e);
                        }
                        break;
                    case TASK_CLOSE:
                        try {
                            session.onClose();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to close " + e);
                        }
                        break;
                }
            } else {
                // The interface is to a local object, so need to execute it
                // asynchronously.
                scp.mSession = session;
                mAsyncHandler.sendMessage(Message.obtain(mAsyncHandler, 1, scp));
            }

            if (scp.mWhat == TASK_CLOSE) {
                // If we are closing, we want to clean up our state now even
                // if it is pending as an async operation.
                synchronized (this) {
                    processCloseLocked();
                }
            }
        }

        @GuardedBy("SpellCheckerSessionListenerImpl.this")
        private void processCloseLocked() {
            if (DBG) Log.d(TAG, "entering processCloseLocked:"
                    + " session" + (mISpellCheckerSession != null ? ".hashCode()=#"
                            + Integer.toHexString(mISpellCheckerSession.hashCode()) : "=null")
                    + " mState=" + stateToString(mState));
            mISpellCheckerSession = null;
            if (mThread != null) {
                mThread.quit();
            }
            mSpellCheckerSession = null;
            mPendingTasks.clear();
            mThread = null;
            mAsyncHandler = null;
            switch (mState) {
                case STATE_WAIT_CONNECTION:
                    mState = STATE_CLOSED_BEFORE_CONNECTION;
                    break;
                case STATE_CONNECTED:
                    mState = STATE_CLOSED_AFTER_CONNECTION;
                    break;
                default:
                    Log.e(TAG, "processCloseLocked is called unexpectedly. mState=" +
                            stateToString(mState));
                    break;
            }
        }

        public void onServiceConnected(ISpellCheckerSession session) {
            synchronized (this) {
                switch (mState) {
                    case STATE_WAIT_CONNECTION:
                        // OK, go ahead.
                        break;
                    case STATE_CLOSED_BEFORE_CONNECTION:
                        // This is possible, and not an error.  The client no longer is interested
                        // in this connection. OK to ignore.
                        if (DBG) Log.i(TAG, "ignoring onServiceConnected since the session is"
                                + " already closed.");
                        return;
                    default:
                        Log.e(TAG, "ignoring onServiceConnected due to unexpected mState="
                                + stateToString(mState));
                        return;
                }
                if (session == null) {
                    Log.e(TAG, "ignoring onServiceConnected due to session=null");
                    return;
                }
                mISpellCheckerSession = session;
                if (session.asBinder() instanceof Binder && mThread == null) {
                    if (DBG) Log.d(TAG, "starting HandlerThread in onServiceConnected.");
                    // If this is a local object, we need to do our own threading
                    // to make sure we handle it asynchronously.
                    mThread = new HandlerThread("SpellCheckerSession",
                            Process.THREAD_PRIORITY_BACKGROUND);
                    mThread.start();
                    mAsyncHandler = new Handler(mThread.getLooper()) {
                        @Override public void handleMessage(Message msg) {
                            SpellCheckerParams scp = (SpellCheckerParams)msg.obj;
                            processTask(scp.mSession, scp, true);
                        }
                    };
                }
                mState = STATE_CONNECTED;
                if (DBG) {
                    Log.d(TAG, "processed onServiceConnected: mISpellCheckerSession.hashCode()=#"
                            + Integer.toHexString(mISpellCheckerSession.hashCode())
                            + " mPendingTasks.size()=" + mPendingTasks.size());
                }
                while (!mPendingTasks.isEmpty()) {
                    processTask(session, mPendingTasks.poll(), false);
                }
            }
        }

        public void cancel() {
            processOrEnqueueTask(new SpellCheckerParams(TASK_CANCEL, null, 0, false));
        }

        public void getSuggestionsMultiple(
                TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
            processOrEnqueueTask(
                    new SpellCheckerParams(TASK_GET_SUGGESTIONS_MULTIPLE, textInfos,
                            suggestionsLimit, sequentialWords));
        }

        public void getSentenceSuggestionsMultiple(TextInfo[] textInfos, int suggestionsLimit) {
            processOrEnqueueTask(
                    new SpellCheckerParams(TASK_GET_SUGGESTIONS_MULTIPLE_FOR_SENTENCE,
                            textInfos, suggestionsLimit, false));
        }

        public void close() {
            processOrEnqueueTask(new SpellCheckerParams(TASK_CLOSE, null, 0, false));
        }

        public boolean isDisconnected() {
            synchronized (this) {
                return mState != STATE_CONNECTED;
            }
        }

        private void processOrEnqueueTask(SpellCheckerParams scp) {
            ISpellCheckerSession session;
            synchronized (this) {
                if (scp.mWhat == TASK_CLOSE && (mState == STATE_CLOSED_AFTER_CONNECTION
                        || mState == STATE_CLOSED_BEFORE_CONNECTION)) {
                    // It is OK to call SpellCheckerSession#close() multiple times.
                    // Don't output confusing/misleading warning messages.
                    return;
                }
                if (mState != STATE_WAIT_CONNECTION && mState != STATE_CONNECTED) {
                    Log.e(TAG, "ignoring processOrEnqueueTask due to unexpected mState="
                            + stateToString(mState)
                            + " scp.mWhat=" + taskToString(scp.mWhat));
                    return;
                }

                if (mState == STATE_WAIT_CONNECTION) {
                    // If we are still waiting for the connection. Need to pay special attention.
                    if (scp.mWhat == TASK_CLOSE) {
                        processCloseLocked();
                        return;
                    }
                    // Enqueue the task to task queue.
                    SpellCheckerParams closeTask = null;
                    if (scp.mWhat == TASK_CANCEL) {
                        if (DBG) Log.d(TAG, "canceling pending tasks in processOrEnqueueTask.");
                        while (!mPendingTasks.isEmpty()) {
                            final SpellCheckerParams tmp = mPendingTasks.poll();
                            if (tmp.mWhat == TASK_CLOSE) {
                                // Only one close task should be processed, while we need to remove
                                // all close tasks from the queue
                                closeTask = tmp;
                            }
                        }
                    }
                    mPendingTasks.offer(scp);
                    if (closeTask != null) {
                        mPendingTasks.offer(closeTask);
                    }
                    if (DBG) Log.d(TAG, "queueing tasks in processOrEnqueueTask since the"
                            + " connection is not established."
                            + " mPendingTasks.size()=" + mPendingTasks.size());
                    return;
                }

                session = mISpellCheckerSession;
            }
            // session must never be null here.
            processTask(session, scp, false);
        }

        @BinderThread
        @Override
        public void onGetSuggestions(SuggestionsInfo[] results) {
            SpellCheckerSession session = getSpellCheckerSession();
            if (session != null) {
                // Lock should not be held when calling callback, in order to avoid deadlock.
                session.handleOnGetSuggestionsMultiple(results);
            }
        }

        @BinderThread
        @Override
        public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
            SpellCheckerSession session = getSpellCheckerSession();
            if (session != null) {
                // Lock should not be held when calling callback, in order to avoid deadlock.
                session.handleOnGetSentenceSuggestionsMultiple(results);
            }
        }

        @Nullable
        private SpellCheckerSession getSpellCheckerSession() {
            synchronized (SpellCheckerSessionListenerImpl.this) {
                return mSpellCheckerSession;
            }
        }
    }

    /** Parameters used to create a {@link SpellCheckerSession}. */
    public static class SpellCheckerSessionParams {
        @Nullable
        private final Locale mLocale;
        private final boolean mShouldReferToSpellCheckerLanguageSettings;
        private final @SuggestionsInfo.ResultAttrs int mSupportedAttributes;
        private final Bundle mExtras;

        private SpellCheckerSessionParams(Locale locale,
                boolean referToSpellCheckerLanguageSettings, int supportedAttributes,
                Bundle extras) {
            mLocale = locale;
            mShouldReferToSpellCheckerLanguageSettings = referToSpellCheckerLanguageSettings;
            mSupportedAttributes = supportedAttributes;
            mExtras = extras;
        }

        /**
         * Returns the locale in which the spell checker should operate.
         *
         * @see android.service.textservice.SpellCheckerService.Session#getLocale()
         */
        @SuppressLint("UseIcu")
        @Nullable
        public Locale getLocale() {
            return mLocale;
        }

        /**
         * Returns true if the user's spell checker language settings should be used to determine
         * the spell checker locale.
         */
        public boolean shouldReferToSpellCheckerLanguageSettings() {
            return mShouldReferToSpellCheckerLanguageSettings;
        }

        /**
         * Returns a bitmask of {@link SuggestionsInfo} attributes that the spell checker can set
         * in {@link SuggestionsInfo} it returns.
         *
         * @see android.service.textservice.SpellCheckerService.Session#getSupportedAttributes()
         */
        public @SuggestionsInfo.ResultAttrs int getSupportedAttributes() {
            return mSupportedAttributes;
        }

        /**
         * Returns a bundle containing extra parameters for the spell checker.
         *
         * <p>This bundle can be used to pass implementation-specific parameters to the
         * {@link android.service.textservice.SpellCheckerService} implementation.
         *
         * @see android.service.textservice.SpellCheckerService.Session#getBundle()
         */
        @NonNull
        public Bundle getExtras() {
            return mExtras;
        }

        /** Builder of {@link SpellCheckerSessionParams}. */
        public static final class Builder {
            @Nullable
            private Locale mLocale;
            private boolean mShouldReferToSpellCheckerLanguageSettings = false;
            private @SuggestionsInfo.ResultAttrs int mSupportedAttributes = 0;
            private Bundle mExtras = Bundle.EMPTY;

            /** Constructs a {@code Builder}. */
            public Builder() {
            }

            /**
             * Returns constructed {@link SpellCheckerSession} instance.
             *
             * <p>Before calling this method, either {@link #setLocale(Locale)} should be called
             * with a non-null locale or
             * {@link #setShouldReferToSpellCheckerLanguageSettings(boolean)} should be called with
             * {@code true}.
             */
            @NonNull
            public SpellCheckerSessionParams build() {
                if (mLocale == null && !mShouldReferToSpellCheckerLanguageSettings) {
                    throw new IllegalArgumentException("mLocale should not be null if "
                            + " mShouldReferToSpellCheckerLanguageSettings is false.");
                }
                return new SpellCheckerSessionParams(mLocale,
                        mShouldReferToSpellCheckerLanguageSettings, mSupportedAttributes, mExtras);
            }

            /**
             * Sets the locale in which the spell checker should operate.
             *
             * @see android.service.textservice.SpellCheckerService.Session#getLocale()
             */
            @NonNull
            public Builder setLocale(@SuppressLint("UseIcu") @Nullable Locale locale) {
                mLocale = locale;
                return this;
            }

            /**
             * Sets whether or not the user's spell checker language settings should be used to
             * determine spell checker locale.
             *
             * <p>If {@code shouldReferToSpellCheckerLanguageSettings} is true, the exact way of
             * determining spell checker locale differs based on {@code locale} specified in
             * {@link #setLocale(Locale)}.
             * If {@code shouldReferToSpellCheckerLanguageSettings} is true and {@code locale} is
             * null, the locale specified in Settings will be used. If
             * {@code shouldReferToSpellCheckerLanguageSettings} is true and {@code locale} is not
             * null, {@link SpellCheckerSession} can be created only when the locale specified in
             * Settings is the same as {@code locale}. Exceptionally, if
             * {@code shouldReferToSpellCheckerLanguageSettings} is true and {@code locale} is
             * language only (e.g. "en"), the specified locale in Settings (e.g. "en_US") will be
             * used.
             *
             * @see #setLocale(Locale)
             */
            @NonNull
            public Builder setShouldReferToSpellCheckerLanguageSettings(
                    boolean shouldReferToSpellCheckerLanguageSettings) {
                mShouldReferToSpellCheckerLanguageSettings =
                        shouldReferToSpellCheckerLanguageSettings;
                return this;
            }

            /**
             * Sets a bitmask of {@link SuggestionsInfo} attributes that the spell checker can set
             * in {@link SuggestionsInfo} it returns.
             *
             * @see android.service.textservice.SpellCheckerService.Session#getSupportedAttributes()
             */
            @NonNull
            public Builder setSupportedAttributes(
                    @SuggestionsInfo.ResultAttrs int supportedAttributes) {
                mSupportedAttributes = supportedAttributes;
                return this;
            }

            /**
             * Sets a bundle containing extra parameters for the spell checker.
             *
             * <p>This bundle can be used to pass implementation-specific parameters to the
             * {@link android.service.textservice.SpellCheckerService} implementation.
             *
             * @see android.service.textservice.SpellCheckerService.Session#getBundle()
             */
            @NonNull
            public Builder setExtras(@NonNull Bundle extras) {
                mExtras = extras;
                return this;
            }
        }
    }

    /**
     * Callback for getting results from text services
     */
    public interface SpellCheckerSessionListener {
        /**
         * Callback for {@link SpellCheckerSession#getSuggestions(TextInfo, int)}
         * and {@link SpellCheckerSession#getSuggestions(TextInfo[], int, boolean)}
         * @param results an array of {@link SuggestionsInfo}s.
         * These results are suggestions for {@link TextInfo}s queried by
         * {@link SpellCheckerSession#getSuggestions(TextInfo, int)} or
         * {@link SpellCheckerSession#getSuggestions(TextInfo[], int, boolean)}
         */
        public void onGetSuggestions(SuggestionsInfo[] results);
        /**
         * Callback for {@link SpellCheckerSession#getSentenceSuggestions(TextInfo[], int)}
         * @param results an array of {@link SentenceSuggestionsInfo}s.
         * These results are suggestions for {@link TextInfo}s
         * queried by {@link SpellCheckerSession#getSentenceSuggestions(TextInfo[], int)}.
         */
        public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results);
    }

    private static final class InternalListener extends ITextServicesSessionListener.Stub {
        private final SpellCheckerSessionListenerImpl mParentSpellCheckerSessionListenerImpl;

        public InternalListener(SpellCheckerSessionListenerImpl spellCheckerSessionListenerImpl) {
            mParentSpellCheckerSessionListenerImpl = spellCheckerSessionListenerImpl;
        }

        @Override
        public void onServiceConnected(ISpellCheckerSession session) {
            mParentSpellCheckerSessionListenerImpl.onServiceConnected(session);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            // Note that mGuard will be null if the constructor threw.
            if (mGuard != null) {
                mGuard.warnIfOpen();
                close();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * @hide
     */
    public ITextServicesSessionListener getTextServicesSessionListener() {
        return mInternalListener;
    }

    /**
     * @hide
     */
    public ISpellCheckerSessionListener getSpellCheckerSessionListener() {
        return mSpellCheckerSessionListenerImpl;
    }
}
