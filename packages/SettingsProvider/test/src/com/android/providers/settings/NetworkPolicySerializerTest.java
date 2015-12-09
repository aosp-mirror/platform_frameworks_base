package com.android.providers.settings;

import android.net.NetworkPolicy;
import android.net.NetworkTemplate;
import android.test.AndroidTestCase;

import java.util.Random;

/**
 * Tests for NetworkPolicySerializer
 */
public class NetworkPolicySerializerTest extends AndroidTestCase {
    static Random sRandom = new Random();

    public void testMarshallAndUnmarshalNetworkPolicy() {
        NetworkPolicy policy = getDummyNetworkPolicy();
        byte[] data = NetworkPolicySerializer.marshalNetworkPolicy(policy);
        assertNotNull("Got Null data from marshal", data);
        assertFalse("Got back an empty byte[] from marshal", data.length == 0);

        NetworkPolicy unmarshaled = NetworkPolicySerializer.unmarshalNetworkPolicy(data);
        assertNotNull("Got Null data from unmarshaled", unmarshaled);
        assertTrue("NetworkPolicy Marshall and Unmarshal Failed!", policy.equals(unmarshaled));
    }

    public void testMarshallNetworkPolicyEdgeCases() {
        byte[] data = NetworkPolicySerializer.marshalNetworkPolicy(null);
        assertNotNull("NetworkPolicy marshal returned null. Expected: byte[0]", data);
        assertEquals("NetworkPolicy marshal returned incomplete byte array. Expected: byte[0]",
                data.length, 0);
    }

    public void testUnmarshallNetworkPolicyEdgeCases() {
        NetworkPolicy policy = NetworkPolicySerializer.unmarshalNetworkPolicy(null);
        assertNull("Non null NetworkPolicy returned for null byte[] input", policy);

        policy = NetworkPolicySerializer.unmarshalNetworkPolicy(new byte[0]);
        assertNull("Non null NetworkPolicy returned for empty byte[] input", policy);

        policy = NetworkPolicySerializer.unmarshalNetworkPolicy(new byte[]{10, 20, 30, 40, 50, 60});
        assertNull("Non null NetworkPolicy returned for incomplete byte[] input", policy);
    }

    public void testMarshallAndUnmarshalNetworkPolicies() {
        NetworkPolicy[] policies = getDummyNetworkPolicies(5);
        byte[] data = NetworkPolicySerializer.marshalNetworkPolicies(policies);
        assertNotNull("Got Null data from marshal", data);
        assertFalse("Got back an empty byte[] from marshal", data.length == 0);

        NetworkPolicy[] unmarshaled = NetworkPolicySerializer.unmarshalNetworkPolicies(data);
        assertNotNull("Got Null data from unmarshaled", unmarshaled);
        try {
            for (int i = 0; i < policies.length; i++) {
                assertTrue("NetworkPolicies Marshall and Unmarshal Failed!",
                        policies[i].equals(unmarshaled[i]));
            }
        } catch (NullPointerException npe) {
            assertTrue("Some policies were not marshaled/unmarshaled correctly", false);
        }
    }

    public void testMarshallNetworkPoliciesEdgeCases() {
        byte[] data = NetworkPolicySerializer.marshalNetworkPolicies(null);
        assertNotNull("NetworkPolicies marshal returned null!", data);
        assertEquals("NetworkPolicies marshal returned incomplete byte array", data.length, 0);

        data = NetworkPolicySerializer.marshalNetworkPolicies(new NetworkPolicy[0]);
        assertNotNull("NetworkPolicies marshal returned null for empty NetworkPolicy[]", data);
        assertEquals("NetworkPolicies marshal returned incomplete byte array for empty NetworkPolicy[]"
                , data.length, 0);
    }

    public void testUnmarshalNetworkPoliciesEdgeCases() {
        NetworkPolicy[] policies = NetworkPolicySerializer.unmarshalNetworkPolicies(null);
        assertNotNull("NetworkPolicies unmarshal returned null for null input. Expected: byte[0] ",
                policies);
        assertEquals("Non Empty NetworkPolicy[] returned for null input Expected: byte[0]",
                policies.length, 0);

        policies = NetworkPolicySerializer.unmarshalNetworkPolicies(new byte[0]);
        assertNotNull("NetworkPolicies unmarshal returned null for empty byte[] input. Expected: byte[0]",
                policies);
        assertEquals("Non Empty NetworkPolicy[] returned for empty byte[] input. Expected: byte[0]",
                policies.length, 0);

        policies = NetworkPolicySerializer.unmarshalNetworkPolicies(new byte[]{10, 20, 30, 40, 50, 60});
        assertNotNull("NetworkPolicies unmarshal returned null for incomplete byte[] input. " +
                "Expected: byte[0] ", policies);
        assertEquals("Non Empty NetworkPolicy[] returned for incomplete byte[] input Expected: byte[0]",
                policies.length, 0);

    }

    private NetworkPolicy[] getDummyNetworkPolicies(int num) {
        NetworkPolicy[] policies = new NetworkPolicy[num];
        for (int i = 0; i < num; i++) {
            policies[i] = getDummyNetworkPolicy();
        }
        return policies;
    }

    private NetworkPolicy getDummyNetworkPolicy() {
        NetworkTemplate template = new NetworkTemplate(NetworkTemplate.MATCH_MOBILE_ALL, "subId",
                "GoogleGuest");
        int cycleDay = sRandom.nextInt();
        String cycleTimezone = "timezone";
        long warningBytes = sRandom.nextLong();
        long limitBytes = sRandom.nextLong();
        long lastWarningSnooze = sRandom.nextLong();
        long lastLimitSnooze = sRandom.nextLong();
        boolean metered = sRandom.nextInt() % 2 == 0;
        boolean inferred = sRandom.nextInt() % 2 == 0;
        return new NetworkPolicy(template, cycleDay, cycleTimezone, warningBytes, limitBytes,
                lastWarningSnooze, lastLimitSnooze, metered, inferred);
    }

}
