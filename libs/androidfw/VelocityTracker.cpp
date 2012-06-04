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

// Log debug messages about the progress of the algorithm itself.
#define DEBUG_STRATEGY 0

#include <math.h>
#include <limits.h>

#include <androidfw/VelocityTracker.h>
#include <utils/BitSet.h>
#include <utils/String8.h>
#include <utils/Timers.h>

#include <cutils/properties.h>

namespace android {

// Nanoseconds per milliseconds.
static const nsecs_t NANOS_PER_MS = 1000000;

// Threshold for determining that a pointer has stopped moving.
// Some input devices do not send ACTION_MOVE events in the case where a pointer has
// stopped.  We need to detect this case so that we can accurately predict the
// velocity after the pointer starts moving again.
static const nsecs_t ASSUME_POINTER_STOPPED_TIME = 40 * NANOS_PER_MS;


static float vectorDot(const float* a, const float* b, uint32_t m) {
    float r = 0;
    while (m--) {
        r += *(a++) * *(b++);
    }
    return r;
}

static float vectorNorm(const float* a, uint32_t m) {
    float r = 0;
    while (m--) {
        float t = *(a++);
        r += t * t;
    }
    return sqrtf(r);
}

#if DEBUG_STRATEGY || DEBUG_VELOCITY
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


// --- VelocityTracker ---

// The default velocity tracker strategy.
// Although other strategies are available for testing and comparison purposes,
// this is the strategy that applications will actually use.  Be very careful
// when adjusting the default strategy because it can dramatically affect
// (often in a bad way) the user experience.
const char* VelocityTracker::DEFAULT_STRATEGY = "lsq2";

VelocityTracker::VelocityTracker(const char* strategy) :
        mLastEventTime(0), mCurrentPointerIdBits(0), mActivePointerId(-1) {
    char value[PROPERTY_VALUE_MAX];

    // Allow the default strategy to be overridden using a system property for debugging.
    if (!strategy) {
        int length = property_get("debug.velocitytracker.strategy", value, NULL);
        if (length > 0) {
            strategy = value;
        } else {
            strategy = DEFAULT_STRATEGY;
        }
    }

    // Configure the strategy.
    if (!configureStrategy(strategy)) {
        ALOGD("Unrecognized velocity tracker strategy name '%s'.", strategy);
        if (!configureStrategy(DEFAULT_STRATEGY)) {
            LOG_ALWAYS_FATAL("Could not create the default velocity tracker strategy '%s'!",
                    strategy);
        }
    }
}

VelocityTracker::~VelocityTracker() {
    delete mStrategy;
}

bool VelocityTracker::configureStrategy(const char* strategy) {
    mStrategy = createStrategy(strategy);
    return mStrategy != NULL;
}

VelocityTrackerStrategy* VelocityTracker::createStrategy(const char* strategy) {
    if (!strcmp("lsq1", strategy)) {
        // 1st order least squares.  Quality: POOR.
        // Frequently underfits the touch data especially when the finger accelerates
        // or changes direction.  Often underestimates velocity.  The direction
        // is overly influenced by historical touch points.
        return new LeastSquaresVelocityTrackerStrategy(1);
    }
    if (!strcmp("lsq2", strategy)) {
        // 2nd order least squares.  Quality: VERY GOOD.
        // Pretty much ideal, but can be confused by certain kinds of touch data,
        // particularly if the panel has a tendency to generate delayed,
        // duplicate or jittery touch coordinates when the finger is released.
        return new LeastSquaresVelocityTrackerStrategy(2);
    }
    if (!strcmp("lsq3", strategy)) {
        // 3rd order least squares.  Quality: UNUSABLE.
        // Frequently overfits the touch data yielding wildly divergent estimates
        // of the velocity when the finger is released.
        return new LeastSquaresVelocityTrackerStrategy(3);
    }
    if (!strcmp("wlsq2-delta", strategy)) {
        // 2nd order weighted least squares, delta weighting.  Quality: EXPERIMENTAL
        return new LeastSquaresVelocityTrackerStrategy(2,
                LeastSquaresVelocityTrackerStrategy::WEIGHTING_DELTA);
    }
    if (!strcmp("wlsq2-central", strategy)) {
        // 2nd order weighted least squares, central weighting.  Quality: EXPERIMENTAL
        return new LeastSquaresVelocityTrackerStrategy(2,
                LeastSquaresVelocityTrackerStrategy::WEIGHTING_CENTRAL);
    }
    if (!strcmp("wlsq2-recent", strategy)) {
        // 2nd order weighted least squares, recent weighting.  Quality: EXPERIMENTAL
        return new LeastSquaresVelocityTrackerStrategy(2,
                LeastSquaresVelocityTrackerStrategy::WEIGHTING_RECENT);
    }
    if (!strcmp("int1", strategy)) {
        // 1st order integrating filter.  Quality: GOOD.
        // Not as good as 'lsq2' because it cannot estimate acceleration but it is
        // more tolerant of errors.  Like 'lsq1', this strategy tends to underestimate
        // the velocity of a fling but this strategy tends to respond to changes in
        // direction more quickly and accurately.
        return new IntegratingVelocityTrackerStrategy(1);
    }
    if (!strcmp("int2", strategy)) {
        // 2nd order integrating filter.  Quality: EXPERIMENTAL.
        // For comparison purposes only.  Unlike 'int1' this strategy can compensate
        // for acceleration but it typically overestimates the effect.
        return new IntegratingVelocityTrackerStrategy(2);
    }
    if (!strcmp("legacy", strategy)) {
        // Legacy velocity tracker algorithm.  Quality: POOR.
        // For comparison purposes only.  This algorithm is strongly influenced by
        // old data points, consistently underestimates velocity and takes a very long
        // time to adjust to changes in direction.
        return new LegacyVelocityTrackerStrategy();
    }
    return NULL;
}

void VelocityTracker::clear() {
    mCurrentPointerIdBits.clear();
    mActivePointerId = -1;

    mStrategy->clear();
}

void VelocityTracker::clearPointers(BitSet32 idBits) {
    BitSet32 remainingIdBits(mCurrentPointerIdBits.value & ~idBits.value);
    mCurrentPointerIdBits = remainingIdBits;

    if (mActivePointerId >= 0 && idBits.hasBit(mActivePointerId)) {
        mActivePointerId = !remainingIdBits.isEmpty() ? remainingIdBits.firstMarkedBit() : -1;
    }

    mStrategy->clearPointers(idBits);
}

void VelocityTracker::addMovement(nsecs_t eventTime, BitSet32 idBits, const Position* positions) {
    while (idBits.count() > MAX_POINTERS) {
        idBits.clearLastMarkedBit();
    }

    if ((mCurrentPointerIdBits.value & idBits.value)
            && eventTime >= mLastEventTime + ASSUME_POINTER_STOPPED_TIME) {
#if DEBUG_VELOCITY
        ALOGD("VelocityTracker: stopped for %0.3f ms, clearing state.",
                (eventTime - mLastEventTime) * 0.000001f);
#endif
        // We have not received any movements for too long.  Assume that all pointers
        // have stopped.
        mStrategy->clear();
    }
    mLastEventTime = eventTime;

    mCurrentPointerIdBits = idBits;
    if (mActivePointerId < 0 || !idBits.hasBit(mActivePointerId)) {
        mActivePointerId = idBits.isEmpty() ? -1 : idBits.firstMarkedBit();
    }

    mStrategy->addMovement(eventTime, idBits, positions);

#if DEBUG_VELOCITY
    ALOGD("VelocityTracker: addMovement eventTime=%lld, idBits=0x%08x, activePointerId=%d",
            eventTime, idBits.value, mActivePointerId);
    for (BitSet32 iterBits(idBits); !iterBits.isEmpty(); ) {
        uint32_t id = iterBits.firstMarkedBit();
        uint32_t index = idBits.getIndexOfBit(id);
        iterBits.clearBit(id);
        Estimator estimator;
        getEstimator(id, &estimator);
        ALOGD("  %d: position (%0.3f, %0.3f), "
                "estimator (degree=%d, xCoeff=%s, yCoeff=%s, confidence=%f)",
                id, positions[index].x, positions[index].y,
                int(estimator.degree),
                vectorToString(estimator.xCoeff, estimator.degree + 1).string(),
                vectorToString(estimator.yCoeff, estimator.degree + 1).string(),
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

    uint32_t pointerIndex[MAX_POINTERS];
    for (size_t i = 0; i < pointerCount; i++) {
        pointerIndex[i] = idBits.getIndexOfBit(event->getPointerId(i));
    }

    nsecs_t eventTime;
    Position positions[pointerCount];

    size_t historySize = event->getHistorySize();
    for (size_t h = 0; h < historySize; h++) {
        eventTime = event->getHistoricalEventTime(h);
        for (size_t i = 0; i < pointerCount; i++) {
            uint32_t index = pointerIndex[i];
            positions[index].x = event->getHistoricalX(i, h);
            positions[index].y = event->getHistoricalY(i, h);
        }
        addMovement(eventTime, idBits, positions);
    }

    eventTime = event->getEventTime();
    for (size_t i = 0; i < pointerCount; i++) {
        uint32_t index = pointerIndex[i];
        positions[index].x = event->getX(i);
        positions[index].y = event->getY(i);
    }
    addMovement(eventTime, idBits, positions);
}

bool VelocityTracker::getVelocity(uint32_t id, float* outVx, float* outVy) const {
    Estimator estimator;
    if (getEstimator(id, &estimator) && estimator.degree >= 1) {
        *outVx = estimator.xCoeff[1];
        *outVy = estimator.yCoeff[1];
        return true;
    }
    *outVx = 0;
    *outVy = 0;
    return false;
}

bool VelocityTracker::getEstimator(uint32_t id, Estimator* outEstimator) const {
    return mStrategy->getEstimator(id, outEstimator);
}


// --- LeastSquaresVelocityTrackerStrategy ---

const nsecs_t LeastSquaresVelocityTrackerStrategy::HORIZON;
const uint32_t LeastSquaresVelocityTrackerStrategy::HISTORY_SIZE;

LeastSquaresVelocityTrackerStrategy::LeastSquaresVelocityTrackerStrategy(
        uint32_t degree, Weighting weighting) :
        mDegree(degree), mWeighting(weighting) {
    clear();
}

LeastSquaresVelocityTrackerStrategy::~LeastSquaresVelocityTrackerStrategy() {
}

void LeastSquaresVelocityTrackerStrategy::clear() {
    mIndex = 0;
    mMovements[0].idBits.clear();
}

void LeastSquaresVelocityTrackerStrategy::clearPointers(BitSet32 idBits) {
    BitSet32 remainingIdBits(mMovements[mIndex].idBits.value & ~idBits.value);
    mMovements[mIndex].idBits = remainingIdBits;
}

void LeastSquaresVelocityTrackerStrategy::addMovement(nsecs_t eventTime, BitSet32 idBits,
        const VelocityTracker::Position* positions) {
    if (++mIndex == HISTORY_SIZE) {
        mIndex = 0;
    }

    Movement& movement = mMovements[mIndex];
    movement.eventTime = eventTime;
    movement.idBits = idBits;
    uint32_t count = idBits.count();
    for (uint32_t i = 0; i < count; i++) {
        movement.positions[i] = positions[i];
    }
}

/**
 * Solves a linear least squares problem to obtain a N degree polynomial that fits
 * the specified input data as nearly as possible.
 *
 * Returns true if a solution is found, false otherwise.
 *
 * The input consists of two vectors of data points X and Y with indices 0..m-1
 * along with a weight vector W of the same size.
 *
 * The output is a vector B with indices 0..n that describes a polynomial
 * that fits the data, such the sum of W[i] * W[i] * abs(Y[i] - (B[0] + B[1] X[i]
 * + B[2] X[i]^2 ... B[n] X[i]^n)) for all i between 0 and m-1 is minimized.
 *
 * Accordingly, the weight vector W should be initialized by the caller with the
 * reciprocal square root of the variance of the error in each input data point.
 * In other words, an ideal choice for W would be W[i] = 1 / var(Y[i]) = 1 / stddev(Y[i]).
 * The weights express the relative importance of each data point.  If the weights are
 * all 1, then the data points are considered to be of equal importance when fitting
 * the polynomial.  It is a good idea to choose weights that diminish the importance
 * of data points that may have higher than usual error margins.
 *
 * Errors among data points are assumed to be independent.  W is represented here
 * as a vector although in the literature it is typically taken to be a diagonal matrix.
 *
 * That is to say, the function that generated the input data can be approximated
 * by y(x) ~= B[0] + B[1] x + B[2] x^2 + ... + B[n] x^n.
 *
 * The coefficient of determination (R^2) is also returned to describe the goodness
 * of fit of the model for the given data.  It is a value between 0 and 1, where 1
 * indicates perfect correspondence.
 *
 * This function first expands the X vector to a m by n matrix A such that
 * A[i][0] = 1, A[i][1] = X[i], A[i][2] = X[i]^2, ..., A[i][n] = X[i]^n, then
 * multiplies it by w[i]./
 *
 * Then it calculates the QR decomposition of A yielding an m by m orthonormal matrix Q
 * and an m by n upper triangular matrix R.  Because R is upper triangular (lower
 * part is all zeroes), we can simplify the decomposition into an m by n matrix
 * Q1 and a n by n matrix R1 such that A = Q1 R1.
 *
 * Finally we solve the system of linear equations given by R1 B = (Qtranspose W Y)
 * to find B.
 *
 * For efficiency, we lay out A and Q column-wise in memory because we frequently
 * operate on the column vectors.  Conversely, we lay out R row-wise.
 *
 * http://en.wikipedia.org/wiki/Numerical_methods_for_linear_least_squares
 * http://en.wikipedia.org/wiki/Gram-Schmidt
 */
static bool solveLeastSquares(const float* x, const float* y,
        const float* w, uint32_t m, uint32_t n, float* outB, float* outDet) {
#if DEBUG_STRATEGY
    ALOGD("solveLeastSquares: m=%d, n=%d, x=%s, y=%s, w=%s", int(m), int(n),
            vectorToString(x, m).string(), vectorToString(y, m).string(),
            vectorToString(w, m).string());
#endif

    // Expand the X vector to a matrix A, pre-multiplied by the weights.
    float a[n][m]; // column-major order
    for (uint32_t h = 0; h < m; h++) {
        a[0][h] = w[h];
        for (uint32_t i = 1; i < n; i++) {
            a[i][h] = a[i - 1][h] * x[h];
        }
    }
#if DEBUG_STRATEGY
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
#if DEBUG_STRATEGY
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
#if DEBUG_STRATEGY
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

    // Solve R B = Qt W Y to find B.  This is easy because R is upper triangular.
    // We just work from bottom-right to top-left calculating B's coefficients.
    float wy[m];
    for (uint32_t h = 0; h < m; h++) {
        wy[h] = y[h] * w[h];
    }
    for (uint32_t i = n; i-- != 0; ) {
        outB[i] = vectorDot(&q[i][0], wy, m);
        for (uint32_t j = n - 1; j > i; j--) {
            outB[i] -= r[i][j] * outB[j];
        }
        outB[i] /= r[i][i];
    }
#if DEBUG_STRATEGY
    ALOGD("  - b=%s", vectorToString(outB, n).string());
#endif

    // Calculate the coefficient of determination as 1 - (SSerr / SStot) where
    // SSerr is the residual sum of squares (variance of the error),
    // and SStot is the total sum of squares (variance of the data) where each
    // has been weighted.
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
        sserr += w[h] * w[h] * err * err;
        float var = y[h] - ymean;
        sstot += w[h] * w[h] * var * var;
    }
    *outDet = sstot > 0.000001f ? 1.0f - (sserr / sstot) : 1;
#if DEBUG_STRATEGY
    ALOGD("  - sserr=%f", sserr);
    ALOGD("  - sstot=%f", sstot);
    ALOGD("  - det=%f", *outDet);
#endif
    return true;
}

bool LeastSquaresVelocityTrackerStrategy::getEstimator(uint32_t id,
        VelocityTracker::Estimator* outEstimator) const {
    outEstimator->clear();

    // Iterate over movement samples in reverse time order and collect samples.
    float x[HISTORY_SIZE];
    float y[HISTORY_SIZE];
    float w[HISTORY_SIZE];
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
        if (age > HORIZON) {
            break;
        }

        const VelocityTracker::Position& position = movement.getPosition(id);
        x[m] = position.x;
        y[m] = position.y;
        w[m] = chooseWeight(index);
        time[m] = -age * 0.000000001f;
        index = (index == 0 ? HISTORY_SIZE : index) - 1;
    } while (++m < HISTORY_SIZE);

    if (m == 0) {
        return false; // no data
    }

    // Calculate a least squares polynomial fit.
    uint32_t degree = mDegree;
    if (degree > m - 1) {
        degree = m - 1;
    }
    if (degree >= 1) {
        float xdet, ydet;
        uint32_t n = degree + 1;
        if (solveLeastSquares(time, x, w, m, n, outEstimator->xCoeff, &xdet)
                && solveLeastSquares(time, y, w, m, n, outEstimator->yCoeff, &ydet)) {
            outEstimator->time = newestMovement.eventTime;
            outEstimator->degree = degree;
            outEstimator->confidence = xdet * ydet;
#if DEBUG_STRATEGY
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
    outEstimator->time = newestMovement.eventTime;
    outEstimator->degree = 0;
    outEstimator->confidence = 1;
    return true;
}

float LeastSquaresVelocityTrackerStrategy::chooseWeight(uint32_t index) const {
    switch (mWeighting) {
    case WEIGHTING_DELTA: {
        // Weight points based on how much time elapsed between them and the next
        // point so that points that "cover" a shorter time span are weighed less.
        //   delta  0ms: 0.5
        //   delta 10ms: 1.0
        if (index == mIndex) {
            return 1.0f;
        }
        uint32_t nextIndex = (index + 1) % HISTORY_SIZE;
        float deltaMillis = (mMovements[nextIndex].eventTime- mMovements[index].eventTime)
                * 0.000001f;
        if (deltaMillis < 0) {
            return 0.5f;
        }
        if (deltaMillis < 10) {
            return 0.5f + deltaMillis * 0.05;
        }
        return 1.0f;
    }

    case WEIGHTING_CENTRAL: {
        // Weight points based on their age, weighing very recent and very old points less.
        //   age  0ms: 0.5
        //   age 10ms: 1.0
        //   age 50ms: 1.0
        //   age 60ms: 0.5
        float ageMillis = (mMovements[mIndex].eventTime - mMovements[index].eventTime)
                * 0.000001f;
        if (ageMillis < 0) {
            return 0.5f;
        }
        if (ageMillis < 10) {
            return 0.5f + ageMillis * 0.05;
        }
        if (ageMillis < 50) {
            return 1.0f;
        }
        if (ageMillis < 60) {
            return 0.5f + (60 - ageMillis) * 0.05;
        }
        return 0.5f;
    }

    case WEIGHTING_RECENT: {
        // Weight points based on their age, weighing older points less.
        //   age   0ms: 1.0
        //   age  50ms: 1.0
        //   age 100ms: 0.5
        float ageMillis = (mMovements[mIndex].eventTime - mMovements[index].eventTime)
                * 0.000001f;
        if (ageMillis < 50) {
            return 1.0f;
        }
        if (ageMillis < 100) {
            return 0.5f + (100 - ageMillis) * 0.01f;
        }
        return 0.5f;
    }

    case WEIGHTING_NONE:
    default:
        return 1.0f;
    }
}


// --- IntegratingVelocityTrackerStrategy ---

IntegratingVelocityTrackerStrategy::IntegratingVelocityTrackerStrategy(uint32_t degree) :
        mDegree(degree) {
}

IntegratingVelocityTrackerStrategy::~IntegratingVelocityTrackerStrategy() {
}

void IntegratingVelocityTrackerStrategy::clear() {
    mPointerIdBits.clear();
}

void IntegratingVelocityTrackerStrategy::clearPointers(BitSet32 idBits) {
    mPointerIdBits.value &= ~idBits.value;
}

void IntegratingVelocityTrackerStrategy::addMovement(nsecs_t eventTime, BitSet32 idBits,
        const VelocityTracker::Position* positions) {
    uint32_t index = 0;
    for (BitSet32 iterIdBits(idBits); !iterIdBits.isEmpty();) {
        uint32_t id = iterIdBits.clearFirstMarkedBit();
        State& state = mPointerState[id];
        const VelocityTracker::Position& position = positions[index++];
        if (mPointerIdBits.hasBit(id)) {
            updateState(state, eventTime, position.x, position.y);
        } else {
            initState(state, eventTime, position.x, position.y);
        }
    }

    mPointerIdBits = idBits;
}

bool IntegratingVelocityTrackerStrategy::getEstimator(uint32_t id,
        VelocityTracker::Estimator* outEstimator) const {
    outEstimator->clear();

    if (mPointerIdBits.hasBit(id)) {
        const State& state = mPointerState[id];
        populateEstimator(state, outEstimator);
        return true;
    }

    return false;
}

void IntegratingVelocityTrackerStrategy::initState(State& state,
        nsecs_t eventTime, float xpos, float ypos) const {
    state.updateTime = eventTime;
    state.degree = 0;

    state.xpos = xpos;
    state.xvel = 0;
    state.xaccel = 0;
    state.ypos = ypos;
    state.yvel = 0;
    state.yaccel = 0;
}

void IntegratingVelocityTrackerStrategy::updateState(State& state,
        nsecs_t eventTime, float xpos, float ypos) const {
    const nsecs_t MIN_TIME_DELTA = 2 * NANOS_PER_MS;
    const float FILTER_TIME_CONSTANT = 0.010f; // 10 milliseconds

    if (eventTime <= state.updateTime + MIN_TIME_DELTA) {
        return;
    }

    float dt = (eventTime - state.updateTime) * 0.000000001f;
    state.updateTime = eventTime;

    float xvel = (xpos - state.xpos) / dt;
    float yvel = (ypos - state.ypos) / dt;
    if (state.degree == 0) {
        state.xvel = xvel;
        state.yvel = yvel;
        state.degree = 1;
    } else {
        float alpha = dt / (FILTER_TIME_CONSTANT + dt);
        if (mDegree == 1) {
            state.xvel += (xvel - state.xvel) * alpha;
            state.yvel += (yvel - state.yvel) * alpha;
        } else {
            float xaccel = (xvel - state.xvel) / dt;
            float yaccel = (yvel - state.yvel) / dt;
            if (state.degree == 1) {
                state.xaccel = xaccel;
                state.yaccel = yaccel;
                state.degree = 2;
            } else {
                state.xaccel += (xaccel - state.xaccel) * alpha;
                state.yaccel += (yaccel - state.yaccel) * alpha;
            }
            state.xvel += (state.xaccel * dt) * alpha;
            state.yvel += (state.yaccel * dt) * alpha;
        }
    }
    state.xpos = xpos;
    state.ypos = ypos;
}

void IntegratingVelocityTrackerStrategy::populateEstimator(const State& state,
        VelocityTracker::Estimator* outEstimator) const {
    outEstimator->time = state.updateTime;
    outEstimator->confidence = 1.0f;
    outEstimator->degree = state.degree;
    outEstimator->xCoeff[0] = state.xpos;
    outEstimator->xCoeff[1] = state.xvel;
    outEstimator->xCoeff[2] = state.xaccel / 2;
    outEstimator->yCoeff[0] = state.ypos;
    outEstimator->yCoeff[1] = state.yvel;
    outEstimator->yCoeff[2] = state.yaccel / 2;
}


// --- LegacyVelocityTrackerStrategy ---

const nsecs_t LegacyVelocityTrackerStrategy::HORIZON;
const uint32_t LegacyVelocityTrackerStrategy::HISTORY_SIZE;
const nsecs_t LegacyVelocityTrackerStrategy::MIN_DURATION;

LegacyVelocityTrackerStrategy::LegacyVelocityTrackerStrategy() {
    clear();
}

LegacyVelocityTrackerStrategy::~LegacyVelocityTrackerStrategy() {
}

void LegacyVelocityTrackerStrategy::clear() {
    mIndex = 0;
    mMovements[0].idBits.clear();
}

void LegacyVelocityTrackerStrategy::clearPointers(BitSet32 idBits) {
    BitSet32 remainingIdBits(mMovements[mIndex].idBits.value & ~idBits.value);
    mMovements[mIndex].idBits = remainingIdBits;
}

void LegacyVelocityTrackerStrategy::addMovement(nsecs_t eventTime, BitSet32 idBits,
        const VelocityTracker::Position* positions) {
    if (++mIndex == HISTORY_SIZE) {
        mIndex = 0;
    }

    Movement& movement = mMovements[mIndex];
    movement.eventTime = eventTime;
    movement.idBits = idBits;
    uint32_t count = idBits.count();
    for (uint32_t i = 0; i < count; i++) {
        movement.positions[i] = positions[i];
    }
}

bool LegacyVelocityTrackerStrategy::getEstimator(uint32_t id,
        VelocityTracker::Estimator* outEstimator) const {
    outEstimator->clear();

    const Movement& newestMovement = mMovements[mIndex];
    if (!newestMovement.idBits.hasBit(id)) {
        return false; // no data
    }

    // Find the oldest sample that contains the pointer and that is not older than HORIZON.
    nsecs_t minTime = newestMovement.eventTime - HORIZON;
    uint32_t oldestIndex = mIndex;
    uint32_t numTouches = 1;
    do {
        uint32_t nextOldestIndex = (oldestIndex == 0 ? HISTORY_SIZE : oldestIndex) - 1;
        const Movement& nextOldestMovement = mMovements[nextOldestIndex];
        if (!nextOldestMovement.idBits.hasBit(id)
                || nextOldestMovement.eventTime < minTime) {
            break;
        }
        oldestIndex = nextOldestIndex;
    } while (++numTouches < HISTORY_SIZE);

    // Calculate an exponentially weighted moving average of the velocity estimate
    // at different points in time measured relative to the oldest sample.
    // This is essentially an IIR filter.  Newer samples are weighted more heavily
    // than older samples.  Samples at equal time points are weighted more or less
    // equally.
    //
    // One tricky problem is that the sample data may be poorly conditioned.
    // Sometimes samples arrive very close together in time which can cause us to
    // overestimate the velocity at that time point.  Most samples might be measured
    // 16ms apart but some consecutive samples could be only 0.5sm apart because
    // the hardware or driver reports them irregularly or in bursts.
    float accumVx = 0;
    float accumVy = 0;
    uint32_t index = oldestIndex;
    uint32_t samplesUsed = 0;
    const Movement& oldestMovement = mMovements[oldestIndex];
    const VelocityTracker::Position& oldestPosition = oldestMovement.getPosition(id);
    nsecs_t lastDuration = 0;

    while (numTouches-- > 1) {
        if (++index == HISTORY_SIZE) {
            index = 0;
        }
        const Movement& movement = mMovements[index];
        nsecs_t duration = movement.eventTime - oldestMovement.eventTime;

        // If the duration between samples is small, we may significantly overestimate
        // the velocity.  Consequently, we impose a minimum duration constraint on the
        // samples that we include in the calculation.
        if (duration >= MIN_DURATION) {
            const VelocityTracker::Position& position = movement.getPosition(id);
            float scale = 1000000000.0f / duration; // one over time delta in seconds
            float vx = (position.x - oldestPosition.x) * scale;
            float vy = (position.y - oldestPosition.y) * scale;
            accumVx = (accumVx * lastDuration + vx * duration) / (duration + lastDuration);
            accumVy = (accumVy * lastDuration + vy * duration) / (duration + lastDuration);
            lastDuration = duration;
            samplesUsed += 1;
        }
    }

    // Report velocity.
    const VelocityTracker::Position& newestPosition = newestMovement.getPosition(id);
    outEstimator->time = newestMovement.eventTime;
    outEstimator->confidence = 1;
    outEstimator->xCoeff[0] = newestPosition.x;
    outEstimator->yCoeff[0] = newestPosition.y;
    if (samplesUsed) {
        outEstimator->xCoeff[1] = accumVx;
        outEstimator->yCoeff[1] = accumVy;
        outEstimator->degree = 1;
    } else {
        outEstimator->degree = 0;
    }
    return true;
}

} // namespace android
