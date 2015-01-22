package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.net.wifi.anqp.Constants.BYTE_MASK;
import static android.net.wifi.anqp.Constants.SHORT_MASK;

/**
 * The Connection Capability vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.5
 */
public class HSConnectionCapabilityElement extends ANQPElement {

    public enum ProtoStatus {Closed, Open, Unknown}

    private final List<ProtocolTuple> mStatusList;

    public static class ProtocolTuple {
        private final int mProtocol;
        private final int mPort;
        private final ProtoStatus mStatus;

        private ProtocolTuple(ByteBuffer payload) throws ProtocolException {
            if (payload.remaining() < 4) {
                throw new ProtocolException("Runt protocol tuple: " + payload.remaining());
            }
            mProtocol = payload.get() & BYTE_MASK;
            mPort = payload.getShort() & SHORT_MASK;
            int statusNumber = payload.get() & BYTE_MASK;
            mStatus = statusNumber < ProtoStatus.values().length ?
                    ProtoStatus.values()[statusNumber] :
                    null;
        }

        public int getProtocol() {
            return mProtocol;
        }

        public int getPort() {
            return mPort;
        }

        public ProtoStatus getStatus() {
            return mStatus;
        }

        @Override
        public String toString() {
            return "ProtocolTuple{" +
                    "mProtocol=" + mProtocol +
                    ", mPort=" + mPort +
                    ", mStatus=" + mStatus +
                    '}';
        }
    }

    public HSConnectionCapabilityElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mStatusList = new ArrayList<ProtocolTuple>();
        while (payload.hasRemaining()) {
            mStatusList.add(new ProtocolTuple(payload));
        }
    }

    public List<ProtocolTuple> getStatusList() {
        return Collections.unmodifiableList(mStatusList);
    }

    @Override
    public String toString() {
        return "HSConnectionCapabilityElement{" +
                "mStatusList=" + mStatusList +
                '}';
    }
}
