package com.artipie.http.rq.multipart;

import com.artipie.http.headers.Header;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

class MultipartHeadersTest {

    @Test
    void buildHeadersFromChunks() throws Exception {
        final MultipartHeaders headers = new MultipartHeaders(10);
        final String source = String.join(
                "\r\n",
                "Accept: application/json",
                "Content-length: 100",
                "Connection: keep-alive"
        );
        for (int pos = 0, take = 3; pos < source.length(); pos += take, take++) {
            if (pos + take > source.length()) {
                take = source.length() - pos;
            }
            final String sub = source.substring(pos, pos + take);
            headers.push(ByteBuffer.wrap(sub.getBytes()));
        }
        MatcherAssert.assertThat(
                headers,
                Matchers.containsInAnyOrder(
                        new Header("Accept", "application/json"),
                        new Header("Connection", "keep-alive"),
                        new Header("Content-length", "100")
                )
        );
    }
}