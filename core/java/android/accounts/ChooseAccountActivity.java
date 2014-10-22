/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.android.internal.R;

import java.util.HashMap;

/**
 * @hide
 */
public class ChooseAccountActivity extends Activity {

    private static final String TAG = "AccountManager";

    private Parcelable[] mAccounts = null;
    private AccountManagerResponse mAccountManagerResponse = null;
    private Bundle mResult;

    private HashMap<String, AuthenticatorDescription> mTypeToAuthDescription
            = new HashMap<String, AuthenticatorDescription>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAccounts = getIntent().getParcelableArrayExtra(AccountManager.KEY_ACCOUNTS);
        mAccountManagerResponse =
                getIntent().getParcelableExtra(AccountManager.KEY_ACCOUNT_MANAGER_RESPONSE);

        // KEY_ACCOUNTS is a required parameter
        if (mAccounts == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        getAuthDescriptions();

        AccountInfo[] mAccountInfos = new AccountInfo[mAccounts.length];
        for (int i = 0; i < mAccounts.length; i++) {
            mAccountInfos[i] = new AccountInfo(((Account) mAccounts[i]).name,
                    getDrawableForType(((Account) mAccounts[i]).type));
        }

        setContentView(R.layout.choose_account);

        // Setup the list
        ListView list = (ListView) findViewById(android.R.id.list);
        // Use an existing ListAdapter that will map an array of strings to TextViews
        list.setAdapter(new AccountArrayAdapter(this,
                android.R.layout.simple_list_item_1, mAccountInfos));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setTextFilterEnabled(true);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                onListItemClick((ListView)parent, v, position, id);
            }
        });
    }

    private void getAuthDescriptions() {
        for(AuthenticatorDescription desc : AccountManager.get(this).getAuthenticatorTypes()) {
            mTypeToAuthDescription.put(desc.type, desc);
        }
    }

    private Drawable getDrawableForType(String accountType) {
        Drawable icon = null;
        if(mTypeToAuthDescription.containsKey(accountType)) {
            try {
                AuthenticatorDescription desc = mTypeToAuthDescription.get(accountType);
                Context authContext = createPackageContext(desc.packageName, 0);
                icon = authContext.getDrawable(desc.iconId);
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
        Account account = (Account) mAccounts[position];
        Log.d(TAG, "selected account " + account);
        Bundle bundle = new Bundle();
        bundle.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        mResult = bundle;
        finish();
    }

    public void finish() {
        if (mAccountManagerResponse != null) {
            if (mResult != null) {
                mAccountManagerResponse.onResult(mResult);
            } else {
                mAccountManagerResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            }
        }
        super.finish();
    }

    private static class AccountInfo {
        final String name;
        final Drawable drawable;

        AccountInfo(String name, Drawable drawable) {
            this.name = name;
            this.drawable = drawable;
        }
    }

    private static class ViewHolder {
        ImageView icon;
        TextView text;
    }

    private static class AccountArrayAdapter extends ArrayAdapter<AccountInfo> {
        private LayoutInflater mLayoutInflater;
        private AccountInfo[] mInfos;

        public AccountArrayAdapter(Context context, int textViewResourceId, AccountInfo[] infos) {
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

            holder.text.setText(mInfos[position].name);
            holder.icon.setImageDrawable(mInfos[position].drawable);

            return convertView;
        }
    }
}
