/*
 * Copyright (C) 2022 The Android Open Source Project
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


#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <androidfw/Util.h>

#include "TestHelpers.h"

#include <androidfw/ResourceTimer.h>

namespace android {

namespace {

// Create a reading in us.  This is a convenience function to avoid multiplying by 1000
// everywhere.
unsigned int US(int us) {
  return us * 1000;
}

}

TEST(ResourceTimerTest, TimerBasic) {
  ResourceTimer::Timer timer;
  ASSERT_THAT(timer.count, 0);
  ASSERT_THAT(timer.total, 0);

  for (int i = 1; i <= 100; i++) {
    timer.record(US(i));
  }
  ASSERT_THAT(timer.count, 100);
  ASSERT_THAT(timer.total, US((101 * 100)/2));
  ASSERT_THAT(timer.mintime, US(1));
  ASSERT_THAT(timer.maxtime, US(100));
  ASSERT_THAT(timer.pvalues.p50.floor, 0);
  ASSERT_THAT(timer.pvalues.p50.nominal, 0);
  ASSERT_THAT(timer.largest[0], US(100));
  ASSERT_THAT(timer.largest[1], US(99));
  ASSERT_THAT(timer.largest[2], US(98));
  ASSERT_THAT(timer.largest[3], US(97));
  ASSERT_THAT(timer.largest[4], US(96));
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(49));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(50));
  ASSERT_THAT(timer.pvalues.p90.floor, US(89));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(90));
  ASSERT_THAT(timer.pvalues.p95.floor, US(94));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(95));
  ASSERT_THAT(timer.pvalues.p99.floor, US(98));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(99));

  // Test reset functionality.  All values should be zero after the reset.  Computing pvalues
  // after the result should also yield zeros.
  timer.reset();
  ASSERT_THAT(timer.count, 0);
  ASSERT_THAT(timer.total, 0);
  ASSERT_THAT(timer.mintime, US(0));
  ASSERT_THAT(timer.maxtime, US(0));
  ASSERT_THAT(timer.pvalues.p50.floor, US(0));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(0));
  ASSERT_THAT(timer.largest[0], US(0));
  ASSERT_THAT(timer.largest[1], US(0));
  ASSERT_THAT(timer.largest[2], US(0));
  ASSERT_THAT(timer.largest[3], US(0));
  ASSERT_THAT(timer.largest[4], US(0));
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(0));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(0));
  ASSERT_THAT(timer.pvalues.p90.floor, US(0));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(0));
  ASSERT_THAT(timer.pvalues.p95.floor, US(0));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(0));
  ASSERT_THAT(timer.pvalues.p99.floor, US(0));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(0));

  // Test again, adding elements in reverse.
  for (int i = 100; i >= 1; i--) {
    timer.record(US(i));
  }
  ASSERT_THAT(timer.count, 100);
  ASSERT_THAT(timer.total, US((101 * 100)/2));
  ASSERT_THAT(timer.mintime, US(1));
  ASSERT_THAT(timer.maxtime, US(100));
  ASSERT_THAT(timer.pvalues.p50.floor, 0);
  ASSERT_THAT(timer.pvalues.p50.nominal, 0);
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(49));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(50));
  ASSERT_THAT(timer.pvalues.p90.floor, US(89));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(90));
  ASSERT_THAT(timer.pvalues.p95.floor, US(94));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(95));
  ASSERT_THAT(timer.pvalues.p99.floor, US(98));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(99));
  ASSERT_THAT(timer.largest[0], US(100));
  ASSERT_THAT(timer.largest[1], US(99));
  ASSERT_THAT(timer.largest[2], US(98));
  ASSERT_THAT(timer.largest[3], US(97));
  ASSERT_THAT(timer.largest[4], US(96));
}

TEST(ResourceTimerTest, TimerLimit) {
  ResourceTimer::Timer timer;

  // Event truncation means that a time of 1050us will be stored in the 1000us
  // bucket.  Since there is a single event, all p-values lie in the same range.
  timer.record(US(1050));
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(900));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(1000));
  ASSERT_THAT(timer.pvalues.p90.floor, US(900));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(1000));
  ASSERT_THAT(timer.pvalues.p95.floor, US(900));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(1000));
  ASSERT_THAT(timer.pvalues.p99.floor, US(900));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(1000));
}

TEST(ResourceTimerTest, TimerCopy) {
  ResourceTimer::Timer source;
  for (int i = 1; i <= 100; i++) {
    source.record(US(i));
  }
  ResourceTimer::Timer timer;
  ResourceTimer::Timer::copy(timer, source, true);
  ASSERT_THAT(source.count, 0);
  ASSERT_THAT(source.total, 0);
  // compute() is not normally be called on a reset timer, but it should work and it should return
  // all zeros.
  source.compute();
  ASSERT_THAT(source.pvalues.p50.floor, US(0));
  ASSERT_THAT(source.pvalues.p50.nominal, US(0));
  ASSERT_THAT(source.pvalues.p90.floor, US(0));
  ASSERT_THAT(source.pvalues.p90.nominal, US(0));
  ASSERT_THAT(source.pvalues.p95.floor, US(0));
  ASSERT_THAT(source.pvalues.p95.nominal, US(0));
  ASSERT_THAT(source.pvalues.p99.floor, US(0));
  ASSERT_THAT(source.pvalues.p99.nominal, US(0));
  ASSERT_THAT(source.largest[0], US(0));
  ASSERT_THAT(source.largest[1], US(0));
  ASSERT_THAT(source.largest[2], US(0));
  ASSERT_THAT(source.largest[3], US(0));
  ASSERT_THAT(source.largest[4], US(0));

  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(49));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(50));
  ASSERT_THAT(timer.pvalues.p90.floor, US(89));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(90));
  ASSERT_THAT(timer.pvalues.p95.floor, US(94));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(95));
  ASSERT_THAT(timer.pvalues.p99.floor, US(98));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(99));
  ASSERT_THAT(timer.largest[0], US(100));
  ASSERT_THAT(timer.largest[1], US(99));
  ASSERT_THAT(timer.largest[2], US(98));
  ASSERT_THAT(timer.largest[3], US(97));
  ASSERT_THAT(timer.largest[4], US(96));

  // Call compute a second time.  The values must be the same.
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(49));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(50));
  ASSERT_THAT(timer.pvalues.p90.floor, US(89));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(90));
  ASSERT_THAT(timer.pvalues.p95.floor, US(94));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(95));
  ASSERT_THAT(timer.pvalues.p99.floor, US(98));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(99));
  ASSERT_THAT(timer.largest[0], US(100));
  ASSERT_THAT(timer.largest[1], US(99));
  ASSERT_THAT(timer.largest[2], US(98));
  ASSERT_THAT(timer.largest[3], US(97));
  ASSERT_THAT(timer.largest[4], US(96));

  // Modify the source.  If timer and source share histogram arrays, this will introduce an
  // error.
  for (int i = 1; i <= 100; i++) {
    source.record(US(i));
  }
  // Call compute a third time.  The values must be the same.
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(49));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(50));
  ASSERT_THAT(timer.pvalues.p90.floor, US(89));
  ASSERT_THAT(timer.pvalues.p90.nominal, US(90));
  ASSERT_THAT(timer.pvalues.p95.floor, US(94));
  ASSERT_THAT(timer.pvalues.p95.nominal, US(95));
  ASSERT_THAT(timer.pvalues.p99.floor, US(98));
  ASSERT_THAT(timer.pvalues.p99.nominal, US(99));
  ASSERT_THAT(timer.largest[0], US(100));
  ASSERT_THAT(timer.largest[1], US(99));
  ASSERT_THAT(timer.largest[2], US(98));
  ASSERT_THAT(timer.largest[3], US(97));
  ASSERT_THAT(timer.largest[4], US(96));
}

// Verify that if too many oversize entries are reported, the percentile values cannot be computed
// and are set to zero.
TEST(ResourceTimerTest, TimerOversize) {
  static const int oversize = US(2 * 1000 * 1000);

  ResourceTimer::Timer timer;
  for (int i = 1; i <= 100; i++) {
    timer.record(US(i));
  }

  // Insert enough oversize values to invalidate the p90, p95, and p99 percentiles.  The p50 is
  // still computable.
  for (int i = 1; i <= 50; i++) {
    timer.record(oversize);
  }
  ASSERT_THAT(timer.largest[0], oversize);
  ASSERT_THAT(timer.largest[1], oversize);
  ASSERT_THAT(timer.largest[2], oversize);
  ASSERT_THAT(timer.largest[3], oversize);
  ASSERT_THAT(timer.largest[4], oversize);
  timer.compute();
  ASSERT_THAT(timer.pvalues.p50.floor, US(74));
  ASSERT_THAT(timer.pvalues.p50.nominal, US(75));
  ASSERT_THAT(timer.pvalues.p90.floor, 0);
  ASSERT_THAT(timer.pvalues.p90.nominal, 0);
  ASSERT_THAT(timer.pvalues.p95.floor, 0);
  ASSERT_THAT(timer.pvalues.p95.nominal, 0);
  ASSERT_THAT(timer.pvalues.p99.floor, 0);
  ASSERT_THAT(timer.pvalues.p99.nominal, 0);
}


}  // namespace android
