/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.text.SimpleDateFormat;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Locale;

/**
 * Delegate that provides implementation for some methods in {@link SimpleMonthView}.
 * <p/>
 * Through the layoutlib_create tool, selected methods of SimpleMonthView have been replaced by
 * calls to methods of the same name in this delegate class.
 * <p/>
 * The main purpose of this class is to use {@link android.icu.text.SimpleDateFormat} instead of
 * {@link java.text.SimpleDateFormat}.
 */
public class SimpleMonthView_Delegate {

    private static final String DEFAULT_TITLE_FORMAT = "MMMMy";
    private static final String DAY_OF_WEEK_FORMAT = "EEEEE";

    // Maintain a cache of the last view used, so that the formatters can be reused.
    @Nullable private static SimpleMonthView sLastView;
    @Nullable private static SimpleMonthView_Delegate sLastDelegate;

    private SimpleDateFormat mTitleFormatter;
    private SimpleDateFormat mDayOfWeekFormatter;

    private Locale locale;

    @LayoutlibDelegate
    /*package*/ static CharSequence getTitle(SimpleMonthView view) {
        if (view.mTitle == null) {
            SimpleMonthView_Delegate delegate = getDelegate(view);
            if (delegate.mTitleFormatter == null) {
                delegate.mTitleFormatter = new SimpleDateFormat(DateFormat.getBestDateTimePattern(
                        getLocale(delegate, view), DEFAULT_TITLE_FORMAT));
            }
            view.mTitle = delegate.mTitleFormatter.format(view.mCalendar.getTime());
        }
        return view.mTitle;
    }

    @LayoutlibDelegate
    /*package*/ static String getDayOfWeekLabel(SimpleMonthView view, int dayOfWeek) {
        view.mDayOfWeekLabelCalendar.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        SimpleMonthView_Delegate delegate = getDelegate(view);
        if (delegate.mDayOfWeekFormatter == null) {
            delegate.mDayOfWeekFormatter =
                    new SimpleDateFormat(DAY_OF_WEEK_FORMAT, getLocale(delegate, view));
        }
        return delegate.mDayOfWeekFormatter.format(view.mDayOfWeekLabelCalendar.getTime());
    }

    private static Locale getLocale(SimpleMonthView_Delegate delegate, SimpleMonthView view) {
        if (delegate.locale == null) {
            delegate.locale = view.getContext().getResources().getConfiguration().locale;
        }
        return delegate.locale;
    }

    @NonNull
    private static SimpleMonthView_Delegate getDelegate(SimpleMonthView view) {
        if (view == sLastView) {
            assert sLastDelegate != null;
            return sLastDelegate;
        } else {
            sLastView = view;
            sLastDelegate = new SimpleMonthView_Delegate();
            return sLastDelegate;
        }
    }

    public static void clearCache() {
        sLastView = null;
        sLastDelegate = null;
    }
}
