/*
 * The MIT License (MIT) Copyright (c) 2020-2021 artipie.com
 * https://github.com/artipie/npm-adapter/LICENSE.txt
 */
package com.artipie.http.rq.multipart;

import com.artipie.ArtipieException;
import com.artipie.http.misc.ByteBufferTokenizer;
import org.jctools.queues.SpscLinkedQueue;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multipart parts publisher.
 *
 * @since 1.0
 */
public final class MultiParts implements Processor<ByteBuffer, RqMultipart.Part>, ByteBufferTokenizer.Receiver {

    private final AtomicReference<Subscriber<? super RqMultipart.Part>> downstream;
    private final AtomicReference<Subscription> upstream = new AtomicReference<>();
    private final ByteBufferTokenizer tokenizer;
    private volatile MultiPart current;
    private final ExecutorService exec;
    // skip preamble until first boundary token
    private volatile boolean preamble;
    private volatile boolean epilogue;
    private final Completion<?> completion;
    /**
     * New multipart parts publisher for upstream publisher.
     */
    public MultiParts(final String boundary, ExecutorService exec) {
        this.tokenizer = new ByteBufferTokenizer(this, boundary.getBytes(StandardCharsets.US_ASCII));
        this.exec = new SpscExecutor(exec);
        this.downstream = new AtomicReference<>();
        this.completion = new Completion<>(this.downstream);
    }

    @Override
    public void subscribe(final Subscriber<? super RqMultipart.Part> sub) {
        if (this.downstream.compareAndSet(null, sub)) {
            sub.onSubscribe(upstream.get());
        } else {
            sub.onSubscribe(DummySubscription.VALUE);
            sub.onError(new IllegalStateException("Downstream already connected"));
            return;
        }
        if (upstream.get() != null) {
            this.upstream.get().request(1);
        }
    }

    @Override
    public void onSubscribe(final Subscription sub) {
        if (!this.upstream.compareAndSet(null, sub)) {
            this.onError(new IllegalStateException("Can't subscribe twice"));
        }
        if (downstream.get() != null) {
            sub.request(1);
        }
    }

    @Override
    public void onNext(final ByteBuffer chunk) {
        this.tokenizer.push(chunk);
    }

    private static String debugBuf(final ByteBuffer buf) {
        final ByteBuffer duplicate = buf.duplicate();
        duplicate.rewind();
        final byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return new String(bytes);
    }

    @Override
    public void onError(final Throwable err) {
        this.downstream.getAndSet(null).onError(
                new ArtipieException("Upstream failed", err)
        );
    }

    @Override
    public void onComplete() {
        this.completion.upstreamCompleted();
        if (this.current != null) {
            this.current.flush();
            this.current = null;
        }
    }

    @Override
    public synchronized void receive(final ByteBuffer next, final boolean end) {
        if (!this.preamble) {
            if (end) {
                this.preamble = true;
            }
            this.upstream.get().request(1L);
            return;
        }
        if (!this.epilogue && this.current == null) {
            ByteBuffer dup = next.duplicate();
            // epilogue starts with double minus `--` seq after end of part
            if (dup.remaining() >= 2 && dup.get() == '-' && dup.get() == '-') {
                this.epilogue = true;
            }
        }
        if (this.epilogue) {
            this.upstream.get().request(1L);
            return;
        }
        if (this.current == null) {
            completion.itemStarted();
            this.current = new MultiPart(this.upstream.get(), completion, part -> {
                final Subscriber<? super RqMultipart.Part> subscriber = this.downstream.get();
                this.exec.submit(() -> subscriber.onNext(part));
            }, this.exec);
        }
        this.current.push(next);
        if (end) {
            this.current.flush();
            this.current = null;
        }
    }

    static final class Completion<T> {
        static final Completion<?> FAKE = new Completion<>(new AtomicReference<>(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {

            }

            @Override
            public void onNext(Object o) {

            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onComplete() {

            }
        }));
        private volatile int counter;
        private volatile boolean completed;
        private final AtomicReference<Subscriber<? super T>> downstream;

        Completion(AtomicReference<Subscriber<? super T>> downstream) {
            this.downstream = downstream;
        }

        synchronized void itemCompleted() {
            if (--counter == 0 && completed) {
                downstream.get().onComplete();
            }
        }

        synchronized void itemStarted() {
            assert !completed;
            counter++;
        }

        synchronized void upstreamCompleted() {
            completed = true;
            if (counter == 0) {
                downstream.get().onComplete();
            }
        }
    }

    private final class SpscExecutor extends AbstractExecutorService {
        private final SpscLinkedQueue<Runnable> tasks;
        private final ExecutorService exec;
        private final AtomicInteger flags;
        private static final int IDLE = 0;
        private static final int RUNNING = 1;
        private static final int SHUTDOWN = 2;
        private static final int TERMINATED = 3;

        private SpscExecutor(ExecutorService exec) {
            this.exec = exec;
            this.flags = new AtomicInteger(IDLE);
            this.tasks = new SpscLinkedQueue<>();
        }

        @Override
        public void shutdown() {
            while (this.flags.compareAndSet(IDLE, SHUTDOWN)) {
                // busy-wait
            }
            this.exec.submit(() -> this.tasks.forEach(Runnable::run));
            this.exec.shutdown();
            final boolean terminated = this.flags.compareAndSet(SHUTDOWN, TERMINATED);
            assert terminated;
        }

        @Override
        public List<Runnable> shutdownNow() {
            while (this.flags.compareAndSet(IDLE, SHUTDOWN)) {
                // busy-wait
            }
            final List<Runnable> out = new ArrayList<>(this.tasks.size());
            Runnable task;
            while ((task = this.tasks.poll()) != null) {
                out.add(task);
            }
            final List<Runnable> empty = this.exec.shutdownNow();
            assert empty.isEmpty();
            final boolean terminated = this.flags.compareAndSet(SHUTDOWN, TERMINATED);
            assert terminated;
            return out;
        }

        @Override
        public boolean isShutdown() {
            return this.flags.get() == SHUTDOWN;
        }

        @Override
        public boolean isTerminated() {
            return this.flags.get() == TERMINATED;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            this.tasks.add(command);
            if (this.flags.get() > RUNNING) {
                this.tasks.remove(command);
                throw new RejectedExecutionException("Executor is terminated or shutdown");
            }
            if (this.flags.compareAndSet(IDLE, RUNNING)) {
                this.exec.submit(this::loop);
            }
        }

        private void loop() {
            while (true) {
                Runnable next = this.tasks.poll();
                if (next == null) {
                    if (!this.flags.compareAndSet(RUNNING, IDLE)) {
                        // TERMINATING or SHUTDOWN
                        return;
                    }
                    next = this.tasks.peek();
                    if (next != null && this.flags.compareAndSet(IDLE, RUNNING)) {
                        this.tasks.remove(next);
                    } else {
                        break;
                    }
                }
                next.run();
            }
        }
    }
}
