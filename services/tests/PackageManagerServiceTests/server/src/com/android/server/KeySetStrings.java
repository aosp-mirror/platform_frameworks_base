/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;

public class KeySetStrings {

    /*
     * public keys taken from:
     * openssl x509 -in cts-keyset-test-${N}.x509.pem -inform PEM -pubkey
     * in /platform/cts/hostsidetests/appsecurity/certs/keysets
     */
    public static final String ctsKeySetPublicKeyA =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwf5zJblvYSB7Ym7or/7Ggg"
            + "AAu7mp7RrykPJsXhod8doFhVT5s7eF3A4MCE55vvANP7HvwMw2b+T6qx7Pq0VJtb"
            + "bSDtlBHBtIc47Pjq0CsDg590BUcgKp7PdJ9J6UVgtzDnV6cGEpXmSag3sY+lqiW0"
            + "4ytPhCVwzYTWGdYe9+TIl47cBrveRfLOlGrcuFQe+zCTmDFqzBKCRHK9b7l5PDWv"
            + "XXyg65Uu/MBUA/TZWO0fEqOlxZG/nn6DUKQLhPdmJRXWJ3WqMNMhJGD+nKtkmdX7"
            + "03xRqmg4h+6g0S7M9Y3IQ2NUGyw05AYzCguHB/Mv6uVIiW659wpbyb45TgKG3UhQ"
            + "IDAQAB";

    public static final String ctsKeySetPublicKeyB =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoeFZqMqTbZiozFTXMkXtSK"
            + "JRzn2qODZgvVXAAwKTi50xYcbPcHTfKxtif8+q7OCp/50JYDH32bg6wkUunn5+dE"
            + "aHkxZY8d7uw46tQtl5dNGi+6cc4MezVLCS6nkqNDusAgdvgLU6Fl6SGi02KTp1vk"
            + "t6CwLO977YJP7kt9ouDRTG7ASJiq3OyRRoOqYHhD9gpsbUq4w+1bXGfuuZujA1dX"
            + "yovXtvrHUGOdFIEBYOVYGfCcwh3lXPmjNJMlHtKQkurq8/LH7a1B5ocoXCGsyR8Y"
            + "HdlWfrqRAfzgOB1KCnNNmWqskU9LOci3uQn9IDeMEFmAd8FqF8SwV+4Ludk/xWGQ"
            + "IDAQAB";

    public static final String ctsKeySetPublicKeyC =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArwIJ/p9zZ6pGe7h1lBJULE"
            + "5lbYbC3mh5G43OsJ+B0CebN4KzEKyVg+wmkuGSvG2xXUp1BlbipSjnTJ5bUt2iBu"
            + "wB81Lvumg9GOfCpTBGtfE4a4igtfo7e2U8IbRzEYbhaZlBEmC1BDUvdTFdMRGZPu"
            + "hUcMkwit4RpHkL6rttuOfaeoJwsgEjbELyzgcm+1Z49Den/JmmXNGMw1/QMibBFG"
            + "vGkhu2rHg/SYiKpupclU4FIeALcOSnPkrrY6LuSATHDnYvuvK3Vhu0EBKID+rAv5"
            + "j6BNvnu25SAf3GgS7PLuyVlhiE5p3hTevXn5g/7tjJlXa0FsbMlnFf53WyP9pRWw"
            + "IDAQAB";

    /*
     * certs taken from packagemanager packages.xml output corresponding to certs in
     * /platform/cts/hostsidetests/appsecurity/certs/keysets
     */
    public static final String ctsKeySetCertA =
            "3082030b308201f3a0030201020209009d76e8a600170813300d06092a864886f7"
            + "0d0101050500301c311a301806035504030c116374732d6b65797365742d7465"
            + "73742d61301e170d3134303931313030343434385a170d343230313237303034"
            + "3434385a301c311a301806035504030c116374732d6b65797365742d74657374"
            + "2d6130820122300d06092a864886f70d01010105000382010f003082010a0282"
            + "010100c1fe7325b96f61207b626ee8affec6820000bbb9a9ed1af290f26c5e1a"
            + "1df1da058554f9b3b785dc0e0c084e79bef00d3fb1efc0cc366fe4faab1ecfab"
            + "4549b5b6d20ed9411c1b48738ecf8ead02b03839f740547202a9ecf749f49e94"
            + "560b730e757a7061295e649a837b18fa5aa25b4e32b4f842570cd84d619d61ef"
            + "7e4c8978edc06bbde45f2ce946adcb8541efb309398316acc12824472bd6fb97"
            + "93c35af5d7ca0eb952efcc05403f4d958ed1f12a3a5c591bf9e7e8350a40b84f"
            + "7662515d62775aa30d3212460fe9cab6499d5fbd37c51aa683887eea0d12eccf"
            + "58dc84363541b2c34e406330a0b8707f32feae548896eb9f70a5bc9be394e028"
            + "6dd4850203010001a350304e301d0603551d0e04160414debf602e08b7573bce"
            + "4816ac32eab215fb052892301f0603551d23041830168014debf602e08b7573b"
            + "ce4816ac32eab215fb052892300c0603551d13040530030101ff300d06092a86"
            + "4886f70d0101050500038201010092f1b8d08252d808d3051dce80780bd27eef"
            + "e3f6b6d935398afb448209461b6f8b352e830d4358661e1b3e9eb9ab3937bddd"
            + "581a28f533da1ebeb6838ce4a84ca64c43507c5ef9528917857e4d1c4c5996cf"
            + "6b3d30823db514a715eeee709d69e38b4f0ef5dce4b08ce40fd52b39ac651311"
            + "b6d1814913d922ce84748b6999256851fb583a49e35cecf79a527108df8e062d"
            + "f4831addbb12a661999d41849e2545150cab74c91447dd15e55cdf3f8082dcab"
            + "667c5cee3350d0f15d3970edcf3e81882e80985b0c0bf9917adb55c634de3a92"
            + "e8fb5d9413b1703bec116b9ee9346b658f394acfe0c60406718be80b7110df8b"
            + "44c984f001e1d16aac3831afee18";

    public static final String ctsKeySetCertB =
            "3082030b308201f3a003020102020900e670a5b2ec1e8a12300d06092a864886f7"
            + "0d0101050500301c311a301806035504030c116374732d6b65797365742d7465"
            + "73742d62301e170d3134303931313030343434315a170d343230313237303034"
            + "3434315a301c311a301806035504030c116374732d6b65797365742d74657374"
            + "2d6230820122300d06092a864886f70d01010105000382010f003082010a0282"
            + "010100a1e159a8ca936d98a8cc54d73245ed48a251ce7daa383660bd55c00302"
            + "938b9d3161c6cf7074df2b1b627fcfaaece0a9ff9d096031f7d9b83ac2452e9e"
            + "7e7e744687931658f1deeec38ead42d97974d1a2fba71ce0c7b354b092ea792a"
            + "343bac02076f80b53a165e921a2d36293a75be4b7a0b02cef7bed824fee4b7da"
            + "2e0d14c6ec04898aadcec914683aa607843f60a6c6d4ab8c3ed5b5c67eeb99ba"
            + "3035757ca8bd7b6fac750639d14810160e55819f09cc21de55cf9a33493251ed"
            + "29092eaeaf3f2c7edad41e687285c21acc91f181dd9567eba9101fce0381d4a0"
            + "a734d996aac914f4b39c8b7b909fd20378c10598077c16a17c4b057ee0bb9d93"
            + "fc56190203010001a350304e301d0603551d0e04160414ccd4d9d47dcc18889d"
            + "cba32de37e6570c88f8109301f0603551d23041830168014ccd4d9d47dcc1888"
            + "9dcba32de37e6570c88f8109300c0603551d13040530030101ff300d06092a86"
            + "4886f70d0101050500038201010061951cf9c9a629b30b560d53d62a72796edc"
            + "97b0b210b567859311b14574abb052ef08cabb0b18cef5517597eabee9498a07"
            + "a04472b8e6eee8668c05d2ff28141a36351593551f0c9d27feb4367fd0d23c76"
            + "e36035f9d06d2d24b4167120fabdcfddfbe872bd127a602de8563ad6027ee19a"
            + "fc21065cf02d6aaf97bf78388c3c129e72d1b31f5727896aaad7fe6773fbc285"
            + "34e89194a75e1ecf64bcc5fa228e71e3be9efc78cb39bbabf60e334b403fc3e4"
            + "9eb59c3407883d10efb04470a7d7d12114e7c9ddc3b381ffc43e8e8a830efa59"
            + "38e47eef0d4dd39a80186c3b4236f812f52775941fe1dd73d51f6f50ab0916e3"
            + "149c31feabcf38860be45d113a54";

    public static final String ctsKeySetCertC =
            "3082030b308201f3a0030201020209008f2e824e4e17810d300d06092a864886f7"
            + "0d0101050500301c311a301806035504030c116374732d6b65797365742d7465"
            + "73742d63301e170d3134303931313030343432325a170d343230313237303034"
            + "3432325a301c311a301806035504030c116374732d6b65797365742d74657374"
            + "2d6330820122300d06092a864886f70d01010105000382010f003082010a0282"
            + "010100af0209fe9f7367aa467bb8759412542c4e656d86c2de68791b8dceb09f"
            + "81d0279b3782b310ac9583ec2692e192bc6db15d4a750656e2a528e74c9e5b52"
            + "dda206ec01f352efba683d18e7c2a53046b5f1386b88a0b5fa3b7b653c21b473"
            + "1186e16999411260b504352f75315d3111993ee85470c9308ade11a4790beabb"
            + "6db8e7da7a8270b201236c42f2ce0726fb5678f437a7fc99a65cd18cc35fd032"
            + "26c1146bc6921bb6ac783f49888aa6ea5c954e0521e00b70e4a73e4aeb63a2ee"
            + "4804c70e762fbaf2b7561bb41012880feac0bf98fa04dbe7bb6e5201fdc6812e"
            + "cf2eec95961884e69de14debd79f983feed8c99576b416c6cc96715fe775b23f"
            + "da515b0203010001a350304e301d0603551d0e041604141b8137c73974a17633"
            + "686f93798a7f7b8385bded301f0603551d230418301680141b8137c73974a176"
            + "33686f93798a7f7b8385bded300c0603551d13040530030101ff300d06092a86"
            + "4886f70d01010505000382010100276ce2ca7b78b12aa2e432c8287075af91e5"
            + "2a15a8586e23cdd7524a4c5ae04156307e95275cdfd841f2d28c0583cb36779e"
            + "25d849a8b608eb48a84a50202a7825c7847e865409b1dd01303b5b1bdfafecab"
            + "bfe1c6ec5f30ce1cb16b93db72ef726f77a48ca4f5ac5e12c4ad08c6df6fbf7e"
            + "1548ef7ca80cf1d98abb550c0e28b246e8c0f1a975ffb624f1a4aeec11f01ba6"
            + "02631d56645f5ae042dbf67b444b160711ca2629c456c5cc12e2ff56fa1332b6"
            + "92483d14d2e6fb8e026246058fb5826e3958ee8f780d0fc2b840d51c2bbf0d24"
            + "e9e108ef1c2d9ec13797bb4e5793349628a2ddb2a79c9d9c5736e7aea93e4552"
            + "18fd162e0a42a4fbb4aa9df82b8a";
}
