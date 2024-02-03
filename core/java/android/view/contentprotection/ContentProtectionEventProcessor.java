/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view.contentprotection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.IContentCaptureManager;
import android.view.contentcapture.ViewNode;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main entry point for processing {@link ContentCaptureEvent} for the content protection flow.
 *
 * @hide
 */
public class ContentProtectionEventProcessor {

    private static final String TAG = "ContentProtectionEventProcessor";

    private static final List<Integer> PASSWORD_FIELD_INPUT_TYPES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));

    private static final List<String> PASSWORD_TEXTS =
            Collections.unmodifiableList(
                    Arrays.asList("password", "pass word", "code", "pin", "credential"));

    private static final List<String> ADDITIONAL_SUSPICIOUS_TEXTS =
            Collections.unmodifiableList(
                    Arrays.asList("user", "mail", "phone", "number", "login", "log in", "sign in"));

    private static final Duration MIN_DURATION_BETWEEN_FLUSHING = Duration.ofSeconds(3);

    private static final String ANDROID_CLASS_NAME_PREFIX = "android.";

    private static final Set<Integer> EVENT_TYPES_TO_STORE =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    ContentCaptureEvent.TYPE_VIEW_APPEARED,
                                    ContentCaptureEvent.TYPE_VIEW_DISAPPEARED,
                                    ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED)));

    private static final int RESET_LOGIN_TOTAL_EVENTS_TO_PROCESS = 150;

    @NonNull private final RingBuffer<ContentCaptureEvent> mEventBuffer;

    @NonNull private final Handler mHandler;

    @NonNull private final IContentCaptureManager mContentCaptureManager;

    @NonNull private final String mPackageName;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean mPasswordFieldDetected = false;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean mSuspiciousTextDetected = false;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    public Instant mLastFlushTime;

    private int mResetLoginRemainingEventsToProcess;

    public ContentProtectionEventProcessor(
            @NonNull RingBuffer<ContentCaptureEvent> eventBuffer,
            @NonNull Handler handler,
            @NonNull IContentCaptureManager contentCaptureManager,
            @NonNull String packageName) {
        mEventBuffer = eventBuffer;
        mHandler = handler;
        mContentCaptureManager = contentCaptureManager;
        mPackageName = packageName;
    }

    /** Main entry point for {@link ContentCaptureEvent} processing. */
    @UiThread
    public void processEvent(@NonNull ContentCaptureEvent event) {
        if (EVENT_TYPES_TO_STORE.contains(event.getType())) {
            storeEvent(event);
        }
        if (event.getType() == ContentCaptureEvent.TYPE_VIEW_APPEARED) {
            processViewAppearedEvent(event);
        }
    }

    @UiThread
    private void storeEvent(@NonNull ContentCaptureEvent event) {
        // Ensure receiver gets the package name which might not be set
        ViewNode viewNode = (event.getViewNode() != null) ? event.getViewNode() : new ViewNode();
        viewNode.setTextIdEntry(mPackageName);
        event.setViewNode(viewNode);
        mEventBuffer.append(event);
    }

    @UiThread
    private void processViewAppearedEvent(@NonNull ContentCaptureEvent event) {
        mPasswordFieldDetected |= isPasswordField(event);
        mSuspiciousTextDetected |= isSuspiciousText(event);
        if (mPasswordFieldDetected && mSuspiciousTextDetected) {
            loginDetected();
        } else {
            maybeResetLoginFlags();
        }
    }

    @UiThread
    private void loginDetected() {
        if (mLastFlushTime == null
                || Instant.now().isAfter(mLastFlushTime.plus(MIN_DURATION_BETWEEN_FLUSHING))) {
            flush();
        }
        resetLoginFlags();
    }

    @UiThread
    private void resetLoginFlags() {
        mPasswordFieldDetected = false;
        mSuspiciousTextDetected = false;
        mResetLoginRemainingEventsToProcess = 0;
    }

    @UiThread
    private void maybeResetLoginFlags() {
        if (mPasswordFieldDetected || mSuspiciousTextDetected) {
            if (mResetLoginRemainingEventsToProcess <= 0) {
                mResetLoginRemainingEventsToProcess = RESET_LOGIN_TOTAL_EVENTS_TO_PROCESS;
            } else {
                mResetLoginRemainingEventsToProcess--;
                if (mResetLoginRemainingEventsToProcess <= 0) {
                    resetLoginFlags();
                }
            }
        }
    }

    @UiThread
    private void flush() {
        mLastFlushTime = Instant.now();

        // Note the thread annotations, do not move clearEvents to mHandler
        ParceledListSlice<ContentCaptureEvent> events = clearEvents();
        mHandler.post(() -> handlerOnLoginDetected(events));
    }

    @UiThread
    @NonNull
    private ParceledListSlice<ContentCaptureEvent> clearEvents() {
        List<ContentCaptureEvent> events = Arrays.asList(mEventBuffer.toArray());
        mEventBuffer.clear();
        return new ParceledListSlice<>(events);
    }

    private void handlerOnLoginDetected(@NonNull ParceledListSlice<ContentCaptureEvent> events) {
        try {
            mContentCaptureManager.onLoginDetected(events);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to flush events for: " + mPackageName, ex);
        }
    }

    private boolean isPasswordField(@NonNull ContentCaptureEvent event) {
        return isPasswordField(event.getViewNode());
    }

    private boolean isPasswordField(@Nullable ViewNode viewNode) {
        if (viewNode == null) {
            return false;
        }
        return isAndroidPasswordField(viewNode) || isWebViewPasswordField(viewNode);
    }

    private boolean isAndroidPasswordField(@NonNull ViewNode viewNode) {
        if (!isAndroidViewNode(viewNode)) {
            return false;
        }
        int inputType = viewNode.getInputType();
        return PASSWORD_FIELD_INPUT_TYPES.stream()
                .anyMatch(passwordInputType -> (inputType & passwordInputType) != 0);
    }

    private boolean isWebViewPasswordField(@NonNull ViewNode viewNode) {
        if (viewNode.getClassName() != null) {
            return false;
        }
        return isPasswordText(ContentProtectionUtils.getViewNodeText(viewNode));
    }

    private boolean isAndroidViewNode(@NonNull ViewNode viewNode) {
        String className = viewNode.getClassName();
        return className != null && className.startsWith(ANDROID_CLASS_NAME_PREFIX);
    }

    private boolean isSuspiciousText(@NonNull ContentCaptureEvent event) {
        return isSuspiciousText(ContentProtectionUtils.getEventText(event))
                || isSuspiciousText(ContentProtectionUtils.getViewNodeText(event));
    }

    private boolean isSuspiciousText(@Nullable String text) {
        if (text == null) {
            return false;
        }
        if (isPasswordText(text)) {
            return true;
        }
        String lowerCaseText = text.toLowerCase();
        return ADDITIONAL_SUSPICIOUS_TEXTS.stream()
                .anyMatch(suspiciousText -> lowerCaseText.contains(suspiciousText));
    }

    private boolean isPasswordText(@Nullable String text) {
        if (text == null) {
            return false;
        }
        String lowerCaseText = text.toLowerCase();
        return PASSWORD_TEXTS.stream()
                .anyMatch(passwordText -> lowerCaseText.contains(passwordText));
    }
}
