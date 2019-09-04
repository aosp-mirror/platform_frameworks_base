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
 * limitations under the License
 */

package com.android.server.backup.remote;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class RemoteResultTest {
    @Test
    public void testIsPresent_whenNonFailedResult_returnsTrue() {
        RemoteResult result = RemoteResult.of(3);

        boolean isPresent = result.isPresent();

        assertThat(isPresent).isTrue();
    }

    @Test
    public void testIsPresent_whenTimeOutResult_returnsFalse() {
        boolean timeOutIsPresent = RemoteResult.FAILED_TIMED_OUT.isPresent();

        assertThat(timeOutIsPresent).isFalse();
    }

    @Test
    public void testIsPresent_whenCancelledResult_returnsFalse() {
        boolean cancelledIsPresent = RemoteResult.FAILED_CANCELLED.isPresent();

        assertThat(cancelledIsPresent).isFalse();
    }

    @Test
    public void testIsPresent_whenThreadInterruptedResult_returnsFalse() {
        boolean threadInterruptedIsPresent = RemoteResult.FAILED_THREAD_INTERRUPTED.isPresent();

        assertThat(threadInterruptedIsPresent).isFalse();
    }

    @Test
    public void testGet_whenSuccessfulResult_returnsValue() {
        RemoteResult result = RemoteResult.of(7);

        long value = result.get();

        assertThat(value).isEqualTo(7);
    }

    @Test
    public void testGet_whenFailedResult_throws() {
        RemoteResult result = RemoteResult.FAILED_TIMED_OUT;

        expectThrows(IllegalStateException.class, result::get);
    }

    @Test
    public void testToString() {
        assertThat(RemoteResult.of(3).toString()).isEqualTo("RemoteResult{3}");
        assertThat(RemoteResult.FAILED_TIMED_OUT.toString())
                .isEqualTo("RemoteResult{FAILED_TIMED_OUT}");
        assertThat(RemoteResult.FAILED_CANCELLED.toString())
                .isEqualTo("RemoteResult{FAILED_CANCELLED}");
        assertThat(RemoteResult.FAILED_THREAD_INTERRUPTED.toString())
                .isEqualTo("RemoteResult{FAILED_THREAD_INTERRUPTED}");
    }

    @Test
    public void testEquals() {
        assertThat(RemoteResult.of(3).equals(RemoteResult.of(3))).isTrue();
        assertThat(RemoteResult.of(3).equals(RemoteResult.of(7))).isFalse();
        assertThat(RemoteResult.of(-1).equals(RemoteResult.of(1))).isFalse();
        assertThat(RemoteResult.of(Long.MAX_VALUE).equals(RemoteResult.of(-1)))
                .isFalse();
        assertThat(RemoteResult.of(3).equals(RemoteResult.FAILED_TIMED_OUT)).isFalse();
        assertThat(RemoteResult.of(3).equals("3")).isFalse();
        assertThat(RemoteResult.of(3).equals(null)).isFalse();
        assertThat(RemoteResult.FAILED_TIMED_OUT.equals(RemoteResult.FAILED_TIMED_OUT)).isTrue();
        assertThat(RemoteResult.FAILED_TIMED_OUT.equals(RemoteResult.FAILED_CANCELLED)).isFalse();
    }

    /** @see Object#hashCode() */
    @Test
    public void testHashCode() {
        RemoteResult result3 = RemoteResult.of(3);
        assertThat(result3.hashCode()).isEqualTo(result3.hashCode());
        assertThat(result3.hashCode()).isEqualTo(RemoteResult.of(3).hashCode());
        assertThat(RemoteResult.FAILED_TIMED_OUT.hashCode())
                .isEqualTo(RemoteResult.FAILED_TIMED_OUT.hashCode());
        assertThat(RemoteResult.FAILED_CANCELLED.hashCode())
                .isEqualTo(RemoteResult.FAILED_CANCELLED.hashCode());
        assertThat(RemoteResult.FAILED_THREAD_INTERRUPTED.hashCode())
                .isEqualTo(RemoteResult.FAILED_THREAD_INTERRUPTED.hashCode());
    }
}
