/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.locksettings.recoverablekeystore.certificate.CertUtils;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPath;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class TestData {

    private static final String KEY_ALGORITHM = "AES";
    private static final long DEFAULT_SERIAL = 1000;
    private static final String CERT_PATH_ENCODING = "PkiPath";

    private static final String CERT_PATH_1_BASE64 = ""
            + "MIIIPzCCBS8wggMXoAMCAQICAhAAMA0GCSqGSIb3DQEBCwUAMCAxHjAcBgNVBAMM"
            + "FUdvb2dsZSBDcnlwdEF1dGhWYXVsdDAeFw0xODAyMDMwMDQyMDNaFw0yODAyMDEw"
            + "MDQyMDNaMC0xKzApBgNVBAMMIkdvb2dsZSBDcnlwdEF1dGhWYXVsdCBJbnRlcm1l"
            + "ZGlhdGUwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDckHib0X6rQyDq"
            + "k4519b5du0OrCPk30XXKwz+Hz5y4cGZaWKGcHOHWS2X9YApRzO00/EbvFkWVUTVG"
            + "27wJ54V+C3HHSOAUWHhEgfFWvvHwfn9HTDx1BEk79aQqJ7DuJ06Sn/WOiMtKVAT5"
            + "6Mi8mekBxpMOrdZqwlcLrUVsZxEHsw5/ceZu4cSWzc7SzlnbNK1cCgyRDGqWf6Gp"
            + "3hGE86kUOtM1i95RgUIpw+w/z0wxpF6kIyQTjK+KjiYH/RBOJIEcm6sSWZlMotKL"
            + "Sn2lhf+XL8yUxExIHTosfeb077QWW4w2BB2NZM4wPAO3w4aw33FNigDQc2SQYmnU"
            + "EYmIcD8kx77+JWCgCxBJc2zTHXtBxWuXAQ+iegt8RO+QD97pd6XKM9xPsAOkcWLp"
            + "79o+AJol4P5fwvgYM69mM4lwH12v86RI4aptPQOag0KDIHXyKbjaQyAgv30l4KkD"
            + "pf2uWODhOOTwNbVPYUm3sYUlhBcbyhTk8YqN9sPU4QAao5sKTAYZgB/mlheQypTU"
            + "wyvqz6bRzGehVB3ltP9gCyKdI04VXEUuUBWk3STyV2REQen5/LKAns6v11Cz22Zr"
            + "EdCvNLgetnyV7CJsOa/wD/GiUWL2Ta7pzshi9ahJqrrcNPRbAzOLcNKZkFexhzPp"
            + "onuo/pNrcaRda1frepXxVkmbsgOULwIDAQABo2YwZDAdBgNVHQ4EFgQUd6md2hCP"
            + "lmf3VkEX5FfDxKBLbaAwHwYDVR0jBBgwFoAUm2X66jmB+eBCaZHSjGYzHM/x6fgw"
            + "EgYDVR0TAQH/BAgwBgEB/wIBATAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQEL"
            + "BQADggIBAFgShhuW+WVTowN080PLf0TWPlHACHHUPghf7rFGxgUjJypCloE84Beg"
            + "3ROpP5l19CDqZ9OyPzA1z6VAzeGXyFhZvby7G2tZDBRP/v0u8pnSAdC5F8l8Vh2Y"
            + "GdgE3sZD25vpdBi7P0Ef6LYQetOJXn86PqgmgW1F6lzxDjKCsi9kpeU0AWwDdOVg"
            + "748wku50o8UEzsVwxzFd9toGlge/nn3FH5J7EuGzAlFwToHqpwTVEegaAd0l9mr5"
            + "+rS7Urd3X80BHDqCBcXE7Uqbtzw5Y+lmowMCnW0kFN02dC9dLt2c9IxC+9sPIA5e"
            + "TkrZBkrkTVRGLj2r29j7nC9m5VaKcBqcLZDWy8pRna8yaZprgNdE8d/WTY9nVsic"
            + "09N8zNF5Q0bhhWa3QonlB9XW5ZqDguiclvn+5TtREzSAtSOyxM+gfG3l0wjOywIk"
            + "1aFa52RaqAWPL67KOM6G3vKNpMnW5hrmHrijuKxiarGIoZfkZMR5ijK0uFgv3/p6"
            + "NHL/YQBaHJJhkKet5ThiPxwW9+1k/ZcXVeY26Xh+22Gp/8to7ZW8guPPiN1hfpD+"
            + "7f1IdSmHDrsZQQ7bfzV0bppsyNNB7e2Ecyw+GQny27nytBLJDGdRBurbwQvzppQO"
            + "6Qmlk0rfCszh7bGCoCQNxXmuDsQ5BC+pQUqJplTqds1smyi29xs3MIIDCDCB8aAD"
            + "AgECAgYBYVkuU0cwDQYJKoZIhvcNAQELBQAwLTErMCkGA1UEAwwiR29vZ2xlIENy"
            + "eXB0QXV0aFZhdWx0IEludGVybWVkaWF0ZTAeFw0xODAyMDIwMTAxMDNaFw0yMDAy"
            + "MDMwMTAxMDNaMCkxJzAlBgNVBAMTHkdvb2dsZSBDcnlwdEF1dGhWYXVsdCBJbnN0"
            + "YW5jZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLgAERiYHfButJT+htocB40B"
            + "tDr2jdxh0EZJlQ8QhpMkZuA/0t/zeSAdkVWw5b16izJ9JVOi/KVl4b0hRH54Uvow"
            + "DQYJKoZIhvcNAQELBQADggIBAJ3PM4GNTNYzMr8E/IGsWZkLx9ARAALqBXz7As59"
            + "F8y5UcLMqkXD/ewOfBZgF5VzjlAePyE/wSw0wc3xzvrDVVDiZaMBW1DVtSlbn25q"
            + "00m00mmcUeyyMc7vuRkPoDshIMQTc8+U3yyYsVScSV+B4TvSx6wPZ9FpwnSPjVPD"
            + "2GkqeMTWszuxNVEWq0wmm0K5lMaX0hfiak+4/IZxOPPGIg2py1KLA/H2gdyeqyJR"
            + "cAsyEkfwLlushR5T9abSiPsIRcYoX8Ck8Lt+gQ7RCMefnm8CoOBKIfcjuV4PGOoe"
            + "Xrq57VR5SsOeT07bL+D7B+mohYFI1v2G3WClAE8XgM3q8NoFFvaYmoi0+UcTduil"
            + "47qvozjdNmjRAgu5j6vMKXEdG5Rqsja8hy0LG1hwfnR0gNiwcZ5Le3GyFnwH1Igq"
            + "vsGOUM0ohnDUAU0zJY7nG0QYrDYe5/QPRNhWDpYkwHDiqcG28wIQCOTPAZHU2EoS"
            + "KjSqEG2l0S5JPcor2BEde9ikSkcmK8foxlOHIdFn+n7RNF3bSEfKn1IOuXoqPidm"
            + "eBQLevqG8KTy/C9CHqlaCNlpbIA9h+WVfsjm2s6JXBu0YbcfoIbJAmSuZVeqB/+Z"
            + "Vvpfiad/jQWzY49fRnsSmV7VveTFPGtJxC89EadbMAinMZo+72u59319RqN5wsP2"
            + "Zus8";
    private static String CERT_PATH_2_BASE64 = ""
            + "MIIFMzCCBS8wggMXoAMCAQICAhAAMA0GCSqGSIb3DQEBCwUAMCAxHjAcBgNVBAMM"
            + "FUdvb2dsZSBDcnlwdEF1dGhWYXVsdDAeFw0xODAyMDMwMDQyMDNaFw0yODAyMDEw"
            + "MDQyMDNaMC0xKzApBgNVBAMMIkdvb2dsZSBDcnlwdEF1dGhWYXVsdCBJbnRlcm1l"
            + "ZGlhdGUwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDckHib0X6rQyDq"
            + "k4519b5du0OrCPk30XXKwz+Hz5y4cGZaWKGcHOHWS2X9YApRzO00/EbvFkWVUTVG"
            + "27wJ54V+C3HHSOAUWHhEgfFWvvHwfn9HTDx1BEk79aQqJ7DuJ06Sn/WOiMtKVAT5"
            + "6Mi8mekBxpMOrdZqwlcLrUVsZxEHsw5/ceZu4cSWzc7SzlnbNK1cCgyRDGqWf6Gp"
            + "3hGE86kUOtM1i95RgUIpw+w/z0wxpF6kIyQTjK+KjiYH/RBOJIEcm6sSWZlMotKL"
            + "Sn2lhf+XL8yUxExIHTosfeb077QWW4w2BB2NZM4wPAO3w4aw33FNigDQc2SQYmnU"
            + "EYmIcD8kx77+JWCgCxBJc2zTHXtBxWuXAQ+iegt8RO+QD97pd6XKM9xPsAOkcWLp"
            + "79o+AJol4P5fwvgYM69mM4lwH12v86RI4aptPQOag0KDIHXyKbjaQyAgv30l4KkD"
            + "pf2uWODhOOTwNbVPYUm3sYUlhBcbyhTk8YqN9sPU4QAao5sKTAYZgB/mlheQypTU"
            + "wyvqz6bRzGehVB3ltP9gCyKdI04VXEUuUBWk3STyV2REQen5/LKAns6v11Cz22Zr"
            + "EdCvNLgetnyV7CJsOa/wD/GiUWL2Ta7pzshi9ahJqrrcNPRbAzOLcNKZkFexhzPp"
            + "onuo/pNrcaRda1frepXxVkmbsgOULwIDAQABo2YwZDAdBgNVHQ4EFgQUd6md2hCP"
            + "lmf3VkEX5FfDxKBLbaAwHwYDVR0jBBgwFoAUm2X66jmB+eBCaZHSjGYzHM/x6fgw"
            + "EgYDVR0TAQH/BAgwBgEB/wIBATAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQEL"
            + "BQADggIBAFgShhuW+WVTowN080PLf0TWPlHACHHUPghf7rFGxgUjJypCloE84Beg"
            + "3ROpP5l19CDqZ9OyPzA1z6VAzeGXyFhZvby7G2tZDBRP/v0u8pnSAdC5F8l8Vh2Y"
            + "GdgE3sZD25vpdBi7P0Ef6LYQetOJXn86PqgmgW1F6lzxDjKCsi9kpeU0AWwDdOVg"
            + "748wku50o8UEzsVwxzFd9toGlge/nn3FH5J7EuGzAlFwToHqpwTVEegaAd0l9mr5"
            + "+rS7Urd3X80BHDqCBcXE7Uqbtzw5Y+lmowMCnW0kFN02dC9dLt2c9IxC+9sPIA5e"
            + "TkrZBkrkTVRGLj2r29j7nC9m5VaKcBqcLZDWy8pRna8yaZprgNdE8d/WTY9nVsic"
            + "09N8zNF5Q0bhhWa3QonlB9XW5ZqDguiclvn+5TtREzSAtSOyxM+gfG3l0wjOywIk"
            + "1aFa52RaqAWPL67KOM6G3vKNpMnW5hrmHrijuKxiarGIoZfkZMR5ijK0uFgv3/p6"
            + "NHL/YQBaHJJhkKet5ThiPxwW9+1k/ZcXVeY26Xh+22Gp/8to7ZW8guPPiN1hfpD+"
            + "7f1IdSmHDrsZQQ7bfzV0bppsyNNB7e2Ecyw+GQny27nytBLJDGdRBurbwQvzppQO"
            + "6Qmlk0rfCszh7bGCoCQNxXmuDsQ5BC+pQUqJplTqds1smyi29xs3";

    private static final String THM_CERT_XML_BEFORE_SERIAL = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<certificates>\n"
                    + "  <metadata>\n"
                    + "    <serial>\n"
                    + "      ";
    private static final String THM_CERT_XML_AFTER_SERIAL = "\n"
            + "    </serial>\n"
            + "    <creation-time>\n"
            + "      1515697631\n"
            + "    </creation-time>\n"
            + "    <refresh-interval>\n"
            + "      2592000\n"
            + "    </refresh-interval>\n"
            + "    <previous>\n"
            + "      <serial>\n"
            + "        0\n"
            + "      </serial>\n"
            + "      <hash>\n"
            + "        47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\n"
            + "      </hash>\n"
            + "    </previous>\n"
            + "  </metadata>\n"
            + "  <intermediates>\n"
            + "    <cert>\n"
            + "      MIIFLzCCAxegAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVR29v\n"
            + "      Z2xlIENyeXB0QXV0aFZhdWx0MB4XDTE4MDIwMzAwNDIwM1oXDTI4MDIwMTAwNDIw\n"
            + "      M1owLTErMCkGA1UEAwwiR29vZ2xlIENyeXB0QXV0aFZhdWx0IEludGVybWVkaWF0\n"
            + "      ZTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANyQeJvRfqtDIOqTjnX1\n"
            + "      vl27Q6sI+TfRdcrDP4fPnLhwZlpYoZwc4dZLZf1gClHM7TT8Ru8WRZVRNUbbvAnn\n"
            + "      hX4LccdI4BRYeESB8Va+8fB+f0dMPHUESTv1pConsO4nTpKf9Y6Iy0pUBPnoyLyZ\n"
            + "      6QHGkw6t1mrCVwutRWxnEQezDn9x5m7hxJbNztLOWds0rVwKDJEMapZ/oaneEYTz\n"
            + "      qRQ60zWL3lGBQinD7D/PTDGkXqQjJBOMr4qOJgf9EE4kgRybqxJZmUyi0otKfaWF\n"
            + "      /5cvzJTETEgdOix95vTvtBZbjDYEHY1kzjA8A7fDhrDfcU2KANBzZJBiadQRiYhw\n"
            + "      PyTHvv4lYKALEElzbNMde0HFa5cBD6J6C3xE75AP3ul3pcoz3E+wA6RxYunv2j4A\n"
            + "      miXg/l/C+Bgzr2YziXAfXa/zpEjhqm09A5qDQoMgdfIpuNpDICC/fSXgqQOl/a5Y\n"
            + "      4OE45PA1tU9hSbexhSWEFxvKFOTxio32w9ThABqjmwpMBhmAH+aWF5DKlNTDK+rP\n"
            + "      ptHMZ6FUHeW0/2ALIp0jThVcRS5QFaTdJPJXZERB6fn8soCezq/XULPbZmsR0K80\n"
            + "      uB62fJXsImw5r/AP8aJRYvZNrunOyGL1qEmqutw09FsDM4tw0pmQV7GHM+mie6j+\n"
            + "      k2txpF1rV+t6lfFWSZuyA5QvAgMBAAGjZjBkMB0GA1UdDgQWBBR3qZ3aEI+WZ/dW\n"
            + "      QRfkV8PEoEttoDAfBgNVHSMEGDAWgBSbZfrqOYH54EJpkdKMZjMcz/Hp+DASBgNV\n"
            + "      HRMBAf8ECDAGAQH/AgEBMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOC\n"
            + "      AgEAWBKGG5b5ZVOjA3TzQ8t/RNY+UcAIcdQ+CF/usUbGBSMnKkKWgTzgF6DdE6k/\n"
            + "      mXX0IOpn07I/MDXPpUDN4ZfIWFm9vLsba1kMFE/+/S7ymdIB0LkXyXxWHZgZ2ATe\n"
            + "      xkPbm+l0GLs/QR/othB604lefzo+qCaBbUXqXPEOMoKyL2Sl5TQBbAN05WDvjzCS\n"
            + "      7nSjxQTOxXDHMV322gaWB7+efcUfknsS4bMCUXBOgeqnBNUR6BoB3SX2avn6tLtS\n"
            + "      t3dfzQEcOoIFxcTtSpu3PDlj6WajAwKdbSQU3TZ0L10u3Zz0jEL72w8gDl5OStkG\n"
            + "      SuRNVEYuPavb2PucL2blVopwGpwtkNbLylGdrzJpmmuA10Tx39ZNj2dWyJzT03zM\n"
            + "      0XlDRuGFZrdCieUH1dblmoOC6JyW+f7lO1ETNIC1I7LEz6B8beXTCM7LAiTVoVrn\n"
            + "      ZFqoBY8vrso4zobe8o2kydbmGuYeuKO4rGJqsYihl+RkxHmKMrS4WC/f+no0cv9h\n"
            + "      AFockmGQp63lOGI/HBb37WT9lxdV5jbpeH7bYan/y2jtlbyC48+I3WF+kP7t/Uh1\n"
            + "      KYcOuxlBDtt/NXRummzI00Ht7YRzLD4ZCfLbufK0EskMZ1EG6tvBC/OmlA7pCaWT\n"
            + "      St8KzOHtsYKgJA3Fea4OxDkEL6lBSommVOp2zWybKLb3Gzc=\n"
            + "    </cert>\n"
            + "  </intermediates>\n"
            + "  <endpoints>\n"
            + "    <cert>\n"
            + "      MIIDCDCB8aADAgECAgYBYVkuU0cwDQYJKoZIhvcNAQELBQAwLTErMCkGA1UEAwwi\n"
            + "      R29vZ2xlIENyeXB0QXV0aFZhdWx0IEludGVybWVkaWF0ZTAeFw0xODAyMDIwMTAx\n"
            + "      MDNaFw0yMDAyMDMwMTAxMDNaMCkxJzAlBgNVBAMTHkdvb2dsZSBDcnlwdEF1dGhW\n"
            + "      YXVsdCBJbnN0YW5jZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLgAERiYHfBu\n"
            + "      tJT+htocB40BtDr2jdxh0EZJlQ8QhpMkZuA/0t/zeSAdkVWw5b16izJ9JVOi/KVl\n"
            + "      4b0hRH54UvowDQYJKoZIhvcNAQELBQADggIBAJ3PM4GNTNYzMr8E/IGsWZkLx9AR\n"
            + "      AALqBXz7As59F8y5UcLMqkXD/ewOfBZgF5VzjlAePyE/wSw0wc3xzvrDVVDiZaMB\n"
            + "      W1DVtSlbn25q00m00mmcUeyyMc7vuRkPoDshIMQTc8+U3yyYsVScSV+B4TvSx6wP\n"
            + "      Z9FpwnSPjVPD2GkqeMTWszuxNVEWq0wmm0K5lMaX0hfiak+4/IZxOPPGIg2py1KL\n"
            + "      A/H2gdyeqyJRcAsyEkfwLlushR5T9abSiPsIRcYoX8Ck8Lt+gQ7RCMefnm8CoOBK\n"
            + "      IfcjuV4PGOoeXrq57VR5SsOeT07bL+D7B+mohYFI1v2G3WClAE8XgM3q8NoFFvaY\n"
            + "      moi0+UcTduil47qvozjdNmjRAgu5j6vMKXEdG5Rqsja8hy0LG1hwfnR0gNiwcZ5L\n"
            + "      e3GyFnwH1IgqvsGOUM0ohnDUAU0zJY7nG0QYrDYe5/QPRNhWDpYkwHDiqcG28wIQ\n"
            + "      COTPAZHU2EoSKjSqEG2l0S5JPcor2BEde9ikSkcmK8foxlOHIdFn+n7RNF3bSEfK\n"
            + "      n1IOuXoqPidmeBQLevqG8KTy/C9CHqlaCNlpbIA9h+WVfsjm2s6JXBu0YbcfoIbJ\n"
            + "      AmSuZVeqB/+ZVvpfiad/jQWzY49fRnsSmV7VveTFPGtJxC89EadbMAinMZo+72u5\n"
            + "      9319RqN5wsP2Zus8\n"
            + "    </cert>\n"
            + "  </endpoints>\n"
            + "</certificates>\n";
    private static final String THM_SIG_XML = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<signature>\n"
            + "  <intermediates>\n"
            + "  </intermediates>\n"
            + "  <certificate>\n"
            + "    MIIFLzCCAxegAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVR29v\n"
            + "    Z2xlIENyeXB0QXV0aFZhdWx0MB4XDTE4MDIwMzAwNDIwM1oXDTI4MDIwMTAwNDIw\n"
            + "    M1owLTErMCkGA1UEAwwiR29vZ2xlIENyeXB0QXV0aFZhdWx0IEludGVybWVkaWF0\n"
            + "    ZTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANyQeJvRfqtDIOqTjnX1\n"
            + "    vl27Q6sI+TfRdcrDP4fPnLhwZlpYoZwc4dZLZf1gClHM7TT8Ru8WRZVRNUbbvAnn\n"
            + "    hX4LccdI4BRYeESB8Va+8fB+f0dMPHUESTv1pConsO4nTpKf9Y6Iy0pUBPnoyLyZ\n"
            + "    6QHGkw6t1mrCVwutRWxnEQezDn9x5m7hxJbNztLOWds0rVwKDJEMapZ/oaneEYTz\n"
            + "    qRQ60zWL3lGBQinD7D/PTDGkXqQjJBOMr4qOJgf9EE4kgRybqxJZmUyi0otKfaWF\n"
            + "    /5cvzJTETEgdOix95vTvtBZbjDYEHY1kzjA8A7fDhrDfcU2KANBzZJBiadQRiYhw\n"
            + "    PyTHvv4lYKALEElzbNMde0HFa5cBD6J6C3xE75AP3ul3pcoz3E+wA6RxYunv2j4A\n"
            + "    miXg/l/C+Bgzr2YziXAfXa/zpEjhqm09A5qDQoMgdfIpuNpDICC/fSXgqQOl/a5Y\n"
            + "    4OE45PA1tU9hSbexhSWEFxvKFOTxio32w9ThABqjmwpMBhmAH+aWF5DKlNTDK+rP\n"
            + "    ptHMZ6FUHeW0/2ALIp0jThVcRS5QFaTdJPJXZERB6fn8soCezq/XULPbZmsR0K80\n"
            + "    uB62fJXsImw5r/AP8aJRYvZNrunOyGL1qEmqutw09FsDM4tw0pmQV7GHM+mie6j+\n"
            + "    k2txpF1rV+t6lfFWSZuyA5QvAgMBAAGjZjBkMB0GA1UdDgQWBBR3qZ3aEI+WZ/dW\n"
            + "    QRfkV8PEoEttoDAfBgNVHSMEGDAWgBSbZfrqOYH54EJpkdKMZjMcz/Hp+DASBgNV\n"
            + "    HRMBAf8ECDAGAQH/AgEBMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOC\n"
            + "    AgEAWBKGG5b5ZVOjA3TzQ8t/RNY+UcAIcdQ+CF/usUbGBSMnKkKWgTzgF6DdE6k/\n"
            + "    mXX0IOpn07I/MDXPpUDN4ZfIWFm9vLsba1kMFE/+/S7ymdIB0LkXyXxWHZgZ2ATe\n"
            + "    xkPbm+l0GLs/QR/othB604lefzo+qCaBbUXqXPEOMoKyL2Sl5TQBbAN05WDvjzCS\n"
            + "    7nSjxQTOxXDHMV322gaWB7+efcUfknsS4bMCUXBOgeqnBNUR6BoB3SX2avn6tLtS\n"
            + "    t3dfzQEcOoIFxcTtSpu3PDlj6WajAwKdbSQU3TZ0L10u3Zz0jEL72w8gDl5OStkG\n"
            + "    SuRNVEYuPavb2PucL2blVopwGpwtkNbLylGdrzJpmmuA10Tx39ZNj2dWyJzT03zM\n"
            + "    0XlDRuGFZrdCieUH1dblmoOC6JyW+f7lO1ETNIC1I7LEz6B8beXTCM7LAiTVoVrn\n"
            + "    ZFqoBY8vrso4zobe8o2kydbmGuYeuKO4rGJqsYihl+RkxHmKMrS4WC/f+no0cv9h\n"
            + "    AFockmGQp63lOGI/HBb37WT9lxdV5jbpeH7bYan/y2jtlbyC48+I3WF+kP7t/Uh1\n"
            + "    KYcOuxlBDtt/NXRummzI00Ht7YRzLD4ZCfLbufK0EskMZ1EG6tvBC/OmlA7pCaWT\n"
            + "    St8KzOHtsYKgJA3Fea4OxDkEL6lBSommVOp2zWybKLb3Gzc=\n"
            + "  </certificate>\n"
            + "  <value>\n"
            + "    uKJ4W8BPCdVaIBe2ZiMxxk5L5vGBV9QwaOEGU80LgtA/gEqkiO2IMUBlQJFqvvhh6RSph5lWpLuv\n"
            + "    /Xt7WBzDsZOcxXNffg2+pWNpbpwZdHohlwQEI1OqiVYVnfG4euAkzeWZZLsRUuAjHfcWVIzDoSoK\n"
            + "    wC+gqdUQHBV+pWyn6PXVslS0JIldeegbiwF076M1D7ybeCABXoQelSZRHkx1szO8UnxSR3X7Cemu\n"
            + "    p9De/7z9+WPPclqybINVIPy6Kvl8mHrGSlzawQRDKtoMrJa8bo93PookF8sbg5EoGapV0yNpMEiA\n"
            + "    spq3DEcdXB6mGDGPnLbS2WXq4zjKopASRKkZvOMdgfS6NdUMDtKS1TsOrv2KKTkLnGYfvdAeWiMg\n"
            + "    oFbuyYQ0mnDlLH1UW6anI8RxXn+wmdyZA+/ksapGvRmkvz0Mb997WzqNl7v7UTr0SU3Ws01hFsm6\n"
            + "    lW++MsotkyfpR9mWB8/dqVNVShLmIlt7U/YFVfziYSrVdjcAdIlgJ6Ihxb92liQHOU+Qr1YDOmm1\n"
            + "    JSnhlQVvFxWZG7hm5laNL6lqXz5VV6Gk5IeLtMb8kdHz3zj4ascdldapVPLJIa5741GNNgQNU0nH\n"
            + "    FhAyKk0zN7PbL1/XGWPU+s5lai4HE6JM2CKA7jE7cYrdaDZxbba+9iWzQ4YEBDr5Z3OoloK5dvs=\n"
            + "  </value>\n"
            + "</signature>\n";

    public static final PublicKey CERT_1_PUBLIC_KEY;
    public static final PrivateKey CERT_1_PRIVATE_KEY;

    static {
        try {
            CERT_1_PUBLIC_KEY =
                    SecureBox.decodePublicKey(
                            new byte[] {
                                (byte) 0x04, (byte) 0xb8, (byte) 0x00, (byte) 0x11, (byte) 0x18,
                                (byte) 0x98, (byte) 0x1d, (byte) 0xf0, (byte) 0x6e, (byte) 0xb4,
                                (byte) 0x94, (byte) 0xfe, (byte) 0x86, (byte) 0xda, (byte) 0x1c,
                                (byte) 0x07, (byte) 0x8d, (byte) 0x01, (byte) 0xb4, (byte) 0x3a,
                                (byte) 0xf6, (byte) 0x8d, (byte) 0xdc, (byte) 0x61, (byte) 0xd0,
                                (byte) 0x46, (byte) 0x49, (byte) 0x95, (byte) 0x0f, (byte) 0x10,
                                (byte) 0x86, (byte) 0x93, (byte) 0x24, (byte) 0x66, (byte) 0xe0,
                                (byte) 0x3f, (byte) 0xd2, (byte) 0xdf, (byte) 0xf3, (byte) 0x79,
                                (byte) 0x20, (byte) 0x1d, (byte) 0x91, (byte) 0x55, (byte) 0xb0,
                                (byte) 0xe5, (byte) 0xbd, (byte) 0x7a, (byte) 0x8b, (byte) 0x32,
                                (byte) 0x7d, (byte) 0x25, (byte) 0x53, (byte) 0xa2, (byte) 0xfc,
                                (byte) 0xa5, (byte) 0x65, (byte) 0xe1, (byte) 0xbd, (byte) 0x21,
                                (byte) 0x44, (byte) 0x7e, (byte) 0x78, (byte) 0x52, (byte) 0xfa
                            });
            CERT_1_PRIVATE_KEY =
                    decodePrivateKey(
                            new byte[] {
                                (byte) 0x70, (byte) 0x01, (byte) 0xc7, (byte) 0x87, (byte) 0x32,
                                (byte) 0x2f, (byte) 0x1c, (byte) 0x9a, (byte) 0x6e, (byte) 0xb1,
                                (byte) 0x91, (byte) 0xca, (byte) 0x4e, (byte) 0xb5, (byte) 0x44,
                                (byte) 0xba, (byte) 0xc8, (byte) 0x68, (byte) 0xc6, (byte) 0x0a,
                                (byte) 0x76, (byte) 0xcb, (byte) 0xd3, (byte) 0x63, (byte) 0x67,
                                (byte) 0x7c, (byte) 0xb0, (byte) 0x11, (byte) 0x82, (byte) 0x65,
                                (byte) 0x77, (byte) 0x01
                            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static byte[] getCertPath1Bytes() {
        try {
            return CertUtils.decodeBase64(CERT_PATH_1_BASE64);
        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    public static byte[] getCertPath2Bytes() {
        try {
            return CertUtils.decodeBase64(CERT_PATH_2_BASE64);
        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    public static final CertPath CERT_PATH_1;
    public static final CertPath CERT_PATH_2;

    static {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            CERT_PATH_1 = certFactory.generateCertPath(
                    new ByteArrayInputStream(getCertPath1Bytes()), CERT_PATH_ENCODING);
            CERT_PATH_2 = certFactory.generateCertPath(
                    new ByteArrayInputStream(getCertPath2Bytes()), CERT_PATH_ENCODING);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] getCertXmlWithSerial(long serial) {
        String xml = THM_CERT_XML_BEFORE_SERIAL + serial + THM_CERT_XML_AFTER_SERIAL;
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] getCertXml() {
        return getCertXmlWithSerial(DEFAULT_SERIAL);
    }

    public static byte[] getSigXml() {
        return THM_SIG_XML.getBytes(StandardCharsets.UTF_8);
    }

    private static PrivateKey decodePrivateKey(byte[] keyBytes) throws Exception {
        assertThat(keyBytes.length).isEqualTo(32);
        BigInteger priv = new BigInteger(/*signum=*/ 1, keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        return keyFactory.generatePrivate(new ECPrivateKeySpec(priv, SecureBox.EC_PARAM_SPEC));
    }

    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }

    private static final String INSECURE_CERT_XML_HEADER = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<certificates>\n"
            + "  <metadata>\n"
            + "    <serial>\n";
    private static final String INSECURE_CERT_XML_BODY = ""
            + "    </serial>\n"
            + "    <creation-time>\n"
            + "      1515697631\n"
            + "    </creation-time>\n"
            + "    <refresh-interval>\n"
            + "      2592000\n"
            + "    </refresh-interval>\n"
            + "    <previous>\n"
            + "      <serial>\n"
            + "          0\n"
            + "      </serial>\n"
            + "      <hash>\n"
            + "        47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\n"
            + "      </hash>\n"
            + "    </previous>\n"
            + "  </metadata>\n"
            + "  <intermediates>\n"
            + "    <cert>\n"
            + "      MIIEQjCCAiqgAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwLTErMCkGA1UEAwwiVGVz\n"
            + "      dCBPbmx5IFVuc2VjdXJlIEludGVybWVkaWF0ZSBDQTAeFw0xODAzMjgwNTA1MjNa\n"
            + "      Fw0yMzAzMDIwNTA1MjNaMDMxMTAvBgNVBAMMKFRlc3QgT25seSBVbnNlY3VyZSBJ\n"
            + "      bnRlci1JbnRlcm1lZGlhdGUgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK\n"
            + "      AoIBAQDF3CyLIFWaNspHUwEr5hHDr6nmP5Iog73E6G7kBB1Xytt955AagHffmHze\n"
            + "      WZ/mAWBYHe6kJMsTLTfmb+kZLK8/s58AjUu/byrgPPIL92v7HgIa1148OePYmohX\n"
            + "      z0uNZQK5sYeb4kT2cyprKSWPBceyCcZDelTYpbleXd2yJSY/37XoiacmR8z6fEds\n"
            + "      ezqNLBpVApiVsVehizdIioYUCAtZlS2DnShacVGOq/FE/RCOC3wfKVcOV4HqaVBM\n"
            + "      JwwMOSL6YUIq34fi6VSNAunReeF7tckESUFu0pz52UmRyMhDZ/FCrjE5EIslxomD\n"
            + "      NJJttZyAmZDUB087SA6vNWhJOwynAgMBAAGjZjBkMB0GA1UdDgQWBBRcOlrOWbGw\n"
            + "      +UIwB8/P1ZRHsgEyvzAfBgNVHSMEGDAWgBQhLxSvWzSypPWVIcPdbtgiAphaNDAS\n"
            + "      BgNVHRMBAf8ECDAGAQH/AgEBMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsF\n"
            + "      AAOCAgEAkI+gomTRa34s3lea8Amg+W9WeyitdVw2MxBF/jYLAELq9pIfgsI8EYRt\n"
            + "      rUGT05xzrcis+cqdwxDTAQNNcvOxHj3F/VkGiAHvCFapw7quxeq6+aHU9m2nnZ1O\n"
            + "      Ss9qz7Low32yWhf5jJSQRA5HPO139H+CquVFDYOx1oiny0JsBivBoVkhb35HJmBu\n"
            + "      dkabfKIVdKAesd0At7KHZM9Voitp4LQsYctsi63EzELNvmFZ/NujVyoLmXz1wmQg\n"
            + "      avdc2FdWHMD3CdJzDyYmfqvn7FPds8wdWEnBnfZ4A5izwFD2BJjMvMfVs4pPv/8z\n"
            + "      GFYPkjhgN43Rh8kakJ+QgSbZpoEY0Vb7WbjMNRbdTBUJCk5waAMSNvWK26wcAzok\n"
            + "      OWZ+j6SHj2cku6sAcDbWLrlaREy6KTLpvhMqsiLzivmfu/FGWCZMb/zX1rllzzGP\n"
            + "      v6tIF29ewYNOic+RT3E4H9I7YO+mSbEs2szoRl6HRoC24DIcgUxpQ24Z3WriuIdB\n"
            + "      XwdHXgDGeoD9NsI3Lt7KRoXePDAyzKTnxTUwo+G9au5+ldtLs645ijDKKcX7CmyJ\n"
            + "      zCnIMeTeU9j88ht5s4Yo50s1WBeQv/Tq+euzprXrdUzFaih4aOFDytuyoZIwAi02\n"
            + "      dr2Dw+CkExaY755XQtEAV3KNi8+oy9Qy6pkBE50KGD40exzOru8=\n"
            + "    </cert>\n"
            + "    <cert>\n"
            + "      MIIFNDCCAxygAwIBAgICEAAwDQYJKoZIhvcNAQENBQAwJTEjMCEGA1UEAwwaVGVz\n"
            + "      dCBPbmx5IFVuc2VjdXJlIFJvb3QgQ0EwHhcNMTgwMzI4MDAzNzE4WhcNMjgwMzI1\n"
            + "      MDAzNzE4WjAtMSswKQYDVQQDDCJUZXN0IE9ubHkgVW5zZWN1cmUgSW50ZXJtZWRp\n"
            + "      YXRlIENBMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA1dwb6jH2QBwA\n"
            + "      kj6W8lrprOvJRCJQhx/sAAnMqcvFMDIb5cY2PIwWglMENmZvtigzOiE6Je+QxHsh\n"
            + "      EYYm7D8XU9vRjOUBW7NQW3Lb/tsEX4FUDmsGWbm7pYRoXQheAy1PTyPRNCRBciUd\n"
            + "      z2mQq7oL03jNGkOIIRKAluo/QzZbXVnVep8nUJRikt3lYxUl8hwiM3Epzqs3+No8\n"
            + "      HGCwf5ohQYXvYcJF/KxnTUKyFHPDpHSME2IViYnmY8bfzRDBkNlHsAcSSXir3AEe\n"
            + "      BDI9/uaY7PlwoQwrG1qQ5MKqPM5eLm+uOFVs1kKwSLRLmPk7X4mu56o88CE2InTc\n"
            + "      zIz5PUcBWW1RvGhAA/FX9fChgXrDC8megGbAzQCE22pxcqR+RUIphXxe2PkhkvUH\n"
            + "      PI6JM4ijFVGhCum6leMkOeRmwtlAZ8mmTBJbGVdHUSaKVxYy6LcFR8Rt1stjQxTN\n"
            + "      BJKXfZsI/qvKf9pTkevvMtaUoMwwYAd8rTMhwuAml+uhrf39UtLRS6SAH/POaD6d\n"
            + "      8Dnl9KeAGLb6P5pixd+TYDUfj/ZebuzDkuK/BfJbDB+qYiXWbD7EhYtyHFIoc9pF\n"
            + "      IXmE0sb+n0/WGtBpXipZWInJqnzV0LDRKoFPfhO1YtsUNtSTCtiPSJGouwn4PlCC\n"
            + "      2rIm8cnA9Mo1qtBfLRLVcHWWM69c7W0CAwEAAaNmMGQwHQYDVR0OBBYEFCEvFK9b\n"
            + "      NLKk9ZUhw91u2CICmFo0MB8GA1UdIwQYMBaAFMMnjaWILF12L4fc9P5Ra42u7Ffr\n"
            + "      MBIGA1UdEwEB/wQIMAYBAf8CAQIwDgYDVR0PAQH/BAQDAgGGMA0GCSqGSIb3DQEB\n"
            + "      DQUAA4ICAQAj08SwC3OWKq3rmDOxdGeODLYwPJl/lfiOdFFQMOhzSKKQ4oBlwRAR\n"
            + "      F4T32KU95/0QhwjgYB2nFo/frDKwk2j3F7gZvZuc4ekuP6Vc1qSSgv99kqIIrk74\n"
            + "      UNUK2BmOIqrCunpW7WF99VQgS4FHS0TJbJdsecV7KNznT4l3dac/QOOXeid5HSeE\n"
            + "      QHGyqMUd0noDS0UjqNfhdbd1sWpsZYjWsIq+gsZ3ADDSTCESwjElHZOMVKz3uyIx\n"
            + "      7i4HY1pzWV4Ob0NedLfYvaauAxnQSsktQMk56mGWqLaiBQA3FjNGh5J28oSws93Z\n"
            + "      e/OBUtUT92vlbVqbqsQCq6GiNgh7RGnsMFqV7+hrJsHjI1SUG5YlgCWIw9sPNdpW\n"
            + "      q4jR0pOR+WVBW8HY8C7HTOlTOh3K6Isdwx813XeU6xGxLhQuN/zEQolQRSD8wBto\n"
            + "      2gmZF9MWTzS3YE68b7LwxR6ByghaLxxN7ULRR2cxoRAuJEbgeFRzaI4xiRqLOoS2\n"
            + "      dwsPp30sVx6meXqfhYUT9CebexrI31+sbryaTAktExoP4Gsnx1uCjLr8UUsde7Jq\n"
            + "      Ln0gg4Sv9tHz7GWm0TE5iwMHk59KCKyMFc+x8MHY9Cdhd+p7drrba3X+FC80DRoK\n"
            + "      uRQ0rcOVWaGNoK3KLAr22axFSgWJX2wrNPMNqRfz9/6/t83HO/5xiw==\n"
            + "    </cert>\n"
            + "  </intermediates>\n"
            + "  <endpoints>\n";
    private static final String INSECURE_CERT_XML_ENDPOINT1_CERT = ""
            + "    <cert>\n"
            + "      MIIDlDCCAXygAwIBAgICEAIwDQYJKoZIhvcNAQELBQAwLTErMCkGA1UEAwwiVGVz\n"
            + "      dCBPbmx5IFVuc2VjdXJlIEludGVybWVkaWF0ZSBDQTAeFw0xODAzMjgwNTIzMTZa\n"
            + "      Fw0yMjA1MDYwNTIzMTZaMCgxJjAkBgNVBAMMHVRlc3QgT25seSBVbnNlY3VyZSBF\n"
            + "      bmRwb2ludCAxMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEt0uibX2wsl5S0gPl\n"
            + "      mR8JNEa9oXYyV8RniS57AG1ZBEpqBi/cBtkLctiJ6RUPYxRSR7xkfGu90TEFapjc\n"
            + "      cELxYaOBjTCBijAJBgNVHRMEAjAAMB0GA1UdDgQWBBTE4A58EAivi5Lf/fgO4tjh\n"
            + "      NgVFJjBOBgNVHSMERzBFgBQhLxSvWzSypPWVIcPdbtgiAphaNKEppCcwJTEjMCEG\n"
            + "      A1UEAwwaVGVzdCBPbmx5IFVuc2VjdXJlIFJvb3QgQ0GCAhAAMA4GA1UdDwEB/wQE\n"
            + "      AwIDCDANBgkqhkiG9w0BAQsFAAOCAgEAOCwjSvJ/6+gjTEgX3uFV3OiGb5C1UIlR\n"
            + "      StZq9h+65m5Rj4rAM/1RYkjYrIy25VZyGk85cJhcv+ZIFKz4gKwgTxhDUjtOtNFS\n"
            + "      rsY50IFwXOwFC+NaP/Z9d75Om5FSbbmXCnoMpb8ErtNvUaJs7CezOg0m19JD3/eq\n"
            + "      9DsroSDY9+3HQluURv92YlfsIhZaByTgQU5X2e56RKR2pcij+AoQGTzmAHou1k3+\n"
            + "      aNkdvZz8YRS7UoVPMyrii1rNFYaYOBYwZHzP/zM8CLGZ3O6nC0Rq+5f6Twi5tiIR\n"
            + "      WNz5gWawOdvPicVfv66EfEBQRfu45HsOLqnPjnAwbLVLUOn2mw/PH+jffgswD8Jl\n"
            + "      AKHIhuS6zE7+ArXz0/Uz88wYSJMj2TU2g9S+KxHCElltf2gLX3aak6lNeOnfbVP3\n"
            + "      Ld+S9t43Vu058Ao7a4htNbT9/kTvaB2gWqL2GIXAtZ/lEcgmOedVIX0AEv5doB9Z\n"
            + "      Ygue5SlJy/uXKcYmSaY5BrjiDBSiXi0tM1fxJHyVTtzfawv6SFszFEqBRFRRZdqL\n"
            + "      aWrNV3jplyOa/8D8HU7e6PGJsnjLKM8yA1zgSfDnNUj0Z3Ovj+AlMiFMU/Schdjb\n"
            + "      YD0cjgViI/4bkUgF4YDzOT1xOxIc1C4pi+PvlKbGYXFluLUEf0qoBe9ZEJN5Cwv6\n"
            + "      puOfIKtVqtA=\n"
            + "    </cert>\n";
    private static final String INSECURE_CERT_XML_ENDPOINT2_CERT = ""
            + "    <cert>\n"
            + "      MIICojCCAYqgAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwMzExMC8GA1UEAwwoVGVz\n"
            + "      dCBPbmx5IFVuc2VjdXJlIEludGVyLUludGVybWVkaWF0ZSBDQTAeFw0xODAzMjgw\n"
            + "      NTI0NDJaFw0yMjA1MDYwNTI0NDJaMCgxJjAkBgNVBAMMHVRlc3QgT25seSBVbnNl\n"
            + "      Y3VyZSBFbmRwb2ludCAyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJFCmWFoj\n"
            + "      Y2neIsYdpWo/eeA2g+EsQayB7gYkt00eAS7bM+1bas0OqYUPeW4iHYF67jEbNg4b\n"
            + "      lzLWa76fzt/8AKOBlTCBkjAJBgNVHRMEAjAAMB0GA1UdDgQWBBS19DdRW13M4VHr\n"
            + "      UUagViNg9AxyujBWBgNVHSMETzBNgBRcOlrOWbGw+UIwB8/P1ZRHsgEyv6ExpC8w\n"
            + "      LTErMCkGA1UEAwwiVGVzdCBPbmx5IFVuc2VjdXJlIEludGVybWVkaWF0ZSBDQYIC\n"
            + "      EAAwDgYDVR0PAQH/BAQDAgMIMA0GCSqGSIb3DQEBCwUAA4IBAQB+0cAYzhkxfn5d\n"
            + "      XoyF6q0pxTNAREsJ6WtHa2wvtx4UnFIT9nxy3TuliGs2x6lR7knJxGmXC6XMYMwG\n"
            + "      suafjEhF3svAscGXXh7pwNZb3Q99/HFuxyCPKAOCwsoaZEm/xeuzvZqBVnVtNTVo\n"
            + "      PbqkTjsaYZNPd3X/hqLafHKA5Aq19vQQ9O9VgwSu9asDr2uv7A8xJY9629wMYRny\n"
            + "      FYWveJG124TEL2xGqdXkOG9lE5BJlC1D4lrqDwF6FQL2A8IRL3cQ5BRg+lFAR4PU\n"
            + "      IT7UgpPK4f4CnKcgpKPn5TXH44TdRlhNPMRyT9MnFOb5K/gV8K+nB2YMAxOMRld+\n"
            + "      4VH7v7k6\n"
            + "    </cert>\n";
    private static final String INSECURE_CERT_XML_FOOTER = ""
            + "  </endpoints>\n"
            + "</certificates>\n";

    private static final String INSECURE_CERT_PATH_FOR_ENDPOINT1_BASE64 = ""
            + "MIII0DCCBTQwggMcoAMCAQICAhAAMA0GCSqGSIb3DQEBDQUAMCUxIzAhBgNVBAMM"
            + "GlRlc3QgT25seSBVbnNlY3VyZSBSb290IENBMB4XDTE4MDMyODAwMzcxOFoXDTI4"
            + "MDMyNTAwMzcxOFowLTErMCkGA1UEAwwiVGVzdCBPbmx5IFVuc2VjdXJlIEludGVy"
            + "bWVkaWF0ZSBDQTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANXcG+ox"
            + "9kAcAJI+lvJa6azryUQiUIcf7AAJzKnLxTAyG+XGNjyMFoJTBDZmb7YoMzohOiXv"
            + "kMR7IRGGJuw/F1Pb0YzlAVuzUFty2/7bBF+BVA5rBlm5u6WEaF0IXgMtT08j0TQk"
            + "QXIlHc9pkKu6C9N4zRpDiCESgJbqP0M2W11Z1XqfJ1CUYpLd5WMVJfIcIjNxKc6r"
            + "N/jaPBxgsH+aIUGF72HCRfysZ01CshRzw6R0jBNiFYmJ5mPG380QwZDZR7AHEkl4"
            + "q9wBHgQyPf7mmOz5cKEMKxtakOTCqjzOXi5vrjhVbNZCsEi0S5j5O1+JrueqPPAh"
            + "NiJ03MyM+T1HAVltUbxoQAPxV/XwoYF6wwvJnoBmwM0AhNtqcXKkfkVCKYV8Xtj5"
            + "IZL1BzyOiTOIoxVRoQrpupXjJDnkZsLZQGfJpkwSWxlXR1EmilcWMui3BUfEbdbL"
            + "Y0MUzQSSl32bCP6ryn/aU5Hr7zLWlKDMMGAHfK0zIcLgJpfroa39/VLS0UukgB/z"
            + "zmg+nfA55fSngBi2+j+aYsXfk2A1H4/2Xm7sw5LivwXyWwwfqmIl1mw+xIWLchxS"
            + "KHPaRSF5hNLG/p9P1hrQaV4qWViJyap81dCw0SqBT34TtWLbFDbUkwrYj0iRqLsJ"
            + "+D5QgtqyJvHJwPTKNarQXy0S1XB1ljOvXO1tAgMBAAGjZjBkMB0GA1UdDgQWBBQh"
            + "LxSvWzSypPWVIcPdbtgiAphaNDAfBgNVHSMEGDAWgBTDJ42liCxddi+H3PT+UWuN"
            + "ruxX6zASBgNVHRMBAf8ECDAGAQH/AgECMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG"
            + "9w0BAQ0FAAOCAgEAI9PEsAtzliqt65gzsXRnjgy2MDyZf5X4jnRRUDDoc0iikOKA"
            + "ZcEQEReE99ilPef9EIcI4GAdpxaP36wysJNo9xe4Gb2bnOHpLj+lXNakkoL/fZKi"
            + "CK5O+FDVCtgZjiKqwrp6Vu1hffVUIEuBR0tEyWyXbHnFeyjc50+Jd3WnP0Djl3on"
            + "eR0nhEBxsqjFHdJ6A0tFI6jX4XW3dbFqbGWI1rCKvoLGdwAw0kwhEsIxJR2TjFSs"
            + "97siMe4uB2Nac1leDm9DXnS32L2mrgMZ0ErJLUDJOephlqi2ogUANxYzRoeSdvKE"
            + "sLPd2XvzgVLVE/dr5W1am6rEAquhojYIe0Rp7DBale/oaybB4yNUlBuWJYAliMPb"
            + "DzXaVquI0dKTkfllQVvB2PAux0zpUzodyuiLHcMfNd13lOsRsS4ULjf8xEKJUEUg"
            + "/MAbaNoJmRfTFk80t2BOvG+y8MUegcoIWi8cTe1C0UdnMaEQLiRG4HhUc2iOMYka"
            + "izqEtncLD6d9LFcepnl6n4WFE/Qnm3sayN9frG68mkwJLRMaD+BrJ8dbgoy6/FFL"
            + "HXuyai59IIOEr/bR8+xlptExOYsDB5OfSgisjBXPsfDB2PQnYXfqe3a622t1/hQv"
            + "NA0aCrkUNK3DlVmhjaCtyiwK9tmsRUoFiV9sKzTzDakX8/f+v7fNxzv+cYswggOU"
            + "MIIBfKADAgECAgIQAjANBgkqhkiG9w0BAQsFADAtMSswKQYDVQQDDCJUZXN0IE9u"
            + "bHkgVW5zZWN1cmUgSW50ZXJtZWRpYXRlIENBMB4XDTE4MDMyODA1MjMxNloXDTIy"
            + "MDUwNjA1MjMxNlowKDEmMCQGA1UEAwwdVGVzdCBPbmx5IFVuc2VjdXJlIEVuZHBv"
            + "aW50IDEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAS3S6JtfbCyXlLSA+WZHwk0"
            + "Rr2hdjJXxGeJLnsAbVkESmoGL9wG2Qty2InpFQ9jFFJHvGR8a73RMQVqmNxwQvFh"
            + "o4GNMIGKMAkGA1UdEwQCMAAwHQYDVR0OBBYEFMTgDnwQCK+Lkt/9+A7i2OE2BUUm"
            + "ME4GA1UdIwRHMEWAFCEvFK9bNLKk9ZUhw91u2CICmFo0oSmkJzAlMSMwIQYDVQQD"
            + "DBpUZXN0IE9ubHkgVW5zZWN1cmUgUm9vdCBDQYICEAAwDgYDVR0PAQH/BAQDAgMI"
            + "MA0GCSqGSIb3DQEBCwUAA4ICAQA4LCNK8n/r6CNMSBfe4VXc6IZvkLVQiVFK1mr2"
            + "H7rmblGPisAz/VFiSNisjLblVnIaTzlwmFy/5kgUrPiArCBPGENSO0600VKuxjnQ"
            + "gXBc7AUL41o/9n13vk6bkVJtuZcKegylvwSu029RomzsJ7M6DSbX0kPf96r0Oyuh"
            + "INj37cdCW5RG/3ZiV+wiFloHJOBBTlfZ7npEpHalyKP4ChAZPOYAei7WTf5o2R29"
            + "nPxhFLtShU8zKuKLWs0Vhpg4FjBkfM//MzwIsZnc7qcLRGr7l/pPCLm2IhFY3PmB"
            + "ZrA528+JxV+/roR8QFBF+7jkew4uqc+OcDBstUtQ6fabD88f6N9+CzAPwmUAociG"
            + "5LrMTv4CtfPT9TPzzBhIkyPZNTaD1L4rEcISWW1/aAtfdpqTqU146d9tU/ct35L2"
            + "3jdW7TnwCjtriG01tP3+RO9oHaBaovYYhcC1n+URyCY551UhfQAS/l2gH1liC57l"
            + "KUnL+5cpxiZJpjkGuOIMFKJeLS0zV/EkfJVO3N9rC/pIWzMUSoFEVFFl2otpas1X"
            + "eOmXI5r/wPwdTt7o8YmyeMsozzIDXOBJ8Oc1SPRnc6+P4CUyIUxT9JyF2NtgPRyO"
            + "BWIj/huRSAXhgPM5PXE7EhzULimL4++UpsZhcWW4tQR/SqgF71kQk3kLC/qm458g"
            + "q1Wq0A==";
    private static final String INSECURE_CERT_PATH_FOR_ENDPOINT2_BASE64 = ""
            + "MIIMJDCCBTQwggMcoAMCAQICAhAAMA0GCSqGSIb3DQEBDQUAMCUxIzAhBgNVBAMM"
            + "GlRlc3QgT25seSBVbnNlY3VyZSBSb290IENBMB4XDTE4MDMyODAwMzcxOFoXDTI4"
            + "MDMyNTAwMzcxOFowLTErMCkGA1UEAwwiVGVzdCBPbmx5IFVuc2VjdXJlIEludGVy"
            + "bWVkaWF0ZSBDQTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANXcG+ox"
            + "9kAcAJI+lvJa6azryUQiUIcf7AAJzKnLxTAyG+XGNjyMFoJTBDZmb7YoMzohOiXv"
            + "kMR7IRGGJuw/F1Pb0YzlAVuzUFty2/7bBF+BVA5rBlm5u6WEaF0IXgMtT08j0TQk"
            + "QXIlHc9pkKu6C9N4zRpDiCESgJbqP0M2W11Z1XqfJ1CUYpLd5WMVJfIcIjNxKc6r"
            + "N/jaPBxgsH+aIUGF72HCRfysZ01CshRzw6R0jBNiFYmJ5mPG380QwZDZR7AHEkl4"
            + "q9wBHgQyPf7mmOz5cKEMKxtakOTCqjzOXi5vrjhVbNZCsEi0S5j5O1+JrueqPPAh"
            + "NiJ03MyM+T1HAVltUbxoQAPxV/XwoYF6wwvJnoBmwM0AhNtqcXKkfkVCKYV8Xtj5"
            + "IZL1BzyOiTOIoxVRoQrpupXjJDnkZsLZQGfJpkwSWxlXR1EmilcWMui3BUfEbdbL"
            + "Y0MUzQSSl32bCP6ryn/aU5Hr7zLWlKDMMGAHfK0zIcLgJpfroa39/VLS0UukgB/z"
            + "zmg+nfA55fSngBi2+j+aYsXfk2A1H4/2Xm7sw5LivwXyWwwfqmIl1mw+xIWLchxS"
            + "KHPaRSF5hNLG/p9P1hrQaV4qWViJyap81dCw0SqBT34TtWLbFDbUkwrYj0iRqLsJ"
            + "+D5QgtqyJvHJwPTKNarQXy0S1XB1ljOvXO1tAgMBAAGjZjBkMB0GA1UdDgQWBBQh"
            + "LxSvWzSypPWVIcPdbtgiAphaNDAfBgNVHSMEGDAWgBTDJ42liCxddi+H3PT+UWuN"
            + "ruxX6zASBgNVHRMBAf8ECDAGAQH/AgECMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG"
            + "9w0BAQ0FAAOCAgEAI9PEsAtzliqt65gzsXRnjgy2MDyZf5X4jnRRUDDoc0iikOKA"
            + "ZcEQEReE99ilPef9EIcI4GAdpxaP36wysJNo9xe4Gb2bnOHpLj+lXNakkoL/fZKi"
            + "CK5O+FDVCtgZjiKqwrp6Vu1hffVUIEuBR0tEyWyXbHnFeyjc50+Jd3WnP0Djl3on"
            + "eR0nhEBxsqjFHdJ6A0tFI6jX4XW3dbFqbGWI1rCKvoLGdwAw0kwhEsIxJR2TjFSs"
            + "97siMe4uB2Nac1leDm9DXnS32L2mrgMZ0ErJLUDJOephlqi2ogUANxYzRoeSdvKE"
            + "sLPd2XvzgVLVE/dr5W1am6rEAquhojYIe0Rp7DBale/oaybB4yNUlBuWJYAliMPb"
            + "DzXaVquI0dKTkfllQVvB2PAux0zpUzodyuiLHcMfNd13lOsRsS4ULjf8xEKJUEUg"
            + "/MAbaNoJmRfTFk80t2BOvG+y8MUegcoIWi8cTe1C0UdnMaEQLiRG4HhUc2iOMYka"
            + "izqEtncLD6d9LFcepnl6n4WFE/Qnm3sayN9frG68mkwJLRMaD+BrJ8dbgoy6/FFL"
            + "HXuyai59IIOEr/bR8+xlptExOYsDB5OfSgisjBXPsfDB2PQnYXfqe3a622t1/hQv"
            + "NA0aCrkUNK3DlVmhjaCtyiwK9tmsRUoFiV9sKzTzDakX8/f+v7fNxzv+cYswggRC"
            + "MIICKqADAgECAgIQADANBgkqhkiG9w0BAQsFADAtMSswKQYDVQQDDCJUZXN0IE9u"
            + "bHkgVW5zZWN1cmUgSW50ZXJtZWRpYXRlIENBMB4XDTE4MDMyODA1MDUyM1oXDTIz"
            + "MDMwMjA1MDUyM1owMzExMC8GA1UEAwwoVGVzdCBPbmx5IFVuc2VjdXJlIEludGVy"
            + "LUludGVybWVkaWF0ZSBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEB"
            + "AMXcLIsgVZo2ykdTASvmEcOvqeY/kiiDvcTobuQEHVfK233nkBqAd9+YfN5Zn+YB"
            + "YFgd7qQkyxMtN+Zv6Rksrz+znwCNS79vKuA88gv3a/seAhrXXjw549iaiFfPS41l"
            + "Armxh5viRPZzKmspJY8Fx7IJxkN6VNiluV5d3bIlJj/fteiJpyZHzPp8R2x7Oo0s"
            + "GlUCmJWxV6GLN0iKhhQIC1mVLYOdKFpxUY6r8UT9EI4LfB8pVw5XgeppUEwnDAw5"
            + "IvphQirfh+LpVI0C6dF54Xu1yQRJQW7SnPnZSZHIyENn8UKuMTkQiyXGiYM0km21"
            + "nICZkNQHTztIDq81aEk7DKcCAwEAAaNmMGQwHQYDVR0OBBYEFFw6Ws5ZsbD5QjAH"
            + "z8/VlEeyATK/MB8GA1UdIwQYMBaAFCEvFK9bNLKk9ZUhw91u2CICmFo0MBIGA1Ud"
            + "EwEB/wQIMAYBAf8CAQEwDgYDVR0PAQH/BAQDAgGGMA0GCSqGSIb3DQEBCwUAA4IC"
            + "AQCQj6CiZNFrfizeV5rwCaD5b1Z7KK11XDYzEEX+NgsAQur2kh+CwjwRhG2tQZPT"
            + "nHOtyKz5yp3DENMBA01y87EePcX9WQaIAe8IVqnDuq7F6rr5odT2baednU5Kz2rP"
            + "sujDfbJaF/mMlJBEDkc87Xf0f4Kq5UUNg7HWiKfLQmwGK8GhWSFvfkcmYG52Rpt8"
            + "ohV0oB6x3QC3sodkz1WiK2ngtCxhy2yLrcTMQs2+YVn826NXKguZfPXCZCBq91zY"
            + "V1YcwPcJ0nMPJiZ+q+fsU92zzB1YScGd9ngDmLPAUPYEmMy8x9Wzik+//zMYVg+S"
            + "OGA3jdGHyRqQn5CBJtmmgRjRVvtZuMw1Ft1MFQkKTnBoAxI29YrbrBwDOiQ5Zn6P"
            + "pIePZyS7qwBwNtYuuVpETLopMum+EyqyIvOK+Z+78UZYJkxv/NfWuWXPMY+/q0gX"
            + "b17Bg06Jz5FPcTgf0jtg76ZJsSzazOhGXodGgLbgMhyBTGlDbhndauK4h0FfB0de"
            + "AMZ6gP02wjcu3spGhd48MDLMpOfFNTCj4b1q7n6V20uzrjmKMMopxfsKbInMKcgx"
            + "5N5T2PzyG3mzhijnSzVYF5C/9Or567Omtet1TMVqKHho4UPK27KhkjACLTZ2vYPD"
            + "4KQTFpjvnldC0QBXco2Lz6jL1DLqmQETnQoYPjR7HM6u7zCCAqIwggGKoAMCAQIC"
            + "AhAAMA0GCSqGSIb3DQEBCwUAMDMxMTAvBgNVBAMMKFRlc3QgT25seSBVbnNlY3Vy"
            + "ZSBJbnRlci1JbnRlcm1lZGlhdGUgQ0EwHhcNMTgwMzI4MDUyNDQyWhcNMjIwNTA2"
            + "MDUyNDQyWjAoMSYwJAYDVQQDDB1UZXN0IE9ubHkgVW5zZWN1cmUgRW5kcG9pbnQg"
            + "MjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABCRQplhaI2Np3iLGHaVqP3ngNoPh"
            + "LEGsge4GJLdNHgEu2zPtW2rNDqmFD3luIh2Beu4xGzYOG5cy1mu+n87f/ACjgZUw"
            + "gZIwCQYDVR0TBAIwADAdBgNVHQ4EFgQUtfQ3UVtdzOFR61FGoFYjYPQMcrowVgYD"
            + "VR0jBE8wTYAUXDpazlmxsPlCMAfPz9WUR7IBMr+hMaQvMC0xKzApBgNVBAMMIlRl"
            + "c3QgT25seSBVbnNlY3VyZSBJbnRlcm1lZGlhdGUgQ0GCAhAAMA4GA1UdDwEB/wQE"
            + "AwIDCDANBgkqhkiG9w0BAQsFAAOCAQEAftHAGM4ZMX5+XV6MheqtKcUzQERLCelr"
            + "R2tsL7ceFJxSE/Z8ct07pYhrNsepUe5JycRplwulzGDMBrLmn4xIRd7LwLHBl14e"
            + "6cDWW90PffxxbscgjygDgsLKGmRJv8Xrs72agVZ1bTU1aD26pE47GmGTT3d1/4ai"
            + "2nxygOQKtfb0EPTvVYMErvWrA69rr+wPMSWPetvcDGEZ8hWFr3iRtduExC9sRqnV"
            + "5DhvZROQSZQtQ+Ja6g8BehUC9gPCES93EOQUYPpRQEeD1CE+1IKTyuH+ApynIKSj"
            + "5+U1x+OE3UZYTTzEck/TJxTm+Sv4FfCvpwdmDAMTjEZXfuFR+7+5Og==";

    public static byte[] getInsecureCertXmlBytesWithEndpoint1(int serial) {
        String str = INSECURE_CERT_XML_HEADER;
        str += serial;
        str += INSECURE_CERT_XML_BODY;
        str += INSECURE_CERT_XML_ENDPOINT1_CERT;
        str += INSECURE_CERT_XML_FOOTER;
        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] getInsecureCertXmlBytesWithEndpoint2(int serial) {
        String str = INSECURE_CERT_XML_HEADER;
        str += serial;
        str += INSECURE_CERT_XML_BODY;
        str += INSECURE_CERT_XML_ENDPOINT2_CERT;
        str += INSECURE_CERT_XML_FOOTER;
        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static CertPath getInsecureCertPathForEndpoint1() throws Exception {
        return decodeCertPath(INSECURE_CERT_PATH_FOR_ENDPOINT1_BASE64);
    }

    public static CertPath getInsecureCertPathForEndpoint2() throws Exception {
        return decodeCertPath(INSECURE_CERT_PATH_FOR_ENDPOINT2_BASE64);
    }

    private static CertPath decodeCertPath(String base64CertPath) throws Exception {
        byte[] certPathBytes = Base64.getDecoder().decode(base64CertPath);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return certFactory.generateCertPath(new ByteArrayInputStream(certPathBytes), "PkiPath");
    }
}
