package com.artipie.http.rq;

import com.artipie.http.Headers;
import org.apache.commons.lang3.NotImplementedException;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;


/**
 * Multipart parts publisher.
 * @since 1.0
 */
public class MultiParts implements Publisher<RqMultipart.Part> {

    /**
     * Upstream processor.
     */
    private final Worker worker;

    /**
     * New multipart parts publisher for upstream publisher.
     * @param upstream Publisher
     * @param exec Executor service for processing
     */
    public MultiParts(final Publisher<ByteBuffer> upstream, final ExecutorService exec) {
        this.worker = new Worker(upstream, exec);
    }

    @Override
    public void subscribe(Subscriber<? super RqMultipart.Part> subscriber) {
        worker.attach(subscriber);
        subscriber.onSubscribe(
                new Subscription() {
                    @Override
                    public void request(long l) {
                        worker.request(l);
                    }

                    @Override
                    public void cancel() {
                        worker.cancel();
                    }
                }
        );
    }

    /**
     * Worker runnable for multipart upstream chunks processing.
     * @since 1.0
     */
    private static final class Worker implements Runnable {

        /**
         * Upstream publisher.
         */
        private final Publisher<ByteBuffer> upstream;

        /**
         * Executor service.
         */
        private final ExecutorService exec;

        /**
         * Running flag.
         */
        private final AtomicBoolean running = new AtomicBoolean();

        /**
         * Demand counter.
         */
        private final AtomicLong demand = new AtomicLong();

        /**
         * Current downstream publisher reference.
         */
        private AtomicReference<Subscriber<? super RqMultipart.Part>> downstream = new AtomicReference<>();

        /**
         * New worker for upstream.
         * @param upstream Publisher
         * @param exec Executor service
         */
        Worker(Publisher<ByteBuffer> upstream, ExecutorService exec) {
            this.upstream = upstream;
            this.exec = exec;
        }

        @Override
        public void run() {
            throw new NotImplementedException(String.format("Worker run is not implemented (%s)", this.upstream));
        }

        /**
         * Demand request from downstream.
         * @param amount Amount for request
         */
        public void request(long amount) {
            this.demand.updateAndGet(Worker.addNewDemand(amount));
            if (!this.running.compareAndSet(false, true)) {
                exec.submit(this);
            }
        }

        /**
         * Cancel current worker.
         */
        public void cancel() {
            throw new NotImplementedException(String.format("Worker cancel is not implemented (%s)", this.upstream));
        }

        /**
         * Attach downstream and trigger worker.
         * @param downstream Publisher
         */
        public void attach(Subscriber<? super RqMultipart.Part> downstream) {
            if (!this.downstream.compareAndSet(null, downstream)) {
                this.downstream.get().onError(new IllegalStateException("Downstream already connected"));
                request(0);
            }
        }

        /**
         * New demand calculator. It counts boundary and special cases with {@link Long#MAX_VALUE}.
         * @param amount New demand request
         * @return Operator to update current demand
         */
        private static LongUnaryOperator addNewDemand(final long amount) {
            return (final long old) -> {
                final long next;
                if (old == Long.MAX_VALUE) {
                    next = old;
                } else if (amount == Long.MAX_VALUE) {
                    next = amount;
                } else {
                    final long tmp = old + amount;
                    if (tmp < 0) {
                        next = Long.MAX_VALUE;
                    } else {
                        next = tmp;
                    }
                }
                return next;
            };
        }
    }

    /**
     * Part of multipart.
     *
     * @since 1.0
     */
    public static final class PartPublisher implements Publisher<ByteBuffer> {

        /**
         * Part headers.
         */
        private final Headers hdr;

        /**
         * Part body.
         */
        private final Publisher<ByteBuffer> source;

        /**
         * New part.
         *
         * @param headers Part headers
         * @param source  Part body
         */
        public PartPublisher(final Headers headers, final Publisher<ByteBuffer> source) {
            this.hdr = headers;
            this.source = source;
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> sub) {
            this.source.subscribe(sub);
        }

        /**
         * Part headers.
         *
         * @return Headers
         */
        public Headers headers() {
            return this.hdr;
        }
    }
}
