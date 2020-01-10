/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import java.util.List;


/**
 * Utility class for dumping the results of a {@link NotifListBuilderImpl} to a debug string.
 */
public class ListDumper {

    /** See class description */
    public static String dumpList(List<ListEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            ListEntry entry = entries.get(i);
            dumpEntry(entry, Integer.toString(i), "", sb);
            if (entry instanceof GroupEntry) {
                GroupEntry ge = (GroupEntry) entry;
                for (int j = 0; j < ge.getChildren().size(); j++) {
                    dumpEntry(
                            ge.getChildren().get(j),
                            Integer.toString(j),
                            INDENT,
                            sb);
                }
            }
        }
        return sb.toString();
    }

    private static void dumpEntry(
            ListEntry entry, String index, String indent, StringBuilder sb) {
        sb.append(indent)
                .append("[").append(index).append("] ")
                .append(entry.getKey())
                .append(" (parent=")
                .append(entry.getParent() != null ? entry.getParent().getKey() : null)
                .append(")\n");
    }

    private static final String INDENT = "  ";
}
