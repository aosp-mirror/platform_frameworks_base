/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.view;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delegate used to provide new implementation of a select few methods of {@link Choreographer}
 *
 * Through the layoutlib_create tool, the original  methods of Choreographer have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 */
public class Choreographer_Delegate {
    static final AtomicReference<Choreographer> mInstance = new AtomicReference<Choreographer>();

    @LayoutlibDelegate
    public static Choreographer getInstance() {
        if (mInstance.get() == null) {
            mInstance.compareAndSet(null, Choreographer.getInstance_Original());
        }

        return mInstance.get();
    }

    @LayoutlibDelegate
    public static float getRefreshRate() {
        return 60.f;
    }

    @LayoutlibDelegate
    static void scheduleVsyncLocked(Choreographer thisChoreographer) {
        // do nothing
    }

    public static void doFrame(long frameTimeNanos) {
        Choreographer thisChoreographer = Choreographer.getInstance();

        thisChoreographer.mLastFrameTimeNanos = frameTimeNanos;

        thisChoreographer.mFrameInfo.markInputHandlingStart();
        thisChoreographer.doCallbacks(Choreographer.CALLBACK_INPUT, frameTimeNanos);

        thisChoreographer.mFrameInfo.markAnimationsStart();
        thisChoreographer.doCallbacks(Choreographer.CALLBACK_ANIMATION, frameTimeNanos);

        thisChoreographer.mFrameInfo.markPerformTraversalsStart();
        thisChoreographer.doCallbacks(Choreographer.CALLBACK_TRAVERSAL, frameTimeNanos);

        thisChoreographer.doCallbacks(Choreographer.CALLBACK_COMMIT, frameTimeNanos);
    }

    public static void dispose() {
        try {
            Field threadInstanceField = Choreographer.class.getDeclaredField("sThreadInstance");
            threadInstanceField.setAccessible(true);
            @SuppressWarnings("unchecked") ThreadLocal<Choreographer> threadInstance =
                    (ThreadLocal<Choreographer>) threadInstanceField.get(null);
            threadInstance.remove();
        } catch (ReflectiveOperationException e) {
            assert false;
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Unable to clear Choreographer memory.", e, null);
        }
    }
}
