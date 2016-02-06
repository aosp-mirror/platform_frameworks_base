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

import android.annotation.IntDef;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class State implements android.os.Parcelable {

    public static final int ACTION_OPEN = 1;
    public static final int ACTION_CREATE = 2;
    public static final int ACTION_GET_CONTENT = 3;
    public static final int ACTION_OPEN_TREE = 4;
    public static final int ACTION_MANAGE = 5;
    public static final int ACTION_BROWSE = 6;
    public static final int ACTION_PICK_COPY_DESTINATION = 8;

    @IntDef(flag = true, value = {
            MODE_UNKNOWN,
            MODE_LIST,
            MODE_GRID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ViewMode {}
    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_LIST = 1;
    public static final int MODE_GRID = 2;

    public static final int SORT_ORDER_UNKNOWN = 0;
    public static final int SORT_ORDER_DISPLAY_NAME = 1;
    public static final int SORT_ORDER_LAST_MODIFIED = 2;
    public static final int SORT_ORDER_SIZE = 3;

    public int action;
    public String[] acceptMimes;

    /** Derived from local preferences */
    public @ViewMode int derivedMode = MODE_GRID;

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
    public boolean restored;
    public boolean directoryCopy;
    public boolean openableOnly;
    /** Transfer mode for file copy/move operations. */
    public int transferMode;

    /** Current user navigation stack; empty implies recents. */
    public DocumentStack stack = new DocumentStack();
    private boolean mStackTouched;

    /** Currently active search, overriding any stack. */
    public String currentSearch;

    /** Instance state for every shown directory */
    public HashMap<String, SparseArray<Parcelable>> dirState = new HashMap<>();

    /** Currently copying file */
    public List<DocumentInfo> selectedDocumentsForCopy = new ArrayList<DocumentInfo>();

    /** Name of the package that started DocsUI */
    public List<String> excludedAuthorities = new ArrayList<>();

    public void initAcceptMimes(Intent intent) {
        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            String glob = intent.getType();
            acceptMimes = new String[] { glob != null ? glob : "*/*" };
        }
    }

    public void onRootChanged(RootInfo root) {
        stack.root = root;
        stack.clear();
        mStackTouched = true;
    }

    public void pushDocument(DocumentInfo info) {
        stack.push(info);
        mStackTouched = true;
    }

    public void popDocument() {
        stack.pop();
        mStackTouched = true;
    }

    public void setStack(DocumentStack stack) {
        this.stack = stack;
        mStackTouched = true;
    }

    public boolean hasLocationChanged() {
        return mStackTouched;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(action);
        out.writeStringArray(acceptMimes);
        out.writeInt(userSortOrder);
        out.writeInt(allowMultiple ? 1 : 0);
        out.writeInt(forceSize ? 1 : 0);
        out.writeInt(showSize ? 1 : 0);
        out.writeInt(localOnly ? 1 : 0);
        out.writeInt(forceAdvanced ? 1 : 0);
        out.writeInt(showAdvanced ? 1 : 0);
        out.writeInt(restored ? 1 : 0);
        DurableUtils.writeToParcel(out, stack);
        out.writeString(currentSearch);
        out.writeMap(dirState);
        out.writeList(selectedDocumentsForCopy);
        out.writeList(excludedAuthorities);
        out.writeInt(openableOnly ? 1 : 0);
        out.writeInt(mStackTouched ? 1 : 0);
    }

    public static final ClassLoaderCreator<State> CREATOR = new ClassLoaderCreator<State>() {
        @Override
        public State createFromParcel(Parcel in) {
            return createFromParcel(in, null);
        }

        @Override
        public State createFromParcel(Parcel in, ClassLoader loader) {
            final State state = new State();
            state.action = in.readInt();
            state.acceptMimes = in.readStringArray();
            state.userSortOrder = in.readInt();
            state.allowMultiple = in.readInt() != 0;
            state.forceSize = in.readInt() != 0;
            state.showSize = in.readInt() != 0;
            state.localOnly = in.readInt() != 0;
            state.forceAdvanced = in.readInt() != 0;
            state.showAdvanced = in.readInt() != 0;
            state.restored = in.readInt() != 0;
            DurableUtils.readFromParcel(in, state.stack);
            state.currentSearch = in.readString();
            in.readMap(state.dirState, loader);
            in.readList(state.selectedDocumentsForCopy, loader);
            in.readList(state.excludedAuthorities, loader);
            state.openableOnly = in.readInt() != 0;
            state.mStackTouched = in.readInt() != 0;
            return state;
        }

        @Override
        public State[] newArray(int size) {
            return new State[size];
        }
    };
}
