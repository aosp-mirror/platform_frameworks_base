/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertEquals;

import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CaptivePortalTest {
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final String TEST_PACKAGE_NAME = "com.google.android.test";

    private final class MyCaptivePortalImpl extends ICaptivePortal.Stub {
        int mCode = -1;
        String mPackageName = null;

        @Override
        public void appResponse(final int response) throws RemoteException {
            mCode = response;
        }

        @Override
        public void logEvent(int eventId, String packageName) throws RemoteException {
            mCode = eventId;
            mPackageName = packageName;
        }
    }

    private interface TestFunctor {
        void useCaptivePortal(CaptivePortal o);
    }

    private MyCaptivePortalImpl runCaptivePortalTest(TestFunctor f) {
        final MyCaptivePortalImpl cp = new MyCaptivePortalImpl();
        f.useCaptivePortal(new CaptivePortal(cp.asBinder()));
        return cp;
    }

    @Test
    public void testReportCaptivePortalDismissed() {
        final MyCaptivePortalImpl result =
                runCaptivePortalTest(c -> c.reportCaptivePortalDismissed());
        assertEquals(result.mCode, CaptivePortal.APP_RETURN_DISMISSED);
    }

    @Test
    public void testIgnoreNetwork() {
        final MyCaptivePortalImpl result = runCaptivePortalTest(c -> c.ignoreNetwork());
        assertEquals(result.mCode, CaptivePortal.APP_RETURN_UNWANTED);
    }

    @Test
    public void testUseNetwork() {
        final MyCaptivePortalImpl result = runCaptivePortalTest(c -> c.useNetwork());
        assertEquals(result.mCode, CaptivePortal.APP_RETURN_WANTED_AS_IS);
    }

    @Test
    public void testLogEvent() {
        final MyCaptivePortalImpl result = runCaptivePortalTest(c -> c.logEvent(
                MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_ACTIVITY,
                TEST_PACKAGE_NAME));
        assertEquals(result.mCode, MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_ACTIVITY);
        assertEquals(result.mPackageName, TEST_PACKAGE_NAME);
    }
}
