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

package android.provider;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Path;

import org.mockito.Mockito;

import java.io.FileNotFoundException;

/**
 * Provides a test double of {@link DocumentsProvider}.
 */
public class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "android.provider.TestDocumentsProvider";

    public Path nextPath;

    public boolean nextIsChildDocument;

    public String lastDocumentId;
    public String lastParentDocumentId;

    @Override
    public void attachInfoForTesting(Context context, ProviderInfo info) {
        context = new TestContext(context);
        super.attachInfoForTesting(context, info);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        return null;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        return null;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        return null;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        return null;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return nextIsChildDocument;
    }

    @Override
    public Path findDocumentPath(@Nullable String parentDocumentId, String documentId) {
        lastDocumentId = documentId;
        lastParentDocumentId = parentDocumentId;

        return nextPath;
    }

    @Override
    protected int enforceReadPermissionInner(Uri uri, String callingPkg,
            @Nullable String callingFeatureId, IBinder callerToken) {
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    protected int enforceWritePermissionInner(Uri uri, String callingPkg,
            @Nullable String callingFeatureId, IBinder callerToken) {
        return AppOpsManager.MODE_ALLOWED;
    }

    private static class TestContext extends ContextWrapper {

        private TestContext(Context context) {
            super(context);
        }

        @Override
        public void enforceCallingPermission(String permission, String message) {
            // Always granted
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.APP_OPS_SERVICE.equals(name)) {
                return Mockito.mock(AppOpsManager.class);
            }

            return super.getSystemService(name);
        }
    }
}
