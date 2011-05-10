/**
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.telephony;

import android.util.Log;
import android.util.Pair;
import android.text.TextUtils;

import java.util.Random;
import java.util.ArrayList;

/**
 * Retry manager allows a simple way to declare a series of
 * retry timeouts. After creating a RetryManager the configure
 * method is used to define the sequence. A simple linear series
 * may be initialized using configure with three integer parameters
 * The other configure method allows a series to be declared using
 * a string.
 *<p>
 * The format of the configuration string is a series of parameters
 * separated by a comma. There are two name value pair parameters plus a series
 * of delay times. The units of of these delay times is unspecified.
 * The name value pairs which may be specified are:
 *<ul>
 *<li>max_retries=<value>
 *<li>default_randomizationTime=<value>
 *</ul>
 *<p>
 * max_retries is the number of times that incrementRetryCount
 * maybe called before isRetryNeeded will return false. if value
 * is infinite then isRetryNeeded will always return true.
 *
 * default_randomizationTime will be used as the randomizationTime
 * for delay times which have no supplied randomizationTime. If
 * default_randomizationTime is not defined it defaults to 0.
 *<p>
 * The other parameters define The series of delay times and each
 * may have an optional randomization value separated from the
 * delay time by a colon.
 *<p>
 * Examples:
 * <ul>
 * <li>3 retries with no randomization value which means its 0:
 * <ul><li><code>"1000, 2000, 3000"</code></ul>
 *
 * <li>10 retries with a 500 default randomization value for each and
 * the 4..10 retries all using 3000 as the delay:
 * <ul><li><code>"max_retries=10, default_randomization=500, 1000, 2000, 3000"</code></ul>
 *
 * <li>4 retries with a 100 as the default randomization value for the first 2 values and
 * the other two having specified values of 500:
 * <ul><li><code>"default_randomization=100, 1000, 2000, 4000:500, 5000:500"</code></ul>
 *
 * <li>Infinite number of retries with the first one at 1000, the second at 2000 all
 * others will be at 3000.
 * <ul><li><code>"max_retries=infinite,1000,2000,3000</code></ul>
 * </ul>
 *
 * {@hide}
 */
public class RetryManager {
    static public final String LOG_TAG = "RetryManager";
    static public final boolean DBG = false;

    /**
     * Retry record with times in milli-seconds
     */
    private static class RetryRec {
        RetryRec(int delayTime, int randomizationTime) {
            mDelayTime = delayTime;
            mRandomizationTime = randomizationTime;
        }

        int mDelayTime;
        int mRandomizationTime;
    }

    /** The array of retry records */
    private ArrayList<RetryRec> mRetryArray = new ArrayList<RetryRec>();

    /** When true isRetryNeeded() will always return true */
    private boolean mRetryForever;

    /**
     * The maximum number of retries to attempt before
     * isRetryNeeded returns false
     */
    private int mMaxRetryCount;

    /** The current number of retries */
    private int mRetryCount;

    /** Random number generator */
    private Random rng = new Random();

    /** Constructor */
    public RetryManager() {
        if (DBG) log("constructor");
    }

    /**
     * Configure for a simple linear sequence of times plus
     * a random value.
     *
     * @param maxRetryCount is the maximum number of retries
     *        before isRetryNeeded returns false.
     * @param retryTime is a time that will be returned by getRetryTime.
     * @param randomizationTime a random value between 0 and
     *        randomizationTime will be added to retryTime. this
     *        parameter may be 0.
     * @return true if successful
     */
    public boolean configure(int maxRetryCount, int retryTime, int randomizationTime) {
        Pair<Boolean, Integer> value;

        if (DBG) log("configure: " + maxRetryCount + ", " + retryTime + "," + randomizationTime);

        if (!validateNonNegativeInt("maxRetryCount", maxRetryCount)) {
            return false;
        }

        if (!validateNonNegativeInt("retryTime", retryTime)) {
            return false;
        }

        if (!validateNonNegativeInt("randomizationTime", randomizationTime)) {
            return false;
        }

        mMaxRetryCount = maxRetryCount;
        resetRetryCount();
        mRetryArray.clear();
        mRetryArray.add(new RetryRec(retryTime, randomizationTime));

        return true;
    }

    /**
     * Configure for using string which allow arbitrary
     * sequences of times. See class comments for the
     * string format.
     *
     * @return true if successful
     */
    public boolean configure(String configStr) {
        // Strip quotes if present.
        if ((configStr.startsWith("\"") && configStr.endsWith("\""))) {
            configStr = configStr.substring(1, configStr.length()-1);
        }
        if (DBG) log("configure: '" + configStr + "'");

        if (!TextUtils.isEmpty(configStr)) {
            int defaultRandomization = 0;

            if (DBG) log("configure: not empty");

            mMaxRetryCount = 0;
            resetRetryCount();
            mRetryArray.clear();

            String strArray[] = configStr.split(",");
            for (int i = 0; i < strArray.length; i++) {
                if (DBG) log("configure: strArray[" + i + "]='" + strArray[i] + "'");
                Pair<Boolean, Integer> value;
                String splitStr[] = strArray[i].split("=", 2);
                splitStr[0] = splitStr[0].trim();
                if (DBG) log("configure: splitStr[0]='" + splitStr[0] + "'");
                if (splitStr.length > 1) {
                    splitStr[1] = splitStr[1].trim();
                    if (DBG) log("configure: splitStr[1]='" + splitStr[1] + "'");
                    if (TextUtils.equals(splitStr[0], "default_randomization")) {
                        value = parseNonNegativeInt(splitStr[0], splitStr[1]);
                        if (!value.first) return false;
                        defaultRandomization = value.second;
                    } else if (TextUtils.equals(splitStr[0], "max_retries")) {
                        if (TextUtils.equals("infinite",splitStr[1])) {
                            mRetryForever = true;
                        } else {
                            value = parseNonNegativeInt(splitStr[0], splitStr[1]);
                            if (!value.first) return false;
                            mMaxRetryCount = value.second;
                        }
                    } else {
                        Log.e(LOG_TAG, "Unrecognized configuration name value pair: "
                                        + strArray[i]);
                        return false;
                    }
                } else {
                    /**
                     * Assume a retry time with an optional randomization value
                     * following a ":"
                     */
                    splitStr = strArray[i].split(":", 2);
                    splitStr[0] = splitStr[0].trim();
                    RetryRec rr = new RetryRec(0, 0);
                    value = parseNonNegativeInt("delayTime", splitStr[0]);
                    if (!value.first) return false;
                    rr.mDelayTime = value.second;

                    // Check if optional randomization value present
                    if (splitStr.length > 1) {
                        splitStr[1] = splitStr[1].trim();
                        if (DBG) log("configure: splitStr[1]='" + splitStr[1] + "'");
                        value = parseNonNegativeInt("randomizationTime", splitStr[1]);
                        if (!value.first) return false;
                        rr.mRandomizationTime = value.second;
                    } else {
                        rr.mRandomizationTime = defaultRandomization;
                    }
                    mRetryArray.add(rr);
                }
            }
            if (mRetryArray.size() > mMaxRetryCount) {
                mMaxRetryCount = mRetryArray.size();
                if (DBG) log("configure: setting mMaxRetryCount=" + mMaxRetryCount);
            }
            if (DBG) log("configure: true");
            return true;
        } else {
            if (DBG) log("configure: false it's empty");
            return false;
        }
    }

    /**
     * Report whether data reconnection should be retried
     *
     * @return {@code true} if the max retries has not been reached. {@code
     *         false} otherwise.
     */
    public boolean isRetryNeeded() {
        boolean retVal = mRetryForever || (mRetryCount < mMaxRetryCount);
        if (DBG) log("isRetryNeeded: " + retVal);
        return retVal;
    }

    /**
     * Return the timer that should be used to trigger the data reconnection
     */
    public int getRetryTimer() {
        int index;
        if (mRetryCount < mRetryArray.size()) {
            index = mRetryCount;
        } else {
            index = mRetryArray.size() - 1;
        }

        int retVal;
        if ((index >= 0) && (index < mRetryArray.size())) {
            retVal = mRetryArray.get(index).mDelayTime + nextRandomizationTime(index);
        } else {
            retVal = 0;
        }

        if (DBG) log("getRetryTimer: " + retVal);
        return retVal;
    }

    /**
     * @return retry count
     */
    public int getRetryCount() {
        if (DBG) log("getRetryCount: " + mRetryCount);
        return mRetryCount;
    }

    /**
     * Increase the retry counter, does not change retry forever.
     */
    public void increaseRetryCount() {
        mRetryCount++;
        if (mRetryCount > mMaxRetryCount) {
            mRetryCount = mMaxRetryCount;
        }
        if (DBG) log("increaseRetryCount: " + mRetryCount);
    }

    /**
     * Set retry count to the specified value
     * and turns off retrying forever.
     */
    public void setRetryCount(int count) {
        mRetryCount = count;
        if (mRetryCount > mMaxRetryCount) {
            mRetryCount = mMaxRetryCount;
        }

        if (mRetryCount < 0) {
            mRetryCount = 0;
        }

        mRetryForever = false;
        if (DBG) log("setRetryCount: " + mRetryCount);
    }

    /**
     * Clear the data-retry counter
     */
    public void resetRetryCount() {
        mRetryCount = 0;
        if (DBG) log("resetRetryCount: " + mRetryCount);
    }

    /**
     * Retry forever using last timeout time.
     */
    public void retryForeverUsingLastTimeout() {
        mRetryCount = mMaxRetryCount;
        mRetryForever = true;
        if (DBG) log("retryForeverUsingLastTimeout: " + mRetryForever + ", " + mRetryCount);
    }

    /**
     * @return true if retrying forever
     */
    public boolean isRetryForever() {
        if (DBG) log("isRetryForever: " + mRetryForever);
        return mRetryForever;
    }

    /**
     * Parse an integer validating the value is not negative.
     *
     * @param name
     * @param stringValue
     * @return Pair.first == true if stringValue an integer >= 0
     */
    private Pair<Boolean, Integer> parseNonNegativeInt(String name, String stringValue) {
        int value;
        Pair<Boolean, Integer> retVal;
        try {
            value = Integer.parseInt(stringValue);
            retVal = new Pair<Boolean, Integer>(validateNonNegativeInt(name, value), value);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, name + " bad value: " + stringValue, e);
            retVal = new Pair<Boolean, Integer>(false, 0);
        }
        if (DBG) log("parseNonNetativeInt: " + name + ", " + stringValue + ", "
                    + retVal.first + ", " + retVal.second);
        return retVal;
    }

    /**
     * Validate an integer is >= 0 and logs an error if not
     *
     * @param name
     * @param value
     * @return Pair.first
     */
    private boolean validateNonNegativeInt(String name, int value) {
        boolean retVal;
        if (value < 0) {
            Log.e(LOG_TAG, name + " bad value: is < 0");
            retVal = false;
        } else {
            retVal = true;
        }
        if (DBG) log("validateNonNegative: " + name + ", " + value + ", " + retVal);
        return retVal;
    }

    /**
     * Return next random number for the index
     */
    private int nextRandomizationTime(int index) {
        int randomTime = mRetryArray.get(index).mRandomizationTime;
        if (randomTime == 0) {
            return 0;
        } else {
            return rng.nextInt(randomTime);
        }
    }

    private void log(String s) {
        Log.d(LOG_TAG, s);
    }
}
