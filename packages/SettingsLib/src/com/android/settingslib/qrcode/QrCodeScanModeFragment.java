/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.qrcode;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import com.android.settingslib.bluetooth.BluetoothBroadcastUtils;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.ObservableFragment;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

public class QrCodeScanModeFragment extends ObservableFragment implements
        TextureView.SurfaceTextureListener,
        QrCamera.ScannerCallback {
    private static final boolean DEBUG = BluetoothUtils.D;
    private static final String TAG = "QrCodeScanModeFragment";

    /** Message sent to hide error message */
    private static final int MESSAGE_HIDE_ERROR_MESSAGE = 1;
    /** Message sent to show error message */
    private static final int MESSAGE_SHOW_ERROR_MESSAGE = 2;
    /** Message sent to broadcast QR code */
    private static final int MESSAGE_SCAN_BROADCAST_SUCCESS = 3;

    private static final long SHOW_ERROR_MESSAGE_INTERVAL = 10000;
    private static final long SHOW_SUCCESS_SQUARE_INTERVAL = 1000;

    private boolean mIsGroupOp;
    private int mCornerRadius;
    private BluetoothDevice mSink;
    private String mBroadcastMetadata;
    private Context mContext;
    private QrCamera mCamera;
    private QrCodeScanModeController mController;
    private TextureView mTextureView;
    private TextView mSummary;
    private TextView mErrorMessage;

    public QrCodeScanModeFragment(boolean isGroupOp, BluetoothDevice sink) {
        mIsGroupOp = isGroupOp;
        mSink = sink;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getContext();
        mController = new QrCodeScanModeController(mContext);
    }

    @Override
    public final View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.qrcode_scanner_fragment, container,
                /* attachToRoot */ false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mTextureView = view.findViewById(R.id.preview_view);
        mCornerRadius = mContext.getResources().getDimensionPixelSize(
                R.dimen.qrcode_preview_radius);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0,0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        mTextureView.setClipToOutline(true);
        mErrorMessage = view.findViewById(R.id.error_message);
    }

    private void initCamera(SurfaceTexture surface) {
        // Check if the camera has already created.
        if (mCamera == null) {
            mCamera = new QrCamera(mContext, this);
            mCamera.start(surface);
        }
    }

    private void destroyCamera() {
        if (mCamera != null) {
            mCamera.stop();
            mCamera = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        initCamera(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width,
            int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        destroyCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
    }

    @Override
    public void handleSuccessfulResult(String qrCode) {
        if (DEBUG) {
            Log.d(TAG, "handleSuccessfulResult(), get the qr code string.");
        }
        mBroadcastMetadata = qrCode;
        handleBtLeAudioScanner();
    }

    @Override
    public void handleCameraFailure() {
        destroyCamera();
    }

    @Override
    public Size getViewSize() {
        return new Size(mTextureView.getWidth(), mTextureView.getHeight());
    }

    @Override
    public Rect getFramePosition(Size previewSize, int cameraOrientation) {
        return new Rect(0, 0, previewSize.getHeight(), previewSize.getHeight());
    }

    @Override
    public void setTransform(Matrix transform) {
        mTextureView.setTransform(transform);
    }

    @Override
    public boolean isValid(String qrCode) {
        if (qrCode.startsWith(BluetoothBroadcastUtils.SCHEME_BT_BROADCAST_METADATA)) {
            return true;
        } else {
            showErrorMessage(R.string.bt_le_audio_qr_code_is_not_valid_format);
            return false;
        }
    }

    protected boolean isDecodeTaskAlive() {
        return mCamera != null && mCamera.isDecodeTaskAlive();
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_HIDE_ERROR_MESSAGE:
                    mErrorMessage.setVisibility(View.INVISIBLE);
                    break;

                case MESSAGE_SHOW_ERROR_MESSAGE:
                    final String errorMessage = (String) msg.obj;

                    mErrorMessage.setVisibility(View.VISIBLE);
                    mErrorMessage.setText(errorMessage);
                    mErrorMessage.sendAccessibilityEvent(
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);

                    // Cancel any pending messages to hide error view and requeue the message so
                    // user has time to see error
                    removeMessages(MESSAGE_HIDE_ERROR_MESSAGE);
                    sendEmptyMessageDelayed(MESSAGE_HIDE_ERROR_MESSAGE,
                            SHOW_ERROR_MESSAGE_INTERVAL);
                    break;

                case MESSAGE_SCAN_BROADCAST_SUCCESS:
                    mController.addSource(mSink, mBroadcastMetadata, mIsGroupOp);
                    updateSummary();
                    mSummary.sendAccessibilityEvent(
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                    break;
                default:
            }
        }
    };

    private void showErrorMessage(@StringRes int messageResId) {
        final Message message = mHandler.obtainMessage(MESSAGE_SHOW_ERROR_MESSAGE,
                getString(messageResId));
        message.sendToTarget();
    }

    private void handleBtLeAudioScanner() {
        Message message = mHandler.obtainMessage(MESSAGE_SCAN_BROADCAST_SUCCESS);
        mHandler.sendMessageDelayed(message, SHOW_SUCCESS_SQUARE_INTERVAL);
    }

    private void updateSummary() {
        mSummary.setText(getString(R.string.bt_le_audio_scan_qr_code_scanner,
                null /* broadcast_name*/));;
    }
}
