/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.widget;

import java.util.Calendar;

/**
 * Controller class to communicate among the various components of the date picker dialog.
 *
 * @hide
 */
interface DatePickerController {

    void onYearSelected(int year);

    void onDayOfMonthSelected(int year, int month, int day);

    void registerOnDateChangedListener(OnDateChangedListener listener);

    void unregisterOnDateChangedListener(OnDateChangedListener listener);

    Calendar getSelectedDay();

    void setFirstDayOfWeek(int firstDayOfWeek);
    int getFirstDayOfWeek();

    int getMinYear();
    int getMaxYear();

    int getMinMonth();
    int getMaxMonth();

    int getMinDay();
    int getMaxDay();

    void setMinDate(long minDate);
    Calendar getMinDate();

    void setMaxDate(long maxDate);
    Calendar getMaxDate();

    void tryVibrate();
}
