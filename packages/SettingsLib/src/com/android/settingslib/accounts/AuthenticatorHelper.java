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
 * limitations under the License
 */

package com.android.settingslib.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncAdapterType;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.util.Log;

import com.android.settingslib.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for monitoring accounts on the device for a given user.
 *
 * Classes using this helper should implement {@link OnAccountsUpdateListener}.
 * {@link OnAccountsUpdateListener#onAccountsUpdate(UserHandle)} will then be
 * called once accounts get updated. For setting up listening for account
 * updates, {@link #listenToAccountUpdates()} and
 * {@link #stopListeningToAccountUpdates()} should be used.
 */
final public class AuthenticatorHelper extends BroadcastReceiver {
    private static final String TAG = "AuthenticatorHelper";

    private final Map<String, AuthenticatorDescription> mTypeToAuthDescription = new HashMap<>();
    private final ArrayList<String> mEnabledAccountTypes = new ArrayList<>();
    private final Map<String, Drawable> mAccTypeIconCache = new HashMap<>();
    private final HashMap<String, ArrayList<String>> mAccountTypeToAuthorities = new HashMap<>();

    private final UserHandle mUserHandle;
    private final Context mContext;
    private final OnAccountsUpdateListener mListener;
    private boolean mListeningToAccountUpdates;

    public interface OnAccountsUpdateListener {
        void onAccountsUpdate(UserHandle userHandle);
    }

    public AuthenticatorHelper(Context context, UserHandle userHandle,
            OnAccountsUpdateListener listener) {
        mContext = context;
        mUserHandle = userHandle;
        mListener = listener;
        // This guarantees that the helper is ready to use once constructed: the account types and
        // authorities are initialized
        onAccountsUpdated(null);
    }

    public String[] getEnabledAccountTypes() {
        return mEnabledAccountTypes.toArray(new String[mEnabledAccountTypes.size()]);
    }

    public void preloadDrawableForType(final Context context, final String accountType) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                getDrawableForType(context, accountType);
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
    }

    /**
     * Gets an icon associated with a particular account type. If none found, return null.
     * @param accountType the type of account
     * @return a drawable for the icon or a default icon returned by
     * {@link PackageManager#getDefaultActivityIcon} if one cannot be found.
     */
    public Drawable getDrawableForType(Context context, final String accountType) {
        Drawable icon = null;
        synchronized (mAccTypeIconCache) {
            if (mAccTypeIconCache.containsKey(accountType)) {
                return mAccTypeIconCache.get(accountType);
            }
        }
        if (mTypeToAuthDescription.containsKey(accountType)) {
            try {
                AuthenticatorDescription desc = mTypeToAuthDescription.get(accountType);
                Context authContext = context.createPackageContextAsUser(desc.packageName, 0,
                        mUserHandle);
                icon = authContext.getDrawable(desc.iconId);
                synchronized (mAccTypeIconCache) {
                    mAccTypeIconCache.put(accountType, icon);
                }
            } catch (PackageManager.NameNotFoundException|Resources.NotFoundException e) {
                // Ignore
            }
        }
        if (icon == null) {
            icon = context.getPackageManager().getDefaultActivityIcon();
        }
        return Utils.getBadgedIcon(mContext, icon, mUserHandle);
    }

    /**
     * Gets the label associated with a particular account type. If none found, return null.
     * @param accountType the type of account
     * @return a CharSequence for the label or null if one cannot be found.
     */
    public CharSequence getLabelForType(Context context, final String accountType) {
        CharSequence label = null;
        if (mTypeToAuthDescription.containsKey(accountType)) {
            try {
                AuthenticatorDescription desc = mTypeToAuthDescription.get(accountType);
                Context authContext = context.createPackageContextAsUser(desc.packageName, 0,
                        mUserHandle);
                label = authContext.getResources().getText(desc.labelId);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "No label name for account type " + accountType);
            } catch (Resources.NotFoundException e) {
                Log.w(TAG, "No label icon for account type " + accountType);
            }
        }
        return label;
    }

    /**
     * Gets the package associated with a particular account type. If none found, return null.
     * @param accountType the type of account
     * @return the package name or null if one cannot be found.
     */
    public String getPackageForType(final String accountType) {
        if (mTypeToAuthDescription.containsKey(accountType)) {
            AuthenticatorDescription desc = mTypeToAuthDescription.get(accountType);
            return desc.packageName;
        }
        return null;
    }

    /**
     * Gets the resource id of the label associated with a particular account type. If none found,
     * return -1.
     * @param accountType the type of account
     * @return a resource id for the label or -1 if none found;
     */
    public int getLabelIdForType(final String accountType) {
        if (mTypeToAuthDescription.containsKey(accountType)) {
            AuthenticatorDescription desc = mTypeToAuthDescription.get(accountType);
            return desc.labelId;
        }
        return -1;
    }

    /**
     * Updates provider icons. Subclasses should call this in onCreate()
     * and update any UI that depends on AuthenticatorDescriptions in onAuthDescriptionsUpdated().
     */
    public void updateAuthDescriptions(Context context) {
        AuthenticatorDescription[] authDescs = AccountManager.get(context)
                .getAuthenticatorTypesAsUser(mUserHandle.getIdentifier());
        for (int i = 0; i < authDescs.length; i++) {
            mTypeToAuthDescription.put(authDescs[i].type, authDescs[i]);
        }
    }

    public boolean containsAccountType(String accountType) {
        return mTypeToAuthDescription.containsKey(accountType);
    }

    public AuthenticatorDescription getAccountTypeDescription(String accountType) {
        return mTypeToAuthDescription.get(accountType);
    }

    public boolean hasAccountPreferences(final String accountType) {
        if (containsAccountType(accountType)) {
            AuthenticatorDescription desc = getAccountTypeDescription(accountType);
            if (desc != null && desc.accountPreferencesId != 0) {
                return true;
            }
        }
        return false;
    }

    void onAccountsUpdated(Account[] accounts) {
        updateAuthDescriptions(mContext);
        if (accounts == null) {
            accounts = AccountManager.get(mContext).getAccountsAsUser(mUserHandle.getIdentifier());
        }
        mEnabledAccountTypes.clear();
        mAccTypeIconCache.clear();
        for (int i = 0; i < accounts.length; i++) {
            final Account account = accounts[i];
            if (!mEnabledAccountTypes.contains(account.type)) {
                mEnabledAccountTypes.add(account.type);
            }
        }
        buildAccountTypeToAuthoritiesMap();
        if (mListeningToAccountUpdates) {
            mListener.onAccountsUpdate(mUserHandle);
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        // TODO: watch for package upgrades to invalidate cache; see http://b/7206643
        final Account[] accounts = AccountManager.get(mContext)
                .getAccountsAsUser(mUserHandle.getIdentifier());
        onAccountsUpdated(accounts);
    }

    public void listenToAccountUpdates() {
        if (!mListeningToAccountUpdates) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
            // At disk full, certain actions are blocked (such as writing the accounts to storage).
            // It is useful to also listen for recovery from disk full to avoid bugs.
            intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
            mContext.registerReceiverAsUser(this, mUserHandle, intentFilter, null, null);
            mListeningToAccountUpdates = true;
        }
    }

    public void stopListeningToAccountUpdates() {
        if (mListeningToAccountUpdates) {
            mContext.unregisterReceiver(this);
            mListeningToAccountUpdates = false;
        }
    }

    public ArrayList<String> getAuthoritiesForAccountType(String type) {
        return mAccountTypeToAuthorities.get(type);
    }

    private void buildAccountTypeToAuthoritiesMap() {
        mAccountTypeToAuthorities.clear();
        SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypesAsUser(
                mUserHandle.getIdentifier());
        for (int i = 0, n = syncAdapters.length; i < n; i++) {
            final SyncAdapterType sa = syncAdapters[i];
            ArrayList<String> authorities = mAccountTypeToAuthorities.get(sa.accountType);
            if (authorities == null) {
                authorities = new ArrayList<String>();
                mAccountTypeToAuthorities.put(sa.accountType, authorities);
            }
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Added authority " + sa.authority + " to accountType "
                        + sa.accountType);
            }
            authorities.add(sa.authority);
        }
    }
}
