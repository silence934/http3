package xyz.nyist.core;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;
import io.netty.util.internal.InternalThreadLocalMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;

/**
 * @author: fucong
 * @Date: 2022/8/12 11:01
 * @Description:
 */
public class Http3Util {


    public static boolean is100ContinueExpected(Http3HeadersFrame headersFrame) {
        return headersFrame.headers().contains(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE, true);
    }

    public static boolean isKeepAlive(Http3Headers headers) {
        //todo http3的 keepAlive不知道是啥样的
        return false;
    }


    public static long getContentLength(Http3HeadersFrame headersFrame) {
        Long value = headersFrame.headers().getLong(HttpHeaderNames.CONTENT_LENGTH);

        if (value != null) {
            return value;
        }
        // Otherwise we don't.
        throw new NumberFormatException("header not found: " + HttpHeaderNames.CONTENT_LENGTH);
    }


    public static long getContentLength(Http3HeadersFrame headersFrame, long defaultValue) {
        Long value = headersFrame.headers().getLong(HttpHeaderNames.CONTENT_LENGTH);

        if (value != null) {
            return value;
        }


        // Otherwise we don't.
        return defaultValue;
    }


    public static int getContentLength(Http3HeadersFrame headersFrame, int defaultValue) {
        Long value = headersFrame.headers().getLong(HttpHeaderNames.CONTENT_LENGTH);

        if (value != null) {
            return value.intValue();
        }

        // Otherwise we don't.
        return defaultValue;
    }

    public static boolean isContentLengthSet(Http3HeadersFrame headersFrame) {
        return headersFrame.headers().contains(HttpHeaderNames.CONTENT_LENGTH);
    }

    public static boolean isTransferEncodingChunked(Http3HeadersFrame headersFrame) {
        return headersFrame.headers().contains(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED, true);
    }


    public static void setTransferEncodingChunked(Http3HeadersFrame headersFrame, boolean chunked) {
        Http3Headers headers = headersFrame.headers();
        if (chunked) {
            headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            headers.remove(HttpHeaderNames.CONTENT_LENGTH);
        } else {
            List<CharSequence> encodings = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
            if (encodings.isEmpty()) {
                return;
            }
            List<CharSequence> values = new ArrayList<>(encodings);
            values.removeIf(HttpHeaderValues.CHUNKED::contentEqualsIgnoreCase);
            if (values.isEmpty()) {
                headers.remove(HttpHeaderNames.TRANSFER_ENCODING);
            } else {
                headers.set(HttpHeaderNames.TRANSFER_ENCODING, values);
            }
        }
    }


    public static HttpHeaders addHttp3ToHttpHeaders(final long streamId, Http3Headers inputHeaders) throws Http3Exception {
        HttpHeaders outputHeaders = new DefaultHttpHeaders();
        Http3ToHttpHeaderTranslator translator = new Http3ToHttpHeaderTranslator(streamId, outputHeaders, false);
        translator.translateHeaders(inputHeaders);

        outputHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
        outputHeaders.remove(HttpHeaderNames.TRAILER);
        outputHeaders.set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
        return outputHeaders;
    }

    private static Http3Exception streamError(long streamId, Http3ErrorCode error, String msg, Throwable cause) {
        return new Http3Exception(error, streamId + ": " + msg, cause);
    }

    public static final class Http3ToHttpHeaderTranslator {

        /**
         * Translations from HTTP/3 header name to the HTTP/1.x equivalent.
         */
        private static final CharSequenceMap<AsciiString>
                REQUEST_HEADER_TRANSLATIONS = new CharSequenceMap<>();

        private static final CharSequenceMap<AsciiString>
                RESPONSE_HEADER_TRANSLATIONS = new CharSequenceMap<>();

        static {
            RESPONSE_HEADER_TRANSLATIONS.add(Http3Headers.PseudoHeaderName.AUTHORITY.value(),
                                             HttpHeaderNames.HOST);
            RESPONSE_HEADER_TRANSLATIONS.add(Http3Headers.PseudoHeaderName.SCHEME.value(),
                                             HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
            REQUEST_HEADER_TRANSLATIONS.add(RESPONSE_HEADER_TRANSLATIONS);
            RESPONSE_HEADER_TRANSLATIONS.add(Http3Headers.PseudoHeaderName.PATH.value(),
                                             HttpConversionUtil.ExtensionHeaderNames.PATH.text());
        }

        private final long streamId;

        private final HttpHeaders output;

        private final CharSequenceMap<AsciiString> translations;

        /**
         * Create a new instance
         *
         * @param output  The HTTP/1.x headers object to store the results of the translation
         * @param request if {@code true}, translates headers using the request translation map. Otherwise uses the
         *                response translation map.
         */
        Http3ToHttpHeaderTranslator(long streamId, HttpHeaders output, boolean request) {
            this.streamId = streamId;
            this.output = output;
            translations = request ? REQUEST_HEADER_TRANSLATIONS : RESPONSE_HEADER_TRANSLATIONS;
        }

        void translateHeaders(Iterable<Map.Entry<CharSequence, CharSequence>> inputHeaders) throws Http3Exception {
            // lazily created as needed
            StringBuilder cookies = null;

            for (Map.Entry<CharSequence, CharSequence> entry : inputHeaders) {
                final CharSequence name = entry.getKey();
                final CharSequence value = entry.getValue();
                AsciiString translatedName = translations.get(name);
                if (translatedName != null) {
                    output.add(translatedName, AsciiString.of(value));
                } else if (!Http3Headers.PseudoHeaderName.isPseudoHeader(name)) {
                    // https://tools.ietf.org/html/rfc7540#section-8.1.2.3
                    // All headers that start with ':' are only valid in HTTP/3 context
                    if (name.length() == 0 || name.charAt(0) == ':') {
                        throw streamError(streamId, Http3ErrorCode.H3_MESSAGE_ERROR,
                                          "Invalid HTTP/3 header '" + name + "' encountered in translation to HTTP/1.x",
                                          null);
                    }
                    if (COOKIE.equals(name)) {
                        // combine the cookie values into 1 header entry.
                        // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
                        if (cookies == null) {
                            cookies = InternalThreadLocalMap.get().stringBuilder();
                        } else if (cookies.length() > 0) {
                            cookies.append("; ");
                        }
                        cookies.append(value);
                    } else {
                        output.add(name, value);
                    }
                }
            }
            if (cookies != null) {
                output.add(COOKIE, cookies.toString());
            }
        }

    }

}
