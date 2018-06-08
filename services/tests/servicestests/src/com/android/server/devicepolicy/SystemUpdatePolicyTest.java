/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.devicepolicy;

import static android.app.admin.SystemUpdatePolicy.ValidationFailedException.ERROR_COMBINED_FREEZE_PERIOD_TOO_CLOSE;
import static android.app.admin.SystemUpdatePolicy.ValidationFailedException.ERROR_COMBINED_FREEZE_PERIOD_TOO_LONG;
import static android.app.admin.SystemUpdatePolicy.ValidationFailedException.ERROR_DUPLICATE_OR_OVERLAP;
import static android.app.admin.SystemUpdatePolicy.ValidationFailedException.ERROR_NEW_FREEZE_PERIOD_TOO_CLOSE;
import static android.app.admin.SystemUpdatePolicy.ValidationFailedException.ERROR_NEW_FREEZE_PERIOD_TOO_LONG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.admin.FreezePeriod;
import android.app.admin.SystemUpdatePolicy;
import android.os.Parcel;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * Unit tests for {@link android.app.admin.SystemUpdatePolicy}.
 * Throughout this test, we use "MM-DD" format to denote dates without year.
 *
 * atest com.android.server.devicepolicy.SystemUpdatePolicyTest
 * runtest -c com.android.server.devicepolicy.SystemUpdatePolicyTest frameworks-services
 */
@RunWith(AndroidJUnit4.class)
public final class SystemUpdatePolicyTest {

    private static final int DUPLICATE_OR_OVERLAP = ERROR_DUPLICATE_OR_OVERLAP;
    private static final int TOO_LONG = ERROR_NEW_FREEZE_PERIOD_TOO_LONG;
    private static final int TOO_CLOSE = ERROR_NEW_FREEZE_PERIOD_TOO_CLOSE;
    private static final int COMBINED_TOO_LONG = ERROR_COMBINED_FREEZE_PERIOD_TOO_LONG;
    private static final int COMBINED_TOO_CLOSE = ERROR_COMBINED_FREEZE_PERIOD_TOO_CLOSE;

    @Test
    public void testSimplePeriod() throws Exception {
        testFreezePeriodsSucceeds("01-01", "01-02");
        testFreezePeriodsSucceeds("01-31", "01-31");
        testFreezePeriodsSucceeds("11-01", "01-15");
        testFreezePeriodsSucceeds("02-01", "02-29"); // Leap year
        testFreezePeriodsSucceeds("02-01", "03-01");
        testFreezePeriodsSucceeds("12-01", "01-30"); // Wrapped Period
        testFreezePeriodsSucceeds("11-02", "01-30", "04-01", "04-30"); // Wrapped Period
    }

    @Test
    public void testCanonicalizationValidation() throws Exception {
        testFreezePeriodsSucceeds("03-01", "03-31", "09-01", "09-30");
        testFreezePeriodsSucceeds("06-01", "07-01", "09-01", "09-30");
        testFreezePeriodsSucceeds("10-01", "10-31", "12-31", "01-31");
        testFreezePeriodsSucceeds("01-01", "01-30", "04-01", "04-30");
        testFreezePeriodsSucceeds("01-01", "02-28", "05-01", "06-30", "09-01", "10-31");

        // One interval fully covers the other
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "03-01", "03-31", "03-15", "03-31");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "03-01", "03-31", "03-15", "03-16");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "11-15", "01-31", "12-01", "12-31");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "12-01", "01-31", "01-01", "01-15");

        // Partial overlap
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "03-01", "03-31", "03-15", "01-01");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "11-15", "01-31", "12-01", "02-28");

        // No gap between two intervals
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "01-31", "01-31", "02-01", "02-01");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "12-01", "12-15", "12-15", "02-01");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "12-01", "12-15", "12-16", "02-01");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "12-01", "01-15", "01-15", "02-01");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "12-01", "01-15", "01-16", "02-01");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "01-01", "01-30", "12-01", "12-31");
        testFreezePeriodsFails(DUPLICATE_OR_OVERLAP, "12-01", "12-31", "04-01", "04-01",
                "01-01", "01-30");
    }

    @Test
    public void testLengthValidation() throws Exception {
        testFreezePeriodsSucceeds("03-01", "03-31");
        testFreezePeriodsSucceeds("03-03", "03-03", "12-31", "01-01");
        testFreezePeriodsSucceeds("01-01", "03-31", "06-01", "08-29");
        // entire year
        testFreezePeriodsFails(TOO_LONG, "01-01", "12-31");
        // long period spanning across year end
        testFreezePeriodsSucceeds("11-01", "01-29");
        testFreezePeriodsFails(TOO_LONG, "11-01", "01-30");
        // Leap year handling
        testFreezePeriodsSucceeds("12-01", "02-28");
        testFreezePeriodsSucceeds("12-01", "02-29");
        testFreezePeriodsFails(TOO_LONG, "12-01", "03-01");
        // Regular long period
        testFreezePeriodsSucceeds("01-01", "03-31", "06-01", "08-29");
        testFreezePeriodsFails(TOO_LONG, "01-01", "03-31", "06-01", "08-30");
    }

    @Test
    public void testSeparationValidation() throws Exception {
        testFreezePeriodsSucceeds("01-01", "03-31", "06-01", "08-29");
        testFreezePeriodsFails(TOO_CLOSE, "01-01", "01-01", "01-03", "01-03");
        testFreezePeriodsFails(TOO_CLOSE, "03-01", "03-31", "05-01", "05-31");
        // Short interval spans across end of year
        testFreezePeriodsSucceeds("01-31", "03-01", "11-01", "12-01");
        testFreezePeriodsFails(TOO_CLOSE, "01-30", "03-01", "11-01", "12-01");
        // Short separation is after wrapped period
        testFreezePeriodsSucceeds("03-03", "03-31", "12-31", "01-01");
        testFreezePeriodsFails(TOO_CLOSE, "03-02", "03-31", "12-31", "01-01");
        // Short separation including Feb 29
        testFreezePeriodsSucceeds("12-01", "01-15", "03-17", "04-01");
        testFreezePeriodsFails(TOO_CLOSE, "12-01", "01-15", "03-16", "04-01");
        // Short separation including Feb 29
        testFreezePeriodsSucceeds("01-01", "02-28", "04-30", "06-01");
        testFreezePeriodsSucceeds("01-01", "02-29", "04-30", "06-01");
        testFreezePeriodsFails(TOO_CLOSE, "01-01", "03-01", "04-30", "06-01");
    }

    @Test
    public void testValidateTotalLengthWithPreviousPeriods() throws Exception {
        testPrevFreezePeriodSucceeds("2018-01-19", "2018-01-19", /* now */"2018-01-19",
                "07-01", "07-31", "10-01", "11-30");
        testPrevFreezePeriodSucceeds("2018-01-01", "2018-01-19", /* now */"2018-01-19",
                "01-01", "03-30");
        testPrevFreezePeriodSucceeds("2018-01-01", "2018-02-01", /* now */"2018-02-01",
                "11-01", "12-31");

        testPrevFreezePeriodSucceeds("2017-11-01", "2018-01-02", /* now */"2018-01-02",
                "01-01", "01-29");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2017-11-01", "2018-01-02", "2018-01-02",
                "01-01", "01-30");
        testPrevFreezePeriodSucceeds("2017-11-01", "2018-01-02", /* now */"2018-01-01",
                "01-02", "01-29");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2017-11-01", "2018-01-02", "2018-01-01",
                "01-02", "01-30");

        testPrevFreezePeriodSucceeds("2017-11-01", "2017-12-01", /* now */"2017-12-01",
                "11-15", "01-29");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2017-11-01", "2017-12-01", "2017-12-01",
                "11-15", "01-30");

        testPrevFreezePeriodSucceeds("2017-11-01", "2018-01-01", /* now */"2018-01-01",
                "11-15", "01-29");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2017-11-01", "2018-01-01", "2018-01-01",
                "11-15", "01-30");

        testPrevFreezePeriodSucceeds("2018-03-01", "2018-03-31", /* now */"2018-03-31",
                "04-01", "05-29");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2018-03-01", "2018-03-31", "2018-03-31",
                "04-01", "05-30");

        // Leap year handing
        testPrevFreezePeriodSucceeds("2017-12-01", "2018-01-02", /* now */"2018-01-02",
                "01-01", "02-28");
        testPrevFreezePeriodSucceeds("2017-12-01", "2018-01-02", /* now */"2018-01-02",
                "01-01", "02-29");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2017-12-01", "2018-01-02", "2018-01-02",
                "01-01", "03-01");

        testPrevFreezePeriodSucceeds("2016-01-01", "2016-02-28", /* now */"2016-02-28",
                "02-01", "03-31");
        testPrevFreezePeriodSucceeds("2016-01-01", "2016-02-28", /* now */"2016-02-29",
                "02-01", "03-31");
        testPrevFreezePeriodFails(COMBINED_TOO_LONG, "2016-01-01", "2016-02-28", "2016-02-29",
                "02-01", "04-01");

    }

    @Test
    public void testValidateSeparationWithPreviousPeriods() throws Exception {
        testPrevFreezePeriodSucceeds("2018-01-01", "2018-01-02", /* now */"2018-03-04",
                "01-01", "03-30");
        testPrevFreezePeriodSucceeds("2018-01-01", "2018-01-02", /* now */"2018-01-19",
                "04-01", "06-29");
        testPrevFreezePeriodSucceeds("2017-01-01", "2017-03-30", /* now */"2018-12-01",
                "01-01", "03-30");

        testPrevFreezePeriodSucceeds("2018-01-01", "2018-02-01", "2018-02-01",
                "04-03", "06-01");
        testPrevFreezePeriodFails(COMBINED_TOO_CLOSE, "2018-01-01", "2018-02-01", "2018-02-01",
                "04-02", "06-01");

        testPrevFreezePeriodSucceeds("2018-04-01", "2018-06-01", "2018-08-01",
                "07-01", "08-30");
        testPrevFreezePeriodFails(COMBINED_TOO_CLOSE, "2018-04-01", "2018-06-01", "2018-07-30",
                "07-01", "08-30");


        testPrevFreezePeriodSucceeds("2018-03-01", "2018-04-01", "2018-06-01",
                "05-01", "07-01");
        testPrevFreezePeriodFails(COMBINED_TOO_CLOSE, "2018-03-01", "2018-04-01", "2018-05-31",
                "05-01", "07-01");
    }

    @Test
    public void testDistanceWithoutLeapYear() {
        assertEquals(364, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 12, 31), LocalDate.of(2016, 1, 1)));
        assertEquals(365, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2017, 1, 1), LocalDate.of(2016, 1, 1)));
        assertEquals(365, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2017, 2, 28), LocalDate.of(2016, 2, 29)));
        assertEquals(-365, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 1, 1), LocalDate.of(2017, 1, 1)));
        assertEquals(1, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 3, 1), LocalDate.of(2016, 2, 29)));
        assertEquals(1, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 3, 1), LocalDate.of(2016, 2, 28)));
        assertEquals(0, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 2, 29), LocalDate.of(2016, 2, 28)));
        assertEquals(0, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 2, 28), LocalDate.of(2016, 2, 28)));

        assertEquals(59, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2016, 3, 1), LocalDate.of(2016, 1, 1)));
        assertEquals(59, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2017, 3, 1), LocalDate.of(2017, 1, 1)));

        assertEquals(365 * 40, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2040, 1, 1), LocalDate.of(2000, 1, 1)));

        assertEquals(365 * 2, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2019, 3, 1), LocalDate.of(2017, 3, 1)));
        assertEquals(365 * 2, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2018, 3, 1), LocalDate.of(2016, 3, 1)));
        assertEquals(365 * 2, FreezePeriod.distanceWithoutLeapYear(
                LocalDate.of(2017, 3, 1), LocalDate.of(2015, 3, 1)));

    }

    @Test
    public void testInstallationOptionWithoutFreeze() {
        // Also duplicated at com.google.android.gts.deviceowner.SystemUpdatePolicyTest
        final long millis_2018_01_01 = toMillis(2018, 1, 1);

        SystemUpdatePolicy p = SystemUpdatePolicy.createAutomaticInstallPolicy();
        assertInstallationOption(SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, Long.MAX_VALUE,
                millis_2018_01_01, p);

        p = SystemUpdatePolicy.createPostponeInstallPolicy();
        assertInstallationOption(SystemUpdatePolicy.TYPE_POSTPONE, Long.MAX_VALUE,
                millis_2018_01_01, p);

        p = SystemUpdatePolicy.createWindowedInstallPolicy(120, 180); // 2:00 - 3:00
        // 00:00 is two hours before the next window
        assertInstallationOption(SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.HOURS.toMillis(2),
                millis_2018_01_01, p);
        // 02:00 is within the current maintenance window, and one hour until the window ends
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.HOURS.toMillis(1),
                millis_2018_01_01 + TimeUnit.HOURS.toMillis(2), p);
        // 04:00 is 22 hours from the window next day
        assertInstallationOption(SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.HOURS.toMillis(22),
                millis_2018_01_01 + TimeUnit.HOURS.toMillis(4), p);

        p = SystemUpdatePolicy.createWindowedInstallPolicy(22 * 60, 2 * 60); // 22:00 - 2:00
        // 21:00 is one hour from the next window
        assertInstallationOption(SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.HOURS.toMillis(1),
                millis_2018_01_01 + TimeUnit.HOURS.toMillis(21), p);
        // 00:00 is two hours from the end of current window
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.HOURS.toMillis(2),
                millis_2018_01_01, p);
        // 03:00 is 22 hours from the window today
        assertInstallationOption(SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.HOURS.toMillis(19),
                millis_2018_01_01 + TimeUnit.HOURS.toMillis(3), p);
    }

    @Test
    public void testInstallationOptionWithFreeze() throws Exception {
        final long millis_2016_02_29 = toMillis(2016, 2, 29);
        final long millis_2017_01_31 = toMillis(2017, 1, 31);
        final long millis_2017_02_28 = toMillis(2017, 2, 28);
        final long millis_2018_01_01 = toMillis(2018, 1, 1);
        final long millis_2018_08_01 = toMillis(2018, 8, 1);

        SystemUpdatePolicy p = SystemUpdatePolicy.createAutomaticInstallPolicy();
        setFreezePeriods(p, "01-01", "01-31");
        // Inside a freeze period
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(31),
                millis_2018_01_01, p);
        // Device is outside freeze between 2/28 to 12/31 inclusive
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.DAYS.toMillis(307),
                millis_2017_02_28, p);

        // Freeze period contains leap day Feb 29
        p = SystemUpdatePolicy.createPostponeInstallPolicy();
        setFreezePeriods(p, "02-01", "03-05");
        // Freezed until 3/5, note 2016 is a leap year
        assertInstallationOption(SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(6),
                millis_2016_02_29, p);
        // Freezed until 3/5, note 2017 is not a leap year
        assertInstallationOption(SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(6),
                millis_2017_02_28, p);
        // Next freeze is 2018/2/1
        assertInstallationOption(SystemUpdatePolicy.TYPE_POSTPONE, TimeUnit.DAYS.toMillis(31),
                millis_2018_01_01, p);

        // Freeze period start on or right after leap day
        p = SystemUpdatePolicy.createAutomaticInstallPolicy();
        setFreezePeriods(p, "03-01", "03-31");
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.DAYS.toMillis(1),
                millis_2016_02_29, p);
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.DAYS.toMillis(1),
                millis_2017_02_28, p);
        setFreezePeriods(p, "02-28", "03-05");
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(6),
                millis_2016_02_29, p);
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(6),
                millis_2017_02_28, p);

        // Freeze period end on or right after leap day
        p = SystemUpdatePolicy.createAutomaticInstallPolicy();
        setFreezePeriods(p, "02-01", "02-28");
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(1),
                millis_2016_02_29, p);
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(1),
                millis_2017_02_28, p);
        p = SystemUpdatePolicy.createAutomaticInstallPolicy();
        setFreezePeriods(p, "02-01", "03-01");
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(2),
                millis_2016_02_29, p);
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.DAYS.toMillis(2),
                millis_2017_02_28, p);

        // Freeze period with maintenance window
        p = SystemUpdatePolicy.createWindowedInstallPolicy(23 * 60, 1 * 60); // 23:00 - 1:00
        setFreezePeriods(p, "02-01", "02-28");
        // 00:00 is within the current window, outside freeze period
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.HOURS.toMillis(1),
                millis_2018_01_01, p);
        // Last day of feeze period, which ends in 22 hours
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.HOURS.toMillis(22),
                millis_2017_02_28 + TimeUnit.HOURS.toMillis(2), p);
        // Last day before the next freeze, and within window
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.HOURS.toMillis(1),
                millis_2017_01_31, p);
        // Last day before the next freeze, and there is still a partial maintenance window before
        // the freeze.
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_PAUSE, TimeUnit.HOURS.toMillis(19),
                millis_2017_01_31 + TimeUnit.HOURS.toMillis(4), p);

        // Two freeze periods
        p = SystemUpdatePolicy.createAutomaticInstallPolicy();
        setFreezePeriods(p, "05-01", "06-01", "10-15", "01-10");
        // automatic policy for July, August, September and October until 15th
        assertInstallationOption(
                SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC, TimeUnit.DAYS.toMillis(31 + 30 + 14),
                millis_2018_08_01, p);
    }

    private void assertInstallationOption(int expectedType, long expectedTime, long now,
            SystemUpdatePolicy p) {
        assertEquals(expectedType, p.getInstallationOptionAt(now).getType());
        assertEquals(expectedTime, p.getInstallationOptionAt(now).getEffectiveTime());
    }

    private void testFreezePeriodsSucceeds(String...dates) throws Exception {
        SystemUpdatePolicy p = SystemUpdatePolicy.createPostponeInstallPolicy();
        setFreezePeriods(p, dates);
    }

    private void testFreezePeriodsFails(int expectedError, String... dates) throws Exception {
        SystemUpdatePolicy p = SystemUpdatePolicy.createPostponeInstallPolicy();
        try {
            setFreezePeriods(p, dates);
            fail("Invalid periods (" + expectedError + ") not flagged: " + String.join(" ", dates));
        } catch (SystemUpdatePolicy.ValidationFailedException e) {
            assertTrue("Exception not expected: " + e.getMessage(),
                    e.getErrorCode() == expectedError);
        }
    }

    private void testPrevFreezePeriodSucceeds(String prevStart, String prevEnd, String now,
            String... dates) throws Exception {
        createPrevFreezePeriod(prevStart, prevEnd, now, dates);
    }

    private void testPrevFreezePeriodFails(int expectedError, String prevStart, String prevEnd,
            String now,  String... dates) throws Exception {
        try {
            createPrevFreezePeriod(prevStart, prevEnd, now, dates);
            fail("Invalid period (" + expectedError + ") not flagged: " + String.join(" ", dates));
        } catch (SystemUpdatePolicy.ValidationFailedException e) {
            assertTrue("Exception not expected: " + e.getMessage(),
                    e.getErrorCode() == expectedError);
        }
    }

    private void createPrevFreezePeriod(String prevStart, String prevEnd, String now,
            String... dates) throws Exception {
        SystemUpdatePolicy p = SystemUpdatePolicy.createPostponeInstallPolicy();
        setFreezePeriods(p, dates);
        p.validateAgainstPreviousFreezePeriod(parseLocalDate(prevStart),
                parseLocalDate(prevEnd), parseLocalDate(now));
    }

    // "MM-DD" format for date
    private void setFreezePeriods(SystemUpdatePolicy policy, String... dates) throws Exception {
        List<FreezePeriod> periods = new ArrayList<>();
        MonthDay lastDate = null;
        for (String date : dates) {
            MonthDay currentDate = parseMonthDay(date);
            if (lastDate != null) {
                periods.add(new FreezePeriod(lastDate, currentDate));
                lastDate = null;
            } else {
                lastDate = currentDate;
            }
        }
        policy.setFreezePeriods(periods);
        testSerialization(policy, periods);
    }

    private void testSerialization(SystemUpdatePolicy policy,
            List<FreezePeriod> expectedPeriods) throws Exception {
        // Test parcel / unparcel
        Parcel parcel = Parcel.obtain();
        policy.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SystemUpdatePolicy q = SystemUpdatePolicy.CREATOR.createFromParcel(parcel);
        checkFreezePeriods(q, expectedPeriods);
        parcel.recycle();

        // Test XML serialization
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        final XmlSerializer outXml = new FastXmlSerializer();
        outXml.setOutput(outStream, StandardCharsets.UTF_8.name());
        outXml.startDocument(null, true);
        outXml.startTag(null, "ota");
        policy.saveToXml(outXml);
        outXml.endTag(null, "ota");
        outXml.endDocument();
        outXml.flush();

        ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new InputStreamReader(inStream));
        assertEquals(XmlPullParser.START_TAG, parser.next());
        checkFreezePeriods(SystemUpdatePolicy.restoreFromXml(parser), expectedPeriods);
    }

    private void checkFreezePeriods(SystemUpdatePolicy policy,
            List<FreezePeriod> expectedPeriods) {
        int i = 0;
        for (FreezePeriod period : policy.getFreezePeriods()) {
            assertEquals(expectedPeriods.get(i).getStart(), period.getStart());
            assertEquals(expectedPeriods.get(i).getEnd(), period.getEnd());
            i++;
        }
    }

    // MonthDay is of format MM-dd
    private MonthDay parseMonthDay(String date) {
        return MonthDay.of(Integer.parseInt(date.substring(0, 2)),
                Integer.parseInt(date.substring(3, 5)));
    }

    // LocalDat is of format YYYY-MM-dd
    private LocalDate parseLocalDate(String date) {
        return parseMonthDay(date.substring(5)).atYear(Integer.parseInt(date.substring(0, 4)));
    }


    private long toMillis(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 0, 0, 0).atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }
}
