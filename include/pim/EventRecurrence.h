/*
 * Copyright (C) 2006 The Android Open Source Project
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

//
#ifndef _PIM_EVENT_RECURRENCE_H
#define _PIM_EVENT_RECURRENCE_H

#include <utils/String16.h>

namespace android {

struct EventRecurrence
{
public:
                EventRecurrence();
                ~EventRecurrence();
    
    status_t    parse(const String16&);


    enum freq_t {
        SECONDLY = 1,
        MINUTELY = 2,
        HOURLY = 3,
        DAILY = 4,
        WEEKLY = 5,
        MONTHLY = 6,
        YEARLY = 7
    };

    enum {
        SU = 0x00010000,
        MO = 0x00020000,
        TU = 0x00040000,
        WE = 0x00080000,
        TH = 0x00100000,
        FR = 0x00200000,
        SA = 0x00400000
    };
    
    freq_t    freq;
    String16  until;
    int       count;
    int       interval;
    int*      bysecond;
    int       bysecondCount;
    int*      byminute;
    int       byminuteCount;
    int*      byhour;
    int       byhourCount;
    int*      byday;
    int*      bydayNum;
    int       bydayCount;   
    int*      bymonthday;
    int       bymonthdayCount;
    int*      byyearday;
    int       byyeardayCount;
    int*      byweekno;
    int       byweeknoCount;
    int*      bymonth;
    int       bymonthCount;
    int*      bysetpos;
    int       bysetposCount;
    int       wkst;
};

}; // namespace android

#endif // _PIM_EVENT_RECURRENCE_H
