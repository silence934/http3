package xyz.nyist.adapter;

import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author: fucong
 * @Date: 2022/8/15 14:25
 * @Description:
 */
public class Http3HeadersAdapter extends HttpHeaders {

    /**
     * The HTTP {@code Accept} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">Section 5.3.2 of RFC 7231</a>
     */
    public static final String ACCEPT = "accept";

    /**
     * The HTTP {@code Accept-Charset} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.3">Section 5.3.3 of RFC 7231</a>
     */
    public static final String ACCEPT_CHARSET = "accept-charset";

    /**
     * The HTTP {@code Accept-Encoding} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.4">Section 5.3.4 of RFC 7231</a>
     */
    public static final String ACCEPT_ENCODING = "accept-encoding";

    /**
     * The HTTP {@code Accept-Language} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.5">Section 5.3.5 of RFC 7231</a>
     */
    public static final String ACCEPT_LANGUAGE = "accept-language";

    /**
     * The HTTP {@code Accept-Patch} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5789#section-3.1">Section 3.1 of RFC 5789</a>
     * @since 5.3.6
     */
    public static final String ACCEPT_PATCH = "accept-patch";

    /**
     * The HTTP {@code Accept-Ranges} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7233#section-2.3">Section 5.3.5 of RFC 7233</a>
     */
    public static final String ACCEPT_RANGES = "accept-ranges";

    /**
     * The CORS {@code Access-Control-Allow-Credentials} response header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_ALLOW_CREDENTIALS = "access-control-allow-credentials";

    /**
     * The CORS {@code Access-Control-Allow-Headers} response header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_ALLOW_HEADERS = "access-control-allow-headers";

    /**
     * The CORS {@code Access-Control-Allow-Methods} response header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_ALLOW_METHODS = "access-control-allow-methods";

    /**
     * The CORS {@code Access-Control-Allow-Origin} response header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "access-control-allow-origin";

    /**
     * The CORS {@code Access-Control-Expose-Headers} response header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_EXPOSE_HEADERS = "access-control-expose-headers";

    /**
     * The CORS {@code Access-Control-Max-Age} response header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_MAX_AGE = "access-control-max-age";

    /**
     * The CORS {@code Access-Control-Request-Headers} request header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_REQUEST_HEADERS = "access-control-request-headers";

    /**
     * The CORS {@code Access-Control-Request-Method} request header field name.
     *
     * @see <a href="https://www.w3.org/TR/cors/">CORS W3C recommendation</a>
     */
    public static final String ACCESS_CONTROL_REQUEST_METHOD = "access-control-request-method";

    /**
     * The HTTP {@code Age} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.1">Section 5.1 of RFC 7234</a>
     */
    public static final String AGE = "age";

    /**
     * The HTTP {@code Allow} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.4.1">Section 7.4.1 of RFC 7231</a>
     */
    public static final String ALLOW = "allow";

    /**
     * The HTTP {@code Authorization} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.2">Section 4.2 of RFC 7235</a>
     */
    public static final String AUTHORIZATION = "authorization";

    /**
     * The HTTP {@code Cache-Control} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Section 5.2 of RFC 7234</a>
     */
    public static final String CACHE_CONTROL = "cache-control";

    /**
     * The HTTP {@code Connection} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-6.1">Section 6.1 of RFC 7230</a>
     */
    public static final String CONNECTION = "connection";

    /**
     * The HTTP {@code Content-Encoding} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Section 3.1.2.2 of RFC 7231</a>
     */
    public static final String CONTENT_ENCODING = "content-encoding";

    /**
     * The HTTP {@code Content-Disposition} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc6266">RFC 6266</a>
     */
    public static final String CONTENT_DISPOSITION = "content-disposition";

    /**
     * The HTTP {@code Content-Language} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.3.2">Section 3.1.3.2 of RFC 7231</a>
     */
    public static final String CONTENT_LANGUAGE = "content-language";

    /**
     * The HTTP {@code Content-Length} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">Section 3.3.2 of RFC 7230</a>
     */
    public static final String CONTENT_LENGTH = "content-length";

    /**
     * The HTTP {@code Content-Location} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.4.2">Section 3.1.4.2 of RFC 7231</a>
     */
    public static final String CONTENT_LOCATION = "content-location";

    /**
     * The HTTP {@code Content-Range} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7233#section-4.2">Section 4.2 of RFC 7233</a>
     */
    public static final String CONTENT_RANGE = "content-range";

    /**
     * The HTTP {@code Content-Type} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.1.5">Section 3.1.1.5 of RFC 7231</a>
     */
    public static final String CONTENT_TYPE = "content-type";

    /**
     * The HTTP {@code Cookie} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2109#section-4.3.4">Section 4.3.4 of RFC 2109</a>
     */
    public static final String COOKIE = "cookie";

    /**
     * The HTTP {@code Date} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.1.2">Section 7.1.1.2 of RFC 7231</a>
     */
    public static final String DATE = "date";

    /**
     * The HTTP {@code ETag} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7232#section-2.3">Section 2.3 of RFC 7232</a>
     */
    public static final String ETAG = "etag";

    /**
     * The HTTP {@code Expect} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.1.1">Section 5.1.1 of RFC 7231</a>
     */
    public static final String EXPECT = "expect";

    /**
     * The HTTP {@code Expires} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.3">Section 5.3 of RFC 7234</a>
     */
    public static final String EXPIRES = "expires";

    /**
     * The HTTP {@code From} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.5.1">Section 5.5.1 of RFC 7231</a>
     */
    public static final String FROM = "from";

    /**
     * The HTTP {@code Host} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-5.4">Section 5.4 of RFC 7230</a>
     */
    public static final String HOST = "host";

    /**
     * The HTTP {@code If-Match} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.1">Section 3.1 of RFC 7232</a>
     */
    public static final String IF_MATCH = "if-match";

    /**
     * The HTTP {@code If-Modified-Since} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.3">Section 3.3 of RFC 7232</a>
     */
    public static final String IF_MODIFIED_SINCE = "ff-modified-since";

    /**
     * The HTTP {@code If-None-Match} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.2">Section 3.2 of RFC 7232</a>
     */
    public static final String IF_NONE_MATCH = "if-none-match";

    /**
     * The HTTP {@code If-Range} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7233#section-3.2">Section 3.2 of RFC 7233</a>
     */
    public static final String IF_RANGE = "if-range";

    /**
     * The HTTP {@code If-Unmodified-Since} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.4">Section 3.4 of RFC 7232</a>
     */
    public static final String IF_UNMODIFIED_SINCE = "if-unmodified-since";

    /**
     * The HTTP {@code Last-Modified} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7232#section-2.2">Section 2.2 of RFC 7232</a>
     */
    public static final String LAST_MODIFIED = "last-modified";

    /**
     * The HTTP {@code Link} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5988">RFC 5988</a>
     */
    public static final String LINK = "link";

    /**
     * The HTTP {@code Location} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.2">Section 7.1.2 of RFC 7231</a>
     */
    public static final String LOCATION = "location";

    /**
     * The HTTP {@code Max-Forwards} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.1.2">Section 5.1.2 of RFC 7231</a>
     */
    public static final String MAX_FORWARDS = "max-forwards";

    /**
     * The HTTP {@code Origin} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc6454">RFC 6454</a>
     */
    public static final String ORIGIN = "origin";

    /**
     * The HTTP {@code Pragma} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.4">Section 5.4 of RFC 7234</a>
     */
    public static final String PRAGMA = "pragma";

    /**
     * The HTTP {@code Proxy-Authenticate} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.3">Section 4.3 of RFC 7235</a>
     */
    public static final String PROXY_AUTHENTICATE = "proxy-authenticate";

    /**
     * The HTTP {@code Proxy-Authorization} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.4">Section 4.4 of RFC 7235</a>
     */
    public static final String PROXY_AUTHORIZATION = "proxy-authorization";

    /**
     * The HTTP {@code Range} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7233#section-3.1">Section 3.1 of RFC 7233</a>
     */
    public static final String RANGE = "range";

    /**
     * The HTTP {@code Referer} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.5.2">Section 5.5.2 of RFC 7231</a>
     */
    public static final String REFERER = "referer";

    /**
     * The HTTP {@code Retry-After} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.3">Section 7.1.3 of RFC 7231</a>
     */
    public static final String RETRY_AFTER = "retry-after";

    /**
     * The HTTP {@code Server} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.4.2">Section 7.4.2 of RFC 7231</a>
     */
    public static final String SERVER = "server";

    /**
     * The HTTP {@code Set-Cookie} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2109#section-4.2.2">Section 4.2.2 of RFC 2109</a>
     */
    public static final String SET_COOKIE = "set-cookie";

    /**
     * The HTTP {@code Set-Cookie2} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2965">RFC 2965</a>
     */
    public static final String SET_COOKIE2 = "set-cookie2";

    /**
     * The HTTP {@code TE} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-4.3">Section 4.3 of RFC 7230</a>
     */
    public static final String TE = "te";

    /**
     * The HTTP {@code Trailer} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-4.4">Section 4.4 of RFC 7230</a>
     */
    public static final String TRAILER = "trailer";

    /**
     * The HTTP {@code Transfer-Encoding} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3.1">Section 3.3.1 of RFC 7230</a>
     */
    public static final String TRANSFER_ENCODING = "transfer-encoding";

    /**
     * The HTTP {@code Upgrade} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-6.7">Section 6.7 of RFC 7230</a>
     */
    public static final String UPGRADE = "upgrade";

    /**
     * The HTTP {@code User-Agent} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.5.3">Section 5.5.3 of RFC 7231</a>
     */
    public static final String USER_AGENT = "user-agent";

    /**
     * The HTTP {@code Vary} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.4">Section 7.1.4 of RFC 7231</a>
     */
    public static final String VARY = "vary";

    /**
     * The HTTP {@code Via} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-5.7.1">Section 5.7.1 of RFC 7230</a>
     */
    public static final String VIA = "via";

    /**
     * The HTTP {@code Warning} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.5">Section 5.5 of RFC 7234</a>
     */
    public static final String WARNING = "warning";

    /**
     * The HTTP {@code WWW-Authenticate} header field name.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.1">Section 4.1 of RFC 7235</a>
     */
    public static final String WWW_AUTHENTICATE = "www-authenticate";

    private static final ZoneId GMT = ZoneId.of("GMT");

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).withZone(GMT);

    private static final DecimalFormatSymbols DECIMAL_FORMAT_SYMBOLS = new DecimalFormatSymbols(Locale.ENGLISH);

    private static final DateTimeFormatter[] DATE_PARSERS = new DateTimeFormatter[]{
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", Locale.US).withZone(GMT)
    };

    public Http3HeadersAdapter(MultiValueMap<String, String> headers) {
        super(headers);
    }

    public static HttpHeaders readOnlyHttpHeaders(MultiValueMap<String, String> headers) {
        return new ReadOnlyHttp3Headers(headers);
    }


    @Override
    public void clearContentHeaders() {
        super.remove(CONTENT_DISPOSITION);
        super.remove(CONTENT_ENCODING);
        super.remove(CONTENT_LANGUAGE);
        super.remove(CONTENT_LENGTH);
        super.remove(CONTENT_LOCATION);
        super.remove(CONTENT_RANGE);
        super.remove(CONTENT_TYPE);
    }

    /**
     * Return the list of acceptable {@linkplain MediaType media types},
     * as specified by the {@code Accept} header.
     * <p>Returns an empty list when the acceptable media types are unspecified.
     */
    @Override
    public List<MediaType> getAccept() {
        return MediaType.parseMediaTypes(get(ACCEPT));
    }

    /**
     * Set the list of acceptable {@linkplain MediaType media types},
     * as specified by the {@code Accept} header.
     */
    @Override
    public void setAccept(List<MediaType> acceptableMediaTypes) {
        set(ACCEPT, MediaType.toString(acceptableMediaTypes));
    }

    /**
     * Return the language ranges from the {@literal "Accept-Language"} header.
     * <p>If you only need sorted, preferred locales only use
     * {@link #getAcceptLanguageAsLocales()} or if you need to filter based on
     * a list of supported locales you can pass the returned list to
     * {@link Locale#filter(List, Collection)}.
     *
     * @throws IllegalArgumentException if the value cannot be converted to a language range
     * @since 5.0
     */
    @Override
    public List<Locale.LanguageRange> getAcceptLanguage() {
        String value = getFirst(ACCEPT_LANGUAGE);
        return (StringUtils.hasText(value) ? Locale.LanguageRange.parse(value) : Collections.emptyList());
    }

    /**
     * Set the acceptable language ranges, as specified by the
     * {@literal Accept-Language} header.
     *
     * @since 5.0
     */
    @Override
    public void setAcceptLanguage(List<Locale.LanguageRange> languages) {
        Assert.notNull(languages, "LanguageRange List must not be null");
        DecimalFormat decimal = new DecimalFormat("0.0", DECIMAL_FORMAT_SYMBOLS);
        List<String> values = languages.stream()
                .map(range ->
                             range.getWeight() == Locale.LanguageRange.MAX_WEIGHT ?
                                     range.getRange() :
                                     range.getRange() + ";q=" + decimal.format(range.getWeight()))
                .collect(Collectors.toList());
        set(ACCEPT_LANGUAGE, toCommaDelimitedString(values));
    }

    /**
     * A variant of {@link #getAcceptLanguage()} that converts each
     * {@link java.util.Locale.LanguageRange} to a {@link Locale}.
     *
     * @return the locales or an empty list
     * @throws IllegalArgumentException if the value cannot be converted to a locale
     * @since 5.0
     */
    @Override
    public List<Locale> getAcceptLanguageAsLocales() {
        List<Locale.LanguageRange> ranges = getAcceptLanguage();
        if (ranges.isEmpty()) {
            return Collections.emptyList();
        }
        return ranges.stream()
                .map(range -> Locale.forLanguageTag(range.getRange()))
                .filter(locale -> StringUtils.hasText(locale.getDisplayName()))
                .collect(Collectors.toList());
    }

    /**
     * Variant of {@link #setAcceptLanguage(List)} using {@link Locale}'s.
     *
     * @since 5.0
     */
    @Override
    public void setAcceptLanguageAsLocales(List<Locale> locales) {
        setAcceptLanguage(locales.stream()
                                  .map(locale -> new Locale.LanguageRange(locale.toLanguageTag()))
                                  .collect(Collectors.toList()));
    }

    /**
     * Return the list of acceptable {@linkplain MediaType media types} for
     * {@code PATCH} methods, as specified by the {@code Accept-Patch} header.
     * <p>Returns an empty list when the acceptable media types are unspecified.
     *
     * @since 5.3.6
     */
    @Override
    public List<MediaType> getAcceptPatch() {
        return MediaType.parseMediaTypes(get(ACCEPT_PATCH));
    }

    /**
     * Set the list of acceptable {@linkplain MediaType media types} for
     * {@code PATCH} methods, as specified by the {@code Accept-Patch} header.
     *
     * @since 5.3.6
     */
    @Override
    public void setAcceptPatch(List<MediaType> mediaTypes) {
        set(ACCEPT_PATCH, MediaType.toString(mediaTypes));
    }

    /**
     * Return the value of the {@code Access-Control-Allow-Credentials} response header.
     */
    @Override
    public boolean getAccessControlAllowCredentials() {
        return Boolean.parseBoolean(getFirst(ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    /**
     * Set the (new) value of the {@code Access-Control-Allow-Credentials} response header.
     */
    @Override
    public void setAccessControlAllowCredentials(boolean allowCredentials) {
        set(ACCESS_CONTROL_ALLOW_CREDENTIALS, Boolean.toString(allowCredentials));
    }

    /**
     * Return the value of the {@code Access-Control-Allow-Headers} response header.
     */
    @Override
    public List<String> getAccessControlAllowHeaders() {
        return getValuesAsList(ACCESS_CONTROL_ALLOW_HEADERS);
    }

    /**
     * Set the (new) value of the {@code Access-Control-Allow-Headers} response header.
     */
    @Override
    public void setAccessControlAllowHeaders(List<String> allowedHeaders) {
        set(ACCESS_CONTROL_ALLOW_HEADERS, toCommaDelimitedString(allowedHeaders));
    }

    /**
     * Return the value of the {@code Access-Control-Allow-Methods} response header.
     */
    @Override
    public List<HttpMethod> getAccessControlAllowMethods() {
        List<HttpMethod> result = new ArrayList<>();
        String value = getFirst(ACCESS_CONTROL_ALLOW_METHODS);
        if (value != null) {
            String[] tokens = StringUtils.tokenizeToStringArray(value, ",");
            for (String token : tokens) {
                HttpMethod resolved = HttpMethod.resolve(token);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
        }
        return result;
    }

    /**
     * Set the (new) value of the {@code Access-Control-Allow-Methods} response header.
     */
    @Override
    public void setAccessControlAllowMethods(List<HttpMethod> allowedMethods) {
        set(ACCESS_CONTROL_ALLOW_METHODS, StringUtils.collectionToCommaDelimitedString(allowedMethods));
    }

    /**
     * Return the value of the {@code Access-Control-Allow-Origin} response header.
     */
    @Override
    @Nullable
    public String getAccessControlAllowOrigin() {
        return getFieldValues(ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    /**
     * Set the (new) value of the {@code Access-Control-Allow-Origin} response header.
     */
    @Override
    public void setAccessControlAllowOrigin(@Nullable String allowedOrigin) {
        setOrRemove(ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
    }

    /**
     * Return the value of the {@code Access-Control-Expose-Headers} response header.
     */
    @Override
    public List<String> getAccessControlExposeHeaders() {
        return getValuesAsList(ACCESS_CONTROL_EXPOSE_HEADERS);
    }

    /**
     * Set the (new) value of the {@code Access-Control-Expose-Headers} response header.
     */
    @Override
    public void setAccessControlExposeHeaders(List<String> exposedHeaders) {
        set(ACCESS_CONTROL_EXPOSE_HEADERS, toCommaDelimitedString(exposedHeaders));
    }

    /**
     * Return the value of the {@code Access-Control-Max-Age} response header.
     * <p>Returns -1 when the max age is unknown.
     */
    @Override
    public long getAccessControlMaxAge() {
        String value = getFirst(ACCESS_CONTROL_MAX_AGE);
        return (value != null ? Long.parseLong(value) : -1);
    }

    /**
     * Set the (new) value of the {@code Access-Control-Max-Age} response header.
     *
     * @since 5.2
     */
    @Override
    public void setAccessControlMaxAge(Duration maxAge) {
        set(ACCESS_CONTROL_MAX_AGE, Long.toString(maxAge.getSeconds()));
    }

    /**
     * Set the (new) value of the {@code Access-Control-Max-Age} response header.
     */
    @Override
    public void setAccessControlMaxAge(long maxAge) {
        set(ACCESS_CONTROL_MAX_AGE, Long.toString(maxAge));
    }

    /**
     * Return the value of the {@code Access-Control-Request-Headers} request header.
     */
    @Override
    public List<String> getAccessControlRequestHeaders() {
        return getValuesAsList(ACCESS_CONTROL_REQUEST_HEADERS);
    }

    /**
     * Set the (new) value of the {@code Access-Control-Request-Headers} request header.
     */
    @Override
    public void setAccessControlRequestHeaders(List<String> requestHeaders) {
        set(ACCESS_CONTROL_REQUEST_HEADERS, toCommaDelimitedString(requestHeaders));
    }

    /**
     * Return the value of the {@code Access-Control-Request-Method} request header.
     */
    @Override
    @Nullable
    public HttpMethod getAccessControlRequestMethod() {
        return HttpMethod.resolve(getFirst(ACCESS_CONTROL_REQUEST_METHOD));
    }

    /**
     * Set the (new) value of the {@code Access-Control-Request-Method} request header.
     */
    @Override
    public void setAccessControlRequestMethod(@Nullable HttpMethod requestMethod) {
        setOrRemove(ACCESS_CONTROL_REQUEST_METHOD, (requestMethod != null ? requestMethod.name() : null));
    }

    /**
     * Return the list of acceptable {@linkplain Charset charsets},
     * as specified by the {@code Accept-Charset} header.
     */
    @Override
    public List<Charset> getAcceptCharset() {
        String value = getFirst(ACCEPT_CHARSET);
        if (value != null) {
            String[] tokens = StringUtils.tokenizeToStringArray(value, ",");
            List<Charset> result = new ArrayList<>(tokens.length);
            for (String token : tokens) {
                int paramIdx = token.indexOf(';');
                String charsetName;
                if (paramIdx == -1) {
                    charsetName = token;
                } else {
                    charsetName = token.substring(0, paramIdx);
                }
                if (!"*".equals(charsetName)) {
                    result.add(Charset.forName(charsetName));
                }
            }
            return result;
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Set the list of acceptable {@linkplain Charset charsets},
     * as specified by the {@code Accept-Charset} header.
     */
    @Override
    public void setAcceptCharset(List<Charset> acceptableCharsets) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Charset charset : acceptableCharsets) {
            joiner.add(charset.name().toLowerCase(Locale.ENGLISH));
        }
        set(ACCEPT_CHARSET, joiner.toString());
    }

    /**
     * Return the set of allowed {@link HttpMethod HTTP methods},
     * as specified by the {@code Allow} header.
     * <p>Returns an empty set when the allowed methods are unspecified.
     */
    @Override
    public Set<HttpMethod> getAllow() {
        String value = getFirst(ALLOW);
        if (StringUtils.hasLength(value)) {
            String[] tokens = StringUtils.tokenizeToStringArray(value, ",");
            List<HttpMethod> result = new ArrayList<>(tokens.length);
            for (String token : tokens) {
                HttpMethod resolved = HttpMethod.resolve(token);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
            return EnumSet.copyOf(result);
        } else {
            return EnumSet.noneOf(HttpMethod.class);
        }
    }

    /**
     * Set the set of allowed {@link HttpMethod HTTP methods},
     * as specified by the {@code Allow} header.
     */
    @Override
    public void setAllow(Set<HttpMethod> allowedMethods) {
        set(ALLOW, StringUtils.collectionToCommaDelimitedString(allowedMethods));
    }

    /**
     * Set the value of the {@linkplain #AUTHORIZATION Authorization} header to
     * Basic Authentication based on the given username and password.
     * <p>Note that this method only supports characters in the
     * {@link StandardCharsets#ISO_8859_1 ISO-8859-1} character set.
     *
     * @param username the username
     * @param password the password
     * @throws IllegalArgumentException if either {@code user} or
     *                                  {@code password} contain characters that cannot be encoded to ISO-8859-1
     * @see #setBasicAuth(String)
     * @see #setBasicAuth(String, String, Charset)
     * @see #encodeBasicAuth(String, String, Charset)
     * @see <a href="https://tools.ietf.org/html/rfc7617">RFC 7617</a>
     * @since 5.1
     */
    @Override
    public void setBasicAuth(String username, String password) {
        setBasicAuth(username, password, null);
    }

    /**
     * Set the value of the {@linkplain #AUTHORIZATION Authorization} header to
     * Basic Authentication based on the given username and password.
     *
     * @param username the username
     * @param password the password
     * @param charset  the charset to use to convert the credentials into an octet
     *                 sequence. Defaults to {@linkplain StandardCharsets#ISO_8859_1 ISO-8859-1}.
     * @throws IllegalArgumentException if {@code username} or {@code password}
     *                                  contains characters that cannot be encoded to the given charset
     * @see #setBasicAuth(String)
     * @see #setBasicAuth(String, String)
     * @see #encodeBasicAuth(String, String, Charset)
     * @see <a href="https://tools.ietf.org/html/rfc7617">RFC 7617</a>
     * @since 5.1
     */
    @Override
    public void setBasicAuth(String username, String password, @Nullable Charset charset) {
        setBasicAuth(encodeBasicAuth(username, password, charset));
    }

    /**
     * Set the value of the {@linkplain #AUTHORIZATION Authorization} header to
     * Basic Authentication based on the given {@linkplain #encodeBasicAuth
     * encoded credentials}.
     * <p>Favor this method over {@link #setBasicAuth(String, String)} and
     * {@link #setBasicAuth(String, String, Charset)} if you wish to cache the
     * encoded credentials.
     *
     * @param encodedCredentials the encoded credentials
     * @throws IllegalArgumentException if supplied credentials string is
     *                                  {@code null} or blank
     * @see #setBasicAuth(String, String)
     * @see #setBasicAuth(String, String, Charset)
     * @see #encodeBasicAuth(String, String, Charset)
     * @see <a href="https://tools.ietf.org/html/rfc7617">RFC 7617</a>
     * @since 5.2
     */
    @Override
    public void setBasicAuth(String encodedCredentials) {
        Assert.hasText(encodedCredentials, "'encodedCredentials' must not be null or blank");
        set(AUTHORIZATION, "Basic " + encodedCredentials);
    }

    /**
     * Set the value of the {@linkplain #AUTHORIZATION Authorization} header to
     * the given Bearer token.
     *
     * @param token the Base64 encoded token
     * @see <a href="https://tools.ietf.org/html/rfc6750">RFC 6750</a>
     * @since 5.1
     */
    @Override
    public void setBearerAuth(String token) {
        set(AUTHORIZATION, "Bearer " + token);
    }

    /**
     * Return the value of the {@code Cache-Control} header.
     */
    @Override
    @Nullable
    public String getCacheControl() {
        return getFieldValues(CACHE_CONTROL);
    }

    /**
     * Set a configured {@link CacheControl} instance as the
     * new value of the {@code Cache-Control} header.
     *
     * @since 5.0.5
     */
    @Override
    public void setCacheControl(CacheControl cacheControl) {
        setOrRemove(CACHE_CONTROL, cacheControl.getHeaderValue());
    }

    /**
     * Set the (new) value of the {@code Cache-Control} header.
     */
    @Override
    public void setCacheControl(@Nullable String cacheControl) {
        setOrRemove(CACHE_CONTROL, cacheControl);
    }

    /**
     * Return the value of the {@code Connection} header.
     */
    @Override
    public List<String> getConnection() {
        return getValuesAsList(CONNECTION);
    }

    /**
     * Set the (new) value of the {@code Connection} header.
     */
    @Override
    public void setConnection(String connection) {
        set(CONNECTION, connection);
    }

    /**
     * Set the (new) value of the {@code Connection} header.
     */
    @Override
    public void setConnection(List<String> connection) {
        set(CONNECTION, toCommaDelimitedString(connection));
    }

    /**
     * Set the {@code Content-Disposition} header when creating a
     * {@code "multipart/form-data"} request.
     * <p>Applications typically would not set this header directly but
     * rather prepare a {@code MultiValueMap<String, Object>}, containing an
     * Object or a {@link org.springframework.core.io.Resource} for each part,
     * and then pass that to the {@code RestTemplate} or {@code WebClient}.
     *
     * @param name     the control name
     * @param filename the filename (may be {@code null})
     * @see #getContentDisposition()
     */
    @Override
    public void setContentDispositionFormData(String name, @Nullable String filename) {
        Assert.notNull(name, "Name must not be null");
        ContentDisposition.Builder disposition = ContentDisposition.formData().name(name);
        if (StringUtils.hasText(filename)) {
            disposition.filename(filename);
        }
        setContentDisposition(disposition.build());
    }

    /**
     * Return a parsed representation of the {@literal Content-Disposition} header.
     *
     * @see #setContentDisposition(ContentDisposition)
     * @since 5.0
     */
    @Override
    public ContentDisposition getContentDisposition() {
        String contentDisposition = getFirst(CONTENT_DISPOSITION);
        if (StringUtils.hasText(contentDisposition)) {
            return ContentDisposition.parse(contentDisposition);
        }
        return ContentDisposition.empty();
    }

    /**
     * Set the {@literal Content-Disposition} header.
     * <p>This could be used on a response to indicate if the content is
     * expected to be displayed inline in the browser or as an attachment to be
     * saved locally.
     * <p>It can also be used for a {@code "multipart/form-data"} request.
     * For more details see notes on {@link #setContentDispositionFormData}.
     *
     * @see #getContentDisposition()
     * @since 5.0
     */
    @Override
    public void setContentDisposition(ContentDisposition contentDisposition) {
        set(CONTENT_DISPOSITION, contentDisposition.toString());
    }

    /**
     * Get the first {@link Locale} of the content languages, as specified by the
     * {@code Content-Language} header.
     * <p>Use {@link #getValuesAsList(String)} if you need to get multiple content
     * languages.
     *
     * @return the first {@code Locale} of the content languages, or {@code null}
     * if unknown
     * @since 5.0
     */
    @Override
    @Nullable
    public Locale getContentLanguage() {
        return getValuesAsList(CONTENT_LANGUAGE)
                .stream()
                .findFirst()
                .map(Locale::forLanguageTag)
                .orElse(null);
    }

    /**
     * Set the {@link Locale} of the content language,
     * as specified by the {@literal Content-Language} header.
     * <p>Use {@code put(CONTENT_LANGUAGE, list)} if you need
     * to set multiple content languages.</p>
     *
     * @since 5.0
     */
    @Override
    public void setContentLanguage(@Nullable Locale locale) {
        setOrRemove(CONTENT_LANGUAGE, (locale != null ? locale.toLanguageTag() : null));
    }

    /**
     * Return the length of the body in bytes, as specified by the
     * {@code Content-Length} header.
     * <p>Returns -1 when the content-length is unknown.
     */
    @Override
    public long getContentLength() {
        String value = getFirst(CONTENT_LENGTH);
        return (value != null ? Long.parseLong(value) : -1);
    }

    /**
     * Set the length of the body in bytes, as specified by the
     * {@code Content-Length} header.
     */
    @Override
    public void setContentLength(long contentLength) {
        set(CONTENT_LENGTH, Long.toString(contentLength));
    }

    /**
     * Return the {@linkplain MediaType media type} of the body, as specified
     * by the {@code Content-Type} header.
     * <p>Returns {@code null} when the content-type is unknown.
     */
    @Override
    @Nullable
    public MediaType getContentType() {
        String value = getFirst(CONTENT_TYPE);
        return (StringUtils.hasLength(value) ? MediaType.parseMediaType(value) : null);
    }

    /**
     * Set the {@linkplain MediaType media type} of the body,
     * as specified by the {@code Content-Type} header.
     */
    @Override
    public void setContentType(@Nullable MediaType mediaType) {
        if (mediaType != null) {
            Assert.isTrue(!mediaType.isWildcardType(), "Content-Type cannot contain wildcard type '*'");
            Assert.isTrue(!mediaType.isWildcardSubtype(), "Content-Type cannot contain wildcard subtype '*'");
            set(CONTENT_TYPE, mediaType.toString());
        } else {
            remove(CONTENT_TYPE);
        }
    }

    /**
     * Return the date and time at which the message was created, as specified
     * by the {@code Date} header.
     * <p>The date is returned as the number of milliseconds since
     * January 1, 1970 GMT. Returns -1 when the date is unknown.
     *
     * @throws IllegalArgumentException if the value cannot be converted to a date
     */
    @Override
    public long getDate() {
        return getFirstDate(DATE);
    }

    /**
     * Set the date and time at which the message was created, as specified
     * by the {@code Date} header.
     *
     * @since 5.2
     */
    @Override
    public void setDate(ZonedDateTime date) {
        setZonedDateTime(DATE, date);
    }

    /**
     * Set the date and time at which the message was created, as specified
     * by the {@code Date} header.
     *
     * @since 5.2
     */
    @Override
    public void setDate(Instant date) {
        setInstant(DATE, date);
    }

    /**
     * Set the date and time at which the message was created, as specified
     * by the {@code Date} header.
     * <p>The date should be specified as the number of milliseconds since
     * January 1, 1970 GMT.
     */
    @Override
    public void setDate(long date) {
        setDate(DATE, date);
    }

    /**
     * Return the entity tag of the body, as specified by the {@code ETag} header.
     */
    @Override
    @Nullable
    public String getETag() {
        return getFirst(ETAG);
    }

    /**
     * Set the (new) entity tag of the body, as specified by the {@code ETag} header.
     */
    @Override
    public void setETag(@Nullable String etag) {
        if (etag != null) {
            Assert.isTrue(etag.startsWith("\"") || etag.startsWith("W/"),
                          "Invalid ETag: does not start with W/ or \"");
            Assert.isTrue(etag.endsWith("\""), "Invalid ETag: does not end with \"");
            set(ETAG, etag);
        } else {
            remove(ETAG);
        }
    }

    /**
     * Return the date and time at which the message is no longer valid,
     * as specified by the {@code Expires} header.
     * <p>The date is returned as the number of milliseconds since
     * January 1, 1970 GMT. Returns -1 when the date is unknown.
     *
     * @see #getFirstZonedDateTime(String)
     */
    @Override
    public long getExpires() {
        return getFirstDate(EXPIRES, false);
    }

    /**
     * Set the duration after which the message is no longer valid,
     * as specified by the {@code Expires} header.
     *
     * @since 5.0.5
     */
    @Override
    public void setExpires(ZonedDateTime expires) {
        setZonedDateTime(EXPIRES, expires);
    }

    /**
     * Set the date and time at which the message is no longer valid,
     * as specified by the {@code Expires} header.
     *
     * @since 5.2
     */
    @Override
    public void setExpires(Instant expires) {
        setInstant(EXPIRES, expires);
    }

    /**
     * Set the date and time at which the message is no longer valid,
     * as specified by the {@code Expires} header.
     * <p>The date should be specified as the number of milliseconds since
     * January 1, 1970 GMT.
     */
    @Override
    public void setExpires(long expires) {
        setDate(EXPIRES, expires);
    }

    /**
     * Return the value of the {@code Host} header, if available.
     * <p>If the header value does not contain a port, the
     * {@linkplain InetSocketAddress#getPort() port} in the returned address will
     * be {@code 0}.
     *
     * @since 5.0
     */
    @Override
    @Nullable
    public InetSocketAddress getHost() {
        String value = getFirst(HOST);
        if (value == null) {
            return null;
        }

        String host = null;
        int port = 0;
        int separator = (value.startsWith("[") ? value.indexOf(':', value.indexOf(']')) : value.lastIndexOf(':'));
        if (separator != -1) {
            host = value.substring(0, separator);
            String portString = value.substring(separator + 1);
            try {
                port = Integer.parseInt(portString);
            } catch (NumberFormatException ex) {
                // ignore
            }
        }

        if (host == null) {
            host = value;
        }
        return InetSocketAddress.createUnresolved(host, port);
    }

    /**
     * Set the (new) value of the {@code Host} header.
     * <p>If the given {@linkplain InetSocketAddress#getPort() port} is {@code 0},
     * the host header will only contain the
     * {@linkplain InetSocketAddress#getHostString() host name}.
     *
     * @since 5.0
     */
    @Override
    public void setHost(@Nullable InetSocketAddress host) {
        if (host != null) {
            String value = host.getHostString();
            int port = host.getPort();
            if (port != 0) {
                value = value + ":" + port;
            }
            set(HOST, value);
        } else {
            remove(HOST, null);
        }
    }

    /**
     * Return the value of the {@code If-Match} header.
     *
     * @throws IllegalArgumentException if parsing fails
     * @since 4.3
     */
    @Override
    public List<String> getIfMatch() {
        return getETagValuesAsList(IF_MATCH);
    }

    /**
     * Set the (new) value of the {@code If-Match} header.
     *
     * @since 4.3
     */
    @Override
    public void setIfMatch(String ifMatch) {
        set(IF_MATCH, ifMatch);
    }

    /**
     * Set the (new) value of the {@code If-Match} header.
     *
     * @since 4.3
     */
    @Override
    public void setIfMatch(List<String> ifMatchList) {
        set(IF_MATCH, toCommaDelimitedString(ifMatchList));
    }

    /**
     * Return the value of the {@code If-Modified-Since} header.
     * <p>The date is returned as the number of milliseconds since
     * January 1, 1970 GMT. Returns -1 when the date is unknown.
     *
     * @see #getFirstZonedDateTime(String)
     */
    @Override
    public long getIfModifiedSince() {
        return getFirstDate(IF_MODIFIED_SINCE, false);
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     *
     * @since 5.1.4
     */
    @Override
    public void setIfModifiedSince(ZonedDateTime ifModifiedSince) {
        setZonedDateTime(IF_MODIFIED_SINCE, ifModifiedSince.withZoneSameInstant(GMT));
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     *
     * @since 5.1.4
     */
    @Override
    public void setIfModifiedSince(Instant ifModifiedSince) {
        setInstant(IF_MODIFIED_SINCE, ifModifiedSince);
    }

    /**
     * Set the (new) value of the {@code If-Modified-Since} header.
     * <p>The date should be specified as the number of milliseconds since
     * January 1, 1970 GMT.
     */
    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        setDate(IF_MODIFIED_SINCE, ifModifiedSince);
    }

    /**
     * Return the value of the {@code If-None-Match} header.
     *
     * @throws IllegalArgumentException if parsing fails
     */
    @Override
    public List<String> getIfNoneMatch() {
        return getETagValuesAsList(IF_NONE_MATCH);
    }

    /**
     * Set the (new) value of the {@code If-None-Match} header.
     */
    @Override
    public void setIfNoneMatch(String ifNoneMatch) {
        set(IF_NONE_MATCH, ifNoneMatch);
    }

    /**
     * Set the (new) values of the {@code If-None-Match} header.
     */
    @Override
    public void setIfNoneMatch(List<String> ifNoneMatchList) {
        set(IF_NONE_MATCH, toCommaDelimitedString(ifNoneMatchList));
    }

    /**
     * Return the value of the {@code If-Unmodified-Since} header.
     * <p>The date is returned as the number of milliseconds since
     * January 1, 1970 GMT. Returns -1 when the date is unknown.
     *
     * @see #getFirstZonedDateTime(String)
     * @since 4.3
     */
    @Override
    public long getIfUnmodifiedSince() {
        return getFirstDate(IF_UNMODIFIED_SINCE, false);
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     *
     * @since 5.1.4
     */
    @Override
    public void setIfUnmodifiedSince(ZonedDateTime ifUnmodifiedSince) {
        setZonedDateTime(IF_UNMODIFIED_SINCE, ifUnmodifiedSince.withZoneSameInstant(GMT));
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     *
     * @since 5.1.4
     */
    @Override
    public void setIfUnmodifiedSince(Instant ifUnmodifiedSince) {
        setInstant(IF_UNMODIFIED_SINCE, ifUnmodifiedSince);
    }

    /**
     * Set the (new) value of the {@code If-Unmodified-Since} header.
     * <p>The date should be specified as the number of milliseconds since
     * January 1, 1970 GMT.
     *
     * @since 4.3
     */
    @Override
    public void setIfUnmodifiedSince(long ifUnmodifiedSince) {
        setDate(IF_UNMODIFIED_SINCE, ifUnmodifiedSince);
    }

    /**
     * Return the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     * <p>The date is returned as the number of milliseconds since
     * January 1, 1970 GMT. Returns -1 when the date is unknown.
     *
     * @see #getFirstZonedDateTime(String)
     */
    @Override
    public long getLastModified() {
        return getFirstDate(LAST_MODIFIED, false);
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     *
     * @since 5.1.4
     */
    @Override
    public void setLastModified(ZonedDateTime lastModified) {
        setZonedDateTime(LAST_MODIFIED, lastModified.withZoneSameInstant(GMT));
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     *
     * @since 5.1.4
     */
    @Override
    public void setLastModified(Instant lastModified) {
        setInstant(LAST_MODIFIED, lastModified);
    }

    /**
     * Set the time the resource was last changed, as specified by the
     * {@code Last-Modified} header.
     * <p>The date should be specified as the number of milliseconds since
     * January 1, 1970 GMT.
     */
    @Override
    public void setLastModified(long lastModified) {
        setDate(LAST_MODIFIED, lastModified);
    }

    /**
     * Return the (new) location of a resource
     * as specified by the {@code Location} header.
     * <p>Returns {@code null} when the location is unknown.
     */
    @Override
    @Nullable
    public URI getLocation() {
        String value = getFirst(LOCATION);
        return (value != null ? URI.create(value) : null);
    }

    /**
     * Set the (new) location of a resource,
     * as specified by the {@code Location} header.
     */
    @Override
    public void setLocation(@Nullable URI location) {
        setOrRemove(LOCATION, (location != null ? location.toASCIIString() : null));
    }

    /**
     * Return the value of the {@code Origin} header.
     */
    @Override
    @Nullable
    public String getOrigin() {
        return getFirst(ORIGIN);
    }

    /**
     * Set the (new) value of the {@code Origin} header.
     */
    @Override
    public void setOrigin(@Nullable String origin) {
        setOrRemove(ORIGIN, origin);
    }

    /**
     * Return the value of the {@code Pragma} header.
     */
    @Override
    @Nullable
    public String getPragma() {
        return getFirst(PRAGMA);
    }

    /**
     * Set the (new) value of the {@code Pragma} header.
     */
    @Override
    public void setPragma(@Nullable String pragma) {
        setOrRemove(PRAGMA, pragma);
    }

    /**
     * Return the value of the {@code Range} header.
     * <p>Returns an empty list when the range is unknown.
     */
    @Override
    public List<HttpRange> getRange() {
        String value = getFirst(RANGE);
        return HttpRange.parseRanges(value);
    }

    /**
     * Sets the (new) value of the {@code Range} header.
     */
    @Override
    public void setRange(List<HttpRange> ranges) {
        String value = HttpRange.toString(ranges);
        set(RANGE, value);
    }

    /**
     * Return the value of the {@code Upgrade} header.
     */
    @Override
    @Nullable
    public String getUpgrade() {
        return getFirst(UPGRADE);
    }

    /**
     * Set the (new) value of the {@code Upgrade} header.
     */
    @Override
    public void setUpgrade(@Nullable String upgrade) {
        setOrRemove(UPGRADE, upgrade);
    }

    /**
     * Return the request header names subject to content negotiation.
     *
     * @since 4.3
     */
    @Override
    public List<String> getVary() {
        return getValuesAsList(VARY);
    }

    /**
     * Set the request header names (e.g. "Accept-Language") for which the
     * response is subject to content negotiation and variances based on the
     * value of those request headers.
     *
     * @param requestHeaders the request header names
     * @since 4.3
     */
    @Override
    public void setVary(List<String> requestHeaders) {
        set(VARY, toCommaDelimitedString(requestHeaders));
    }

    /**
     * Set the given date under the given header name after formatting it as a string
     * using the RFC-1123 date-time formatter. The equivalent of
     * {@link #set(String, String)} but for date headers.
     *
     * @since 5.0
     */
    @Override
    public void setZonedDateTime(String headerName, ZonedDateTime date) {
        set(headerName, DATE_FORMATTER.format(date));
    }

    /**
     * Set the given date under the given header name after formatting it as a string
     * using the RFC-1123 date-time formatter. The equivalent of
     * {@link #set(String, String)} but for date headers.
     *
     * @since 5.1.4
     */
    @Override
    public void setInstant(String headerName, Instant date) {
        setZonedDateTime(headerName, ZonedDateTime.ofInstant(date, GMT));
    }


    /**
     * Set the given header value, or remove the header if {@code null}.
     *
     * @param headerName  the header name
     * @param headerValue the header value, or {@code null} for none
     */
    private void setOrRemove(String headerName, @Nullable String headerValue) {
        if (headerValue != null) {
            set(headerName, headerValue);
        } else {
            remove(headerName);
        }
    }

    /**
     * Parse the first header value for the given header name as a date,
     * return -1 if there is no value or also in case of an invalid value
     * (if {@code rejectInvalid=false}), or raise {@link IllegalArgumentException}
     * if the value cannot be parsed as a date.
     *
     * @param headerName    the header name
     * @param rejectInvalid whether to reject invalid values with an
     *                      {@link IllegalArgumentException} ({@code true}) or rather return -1
     *                      in that case ({@code false})
     * @return the parsed date header, or -1 if none (or invalid)
     */
    private long getFirstDate(String headerName, boolean rejectInvalid) {
        ZonedDateTime zonedDateTime = getFirstZonedDateTime(headerName, rejectInvalid);
        return (zonedDateTime != null ? zonedDateTime.toInstant().toEpochMilli() : -1);
    }

    /**
     * Parse the first header value for the given header name as a date,
     * return {@code null} if there is no value or also in case of an invalid value
     * (if {@code rejectInvalid=false}), or raise {@link IllegalArgumentException}
     * if the value cannot be parsed as a date.
     *
     * @param headerName    the header name
     * @param rejectInvalid whether to reject invalid values with an
     *                      {@link IllegalArgumentException} ({@code true}) or rather return {@code null}
     *                      in that case ({@code false})
     * @return the parsed date header, or {@code null} if none (or invalid)
     */
    @Nullable
    private ZonedDateTime getFirstZonedDateTime(String headerName, boolean rejectInvalid) {
        String headerValue = getFirst(headerName);
        if (headerValue == null) {
            // No header value sent at all
            return null;
        }
        if (headerValue.length() >= 3) {
            // Short "0" or "-1" like values are never valid HTTP date headers...
            // Let's only bother with DateTimeFormatter parsing for long enough values.

            // See https://stackoverflow.com/questions/12626699/if-modified-since-http-header-passed-by-ie9-includes-length
            int parametersIndex = headerValue.indexOf(';');
            if (parametersIndex != -1) {
                headerValue = headerValue.substring(0, parametersIndex);
            }

            for (DateTimeFormatter dateFormatter : DATE_PARSERS) {
                try {
                    return ZonedDateTime.parse(headerValue, dateFormatter);
                } catch (DateTimeParseException ex) {
                    // ignore
                }
            }

        }
        if (rejectInvalid) {
            throw new IllegalArgumentException("Cannot parse date value \"" + headerValue +
                                                       "\" for \"" + headerName + "\" header");
        }
        return null;
    }

}
