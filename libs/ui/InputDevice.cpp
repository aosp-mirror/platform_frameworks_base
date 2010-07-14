//
// Copyright 2010 The Android Open Source Project
//
// The input reader.
//
#define LOG_TAG "InputDevice"

//#define LOG_NDEBUG 0

// Log debug messages for each raw event received from the EventHub.
#define DEBUG_RAW_EVENTS 0

// Log debug messages about touch screen filtering hacks.
#define DEBUG_HACKS 0

// Log debug messages about virtual key processing.
#define DEBUG_VIRTUAL_KEYS 0

// Log debug messages about pointers.
#define DEBUG_POINTERS 0

// Log debug messages about pointer assignment calculations.
#define DEBUG_POINTER_ASSIGNMENT 0

#include <cutils/log.h>
#include <ui/InputDevice.h>

#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>

/* Slop distance for jumpy pointer detection.
 * The vertical range of the screen divided by this is our epsilon value. */
#define JUMPY_EPSILON_DIVISOR 212

/* Number of jumpy points to drop for touchscreens that need it. */
#define JUMPY_TRANSITION_DROPS 3
#define JUMPY_DROP_LIMIT 3

/* Maximum squared distance for averaging.
 * If moving farther than this, turn of averaging to avoid lag in response. */
#define AVERAGING_DISTANCE_LIMIT (75 * 75)


namespace android {

// --- Static Functions ---

template<typename T>
inline static T abs(const T& value) {
    return value < 0 ? - value : value;
}

template<typename T>
inline static T min(const T& a, const T& b) {
    return a < b ? a : b;
}

template<typename T>
inline static void swap(T& a, T& b) {
    T temp = a;
    a = b;
    b = temp;
}


// --- InputDevice ---

InputDevice::InputDevice(int32_t id, uint32_t classes, String8 name) :
    id(id), classes(classes), name(name), ignored(false) {
}

void InputDevice::reset() {
    if (isKeyboard()) {
        keyboard.reset();
    }

    if (isTrackball()) {
        trackball.reset();
    }

    if (isMultiTouchScreen()) {
        multiTouchScreen.reset();
    } else if (isSingleTouchScreen()) {
        singleTouchScreen.reset();
    }

    if (isTouchScreen()) {
        touchScreen.reset();
    }
}


// --- InputDevice::TouchData ---

void InputDevice::TouchData::copyFrom(const TouchData& other) {
    pointerCount = other.pointerCount;
    idBits = other.idBits;

    for (uint32_t i = 0; i < pointerCount; i++) {
        pointers[i] = other.pointers[i];
        idToIndex[i] = other.idToIndex[i];
    }
}


// --- InputDevice::KeyboardState ---

void InputDevice::KeyboardState::reset() {
    current.metaState = META_NONE;
    current.downTime = 0;
}


// --- InputDevice::TrackballState ---

void InputDevice::TrackballState::reset() {
    accumulator.clear();
    current.down = false;
    current.downTime = 0;
}


// --- InputDevice::TouchScreenState ---

void InputDevice::TouchScreenState::reset() {
    lastTouch.clear();
    downTime = 0;
    currentVirtualKey.status = CurrentVirtualKeyState::STATUS_UP;

    for (uint32_t i = 0; i < MAX_POINTERS; i++) {
        averagingTouchFilter.historyStart[i] = 0;
        averagingTouchFilter.historyEnd[i] = 0;
    }

    jumpyTouchFilter.jumpyPointsDropped = 0;
}

struct PointerDistanceHeapElement {
    uint32_t currentPointerIndex : 8;
    uint32_t lastPointerIndex : 8;
    uint64_t distance : 48; // squared distance
};

void InputDevice::TouchScreenState::calculatePointerIds() {
    uint32_t currentPointerCount = currentTouch.pointerCount;
    uint32_t lastPointerCount = lastTouch.pointerCount;

    if (currentPointerCount == 0) {
        // No pointers to assign.
        currentTouch.idBits.clear();
    } else if (lastPointerCount == 0) {
        // All pointers are new.
        currentTouch.idBits.clear();
        for (uint32_t i = 0; i < currentPointerCount; i++) {
            currentTouch.pointers[i].id = i;
            currentTouch.idToIndex[i] = i;
            currentTouch.idBits.markBit(i);
        }
    } else if (currentPointerCount == 1 && lastPointerCount == 1) {
        // Only one pointer and no change in count so it must have the same id as before.
        uint32_t id = lastTouch.pointers[0].id;
        currentTouch.pointers[0].id = id;
        currentTouch.idToIndex[id] = 0;
        currentTouch.idBits.value = BitSet32::valueForBit(id);
    } else {
        // General case.
        // We build a heap of squared euclidean distances between current and last pointers
        // associated with the current and last pointer indices.  Then, we find the best
        // match (by distance) for each current pointer.
        PointerDistanceHeapElement heap[MAX_POINTERS * MAX_POINTERS];

        uint32_t heapSize = 0;
        for (uint32_t currentPointerIndex = 0; currentPointerIndex < currentPointerCount;
                currentPointerIndex++) {
            for (uint32_t lastPointerIndex = 0; lastPointerIndex < lastPointerCount;
                    lastPointerIndex++) {
                int64_t deltaX = currentTouch.pointers[currentPointerIndex].x
                        - lastTouch.pointers[lastPointerIndex].x;
                int64_t deltaY = currentTouch.pointers[currentPointerIndex].y
                        - lastTouch.pointers[lastPointerIndex].y;

                uint64_t distance = uint64_t(deltaX * deltaX + deltaY * deltaY);

                // Insert new element into the heap (sift up).
                heap[heapSize].currentPointerIndex = currentPointerIndex;
                heap[heapSize].lastPointerIndex = lastPointerIndex;
                heap[heapSize].distance = distance;
                heapSize += 1;
            }
        }

        // Heapify
        for (uint32_t startIndex = heapSize / 2; startIndex != 0; ) {
            startIndex -= 1;
            for (uint32_t parentIndex = startIndex; ;) {
                uint32_t childIndex = parentIndex * 2 + 1;
                if (childIndex >= heapSize) {
                    break;
                }

                if (childIndex + 1 < heapSize
                        && heap[childIndex + 1].distance < heap[childIndex].distance) {
                    childIndex += 1;
                }

                if (heap[parentIndex].distance <= heap[childIndex].distance) {
                    break;
                }

                swap(heap[parentIndex], heap[childIndex]);
                parentIndex = childIndex;
            }
        }

#if DEBUG_POINTER_ASSIGNMENT
        LOGD("calculatePointerIds - initial distance min-heap: size=%d", heapSize);
        for (size_t i = 0; i < heapSize; i++) {
            LOGD("  heap[%d]: cur=%d, last=%d, distance=%lld",
                    i, heap[i].currentPointerIndex, heap[i].lastPointerIndex,
                    heap[i].distance);
        }
#endif

        // Pull matches out by increasing order of distance.
        // To avoid reassigning pointers that have already been matched, the loop keeps track
        // of which last and current pointers have been matched using the matchedXXXBits variables.
        // It also tracks the used pointer id bits.
        BitSet32 matchedLastBits(0);
        BitSet32 matchedCurrentBits(0);
        BitSet32 usedIdBits(0);
        bool first = true;
        for (uint32_t i = min(currentPointerCount, lastPointerCount); i > 0; i--) {
            for (;;) {
                if (first) {
                    // The first time through the loop, we just consume the root element of
                    // the heap (the one with smallest distance).
                    first = false;
                } else {
                    // Previous iterations consumed the root element of the heap.
                    // Pop root element off of the heap (sift down).
                    heapSize -= 1;
                    assert(heapSize > 0);

                    // Sift down.
                    heap[0] = heap[heapSize];
                    for (uint32_t parentIndex = 0; ;) {
                        uint32_t childIndex = parentIndex * 2 + 1;
                        if (childIndex >= heapSize) {
                            break;
                        }

                        if (childIndex + 1 < heapSize
                                && heap[childIndex + 1].distance < heap[childIndex].distance) {
                            childIndex += 1;
                        }

                        if (heap[parentIndex].distance <= heap[childIndex].distance) {
                            break;
                        }

                        swap(heap[parentIndex], heap[childIndex]);
                        parentIndex = childIndex;
                    }

#if DEBUG_POINTER_ASSIGNMENT
                    LOGD("calculatePointerIds - reduced distance min-heap: size=%d", heapSize);
                    for (size_t i = 0; i < heapSize; i++) {
                        LOGD("  heap[%d]: cur=%d, last=%d, distance=%lld",
                                i, heap[i].currentPointerIndex, heap[i].lastPointerIndex,
                                heap[i].distance);
                    }
#endif
                }

                uint32_t currentPointerIndex = heap[0].currentPointerIndex;
                if (matchedCurrentBits.hasBit(currentPointerIndex)) continue; // already matched

                uint32_t lastPointerIndex = heap[0].lastPointerIndex;
                if (matchedLastBits.hasBit(lastPointerIndex)) continue; // already matched

                matchedCurrentBits.markBit(currentPointerIndex);
                matchedLastBits.markBit(lastPointerIndex);

                uint32_t id = lastTouch.pointers[lastPointerIndex].id;
                currentTouch.pointers[currentPointerIndex].id = id;
                currentTouch.idToIndex[id] = currentPointerIndex;
                usedIdBits.markBit(id);

#if DEBUG_POINTER_ASSIGNMENT
                LOGD("calculatePointerIds - matched: cur=%d, last=%d, id=%d, distance=%lld",
                        lastPointerIndex, currentPointerIndex, id, heap[0].distance);
#endif
                break;
            }
        }

        // Assign fresh ids to new pointers.
        if (currentPointerCount > lastPointerCount) {
            for (uint32_t i = currentPointerCount - lastPointerCount; ;) {
                uint32_t currentPointerIndex = matchedCurrentBits.firstUnmarkedBit();
                uint32_t id = usedIdBits.firstUnmarkedBit();

                currentTouch.pointers[currentPointerIndex].id = id;
                currentTouch.idToIndex[id] = currentPointerIndex;
                usedIdBits.markBit(id);

#if DEBUG_POINTER_ASSIGNMENT
                LOGD("calculatePointerIds - assigned: cur=%d, id=%d",
                        currentPointerIndex, id);
#endif

                if (--i == 0) break; // done
                matchedCurrentBits.markBit(currentPointerIndex);
            }
        }

        // Fix id bits.
        currentTouch.idBits = usedIdBits;
    }
}

/* Special hack for devices that have bad screen data: if one of the
 * points has moved more than a screen height from the last position,
 * then drop it. */
bool InputDevice::TouchScreenState::applyBadTouchFilter() {
    // This hack requires valid axis parameters.
    if (! parameters.yAxis.valid) {
        return false;
    }

    uint32_t pointerCount = currentTouch.pointerCount;

    // Nothing to do if there are no points.
    if (pointerCount == 0) {
        return false;
    }

    // Don't do anything if a finger is going down or up.  We run
    // here before assigning pointer IDs, so there isn't a good
    // way to do per-finger matching.
    if (pointerCount != lastTouch.pointerCount) {
        return false;
    }

    // We consider a single movement across more than a 7/16 of
    // the long size of the screen to be bad.  This was a magic value
    // determined by looking at the maximum distance it is feasible
    // to actually move in one sample.
    int32_t maxDeltaY = parameters.yAxis.range * 7 / 16;

    // XXX The original code in InputDevice.java included commented out
    //     code for testing the X axis.  Note that when we drop a point
    //     we don't actually restore the old X either.  Strange.
    //     The old code also tries to track when bad points were previously
    //     detected but it turns out that due to the placement of a "break"
    //     at the end of the loop, we never set mDroppedBadPoint to true
    //     so it is effectively dead code.
    // Need to figure out if the old code is busted or just overcomplicated
    // but working as intended.

    // Look through all new points and see if any are farther than
    // acceptable from all previous points.
    for (uint32_t i = pointerCount; i-- > 0; ) {
        int32_t y = currentTouch.pointers[i].y;
        int32_t closestY = INT_MAX;
        int32_t closestDeltaY = 0;

#if DEBUG_HACKS
        LOGD("BadTouchFilter: Looking at next point #%d: y=%d", i, y);
#endif

        for (uint32_t j = pointerCount; j-- > 0; ) {
            int32_t lastY = lastTouch.pointers[j].y;
            int32_t deltaY = abs(y - lastY);

#if DEBUG_HACKS
            LOGD("BadTouchFilter: Comparing with last point #%d: y=%d deltaY=%d",
                    j, lastY, deltaY);
#endif

            if (deltaY < maxDeltaY) {
                goto SkipSufficientlyClosePoint;
            }
            if (deltaY < closestDeltaY) {
                closestDeltaY = deltaY;
                closestY = lastY;
            }
        }

        // Must not have found a close enough match.
#if DEBUG_HACKS
        LOGD("BadTouchFilter: Dropping bad point #%d: newY=%d oldY=%d deltaY=%d maxDeltaY=%d",
                i, y, closestY, closestDeltaY, maxDeltaY);
#endif

        currentTouch.pointers[i].y = closestY;
        return true; // XXX original code only corrects one point

    SkipSufficientlyClosePoint: ;
    }

    // No change.
    return false;
}

/* Special hack for devices that have bad screen data: drop points where
 * the coordinate value for one axis has jumped to the other pointer's location.
 */
bool InputDevice::TouchScreenState::applyJumpyTouchFilter() {
    // This hack requires valid axis parameters.
    if (! parameters.yAxis.valid) {
        return false;
    }

    uint32_t pointerCount = currentTouch.pointerCount;
    if (lastTouch.pointerCount != pointerCount) {
#if DEBUG_HACKS
        LOGD("JumpyTouchFilter: Different pointer count %d -> %d",
                lastTouch.pointerCount, pointerCount);
        for (uint32_t i = 0; i < pointerCount; i++) {
            LOGD("  Pointer %d (%d, %d)", i,
                    currentTouch.pointers[i].x, currentTouch.pointers[i].y);
        }
#endif

        if (jumpyTouchFilter.jumpyPointsDropped < JUMPY_TRANSITION_DROPS) {
            if (lastTouch.pointerCount == 1 && pointerCount == 2) {
                // Just drop the first few events going from 1 to 2 pointers.
                // They're bad often enough that they're not worth considering.
                currentTouch.pointerCount = 1;
                jumpyTouchFilter.jumpyPointsDropped += 1;

#if DEBUG_HACKS
                LOGD("JumpyTouchFilter: Pointer 2 dropped");
#endif
                return true;
            } else if (lastTouch.pointerCount == 2 && pointerCount == 1) {
                // The event when we go from 2 -> 1 tends to be messed up too
                currentTouch.pointerCount = 2;
                currentTouch.pointers[0] = lastTouch.pointers[0];
                currentTouch.pointers[1] = lastTouch.pointers[1];
                jumpyTouchFilter.jumpyPointsDropped += 1;

#if DEBUG_HACKS
                for (int32_t i = 0; i < 2; i++) {
                    LOGD("JumpyTouchFilter: Pointer %d replaced (%d, %d)", i,
                            currentTouch.pointers[i].x, currentTouch.pointers[i].y);
                }
#endif
                return true;
            }
        }
        // Reset jumpy points dropped on other transitions or if limit exceeded.
        jumpyTouchFilter.jumpyPointsDropped = 0;

#if DEBUG_HACKS
        LOGD("JumpyTouchFilter: Transition - drop limit reset");
#endif
        return false;
    }

    // We have the same number of pointers as last time.
    // A 'jumpy' point is one where the coordinate value for one axis
    // has jumped to the other pointer's location. No need to do anything
    // else if we only have one pointer.
    if (pointerCount < 2) {
        return false;
    }

    if (jumpyTouchFilter.jumpyPointsDropped < JUMPY_DROP_LIMIT) {
        int jumpyEpsilon = parameters.yAxis.range / JUMPY_EPSILON_DIVISOR;

        // We only replace the single worst jumpy point as characterized by pointer distance
        // in a single axis.
        int32_t badPointerIndex = -1;
        int32_t badPointerReplacementIndex = -1;
        int32_t badPointerDistance = INT_MIN; // distance to be corrected

        for (uint32_t i = pointerCount; i-- > 0; ) {
            int32_t x = currentTouch.pointers[i].x;
            int32_t y = currentTouch.pointers[i].y;

#if DEBUG_HACKS
            LOGD("JumpyTouchFilter: Point %d (%d, %d)", i, x, y);
#endif

            // Check if a touch point is too close to another's coordinates
            bool dropX = false, dropY = false;
            for (uint32_t j = 0; j < pointerCount; j++) {
                if (i == j) {
                    continue;
                }

                if (abs(x - currentTouch.pointers[j].x) <= jumpyEpsilon) {
                    dropX = true;
                    break;
                }

                if (abs(y - currentTouch.pointers[j].y) <= jumpyEpsilon) {
                    dropY = true;
                    break;
                }
            }
            if (! dropX && ! dropY) {
                continue; // not jumpy
            }

            // Find a replacement candidate by comparing with older points on the
            // complementary (non-jumpy) axis.
            int32_t distance = INT_MIN; // distance to be corrected
            int32_t replacementIndex = -1;

            if (dropX) {
                // X looks too close.  Find an older replacement point with a close Y.
                int32_t smallestDeltaY = INT_MAX;
                for (uint32_t j = 0; j < pointerCount; j++) {
                    int32_t deltaY = abs(y - lastTouch.pointers[j].y);
                    if (deltaY < smallestDeltaY) {
                        smallestDeltaY = deltaY;
                        replacementIndex = j;
                    }
                }
                distance = abs(x - lastTouch.pointers[replacementIndex].x);
            } else {
                // Y looks too close.  Find an older replacement point with a close X.
                int32_t smallestDeltaX = INT_MAX;
                for (uint32_t j = 0; j < pointerCount; j++) {
                    int32_t deltaX = abs(x - lastTouch.pointers[j].x);
                    if (deltaX < smallestDeltaX) {
                        smallestDeltaX = deltaX;
                        replacementIndex = j;
                    }
                }
                distance = abs(y - lastTouch.pointers[replacementIndex].y);
            }

            // If replacing this pointer would correct a worse error than the previous ones
            // considered, then use this replacement instead.
            if (distance > badPointerDistance) {
                badPointerIndex = i;
                badPointerReplacementIndex = replacementIndex;
                badPointerDistance = distance;
            }
        }

        // Correct the jumpy pointer if one was found.
        if (badPointerIndex >= 0) {
#if DEBUG_HACKS
            LOGD("JumpyTouchFilter: Replacing bad pointer %d with (%d, %d)",
                    badPointerIndex,
                    lastTouch.pointers[badPointerReplacementIndex].x,
                    lastTouch.pointers[badPointerReplacementIndex].y);
#endif

            currentTouch.pointers[badPointerIndex].x =
                    lastTouch.pointers[badPointerReplacementIndex].x;
            currentTouch.pointers[badPointerIndex].y =
                    lastTouch.pointers[badPointerReplacementIndex].y;
            jumpyTouchFilter.jumpyPointsDropped += 1;
            return true;
        }
    }

    jumpyTouchFilter.jumpyPointsDropped = 0;
    return false;
}

/* Special hack for devices that have bad screen data: aggregate and
 * compute averages of the coordinate data, to reduce the amount of
 * jitter seen by applications. */
void InputDevice::TouchScreenState::applyAveragingTouchFilter() {
    for (uint32_t currentIndex = 0; currentIndex < currentTouch.pointerCount; currentIndex++) {
        uint32_t id = currentTouch.pointers[currentIndex].id;
        int32_t x = currentTouch.pointers[currentIndex].x;
        int32_t y = currentTouch.pointers[currentIndex].y;
        int32_t pressure = currentTouch.pointers[currentIndex].pressure;

        if (lastTouch.idBits.hasBit(id)) {
            // Pointer was down before and is still down now.
            // Compute average over history trace.
            uint32_t start = averagingTouchFilter.historyStart[id];
            uint32_t end = averagingTouchFilter.historyEnd[id];

            int64_t deltaX = x - averagingTouchFilter.historyData[end].pointers[id].x;
            int64_t deltaY = y - averagingTouchFilter.historyData[end].pointers[id].y;
            uint64_t distance = uint64_t(deltaX * deltaX + deltaY * deltaY);

#if DEBUG_HACKS
            LOGD("AveragingTouchFilter: Pointer id %d - Distance from last sample: %lld",
                    id, distance);
#endif

            if (distance < AVERAGING_DISTANCE_LIMIT) {
                // Increment end index in preparation for recording new historical data.
                end += 1;
                if (end > AVERAGING_HISTORY_SIZE) {
                    end = 0;
                }

                // If the end index has looped back to the start index then we have filled
                // the historical trace up to the desired size so we drop the historical
                // data at the start of the trace.
                if (end == start) {
                    start += 1;
                    if (start > AVERAGING_HISTORY_SIZE) {
                        start = 0;
                    }
                }

                // Add the raw data to the historical trace.
                averagingTouchFilter.historyStart[id] = start;
                averagingTouchFilter.historyEnd[id] = end;
                averagingTouchFilter.historyData[end].pointers[id].x = x;
                averagingTouchFilter.historyData[end].pointers[id].y = y;
                averagingTouchFilter.historyData[end].pointers[id].pressure = pressure;

                // Average over all historical positions in the trace by total pressure.
                int32_t averagedX = 0;
                int32_t averagedY = 0;
                int32_t totalPressure = 0;
                for (;;) {
                    int32_t historicalX = averagingTouchFilter.historyData[start].pointers[id].x;
                    int32_t historicalY = averagingTouchFilter.historyData[start].pointers[id].y;
                    int32_t historicalPressure = averagingTouchFilter.historyData[start]
                            .pointers[id].pressure;

                    averagedX += historicalX * historicalPressure;
                    averagedY += historicalY * historicalPressure;
                    totalPressure += historicalPressure;

                    if (start == end) {
                        break;
                    }

                    start += 1;
                    if (start > AVERAGING_HISTORY_SIZE) {
                        start = 0;
                    }
                }

                averagedX /= totalPressure;
                averagedY /= totalPressure;

#if DEBUG_HACKS
                LOGD("AveragingTouchFilter: Pointer id %d - "
                        "totalPressure=%d, averagedX=%d, averagedY=%d", id, totalPressure,
                        averagedX, averagedY);
#endif

                currentTouch.pointers[currentIndex].x = averagedX;
                currentTouch.pointers[currentIndex].y = averagedY;
            } else {
#if DEBUG_HACKS
                LOGD("AveragingTouchFilter: Pointer id %d - Exceeded max distance", id);
#endif
            }
        } else {
#if DEBUG_HACKS
            LOGD("AveragingTouchFilter: Pointer id %d - Pointer went up", id);
#endif
        }

        // Reset pointer history.
        averagingTouchFilter.historyStart[id] = 0;
        averagingTouchFilter.historyEnd[id] = 0;
        averagingTouchFilter.historyData[0].pointers[id].x = x;
        averagingTouchFilter.historyData[0].pointers[id].y = y;
        averagingTouchFilter.historyData[0].pointers[id].pressure = pressure;
    }
}

bool InputDevice::TouchScreenState::isPointInsideDisplay(int32_t x, int32_t y) const {
    if (! parameters.xAxis.valid || ! parameters.yAxis.valid) {
        // Assume all points on a touch screen without valid axis parameters are
        // inside the display.
        return true;
    }

    return x >= parameters.xAxis.minValue
        && x <= parameters.xAxis.maxValue
        && y >= parameters.yAxis.minValue
        && y <= parameters.yAxis.maxValue;
}

const InputDevice::VirtualKey* InputDevice::TouchScreenState::findVirtualKeyHit() const {
    int32_t x = currentTouch.pointers[0].x;
    int32_t y = currentTouch.pointers[0].y;
    for (size_t i = 0; i < virtualKeys.size(); i++) {
        const InputDevice::VirtualKey& virtualKey = virtualKeys[i];

#if DEBUG_VIRTUAL_KEYS
        LOGD("VirtualKeys: Hit test (%d, %d): keyCode=%d, scanCode=%d, "
                "left=%d, top=%d, right=%d, bottom=%d",
                x, y,
                virtualKey.keyCode, virtualKey.scanCode,
                virtualKey.hitLeft, virtualKey.hitTop,
                virtualKey.hitRight, virtualKey.hitBottom);
#endif

        if (virtualKey.isHit(x, y)) {
            return & virtualKey;
        }
    }

    return NULL;
}


// --- InputDevice::SingleTouchScreenState ---

void InputDevice::SingleTouchScreenState::reset() {
    accumulator.clear();
    current.down = false;
    current.x = 0;
    current.y = 0;
    current.pressure = 0;
    current.size = 0;
}


// --- InputDevice::MultiTouchScreenState ---

void InputDevice::MultiTouchScreenState::reset() {
    accumulator.clear();
}

} // namespace android
