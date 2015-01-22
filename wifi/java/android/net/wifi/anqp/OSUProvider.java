package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static android.net.wifi.anqp.Constants.BYTE_MASK;
import static android.net.wifi.anqp.Constants.SHORT_MASK;

/**
 * An OSU Provider, as specified in
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.1
 */
public class OSUProvider {

    public enum OSUMethod {OmaDm, SoapXml}

    private final List<I18Name> mNames;
    private final String mOSUServer;
    private final List<OSUMethod> mOSUMethods;
    private final List<IconInfo> mIcons;
    private final String mOsuNai;
    private final List<I18Name> mServiceDescriptions;

    public OSUProvider(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 11) {
            throw new ProtocolException("Truncated OSU provider: " + payload.remaining());
        }

        int length = payload.getShort() & SHORT_MASK;
        int namesLength = payload.getShort() & SHORT_MASK;

        ByteBuffer namesBuffer = payload.duplicate();
        namesBuffer.limit(namesBuffer.position() + namesLength);
        payload.position(payload.position() + namesLength);

        mNames = new ArrayList<I18Name>();

        while (namesBuffer.hasRemaining()) {
            mNames.add(new I18Name(namesBuffer));
        }

        mOSUServer = Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8);
        int methodLength = payload.get() & BYTE_MASK;
        mOSUMethods = new ArrayList<OSUMethod>(methodLength);
        while (methodLength > 0) {
            int methodID = payload.get() & BYTE_MASK;
            mOSUMethods.add(methodID < OSUMethod.values().length ?
                    OSUMethod.values()[methodID] :
                    null);
            methodLength--;
        }

        int iconsLength = payload.getShort() & SHORT_MASK;
        ByteBuffer iconsBuffer = payload.duplicate();
        iconsBuffer.limit(iconsBuffer.position() + iconsLength);
        payload.position(payload.position() + iconsLength);

        mIcons = new ArrayList<IconInfo>();

        while (iconsBuffer.hasRemaining()) {
            mIcons.add(new IconInfo(iconsBuffer));
        }

        mOsuNai = Constants.getString(payload, 1, StandardCharsets.UTF_8, true);

        int descriptionsLength = payload.getShort() & SHORT_MASK;
        ByteBuffer descriptionsBuffer = payload.duplicate();
        descriptionsBuffer.limit(descriptionsBuffer.position() + descriptionsLength);
        payload.position(payload.position() + descriptionsLength);

        mServiceDescriptions = new ArrayList<I18Name>();

        while (descriptionsBuffer.hasRemaining()) {
            mServiceDescriptions.add(new I18Name(descriptionsBuffer));
        }
    }

    public List<I18Name> getNames() {
        return mNames;
    }

    public String getOSUServer() {
        return mOSUServer;
    }

    public List<OSUMethod> getOSUMethods() {
        return mOSUMethods;
    }

    public List<IconInfo> getIcons() {
        return mIcons;
    }

    public String getOsuNai() {
        return mOsuNai;
    }

    public List<I18Name> getServiceDescriptions() {
        return mServiceDescriptions;
    }

    @Override
    public String toString() {
        return "OSUProvider{" +
                "mNames=" + mNames +
                ", mOSUServer='" + mOSUServer + '\'' +
                ", mOSUMethods=" + mOSUMethods +
                ", mIcons=" + mIcons +
                ", mOsuNai='" + mOsuNai + '\'' +
                ", mServiceDescriptions=" + mServiceDescriptions +
                '}';
    }
}
