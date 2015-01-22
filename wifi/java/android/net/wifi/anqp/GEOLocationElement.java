package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * Holds an AP Geospatial Location ANQP Element, as specified in IEEE802.11-2012 section
 * 8.4.4.12.
 * <p/>
 * <p>
 * Section 8.4.2.24.10 of the IEEE802.11-2012 specification refers to RFC-3825 for the format of the
 * Geospatial location information. RFC-3825 has subsequently been obsoleted by RFC-6225 which
 * defines the same basic binary format for the DHCPv4 payload except that a few unused bits of the
 * Datum field have been reserved for other uses.
 * </p>
 * <p/>
 * <p>
 * RFC-3825 defines a resolution field for each of latitude, longitude and altitude as "the number
 * of significant bits" of precision in the respective values and implies through examples and
 * otherwise that the non-significant bits should be simply disregarded and the range of values are
 * calculated as the numeric interval obtained by varying the range of "insignificant bits" between
 * its extremes. As a simple example, consider the value 33 as a simple 8-bit number with three
 * significant bits: 33 is 00100001 binary and the leading 001 are the significant bits. With the
 * above definition, the range of numbers are [32,63] with 33 asymmetrically located at the low end
 * of the interval. In a more realistic setting an instrument, such as a GPS, would most likely
 * deliver measurements with a gaussian distribution around the exact value, meaning it is more
 * reasonable to assume the value as a "center" value with a symmetric uncertainty interval.
 * RFC-6225 redefines the "resolution" from RFC-3825 with an "uncertainty" value with these
 * properties, which is also the definition suggested here.
 * </p>
 * <p/>
 * <p>
 * The res fields provides the resolution as the exponent to a power of two,
 * e.g. 8 means 2^8 = +/- 256, 0 means 2^0 = +/- 1 and -7 means 2^-7 +/- 0.00781250.
 * Unknown resolution is indicated by not setting the respective resolution field in the RealValue.
 * </p>
 */
public class GEOLocationElement extends ANQPElement {
    public enum AltitudeType {Unknown, Meters, Floors}

    public enum Datum {Unknown, WGS84, NAD83Land, NAD83Water}

    private static final int ELEMENT_ID = 123;       // ???
    private static final int GEO_LOCATION_LENGTH = 16;

    private static final int LL_FRACTION_SIZE = 25;
    private static final int LL_WIDTH = 34;
    private static final int ALT_FRACTION_SIZE = 8;
    private static final int ALT_WIDTH = 30;
    private static final int RES_WIDTH = 6;
    private static final int ALT_TYPE_WIDTH = 4;
    private static final int DATUM_WIDTH = 8;

    private final RealValue mLatitude;
    private final RealValue mLongitude;
    private final RealValue mAltitude;
    private final AltitudeType mAltitudeType;
    private final Datum mDatum;

    public static class RealValue {
        private final double mValue;
        private final boolean mResolutionSet;
        private final int mResolution;

        public RealValue(double value) {
            mValue = value;
            mResolution = Integer.MIN_VALUE;
            mResolutionSet = false;
        }

        public RealValue(double value, int resolution) {
            mValue = value;
            mResolution = resolution;
            mResolutionSet = true;
        }

        public double getValue() {
            return mValue;
        }

        public boolean isResolutionSet() {
            return mResolutionSet;
        }

        public int getResolution() {
            return mResolution;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%f", mValue));
            if (mResolutionSet) {
                sb.append("+/-2^").append(mResolution);
            }
            return sb.toString();
        }
    }

    public GEOLocationElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        payload.get();
        int locLength = payload.get() & BYTE_MASK;

        if (locLength != GEO_LOCATION_LENGTH) {
            throw new ProtocolException("GeoLocation length field value " + locLength +
                    " incorrect, expected 16");
        }
        if (payload.remaining() != GEO_LOCATION_LENGTH) {
            throw new ProtocolException("Bad buffer length " + payload.remaining() +
                    ", expected 16");
        }

        ReverseBitStream reverseBitStream = new ReverseBitStream(payload);

        int rawLatRes = (int) reverseBitStream.sliceOff(RES_WIDTH);
        double latitude =
                fixToFloat(reverseBitStream.sliceOff(LL_WIDTH), LL_FRACTION_SIZE, LL_WIDTH);

        mLatitude = rawLatRes != 0 ?
                new RealValue(latitude, bitsToAbsResolution(rawLatRes, LL_WIDTH,
                        LL_FRACTION_SIZE)) :
                new RealValue(latitude);

        int rawLonRes = (int) reverseBitStream.sliceOff(RES_WIDTH);
        double longitude =
                fixToFloat(reverseBitStream.sliceOff(LL_WIDTH), LL_FRACTION_SIZE, LL_WIDTH);

        mLongitude = rawLonRes != 0 ?
                new RealValue(longitude, bitsToAbsResolution(rawLonRes, LL_WIDTH,
                        LL_FRACTION_SIZE)) :
                new RealValue(longitude);

        int altType = (int) reverseBitStream.sliceOff(ALT_TYPE_WIDTH);
        mAltitudeType = altType < AltitudeType.values().length ?
                AltitudeType.values()[altType] :
                AltitudeType.Unknown;

        int rawAltRes = (int) reverseBitStream.sliceOff(RES_WIDTH);
        double altitude = fixToFloat(reverseBitStream.sliceOff(ALT_WIDTH), ALT_FRACTION_SIZE,
                ALT_WIDTH);

        mAltitude = rawAltRes != 0 ?
                new RealValue(altitude, bitsToAbsResolution(rawAltRes, ALT_WIDTH,
                        ALT_FRACTION_SIZE)) :
                new RealValue(altitude);

        int datumValue = (int) reverseBitStream.sliceOff(DATUM_WIDTH);
        mDatum = datumValue < Datum.values().length ? Datum.values()[datumValue] : Datum.Unknown;
    }

    public RealValue getLatitude() {
        return mLatitude;
    }

    public RealValue getLongitude() {
        return mLongitude;
    }

    public RealValue getAltitude() {
        return mAltitude;
    }

    public AltitudeType getAltitudeType() {
        return mAltitudeType;
    }

    public Datum getDatum() {
        return mDatum;
    }

    @Override
    public String toString() {
        return "GEOLocationElement{" +
                "mLatitude=" + mLatitude +
                ", mLongitude=" + mLongitude +
                ", mAltitude=" + mAltitude +
                ", mAltitudeType=" + mAltitudeType +
                ", mDatum=" + mDatum +
                '}';
    }

    private static class ReverseBitStream {

        private final byte[] mOctets;
        private int mBitoffset;

        private ReverseBitStream(ByteBuffer octets) {
            mOctets = new byte[octets.remaining()];
            octets.get(mOctets);
        }

        private long sliceOff(int bits) {
            final int bn = mBitoffset + bits;
            int remaining = bits;
            long value = 0;

            while (mBitoffset < bn) {
                int sbit = mBitoffset & 0x7;        // Bit #0 is MSB, inclusive
                int octet = mBitoffset >>> 3;

                // Copy the minimum of what's to the right of sbit
                // and how much more goes to the target
                int width = Math.min(Byte.SIZE - sbit, remaining);

                value = (value << width) | getBits(mOctets[octet], sbit, width);

                mBitoffset += width;
                remaining -= width;
            }

            System.out.printf(" - Sliced off %d bits: %x\n", bits, value);
            return value;
        }

        private static int getBits(byte b, int b0, int width) {
            int mask = (1 << width) - 1;
            return (b >> (Byte.SIZE - b0 - width)) & mask;
        }
    }

    private static class BitStream {

        private final byte[] data;
        private int bitOffset;              // bit 0 is MSB of data[0]

        private BitStream(int octets) {
            data = new byte[octets];
        }

        private void append(long value, int width) {
            System.out.printf("Appending %x:%d\n", value, width);
            for (int sbit = width - 1; sbit >= 0; ) {
                int b0 = bitOffset >>> 3;
                int dbit = bitOffset & 0x7;

                int shr = sbit - 7 + dbit;
                int dmask = 0xff >>> dbit;

                if (shr >= 0) {
                    data[b0] = (byte) ((data[b0] & ~dmask) | ((value >>> shr) & dmask));
                    bitOffset += Byte.SIZE - dbit;
                    sbit -= Byte.SIZE - dbit;
                } else {
                    data[b0] = (byte) ((data[b0] & ~dmask) | ((value << -shr) & dmask));
                    bitOffset += sbit + 1;
                    sbit = -1;
                }
            }
        }

        private byte[] getOctets() {
            return data;
        }
    }

    static double fixToFloat(long value, int fractionSize, int width) {
        long sign = 1L << (width - 1);
        if ((value & sign) != 0) {
            value = -value;
            return -(double) (value & (sign - 1)) / (double) (1L << fractionSize);
        } else {
            return (double) (value & (sign - 1)) / (double) (1L << fractionSize);
        }
    }

    private static long floatToFix(double value, int fractionSize, int width) {
        return Math.round(value * (1L << fractionSize)) & ((1L << width) - 1);
    }

    private static final double LOG2_FACTOR = 1.0 / Math.log(2.0);

    /**
     * Convert an absolute variance value into absolute resolution representation,
     * where the variance = 2^resolution.
     *
     * @param variance The absolute variance
     * @return the absolute resolution.
     */
    private static int getResolution(double variance) {
        return (int) Math.ceil(Math.log(variance) * LOG2_FACTOR);
    }

    /**
     * Convert an absolute resolution, into the "number of significant bits" for the given fixed
     * point notation as defined in RFC-3825 and refined in RFC-6225.
     *
     * @param resolution   absolute resolution given as 2^resolution.
     * @param fieldWidth   Full width of the fixed point number used to represent the value.
     * @param fractionBits Number of fraction bits in the fixed point number used to represent the
     *                     value.
     * @return The number of "significant bits".
     */
    private static int absResolutionToBits(int resolution, int fieldWidth, int fractionBits) {
        return fieldWidth - fractionBits - 1 - resolution;
    }

    /**
     * Convert the protocol definition of "number of significant bits" into an absolute resolution.
     *
     * @param bits         The number of "significant bits" from the binary protocol.
     * @param fieldWidth   Full width of the fixed point number used to represent the value.
     * @param fractionBits Number of fraction bits in the fixed point number used to represent the
     *                     value.
     * @return The absolute resolution given as 2^resolution.
     */
    private static int bitsToAbsResolution(long bits, int fieldWidth, int fractionBits) {
        return fieldWidth - fractionBits - 1 - (int) bits;
    }
}
