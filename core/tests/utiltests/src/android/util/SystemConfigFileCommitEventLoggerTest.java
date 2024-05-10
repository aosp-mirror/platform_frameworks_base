/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.util;

import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import android.os.SystemClock;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = SystemConfigFileCommitEventLogger.class)
public class SystemConfigFileCommitEventLoggerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testSimple() throws Exception {
        var logger = spy(new SystemConfigFileCommitEventLogger("name"));
        var startTime = SystemClock.uptimeMillis();
        logger.onStartWrite();
        logger.onFinishWrite();
        var endTime = SystemClock.uptimeMillis();
        Mockito.verify(logger, times(1)).writeLogRecord(
                longThat(l -> l <= endTime - startTime));
    }

    @Test
    public void testMultipleWrong() throws Exception {
        var logger = spy(new SystemConfigFileCommitEventLogger("name"));
        var startTime = SystemClock.uptimeMillis();
        logger.onStartWrite();
        logger.onFinishWrite();
        var endTime1 = SystemClock.uptimeMillis();
        SystemClock.sleep(10);
        logger.onStartWrite();
        logger.onFinishWrite();
        var endTime2 = SystemClock.uptimeMillis();
        var inOrder = Mockito.inOrder(logger);
        inOrder.verify(logger).writeLogRecord(longThat(
                l -> l <= endTime1 - startTime));
        inOrder.verify(logger).writeLogRecord(longThat(
                l -> l > endTime1 - startTime && l <= endTime2 - startTime));
    }

    @Test
    public void testMultipleRight() throws Exception {
        var logger = spy(new SystemConfigFileCommitEventLogger("name"));
        var startTime = SystemClock.uptimeMillis();
        logger.onStartWrite();
        logger.onFinishWrite();
        var endTime1 = SystemClock.uptimeMillis();
        SystemClock.sleep(10);
        logger.setStartTime(0);
        logger.onStartWrite();
        logger.onFinishWrite();
        var endTime2 = SystemClock.uptimeMillis();
        var inOrder = Mockito.inOrder(logger);
        inOrder.verify(logger).writeLogRecord(longThat(
                l -> l <= endTime1 - startTime));
        inOrder.verify(logger).writeLogRecord(longThat(
                l -> l <= endTime2 - endTime1));
    }
}
