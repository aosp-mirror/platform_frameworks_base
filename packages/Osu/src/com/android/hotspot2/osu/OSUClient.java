package com.android.hotspot2.osu;

/*
 * policy-server.r2-testbed             IN      A       10.123.107.107
 * remediation-server.r2-testbed        IN      A       10.123.107.107
 * subscription-server.r2-testbed       IN      A       10.123.107.107
 * www.r2-testbed                       IN      A       10.123.107.107
 * osu-server.r2-testbed-rks            IN      A       10.123.107.107
 * policy-server.r2-testbed-rks         IN      A       10.123.107.107
 * remediation-server.r2-testbed-rks    IN      A       10.123.107.107
 * subscription-server.r2-testbed-rks   IN      A       10.123.107.107
 */

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.util.Log;

import com.android.hotspot2.OMADMAdapter;
import com.android.hotspot2.est.ESTHandler;
import com.android.hotspot2.flow.OSUInfo;
import com.android.hotspot2.flow.PlatformAdapter;
import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.OMANode;
import com.android.hotspot2.osu.commands.BrowserURI;
import com.android.hotspot2.osu.commands.ClientCertInfo;
import com.android.hotspot2.osu.commands.GetCertData;
import com.android.hotspot2.osu.commands.MOData;
import com.android.hotspot2.osu.service.RedirectListener;
import com.android.hotspot2.pps.Credential;
import com.android.hotspot2.pps.HomeSP;
import com.android.hotspot2.pps.UpdateInfo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManager;

public class OSUClient {
    private static final String TAG = "OSUCLT";

    private final OSUInfo mOSUInfo;
    private final URL mURL;
    private final KeyStore mKeyStore;
    private final Context mContext;
    private volatile HTTPHandler mHTTPHandler;
    private volatile RedirectListener mRedirectListener;

    public OSUClient(OSUInfo osuInfo, KeyStore ks, Context context) throws MalformedURLException {
        mOSUInfo = osuInfo;
        mURL = new URL(osuInfo.getOSUProvider().getOSUServer());
        mKeyStore = ks;
        mContext = context;
    }

    public OSUClient(String osu, KeyStore ks, Context context) throws MalformedURLException {
        mOSUInfo = null;
        mURL = new URL(osu);
        mKeyStore = ks;
        mContext = context;
    }

    public OSUInfo getOSUInfo() {
        return mOSUInfo;
    }

    public void provision(PlatformAdapter platformAdapter, Network network, KeyManager km)
            throws IOException, GeneralSecurityException {
        try (HTTPHandler httpHandler = new HTTPHandler(StandardCharsets.UTF_8,
                OSUSocketFactory.getSocketFactory(mKeyStore, null,
                        OSUFlowManager.FlowType.Provisioning, network, mURL, km, true))) {

            mHTTPHandler = httpHandler;

            SPVerifier spVerifier = new SPVerifier(mOSUInfo);
            spVerifier.verify(httpHandler.getOSUCertificate(mURL));

            URL redirectURL = prepareUserInput(platformAdapter,
                    mOSUInfo.getName(Locale.getDefault()));
            OMADMAdapter omadmAdapter = getOMADMAdapter();

            String regRequest = SOAPBuilder.buildPostDevDataResponse(RequestReason.SubRegistration,
                    null,
                    redirectURL.toString(),
                    omadmAdapter.getMO(OMAConstants.DevInfoURN),
                    omadmAdapter.getMO(OMAConstants.DevDetailURN));
            Log.d(TAG, "Registration request: " + regRequest);
            OSUResponse osuResponse = httpHandler.exchangeSOAP(mURL, regRequest);

            Log.d(TAG, "Response: " + osuResponse);
            if (osuResponse.getMessageType() != OSUMessageType.PostDevData) {
                throw new IOException("Expected a PostDevDataResponse");
            }
            PostDevDataResponse regResponse = (PostDevDataResponse) osuResponse;
            String sessionID = regResponse.getSessionID();
            if (regResponse.getExecCommand() == ExecCommand.UseClientCertTLS) {
                ClientCertInfo ccInfo = (ClientCertInfo) regResponse.getCommandData();
                if (ccInfo.doesAcceptMfgCerts()) {
                    throw new IOException("Mfg certs are not supported in Android");
                } else if (ccInfo.doesAcceptProviderCerts()) {
                    ((WiFiKeyManager) km).enableClientAuth(ccInfo.getIssuerNames());
                    httpHandler.renegotiate(null, null);
                } else {
                    throw new IOException("Neither manufacturer nor provider cert specified");
                }
                regRequest = SOAPBuilder.buildPostDevDataResponse(RequestReason.SubRegistration,
                        sessionID,
                        redirectURL.toString(),
                        omadmAdapter.getMO(OMAConstants.DevInfoURN),
                        omadmAdapter.getMO(OMAConstants.DevDetailURN));

                osuResponse = httpHandler.exchangeSOAP(mURL, regRequest);
                if (osuResponse.getMessageType() != OSUMessageType.PostDevData) {
                    throw new IOException("Expected a PostDevDataResponse");
                }
                regResponse = (PostDevDataResponse) osuResponse;
            }

            if (regResponse.getExecCommand() != ExecCommand.Browser) {
                throw new IOException("Expected a launchBrowser command");
            }
            Log.d(TAG, "Exec: " + regResponse.getExecCommand() + ", for '" +
                    regResponse.getCommandData() + "'");

            if (!osuResponse.getSessionID().equals(sessionID)) {
                throw new IOException("Mismatching session IDs");
            }
            String webURL = ((BrowserURI) regResponse.getCommandData()).getURI();

            if (webURL == null) {
                throw new IOException("No web-url");
            } else if (!webURL.contains(sessionID)) {
                throw new IOException("Bad or missing session ID in webURL");
            }

            if (!startUserInput(new URL(webURL), network)) {
                throw new IOException("User session failed");
            }

            Log.d(TAG, " -- Sending user input complete:");
            String userComplete = SOAPBuilder.buildPostDevDataResponse(RequestReason.InputComplete,
                    sessionID, null,
                    omadmAdapter.getMO(OMAConstants.DevInfoURN),
                    omadmAdapter.getMO(OMAConstants.DevDetailURN));
            OSUResponse moResponse1 = httpHandler.exchangeSOAP(mURL, userComplete);
            if (moResponse1.getMessageType() != OSUMessageType.PostDevData) {
                throw new IOException("Bad user input complete response: " + moResponse1);
            }
            PostDevDataResponse provResponse = (PostDevDataResponse) moResponse1;
            GetCertData estData = checkResponse(provResponse);

            Map<OSUCertType, List<X509Certificate>> certs = new HashMap<>();
            PrivateKey clientKey = null;

            MOData moData;
            if (estData == null) {
                moData = (MOData) provResponse.getCommandData();
            } else {
                try (ESTHandler estHandler = new ESTHandler((GetCertData) provResponse.
                        getCommandData(), network, getOMADMAdapter(),
                        km, mKeyStore, null, OSUFlowManager.FlowType.Provisioning)) {
                    estHandler.execute(false);
                    certs.put(OSUCertType.CA, estHandler.getCACerts());
                    certs.put(OSUCertType.Client, estHandler.getClientCerts());
                    clientKey = estHandler.getClientKey();
                }

                Log.d(TAG, " -- Sending provisioning cert enrollment complete:");
                String certComplete =
                        SOAPBuilder.buildPostDevDataResponse(RequestReason.CertEnrollmentComplete,
                                sessionID, null,
                                omadmAdapter.getMO(OMAConstants.DevInfoURN),
                                omadmAdapter.getMO(OMAConstants.DevDetailURN));
                OSUResponse moResponse2 = httpHandler.exchangeSOAP(mURL, certComplete);
                if (moResponse2.getMessageType() != OSUMessageType.PostDevData) {
                    throw new IOException("Bad cert enrollment complete response: " + moResponse2);
                }
                PostDevDataResponse provComplete = (PostDevDataResponse) moResponse2;
                if (provComplete.getStatus() != OSUStatus.ProvComplete ||
                        provComplete.getOSUCommand() != OSUCommandID.AddMO) {
                    throw new IOException("Expected addMO: " + provComplete);
                }
                moData = (MOData) provComplete.getCommandData();
            }

            // !!! How can an ExchangeComplete be sent w/o knowing the fate of the certs???
            String updateResponse = SOAPBuilder.buildUpdateResponse(sessionID, null);
            Log.d(TAG, " -- Sending updateResponse:");
            OSUResponse exComplete = httpHandler.exchangeSOAP(mURL, updateResponse);
            Log.d(TAG, "exComplete response: " + exComplete);
            if (exComplete.getMessageType() != OSUMessageType.ExchangeComplete) {
                throw new IOException("Expected ExchangeComplete: " + exComplete);
            } else if (exComplete.getStatus() != OSUStatus.ExchangeComplete) {
                throw new IOException("Bad ExchangeComplete status: " + exComplete);
            }

            retrieveCerts(moData.getMOTree().getRoot(), certs, network, km, mKeyStore);
            platformAdapter.provisioningComplete(mOSUInfo, moData, certs, clientKey, network);
        }
    }

    public void remediate(PlatformAdapter platformAdapter, Network network, KeyManager km,
            HomeSP homeSP, OSUFlowManager.FlowType flowType)
            throws IOException, GeneralSecurityException {
        try (HTTPHandler httpHandler = createHandler(network, homeSP, km, flowType)) {

            mHTTPHandler = httpHandler;

            URL redirectURL = prepareUserInput(platformAdapter, homeSP.getFriendlyName());
            OMADMAdapter omadmAdapter = getOMADMAdapter();

            String regRequest = SOAPBuilder.buildPostDevDataResponse(RequestReason.SubRemediation,
                    null,
                    redirectURL.toString(),
                    omadmAdapter.getMO(OMAConstants.DevInfoURN),
                    omadmAdapter.getMO(OMAConstants.DevDetailURN));

            OSUResponse serverResponse = httpHandler.exchangeSOAP(mURL, regRequest);
            if (serverResponse.getMessageType() != OSUMessageType.PostDevData) {
                throw new IOException("Expected a PostDevDataResponse");
            }
            String sessionID = serverResponse.getSessionID();

            PostDevDataResponse pddResponse = (PostDevDataResponse) serverResponse;
            Log.d(TAG, "Remediation response: " + pddResponse);

            Map<OSUCertType, List<X509Certificate>> certs = null;
            PrivateKey clientKey = null;

            if (pddResponse.getStatus() != OSUStatus.RemediationComplete) {
                if (pddResponse.getExecCommand() == ExecCommand.UploadMO) {
                    String ulMessage = SOAPBuilder.buildPostDevDataResponse(RequestReason.MOUpload,
                            null,
                            redirectURL.toString(),
                            omadmAdapter.getMO(OMAConstants.DevInfoURN),
                            omadmAdapter.getMO(OMAConstants.DevDetailURN),
                            platformAdapter.getMOTree(homeSP));

                    Log.d(TAG, "Upload MO: " + ulMessage);

                    OSUResponse ulResponse = httpHandler.exchangeSOAP(mURL, ulMessage);
                    if (ulResponse.getMessageType() != OSUMessageType.PostDevData) {
                        throw new IOException("Expected a PostDevDataResponse to MOUpload");
                    }
                    pddResponse = (PostDevDataResponse) ulResponse;
                }

                if (pddResponse.getExecCommand() == ExecCommand.Browser) {
                    if (flowType == OSUFlowManager.FlowType.Policy) {
                        throw new IOException("Browser launch requested in policy flow");
                    }
                    String webURL = ((BrowserURI) pddResponse.getCommandData()).getURI();

                    if (webURL == null) {
                        throw new IOException("No web-url");
                    } else if (!webURL.contains(sessionID)) {
                        throw new IOException("Bad or missing session ID in webURL");
                    }

                    if (!startUserInput(new URL(webURL), network)) {
                        throw new IOException("User session failed");
                    }

                    Log.d(TAG, " -- Sending user input complete:");
                    String userComplete =
                            SOAPBuilder.buildPostDevDataResponse(RequestReason.InputComplete,
                                    sessionID, null,
                                    omadmAdapter.getMO(OMAConstants.DevInfoURN),
                                    omadmAdapter.getMO(OMAConstants.DevDetailURN));

                    OSUResponse udResponse = httpHandler.exchangeSOAP(mURL, userComplete);
                    if (udResponse.getMessageType() != OSUMessageType.PostDevData) {
                        throw new IOException("Bad user input complete response: " + udResponse);
                    }
                    pddResponse = (PostDevDataResponse) udResponse;
                } else if (pddResponse.getExecCommand() == ExecCommand.GetCert) {
                    certs = new HashMap<>();
                    try (ESTHandler estHandler = new ESTHandler((GetCertData) pddResponse.
                            getCommandData(), network, getOMADMAdapter(),
                            km, mKeyStore, homeSP, flowType)) {
                        estHandler.execute(true);
                        certs.put(OSUCertType.CA, estHandler.getCACerts());
                        certs.put(OSUCertType.Client, estHandler.getClientCerts());
                        clientKey = estHandler.getClientKey();
                    }

                    if (httpHandler.isHTTPAuthPerformed()) {        // 8.4.3.6
                        httpHandler.renegotiate(certs, clientKey);
                    }

                    Log.d(TAG, " -- Sending remediation cert enrollment complete:");
                    // 8.4.3.5 in the spec actually prescribes that an update URI is sent here,
                    // but there is no remediation flow that defines user interaction after EST
                    // so for now a null is passed.
                    String certComplete =
                            SOAPBuilder
                                    .buildPostDevDataResponse(RequestReason.CertEnrollmentComplete,
                                            sessionID, null,
                                            omadmAdapter.getMO(OMAConstants.DevInfoURN),
                                            omadmAdapter.getMO(OMAConstants.DevDetailURN));
                    OSUResponse ceResponse = httpHandler.exchangeSOAP(mURL, certComplete);
                    if (ceResponse.getMessageType() != OSUMessageType.PostDevData) {
                        throw new IOException("Bad cert enrollment complete response: "
                                + ceResponse);
                    }
                    pddResponse = (PostDevDataResponse) ceResponse;
                } else {
                    throw new IOException("Unexpected command: " + pddResponse.getExecCommand());
                }
            }

            if (pddResponse.getStatus() != OSUStatus.RemediationComplete) {
                throw new IOException("Expected a PostDevDataResponse to MOUpload");
            }

            Log.d(TAG, "Remediation response: " + pddResponse);

            List<MOData> mods = new ArrayList<>();
            for (OSUCommand command : pddResponse.getCommands()) {
                if (command.getOSUCommand() == OSUCommandID.UpdateNode) {
                    mods.add((MOData) command.getCommandData());
                } else if (command.getOSUCommand() != OSUCommandID.NoMOUpdate) {
                    throw new IOException("Unexpected OSU response: " + command);
                }
            }

            // 1. Machine remediation: Remediation complete + replace node
            // 2a. User remediation with upload: ExecCommand.UploadMO
            // 2b. User remediation without upload: ExecCommand.Browser
            // 3. User remediation only: -> sppPostDevData user input complete
            //
            // 4. Update node
            // 5. -> Update response
            // 6. Exchange complete

            OSUError error = null;

            String updateResponse = SOAPBuilder.buildUpdateResponse(sessionID, error);
            Log.d(TAG, " -- Sending updateResponse:");
            OSUResponse exComplete = httpHandler.exchangeSOAP(mURL, updateResponse);
            Log.d(TAG, "exComplete response: " + exComplete);
            if (exComplete.getMessageType() != OSUMessageType.ExchangeComplete) {
                throw new IOException("Expected ExchangeComplete: " + exComplete);
            } else if (exComplete.getStatus() != OSUStatus.ExchangeComplete) {
                throw new IOException("Bad ExchangeComplete status: " + exComplete);
            }

            // There's a chicken and egg here: If the config is saved before sending update complete
            // the network is lost and the remediation flow fails.
            try {
                platformAdapter.remediationComplete(homeSP, mods, certs, clientKey,
                        flowType == OSUFlowManager.FlowType.Policy);
            } catch (IOException | GeneralSecurityException e) {
                platformAdapter.provisioningFailed(homeSP.getFriendlyName(), e.getMessage());
                error = OSUError.CommandFailed;
            }
        }
    }

    private OMADMAdapter getOMADMAdapter() {
        return OMADMAdapter.getInstance(mContext);
    }

    private URL prepareUserInput(PlatformAdapter platformAdapter, String spName)
            throws IOException {
        mRedirectListener = new RedirectListener(platformAdapter, spName);
        return mRedirectListener.getURL();
    }

    private boolean startUserInput(URL target, Network network)
            throws IOException {
        mRedirectListener.startService();

        Intent intent = new Intent(mContext, OSUWebView.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(OSUWebView.OSU_NETWORK, network);
        intent.putExtra(OSUWebView.OSU_URL, target.toString());
        mContext.startActivity(intent);

        return mRedirectListener.waitForUser();
    }

    public void close(boolean abort) {
        if (mRedirectListener != null) {
            mRedirectListener.abort();
            mRedirectListener = null;
        }
        if (abort) {
            try {
                mHTTPHandler.close();
            } catch (IOException ioe) {
                /**/
            }
        }
    }

    private HTTPHandler createHandler(Network network, HomeSP homeSP, KeyManager km,
            OSUFlowManager.FlowType flowType)
            throws GeneralSecurityException, IOException {
        Credential credential = homeSP.getCredential();

        Log.d(TAG, "Credential method " + credential.getEAPMethod().getEAPMethodID());
        switch (credential.getEAPMethod().getEAPMethodID()) {
            case EAP_TTLS:
                String user;
                byte[] password;
                UpdateInfo subscriptionUpdate;
                if (flowType == OSUFlowManager.FlowType.Policy) {
                    subscriptionUpdate = homeSP.getPolicy() != null ?
                            homeSP.getPolicy().getPolicyUpdate() : null;
                } else {
                    subscriptionUpdate = homeSP.getSubscriptionUpdate();
                }
                if (subscriptionUpdate != null && subscriptionUpdate.getUsername() != null) {
                    user = subscriptionUpdate.getUsername();
                    password = subscriptionUpdate.getPassword() != null ?
                            subscriptionUpdate.getPassword().getBytes(StandardCharsets.UTF_8) :
                            new byte[0];
                } else {
                    user = credential.getUserName();
                    password = credential.getPassword().getBytes(StandardCharsets.UTF_8);
                }
                return new HTTPHandler(StandardCharsets.UTF_8,
                        OSUSocketFactory.getSocketFactory(mKeyStore, homeSP, flowType, network,
                                mURL, km, true), user, password);
            case EAP_TLS:
                return new HTTPHandler(StandardCharsets.UTF_8,
                        OSUSocketFactory.getSocketFactory(mKeyStore, homeSP, flowType, network,
                                mURL, km, true));
            default:
                throw new IOException("Cannot remediate account with " +
                        credential.getEAPMethod().getEAPMethodID());
        }
    }

    private static GetCertData checkResponse(PostDevDataResponse response) throws IOException {
        if (response.getStatus() == OSUStatus.ProvComplete &&
                response.getOSUCommand() == OSUCommandID.AddMO) {
            return null;
        }

        if (response.getOSUCommand() == OSUCommandID.Exec &&
                response.getExecCommand() == ExecCommand.GetCert) {
            return (GetCertData) response.getCommandData();
        } else {
            throw new IOException("Unexpected command: " + response);
        }
    }

    private static final String[] AAACertPath =
            {"PerProviderSubscription", "?", "AAAServerTrustRoot", "*", "CertURL"};
    private static final String[] RemdCertPath =
            {"PerProviderSubscription", "?", "SubscriptionUpdate", "TrustRoot", "CertURL"};
    private static final String[] PolicyCertPath =
            {"PerProviderSubscription", "?", "Policy", "PolicyUpdate", "TrustRoot", "CertURL"};

    private static void retrieveCerts(OMANode ppsRoot,
                                      Map<OSUCertType, List<X509Certificate>> certs,
                                      Network network, KeyManager km, KeyStore ks)
            throws GeneralSecurityException, IOException {

        List<X509Certificate> aaaCerts = getCerts(ppsRoot, AAACertPath, network, km, ks);
        certs.put(OSUCertType.AAA, aaaCerts);
        certs.put(OSUCertType.Remediation, getCerts(ppsRoot, RemdCertPath, network, km, ks));
        certs.put(OSUCertType.Policy, getCerts(ppsRoot, PolicyCertPath, network, km, ks));
    }

    private static List<X509Certificate> getCerts(OMANode ppsRoot, String[] path, Network network,
                                                  KeyManager km, KeyStore ks)
            throws GeneralSecurityException, IOException {
        List<String> urls = new ArrayList<>();
        getCertURLs(ppsRoot, Arrays.asList(path).iterator(), urls);
        Log.d(TAG, Arrays.toString(path) + ": " + urls);

        List<X509Certificate> certs = new ArrayList<>(urls.size());
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        for (String urlString : urls) {
            URL url = new URL(urlString);
            HTTPHandler httpHandler = new HTTPHandler(StandardCharsets.UTF_8,
                    OSUSocketFactory.getSocketFactory(ks, null,
                            OSUFlowManager.FlowType.Provisioning, network, url, km, false));

            certs.add((X509Certificate) certFactory.generateCertificate(httpHandler.doGet(url)));
        }
        return certs;
    }

    private static void getCertURLs(OMANode root, Iterator<String> path, List<String> urls)
            throws IOException {

        String name = path.next();
        // Log.d(TAG, "Pulling '" + name + "' out of '" + root.getName() + "'");
        Collection<OMANode> nodes = null;
        switch (name) {
            case "?":
                for (OMANode node : root.getChildren()) {
                    if (!node.isLeaf()) {
                        nodes = Collections.singletonList(node);
                        break;
                    }
                }
                break;
            case "*":
                nodes = root.getChildren();
                break;
            default:
                nodes = Collections.singletonList(root.getChild(name));
                break;
        }

        if (nodes == null) {
            throw new IllegalArgumentException("No matching node in " + root.getName()
                    + " for " + name);
        }

        for (OMANode node : nodes) {
            if (path.hasNext()) {
                getCertURLs(node, path, urls);
            } else {
                urls.add(node.getValue());
            }
        }
    }
}
