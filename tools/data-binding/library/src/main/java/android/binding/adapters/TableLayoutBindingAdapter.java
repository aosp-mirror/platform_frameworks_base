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
package android.binding.adapters;

import android.binding.BindingAdapter;
import android.util.SparseBooleanArray;
import android.widget.TableLayout;

import java.util.regex.Pattern;

public class TableLayoutBindingAdapter {

    private static final int MAX_COLUMNS = 20;

    private static Pattern sColumnPattern = Pattern.compile("\\s*,\\s*");

    @BindingAdapter("android:collapseColumns")
    public static void setCollapseColumns(TableLayout view, CharSequence columnsStr) {
        SparseBooleanArray columns = parseColumns(columnsStr);
        for (int i = 0; i < MAX_COLUMNS; i++) {
            boolean isCollapsed = columns.get(i, false);
            if (isCollapsed != view.isColumnCollapsed(i)) {
                view.setColumnCollapsed(i, isCollapsed);
            }
        }
    }

    @BindingAdapter("android:shrinkColumns")
    public static void setShrinkColumns(TableLayout view, CharSequence columnsStr) {
        if (columnsStr.charAt(0) == '*') {
            view.setShrinkAllColumns(true);
        } else {
            view.setShrinkAllColumns(false);
            SparseBooleanArray columns = parseColumns(columnsStr);
            for (int i = 0; i < MAX_COLUMNS; i++) {
                boolean shrinkable = columns.get(i, false);
                if (shrinkable != view.isColumnShrinkable(i)) {
                    view.setColumnShrinkable(i, shrinkable);
                }
            }
        }
    }

    @BindingAdapter("android:stretchColumns")
    public static void setStretchColumns(TableLayout view, CharSequence columnsStr) {
        if (columnsStr.charAt(0) == '*') {
            view.setStretchAllColumns(true);
        } else {
            view.setStretchAllColumns(false);
            SparseBooleanArray columns = parseColumns(columnsStr);
            for (int i = 0; i < MAX_COLUMNS; i++) {
                boolean stretchable = columns.get(i, false);
                if (stretchable != view.isColumnStretchable(i)) {
                    view.setColumnStretchable(i, stretchable);
                }
            }
        }
    }

    private static SparseBooleanArray parseColumns(CharSequence sequence) {
        SparseBooleanArray columns = new SparseBooleanArray();
        String[] columnDefs = sColumnPattern.split(sequence);

        for (String columnIdentifier : columnDefs) {
            try {
                int columnIndex = Integer.parseInt(columnIdentifier);
                // only valid, i.e. positive, columns indexes are handled
                if (columnIndex >= 0) {
                    // putting true in this sparse array indicates that the
                    // column index was defined in the XML file
                    columns.put(columnIndex, true);
                }
            } catch (NumberFormatException e) {
                // we just ignore columns that don't exist
            }
        }

        return columns;
    }
}
