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

import static com.android.documentsui.DocumentsActivity.DisplayState.ACTION_CREATE;
import static com.android.documentsui.DocumentsActivity.DisplayState.ACTION_GET_CONTENT;
import static com.android.documentsui.DocumentsActivity.DisplayState.ACTION_MANAGE;
import static com.android.documentsui.DocumentsActivity.DisplayState.ACTION_OPEN;
import static com.android.documentsui.DocumentsActivity.DisplayState.MODE_GRID;
import static com.android.documentsui.DocumentsActivity.DisplayState.MODE_LIST;
import static com.android.documentsui.DocumentsActivity.DisplayState.SORT_ORDER_DATE;

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
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
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
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;
import android.widget.Toast;

import com.android.documentsui.model.Document;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.Root;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

public class DocumentsActivity extends Activity {
    public static final String TAG = "Documents";

    private int mAction;

    private SearchView mSearchView;

    private View mRootsContainer;
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;

    private final DisplayState mDisplayState = new DisplayState();

    private RootsCache mRoots;

    /** Current user navigation stack; empty implies recents. */
    private DocumentStack mStack = new DocumentStack();
    /** Currently active search, overriding any stack. */
    private String mCurrentSearch;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mRoots = DocumentsApplication.getRootsCache(this);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            mAction = ACTION_OPEN;
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            mAction = ACTION_CREATE;
        } else if (Intent.ACTION_GET_CONTENT.equals(action)) {
            mAction = ACTION_GET_CONTENT;
        } else if (Intent.ACTION_MANAGE_DOCUMENT.equals(action)) {
            mAction = ACTION_MANAGE;
        }

        // TODO: unify action in single place
        mDisplayState.action = mAction;

        if (mAction == ACTION_OPEN || mAction == ACTION_GET_CONTENT) {
            mDisplayState.allowMultiple = intent.getBooleanExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE, false);
        }

        if (mAction == ACTION_MANAGE) {
            mDisplayState.acceptMimes = new String[] { "*/*" };
            mDisplayState.allowMultiple = true;
        } else if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            mDisplayState.acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            mDisplayState.acceptMimes = new String[] { intent.getType() };
        }

        mDisplayState.localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false);

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        if (mAction == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        }

        if (mAction == ACTION_GET_CONTENT) {
            final Intent moreApps = new Intent(getIntent());
            moreApps.setComponent(null);
            moreApps.setPackage(null);
            RootsFragment.show(getFragmentManager(), moreApps);
        } else if (mAction == ACTION_OPEN || mAction == ACTION_CREATE) {
            RootsFragment.show(getFragmentManager(), null);
        }

        if (mAction == ACTION_MANAGE) {
            mDisplayState.sortOrder = SORT_ORDER_DATE;
        }

        mRootsContainer = findViewById(R.id.container_roots);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.drawable.ic_drawer, R.string.drawer_open, R.string.drawer_close);

        mDrawerLayout.setDrawerListener(mDrawerListener);
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        if (mAction == ACTION_MANAGE) {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

            final Uri rootUri = intent.getData();
            final String authority = rootUri.getAuthority();
            final String rootId = DocumentsContract.getRootId(rootUri);

            final Root root = mRoots.findRoot(authority, rootId);
            if (root != null) {
                onRootPicked(root, true);
            } else {
                Log.w(TAG, "Failed to find root: " + rootUri);
                finish();
            }

        } else {
            mDrawerLayout.openDrawer(mRootsContainer);

            // Restore last stack for calling package
            // TODO: move into async loader
            final String packageName = getCallingPackage();
            final Cursor cursor = getContentResolver()
                    .query(RecentsProvider.buildResume(packageName), null, null, null, null);
            try {
                if (cursor.moveToFirst()) {
                    final String raw = cursor.getString(
                            cursor.getColumnIndex(RecentsProvider.COL_PATH));
                    mStack = DocumentStack.deserialize(getContentResolver(), raw);
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to resume", e);
            } finally {
                cursor.close();
            }

            onCurrentDirectoryChanged();
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mAction == ACTION_MANAGE) {
            mDisplayState.showSize = true;
        } else {
            mDisplayState.showSize = SettingsActivity.getDisplayFileSize(this);
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

        if (mDrawerLayout.isDrawerOpen(mRootsContainer)) {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            actionBar.setIcon(new ColorDrawable());

            if (mAction == ACTION_OPEN || mAction == ACTION_GET_CONTENT) {
                actionBar.setTitle(R.string.title_open);
            } else if (mAction == ACTION_CREATE) {
                actionBar.setTitle(R.string.title_save);
            }

            actionBar.setDisplayHomeAsUpEnabled(true);
            mDrawerToggle.setDrawerIndicatorEnabled(true);

        } else {
            final Root root = getCurrentRoot();
            actionBar.setIcon(root != null ? root.icon : null);

            if (root.isRecents) {
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
                actionBar.setTitle(root.title);
            } else {
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
                actionBar.setTitle(null);
                actionBar.setListNavigationCallbacks(mSortAdapter, mSortListener);
                actionBar.setSelectedNavigationItem(mDisplayState.sortOrder);
            }

            if (mStack.size() > 1) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                mDrawerToggle.setDrawerIndicatorEnabled(false);
            } else if (mAction == ACTION_MANAGE) {
                actionBar.setDisplayHomeAsUpEnabled(false);
                mDrawerToggle.setDrawerIndicatorEnabled(false);
            } else {
                actionBar.setDisplayHomeAsUpEnabled(true);
                mDrawerToggle.setDrawerIndicatorEnabled(true);
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
                mCurrentSearch = query;
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
                mCurrentSearch = null;
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
        final Document cwd = getCurrentDirectory();

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem search = menu.findItem(R.id.menu_search);
        final MenuItem grid =  menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem settings = menu.findItem(R.id.menu_settings);

        grid.setVisible(mDisplayState.mode != MODE_GRID);
        list.setVisible(mDisplayState.mode != MODE_LIST);

        final boolean searchVisible;
        if (mAction == ACTION_CREATE) {
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

        settings.setVisible(mAction != ACTION_MANAGE);

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
        } else if (id == R.id.menu_grid) {
            // TODO: persist explicit user mode for cwd
            mDisplayState.mode = MODE_GRID;
            updateDisplayState();
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.menu_list) {
            // TODO: persist explicit user mode for cwd
            mDisplayState.mode = MODE_LIST;
            updateDisplayState();
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        final int size = mStack.size();
        if (size > 1) {
            mStack.pop();
            onCurrentDirectoryChanged();
        } else if (size == 1 && !mDrawerLayout.isDrawerOpen(mRootsContainer)) {
            // TODO: open root drawer once we can capture back key
            super.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    // TODO: support additional sort orders
    private BaseAdapter mSortAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return mDisplayState.showSize ? 3 : 2;
        }

        @Override
        public Object getItem(int position) {
            switch (position) {
                case 0:
                    return getText(R.string.sort_name);
                case 1:
                    return getText(R.string.sort_date);
                case 2:
                    return getText(R.string.sort_size);
                default:
                    return null;
            }
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
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final Document cwd = getCurrentDirectory();
            if (cwd != null) {
                title.setText(cwd.displayName);
            } else {
                // No directory means recents
                title.setText(R.string.root_recent);
            }

            summary.setText((String) getItem(position));

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(android.R.layout.simple_dropdown_item_1line, parent, false);
            }

            final TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);
            text1.setText((String) getItem(position));

            return convertView;
        }
    };

    private OnNavigationListener mSortListener = new OnNavigationListener() {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long itemId) {
            mDisplayState.sortOrder = itemPosition;
            updateDisplayState();
            return true;
        }
    };

    public Root getCurrentRoot() {
        final Document cwd = getCurrentDirectory();
        if (cwd != null) {
            return mRoots.findRoot(cwd);
        } else {
            return mRoots.getRecentsRoot();
        }
    }

    public Document getCurrentDirectory() {
        return mStack.peek();
    }

    public DisplayState getDisplayState() {
        return mDisplayState;
    }

    private void onCurrentDirectoryChanged() {
        final FragmentManager fm = getFragmentManager();
        final Document cwd = getCurrentDirectory();

        if (cwd == null) {
            // No directory means recents
            if (mAction == ACTION_CREATE) {
                RecentsCreateFragment.show(fm);
            } else {
                DirectoryFragment.showRecentsOpen(fm);
            }
        } else {
            if (mCurrentSearch != null) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, cwd.uri, mCurrentSearch);
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, cwd.uri);
            }
        }

        // Forget any replacement target
        if (mAction == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        updateActionBar();
        invalidateOptionsMenu();
        dumpStack();
    }

    private void updateDisplayState() {
        // TODO: handle multiple directory stacks on tablets
        DirectoryFragment.get(getFragmentManager()).updateDisplayState();
    }

    public void onStackPicked(DocumentStack stack) {
        mStack = stack;
        onCurrentDirectoryChanged();
    }

    public void onRootPicked(Root root, boolean closeDrawer) {
        // Clear entire backstack and start in new root
        mStack.clear();

        if (!root.isRecents) {
            try {
                onDocumentPicked(Document.fromRoot(getContentResolver(), root));
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

    public void onDocumentPicked(Document doc) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isDirectory()) {
            // TODO: query display mode user preference for this dir
            if (doc.isGridPreferred()) {
                mDisplayState.mode = MODE_GRID;
            } else {
                mDisplayState.mode = MODE_LIST;
            }
            mStack.push(doc);
            onCurrentDirectoryChanged();
        } else if (mAction == ACTION_OPEN || mAction == ACTION_GET_CONTENT) {
            // Explicit file picked, return
            onFinished(doc.uri);
        } else if (mAction == ACTION_CREATE) {
            // Replace selected file
            SaveFragment.get(fm).setReplaceTarget(doc);
        } else if (mAction == ACTION_MANAGE) {
            // Open the document
            // TODO: trampoline activity for launching downloaded APKs
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setData(doc.uri);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                Toast.makeText(this, R.string.toast_no_application, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void onDocumentsPicked(List<Document> docs) {
        if (mAction == ACTION_OPEN || mAction == ACTION_GET_CONTENT) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).uri;
            }
            onFinished(uris);
        }
    }

    public void onSaveRequested(Document replaceTarget) {
        onFinished(replaceTarget.uri);
    }

    public void onSaveRequested(String mimeType, String displayName) {
        final ContentValues values = new ContentValues();
        values.put(DocumentColumns.MIME_TYPE, mimeType);
        values.put(DocumentColumns.DISPLAY_NAME, displayName);

        final Document cwd = getCurrentDirectory();
        final Uri childUri = getContentResolver().insert(cwd.uri, values);
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

        final String rawStack = DocumentStack.serialize(mStack);
        if (mAction == ACTION_CREATE) {
            // Remember stack for last create
            values.clear();
            values.put(RecentsProvider.COL_PATH, rawStack);
            resolver.insert(RecentsProvider.buildRecentCreate(), values);

        } else if (mAction == ACTION_OPEN || mAction == ACTION_GET_CONTENT) {
            // Remember opened items
            for (Uri uri : uris) {
                values.clear();
                values.put(RecentsProvider.COL_URI, uri.toString());
                resolver.insert(RecentsProvider.buildRecentOpen(), values);
            }
        }

        // Remember location for next app launch
        final String packageName = getCallingPackage();
        values.clear();
        values.put(RecentsProvider.COL_PATH, rawStack);
        resolver.insert(RecentsProvider.buildResume(packageName), values);

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mDisplayState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        if (mAction == ACTION_GET_CONTENT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_PERSIST_GRANT_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static class DisplayState {
        public int action;
        public int mode = MODE_LIST;
        public String[] acceptMimes;
        public int sortOrder = SORT_ORDER_NAME;
        public boolean allowMultiple = false;
        public boolean showSize = false;
        public boolean localOnly = false;

        public static final int ACTION_OPEN = 1;
        public static final int ACTION_CREATE = 2;
        public static final int ACTION_GET_CONTENT = 3;
        public static final int ACTION_MANAGE = 4;

        public static final int MODE_LIST = 0;
        public static final int MODE_GRID = 1;

        public static final int SORT_ORDER_NAME = 0;
        public static final int SORT_ORDER_DATE = 1;
        public static final int SORT_ORDER_SIZE = 2;
    }

    private void dumpStack() {
        Log.d(TAG, "Current stack:");
        for (Document doc : mStack) {
            Log.d(TAG, "--> " + doc);
        }
    }

    public static DocumentsActivity get(Fragment fragment) {
        return (DocumentsActivity) fragment.getActivity();
    }
}
