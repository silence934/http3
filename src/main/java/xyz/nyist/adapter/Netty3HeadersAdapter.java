/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.nyist.adapter;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import xyz.nyist.core.CharSequenceMap;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.core.HttpConversionUtil;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.TE;
import static io.netty.handler.codec.http.HttpHeaderValues.TRAILERS;
import static io.netty.util.AsciiString.*;
import static io.netty.util.internal.StringUtil.unescapeCsvFields;

/**
 * {@code MultiValueMap} implementation for wrapping Netty HTTP headers.
 *
 * <p>There is a duplicate of this class in the client package!
 *
 * @author Brian Clozel
 * @since 5.1.1
 */
class Netty3HeadersAdapter implements MultiValueMap<String, String> {


    /**
     * Translations from HTTP/3 header name to the HTTP/1.x equivalent.
     */
    private static final CharSequenceMap<AsciiString>
            REQUEST_HEADER_TRANSLATIONS = new CharSequenceMap<>();

    private static final CharSequenceMap<AsciiString>
            RESPONSE_HEADER_TRANSLATIONS = new CharSequenceMap<>();

    static {
        RESPONSE_HEADER_TRANSLATIONS.add(Http3Headers.PseudoHeaderName.AUTHORITY.value(), HttpHeaderNames.HOST);
        RESPONSE_HEADER_TRANSLATIONS.add(Http3Headers.PseudoHeaderName.SCHEME.value(), HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
        REQUEST_HEADER_TRANSLATIONS.add(RESPONSE_HEADER_TRANSLATIONS);
        RESPONSE_HEADER_TRANSLATIONS.add(Http3Headers.PseudoHeaderName.PATH.value(), HttpConversionUtil.ExtensionHeaderNames.PATH.text());
    }

    private final CharSequenceMap<AsciiString> translations;

    private final Http3HeadersFrame headers;


    Netty3HeadersAdapter(Http3HeadersFrame headers, boolean request) {
        this.headers = headers;
        translations = request ? REQUEST_HEADER_TRANSLATIONS : RESPONSE_HEADER_TRANSLATIONS;
    }

    private static void toHttp3HeadersFilterTE(Entry<CharSequence, CharSequence> entry, Http3Headers out) {
        if (indexOf(entry.getValue(), ',', 0) == -1) {
            if (contentEqualsIgnoreCase(trim(entry.getValue()), TRAILERS)) {
                out.add(TE, TRAILERS);
            }
        } else {
            List<CharSequence> teValues = unescapeCsvFields(entry.getValue());
            for (CharSequence teValue : teValues) {
                if (contentEqualsIgnoreCase(trim(teValue), TRAILERS)) {
                    out.add(TE, TRAILERS);
                    break;
                }
            }
        }
    }

    private AsciiString keyConvert(String key) {
        AsciiString translatedName = translations.get(AsciiString.of(key));
        if (translatedName != null) {
            return translatedName;
        }
        return AsciiString.of(key).toLowerCase();
    }


    private <T> T get(String key, Function<String, T> function) {
        return function.apply(key.toLowerCase());
    }


    private <T, U> void set(String key, U value, BiConsumer<String, U> consumer) {
        consumer.accept(key.toLowerCase(), value);
    }

    @Override
    @Nullable
    public String getFirst(String key) {
        return get(key, headers::get);
    }


    @Override
    public void add(String key, @Nullable String value) {
        if (value != null) {
            set(key, value, headers::add);
        }
    }

    @Override
    public void addAll(String key, List<? extends String> values) {
        set(key, values, headers::add);
    }

    @Override
    public void addAll(MultiValueMap<String, String> values) {
        values.forEach((k, v) -> set(k, v, headers::add));
    }

    @Override
    public void set(String key, @Nullable String value) {
        if (value != null) {
            set(key, value, headers::set);
        }
    }

    @Override
    public void setAll(Map<String, String> values) {
        values.forEach((k, v) -> set(k, v, headers::set));
    }

    @Override
    public Map<String, String> toSingleValueMap() {
        Map<String, String> singleValueMap = CollectionUtils.newLinkedHashMap(this.headers.size());
        this.headers.entries()
                .forEach(entry -> singleValueMap.putIfAbsent(entry.getKey(), entry.getValue()));
        return singleValueMap;
    }

    @Override
    public int size() {
        return this.headers.names().size();
    }

    @Override
    public boolean isEmpty() {
        return this.headers.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return (key instanceof String && get((String) key, headers::contains));
    }

    @Override
    public boolean containsValue(Object value) {
        return (value instanceof String &&
                this.headers.entries().stream()
                        .anyMatch(entry -> value.equals(entry.getValue())));
    }

    @Override
    @Nullable
    public List<String> get(Object key) {
        if (containsKey(key)) {
            return get((String) key, headers::getAll);
        }
        return null;
    }

    @Nullable
    @Override
    public List<String> put(String key, @Nullable List<String> value) {
        List<String> previousValues = get(key);
        set(key, value, headers::set);
        return previousValues;
    }

    @Nullable
    @Override
    public List<String> remove(Object key) {
        if (key instanceof String) {
            List<String> previousValues = get(key);
            set((String) key, null, (k, v) -> headers.remove(k));
            return previousValues;
        }
        return null;
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> map) {
        map.forEach((k, v) -> set(k, v, headers::set));
    }

    @Override
    public void clear() {
        this.headers.clear();
    }

    @Override
    public Set<String> keySet() {
        return new HeaderNames();
    }

    @Override
    public Collection<List<String>> values() {
        return this.headers.names().stream()
                .map(this::get).collect(Collectors.toList());
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return new AbstractSet<Entry<String, List<String>>>() {
            @Override
            public Iterator<Entry<String, List<String>>> iterator() {
                return new EntryIterator();
            }

            @Override
            public int size() {
                return headers.size();
            }
        };
    }


    @Override
    public String toString() {
        return Http3HeadersFrame.formatHeaders(this);
    }


    private CharSequence convert(String key) {
        return AsciiString.of(key).toLowerCase();
    }

    private class EntryIterator implements Iterator<Entry<String, List<String>>> {

        private final Iterator<String> names = headers.names().iterator();

        @Override
        public boolean hasNext() {
            return this.names.hasNext();
        }

        @Override
        public Entry<String, List<String>> next() {
            return new HeaderEntry(this.names.next());
        }

    }

    private class HeaderEntry implements Entry<String, List<String>> {

        private final String key;

        HeaderEntry(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return this.key;
        }

        @Override
        public List<String> getValue() {
            return headers.getAll(this.key).stream().map(Objects::toString).collect(Collectors.toList());
        }

        @Override
        public List<String> setValue(List<String> value) {
            List<String> previousValues = getValue();
            headers.set(this.key, value);
            return previousValues;
        }

    }

    private class HeaderNames extends AbstractSet<String> {

        @Override
        public Iterator<String> iterator() {
            return new HeaderNamesIterator(headers.names().iterator());
        }

        @Override
        public int size() {
            return headers.names().size();
        }

    }

    private final class HeaderNamesIterator implements Iterator<String> {

        private final Iterator<String> iterator;

        @Nullable
        private String currentName;

        private HeaderNamesIterator(Iterator<String> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasNext() {
            return this.iterator.hasNext();
        }

        @Override
        public String next() {
            this.currentName = this.iterator.next();
            return this.currentName;
        }

        @Override
        public void remove() {
            if (this.currentName == null) {
                throw new IllegalStateException("No current Header in iterator");
            }
            if (!headers.contains(this.currentName)) {
                throw new IllegalStateException("Header not present: " + this.currentName);
            }
            headers.remove(this.currentName);
        }

    }

}
