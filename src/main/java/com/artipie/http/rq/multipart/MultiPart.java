/*
 * The MIT License (MIT) Copyright (c) 2020-2021 artipie.com
 * https://github.com/artipie/npm-adapter/LICENSE.txt
 */
package com.artipie.http.rq.multipart;

import com.artipie.http.Headers;
import com.artipie.http.misc.BufAccumulator;
import com.artipie.http.misc.ByteBufferTokenizer;
import com.artipie.http.misc.DummySubscription;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Multipart request part.
 * @since 1.0
 */
final class MultiPart implements RqMultipart.Part, ByteBufferTokenizer.Receiver, Subscription {

    /**
     * Header buffer capacity.
     */
    private static final int CAP_HEADER = 256;

    /**
     * Part body buffer capacity.
     */
    private static final int CAP_PART = 1024;

    /**
     * Delimiter token.
     */
    private static final String DELIM = "\r\n\r\n";

    /**
     * Header state flag.
     */
    private static final int STATE_HEADER = 1;

    /**
     * Body state flag.
     */
    private static final int STATE_BODY = 2;

    /**
     * Upstream subscription.
     */
    private final Subscription upstream;

    /**
     * CRLF tokenizer.
     */
    private final ByteBufferTokenizer tokenizer;

    /**
     * Multipart header.
     */
    private final MultipartHeaders hdr;

    /**
     * Downstream reference.
     */
    private final AtomicReference<Subscriber<? super ByteBuffer>> downstream;

    /**
     * Current state.
     */
    private final AtomicInteger state;

    /**
     * Ready callback.
     * <p>
     * Called when the header is received and the part could be submitted to
     * {@link com.artipie.http.rq.multipart.RqMultipart.Part} downstream.
     * </p>
     */
    private final Consumer<? super RqMultipart.Part> ready;

    /**
     * Temporary body accumulator.
     * <p>
     * It's needed when the downstream connected after the part of body received.
     * It may happen if the first chunk of body received with last header chunk
     * before downstream subscription.
     * </p>
     */
    private final BufAccumulator tmpacc;

    /**
     * Async back-pressure executor.
     */
    private final ExecutorService exec;

    /**
     * Completed flag.
     */
    private final AtomicBoolean completed;

    private final MultiParts.Completion<?> completion;

    /**
     * New multipart request part.
     * @param upstream Upstream subscription
     * @param ready Ready callback
     * @param exec Back-pressure async executor
     */
    MultiPart(final Subscription upstream, final MultiParts.Completion<?> completion, final Consumer<? super RqMultipart.Part> ready,
        final ExecutorService exec) {
        this.upstream = upstream;
        this.ready = ready;
        this.exec = exec;
        this.completion = completion;
        this.tokenizer = new ByteBufferTokenizer(
            this, MultiPart.DELIM.getBytes(), MultiPart.CAP_PART
        );
        this.hdr = new MultipartHeaders(MultiPart.CAP_HEADER);
        this.downstream = new AtomicReference<>();
        this.state = new AtomicInteger(MultiPart.STATE_HEADER);
        this.tmpacc = new BufAccumulator(MultiPart.CAP_HEADER);
        this.completed = new AtomicBoolean();
    }

    @Override
    public Headers headers() {
        return this.hdr;
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> sub) {
        synchronized (this.downstream) {
            if (this.downstream.get() != null) {
                sub.onSubscribe(DummySubscription.VALUE);
                sub.onError(new IllegalStateException("Downstream already connected"));
                return;
            }
            sub.onSubscribe(this);
            final ByteBuffer acc = this.tmpacc.duplicate();
            acc.rewind();
            if (acc.hasRemaining()) {
                sub.onNext(acc);
            } else {
                this.upstream.request(1L);
            }
            this.tmpacc.close();
            if (this.completed.get()) {
                sub.onComplete();
                this.completion.itemCompleted();
            } else {
                this.downstream.set(sub);
            }
        }
    }

    private static String debugBuf(final ByteBuffer buf) {
        final ByteBuffer duplicate = buf.duplicate();
        duplicate.rewind();
        final byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return new String(bytes);
    }

    @Override
    public void receive(final ByteBuffer next, final boolean end) {
        synchronized (this.downstream) {
            final int current = this.state.get();
            if (current == MultiPart.STATE_HEADER) {
                this.hdr.push(next);
                this.upstream.request(1);
                if (end) {
                    this.state.incrementAndGet();
                    this.ready.accept(this);
                }
            } else if (current == MultiPart.STATE_BODY) {
                final Subscriber<? super ByteBuffer> subscriber = this.downstream.get();
                if (subscriber == null) {
                    this.tmpacc.push(next);
                } else {
                    subscriber.onNext(next);
                }
            }
            if (end && current == MultiPart.STATE_BODY
                && this.completed.compareAndSet(false, true)) {
                final Subscriber<? super ByteBuffer> sub = this.downstream.getAndSet(null);
                if (sub != null) {
                    sub.onComplete();
                    this.completion.itemCompleted();
                }
            }
        }
    }

    @Override
    public void request(final long amt) {
        this.exec.submit(() -> this.upstream.request(amt));
    }

    @Override
    public void cancel() {
        this.downstream.set(null);
        this.upstream.cancel();
    }

    /**
     * Push next chunk of raw data.
     * @param chunk Chunk buffer
     */
    void push(final ByteBuffer chunk) {
        this.tokenizer.push(chunk);
    }

    /**
     * Flush all data in temporary buffers.
     */
    void flush() {
        this.tokenizer.close();
    }
}
