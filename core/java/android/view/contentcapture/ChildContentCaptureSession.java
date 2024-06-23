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
package android.view.contentcapture;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import java.util.ArrayList;

/**
 * A session that is explicitly created by the app (and hence is a descendant of
 * {@link MainContentCaptureSession}).
 *
 * @hide
 */
final class ChildContentCaptureSession extends ContentCaptureSession {

    @NonNull
    private final ContentCaptureSession mParent;

    /** @hide */
    protected ChildContentCaptureSession(@NonNull ContentCaptureSession parent,
            @NonNull ContentCaptureContext clientContext) {
        super(clientContext);
        mParent = parent;
    }

    @Override
    ContentCaptureSession getMainCaptureSession() {
        return mParent.getMainCaptureSession();
    }

    @Override
    void start(@NonNull IBinder token, @NonNull IBinder shareableActivityToken,
            @NonNull ComponentName component, int flags) {
        getMainCaptureSession().start(token, shareableActivityToken, component, flags);
    }

    @Override
    boolean isDisabled() {
        return getMainCaptureSession().isDisabled();
    }

    @Override
    boolean setDisabled(boolean disabled) {
        return getMainCaptureSession().setDisabled(disabled);
    }

    @Override
    ContentCaptureSession newChild(@NonNull ContentCaptureContext clientContext) {
        final ContentCaptureSession child = new ChildContentCaptureSession(this, clientContext);
        internalNotifyChildSessionStarted(mId, child.mId, clientContext);
        return child;
    }

    @Override
    void flush(@FlushReason int reason) {
        mParent.flush(reason);
    }

    @Override
    public void updateContentCaptureContext(@Nullable ContentCaptureContext context) {
        internalNotifyContextUpdated(mId, context);
    }

    @Override
    void onDestroy() {
        internalNotifyChildSessionFinished(mParent.mId, mId);
    }

    @Override
    void internalNotifyChildSessionStarted(int parentSessionId, int childSessionId,
            @NonNull ContentCaptureContext clientContext) {
        getMainCaptureSession()
                .internalNotifyChildSessionStarted(parentSessionId, childSessionId, clientContext);
    }

    @Override
    void internalNotifyChildSessionFinished(int parentSessionId, int childSessionId) {
        getMainCaptureSession().internalNotifyChildSessionFinished(parentSessionId, childSessionId);
    }

    @Override
    void internalNotifyContextUpdated(int sessionId, @Nullable ContentCaptureContext context) {
        getMainCaptureSession().internalNotifyContextUpdated(sessionId, context);
    }

    @Override
    void internalNotifyViewAppeared(int sessionId, @NonNull ViewStructureImpl node) {
        getMainCaptureSession().internalNotifyViewAppeared(sessionId, node);
    }

    @Override
    void internalNotifyViewDisappeared(int sessionId, @NonNull AutofillId id) {
        getMainCaptureSession().internalNotifyViewDisappeared(sessionId, id);
    }

    @Override
    void internalNotifyViewTextChanged(
            int sessionId, @NonNull AutofillId id, @Nullable CharSequence text) {
        getMainCaptureSession().internalNotifyViewTextChanged(sessionId, id, text);
    }

    @Override
    void internalNotifyViewInsetsChanged(int sessionId, @NonNull Insets viewInsets) {
        getMainCaptureSession().internalNotifyViewInsetsChanged(mId, viewInsets);
    }

    @Override
    public void internalNotifyViewTreeEvent(int sessionId, boolean started) {
        getMainCaptureSession().internalNotifyViewTreeEvent(sessionId, started);
    }

    @Override
    void internalNotifySessionResumed() {
        getMainCaptureSession().internalNotifySessionResumed();
    }

    @Override
    void internalNotifySessionPaused() {
        getMainCaptureSession().internalNotifySessionPaused();
    }

    @Override
    boolean isContentCaptureEnabled() {
        return getMainCaptureSession().isContentCaptureEnabled();
    }

    @Override
    public void notifyWindowBoundsChanged(int sessionId, @NonNull Rect bounds) {
        getMainCaptureSession().notifyWindowBoundsChanged(sessionId, bounds);
    }

    @Override
    public void notifyContentCaptureEvents(
            @NonNull SparseArray<ArrayList<Object>> contentCaptureEvents) {
        getMainCaptureSession().notifyContentCaptureEvents(contentCaptureEvents);
    }
}
