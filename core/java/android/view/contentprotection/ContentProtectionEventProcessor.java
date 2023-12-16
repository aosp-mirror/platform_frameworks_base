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
import android.content.ContentCaptureOptions;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.util.Log;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.IContentCaptureManager;
import android.view.contentcapture.ViewNode;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Main entry point for processing {@link ContentCaptureEvent} for the content protection flow.
 *
 * @hide
 */
public class ContentProtectionEventProcessor {

    private static final String TAG = "ContentProtectionEventProcessor";

    private static final Duration MIN_DURATION_BETWEEN_FLUSHING = Duration.ofSeconds(3);

    private static final Set<Integer> EVENT_TYPES_TO_STORE =
            Set.of(
                    ContentCaptureEvent.TYPE_VIEW_APPEARED,
                    ContentCaptureEvent.TYPE_VIEW_DISAPPEARED,
                    ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED);

    private static final int RESET_LOGIN_TOTAL_EVENTS_TO_PROCESS = 150;

    @NonNull private final RingBuffer<ContentCaptureEvent> mEventBuffer;

    @NonNull private final Handler mHandler;

    @NonNull private final IContentCaptureManager mContentCaptureManager;

    @NonNull private final String mPackageName;

    @NonNull private final ContentCaptureOptions.ContentProtectionOptions mOptions;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    public Instant mLastFlushTime;

    private int mResetLoginRemainingEventsToProcess;

    private boolean mAnyGroupFound = false;

    // Ordered by priority
    private final List<SearchGroup> mGroupsRequired;

    // Ordered by priority
    private final List<SearchGroup> mGroupsOptional;

    // Ordered by priority
    private final List<SearchGroup> mGroupsAll;

    public ContentProtectionEventProcessor(
            @NonNull RingBuffer<ContentCaptureEvent> eventBuffer,
            @NonNull Handler handler,
            @NonNull IContentCaptureManager contentCaptureManager,
            @NonNull String packageName,
            @NonNull ContentCaptureOptions.ContentProtectionOptions options) {
        mEventBuffer = eventBuffer;
        mHandler = handler;
        mContentCaptureManager = contentCaptureManager;
        mPackageName = packageName;
        mOptions = options;
        mGroupsRequired = options.requiredGroups.stream().map(SearchGroup::new).toList();
        mGroupsOptional = options.optionalGroups.stream().map(SearchGroup::new).toList();
        mGroupsAll =
                Stream.of(mGroupsRequired, mGroupsOptional).flatMap(Collection::stream).toList();
    }

    /** Main entry point for {@link ContentCaptureEvent} processing. */
    public void processEvent(@NonNull ContentCaptureEvent event) {
        if (EVENT_TYPES_TO_STORE.contains(event.getType())) {
            storeEvent(event);
        }
        if (event.getType() == ContentCaptureEvent.TYPE_VIEW_APPEARED) {
            processViewAppearedEvent(event);
        }
    }

    private void storeEvent(@NonNull ContentCaptureEvent event) {
        // Ensure receiver gets the package name which might not be set
        ViewNode viewNode = (event.getViewNode() != null) ? event.getViewNode() : new ViewNode();
        viewNode.setTextIdEntry(mPackageName);
        event.setViewNode(viewNode);
        mEventBuffer.append(event);
    }

    private void processViewAppearedEvent(@NonNull ContentCaptureEvent event) {
        ViewNode viewNode = event.getViewNode();
        String eventText = ContentProtectionUtils.getEventTextLower(event);
        String viewNodeText = ContentProtectionUtils.getViewNodeTextLower(viewNode);
        String hintText = ContentProtectionUtils.getHintTextLower(viewNode);

        mGroupsAll.stream()
                .filter(group -> !group.mFound)
                .filter(
                        group ->
                                group.matches(eventText)
                                        || group.matches(viewNodeText)
                                        || group.matches(hintText))
                .findFirst()
                .ifPresent(
                        group -> {
                            group.mFound = true;
                            mAnyGroupFound = true;
                        });

        boolean loginDetected =
                mGroupsRequired.stream().allMatch(group -> group.mFound)
                        && mGroupsOptional.stream().filter(group -> group.mFound).count()
                                >= mOptions.optionalGroupsThreshold;

        if (loginDetected) {
            loginDetected();
        } else {
            maybeResetLoginFlags();
        }
    }

    private void loginDetected() {
        if (mLastFlushTime == null
                || Instant.now().isAfter(mLastFlushTime.plus(MIN_DURATION_BETWEEN_FLUSHING))) {
            flush();
        }
        resetLoginFlags();
    }

    private void resetLoginFlags() {
        mGroupsAll.forEach(group -> group.mFound = false);
        mAnyGroupFound = false;
    }

    private void maybeResetLoginFlags() {
        if (mAnyGroupFound) {
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

    private void flush() {
        mLastFlushTime = Instant.now();

        // Note the thread annotations, do not move clearEvents to mHandler
        ParceledListSlice<ContentCaptureEvent> events = clearEvents();
        mHandler.post(() -> handlerOnLoginDetected(events));
    }

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

    private static final class SearchGroup {

        @NonNull private final List<String> mSearchStrings;

        public boolean mFound = false;

        SearchGroup(@NonNull List<String> searchStrings) {
            mSearchStrings = searchStrings;
        }

        public boolean matches(@Nullable String text) {
            if (text == null) {
                return false;
            }
            return mSearchStrings.stream().anyMatch(text::contains);
        }
    }
}
