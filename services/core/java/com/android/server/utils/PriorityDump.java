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

import android.annotation.IntDef;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Helper for {@link android.os.Binder#dump(java.io.FileDescriptor, String[])} that supports the
 * {@link #PRIORITY_ARG} and {@link #PROTO_ARG} arguments.
 * <p>
 * Typical usage:
 *
 * <pre><code>
public class SpringfieldNuclearPowerPlant extends Binder {

 private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {

     @Override
     public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
       if (asProto) {
         ProtoOutputStream proto = new ProtoOutputStream(fd);
         proto.write(SpringfieldProto.DONUTS, 1);
         proto.flush();
       } else {
         pw.println("Donuts in the box: 1");
       }
     }

     @Override
     public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (asProto) {
          ProtoOutputStream proto = new ProtoOutputStream(fd);
          proto.write(SpringfieldProto.REACTOR_STATUS, DANGER_MELTDOWN_IMMINENT);
          proto.flush();
        } else {
          pw.println("Nuclear reactor status: DANGER - MELTDOWN IMMINENT");
        }
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

    $ adb shell dumpsys snpp --dump-priority CRITICAL
    Donuts in the box: 1

    $ adb shell dumpsys snpp --dump-priority NORMAL
    Nuclear reactor status: DANGER - MELTDOWN IMMINENT

    $ adb shell dumpsys snpp --dump-priority CRITICAL --proto
    //binary output

 * </code></pre>
 *
 *
 *
 * <p>To run the unit tests:
 * <pre><code>
 *
 atest FrameworksServicesTests:PriorityDumpTest
 * </code></pre>
 *
 *
 * @hide
 */
public final class PriorityDump {

    public static final String PRIORITY_ARG = "--dump-priority";
    public static final String PROTO_ARG = "--proto";
    public static final String PRIORITY_ARG_CRITICAL = "CRITICAL";
    public static final String PRIORITY_ARG_HIGH = "HIGH";
    public static final String PRIORITY_ARG_NORMAL = "NORMAL";

    private PriorityDump() {
        throw new UnsupportedOperationException();
    }

    /** Enum to switch through supported priority types */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({PRIORITY_TYPE_INVALID, PRIORITY_TYPE_CRITICAL, PRIORITY_TYPE_HIGH,
            PRIORITY_TYPE_NORMAL})
    private @interface PriorityType { }
    private static final int PRIORITY_TYPE_INVALID = 0;
    private static final int PRIORITY_TYPE_CRITICAL = 1;
    private static final int PRIORITY_TYPE_HIGH = 2;
    private static final int PRIORITY_TYPE_NORMAL = 3;

    /**
     * Parses {@code args} matching {@code --dump-priority} and/or {@code --proto}. The matching
     * arguments are stripped.
     * <p>
     * If priority args are passed as an argument, it will call the appropriate method and if proto
     * args are passed then the {@code asProto} flag is set.
     * <p>
     * For example, if called as {@code --dump-priority HIGH arg1 arg2 arg3}, it will call
     * <code>dumper.dumpHigh(fd, pw, {"arg1", "arg2", "arg3"}, false) </code>
     * <p>
     * If the {@code --dump-priority} is not set, it calls
     * {@link PriorityDumper#dump(FileDescriptor, PrintWriter, String[], boolean)} passing the whole
     * {@code args} instead.
     */
    public static void dump(PriorityDumper dumper, FileDescriptor fd, PrintWriter pw,
            String[] args) {
        boolean asProto = false;
        @PriorityType int priority = PRIORITY_TYPE_INVALID;

        if (args == null) {
            dumper.dump(fd, pw, args, asProto);
            return;
        }

        String[] strippedArgs = new String[args.length];
        int strippedCount = 0;
        for (int argIndex = 0; argIndex < args.length; argIndex++) {
            if (args[argIndex].equals(PROTO_ARG)) {
                asProto = true;
            } else if (args[argIndex].equals(PRIORITY_ARG)) {
                if (argIndex + 1 < args.length) {
                    argIndex++;
                    priority = getPriorityType(args[argIndex]);
                }
            } else {
                strippedArgs[strippedCount++] = args[argIndex];
            }
        }

        if (strippedCount < args.length) {
            strippedArgs = Arrays.copyOf(strippedArgs, strippedCount);
        }

        switch (priority) {
            case PRIORITY_TYPE_CRITICAL: {
                dumper.dumpCritical(fd, pw, strippedArgs, asProto);
                return;
            }
            case PRIORITY_TYPE_HIGH: {
                dumper.dumpHigh(fd, pw, strippedArgs, asProto);
                return;
            }
            case PRIORITY_TYPE_NORMAL: {
                dumper.dumpNormal(fd, pw, strippedArgs, asProto);
                return;
            }
            default: {
                dumper.dump(fd, pw, strippedArgs, asProto);
                return;
            }
        }
    }

    /**
     * Converts priority argument type to enum.
     */
    private static @PriorityType int getPriorityType(String arg) {
        switch (arg) {
            case PRIORITY_ARG_CRITICAL: {
                return PRIORITY_TYPE_CRITICAL;
            }
            case PRIORITY_ARG_HIGH: {
                return PRIORITY_TYPE_HIGH;
            }
            case PRIORITY_ARG_NORMAL: {
                return PRIORITY_TYPE_NORMAL;
            }
            default: {
                return PRIORITY_TYPE_INVALID;
            }
        }
    }

    /**
     * Helper for {@link android.os.Binder#dump(java.io.FileDescriptor, String[])} that supports the
     * {@link #PRIORITY_ARG} and {@link #PROTO_ARG} arguments.
     *
     * @hide
     */
    public interface PriorityDumper {

        /**
         * Dumps only the critical section.
         */
        @SuppressWarnings("unused")
        default void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                boolean asProto) {
        }

        /**
         * Dumps only the high-priority section.
         */
        @SuppressWarnings("unused")
        default void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
        }

        /**
         * Dumps only the normal section.
         */
        @SuppressWarnings("unused")
        default void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
        }

        /**
         * Dumps all sections.
         * <p>
         * This method is called when
         * {@link PriorityDump#dump(PriorityDumper, FileDescriptor, PrintWriter, String[])}
         * is called without priority arguments. By default, it calls the 3 {@code dumpTYPE}
         * methods, so sub-classes just need to implement the priority types they support.
         */
        @SuppressWarnings("unused")
        default void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            dumpCritical(fd, pw, args, asProto);
            dumpHigh(fd, pw, args, asProto);
            dumpNormal(fd, pw, args, asProto);
        }
    }
}
