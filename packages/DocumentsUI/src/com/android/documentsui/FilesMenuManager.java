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

import android.view.Menu;
import android.view.MenuItem;

import com.android.documentsui.R;

final class FilesMenuManager implements MenuManager {

    private final SearchViewManager mSearchManager;

    public FilesMenuManager(SearchViewManager searchManager) {
        mSearchManager = searchManager;
    }

    @Override
    public void updateActionMenu(Menu menu, SelectionDetails selection) {

        menu.findItem(R.id.menu_open).setVisible(false); // "open" is never used in Files.

        // Commands accessible only via keyboard...
        MenuItem copy = menu.findItem(R.id.menu_copy_to_clipboard);
        MenuItem paste = menu.findItem(R.id.menu_paste_from_clipboard);

        // Commands visible in the UI...
        MenuItem rename = menu.findItem(R.id.menu_rename);
        MenuItem moveTo = menu.findItem(R.id.menu_move_to);
        MenuItem copyTo = menu.findItem(R.id.menu_copy_to);
        MenuItem share = menu.findItem(R.id.menu_share);
        MenuItem delete = menu.findItem(R.id.menu_delete);

        // Commands usually on action-bar, so we always manage visibility.
        share.setVisible(!selection.containsDirectories() && !selection.containsPartialFiles());
        delete.setVisible(selection.canDelete());

        // Commands always in overflow, so we don't bother showing/hiding...
        copyTo.setVisible(true);
        moveTo.setVisible(true);
        rename.setVisible(true);

        // copy is not visible, keyboard only
        copy.setEnabled(!selection.containsPartialFiles());

        copyTo.setEnabled(!selection.containsPartialFiles());
        moveTo.setEnabled(!selection.containsPartialFiles() && selection.canDelete());
        rename.setEnabled(!selection.containsPartialFiles() && selection.canRename());

        Menus.disableHiddenItems(menu, copy, paste);
    }

    @Override
    public void updateOptionMenu(Menu menu, DirectoryDetails details) {

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem pasteFromCb = menu.findItem(R.id.menu_paste_from_clipboard);
        final MenuItem settings = menu.findItem(R.id.menu_settings);
        final MenuItem newWindow = menu.findItem(R.id.menu_new_window);

        createDir.setVisible(true);
        createDir.setEnabled(details.canCreateDirectory());
        pasteFromCb.setEnabled(details.hasItemsToPaste());
        settings.setVisible(details.hasRootSettings());
        newWindow.setVisible(details.shouldShowFancyFeatures());

        Menus.disableHiddenItems(menu, pasteFromCb);

        // It hides icon if searching in progress
        mSearchManager.updateMenu();
    }

}
