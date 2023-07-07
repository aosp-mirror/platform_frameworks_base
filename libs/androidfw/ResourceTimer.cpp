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

#include <unistd.h>
#include <string.h>

#include <map>
#include <atomic>

#include <utils/Log.h>
#include <androidfw/ResourceTimer.h>

// The following block allows compilation on windows, which does not have getuid().
#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#define getuid() (getUidWindows_)
#endif

namespace android {

namespace {

#ifdef _WIN32
// A temporary to confuse lint into thinking that getuid() on windows might return something other
// than zero.
int getUidWindows_ = 0;
#endif

// The number of nanoseconds in a microsecond.
static const unsigned int US = 1000;
// The number of nanoseconds in a second.
static const unsigned int S = 1000 * 1000 * 1000;

// Return the difference between two timespec values.  The difference is in nanoseconds.  If the
// return value would exceed 2s (2^31 nanoseconds) then UINT_MAX is returned.
unsigned int diffInNs(timespec const &a, timespec const &b) {
  timespec r = { 0, 0 };
  r.tv_nsec = a.tv_nsec - b.tv_nsec;
  if (r.tv_nsec < 0) {
    r.tv_sec = -1;
    r.tv_nsec += S;
  }
  r.tv_sec = r.tv_sec + (a.tv_sec - b.tv_sec);
  if (r.tv_sec > 2) return UINT_MAX;
  unsigned int result = (r.tv_sec * S) + r.tv_nsec;
  if (result > 2 * S) return UINT_MAX;
  return result;
}

}

ResourceTimer::ResourceTimer(Counter api)
    : active_(enabled_.load()),
      api_(api) {
  if (active_) {
    clock_gettime(CLOCK_MONOTONIC, &start_);
  }
}

ResourceTimer::~ResourceTimer() {
  record();
}

void ResourceTimer::enable() {
  if (!enabled_.load()) counter_ = new GuardedTimer[ResourceTimer::counterSize];
  enabled_.store(true);
}

void ResourceTimer::cancel() {
  active_ = false;
}

void ResourceTimer::record() {
  if (!active_) return;

  struct timespec end;
  clock_gettime(CLOCK_MONOTONIC, &end);
  // Get the difference in microseconds.
  const unsigned int ticks = diffInNs(end, start_);
  ScopedTimer t(counter_[toIndex(api_)]);
  t->record(ticks);
  active_ = false;
}

bool ResourceTimer::copy(int counter, Timer &dst, bool reset) {
  ScopedTimer t(counter_[counter]);
  if (t->count == 0) {
    dst.reset();
    if (reset) t->reset();
    return false;
  }
  Timer::copy(dst, *t, reset);
  return true;
}

void ResourceTimer::reset() {
  for (int i = 0; i < counterSize; i++) {
    ScopedTimer t(counter_[i]);
    t->reset();
  }
}

ResourceTimer::Timer::Timer() {
  // Ensure newly-created objects are zeroed.
  memset(buckets, 0, sizeof(buckets));
  reset();
}

ResourceTimer::Timer::~Timer() {
  for (int d = 0; d < MaxDimension; d++) {
    delete[] buckets[d];
  }
}

void ResourceTimer::Timer::freeBuckets() {
  for (int d = 0; d < MaxDimension; d++) {
    delete[] buckets[d];
    buckets[d] = 0;
  }
}

void ResourceTimer::Timer::reset() {
  count = total = mintime = maxtime = 0;
  memset(largest, 0, sizeof(largest));
  memset(&pvalues, 0, sizeof(pvalues));
  // Zero the histogram, keeping any allocated dimensions.
  for (int d = 0; d < MaxDimension; d++) {
    if (buckets[d] != 0) memset(buckets[d], 0, sizeof(int) * MaxBuckets);
  }
}

void ResourceTimer::Timer::copy(Timer &dst, Timer &src, bool reset) {
  dst.freeBuckets();
  dst = src;
  // Clean up the histograms.
  if (reset) {
    // Do NOT free the src buckets because they being used by dst.
    memset(src.buckets, 0, sizeof(src.buckets));
    src.reset();
  } else {
    for (int d = 0; d < MaxDimension; d++) {
      if (src.buckets[d] != nullptr) {
        dst.buckets[d] = new int[MaxBuckets];
        memcpy(dst.buckets[d], src.buckets[d], sizeof(int) * MaxBuckets);
      }
    }
  }
}

void ResourceTimer::Timer::record(int ticks) {
  // Record that the event happened.
  count++;

  total += ticks;
  if (mintime == 0 || ticks < mintime) mintime = ticks;
  if (ticks > maxtime) maxtime = ticks;

  // Do not add oversized events to the histogram.
  if (ticks != UINT_MAX) {
    for (int d = 0; d < MaxDimension; d++) {
      if (ticks < range[d]) {
        if (buckets[d] == 0) {
          buckets[d] = new int[MaxBuckets];
          memset(buckets[d], 0, sizeof(int) * MaxBuckets);
        }
        if (ticks < width[d]) {
          // Special case: never write to bucket 0 because it complicates the percentile logic.
          // However, this is always the smallest possible value to it is very unlikely to ever
          // affect any of the percentile results.
          buckets[d][1]++;
        } else {
          buckets[d][ticks / width[d]]++;
        }
        break;
      }
    }
  }

  // The list of largest times is sorted with the biggest value at index 0 and the smallest at
  // index MaxLargest-1.  The incoming tick count should be added to the array only if it is
  // larger than the current value at MaxLargest-1.
  if (ticks > largest[Timer::MaxLargest-1]) {
    for (size_t i = 0; i < Timer::MaxLargest; i++) {
      if (ticks > largest[i]) {
        if (i < Timer::MaxLargest-1) {
          for (size_t j = Timer::MaxLargest - 1; j > i; j--) {
            largest[j] = largest[j-1];
          }
        }
        largest[i] = ticks;
        break;
      }
    }
  }
}

void ResourceTimer::Timer::Percentile::compute(
    int cumulative, int current, int count, int width, int time) {
  nominal = time;
  nominal_actual = (cumulative * 100) / count;
  floor = nominal - width;
  floor_actual = ((cumulative - current) * 100) / count;
}

void ResourceTimer::Timer::compute() {
  memset(&pvalues, 0, sizeof(pvalues));

  float l50 = count / 2.0;
  float l90 = (count * 9.0) / 10.0;
  float l95 = (count * 95.0) / 100.0;
  float l99 = (count * 99.0) / 100.0;

  int sum = 0;
  for (int d = 0; d < MaxDimension; d++) {
    if (buckets[d] == 0) continue;
    for (int j = 0; j < MaxBuckets && sum < count; j++) {
      // Empty buckets don't contribute to the answers.  Skip them.
      if (buckets[d][j] == 0) continue;
      sum += buckets[d][j];
      // A word on indexing.  j is never zero in the following lines.  buckets[0][0] corresponds
      // to a delay of 0us, which cannot happen.  buckets[n][0], for n > 0 overlaps a value in
      // buckets[n-1], and the code would have stopped there.
      if (sum >= l50 && pvalues.p50.nominal == 0) {
        pvalues.p50.compute(sum, buckets[d][j], count, width[d], j * width[d]);
      }
      if (sum >= l90 && pvalues.p90.nominal == 0) {
        pvalues.p90.compute(sum, buckets[d][j], count, width[d], j * width[d]);
      }
      if (sum >= l95 && pvalues.p95.nominal == 0) {
        pvalues.p95.compute(sum, buckets[d][j], count, width[d], j * width[d]);
      }
      if (sum >= l99 && pvalues.p99.nominal == 0) {
        pvalues.p99.compute(sum, buckets[d][j], count, width[d], j * width[d]);
      }
    }
  }
}

char const *ResourceTimer::toString(ResourceTimer::Counter counter) {
  switch (counter) {
    case Counter::GetResourceValue:
      return "GetResourceValue";
    case Counter::RetrieveAttributes:
      return "RetrieveAttributes";
  };
  return "Unknown";
}

std::atomic<bool> ResourceTimer::enabled_(false);
std::atomic<ResourceTimer::GuardedTimer *> ResourceTimer::counter_(nullptr);

const int ResourceTimer::Timer::range[] = { 100 * US, 1000 * US, 10*1000 * US, 100*1000 * US };
const int ResourceTimer::Timer::width[] = {   1 * US,   10 * US,     100 * US,     1000 * US };


}  // namespace android
