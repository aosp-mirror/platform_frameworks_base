/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.google.android.startop.iorap

import android.content.Intent;
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.test.filters.SmallTest
import com.google.android.startop.iorap.AppLaunchEvent;
import com.google.android.startop.iorap.AppLaunchEvent.ActivityLaunched
import com.google.android.startop.iorap.AppLaunchEvent.ActivityLaunchCancelled
import com.google.android.startop.iorap.AppLaunchEvent.ActivityLaunchFinished
import com.google.android.startop.iorap.AppLaunchEvent.IntentStarted;
import com.google.android.startop.iorap.AppLaunchEvent.IntentFailed;
import com.google.android.startop.iorap.AppLaunchEvent.ReportFullyDrawn
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized


/**
 * Basic unit tests to test all of the [AppLaunchEvent]s in [com.google.android.startop.iorap].
 */
@SmallTest
class AppLaunchEventTest {
  /**
   * Test for IntentStarted.
   */
  @Test
  fun testIntentStarted() {
    var intent = Intent()
    val valid = IntentStarted(/* sequenceId= */2L, intent, /* timestampNs= */ 1L)
    val copy = IntentStarted(/* sequenceId= */2L, intent, /* timestampNs= */ 1L)
    val noneCopy1 = IntentStarted(/* sequenceId= */1L, intent, /* timestampNs= */ 1L)
    val noneCopy2 = IntentStarted(/* sequenceId= */2L, intent, /* timestampNs= */ 2L)
    val noneCopy3 = IntentStarted(/* sequenceId= */2L, Intent(), /* timestampNs= */ 1L)

    // equals(Object other)
    assertThat(valid).isEqualTo(copy)
    assertThat(valid).isNotEqualTo(noneCopy1)
    assertThat(valid).isNotEqualTo(noneCopy2)
    assertThat(valid).isNotEqualTo(noneCopy3)

    // test toString()
    val result = valid.toString()
    assertThat(result).isEqualTo("IntentStarted{sequenceId=2, intent=Intent {  } , timestampNs=1}")
  }

  /**
   * Test for IntentFailed.
   */
  @Test
  fun testIntentFailed() {
    val valid = IntentFailed(/* sequenceId= */2L)
    val copy = IntentFailed(/* sequenceId= */2L)
    val noneCopy = IntentFailed(/* sequenceId= */1L)

    // equals(Object other)
    assertThat(valid).isEqualTo(copy)
    assertThat(valid).isNotEqualTo(noneCopy)

    // test toString()
    val result = valid.toString()
    assertThat(result).isEqualTo("IntentFailed{sequenceId=2}")
  }

  /**
   * Test for ActivityLaunched.
   */
  @Test
  fun testActivityLaunched() {
    //var activityRecord =
    val valid = ActivityLaunched(/* sequenceId= */2L, "test".toByteArray(),
      /* temperature= */ 0)
    val copy = ActivityLaunched(/* sequenceId= */2L, "test".toByteArray(),
      /* temperature= */ 0)
    val noneCopy1 = ActivityLaunched(/* sequenceId= */1L, "test".toByteArray(),
      /* temperature= */ 0)
    val noneCopy2 = ActivityLaunched(/* sequenceId= */1L, "test".toByteArray(),
      /* temperature= */ 1)
    val noneCopy3 = ActivityLaunched(/* sequenceId= */1L, "test1".toByteArray(),
      /* temperature= */ 0)

    // equals(Object other)
    assertThat(valid).isEqualTo(copy)
    assertThat(valid).isNotEqualTo(noneCopy1)
    assertThat(valid).isNotEqualTo(noneCopy2)
    assertThat(valid).isNotEqualTo(noneCopy3)

    // test toString()
    val result = valid.toString()
    assertThat(result).isEqualTo("ActivityLaunched{sequenceId=2, test, temperature=0}")
  }


  /**
   * Test for ActivityLaunchFinished.
   */
  @Test
  fun testActivityLaunchFinished() {
    val valid = ActivityLaunchFinished(/* sequenceId= */2L, "test".toByteArray(),
      /* timestampNs= */ 1L)
    val copy = ActivityLaunchFinished(/* sequenceId= */2L, "test".toByteArray(),
      /* timestampNs= */ 1L)
    val noneCopy1 = ActivityLaunchFinished(/* sequenceId= */1L, "test".toByteArray(),
      /* timestampNs= */ 1L)
    val noneCopy2 = ActivityLaunchFinished(/* sequenceId= */1L, "test".toByteArray(),
      /* timestampNs= */ 2L)
    val noneCopy3 = ActivityLaunchFinished(/* sequenceId= */2L, "test1".toByteArray(),
      /* timestampNs= */ 1L)

    // equals(Object other)
    assertThat(valid).isEqualTo(copy)
    assertThat(valid).isNotEqualTo(noneCopy1)
    assertThat(valid).isNotEqualTo(noneCopy2)
    assertThat(valid).isNotEqualTo(noneCopy3)

    // test toString()
    val result = valid.toString()
    assertThat(result).isEqualTo("ActivityLaunchFinished{sequenceId=2, test, timestampNs=1}")
  }

  /**
   * Test for ActivityLaunchCancelled.
   */
  @Test
  fun testActivityLaunchCancelled() {
    val valid = ActivityLaunchCancelled(/* sequenceId= */2L, "test".toByteArray())
    val copy = ActivityLaunchCancelled(/* sequenceId= */2L, "test".toByteArray())
    val noneCopy1 = ActivityLaunchCancelled(/* sequenceId= */1L, "test".toByteArray())
    val noneCopy2 = ActivityLaunchCancelled(/* sequenceId= */2L, "test1".toByteArray())

    // equals(Object other)
    assertThat(valid).isEqualTo(copy)
    assertThat(valid).isNotEqualTo(noneCopy1)
    assertThat(valid).isNotEqualTo(noneCopy2)

    // test toString()
    val result = valid.toString()
    assertThat(result).isEqualTo("ActivityLaunchCancelled{sequenceId=2, test}")
  }

  /**
   * Test for ReportFullyDrawn.
   */
  @Test
  fun testReportFullyDrawn() {
    val valid = ReportFullyDrawn(/* sequenceId= */2L, "test".toByteArray(), /* timestampNs= */ 1L)
    val copy = ReportFullyDrawn(/* sequenceId= */2L, "test".toByteArray(), /* timestampNs= */ 1L)
    val noneCopy1 = ReportFullyDrawn(/* sequenceId= */1L, "test".toByteArray(),
      /* timestampNs= */ 1L)
    val noneCopy2 = ReportFullyDrawn(/* sequenceId= */1L, "test".toByteArray(),
      /* timestampNs= */ 1L)
    val noneCopy3 = ReportFullyDrawn(/* sequenceId= */2L, "test1".toByteArray(),
      /* timestampNs= */ 1L)

    // equals(Object other)
    assertThat(valid).isEqualTo(copy)
    assertThat(valid).isNotEqualTo(noneCopy1)
    assertThat(valid).isNotEqualTo(noneCopy2)
    assertThat(valid).isNotEqualTo(noneCopy3)

    // test toString()
    val result = valid.toString()
    assertThat(result).isEqualTo("ReportFullyDrawn{sequenceId=2, test, timestampNs=1}")
  }
}
