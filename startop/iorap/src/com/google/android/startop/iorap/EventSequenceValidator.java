/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.google.android.startop.iorap;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.util.Log;

import com.android.server.wm.ActivityMetricsLaunchObserver;

import java.io.StringWriter;
import java.io.PrintWriter;

/**
 * A validator to check the correctness of event sequence during app startup.
 *
 * <p> A valid state transition of event sequence is shown as the following:
 *
 * <pre>
 *
 *                                +--------------------+
 *                                |                    |
 *                                |        INIT        |
 *                                |                    |
 *                                +--------------------+
 *                                          |
 *                                          |
 *                                          ↓
 *                                +--------------------+
 *                                |                    |
 *            +-------------------|   INTENT_STARTED   | ←--------------------------------+
 *            |                   |                    |                                  |
 *            |                   +--------------------+                                  |
 *            |                             |                                             |
 *            |                             |                                             |
 *            ↓                             ↓                                             |
 * +--------------------+         +--------------------+                                  |
 * |                    |         |                    |                                  |
 * |   INTENT_FAILED    |         | ACTIVITY_LAUNCHED  |------------------+               |
 * |                    |         |                    |                  |               |
 * +--------------------+         +--------------------+                  |               |
 *            |                              |                            |               |
 *            |                              ↓                            ↓               |
 *            |                   +--------------------+       +--------------------+     |
 *            |                   |                    |       |                    |     |
 *            +------------------ |  ACTIVITY_FINISHED |       | ACTIVITY_CANCELLED |     |
 *            |                   |                    |       |                    |     |
 *            |                   +--------------------+       +--------------------+     |
 *            |                              |                            |               |
 *            |                              |                            |               |
 *            |                              ↓                            |               |
 *            |                   +--------------------+                  |               |
 *            |                   |                    |                  |               |
 *            |                   | REPORT_FULLY_DRAWN |                  |               |
 *            |                   |                    |                  |               |
 *            |                   +--------------------+                  |               |
 *            |                              |                            |               |
 *            |                              |                            |               |
 *            |                              ↓                            |               |
 *            |                   +--------------------+                  |               |
 *            |                   |                    |                  |               |
 *            +-----------------→ |        END         |←-----------------+               |
 *                                |                    |                                  |
 *                                +--------------------+                                  |
 *                                           |                                            |
 *                                           |                                            |
 *                                           |                                            |
 *                                           +---------------------------------------------
 *
 * <p> END is not a real state in implementation. All states that points to END directly
 * could transition to INTENT_STARTED.
 *
 * <p> If any bad transition happened, the state becomse UNKNOWN. The UNKNOWN state
 * could be accumulated, because during the UNKNOWN state more IntentStarted may
 * be triggered. To recover from UNKNOWN to INIT, all the accumualted IntentStarted
 * should termniate.
 *
 * <p> During UNKNOWN state, each IntentStarted increases the accumulation, and any of
 * IntentFailed, ActivityLaunchCancelled and ActivityFinished decreases the accumulation.
 * ReportFullyDrawn doesn't impact the accumulation.
 */
public class EventSequenceValidator implements ActivityMetricsLaunchObserver {
  static final String TAG = "EventSequenceValidator";
  /** $> adb shell 'setprop log.tag.EventSequenceValidator VERBOSE' */
  public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
  private State state = State.INIT;
  private long accIntentStartedEvents = 0;

  @Override
  public void onIntentStarted(@NonNull Intent intent, long timestampNs) {
    if (state == State.UNKNOWN) {
      logWarningWithStackTrace("IntentStarted during UNKNOWN. " + intent);
      incAccIntentStartedEvents();
      return;
    }

    if (state != State.INIT &&
        state != State.INTENT_FAILED &&
        state != State.ACTIVITY_CANCELLED &&
        state != State.ACTIVITY_FINISHED &&
        state != State.REPORT_FULLY_DRAWN) {
      logWarningWithStackTrace(
          String.format("Cannot transition from %s to %s", state, State.INTENT_STARTED));
      incAccIntentStartedEvents();
      incAccIntentStartedEvents();
      return;
    }

    Log.d(TAG, String.format("Transition from %s to %s", state, State.INTENT_STARTED));
    state = State.INTENT_STARTED;
  }

  @Override
  public void onIntentFailed() {
    if (state == State.UNKNOWN) {
      logWarningWithStackTrace("onIntentFailed during UNKNOWN.");
      decAccIntentStartedEvents();
      return;
    }
    if (state != State.INTENT_STARTED) {
      logWarningWithStackTrace(
          String.format("Cannot transition from %s to %s", state, State.INTENT_FAILED));
      incAccIntentStartedEvents();
      return;
    }

    Log.d(TAG, String.format("Transition from %s to %s", state, State.INTENT_FAILED));
    state = State.INTENT_FAILED;
  }

  @Override
  public void onActivityLaunched(@NonNull @ActivityRecordProto byte[] activity,
      @Temperature int temperature) {
    if (state == State.UNKNOWN) {
      logWarningWithStackTrace("onActivityLaunched during UNKNOWN.");
      return;
    }
    if (state != State.INTENT_STARTED) {
      logWarningWithStackTrace(
          String.format("Cannot transition from %s to %s", state, State.ACTIVITY_LAUNCHED));
      incAccIntentStartedEvents();
      return;
    }

    Log.d(TAG, String.format("Transition from %s to %s", state, State.ACTIVITY_LAUNCHED));
    state = State.ACTIVITY_LAUNCHED;
  }

  @Override
  public void onActivityLaunchCancelled(@Nullable @ActivityRecordProto byte[] activity) {
    if (state == State.UNKNOWN) {
      logWarningWithStackTrace("onActivityLaunchCancelled during UNKNOWN.");
      decAccIntentStartedEvents();
      return;
    }
    if (state != State.ACTIVITY_LAUNCHED) {
      logWarningWithStackTrace(
          String.format("Cannot transition from %s to %s", state, State.ACTIVITY_CANCELLED));
      incAccIntentStartedEvents();
      return;
    }

    Log.d(TAG, String.format("Transition from %s to %s", state, State.ACTIVITY_CANCELLED));
    state = State.ACTIVITY_CANCELLED;
  }

  @Override
  public void onActivityLaunchFinished(@NonNull @ActivityRecordProto byte[] activity,
      long timestampNs) {
    if (state == State.UNKNOWN) {
      logWarningWithStackTrace("onActivityLaunchFinished during UNKNOWN.");
      decAccIntentStartedEvents();
      return;
    }

    if (state != State.ACTIVITY_LAUNCHED) {
      logWarningWithStackTrace(
          String.format("Cannot transition from %s to %s", state, State.ACTIVITY_FINISHED));
      incAccIntentStartedEvents();
      return;
    }

    Log.d(TAG, String.format("Transition from %s to %s", state, State.ACTIVITY_FINISHED));
    state = State.ACTIVITY_FINISHED;
  }

  @Override
  public void onReportFullyDrawn(@NonNull @ActivityRecordProto byte[] activity,
      long timestampNs) {
    if (state == State.UNKNOWN) {
      logWarningWithStackTrace("onReportFullyDrawn during UNKNOWN.");
      return;
    }
    if (state == State.INIT) {
      return;
    }

    if (state != State.ACTIVITY_FINISHED) {
      logWarningWithStackTrace(
          String.format("Cannot transition from %s to %s", state, State.REPORT_FULLY_DRAWN));
      return;
    }

    Log.d(TAG, String.format("Transition from %s to %s", state, State.REPORT_FULLY_DRAWN));
    state = State.REPORT_FULLY_DRAWN;
  }

  enum State {
    INIT,
    INTENT_STARTED,
    INTENT_FAILED,
    ACTIVITY_LAUNCHED,
    ACTIVITY_CANCELLED,
    ACTIVITY_FINISHED,
    REPORT_FULLY_DRAWN,
    UNKNOWN,
  }

  private void incAccIntentStartedEvents() {
    if (accIntentStartedEvents < 0) {
      throw new AssertionError("The number of unknowns cannot be negative");
    }
    if (accIntentStartedEvents == 0) {
      state = State.UNKNOWN;
    }
    ++accIntentStartedEvents;
    Log.d(TAG,
        String.format("inc AccIntentStartedEvents to %d", accIntentStartedEvents));
  }

  private void decAccIntentStartedEvents() {
    if (accIntentStartedEvents <= 0) {
      throw new AssertionError("The number of unknowns cannot be negative");
    }
    if(accIntentStartedEvents == 1) {
      state = State.INIT;
    }
    --accIntentStartedEvents;
    Log.d(TAG,
        String.format("dec AccIntentStartedEvents to %d", accIntentStartedEvents));
  }

  private void logWarningWithStackTrace(String log) {
    if (DEBUG) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      new Throwable("EventSequenceValidator#getStackTrace").printStackTrace(pw);
      Log.wtf(TAG, String.format("%s\n%s", log, sw));
    }
  }
}
