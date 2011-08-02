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
        mInternalListener = new InternalListener();
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
     * Finish this session and allow TextServicesManagerService to disconnect the bound spell
     * checker.
     */
    public void close() {
        mIsUsed = false;
        try {
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
        private final Queue<SpellCheckerParams> mPendingTasks =
                new LinkedList<SpellCheckerParams>();
        private final Handler mHandler;

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

        public void getSuggestionsMultiple(
                TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
            if (DBG) {
                Log.w(TAG, "getSuggestionsMultiple");
            }
            processOrEnqueueTask(
                    new SpellCheckerParams(TASK_GET_SUGGESTIONS_MULTIPLE, textInfos,
                            suggestionsLimit, sequentialWords));
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
            if (mISpellCheckerSession == null) {
                mPendingTasks.offer(scp);
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
                mISpellCheckerSession.cancel();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to cancel " + e);
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
                mISpellCheckerSession.getSuggestionsMultiple(
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

    private class InternalListener extends ITextServicesSessionListener.Stub {
        @Override
        public void onServiceConnected(ISpellCheckerSession session) {
            if (DBG) {
                Log.w(TAG, "SpellCheckerSession connected.");
            }
            mSpellCheckerSessionListenerImpl.onServiceConnected(session);
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
