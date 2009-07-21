/*
 * Copyright (C) 2005 The Android Open Source Project
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

#ifndef ANDROID_TIME_H
#define ANDROID_TIME_H

#include <time.h>
#include <cutils/tztime.h>
#include <stdint.h>
#include <sys/types.h>
#include <sys/time.h>
#include <utils/String8.h>
#include <utils/String16.h>

namespace android {

/*
 * This class is the core implementation of the android.util.Time java
 * class.  It doesn't implement some of the methods that are implemented
 * in Java.  They could be done here, but it's not expected that this class
 * will be used.  If that assumption is incorrect, feel free to update this
 * file.  The reason to do it here is to not mix the implementation of this
 * class and the jni glue code.
 */
class Time
{
public:
    struct tm t;

    // this object doesn't own this string
    const char *timezone;

    enum {
        SEC = 1,
        MIN = 2,
        HOUR = 3,
        MDAY = 4,
        MON = 5,
        YEAR = 6,
        WDAY = 7,
        YDAY = 8
    };

    static int compare(Time& a, Time& b);

    Time();

    void switchTimezone(const char *timezone);
    String8 format(const char *format, const struct strftime_locale *locale) const;
    void format2445(short* buf, bool hasTime) const;
    String8 toString() const;
    void setToNow();
    int64_t toMillis(bool ignoreDst);
    void set(int64_t millis);

    inline void set(int sec, int min, int hour, int mday, int mon, int year,
            int isdst)
    {
        this->t.tm_sec = sec;
        this->t.tm_min = min;
        this->t.tm_hour = hour;
        this->t.tm_mday = mday;
        this->t.tm_mon = mon;
        this->t.tm_year = year;
        this->t.tm_isdst = isdst;
#ifdef HAVE_TM_GMTOFF
        this->t.tm_gmtoff = 0;
#endif
        this->t.tm_wday = 0;
        this->t.tm_yday = 0;
    }
};

}; // namespace android

#endif // ANDROID_TIME_H
