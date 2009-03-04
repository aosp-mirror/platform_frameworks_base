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

/**
 * vCalendar string composer class
 */
public class VCalComposer {

    public final static String VERSION_VCALENDAR10 = "vcalendar1.0";
    public final static String VERSION_VCALENDAR20 = "vcalendar2.0";

    public final static int VERSION_VCAL10_INT = 1;
    public final static int VERSION_VCAL20_INT = 2;

    private static String mNewLine = "\r\n";
    private String mVersion = null;

    public VCalComposer() {
    }

    /**
     * Create a vCalendar String.
     * @param struct see more from CalendarStruct class
     * @param vcalversion MUST be VERSION_VCAL10 /VERSION_VCAL20
     * @return vCalendar string
     * @throws VcalException if version is invalid or create failed
     */
    public String createVCal(CalendarStruct struct, int vcalversion)
                                                throws VCalException{

        StringBuilder returnStr = new StringBuilder();

        //Version check
        if(vcalversion != 1 && vcalversion != 2)
            throw new VCalException("version not match 1.0 or 2.0.");
        if (vcalversion == 1)
            mVersion = VERSION_VCALENDAR10;
        else
            mVersion = VERSION_VCALENDAR20;

        //Build vCalendar:
        returnStr.append("BEGIN:VCALENDAR").append(mNewLine);

        if(vcalversion == VERSION_VCAL10_INT)
            returnStr.append("VERSION:1.0").append(mNewLine);
        else
            returnStr.append("VERSION:2.0").append(mNewLine);

        returnStr.append("PRODID:vCal ID default").append(mNewLine);

        if(!isNull(struct.timezone)){
            if(vcalversion == VERSION_VCAL10_INT)
                returnStr.append("TZ:").append(struct.timezone).append(mNewLine);
            else//down here MUST have
                returnStr.append("BEGIN:VTIMEZONE").append(mNewLine).
                    append("TZID:vCal default").append(mNewLine).
                    append("BEGIN:STANDARD").append(mNewLine).
                    append("DTSTART:16010101T000000").append(mNewLine).
                    append("TZOFFSETFROM:").append(struct.timezone).append(mNewLine).
                    append("TZOFFSETTO:").append(struct.timezone).append(mNewLine).
                    append("END:STANDARD").append(mNewLine).
                    append("END:VTIMEZONE").append(mNewLine);
        }
        //Build VEVNET
        for(int i = 0; i < struct.eventList.size(); i++){
            String str = buildEventStr( struct.eventList.get(i) );
            returnStr.append(str);
        }

        //Build VTODO
        //TODO

        returnStr.append("END:VCALENDAR").append(mNewLine).append(mNewLine);

        return returnStr.toString();
    }

    private String buildEventStr(CalendarStruct.EventStruct stru){

        StringBuilder strbuf = new StringBuilder();

        strbuf.append("BEGIN:VEVENT").append(mNewLine);

        if(!isNull(stru.uid))
            strbuf.append("UID:").append(stru.uid).append(mNewLine);

        if(!isNull(stru.description))
            strbuf.append("DESCRIPTION:").
            append(foldingString(stru.description)).append(mNewLine);

        if(!isNull(stru.dtend))
            strbuf.append("DTEND:").append(stru.dtend).append(mNewLine);

        if(!isNull(stru.dtstart))
            strbuf.append("DTSTART:").append(stru.dtstart).append(mNewLine);

        if(!isNull(stru.duration))
            strbuf.append("DUE:").append(stru.duration).append(mNewLine);

        if(!isNull(stru.event_location))
            strbuf.append("LOCATION:").append(stru.event_location).append(mNewLine);

        if(!isNull(stru.last_date))
            strbuf.append("COMPLETED:").append(stru.last_date).append(mNewLine);

        if(!isNull(stru.rrule))
            strbuf.append("RRULE:").append(stru.rrule).append(mNewLine);

        if(!isNull(stru.title))
            strbuf.append("SUMMARY:").append(stru.title).append(mNewLine);

        if(!isNull(stru.status)){
            String stat = "TENTATIVE";
            switch (Integer.parseInt(stru.status)){
            case 0://Calendar.Calendars.STATUS_TENTATIVE
                stat = "TENTATIVE";
                break;
            case 1://Calendar.Calendars.STATUS_CONFIRMED
                stat = "CONFIRMED";
                break;
            case 2://Calendar.Calendars.STATUS_CANCELED
                stat = "CANCELLED";
                break;
            }
            strbuf.append("STATUS:").append(stat).append(mNewLine);
        }
        //Alarm
        if(!isNull(stru.has_alarm)
            && stru.reminderList != null
            && stru.reminderList.size() > 0){

            if (mVersion.equals(VERSION_VCALENDAR10)){
                String prefix = "";
                for(String method : stru.reminderList){
                    switch (Integer.parseInt(method)){
                    case 0:
                        prefix = "DALARM";
                        break;
                    case 1:
                        prefix = "AALARM";
                        break;
                    case 2:
                        prefix = "MALARM";
                        break;
                    case 3:
                    default:
                        prefix = "DALARM";
                        break;
                    }
                    strbuf.append(prefix).append(":default").append(mNewLine);
                }
            }else {//version 2.0 only support audio-method now.
                strbuf.append("BEGIN:VALARM").append(mNewLine).
                       append("ACTION:AUDIO").append(mNewLine).
                       append("TRIGGER:-PT10M").append(mNewLine).
                       append("END:VALARM").append(mNewLine);
            }
        }
        strbuf.append("END:VEVENT").append(mNewLine);
        return strbuf.toString();
    }

    /** Alter str to folding supported format. */
    private String foldingString(String str){
        return str.replaceAll("\r\n", "\n").replaceAll("\n", "\r\n ");
    }

    /** is null */
    private boolean isNull(String str){
        if(str == null || str.trim().equals(""))
            return true;
        return false;
    }
}
