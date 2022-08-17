package xyz.nyist.adapter;

import org.springframework.http.server.reactive.SslInfo;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * @author: fucong
 * @Date: 2022/8/17 11:05
 * @Description:
 */
public class DefaultSslInfo implements SslInfo {

    @Nullable
    private final String sessionId;

    @Nullable
    private final X509Certificate[] peerCertificates;


    DefaultSslInfo(@Nullable String sessionId, X509Certificate[] peerCertificates) {
        Assert.notNull(peerCertificates, "No SSL certificates");
        this.sessionId = sessionId;
        this.peerCertificates = peerCertificates;
    }

    DefaultSslInfo(SSLSession session) {
        Assert.notNull(session, "SSLSession is required");
        this.sessionId = initSessionId(session);
        this.peerCertificates = initCertificates(session);
    }

    @Nullable
    private static String initSessionId(SSLSession session) {
        byte[] bytes = session.getId();
        if (bytes == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String digit = Integer.toHexString(b);
            if (digit.length() < 2) {
                sb.append('0');
            }
            if (digit.length() > 2) {
                digit = digit.substring(digit.length() - 2);
            }
            sb.append(digit);
        }
        return sb.toString();
    }

    @Nullable
    private static X509Certificate[] initCertificates(SSLSession session) {
        Certificate[] certificates;
        try {
            certificates = session.getPeerCertificates();
        } catch (Throwable ex) {
            return null;
        }

        List<X509Certificate> result = new ArrayList<>(certificates.length);
        for (Certificate certificate : certificates) {
            if (certificate instanceof X509Certificate) {
                result.add((X509Certificate) certificate);
            }
        }
        return (!result.isEmpty() ? result.toArray(new X509Certificate[0]) : null);
    }

    @Override
    @Nullable
    public String getSessionId() {
        return this.sessionId;
    }

    @Override
    @Nullable
    public X509Certificate[] getPeerCertificates() {
        return this.peerCertificates;
    }

}

