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

#include <rights/Constraint.h>

/** see Constraint.h */
Constraint::Constraint()
{
    mCount = INIT_VALUE;
    mTimedCount = INIT_VALUE;
    mTimer = INIT_VALUE;
    mStart = INIT_VALUE;
    mEnd = INIT_VALUE;
    mInterval = INIT_VALUE;
    mAccumulated = INIT_VALUE;
    mExport = NONE;
}

/** see Constraint.h */
Constraint::~Constraint()
{}

/** see Constraint.h */
bool Constraint::isUnConstraint() const
{
    return (mCount == INIT_VALUE && mTimedCount == INIT_VALUE &&
            mTimer == INIT_VALUE && mStart == INIT_VALUE &&
            mEnd == INIT_VALUE && mInterval == INIT_VALUE &&
            mAccumulated == INIT_VALUE && mExport == NONE &&
            mSystemList.empty());
}

/** see Constraint.h */
bool Constraint::isDateTimeConstraint() const
{
    return !(mStart == INIT_VALUE && mEnd == INIT_VALUE);
}

/** see Constraint.h */
bool Constraint::isIntervalConstraint() const
{
    return !(mInterval == INIT_VALUE);
}

/** see Constraint.h */
bool Constraint::isTimedCountConstraint() const
{
    return !(mTimedCount == INIT_VALUE);
}

/** see Constraint.h */
bool Constraint::isValid(long time) const
{
    if (isUnConstraint())
    {
        return true;
    }

    if (isDateTimeConstraint())
    {
        if (time < mStart || time > mEnd)
        {
            return false;
        }
    }

    if (mInterval == 0 || mCount == 0 ||
        mTimedCount == 0 || mAccumulated == 0)
    {
        return false;
    }

    return true;
}

/** see Constraint.h */
void Constraint::setStartTime(long time)
{
    mStart = time;
}

/** see Constraint.h */
long Constraint::getStartTime() const
{
    return mStart;
}

/** see Constraint.h */
void Constraint::setEndTime(long time)
{
    mEnd = time;
}

/** see Constraint.h */
long Constraint::getEndTime() const
{
    return mEnd;
}

/** see Constraint.h */
void Constraint::setAccumulated(long time)
{
    mAccumulated = time;
}

/** see Constraint.h */
long Constraint::getAccumulated() const
{
    return mAccumulated;
}

/** see Constraint.h */
void Constraint::setCount(int count)
{
    mCount = count;
}

/** see Constraint.h */
int Constraint::getCount() const
{
    return mCount;
}

/** see Constraint.h */
void Constraint::setTimer(int timer)
{
    mTimer = timer;
}

/** see Constraint.h */
int Constraint::getTimer() const
{
    return mTimer;
}

/** see Constraint.h */
void Constraint::setTimedCount(int timedCount)
{
    mTimedCount = timedCount;
}

/** see Constraint.h */
int Constraint::getTimedCount() const
{
    return mTimedCount;
}

/** see Constraint.h */
void Constraint::setInterval(int interval)
{
    mInterval = interval;
}

/** see Constraint.h */
int Constraint::getInterval() const
{
    return mInterval;
}

/** see Constraint.h */
void Constraint::setExportMode(MODE mode)
{
    mExport = mode;
}

/** see Constraint.h */
Constraint::MODE Constraint::getExportMode() const
{
    return mExport;
}

/** see Constraint.h */
bool Constraint::consume()
{
    if (isUnConstraint())
    {
        return true;
    }

    if (mCount > 0)
    {
        mCount--;
        return true;
    }

    if (mAccumulated > 0)
    {
        mAccumulated--;
        return true;
    }

    if (mTimedCount > 0)
    {

    }
    return false;
}
