package com.android.preload.ui;

import com.android.ddmlib.Client;
import com.android.ddmlib.ClientData;
import java.io.File;
import java.util.LinkedList;
import java.util.List;
import javax.swing.Action;
import javax.swing.ListModel;
import javax.swing.table.TableModel;

public class SequenceUI implements IUI {

    private ListModel<Client> clientListModel;
    @SuppressWarnings("unused")
    private TableModel dataTableModel;
    private List<Action> actions;

    private List<Object> sequence = new LinkedList<>();

    public SequenceUI() {
    }

    @Override
    public boolean isSingleThreaded() {
        return true;
    }

    @Override
    public void prepare(ListModel<Client> clientListModel, TableModel dataTableModel,
            List<Action> actions) {
        this.clientListModel = clientListModel;
        this.dataTableModel = dataTableModel;
        this.actions = actions;
    }

    public SequenceUI action(Action a) {
        sequence.add(a);
        return this;
    }

    public SequenceUI action(Class<? extends Action> actionClass) {
        for (Action a : actions) {
            if (actionClass.equals(a.getClass())) {
                sequence.add(a);
                return this;
            }
        }
        throw new IllegalArgumentException("No action of class " + actionClass + " found.");
    }

    public SequenceUI confirmYes() {
        sequence.add(Boolean.TRUE);
        return this;
    }

    public SequenceUI confirmNo() {
        sequence.add(Boolean.FALSE);
        return this;
    }

    public SequenceUI input(String input) {
        sequence.add(input);
        return this;
    }

    public SequenceUI input(File... f) {
        sequence.add(f);
        return this;
    }

    public SequenceUI output(File f) {
        sequence.add(f);
        return this;
    }

    public SequenceUI tableRow(int i) {
        sequence.add(i);
        return this;
    }

    private class ClientSelector {
        private String pkg;

        public ClientSelector(String pkg) {
            this.pkg = pkg;
        }

        public Client getClient() {
            for (int i = 0; i < clientListModel.getSize(); i++) {
                ClientData cd = clientListModel.getElementAt(i).getClientData();
                if (cd != null) {
                    String s = cd.getClientDescription();
                    if (pkg.equals(s)) {
                        return clientListModel.getElementAt(i);
                    }
                }
            }
            throw new RuntimeException("Didn't find client " + pkg);
        }
    }

    public SequenceUI client(String pkg) {
        sequence.add(new ClientSelector(pkg));
        return this;
    }

    public SequenceUI choice(String pattern) {
        sequence.add(pattern);
        return this;
    }

    @Override
    public void ready() {
        // Run the actions.
        // No iterator or foreach loop as the sequence will be emptied while running.
        try {
            while (!sequence.isEmpty()) {
                Object next = sequence.remove(0);
                if (next instanceof Action) {
                    ((Action)next).actionPerformed(null);
                } else {
                    throw new IllegalStateException("Didn't expect a non-action: " + next);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }

        // Now shut down.
        System.exit(0);
    }

    @Override
    public Client getSelectedClient() {
        Object next = sequence.remove(0);
        if (next instanceof ClientSelector) {
            return ((ClientSelector)next).getClient();
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

    @Override
    public int getSelectedDataTableRow() {
        Object next = sequence.remove(0);
        if (next instanceof Integer) {
            return ((Integer)next).intValue();
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

    @Override
    public void showWaitDialog() {
    }

    @Override
    public void updateWaitDialog(String s) {
        System.out.println(s);
    }

    @Override
    public void hideWaitDialog() {
    }

    @Override
    public void showMessageDialog(String s) {
        System.out.println(s);
    }

    @Override
    public boolean showConfirmDialog(String title, String message) {
        Object next = sequence.remove(0);
        if (next instanceof Boolean) {
            return ((Boolean)next).booleanValue();
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

    @Override
    public String showInputDialog(String message) {
        Object next = sequence.remove(0);
        if (next instanceof String) {
            return (String)next;
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

    @Override
    public <T> T showChoiceDialog(String title, String message, T[] choices) {
        Object next = sequence.remove(0);
        if (next instanceof String) {
            String s = (String)next;
            for (T t : choices) {
                if (t.toString().contains(s)) {
                    return t;
                }
            }
            return null;
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

    @Override
    public File showSaveDialog() {
        Object next = sequence.remove(0);
        if (next instanceof File) {
            System.out.println(next);
            return (File)next;
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

    @Override
    public File[] showOpenDialog(boolean multi) {
        Object next = sequence.remove(0);
        if (next instanceof File[]) {
            return (File[])next;
        }
        throw new IllegalStateException("Unexpected: " + next);
    }

}
