package com.artipie.http.rq.multipart;

import com.artipie.http.Headers;
import com.artipie.http.headers.Header;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class MultipartHeaders implements Headers {

    private final Object lock = new Object();
    private volatile ByteBuffer data;
    private volatile Headers cache;

    public MultipartHeaders(final int cap) {
        this.data = ByteBuffer.allocate(cap);
        this.data.flip();
    }

    public void push(final ByteBuffer chunk) {
        synchronized (this.lock) {
            if (this.data.capacity() - this.data.limit() >= chunk.remaining()) {
                this.data.limit(this.data.limit() + chunk.remaining());
                this.data.put(chunk);
            } else {
                final ByteBuffer resized =
                        ByteBuffer.allocate(this.data.capacity() + chunk.capacity());
                final int pos = this.data.position();
                final int lim = this.data.limit();
                this.data.flip();
                resized.put(this.data);
                resized.limit(lim + chunk.remaining());
                resized.position(pos);
                resized.put(chunk);
                this.data = resized;
            }
        }
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        if (this.cache == null) {
            synchronized (this.lock) {
                if (cache == null) {
                    this.data.rewind();
                    final byte[] arr = new byte[this.data.remaining()];
                    this.data.get(arr);
                    final String hstr = new String(arr, StandardCharsets.US_ASCII);
                    this.cache = new Headers.From(
                            Arrays.stream(hstr.split("\r\n")).map(
                                    line -> {
                                        final String[] parts = line.split(":");
                                        return new Header(
                                                parts[0].trim().toLowerCase(Locale.US),
                                                parts[1].trim().toLowerCase(Locale.US)
                                        );
                                    }
                            ).collect(Collectors.toList())
                    );
                }
                this.data = null;
            }
        }
        return this.cache.iterator();
    }
}
