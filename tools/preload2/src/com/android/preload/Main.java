/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.preload;

import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
import com.android.preload.actions.ClearTableAction;
import com.android.preload.actions.ComputeThresholdAction;
import com.android.preload.actions.ComputeThresholdXAction;
import com.android.preload.actions.DeviceSpecific;
import com.android.preload.actions.ExportAction;
import com.android.preload.actions.ImportAction;
import com.android.preload.actions.ReloadListAction;
import com.android.preload.actions.RunMonkeyAction;
import com.android.preload.actions.ScanAllPackagesAction;
import com.android.preload.actions.ScanPackageAction;
import com.android.preload.actions.ShowDataAction;
import com.android.preload.actions.WritePreloadedClassesAction;
import com.android.preload.classdataretrieval.ClassDataRetriever;
import com.android.preload.classdataretrieval.hprof.Hprof;
import com.android.preload.classdataretrieval.jdwp.JDWPClassDataRetriever;
import com.android.preload.ui.IUI;
import com.android.preload.ui.SequenceUI;
import com.android.preload.ui.SwingUI;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.swing.Action;
import javax.swing.DefaultListModel;

public class Main {

    /**
     * Enable tracing mode. This is a work-in-progress to derive compiled-methods data, so it is
     * off for now.
     */
    public final static boolean ENABLE_TRACING = false;

    /**
     * Ten-second timeout.
     */
    public final static int DEFAULT_TIMEOUT_MILLIS = 10 * 1000;

    /**
     * Hprof timeout. Two minutes.
     */
    public final static int HPROF_TIMEOUT_MILLIS = 120 * 1000;

    private IDevice device;
    private static ClientUtils clientUtils;

    private DumpTableModel dataTableModel;
    private DefaultListModel<Client> clientListModel;

    private IUI ui;

    // Actions that need to be updated once a device is selected.
    private Collection<DeviceSpecific> deviceSpecificActions;

    // Current main instance.
    private static Main top;
    private static boolean useJdwpClassDataRetriever = false;

    public final static String CLASS_PRELOAD_BLACKLIST = "android.app.AlarmManager$" + "|"
            + "android.app.SearchManager$" + "|" + "android.os.FileObserver$" + "|"
            + "com.android.server.PackageManagerService\\$AppDirObserver$" + "|" +


            // Threads
            "android.os.AsyncTask$" + "|" + "android.pim.ContactsAsyncHelper$" + "|"
            + "android.webkit.WebViewClassic\\$1$" + "|" + "java.lang.ProcessManager$" + "|"
            + "(.*\\$NoPreloadHolder$)";

    public final static String SCAN_ALL_CMD = "scan-all";
    public final static String SCAN_PACKAGE_CMD = "scan";
    public final static String COMPUTE_FILE_CMD = "comp";
    public final static String EXPORT_CMD = "export";
    public final static String IMPORT_CMD = "import";
    public final static String WRITE_CMD = "write";

    /**
     * @param args
     */
    public static void main(String[] args) {
        Main m;
        if (args.length > 0 && args[0].equals("--seq")) {
            m = createSequencedMain(args);
        } else {
            m = new Main(new SwingUI());
        }

        top = m;
        m.startUp();
    }

    public Main(IUI ui) {
        this.ui = ui;

        clientListModel = new DefaultListModel<Client>();
        dataTableModel = new DumpTableModel();

        clientUtils = new ClientUtils(DEFAULT_TIMEOUT_MILLIS);  // Client utils with 10s timeout.

        List<Action> actions = new ArrayList<Action>();
        actions.add(new ReloadListAction(clientUtils, null, clientListModel));
        actions.add(new ClearTableAction(dataTableModel));
        actions.add(new RunMonkeyAction(null, dataTableModel));
        actions.add(new ScanPackageAction(clientUtils, null, dataTableModel));
        actions.add(new ScanAllPackagesAction(clientUtils, null, dataTableModel));
        actions.add(new ComputeThresholdAction("Compute preloaded-classes", dataTableModel, 2,
                CLASS_PRELOAD_BLACKLIST));
        actions.add(new ComputeThresholdAction("Compute compiled-classes", dataTableModel, 1,
                null));
        actions.add(new ComputeThresholdXAction("Compute(X)", dataTableModel,
                CLASS_PRELOAD_BLACKLIST));
        actions.add(new WritePreloadedClassesAction(clientUtils, null, dataTableModel));
        actions.add(new ShowDataAction(dataTableModel));
        actions.add(new ImportAction(dataTableModel));
        actions.add(new ExportAction(dataTableModel));

        deviceSpecificActions = new ArrayList<DeviceSpecific>();
        for (Action a : actions) {
            if (a instanceof DeviceSpecific) {
                deviceSpecificActions.add((DeviceSpecific)a);
            }
        }

        ui.prepare(clientListModel, dataTableModel, actions);
    }

    /**
     * @param args
     * @return
     */
    private static Main createSequencedMain(String[] args) {
        SequenceUI ui = new SequenceUI();
        Main main = new Main(ui);

        Iterator<String> it = Arrays.asList(args).iterator();
        it.next();  // --seq
        // Setup
        ui.choice("#" + it.next());  // Device.
        ui.confirmNo();              // Prepare: no.
        // Actions
        try {
            while (it.hasNext()) {
                String op = it.next();
                // Operation: Scan a single package
                if (SCAN_PACKAGE_CMD.equals(op)) {
                    System.out.println("Scanning package.");
                    ui.action(ScanPackageAction.class);
                    ui.client(it.next());
                // Operation: Scan all packages
                } else if (SCAN_ALL_CMD.equals(op)) {
                    System.out.println("Scanning all packages.");
                    ui.action(ScanAllPackagesAction.class);
                // Operation: Export the output to a file
                } else if (EXPORT_CMD.equals(op)) {
                    System.out.println("Exporting data.");
                    ui.action(ExportAction.class);
                    ui.output(new File(it.next()));
                // Operation: Import the input from a file or directory
                } else if (IMPORT_CMD.equals(op)) {
                    System.out.println("Importing data.");
                    File file = new File(it.next());
                    if (!file.exists()) {
                        throw new RuntimeException(
                                String.format("File does not exist, %s.", file.getAbsolutePath()));
                    } else if (file.isFile()) {
                        ui.action(ImportAction.class);
                        ui.input(file);
                    } else if (file.isDirectory()) {
                        for (File content : file.listFiles()) {
                            ui.action(ImportAction.class);
                            ui.input(content);
                        }
                    }
                // Operation: Compute preloaded classes with specific threshold
                } else if (COMPUTE_FILE_CMD.equals(op)) {
                    System.out.println("Compute preloaded classes.");
                    ui.action(ComputeThresholdXAction.class);
                    ui.input(it.next());
                    ui.confirmYes();
                    ui.output(new File(it.next()));
                // Operation: Write preloaded classes from a specific file
                } else if (WRITE_CMD.equals(op)) {
                    System.out.println("Writing preloaded classes.");
                    ui.action(WritePreloadedClassesAction.class);
                    ui.input(new File(it.next()));
                }
            }
        } catch (NoSuchElementException e) {
            System.out.println("Failed to parse action sequence correctly.");
            throw e;
        }

        return main;
    }

    public static IUI getUI() {
        return top.ui;
    }

    public static ClassDataRetriever getClassDataRetriever() {
        if (useJdwpClassDataRetriever) {
            return new JDWPClassDataRetriever();
        } else {
            return new Hprof(HPROF_TIMEOUT_MILLIS);
        }
    }

    public IDevice getDevice() {
        return device;
    }

    public void setDevice(IDevice device) {
        this.device = device;
        for (DeviceSpecific ds : deviceSpecificActions) {
            ds.setDevice(device);
        }
    }

    public DefaultListModel<Client> getClientListModel() {
        return clientListModel;
    }

    static class DeviceWrapper {
        IDevice device;

        public DeviceWrapper(IDevice d) {
            device = d;
        }

        @Override
        public String toString() {
            return device.getName() + " (#" + device.getSerialNumber() + ")";
        }
    }

    private void startUp() {
        getUI().showWaitDialog();
        initDevice();

        // Load clients.
        new ReloadListAction(clientUtils, getDevice(), clientListModel).run();

        getUI().hideWaitDialog();
        getUI().ready();
    }

    private void initDevice() {
        DeviceUtils.init(DEFAULT_TIMEOUT_MILLIS);

        IDevice devices[] = DeviceUtils.findDevices(DEFAULT_TIMEOUT_MILLIS);
        if (devices == null || devices.length == 0) {
            throw new RuntimeException("Could not find any devices...");
        }

        getUI().hideWaitDialog();

        DeviceWrapper deviceWrappers[] = new DeviceWrapper[devices.length];
        for (int i = 0; i < devices.length; i++) {
            deviceWrappers[i] = new DeviceWrapper(devices[i]);
        }

        DeviceWrapper ret = Main.getUI().showChoiceDialog("Choose a device", "Choose device",
                deviceWrappers);
        if (ret != null) {
            setDevice(ret.device);
        } else {
            System.exit(0);
        }

        boolean prepare = Main.getUI().showConfirmDialog("Prepare device?",
                "Do you want to prepare the device? This is highly recommended.");
        if (prepare) {
            String buildType = DeviceUtils.getBuildType(device);
            if (buildType == null || (!buildType.equals("userdebug") && !buildType.equals("eng"))) {
                Main.getUI().showMessageDialog("Need a userdebug or eng build! (Found " + buildType
                        + ")");
                return;
            }
            if (DeviceUtils.hasPrebuiltBootImage(device)) {
                Main.getUI().showMessageDialog("Cannot prepare a device with pre-optimized boot "
                        + "image!");
                return;
            }

            if (ENABLE_TRACING) {
                DeviceUtils.enableTracing(device);
            }

            Main.getUI().showMessageDialog("The device will reboot. This will potentially take a "
                    + "long time. Please be patient.");
            boolean success = false;
            try {
                success = DeviceUtils.overwritePreloaded(device, null, 15 * 60);
            } catch (Exception e) {
                System.err.println(e);
            } finally {
                if (!success) {
                    Main.getUI().showMessageDialog(
                            "Removing preloaded-classes failed unexpectedly!");
                }
            }
        }
    }

    public static Map<String, String> findAndGetClassData(IDevice device, String packageName)
            throws Exception {
        Client client = clientUtils.findClient(device, packageName, -1);
        if (client == null) {
            throw new RuntimeException("Could not find client...");
        }
        System.out.println("Found client: " + client);

        return getClassDataRetriever().getClassData(client);
    }

}
