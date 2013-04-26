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

import android.app.Activity;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.ListFragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class DocumentsActivity extends Activity {
    private static final String TAG = "Documents";

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        SourceFragment.show(getFragmentManager());
        setResult(Activity.RESULT_CANCELED);
    }

    public void onDocumentPicked(Uri uri) {
        Log.d(TAG, "onDocumentPicked() " + uri);

        final Intent intent = new Intent();
        intent.setData(uri);

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_PERSIST_GRANT_URI_PERMISSION);
        if (Intent.ACTION_CREATE_DOCUMENT.equals(getIntent().getAction())) {
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    public static class SourceFragment extends ListFragment {
        private ArrayList<ProviderInfo> mProviders = Lists.newArrayList();
        private ArrayAdapter<ProviderInfo> mAdapter;

        public static void show(FragmentManager fm) {
            final SourceFragment fragment = new SourceFragment();

            final FragmentTransaction ft = fm.beginTransaction();
            ft.replace(android.R.id.content, fragment);
            ft.setBreadCrumbTitle("TOP");
            ft.commitAllowingStateLoss();
        }

        @Override
        public View onCreateView(
                LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            final Context context = inflater.getContext();

            // Gather known storage providers
            mProviders.clear();
            final List<ProviderInfo> providers = context.getPackageManager()
                    .queryContentProviders(null, -1, PackageManager.GET_META_DATA);
            for (ProviderInfo info : providers) {
                if (info.metaData != null
                        && info.metaData.containsKey(
                                DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                    mProviders.add(info);
                }
            }

            mAdapter = new ArrayAdapter<ProviderInfo>(
                    context, android.R.layout.simple_list_item_1, mProviders);
            setListAdapter(mAdapter);

            return super.onCreateView(inflater, container, savedInstanceState);
        }

        @Override
        public void onListItemClick(ListView l, View v, int position, long id) {
            final ProviderInfo info = mAdapter.getItem(position);
            final Uri uri = DocumentsContract.buildContentsUri(DocumentsContract.buildDocumentUri(
                    info.authority, DocumentsContract.ROOT_GUID));
            final String displayName = info.name;
            DirectoryFragment.show(getFragmentManager(), uri, displayName);
        }
    }
}
