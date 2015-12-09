package com.android.providers.settings;

import android.net.NetworkPolicy;
import android.net.NetworkTemplate;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Backup/Restore Serializer Class for android.net.NetworkPolicy
 */
public class NetworkPolicySerializer {
    private static final boolean DEBUG = false;
    private static final String TAG = "NetworkPolicySerializer";

    private static final int NULL = 0;
    private static final int NOT_NULL = 1;
    /**
     * Current Version of the Serializer.
     */
    private static int STATE_VERSION = 1;

    /**
     * Marshals an array of NetworkPolicy objects into a byte-array.
     *
     * @param policies - NetworkPolicies to be Marshaled
     * @return byte array
     */

    public static byte[] marshalNetworkPolicies(NetworkPolicy policies[]) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (policies != null && policies.length != 0) {
            DataOutputStream out = new DataOutputStream(baos);
            try {
                out.writeInt(STATE_VERSION);
                out.writeInt(policies.length);
                for (NetworkPolicy policy : policies) {
                    byte[] marshaledPolicy = marshalNetworkPolicy(policy);
                    if (marshaledPolicy != null) {
                        out.writeByte(NOT_NULL);
                        out.writeInt(marshaledPolicy.length);
                        out.write(marshaledPolicy);
                    } else {
                        out.writeByte(NULL);
                    }
                }
            } catch (IOException ioe) {
                Log.e(TAG, "Failed to Convert NetworkPolicies to byte array", ioe);
                baos.reset();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Unmarshals a byte array into an array of NetworkPolicy Objects
     *
     * @param data - marshaled NetworkPolicies Array
     * @return NetworkPolicy[] array
     */
    public static NetworkPolicy[] unmarshalNetworkPolicies(byte[] data) {
        if (data == null || data.length == 0) {
            return new NetworkPolicy[0];
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int version = in.readInt();
            int length = in.readInt();
            NetworkPolicy[] policies = new NetworkPolicy[length];
            for (int i = 0; i < length; i++) {
                byte isNull = in.readByte();
                if (isNull == NULL) continue;
                int byteLength = in.readInt();
                byte[] policyData = new byte[byteLength];
                in.read(policyData, 0, byteLength);
                policies[i] = unmarshalNetworkPolicy(policyData);
            }
            return policies;
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to Convert byte array to NetworkPolicies", ioe);
            return new NetworkPolicy[0];
        }
    }

    /**
     * Marshals a NetworkPolicy object into a byte-array.
     *
     * @param networkPolicy - NetworkPolicy to be Marshaled
     * @return byte array
     */
    public static byte[] marshalNetworkPolicy(NetworkPolicy networkPolicy) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (networkPolicy != null) {
            DataOutputStream out = new DataOutputStream(baos);
            try {
                out.writeInt(STATE_VERSION);
                writeNetworkTemplate(out, networkPolicy.template);
                out.writeInt(networkPolicy.cycleDay);
                writeString(out, networkPolicy.cycleTimezone);
                out.writeLong(networkPolicy.warningBytes);
                out.writeLong(networkPolicy.limitBytes);
                out.writeLong(networkPolicy.lastWarningSnooze);
                out.writeLong(networkPolicy.lastLimitSnooze);
                out.writeInt(networkPolicy.metered ? 1 : 0);
                out.writeInt(networkPolicy.inferred ? 1 : 0);
            } catch (IOException ioe) {
                Log.e(TAG, "Failed to Convert NetworkPolicy to byte array", ioe);
                baos.reset();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Unmarshals a byte array into a NetworkPolicy Object
     *
     * @param data - marshaled NetworkPolicy Object
     * @return NetworkPolicy Object
     */
    public static NetworkPolicy unmarshalNetworkPolicy(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int version = in.readInt();
            NetworkTemplate template = readNetworkTemplate(in, version);
            int cycleDay = in.readInt();
            String cycleTimeZone = readString(in, version);
            long warningBytes = in.readLong();
            long limitBytes = in.readLong();
            long lastWarningSnooze = in.readLong();
            long lastLimitSnooze = in.readLong();
            boolean metered = in.readInt() == 1;
            boolean inferred = in.readInt() == 1;
            return new NetworkPolicy(template, cycleDay, cycleTimeZone, warningBytes, limitBytes,
                    lastWarningSnooze, lastLimitSnooze, metered, inferred);
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to Convert byte array to NetworkPolicy", ioe);
            return null;
        }
    }

    private static NetworkTemplate readNetworkTemplate(DataInputStream in, int version)
            throws IOException {
        byte isNull = in.readByte();
        if (isNull == NULL) return null;
        int matchRule = in.readInt();
        String subscriberId = readString(in, version);
        String networkId = readString(in, version);
        return new NetworkTemplate(matchRule, subscriberId, networkId);
    }

    private static void writeNetworkTemplate(DataOutputStream out, NetworkTemplate template)
            throws IOException {
        if (template != null) {
            out.writeByte(NOT_NULL);
            out.writeInt(template.getMatchRule());
            writeString(out, template.getSubscriberId());
            writeString(out, template.getNetworkId());
        } else {
            out.writeByte(NULL);
        }
    }

    private static String readString(DataInputStream in, int version) throws IOException {
        byte isNull = in.readByte();
        if (isNull == NOT_NULL) {
            return in.readUTF();
        }
        return null;
    }

    private static void writeString(DataOutputStream out, String val) throws IOException {
        if (val != null) {
            out.writeByte(NOT_NULL);
            out.writeUTF(val);
        } else {
            out.writeByte(NULL);
        }
    }
}
