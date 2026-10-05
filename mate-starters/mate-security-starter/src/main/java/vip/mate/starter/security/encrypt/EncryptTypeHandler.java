package vip.mate.starter.security.encrypt;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.springframework.core.env.Environment;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;

/**
 * MyBatis TypeHandler that transparently encrypts/decrypts String columns
 * using AES/CBC/PKCS5Padding.
 * <p>
 * Usage: annotate PO fields with {@code @TableField(typeHandler = EncryptTypeHandler.class)}.
 * <p>
 * The AES key is read from {@code mate.security.encrypt.key} (must be 16/24/32 bytes).
 * <p>
 * <b>IV handling:</b> a fresh random 16-byte IV is generated per write and stored
 * as a prefix of the stored value ({@code Base64(IV || ciphertext)}). This avoids
 * the equal-plaintext-leaks-equal-ciphertext weakness of a fixed/derived IV.
 * <p>
 * <b>Fail-closed on write:</b> if encryption fails, the write throws — plaintext is
 * never silently persisted to a column that is supposed to be encrypted.
 * <p>
 * <b>Backward-compatible reads:</b> {@link #decryptSafe} tries the current
 * prefixed-IV format, then the legacy fixed-IV format, then finally returns the
 * value verbatim (plaintext-before-migration scenario).
 *
 * @author mateaix
 */
@Slf4j
public class EncryptTypeHandler implements TypeHandler<String> {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_PROPERTY = "mate.security.encrypt.key";
    private static final int IV_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Environment environment;

    public EncryptTypeHandler(Environment environment) {
        this.environment = environment;
    }

    /**
     * Register this bean by handler class only. The official Boot starter
     * discovers all TypeHandler beans; inferring String here would make every
     * String parameter (including IDs and usernames) encrypted by default.
     * Explicit @TableField(typeHandler=...) mappings still resolve this instance.
     */
    @Override
    public void setParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter == null) {
            if (jdbcType == null) {
                throw new SQLException("JDBC type is required for a null encrypted parameter");
            }
            ps.setNull(i, jdbcType.TYPE_CODE);
        } else {
            setNonNullParameter(ps, i, parameter, jdbcType);
        }
    }

    @Override
    public String getResult(ResultSet rs, String columnName) throws SQLException {
        return getNullableResult(rs, columnName);
    }

    @Override
    public String getResult(ResultSet rs, int columnIndex) throws SQLException {
        return getNullableResult(rs, columnIndex);
    }

    @Override
    public String getResult(CallableStatement cs, int columnIndex) throws SQLException {
        return getNullableResult(cs, columnIndex);
    }

    // ---- write -----------------------------------------------------------

    public void setNonNullParameter(PreparedStatement ps, int i,
                                     String parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, encrypt(parameter));
        } catch (Exception e) {
            // Fail closed: never silently persist plaintext into an encrypted column.
            throw new SQLException("Failed to encrypt value for an encrypted column", e);
        }
    }

    // ---- read ------------------------------------------------------------

    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decryptSafe(rs.getString(columnName));
    }

    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decryptSafe(rs.getString(columnIndex));
    }

    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decryptSafe(cs.getString(columnIndex));
    }

    // ---- crypto helpers --------------------------------------------------

    private String encrypt(String plaintext) throws Exception {
        byte[] keyBytes = getKeyBytes();
        byte[] ivBytes = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(ivBytes);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, KEY_ALGORITHM),
                new IvParameterSpec(ivBytes));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[IV_LENGTH + encrypted.length];
        System.arraycopy(ivBytes, 0, combined, 0, IV_LENGTH);
        System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    /** Decrypt a value written in the current {@code Base64(IV || ciphertext)} format. */
    private String decrypt(String stored) throws Exception {
        byte[] keyBytes = getKeyBytes();
        byte[] decoded = Base64.getDecoder().decode(stored);
        if (decoded.length <= IV_LENGTH) {
            throw new IllegalArgumentException("ciphertext too short to contain an IV");
        }
        byte[] ivBytes = Arrays.copyOfRange(decoded, 0, IV_LENGTH);
        byte[] ct = Arrays.copyOfRange(decoded, IV_LENGTH, decoded.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, KEY_ALGORITHM),
                new IvParameterSpec(ivBytes));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** Legacy format: whole value is the ciphertext, IV derived from the key. */
    private String decryptLegacyFixedIv(String stored) throws Exception {
        byte[] keyBytes = getKeyBytes();
        byte[] decoded = Base64.getDecoder().decode(stored);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, KEY_ALGORITHM),
                new IvParameterSpec(Arrays.copyOf(keyBytes, IV_LENGTH)));
        return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
    }

    /**
     * Try current format, then legacy fixed-IV format, then fall back to the raw
     * value (plaintext-before-migration). Decryption failures are not fatal on
     * read so a partially-migrated table stays queryable.
     */
    private String decryptSafe(String value) {
        if (value == null) {
            return null;
        }
        try {
            return decrypt(value);
        } catch (Exception currentFormat) {
            try {
                return decryptLegacyFixedIv(value);
            } catch (Exception legacy) {
                log.debug("Value not decryptable in current or legacy format; "
                        + "returning as-is (plaintext migration scenario)");
                return value;
            }
        }
    }

    private byte[] getKeyBytes() {
        String key = environment.getProperty(KEY_PROPERTY);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "AES encryption key not configured. Set property: " + KEY_PROPERTY);
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                    "AES key must be 16, 24 or 32 bytes, got " + keyBytes.length);
        }
        return keyBytes;
    }
}
