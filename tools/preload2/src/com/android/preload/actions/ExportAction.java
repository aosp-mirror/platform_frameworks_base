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

import com.android.preload.DumpDataIO;
import com.android.preload.DumpTableModel;
import com.android.preload.Main;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.PrintWriter;

import javax.swing.AbstractAction;

public class ExportAction extends AbstractAction implements Runnable {
    private File lastSaveFile;
    private DumpTableModel dataTableModel;

    public ExportAction(DumpTableModel dataTableModel) {
        super("Export data");
        this.dataTableModel = dataTableModel;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        lastSaveFile = Main.getUI().showSaveDialog();
        if (lastSaveFile != null) {
            new Thread(this).start();
        }
    }

    @Override
    public void run() {
        Main.getUI().showWaitDialog();

        String serialized = DumpDataIO.serialize(dataTableModel.getData());

        if (serialized != null) {
            try {
                PrintWriter out = new PrintWriter(lastSaveFile);
                out.println(serialized);
                out.close();

                Main.getUI().hideWaitDialog();
            } catch (Exception e) {
                Main.getUI().hideWaitDialog();
                Main.getUI().showMessageDialog("Failed writing: " + e.getMessage());
            }
        }
    }
}