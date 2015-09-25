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

package com.android.documentsui;

import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class State implements android.os.Parcelable {
    public int action;
    public String[] acceptMimes;

    /** Explicit user choice */
    public int userMode = MODE_UNKNOWN;
    /** Derived after loader */
    public int derivedMode = MODE_LIST;

    /** Explicit user choice */
    public int userSortOrder = SORT_ORDER_UNKNOWN;
    /** Derived after loader */
    public int derivedSortOrder = SORT_ORDER_DISPLAY_NAME;

    public boolean allowMultiple;
    public boolean forceSize;
    public boolean showSize;
    public boolean localOnly;
    public boolean forceAdvanced;
    public boolean showAdvanced;
    public boolean stackTouched;
    public boolean restored;
    public boolean directoryCopy;
    /** Transfer mode for file copy/move operations. */
    public int transferMode;

    /** Current user navigation stack; empty implies recents. */
    public DocumentStack stack = new DocumentStack();
    /** Currently active search, overriding any stack. */
    public String currentSearch;

    /** Instance state for every shown directory */
    public HashMap<String, SparseArray<Parcelable>> dirState = new HashMap<>();

    /** Currently copying file */
    public List<DocumentInfo> selectedDocumentsForCopy = new ArrayList<DocumentInfo>();

    /** Name of the package that started DocsUI */
    public List<String> excludedAuthorities = new ArrayList<>();

    public static final int ACTION_OPEN = 1;
    public static final int ACTION_CREATE = 2;
    public static final int ACTION_GET_CONTENT = 3;
    public static final int ACTION_OPEN_TREE = 4;
    public static final int ACTION_MANAGE = 5;
    public static final int ACTION_BROWSE = 6;
    public static final int ACTION_PICK_COPY_DESTINATION = 8;

    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_LIST = 1;
    public static final int MODE_GRID = 2;

    public static final int SORT_ORDER_UNKNOWN = 0;
    public static final int SORT_ORDER_DISPLAY_NAME = 1;
    public static final int SORT_ORDER_LAST_MODIFIED = 2;
    public static final int SORT_ORDER_SIZE = 3;

    public void initAcceptMimes(Intent intent) {
        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            String glob = intent.getType();
            acceptMimes = new String[] { glob != null ? glob : "*/*" };
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(action);
        out.writeInt(userMode);
        out.writeStringArray(acceptMimes);
        out.writeInt(userSortOrder);
        out.writeInt(allowMultiple ? 1 : 0);
        out.writeInt(forceSize ? 1 : 0);
        out.writeInt(showSize ? 1 : 0);
        out.writeInt(localOnly ? 1 : 0);
        out.writeInt(forceAdvanced ? 1 : 0);
        out.writeInt(showAdvanced ? 1 : 0);
        out.writeInt(stackTouched ? 1 : 0);
        out.writeInt(restored ? 1 : 0);
        DurableUtils.writeToParcel(out, stack);
        out.writeString(currentSearch);
        out.writeMap(dirState);
        out.writeList(selectedDocumentsForCopy);
        out.writeList(excludedAuthorities);
    }

    public static final Creator<State> CREATOR = new Creator<State>() {
        @Override
        public State createFromParcel(Parcel in) {
            final State state = new State();
            state.action = in.readInt();
            state.userMode = in.readInt();
            state.acceptMimes = in.readStringArray();
            state.userSortOrder = in.readInt();
            state.allowMultiple = in.readInt() != 0;
            state.forceSize = in.readInt() != 0;
            state.showSize = in.readInt() != 0;
            state.localOnly = in.readInt() != 0;
            state.forceAdvanced = in.readInt() != 0;
            state.showAdvanced = in.readInt() != 0;
            state.stackTouched = in.readInt() != 0;
            state.restored = in.readInt() != 0;
            DurableUtils.readFromParcel(in, state.stack);
            state.currentSearch = in.readString();
            in.readMap(state.dirState, null);
            in.readList(state.selectedDocumentsForCopy, null);
            in.readList(state.excludedAuthorities, null);
            return state;
        }

        @Override
        public State[] newArray(int size) {
            return new State[size];
        }
    };
}
