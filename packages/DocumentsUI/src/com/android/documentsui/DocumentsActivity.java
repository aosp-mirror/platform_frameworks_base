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

import static com.android.documentsui.DocumentsActivity.State.ACTION_CREATE;
import static com.android.documentsui.DocumentsActivity.State.ACTION_GET_CONTENT;
import static com.android.documentsui.DocumentsActivity.State.ACTION_MANAGE;
import static com.android.documentsui.DocumentsActivity.State.ACTION_OPEN;
import static com.android.documentsui.DocumentsActivity.State.MODE_GRID;
import static com.android.documentsui.DocumentsActivity.State.MODE_LIST;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_LAST_MODIFIED;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.provider.DocumentsContract;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;
import android.widget.Toast;

import com.android.documentsui.RecentsProvider.RecentColumns;
import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.RecentsProvider.StateColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class DocumentsActivity extends Activity {
    public static final String TAG = "Documents";

    private SearchView mSearchView;

    private View mRootsContainer;
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;

    private static final String EXTRA_STATE = "state";

    private boolean mIgnoreNextNavigation;

    private RootsCache mRoots;
    private State mState;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mRoots = DocumentsApplication.getRootsCache(this);

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        mRootsContainer = findViewById(R.id.container_roots);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.drawable.ic_drawer, R.string.drawer_open, R.string.drawer_close);

        mDrawerLayout.setDrawerListener(mDrawerListener);
        mDrawerLayout.setDrawerShadow(R.drawable.ic_drawer_shadow, GravityCompat.START);

        if (icicle != null) {
            mState = icicle.getParcelable(EXTRA_STATE);
        } else {
            buildDefaultState();
        }

        if (mState.action == ACTION_MANAGE) {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }

        if (mState.action == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        }

        if (mState.action == ACTION_GET_CONTENT) {
            final Intent moreApps = new Intent(getIntent());
            moreApps.setComponent(null);
            moreApps.setPackage(null);
            RootsFragment.show(getFragmentManager(), moreApps);
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_CREATE) {
            RootsFragment.show(getFragmentManager(), null);
        }

        onCurrentDirectoryChanged();
    }

    private void buildDefaultState() {
        mState = new State();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            mState.action = ACTION_OPEN;
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            mState.action = ACTION_CREATE;
        } else if (Intent.ACTION_GET_CONTENT.equals(action)) {
            mState.action = ACTION_GET_CONTENT;
        } else if (DocumentsContract.ACTION_MANAGE_ROOT.equals(action)) {
            mState.action = ACTION_MANAGE;
        }

        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            mState.allowMultiple = intent.getBooleanExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE, false);
        }

        if (mState.action == ACTION_MANAGE) {
            mState.acceptMimes = new String[] { "*/*" };
            mState.allowMultiple = true;
        } else if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            mState.acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            mState.acceptMimes = new String[] { intent.getType() };
        }

        mState.localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false);
        mState.showAdvanced = SettingsActivity.getDisplayAdvancedDevices(this);

        if (mState.action == ACTION_MANAGE) {
            mState.sortOrder = SORT_ORDER_LAST_MODIFIED;
        }

        if (mState.action == ACTION_MANAGE) {
            final Uri uri = intent.getData();
            final String rootId = DocumentsContract.getRootId(uri);
            final RootInfo root = mRoots.getRoot(uri.getAuthority(), rootId);
            if (root != null) {
                onRootPicked(root, true);
            } else {
                Log.w(TAG, "Failed to find root: " + uri);
                finish();
            }

        } else {
            // Restore last stack for calling package
            // TODO: move into async loader
            final String packageName = getCallingPackage();
            final Cursor cursor = getContentResolver()
                    .query(RecentsProvider.buildResume(packageName), null, null, null, null);
            try {
                if (cursor.moveToFirst()) {
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(ResumeColumns.STACK));
                    DurableUtils.readFromArray(rawStack, mState.stack);
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to resume", e);
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            // If restored root isn't valid, fall back to recents
            final RootInfo root = getCurrentRoot();
            final List<RootInfo> matchingRoots = mRoots.getMatchingRoots(mState);
            if (!matchingRoots.contains(root)) {
                mState.stack.reset();
            }

            // Only open drawer when showing recents
            if (mState.stack.isRecents()) {
                mDrawerLayout.openDrawer(mRootsContainer);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mState.action == ACTION_MANAGE) {
            mState.showSize = true;
        } else {
            mState.showSize = SettingsActivity.getDisplayFileSize(this);
        }
    }

    private DrawerListener mDrawerListener = new DrawerListener() {
        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {
            mDrawerToggle.onDrawerSlide(drawerView, slideOffset);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            mDrawerToggle.onDrawerOpened(drawerView);
            updateActionBar();
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            mDrawerToggle.onDrawerClosed(drawerView);
            updateActionBar();
        }

        @Override
        public void onDrawerStateChanged(int newState) {
            mDrawerToggle.onDrawerStateChanged(newState);
        }
    };

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    public void updateActionBar() {
        final ActionBar actionBar = getActionBar();

        actionBar.setDisplayShowHomeEnabled(true);

        if (mState.action == ACTION_MANAGE) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            mDrawerToggle.setDrawerIndicatorEnabled(false);
        } else {
            actionBar.setDisplayHomeAsUpEnabled(true);
            mDrawerToggle.setDrawerIndicatorEnabled(true);
        }

        if (mDrawerLayout.isDrawerOpen(mRootsContainer)) {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            actionBar.setIcon(new ColorDrawable());

            if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
                actionBar.setTitle(R.string.title_open);
            } else if (mState.action == ACTION_CREATE) {
                actionBar.setTitle(R.string.title_save);
            }
        } else {
            final RootInfo root = getCurrentRoot();
            actionBar.setIcon(root != null ? root.loadIcon(this) : null);

            if (mState.stack.size() <= 1) {
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
                actionBar.setTitle(root.title);
            } else {
                mIgnoreNextNavigation = true;
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
                actionBar.setTitle(null);
                actionBar.setListNavigationCallbacks(mStackAdapter, mStackListener);
                actionBar.setSelectedNavigationItem(mStackAdapter.getCount() - 1);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity, menu);

        final MenuItem searchMenu = menu.findItem(R.id.menu_search);
        mSearchView = (SearchView) searchMenu.getActionView();
        mSearchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mState.currentSearch = query;
                onCurrentDirectoryChanged();
                mSearchView.setIconified(true);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        mSearchView.setOnCloseListener(new OnCloseListener() {
            @Override
            public boolean onClose() {
                mState.currentSearch = null;
                onCurrentDirectoryChanged();
                return false;
            }
        });

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final FragmentManager fm = getFragmentManager();
        final DocumentInfo cwd = getCurrentDirectory();

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem search = menu.findItem(R.id.menu_search);
        final MenuItem sort = menu.findItem(R.id.menu_sort);
        final MenuItem sortSize = menu.findItem(R.id.menu_sort_size);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem settings = menu.findItem(R.id.menu_settings);

        if (cwd != null) {
            sort.setVisible(true);
            grid.setVisible(mState.mode != MODE_GRID);
            list.setVisible(mState.mode != MODE_LIST);
        } else {
            sort.setVisible(false);
            grid.setVisible(false);
            list.setVisible(false);
        }

        // Only sort by size when visible
        sortSize.setVisible(mState.showSize);

        final boolean searchVisible;
        if (mState.action == ACTION_CREATE) {
            createDir.setVisible(cwd != null && cwd.isCreateSupported());
            searchVisible = false;

            // No display options in recent directories
            if (cwd == null) {
                grid.setVisible(false);
                list.setVisible(false);
            }

            SaveFragment.get(fm).setSaveEnabled(cwd != null && cwd.isCreateSupported());
        } else {
            createDir.setVisible(false);
            searchVisible = cwd != null && cwd.isSearchSupported();
        }

        // TODO: close any search in-progress when hiding
        search.setVisible(searchVisible);

        settings.setVisible(mState.action != ACTION_MANAGE);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        final int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.menu_create_dir) {
            CreateDirectoryFragment.show(getFragmentManager());
            return true;
        } else if (id == R.id.menu_search) {
            return false;
        } else if (id == R.id.menu_sort_name) {
            setUserSortOrder(State.SORT_ORDER_DISPLAY_NAME);
            return true;
        } else if (id == R.id.menu_sort_date) {
            setUserSortOrder(State.SORT_ORDER_LAST_MODIFIED);
            return true;
        } else if (id == R.id.menu_sort_size) {
            setUserSortOrder(State.SORT_ORDER_SIZE);
            return true;
        } else if (id == R.id.menu_grid) {
            setUserMode(State.MODE_GRID);
            return true;
        } else if (id == R.id.menu_list) {
            setUserMode(State.MODE_LIST);
            return true;
        } else if (id == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Update UI to reflect internal state changes not from user.
     */
    public void onStateChanged() {
        invalidateOptionsMenu();
    }

    /**
     * Set state sort order based on explicit user action.
     */
    private void setUserSortOrder(int sortOrder) {
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        // TODO: persist async, then trigger rebind
        final Uri stateUri = RecentsProvider.buildState(
                root.authority, root.rootId, cwd.documentId);
        final ContentValues values = new ContentValues();
        values.put(StateColumns.SORT_ORDER, sortOrder);
        getContentResolver().insert(stateUri, values);

        DirectoryFragment.get(getFragmentManager()).onUserSortOrderChanged();
        onStateChanged();
    }

    /**
     * Set state mode based on explicit user action.
     */
    private void setUserMode(int mode) {
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        // TODO: persist async, then trigger rebind
        final Uri stateUri = RecentsProvider.buildState(
                root.authority, root.rootId, cwd.documentId);
        final ContentValues values = new ContentValues();
        values.put(StateColumns.MODE, mode);
        getContentResolver().insert(stateUri, values);

        mState.mode = mode;

        DirectoryFragment.get(getFragmentManager()).onUserModeChanged();
        onStateChanged();
    }

    @Override
    public void onBackPressed() {
        final int size = mState.stack.size();
        if (size > 1) {
            mState.stack.pop();
            onCurrentDirectoryChanged();
        } else if (size == 1 && !mDrawerLayout.isDrawerOpen(mRootsContainer)) {
            // TODO: open root drawer once we can capture back key
            super.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable(EXTRA_STATE, mState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        updateActionBar();
    }

    private BaseAdapter mStackAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return mState.stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            // No padding when shown in actionbar
            convertView.setPadding(0, 0, 0, 0);
            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_title, parent, false);
            }

            final ImageView subdir = (ImageView) convertView.findViewById(R.id.subdir);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
                subdir.setVisibility(View.GONE);
            } else {
                title.setText(doc.displayName);
                subdir.setVisibility(View.VISIBLE);
            }

            return convertView;
        }
    };

    private OnNavigationListener mStackListener = new OnNavigationListener() {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long itemId) {
            if (mIgnoreNextNavigation) {
                mIgnoreNextNavigation = false;
                return false;
            }

            while (mState.stack.size() > itemPosition + 1) {
                mState.stack.pop();
            }
            onCurrentDirectoryChanged();
            return true;
        }
    };

    public RootInfo getCurrentRoot() {
        if (mState.stack.root != null) {
            return mState.stack.root;
        } else {
            return mRoots.getRecentsRoot();
        }
    }

    public DocumentInfo getCurrentDirectory() {
        return mState.stack.peek();
    }

    public State getDisplayState() {
        return mState;
    }

    private void onCurrentDirectoryChanged() {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        if (cwd == null) {
            // No directory means recents
            if (mState.action == ACTION_CREATE) {
                RecentsCreateFragment.show(fm);
            } else {
                DirectoryFragment.showRecentsOpen(fm);
            }
        } else {
            if (mState.currentSearch != null) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, root, cwd, mState.currentSearch);
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, root, cwd);
            }
        }

        // Forget any replacement target
        if (mState.action == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        final RootsFragment roots = RootsFragment.get(fm);
        if (roots != null) {
            roots.onCurrentRootChanged();
        }

        updateActionBar();
        invalidateOptionsMenu();
        dumpStack();
    }

    public void onStackPicked(DocumentStack stack) {
        mState.stack = stack;
        onCurrentDirectoryChanged();
    }

    public void onRootPicked(RootInfo root, boolean closeDrawer) {
        // Clear entire backstack and start in new root
        mState.stack.root = root;
        mState.stack.clear();

        if (!mRoots.isRecentsRoot(root)) {
            try {
                final Uri uri = DocumentsContract.buildDocumentUri(root.authority, root.documentId);
                onDocumentPicked(DocumentInfo.fromUri(getContentResolver(), uri));
            } catch (FileNotFoundException e) {
            }
        } else {
            onCurrentDirectoryChanged();
        }

        if (closeDrawer) {
            mDrawerLayout.closeDrawers();
        }
    }

    public void onAppPicked(ResolveInfo info) {
        final Intent intent = new Intent(getIntent());
        intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.setComponent(new ComponentName(
                info.activityInfo.applicationInfo.packageName, info.activityInfo.name));
        startActivity(intent);
        finish();
    }

    public void onDocumentPicked(DocumentInfo doc) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isDirectory()) {
            // TODO: query display mode user preference for this dir
            if (doc.isGridPreferred()) {
                mState.mode = MODE_GRID;
            } else {
                mState.mode = MODE_LIST;
            }
            mState.stack.push(doc);
            onCurrentDirectoryChanged();
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            // Explicit file picked, return
            onFinished(doc.derivedUri);
        } else if (mState.action == ACTION_CREATE) {
            // Replace selected file
            SaveFragment.get(fm).setReplaceTarget(doc);
        } else if (mState.action == ACTION_MANAGE) {
            // First try managing the document; we expect manager to filter
            // based on authority, so we don't grant.
            final Intent manage = new Intent(DocumentsContract.ACTION_MANAGE_DOCUMENT);
            manage.setData(doc.derivedUri);

            try {
                startActivity(manage);
            } catch (ActivityNotFoundException ex) {
                // Fall back to viewing
                final Intent view = new Intent(Intent.ACTION_VIEW);
                view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                view.setData(doc.derivedUri);

                try {
                    startActivity(view);
                } catch (ActivityNotFoundException ex2) {
                    Toast.makeText(this, R.string.toast_no_application, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    public void onDocumentsPicked(List<DocumentInfo> docs) {
        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).derivedUri;
            }
            onFinished(uris);
        }
    }

    public void onSaveRequested(DocumentInfo replaceTarget) {
        onFinished(replaceTarget.derivedUri);
    }

    public void onSaveRequested(String mimeType, String displayName) {
        final DocumentInfo cwd = getCurrentDirectory();

        final Uri childUri = DocumentsContract.createDocument(
                getContentResolver(), cwd.derivedUri, mimeType, displayName);
        if (childUri != null) {
            onFinished(childUri);
        } else {
            Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void onFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);
        if (mState.action == ACTION_CREATE) {
            // Remember stack for last create
            values.clear();
            values.put(RecentColumns.STACK, rawStack);
            resolver.insert(RecentsProvider.buildRecent(), values);
        }

        // Remember location for next app launch
        final String packageName = getCallingPackage();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        resolver.insert(RecentsProvider.buildResume(packageName), values);

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
        } else {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_PERSIST_GRANT_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static class State implements android.os.Parcelable {
        public int action;
        public int mode = MODE_LIST;
        public String[] acceptMimes;
        public int sortOrder = SORT_ORDER_DISPLAY_NAME;
        public boolean allowMultiple = false;
        public boolean showSize = false;
        public boolean localOnly = false;
        public boolean showAdvanced = false;

        /** Current user navigation stack; empty implies recents. */
        public DocumentStack stack = new DocumentStack();
        /** Currently active search, overriding any stack. */
        public String currentSearch;

        public static final int ACTION_OPEN = 1;
        public static final int ACTION_CREATE = 2;
        public static final int ACTION_GET_CONTENT = 3;
        public static final int ACTION_MANAGE = 4;

        public static final int MODE_UNKNOWN = 0;
        public static final int MODE_LIST = 1;
        public static final int MODE_GRID = 2;

        public static final int SORT_ORDER_UNKNOWN = 0;
        public static final int SORT_ORDER_DISPLAY_NAME = 1;
        public static final int SORT_ORDER_LAST_MODIFIED = 2;
        public static final int SORT_ORDER_SIZE = 3;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(action);
            out.writeInt(mode);
            out.writeStringArray(acceptMimes);
            out.writeInt(sortOrder);
            out.writeInt(allowMultiple ? 1 : 0);
            out.writeInt(showSize ? 1 : 0);
            out.writeInt(localOnly ? 1 : 0);
            out.writeInt(showAdvanced ? 1 : 0);
            DurableUtils.writeToParcel(out, stack);
            out.writeString(currentSearch);
        }

        public static final Creator<State> CREATOR = new Creator<State>() {
            @Override
            public State createFromParcel(Parcel in) {
                final State state = new State();
                state.action = in.readInt();
                state.mode = in.readInt();
                state.acceptMimes = in.readStringArray();
                state.sortOrder = in.readInt();
                state.allowMultiple = in.readInt() != 0;
                state.showSize = in.readInt() != 0;
                state.localOnly = in.readInt() != 0;
                state.showAdvanced = in.readInt() != 0;
                DurableUtils.readFromParcel(in, state.stack);
                state.currentSearch = in.readString();
                return state;
            }

            @Override
            public State[] newArray(int size) {
                return new State[size];
            }
        };
    }

    private void dumpStack() {
        Log.d(TAG, "Current stack: ");
        Log.d(TAG, " * " + mState.stack.root);
        for (DocumentInfo doc : mState.stack) {
            Log.d(TAG, " +-- " + doc);
        }
    }

    public static DocumentsActivity get(Fragment fragment) {
        return (DocumentsActivity) fragment.getActivity();
    }
}
