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

package com.android.systemui.statusbar.notification.collection.init;

import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * Temporary class that tracks the result of the list builder and dumps it to text when requested.
 *
 * Eventually, this will be something that hands off the result of the pipeline to the View layer.
 */
public class FakePipelineConsumer implements Dumpable {
    private List<ListEntry> mEntries = Collections.emptyList();

    /** Attach the consumer to the pipeline. */
    public void attach(ShadeListBuilder listBuilder) {
        listBuilder.setOnRenderListListener(this::onBuildComplete);
    }

    private void onBuildComplete(List<ListEntry> entries) {
        mEntries = entries;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println();
        pw.println("Active notif tree:");
        for (int i = 0; i < mEntries.size(); i++) {
            ListEntry entry = mEntries.get(i);
            if (entry instanceof GroupEntry) {
                GroupEntry ge = (GroupEntry) entry;
                pw.println(dumpGroup(ge, "", i));

                pw.println(dumpEntry(ge.getSummary(), INDENT, -1));
                for (int j = 0; j < ge.getChildren().size(); j++) {
                    pw.println(dumpEntry(ge.getChildren().get(j), INDENT, j));
                }
            } else {
                pw.println(dumpEntry(entry.getRepresentativeEntry(), "", i));
            }
        }
    }

    private String dumpGroup(GroupEntry entry, String indent, int index) {
        return String.format(
                "%s[%d] %s (group)",
                indent,
                index,
                entry.getKey());
    }

    private String dumpEntry(NotificationEntry entry, String indent, int index) {
        return String.format(
                "%s[%s] %s (channel=%s)",
                indent,
                index == -1 ? "*" : Integer.toString(index),
                entry.getKey(),
                entry.getChannel() != null ? entry.getChannel().getId() : "");
    }

    private static final String INDENT = "   ";
}
