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

package com.android.server;

import static org.junit.Assert.assertEquals;

import android.os.Binder;
import android.os.Process;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BinderInternal;
import com.android.internal.os.BinderInternal.CallSession;
import com.android.server.BinderCallsStatsService.WorkSourceProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class BinderCallsStatsServiceTest {
    @Test
    public void weTrustOurselves() {
        WorkSourceProvider workSourceProvider = new WorkSourceProvider() {
            protected int getCallingUid() {
                return Process.myUid();
            }

            protected int getCallingWorkSourceUid() {
                return 1;
            }
        };
        workSourceProvider.systemReady(InstrumentationRegistry.getContext());

        assertEquals(1, workSourceProvider.resolveWorkSourceUid());
    }

    @Test
    public void workSourceSetIfCallerHasPermission() {
        WorkSourceProvider workSourceProvider = new WorkSourceProvider() {
            protected int getCallingUid() {
                // System process uid which as UPDATE_DEVICE_STATS.
                return 1001;
            }

            protected int getCallingWorkSourceUid() {
                return 1;
            }
        };
        workSourceProvider.systemReady(InstrumentationRegistry.getContext());

        assertEquals(1, workSourceProvider.resolveWorkSourceUid());
    }

    @Test
    public void workSourceResolvedToCallingUid() {
        WorkSourceProvider workSourceProvider = new WorkSourceProvider() {
            protected int getCallingUid() {
                // UID without permissions.
                return Integer.MAX_VALUE;
            }

            protected int getCallingWorkSourceUid() {
                return 1;
            }
        };
        workSourceProvider.systemReady(InstrumentationRegistry.getContext());

        assertEquals(Integer.MAX_VALUE, workSourceProvider.resolveWorkSourceUid());
    }

    @Test
    public void workSourceSet() {
        TestObserver observer = new TestObserver();
        observer.callStarted(new Binder(), 0);
        assertEquals(true, observer.workSourceSet);
    }

    static class TestObserver extends BinderCallsStatsService.BinderCallsObserver {
        public boolean workSourceSet = false;

        TestObserver() {
            super(new NoopObserver(), new WorkSourceProvider());
        }

        @Override
        protected void setThreadLocalWorkSourceUid(int uid) {
            workSourceSet = true;
        }
    }


    static class NoopObserver implements BinderInternal.Observer {
        @Override
        public CallSession callStarted(Binder binder, int code) {
            return null;
        }

        @Override
        public void callEnded(CallSession s, int parcelRequestSize, int parcelReplySize) {
        }

        @Override
        public void callThrewException(CallSession s, Exception exception) {
        }
    }
}
