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

import android.annotation.Nullable;
import android.view.Menu;
import android.view.MenuItem;

public abstract class MenuManager {

    /** @See DirectoryFragment.SelectionModeListener#updateActionMenu */
    public void updateActionMenu(Menu menu, SelectionDetails selection) {
        updateOpen(menu.findItem(R.id.menu_open), selection);
        updateDelete(menu.findItem(R.id.menu_delete), selection);
        updateShare(menu.findItem(R.id.menu_share), selection);
        updateRename(menu.findItem(R.id.menu_rename), selection);
        updateSelectAll(menu.findItem(R.id.menu_select_all), selection);
        updateMoveTo(menu.findItem(R.id.menu_move_to), selection);
        updateCopyTo(menu.findItem(R.id.menu_copy_to), selection);

        Menus.disableHiddenItems(menu);
    }

    /** @See Activity#onPrepareOptionsMenu */
    public void updateOptionMenu(Menu menu, DirectoryDetails details) {
        updateCreateDir(menu.findItem(R.id.menu_create_dir), details);
        updateSettings(menu.findItem(R.id.menu_settings), details);
        updateNewWindow(menu.findItem(R.id.menu_new_window), details);
        updateFileSize(menu.findItem(R.id.menu_file_size), details);
        updateModePicker(menu.findItem(R.id.menu_grid), menu.findItem(R.id.menu_list), details);

        Menus.disableHiddenItems(menu);
    }

    /** @See DirectoryFragment.onCreateContextMenu */
    public void updateContextMenu(Menu menu,
            @Nullable SelectionDetails selectionDetails,
            DirectoryDetails directoryDetails) {

        MenuItem cut = menu.findItem(R.id.menu_cut_to_clipboard);
        MenuItem copy = menu.findItem(R.id.menu_copy_to_clipboard);
        MenuItem paste = menu.findItem(R.id.menu_paste_from_clipboard);
        MenuItem delete = menu.findItem(R.id.menu_delete);
        MenuItem rename = menu.findItem(R.id.menu_rename);
        MenuItem createDir = menu.findItem(R.id.menu_create_dir);

        if (selectionDetails == null) {
            cut.setEnabled(false);
            copy.setEnabled(false);
            rename.setEnabled(false);
            delete.setEnabled(false);
        } else {
            copy.setEnabled(!selectionDetails.containsPartialFiles());
            cut.setEnabled(
                    !selectionDetails.containsPartialFiles() && selectionDetails.canDelete());
            updateRename(rename, selectionDetails);
            updateDelete(delete, selectionDetails);
        }
        menu.findItem(R.id.menu_paste_from_clipboard)
                .setEnabled(directoryDetails.hasItemsToPaste());
        updateCreateDir(createDir, directoryDetails);

        //Cut, Copy, Paste and Delete should always be visible
        cut.setVisible(true);
        copy.setVisible(true);
        paste.setVisible(true);
        delete.setVisible(true);
    }

    abstract void updateModePicker(MenuItem grid, MenuItem list, DirectoryDetails directoryDetails);
    abstract void updateFileSize(MenuItem fileSize, DirectoryDetails directoryDetails);
    abstract void updateSettings(MenuItem settings, DirectoryDetails directoryDetails);
    abstract void updateNewWindow(MenuItem newWindow, DirectoryDetails directoryDetails);
    abstract void updateMoveTo(MenuItem moveTo, SelectionDetails selectionDetails);
    abstract void updateCopyTo(MenuItem copyTo, SelectionDetails selectionDetails);
    abstract void updateSelectAll(MenuItem selectAll, SelectionDetails selectionDetails);
    abstract void updateCreateDir(MenuItem createDir, DirectoryDetails directoryDetails);
    abstract void updateOpen(MenuItem open, SelectionDetails selectionDetails);
    abstract void updateShare(MenuItem share, SelectionDetails selectionDetails);
    abstract void updateRename(MenuItem rename, SelectionDetails selectionDetails);
    abstract void updateDelete(MenuItem delete, SelectionDetails selectionDetails);

    /**
     * Access to meta data about the selection.
     */
    public interface SelectionDetails {
        boolean containsDirectories();

        boolean containsPartialFiles();

        // TODO: Update these to express characteristics instead of answering concrete questions,
        // since the answer to those questions is (or can be) activity specific.
        boolean canDelete();

        boolean canRename();
    }

    public static class DirectoryDetails {
        private final BaseActivity mActivity;

        public DirectoryDetails(BaseActivity activity) {
            mActivity = activity;
        }

        public boolean shouldShowFancyFeatures() {
            return Shared.shouldShowFancyFeatures(mActivity);
        }

        public boolean hasRootSettings() {
            return mActivity.getCurrentRoot().hasSettings();
        }

        public boolean hasItemsToPaste() {
            return false;
        }

        public boolean isInRecents() {
            return mActivity.getCurrentDirectory() == null;
        }

        public boolean canCreateDirectory() {
            return mActivity.canCreateDirectory();
        }
    }
}
