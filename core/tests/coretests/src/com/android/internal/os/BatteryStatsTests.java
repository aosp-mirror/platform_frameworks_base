package com.android.internal.os;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        BatteryStatsBackgroundStatsTest.class,
        BatteryStatsCounterTest.class,
        BatteryStatsDualTimerTest.class,
        BatteryStatsDurationTimerTest.class,
        BatteryStatsNoteTest.class,
        BatteryStatsSamplingTimerTest.class,
        BatteryStatsSensorTest.class,
        BatteryStatsServTest.class,
        BatteryStatsStopwatchTimerTest.class,
        BatteryStatsTimeBaseTest.class,
        BatteryStatsTimerTest.class,
        BatteryStatsUidTest.class,
    })
public class BatteryStatsTests {
}

