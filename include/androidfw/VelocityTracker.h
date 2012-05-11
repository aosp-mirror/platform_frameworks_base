/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef _ANDROIDFW_VELOCITY_TRACKER_H
#define _ANDROIDFW_VELOCITY_TRACKER_H

#include <androidfw/Input.h>
#include <utils/Timers.h>
#include <utils/BitSet.h>

namespace android {

/*
 * Calculates the velocity of pointer movements over time.
 */
class VelocityTracker {
public:
    // Default polynomial degree.  (used by getVelocity)
    static const uint32_t DEFAULT_DEGREE = 2;

    // Default sample horizon.  (used by getVelocity)
    // We don't use too much history by default since we want to react to quick
    // changes in direction.
    static const nsecs_t DEFAULT_HORIZON = 100 * 1000000; // 100 ms

    struct Position {
        float x, y;
    };

    struct Estimator {
        static const size_t MAX_DEGREE = 2;

        // Polynomial coefficients describing motion in X and Y.
        float xCoeff[MAX_DEGREE + 1], yCoeff[MAX_DEGREE + 1];

        // Polynomial degree (number of coefficients), or zero if no information is
        // available.
        uint32_t degree;

        // Confidence (coefficient of determination), between 0 (no fit) and 1 (perfect fit).
        float confidence;

        inline void clear() {
            degree = 0;
            confidence = 0;
            for (size_t i = 0; i <= MAX_DEGREE; i++) {
                xCoeff[i] = 0;
                yCoeff[i] = 0;
            }
        }
    };

    VelocityTracker();

    // Resets the velocity tracker state.
    void clear();

    // Resets the velocity tracker state for specific pointers.
    // Call this method when some pointers have changed and may be reusing
    // an id that was assigned to a different pointer earlier.
    void clearPointers(BitSet32 idBits);

    // Adds movement information for a set of pointers.
    // The idBits bitfield specifies the pointer ids of the pointers whose positions
    // are included in the movement.
    // The positions array contains position information for each pointer in order by
    // increasing id.  Its size should be equal to the number of one bits in idBits.
    void addMovement(nsecs_t eventTime, BitSet32 idBits, const Position* positions);

    // Adds movement information for all pointers in a MotionEvent, including historical samples.
    void addMovement(const MotionEvent* event);

    // Gets the velocity of the specified pointer id in position units per second.
    // Returns false and sets the velocity components to zero if there is
    // insufficient movement information for the pointer.
    bool getVelocity(uint32_t id, float* outVx, float* outVy) const;

    // Gets a quadratic estimator for the movements of the specified pointer id.
    // Returns false and clears the estimator if there is no information available
    // about the pointer.
    bool getEstimator(uint32_t id, uint32_t degree, nsecs_t horizon,
            Estimator* outEstimator) const;

    // Gets the active pointer id, or -1 if none.
    inline int32_t getActivePointerId() const { return mActivePointerId; }

    // Gets a bitset containing all pointer ids from the most recent movement.
    inline BitSet32 getCurrentPointerIdBits() const { return mMovements[mIndex].idBits; }

private:
    // Number of samples to keep.
    static const uint32_t HISTORY_SIZE = 20;

    struct Movement {
        nsecs_t eventTime;
        BitSet32 idBits;
        Position positions[MAX_POINTERS];

        inline const Position& getPosition(uint32_t id) const {
            return positions[idBits.getIndexOfBit(id)];
        }
    };

    uint32_t mIndex;
    Movement mMovements[HISTORY_SIZE];
    int32_t mActivePointerId;
};

} // namespace android

#endif // _ANDROIDFW_VELOCITY_TRACKER_H
