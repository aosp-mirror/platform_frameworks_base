/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import static com.android.documentsui.State.ACTION_BROWSE;
import static com.android.documentsui.State.ACTION_CREATE;
import static com.android.documentsui.State.ACTION_GET_CONTENT;
import static com.android.documentsui.State.ACTION_MANAGE;
import static com.android.documentsui.State.ACTION_OPEN;
import static com.android.documentsui.State.ACTION_OPEN_TREE;
import static com.android.internal.util.Preconditions.checkArgument;

import com.android.documentsui.Menus;
import com.android.documentsui.MimePredicate;
import com.android.documentsui.R;
import com.android.documentsui.State;

import android.os.SystemProperties;
import android.provider.DocumentsContract.Document;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Providers support for specializing the DirectoryFragment to the "host" Activity.
 * Feel free to expand the role of this class to handle other specializations.
 */
public abstract class FragmentTuner {

    final State mState;

    public FragmentTuner(State state) {
        mState = state;
    }

    public static FragmentTuner pick(State state) {
        switch (state.action) {
            case ACTION_BROWSE:
                return new FilesTuner(state);
            case ACTION_MANAGE:
                return new DownloadsTuner(state);
            default:
                return new DocumentsTuner(state);
        }
    }


    public abstract void updateActionMenu(Menu menu, int dirType, boolean canDelete);

    // Subtly different from isDocumentEnabled. The reason may be illuminated as follows.
    // A folder is enabled such that it may be double clicked, even in settings
    // when the folder itself cannot be selected. This may also be true of container types.
    public boolean canSelectType(String docMimeType, int docFlags) {
        return true;
    }

    public boolean isDocumentEnabled(String docMimeType, int docFlags) {
        if (isDirectory(docMimeType)) {
            return true;
        }

        return MimePredicate.mimeMatches(mState.acceptMimes, docMimeType);
    }

    /**
     * Provides support for Platform specific specializations of DirectoryFragment.
     */
    private static final class DocumentsTuner extends FragmentTuner {

        public DocumentsTuner(State state) {
            super(state);
        }

        @Override
        public boolean canSelectType(String docMimeType, int docFlags) {
            if (!isDocumentEnabled(docMimeType, docFlags)) {
                return false;
            }

            if (isDirectory(docMimeType)) {
                return false;
            }

            if (mState.action == ACTION_OPEN_TREE) {
                // In this case nothing *ever* is selectable...the expected user behavior is
                // they navigate *into* a folder, then click a confirmation button indicating
                // that the current directory is the directory they are picking.
                return false;
            }

            return true;
        }

        @Override
        public boolean isDocumentEnabled(String docMimeType, int docFlags) {
            // Directories are always enabled.
            if (isDirectory(docMimeType)) {
                return true;
            }

            switch (mState.action) {
                case ACTION_CREATE:
                    // Read-only files are disabled when creating.
                    if ((docFlags & Document.FLAG_SUPPORTS_WRITE) == 0) {
                        return false;
                    }
                case ACTION_OPEN:
                case ACTION_GET_CONTENT:
                    final boolean isVirtual = (docFlags & Document.FLAG_VIRTUAL_DOCUMENT) != 0;
                    if (isVirtual && mState.openableOnly) {
                        return false;
                    }
            }

            return MimePredicate.mimeMatches(mState.acceptMimes, docMimeType);
        }

        @Override
        public void updateActionMenu(Menu menu, int dirType, boolean canDelete) {

            boolean copyEnabled = dirType != DirectoryFragment.TYPE_RECENT_OPEN;
            boolean moveEnabled =
                    SystemProperties.getBoolean("debug.documentsui.enable_move", false);
            menu.findItem(R.id.menu_copy_to_clipboard).setEnabled(copyEnabled);

            final MenuItem open = menu.findItem(R.id.menu_open);
            final MenuItem share = menu.findItem(R.id.menu_share);
            final MenuItem delete = menu.findItem(R.id.menu_delete);
            final MenuItem copyTo = menu.findItem(R.id.menu_copy_to);
            final MenuItem moveTo = menu.findItem(R.id.menu_move_to);

            open.setVisible(true);
            share.setVisible(false);
            delete.setVisible(false);
            copyTo.setVisible(copyEnabled);
            copyTo.setEnabled(copyEnabled);
            moveTo.setVisible(moveEnabled);
            moveTo.setEnabled(moveEnabled);
        }
    }

    /**
     * Provides support for Platform specific specializations of DirectoryFragment.
     */
    private static final class DownloadsTuner extends FragmentTuner {

        public DownloadsTuner(State state) {
            super(state);
        }

        @Override
        public void updateActionMenu(Menu menu, int dirType, boolean canDelete) {
            checkArgument(dirType != DirectoryFragment.TYPE_RECENT_OPEN);

            boolean moveEnabled =
                    SystemProperties.getBoolean("debug.documentsui.enable_move", false);
            menu.findItem(R.id.menu_copy_to_clipboard).setEnabled(true);

            final MenuItem open = menu.findItem(R.id.menu_open);
            final MenuItem share = menu.findItem(R.id.menu_share);
            final MenuItem delete = menu.findItem(R.id.menu_delete);
            final MenuItem copyTo = menu.findItem(R.id.menu_copy_to);
            final MenuItem moveTo = menu.findItem(R.id.menu_move_to);

            open.setVisible(false);
            share.setVisible(false);
            delete.setVisible(canDelete);
            copyTo.setVisible(true);
            copyTo.setEnabled(true);
            moveTo.setVisible(moveEnabled);
            moveTo.setEnabled(moveEnabled);
        }
    }

    /**
     * Provides support for Files activity specific specializations of DirectoryFragment.
     */
    private static final class FilesTuner extends FragmentTuner {

        public FilesTuner(State state) {
            super(state);
        }

        @Override
        public void updateActionMenu(Menu menu, int dirType, boolean canDelete) {

            MenuItem copy = menu.findItem(R.id.menu_copy_to_clipboard);
            MenuItem paste = menu.findItem(R.id.menu_paste_from_clipboard);
            copy.setEnabled(dirType != DirectoryFragment.TYPE_RECENT_OPEN);

            menu.findItem(R.id.menu_share).setVisible(true);
            menu.findItem(R.id.menu_delete).setVisible(canDelete);

            menu.findItem(R.id.menu_open).setVisible(false);
            menu.findItem(R.id.menu_copy_to).setVisible(true);
            menu.findItem(R.id.menu_move_to).setVisible(true);

            Menus.disableHiddenItems(menu, copy, paste);
        }
    }

    private static boolean isDirectory(String mimeType) {
        return Document.MIME_TYPE_DIR.equals(mimeType);
    }
}
