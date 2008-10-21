/*
 * Copyright (C) 2007 The Android Open Source Project
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
#ifndef _CONSTRAINT_H
#define _CONSTRAINT_H

#include <Drm2CommonTypes.h>
#include <ustring.h>
#include <uvector.h>
using namespace ustl;

struct Context {
    string id;
    string version;
};

const int INIT_VALUE = -1;

class Constraint {
public:
    enum MODE {NONE, MOVE, COPY}; /**< export mode type. */

    /**
     * Construtor for constraint.
     */
    Constraint();

    /**
     * Destructor for constraint.
     */
    ~Constraint();

public:
    /**
     * Test whether constraint is valid or not
     * @param time the specitic time to test.
     * @return true/false to indicate the result.
     */
    bool isValid(long time) const;

    /**
     * Test whether constraint is unconstraint or not
     * @return true/false to indicate the result.
     */
    bool isUnConstraint() const;

    /**
     * Test whether constraint is datetime related or not.
     * @return true/false to indicate the result.
     */
    bool isDateTimeConstraint() const;

    /**
     * Test whether constraint contain interval or not
     * @return true/false to indicate the result.
     */
    bool isIntervalConstraint() const;

    /**
     * Test whether constraint is timed count or not
     * @return true/false to indicate the result.
     */
    bool isTimedCountConstraint() const;

    /**
     * Set the start time value of constraint.
     * @param time the specific time value.
     */
    void setStartTime(long time);

    /**
     * Get the start time.
     * @return value of start time.
     */
    long getStartTime() const;

    /**
     * Set the end time.
     * @param time the value of end time.
     */
    void setEndTime(long time);

    /**
     * Get the end time.
     * @param return the value of  end time.
     */
    long getEndTime() const;

    /**
     * Set the accumulated .
     * @param time the specific time.
     */
    void setAccumulated(long time);

    /**
     * Get the accumulated.
     * @return the value of accumulated
     */
    long getAccumulated() const;

    /**
     * Set the count.
     * @param count the value of count.
     */
    void setCount(int count);

    /**
     * Get the count.
     * @return value of count.
     */
    int getCount() const;

    /**
     * Set the value of timer.
     * @param timer the value of the timer.
     */
    void setTimer(int timer);

    /**
     * Get the timer.
     * @return value of time.
     */
    int getTimer() const;

    /**
     * Set the timedCount.
     * @param timedCount the value of timedCount.
     */
    void setTimedCount(int timedCount);

    /**
     * Get the timedCount.
     * @return the value of timedCount.
     */
    int getTimedCount() const;

    /**
     * Set the interval.
     * @param interval the value of interval.
     */
    void setInterval(int interval);

    /**
     * Get the interval.
     * @return the value of interval.
     */
    int getInterval() const;

    /**
     * set export mode.
     * @param mode the mode type of export.
     */
    void setExportMode(MODE mode);

    /**
     * Get the export mode.
     * @return the export mode.
     */
    MODE getExportMode() const;

    /**
     * Consume the constraint.
     * @return true/false to indicate whether consume succesfully or not.
     */
    bool consume();

PRIVATE:
    int mCount; /**< the count. */
    int mTimedCount; /**< timed count. */
    int mTimer; /**< timer for timed count. */
    long mStart; /**< start time. */
    long mEnd; /**< end time. */
    int mInterval; /**< interval. */
    long mAccumulated; /**< accumlated. */
    vector<Context> mSystemList; /**< system list. */
    MODE mExport; /**< export mode. */
};
#endif
