/*
**
** Copyright 2012, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.commands.requestsync;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.SyncRequest;
import android.os.Bundle;

import java.net.URISyntaxException;

public class RequestSync {
    // agr parsing fields
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private int mExemptionFlag = ContentResolver.SYNC_EXEMPTION_NONE;

    enum Operation {
        REQUEST_SYNC {
            @Override
            void invoke(RequestSync caller) {
                final int flag = caller.mExemptionFlag;
                caller.mExtras.putInt(ContentResolver.SYNC_VIRTUAL_EXTRAS_EXEMPTION_FLAG, flag);
                if (flag == ContentResolver.SYNC_EXEMPTION_NONE) {
                    System.out.println(
                            "Making a sync request as a background app.\n"
                            + "Note: request may be throttled by App Standby.\n"
                            + "To override this behavior and run a sync immediately,"
                            + " pass a -f or -F option (use -h for help).\n");
                }
                final SyncRequest request =
                        new SyncRequest.Builder()
                                .setSyncAdapter(caller.mAccount, caller.mAuthority)
                                .setExtras(caller.mExtras)
                                .syncOnce()
                                .build();
                ContentResolver.requestSync(request);
            }
        },
        ADD_PERIODIC_SYNC {
            @Override
            void invoke(RequestSync caller) {
                ContentResolver.addPeriodicSync(caller.mAccount, caller.mAuthority, caller.mExtras,
                        caller.mPeriodicIntervalSeconds);
            }
        },
        REMOVE_PERIODIC_SYNC {
            @Override
            void invoke(RequestSync caller) {
                ContentResolver.removePeriodicSync(
                        caller.mAccount, caller.mAuthority, caller.mExtras);
            }
        };

        abstract void invoke(RequestSync caller);
    }

    private Operation mOperation;

    // account & authority
    private String mAccountName;
    private String mAccountType;
    private String mAuthority;

    private Account mAccount;

    private int mPeriodicIntervalSeconds;

    // extras
    private Bundle mExtras = new Bundle();

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        try {
            (new RequestSync()).run(args);
        } catch (IllegalArgumentException e) {
            showUsage();
            System.err.println("Error: " + e);
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        mArgs = args;
        mNextArg = 0;

        final boolean ok = parseArgs();
        if (ok) {
            final Account account = mAccountName != null && mAccountType != null
                    ? new Account(mAccountName, mAccountType) : null;

            System.out.printf("Requesting sync for: \n");
            if (account != null) {
                System.out.printf("  Account: %s (%s)\n", account.name, account.type);
            } else {
                System.out.printf("  Account: all\n");
            }

            System.out.printf("  Authority: %s\n", mAuthority != null ? mAuthority : "All");

            if (mExtras.size() > 0) {
                System.out.printf("  Extras:\n");
                for (String key : mExtras.keySet()) {
                    System.out.printf("    %s: %s\n", key, mExtras.get(key));
                }
            }

            mAccount = account;

            mOperation.invoke(this);
        }
    }

    private boolean parseArgs() throws URISyntaxException {
        mOperation = Operation.REQUEST_SYNC;
        if (mArgs.length > 0) {
            switch (mArgs[0]) {
                case "add-periodic":
                    mNextArg++;
                    mOperation = Operation.ADD_PERIODIC_SYNC;
                    mPeriodicIntervalSeconds = Integer.parseInt(nextArgRequired());
                    break;
                case "remove-periodic":
                    mNextArg++;
                    mOperation = Operation.REMOVE_PERIODIC_SYNC;
                    break;
            }
        }

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-h") || opt.equals("--help")) {
                showUsage();
                return false;
            } else if (opt.equals("-n") || opt.equals("--account-name")) {
                mAccountName = nextArgRequired();
            } else if (opt.equals("-t") || opt.equals("--account-type")) {
                mAccountType = nextArgRequired();
            } else if (opt.equals("-a") || opt.equals("--authority")) {
                mAuthority = nextArgRequired();
            } else if (opt.equals("--is") || opt.equals("--ignore-settings")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
            } else if (opt.equals("--ib") || opt.equals("--ignore-backoff")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
            } else if (opt.equals("--dd") || opt.equals("--discard-deletions")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS, true);
            } else if (opt.equals("--nr") || opt.equals("--no-retry")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
            } else if (opt.equals("--ex") || opt.equals("--expedited")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            } else if (opt.equals("-i") || opt.equals("--initialize")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
            } else if (opt.equals("-m") || opt.equals("--manual")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            } else if (opt.equals("--od") || opt.equals("--override-deletions")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS, true);
            } else if (opt.equals("-u") || opt.equals("--upload-only")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
            } else if (opt.equals("--rc") || opt.equals("--require-charging")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_REQUIRE_CHARGING, true);
            } else if (opt.equals("--ej") || opt.equals("--schedule-as-ej")) {
                mExtras.putBoolean(ContentResolver.SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB, true);
            } else if (opt.equals("-e") || opt.equals("--es") || opt.equals("--extra-string")) {
                final String key = nextArgRequired();
                final String value = nextArgRequired();
                mExtras.putString(key, value);
            } else if (opt.equals("--esn") || opt.equals("--extra-string-null")) {
                final String key = nextArgRequired();
                mExtras.putString(key, null);
            } else if (opt.equals("--ei") || opt.equals("--extra-int")) {
                final String key = nextArgRequired();
                final String value = nextArgRequired();
                mExtras.putInt(key, Integer.valueOf(value));
            } else if (opt.equals("--el") || opt.equals("--extra-long")) {
                final String key = nextArgRequired();
                final String value = nextArgRequired();
                mExtras.putLong(key, Long.parseLong(value));
            } else if (opt.equals("--ef") || opt.equals("--extra-float")) {
                final String key = nextArgRequired();
                final String value = nextArgRequired();
                mExtras.putFloat(key, Long.parseLong(value));
            } else if (opt.equals("--ed") || opt.equals("--extra-double")) {
                final String key = nextArgRequired();
                final String value = nextArgRequired();
                mExtras.putFloat(key, Long.parseLong(value));
            } else if (opt.equals("--ez") || opt.equals("--extra-bool")) {
                final String key = nextArgRequired();
                final String value = nextArgRequired();
                mExtras.putBoolean(key, Boolean.valueOf(value));

            } else if (opt.equals("-f") || opt.equals("--foreground")) {
                mExemptionFlag = ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET;

            } else if (opt.equals("-F") || opt.equals("--top")) {
                mExemptionFlag = ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP;

            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return false;
            }
        }

        if (mNextArg < mArgs.length) {
            showUsage();
            return false;
        }
        return true;
    }

    private String nextOption() {
        if (mCurArgData != null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("No argument expected after \"" + prev + "\"");
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextArg() {
        if (mCurArgData != null) {
            String arg = mCurArgData;
            mCurArgData = null;
            return arg;
        } else if (mNextArg < mArgs.length) {
            return mArgs[mNextArg++];
        } else {
            return null;
        }
    }

    private String nextArgRequired() {
        String arg = nextArg();
        if (arg == null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("Argument expected after \"" + prev + "\"");
        }
        return arg;
    }

    private static void showUsage() {
        System.err.println(
                "Usage:\n" +
                "\n" +
                "  requestsync [options]\n" +
                "    With no options, a sync will be requested for all account and all sync\n" +
                "    authorities with no extras.\n" +
                "    Basic options:\n" +
                "       -h|--help: Display this message\n" +
                "       -n|--account-name <ACCOUNT-NAME>\n" +
                "       -t|--account-type <ACCOUNT-TYPE>\n" +
                "       -a|--authority <AUTHORITY>\n" +
                "    App-standby related options\n" +
                "\n" +
                "       -f|--foreground (defeat app-standby job throttling," +
                " but not battery saver)\n" +
                "       -F|--top (defeat app-standby job throttling and battery saver)\n" +
                "    ContentResolver extra options:\n" +
                "      --is|--ignore-settings: Add SYNC_EXTRAS_IGNORE_SETTINGS\n" +
                "      --ib|--ignore-backoff: Add SYNC_EXTRAS_IGNORE_BACKOFF\n" +
                "      --dd|--discard-deletions: Add SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS\n" +
                "      --nr|--no-retry: Add SYNC_EXTRAS_DO_NOT_RETRY\n" +
                "      --ex|--expedited: Add SYNC_EXTRAS_EXPEDITED\n" +
                "      -i|--initialize: Add SYNC_EXTRAS_INITIALIZE\n" +
                "      --m|--manual: Add SYNC_EXTRAS_MANUAL\n" +
                "      --od|--override-deletions: Add SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS\n" +
                "      -u|--upload-only: Add SYNC_EXTRAS_UPLOAD\n" +
                "      --rc|--require-charging: Add SYNC_EXTRAS_REQUIRE_CHARGING\n" +
                "    Custom extra options:\n" +
                "      -e|--es|--extra-string <KEY> <VALUE>\n" +
                "      --esn|--extra-string-null <KEY>\n" +
                "      --ei|--extra-int <KEY> <VALUE>\n" +
                "      --el|--extra-long <KEY> <VALUE>\n" +
                "      --ef|--extra-float <KEY> <VALUE>\n" +
                "      --ed|--extra-double <KEY> <VALUE>\n" +
                "      --ez|--extra-bool <KEY> <VALUE>\n" +
                "\n" +
                "  requestsync add-periodic INTERVAL-SECOND [options]\n" +
                        "  requestsync remove-periodic [options]\n" +
                "    Mandatory options:\n" +
                "      -n|--account-name <ACCOUNT-NAME>\n" +
                "      -t|--account-type <ACCOUNT-TYPE>\n" +
                "      -a|--authority <AUTHORITY>\n" +
                "    Also takes the above extra options.\n"
                );
    }
}
