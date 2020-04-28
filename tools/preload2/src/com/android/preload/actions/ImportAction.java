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
import com.android.preload.DumpDataIO;
import com.android.preload.DumpTableModel;
import com.android.preload.Main;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.Collection;

import javax.swing.AbstractAction;

public class ImportAction extends AbstractThreadedAction {
    private File[] lastOpenFiles;
    private DumpTableModel dataTableModel;

    public ImportAction(DumpTableModel dataTableModel) {
        super("Import data");
        this.dataTableModel = dataTableModel;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        lastOpenFiles = Main.getUI().showOpenDialog(true);
        if (lastOpenFiles != null) {
            super.actionPerformed(e);
        }
    }

    @Override
    public void run() {
        Main.getUI().showWaitDialog();

        try {
            for (File f : lastOpenFiles) {
                try {
                    Collection<DumpData> data = DumpDataIO.deserialize(f);

                    for (DumpData d : data) {
                        dataTableModel.addData(d);
                    }
                } catch (Exception e) {
                    Main.getUI().showMessageDialog("Failed reading: " + e.getMessage());
                }
            }
        } finally {
            Main.getUI().hideWaitDialog();
        }

    }
}