/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.utils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Helper for {@link android.os.Binder#dump(java.io.FileDescriptor, String[])} that supports the
 * {@link #PRIORITY_ARG} argument.
 * <p>
 * Typical usage:
 *
 * <pre><code>
public class SpringfieldNuclearPowerPlant extends Binder {

 private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {

     @Override
     public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args) {
       pw.println("Donuts in the box: 1");
     }

     @Override
     public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args) {
       pw.println("Nuclear reactor status: DANGER - MELTDOWN IMMINENT");
     }
  };

  @Override
  protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
      PriorityDump.dump(mPriorityDumper, fd, pw, args);
  }
}

 * </code></pre>
 *
 * <strong>Disclaimer</strong>: a real-life service should prioritize core status over donuts :-)
 *
 * <p>Then to invoke it:
 *
 * <pre><code>
 *
    $ adb shell dumpsys snpp
    Donuts in the box: 1
    Nuclear reactor status: DANGER - MELTDOWN IMMINENT

    $ adb shell dumpsys snpp --dump_priority CRITICAL
    Donuts in the box: 1

    $ adb shell dumpsys snpp --dump_priority NORMAL
    Nuclear reactor status: DANGER - MELTDOWN IMMINENT

 * </code></pre>
 *
 *
 *
 * <p>To run the unit tests:
 * <pre><code>
 *
 mmm -j32 frameworks/base/services/tests/servicestests/ && \
 adb install -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk && \
 adb shell am instrument -e class "com.android.server.utils.PriorityDumpTest" \
 -w "com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner"

 * </code></pre>
 *
 *
 * @hide
 */
public final class PriorityDump {

    public static final String PRIORITY_ARG = "--dump_priority";

    private PriorityDump() {
        throw new UnsupportedOperationException();
    }

    /**
     * Parses {@code} and call the proper {@link PriorityDumper} method when the first argument is
     * {@code --dump_priority}, stripping the priority and its type.
     * <p>
     * For example, if called as {@code --dump_priority HIGH arg1 arg2 arg3}, it will call
     * <code>dumper.dumpHigh(fd, pw, {"arg1", "arg2", "arg3"}) </code>
     * <p>
     * If the {@code --dump_priority} is not set, it calls
     * {@link PriorityDumper#dump(FileDescriptor, PrintWriter, String[])} passing the whole
     * {@code args} instead.
     */
    public static void dump(PriorityDumper dumper, FileDescriptor fd, PrintWriter pw,
            String[] args) {
        if (args != null && args.length >= 2 && args[0].equals(PRIORITY_ARG)) {
            final String priority = args[1];
            switch (priority) {
                case "CRITICAL": {
                    dumper.dumpCritical(fd, pw, getStrippedArgs(args));
                    return;
                }
                case "HIGH": {
                    dumper.dumpHigh(fd, pw, getStrippedArgs(args));
                    return;
                }
                case "NORMAL": {
                    dumper.dumpNormal(fd, pw, getStrippedArgs(args));
                    return;
                }
            }
        }
        dumper.dump(fd, pw, args);
    }

    /**
     * Gets an array without the {@code --dump_priority PRIORITY} prefix.
     */
    private static String[] getStrippedArgs(String[] args) {
        final String[] stripped = new String[args.length - 2];
        System.arraycopy(args, 2, stripped, 0, stripped.length);
        return stripped;
    }

    /**
     * Helper for {@link android.os.Binder#dump(java.io.FileDescriptor, String[])} that supports the
     * {@link #PRIORITY_ARG} argument.
     *
     * @hide
     */
    public static interface PriorityDumper {

        /**
         * Dumps only the critical section.
         */
        @SuppressWarnings("unused")
        default void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args) {
        }

        /**
         * Dumps only the high-priority section.
         */
        @SuppressWarnings("unused")
        default void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args) {
        }

        /**
         * Dumps only the normal section.
         */
        @SuppressWarnings("unused")
        default void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args) {
        }

        /**
         * Dumps all sections.
         * <p>
         * This method is called when
         * {@link PriorityDump#dump(PriorityDumper, FileDescriptor, PrintWriter, String[])} is
         * called without priority arguments. By default, it calls the 3 {@code dumpTYPE} methods,
         * so sub-classes just need to implement the priority types they support.
         */
        @SuppressWarnings("unused")
        default void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            dumpCritical(fd, pw, args);
            dumpHigh(fd, pw, args);
            dumpNormal(fd, pw, args);
        }
    }
}
