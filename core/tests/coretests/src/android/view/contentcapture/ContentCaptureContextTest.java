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
package android.view.contentcapture;

import static com.google.common.truth.Truth.assertThat;

import android.app.assist.ActivityId;
import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link ContentCaptureContext}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureContextTest}
 */
@RunWith(JUnit4.class)
public class ContentCaptureContextTest {

    @Test
    public void testConstructorAdditionalFlags() {
        final ComponentName componentName = new ComponentName("component", "name");
        final IBinder token = new Binder();
        final ContentCaptureContext ctx = new ContentCaptureContext(/* clientContext= */ null,
                new ActivityId(/* taskId= */ 666, token), componentName, /* displayId= */
                42, /* flags= */ 1);
        final ContentCaptureContext newCtx = new ContentCaptureContext(ctx, /* extraFlags= */ 2);
        assertThat(newCtx.getFlags()).isEqualTo(3);
        assertThat(newCtx.getActivityComponent()).isEqualTo(componentName);
        ActivityId activityId = newCtx.getActivityId();
        assertThat(activityId).isNotNull();
        assertThat(activityId.getTaskId()).isEqualTo(666);
        assertThat(activityId.getToken()).isEqualTo(token);
        assertThat(newCtx.getDisplayId()).isEqualTo(42);
        assertThat(newCtx.getExtras()).isNull();
        assertThat(newCtx.getLocusId()).isNull();
        assertThat(newCtx.getParentSessionId()).isNull();
    }
}
