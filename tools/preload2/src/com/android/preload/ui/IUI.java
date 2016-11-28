package com.android.preload.ui;

import com.android.ddmlib.Client;
import java.io.File;
import java.util.List;
import javax.swing.Action;
import javax.swing.ListModel;
import javax.swing.table.TableModel;

/**
 * UI abstraction for the tool. This allows a graphical mode, command line mode,
 * or silent mode.
 */
public interface IUI {

    void prepare(ListModel<Client> clientListModel, TableModel dataTableModel,
            List<Action> actions);

    void ready();

    boolean isSingleThreaded();

    Client getSelectedClient();

    int getSelectedDataTableRow();

    void showWaitDialog();

    void updateWaitDialog(String s);

    void hideWaitDialog();

    void showMessageDialog(String s);

    boolean showConfirmDialog(String title, String message);

    String showInputDialog(String message);

    <T> T showChoiceDialog(String title, String message, T[] choices);

    File showSaveDialog();

    File[] showOpenDialog(boolean multi);

}
