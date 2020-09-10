/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.quickaccesswallet;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

/**
 * Handles response from the {@link QuickAccessWalletService} for {@link GetWalletCardsRequest}
 *
 * @hide
 */
final class GetWalletCardsCallbackImpl implements GetWalletCardsCallback {

    private static final String TAG = "QAWalletCallback";

    private final IQuickAccessWalletServiceCallbacks mCallback;
    private final GetWalletCardsRequest mRequest;
    private final Handler mHandler;
    private boolean mCalled;

    GetWalletCardsCallbackImpl(GetWalletCardsRequest request,
            IQuickAccessWalletServiceCallbacks callback, Handler handler) {
        mRequest = request;
        mCallback = callback;
        mHandler = handler;
    }

    /**
     * Notifies the Android System that an {@link QuickAccessWalletService#onWalletCardsRequested}
     * was successfully handled by the service.
     *
     * @param response The response contains the list of {@link WalletCard walletCards} to be shown
     *                 to the user as well as the index of the card that should initially be
     *                 presented as the selected card.
     */
    public void onSuccess(@NonNull GetWalletCardsResponse response) {
        if (isValidResponse(response)) {
            mHandler.post(() -> onSuccessInternal(response));
        } else {
            Log.w(TAG, "Invalid GetWalletCards response");
            mHandler.post(() -> onFailureInternal(new GetWalletCardsError(null, null)));
        }
    }

    /**
     * Notifies the Android System that an {@link QuickAccessWalletService#onWalletCardsRequested}
     * could not be handled by the service.
     *
     * @param error The error message. <b>Note: </b> this message should <b>not</b> contain PII
     *              (Personally Identifiable Information, such as username or email address).
     * @throws IllegalStateException if this method or {@link #onSuccess} was already called.
     */
    public void onFailure(@NonNull GetWalletCardsError error) {
        mHandler.post(() -> onFailureInternal(error));
    }

    private void onSuccessInternal(GetWalletCardsResponse response) {
        if (mCalled) {
            Log.w(TAG, "already called");
            return;
        }
        mCalled = true;
        try {
            mCallback.onGetWalletCardsSuccess(response);
        } catch (RemoteException e) {
            Log.w(TAG, "Error returning wallet cards", e);
        }
    }

    private void onFailureInternal(GetWalletCardsError error) {
        if (mCalled) {
            Log.w(TAG, "already called");
            return;
        }
        mCalled = true;
        try {
            mCallback.onGetWalletCardsFailure(error);
        } catch (RemoteException e) {
            Log.e(TAG, "Error returning failure message", e);
        }
    }

    private boolean isValidResponse(@NonNull GetWalletCardsResponse response) {
        if (response == null) {
            Log.w(TAG, "Invalid response: response is null");
            return false;
        }
        if (response.getWalletCards() == null) {
            Log.w(TAG, "Invalid response: walletCards is null");
            return false;
        }
        if (response.getSelectedIndex() < 0) {
            Log.w(TAG, "Invalid response: selectedIndex is negative");
            return false;
        }
        if (!response.getWalletCards().isEmpty()
                && response.getSelectedIndex() >= response.getWalletCards().size()) {
            Log.w(TAG, "Invalid response: selectedIndex out of bounds");
            return false;
        }
        if (response.getWalletCards().size() > mRequest.getMaxCards()) {
            Log.w(TAG, "Invalid response: too many cards");
            return false;
        }
        for (WalletCard walletCard : response.getWalletCards()) {
            if (walletCard == null) {
                Log.w(TAG, "Invalid response: card is null");
                return false;
            }
            if (walletCard.getCardId() == null) {
                Log.w(TAG, "Invalid response: cardId is null");
                return false;
            }
            Icon cardImage = walletCard.getCardImage();
            if (cardImage == null) {
                Log.w(TAG, "Invalid response: cardImage is null");
                return false;
            }
            if (cardImage.getType() == Icon.TYPE_BITMAP
                    && cardImage.getBitmap().getConfig() != Bitmap.Config.HARDWARE) {
                Log.w(TAG, "Invalid response: cardImage bitmaps must be hardware bitmaps");
                return false;
            }
            if (TextUtils.isEmpty(walletCard.getContentDescription())) {
                Log.w(TAG, "Invalid response: contentDescription is null");
                return false;
            }
            if (walletCard.getPendingIntent() == null) {
                Log.w(TAG, "Invalid response: pendingIntent is null");
                return false;
            }
        }
        return true;
    }
}
