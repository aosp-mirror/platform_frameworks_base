/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.sip;

import gov.nist.javax.sip.SipStackExt;
import gov.nist.javax.sip.clientauthutils.AccountManager;
import gov.nist.javax.sip.clientauthutils.AuthenticationHelper;

import android.net.sip.SipProfile;
import android.util.Log;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.regex.Pattern;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.Transaction;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransactionUnavailableException;
import javax.sip.TransactionState;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * Helper class for holding SIP stack related classes and for various low-level
 * SIP tasks like sending messages.
 */
class SipHelper {
    private static final String TAG = SipHelper.class.getSimpleName();
    private static final boolean DEBUG = true;

    private SipStack mSipStack;
    private SipProvider mSipProvider;
    private AddressFactory mAddressFactory;
    private HeaderFactory mHeaderFactory;
    private MessageFactory mMessageFactory;

    public SipHelper(SipStack sipStack, SipProvider sipProvider)
            throws PeerUnavailableException {
        mSipStack = sipStack;
        mSipProvider = sipProvider;

        SipFactory sipFactory = SipFactory.getInstance();
        mAddressFactory = sipFactory.createAddressFactory();
        mHeaderFactory = sipFactory.createHeaderFactory();
        mMessageFactory = sipFactory.createMessageFactory();
    }

    private FromHeader createFromHeader(SipProfile profile, String tag)
            throws ParseException {
        return mHeaderFactory.createFromHeader(profile.getSipAddress(), tag);
    }

    private ToHeader createToHeader(SipProfile profile) throws ParseException {
        return createToHeader(profile, null);
    }

    private ToHeader createToHeader(SipProfile profile, String tag)
            throws ParseException {
        return mHeaderFactory.createToHeader(profile.getSipAddress(), tag);
    }

    private CallIdHeader createCallIdHeader() {
        return mSipProvider.getNewCallId();
    }

    private CSeqHeader createCSeqHeader(String method)
            throws ParseException, InvalidArgumentException {
        long sequence = (long) (Math.random() * 10000);
        return mHeaderFactory.createCSeqHeader(sequence, method);
    }

    private MaxForwardsHeader createMaxForwardsHeader()
            throws InvalidArgumentException {
        return mHeaderFactory.createMaxForwardsHeader(70);
    }

    private MaxForwardsHeader createMaxForwardsHeader(int max)
            throws InvalidArgumentException {
        return mHeaderFactory.createMaxForwardsHeader(max);
    }

    private ListeningPoint getListeningPoint() throws SipException {
        ListeningPoint lp = mSipProvider.getListeningPoint(ListeningPoint.UDP);
        if (lp == null) lp = mSipProvider.getListeningPoint(ListeningPoint.TCP);
        if (lp == null) {
            ListeningPoint[] lps = mSipProvider.getListeningPoints();
            if ((lps != null) && (lps.length > 0)) lp = lps[0];
        }
        if (lp == null) {
            throw new SipException("no listening point is available");
        }
        return lp;
    }

    private List<ViaHeader> createViaHeaders()
            throws ParseException, SipException {
        List<ViaHeader> viaHeaders = new ArrayList<ViaHeader>(1);
        ListeningPoint lp = getListeningPoint();
        ViaHeader viaHeader = mHeaderFactory.createViaHeader(lp.getIPAddress(),
                lp.getPort(), lp.getTransport(), null);
        viaHeader.setRPort();
        viaHeaders.add(viaHeader);
        return viaHeaders;
    }

    private ContactHeader createContactHeader(SipProfile profile)
            throws ParseException, SipException {
        ListeningPoint lp = getListeningPoint();
        SipURI contactURI =
                createSipUri(profile.getUserName(), profile.getProtocol(), lp);

        Address contactAddress = mAddressFactory.createAddress(contactURI);
        contactAddress.setDisplayName(profile.getDisplayName());

        return mHeaderFactory.createContactHeader(contactAddress);
    }

    private ContactHeader createWildcardContactHeader() {
        ContactHeader contactHeader  = mHeaderFactory.createContactHeader();
        contactHeader.setWildCard();
        return contactHeader;
    }

    private SipURI createSipUri(String username, String transport,
            ListeningPoint lp) throws ParseException {
        SipURI uri = mAddressFactory.createSipURI(username, lp.getIPAddress());
        try {
            uri.setPort(lp.getPort());
            uri.setTransportParam(transport);
        } catch (InvalidArgumentException e) {
            throw new RuntimeException(e);
        }
        return uri;
    }

    public ClientTransaction sendKeepAlive(SipProfile userProfile, String tag)
            throws SipException {
        try {
            Request request = createRequest(Request.OPTIONS, userProfile, tag);

            ClientTransaction clientTransaction =
                    mSipProvider.getNewClientTransaction(request);
            clientTransaction.sendRequest();
            return clientTransaction;
        } catch (Exception e) {
            throw new SipException("sendKeepAlive()", e);
        }
    }

    public ClientTransaction sendRegister(SipProfile userProfile, String tag,
            int expiry) throws SipException {
        try {
            Request request = createRequest(Request.REGISTER, userProfile, tag);
            if (expiry == 0) {
                // remove all previous registrations by wildcard
                // rfc3261#section-10.2.2
                request.addHeader(createWildcardContactHeader());
            } else {
                request.addHeader(createContactHeader(userProfile));
            }
            request.addHeader(mHeaderFactory.createExpiresHeader(expiry));

            ClientTransaction clientTransaction =
                    mSipProvider.getNewClientTransaction(request);
            clientTransaction.sendRequest();
            return clientTransaction;
        } catch (ParseException e) {
            throw new SipException("sendRegister()", e);
        }
    }

    private Request createRequest(String requestType, SipProfile userProfile,
            String tag) throws ParseException, SipException {
        FromHeader fromHeader = createFromHeader(userProfile, tag);
        ToHeader toHeader = createToHeader(userProfile);

        String replaceStr = Pattern.quote(userProfile.getUserName() + "@");
        SipURI requestURI = mAddressFactory.createSipURI(
                userProfile.getUriString().replaceFirst(replaceStr, ""));

        List<ViaHeader> viaHeaders = createViaHeaders();
        CallIdHeader callIdHeader = createCallIdHeader();
        CSeqHeader cSeqHeader = createCSeqHeader(requestType);
        MaxForwardsHeader maxForwards = createMaxForwardsHeader();
        Request request = mMessageFactory.createRequest(requestURI,
                requestType, callIdHeader, cSeqHeader, fromHeader,
                toHeader, viaHeaders, maxForwards);
        Header userAgentHeader = mHeaderFactory.createHeader("User-Agent",
                "SIPAUA/0.1.001");
        request.addHeader(userAgentHeader);
        return request;
    }

    public ClientTransaction handleChallenge(ResponseEvent responseEvent,
            AccountManager accountManager) throws SipException {
        AuthenticationHelper authenticationHelper =
                ((SipStackExt) mSipStack).getAuthenticationHelper(
                        accountManager, mHeaderFactory);
        ClientTransaction tid = responseEvent.getClientTransaction();
        ClientTransaction ct = authenticationHelper.handleChallenge(
                responseEvent.getResponse(), tid, mSipProvider, 5);
        if (DEBUG) Log.d(TAG, "send request with challenge response: "
                + ct.getRequest());
        ct.sendRequest();
        return ct;
    }

    public ClientTransaction sendInvite(SipProfile caller, SipProfile callee,
            String sessionDescription, String tag)
            throws SipException {
        try {
            FromHeader fromHeader = createFromHeader(caller, tag);
            ToHeader toHeader = createToHeader(callee);
            SipURI requestURI = callee.getUri();
            List<ViaHeader> viaHeaders = createViaHeaders();
            CallIdHeader callIdHeader = createCallIdHeader();
            CSeqHeader cSeqHeader = createCSeqHeader(Request.INVITE);
            MaxForwardsHeader maxForwards = createMaxForwardsHeader();

            Request request = mMessageFactory.createRequest(requestURI,
                    Request.INVITE, callIdHeader, cSeqHeader, fromHeader,
                    toHeader, viaHeaders, maxForwards);

            request.addHeader(createContactHeader(caller));
            request.setContent(sessionDescription,
                    mHeaderFactory.createContentTypeHeader(
                            "application", "sdp"));

            ClientTransaction clientTransaction =
                    mSipProvider.getNewClientTransaction(request);
            if (DEBUG) Log.d(TAG, "send INVITE: " + request);
            clientTransaction.sendRequest();
            return clientTransaction;
        } catch (ParseException e) {
            throw new SipException("sendInvite()", e);
        }
    }

    public ClientTransaction sendReinvite(Dialog dialog,
            String sessionDescription) throws SipException {
        try {
            Request request = dialog.createRequest(Request.INVITE);
            request.setContent(sessionDescription,
                    mHeaderFactory.createContentTypeHeader(
                            "application", "sdp"));

            ClientTransaction clientTransaction =
                    mSipProvider.getNewClientTransaction(request);
            if (DEBUG) Log.d(TAG, "send RE-INVITE: " + request);
            dialog.sendRequest(clientTransaction);
            return clientTransaction;
        } catch (ParseException e) {
            throw new SipException("sendReinvite()", e);
        }
    }

    private ServerTransaction getServerTransaction(RequestEvent event)
            throws SipException {
        ServerTransaction transaction = event.getServerTransaction();
        if (transaction == null) {
            Request request = event.getRequest();
            return mSipProvider.getNewServerTransaction(request);
        } else {
            return transaction;
        }
    }

    /**
     * @param event the INVITE request event
     */
    public ServerTransaction sendRinging(RequestEvent event, String tag)
            throws SipException {
        try {
            Request request = event.getRequest();
            ServerTransaction transaction = getServerTransaction(event);

            Response response = mMessageFactory.createResponse(Response.RINGING,
                    request);

            ToHeader toHeader = (ToHeader) response.getHeader(ToHeader.NAME);
            toHeader.setTag(tag);
            response.addHeader(toHeader);
            if (DEBUG) Log.d(TAG, "send RINGING: " + response);
            transaction.sendResponse(response);
            return transaction;
        } catch (ParseException e) {
            throw new SipException("sendRinging()", e);
        }
    }

    /**
     * @param event the INVITE request event
     */
    public ServerTransaction sendInviteOk(RequestEvent event,
            SipProfile localProfile, String sessionDescription,
            ServerTransaction inviteTransaction)
            throws SipException {
        try {
            Request request = event.getRequest();
            Response response = mMessageFactory.createResponse(Response.OK,
                    request);
            response.addHeader(createContactHeader(localProfile));
            response.setContent(sessionDescription,
                    mHeaderFactory.createContentTypeHeader(
                            "application", "sdp"));

            if (inviteTransaction == null) {
                inviteTransaction = getServerTransaction(event);
            }

            if (inviteTransaction.getState() != TransactionState.COMPLETED) {
                if (DEBUG) Log.d(TAG, "send OK: " + response);
                inviteTransaction.sendResponse(response);
            }

            return inviteTransaction;
        } catch (ParseException e) {
            throw new SipException("sendInviteOk()", e);
        }
    }

    public void sendInviteBusyHere(RequestEvent event,
            ServerTransaction inviteTransaction) throws SipException {
        try {
            Request request = event.getRequest();
            Response response = mMessageFactory.createResponse(
                    Response.BUSY_HERE, request);

            if (inviteTransaction == null) {
                inviteTransaction = getServerTransaction(event);
            }

            if (inviteTransaction.getState() != TransactionState.COMPLETED) {
                if (DEBUG) Log.d(TAG, "send BUSY HERE: " + response);
                inviteTransaction.sendResponse(response);
            }
        } catch (ParseException e) {
            throw new SipException("sendInviteBusyHere()", e);
        }
    }

    /**
     * @param event the INVITE ACK request event
     */
    public void sendInviteAck(ResponseEvent event, Dialog dialog)
            throws SipException {
        Response response = event.getResponse();
        long cseq = ((CSeqHeader) response.getHeader(CSeqHeader.NAME))
                .getSeqNumber();
        Request ack = dialog.createAck(cseq);
        if (DEBUG) Log.d(TAG, "send ACK: " + ack);
        dialog.sendAck(ack);
    }

    public void sendBye(Dialog dialog) throws SipException {
        Request byeRequest = dialog.createRequest(Request.BYE);
        if (DEBUG) Log.d(TAG, "send BYE: " + byeRequest);
        dialog.sendRequest(mSipProvider.getNewClientTransaction(byeRequest));
    }

    public void sendCancel(ClientTransaction inviteTransaction)
            throws SipException {
        Request cancelRequest = inviteTransaction.createCancel();
        if (DEBUG) Log.d(TAG, "send CANCEL: " + cancelRequest);
        mSipProvider.getNewClientTransaction(cancelRequest).sendRequest();
    }

    public void sendResponse(RequestEvent event, int responseCode)
            throws SipException {
        try {
            Response response = mMessageFactory.createResponse(
                    responseCode, event.getRequest());
            if (DEBUG) Log.d(TAG, "send response: " + response);
            getServerTransaction(event).sendResponse(response);
        } catch (ParseException e) {
            throw new SipException("sendResponse()", e);
        }
    }

    public void sendInviteRequestTerminated(Request inviteRequest,
            ServerTransaction inviteTransaction) throws SipException {
        try {
            Response response = mMessageFactory.createResponse(
                    Response.REQUEST_TERMINATED, inviteRequest);
            if (DEBUG) Log.d(TAG, "send response: " + response);
            inviteTransaction.sendResponse(response);
        } catch (ParseException e) {
            throw new SipException("sendInviteRequestTerminated()", e);
        }
    }

    public static String getCallId(EventObject event) {
        if (event == null) return null;
        if (event instanceof RequestEvent) {
            return getCallId(((RequestEvent) event).getRequest());
        } else if (event instanceof ResponseEvent) {
            return getCallId(((ResponseEvent) event).getResponse());
        } else if (event instanceof DialogTerminatedEvent) {
            Dialog dialog = ((DialogTerminatedEvent) event).getDialog();
            return getCallId(((DialogTerminatedEvent) event).getDialog());
        } else if (event instanceof TransactionTerminatedEvent) {
            TransactionTerminatedEvent e = (TransactionTerminatedEvent) event;
            return getCallId(e.isServerTransaction()
                    ? e.getServerTransaction()
                    : e.getClientTransaction());
        } else {
            Object source = event.getSource();
            if (source instanceof Transaction) {
                return getCallId(((Transaction) source));
            } else if (source instanceof Dialog) {
                return getCallId((Dialog) source);
            }
        }
        return "";
    }

    public static String getCallId(Transaction transaction) {
        return ((transaction != null) ? getCallId(transaction.getRequest())
                                      : "");
    }

    private static String getCallId(Message message) {
        CallIdHeader callIdHeader =
                (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        return callIdHeader.getCallId();
    }

    private static String getCallId(Dialog dialog) {
        return dialog.getCallId().getCallId();
    }
}
