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

package com.android.preload.ui;

import com.android.ddmlib.Client;
import com.android.ddmlib.ClientData;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.util.List;

import javax.swing.Action;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;

public class SwingUI extends JFrame implements IUI {

    private JList<Client> clientList;
    private JTable dataTable;

    // Shared file chooser, means the directory is retained.
    private JFileChooser jfc;

    public SwingUI() {
        super("Preloaded-classes computation");
    }

    @Override
    public boolean isSingleThreaded() {
        return false;
    }

    @Override
    public void prepare(ListModel<Client> clientListModel, TableModel dataTableModel,
            List<Action> actions) {
        getContentPane().add(new JScrollPane(clientList = new JList<Client>(clientListModel)),
                BorderLayout.WEST);
        clientList.setCellRenderer(new ClientListCellRenderer());
        // clientList.addListSelectionListener(listener);

        dataTable = new JTable(dataTableModel);
        getContentPane().add(new JScrollPane(dataTable), BorderLayout.CENTER);

        JToolBar toolbar = new JToolBar(JToolBar.HORIZONTAL);
        for (Action a : actions) {
            if (a == null) {
                toolbar.addSeparator();
            } else {
                toolbar.add(a);
            }
        }
        getContentPane().add(toolbar, BorderLayout.PAGE_START);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setBounds(100, 100, 800, 600);

        setVisible(true);
    }

    @Override
    public void ready() {
    }

    @Override
    public Client getSelectedClient() {
        return clientList.getSelectedValue();
    }

    @Override
    public int getSelectedDataTableRow() {
        return dataTable.getSelectedRow();
    }

    private JDialog currentWaitDialog = null;

    @Override
    public void showWaitDialog() {
        if (currentWaitDialog == null) {
            currentWaitDialog = new JDialog(this, "Please wait...", true);
            currentWaitDialog.getContentPane().add(new JLabel("Please be patient."),
                    BorderLayout.CENTER);
            JProgressBar progress = new JProgressBar(JProgressBar.HORIZONTAL);
            progress.setIndeterminate(true);
            currentWaitDialog.getContentPane().add(progress, BorderLayout.SOUTH);
            currentWaitDialog.setSize(200, 100);
            currentWaitDialog.setLocationRelativeTo(null);
            showWaitDialogLater();
        }
    }

    private void showWaitDialogLater() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (currentWaitDialog != null) {
                    currentWaitDialog.setVisible(true); // This is blocking.
                }
            }
        });
    }

    @Override
    public void updateWaitDialog(String s) {
        if (currentWaitDialog != null) {
            ((JLabel) currentWaitDialog.getContentPane().getComponent(0)).setText(s);
            Dimension prefSize = currentWaitDialog.getPreferredSize();
            Dimension curSize = currentWaitDialog.getSize();
            if (prefSize.width > curSize.width || prefSize.height > curSize.height) {
                currentWaitDialog.setSize(Math.max(prefSize.width, curSize.width),
                        Math.max(prefSize.height, curSize.height));
                currentWaitDialog.invalidate();
            }
        }
    }

    @Override
    public void hideWaitDialog() {
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
            currentWaitDialog = null;
        }
    }

    @Override
    public void showMessageDialog(String s) {
        // Hide the wait dialog...
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
        }

        try {
            JOptionPane.showMessageDialog(this, s);
        } finally {
            // And reshow it afterwards...
            if (currentWaitDialog != null) {
                showWaitDialogLater();
            }
        }
    }

    @Override
    public boolean showConfirmDialog(String title, String message) {
        // Hide the wait dialog...
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
        }

        try {
            return JOptionPane.showConfirmDialog(this, title, message, JOptionPane.YES_NO_OPTION)
                    == JOptionPane.YES_OPTION;
        } finally {
            // And reshow it afterwards...
            if (currentWaitDialog != null) {
                showWaitDialogLater();
            }
        }
    }

    @Override
    public String showInputDialog(String message) {
        // Hide the wait dialog...
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
        }

        try {
            return JOptionPane.showInputDialog(message);
        } finally {
            // And reshow it afterwards...
            if (currentWaitDialog != null) {
                showWaitDialogLater();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T showChoiceDialog(String title, String message, T[] choices) {
        // Hide the wait dialog...
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
        }

        try{
            return (T)JOptionPane.showInputDialog(this,
                    title,
                    message,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    choices,
                    choices[0]);
        } finally {
            // And reshow it afterwards...
            if (currentWaitDialog != null) {
                showWaitDialogLater();
            }
        }
    }

    @Override
    public File showSaveDialog() {
        // Hide the wait dialog...
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
        }

        try{
            if (jfc == null) {
                jfc = new JFileChooser();
            }

            int ret = jfc.showSaveDialog(this);
            if (ret == JFileChooser.APPROVE_OPTION) {
                return jfc.getSelectedFile();
            } else {
                return null;
            }
        } finally {
            // And reshow it afterwards...
            if (currentWaitDialog != null) {
                showWaitDialogLater();
            }
        }
    }

    @Override
    public File[] showOpenDialog(boolean multi) {
        // Hide the wait dialog...
        if (currentWaitDialog != null) {
            currentWaitDialog.setVisible(false);
        }

        try{
            if (jfc == null) {
                jfc = new JFileChooser();
            }

            jfc.setMultiSelectionEnabled(multi);
            int ret = jfc.showOpenDialog(this);
            if (ret == JFileChooser.APPROVE_OPTION) {
                return jfc.getSelectedFiles();
            } else {
                return null;
            }
        } finally {
            // And reshow it afterwards...
            if (currentWaitDialog != null) {
                showWaitDialogLater();
            }
        }
    }

    private class ClientListCellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            ClientData cd = ((Client) value).getClientData();
            String s = cd.getClientDescription() + " (pid " + cd.getPid() + ")";
            return super.getListCellRendererComponent(list, s, index, isSelected, cellHasFocus);
        }
    }
}
