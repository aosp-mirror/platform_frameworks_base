/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.accounts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.android.internal.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public class ChooseTypeAndAccountActivity extends Activity {
    private static final String TAG = "AccountManager";

    /**
     * A Parcelable ArrayList of Account objects that limits the choosable accounts to those
     * in this list, if this parameter is supplied.
     */
    public static final String EXTRA_ALLOWABLE_ACCOUNTS_ARRAYLIST = "allowableAccounts";

    /**
     * A Parcelable ArrayList of String objects that limits the accounts to choose to those
     * that match the types in this list, if this parameter is supplied. This list is also
     * used to filter the allowable account types if add account is selected.
     */
    public static final String EXTRA_ALLOWABLE_ACCOUNT_TYPES_ARRAYLIST = "allowableAccountTypes";

    /**
     * This is passed as the options bundle in AccountManager.addAccount() if it is called.
     */
    public static final String EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE = "addAccountOptions";

    /**
     * If set then the specified account is already "selected".
     */
    public static final String EXTRA_SELECTED_ACCOUNT = "selectedAccount";

    private ArrayList<AccountInfo> mAccountInfos;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.choose_type_and_account);
        final AccountManager accountManager = AccountManager.get(this);

        // build an efficiently queryable map of account types to authenticator descriptions
        final HashMap<String, AuthenticatorDescription> typeToAuthDescription =
                new HashMap<String, AuthenticatorDescription>();
        for(AuthenticatorDescription desc : accountManager.getAuthenticatorTypes()) {
            typeToAuthDescription.put(desc.type, desc);
        }

        // Read the validAccounts, if present, and add them to the setOfAllowableAccounts
        Set<Account> setOfAllowableAccounts = null;
        final ArrayList<Parcelable> validAccounts =
                getIntent().getParcelableArrayListExtra(EXTRA_ALLOWABLE_ACCOUNTS_ARRAYLIST);
        if (validAccounts != null) {
            setOfAllowableAccounts = new HashSet<Account>(validAccounts.size());
            for (Parcelable parcelable : validAccounts) {
                setOfAllowableAccounts.add((Account)parcelable);
            }
        }

        // Read the validAccountTypes, if present, and add them to the setOfAllowableAccountTypes
        Set<String> setOfAllowableAccountTypes = null;
        final ArrayList<String> validAccountTypes =
                getIntent().getStringArrayListExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_ARRAYLIST);
        if (validAccountTypes != null) {
            setOfAllowableAccountTypes = new HashSet<String>(validAccountTypes.size());
            for (String type : validAccountTypes) {
                setOfAllowableAccountTypes.add(type);
            }
        }

        // Create a list of AccountInfo objects for each account that is allowable. Filter out
        // accounts that don't match the allowable types, if provided, or that don't match the
        // allowable accounts, if provided.
        final Account[] accounts = accountManager.getAccounts();
        mAccountInfos = new ArrayList<AccountInfo>(accounts.length);
        for (Account account : accounts) {
            if (setOfAllowableAccounts != null
                    && !setOfAllowableAccounts.contains(account)) {
                continue;
            }
            if (setOfAllowableAccountTypes != null
                    && !setOfAllowableAccountTypes.contains(account.type)) {
                continue;
            }
            mAccountInfos.add(new AccountInfo(account,
                    getDrawableForType(typeToAuthDescription, account.type)));
        }

        // If there are no allowable accounts go directly to add account
        if (mAccountInfos.isEmpty()) {
            startChooseAccountTypeActivity();
            return;
        }

        // if there is only one allowable account return it
        if (mAccountInfos.size() == 1) {
            Account account = mAccountInfos.get(0).account;
            setResultAndFinish(account.name, account.type);
            return;
        }

        // there is more than one allowable account. initialize the list adapter to allow
        // the user to select an account.
        ListView list = (ListView) findViewById(android.R.id.list);
        list.setAdapter(new AccountArrayAdapter(this,
                android.R.layout.simple_list_item_1, mAccountInfos));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setTextFilterEnabled(false);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                onListItemClick((ListView)parent, v, position, id);
            }
        });

        // set the listener for the addAccount button
        Button addAccountButton = (Button) findViewById(R.id.addAccount);
        addAccountButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(final View v) {
                startChooseAccountTypeActivity();
            }
        });
    }

    // Called when the choose account type activity (for adding an account) returns.
    // If it was a success read the account and set it in the result. In all cases
    // return the result and finish this activity.
    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            String accountType = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
            if (accountName != null && accountType != null) {
                setResultAndFinish(accountName, accountType);
                return;
            }
        }
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private Drawable getDrawableForType(
            final HashMap<String, AuthenticatorDescription> typeToAuthDescription,
            String accountType) {
        Drawable icon = null;
        if (typeToAuthDescription.containsKey(accountType)) {
            try {
                AuthenticatorDescription desc = typeToAuthDescription.get(accountType);
                Context authContext = createPackageContext(desc.packageName, 0);
                icon = authContext.getResources().getDrawable(desc.iconId);
            } catch (PackageManager.NameNotFoundException e) {
                // Nothing we can do much here, just log
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "No icon name for account type " + accountType);
                }
            } catch (Resources.NotFoundException e) {
                // Nothing we can do much here, just log
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "No icon resource for account type " + accountType);
                }
            }
        }
        return icon;
    }

    protected void onListItemClick(ListView l, View v, int position, long id) {
        AccountInfo accountInfo = mAccountInfos.get(position);
        Log.d(TAG, "selected account " + accountInfo.account);
        setResultAndFinish(accountInfo.account.name, accountInfo.account.type);
    }

    private void setResultAndFinish(final String accountName, final String accountType) {
        Bundle bundle = new Bundle();
        bundle.putString(AccountManager.KEY_ACCOUNT_NAME, accountName);
        bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, accountType);
        setResult(Activity.RESULT_OK, new Intent().putExtras(bundle));
        finish();
    }

    private void startChooseAccountTypeActivity() {
        final Intent intent = new Intent(this, ChooseAccountTypeActivity.class);
        intent.putStringArrayListExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_ARRAYLIST,
                getIntent().getStringArrayListExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_ARRAYLIST));
        intent.putExtra(EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE,
                getIntent().getBundleExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_ARRAYLIST));
        startActivityForResult(intent, 0);
    }

    private static class AccountInfo {
        final Account account;
        final Drawable drawable;

        AccountInfo(Account account, Drawable drawable) {
            this.account = account;
            this.drawable = drawable;
        }
    }

    private static class ViewHolder {
        ImageView icon;
        TextView text;
    }

    private static class AccountArrayAdapter extends ArrayAdapter<AccountInfo> {
        private LayoutInflater mLayoutInflater;
        private ArrayList<AccountInfo> mInfos;

        public AccountArrayAdapter(Context context, int textViewResourceId,
                ArrayList<AccountInfo> infos) {
            super(context, textViewResourceId, infos);
            mInfos = infos;
            mLayoutInflater = (LayoutInflater) context.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                convertView = mLayoutInflater.inflate(R.layout.choose_account_row, null);
                holder = new ViewHolder();
                holder.text = (TextView) convertView.findViewById(R.id.account_row_text);
                holder.icon = (ImageView) convertView.findViewById(R.id.account_row_icon);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            holder.text.setText(mInfos.get(position).account.name);
            holder.icon.setImageDrawable(mInfos.get(position).drawable);

            return convertView;
        }
    }
}
