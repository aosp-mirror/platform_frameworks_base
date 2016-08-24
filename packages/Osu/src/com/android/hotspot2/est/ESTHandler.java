package com.android.hotspot2.est;

import android.net.Network;
import android.util.Base64;
import android.util.Log;

import com.android.hotspot2.OMADMAdapter;
import com.android.hotspot2.asn1.Asn1Class;
import com.android.hotspot2.asn1.Asn1Constructed;
import com.android.hotspot2.asn1.Asn1Decoder;
import com.android.hotspot2.asn1.Asn1ID;
import com.android.hotspot2.asn1.Asn1Integer;
import com.android.hotspot2.asn1.Asn1Object;
import com.android.hotspot2.asn1.Asn1Oid;
import com.android.hotspot2.asn1.OidMappings;
import com.android.hotspot2.osu.HTTPHandler;
import com.android.hotspot2.osu.OSUSocketFactory;
import com.android.hotspot2.osu.commands.GetCertData;
import com.android.hotspot2.pps.HomeSP;
import com.android.hotspot2.utils.HTTPMessage;
import com.android.hotspot2.utils.HTTPResponse;
import com.android.org.bouncycastle.asn1.ASN1Encodable;
import com.android.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.org.bouncycastle.asn1.ASN1Set;
import com.android.org.bouncycastle.asn1.DERBitString;
import com.android.org.bouncycastle.asn1.DEREncodableVector;
import com.android.org.bouncycastle.asn1.DERIA5String;
import com.android.org.bouncycastle.asn1.DERObjectIdentifier;
import com.android.org.bouncycastle.asn1.DERPrintableString;
import com.android.org.bouncycastle.asn1.DERSet;
import com.android.org.bouncycastle.asn1.x509.Attribute;
import com.android.org.bouncycastle.jce.PKCS10CertificationRequest;
import com.android.org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.KeyManager;
import javax.security.auth.x500.X500Principal;

//import com.android.org.bouncycastle.jce.provider.BouncyCastleProvider;

public class ESTHandler implements AutoCloseable {
    private static final String TAG = "HS2EST";
    private static final int MinRSAKeySize = 2048;

    private static final String CACERT_PATH = "/cacerts";
    private static final String CSR_PATH = "/csrattrs";
    private static final String SIMPLE_ENROLL_PATH = "/simpleenroll";
    private static final String SIMPLE_REENROLL_PATH = "/simplereenroll";

    private final URL mURL;
    private final String mUser;
    private final byte[] mPassword;
    private final OSUSocketFactory mSocketFactory;
    private final OMADMAdapter mOMADMAdapter;

    private final List<X509Certificate> mCACerts = new ArrayList<>();
    private final List<X509Certificate> mClientCerts = new ArrayList<>();
    private PrivateKey mClientKey;

    public ESTHandler(GetCertData certData, Network network, OMADMAdapter omadmAdapter,
                      KeyManager km, KeyStore ks, HomeSP homeSP, int flowType)
            throws IOException, GeneralSecurityException {
        mURL = new URL(certData.getServer());
        mUser = certData.getUserName();
        mPassword = certData.getPassword();
        mSocketFactory = OSUSocketFactory.getSocketFactory(ks, homeSP, flowType,
                network, mURL, km, true);
        mOMADMAdapter = omadmAdapter;
    }

    @Override
    public void close() throws IOException {
    }

    public List<X509Certificate> getCACerts() {
        return mCACerts;
    }

    public List<X509Certificate> getClientCerts() {
        return mClientCerts;
    }

    public PrivateKey getClientKey() {
        return mClientKey;
    }

    private static String indent(int amount) {
        char[] indent = new char[amount * 2];
        Arrays.fill(indent, ' ');
        return new String(indent);
    }

    public void execute(boolean reenroll) throws IOException, GeneralSecurityException {
        URL caURL = new URL(mURL.getProtocol(), mURL.getHost(), mURL.getPort(),
                mURL.getFile() + CACERT_PATH);

        HTTPResponse response;
        try (HTTPHandler httpHandler = new HTTPHandler(StandardCharsets.ISO_8859_1, mSocketFactory,
                mUser, mPassword)) {
            response = httpHandler.doGetHTTP(caURL);

            if (!"application/pkcs7-mime".equals(response.getHeaders().
                    get(HTTPMessage.ContentTypeHeader))) {
                throw new IOException("Unexpected Content-Type: " +
                        response.getHeaders().get(HTTPMessage.ContentTypeHeader));
            }
            ByteBuffer octetBuffer = response.getBinaryPayload();
            Collection<Asn1Object> pkcs7Content1 = Asn1Decoder.decode(octetBuffer);
            for (Asn1Object asn1Object : pkcs7Content1) {
                Log.d(TAG, "---");
                Log.d(TAG, asn1Object.toString());
            }
            Log.d(TAG, CACERT_PATH);

            mCACerts.addAll(unpackPkcs7(octetBuffer));
            for (X509Certificate certificate : mCACerts) {
                Log.d(TAG, "CA-Cert: " + certificate.getSubjectX500Principal());
            }

            /*
            byte[] octets = new byte[octetBuffer.remaining()];
            octetBuffer.duplicate().get(octets);
            for (byte b : octets) {
                System.out.printf("%02x ", b & 0xff);
            }
            Log.d(TAG, );
            */

            /* + BC
            try {
                byte[] octets = new byte[octetBuffer.remaining()];
                octetBuffer.duplicate().get(octets);
                ASN1InputStream asnin = new ASN1InputStream(octets);
                for (int n = 0; n < 100; n++) {
                    ASN1Primitive object = asnin.readObject();
                    if (object == null) {
                        break;
                    }
                    parseObject(object, 0);
                }
            }
            catch (Throwable t) {
                t.printStackTrace();
            }

            Collection<Asn1Object> pkcs7Content = Asn1Decoder.decode(octetBuffer);
            for (Asn1Object asn1Object : pkcs7Content) {
                Log.d(TAG, asn1Object);
            }

            if (pkcs7Content.size() != 1) {
                throw new IOException("Unexpected pkcs 7 container: " + pkcs7Content.size());
            }

            Asn1Constructed pkcs7Root = (Asn1Constructed) pkcs7Content.iterator().next();
            Iterator<Asn1ID> certPath = Arrays.asList(Pkcs7CertPath).iterator();
            Asn1Object certObject = pkcs7Root.findObject(certPath);
            if (certObject == null || certPath.hasNext()) {
                throw new IOException("Failed to find cert; returned object " + certObject +
                        ", path " + (certPath.hasNext() ? "short" : "exhausted"));
            }

            ByteBuffer certOctets = certObject.getPayload();
            if (certOctets == null) {
                throw new IOException("No cert payload in: " + certObject);
            }

            byte[] certBytes = new byte[certOctets.remaining()];
            certOctets.get(certBytes);

            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
            Log.d(TAG, "EST Cert: " + cert);
            */

            URL csrURL = new URL(mURL.getProtocol(), mURL.getHost(), mURL.getPort(),
                    mURL.getFile() + CSR_PATH);
            response = httpHandler.doGetHTTP(csrURL);

            octetBuffer = response.getBinaryPayload();
            byte[] csrData = buildCSR(octetBuffer, mOMADMAdapter, httpHandler);

        /**/
            Collection<Asn1Object> o = Asn1Decoder.decode(ByteBuffer.wrap(csrData));
            Log.d(TAG, "CSR:");
            Log.d(TAG, o.iterator().next().toString());
            Log.d(TAG, "End CSR.");
        /**/

            URL enrollURL = new URL(mURL.getProtocol(), mURL.getHost(), mURL.getPort(),
                    mURL.getFile() + (reenroll ? SIMPLE_REENROLL_PATH : SIMPLE_ENROLL_PATH));
            String data = Base64.encodeToString(csrData, Base64.DEFAULT);
            octetBuffer = httpHandler.exchangeBinary(enrollURL, data, "application/pkcs10");

            Collection<Asn1Object> pkcs7Content2 = Asn1Decoder.decode(octetBuffer);
            for (Asn1Object asn1Object : pkcs7Content2) {
                Log.d(TAG, "---");
                Log.d(TAG, asn1Object.toString());
            }
            mClientCerts.addAll(unpackPkcs7(octetBuffer));
            for (X509Certificate cert : mClientCerts) {
                Log.d(TAG, cert.toString());
            }
        }
    }

    private static final Asn1ID sSEQUENCE = new Asn1ID(Asn1Decoder.TAG_SEQ, Asn1Class.Universal);
    private static final Asn1ID sCTXT0 = new Asn1ID(0, Asn1Class.Context);
    private static final int PKCS7DataVersion = 1;
    private static final int PKCS7SignedDataVersion = 3;

    private static List<X509Certificate> unpackPkcs7(ByteBuffer pkcs7)
            throws IOException, GeneralSecurityException {
        Collection<Asn1Object> pkcs7Content = Asn1Decoder.decode(pkcs7);

        if (pkcs7Content.size() != 1) {
            throw new IOException("Unexpected pkcs 7 container: " + pkcs7Content.size());
        }

        Asn1Object data = pkcs7Content.iterator().next();
        if (!data.isConstructed() || !data.matches(sSEQUENCE)) {
            throw new IOException("Expected SEQ OF, got " + data.toSimpleString());
        } else if (data.getChildren().size() != 2) {
            throw new IOException("Expected content info to have two children, got " +
                    data.getChildren().size());
        }

        Iterator<Asn1Object> children = data.getChildren().iterator();
        Asn1Object contentType = children.next();
        if (!contentType.equals(Asn1Oid.PKCS7SignedData)) {
            throw new IOException("Content not PKCS7 signed data");
        }
        Asn1Object content = children.next();
        if (!content.isConstructed() || !content.matches(sCTXT0)) {
            throw new IOException("Expected [CONTEXT 0] with one child, got " +
                    content.toSimpleString() + ", " + content.getChildren().size());
        }

        Asn1Object signedData = content.getChildren().iterator().next();
        Map<Integer, Asn1Object> itemMap = new HashMap<>();
        for (Asn1Object item : signedData.getChildren()) {
            if (itemMap.put(item.getTag(), item) != null && item.getTag() != Asn1Decoder.TAG_SET) {
                throw new IOException("Duplicate item in SignedData: " + item.toSimpleString());
            }
        }

        Asn1Object versionObject = itemMap.get(Asn1Decoder.TAG_INTEGER);
        if (versionObject == null || !(versionObject instanceof Asn1Integer)) {
            throw new IOException("Bad or missing PKCS7 version: " + versionObject);
        }
        int pkcs7version = (int) ((Asn1Integer) versionObject).getValue();
        Asn1Object innerContentInfo = itemMap.get(Asn1Decoder.TAG_SEQ);
        if (innerContentInfo == null ||
                !innerContentInfo.isConstructed() ||
                !innerContentInfo.matches(sSEQUENCE) ||
                innerContentInfo.getChildren().size() != 1) {
            throw new IOException("Bad or missing PKCS7 contentInfo");
        }
        Asn1Object contentID = innerContentInfo.getChildren().iterator().next();
        if (pkcs7version == PKCS7DataVersion && !contentID.equals(Asn1Oid.PKCS7Data) ||
                pkcs7version == PKCS7SignedDataVersion && !contentID.equals(Asn1Oid.PKCS7SignedData)) {
            throw new IOException("Inner PKCS7 content (" + contentID +
                    ") not expected for version " + pkcs7version);
        }
        Asn1Object certWrapper = itemMap.get(0);
        if (certWrapper == null || !certWrapper.isConstructed() || !certWrapper.matches(sCTXT0)) {
            throw new IOException("Expected [CONTEXT 0], got: " + certWrapper);
        }

        List<X509Certificate> certList = new ArrayList<>(certWrapper.getChildren().size());
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        for (Asn1Object certObject : certWrapper.getChildren()) {
            ByteBuffer certOctets = ((Asn1Constructed) certObject).getEncoding();
            if (certOctets == null) {
                throw new IOException("No cert payload in: " + certObject);
            }
            byte[] certBytes = new byte[certOctets.remaining()];
            certOctets.get(certBytes);

            certList.add((X509Certificate) certFactory.
                    generateCertificate(new ByteArrayInputStream(certBytes)));
        }
        return certList;
    }

    private byte[] buildCSR(ByteBuffer octetBuffer, OMADMAdapter omadmAdapter,
                            HTTPHandler httpHandler) throws IOException, GeneralSecurityException {

        //Security.addProvider(new BouncyCastleProvider());

        Log.d(TAG, "/csrattrs:");
        /*
        byte[] octets = new byte[octetBuffer.remaining()];
        octetBuffer.duplicate().get(octets);
        for (byte b : octets) {
            System.out.printf("%02x ", b & 0xff);
        }
        */
        Collection<Asn1Object> csrs = Asn1Decoder.decode(octetBuffer);
        for (Asn1Object asn1Object : csrs) {
            Log.d(TAG, asn1Object.toString());
        }

        if (csrs.size() != 1) {
            throw new IOException("Unexpected object count in CSR attributes response: " +
                    csrs.size());
        }
        Asn1Object sequence = csrs.iterator().next();
        if (sequence.getClass() != Asn1Constructed.class) {
            throw new IOException("Unexpected CSR attribute container: " + sequence);
        }

        String keyAlgo = null;
        Asn1Oid keyAlgoOID = null;
        String sigAlgo = null;
        String curveName = null;
        Asn1Oid pubCrypto = null;
        int keySize = -1;
        Map<Asn1Oid, ASN1Encodable> idAttributes = new HashMap<>();

        for (Asn1Object child : sequence.getChildren()) {
            if (child.getTag() == Asn1Decoder.TAG_OID) {
                Asn1Oid oid = (Asn1Oid) child;
                OidMappings.SigEntry sigEntry = OidMappings.getSigEntry(oid);
                if (sigEntry != null) {
                    sigAlgo = sigEntry.getSigAlgo();
                    keyAlgoOID = sigEntry.getKeyAlgo();
                    keyAlgo = OidMappings.getJCEName(keyAlgoOID);
                } else if (oid.equals(OidMappings.sPkcs9AtChallengePassword)) {
                    byte[] tlsUnique = httpHandler.getTLSUnique();
                    if (tlsUnique != null) {
                        idAttributes.put(oid, new DERPrintableString(
                                Base64.encodeToString(tlsUnique, Base64.DEFAULT)));
                    } else {
                        Log.w(TAG, "Cannot retrieve TLS unique channel binding");
                    }
                }
            } else if (child.getTag() == Asn1Decoder.TAG_SEQ) {
                Asn1Oid oid = null;
                Set<Asn1Oid> oidValues = new HashSet<>();
                List<Asn1Object> values = new ArrayList<>();

                for (Asn1Object attributeSeq : child.getChildren()) {
                    if (attributeSeq.getTag() == Asn1Decoder.TAG_OID) {
                        oid = (Asn1Oid) attributeSeq;
                    } else if (attributeSeq.getTag() == Asn1Decoder.TAG_SET) {
                        for (Asn1Object value : attributeSeq.getChildren()) {
                            if (value.getTag() == Asn1Decoder.TAG_OID) {
                                oidValues.add((Asn1Oid) value);
                            } else {
                                values.add(value);
                            }
                        }
                    }
                }
                if (oid == null) {
                    throw new IOException("Invalid attribute, no OID");
                }
                if (oid.equals(OidMappings.sExtensionRequest)) {
                    for (Asn1Oid subOid : oidValues) {
                        if (OidMappings.isIDAttribute(subOid)) {
                            if (subOid.equals(OidMappings.sMAC)) {
                                idAttributes.put(subOid, new DERIA5String(omadmAdapter.getMAC()));
                            } else if (subOid.equals(OidMappings.sIMEI)) {
                                idAttributes.put(subOid, new DERIA5String(omadmAdapter.getImei()));
                            } else if (subOid.equals(OidMappings.sMEID)) {
                                idAttributes.put(subOid, new DERBitString(omadmAdapter.getMeid()));
                            } else if (subOid.equals(OidMappings.sDevID)) {
                                idAttributes.put(subOid,
                                        new DERPrintableString(omadmAdapter.getDevID()));
                            }
                        }
                    }
                } else if (OidMappings.getCryptoID(oid) != null) {
                    pubCrypto = oid;
                    if (!values.isEmpty()) {
                        for (Asn1Object value : values) {
                            if (value.getTag() == Asn1Decoder.TAG_INTEGER) {
                                keySize = (int) ((Asn1Integer) value).getValue();
                            }
                        }
                    }
                    if (oid.equals(OidMappings.sAlgo_EC)) {
                        if (oidValues.isEmpty()) {
                            throw new IOException("No ECC curve name provided");
                        }
                        for (Asn1Oid value : oidValues) {
                            curveName = OidMappings.getJCEName(value);
                            if (curveName != null) {
                                break;
                            }
                        }
                        if (curveName == null) {
                            throw new IOException("Found no ECC curve for " + oidValues);
                        }
                    }
                }
            }
        }

        if (keyAlgoOID == null) {
            throw new IOException("No public key algorithm specified");
        }
        if (pubCrypto != null && !pubCrypto.equals(keyAlgoOID)) {
            throw new IOException("Mismatching key algorithms");
        }

        if (keyAlgoOID.equals(OidMappings.sAlgo_RSA)) {
            if (keySize < MinRSAKeySize) {
                if (keySize >= 0) {
                    Log.i(TAG, "Upgrading suggested RSA key size from " +
                            keySize + " to " + MinRSAKeySize);
                }
                keySize = MinRSAKeySize;
            }
        }

        Log.d(TAG, String.format("pub key '%s', signature '%s', ECC curve '%s', id-atts %s",
                keyAlgo, sigAlgo, curveName, idAttributes));

        /*
          Ruckus:
            SEQUENCE:
              OID=1.2.840.113549.1.1.11 (algo_id_sha256WithRSAEncryption)

          RFC-7030:
            SEQUENCE:
              OID=1.2.840.113549.1.9.7 (challengePassword)
              SEQUENCE:
                OID=1.2.840.10045.2.1 (algo_id_ecPublicKey)
                SET:
                  OID=1.3.132.0.34 (secp384r1)
              SEQUENCE:
                OID=1.2.840.113549.1.9.14 (extensionRequest)
                SET:
                  OID=1.3.6.1.1.1.1.22 (mac-address)
              OID=1.2.840.10045.4.3.3 (eccdaWithSHA384)

              1L, 3L, 6L, 1L, 1L, 1L, 1L, 22
         */

        // ECC Does not appear to be supported currently
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(keyAlgo);
        if (curveName != null) {
            AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance(keyAlgo);
            algorithmParameters.init(new ECNamedCurveGenParameterSpec(curveName));
            kpg.initialize(algorithmParameters
                    .getParameterSpec(ECNamedCurveGenParameterSpec.class));
        } else {
            kpg.initialize(keySize);
        }
        KeyPair kp = kpg.generateKeyPair();

        X500Principal subject = new X500Principal("CN=Android, O=Google, C=US");

        mClientKey = kp.getPrivate();

        // !!! Map the idAttributes into an ASN1Set of values to pass to
        // the PKCS10CertificationRequest - this code is using outdated BC classes and
        // has *not* been tested.
        ASN1Set attributes;
        if (!idAttributes.isEmpty()) {
            ASN1EncodableVector payload = new DEREncodableVector();
            for (Map.Entry<Asn1Oid, ASN1Encodable> entry : idAttributes.entrySet()) {
                DERObjectIdentifier type = new DERObjectIdentifier(entry.getKey().toOIDString());
                ASN1Set values = new DERSet(entry.getValue());
                Attribute attribute = new Attribute(type, values);
                payload.add(attribute);
            }
            attributes = new DERSet(payload);
        } else {
            attributes = null;
        }

        return new PKCS10CertificationRequest(sigAlgo, subject, kp.getPublic(),
                attributes, mClientKey).getEncoded();
    }
}
