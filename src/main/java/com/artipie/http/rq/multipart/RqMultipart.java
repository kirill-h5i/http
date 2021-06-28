/*
 * The MIT License (MIT) Copyright (c) 2020-2021 artipie.com
 * https://github.com/artipie/npm-adapter/LICENSE.txt
 */

package com.artipie.http.rq.multipart;

import com.artipie.http.ArtipieHttpException;
import com.artipie.http.Headers;
import com.artipie.http.headers.ContentType;
import com.artipie.http.rs.RsStatus;
import org.reactivestreams.Publisher;
import wtf.g4s8.mime.MimeType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multipart request.
 * <p>
 * Parses multipart request into body parts as publisher of {@code Request}s.
 * It accepts bodies acoording to
 * <a href="https://www.w3.org/Protocols/rfc1341/7_2_Multipart.html">RFC-1341-7.2</a>
 * specification.
 * </p>
 *
 * @implNote Since the multipart body is always received sequentially part by part,
 * the parts() method does not publish the next part until the previous is fully read.
 * @implNote The implementation does not keep request part data in memory or storage,
 * it should process each chunk and send to proper downstream.
 * @implNote The body part will not be parsed until {@code parts()} method call.
 * @since 1.0
 */
public final class RqMultipart {

    /**
     * Content type.
     */
    private ContentType ctype;

    /**
     * Body upstream.
     */
    private Publisher<ByteBuffer> upstream;

    private final ExecutorService exec;

    /**
     * Multipart request from headers and body upstream.
     *  @param headers Request headers
     * @param body    Upstream
     * @param exec
     */
    public RqMultipart(final Headers headers, final Publisher<ByteBuffer> body, ExecutorService exec) {
        this(new ContentType(headers), body, exec);
    }

    /**
     * Multipart request from content type and body upstream.
     *
     * @param ctype Content type
     * @param body  Upstream
     */
    public RqMultipart(final ContentType ctype, final Publisher<ByteBuffer> body, ExecutorService exec) {
        this.ctype = ctype;
        this.upstream = body;
        this.exec = exec;
    }

    /**
     * Body parts.
     *
     * @return Publisher of parts
     */
    public Publisher<Part> parts() {
        final MultiParts pub = new MultiParts(this.boundary(), this.exec);
        exec.submit(() -> this.upstream.subscribe(pub));
        return pub;
    }

    /**
     * Multipart boundary.
     * @return Boundary string
     */
    private String boundary() {
        final String header = MimeType.of(ctype.getValue())
                .param("boundary")
                .orElseThrow(
                        () -> new ArtipieHttpException(
                                RsStatus.BAD_REQUEST,
                                "Content-type boundary param missed"
                        )
                );
        return String.format("\r\n--%s", header);
    }

    /**
     * Part of multipart.
     *
     * @since 1.0
     */
    public interface Part extends Publisher<ByteBuffer> {

        /**
         * Part headers.
         *
         * @return Headers
         */
        Headers headers();
    }
}
