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

package android.security.keystore.recovery;

import java.io.ByteArrayInputStream;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.util.Base64;

/** This class provides data for testing purposes. */
class TestData {

    private static final String THM_CERT_PATH_BASE64 = ""
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

    static CertPath getThmCertPath() {
        try {
            return decodeCertPath(THM_CERT_PATH_BASE64);
        } catch (Exception e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }

    private static CertPath decodeCertPath(String base64CertPath) throws Exception {
        byte[] certPathBytes = Base64.getDecoder().decode(base64CertPath);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return certFactory.generateCertPath(new ByteArrayInputStream(certPathBytes), "PkiPath");
    }
}
