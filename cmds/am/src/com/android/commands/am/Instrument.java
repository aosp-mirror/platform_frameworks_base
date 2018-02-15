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

package com.android.commands.am;

import android.app.IActivityManager;
import android.app.IInstrumentationWatcher;
import android.app.Instrumentation;
import android.app.UiAutomationConnection;
import android.content.ComponentName;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.AndroidException;
import android.util.proto.ProtoOutputStream;
import android.view.IWindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;


/**
 * Runs the am instrument command
 *
 * Test Result Code:
 * 1 - Test running
 * 0 - Test passed
 * -2 - assertion failure
 * -1 - other exceptions
 *
 * Session Result Code:
 * -1: Success
 * other: Failure
 */
public class Instrument {
    public static final String DEFAULT_LOG_DIR = "instrument-logs";

    private final IActivityManager mAm;
    private final IPackageManager mPm;
    private final IWindowManager mWm;

    // Command line arguments
    public String profileFile = null;
    public boolean wait = false;
    public boolean rawMode = false;
    boolean protoStd = false;  // write proto to stdout
    boolean protoFile = false;  // write proto to a file
    String logPath = null;
    public boolean noWindowAnimation = false;
    public boolean disableHiddenApiChecks = false;
    public String abi = null;
    public int userId = UserHandle.USER_CURRENT;
    public Bundle args = new Bundle();
    // Required
    public String componentNameArg;

    // Disable hidden API checks for the newly started instrumentation.
    // Must be kept in sync with ActivityManagerService.
    private static final int INSTRUMENTATION_FLAG_DISABLE_HIDDEN_API_CHECKS = 1 << 0;

    /**
     * Construct the instrument command runner.
     */
    public Instrument(IActivityManager am, IPackageManager pm) {
        mAm = am;
        mPm = pm;
        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
    }

    /**
     * Base class for status reporting.
     *
     * All the methods on this interface are called within the synchronized block
     * of the InstrumentationWatcher, so calls are in order.  However, that means
     * you must be careful not to do blocking operations because you don't know
     * exactly the locking dependencies.
     */
    private interface StatusReporter {
        /**
         * Status update for tests.
         */
        public void onInstrumentationStatusLocked(ComponentName name, int resultCode,
                Bundle results);

        /**
         * The tests finished.
         */
        public void onInstrumentationFinishedLocked(ComponentName name, int resultCode,
                Bundle results);

        /**
         * @param errorText a description of the error
         * @param commandError True if the error is related to the commandline, as opposed
         *      to a test failing.
         */
        public void onError(String errorText, boolean commandError);
    }

    private static Collection<String> sorted(Collection<String> list) {
        final ArrayList<String> copy = new ArrayList<>(list);
        Collections.sort(copy);
        return copy;
    }

    /**
     * Printer for the 'classic' text based status reporting.
     */
    private class TextStatusReporter implements StatusReporter {
        private boolean mRawMode;

        /**
         * Human-ish readable output.
         *
         * @param rawMode   In "raw mode" (true), all bundles are dumped.
         *                  In "pretty mode" (false), if a bundle includes
         *                  Instrumentation.REPORT_KEY_STREAMRESULT, just print that.
         */
        public TextStatusReporter(boolean rawMode) {
            mRawMode = rawMode;
        }

        @Override
        public void onInstrumentationStatusLocked(ComponentName name, int resultCode,
                Bundle results) {
            // pretty printer mode?
            String pretty = null;
            if (!mRawMode && results != null) {
                pretty = results.getString(Instrumentation.REPORT_KEY_STREAMRESULT);
            }
            if (pretty != null) {
                System.out.print(pretty);
            } else {
                if (results != null) {
                    for (String key : sorted(results.keySet())) {
                        System.out.println(
                                "INSTRUMENTATION_STATUS: " + key + "=" + results.get(key));
                    }
                }
                System.out.println("INSTRUMENTATION_STATUS_CODE: " + resultCode);
            }
        }

        @Override
        public void onInstrumentationFinishedLocked(ComponentName name, int resultCode,
                Bundle results) {
            // pretty printer mode?
            String pretty = null;
            if (!mRawMode && results != null) {
                pretty = results.getString(Instrumentation.REPORT_KEY_STREAMRESULT);
            }
            if (pretty != null) {
                System.out.println(pretty);
            } else {
                if (results != null) {
                    for (String key : sorted(results.keySet())) {
                        System.out.println(
                                "INSTRUMENTATION_RESULT: " + key + "=" + results.get(key));
                    }
                }
                System.out.println("INSTRUMENTATION_CODE: " + resultCode);
            }
        }

        @Override
        public void onError(String errorText, boolean commandError) {
            if (mRawMode) {
                System.out.println("onError: commandError=" + commandError + " message="
                        + errorText);
            }
            // The regular BaseCommand error printing will print the commandErrors.
            if (!commandError) {
                System.out.println(errorText);
            }
        }
    }

    /**
     * Printer for the protobuf based status reporting.
     */
    private class ProtoStatusReporter implements StatusReporter {

        private File mLog;

        ProtoStatusReporter() {
            if (protoFile) {
                if (logPath == null) {
                    File logDir = new File(Environment.getLegacyExternalStorageDirectory(),
                            DEFAULT_LOG_DIR);
                    if (!logDir.exists() && !logDir.mkdirs()) {
                        System.err.format("Unable to create log directory: %s\n",
                                logDir.getAbsolutePath());
                        protoFile = false;
                        return;
                    }
                    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-hhmmss-SSS", Locale.US);
                    String fileName = String.format("log-%s.instrumentation_data_proto",
                            format.format(new Date()));
                    mLog = new File(logDir, fileName);
                } else {
                    mLog = new File(Environment.getLegacyExternalStorageDirectory(), logPath);
                    File logDir = mLog.getParentFile();
                    if (!logDir.exists() && !logDir.mkdirs()) {
                        System.err.format("Unable to create log directory: %s\n",
                                logDir.getAbsolutePath());
                        protoFile = false;
                        return;
                    }
                }
                if (mLog.exists()) mLog.delete();
            }
        }

        @Override
        public void onInstrumentationStatusLocked(ComponentName name, int resultCode,
                Bundle results) {
            final ProtoOutputStream proto = new ProtoOutputStream();

            final long token = proto.start(InstrumentationData.Session.TEST_STATUS);
            proto.write(InstrumentationData.TestStatus.RESULT_CODE, resultCode);
            writeBundle(proto, InstrumentationData.TestStatus.RESULTS, results);
            proto.end(token);

            outputProto(proto);
        }

        @Override
        public void onInstrumentationFinishedLocked(ComponentName name, int resultCode,
                Bundle results) {
            final ProtoOutputStream proto = new ProtoOutputStream();

            final long token = proto.start(InstrumentationData.Session.SESSION_STATUS);
            proto.write(InstrumentationData.SessionStatus.STATUS_CODE,
                    InstrumentationData.SESSION_FINISHED);
            proto.write(InstrumentationData.SessionStatus.RESULT_CODE, resultCode);
            writeBundle(proto, InstrumentationData.SessionStatus.RESULTS, results);
            proto.end(token);

            outputProto(proto);
        }

        @Override
        public void onError(String errorText, boolean commandError) {
            final ProtoOutputStream proto = new ProtoOutputStream();

            final long token = proto.start(InstrumentationData.Session.SESSION_STATUS);
            proto.write(InstrumentationData.SessionStatus.STATUS_CODE,
                    InstrumentationData.SESSION_ABORTED);
            proto.write(InstrumentationData.SessionStatus.ERROR_TEXT, errorText);
            proto.end(token);

            outputProto(proto);
        }

        private void writeBundle(ProtoOutputStream proto, long fieldId, Bundle bundle) {
            final long bundleToken = proto.start(fieldId);

            for (final String key: sorted(bundle.keySet())) {
                final long entryToken = proto.startRepeatedObject(
                        InstrumentationData.ResultsBundle.ENTRIES);

                proto.write(InstrumentationData.ResultsBundleEntry.KEY, key);

                final Object val = bundle.get(key);
                if (val instanceof String) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_STRING,
                            (String)val);
                } else if (val instanceof Byte) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_INT,
                            ((Byte)val).intValue());
                } else if (val instanceof Double) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_DOUBLE, (double)val);
                } else if (val instanceof Float) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_FLOAT, (float)val);
                } else if (val instanceof Integer) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_INT, (int)val);
                } else if (val instanceof Long) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_LONG, (long)val);
                } else if (val instanceof Short) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_INT, (short)val);
                } else if (val instanceof Bundle) {
                    writeBundle(proto, InstrumentationData.ResultsBundleEntry.VALUE_BUNDLE,
                            (Bundle)val);
                } else if (val instanceof byte[]) {
                    proto.write(InstrumentationData.ResultsBundleEntry.VALUE_BYTES, (byte[])val);
                }

                proto.end(entryToken);
            }

            proto.end(bundleToken);
        }

        private void outputProto(ProtoOutputStream proto) {
            byte[] out = proto.getBytes();
            if (protoStd) {
                try {
                    System.out.write(out);
                    System.out.flush();
                } catch (IOException ex) {
                    System.err.println("Error writing finished response: ");
                    ex.printStackTrace(System.err);
                }
            }
            if (protoFile) {
                try (OutputStream os = new FileOutputStream(mLog, true)) {
                    os.write(proto.getBytes());
                    os.flush();
                } catch (IOException ex) {
                    System.err.format("Cannot write to %s:\n", mLog.getAbsolutePath());
                    ex.printStackTrace();
                }
            }
        }
    }


    /**
     * Callbacks from the remote instrumentation instance.
     */
    private class InstrumentationWatcher extends IInstrumentationWatcher.Stub {
        private final StatusReporter mReporter;

        private boolean mFinished = false;

        public InstrumentationWatcher(StatusReporter reporter) {
            mReporter = reporter;
        }

        @Override
        public void instrumentationStatus(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                mReporter.onInstrumentationStatusLocked(name, resultCode, results);
                notifyAll();
            }
        }

        @Override
        public void instrumentationFinished(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                mReporter.onInstrumentationFinishedLocked(name, resultCode, results);
                mFinished = true;
                notifyAll();
            }
        }

        public boolean waitForFinish() {
            synchronized (this) {
                while (!mFinished) {
                    try {
                        if (!mAm.asBinder().pingBinder()) {
                            return false;
                        }
                        wait(1000);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return true;
        }
    }

    /**
     * Figure out which component they really meant.
     */
    private ComponentName parseComponentName(String cnArg) throws Exception {
        if (cnArg.contains("/")) {
            ComponentName cn = ComponentName.unflattenFromString(cnArg);
            if (cn == null) throw new IllegalArgumentException("Bad component name: " + cnArg);
            return cn;
        } else {
            List<InstrumentationInfo> infos = mPm.queryInstrumentation(null, 0).getList();

            final int numInfos = infos == null ? 0: infos.size();
            ArrayList<ComponentName> cns = new ArrayList<>();
            for (int i = 0; i < numInfos; i++) {
                InstrumentationInfo info = infos.get(i);

                ComponentName c = new ComponentName(info.packageName, info.name);
                if (cnArg.equals(info.packageName)) {
                    cns.add(c);
                }
            }

            if (cns.size() == 0) {
                throw new IllegalArgumentException("No instrumentation found for: " + cnArg);
            } else if (cns.size() == 1) {
                return cns.get(0);
            } else {
                StringBuilder cnsStr = new StringBuilder();
                final int numCns = cns.size();
                for (int i = 0; i < numCns; i++) {
                    cnsStr.append(cns.get(i).flattenToString());
                    cnsStr.append(", ");
                }

                // Remove last ", "
                cnsStr.setLength(cnsStr.length() - 2);

                throw new IllegalArgumentException("Found multiple instrumentations: "
                        + cnsStr.toString());
            }
        }
    }

    /**
     * Run the instrumentation.
     */
    public void run() throws Exception {
        StatusReporter reporter = null;
        float[] oldAnims = null;

        try {
            // Choose which output we will do.
            if (protoFile || protoStd) {
                reporter = new ProtoStatusReporter();
            } else if (wait) {
                reporter = new TextStatusReporter(rawMode);
            }

            // Choose whether we have to wait for the results.
            InstrumentationWatcher watcher = null;
            UiAutomationConnection connection = null;
            if (reporter != null) {
                watcher = new InstrumentationWatcher(reporter);
                connection = new UiAutomationConnection();
            }

            // Set the window animation if necessary
            if (noWindowAnimation) {
                oldAnims = mWm.getAnimationScales();
                mWm.setAnimationScale(0, 0.0f);
                mWm.setAnimationScale(1, 0.0f);
                mWm.setAnimationScale(2, 0.0f);
            }

            // Figure out which component we are trying to do.
            final ComponentName cn = parseComponentName(componentNameArg);

            // Choose an ABI if necessary
            if (abi != null) {
                final String[] supportedAbis = Build.SUPPORTED_ABIS;
                boolean matched = false;
                for (String supportedAbi : supportedAbis) {
                    if (supportedAbi.equals(abi)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new AndroidException(
                            "INSTRUMENTATION_FAILED: Unsupported instruction set " + abi);
                }
            }

            // Start the instrumentation
            int flags = disableHiddenApiChecks ? INSTRUMENTATION_FLAG_DISABLE_HIDDEN_API_CHECKS : 0;
            if (!mAm.startInstrumentation(cn, profileFile, flags, args, watcher, connection, userId,
                        abi)) {
                throw new AndroidException("INSTRUMENTATION_FAILED: " + cn.flattenToString());
            }

            // If we have been requested to wait, do so until the instrumentation is finished.
            if (watcher != null) {
                if (!watcher.waitForFinish()) {
                    reporter.onError("INSTRUMENTATION_ABORTED: System has crashed.", false);
                    return;
                }
            }
        } catch (Exception ex) {
            // Report failures
            if (reporter != null) {
                reporter.onError(ex.getMessage(), true);
            }

            // And re-throw the exception
            throw ex;
        } finally {
            // Clean up
            if (oldAnims != null) {
                mWm.setAnimationScales(oldAnims);
            }
        }
    }
}

