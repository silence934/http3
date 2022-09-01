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

import io.netty.handler.codec.HeadersUtils;
import io.netty.util.AsciiString;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.*;

import static io.netty.util.AsciiString.*;

public final class DefaultHttp3LastHeadersFrame implements Http3HeadersFrame, Http3LastFrame {

    private final Http3Headers headers;

    public DefaultHttp3LastHeadersFrame() {
        this(new DefaultHttp3Headers());
    }

    public DefaultHttp3LastHeadersFrame(Http3Headers headers) {
        this.headers = ObjectUtil.checkNotNull(headers, "headers");
    }

    @Override
    public Http3Headers headers() {
        return headers;
    }

    @Override
    public String get(String name) {
        return get((CharSequence) name);
    }

    @Override
    public String get(CharSequence name) {
        return HeadersUtils.getAsString(headers, name);
    }

    @Override
    public Integer getInt(CharSequence name) {
        return headers.getInt(name);
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return headers.getInt(name, defaultValue);
    }

    @Override
    public Short getShort(CharSequence name) {
        return headers.getShort(name);
    }

    @Override
    public short getShort(CharSequence name, short defaultValue) {
        return headers.getShort(name, defaultValue);
    }

    @Override
    public Long getTimeMillis(CharSequence name) {
        return headers.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return headers.getTimeMillis(name, defaultValue);
    }

    @Override
    public List<String> getAll(String name) {
        return getAll((CharSequence) name);
    }

    @Override
    public List<String> getAll(CharSequence name) {
        return HeadersUtils.getAllAsString(headers, name);
    }

    @Override
    public List<Map.Entry<String, String>> entries() {
        if (isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, String>> entriesConverted = new ArrayList<>(
                headers.size());
        for (Map.Entry<String, String> entry : this) {
            entriesConverted.add(entry);
        }
        return entriesConverted;
    }

    @Override
    @Deprecated
    public Iterator<Map.Entry<String, String>> iterator() {
        return HeadersUtils.iteratorAsString(headers);
    }

    @Override
    public Iterator<Map.Entry<CharSequence, CharSequence>> iteratorCharSequence() {
        return headers.iterator();
    }

    @Override
    public Iterator<String> valueStringIterator(CharSequence name) {
        final Iterator<CharSequence> itr = valueCharSequenceIterator(name);
        return new Iterator<String>() {
            @Override
            public boolean hasNext() {
                return itr.hasNext();
            }

            @Override
            public String next() {
                return itr.next().toString();
            }

            @Override
            public void remove() {
                itr.remove();
            }
        };
    }

    @Override
    public Iterator<CharSequence> valueCharSequenceIterator(CharSequence name) {
        return headers.valueIterator(name);
    }

    @Override
    public boolean contains(String name) {
        return contains((CharSequence) name);
    }

    @Override
    public boolean contains(CharSequence name) {
        return headers.contains(name);
    }

    @Override
    public boolean isEmpty() {
        return headers.isEmpty();
    }

    @Override
    public int size() {
        return headers.size();
    }

    @Override
    public Set<String> names() {
        return HeadersUtils.namesAsString(headers);
    }

    @Override
    public Http3HeadersFrame add(String name, Object value) {
        headers.addObject(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame add(CharSequence name, Object value) {
        headers.addObject(name, value);
        return this;
    }


    @Override
    public Http3HeadersFrame add(String name, Iterable<?> values) {
        headers.addObject(name, values);
        return this;
    }

    @Override
    public Http3HeadersFrame add(CharSequence name, Iterable<?> values) {
        headers.addObject(name, values);
        return this;
    }


    @Override
    public Http3HeadersFrame addInt(CharSequence name, int value) {
        headers.addInt(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame addShort(CharSequence name, short value) {
        headers.addShort(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame set(String name, Object value) {
        headers.setObject(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame set(CharSequence name, Object value) {
        headers.setObject(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame set(String name, Iterable<?> values) {
        headers.setObject(name, values);
        return this;
    }

    @Override
    public Http3HeadersFrame set(CharSequence name, Iterable<?> values) {
        headers.setObject(name, values);
        return this;
    }

    @Override
    public Http3HeadersFrame setInt(CharSequence name, int value) {
        headers.setInt(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame setShort(CharSequence name, short value) {
        headers.setShort(name, value);
        return this;
    }

    @Override
    public Http3HeadersFrame remove(String name) {
        headers.remove(name);
        return this;
    }

    @Override
    public Http3HeadersFrame remove(CharSequence name) {
        headers.remove(name);
        return this;
    }


    @Override
    public Http3HeadersFrame clear() {
        headers.clear();
        return this;
    }

    @Override
    public boolean contains(String name, String value, boolean ignoreCase) {
        return contains(name, (CharSequence) value, ignoreCase);
    }

    @Override
    public boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase) {
        Iterator<? extends CharSequence> itr = valueCharSequenceIterator(name);
        while (itr.hasNext()) {
            if (containsCommaSeparatedTrimmed(itr.next(), value, ignoreCase)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        return headers.contains(name, value, ignoreCase);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultHttp3LastHeadersFrame that = (DefaultHttp3LastHeadersFrame) o;
        return Objects.equals(headers, that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(headers);
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(this) + "(headers=" + headers() + ')';
    }


    private boolean containsCommaSeparatedTrimmed(CharSequence rawNext, CharSequence expected, boolean ignoreCase) {
        int begin = 0;
        int end;
        if (ignoreCase) {
            if ((end = AsciiString.indexOf(rawNext, ',', begin)) == -1) {
                return contentEqualsIgnoreCase(trim(rawNext), expected);
            } else {
                do {
                    if (contentEqualsIgnoreCase(trim(rawNext.subSequence(begin, end)), expected)) {
                        return true;
                    }
                    begin = end + 1;
                } while ((end = AsciiString.indexOf(rawNext, ',', begin)) != -1);

                if (begin < rawNext.length()) {
                    return contentEqualsIgnoreCase(trim(rawNext.subSequence(begin, rawNext.length())), expected);
                }
            }
        } else {
            if ((end = AsciiString.indexOf(rawNext, ',', begin)) == -1) {
                return contentEquals(trim(rawNext), expected);
            } else {
                do {
                    if (contentEquals(trim(rawNext.subSequence(begin, end)), expected)) {
                        return true;
                    }
                    begin = end + 1;
                } while ((end = AsciiString.indexOf(rawNext, ',', begin)) != -1);

                if (begin < rawNext.length()) {
                    return contentEquals(trim(rawNext.subSequence(begin, rawNext.length())), expected);
                }
            }
        }
        return false;
    }

}
