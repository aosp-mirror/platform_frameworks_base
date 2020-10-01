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

package com.android.server.wm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Observe activity manager launch sequences.
 *
 * The activity manager can have at most 1 concurrent launch sequences. Calls to this interface
 * are ordered by a happens-before relation for each defined state transition (see below).
 *
 * When a new launch sequence is made, that sequence is in the {@code INTENT_STARTED} state which
 * is communicated by the {@link #onIntentStarted} callback. This is a transient state.
 *
 * The intent can fail to launch the activity, in which case the sequence's state transitions to
 * {@code INTENT_FAILED} via {@link #onIntentFailed}. This is a terminal state.
 *
 * If an activity is successfully started, the launch sequence's state will transition into
 * {@code STARTED} via {@link #onActivityLaunched}. This is a transient state.
 *
 * It must then transition to either {@code CANCELLED} with {@link #onActivityLaunchCancelled},
 * which is a terminal state or into {@code FINISHED} with {@link #onActivityLaunchFinished}.
 *
 * The {@code FINISHED} with {@link #onActivityLaunchFinished} then may transition to
 * {@code FULLY_DRAWN} with {@link #onReportFullyDrawn}, which is a terminal state.
 * Note this transition may not happen if the reportFullyDrawn event is not receivied,
 * in which case {@code FINISHED} is terminal.
 *
 * Note that the {@code ActivityRecordProto} provided as a parameter to some state transitions isn't
 * necessarily the same within a single launch sequence: it is only the top-most activity at the
 * time (if any). Trampoline activities coalesce several activity starts into a single launch
 * sequence.
 *
 * Upon reaching a terminal state, it is considered that there are no active launch sequences
 * until a subsequent transition into {@code INTENT_STARTED} initiates a new launch sequence.
 *
 * <pre>
 *        ┌⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯┐     ┌⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯┐     ┌--------------------------┐
 *    ╴╴▶  INTENT_STARTED    ──▶      ACTIVITY_LAUNCHED        ──▶   ACTIVITY_LAUNCH_FINISHED
 *        └⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯┘     └⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯⋯┘     └--------------------------┘
 *          :                      :                                 :
 *          :                      :                                 :
 *          ▼                      ▼                                 ▼
 *        ╔════════════════╗     ╔═══════════════════════════╗     ╔═══════════════════════════╗
 *        ║ INTENT_FAILED  ║     ║ ACTIVITY_LAUNCH_CANCELLED ║     ║    REPORT_FULLY_DRAWN     ║
 *        ╚════════════════╝     ╚═══════════════════════════╝     ╚═══════════════════════════╝
 * </pre>
 */
public interface ActivityMetricsLaunchObserver {
    /**
     * The 'temperature' at which a launch sequence had started.
     *
     * The lower the temperature the more work has to be done during start-up.
     * A 'cold' temperature means that a new process has been started and likely
     * nothing is cached.
     *
     * A hot temperature means the existing activity is brought to the foreground.
     * It may need to regenerate some objects as a result of {@code onTrimMemory}.
     *
     * A warm temperature is in the middle; an existing process is used, but the activity
     * has to be created from scratch with {@code #onCreate}.
     *
     * @see https://developer.android.com/topic/performance/vitals/launch-time
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TEMPERATURE_COLD,
            TEMPERATURE_WARM,
            TEMPERATURE_HOT
    })
    @interface Temperature {}

    /** Cold launch sequence: a new process has started. */
    public static final int TEMPERATURE_COLD = 1;
    /** Warm launch sequence: process reused, but activity has to be created. */
    public static final int TEMPERATURE_WARM = 2;
    /** Hot launch sequence: process reused, activity brought-to-top. */
    public static final int TEMPERATURE_HOT = 3;

    /**
     * Typedef marker that a {@code byte[]} actually contains an
     * <a href="proto/android/server/activitymanagerservice.proto">ActivityRecordProto</a>
     * in the protobuf format.
     */
    @Retention(RetentionPolicy.SOURCE)
    @interface ActivityRecordProto {}

    /**
     * Notifies the observer that a new launch sequence has begun as a result of a new intent.
     *
     * Once a launch sequence begins, the resolved activity will either subsequently start with
     * {@link #onActivityLaunched} or abort early (for example due to a resolution error or due to
     * a security error) with {@link #onIntentFailed}.
     *
     * Multiple calls to this method cannot occur without first terminating the current
     * launch sequence.
     */
    public void onIntentStarted(@NonNull Intent intent, long timestampNanos);

    /**
     * Notifies the observer that the current launch sequence has failed to launch an activity.
     *
     * This function call terminates the current launch sequence. The next method call, if any,
     * must be {@link #onIntentStarted}.
     *
     * Examples of this happening:
     *  - Failure to resolve to an activity
     *  - Calling package did not have the security permissions to call the requested activity
     *  - Resolved activity was already running and only needed to be brought to the top
     *
     * Multiple calls to this method cannot occur without first terminating the current
     * launch sequence.
     */
    public void onIntentFailed();

    /**
     * Notifies the observer that the current launch sequence had begun starting an activity.
     *
     * This is an intermediate state: once an activity begins starting, the entire launch sequence
     * will later terminate by either finishing or cancelling.
     *
     * The initial activity is the first activity to be started as part of a launch sequence:
     * it is represented by {@param activity} However, it isn't
     * necessarily the activity which will be considered as displayed when the activity
     * finishes launching (e.g. {@code activity} in {@link #onActivityLaunchFinished}).
     *
     * Multiple calls to this method cannot occur without first terminating the current
     * launch sequence.
     */
    public void onActivityLaunched(@NonNull @ActivityRecordProto byte[] activity,
                                   @Temperature int temperature);

    /**
     * Notifies the observer that the current launch sequence has been aborted.
     *
     * This function call terminates the current launch sequence. The next method call, if any,
     * must be {@link #onIntentStarted}.
     *
     * This can happen for many reasons, for example the user switches away to another app
     * prior to the launch sequence completing, or the application being killed.
     *
     * Multiple calls to this method cannot occur without first terminating the current
     * launch sequence.
     *
     * @param abortingActivity the last activity that had the top-most window during abort
     *                         (this can be {@code null} in rare situations its unknown).
     *
     * @apiNote The aborting activity isn't necessarily the same as the starting activity;
     *          in the case of a trampoline, multiple activities could've been started
     *          and only the latest activity is reported here.
     */
    public void onActivityLaunchCancelled(@Nullable @ActivityRecordProto byte[] abortingActivity);

    /**
     * Notifies the observer that the current launch sequence has been successfully finished.
     *
     * This function call terminates the current launch sequence. The next method call, if any,
     * must be {@link #onIntentStarted}.
     *
     * A launch sequence is considered to be successfully finished when a frame is fully
     * drawn for the first time: the top-most activity at the time is what's reported here.
     *
     * @param finalActivity the top-most activity whose windows were first to fully draw
     * @param timestampNanos the timestamp of ActivityLaunchFinished event in nanoseconds.
     *        To compute the TotalTime duration, deduct the timestamp {@link #onIntentStarted}
     *        from {@code timestampNanos}.
     *
     * Multiple calls to this method cannot occur without first terminating the current
     * launch sequence.
     *
     * @apiNote The finishing activity isn't necessarily the same as the starting activity;
     *          in the case of a trampoline, multiple activities could've been started
     *          and only the latest activity that was top-most during first-frame drawn
     *          is reported here.
     */
    public void onActivityLaunchFinished(@NonNull @ActivityRecordProto byte[] finalActivity,
                                         long timestampNanos);

    /**
     * Notifies the observer that the application self-reported itself as being fully drawn.
     *
     * @param activity the activity that triggers the ReportFullyDrawn event.
     * @param timestampNanos the timestamp of ReportFullyDrawn event in nanoseconds.
     *        To compute the duration, deduct the deduct the timestamp {@link #onIntentStarted}
     *        from {@code timestampNanos}.
     *
     * @apiNote The behavior of ReportFullyDrawn mostly depends on the app.
     *          It is used as an accurate estimate of meanfully app startup time.
     *          This event may be missing for many apps.
     */
    public void onReportFullyDrawn(@NonNull @ActivityRecordProto byte[] activity,
        long timestampNanos);

}
