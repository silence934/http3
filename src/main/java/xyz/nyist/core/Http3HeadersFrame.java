/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package xyz.nyist.core;

import io.netty.handler.codec.Headers;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.util.internal.ObjectUtil;
import org.springframework.util.MultiValueMap;

import java.util.*;
import java.util.stream.Collectors;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * See <a href="https://tools.ietf.org/html/draft-ietf-quic-http-32#section-7.2.2">HEADERS</a>.
 */
public interface Http3HeadersFrame extends Http3RequestStreamFrame, Http3PushStreamFrame, Iterable<Map.Entry<String, String>> {


    /**
     * Helps to format HTTP header values, as HTTP header values themselves can
     * contain comma-separated values, can become confusing with regular
     * {@link Map} formatting that also uses commas between entries.
     *
     * @param headers the headers to format
     * @return the headers to a String
     * @since 5.1.4
     */
    static String formatHeaders(MultiValueMap<String, String> headers) {
        return headers.entrySet().stream()
                .map(entry -> {
                    List<String> values = entry.getValue();
                    return entry.getKey() + ":" + (values.size() == 1 ?
                            "\"" + values.get(0) + "\"" :
                            values.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
                })
                .collect(Collectors.joining(", ", "[", "]"));
    }

    @Override
    default long type() {
        return Http3CodecUtils.HTTP3_HEADERS_FRAME_TYPE;
    }

    /**
     * Returns the carried headers.
     *
     * @return the carried headers.
     */
    Http3Headers headers();

    String get(String name);

    /**
     * Returns the value of a header with the specified name.  If there are
     * more than one values for the specified name, the first value is returned.
     *
     * @param name The name of the header to search
     * @return The first header value or {@code null} if there is no such header
     * @see #getAsString(CharSequence)
     */
    default String get(CharSequence name) {
        return get(name.toString());
    }

    /**
     * Returns the value of a header with the specified name.  If there are
     * more than one values for the specified name, the first value is returned.
     *
     * @param name The name of the header to search
     * @return The first header value or {@code defaultValue} if there is no such header
     */
    default String get(CharSequence name, String defaultValue) {
        String value = get(name);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }

    /**
     * Returns the integer value of a header with the specified name. If there are more than one values for the
     * specified name, the first value is returned.
     *
     * @param name the name of the header to search
     * @return the first header value if the header is found and its value is an integer. {@code null} if there's no
     * such header or its value is not an integer.
     */
    Integer getInt(CharSequence name);

    /**
     * Returns the integer value of a header with the specified name. If there are more than one values for the
     * specified name, the first value is returned.
     *
     * @param name         the name of the header to search
     * @param defaultValue the default value
     * @return the first header value if the header is found and its value is an integer. {@code defaultValue} if
     * there's no such header or its value is not an integer.
     */
    int getInt(CharSequence name, int defaultValue);

    /**
     * Returns the short value of a header with the specified name. If there are more than one values for the
     * specified name, the first value is returned.
     *
     * @param name the name of the header to search
     * @return the first header value if the header is found and its value is a short. {@code null} if there's no
     * such header or its value is not a short.
     */
    Short getShort(CharSequence name);

    /**
     * Returns the short value of a header with the specified name. If there are more than one values for the
     * specified name, the first value is returned.
     *
     * @param name         the name of the header to search
     * @param defaultValue the default value
     * @return the first header value if the header is found and its value is a short. {@code defaultValue} if
     * there's no such header or its value is not a short.
     */
    short getShort(CharSequence name, short defaultValue);

    /**
     * Returns the date value of a header with the specified name. If there are more than one values for the
     * specified name, the first value is returned.
     *
     * @param name the name of the header to search
     * @return the first header value if the header is found and its value is a date. {@code null} if there's no
     * such header or its value is not a date.
     */
    Long getTimeMillis(CharSequence name);

    /**
     * Returns the date value of a header with the specified name. If there are more than one values for the
     * specified name, the first value is returned.
     *
     * @param name         the name of the header to search
     * @param defaultValue the default value
     * @return the first header value if the header is found and its value is a date. {@code defaultValue} if
     * there's no such header or its value is not a date.
     */
    long getTimeMillis(CharSequence name, long defaultValue);

    /**
     * @see #getAll(CharSequence)
     */
    List<String> getAll(String name);

    /**
     * Returns the values of headers with the specified name
     *
     * @param name The name of the headers to search
     * @return A {@link List} of header values which will be empty if no values
     * are found
     * @see #getAllAsString(CharSequence)
     */
    default List<String> getAll(CharSequence name) {
        return getAll(name.toString());
    }

    /**
     * Returns a new {@link List} that contains all headers in this object.  Note that modifying the
     * returned {@link List} will not affect the state of this object.  If you intend to enumerate over the header
     * entries only, use {@link #iterator()} instead, which has much less overhead.
     *
     * @see #iteratorCharSequence()
     */
    List<Map.Entry<String, String>> entries();

    /**
     * @see #contains(CharSequence)
     */
    boolean contains(String name);

    /**
     * @deprecated It is preferred to use {@link #iteratorCharSequence()} unless you need {@link String}.
     * If {@link String} is required then use {@link #iteratorAsString()}.
     */
    @Deprecated
    @Override
    Iterator<Map.Entry<String, String>> iterator();

    /**
     * @return Iterator over the name/value header pairs.
     */
    Iterator<Map.Entry<CharSequence, CharSequence>> iteratorCharSequence();

    /**
     * Equivalent to {@link #getAll(String)} but it is possible that no intermediate list is generated.
     *
     * @param name the name of the header to retrieve
     * @return an {@link Iterator} of header values corresponding to {@code name}.
     */
    default Iterator<String> valueStringIterator(CharSequence name) {
        return getAll(name).iterator();
    }

    /**
     * Equivalent to {@link #getAll(String)} but it is possible that no intermediate list is generated.
     *
     * @param name the name of the header to retrieve
     * @return an {@link Iterator} of header values corresponding to {@code name}.
     */
    default Iterator<? extends CharSequence> valueCharSequenceIterator(CharSequence name) {
        return valueStringIterator(name);
    }

    /**
     * Checks to see if there is a header with the specified name
     *
     * @param name The name of the header to search for
     * @return True if at least one header is found
     */
    default boolean contains(CharSequence name) {
        return contains(name.toString());
    }

    /**
     * Checks if no header exists.
     */
    boolean isEmpty();

    /**
     * Returns the number of headers in this object.
     */
    int size();

    /**
     * Returns a new {@link Set} that contains the names of all headers in this object.  Note that modifying the
     * returned {@link Set} will not affect the state of this object.  If you intend to enumerate over the header
     * entries only, use {@link #iterator()} instead, which has much less overhead.
     */
    Set<String> names();

    /**
     * @see #add(CharSequence, Object)
     */
    Http3HeadersFrame add(String name, Object value);

    /**
     * Adds a new header with the specified name and value.
     * <p>
     * If the specified value is not a {@link String}, it is converted
     * into a {@link String} by {@link Object#toString()}, except in the cases
     * of {@link Date} and {@link Calendar}, which are formatted to the date
     * format defined in <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.3.1">RFC2616</a>.
     *
     * @param name  The name of the header being added
     * @param value The value of the header being added
     * @return {@code this}
     */
    default Http3HeadersFrame add(CharSequence name, Object value) {
        return add(name.toString(), value);
    }

    /**
     * @see #add(CharSequence, Iterable)
     */
    Http3HeadersFrame add(String name, Iterable<?> values);

    /**
     * Adds a new header with the specified name and values.
     * <p>
     * This getMethod can be represented approximately as the following code:
     * <pre>
     * for (Object v: values) {
     *     if (v == null) {
     *         break;
     *     }
     *     headers.add(name, v);
     * }
     * </pre>
     *
     * @param name   The name of the headers being set
     * @param values The values of the headers being set
     * @return {@code this}
     */
    default Http3HeadersFrame add(CharSequence name, Iterable<?> values) {
        return add(name.toString(), values);
    }

    /**
     * Adds all header entries of the specified {@code headers}.
     *
     * @return {@code this}
     */
    default Http3HeadersFrame add(HttpHeaders headers) {
        ObjectUtil.checkNotNull(headers, "headers");
        for (Map.Entry<String, String> e : headers) {
            add(e.getKey(), e.getValue());
        }
        return this;
    }

    /**
     * Add the {@code name} to {@code value}.
     *
     * @param name  The name to modify
     * @param value The value
     * @return {@code this}
     */
    Http3HeadersFrame addInt(CharSequence name, int value);

    /**
     * Add the {@code name} to {@code value}.
     *
     * @param name  The name to modify
     * @param value The value
     * @return {@code this}
     */
    Http3HeadersFrame addShort(CharSequence name, short value);

    /**
     * @see #set(CharSequence, Object)
     */
    Http3HeadersFrame set(String name, Object value);

    /**
     * Sets a header with the specified name and value.
     * <p>
     * If there is an existing header with the same name, it is removed.
     * If the specified value is not a {@link String}, it is converted into a
     * {@link String} by {@link Object#toString()}, except for {@link Date}
     * and {@link Calendar}, which are formatted to the date format defined in
     * <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.3.1">RFC2616</a>.
     *
     * @param name  The name of the header being set
     * @param value The value of the header being set
     * @return {@code this}
     */
    default Http3HeadersFrame set(CharSequence name, Object value) {
        return set(name.toString(), value);
    }

    /**
     * @see #set(CharSequence, Iterable)
     */
    Http3HeadersFrame set(String name, Iterable<?> values);

    /**
     * Sets a header with the specified name and values.
     * <p>
     * If there is an existing header with the same name, it is removed.
     * This getMethod can be represented approximately as the following code:
     * <pre>
     * headers.remove(name);
     * for (Object v: values) {
     *     if (v == null) {
     *         break;
     *     }
     *     headers.add(name, v);
     * }
     * </pre>
     *
     * @param name   The name of the headers being set
     * @param values The values of the headers being set
     * @return {@code this}
     */
    default Http3HeadersFrame set(CharSequence name, Iterable<?> values) {
        return set(name.toString(), values);
    }

    /**
     * Cleans the current header entries and copies all header entries of the specified {@code headers}.
     *
     * @return {@code this}
     */
    default Http3HeadersFrame set(HttpHeaders headers) {
        checkNotNull(headers, "headers");

        clear();

        if (headers.isEmpty()) {
            return this;
        }

        for (Map.Entry<String, String> entry : headers) {
            add(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * Retains all current headers but calls {@link #set(String, Object)} for each entry in {@code headers}
     *
     * @param headers The headers used to {@link #set(String, Object)} values in this instance
     * @return {@code this}
     */
    default Http3HeadersFrame setAll(HttpHeaders headers) {
        checkNotNull(headers, "headers");

        if (headers.isEmpty()) {
            return this;
        }

        for (Map.Entry<String, String> entry : headers) {
            set(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * Set the {@code name} to {@code value}. This will remove all previous values associated with {@code name}.
     *
     * @param name  The name to modify
     * @param value The value
     * @return {@code this}
     */
    Http3HeadersFrame setInt(CharSequence name, int value);

    /**
     * Set the {@code name} to {@code value}. This will remove all previous values associated with {@code name}.
     *
     * @param name  The name to modify
     * @param value The value
     * @return {@code this}
     */
    Http3HeadersFrame setShort(CharSequence name, short value);

    /**
     * @see #remove(CharSequence)
     */
    Http3HeadersFrame remove(String name);

    /**
     * Removes the header with the specified name.
     *
     * @param name The name of the header to remove
     * @return {@code this}
     */
    default Http3HeadersFrame remove(CharSequence name) {
        return remove(name.toString());
    }

    /**
     * Removes all headers from this {@link HttpMessage}.
     *
     * @return {@code this}
     */
    Http3HeadersFrame clear();

    /**
     * @see #contains(CharSequence, CharSequence, boolean)
     */
    default boolean contains(String name, String value, boolean ignoreCase) {
        Iterator<String> valueIterator = valueStringIterator(name);
        if (ignoreCase) {
            while (valueIterator.hasNext()) {
                if (valueIterator.next().equalsIgnoreCase(value)) {
                    return true;
                }
            }
        } else {
            while (valueIterator.hasNext()) {
                if (valueIterator.next().equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists, {@code false} otherwise.
     * This also handles multiple values that are separated with a {@code ,}.
     * <p>
     * If {@code ignoreCase} is {@code true} then a case insensitive compare is done on the value.
     *
     * @param name       the name of the header to find
     * @param value      the value of the header to find
     * @param ignoreCase {@code true} then a case insensitive compare is run to compare values.
     *                   otherwise a case sensitive compare is run to compare values.
     */
    boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase);

    /**
     * {@link Headers#get(Object)} and convert the result to a {@link String}.
     *
     * @param name the name of the header to retrieve
     * @return the first header value if the header is found. {@code null} if there's no such header.
     */
    default String getAsString(CharSequence name) {
        return get(name);
    }

    /**
     * {@link Headers#getAll(Object)} and convert each element of {@link List} to a {@link String}.
     *
     * @param name the name of the header to retrieve
     * @return a {@link List} of header values or an empty {@link List} if no values are found.
     */
    default List<String> getAllAsString(CharSequence name) {
        return getAll(name);
    }

    /**
     * {@link Iterator} that converts each {@link Map.Entry}'s key and value to a {@link String}.
     */
    default Iterator<Map.Entry<String, String>> iteratorAsString() {
        return iterator();
    }

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists, {@code false} otherwise.
     * <p>
     * If {@code ignoreCase} is {@code true} then a case insensitive compare is done on the value.
     *
     * @param name       the name of the header to find
     * @param value      the value of the header to find
     * @param ignoreCase {@code true} then a case insensitive compare is run to compare values.
     *                   otherwise a case sensitive compare is run to compare values.
     */
    default boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        return contains(name.toString(), value.toString(), ignoreCase);
    }

}
