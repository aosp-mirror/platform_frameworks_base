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
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public class ChooseTypeAndAccountActivity extends Activity
        implements AccountManagerCallback<Bundle> {
    private static final String TAG = "AccountChooser";

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
    public static final String EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY = "allowableAccountTypes";

    /**
     * This is passed as the addAccountOptions parameter in AccountManager.addAccount()
     * if it is called.
     */
    public static final String EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE = "addAccountOptions";

    /**
     * This is passed as the requiredFeatures parameter in AccountManager.addAccount()
     * if it is called.
     */
    public static final String EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES_STRING_ARRAY =
            "addAccountRequiredFeatures";

    /**
     * This is passed as the authTokenType string in AccountManager.addAccount()
     * if it is called.
     */
    public static final String EXTRA_ADD_ACCOUNT_AUTH_TOKEN_TYPE_STRING = "authTokenType";

    /**
     * If set then the specified account is already "selected".
     */
    public static final String EXTRA_SELECTED_ACCOUNT = "selectedAccount";

    /**
     * If true then display the account selection list even if there is just
     * one account to choose from. boolean.
     */
    public static final String EXTRA_ALWAYS_PROMPT_FOR_ACCOUNT =
            "alwaysPromptForAccount";

    /**
     * If set then this string willb e used as the description rather than
     * the default.
     */
    public static final String EXTRA_DESCRIPTION_TEXT_OVERRIDE =
            "descriptionTextOverride";

    public static final int REQUEST_NULL = 0;
    public static final int REQUEST_CHOOSE_TYPE = 1;
    public static final int REQUEST_ADD_ACCOUNT = 2;

    private static final String KEY_INSTANCE_STATE_PENDING_REQUEST = "pendingRequest";
    private static final String KEY_INSTANCE_STATE_EXISTING_ACCOUNTS = "existingAccounts";
    private static final String KEY_INSTANCE_STATE_SELECTED_ACCOUNT_NAME = "selectedAccountName";
    private static final String KEY_INSTANCE_STATE_SELECTED_ADD_ACCOUNT = "selectedAddAccount";

    private static final int SELECTED_ITEM_NONE = -1;

    private ArrayList<Account> mAccounts;
    private int mPendingRequest = REQUEST_NULL;
    private Parcelable[] mExistingAccounts = null;
    private int mSelectedItemIndex;
    private Button mOkButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "ChooseTypeAndAccountActivity.onCreate(savedInstanceState="
                    + savedInstanceState + ")");
        }

        // save some items we use frequently
        final AccountManager accountManager = AccountManager.get(this);
        final Intent intent = getIntent();

        String selectedAccountName = null;
        boolean selectedAddNewAccount = false;

        if (savedInstanceState != null) {
            mPendingRequest = savedInstanceState.getInt(KEY_INSTANCE_STATE_PENDING_REQUEST);
            mExistingAccounts =
                    savedInstanceState.getParcelableArray(KEY_INSTANCE_STATE_EXISTING_ACCOUNTS);

            // Makes sure that any user selection is preserved across orientation changes.
            selectedAccountName = savedInstanceState.getString(
                    KEY_INSTANCE_STATE_SELECTED_ACCOUNT_NAME);

            selectedAddNewAccount = savedInstanceState.getBoolean(
                    KEY_INSTANCE_STATE_SELECTED_ADD_ACCOUNT, false);
        } else {
            mPendingRequest = REQUEST_NULL;
            mExistingAccounts = null;
            // If the selected account as specified in the intent matches one in the list we will
            // show is as pre-selected.
            Account selectedAccount = (Account) intent.getParcelableExtra(EXTRA_SELECTED_ACCOUNT);
            if (selectedAccount != null) {
                selectedAccountName = selectedAccount.name;
            }
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "selected account name is " + selectedAccountName);
        }

        // build an efficiently queryable map of account types to authenticator descriptions
        final HashMap<String, AuthenticatorDescription> typeToAuthDescription =
                new HashMap<String, AuthenticatorDescription>();
        for(AuthenticatorDescription desc : accountManager.getAuthenticatorTypes()) {
            typeToAuthDescription.put(desc.type, desc);
        }

        // Read the validAccounts, if present, and add them to the setOfAllowableAccounts
        Set<Account> setOfAllowableAccounts = null;
        final ArrayList<Parcelable> validAccounts =
                intent.getParcelableArrayListExtra(EXTRA_ALLOWABLE_ACCOUNTS_ARRAYLIST);
        if (validAccounts != null) {
            setOfAllowableAccounts = new HashSet<Account>(validAccounts.size());
            for (Parcelable parcelable : validAccounts) {
                setOfAllowableAccounts.add((Account)parcelable);
            }
        }

        // An account type is relevant iff it is allowed by the caller and supported by the account
        // manager.
        Set<String> setOfRelevantAccountTypes = null;
        final String[] allowedAccountTypes =
                intent.getStringArrayExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY);
        if (allowedAccountTypes != null) {

            setOfRelevantAccountTypes = new HashSet<String>(allowedAccountTypes.length);
            Set<String> setOfAllowedAccountTypes = new HashSet<String>(allowedAccountTypes.length);
            for (String type : allowedAccountTypes) {
                setOfAllowedAccountTypes.add(type);
            }

            AuthenticatorDescription[] descs = AccountManager.get(this).getAuthenticatorTypes();
            Set<String> supportedAccountTypes = new HashSet<String>(descs.length);
            for (AuthenticatorDescription desc : descs) {
                supportedAccountTypes.add(desc.type);
            }

            for (String acctType : setOfAllowedAccountTypes) {
                if (supportedAccountTypes.contains(acctType)) {
                    setOfRelevantAccountTypes.add(acctType);
                }
            }
        }

        // Create a list of AccountInfo objects for each account that is allowable. Filter out
        // accounts that don't match the allowable types, if provided, or that don't match the
        // allowable accounts, if provided.
        final Account[] accounts = accountManager.getAccounts();
        mAccounts = new ArrayList<Account>(accounts.length);
        mSelectedItemIndex = SELECTED_ITEM_NONE;
        for (Account account : accounts) {
            if (setOfAllowableAccounts != null
                    && !setOfAllowableAccounts.contains(account)) {
                continue;
            }
            if (setOfRelevantAccountTypes != null
                    && !setOfRelevantAccountTypes.contains(account.type)) {
                continue;
            }
            if (account.name.equals(selectedAccountName)) {
                mSelectedItemIndex = mAccounts.size();
            }
            mAccounts.add(account);
        }

        if (mPendingRequest == REQUEST_NULL) {
            // If there are no relevant accounts and only one relevant account type go directly to
            // add account. Otherwise let the user choose.
            if (mAccounts.isEmpty()) {
                if (setOfRelevantAccountTypes.size() == 1) {
                    runAddAccountForAuthenticator(setOfRelevantAccountTypes.iterator().next());
                } else {
                    startChooseAccountTypeActivity();
                }
                return;
            }

            // if there is only one allowable account return it
            if (!intent.getBooleanExtra(EXTRA_ALWAYS_PROMPT_FOR_ACCOUNT, false)
                    && mAccounts.size() == 1) {
                Account account = mAccounts.get(0);
                setResultAndFinish(account.name, account.type);
                return;
            }
        }

        // Cannot set content view until we know that mPendingRequest is not null, otherwise
        // would cause screen flicker.
        setContentView(R.layout.choose_type_and_account);

        // Override the description text if supplied
        final String descriptionOverride =
                intent.getStringExtra(EXTRA_DESCRIPTION_TEXT_OVERRIDE);
        TextView descriptionView = (TextView) findViewById(R.id.description);
        if (!TextUtils.isEmpty(descriptionOverride)) {
            descriptionView.setText(descriptionOverride);
        } else {
            descriptionView.setVisibility(View.GONE);
        }

        // List of options includes all accounts found together with "Add new account" as the
        // last item in the list.
        String[] listItems = new String[mAccounts.size() + 1];
        for (int i = 0; i < mAccounts.size(); i++) {
            listItems[i] = mAccounts.get(i).name;
        }
        listItems[mAccounts.size()] = getResources().getString(
                R.string.add_account_button_label);

        ListView list = (ListView) findViewById(android.R.id.list);
        list.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, listItems));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setItemsCanFocus(false);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                mSelectedItemIndex = position;
                mOkButton.setEnabled(true);
            }
        });

        // If "Add account" option was previously selected by user, preserve it across
        // orientation changes.
        if (selectedAddNewAccount) {
            mSelectedItemIndex = mAccounts.size();
        }
        if (mSelectedItemIndex != SELECTED_ITEM_NONE) {
            list.setItemChecked(mSelectedItemIndex, true);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "List item " + mSelectedItemIndex + " should be selected");
            }
        }

        // Only enable "OK" button if something has been selected.
        mOkButton = (Button) findViewById(android.R.id.button2);
        mOkButton.setEnabled(mSelectedItemIndex != SELECTED_ITEM_NONE);
    }

    @Override
    protected void onDestroy() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "ChooseTypeAndAccountActivity.onDestroy()");
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_INSTANCE_STATE_PENDING_REQUEST, mPendingRequest);
        if (mPendingRequest == REQUEST_ADD_ACCOUNT) {
            outState.putParcelableArray(KEY_INSTANCE_STATE_EXISTING_ACCOUNTS, mExistingAccounts);
        }
        if (mSelectedItemIndex != SELECTED_ITEM_NONE) {
            if (mSelectedItemIndex == mAccounts.size()) {
                outState.putBoolean(KEY_INSTANCE_STATE_SELECTED_ADD_ACCOUNT, true);
            } else {
                outState.putBoolean(KEY_INSTANCE_STATE_SELECTED_ADD_ACCOUNT, false);
                outState.putString(KEY_INSTANCE_STATE_SELECTED_ACCOUNT_NAME,
                        mAccounts.get(mSelectedItemIndex).name);
            }
        }
    }

    public void onCancelButtonClicked(View view) {
        onBackPressed();
    }

    public void onOkButtonClicked(View view) {
        if (mSelectedItemIndex == mAccounts.size()) {
            // Selected "Add New Account" option
            startChooseAccountTypeActivity();
        } else if (mSelectedItemIndex != SELECTED_ITEM_NONE) {
            onAccountSelected(mAccounts.get(mSelectedItemIndex));
        }
    }

    // Called when the choose account type activity (for adding an account) returns.
    // If it was a success read the account and set it in the result. In all cases
    // return the result and finish this activity.
    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent data) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            if (data != null && data.getExtras() != null) data.getExtras().keySet();
            Bundle extras = data != null ? data.getExtras() : null;
            Log.v(TAG, "ChooseTypeAndAccountActivity.onActivityResult(reqCode=" + requestCode
                    + ", resCode=" + resultCode + ", extras=" + extras + ")");
        }

        // we got our result, so clear the fact that we had a pending request
        mPendingRequest = REQUEST_NULL;

        if (resultCode == RESULT_CANCELED) {
            // if canceling out of addAccount and the original state caused us to skip this,
            // finish this activity
            if (mAccounts.isEmpty()) {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
            return;
        }

        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_CHOOSE_TYPE) {
                if (data != null) {
                    String accountType = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
                    if (accountType != null) {
                        runAddAccountForAuthenticator(accountType);
                        return;
                    }
                }
                Log.d(TAG, "ChooseTypeAndAccountActivity.onActivityResult: unable to find account "
                        + "type, pretending the request was canceled");
            } else if (requestCode == REQUEST_ADD_ACCOUNT) {
                String accountName = null;
                String accountType = null;

                if (data != null) {
                    accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    accountType = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
                }

                if (accountName == null || accountType == null) {
                    Account[] currentAccounts = AccountManager.get(this).getAccounts();
                    Set<Account> preExistingAccounts = new HashSet<Account>();
                    for (Parcelable accountParcel : mExistingAccounts) {
                        preExistingAccounts.add((Account) accountParcel);
                    }
                    for (Account account : currentAccounts) {
                        if (!preExistingAccounts.contains(account)) {
                            accountName = account.name;
                            accountType = account.type;
                            break;
                        }
                    }
                }

                if (accountName != null || accountType != null) {
                    setResultAndFinish(accountName, accountType);
                    return;
                }
            }
            Log.d(TAG, "ChooseTypeAndAccountActivity.onActivityResult: unable to find added "
                    + "account, pretending the request was canceled");
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "ChooseTypeAndAccountActivity.onActivityResult: canceled");
        }
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    protected void runAddAccountForAuthenticator(String type) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "runAddAccountForAuthenticator: " + type);
        }
        final Bundle options = getIntent().getBundleExtra(
                ChooseTypeAndAccountActivity.EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE);
        final String[] requiredFeatures = getIntent().getStringArrayExtra(
                ChooseTypeAndAccountActivity.EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES_STRING_ARRAY);
        final String authTokenType = getIntent().getStringExtra(
                ChooseTypeAndAccountActivity.EXTRA_ADD_ACCOUNT_AUTH_TOKEN_TYPE_STRING);
        AccountManager.get(this).addAccount(type, authTokenType, requiredFeatures,
                options, null /* activity */, this /* callback */, null /* Handler */);
    }

    @Override
    public void run(final AccountManagerFuture<Bundle> accountManagerFuture) {
        try {
            final Bundle accountManagerResult = accountManagerFuture.getResult();
            final Intent intent = (Intent)accountManagerResult.getParcelable(
                    AccountManager.KEY_INTENT);
            if (intent != null) {
                mPendingRequest = REQUEST_ADD_ACCOUNT;
                mExistingAccounts = AccountManager.get(this).getAccounts();
                intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityForResult(intent, REQUEST_ADD_ACCOUNT);
                return;
            }
        } catch (OperationCanceledException e) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        } catch (IOException e) {
        } catch (AuthenticatorException e) {
        }
        Bundle bundle = new Bundle();
        bundle.putString(AccountManager.KEY_ERROR_MESSAGE, "error communicating with server");
        setResult(Activity.RESULT_OK, new Intent().putExtras(bundle));
        finish();
    }

    private void onAccountSelected(Account account) {
      Log.d(TAG, "selected account " + account);
      setResultAndFinish(account.name, account.type);
    }

    private void setResultAndFinish(final String accountName, final String accountType) {
        Bundle bundle = new Bundle();
        bundle.putString(AccountManager.KEY_ACCOUNT_NAME, accountName);
        bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, accountType);
        setResult(Activity.RESULT_OK, new Intent().putExtras(bundle));
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "ChooseTypeAndAccountActivity.setResultAndFinish: "
                    + "selected account " + accountName + ", " + accountType);
        }
        finish();
    }

    private void startChooseAccountTypeActivity() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "ChooseAccountTypeActivity.startChooseAccountTypeActivity()");
        }
        final Intent intent = new Intent(this, ChooseAccountTypeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        intent.putExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY,
                getIntent().getStringArrayExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY));
        intent.putExtra(EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE,
                getIntent().getBundleExtra(EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE));
        intent.putExtra(EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES_STRING_ARRAY,
                getIntent().getStringArrayExtra(EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES_STRING_ARRAY));
        intent.putExtra(EXTRA_ADD_ACCOUNT_AUTH_TOKEN_TYPE_STRING,
                getIntent().getStringExtra(EXTRA_ADD_ACCOUNT_AUTH_TOKEN_TYPE_STRING));
        startActivityForResult(intent, REQUEST_CHOOSE_TYPE);
        mPendingRequest = REQUEST_CHOOSE_TYPE;
    }
}
