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
import static com.android.documentsui.State.ACTION_OPEN;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.provider.DocumentsContract.Root;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.model.RootInfo;
import com.android.documentsui.testing.TestDirectoryDetails;
import com.android.documentsui.testing.TestMenu;
import com.android.documentsui.testing.TestMenuItem;
import com.android.documentsui.testing.TestSearchViewManager;
import com.android.documentsui.testing.TestSelectionDetails;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class DocumentsMenuManagerTest {

    private TestMenu testMenu;
    private TestMenuItem open;
    private TestMenuItem share;
    private TestMenuItem delete;
    private TestMenuItem rename;
    private TestMenuItem selectAll;
    private TestMenuItem createDir;
    private TestMenuItem fileSize;
    private TestMenuItem grid;
    private TestMenuItem list;
    private TestMenuItem cut;
    private TestMenuItem copy;
    private TestMenuItem paste;
    private TestMenuItem sort;
    private TestMenuItem sortSize;
    private TestMenuItem advanced;
    private TestMenuItem settings;
    private TestMenuItem eject;

    private TestSelectionDetails selectionDetails;
    private TestDirectoryDetails directoryDetails;
    private TestSearchViewManager testSearchManager;
    private State state = new State();
    private RootInfo testRootInfo;

    @Before
    public void setUp() {
        testMenu = TestMenu.create();
        open = testMenu.findItem(R.id.menu_open);
        share = testMenu.findItem(R.id.menu_share);
        delete = testMenu.findItem(R.id.menu_delete);
        rename =  testMenu.findItem(R.id.menu_rename);
        selectAll = testMenu.findItem(R.id.menu_select_all);
        createDir = testMenu.findItem(R.id.menu_create_dir);
        fileSize = testMenu.findItem(R.id.menu_file_size);
        grid = testMenu.findItem(R.id.menu_grid);
        list = testMenu.findItem(R.id.menu_list);
        cut = testMenu.findItem(R.id.menu_cut_to_clipboard);
        copy = testMenu.findItem(R.id.menu_copy_to_clipboard);
        paste = testMenu.findItem(R.id.menu_paste_from_clipboard);
        sort = testMenu.findItem(R.id.menu_sort);
        sortSize = testMenu.findItem(R.id.menu_sort_size);
        advanced = testMenu.findItem(R.id.menu_advanced);
        settings = testMenu.findItem(R.id.menu_settings);
        eject = testMenu.findItem(R.id.menu_eject_root);

        selectionDetails = new TestSelectionDetails();
        directoryDetails = new TestDirectoryDetails();
        testSearchManager = new TestSearchViewManager();
        testRootInfo = new RootInfo();
        state.action = ACTION_CREATE;
        state.allowMultiple = true;
    }

    @Test
    public void testActionMenu() {
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        open.assertInvisible();
        delete.assertInvisible();
        share.assertInvisible();
        rename.assertInvisible();
        selectAll.assertVisible();
    }

    @Test
    public void testActionMenu_openAction() {
        state.action = ACTION_OPEN;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        open.assertVisible();
    }


    @Test
    public void testActionMenu_notAllowMultiple() {
        state.allowMultiple = false;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        selectAll.assertInvisible();
    }

    @Test
    public void testOptionMenu() {
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        sort.assertEnabled();
        sortSize.assertInvisible();
        advanced.assertInvisible();
        advanced.assertTitle(R.string.menu_advanced_show);
        createDir.assertDisabled();
        fileSize.assertInvisible();
        assertTrue(testSearchManager.showMenuCalled());
    }

    @Test
    public void testOptionMenu_hideSize() {
        state.showSize = true;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        sortSize.assertVisible();
    }

    @Test
    public void testOptionMenu_notPicking() {
        state.action = ACTION_OPEN;
        state.derivedMode = State.MODE_LIST;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        createDir.assertInvisible();
        grid.assertVisible();
        list.assertInvisible();
        assertFalse(testSearchManager.showMenuCalled());
    }

    @Test
    public void testOptionMenu_canCreateDirectory() {
        directoryDetails.canCreateDirectory = true;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        createDir.assertEnabled();
    }

    @Test
    public void testOptionMenu_showAdvanced() {
        state.showAdvanced = true;
        state.showAdvancedOption = true;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        advanced.assertVisible();
        advanced.assertTitle(R.string.menu_advanced_hide);
    }

    @Test
    public void testOptionMenu_inRecents() {
        directoryDetails.isInRecents = true;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        sort.assertDisabled();
        grid.assertInvisible();
        list.assertInvisible();
    }

    @Test
    public void testContextMenu_NoSelection() {
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateContextMenu(testMenu, null, directoryDetails);
        cut.assertVisible();
        copy.assertVisible();
        cut.assertDisabled();
        copy.assertDisabled();
        paste.assertVisible();
        createDir.assertVisible();
        delete.assertVisible();
    }

    @Test
    public void testContextMenu_Selection() {
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateContextMenu(testMenu, selectionDetails, directoryDetails);
        cut.assertVisible();
        copy.assertVisible();
        paste.assertVisible();
        rename.assertInvisible();
        createDir.assertVisible();
        delete.assertVisible();
    }

    @Test
    public void testRootContextMenu() {
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateRootContextMenu(testMenu, testRootInfo);

        eject.assertInvisible();
        settings.assertInvisible();
    }

    @Test
    public void testRootContextMenu_hasRootSettings() {
        testRootInfo.flags = Root.FLAG_HAS_SETTINGS;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateRootContextMenu(testMenu, testRootInfo);

        settings.assertInvisible();
    }

    @Test
    public void testRootContextMenu_canEject() {
        testRootInfo.flags = Root.FLAG_SUPPORTS_EJECT;
        DocumentsMenuManager mgr = new DocumentsMenuManager(testSearchManager, state);
        mgr.updateRootContextMenu(testMenu, testRootInfo);

        eject.assertInvisible();
    }
}
