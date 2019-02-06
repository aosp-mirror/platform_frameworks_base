/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.os;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Handles argument parsing for args related to the zygote spawner.
 *
 * Current recognized args:
 * <ul>
 *   <li> --setuid=<i>uid of child process, defaults to 0</i>
 *   <li> --setgid=<i>gid of child process, defaults to 0</i>
 *   <li> --setgroups=<i>comma-separated list of supplimentary gid's</i>
 *   <li> --capabilities=<i>a pair of comma-separated integer strings
 * indicating Linux capabilities(2) set for child. The first string
 * represents the <code>permitted</code> set, and the second the
 * <code>effective</code> set. Precede each with 0 or
 * 0x for octal or hexidecimal value. If unspecified, both default to 0.
 * This parameter is only applied if the uid of the new process will
 * be non-0. </i>
 *   <li> --rlimit=r,c,m<i>tuple of values for setrlimit() call.
 *    <code>r</code> is the resource, <code>c</code> and <code>m</code>
 *    are the settings for current and max value.</i>
 *   <li> --instruction-set=<i>instruction-set-string</i> which instruction set to use/emulate.
 *   <li> --nice-name=<i>nice name to appear in ps</i>
 *   <li> --package-name=<i>package name this process belongs to</i>
 *   <li> --runtime-args indicates that the remaining arg list should
 * be handed off to com.android.internal.os.RuntimeInit, rather than
 * processed directly.
 * Android runtime startup (eg, Binder initialization) is also eschewed.
 *   <li> [--] &lt;args for RuntimeInit &gt;
 * </ul>
 */
class ZygoteArguments {

    /**
     * from --setuid
     */
    int mUid = 0;
    boolean mUidSpecified;

    /**
     * from --setgid
     */
    int mGid = 0;
    boolean mGidSpecified;

    /**
     * from --setgroups
     */
    int[] mGids;

    /**
     * From --runtime-flags.
     */
    int mRuntimeFlags;

    /**
     * From --mount-external
     */
    int mMountExternal = Zygote.MOUNT_EXTERNAL_NONE;

    /**
     * from --target-sdk-version.
     */
    int mTargetSdkVersion;
    boolean mTargetSdkVersionSpecified;

    /**
     * from --nice-name
     */
    String mNiceName;

    /**
     * from --capabilities
     */
    boolean mCapabilitiesSpecified;
    long mPermittedCapabilities;
    long mEffectiveCapabilities;

    /**
     * from --seinfo
     */
    boolean mSeInfoSpecified;
    String mSeInfo;

    /**
     * from all --rlimit=r,c,m
     */
    ArrayList<int[]> mRLimits;

    /**
     * from --invoke-with
     */
    String mInvokeWith;

    /** from --package-name */
    String mPackageName;

    /** from --packages-for-uid */
    String[] mPackagesForUid;

    /** from --visible-vols */
    String[] mVisibleVolIds;

    /** from --sandbox-id */
    String mSandboxId;

    /**
     * Any args after and including the first non-option arg (or after a '--')
     */
    String[] mRemainingArgs;

    /**
     * Whether the current arguments constitute an ABI list query.
     */
    boolean mAbiListQuery;

    /**
     * The instruction set to use, or null when not important.
     */
    String mInstructionSet;

    /**
     * The app data directory. May be null, e.g., for the system server. Note that this might not be
     * reliable in the case of process-sharing apps.
     */
    String mAppDataDir;

    /**
     * The APK path of the package to preload, when using --preload-package.
     */
    String mPreloadPackage;

    /**
     * A Base64 string representing a serialize ApplicationInfo Parcel,
     when using --preload-app.
     */
    String mPreloadApp;

    /**
     * The native library path of the package to preload, when using --preload-package.
     */
    String mPreloadPackageLibs;

    /**
     * The filename of the native library to preload, when using --preload-package.
     */
    String mPreloadPackageLibFileName;

    /**
     * The cache key under which to enter the preloaded package into the classloader cache, when
     * using --preload-package.
     */
    String mPreloadPackageCacheKey;

    /**
     * Whether this is a request to start preloading the default resources and classes. This
     * argument only makes sense when the zygote is in lazy preload mode (i.e, when it's started
     * with --enable-lazy-preload).
     */
    boolean mPreloadDefault;

    /**
     * Whether this is a request to start a zygote process as a child of this zygote. Set with
     * --start-child-zygote. The remaining arguments must include the CHILD_ZYGOTE_SOCKET_NAME_ARG
     * flag to indicate the abstract socket name that should be used for communication.
     */
    boolean mStartChildZygote;

    /**
     * Whether the current arguments constitute a request for the zygote's PID.
     */
    boolean mPidQuery;

    /**
     * Exemptions from API blacklisting. These are sent to the pre-forked zygote at boot time, or
     * when they change, via --set-api-blacklist-exemptions.
     */
    String[] mApiBlacklistExemptions;

    /**
     * Sampling rate for logging hidden API accesses to the event log. This is sent to the
     * pre-forked zygote at boot time, or when it changes, via --hidden-api-log-sampling-rate.
     */
    int mHiddenApiAccessLogSampleRate = -1;

    /**
     * Constructs instance and parses args
     *
     * @param args zygote command-line args
     */
    ZygoteArguments(String[] args) throws IllegalArgumentException {
        parseArgs(args);
    }

    /**
     * Parses the commandline arguments intended for the Zygote spawner (such as "--setuid=" and
     * "--setgid=") and creates an array containing the remaining args.
     *
     * Per security review bug #1112214, duplicate args are disallowed in critical cases to make
     * injection harder.
     */
    private void parseArgs(String[] args)
            throws IllegalArgumentException {
        int curArg = 0;

        boolean seenRuntimeArgs = false;

        boolean expectRuntimeArgs = true;
        for ( /* curArg */ ; curArg < args.length; curArg++) {
            String arg = args[curArg];

            if (arg.equals("--")) {
                curArg++;
                break;
            } else if (arg.startsWith("--setuid=")) {
                if (mUidSpecified) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }
                mUidSpecified = true;
                mUid = Integer.parseInt(
                    arg.substring(arg.indexOf('=') + 1));
            } else if (arg.startsWith("--setgid=")) {
                if (mGidSpecified) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }
                mGidSpecified = true;
                mGid = Integer.parseInt(
                    arg.substring(arg.indexOf('=') + 1));
            } else if (arg.startsWith("--target-sdk-version=")) {
                if (mTargetSdkVersionSpecified) {
                    throw new IllegalArgumentException(
                        "Duplicate target-sdk-version specified");
                }
                mTargetSdkVersionSpecified = true;
                mTargetSdkVersion = Integer.parseInt(
                    arg.substring(arg.indexOf('=') + 1));
            } else if (arg.equals("--runtime-args")) {
                seenRuntimeArgs = true;
            } else if (arg.startsWith("--runtime-flags=")) {
                mRuntimeFlags = Integer.parseInt(
                    arg.substring(arg.indexOf('=') + 1));
            } else if (arg.startsWith("--seinfo=")) {
                if (mSeInfoSpecified) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }
                mSeInfoSpecified = true;
                mSeInfo = arg.substring(arg.indexOf('=') + 1);
            } else if (arg.startsWith("--capabilities=")) {
                if (mCapabilitiesSpecified) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }
                mCapabilitiesSpecified = true;
                String capString = arg.substring(arg.indexOf('=') + 1);

                String[] capStrings = capString.split(",", 2);

                if (capStrings.length == 1) {
                    mEffectiveCapabilities = Long.decode(capStrings[0]);
                    mPermittedCapabilities = mEffectiveCapabilities;
                } else {
                    mPermittedCapabilities = Long.decode(capStrings[0]);
                    mEffectiveCapabilities = Long.decode(capStrings[1]);
                }
            } else if (arg.startsWith("--rlimit=")) {
                // Duplicate --rlimit arguments are specifically allowed.
                String[] limitStrings = arg.substring(arg.indexOf('=') + 1).split(",");

                if (limitStrings.length != 3) {
                    throw new IllegalArgumentException(
                        "--rlimit= should have 3 comma-delimited ints");
                }
                int[] rlimitTuple = new int[limitStrings.length];

                for (int i = 0; i < limitStrings.length; i++) {
                    rlimitTuple[i] = Integer.parseInt(limitStrings[i]);
                }

                if (mRLimits == null) {
                    mRLimits = new ArrayList();
                }

                mRLimits.add(rlimitTuple);
            } else if (arg.startsWith("--setgroups=")) {
                if (mGids != null) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }

                String[] params = arg.substring(arg.indexOf('=') + 1).split(",");

                mGids = new int[params.length];

                for (int i = params.length - 1; i >= 0; i--) {
                    mGids[i] = Integer.parseInt(params[i]);
                }
            } else if (arg.equals("--invoke-with")) {
                if (mInvokeWith != null) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }
                try {
                    mInvokeWith = args[++curArg];
                } catch (IndexOutOfBoundsException ex) {
                    throw new IllegalArgumentException(
                        "--invoke-with requires argument");
                }
            } else if (arg.startsWith("--nice-name=")) {
                if (mNiceName != null) {
                    throw new IllegalArgumentException(
                        "Duplicate arg specified");
                }
                mNiceName = arg.substring(arg.indexOf('=') + 1);
            } else if (arg.equals("--mount-external-default")) {
                mMountExternal = Zygote.MOUNT_EXTERNAL_DEFAULT;
            } else if (arg.equals("--mount-external-read")) {
                mMountExternal = Zygote.MOUNT_EXTERNAL_READ;
            } else if (arg.equals("--mount-external-write")) {
                mMountExternal = Zygote.MOUNT_EXTERNAL_WRITE;
            } else if (arg.equals("--mount-external-full")) {
                mMountExternal = Zygote.MOUNT_EXTERNAL_FULL;
            }  else if (arg.equals("--mount-external-installer")) {
                mMountExternal = Zygote.MOUNT_EXTERNAL_INSTALLER;
            }  else if (arg.equals("--mount-external-legacy")) {
                mMountExternal = Zygote.MOUNT_EXTERNAL_LEGACY;
            } else if (arg.equals("--query-abi-list")) {
                mAbiListQuery = true;
            } else if (arg.equals("--get-pid")) {
                mPidQuery = true;
            } else if (arg.startsWith("--instruction-set=")) {
                mInstructionSet = arg.substring(arg.indexOf('=') + 1);
            } else if (arg.startsWith("--app-data-dir=")) {
                mAppDataDir = arg.substring(arg.indexOf('=') + 1);
            } else if (arg.equals("--preload-app")) {
                mPreloadApp = args[++curArg];
            } else if (arg.equals("--preload-package")) {
                mPreloadPackage = args[++curArg];
                mPreloadPackageLibs = args[++curArg];
                mPreloadPackageLibFileName = args[++curArg];
                mPreloadPackageCacheKey = args[++curArg];
            } else if (arg.equals("--preload-default")) {
                mPreloadDefault = true;
                expectRuntimeArgs = false;
            } else if (arg.equals("--start-child-zygote")) {
                mStartChildZygote = true;
            } else if (arg.equals("--set-api-blacklist-exemptions")) {
                // consume all remaining args; this is a stand-alone command, never included
                // with the regular fork command.
                mApiBlacklistExemptions = Arrays.copyOfRange(args, curArg + 1, args.length);
                curArg = args.length;
                expectRuntimeArgs = false;
            } else if (arg.startsWith("--hidden-api-log-sampling-rate=")) {
                String rateStr = arg.substring(arg.indexOf('=') + 1);
                try {
                    mHiddenApiAccessLogSampleRate = Integer.parseInt(rateStr);
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException(
                        "Invalid log sampling rate: " + rateStr, nfe);
                }
                expectRuntimeArgs = false;
            } else if (arg.startsWith("--package-name=")) {
                if (mPackageName != null) {
                    throw new IllegalArgumentException("Duplicate arg specified");
                }
                mPackageName = arg.substring(arg.indexOf('=') + 1);
            } else if (arg.startsWith("--packages-for-uid=")) {
                mPackagesForUid = arg.substring(arg.indexOf('=') + 1).split(",");
            } else if (arg.startsWith("--visible-vols=")) {
                mVisibleVolIds = arg.substring(arg.indexOf('=') + 1).split(",");
            } else if (arg.startsWith("--sandbox-id=")) {
                if (mSandboxId != null) {
                    throw new IllegalArgumentException("Duplicate arg specified");
                }
                mSandboxId = arg.substring(arg.indexOf('=') + 1);
            } else {
                break;
            }
        }

        if (mAbiListQuery || mPidQuery) {
            if (args.length - curArg > 0) {
                throw new IllegalArgumentException("Unexpected arguments after --query-abi-list.");
            }
        } else if (mPreloadPackage != null) {
            if (args.length - curArg > 0) {
                throw new IllegalArgumentException(
                    "Unexpected arguments after --preload-package.");
            }
        } else if (mPreloadApp != null) {
            if (args.length - curArg > 0) {
                throw new IllegalArgumentException(
                    "Unexpected arguments after --preload-app.");
            }
        } else if (expectRuntimeArgs) {
            if (!seenRuntimeArgs) {
                throw new IllegalArgumentException("Unexpected argument : " + args[curArg]);
            }

            mRemainingArgs = new String[args.length - curArg];
            System.arraycopy(args, curArg, mRemainingArgs, 0, mRemainingArgs.length);
        }

        if (mStartChildZygote) {
            boolean seenChildSocketArg = false;
            for (String arg : mRemainingArgs) {
                if (arg.startsWith(Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG)) {
                    seenChildSocketArg = true;
                    break;
                }
            }
            if (!seenChildSocketArg) {
                throw new IllegalArgumentException("--start-child-zygote specified "
                        + "without " + Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG);
            }
        }
    }
}
