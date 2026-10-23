package com.github.stjiege.ftpshare.services;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.github.stjiege.ftpshare.utils.LogManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.security.auth.x500.X500Principal;

/**
 * FTPS 服务端身份（EC P-256 密钥对 + 自签证书）。
 *
 * <p><b>为什么私钥不放在 AndroidKeyStore 里。</b>
 * TLS 握手的 ServerKeyExchange 要用证书私钥做一次 ECDSA 签名。私钥一旦在 AndroidKeyStore
 * 里，这次签名就是一次 Binder IPC + 安全环境调用（真机 10–100 ms），而且底层 KeyMint /
 * Keymaster 的并发 operation 槽位是个位数。FTP 每传一个文件、每列一次目录都要新建一条
 * 数据连接 = 一次握手 = 一次签名，所以并发传输时它既是「慢」的来源，也是「握手失败」的
 * 来源。
 *
 * <p>这里改成：密钥对在进程内生成（软件实现，签名是纯 CPU，微秒级），私钥用
 * AndroidKeyStore 里一把**只用于包裹**的 AES-256-GCM 密钥加密后存到 app 私有目录。
 *
 * <p><b>安全上的取舍，写在这里以免后来人误判：</b>
 * <ul>
 *   <li>私钥会以加密形式落在磁盘上，使用时以明文形式存在于进程内存，
 *       **不再享有「私钥永不离开安全硬件」**这一属性。root 过或能读进程内存、
 *       能拿到备份镜像的攻击者，理论上可以提取并用它冒充本机的 FTPS 身份。</li>
 *   <li>落到磁盘前用 AndroidKeyStore 的 AES-GCM 密钥包裹，所以**只拷走文件没用**——
 *       解不开。文件存在 {@code noBackupFilesDir}，不进系统备份与 {@code adb backup}。</li>
 *   <li>GCM 自带完整性校验，篡改文件只会导致解密失败并触发重新签发，不会被静默接受。</li>
 * </ul>
 * 换来的收益是握手不再受安全硬件吞吐与并发槽位限制。相比「FTPS 一开就慢且并发失败」，
 * 这个取舍是值得的。
 *
 * <p>身份只签发一次并持久化：证书变了客户端就必须重新信任它，会不停弹未知证书警告。
 *
 * <p>早期版本把 TLS 私钥直接放在 AndroidKeyStore 的别名 {@code ftpshare.ftps.ec.v1} 下。
 * 改用软件密钥后那份已不再被读取，但**这里刻意不去删除它**：删除不可逆，留着不占什么，
 * 需要回滚排查时还能拿到当时的证书。
 */
public final class FtpsIdentity {

    private static final String WRAP_ALIAS = "ftpshare.ftps.wrap.v1";

    private static final String CERT_FILE = "ftps-identity.crt";
    private static final String KEY_FILE = "ftps-identity.key";
    private static final String SUBJECT = "CN=FTP Server G2";

    private static final byte[] FILE_MAGIC = {'F', 'T', 'P', 'K'};
    private static final byte FILE_VERSION = 1;

    private static final int VALIDITY_YEARS = 10;
    private static final int GCM_TAG_BITS = 128;

    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private FtpsIdentity(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /**
     * 取本机的 FTPS 身份：能用就用磁盘上那份，不能就重新签发一份并落盘。
     *
     * <p>只在 FTP 服务启动时调用一次（见 {@code FtpService.startFTPService()}），
     * 但加了同步以防多处并发首次加载时写坏文件。
     */
    public static synchronized FtpsIdentity load(Context context)
            throws GeneralSecurityException, IOException {
        File dir = context.getNoBackupFilesDir();
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create FTPS identity directory: " + dir);
        }
        File certFile = new File(dir, CERT_FILE);
        File keyFile = new File(dir, KEY_FILE);

        if (certFile.isFile() && keyFile.isFile()) {
            try {
                return read(certFile, keyFile);
            } catch (GeneralSecurityException | IOException broken) {
                // 包裹密钥不可用（例如设备恢复后 keystore 密钥失效）或文件被改坏。
                // 重新签发比让 FTPS 起不来好；代价是客户端要重新信任一次证书。
                LogManager.warning("FTPS 身份无法读取，已重新签发（客户端需重新信任证书）");
            }
        }
        FtpsIdentity fresh = generate();
        fresh.write(certFile, keyFile);
        return fresh;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    // ---------------------------------------------------------------- 读写

    private static FtpsIdentity read(File certFile, File keyFile)
            throws GeneralSecurityException, IOException {
        X509Certificate cert = readCertificate(certFile);
        PrivateKey key = readPrivateKey(keyFile);

        // 磁盘上的两份文件可能被单独替换或截断，逐项核对，任何一项不过就整份作废。
        cert.checkValidity();
        cert.verify(cert.getPublicKey());
        if (!matches(key, cert.getPublicKey())) {
            throw new GeneralSecurityException("Stored FTPS key does not match its certificate");
        }
        return new FtpsIdentity(key, cert);
    }

    private static X509Certificate readCertificate(File file)
            throws GeneralSecurityException, IOException {
        byte[] der = readAll(file);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
    }

    private static PrivateKey readPrivateKey(File file)
            throws GeneralSecurityException, IOException {
        byte[] blob = readAll(file);
        // 注意别用 Arrays.equals(byte[],int,int,byte[],int,int)——那是 API 33 才有的重载。
        if (blob.length < 8 || !Arrays.equals(FILE_MAGIC, Arrays.copyOfRange(blob, 0, 4))) {
            throw new GeneralSecurityException("Unrecognised FTPS key file");
        }
        if (blob[4] != FILE_VERSION) {
            throw new GeneralSecurityException("Unsupported FTPS key file version: " + blob[4]);
        }
        int ivLength = blob[5] & 0xFF;
        if (blob.length < 6 + ivLength) {
            throw new GeneralSecurityException("Truncated FTPS key file");
        }
        byte[] iv = Arrays.copyOfRange(blob, 6, 6 + ivLength);
        byte[] sealed = Arrays.copyOfRange(blob, 6 + ivLength, blob.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] pkcs8 = cipher.doFinal(sealed);

        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private void write(File certFile, File keyFile)
            throws GeneralSecurityException, IOException {
        byte[] pkcs8 = privateKey.getEncoded();
        if (pkcs8 == null) {
            throw new GeneralSecurityException("EC private key refuses to be encoded");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey());
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(pkcs8);

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(FILE_MAGIC);
        blob.write(FILE_VERSION);
        blob.write(iv.length);
        blob.write(iv);
        blob.write(sealed);

        // 先写临时文件再 rename：中途失败不会留下半截身份文件，下次启动还能正常重建。
        writeAtomically(certFile, certificate.getEncoded());
        writeAtomically(keyFile, blob.toByteArray());
    }

    private static void writeAtomically(File target, byte[] data)
            throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            throw new IOException("Failed to replace " + target.getName());
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[(int) file.length()];
            in.readFully(buffer);
            return buffer;
        }
    }

    /** 私钥与证书公钥是否配对：签一段固定数据再用证书公钥验。 */
    private static boolean matches(PrivateKey key, PublicKey publicKey)
            throws GeneralSecurityException {
        byte[] probe = {1, 2, 3, 4, 5};
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(probe);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(probe);
        return verifier.verify(signer.sign());
    }

    // ------------------------------------------------------------ 包裹密钥

    /**
     * AndroidKeyStore 里只用于加密/解密私钥文件的 AES-256-GCM 密钥。
     *
     * 它**不参与任何 TLS 运算**，所以走硬件不会成为瓶颈——每次服务启动只用两三次。
     */
    private static SecretKey wrapKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(WRAP_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // GCM 绝不能复用 IV；交给 keystore 强制随机化，比自己管 IV 可靠。
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        SecretKey key = (SecretKey) store.getKey(WRAP_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("FTPS wrap key unavailable");
        }
        return key;
    }

    // -------------------------------------------------------------- 签发

    private static FtpsIdentity generate() throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 86400000L);
        Date notAfter = new Date(now + VALIDITY_YEARS * 365L * 86400000L);

        byte[] der = selfSign(pair.getPrivate(), pair.getPublic(), notBefore, notAfter);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) factory
                .generateCertificate(new ByteArrayInputStream(der));

        // 自己签出来的东西自己先验一遍：DER 拼错的话这里就会炸，不会留到真握手时才暴露。
        cert.checkValidity();
        cert.verify(cert.getPublicKey());
        return new FtpsIdentity(pair.getPrivate(), cert);
    }

    /**
     * 手工拼一份 X.509 v3 自签证书。
     *
     * Android 的公开 API 里没有签发证书的办法（{@code sun.security.x509} 不在 SDK 里），
     * 所以按 RFC 5280 直接写 DER。用到的两处现成编码：
     * <ul>
     *   <li>DN：{@link X500Principal#getEncoded()} 直接给出完整的 Name DER；</li>
     *   <li>公钥：{@link PublicKey#getEncoded()} 直接给出 SubjectPublicKeyInfo DER。</li>
     * </ul>
     * 这样就只需要拼剩下的骨架和扩展。
     *
     * 扩展只放三项，都不含动态内容，所以是固定字节：
     * BasicConstraints(CA=true)、KeyUsage(digitalSignature + keyCertSign)、
     * ExtKeyUsage(serverAuth)。
     * 刻意<b>不加 SAN</b>——证书要被客户端连的局域网 IP 校验，而那个 IP 会变；
     * 写死一个反而会让严格校验的客户端因为名字对不上而拒绝，不如保持和原先一致。
     */
    private static byte[] selfSign(PrivateKey issuerKey, PublicKey subjectKey,
            Date notBefore, Date notAfter) throws GeneralSecurityException {
        // ecdsa-with-SHA256 = 1.2.840.10045.4.3.2
        byte[] ecdsaSha256 = der(0x30, der(0x06,
                new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02}));
        byte[] subject = new X500Principal(SUBJECT).getEncoded();

        byte[] tbs = der(0x30,
                // version [0] EXPLICIT INTEGER 2 —— v3
                der(0xA0, der(0x02, new byte[]{0x02})),
                der(0x02, serialNumber().toByteArray()),
                ecdsaSha256,
                subject,                       // issuer
                der(0x30, timeDer(notBefore), timeDer(notAfter)),
                subject,                       // subject（自签，同上）
                subjectKey.getEncoded(),       // SubjectPublicKeyInfo
                extensions());

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(issuerKey);
        signature.update(tbs);
        byte[] signatureValue = signature.sign();

        return der(0x30, tbs, ecdsaSha256, der(0x03, concat(new byte[]{0x00}, signatureValue)));
    }

    private static byte[] extensions() {
        // 2.5.29.19 basicConstraints，critical：SEQUENCE { cA BOOLEAN TRUE }
        byte[] basicConstraints = der(0x30,
                der(0x06, new byte[]{0x55, 0x1D, 0x13}),
                der(0x01, new byte[]{(byte) 0xFF}),
                der(0x04, der(0x30, der(0x01, new byte[]{(byte) 0xFF}))));

        // 2.5.29.15 keyUsage，critical：BIT STRING，置 digitalSignature(0) 与 keyCertSign(5)
        // 共 9 位，补齐到两个字节，末尾未用位 = 7。
        byte[] keyUsage = der(0x30,
                der(0x06, new byte[]{0x55, 0x1D, 0x0F}),
                der(0x01, new byte[]{(byte) 0xFF}),
                der(0x04, der(0x03, new byte[]{0x07, (byte) 0x84, 0x00})));

        // 2.5.29.37 extKeyUsage：SEQUENCE { 1.3.6.1.5.5.7.3.1 serverAuth }
        byte[] extKeyUsage = der(0x30,
                der(0x06, new byte[]{0x55, 0x1D, 0x25}),
                der(0x04, der(0x30, der(0x06,
                        new byte[]{0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01}))));

        // [3] EXPLICIT，v3 的 extensions
        return der(0xA3, der(0x30, basicConstraints, keyUsage, extKeyUsage));
    }

    private static BigInteger serialNumber() {
        // 128 位随机数 + 1：保证为正（BigInteger 的二补码编码需要最高位为 0）。
        return new BigInteger(128, new SecureRandom()).add(BigInteger.ONE);
    }

    /**
     * DER 时间。1950–2049 之间用 UTCTime（YYMMDDHHMMSSZ），
     * 超出这个范围才用 GeneralizedTime（YYYYMMDDHHMMSSZ）——设备时间被设成很怪的值时用得上。
     */
    private static byte[] timeDer(Date value) {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTime(value);
        int year = calendar.get(Calendar.YEAR);
        String text = String.format(java.util.Locale.ROOT,
                year >= 1950 && year < 2050 ? "%02d%02d%02d%02d%02d%02dZ" : "%04d%02d%02d%02d%02d%02dZ",
                year % (year >= 1950 && year < 2050 ? 100 : 10000),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND));
        return der(year >= 1950 && year < 2050 ? 0x17 : 0x18,
                text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] der(int tag, byte[]... parts) {
        return der(tag, concat(parts));
    }

    /** 写一个 DER TLV。长度按短格式 / 1 字节 / 2 字节长格式处理，本文件里够用。 */
    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
