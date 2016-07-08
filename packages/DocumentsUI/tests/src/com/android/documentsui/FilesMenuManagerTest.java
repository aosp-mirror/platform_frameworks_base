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
public final class FilesMenuManagerTest {

    private TestMenu testMenu;
    private TestMenuItem rename;
    private TestMenuItem moveTo;
    private TestMenuItem copyTo;
    private TestMenuItem share;
    private TestMenuItem delete;
    private TestMenuItem createDir;
    private TestMenuItem fileSize;
    private TestMenuItem settings;
    private TestMenuItem newWindow;
    private TestMenuItem cut;
    private TestMenuItem copy;
    private TestMenuItem paste;
    private TestMenuItem sort;
    private TestMenuItem sortSize;
    private TestMenuItem advanced;
    private TestMenuItem eject;
    private TestSelectionDetails selectionDetails;
    private TestDirectoryDetails directoryDetails;
    private TestSearchViewManager testSearchManager;
    private RootInfo testRootInfo;
    private State state = new State();

    @Before
    public void setUp() {
        testMenu = TestMenu.create();
        rename = testMenu.findItem(R.id.menu_rename);
        moveTo = testMenu.findItem(R.id.menu_move_to);
        copyTo = testMenu.findItem(R.id.menu_copy_to);
        share = testMenu.findItem(R.id.menu_share);
        delete = testMenu.findItem(R.id.menu_delete);
        createDir = testMenu.findItem(R.id.menu_create_dir);
        fileSize = testMenu.findItem(R.id.menu_file_size);
        settings = testMenu.findItem(R.id.menu_settings);
        newWindow = testMenu.findItem(R.id.menu_new_window);
        cut = testMenu.findItem(R.id.menu_cut_to_clipboard);
        copy = testMenu.findItem(R.id.menu_copy_to_clipboard);
        paste = testMenu.findItem(R.id.menu_paste_from_clipboard);
        sort = testMenu.findItem(R.id.menu_sort);
        sortSize = testMenu.findItem(R.id.menu_sort_size);
        advanced = testMenu.findItem(R.id.menu_advanced);
        eject = testMenu.findItem(R.id.menu_eject_root);

        // These items by default are visible
        testMenu.findItem(R.id.menu_select_all).setVisible(true);
        testMenu.findItem(R.id.menu_list).setVisible(true);
        testMenu.findItem(R.id.menu_file_size).setVisible(true);

        selectionDetails = new TestSelectionDetails();
        directoryDetails = new TestDirectoryDetails();
        testSearchManager = new TestSearchViewManager();
        testRootInfo = new RootInfo();
    }

    @Test
    public void testActionMenu() {
        selectionDetails.canDelete = true;
        selectionDetails.canRename = true;

        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        rename.assertEnabled();
        delete.assertVisible();
        share.assertVisible();
        copyTo.assertEnabled();
        moveTo.assertEnabled();
    }

    @Test
    public void testActionMenu_containsPartial() {
        selectionDetails.containPartial = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        rename.assertDisabled();
        share.assertInvisible();
        copyTo.assertDisabled();
        moveTo.assertDisabled();
    }

    @Test
    public void testActionMenu_cantRename() {
        selectionDetails.canRename = false;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        rename.assertDisabled();
    }

    @Test
    public void testActionMenu_cantDelete() {
        selectionDetails.canDelete = false;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        delete.assertInvisible();
        // We shouldn't be able to move files if we can't delete them
        moveTo.assertDisabled();
    }

    @Test
    public void testActionMenu_containsDirectory() {
        selectionDetails.containDirectories = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateActionMenu(testMenu, selectionDetails);

        // We can't share directories
        share.assertInvisible();
    }

    @Test
    public void testOptionMenu() {
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        sort.assertEnabled();
        sortSize.assertInvisible();
        advanced.assertInvisible();
        advanced.assertTitle(R.string.menu_advanced_show);
        createDir.assertDisabled();
        fileSize.assertVisible();
        assertTrue(testSearchManager.updateMenuCalled());
    }

    @Test
    public void testOptionMenu_hideSize() {
        state.showSize = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        sortSize.assertVisible();
    }

    @Test
    public void testOptionMenu_showAdvanced() {
        state.showAdvanced = true;
        state.showAdvancedOption = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        advanced.assertVisible();
        advanced.assertTitle(R.string.menu_advanced_hide);
    }

    @Test
    public void testOptionMenu_inRecents() {
        directoryDetails.isInRecents = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        sort.assertDisabled();
    }

    @Test
    public void testOptionMenu_canCreateDirectory() {
        directoryDetails.canCreateDirectory = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        createDir.assertEnabled();
    }

    @Test
    public void testOptionMenu_hasRootSettings() {
        directoryDetails.hasRootSettings = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        settings.assertVisible();
    }

    @Test
    public void testOptionMenu_shouldShowFancyFeatures() {
        directoryDetails.shouldShowFancyFeatures = true;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateOptionMenu(testMenu, directoryDetails);

        newWindow.assertVisible();
    }

    @Test
    public void testContextMenu_NoSelection() {
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
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
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateContextMenu(testMenu, selectionDetails, directoryDetails);
        cut.assertVisible();
        copy.assertVisible();
        paste.assertVisible();
        rename.assertVisible();
        createDir.assertVisible();
        delete.assertVisible();
    }

    @Test
    public void testRootContextMenu() {
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateRootContextMenu(testMenu, testRootInfo);

        eject.assertVisible();
        eject.assertDisabled();

        settings.assertVisible();
        settings.assertDisabled();
    }

    @Test
    public void testRootContextMenu_hasRootSettings() {
        testRootInfo.flags = Root.FLAG_HAS_SETTINGS;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateRootContextMenu(testMenu, testRootInfo);

        settings.assertEnabled();
    }

    @Test
    public void testRootContextMenu_canEject() {
        testRootInfo.flags = Root.FLAG_SUPPORTS_EJECT;
        FilesMenuManager mgr = new FilesMenuManager(testSearchManager, state);
        mgr.updateRootContextMenu(testMenu, testRootInfo);

        eject.assertEnabled();
    }
}
