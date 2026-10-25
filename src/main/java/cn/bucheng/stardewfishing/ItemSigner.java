package cn.bucheng.stardewfishing;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authentication key must be backed up together with the plugin's player data. */
public final class ItemSigner {
    private final byte[] key;
    public ItemSigner(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) key = Files.readAllBytes(path);
        else {
            key = new byte[32]; new SecureRandom().nextBytes(key);
            Files.write(path, key, StandardOpenOption.CREATE_NEW);
        }
        if (key.length != 32) throw new IOException("物品签名密钥损坏；请恢复备份，不能重新生成替代现有密钥");
    }
    public String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("无法签名物品", e); }
    }
    public boolean verify(String value, String signature) {
        return signature != null && MessageDigest.isEqual(sign(value).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
