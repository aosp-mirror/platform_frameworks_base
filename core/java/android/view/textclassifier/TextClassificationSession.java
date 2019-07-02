/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.WorkerThread;
import android.view.textclassifier.SelectionEvent.InvocationMethod;

import com.android.internal.util.Preconditions;

/**
 * Session-aware TextClassifier.
 */
@WorkerThread
final class TextClassificationSession implements TextClassifier {

    private static final String LOG_TAG = "TextClassificationSession";

    private final TextClassifier mDelegate;
    private final SelectionEventHelper mEventHelper;
    private final TextClassificationSessionId mSessionId;
    private final TextClassificationContext mClassificationContext;

    private boolean mDestroyed;

    TextClassificationSession(TextClassificationContext context, TextClassifier delegate) {
        mClassificationContext = Preconditions.checkNotNull(context);
        mDelegate = Preconditions.checkNotNull(delegate);
        mSessionId = new TextClassificationSessionId();
        mEventHelper = new SelectionEventHelper(mSessionId, mClassificationContext);
        initializeRemoteSession();
    }

    @Override
    public TextSelection suggestSelection(TextSelection.Request request) {
        checkDestroyed();
        return mDelegate.suggestSelection(request);
    }

    private void initializeRemoteSession() {
        if (mDelegate instanceof SystemTextClassifier) {
            ((SystemTextClassifier) mDelegate).initializeRemoteSession(
                    mClassificationContext, mSessionId);
        }
    }

    @Override
    public TextClassification classifyText(TextClassification.Request request) {
        checkDestroyed();
        return mDelegate.classifyText(request);
    }

    @Override
    public TextLinks generateLinks(TextLinks.Request request) {
        checkDestroyed();
        return mDelegate.generateLinks(request);
    }

    @Override
    public void onSelectionEvent(SelectionEvent event) {
        try {
            if (mEventHelper.sanitizeEvent(event)) {
                mDelegate.onSelectionEvent(event);
            }
        } catch (Exception e) {
            // Avoid crashing for event reporting.
            Log.e(LOG_TAG, "Error reporting text classifier selection event", e);
        }
    }

    @Override
    public void onTextClassifierEvent(TextClassifierEvent event) {
        try {
            event.mHiddenTempSessionId = mSessionId;
            mDelegate.onTextClassifierEvent(event);
        } catch (Exception e) {
            // Avoid crashing for event reporting.
            Log.e(LOG_TAG, "Error reporting text classifier event", e);
        }
    }

    @Override
    public void destroy() {
        mEventHelper.endSession();
        mDelegate.destroy();
        mDestroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * @throws IllegalStateException if this TextClassification session has been destroyed.
     * @see #isDestroyed()
     * @see #destroy()
     */
    private void checkDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("This TextClassification session has been destroyed");
        }
    }

    /**
     * Helper class for updating SelectionEvent fields.
     */
    private static final class SelectionEventHelper {

        private final TextClassificationSessionId mSessionId;
        private final TextClassificationContext mContext;

        @InvocationMethod
        private int mInvocationMethod = SelectionEvent.INVOCATION_UNKNOWN;
        private SelectionEvent mPrevEvent;
        private SelectionEvent mSmartEvent;
        private SelectionEvent mStartEvent;

        SelectionEventHelper(
                TextClassificationSessionId sessionId, TextClassificationContext context) {
            mSessionId = Preconditions.checkNotNull(sessionId);
            mContext = Preconditions.checkNotNull(context);
        }

        /**
         * Updates the necessary fields in the event for the current session.
         *
         * @return true if the event should be reported. false if the event should be ignored
         */
        boolean sanitizeEvent(SelectionEvent event) {
            updateInvocationMethod(event);
            modifyAutoSelectionEventType(event);

            if (event.getEventType() != SelectionEvent.EVENT_SELECTION_STARTED
                    && mStartEvent == null) {
                Log.d(LOG_TAG, "Selection session not yet started. Ignoring event");
                return false;
            }

            final long now = System.currentTimeMillis();
            switch (event.getEventType()) {
                case SelectionEvent.EVENT_SELECTION_STARTED:
                    Preconditions.checkArgument(
                            event.getAbsoluteEnd() == event.getAbsoluteStart() + 1);
                    event.setSessionId(mSessionId);
                    mStartEvent = event;
                    break;
                case SelectionEvent.EVENT_SMART_SELECTION_SINGLE:  // fall through
                case SelectionEvent.EVENT_SMART_SELECTION_MULTI:   // fall through
                case SelectionEvent.EVENT_AUTO_SELECTION:
                    mSmartEvent = event;
                    break;
                case SelectionEvent.EVENT_SELECTION_MODIFIED:
                    if (mPrevEvent != null
                            && mPrevEvent.getAbsoluteStart() == event.getAbsoluteStart()
                            && mPrevEvent.getAbsoluteEnd() == event.getAbsoluteEnd()) {
                        // Selection did not change. Ignore event.
                        return false;
                    }
                    break;
                default:
                    // do nothing.
            }

            event.setEventTime(now);
            if (mStartEvent != null) {
                event.setSessionId(mStartEvent.getSessionId())
                        .setDurationSinceSessionStart(now - mStartEvent.getEventTime())
                        .setStart(event.getAbsoluteStart() - mStartEvent.getAbsoluteStart())
                        .setEnd(event.getAbsoluteEnd() - mStartEvent.getAbsoluteStart());
            }
            if (mSmartEvent != null) {
                event.setResultId(mSmartEvent.getResultId())
                        .setSmartStart(
                                mSmartEvent.getAbsoluteStart() - mStartEvent.getAbsoluteStart())
                        .setSmartEnd(mSmartEvent.getAbsoluteEnd() - mStartEvent.getAbsoluteStart());
            }
            if (mPrevEvent != null) {
                event.setDurationSincePreviousEvent(now - mPrevEvent.getEventTime())
                        .setEventIndex(mPrevEvent.getEventIndex() + 1);
            }
            mPrevEvent = event;
            return true;
        }

        void endSession() {
            mPrevEvent = null;
            mSmartEvent = null;
            mStartEvent = null;
        }

        private void updateInvocationMethod(SelectionEvent event) {
            event.setTextClassificationSessionContext(mContext);
            if (event.getInvocationMethod() == SelectionEvent.INVOCATION_UNKNOWN) {
                event.setInvocationMethod(mInvocationMethod);
            } else {
                mInvocationMethod = event.getInvocationMethod();
            }
        }

        private void modifyAutoSelectionEventType(SelectionEvent event) {
            switch (event.getEventType()) {
                case SelectionEvent.EVENT_SMART_SELECTION_SINGLE:  // fall through
                case SelectionEvent.EVENT_SMART_SELECTION_MULTI:  // fall through
                case SelectionEvent.EVENT_AUTO_SELECTION:
                    if (SelectionSessionLogger.isPlatformLocalTextClassifierSmartSelection(
                            event.getResultId())) {
                        if (event.getAbsoluteEnd() - event.getAbsoluteStart() > 1) {
                            event.setEventType(SelectionEvent.EVENT_SMART_SELECTION_MULTI);
                        } else {
                            event.setEventType(SelectionEvent.EVENT_SMART_SELECTION_SINGLE);
                        }
                    } else {
                        event.setEventType(SelectionEvent.EVENT_AUTO_SELECTION);
                    }
                    return;
                default:
                    return;
            }
        }
    }
}
