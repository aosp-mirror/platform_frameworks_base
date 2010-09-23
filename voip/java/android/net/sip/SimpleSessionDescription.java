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

import java.util.ArrayList;
import java.util.Arrays;

/**
 * An object used to manipulate messages of Session Description Protocol (SDP).
 * It is mainly designed for the uses of Session Initiation Protocol (SIP).
 * Therefore, it only handles connection addresses ("c="), bandwidth limits,
 * ("b="), encryption keys ("k="), and attribute fields ("a="). Currently this
 * implementation does not support multicast sessions.
 *
 * <p>Here is an example code to create a session description.</p>
 * <pre>
 * SimpleSessionDescription description = new SimpleSessionDescription(
 *     System.currentTimeMillis(), "1.2.3.4");
 * Media media = description.newMedia("audio", 56789, 1, "RTP/AVP");
 * media.setRtpPayload(0, "PCMU/8000", null);
 * media.setRtpPayload(8, "PCMA/8000", null);
 * media.setRtpPayload(127, "telephone-event/8000", "0-15");
 * media.setAttribute("sendrecv", "");
 * </pre>
 * <p>Invoking <code>description.encode()</code> will produce a result like the
 * one below.</p>
 * <pre>
 * v=0
 * o=- 1284970442706 1284970442709 IN IP4 1.2.3.4
 * s=-
 * c=IN IP4 1.2.3.4
 * t=0 0
 * m=audio 56789 RTP/AVP 0 8 127
 * a=rtpmap:0 PCMU/8000
 * a=rtpmap:8 PCMA/8000
 * a=rtpmap:127 telephone-event/8000
 * a=fmtp:127 0-15
 * a=sendrecv
 * </pre>
 * @hide
 */
public class SimpleSessionDescription {
    private final Fields mFields = new Fields("voscbtka");
    private final ArrayList<Media> mMedia = new ArrayList<Media>();

    /**
     * Creates a minimal session description from the given session ID and
     * unicast address. The address is used in the origin field ("o=") and the
     * connection field ("c="). See {@link SimpleSessionDescription} for an
     * example of its usage.
     */
    public SimpleSessionDescription(long sessionId, String address) {
        address = (address.indexOf(':') < 0 ? "IN IP4 " : "IN IP6 ") + address;
        mFields.parse("v=0");
        mFields.parse(String.format("o=- %d %d %s", sessionId,
                System.currentTimeMillis(), address));
        mFields.parse("s=-");
        mFields.parse("t=0 0");
        mFields.parse("c=" + address);
    }

    /**
     * Creates a session description from the given message.
     *
     * @throws IllegalArgumentException if message is invalid.
     */
    public SimpleSessionDescription(String message) {
        String[] lines = message.trim().replaceAll(" +", " ").split("[\r\n]+");
        Fields fields = mFields;

        for (String line : lines) {
            try {
                if (line.charAt(1) != '=') {
                    throw new IllegalArgumentException();
                }
                if (line.charAt(0) == 'm') {
                    String[] parts = line.substring(2).split(" ", 4);
                    String[] ports = parts[1].split("/", 2);
                    Media media = newMedia(parts[0], Integer.parseInt(ports[0]),
                            (ports.length < 2) ? 1 : Integer.parseInt(ports[1]),
                            parts[2]);
                    for (String format : parts[3].split(" ")) {
                        media.setFormat(format, null);
                    }
                    fields = media;
                } else {
                    fields.parse(line);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid SDP: " + line);
            }
        }
    }

    /**
     * Creates a new media description in this session description.
     *
     * @param type The media type, e.g. {@code "audio"}.
     * @param port The first transport port used by this media.
     * @param portCount The number of contiguous ports used by this media.
     * @param protocol The transport protocol, e.g. {@code "RTP/AVP"}.
     */
    public Media newMedia(String type, int port, int portCount,
            String protocol) {
        Media media = new Media(type, port, portCount, protocol);
        mMedia.add(media);
        return media;
    }

    /**
     * Returns all the media descriptions in this session description.
     */
    public Media[] getMedia() {
        return mMedia.toArray(new Media[mMedia.size()]);
    }

    /**
     * Encodes the session description and all its media descriptions in a
     * string. Note that the result might be incomplete if a required field
     * has never been added before.
     */
    public String encode() {
        StringBuilder buffer = new StringBuilder();
        mFields.write(buffer);
        for (Media media : mMedia) {
            media.write(buffer);
        }
        return buffer.toString();
    }

    /**
     * Returns the connection address or {@code null} if it is not present.
     */
    public String getAddress() {
        return mFields.getAddress();
    }

    /**
     * Sets the connection address. The field will be removed if the address
     * is {@code null}.
     */
    public void setAddress(String address) {
        mFields.setAddress(address);
    }

    /**
     * Returns the encryption method or {@code null} if it is not present.
     */
    public String getEncryptionMethod() {
        return mFields.getEncryptionMethod();
    }

    /**
     * Returns the encryption key or {@code null} if it is not present.
     */
    public String getEncryptionKey() {
        return mFields.getEncryptionKey();
    }

    /**
     * Sets the encryption method and the encryption key. The field will be
     * removed if the method is {@code null}.
     */
    public void setEncryption(String method, String key) {
        mFields.setEncryption(method, key);
    }

    /**
     * Returns the types of the bandwidth limits.
     */
    public String[] getBandwidthTypes() {
        return mFields.getBandwidthTypes();
    }

    /**
     * Returns the bandwidth limit of the given type or {@code -1} if it is not
     * present.
     */
    public int getBandwidth(String type) {
        return mFields.getBandwidth(type);
    }

    /**
     * Sets the bandwith limit for the given type. The field will be removed if
     * the value is negative.
     */
    public void setBandwidth(String type, int value) {
        mFields.setBandwidth(type, value);
    }

    /**
     * Returns the names of all the attributes.
     */
    public String[] getAttributeNames() {
        return mFields.getAttributeNames();
    }

    /**
     * Returns the attribute of the given name or {@code null} if it is not
     * present.
     */
    public String getAttribute(String name) {
        return mFields.getAttribute(name);
    }

    /**
     * Sets the attribute for the given name. The field will be removed if
     * the value is {@code null}. To set a binary attribute, use an empty
     * string as the value.
     */
    public void setAttribute(String name, String value) {
        mFields.setAttribute(name, value);
    }

    /**
     * This class represents a media description of a session description. It
     * can only be created by {@link SimpleSessionDescription#newMedia}. Since
     * the syntax is more restricted for RTP based protocols, two sets of access
     * methods are implemented. See {@link SimpleSessionDescription} for an
     * example of its usage.
     */
    public static class Media extends Fields {
        private final String mType;
        private final int mPort;
        private final int mPortCount;
        private final String mProtocol;
        private ArrayList<String> mFormats = new ArrayList<String>();

        private Media(String type, int port, int portCount, String protocol) {
            super("icbka");
            mType = type;
            mPort = port;
            mPortCount = portCount;
            mProtocol = protocol;
        }

        /**
         * Returns the media type.
         */
        public String getType() {
            return mType;
        }

        /**
         * Returns the first transport port used by this media.
         */
        public int getPort() {
            return mPort;
        }

        /**
         * Returns the number of contiguous ports used by this media.
         */
        public int getPortCount() {
            return mPortCount;
        }

        /**
         * Returns the transport protocol.
         */
        public String getProtocol() {
            return mProtocol;
        }

        /**
         * Returns the media formats.
         */
        public String[] getFormats() {
            return mFormats.toArray(new String[mFormats.size()]);
        }

        /**
         * Returns the {@code fmtp} attribute of the given format or
         * {@code null} if it is not present.
         */
        public String getFmtp(String format) {
            return super.get("a=fmtp:" + format, ' ');
        }

        /**
         * Sets a format and its {@code fmtp} attribute. If the attribute is
         * {@code null}, the corresponding field will be removed.
         */
        public void setFormat(String format, String fmtp) {
            mFormats.remove(format);
            mFormats.add(format);
            super.set("a=rtpmap:" + format, ' ', null);
            super.set("a=fmtp:" + format, ' ', fmtp);
        }

        /**
         * Removes a format and its {@code fmtp} attribute.
         */
        public void removeFormat(String format) {
            mFormats.remove(format);
            super.set("a=rtpmap:" + format, ' ', null);
            super.set("a=fmtp:" + format, ' ', null);
        }

        /**
         * Returns the RTP payload types.
         */
        public int[] getRtpPayloadTypes() {
            int[] types = new int[mFormats.size()];
            int length = 0;
            for (String format : mFormats) {
                try {
                    types[length] = Integer.parseInt(format);
                    ++length;
                } catch (NumberFormatException e) { }
            }
            return Arrays.copyOf(types, length);
        }

        /**
         * Returns the {@code rtpmap} attribute of the given RTP payload type
         * or {@code null} if it is not present.
         */
        public String getRtpmap(int type) {
            return super.get("a=rtpmap:" + type, ' ');
        }

        /**
         * Returns the {@code fmtp} attribute of the given RTP payload type or
         * {@code null} if it is not present.
         */
        public String getFmtp(int type) {
            return super.get("a=fmtp:" + type, ' ');
        }

        /**
         * Sets a RTP payload type and its {@code rtpmap} and {@code fmtp}
         * attributes. If any of the attributes is {@code null}, the
         * corresponding field will be removed. See
         * {@link SimpleSessionDescription} for an example of its usage.
         */
        public void setRtpPayload(int type, String rtpmap, String fmtp) {
            String format = String.valueOf(type);
            mFormats.remove(format);
            mFormats.add(format);
            super.set("a=rtpmap:" + format, ' ', rtpmap);
            super.set("a=fmtp:" + format, ' ', fmtp);
        }

        /**
         * Removes a RTP payload and its {@code rtpmap} and {@code fmtp}
         * attributes.
         */
        public void removeRtpPayload(int type) {
            removeFormat(String.valueOf(type));
        }

        private void write(StringBuilder buffer) {
            buffer.append("m=").append(mType).append(' ').append(mPort);
            if (mPortCount != 1) {
                buffer.append('/').append(mPortCount);
            }
            buffer.append(' ').append(mProtocol);
            for (String format : mFormats) {
                buffer.append(' ').append(format);
            }
            buffer.append("\r\n");
            super.write(buffer);
        }
    }

    /**
     * This class acts as a set of fields, and the size of the set is expected
     * to be small. Therefore, it uses a simple list instead of maps. Each field
     * has three parts: a key, a delimiter, and a value. Delimiters are special
     * because they are not included in binary attributes. As a result, the
     * private methods, which are the building blocks of this class, all take
     * the delimiter as an argument.
     */
    private static class Fields {
        private final String mOrder;
        private final ArrayList<String> mLines = new ArrayList<String>();

        Fields(String order) {
            mOrder = order;
        }

        /**
         * Returns the connection address or {@code null} if it is not present.
         */
        public String getAddress() {
            String address = get("c", '=');
            if (address == null) {
                return null;
            }
            String[] parts = address.split(" ");
            if (parts.length != 3) {
                return null;
            }
            int slash = parts[2].indexOf('/');
            return (slash < 0) ? parts[2] : parts[2].substring(0, slash);
        }

        /**
         * Sets the connection address. The field will be removed if the address
         * is {@code null}.
         */
        public void setAddress(String address) {
            if (address != null) {
                address = (address.indexOf(':') < 0 ? "IN IP4 " : "IN IP6 ") +
                        address;
            }
            set("c", '=', address);
        }

        /**
         * Returns the encryption method or {@code null} if it is not present.
         */
        public String getEncryptionMethod() {
            String encryption = get("k", '=');
            if (encryption == null) {
                return null;
            }
            int colon = encryption.indexOf(':');
            return (colon == -1) ? encryption : encryption.substring(0, colon);
        }

        /**
         * Returns the encryption key or {@code null} if it is not present.
         */
        public String getEncryptionKey() {
            String encryption = get("k", '=');
            if (encryption == null) {
                return null;
            }
            int colon = encryption.indexOf(':');
            return (colon == -1) ? null : encryption.substring(0, colon + 1);
        }

        /**
         * Sets the encryption method and the encryption key. The field will be
         * removed if the method is {@code null}.
         */
        public void setEncryption(String method, String key) {
            set("k", '=', (method == null || key == null) ?
                    method : method + ':' + key);
        }

        /**
         * Returns the types of the bandwidth limits.
         */
        public String[] getBandwidthTypes() {
            return cut("b=", ':');
        }

        /**
         * Returns the bandwidth limit of the given type or {@code -1} if it is
         * not present.
         */
        public int getBandwidth(String type) {
            String value = get("b=" + type, ':');
            if (value != null) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) { }
                setBandwidth(type, -1);
            }
            return -1;
        }

        /**
         * Sets the bandwith limit for the given type. The field will be removed
         * if the value is negative.
         */
        public void setBandwidth(String type, int value) {
            set("b=" + type, ':', (value < 0) ? null : String.valueOf(value));
        }

        /**
         * Returns the names of all the attributes.
         */
        public String[] getAttributeNames() {
            return cut("a=", ':');
        }

        /**
         * Returns the attribute of the given name or {@code null} if it is not
         * present.
         */
        public String getAttribute(String name) {
            return get("a=" + name, ':');
        }

        /**
         * Sets the attribute for the given name. The field will be removed if
         * the value is {@code null}. To set a binary attribute, use an empty
         * string as the value.
         */
        public void setAttribute(String name, String value) {
            set("a=" + name, ':', value);
        }

        private void write(StringBuilder buffer) {
            for (int i = 0; i < mOrder.length(); ++i) {
                char type = mOrder.charAt(i);
                for (String line : mLines) {
                    if (line.charAt(0) == type) {
                        buffer.append(line).append("\r\n");
                    }
                }
            }
        }

        /**
         * Invokes {@link #set} after splitting the line into three parts.
         */
        private void parse(String line) {
            char type = line.charAt(0);
            if (mOrder.indexOf(type) == -1) {
                return;
            }
            char delimiter = '=';
            if (line.startsWith("a=rtpmap:") || line.startsWith("a=fmtp:")) {
                delimiter = ' ';
            } else if (type == 'b' || type == 'a') {
                delimiter = ':';
            }
            int i = line.indexOf(delimiter);
            if (i == -1) {
                set(line, delimiter, "");
            } else {
                set(line.substring(0, i), delimiter, line.substring(i + 1));
            }
        }

        /**
         * Finds the key with the given prefix and returns its suffix.
         */
        private String[] cut(String prefix, char delimiter) {
            String[] names = new String[mLines.size()];
            int length = 0;
            for (String line : mLines) {
                if (line.startsWith(prefix)) {
                    int i = line.indexOf(delimiter);
                    if (i == -1) {
                        i = line.length();
                    }
                    names[length] = line.substring(prefix.length(), i);
                    ++length;
                }
            }
            return Arrays.copyOf(names, length);
        }

        /**
         * Returns the index of the key.
         */
        private int find(String key, char delimiter) {
            int length = key.length();
            for (int i = mLines.size() - 1; i >= 0; --i) {
                String line = mLines.get(i);
                if (line.startsWith(key) && (line.length() == length ||
                        line.charAt(length) == delimiter)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Sets the key with the value or removes the key if the value is
         * {@code null}.
         */
        private void set(String key, char delimiter, String value) {
            int index = find(key, delimiter);
            if (value != null) {
                if (value.length() != 0) {
                    key = key + delimiter + value;
                }
                if (index == -1) {
                    mLines.add(key);
                } else {
                    mLines.set(index, key);
                }
            } else if (index != -1) {
                mLines.remove(index);
            }
        }

        /**
         * Returns the value of the key.
         */
        private String get(String key, char delimiter) {
            int index = find(key, delimiter);
            if (index == -1) {
                return null;
            }
            String line = mLines.get(index);
            int length = key.length();
            return (line.length() == length) ? "" : line.substring(length + 1);
        }
    }
}
