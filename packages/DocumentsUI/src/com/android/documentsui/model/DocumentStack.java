/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui.model;

import com.android.documentsui.RootsCache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.LinkedList;

/**
 * Representation of a stack of {@link DocumentInfo}, usually the result of a
 * user-driven traversal.
 */
public class DocumentStack extends LinkedList<DocumentInfo> implements Durable {
    private static final int VERSION_INIT = 1;

    public RootInfo getRoot(RootsCache roots) {
        return roots.findRoot(getLast().uri);
    }

    public String getTitle(RootsCache roots) {
        if (size() == 1) {
            return getRoot(roots).title;
        } else if (size() > 1) {
            return peek().displayName;
        } else {
            return null;
        }
    }

    public boolean isRecents() {
        return size() == 0;
    }

    @Override
    public void reset() {
        clear();
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_INIT:
                final int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    final DocumentInfo doc = new DocumentInfo();
                    doc.read(in);
                    add(doc);
                }
                break;
            default:
                throw new ProtocolException("Unknown version " + version);
        }
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_INIT);
        final int size = size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            final DocumentInfo doc = get(i);
            doc.write(out);
        }
    }
}
