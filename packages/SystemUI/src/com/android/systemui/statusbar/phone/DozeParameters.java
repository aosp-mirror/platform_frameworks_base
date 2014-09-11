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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;

import com.android.systemui.R;

import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DozeParameters {
    private static final String TAG = "DozeParameters";

    private static final int MAX_DURATION = 10 * 1000;

    private final Context mContext;

    private StepFunction mPulsePeriodFunction;

    public DozeParameters(Context context) {
        mContext = context;
    }

    public void dump(PrintWriter pw) {
        pw.println("  DozeParameters:");
        pw.print("    getPulseDuration(): "); pw.println(getPulseDuration());
        pw.print("    getPulseInDuration(): "); pw.println(getPulseInDuration());
        pw.print("    getPulseInVisibleDuration(): "); pw.println(getPulseVisibleDuration());
        pw.print("    getPulseOutDuration(): "); pw.println(getPulseOutDuration());
        pw.print("    getPulseStartDelay(): "); pw.println(getPulseStartDelay());
        pw.print("    getPulsePeriodFunction(): "); pw.println(getPulsePeriodFunction());
    }

    public int getPulseDuration() {
        return getPulseInDuration() + getPulseVisibleDuration() + getPulseOutDuration();
    }

    public int getPulseInDuration() {
        return getInt("doze.pulse.duration.in", R.integer.doze_pulse_duration_in);
    }

    public int getPulseVisibleDuration() {
        return getInt("doze.pulse.duration.visible", R.integer.doze_pulse_duration_visible);
    }

    public int getPulseOutDuration() {
        return getInt("doze.pulse.duration.out", R.integer.doze_pulse_duration_out);
    }

    public int getPulseStartDelay() {
        return getInt("doze.pulse.delay", R.integer.doze_pulse_delay);
    }

    public long getPulsePeriod(long age) {
        final String spec = getPulsePeriodFunction();
        if (mPulsePeriodFunction == null || !mPulsePeriodFunction.mSpec.equals(spec)) {
            mPulsePeriodFunction = StepFunction.parse(spec);
        }
        return mPulsePeriodFunction != null ? mPulsePeriodFunction.evaluate(age) : 0;
    }

    private String getPulsePeriodFunction() {
        return getString("doze.pulse.period.function", R.string.doze_pulse_period_function);
    }

    private int getInt(String propName, int resId) {
        int value = SystemProperties.getInt(propName, mContext.getResources().getInteger(resId));
        return MathUtils.constrain(value, 0, MAX_DURATION);
    }

    private String getString(String propName, int resId) {
        return SystemProperties.get(propName, mContext.getString(resId));
    }

    private static class StepFunction {
        private static final Pattern PATTERN = Pattern.compile("(\\d+?)(:(\\d+?))?", 0);

        private String mSpec;
        private long[] mSteps;
        private long[] mValues;
        private long mDefault;

        public static StepFunction parse(String spec) {
            if (TextUtils.isEmpty(spec)) return null;
            try {
                final StepFunction rt = new StepFunction();
                rt.mSpec = spec;
                final String[] tokens = spec.split(",");
                rt.mSteps = new long[tokens.length - 1];
                rt.mValues = new long[tokens.length - 1];
                for (int i = 0; i < tokens.length - 1; i++) {
                    final Matcher m = PATTERN.matcher(tokens[i]);
                    if (!m.matches()) throw new IllegalArgumentException("Bad token: " + tokens[i]);
                    rt.mSteps[i] = Long.parseLong(m.group(1));
                    rt.mValues[i] = Long.parseLong(m.group(3));
                }
                rt.mDefault = Long.parseLong(tokens[tokens.length - 1]);
                return rt;
            } catch (RuntimeException e) {
                Log.w(TAG, "Error parsing spec: " + spec, e);
                return null;
            }
        }

        public long evaluate(long x) {
            for (int i = 0; i < mSteps.length; i++) {
                if (x < mSteps[i]) return mValues[i];
            }
            return mDefault;
        }
    }
}
