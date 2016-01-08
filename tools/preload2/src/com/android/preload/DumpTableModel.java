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

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * A table model for collected DumpData. This is both the internal storage as well as the model
 * for display.
 */
public class DumpTableModel extends AbstractTableModel {

    private List<DumpData> data = new ArrayList<DumpData>();

    public void addData(DumpData d) {
        data.add(d);
        fireTableRowsInserted(data.size() - 1, data.size() - 1);
    }

    public void clear() {
        int size = data.size();
        if (size > 0) {
            data.clear();
            fireTableRowsDeleted(0, size - 1);
        }
    }

    public List<DumpData> getData() {
        return data;
    }

    @Override
    public int getRowCount() {
        return data.size();
    }

    @Override
    public int getColumnCount() {
        return 4;
    }

    @Override
    public String getColumnName(int column) {
        switch (column) {
            case 0:
                return "Package";
            case 1:
                return "Date";
            case 2:
                return "# All Classes";
            case 3:
                return "# Boot Classpath Classes";

            default:
                throw new IndexOutOfBoundsException(String.valueOf(column));
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        DumpData d = data.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return d.packageName;
            case 1:
                return d.date;
            case 2:
                return d.dumpData.size();
            case 3:
                return d.bcpClasses;

            default:
                throw new IndexOutOfBoundsException(String.valueOf(columnIndex));
        }
    }
}