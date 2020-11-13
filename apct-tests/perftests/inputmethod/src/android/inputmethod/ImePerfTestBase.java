/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.inputmethod;

import static android.perftests.utils.PerfTestActivity.INTENT_EXTRA_ADD_EDIT_TEXT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.perftests.utils.PerfTestActivity;


import androidx.test.rule.ActivityTestRule;

import org.junit.BeforeClass;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

public class ImePerfTestBase {
    static final UiAutomation UI_AUTOMATION = getInstrumentation().getUiAutomation();
    static final long NANOS_PER_S = 1000L * 1000 * 1000;
    static final long TIME_1_S_IN_NS = 1 * NANOS_PER_S;
    static final long TIMEOUT_1_S_IN_MS = 1 * 1000L;

    @BeforeClass
    public static void setUpOnce() {
        final Context context = getInstrumentation().getContext();

        if (!context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            executeShellCommand("input keyevent KEYCODE_WAKEUP");
            executeShellCommand("wm dismiss-keyguard");
        }
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Executes shell command with reading the output. It may also used to block until the current
     * command is completed.
     */
    static ByteArrayOutputStream executeShellCommand(String command) {
        final ParcelFileDescriptor pfd = UI_AUTOMATION.executeShellCommand(command);
        final byte[] buf = new byte[512];
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int bytesRead;
        try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            while ((bytesRead = fis.read(buf)) != -1) {
                bytes.write(buf, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytes;
    }

    /** Returns how many iterations should run with method tracing. */
    static int getProfilingIterations() {
        return ImePerfRunPrecondition.sProfilingIterations;
    }

    static void runWithShellPermissionIdentity(Runnable runnable) {
        UI_AUTOMATION.adoptShellPermissionIdentity();
        try {
            runnable.run();
        } finally {
            UI_AUTOMATION.dropShellPermissionIdentity();
        }
    }

    static class SettingsSession<T> implements AutoCloseable {
        private final Consumer<T> mSetter;
        private final T mOriginalValue;
        private boolean mChanged;

        SettingsSession(T originalValue, Consumer<T> setter) {
            mOriginalValue = originalValue;
            mSetter = setter;
        }

        void set(T value) {
            if (Objects.equals(value, mOriginalValue)) {
                mChanged = false;
                return;
            }
            mSetter.accept(value);
            mChanged = true;
        }

        @Override
        public void close() {
            if (mChanged) {
                mSetter.accept(mOriginalValue);
            }
        }
    }

    /**
     * Provides an activity that keeps screen on and is able to wait for a stable lifecycle stage.
     */
    static class PerfTestActivityRule extends ActivityTestRule<PerfTestActivity> {
        private final Intent mStartIntent =
                new Intent(getInstrumentation().getTargetContext(), PerfTestActivity.class);

        PerfTestActivityRule() {
            this(false /* launchActivity */);
        }

        PerfTestActivityRule(boolean launchActivity) {
            super(PerfTestActivity.class, false /* initialTouchMode */, launchActivity);
        }

        @Override
        protected Intent getActivityIntent() {
            return mStartIntent;
        }

        @Override
        public PerfTestActivity launchActivity(Intent intent) {
            intent.putExtra(INTENT_EXTRA_ADD_EDIT_TEXT, true);
            return super.launchActivity(intent);
        }

        PerfTestActivity launchActivity() {
            return launchActivity(mStartIntent);
        }
    }

    static String[] buildArray(String[]... arrays) {
        int length = 0;
        for (String[] array : arrays) {
            length += array.length;
        }
        String[] newArray = new String[length];
        int offset = 0;
        for (String[] array : arrays) {
            System.arraycopy(array, 0, newArray, offset, array.length);
            offset += array.length;
        }
        return newArray;
    }
}
