/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.documentsui.State.ACTION_CREATE;
import static com.android.documentsui.State.ACTION_GET_CONTENT;
import static com.android.documentsui.State.ACTION_OPEN;
import static com.android.documentsui.State.ACTION_OPEN_TREE;
import static com.android.documentsui.State.ACTION_PICK_COPY_DESTINATION;

import android.view.Menu;
import android.view.MenuItem;

final class DocumentsMenuManager extends MenuManager {

    private final State mState;
    private final SearchViewManager mSearchManager;
    private boolean mPicking;

    public DocumentsMenuManager(SearchViewManager searchManager, State displayState) {
        mSearchManager = searchManager;
        mState = displayState;

        mPicking = mState.action == ACTION_CREATE
                || mState.action == ACTION_OPEN_TREE
                || mState.action == ACTION_PICK_COPY_DESTINATION;
    }

    @Override
    public void updateOptionMenu(Menu menu, DirectoryDetails details) {
        super.updateOptionMenu(menu, details);
        if (mPicking) {
            // May already be hidden because the root
            // doesn't support search.
            mSearchManager.showMenu(false);
        }
    }

    @Override
    void updateModePicker(MenuItem grid, MenuItem list, DirectoryDetails directoryDetails) {
     // No display options in recent directories
        if (mPicking && directoryDetails.isInRecents()) {
            grid.setVisible(false);
            list.setVisible(false);
        }
    }

    @Override
    void updateFileSize(MenuItem fileSize, DirectoryDetails directoryDetails) {
        fileSize.setVisible(fileSize.isVisible() && !mPicking);
    }

    @Override
    void updateSettings(MenuItem settings, DirectoryDetails directoryDetails) {
        assert(!settings.isVisible());
    }

    @Override
    void updateNewWindow(MenuItem newWindow, DirectoryDetails directoryDetails) {
        assert(!newWindow.isVisible());
    }

    @Override
    void updateSelectAll(MenuItem selectAll, SelectionDetails selectionDetails) {
        selectAll.setVisible(mState.allowMultiple);
    }

    @Override
    void updateCreateDir(MenuItem createDir, DirectoryDetails directoryDetails) {
        createDir.setVisible(mPicking);
        createDir.setEnabled(mPicking && directoryDetails.canCreateDirectory());
    }

    @Override
    void updateOpen(MenuItem open, SelectionDetails selectionDetails) {
        open.setVisible(mState.action == ACTION_GET_CONTENT
                || mState.action == ACTION_OPEN);
    }

    @Override
    void updateShare(MenuItem share, SelectionDetails selectionDetails) {
        share.setVisible(false);
    }

    @Override
    void updateDelete(MenuItem delete, SelectionDetails selectionDetails) {
        delete.setVisible(false);
    }

    @Override
    void updateRename(MenuItem rename, SelectionDetails selectionDetails) {
        rename.setVisible(false);
    }

    @Override
    void updateMoveTo(MenuItem moveTo, SelectionDetails selectionDetails) {
        assert(!moveTo.isVisible());
    }

    @Override
    void updateCopyTo(MenuItem copyTo, SelectionDetails selectionDetails) {
        assert(!copyTo.isVisible());
    }
}
