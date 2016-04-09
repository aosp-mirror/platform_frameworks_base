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
import static com.android.documentsui.State.ACTION_CREATE;
import static com.android.documentsui.State.ACTION_GET_CONTENT;
import static com.android.documentsui.State.ACTION_OPEN;
import static com.android.documentsui.State.ACTION_OPEN_TREE;
import static com.android.documentsui.State.ACTION_PICK_COPY_DESTINATION;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.android.documentsui.RecentsProvider.RecentColumns;
import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.dirlist.AnimationView;
import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.dirlist.Model;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperationService;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class DocumentsActivity extends BaseActivity {
    private static final int CODE_FORWARD = 42;
    private static final String TAG = "DocumentsActivity";

    public DocumentsActivity() {
        super(R.layout.documents_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (mState.action == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        } else if (mState.action == ACTION_OPEN_TREE ||
                   mState.action == ACTION_PICK_COPY_DESTINATION) {
            PickFragment.show(getFragmentManager());
        }

        if (mState.action == ACTION_GET_CONTENT) {
            final Intent moreApps = new Intent(getIntent());
            moreApps.setComponent(null);
            moreApps.setPackage(null);
            RootsFragment.show(getFragmentManager(), moreApps);
        } else if (mState.action == ACTION_OPEN ||
                   mState.action == ACTION_CREATE ||
                   mState.action == ACTION_OPEN_TREE ||
                   mState.action == ACTION_PICK_COPY_DESTINATION) {
            RootsFragment.show(getFragmentManager(), null);
        }

        if (mState.restored) {
            if (DEBUG) Log.d(TAG, "Stack already resolved");
        } else {
            // We set the activity title in AsyncTask.onPostExecute().
            // To prevent talkback from reading aloud the default title, we clear it here.
            setTitle("");

            // As a matter of policy we don't load the last used stack for the copy
            // destination picker (user is already in Files app).
            // Concensus was that the experice was too confusing.
            // In all other cases, where the user is visiting us from another app
            // we restore the stack as last used from that app.
            if (mState.action == ACTION_PICK_COPY_DESTINATION) {
                if (DEBUG) Log.d(TAG, "Launching directly into Home directory.");
                loadRoot(getDefaultRoot());
            } else {
                if (DEBUG) Log.d(TAG, "Attempting to load last used stack for calling package.");
                new LoadLastUsedStackTask(this).execute();
            }
        }
    }

    @Override
    void includeState(State state) {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            state.action = ACTION_OPEN;
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            state.action = ACTION_CREATE;
        } else if (Intent.ACTION_GET_CONTENT.equals(action)) {
            state.action = ACTION_GET_CONTENT;
        } else if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            state.action = ACTION_OPEN_TREE;
        } else if (Shared.ACTION_PICK_COPY_DESTINATION.equals(action)) {
            state.action = ACTION_PICK_COPY_DESTINATION;
        }

        if (state.action == ACTION_OPEN || state.action == ACTION_GET_CONTENT) {
            state.allowMultiple = intent.getBooleanExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE, false);
        }

        if (state.action == ACTION_OPEN || state.action == ACTION_GET_CONTENT
                || state.action == ACTION_CREATE) {
            state.openableOnly = intent.hasCategory(Intent.CATEGORY_OPENABLE);
        }

        if (state.action == ACTION_PICK_COPY_DESTINATION) {
            // Indicates that a copy operation (or move) includes a directory.
            // Why? Directory creation isn't supported by some roots (like Downloads).
            // This allows us to restrict available roots to just those with support.
            state.directoryCopy = intent.getBooleanExtra(
                    Shared.EXTRA_DIRECTORY_COPY, false);
            state.copyOperationSubType = intent.getIntExtra(
                    FileOperationService.EXTRA_OPERATION,
                    FileOperationService.OPERATION_COPY);
        }
    }

    public void onAppPicked(ResolveInfo info) {
        final Intent intent = new Intent(getIntent());
        intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.setComponent(new ComponentName(
                info.activityInfo.applicationInfo.packageName, info.activityInfo.name));
        startActivityForResult(intent, CODE_FORWARD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (DEBUG) Log.d(TAG, "onActivityResult() code=" + resultCode);

        // Only relay back results when not canceled; otherwise stick around to
        // let the user pick another app/backend.
        if (requestCode == CODE_FORWARD && resultCode != RESULT_CANCELED) {

            // Remember that we last picked via external app
            final String packageName = getCallingPackageMaybeExtra();
            final ContentValues values = new ContentValues();
            values.put(ResumeColumns.EXTERNAL, 1);
            getContentResolver().insert(RecentsProvider.buildResume(packageName), values);

            // Pass back result to original caller
            setResult(resultCode, data);
            finish();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawer.update();
        mNavigator.update();
    }

    @Override
    public String getDrawerTitle() {
        String title = getIntent().getStringExtra(DocumentsContract.EXTRA_PROMPT);
        if (title == null) {
            if (mState.action == ACTION_OPEN ||
                mState.action == ACTION_GET_CONTENT ||
                mState.action == ACTION_OPEN_TREE) {
                title = getResources().getString(R.string.title_open);
            } else if (mState.action == ACTION_CREATE ||
                       mState.action == ACTION_PICK_COPY_DESTINATION) {
                title = getResources().getString(R.string.title_save);
            } else {
                // If all else fails, just call it "Documents".
                title = getResources().getString(R.string.app_label);
            }
        }

        return title;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final DocumentInfo cwd = getCurrentDirectory();

        boolean picking = mState.action == ACTION_CREATE
                || mState.action == ACTION_OPEN_TREE
                || mState.action == ACTION_PICK_COPY_DESTINATION;

        if (picking) {
            // May already be hidden because the root
            // doesn't support search.
            mSearchManager.showMenu(false);
        }

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem fileSize = menu.findItem(R.id.menu_file_size);


        createDir.setVisible(picking);
        createDir.setEnabled(canCreateDirectory());

        // No display options in recent directories
        boolean inRecents = cwd == null;
        if (picking && inRecents) {
            grid.setVisible(false);
            list.setVisible(false);
        }

        fileSize.setVisible(fileSize.isVisible() && !picking);

        if (mState.action == ACTION_CREATE) {
            final FragmentManager fm = getFragmentManager();
            SaveFragment.get(fm).prepareForDirectory(cwd);
        }

        Menus.disableHiddenItems(menu);

        return true;
    }

    @Override
    void refreshDirectory(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        if (cwd == null) {
            // No directory means recents
            if (mState.action == ACTION_CREATE ||
                mState.action == ACTION_OPEN_TREE ||
                mState.action == ACTION_PICK_COPY_DESTINATION) {
                RecentsCreateFragment.show(fm);
            } else {
                DirectoryFragment.showRecentsOpen(fm, anim);

                // In recents we pick layout mode based on the mimetype,
                // picking GRID for visual types. We intentionally don't
                // consult a user's saved preferences here since they are
                // set per root (not per root and per mimetype).
                boolean visualMimes = MimePredicate.mimeMatches(
                        MimePredicate.VISUAL_MIMES, mState.acceptMimes);
                mState.derivedMode = visualMimes ? State.MODE_GRID : State.MODE_LIST;
            }
        } else {
                // Normal boring directory
                DirectoryFragment.showDirectory(fm, root, cwd, anim);
        }

        // Forget any replacement target
        if (mState.action == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        if (mState.action == ACTION_OPEN_TREE ||
            mState.action == ACTION_PICK_COPY_DESTINATION) {
            final PickFragment pick = PickFragment.get(fm);
            if (pick != null) {
                pick.setPickTarget(mState.action, mState.copyOperationSubType, cwd);
            }
        }
    }

    void onSaveRequested(DocumentInfo replaceTarget) {
        new ExistingFinishTask(this, replaceTarget.derivedUri)
                .executeOnExecutor(getExecutorForCurrentDirectory());
    }

    @Override
    void onDirectoryCreated(DocumentInfo doc) {
        assert(doc.isDirectory());
        openContainerDocument(doc);
    }

    void onSaveRequested(String mimeType, String displayName) {
        new CreateFinishTask(this, mimeType, displayName)
                .executeOnExecutor(getExecutorForCurrentDirectory());
    }

    @Override
    void onRootPicked(RootInfo root) {
        super.onRootPicked(root);
        mNavigator.revealRootsDrawer(false);
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc, Model model) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isContainer()) {
            openContainerDocument(doc);
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            // Explicit file picked, return
            new ExistingFinishTask(this, doc.derivedUri)
                    .executeOnExecutor(getExecutorForCurrentDirectory());
        } else if (mState.action == ACTION_CREATE) {
            // Replace selected file
            SaveFragment.get(fm).setReplaceTarget(doc);
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).derivedUri;
            }
            new ExistingFinishTask(this, uris)
                    .executeOnExecutor(getExecutorForCurrentDirectory());
        }
    }

    public void onPickRequested(DocumentInfo pickTarget) {
        Uri result;
        if (mState.action == ACTION_OPEN_TREE) {
            result = DocumentsContract.buildTreeDocumentUri(
                    pickTarget.authority, pickTarget.documentId);
        } else if (mState.action == ACTION_PICK_COPY_DESTINATION) {
            result = pickTarget.derivedUri;
        } else {
            // Should not be reached.
            throw new IllegalStateException("Invalid mState.action.");
        }
        new PickFinishTask(this, result).executeOnExecutor(getExecutorForCurrentDirectory());
    }

    void writeStackToRecentsBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);
        if (mState.action == ACTION_CREATE ||
            mState.action == ACTION_OPEN_TREE ||
            mState.action == ACTION_PICK_COPY_DESTINATION) {
            // Remember stack for last create
            values.clear();
            values.put(RecentColumns.KEY, mState.stack.buildKey());
            values.put(RecentColumns.STACK, rawStack);
            resolver.insert(RecentsProvider.buildRecent(), values);
        }

        // Remember location for next app launch
        final String packageName = getCallingPackageMaybeExtra();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        values.put(ResumeColumns.EXTERNAL, 0);
        resolver.insert(RecentsProvider.buildResume(packageName), values);
    }

    @Override
    void onTaskFinished(Uri... uris) {
        if (DEBUG) Log.d(TAG, "onFinished() " + Arrays.toString(uris));

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

        if (mState.action == ACTION_GET_CONTENT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (mState.action == ACTION_OPEN_TREE) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        } else if (mState.action == ACTION_PICK_COPY_DESTINATION) {
            // Picking a copy destination is only used internally by us, so we
            // don't need to extend permissions to the caller.
            intent.putExtra(Shared.EXTRA_STACK, (Parcelable) mState.stack);
            intent.putExtra(FileOperationService.EXTRA_OPERATION, mState.copyOperationSubType);
        } else {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }


    public static DocumentsActivity get(Fragment fragment) {
        return (DocumentsActivity) fragment.getActivity();
    }

    /**
     * Loads the last used path (stack) from Recents (history).
     * The path selected is based on the calling package name. So the last
     * path for an app like Gmail can be different than the last path
     * for an app like DropBox.
     */
    private static final class LoadLastUsedStackTask
            extends PairedTask<DocumentsActivity, Void, Void> {

        private volatile boolean mRestoredStack;
        private volatile boolean mExternal;
        private State mState;

        public LoadLastUsedStackTask(DocumentsActivity activity) {
            super(activity);
            mState = activity.mState;
        }

        @Override
        protected Void run(Void... params) {
            if (DEBUG && !mState.stack.isEmpty()) {
                Log.w(TAG, "Overwriting existing stack.");
            }
            RootsCache roots = DocumentsApplication.getRootsCache(mOwner);

            String packageName = mOwner.getCallingPackageMaybeExtra();
            Uri resumeUri = RecentsProvider.buildResume(packageName);
            Cursor cursor = mOwner.getContentResolver().query(resumeUri, null, null, null, null);
            try {
                if (cursor.moveToFirst()) {
                    mExternal = cursor.getInt(cursor.getColumnIndex(ResumeColumns.EXTERNAL)) != 0;
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(ResumeColumns.STACK));
                    DurableUtils.readFromArray(rawStack, mState.stack);
                    mRestoredStack = true;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to resume: " + e);
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            if (mRestoredStack) {
                // Update the restored stack to ensure we have freshest data
                final Collection<RootInfo> matchingRoots = roots.getMatchingRootsBlocking(mState);
                try {
                    mState.stack.updateRoot(matchingRoots);
                    mState.stack.updateDocuments(mOwner.getContentResolver());
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to restore stack for package: " + packageName
                            + " because of error: "+ e);
                    mState.stack.reset();
                    mRestoredStack = false;
                }
            }

            return null;
        }

        @Override
        protected void finish(Void result) {
            mState.restored = true;
            mState.external = mExternal;
            mOwner.refreshCurrentRootAndDirectory(AnimationView.ANIM_NONE);
        }
    }

    private static final class PickFinishTask extends PairedTask<DocumentsActivity, Void, Void> {
        private final Uri mUri;

        public PickFinishTask(DocumentsActivity activity, Uri uri) {
            super(activity);
            mUri = uri;
        }

        @Override
        protected Void run(Void... params) {
            mOwner.writeStackToRecentsBlocking();
            return null;
        }

        @Override
        protected void finish(Void result) {
            mOwner.onTaskFinished(mUri);
        }
    }

    private static final class ExistingFinishTask extends PairedTask<DocumentsActivity, Void, Void> {
        private final Uri[] mUris;

        public ExistingFinishTask(DocumentsActivity activity, Uri... uris) {
            super(activity);
            mUris = uris;
        }

        @Override
        protected Void run(Void... params) {
            mOwner.writeStackToRecentsBlocking();
            return null;
        }

        @Override
        protected void finish(Void result) {
            mOwner.onTaskFinished(mUris);
        }
    }

    /**
     * Task that creates a new document in the background.
     */
    private static final class CreateFinishTask extends PairedTask<DocumentsActivity, Void, Uri> {
        private final String mMimeType;
        private final String mDisplayName;

        public CreateFinishTask(DocumentsActivity activity, String mimeType, String displayName) {
            super(activity);
            mMimeType = mimeType;
            mDisplayName = displayName;
        }

        @Override
        protected void prepare() {
            mOwner.setPending(true);
        }

        @Override
        protected Uri run(Void... params) {
            final ContentResolver resolver = mOwner.getContentResolver();
            final DocumentInfo cwd = mOwner.getCurrentDirectory();

            ContentProviderClient client = null;
            Uri childUri = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, cwd.derivedUri.getAuthority());
                childUri = DocumentsContract.createDocument(
                        client, cwd.derivedUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create document", e);
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }

            if (childUri != null) {
                mOwner.writeStackToRecentsBlocking();
            }

            return childUri;
        }

        @Override
        protected void finish(Uri result) {
            if (result != null) {
                mOwner.onTaskFinished(result);
            } else {
                Snackbars.makeSnackbar(
                        mOwner, R.string.save_error, Snackbar.LENGTH_SHORT).show();
            }

            mOwner.setPending(false);
        }
    }
}
