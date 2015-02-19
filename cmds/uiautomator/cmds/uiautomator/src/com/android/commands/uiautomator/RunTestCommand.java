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

package com.android.commands.uiautomator;

import android.os.Bundle;
import android.util.Log;

import com.android.commands.uiautomator.Launcher.Command;
import com.android.uiautomator.testrunner.UiAutomatorTestRunner;

import dalvik.system.DexFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Implementation of the runtest sub command
 *
 */
public class RunTestCommand extends Command {
    private static final String LOGTAG = RunTestCommand.class.getSimpleName();

    private static final String OUTPUT_SIMPLE = "simple";
    private static final String OUTPUT_FORMAT_KEY = "outputFormat";
    private static final String CLASS_PARAM = "class";
    private static final String JARS_PARAM = "jars";
    private static final String DEBUG_PARAM = "debug";
    private static final String RUNNER_PARAM = "runner";
    private static final String CLASS_SEPARATOR = ",";
    private static final String JARS_SEPARATOR = ":";
    private static final int ARG_OK = 0;
    private static final int ARG_FAIL_INCOMPLETE_E = -1;
    private static final int ARG_FAIL_INCOMPLETE_C = -2;
    private static final int ARG_FAIL_NO_CLASS = -3;
    private static final int ARG_FAIL_RUNNER = -4;
    private static final int ARG_FAIL_UNSUPPORTED = -99;

    private final Bundle mParams = new Bundle();
    private final List<String> mTestClasses = new ArrayList<String>();
    private boolean mDebug;
    private boolean mMonkey = false;
    private String mRunnerClassName;
    private UiAutomatorTestRunner mRunner;

    public RunTestCommand() {
        super("runtest");
    }

    @Override
    public void run(String[] args) {
        int ret = parseArgs(args);
        switch (ret) {
            case ARG_FAIL_INCOMPLETE_C:
                System.err.println("Incomplete '-c' parameter.");
                System.exit(ARG_FAIL_INCOMPLETE_C);
                break;
            case ARG_FAIL_INCOMPLETE_E:
                System.err.println("Incomplete '-e' parameter.");
                System.exit(ARG_FAIL_INCOMPLETE_E);
                break;
            case ARG_FAIL_UNSUPPORTED:
                System.err.println("Unsupported standalone parameter.");
                System.exit(ARG_FAIL_UNSUPPORTED);
                break;
            default:
                break;
        }
        if (mTestClasses.isEmpty()) {
            addTestClassesFromJars();
            if (mTestClasses.isEmpty()) {
                System.err.println("No test classes found.");
                System.exit(ARG_FAIL_NO_CLASS);
            }
        }
        getRunner().run(mTestClasses, mParams, mDebug, mMonkey);
    }

    private int parseArgs(String[] args) {
        // we are parsing for these parameters:
        // -e <key> <value>
        // key-value pairs
        // special ones are:
        // key is "class", parameter is passed onto JUnit as class name to run
        // key is "debug", parameter will determine whether to wait for debugger
        // to attach
        // -c <class name>
        // -s turns on the simple output format
        // equivalent to -e class <class name>, i.e. passed onto JUnit
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-e")) {
                if (i + 2 < args.length) {
                    String key = args[++i];
                    String value = args[++i];
                    if (CLASS_PARAM.equals(key)) {
                        addTestClasses(value);
                    } else if (DEBUG_PARAM.equals(key)) {
                        mDebug = "true".equals(value) || "1".equals(value);
                    } else if (RUNNER_PARAM.equals(key)) {
                        mRunnerClassName = value;
                    } else {
                        mParams.putString(key, value);
                    }
                } else {
                    return ARG_FAIL_INCOMPLETE_E;
                }
            } else if (args[i].equals("-c")) {
                if (i + 1 < args.length) {
                    addTestClasses(args[++i]);
                } else {
                    return ARG_FAIL_INCOMPLETE_C;
                }
            } else if (args[i].equals("--monkey")) {
                mMonkey = true;
            } else if (args[i].equals("-s")) {
                mParams.putString(OUTPUT_FORMAT_KEY, OUTPUT_SIMPLE);
            } else {
                return ARG_FAIL_UNSUPPORTED;
            }
        }
        return ARG_OK;
    }

    protected UiAutomatorTestRunner getRunner() {
        if (mRunner != null) {
            return mRunner;
        }

        if (mRunnerClassName == null) {
            mRunner = new UiAutomatorTestRunner();
            return mRunner;
        }
        // use reflection to get the runner
        Object o = null;
        try {
            Class<?> clazz = Class.forName(mRunnerClassName);
            o = clazz.newInstance();
        } catch (ClassNotFoundException cnfe) {
            System.err.println("Cannot find runner: " + mRunnerClassName);
            System.exit(ARG_FAIL_RUNNER);
        } catch (InstantiationException ie) {
            System.err.println("Cannot instantiate runner: " + mRunnerClassName);
            System.exit(ARG_FAIL_RUNNER);
        } catch (IllegalAccessException iae) {
            System.err.println("Constructor of runner " + mRunnerClassName + " is not accessibile");
            System.exit(ARG_FAIL_RUNNER);
        }
        try {
            UiAutomatorTestRunner runner = (UiAutomatorTestRunner)o;
            mRunner = runner;
            return runner;
        } catch (ClassCastException cce) {
            System.err.println("Specified runner is not subclass of "
                    + UiAutomatorTestRunner.class.getSimpleName());
            System.exit(ARG_FAIL_RUNNER);
        }
        // won't reach here
        return null;
    }

    /**
     * Add test classes from a potentially comma separated list
     * @param classes
     */
    private void addTestClasses(String classes) {
        String[] classArray = classes.split(CLASS_SEPARATOR);
        for (String clazz : classArray) {
            mTestClasses.add(clazz);
        }
    }

    /**
     * Add test classes from jars passed on the command line. Use this if nothing was explicitly
     * specified on the command line.
     */
    private void addTestClassesFromJars() {
        String jars = mParams.getString(JARS_PARAM);
        if (jars == null) return;

        String[] jarFileNames = jars.split(JARS_SEPARATOR);
        for (String fileName : jarFileNames) {
            fileName = fileName.trim();
            if (fileName.isEmpty()) continue;
            try {
                DexFile dexFile = new DexFile(fileName);
                for(Enumeration<String> e = dexFile.entries(); e.hasMoreElements();) {
                    String className = e.nextElement();
                    if (isTestClass(className)) {
                        mTestClasses.add(className);
                    }
                }
                dexFile.close();
            } catch (IOException e) {
                Log.w(LOGTAG, String.format("Could not read %s: %s", fileName, e.getMessage()));
            }
        }
    }

    /**
     * Tries to determine if a given class is a test class. A test class has to inherit from
     * UiAutomator test case and it must be a top-level class.
     * @param className
     * @return
     */
    private boolean isTestClass(String className) {
        try {
            Class<?> clazz = this.getClass().getClassLoader().loadClass(className);
            if (clazz.getEnclosingClass() != null) return false;
            return getRunner().getTestCaseFilter().accept(clazz);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String detailedOptions() {
        return "    runtest <class spec> [options]\n"
            + "    <class spec>: <JARS> < -c <CLASSES> | -e class <CLASSES> >\n"
            + "      <JARS>: a list of jar files containing test classes and dependencies. If\n"
            + "        the path is relative, it's assumed to be under /data/local/tmp. Use\n"
            + "        absolute path if the file is elsewhere. Multiple files can be\n"
            + "        specified, separated by space.\n"
            + "      <CLASSES>: a list of test class names to run, separated by comma. To\n"
            + "        a single method, use TestClass#testMethod format. The -e or -c option\n"
            + "        may be repeated. This option is not required and if not provided then\n"
            + "        all the tests in provided jars will be run automatically.\n"
            + "    options:\n"
            + "      --nohup: trap SIG_HUP, so test won't terminate even if parent process\n"
            + "               is terminated, e.g. USB is disconnected.\n"
            + "      -e debug [true|false]: wait for debugger to connect before starting.\n"
            + "      -e runner [CLASS]: use specified test runner class instead. If\n"
            + "        unspecified, framework default runner will be used.\n"
            + "      -e <NAME> <VALUE>: other name-value pairs to be passed to test classes.\n"
            + "        May be repeated.\n"
            + "      -e outputFormat simple | -s: enabled less verbose JUnit style output.\n";
    }

    @Override
    public String shortHelp() {
        return "executes UI automation tests";
    }

}
