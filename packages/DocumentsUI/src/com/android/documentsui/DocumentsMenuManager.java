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

final class DocumentsMenuManager implements MenuManager {

  private final State mState;
  private final SearchViewManager mSearchManager;

  public DocumentsMenuManager(SearchViewManager searchManager, State displayState) {
       mSearchManager = searchManager;
       mState = displayState;
  }

  @Override
  public void updateActionMenu(Menu menu, MenuManager.SelectionDetails selection) {
      MenuItem open = menu.findItem(R.id.menu_open);
      MenuItem share = menu.findItem(R.id.menu_share);
      MenuItem delete = menu.findItem(R.id.menu_delete);
      MenuItem rename = menu.findItem(R.id.menu_rename);
      MenuItem selectAll = menu.findItem(R.id.menu_select_all);

      open.setVisible(mState.action == ACTION_GET_CONTENT
              || mState.action == ACTION_OPEN);
      share.setVisible(false);
      delete.setVisible(false);
      rename.setVisible(false);
      selectAll.setVisible(mState.allowMultiple);

      Menus.disableHiddenItems(menu);
  }

    @Override
    public void updateOptionMenu(Menu menu, DirectoryDetails details) {

        boolean picking = mState.action == ACTION_CREATE
                || mState.action == ACTION_OPEN_TREE
                || mState.action == ACTION_PICK_COPY_DESTINATION;

        if (picking) {
            // May already be hidden because the root
            // doesn't support search.
            mSearchManager.showMenu(false);
        }

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);

        createDir.setVisible(picking);
        createDir.setEnabled(details.canCreateDirectory());

        // No display options in recent directories
        if (picking && details.isInRecents()) {
            final MenuItem grid = menu.findItem(R.id.menu_grid);
            final MenuItem list = menu.findItem(R.id.menu_list);
            grid.setVisible(false);
            list.setVisible(false);
        }

        fileSize.setVisible(fileSize.isVisible() && !picking);

        Menus.disableHiddenItems(menu);
    }
}
