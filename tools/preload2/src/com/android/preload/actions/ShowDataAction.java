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

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class ShowDataAction extends AbstractAction {
    private DumpTableModel dataTableModel;

    public ShowDataAction(DumpTableModel dataTableModel) {
        super("Show data");
        this.dataTableModel = dataTableModel;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        // TODO(agampe): Auto-generated method stub
        int selRow = Main.getUI().getSelectedDataTableRow();
        if (selRow != -1) {
            DumpData data = dataTableModel.getData().get(selRow);
            Map<String, Set<String>> inv = data.invertData();

            StringBuilder builder = new StringBuilder();

            // First bootclasspath.
            add(builder, "Boot classpath:", inv.get(null));

            // Now everything else.
            for (String k : inv.keySet()) {
                if (k != null) {
                    builder.append("==================\n\n");
                    add(builder, k, inv.get(k));
                }
            }

            JFrame newFrame = new JFrame(data.getPackageName() + " " + data.getDate());
            newFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            newFrame.getContentPane().add(new JScrollPane(new JTextArea(builder.toString())),
                    BorderLayout.CENTER);
            newFrame.setSize(800, 600);
            newFrame.setLocationRelativeTo(null);
            newFrame.setVisible(true);
        }
    }

    private void add(StringBuilder builder, String head, Set<String> set) {
        builder.append(head);
        builder.append('\n');
        addSet(builder, set);
        builder.append('\n');
    }

    private void addSet(StringBuilder builder, Set<String> set) {
        if (set == null) {
            builder.append("  NONE\n");
            return;
        }
        List<String> sorted = new ArrayList<>(set);
        Collections.sort(sorted);
        for (String s : sorted) {
            builder.append(s);
            builder.append('\n');
        }
    }
}