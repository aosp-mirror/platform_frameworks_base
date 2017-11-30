/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims.feature;

import android.annotation.SystemApi;
import android.os.RemoteException;
import com.android.ims.internal.IImsSmsFeature;
import com.android.ims.internal.ISmsListener;

/**
 * Base implementation of SMS over IMS functionality.
 *
 * @hide
 */
public class SmsFeature extends ImsFeature {
  /**
   * SMS over IMS format is 3gpp.
   */
  public static final int IMS_SMS_FORMAT_3GPP = 1;

  /**
   * SMS over IMS format is 3gpp2.
   */
  public static final int IMS_SMS_FORMAT_3GPP2 = 2;

  /**
   * Message was sent successfully.
   */
  public static final int SEND_STATUS_OK = 1;

  /**
   * IMS provider failed to send the message and platform should not retry falling back to sending
   * the message using the radio.
   */
  public static final int SEND_STATUS_ERROR = 2;

  /**
   * IMS provider failed to send the message and platform should retry again after setting TP-RD bit
   * to high.
   */
  public static final int SEND_STATUS_ERROR_RETRY = 3;

  /**
   * IMS provider failed to send the message and platform should retry falling back to sending
   * the message using the radio.
   */
  public static final int SEND_STATUS_ERROR_FALLBACK = 4;

  /**
   * Message was delivered successfully.
   */
  public static final int DELIVER_STATUS_OK = 1;

  /**
   * Message was not delivered.
   */
  public static final int DELIVER_STATUS_ERROR = 2;

  // Lock for feature synchronization
  private final Object mLock = new Object();
  private ISmsListener mSmsListener;

  private final IImsSmsFeature mIImsSmsBinder = new IImsSmsFeature.Stub() {
    @Override
    public void registerSmsListener(ISmsListener listener) {
      synchronized (mLock) {
        SmsFeature.this.registerSmsListener(listener);
      }
    }

    @Override
    public void sendSms(int format, int messageRef, boolean retry, byte[] pdu) {
      synchronized (mLock) {
        SmsFeature.this.sendSms(format, messageRef, retry, pdu);
      }
    }

    @Override
    public void acknowledgeSms(int messageRef, int result) {
      synchronized (mLock) {
        SmsFeature.this.acknowledgeSms(messageRef, result);
      }
    }

    @Override
    public int getSmsFormat() {
      synchronized (mLock) {
        return SmsFeature.this.getSmsFormat();
      }
    }
  };

  /**
   * Registers a listener responsible for handling tasks like delivering messages.

   * @param listener listener to register.
   *
   * @hide
   */
  @SystemApi
  public final void registerSmsListener(ISmsListener listener) {
    synchronized (mLock) {
      mSmsListener = listener;
    }
  }

  /**
   * This method will be triggered by the platform when the user attempts to send an SMS. This
   * method should be implemented by the IMS providers to provide implementation of sending an SMS
   * over IMS.
   *
   * @param format the format of the message. One of {@link #IMS_SMS_FORMAT_3GPP} or
   *                {@link #IMS_SMS_FORMAT_3GPP2}
   * @param messageRef the message reference.
   * @param retry whether it is a retry of an already attempted message or not.
   * @param pdu PDUs representing the contents of the message.
   */
  public void sendSms(int format, int messageRef, boolean isRetry, byte[] pdu) {
  }

  /**
   * This method will be triggered by the platform after {@link #deliverSms(int, byte[])} has been
   * called to deliver the result to the IMS provider. It will also be triggered after
   * {@link #setSentSmsResult(int, int)} has been called to provide the result of the operation.
   *
   * @param result Should be {@link #DELIVER_STATUS_OK} if the message was delivered successfully,
   * {@link #DELIVER_STATUS_ERROR} otherwise.
   * @param messageRef the message reference.
   */
  public void acknowledgeSms(int messageRef, int result) {

  }

  /**
   * This method should be triggered by the IMS providers when there is an incoming message. The
   * platform will deliver the message to the messages database and notify the IMS provider of the
   * result by calling {@link #acknowledgeSms(int)}.
   *
   * This method must not be called before {@link #onFeatureReady()} is called.
   *
   * @param format the format of the message.One of {@link #IMS_SMS_FORMAT_3GPP} or
   *                {@link #IMS_SMS_FORMAT_3GPP2}
   * @param pdu PDUs representing the contents of the message.
   * @throws IllegalStateException if called before {@link #onFeatureReady()}
   */
  public final void deliverSms(int format, byte[] pdu) throws IllegalStateException {
    // TODO: Guard against NPE/ Check if feature is ready and thrown an exception
    // otherwise.
    try {
      mSmsListener.deliverSms(format, pdu);
    } catch (RemoteException e) {
    }
  }

  /**
   * This method should be triggered by the IMS providers to pass the result of the sent message
   * to the platform.
   *
   * This method must not be called before {@link #onFeatureReady()} is called.
   *
   * @param messageRef the message reference.
   * @param result One of {@link #SEND_STATUS_OK}, {@link #SEND_STATUS_ERROR},
   *                {@link #SEND_STATUS_ERROR_RETRY}, {@link #SEND_STATUS_ERROR_FALLBACK}
   * @throws IllegalStateException if called before {@link #onFeatureReady()}
   */
  public final void setSentSmsResult(int messageRef, int result) throws IllegalStateException {
    // TODO: Guard against NPE/ Check if feature is ready and thrown an exception
    // otherwise.
    try {
      mSmsListener.setSentSmsResult(messageRef, result);
    } catch (RemoteException e) {
    }
  }

  /**
   * Sets the status report of the sent message.
   *
   * @param format Should be {@link #IMS_SMS_FORMAT_3GPP} or {@link #IMS_SMS_FORMAT_3GPP2}
   * @param pdu PDUs representing the content of the status report.
   * @throws IllegalStateException if called before {@link #onFeatureReady()}
   */
  public final void setSentSmsStatusReport(int format, byte[] pdu) {
    // TODO: Guard against NPE/ Check if feature is ready and thrown an exception
    // otherwise.
    try {
      mSmsListener.setSentSmsStatusReport(format, pdu);
    } catch (RemoteException e) {
    }
  }

  /**
   * Returns the SMS format. Default is {@link #IMS_SMS_FORMAT_3GPP} unless overridden by IMS
   * Provider.
   *
   * @return sms format.
   */
  public int getSmsFormat() {
    return IMS_SMS_FORMAT_3GPP;
  }

  /**
   * {@inheritDoc}
   */
  public void onFeatureReady() {

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onFeatureRemoved() {

  }

  /**
   * @hide
   */
  @Override
  public final IImsSmsFeature getBinder() {
    return mIImsSmsBinder;
  }
}