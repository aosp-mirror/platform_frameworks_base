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

package com.android.preload.actions;

import com.android.preload.DumpData;
import com.android.preload.DumpTableModel;
import com.android.preload.Main;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;

/**
 * Compute an intersection of classes from the given data. A class is in the intersection if it
 * appears in at least the number of threshold given packages. An optional blacklist can be
 * used to filter classes from the intersection.
 */
public class ComputeThresholdAction extends AbstractThreadedAction {
    protected int threshold;
    private Pattern blacklist;
    private DumpTableModel dataTableModel;

    /**
     * Create an action with the given parameters. The blacklist is a regular expression
     * that filters classes.
     */
    public ComputeThresholdAction(String name, DumpTableModel dataTableModel, int threshold,
            String blacklist) {
        super(name);
        this.dataTableModel = dataTableModel;
        this.threshold = threshold;
        if (blacklist != null) {
            this.blacklist = Pattern.compile(blacklist);
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<DumpData> data = dataTableModel.getData();
        if (data.size() == 0) {
            Main.getUI().showMessageDialog("No data available, please scan packages or run "
                    + "monkeys.");
            return;
        }
        if (data.size() == 1) {
            Main.getUI().showMessageDialog("Cannot compute list from only one data set, please "
                    + "scan packages or run monkeys.");
            return;
        }

        super.actionPerformed(e);
    }

    @Override
    public void run() {
        Main.getUI().showWaitDialog();

        Map<String, Set<String>> uses = new HashMap<String, Set<String>>();
        for (DumpData d : dataTableModel.getData()) {
            Main.getUI().updateWaitDialog("Merging " + d.getPackageName());
            updateClassUse(d.getPackageName(), uses, getBootClassPathClasses(d.getDumpData()));
        }

        Main.getUI().updateWaitDialog("Computing thresholded set");
        Set<String> result = fromThreshold(uses, blacklist, threshold);
        Main.getUI().hideWaitDialog();

        boolean ret = Main.getUI().showConfirmDialog("Computed a set with " + result.size()
                + " classes, would you like to save to disk?", "Save?");
        if (ret) {
            File f = Main.getUI().showSaveDialog();
            if (f != null) {
                saveSet(result, f);
            }
        }
    }

    private Set<String> fromThreshold(Map<String, Set<String>> classUses, Pattern blacklist,
            int threshold) {
        TreeSet<String> ret = new TreeSet<>(); // TreeSet so it's nicely ordered by name.

        for (Map.Entry<String, Set<String>> e : classUses.entrySet()) {
            if (e.getValue().size() >= threshold) {
                if (blacklist == null || !blacklist.matcher(e.getKey()).matches()) {
                    ret.add(e.getKey());
                }
            }
        }

        return ret;
    }

    private static void updateClassUse(String pkg, Map<String, Set<String>> classUses,
            Set<String> classes) {
        for (String className : classes) {
            Set<String> old = classUses.get(className);
            if (old == null) {
                classUses.put(className, new HashSet<String>());
            }
            classUses.get(className).add(pkg);
        }
    }

    private static Set<String> getBootClassPathClasses(Map<String, String> source) {
        Set<String> ret = new HashSet<>();
        for (Map.Entry<String, String> e : source.entrySet()) {
            if (e.getValue() == null) {
                ret.add(e.getKey());
            }
        }
        return ret;
    }

    private static void saveSet(Set<String> result, File f) {
        try {
            PrintWriter out = new PrintWriter(f);
            for (String s : result) {
                out.println(s);
            }
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}