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

#define LOG_TAG "VelocityTracker"
//#define LOG_NDEBUG 0

// Log debug messages about velocity tracking.
#define DEBUG_VELOCITY 0

// Log debug messages about least squares fitting.
#define DEBUG_LEAST_SQUARES 0

#include <math.h>
#include <limits.h>

#include <androidfw/VelocityTracker.h>
#include <utils/BitSet.h>
#include <utils/String8.h>
#include <utils/Timers.h>

namespace android {

// --- VelocityTracker ---

const uint32_t VelocityTracker::DEFAULT_DEGREE;
const nsecs_t VelocityTracker::DEFAULT_HORIZON;
const uint32_t VelocityTracker::HISTORY_SIZE;

static inline float vectorDot(const float* a, const float* b, uint32_t m) {
    float r = 0;
    while (m--) {
        r += *(a++) * *(b++);
    }
    return r;
}

static inline float vectorNorm(const float* a, uint32_t m) {
    float r = 0;
    while (m--) {
        float t = *(a++);
        r += t * t;
    }
    return sqrtf(r);
}

#if DEBUG_LEAST_SQUARES || DEBUG_VELOCITY
static String8 vectorToString(const float* a, uint32_t m) {
    String8 str;
    str.append("[");
    while (m--) {
        str.appendFormat(" %f", *(a++));
        if (m) {
            str.append(",");
        }
    }
    str.append(" ]");
    return str;
}

static String8 matrixToString(const float* a, uint32_t m, uint32_t n, bool rowMajor) {
    String8 str;
    str.append("[");
    for (size_t i = 0; i < m; i++) {
        if (i) {
            str.append(",");
        }
        str.append(" [");
        for (size_t j = 0; j < n; j++) {
            if (j) {
                str.append(",");
            }
            str.appendFormat(" %f", a[rowMajor ? i * n + j : j * m + i]);
        }
        str.append(" ]");
    }
    str.append(" ]");
    return str;
}
#endif

VelocityTracker::VelocityTracker() {
    clear();
}

void VelocityTracker::clear() {
    mIndex = 0;
    mMovements[0].idBits.clear();
    mActivePointerId = -1;
}

void VelocityTracker::clearPointers(BitSet32 idBits) {
    BitSet32 remainingIdBits(mMovements[mIndex].idBits.value & ~idBits.value);
    mMovements[mIndex].idBits = remainingIdBits;

    if (mActivePointerId >= 0 && idBits.hasBit(mActivePointerId)) {
        mActivePointerId = !remainingIdBits.isEmpty() ? remainingIdBits.firstMarkedBit() : -1;
    }
}

void VelocityTracker::addMovement(nsecs_t eventTime, BitSet32 idBits, const Position* positions) {
    if (++mIndex == HISTORY_SIZE) {
        mIndex = 0;
    }

    while (idBits.count() > MAX_POINTERS) {
        idBits.clearLastMarkedBit();
    }

    Movement& movement = mMovements[mIndex];
    movement.eventTime = eventTime;
    movement.idBits = idBits;
    uint32_t count = idBits.count();
    for (uint32_t i = 0; i < count; i++) {
        movement.positions[i] = positions[i];
    }

    if (mActivePointerId < 0 || !idBits.hasBit(mActivePointerId)) {
        mActivePointerId = count != 0 ? idBits.firstMarkedBit() : -1;
    }

#if DEBUG_VELOCITY
    ALOGD("VelocityTracker: addMovement eventTime=%lld, idBits=0x%08x, activePointerId=%d",
            eventTime, idBits.value, mActivePointerId);
    for (BitSet32 iterBits(idBits); !iterBits.isEmpty(); ) {
        uint32_t id = iterBits.firstMarkedBit();
        uint32_t index = idBits.getIndexOfBit(id);
        iterBits.clearBit(id);
        Estimator estimator;
        getEstimator(id, DEFAULT_DEGREE, DEFAULT_HORIZON, &estimator);
        ALOGD("  %d: position (%0.3f, %0.3f), "
                "estimator (degree=%d, xCoeff=%s, yCoeff=%s, confidence=%f)",
                id, positions[index].x, positions[index].y,
                int(estimator.degree),
                vectorToString(estimator.xCoeff, estimator.degree).string(),
                vectorToString(estimator.yCoeff, estimator.degree).string(),
                estimator.confidence);
    }
#endif
}

void VelocityTracker::addMovement(const MotionEvent* event) {
    int32_t actionMasked = event->getActionMasked();

    switch (actionMasked) {
    case AMOTION_EVENT_ACTION_DOWN:
    case AMOTION_EVENT_ACTION_HOVER_ENTER:
        // Clear all pointers on down before adding the new movement.
        clear();
        break;
    case AMOTION_EVENT_ACTION_POINTER_DOWN: {
        // Start a new movement trace for a pointer that just went down.
        // We do this on down instead of on up because the client may want to query the
        // final velocity for a pointer that just went up.
        BitSet32 downIdBits;
        downIdBits.markBit(event->getPointerId(event->getActionIndex()));
        clearPointers(downIdBits);
        break;
    }
    case AMOTION_EVENT_ACTION_MOVE:
    case AMOTION_EVENT_ACTION_HOVER_MOVE:
        break;
    default:
        // Ignore all other actions because they do not convey any new information about
        // pointer movement.  We also want to preserve the last known velocity of the pointers.
        // Note that ACTION_UP and ACTION_POINTER_UP always report the last known position
        // of the pointers that went up.  ACTION_POINTER_UP does include the new position of
        // pointers that remained down but we will also receive an ACTION_MOVE with this
        // information if any of them actually moved.  Since we don't know how many pointers
        // will be going up at once it makes sense to just wait for the following ACTION_MOVE
        // before adding the movement.
        return;
    }

    size_t pointerCount = event->getPointerCount();
    if (pointerCount > MAX_POINTERS) {
        pointerCount = MAX_POINTERS;
    }

    BitSet32 idBits;
    for (size_t i = 0; i < pointerCount; i++) {
        idBits.markBit(event->getPointerId(i));
    }

    nsecs_t eventTime;
    Position positions[pointerCount];

    size_t historySize = event->getHistorySize();
    for (size_t h = 0; h < historySize; h++) {
        eventTime = event->getHistoricalEventTime(h);
        for (size_t i = 0; i < pointerCount; i++) {
            positions[i].x = event->getHistoricalX(i, h);
            positions[i].y = event->getHistoricalY(i, h);
        }
        addMovement(eventTime, idBits, positions);
    }

    eventTime = event->getEventTime();
    for (size_t i = 0; i < pointerCount; i++) {
        positions[i].x = event->getX(i);
        positions[i].y = event->getY(i);
    }
    addMovement(eventTime, idBits, positions);
}

/**
 * Solves a linear least squares problem to obtain a N degree polynomial that fits
 * the specified input data as nearly as possible.
 *
 * Returns true if a solution is found, false otherwise.
 *
 * The input consists of two vectors of data points X and Y with indices 0..m-1.
 * The output is a vector B with indices 0..n-1 that describes a polynomial
 * that fits the data, such the sum of abs(Y[i] - (B[0] + B[1] X[i] + B[2] X[i]^2 ... B[n] X[i]^n))
 * for all i between 0 and m-1 is minimized.
 *
 * That is to say, the function that generated the input data can be approximated
 * by y(x) ~= B[0] + B[1] x + B[2] x^2 + ... + B[n] x^n.
 *
 * The coefficient of determination (R^2) is also returned to describe the goodness
 * of fit of the model for the given data.  It is a value between 0 and 1, where 1
 * indicates perfect correspondence.
 *
 * This function first expands the X vector to a m by n matrix A such that
 * A[i][0] = 1, A[i][1] = X[i], A[i][2] = X[i]^2, ..., A[i][n] = X[i]^n.
 *
 * Then it calculates the QR decomposition of A yielding an m by m orthonormal matrix Q
 * and an m by n upper triangular matrix R.  Because R is upper triangular (lower
 * part is all zeroes), we can simplify the decomposition into an m by n matrix
 * Q1 and a n by n matrix R1 such that A = Q1 R1.
 *
 * Finally we solve the system of linear equations given by R1 B = (Qtranspose Y)
 * to find B.
 *
 * For efficiency, we lay out A and Q column-wise in memory because we frequently
 * operate on the column vectors.  Conversely, we lay out R row-wise.
 *
 * http://en.wikipedia.org/wiki/Numerical_methods_for_linear_least_squares
 * http://en.wikipedia.org/wiki/Gram-Schmidt
 */
static bool solveLeastSquares(const float* x, const float* y, uint32_t m, uint32_t n,
        float* outB, float* outDet) {
#if DEBUG_LEAST_SQUARES
    ALOGD("solveLeastSquares: m=%d, n=%d, x=%s, y=%s", int(m), int(n),
            vectorToString(x, m).string(), vectorToString(y, m).string());
#endif

    // Expand the X vector to a matrix A.
    float a[n][m]; // column-major order
    for (uint32_t h = 0; h < m; h++) {
        a[0][h] = 1;
        for (uint32_t i = 1; i < n; i++) {
            a[i][h] = a[i - 1][h] * x[h];
        }
    }
#if DEBUG_LEAST_SQUARES
    ALOGD("  - a=%s", matrixToString(&a[0][0], m, n, false /*rowMajor*/).string());
#endif

    // Apply the Gram-Schmidt process to A to obtain its QR decomposition.
    float q[n][m]; // orthonormal basis, column-major order
    float r[n][n]; // upper triangular matrix, row-major order
    for (uint32_t j = 0; j < n; j++) {
        for (uint32_t h = 0; h < m; h++) {
            q[j][h] = a[j][h];
        }
        for (uint32_t i = 0; i < j; i++) {
            float dot = vectorDot(&q[j][0], &q[i][0], m);
            for (uint32_t h = 0; h < m; h++) {
                q[j][h] -= dot * q[i][h];
            }
        }

        float norm = vectorNorm(&q[j][0], m);
        if (norm < 0.000001f) {
            // vectors are linearly dependent or zero so no solution
#if DEBUG_LEAST_SQUARES
            ALOGD("  - no solution, norm=%f", norm);
#endif
            return false;
        }

        float invNorm = 1.0f / norm;
        for (uint32_t h = 0; h < m; h++) {
            q[j][h] *= invNorm;
        }
        for (uint32_t i = 0; i < n; i++) {
            r[j][i] = i < j ? 0 : vectorDot(&q[j][0], &a[i][0], m);
        }
    }
#if DEBUG_LEAST_SQUARES
    ALOGD("  - q=%s", matrixToString(&q[0][0], m, n, false /*rowMajor*/).string());
    ALOGD("  - r=%s", matrixToString(&r[0][0], n, n, true /*rowMajor*/).string());

    // calculate QR, if we factored A correctly then QR should equal A
    float qr[n][m];
    for (uint32_t h = 0; h < m; h++) {
        for (uint32_t i = 0; i < n; i++) {
            qr[i][h] = 0;
            for (uint32_t j = 0; j < n; j++) {
                qr[i][h] += q[j][h] * r[j][i];
            }
        }
    }
    ALOGD("  - qr=%s", matrixToString(&qr[0][0], m, n, false /*rowMajor*/).string());
#endif

    // Solve R B = Qt Y to find B.  This is easy because R is upper triangular.
    // We just work from bottom-right to top-left calculating B's coefficients.
    for (uint32_t i = n; i-- != 0; ) {
        outB[i] = vectorDot(&q[i][0], y, m);
        for (uint32_t j = n - 1; j > i; j--) {
            outB[i] -= r[i][j] * outB[j];
        }
        outB[i] /= r[i][i];
    }
#if DEBUG_LEAST_SQUARES
    ALOGD("  - b=%s", vectorToString(outB, n).string());
#endif

    // Calculate the coefficient of determination as 1 - (SSerr / SStot) where
    // SSerr is the residual sum of squares (squared variance of the error),
    // and SStot is the total sum of squares (squared variance of the data).
    float ymean = 0;
    for (uint32_t h = 0; h < m; h++) {
        ymean += y[h];
    }
    ymean /= m;

    float sserr = 0;
    float sstot = 0;
    for (uint32_t h = 0; h < m; h++) {
        float err = y[h] - outB[0];
        float term = 1;
        for (uint32_t i = 1; i < n; i++) {
            term *= x[h];
            err -= term * outB[i];
        }
        sserr += err * err;
        float var = y[h] - ymean;
        sstot += var * var;
    }
    *outDet = sstot > 0.000001f ? 1.0f - (sserr / sstot) : 1;
#if DEBUG_LEAST_SQUARES
    ALOGD("  - sserr=%f", sserr);
    ALOGD("  - sstot=%f", sstot);
    ALOGD("  - det=%f", *outDet);
#endif
    return true;
}

bool VelocityTracker::getVelocity(uint32_t id, float* outVx, float* outVy) const {
    Estimator estimator;
    if (getEstimator(id, DEFAULT_DEGREE, DEFAULT_HORIZON, &estimator)) {
        if (estimator.degree >= 1) {
            *outVx = estimator.xCoeff[1];
            *outVy = estimator.yCoeff[1];
            return true;
        }
    }
    *outVx = 0;
    *outVy = 0;
    return false;
}

bool VelocityTracker::getEstimator(uint32_t id, uint32_t degree, nsecs_t horizon,
        Estimator* outEstimator) const {
    outEstimator->clear();

    // Iterate over movement samples in reverse time order and collect samples.
    float x[HISTORY_SIZE];
    float y[HISTORY_SIZE];
    float time[HISTORY_SIZE];
    uint32_t m = 0;
    uint32_t index = mIndex;
    const Movement& newestMovement = mMovements[mIndex];
    do {
        const Movement& movement = mMovements[index];
        if (!movement.idBits.hasBit(id)) {
            break;
        }

        nsecs_t age = newestMovement.eventTime - movement.eventTime;
        if (age > horizon) {
            break;
        }

        const Position& position = movement.getPosition(id);
        x[m] = position.x;
        y[m] = position.y;
        time[m] = -age * 0.000000001f;
        index = (index == 0 ? HISTORY_SIZE : index) - 1;
    } while (++m < HISTORY_SIZE);

    if (m == 0) {
        return false; // no data
    }

    // Calculate a least squares polynomial fit.
    if (degree > Estimator::MAX_DEGREE) {
        degree = Estimator::MAX_DEGREE;
    }
    if (degree > m - 1) {
        degree = m - 1;
    }
    if (degree >= 1) {
        float xdet, ydet;
        uint32_t n = degree + 1;
        if (solveLeastSquares(time, x, m, n, outEstimator->xCoeff, &xdet)
                && solveLeastSquares(time, y, m, n, outEstimator->yCoeff, &ydet)) {
            outEstimator->degree = degree;
            outEstimator->confidence = xdet * ydet;
#if DEBUG_LEAST_SQUARES
            ALOGD("estimate: degree=%d, xCoeff=%s, yCoeff=%s, confidence=%f",
                    int(outEstimator->degree),
                    vectorToString(outEstimator->xCoeff, n).string(),
                    vectorToString(outEstimator->yCoeff, n).string(),
                    outEstimator->confidence);
#endif
            return true;
        }
    }

    // No velocity data available for this pointer, but we do have its current position.
    outEstimator->xCoeff[0] = x[0];
    outEstimator->yCoeff[0] = y[0];
    outEstimator->degree = 0;
    outEstimator->confidence = 1;
    return true;
}

} // namespace android
