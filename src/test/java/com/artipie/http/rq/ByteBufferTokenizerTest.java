package com.artipie.http.rq;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedList;
import java.util.stream.Collectors;

final class ByteBufferTokenizerTest {


    @ParameterizedTest
    @CsvSource({
            "/,---test123,on/e---test123two---test123t/hree---test123four,one/two/three/four",
            "/,--any--,one,one",
            "/,|,|one|two|,/one/two/",
            "/,|,o/n/e/|/t/w/o/|/t/h/r/e/e,one/two/three",
            "/,|,|||,///",
            "/,--boundary--,one-/-/b/o/u/n/d/a/r/y/-/-two--b/oun/dar/y--three,one/two/three",
            "/,---,o/n/e/---/t/w/o--/-three---four-/-/-,one/two/three/four/"
    })
    public void splitByTokens(final String split, final String delim, final String source, final String expect) {
        for (int cap = 1; cap < source.length() + 1; cap++) {
            final Deque<ByteBuffer> result = new LinkedList<>();
            try (AccReceiver receiver = new AccReceiver(result);
                 ByteBufferTokenizer target = new ByteBufferTokenizer(receiver, delim.getBytes(), cap)) {
                for (String part : source.split(split)) {
                    final ByteBuffer buf = ByteBuffer.wrap(part.getBytes());
                    target.push(buf.asReadOnlyBuffer());
                }
            }
            MatcherAssert.assertThat(
                    result.stream().map(ByteBufferTokenizerTest::bufToStr).collect(Collectors.toList()),
                    Matchers.contains(expect.split(split, -1))
            );
        }
    }

    private static String bufToStr(final ByteBuffer buf) {
        final byte[] bts = new byte[buf.remaining()];
        buf.get(bts);
        return new String(bts);
    }

    private static final class AccReceiver implements ByteBufferTokenizer.Receiver, Closeable {

        private final Deque<ByteBuffer> result;

        private AccReceiver(Deque<ByteBuffer> result) {
            this.result = result;
        }


        @Override
        public void receive(ByteBuffer next, boolean end) {
            if (result.isEmpty()) {
                result.addFirst(next);
            } else {
                final ByteBuffer last = result.getLast();
                final ByteBuffer concat = ByteBuffer.allocate(last.remaining() + next.remaining());
                concat.put(last);
                concat.put(next);
                concat.flip();
                result.removeLast();
                result.addLast(concat);
            }
            if (end) {
                result.addLast(ByteBuffer.allocate(0));
            }
        }

        @Override
        public void close() {
            this.result.removeLast();
        }
    }
}