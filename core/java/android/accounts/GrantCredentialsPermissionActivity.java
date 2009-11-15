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
import android.os.Bundle;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import com.android.internal.R;

/**
 * @hide
 */
public class GrantCredentialsPermissionActivity extends Activity implements View.OnClickListener {
    public static final String EXTRAS_ACCOUNT = "account";
    public static final String EXTRAS_AUTH_TOKEN_LABEL = "authTokenLabel";
    public static final String EXTRAS_AUTH_TOKEN_TYPE = "authTokenType";
    public static final String EXTRAS_RESPONSE = "response";
    public static final String EXTRAS_ACCOUNT_TYPE_LABEL = "accountTypeLabel";
    public static final String EXTRAS_PACKAGES = "application";
    public static final String EXTRAS_REQUESTING_UID = "uid";
    private Account mAccount;
    private String mAuthTokenType;
    private int mUid;
    private Bundle mResultBundle = null;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setContentView(R.layout.grant_credentials_permission);
        mAccount = getIntent().getExtras().getParcelable(EXTRAS_ACCOUNT);
        mAuthTokenType = getIntent().getExtras().getString(EXTRAS_AUTH_TOKEN_TYPE);
        mUid = getIntent().getExtras().getInt(EXTRAS_REQUESTING_UID);
        final String accountTypeLabel =
                getIntent().getExtras().getString(EXTRAS_ACCOUNT_TYPE_LABEL);
        final String[] packages = getIntent().getExtras().getStringArray(EXTRAS_PACKAGES);

        findViewById(R.id.allow).setOnClickListener(this);
        findViewById(R.id.deny).setOnClickListener(this);

        TextView messageView = (TextView) getWindow().findViewById(R.id.message);
        String authTokenLabel = getIntent().getExtras().getString(EXTRAS_AUTH_TOKEN_LABEL);
        if (TextUtils.isEmpty(authTokenLabel)) {
            CharSequence grantCredentialsPermissionFormat = getResources().getText(
                    R.string.grant_credentials_permission_message_desc);
            messageView.setText(String.format(grantCredentialsPermissionFormat.toString(),
                    mAccount.name, accountTypeLabel));
        } else {
            CharSequence grantCredentialsPermissionFormat = getResources().getText(
                    R.string.grant_credentials_permission_message_with_authtokenlabel_desc);
            messageView.setText(String.format(grantCredentialsPermissionFormat.toString(),
                    authTokenLabel, mAccount.name, accountTypeLabel));
        }

        String[] packageLabels = new String[packages.length];
        final PackageManager pm = getPackageManager();
        for (int i = 0; i < packages.length; i++) {
            try {
                packageLabels[i] =
                        pm.getApplicationLabel(pm.getApplicationInfo(packages[i], 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                packageLabels[i] = packages[i];
            }
        }
        ((ListView) findViewById(R.id.packages_list)).setAdapter(
                new PackagesArrayAdapter(this, packageLabels));
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.allow:
                AccountManagerService.getSingleton().grantAppPermission(mAccount, mAuthTokenType,
                        mUid);
                Intent result = new Intent();
                result.putExtra("retry", true);
                setResult(RESULT_OK, result);
                setAccountAuthenticatorResult(result.getExtras());
                break;

            case R.id.deny:
                AccountManagerService.getSingleton().revokeAppPermission(mAccount, mAuthTokenType,
                        mUid);
                setResult(RESULT_CANCELED);
                break;
        }
        finish();
    }

    public final void setAccountAuthenticatorResult(Bundle result) {
        mResultBundle = result;
    }

    /**
     * Sends the result or a Constants.ERROR_CODE_CANCELED error if a result isn't present.
     */
    public void finish() {
        Intent intent = getIntent();
        AccountAuthenticatorResponse accountAuthenticatorResponse =
                intent.getParcelableExtra(EXTRAS_RESPONSE);
        if (accountAuthenticatorResponse != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                accountAuthenticatorResponse.onResult(mResultBundle);
            } else {
                accountAuthenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            }
        }
        super.finish();
    }

    private static class PackagesArrayAdapter extends ArrayAdapter<String> {
        protected LayoutInflater mInflater;
        private static final int mResource = R.layout.simple_list_item_1;

        public PackagesArrayAdapter(Context context, String[] items) {
            super(context, mResource, items);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        static class ViewHolder {
            TextView label;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // A ViewHolder keeps references to children views to avoid unneccessary calls
            // to findViewById() on each row.
            ViewHolder holder;

            // When convertView is not null, we can reuse it directly, there is no need
            // to reinflate it. We only inflate a new View when the convertView supplied
            // by ListView is null.
            if (convertView == null) {
                convertView = mInflater.inflate(mResource, null);

                // Creates a ViewHolder and store references to the two children views
                // we want to bind data to.
                holder = new ViewHolder();
                holder.label = (TextView) convertView.findViewById(R.id.text1);

                convertView.setTag(holder);
            } else {
                // Get the ViewHolder back to get fast access to the TextView
                // and the ImageView.
                holder = (ViewHolder) convertView.getTag();
            }

            holder.label.setText(getItem(position));

            return convertView;
        }
    }
}
