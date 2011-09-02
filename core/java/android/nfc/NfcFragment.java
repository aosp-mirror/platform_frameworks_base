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

package android.nfc;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;

/**
 * Used by {@link NfcActivityManager} to attach to activity life-cycle.
 * @hide
 */
public final class NfcFragment extends Fragment {
    static final String FRAGMENT_TAG = "android.nfc.NfcFragment";

    // only used on UI thread
    static boolean sIsInitialized = false;
    static NfcActivityManager sNfcActivityManager;

    /**
     * Attach NfcFragment to an activity (if not already attached).
     */
    public static void attach(Activity activity) {
        FragmentManager manager = activity.getFragmentManager();
        if (manager.findFragmentByTag(FRAGMENT_TAG) == null) {
            manager.beginTransaction().add(new NfcFragment(), FRAGMENT_TAG).commit();
        }
    }

    /**
     * Remove NfcFragment from activity.
     */
    public static void remove(Activity activity) {
        FragmentManager manager = activity.getFragmentManager();
        Fragment fragment = manager.findFragmentByTag(FRAGMENT_TAG);
        if (fragment != null) {
            manager.beginTransaction().remove(fragment).commit();
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!sIsInitialized) {
            sIsInitialized = true;
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(
                    activity.getApplicationContext());
            if (adapter != null) {
                sNfcActivityManager = adapter.mNfcActivityManager;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sNfcActivityManager != null) {
            sNfcActivityManager.onResume(getActivity());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sNfcActivityManager != null) {
            sNfcActivityManager.onPause(getActivity());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sNfcActivityManager != null) {
            sNfcActivityManager.onDestroy(getActivity());
        }
    }


}
