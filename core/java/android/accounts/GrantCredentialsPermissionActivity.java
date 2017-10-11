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
import android.content.res.Resources;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.View;
import android.view.LayoutInflater;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import com.android.internal.R;

import java.io.IOException;

/**
 * @hide
 */
public class GrantCredentialsPermissionActivity extends Activity implements View.OnClickListener {
    public static final String EXTRAS_ACCOUNT = "account";
    public static final String EXTRAS_AUTH_TOKEN_TYPE = "authTokenType";
    public static final String EXTRAS_RESPONSE = "response";
    public static final String EXTRAS_REQUESTING_UID = "uid";

    private Account mAccount;
    private String mAuthTokenType;
    private int mUid;
    private Bundle mResultBundle = null;
    protected LayoutInflater mInflater;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.grant_credentials_permission);
        setTitle(R.string.grant_permissions_header_text);

        mInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        final Bundle extras = getIntent().getExtras();
        if (extras == null) {
            // we were somehow started with bad parameters. abort the activity.
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        // Grant 'account'/'type' to mUID
        mAccount = extras.getParcelable(EXTRAS_ACCOUNT);
        mAuthTokenType = extras.getString(EXTRAS_AUTH_TOKEN_TYPE);
        mUid = extras.getInt(EXTRAS_REQUESTING_UID);
        final PackageManager pm = getPackageManager();
        final String[] packages = pm.getPackagesForUid(mUid);

        if (mAccount == null || mAuthTokenType == null || packages == null) {
            // we were somehow started with bad parameters. abort the activity.
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        String accountTypeLabel;
        try {
            accountTypeLabel = getAccountLabel(mAccount);
        } catch (IllegalArgumentException e) {
            // label or resource was missing. abort the activity.
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        final TextView authTokenTypeView = findViewById(R.id.authtoken_type);
        authTokenTypeView.setVisibility(View.GONE);

        final AccountManagerCallback<String> callback = new AccountManagerCallback<String>() {
            public void run(AccountManagerFuture<String> future) {
                try {
                    final String authTokenLabel = future.getResult();
                    if (!TextUtils.isEmpty(authTokenLabel)) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (!isFinishing()) {
                                    authTokenTypeView.setText(authTokenLabel);
                                    authTokenTypeView.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    }
                } catch (OperationCanceledException e) {
                } catch (IOException e) {
                } catch (AuthenticatorException e) {
                }
            }
        };

        if (!AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE.equals(mAuthTokenType)) {
            AccountManager.get(this).getAuthTokenLabel(mAccount.type,
                    mAuthTokenType, callback, null);
        }

        findViewById(R.id.allow_button).setOnClickListener(this);
        findViewById(R.id.deny_button).setOnClickListener(this);

        LinearLayout packagesListView = findViewById(R.id.packages_list);

        for (String pkg : packages) {
            String packageLabel;
            try {
                packageLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                packageLabel = pkg;
            }
            packagesListView.addView(newPackageView(packageLabel));
        }

        ((TextView) findViewById(R.id.account_name)).setText(mAccount.name);
        ((TextView) findViewById(R.id.account_type)).setText(accountTypeLabel);
    }

    private String getAccountLabel(Account account) {
        final AuthenticatorDescription[] authenticatorTypes =
                AccountManager.get(this).getAuthenticatorTypes();
        for (int i = 0, N = authenticatorTypes.length; i < N; i++) {
            final AuthenticatorDescription desc = authenticatorTypes[i];
            if (desc.type.equals(account.type)) {
                try {
                    return createPackageContext(desc.packageName, 0).getString(desc.labelId);
                } catch (PackageManager.NameNotFoundException e) {
                    return account.type;
                } catch (Resources.NotFoundException e) {
                    return account.type;
                }
            }
        }
        return account.type;
    }

    private View newPackageView(String packageLabel) {
        View view = mInflater.inflate(R.layout.permissions_package_list_item, null);
        ((TextView) view.findViewById(R.id.package_label)).setText(packageLabel);
        return view;
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.allow_button:
                AccountManager.get(this).updateAppPermission(mAccount, mAuthTokenType, mUid, true);
                Intent result = new Intent();
                result.putExtra("retry", true);
                setResult(RESULT_OK, result);
                setAccountAuthenticatorResult(result.getExtras());
                break;

            case R.id.deny_button:
                AccountManager.get(this).updateAppPermission(mAccount, mAuthTokenType, mUid, false);
                setResult(RESULT_CANCELED);
                break;
        }
        finish();
    }

    public final void setAccountAuthenticatorResult(Bundle result) {
        mResultBundle = result;
    }

    /**
     * Sends the result or a {@link AccountManager#ERROR_CODE_CANCELED} error if a
     * result isn't present.
     */
    public void finish() {
        Intent intent = getIntent();
        AccountAuthenticatorResponse response = intent.getParcelableExtra(EXTRAS_RESPONSE);
        if (response != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                response.onResult(mResultBundle);
            } else {
                response.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            }
        }
        super.finish();
    }
}
