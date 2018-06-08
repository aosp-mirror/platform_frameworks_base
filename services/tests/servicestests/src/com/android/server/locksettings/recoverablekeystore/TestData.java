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
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class TestData {

    private static final String KEY_ALGORITHM = "AES";
    private static final long DEFAULT_SERIAL = 10001;
    private static final String CERT_PATH_ENCODING = "PkiPath";

    private static final String CERT_PATH_1_BASE64 = ""
            + "MIIIXTCCBRowggMCoAMCAQICEB35ZwzVpI9ssXg9SAehnU0wDQYJKoZIhvcNAQEL"
            + "BQAwMTEvMC0GA1UEAxMmR29vZ2xlIENsb3VkIEtleSBWYXVsdCBTZXJ2aWNlIFJv"
            + "b3QgQ0EwHhcNMTgwNTA3MTg1ODEwWhcNMjgwNTA4MTg1ODEwWjA5MTcwNQYDVQQD"
            + "Ey5Hb29nbGUgQ2xvdWQgS2V5IFZhdWx0IFNlcnZpY2UgSW50ZXJtZWRpYXRlIENB"
            + "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA73TrvH3j6zEimpcc32tx"
            + "2iupWwfyzdE5l4Ejc5EBYzx0aZH6b/KDuutwustk0IoyjlGySMBz/21YgWejIm+n"
            + "duAlpk7WY5kYHp0XWtzdmxZknmWTqugPeNZeiKEjoDmpyIbY6N+f13hQ2RVh+WDT"
            + "EowQ/i04WBL75chshlIG+3A42g5Qr7DZEKdT9oJQqkntzj0cGyJ5X8BwjeTiJrvY"
            + "k2Kn/0555/Kpp65G3Rf29VPPU3i67kthAT3SavLBpH03S4WZ+QlfrAiGQziydtz9"
            + "t7mSk1xefjax5ZWAuJAfCbKfI3VWAcaUr4P57BzmDcSi0jgs1aM3t2BrPfAMRxWv"
            + "35yDZnrC+HipzkjyDGBfHmFgoglyhc9e/Kj3mSusO0Rq1wguVXKs2hKXRoaGJuHt"
            + "e3YIwTC1pLznqvolhD1nPoXf8rMzgHRzlc9H8iXsgB1p7975nh5WCPrMDX2eAmYd"
            + "a0xTMccTeBzIM2ohxQsxlh5rsjXVNU3ihbWkHquzIiwFcAtldP3dMksj0dn/DnYD"
            + "yokjEgU/z2I216E93x9hmKkEk6Pp7o8t/z6lwMT9FJIuzp7NREnWCSi+e5s2E7FD"
            + "j6S7xY2zEIUHrmwuuJc0jzJnwdZ+0myinaTmBDvBXR5cU1cmEAZoheCAoRv9Z/6o"
            + "ASczLF0C4uuVfA5GXcAm14cCAwEAAaMmMCQwDgYDVR0PAQH/BAQDAgGGMBIGA1Ud"
            + "EwEB/wQIMAYBAf8CAQEwDQYJKoZIhvcNAQELBQADggIBAEPht79yQm8woQbPB1Bs"
            + "eotkzJtTWTO9fnIWwNiRfQ3vJFXf69ghE77wUS13Ez3FlgNPj0Qxmg5ouE0d2yYV"
            + "4AUrXnEGZELcyN2XHRXyNK0zXgnr3x6eZyY7QfgGKJgkyja5TS6ZPWGyaLKhClS0"
            + "AYZSzWJtz0+AkGCdTbmyy7ShdXJ+GfnmssbndZA62VhcjeQmHsDq7V3PKAsp4/B9"
            + "PzcnTrgkUFNnP1F1pr7JpUUX3xyRFy6gjIrUx1fcOFRxFYPWGLLMZ6P41rafm+M/"
            + "CbBNr5CY7NrZjr34jLqWycfYes49o9OK44X/wPrxj0Sjg+VrW21+AJ9vrM7DS5hE"
            + "QX1lDbDtQGkk3N1vgCTo6xt9LXsEu4xUT5bk7YAfpJqM0ltDFPwYAGCbjSkVT/M5"
            + "JVZkKiUW668Us67x8yZc/5bxbvTA+5xrYhak/VYIBY6qub4J+bKwadw6uBgxnq4P"
            + "hwgwjfaoJy9YAXCswjCtaE9GwkVmRnJE9vFjJ33IGf37hFTYEHBFy4FomVmQwRFZ"
            + "TIe7tkKDq9i18F7lzBPJPO6wEG8bxi4csatrjcVHR9erpY5u6ebtkKG8qsan9qzh"
            + "iWAgSytiT++HejZeoQ+RRgQWjupjdDo5/0oSdQqvaN8Ah6C2J+ecCZ12Lu0FwF+t"
            + "t9Ie3pF6W8TzxzuMdFWq+afvMIIDOzCCASOgAwIBAgIRAOTj/iNQb6/Qit7zAW9n"
            + "cL0wDQYJKoZIhvcNAQELBQAwOTE3MDUGA1UEAxMuR29vZ2xlIENsb3VkIEtleSBW"
            + "YXVsdCBTZXJ2aWNlIEludGVybWVkaWF0ZSBDQTAeFw0xODA1MDcyMjE4MTFaFw0y"
            + "MzA1MDgyMjE4MTFaMDIxMDAuBgNVBAMTJ0dvb2dsZSBDbG91ZCBLZXkgVmF1bHQg"
            + "U2VydmljZSBFbmRwb2ludDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABI4MEUp5"
            + "IHwATNfpBuJYIUX6JMsHZt798YO0JlWYy6nVVa1lxf9c+xxONJh+T5aio370RlIE"
            + "uiq5R7vCHt0VGsCjEDAOMAwGA1UdEwEB/wQCMAAwDQYJKoZIhvcNAQELBQADggIB"
            + "AGf6QU58lU+gGzy8hnp0suR/ixC++CExtf39pDHkdfU/e3ui4ROR+pjQ5F7okDFW"
            + "eKSCNcJZ7tyXMJ9g7/I0qVY8Bj/gRnlVokdl/wD5PiL9GIzqWfnHNe3T+xrAAAgO"
            + "D0bEmjgwNYmekfUIYQczd04d7ZMGnmAkpVH/0O2mf9q5x9fMlbKuAygUqQ/gmnlg"
            + "xKfl9DSRWi4oMBOqlKlCRP1XAh3anu92+M/EhsFbyc07CWZY0SByX5M/cHVMLhUX"
            + "jZHvcYLyOmJWJmXznidgyNeIR6t9yDB55iCt7WSn3qMY+9vA9ELzt8jYpBNaKc0G"
            + "bWQkRzYWegkf4kMis98eQ3SnAKbRz6669nmuAdxKs9/LK6BlFOFw1xvsTRQ96dBa"
            + "oiX2XGhou+Im0Td/AMs0Aigz2N+Ujq/yW//35GZQfdGGIYtFbkcltStygjIJyAM1"
            + "pBhyBBkJhOhRpO4fXh98aq8H5J7R9i5A9WpnDstAxPxcNCDWn0O/WxhPvVZkFTpi"
            + "NXh9dnlJ/kZe+j+z5ZMaxW435drLPx2AQKjXA9GgGrFPltTUyGycmEGtuxLvSnm/"
            + "zPlmk5FUk7x2wEr0+bZ3cx0JHHgAtgXpe0jkDi8Bw8O3X7mUOjxVhYU6auiYJezW"
            + "9LGmweaKwYvS04UCWOReolUVexob9LI/VX1JrrwD3s7k";
    private static final String CERT_PATH_2_BASE64 = ""
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
            + "<certificate>\n"
            + "  <metadata>\n"
            + "    <serial>";
    private static final String THM_CERT_XML_AFTER_SERIAL = ""
            + "</serial>\n"
            + "    <creation-time>1525817891</creation-time>\n"
            + "    <refresh-interval>2592000</refresh-interval>\n"
            + "    <previous>\n"
            + "      <serial>10000</serial>\n"
            + "      <hash>ahyI+59KW2tVxi0inRdUSo1Y8kmx5xK1isDvYfzxWbo=</hash>\n"
            + "    </previous>\n"
            + "  </metadata>\n"
            + "  <intermediates>\n"
            + "    <cert>MIIFGjCCAwKgAwIBAgIQHflnDNWkj2yxeD1IB6GdTTANBgkqhkiG9w0BAQsFADAxMS8wLQYDVQQDEyZHb29nbGUgQ2xvdWQgS2V5IFZhdWx0IFNlcnZpY2UgUm9vdCBDQTAeFw0xODA1MDcxODU4MTBaFw0yODA1MDgxODU4MTBaMDkxNzA1BgNVBAMTLkdvb2dsZSBDbG91ZCBLZXkgVmF1bHQgU2VydmljZSBJbnRlcm1lZGlhdGUgQ0EwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDvdOu8fePrMSKalxzfa3HaK6lbB/LN0TmXgSNzkQFjPHRpkfpv8oO663C6y2TQijKOUbJIwHP/bViBZ6Mib6d24CWmTtZjmRgenRda3N2bFmSeZZOq6A941l6IoSOgOanIhtjo35/XeFDZFWH5YNMSjBD+LThYEvvlyGyGUgb7cDjaDlCvsNkQp1P2glCqSe3OPRwbInlfwHCN5OImu9iTYqf/Tnnn8qmnrkbdF/b1U89TeLruS2EBPdJq8sGkfTdLhZn5CV+sCIZDOLJ23P23uZKTXF5+NrHllYC4kB8Jsp8jdVYBxpSvg/nsHOYNxKLSOCzVoze3YGs98AxHFa/fnINmesL4eKnOSPIMYF8eYWCiCXKFz178qPeZK6w7RGrXCC5VcqzaEpdGhoYm4e17dgjBMLWkvOeq+iWEPWc+hd/yszOAdHOVz0fyJeyAHWnv3vmeHlYI+swNfZ4CZh1rTFMxxxN4HMgzaiHFCzGWHmuyNdU1TeKFtaQeq7MiLAVwC2V0/d0ySyPR2f8OdgPKiSMSBT/PYjbXoT3fH2GYqQSTo+nujy3/PqXAxP0Uki7Ons1ESdYJKL57mzYTsUOPpLvFjbMQhQeubC64lzSPMmfB1n7SbKKdpOYEO8FdHlxTVyYQBmiF4IChG/1n/qgBJzMsXQLi65V8DkZdwCbXhwIDAQABoyYwJDAOBgNVHQ8BAf8EBAMCAYYwEgYDVR0TAQH/BAgwBgEB/wIBATANBgkqhkiG9w0BAQsFAAOCAgEAQ+G3v3JCbzChBs8HUGx6i2TMm1NZM71+chbA2JF9De8kVd/r2CETvvBRLXcTPcWWA0+PRDGaDmi4TR3bJhXgBStecQZkQtzI3ZcdFfI0rTNeCevfHp5nJjtB+AYomCTKNrlNLpk9YbJosqEKVLQBhlLNYm3PT4CQYJ1NubLLtKF1cn4Z+eayxud1kDrZWFyN5CYewOrtXc8oCynj8H0/NydOuCRQU2c/UXWmvsmlRRffHJEXLqCMitTHV9w4VHEVg9YYssxno/jWtp+b4z8JsE2vkJjs2tmOvfiMupbJx9h6zj2j04rjhf/A+vGPRKOD5WtbbX4An2+szsNLmERBfWUNsO1AaSTc3W+AJOjrG30tewS7jFRPluTtgB+kmozSW0MU/BgAYJuNKRVP8zklVmQqJRbrrxSzrvHzJlz/lvFu9MD7nGtiFqT9VggFjqq5vgn5srBp3Dq4GDGerg+HCDCN9qgnL1gBcKzCMK1oT0bCRWZGckT28WMnfcgZ/fuEVNgQcEXLgWiZWZDBEVlMh7u2QoOr2LXwXuXME8k87rAQbxvGLhyxq2uNxUdH16uljm7p5u2Qobyqxqf2rOGJYCBLK2JP74d6Nl6hD5FGBBaO6mN0Ojn/ShJ1Cq9o3wCHoLYn55wJnXYu7QXAX6230h7ekXpbxPPHO4x0Var5p+8=</cert>\n"
            + "  </intermediates>\n"
            + "  <endpoints>\n"
            // The public key is chosen by using the following hash as the first 32 bytes (x-axis)
            //     SHA256("Google Cloud Key Vault Service Test Endpoint") = 8e0c114a79207c004cd7e906e2582145fa24cb0766defdf183b4265598cba9d5
            // so its private key is unknown.
            + "    <cert>MIIDOzCCASOgAwIBAgIRAOTj/iNQb6/Qit7zAW9ncL0wDQYJKoZIhvcNAQELBQAwOTE3MDUGA1UEAxMuR29vZ2xlIENsb3VkIEtleSBWYXVsdCBTZXJ2aWNlIEludGVybWVkaWF0ZSBDQTAeFw0xODA1MDcyMjE4MTFaFw0yMzA1MDgyMjE4MTFaMDIxMDAuBgNVBAMTJ0dvb2dsZSBDbG91ZCBLZXkgVmF1bHQgU2VydmljZSBFbmRwb2ludDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABI4MEUp5IHwATNfpBuJYIUX6JMsHZt798YO0JlWYy6nVVa1lxf9c+xxONJh+T5aio370RlIEuiq5R7vCHt0VGsCjEDAOMAwGA1UdEwEB/wQCMAAwDQYJKoZIhvcNAQELBQADggIBAGf6QU58lU+gGzy8hnp0suR/ixC++CExtf39pDHkdfU/e3ui4ROR+pjQ5F7okDFWeKSCNcJZ7tyXMJ9g7/I0qVY8Bj/gRnlVokdl/wD5PiL9GIzqWfnHNe3T+xrAAAgOD0bEmjgwNYmekfUIYQczd04d7ZMGnmAkpVH/0O2mf9q5x9fMlbKuAygUqQ/gmnlgxKfl9DSRWi4oMBOqlKlCRP1XAh3anu92+M/EhsFbyc07CWZY0SByX5M/cHVMLhUXjZHvcYLyOmJWJmXznidgyNeIR6t9yDB55iCt7WSn3qMY+9vA9ELzt8jYpBNaKc0GbWQkRzYWegkf4kMis98eQ3SnAKbRz6669nmuAdxKs9/LK6BlFOFw1xvsTRQ96dBaoiX2XGhou+Im0Td/AMs0Aigz2N+Ujq/yW//35GZQfdGGIYtFbkcltStygjIJyAM1pBhyBBkJhOhRpO4fXh98aq8H5J7R9i5A9WpnDstAxPxcNCDWn0O/WxhPvVZkFTpiNXh9dnlJ/kZe+j+z5ZMaxW435drLPx2AQKjXA9GgGrFPltTUyGycmEGtuxLvSnm/zPlmk5FUk7x2wEr0+bZ3cx0JHHgAtgXpe0jkDi8Bw8O3X7mUOjxVhYU6auiYJezW9LGmweaKwYvS04UCWOReolUVexob9LI/VX1JrrwD3s7k</cert>\n"
            + "  </endpoints>\n"
            + "</certificate>\n";
    private static final String THM_SIG_XML = ""
            + "<signature>\n"
            + "  <intermediates></intermediates>\n"
            + "  <certificate>MIIFGjCCAwKgAwIBAgIQHflnDNWkj2yxeD1IB6GdTTANBgkqhkiG9w0BAQsFADAxMS8wLQYDVQQDEyZHb29nbGUgQ2xvdWQgS2V5IFZhdWx0IFNlcnZpY2UgUm9vdCBDQTAeFw0xODA1MDcxODU4MTBaFw0yODA1MDgxODU4MTBaMDkxNzA1BgNVBAMTLkdvb2dsZSBDbG91ZCBLZXkgVmF1bHQgU2VydmljZSBJbnRlcm1lZGlhdGUgQ0EwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDvdOu8fePrMSKalxzfa3HaK6lbB/LN0TmXgSNzkQFjPHRpkfpv8oO663C6y2TQijKOUbJIwHP/bViBZ6Mib6d24CWmTtZjmRgenRda3N2bFmSeZZOq6A941l6IoSOgOanIhtjo35/XeFDZFWH5YNMSjBD+LThYEvvlyGyGUgb7cDjaDlCvsNkQp1P2glCqSe3OPRwbInlfwHCN5OImu9iTYqf/Tnnn8qmnrkbdF/b1U89TeLruS2EBPdJq8sGkfTdLhZn5CV+sCIZDOLJ23P23uZKTXF5+NrHllYC4kB8Jsp8jdVYBxpSvg/nsHOYNxKLSOCzVoze3YGs98AxHFa/fnINmesL4eKnOSPIMYF8eYWCiCXKFz178qPeZK6w7RGrXCC5VcqzaEpdGhoYm4e17dgjBMLWkvOeq+iWEPWc+hd/yszOAdHOVz0fyJeyAHWnv3vmeHlYI+swNfZ4CZh1rTFMxxxN4HMgzaiHFCzGWHmuyNdU1TeKFtaQeq7MiLAVwC2V0/d0ySyPR2f8OdgPKiSMSBT/PYjbXoT3fH2GYqQSTo+nujy3/PqXAxP0Uki7Ons1ESdYJKL57mzYTsUOPpLvFjbMQhQeubC64lzSPMmfB1n7SbKKdpOYEO8FdHlxTVyYQBmiF4IChG/1n/qgBJzMsXQLi65V8DkZdwCbXhwIDAQABoyYwJDAOBgNVHQ8BAf8EBAMCAYYwEgYDVR0TAQH/BAgwBgEB/wIBATANBgkqhkiG9w0BAQsFAAOCAgEAQ+G3v3JCbzChBs8HUGx6i2TMm1NZM71+chbA2JF9De8kVd/r2CETvvBRLXcTPcWWA0+PRDGaDmi4TR3bJhXgBStecQZkQtzI3ZcdFfI0rTNeCevfHp5nJjtB+AYomCTKNrlNLpk9YbJosqEKVLQBhlLNYm3PT4CQYJ1NubLLtKF1cn4Z+eayxud1kDrZWFyN5CYewOrtXc8oCynj8H0/NydOuCRQU2c/UXWmvsmlRRffHJEXLqCMitTHV9w4VHEVg9YYssxno/jWtp+b4z8JsE2vkJjs2tmOvfiMupbJx9h6zj2j04rjhf/A+vGPRKOD5WtbbX4An2+szsNLmERBfWUNsO1AaSTc3W+AJOjrG30tewS7jFRPluTtgB+kmozSW0MU/BgAYJuNKRVP8zklVmQqJRbrrxSzrvHzJlz/lvFu9MD7nGtiFqT9VggFjqq5vgn5srBp3Dq4GDGerg+HCDCN9qgnL1gBcKzCMK1oT0bCRWZGckT28WMnfcgZ/fuEVNgQcEXLgWiZWZDBEVlMh7u2QoOr2LXwXuXME8k87rAQbxvGLhyxq2uNxUdH16uljm7p5u2Qobyqxqf2rOGJYCBLK2JP74d6Nl6hD5FGBBaO6mN0Ojn/ShJ1Cq9o3wCHoLYn55wJnXYu7QXAX6230h7ekXpbxPPHO4x0Var5p+8=</certificate>\n"
            + "  <value>WkmYBCY4heNutMf3tEbyg+Omm+MvWF4EoDmv2vCd259oy3t8URqDqu5vEi3TqQguX0GO3r5mRKCcEYged121xJltC6zShbDMZZNAlB6sqvS6/vIVBBx5jKecUaEpRuQ4ruTyF93YXDi7DgaCNGaYCjkDrnr8lSAyelZl2tfe2BbpOMiwH1fNvI6Xmb++iyMmZGoJo91ovucC635SnnULNUtivL2CjLgU3mKb2uZMB8XPr1t1FOTJEA81ghDU+p3ZrPLxLB3KBTtfAwPQyqStRuY8+3bnPqi7VWeZgfoesvJiPF6q1PraoaL/inlRFo7wr37CS9EtNR/k5cq1UJ4/4Ernvj5k2Zw/IclzYHUGSd3ljCOTJJB6cDtR7WDqprlr1J4nr9hf1Ya4DFJmlX3FXix43Dw6lfk/1gCiWu0y2i1A2NCn0QRxuPh5b385Epj98QlZnd2roB2GfzchJTAxI+oeLc3CowkyLDS5jjuTMERbKbPpkhQu9gtskYtB0I4fEGOMn17+ZrXRcTPJexCS2NGgSqiF4X9Fwe+9XRg3Nk+SoUj9gBCysP8UeSz1POZHQIlZ24mQzxOK2hwGwDtxVchGCrNyf8rh5bZE2hUKHYNH9UWQ+YWrieKeulfP+o1wL9NLZOTz3SHcV/kCv5WqgynzkrKf382FwunxF56NapA=</value>\n"
            + "</signature>\n";

    public static final CertPath CERT_PATH_1;
    public static final CertPath CERT_PATH_2;
    public static final PublicKey CERT_1_PUBLIC_KEY;

    static {
        try {
            CERT_PATH_1 = decodeCertPath(CERT_PATH_1_BASE64);
            CERT_PATH_2 = decodeCertPath(CERT_PATH_2_BASE64);
            CERT_1_PUBLIC_KEY = CERT_PATH_1.getCertificates().get(0).getPublicKey();
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
    private static final String INSECURE_PRIVATE_KEY_FOR_ENDPOINT1_BASE64 = ""
            + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgdCqcARU7lWJVXKY/"
            + "+DTlFC0dzcTvh1ufkfZdnoZoR7ShRANCAAS3S6JtfbCyXlLSA+WZHwk0Rr2hdjJX"
            + "xGeJLnsAbVkESmoGL9wG2Qty2InpFQ9jFFJHvGR8a73RMQVqmNxwQvFh";

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

    public static PrivateKey getInsecurePrivateKeyForEndpoint1() throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(INSECURE_PRIVATE_KEY_FOR_ENDPOINT1_BASE64);
        KeyFactory kf = KeyFactory.getInstance("EC");
        PKCS8EncodedKeySpec skSpec = new PKCS8EncodedKeySpec(keyBytes);
        return kf.generatePrivate(skSpec);
    }

    private static CertPath decodeCertPath(String base64CertPath) throws Exception {
        byte[] certPathBytes = Base64.getDecoder().decode(base64CertPath);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return certFactory.generateCertPath(new ByteArrayInputStream(certPathBytes), "PkiPath");
    }
}
