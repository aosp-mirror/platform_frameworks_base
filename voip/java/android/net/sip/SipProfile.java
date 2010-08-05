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

package android.net.sip;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.io.Serializable;
import java.text.ParseException;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;

/**
 * Class containing a SIP account, domain and server information.
 * @hide
 */
public class SipProfile implements Parcelable, Serializable {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_PORT = 5060;
    private Address mAddress;
    private String mProxyAddress;
    private String mPassword;
    private String mDomain;
    private String mProtocol = ListeningPoint.UDP;
    private String mProfileName;
    private boolean mSendKeepAlive = false;
    private boolean mAutoRegistration = true;

    /** @hide */
    public static final Parcelable.Creator<SipProfile> CREATOR =
            new Parcelable.Creator<SipProfile>() {
                public SipProfile createFromParcel(Parcel in) {
                    return new SipProfile(in);
                }

                public SipProfile[] newArray(int size) {
                    return new SipProfile[size];
                }
            };

    /**
     * Class to help create a {@link SipProfile}.
     */
    public static class Builder {
        private AddressFactory mAddressFactory;
        private SipProfile mProfile = new SipProfile();
        private SipURI mUri;
        private String mDisplayName;
        private String mProxyAddress;

        {
            try {
                mAddressFactory =
                        SipFactory.getInstance().createAddressFactory();
            } catch (PeerUnavailableException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Constructor.
         *
         * @param uriString the URI string as "sip:<user_name>@<domain>"
         * @throws ParseException if the string is not a valid URI
         */
        public Builder(String uriString) throws ParseException {
            if (uriString == null) {
                throw new NullPointerException("uriString cannot be null");
            }
            URI uri = mAddressFactory.createURI(fix(uriString));
            if (uri instanceof SipURI) {
                mUri = (SipURI) uri;
            } else {
                throw new ParseException(uriString + " is not a SIP URI", 0);
            }
            mProfile.mDomain = mUri.getHost();
        }

        /**
         * Constructor.
         *
         * @param username username of the SIP account
         * @param serverDomain the SIP server domain; if the network address
         *      is different from the domain, use
         *      {@link #setOutboundProxy(String)} to set server address
         * @throws ParseException if the parameters are not valid
         */
        public Builder(String username, String serverDomain)
                throws ParseException {
            if ((username == null) || (serverDomain == null)) {
                throw new NullPointerException(
                        "username and serverDomain cannot be null");
            }
            mUri = mAddressFactory.createSipURI(username, serverDomain);
            mProfile.mDomain = serverDomain;
        }

        private String fix(String uriString) {
            return (uriString.trim().toLowerCase().startsWith("sip:")
                    ? uriString
                    : "sip:" + uriString);
        }

        /**
         * Sets the name of the profile. This name is given by user.
         *
         * @param name name of the profile
         * @return this builder object
         */
        public Builder setProfileName(String name) {
            mProfile.mProfileName = name;
            return this;
        }

        /**
         * Sets the password of the SIP account
         *
         * @param password password of the SIP account
         * @return this builder object
         */
        public Builder setPassword(String password) {
            mUri.setUserPassword(password);
            return this;
        }

        /**
         * Sets the port number of the server. By default, it is 5060.
         *
         * @param port port number of the server
         * @return this builder object
         * @throws InvalidArgumentException if the port number is out of range
         */
        public Builder setPort(int port) throws InvalidArgumentException {
            mUri.setPort(port);
            return this;
        }

        /**
         * Sets the protocol used to connect to the SIP server. Currently,
         * only "UDP" and "TCP" are supported.
         *
         * @param protocol the protocol string
         * @return this builder object
         * @throws InvalidArgumentException if the protocol is not recognized
         */
        public Builder setProtocol(String protocol)
                throws InvalidArgumentException {
            if (protocol == null) {
                throw new NullPointerException("protocol cannot be null");
            }
            protocol = protocol.toUpperCase();
            if (!protocol.equals("UDP") && !protocol.equals("TCP")) {
                throw new InvalidArgumentException(
                        "unsupported protocol: " + protocol);
            }
            mProfile.mProtocol = protocol;
            return this;
        }

        /**
         * Sets the outbound proxy of the SIP server.
         *
         * @param outboundProxy the network address of the outbound proxy
         * @return this builder object
         */
        public Builder setOutboundProxy(String outboundProxy) {
            mProxyAddress = outboundProxy;
            return this;
        }

        /**
         * Sets the display name of the user.
         *
         * @param displayName display name of the user
         * @return this builder object
         */
        public Builder setDisplayName(String displayName) {
            mDisplayName = displayName;
            return this;
        }

        /**
         * Sets the send keep-alive flag.
         *
         * @param flag true if sending keep-alive message is required,
         *      false otherwise
         * @return this builder object
         */
        public Builder setSendKeepAlive(boolean flag) {
            mProfile.mSendKeepAlive = flag;
            return this;
        }


        /**
         * Sets the auto. registration flag.
         *
         * @param flag true if the profile will be registered automatically,
         *      false otherwise
         * @return this builder object
         */
        public Builder setAutoRegistration(boolean flag) {
            mProfile.mAutoRegistration = flag;
            return this;
        }

        /**
         * Builds and returns the SIP profile object.
         *
         * @return the profile object created
         */
        public SipProfile build() {
            // remove password from URI
            mProfile.mPassword = mUri.getUserPassword();
            mUri.setUserPassword(null);
            try {
                mProfile.mAddress = mAddressFactory.createAddress(
                        mDisplayName, mUri);
                if (!TextUtils.isEmpty(mProxyAddress)) {
                    SipURI uri = (SipURI)
                            mAddressFactory.createURI(fix(mProxyAddress));
                    mProfile.mProxyAddress = uri.getHost();
                }
            } catch (ParseException e) {
                // must not occur
                throw new RuntimeException(e);
            }
            return mProfile;
        }
    }

    private SipProfile() {
    }

    private SipProfile(Parcel in) {
        mAddress = (Address) in.readSerializable();
        mProxyAddress = in.readString();
        mPassword = in.readString();
        mDomain = in.readString();
        mProtocol = in.readString();
        mProfileName = in.readString();
        mSendKeepAlive = (in.readInt() == 0) ? false : true;
        mAutoRegistration = (in.readInt() == 0) ? false : true;
    }

    /** @hide */
    public void writeToParcel(Parcel out, int flags) {
        out.writeSerializable(mAddress);
        out.writeString(mProxyAddress);
        out.writeString(mPassword);
        out.writeString(mDomain);
        out.writeString(mProtocol);
        out.writeString(mProfileName);
        out.writeInt(mSendKeepAlive ? 1 : 0);
        out.writeInt(mAutoRegistration ? 1 : 0);
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /**
     * Gets the SIP URI of this profile.
     *
     * @return the SIP URI of this profile
     */
    public SipURI getUri() {
        return (SipURI) mAddress.getURI();
    }

    /**
     * Gets the SIP URI string of this profile.
     *
     * @return the SIP URI string of this profile
     */
    public String getUriString() {
        return mAddress.getURI().toString();
    }

    /**
     * Gets the SIP address of this profile.
     *
     * @return the SIP address of this profile
     */
    public Address getSipAddress() {
        return mAddress;
    }

    /**
     * Gets the display name of the user.
     *
     * @return the display name of the user
     */
    public String getDisplayName() {
        return mAddress.getDisplayName();
    }

    /**
     * Gets the username.
     *
     * @return the username
     */
    public String getUserName() {
        return getUri().getUser();
    }

    /**
     * Gets the password.
     *
     * @return the password
     */
    public String getPassword() {
        return mPassword;
    }

    /**
     * Gets the SIP domain.
     *
     * @return the SIP domain
     */
    public String getSipDomain() {
        return mDomain;
    }

    /**
     * Gets the port number of the SIP server.
     *
     * @return the port number of the SIP server
     */
    public int getPort() {
        int port = getUri().getPort();
        return (port == -1) ? DEFAULT_PORT : port;
    }

    /**
     * Gets the protocol used to connect to the server.
     *
     * @return the protocol
     */
    public String getProtocol() {
        return mProtocol;
    }

    /**
     * Gets the network address of the server outbound proxy.
     *
     * @return the network address of the server outbound proxy
     */
    public String getProxyAddress() {
        return mProxyAddress;
    }

    /**
     * Gets the (user-defined) name of the profile.
     *
     * @return name of the profile
     */
    public String getProfileName() {
        return mProfileName;
    }

    /**
     * Gets the flag of 'Sending keep-alive'.
     *
     * @return the flag of sending SIP keep-alive messages.
     */
    public boolean getSendKeepAlive() {
        return mSendKeepAlive;
    }

    /**
     * Gets the flag of 'Auto Registration'.
     *
     * @return the flag of registering the profile automatically.
     */
    public boolean getAutoRegistration() {
        return mAutoRegistration;
    }
}
