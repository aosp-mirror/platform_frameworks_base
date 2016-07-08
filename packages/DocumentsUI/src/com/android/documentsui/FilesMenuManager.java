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

import android.provider.DocumentsContract.Root;
import android.view.Menu;
import android.view.MenuItem;

import com.android.documentsui.model.RootInfo;

final class FilesMenuManager extends MenuManager {

    public FilesMenuManager(SearchViewManager searchManager, State displayState) {
        super(searchManager, displayState);
    }

    @Override
    public void updateOptionMenu(Menu menu, DirectoryDetails details) {
        super.updateOptionMenu(menu, details);

        // It hides icon if searching in progress
        mSearchManager.updateMenu();
    }

    @Override
    void updateSettings(MenuItem settings, RootInfo root) {
        settings.setVisible(true);
        settings.setEnabled(root.hasSettings());
    }

    @Override
    void updateEject(MenuItem eject, RootInfo root) {
        eject.setVisible(true);
        eject.setEnabled(((root.flags & Root.FLAG_SUPPORTS_EJECT) > 0) && !root.ejecting);
    }

    @Override
    void updateSettings(MenuItem settings, DirectoryDetails directoryDetails) {
        settings.setVisible(directoryDetails.hasRootSettings());
    }

    @Override
    void updateNewWindow(MenuItem newWindow, DirectoryDetails directoryDetails) {
        newWindow.setVisible(directoryDetails.shouldShowFancyFeatures());
    }

    @Override
    void updateMoveTo(MenuItem moveTo, SelectionDetails selectionDetails) {
        moveTo.setVisible(true);
        moveTo.setEnabled(!selectionDetails.containsPartialFiles() && selectionDetails.canDelete());
    }

    @Override
    void updateCopyTo(MenuItem copyTo, SelectionDetails selectionDetails) {
        copyTo.setVisible(true);
        copyTo.setEnabled(!selectionDetails.containsPartialFiles());
    }

    @Override
    void updateSelectAll(MenuItem selectAll, SelectionDetails selectionDetails) {
        selectAll.setVisible(true);
    }

    @Override
    void updateCreateDir(MenuItem createDir, DirectoryDetails directoryDetails) {
        createDir.setVisible(true);
        createDir.setEnabled(directoryDetails.canCreateDirectory());
    }

    @Override
    void updateShare(MenuItem share, SelectionDetails selectionDetails) {
        share.setVisible(!selectionDetails.containsDirectories()
                && !selectionDetails.containsPartialFiles());
    }

    @Override
    void updateDelete(MenuItem delete, SelectionDetails selectionDetails) {
        delete.setVisible(selectionDetails.canDelete());
    }

    @Override
    void updateRename(MenuItem rename, SelectionDetails selectionDetails) {
        rename.setVisible(true);
        rename.setEnabled(!selectionDetails.containsPartialFiles() && selectionDetails.canRename());
    }
}