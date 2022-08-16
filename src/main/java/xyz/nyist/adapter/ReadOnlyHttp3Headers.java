package xyz.nyist.adapter;

import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author: fucong
 * @Date: 2022/8/16 14:53
 * @Description:
 */
public class ReadOnlyHttp3Headers extends Http3HeadersAdapter {

    @Nullable
    private MediaType cachedContentType;

    @Nullable
    private List<MediaType> cachedAccept;

    ReadOnlyHttp3Headers(MultiValueMap<String, String> headers) {
        super(headers);
    }


    @Override
    public MediaType getContentType() {
        if (this.cachedContentType != null) {
            return this.cachedContentType;
        } else {
            MediaType contentType = super.getContentType();
            this.cachedContentType = contentType;
            return contentType;
        }
    }

    @Override
    public List<MediaType> getAccept() {
        if (this.cachedAccept != null) {
            return this.cachedAccept;
        } else {
            List<MediaType> accept = super.getAccept();
            this.cachedAccept = accept;
            return accept;
        }
    }

    @Override
    public void clearContentHeaders() {
        // No-op.
    }

    @Override
    public List<String> get(Object key) {
        List<String> values = super.get(key);
        return (values != null ? Collections.unmodifiableList(values) : null);
    }

    @Override
    public void add(String headerName, @Nullable String headerValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addAll(String key, List<? extends String> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addAll(MultiValueMap<String, String> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set(String headerName, @Nullable String headerValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAll(Map<String, String> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, String> toSingleValueMap() {
        return Collections.unmodifiableMap(super.toSingleValueMap());
    }

    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(super.keySet());
    }

    @Override
    public List<String> put(String key, List<String> value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<List<String>> values() {
        return Collections.unmodifiableCollection(super.values());
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return super.entrySet().stream().map(AbstractMap.SimpleImmutableEntry::new)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new), // Retain original ordering of entries
                        Collections::unmodifiableSet));
    }

}
