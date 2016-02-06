package com.android.anqp.eap;

/**
 * An Authentication parameter, part of the NAI Realm ANQP element, specified in
 * IEEE802.11-2012 section 8.4.4.10, table 8-188
 */
public interface AuthParam {
    public EAP.AuthInfoID getAuthInfoID();
}
