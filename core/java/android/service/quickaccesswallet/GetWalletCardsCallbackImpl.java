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

import java.util.List;

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
        Log.i(TAG, "onSuccess");
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
        Log.i(TAG, "onSuccessInternal");
        if (mCalled) {
            Log.w(TAG, "already called");
            return;
        }
        mCalled = true;
        try {
            mCallback.onGetWalletCardsSuccess(response);
            Log.i(TAG, "onSuccessInternal: returned response");
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
        return response != null
                && response.getWalletCards() != null
                && response.getSelectedIndex() >= 0
                && (response.getWalletCards().isEmpty() // selectedIndex may be 0 when list is empty
                || response.getSelectedIndex() < response.getWalletCards().size())
                && response.getWalletCards().size() < mRequest.getMaxCards()
                && areValidCards(response.getWalletCards());
    }

    private boolean areValidCards(List<WalletCard> walletCards) {
        for (WalletCard walletCard : walletCards) {
            if (walletCard == null
                    || walletCard.getCardId() == null
                    || walletCard.getCardImage() == null
                    || TextUtils.isEmpty(walletCard.getContentDescription())
                    || walletCard.getPendingIntent() == null) {
                return false;
            }
            Icon cardImage = walletCard.getCardImage();
            if (cardImage.getType() == Icon.TYPE_BITMAP
                    && walletCard.getCardImage().getBitmap().getConfig()
                    != Bitmap.Config.HARDWARE) {
                Log.w(TAG, "WalletCard bitmaps should be hardware bitmaps");
            }
        }
        return true;
    }
}
