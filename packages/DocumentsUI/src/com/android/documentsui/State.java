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

import static com.android.documentsui.Shared.DEBUG;

import android.annotation.IntDef;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.SparseArray;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class State implements android.os.Parcelable {

    private static final String TAG = "State";

    @IntDef(flag = true, value = {
            ACTION_BROWSE,
            ACTION_PICK_COPY_DESTINATION,
            ACTION_OPEN,
            ACTION_CREATE,
            ACTION_GET_CONTENT,
            ACTION_OPEN_TREE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActionType {}
    // File manager and related private picking activity.
    public static final int ACTION_BROWSE = 1;
    public static final int ACTION_PICK_COPY_DESTINATION = 2;
    // All public picking activities
    public static final int ACTION_OPEN = 3;
    public static final int ACTION_CREATE = 4;
    public static final int ACTION_GET_CONTENT = 5;
    public static final int ACTION_OPEN_TREE = 6;

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

    public @ActionType int action;
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
    public boolean showAdvancedOption;
    public boolean showAdvanced;
    public boolean restored;
    /*
     * Indicates handler was an external app, like photos.
     */
    public boolean external;

    // Indicates that a copy operation (or move) includes a directory.
    // Why? Directory creation isn't supported by some roots (like Downloads).
    // This allows us to restrict available roots to just those with support.
    public boolean directoryCopy;
    public boolean openableOnly;

    /**
     * This is basically a sub-type for the copy operation. It can be either COPY or MOVE.
     * The only legal values, if set, are: OPERATION_COPY, OPERATION_MOVE. Other pick
     * operations don't use this. In those cases OPERATION_UNKNOWN is also legal.
     */
    public @OpType int copyOperationSubType = FileOperationService.OPERATION_UNKNOWN;

    /** Current user navigation stack; empty implies recents. */
    public DocumentStack stack = new DocumentStack();
    private boolean mStackTouched;
    private boolean mInitialRootChanged;
    private boolean mInitialDocChanged;

    /** Instance state for every shown directory */
    public HashMap<String, SparseArray<Parcelable>> dirState = new HashMap<>();

    /** Currently copying file */
    public List<DocumentInfo> selectedDocumentsForCopy = new ArrayList<>();

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
        if (DEBUG) Log.d(TAG, "Root changed to: " + root);
        if (!mInitialRootChanged && stack.root != null && !root.equals(stack.root)) {
            mInitialRootChanged = true;
        }
        stack.root = root;
        stack.clear();
        mStackTouched = true;
    }

    public void pushDocument(DocumentInfo info) {
        if (DEBUG) Log.d(TAG, "Adding doc to stack: " + info);
        if (!mInitialDocChanged && stack.size() > 0 && !info.equals(stack.peek())) {
            mInitialDocChanged = true;
        }
        stack.push(info);
        mStackTouched = true;
    }

    public void popDocument() {
        if (DEBUG) Log.d(TAG, "Popping doc off stack.");
        stack.pop();
        mStackTouched = true;
    }

    public void setStack(DocumentStack stack) {
        if (DEBUG) Log.d(TAG, "Setting the whole darn stack to: " + stack);
        this.stack = stack;
        mStackTouched = true;
    }

    // This will return true even when the initial location is set.
    // To get a read on if the user has changed something, use #hasInitialLocationChanged.
    public boolean hasLocationChanged() {
        return mStackTouched;
    }

    public boolean hasInitialLocationChanged() {
        return mInitialRootChanged || mInitialDocChanged;
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
        out.writeInt(showAdvancedOption ? 1 : 0);
        out.writeInt(showAdvanced ? 1 : 0);
        out.writeInt(restored ? 1 : 0);
        out.writeInt(external ? 1 : 0);
        DurableUtils.writeToParcel(out, stack);
        out.writeMap(dirState);
        out.writeList(selectedDocumentsForCopy);
        out.writeList(excludedAuthorities);
        out.writeInt(openableOnly ? 1 : 0);
        out.writeInt(mStackTouched ? 1 : 0);
        out.writeInt(mInitialRootChanged ? 1 : 0);
        out.writeInt(mInitialDocChanged ? 1 : 0);
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
            state.showAdvancedOption = in.readInt() != 0;
            state.showAdvanced = in.readInt() != 0;
            state.restored = in.readInt() != 0;
            state.external = in.readInt() != 0;
            DurableUtils.readFromParcel(in, state.stack);
            in.readMap(state.dirState, loader);
            in.readList(state.selectedDocumentsForCopy, loader);
            in.readList(state.excludedAuthorities, loader);
            state.openableOnly = in.readInt() != 0;
            state.mStackTouched = in.readInt() != 0;
            state.mInitialRootChanged = in.readInt() != 0;
            state.mInitialDocChanged = in.readInt() != 0;
            return state;
        }

        @Override
        public State[] newArray(int size) {
            return new State[size];
        }
    };
}
