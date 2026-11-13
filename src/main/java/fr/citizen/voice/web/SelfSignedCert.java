package fr.citizen.voice.web;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Génère un certificat HTTPS auto-signé sans aucune dépendance (encodage DER fait main),
 * pour que le navigateur accepte d'activer le micro.
 */
public final class SelfSignedCert {
    public static final char[] PASSWORD = "citizenvoix".toCharArray();

    private SelfSignedCert() {
    }

    /** Charge le .p12 auto-signé s'il existe et correspond à l'adresse, sinon le (re)crée. */
    public static KeyStore loadOrCreate(Path file, String host) throws Exception {
        if (Files.exists(file)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(file)) {
                ks.load(in, PASSWORD);
            }
            Certificate c = ks.getCertificate("citizenvoix");
            if (c instanceof X509Certificate x && x.getSubjectX500Principal().getName().contains("CN=" + host)
                    && x.getNotAfter().after(new Date(System.currentTimeMillis() + 30L * 86400_000L))) {
                return ks;
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = build(kp, host);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("citizenvoix", kp.getPrivate(), PASSWORD, new Certificate[]{cert});
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, PASSWORD);
        }
        return ks;
    }

    static X509Certificate build(KeyPair kp, String host) throws Exception {
        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00}); // sha256WithRSAEncryption
        byte[] name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, host.getBytes(StandardCharsets.UTF_8)))));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusDays(1);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'");
        byte[] validity = seq(tlv(0x17, f.format(now).getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, f.format(now.plusYears(10)).getBytes(StandardCharsets.US_ASCII)));
        boolean ip = host.matches("[0-9.]+");
        byte[] sanEntry = ip ? tlv(0x87, ipBytes(host)) : tlv(0x82, host.getBytes(StandardCharsets.US_ASCII));
        byte[] san = seq(oid("2.5.29.17"), tlv(0x04, seq(sanEntry)));
        byte[] basic = seq(oid("2.5.29.19"), tlv(0x04, seq()));
        byte[] exts = tlv(0xA3, seq(san, basic));
        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.TWO)),
                integer(new BigInteger(63, new SecureRandom()).add(BigInteger.ONE)),
                sigAlg, name, validity, name,
                kp.getPublic().getEncoded(),
                exts);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] ipBytes(String ip) {
        String[] p = ip.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            b[i] = (byte) Integer.parseInt(p[i]);
        }
        return b;
    }

    // ---- DER
    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else if (n < 65536) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
        o.writeBytes(v);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, cat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, cat(parts));
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] oid(String s) {
        String[] p = s.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>= 7;
            } while (v > 0);
            for (int j = n - 1; j >= 0; j--) {
                o.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, o.toByteArray());
    }
}
