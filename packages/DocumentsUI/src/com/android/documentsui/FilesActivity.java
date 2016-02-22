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

package com.android.documentsui;

import static com.android.documentsui.OperationDialogFragment.DIALOG_TYPE_UNKNOWN;
import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.dirlist.DirectoryFragment.ANIM_NONE;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkState;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;

import com.android.documentsui.OperationDialogFragment.DialogType;
import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.dirlist.Model;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperationService;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Standalone file management activity.
 */
public class FilesActivity extends BaseActivity {

    public static final String TAG = "FilesActivity";

    private DocumentClipper mClipper;

    public FilesActivity() {
        super(R.layout.files_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mClipper = new DocumentClipper(this);

        RootsFragment.show(getFragmentManager(), null);

        final Intent intent = getIntent();
        final Uri uri = intent.getData();

        if (mState.restored) {
            if (DEBUG) Log.d(TAG, "Stack already resolved for uri: " + intent.getData());
        } else if (!mState.stack.isEmpty()) {
            // If a non-empty stack is present in our state it was read (presumably)
            // from EXTRA_STACK intent extra. In this case, we'll skip other means of
            // loading or restoring the stack.
            //
            // When restoring from a stack, if a URI is present, it should only ever
            // be a launch URI, or a fake Uri from notifications.
            // Launch URIs support sensible activity management, but don't specify a real
            // content target.
            if (DEBUG) Log.d(TAG, "Launching with non-empty stack.");
            checkState(uri == null || uri.getAuthority() == null ||
                    LauncherActivity.isLaunchUri(uri));
            refreshCurrentRootAndDirectory(ANIM_NONE);
        } else if (intent.getAction() == Intent.ACTION_VIEW) {
            checkArgument(uri != null);
            new OpenUriForViewTask(this).executeOnExecutor(
                    ProviderExecutor.forAuthority(uri.getAuthority()), uri);
        } else if (DocumentsContract.isRootUri(this, uri)) {
            if (DEBUG) Log.d(TAG, "Launching with root URI.");
            // If we've got a specific root to display, restore that root using a dedicated
            // authority. That way a misbehaving provider won't result in an ANR.
            new RestoreRootTask(this, uri).executeOnExecutor(
                    ProviderExecutor.forAuthority(uri.getAuthority()));
        } else {
            if (DEBUG) Log.d(TAG, "Launching into Home directory.");
            // If all else fails, try to load "Home" directory.
            final Uri homeUri = DocumentsContract.buildHomeUri();
            new RestoreRootTask(this, homeUri).executeOnExecutor(
                    ProviderExecutor.forAuthority(homeUri.getAuthority()));
        }

        final @DialogType int dialogType = intent.getIntExtra(
                FileOperationService.EXTRA_DIALOG_TYPE, DIALOG_TYPE_UNKNOWN);
        // DialogFragment takes care of restoring the dialog on configuration change.
        // Only show it manually for the first time (icicle is null).
        if (icicle == null && dialogType != DIALOG_TYPE_UNKNOWN) {
            final int opType = intent.getIntExtra(
                    FileOperationService.EXTRA_OPERATION,
                    FileOperationService.OPERATION_COPY);
            final ArrayList<DocumentInfo> srcList =
                    intent.getParcelableArrayListExtra(FileOperationService.EXTRA_SRC_LIST);
            OperationDialogFragment.show(
                    getFragmentManager(),
                    dialogType,
                    srcList,
                    mState.stack,
                    opType);
        }
    }

    @Override
    void includeState(State state) {
        final Intent intent = getIntent();

        state.action = State.ACTION_BROWSE;
        state.allowMultiple = true;

        // Options specific to the DocumentsActivity.
        checkArgument(!intent.hasExtra(Intent.EXTRA_LOCAL_ONLY));

        final DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);
        if (stack != null) {
            state.stack = stack;
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // This check avoids a flicker from "Recents" to "Home".
        // Only update action bar at this point if there is an active
        // serach. Why? Because this avoid an early (undesired) load of
        // the recents root...which is the default root in other activities.
        // In Files app "Home" is the default, but it is loaded async.
        // update will be called once Home root is loaded.
        // Except while searching we need this call to ensure the
        // search bits get layed out correctly.
        if (mSearchManager.isSearching()) {
            mNavigator.update();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        final RootInfo root = getCurrentRoot();

        // If we're browsing a specific root, and that root went away, then we
        // have no reason to hang around.
        // TODO: Rather than just disappearing, maybe we should inform
        // the user what has happened, let them close us. Less surprising.
        if (mRoots.getRootBlocking(root.authority, root.rootId) == null) {
            finish();
        }
    }

    @Override
    public String getDrawerTitle() {
        return getResources().getString(R.string.files_label);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final RootInfo root = getCurrentRoot();

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem pasteFromCb = menu.findItem(R.id.menu_paste_from_clipboard);
        final MenuItem settings = menu.findItem(R.id.menu_settings);
        final MenuItem newWindow = menu.findItem(R.id.menu_new_window);

        createDir.setVisible(true);
        createDir.setEnabled(canCreateDirectory());
        pasteFromCb.setEnabled(mClipper.hasItemsToPaste());
        settings.setVisible(root.hasSettings());
        newWindow.setVisible(true);

        Menus.disableHiddenItems(menu, pasteFromCb);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_create_dir:
                checkState(canCreateDirectory());
                showCreateDirectoryDialog();
                return true;
            case R.id.menu_new_window:
                createNewWindow();
                return true;
            case R.id.menu_paste_from_clipboard:
                DirectoryFragment dir = getDirectoryFragment();
                if (dir != null) {
                    dir.pasteFromClipboard();
                }
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void createNewWindow() {
        Metrics.logMultiWindow(this);
        Intent intent = LauncherActivity.createLaunchIntent(this);
        intent.putExtra(Shared.EXTRA_STACK, (Parcelable) mState.stack);

        // With new multi-window mode we have to pick how we are launched.
        // By default we'd be launched in-place above the existing app.
        // By setting launch-to-side ActivityManager will open us to side.
        if (inMultiWindow()) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        }

        startActivity(intent);
    }

    @Override
    void refreshDirectory(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        if (DEBUG) checkState(!mSearchManager.isSearching());

        if (cwd == null) {
            DirectoryFragment.showRecentsOpen(fm, anim);
        } else {
            // Normal boring directory
            DirectoryFragment.showDirectory(fm, root, cwd, anim);
        }
    }

    @Override
    void onRootPicked(RootInfo root) {
        super.onRootPicked(root);
        mDrawer.setOpen(false);
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc, Model model) {
        if (doc.isContainer()) {
            openContainerDocument(doc);
        } else {
            openDocument(doc, model);
        }
    }

    /**
     * Launches an intent to view the specified document.
     */
    private void openDocument(DocumentInfo doc, Model model) {
        Intent intent = new QuickViewIntentBuilder(
                getPackageManager(), getResources(), doc, model).build();

        if (intent != null) {
            // TODO: un-work around issue b/24963914. Should be fixed soon.
            try {
                startActivity(intent);
                return;
            } catch (SecurityException e) {
                // Carry on to regular view mode.
                Log.e(TAG, "Caught security error: " + e.getLocalizedMessage());
            }
        }

        // Fallback to traditional VIEW action...
        intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setData(doc.derivedUri);

        if (DEBUG && intent.getClipData() != null) {
            Log.d(TAG, "Starting intent w/ clip data: " + intent.getClipData());
        }

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Snackbars.makeSnackbar(
                    this, R.string.toast_no_application, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        DirectoryFragment dir;
        // TODO: All key events should be statically bound using alphabeticShortcut.
        // But not working.
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                dir = getDirectoryFragment();
                if (dir != null) {
                    dir.selectAllFiles();
                }
                return true;
            case KeyEvent.KEYCODE_C:
                dir = getDirectoryFragment();
                if (dir != null) {
                    dir.copySelectedToClipboard();
                }
                return true;
            case KeyEvent.KEYCODE_V:
                dir = getDirectoryFragment();
                if (dir != null) {
                    dir.pasteFromClipboard();
                }
                return true;
            default:
                return super.onKeyShortcut(keyCode, event);
        }
    }

    // Turns out only DocumentsActivity was ever calling saveStackBlocking.
    // There may be a  case where we want to contribute entries from
    // Behavior here in FilesActivity, but it isn't yet obvious.
    // TODO: Contribute to recents, or remove this.
    void writeStackToRecentsBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);

        // Remember location for next app launch
        final String packageName = getCallingPackageMaybeExtra();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        values.put(ResumeColumns.EXTERNAL, 0);
        resolver.insert(RecentsProvider.buildResume(packageName), values);
    }

    @Override
    void onTaskFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    /**
     * Builds a stack for the specific Uris. Multi roots are not supported, as it's impossible
     * to know which root to select. Also, the stack doesn't contain intermediate directories.
     * It's primarly used for opening ZIP archives from Downloads app.
     */
    private static final class OpenUriForViewTask extends PairedTask<FilesActivity, Uri, Void> {

        private final State mState;
        public OpenUriForViewTask(FilesActivity activity) {
            super(activity);
            mState = activity.mState;
        }

        @Override
        protected Void run(Uri... params) {
            final Uri uri = params[0];

            final RootsCache rootsCache = DocumentsApplication.getRootsCache(mOwner);
            final String authority = uri.getAuthority();

            final Collection<RootInfo> roots =
                    rootsCache.getRootsForAuthorityBlocking(authority);
            if (roots.isEmpty()) {
                Log.e(TAG, "Failed to find root for the requested Uri: " + uri);
                return null;
            }

            final RootInfo root = roots.iterator().next();
            mState.stack.root = root;
            try {
                mState.stack.add(DocumentInfo.fromUri(mOwner.getContentResolver(), uri));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Failed to resolve DocumentInfo from Uri: " + uri);
            }
            mState.stack.add(mOwner.getRootDocumentBlocking(root));
            return null;
        }

        @Override
        protected void finish(Void result) {
            mOwner.refreshCurrentRootAndDirectory(ANIM_NONE);
        }
    }
}
