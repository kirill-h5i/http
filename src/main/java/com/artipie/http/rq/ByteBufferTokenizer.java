package com.artipie.http.rq;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * TODO: description
 *
 * @implNote This class is not thread safe, the access to push and flush should be synchronized externally if needed
 * @implNote The state could be broken in case if runtime exception occurs during the tokenization process,
 * it couldn't be recovered and should not be used after failure.
 */
public final class ByteBufferTokenizer implements Closeable {

    private static final ByteBuffer EMPTY_BUF = ByteBuffer.allocate(0).asReadOnlyBuffer();

    @FunctionalInterface
    public interface Receiver {

        /**
         * Receive next chunk of current token.
         *
         * @param next Chunk
         * @param end  True if the end of current token
         */
        void receive(ByteBuffer next, boolean end);
    }

    /**
     * Default capacity is 4 Kb.
     */
    private static final int CAP_DEFAULT = 1024 * 4;

    /**
     * Delimiter.
     */
    private final byte[] delim;

    /**
     * Token receiver.
     */
    private final Receiver receiver;

    /**
     * Current buffer.
     */
    private ByteBuffer buffer;

    public ByteBufferTokenizer(final Receiver receiver, final byte[] delim) {
        this(receiver, delim, ByteBufferTokenizer.CAP_DEFAULT);
    }

    public ByteBufferTokenizer(final Receiver receiver, final byte[] delim, final int cap) {
        this.receiver = receiver;
        this.delim = Arrays.copyOf(delim, delim.length);
        this.buffer = ByteBufferTokenizer.newEmptyBuffer(cap);
    }

    /**
     * Push next chunk of data to tokenizer.
     *
     * @param chunk Data
     */
    public void push(final ByteBuffer chunk) {
        this.check();
        this.append(chunk);
//        final ByteBuffer dup = this.buffer.duplicate();
//        dup.flip();
        final byte[] arr = ByteBufferTokenizer.array(this.buffer);
        // bid is a next boundary id, offset is current offset of token + boundary
        int bid, offset = 0;
        // find next boundary token with offset as `bid`
        while ((bid = indexOf(offset, arr, delim)) >= 0) {
            if (bid == 0) {
                // if boundary seq is a head, then next token is and empty token
                this.receiver.receive(EMPTY_BUF, true);
                // and offset for next lookup is a delimiter length
                offset = delim.length;
                continue;
            }
            // set bounds for next tokens from current offset to next delimiter id
            this.receiver.receive(copy(this.buffer, offset, bid), true);
            // update offset to token position + delimiter length
            offset = bid + delim.length;
        }
        // if next delimiter was not found, then try to send save range of bytes to receiver,
        // since it may contain next delimiter partially, then safe range is a:
        // (buffer-length - (delimiter - 1))
        final int margin = arr.length - this.delim.length + 1;
        if (margin <= 0) {
            // skip small buffers
            return;
        }
        if (offset < margin) {
            // if there are some bytes between last offset and margin, then send it
            // to the receiver and update buffer bounds
            this.receiver.receive(copy(this.buffer, offset, margin), false);
            this.buffer.position(margin);
        } else {
            // if the offset is crossing possible delimiter margin, then just reset to the offset position
            this.buffer.position(offset);
        }
        // remove processed bytes and reset buffer limits
        this.buffer.compact();
        this.buffer.limit(this.buffer.position());
    }

    //TODO: remove after debugging
    static String __bufDebug(final ByteBuffer buffer) {
        final ByteBuffer dup = buffer.duplicate();
        final byte[] arr = new byte[dup.remaining()];
        dup.get(arr);
        return new String(arr);
    }

    /**
     * Append new chunk to current buffer. Resize buffer if not enough capacity.
     *
     * @param chunk To append
     */
    private void append(final ByteBuffer chunk) {
        System.out.printf("append %s (%s) to %s (%s): ", chunk, __bufDebug(chunk), this.buffer, __bufDebug(buffer));
        if (this.buffer.capacity() - this.buffer.limit() >= chunk.remaining()) {
            this.buffer.limit(this.buffer.limit() + chunk.remaining());
            this.buffer.put(chunk);
        } else {
            final ByteBuffer resized = ByteBuffer.allocate(this.buffer.capacity() + chunk.capacity());
            final int pos = this.buffer.position();
            final int lim = this.buffer.limit();
            this.buffer.flip();
            resized.put(this.buffer);
            resized.limit(lim + chunk.remaining());
            resized.position(pos);
            resized.put(chunk);
            this.buffer = resized;
        }
        System.out.printf("%s (%s)\n", this.buffer, __bufDebug(buffer));
    }

    /**
     * Flush buffer, sends all remaining data to receiver.
     */
    private void flush() {
        this.check();
        this.buffer.flip();
        if (this.buffer.hasRemaining()) {
            this.receiver.receive(copy(this.buffer, this.buffer.position(), this.buffer.limit()), true);
        } else {
            this.receiver.receive(EMPTY_BUF, true);
        }
    }

    @Override
    public void close() {
        this.flush();
        this.buffer = null;
    }

    /**
     * Sanity check. Enabled only with assertions flag enabled.
     */
    private void check() {
        assert this.buffer != null : "tokenizer was closed";
    }

    /**
     * Finds index of token in array starting with offset.
     *
     * @param offset Offset to start
     * @param array  Source array
     * @param token  Token to find
     * @return Position of token or -1 if not found
     */
    public static int indexOf(final int offset, final byte[] array, final byte[] token) {
        for (int i = offset; i < array.length - token.length + 1; ++i) {
            boolean found = true;
            for (int j = 0; j < token.length; ++j) {
                if (array[i + j] != token[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] array(final ByteBuffer buffer) {
        final ByteBuffer dup = buffer.duplicate();
        dup.flip();
        final byte[] arr = new byte[dup.remaining()];
        dup.get(arr);
        return arr;
    }

    /**
     * Copy buffer range to another buffer.
     *
     * @param src Buffer to copy
     * @param pos Buffer position
     * @param lim Buffer limit
     * @return Readonly copy
     */
    private static ByteBuffer copy(final ByteBuffer src, final int pos, final int lim) {
        final ByteBuffer dup = src.duplicate();
        dup.limit(lim);
        dup.position(pos);
        final ByteBuffer slice = dup.slice();
        final ByteBuffer res = ByteBuffer.allocate(slice.remaining());
        res.put(slice);
        res.flip();
        return res.asReadOnlyBuffer();
    }

    /**
     * Creates new empty buffer with zero position and limit.
     *
     * @param cap Capacity
     * @return New buffer
     */
    private static ByteBuffer newEmptyBuffer(final int cap) {
        final ByteBuffer buf = ByteBuffer.allocate(cap);
        buf.flip();
        return buf;
    }
}
