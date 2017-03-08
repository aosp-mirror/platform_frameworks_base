package com.android.internal.os;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        BatteryStatsDurationTimerTest.class,
        BatteryStatsSamplingTimerTest.class,
        BatteryStatsServTest.class,
        BatteryStatsTimeBaseTest.class,
        BatteryStatsTimerTest.class,
        BatteryStatsUidTest.class,
        BatteryStatsSensorTest.class,
        BatteryStatsBackgroundStatsTest.class,
    })
public class BatteryStatsTests {
}

