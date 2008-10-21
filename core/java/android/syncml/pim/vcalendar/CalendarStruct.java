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

package android.syncml.pim.vcalendar;

import java.util.List;
import java.util.ArrayList;

/**
 * Same comment as ContactStruct.
 */
public class CalendarStruct{

    public static class EventStruct{
        public String description;
        public String dtend;
        public String dtstart;
        public String duration;
        public String has_alarm;
        public String last_date;
        public String rrule;
        public String status;
        public String title;
        public String event_location;
        public String uid;
        public List<String> reminderList;

        public void addReminderList(String method){
            if(reminderList == null)
                reminderList = new ArrayList<String>();
            reminderList.add(method);
        }
    }

    public String timezone;
    public List<EventStruct> eventList;

    public void addEventList(EventStruct stru){
        if(eventList == null)
            eventList = new ArrayList<EventStruct>();
        eventList.add(stru);
    }
}
