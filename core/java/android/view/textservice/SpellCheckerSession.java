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

import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;

import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import java.util.LinkedList;
import java.util.Queue;

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

    private final InternalListener mInternalListener;
    private final ITextServicesManager mTextServicesManager;
    private final SpellCheckerInfo mSpellCheckerInfo;
    private final SpellCheckerSessionListenerImpl mSpellCheckerSessionListenerImpl;

    private boolean mIsUsed;
    private SpellCheckerSessionListener mSpellCheckerSessionListener;

    /** Handler that will execute the main tasks */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_GET_SUGGESTION_MULTIPLE:
                    handleOnGetSuggestionsMultiple((SuggestionsInfo[]) msg.obj);
                    break;
            }
        }
    };

    /**
     * Constructor
     * @hide
     */
    public SpellCheckerSession(
            SpellCheckerInfo info, ITextServicesManager tsm, SpellCheckerSessionListener listener) {
        if (info == null || listener == null || tsm == null) {
            throw new NullPointerException();
        }
        mSpellCheckerInfo = info;
        mSpellCheckerSessionListenerImpl = new SpellCheckerSessionListenerImpl(mHandler);
        mInternalListener = new InternalListener(mSpellCheckerSessionListenerImpl);
        mTextServicesManager = tsm;
        mIsUsed = true;
        mSpellCheckerSessionListener = listener;
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
        mIsUsed = false;
        try {
            mSpellCheckerSessionListenerImpl.close();
            mTextServicesManager.finishSpellCheckerService(mSpellCheckerSessionListenerImpl);
        } catch (RemoteException e) {
            // do nothing
        }
    }

    /**
     * Get candidate strings for a substring of the specified text.
     * @param textInfo text metadata for a spell checker
     * @param suggestionsLimit the number of limit of suggestions returned
     */
    public void getSuggestions(TextInfo textInfo, int suggestionsLimit) {
        getSuggestions(new TextInfo[] {textInfo}, suggestionsLimit, false);
    }

    /**
     * A batch process of getSuggestions
     * @param textInfos an array of text metadata for a spell checker
     * @param suggestionsLimit the number of limit of suggestions returned
     * @param sequentialWords true if textInfos can be treated as sequential words.
     */
    public void getSuggestions(
            TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
        if (DBG) {
            Log.w(TAG, "getSuggestions from " + mSpellCheckerInfo.getId());
        }
        // TODO: Handle multiple words suggestions by using WordBreakIterator
        mSpellCheckerSessionListenerImpl.getSuggestionsMultiple(
                textInfos, suggestionsLimit, sequentialWords);
    }

    private void handleOnGetSuggestionsMultiple(SuggestionsInfo[] suggestionInfos) {
        mSpellCheckerSessionListener.onGetSuggestions(suggestionInfos);
    }

    private static class SpellCheckerSessionListenerImpl extends ISpellCheckerSessionListener.Stub {
        private static final int TASK_CANCEL = 1;
        private static final int TASK_GET_SUGGESTIONS_MULTIPLE = 2;
        private static final int TASK_CLOSE = 3;
        private final Queue<SpellCheckerParams> mPendingTasks =
                new LinkedList<SpellCheckerParams>();
        private Handler mHandler;

        private boolean mOpened;
        private ISpellCheckerSession mISpellCheckerSession;

        public SpellCheckerSessionListenerImpl(Handler handler) {
            mOpened = false;
            mHandler = handler;
        }

        private static class SpellCheckerParams {
            public final int mWhat;
            public final TextInfo[] mTextInfos;
            public final int mSuggestionsLimit;
            public final boolean mSequentialWords;
            public SpellCheckerParams(int what, TextInfo[] textInfos, int suggestionsLimit,
                    boolean sequentialWords) {
                mWhat = what;
                mTextInfos = textInfos;
                mSuggestionsLimit = suggestionsLimit;
                mSequentialWords = sequentialWords;
            }
        }

        private void processTask(SpellCheckerParams scp) {
            switch (scp.mWhat) {
                case TASK_CANCEL:
                    processCancel();
                    break;
                case TASK_GET_SUGGESTIONS_MULTIPLE:
                    processGetSuggestionsMultiple(scp);
                    break;
                case TASK_CLOSE:
                    processClose();
                    break;
            }
        }

        public synchronized void onServiceConnected(ISpellCheckerSession session) {
            mISpellCheckerSession = session;
            mOpened = true;
            if (DBG)
                Log.d(TAG, "onServiceConnected - Success");
            while (!mPendingTasks.isEmpty()) {
                processTask(mPendingTasks.poll());
            }
        }

        public void cancel() {
            if (DBG) {
                Log.w(TAG, "cancel");
            }
            processOrEnqueueTask(new SpellCheckerParams(TASK_CANCEL, null, 0, false));
        }

        public void getSuggestionsMultiple(
                TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
            if (DBG) {
                Log.w(TAG, "getSuggestionsMultiple");
            }
            processOrEnqueueTask(
                    new SpellCheckerParams(TASK_GET_SUGGESTIONS_MULTIPLE, textInfos,
                            suggestionsLimit, sequentialWords));
        }

        public void close() {
            if (DBG) {
                Log.w(TAG, "close");
            }
            processOrEnqueueTask(new SpellCheckerParams(TASK_CLOSE, null, 0, false));
        }

        public boolean isDisconnected() {
            return mOpened && mISpellCheckerSession == null;
        }

        public boolean checkOpenConnection() {
            if (mISpellCheckerSession != null) {
                return true;
            }
            Log.e(TAG, "not connected to the spellchecker service.");
            return false;
        }

        private void processOrEnqueueTask(SpellCheckerParams scp) {
            if (DBG) {
                Log.d(TAG, "process or enqueue task: " + mISpellCheckerSession);
            }
            SpellCheckerParams closeTask = null;
            if (mISpellCheckerSession == null) {
                if (scp.mWhat == TASK_CANCEL) {
                    while (!mPendingTasks.isEmpty()) {
                        final SpellCheckerParams tmp = mPendingTasks.poll();
                        if (tmp.mWhat == TASK_CLOSE) {
                            // Only one close task should be processed, while we need to remove all
                            // close tasks from the queue
                            closeTask = tmp;
                        }
                    }
                }
                mPendingTasks.offer(scp);
                if (closeTask != null) {
                    mPendingTasks.offer(closeTask);
                }
            } else {
                processTask(scp);
            }
        }

        private void processCancel() {
            if (!checkOpenConnection()) {
                return;
            }
            if (DBG) {
                Log.w(TAG, "Cancel spell checker tasks.");
            }
            try {
                mISpellCheckerSession.onCancel();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to cancel " + e);
            }
        }

        private void processClose() {
            if (!checkOpenConnection()) {
                return;
            }
            if (DBG) {
                Log.w(TAG, "Close spell checker tasks.");
            }
            try {
                mISpellCheckerSession.onClose();
                mISpellCheckerSession = null;
                mHandler = null;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to close " + e);
            }
        }

        private void processGetSuggestionsMultiple(SpellCheckerParams scp) {
            if (!checkOpenConnection()) {
                return;
            }
            if (DBG) {
                Log.w(TAG, "Get suggestions from the spell checker.");
            }
            try {
                mISpellCheckerSession.onGetSuggestionsMultiple(
                        scp.mTextInfos, scp.mSuggestionsLimit, scp.mSequentialWords);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to get suggestions " + e);
            }
        }

        @Override
        public void onGetSuggestions(SuggestionsInfo[] results) {
            mHandler.sendMessage(Message.obtain(mHandler, MSG_ON_GET_SUGGESTION_MULTIPLE, results));
        }
    }

    /**
     * Callback for getting results from text services
     */
    public interface SpellCheckerSessionListener {
        /**
         * Callback for "getSuggestions"
         * @param results an array of results of getSuggestions
         */
        public void onGetSuggestions(SuggestionsInfo[] results);
    }

    private static class InternalListener extends ITextServicesSessionListener.Stub {
        private final SpellCheckerSessionListenerImpl mParentSpellCheckerSessionListenerImpl;

        public InternalListener(SpellCheckerSessionListenerImpl spellCheckerSessionListenerImpl) {
            mParentSpellCheckerSessionListenerImpl = spellCheckerSessionListenerImpl;
        }

        @Override
        public void onServiceConnected(ISpellCheckerSession session) {
            if (DBG) {
                Log.w(TAG, "SpellCheckerSession connected.");
            }
            mParentSpellCheckerSessionListenerImpl.onServiceConnected(session);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (mIsUsed) {
            Log.e(TAG, "SpellCheckerSession was not finished properly." +
                    "You should call finishShession() when you finished to use a spell checker.");
            close();
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
