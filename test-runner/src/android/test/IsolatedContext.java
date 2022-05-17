/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test;

import android.accounts.AccountManager;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.test.mock.MockAccountManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;


/**
 * A mock context which prevents its users from talking to the rest of the device while
 * stubbing enough methods to satify code that tries to talk to other packages.
 *
 * @deprecated New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class IsolatedContext extends ContextWrapper {

    private ContentResolver mResolver;
    private final AccountManager mMockAccountManager;

    private List<Intent> mBroadcastIntents = new ArrayList<>();

    public IsolatedContext(
            ContentResolver resolver, Context targetContext) {
        super(targetContext);
        mResolver = resolver;
        mMockAccountManager = MockAccountManager.newMockAccountManager(IsolatedContext.this);
    }

    /** Returns the list of intents that were broadcast since the last call to this method. */
    public List<Intent> getAndClearBroadcastIntents() {
        List<Intent> intents = mBroadcastIntents;
        mBroadcastIntents = new ArrayList<>();
        return intents;
    }

    @Override
    public AttributionSource getAttributionSource() {
        AttributionSource attributionSource = super.getAttributionSource();
        if (attributionSource == null) {
            return new AttributionSource.Builder(Process.myUid()).build();
        }
        return attributionSource;
    }

    @Override
    public ContentResolver getContentResolver() {
        // We need to return the real resolver so that MailEngine.makeRight can get to the
        // subscribed feeds provider. TODO: mock out subscribed feeds too.
        return mResolver;
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        return false;
    }

    @Override
    public boolean bindService(Intent service, int flags, Executor executor,
            ServiceConnection conn) {
        return false;
    }

    @Override
    public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection conn) {
        return false;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return null;
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        // Ignore
    }

    @Override
    public void sendBroadcast(Intent intent) {
        mBroadcastIntents.add(intent);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        mBroadcastIntents.add(intent);
    }

    @Override
    public int checkUriPermission(
            Uri uri, String readPermission, String writePermission, int pid,
            int uid, int modeFlags) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.ACCOUNT_SERVICE.equals(name)) {
            return mMockAccountManager;
        }
        // No other services exist in this context.
        return null;
    }

    @Override
    public File getFilesDir() {
        return new File("/dev/null");
    }
}
