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
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

/**
 * A session that is explicitly created by the app (and hence is a descendant of
 * {@link MainContentCaptureSession}).
 *
 * @hide
 */
final class ChildContentCaptureSession extends ContentCaptureSession {

    @NonNull
    private final ContentCaptureSession mParent;

    /**
     * {@link ContentCaptureContext} set by client, or {@code null} when it's the
     * {@link ContentCaptureManager#getMainContentCaptureSession() default session} for the
     * context.
     *
     * @hide
     */
    @NonNull
    private final ContentCaptureContext mClientContext;

    /** @hide */
    protected ChildContentCaptureSession(@NonNull ContentCaptureSession parent,
            @NonNull ContentCaptureContext clientContext) {
        mParent = parent;
        mClientContext = Preconditions.checkNotNull(clientContext);
    }

    @Override
    MainContentCaptureSession getMainCaptureSession() {
        if (mParent instanceof MainContentCaptureSession) {
            return (MainContentCaptureSession) mParent;
        }
        return mParent.getMainCaptureSession();
    }

    @Override
    ContentCaptureSession newChild(@NonNull ContentCaptureContext clientContext) {
        final ContentCaptureSession child = new ChildContentCaptureSession(this, clientContext);
        getMainCaptureSession().notifyChildSessionStarted(mId, child.mId, clientContext);
        return child;
    }

    @Override
    void flush(@FlushReason int reason) {
        mParent.flush(reason);
    }

    @Override
    void onDestroy() {
        getMainCaptureSession().notifyChildSessionFinished(mParent.mId, mId);
    }

    @Override
    void internalNotifyViewAppeared(@NonNull ViewStructureImpl node) {
        getMainCaptureSession().notifyViewAppeared(mId, node);
    }

    @Override
    void internalNotifyViewDisappeared(@NonNull AutofillId id) {
        getMainCaptureSession().notifyViewDisappeared(mId, id);
    }

    @Override
    void internalNotifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text) {
        getMainCaptureSession().notifyViewTextChanged(mId, id, text);
    }

    @Override
    public void internalNotifyViewHierarchyEvent(boolean started) {
        getMainCaptureSession().notifyInitialViewHierarchyEvent(mId, started);
    }

    @Override
    boolean isContentCaptureEnabled() {
        return getMainCaptureSession().isContentCaptureEnabled();
    }

    @Override
    void dump(String prefix, PrintWriter pw) {
        if (mClientContext != null) {
            // NOTE: we don't dump clientContent because it could have PII
            pw.print(prefix); pw.println("hasClientContext");
        }
        super.dump(prefix, pw);
    }
}
