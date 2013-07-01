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

import static com.android.documentsui.DirectoryFragment.getCursorString;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.FragmentManager;
import android.app.FragmentManager.BackStackEntry;
import android.app.FragmentManager.OnBackStackChangedListener;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class DocumentsActivity extends Activity {
    private static final String TAG = "Documents";

    // TODO: fragment to show recently opened documents
    // TODO: pull actionbar icon from current backend

    private static final int MODE_OPEN = 1;
    private static final int MODE_CREATE = 2;

    private int mMode;
    private boolean mAllowMultiple;
    private String[] mAcceptMimes;

    private boolean mIgnoreNextNavigation;

    private Uri mCurrentDir;
    private boolean mCurrentSupportsCreate;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            mMode = MODE_OPEN;
            mAllowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            mMode = MODE_CREATE;
            mAllowMultiple = false;
        }

        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            mAcceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            mAcceptMimes = new String[] { intent.getType() };
        }

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        getFragmentManager().addOnBackStackChangedListener(mStackListener);
        BackendFragment.show(getFragmentManager());

        updateActionBar();

        if (mMode == MODE_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(Intent.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        }
    }

    public void updateActionBar() {
        final FragmentManager fm = getFragmentManager();
        final ActionBar actionBar = getActionBar();

        if (fm.getBackStackEntryCount() > 0) {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            actionBar.setDisplayShowHomeEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(null);
            actionBar.setListNavigationCallbacks(mStackAdapter, mNavigationListener);
            actionBar.setSelectedNavigationItem(mStackAdapter.getCount() - 1);
            mIgnoreNextNavigation = true;

        } else {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            actionBar.setDisplayShowHomeEnabled(false);
            actionBar.setDisplayHomeAsUpEnabled(false);

            if (mMode == MODE_OPEN) {
                actionBar.setTitle(R.string.title_open);
            } else if (mMode == MODE_CREATE) {
                actionBar.setTitle(R.string.title_save);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        createDir.setVisible(mMode == MODE_CREATE);
        createDir.setEnabled(mCurrentSupportsCreate);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            getFragmentManager().popBackStack();
            updateActionBar();
        } else if (id == R.id.menu_create_dir) {
            // TODO: show dialog to create directory
        }
        return super.onOptionsItemSelected(item);
    }

    private OnBackStackChangedListener mStackListener = new OnBackStackChangedListener() {
        @Override
        public void onBackStackChanged() {
            updateActionBar();
        }
    };

    private BaseAdapter mStackAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return getFragmentManager().getBackStackEntryCount();
        }

        @Override
        public Object getItem(int position) {
            return getFragmentManager().getBackStackEntryAt(position);
        }

        @Override
        public long getItemId(int position) {
            return getFragmentManager().getBackStackEntryAt(position).getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(android.R.layout.simple_dropdown_item_1line, parent, false);
            }

            final BackStackEntry entry = getFragmentManager().getBackStackEntryAt(position);
            final TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);
            text1.setText(entry.getBreadCrumbTitle());

            return convertView;
        }
    };

    private OnNavigationListener mNavigationListener = new OnNavigationListener() {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long itemId) {
            if (mIgnoreNextNavigation) {
                mIgnoreNextNavigation = false;
                return false;
            }

            getFragmentManager().popBackStack((int) itemId, 0);
            return true;
        }
    };

    public void onDirectoryChanged(Uri uri, int flags) {
        mCurrentDir = uri;
        mCurrentSupportsCreate = (flags & DocumentsContract.FLAG_SUPPORTS_CREATE) != 0;

        if (mMode == MODE_CREATE) {
            final FragmentManager fm = getFragmentManager();
            SaveFragment.get(fm).setSaveEnabled(mCurrentSupportsCreate);
        }

        invalidateOptionsMenu();
    }

    public void onBackendPicked(ProviderInfo info) {
        final Uri uri = DocumentsContract.buildDocumentUri(
                info.authority, DocumentsContract.ROOT_GUID);
        final CharSequence displayName = info.loadLabel(getPackageManager());
        DirectoryFragment.show(getFragmentManager(), uri, displayName.toString(), mAllowMultiple);
    }

    public void onDocumentPicked(Document doc) {
        final FragmentManager fm = getFragmentManager();
        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(doc.mimeType)) {
            // Nested directory picked, recurse using new fragment
            DirectoryFragment.show(fm, doc.uri, doc.displayName, mAllowMultiple);
        } else if (mMode == MODE_OPEN) {
            // Explicit file picked, return
            onFinished(doc.uri);
        } else if (mMode == MODE_CREATE) {
            // Overwrite current filename
            SaveFragment.get(fm).setDisplayName(doc.displayName);
        }
    }

    public void onDocumentsPicked(List<Document> docs) {
        final int size = docs.size();
        final Uri[] uris = new Uri[size];
        for (int i = 0; i < size; i++) {
            uris[i] = docs.get(i).uri;
        }
        onFinished(uris);
    }

    public void onSaveRequested(String mimeType, String displayName) {
        // TODO: create file, confirming before overwriting
        final Uri uri = null;
        onFinished(uri);
    }

    private void onFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ContentResolver resolver = getContentResolver();
            final ClipData clipData = new ClipData(null, mAcceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_PERSIST_GRANT_URI_PERMISSION);
        if (mMode == MODE_CREATE) {
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static class Document {
        public Uri uri;
        public String mimeType;
        public String displayName;

        public static Document fromCursor(String authority, Cursor cursor) {
            final Document doc = new Document();
            final String guid = getCursorString(cursor, DocumentColumns.GUID);
            doc.uri = DocumentsContract.buildDocumentUri(authority, guid);
            doc.mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            doc.displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            return doc;
        }
    }

    public static Drawable resolveDocumentIcon(Context context, String mimeType) {
        // TODO: allow backends to provide custom MIME icons
        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
            return context.getResources().getDrawable(R.drawable.ic_dir);
        } else {
            final PackageManager pm = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType(mimeType);

            final ResolveInfo info = pm.resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                return info.loadIcon(pm);
            } else {
                return null;
            }
        }
    }
}
