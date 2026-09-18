package com.mineaudio.stream.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EapiCryptoTest {

    private static final String JSON = "{\"ids\":\"[1]\",\"level\":\"exhigh\"}";

    /** 固定向量：md5(nobody + path + use + json + md5forencrypt)。 */
    @Test
    void digestFixedVector() {
        assertEquals("34219a2e9af9d7889e83887ee8c7cab1", EapiCrypto.digest(NeteaseEapiResolver.API_PATH, JSON));
    }

    /** 固定向量：AES-128-ECB/PKCS5，密钥 e82ckenh8dichen8（openssl 生成）。 */
    @Test
    void encryptFixedVector() {
        assertEquals(
                "FA90B329E9614F79E79598F37DC2EDB487F00D1BC4C9B24CD57E6C318B907356"
                        + "9338432CD7D98D1A3626E997A2C53121F2652556B9CAA141000D39A0C1D1AE12"
                        + "0A1ECF50C0E5DF76D3B1F05C2C70FFD94CB88F7B9CEAF29870B8023896488D61"
                        + "A8FF2EB06A5BD3947CC05B7F6B5DB4115BB313B7F6000E528D83557A034E07A1",
                EapiCrypto.encrypt(NeteaseEapiResolver.API_PATH, JSON));
    }

    @Test
    void bodyIsFormEncoded() {
        String body = EapiCrypto.body(NeteaseEapiResolver.API_PATH, JSON);
        assertTrue(body.startsWith("params="), "应为表单参数");
        assertTrue(body.indexOf('%') < 0, "十六进制大写无需转义");
    }
}
