/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.hue.internal.connection;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Response;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise.Completable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.openhab.binding.hue.internal.dto.CreateUserRequest;
import org.openhab.binding.hue.internal.dto.SuccessResponse;
import org.openhab.binding.hue.internal.dto.clip2.BridgeConfig;
import org.openhab.binding.hue.internal.dto.clip2.Event;
import org.openhab.binding.hue.internal.dto.clip2.Resource;
import org.openhab.binding.hue.internal.dto.clip2.ResourceReference;
import org.openhab.binding.hue.internal.dto.clip2.Resources;
import org.openhab.binding.hue.internal.dto.clip2.enums.ResourceType;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.HttpUnauthorizedException;
import org.openhab.binding.hue.internal.handler.Clip2BridgeHandler;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.io.net.http.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * This class handles HTTP and SSE connections to/from a Hue Bridge running CLIP 2.
 *
 * It uses the following connection mechanisms:
 *
 * <li>The primary communication uses HTTP 2 streams over a shared permanent HTTP 2 session.</li>
 * <li>The 'registerApplicationKey()' method uses HTTP/1.1 over the OH common Jetty client.</li>
 * <li>The 'isClip2Supported()' static method uses HTTP/1.1 over the OH common Jetty client via 'HttpUtil'.</li>
 *
 * @author Andrew Fiddian-Green - Initial Contribution
 */
@NonNullByDefault
public class Clip2Bridge implements Closeable {

    /**
     * Base (abstract) adapter for listening to HTTP 2 stream events.
     *
     * It implements a CompletableFuture by means of which the caller can wait for the response data to come in. And
     * which, in the case of fatal errors, gets completed exceptionally.
     *
     * It handles the following fatal error events by notifying the containing class:
     *
     * <li>onHeaders() HTTP unauthorized codes</li>
     */
    private abstract class BaseStreamListenerAdapter<T> extends Stream.Listener.Adapter {
        protected final CompletableFuture<T> completable = new CompletableFuture<T>();
        private String contentType = "UNDEFINED";

        protected T awaitResult() throws ExecutionException, InterruptedException, TimeoutException {
            return completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * Return the HTTP content type.
         *
         * @return content type e.g. 'application/json'
         */
        protected String getContentType() {
            return contentType;
        }

        protected void handleHttp2Error(Http2Error error) {
            Http2Exception e = new Http2Exception(error);
            if (Http2Error.UNAUTHORIZED.equals(error)) {
                // for external error handling, abstract authorization errors into a separate exception
                completable.completeExceptionally(new HttpUnauthorizedException("HTTP 2 request not authorized"));
            } else {
                completable.completeExceptionally(e);
            }
            fatalErrorDelayed(this, e);
        }

        /**
         * Check the reply headers to see whether the request was authorised.
         */
        @Override
        public void onHeaders(@Nullable Stream stream, @Nullable HeadersFrame frame) {
            Objects.requireNonNull(frame);
            MetaData metaData = frame.getMetaData();
            if (metaData.isResponse()) {
                Response responseMetaData = (Response) metaData;
                int httpStatus = responseMetaData.getStatus();
                switch (httpStatus) {
                    case HttpStatus.UNAUTHORIZED_401:
                    case HttpStatus.FORBIDDEN_403:
                        handleHttp2Error(Http2Error.UNAUTHORIZED);
                    default:
                }
                contentType = responseMetaData.getFields().get(HttpHeader.CONTENT_TYPE).toLowerCase();
            }
        }
    }

    /**
     * Adapter for listening to regular HTTP 2 GET/PUT request stream events.
     *
     * It assembles the incoming text data into an HTTP 'content' entity. And when the last data frame arrives, it
     * returns the full content by completing the CompletableFuture with that data.
     *
     * In addition to those handled by the parent, it handles the following fatal error events by notifying the
     * containing class:
     *
     * <li>onIdleTimeout()</li>
     * <li>onTimeout()</li>
     */
    private class ContentStreamListenerAdapter extends BaseStreamListenerAdapter<String> {
        private final DataFrameCollector content = new DataFrameCollector();

        @Override
        public void onData(@Nullable Stream stream, @Nullable DataFrame frame, @Nullable Callback callback) {
            Objects.requireNonNull(frame);
            Objects.requireNonNull(callback);
            synchronized (this) {
                content.append(frame.getData());
                if (frame.isEndStream() && !completable.isDone()) {
                    completable.complete(content.contentAsString().trim());
                    content.reset();
                }
            }
            callback.succeeded();
        }

        @Override
        public boolean onIdleTimeout(@Nullable Stream stream, @Nullable Throwable x) {
            handleHttp2Error(Http2Error.IDLE);
            return true;
        }

        @Override
        public void onTimeout(@Nullable Stream stream, @Nullable Throwable x) {
            handleHttp2Error(Http2Error.TIMEOUT);
        }
    }

    /**
     * Class to collect incoming ByteBuffer data from HTTP 2 Data frames.
     */
    private static class DataFrameCollector {
        private byte[] buffer = new byte[512];
        private int usedSize = 0;

        public void append(ByteBuffer data) {
            int dataCapacity = data.capacity();
            int neededSize = usedSize + dataCapacity;
            if (neededSize > buffer.length) {
                int newSize = (dataCapacity < 4096) ? neededSize : Math.max(2 * buffer.length, neededSize);
                buffer = Arrays.copyOf(buffer, newSize);
            }
            data.get(buffer, usedSize, dataCapacity);
            usedSize += dataCapacity;
        }

        public String contentAsString() {
            return new String(buffer, 0, usedSize, StandardCharsets.UTF_8);
        }

        public Reader contentStreamReader() {
            return new InputStreamReader(new ByteArrayInputStream(buffer, 0, usedSize), StandardCharsets.UTF_8);
        }

        public void reset() {
            usedSize = 0;
        }
    }

    /**
     * Adapter for listening to SSE event stream events.
     *
     * It receives the incoming text lines. Receipt of the first data line causes the CompletableFuture to complete. It
     * then parses subsequent data according to the SSE specification. If the line starts with a 'data:' message, it
     * adds the data to the list of strings. And if the line is empty (i.e. the last line of an event), it passes the
     * full set of strings to the owner via a call-back method.
     *
     * The stream must be permanently connected, so it ignores onIdleTimeout() events.
     *
     * The parent class handles most fatal errors, but since the event stream is supposed to be permanently connected,
     * the following events are also considered as fatal:
     *
     * <li>onClosed()</li>
     * <li>onReset()</li>
     */
    private class EventStreamListenerAdapter extends BaseStreamListenerAdapter<Boolean> {
        private final DataFrameCollector eventData = new DataFrameCollector();

        @Override
        public void onClosed(@Nullable Stream stream) {
            handleHttp2Error(Http2Error.CLOSED);
        }

        @Override
        public void onData(@Nullable Stream stream, @Nullable DataFrame frame, @Nullable Callback callback) {
            Objects.requireNonNull(frame);
            Objects.requireNonNull(callback);
            synchronized (this) {
                eventData.append(frame.getData());
                BufferedReader reader = new BufferedReader(eventData.contentStreamReader());
                @SuppressWarnings("null")
                List<String> receivedLines = reader.lines().collect(Collectors.toList());

                // a blank line marks the end of an SSE message
                boolean endOfMessage = !receivedLines.isEmpty()
                        && receivedLines.get(receivedLines.size() - 1).isBlank();

                if (endOfMessage) {
                    eventData.reset();
                    // receipt of ANY message means the event stream is established
                    if (!completable.isDone()) {
                        completable.complete(Boolean.TRUE);
                    }
                    // append any 'data' field values to the event message
                    StringBuilder eventContent = new StringBuilder();
                    for (String receivedLine : receivedLines) {
                        if (receivedLine.startsWith("data:")) {
                            eventContent.append(receivedLine.substring(5).stripLeading());
                        }
                    }
                    if (eventContent.length() > 0) {
                        onEventData(eventContent.toString().trim());
                    }
                }
            }
            callback.succeeded();
        }

        @Override
        public boolean onIdleTimeout(@Nullable Stream stream, @Nullable Throwable x) {
            return false;
        }

        @Override
        public void onReset(@Nullable Stream stream, @Nullable ResetFrame frame) {
            handleHttp2Error(Http2Error.RESET);
        }
    }

    /**
     * Enum of potential fatal HTTP 2 session/stream errors.
     */
    private enum Http2Error {
        CLOSED,
        FAILURE,
        TIMEOUT,
        RESET,
        IDLE,
        GO_AWAY,
        UNAUTHORIZED;
    }

    /**
     * Private exception for handling HTTP 2 stream and session errors.
     */
    @SuppressWarnings("serial")
    private static class Http2Exception extends ApiException {
        public final Http2Error error;

        public Http2Exception(Http2Error error) {
            this(error, null);
        }

        public Http2Exception(Http2Error error, @Nullable Throwable cause) {
            super("HTTP 2 stream " + error.toString().toLowerCase(), cause);
            this.error = error;
        }
    }

    /**
     * Adapter for listening to HTTP 2 session status events.
     *
     * The session must be permanently connected, so it ignores onIdleTimeout() events.
     * It also handles the following fatal events by notifying the containing class:
     *
     * <li>onClose()</li>
     * <li>onFailure()</li>
     * <li>onGoAway()</li>
     * <li>onReset()</li>
     */
    private class SessionListenerAdapter extends Session.Listener.Adapter {

        @Override
        public void onClose(@Nullable Session session, @Nullable GoAwayFrame frame) {
            fatalErrorDelayed(this, new Http2Exception(Http2Error.CLOSED));
        }

        @Override
        public void onFailure(@Nullable Session session, @Nullable Throwable failure) {
            fatalErrorDelayed(this, new Http2Exception(Http2Error.FAILURE));
        }

        @Override
        public void onGoAway(@Nullable Session session, @Nullable GoAwayFrame frame) {
            fatalErrorDelayed(this, new Http2Exception(Http2Error.GO_AWAY));
        }

        @Override
        public boolean onIdleTimeout(@Nullable Session session) {
            return false;
        }

        @Override
        public void onPing(@Nullable Session session, @Nullable PingFrame frame) {
            checkAliveOk();
            if (Objects.nonNull(session) && Objects.nonNull(frame) && !frame.isReply()) {
                session.ping(new PingFrame(true), Callback.NOOP);
            }
        }

        @Override
        public void onReset(@Nullable Session session, @Nullable ResetFrame frame) {
            fatalErrorDelayed(this, new Http2Exception(Http2Error.RESET));
        }
    }

    /**
     * Enum showing the online state of the session connection.
     */
    private static enum State {
        /**
         * Session closed
         */
        CLOSED,
        /**
         * Session open for HTTP calls only
         */
        PASSIVE,
        /**
         * Session open for HTTP calls and actively receiving SSE events
         */
        ACTIVE;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Clip2Bridge.class);

    private static final String APPLICATION_ID = "org-openhab-binding-hue-clip2";
    private static final String APPLICATION_KEY = "hue-application-key";

    private static final String EVENT_STREAM_ID = "eventStream";
    private static final String FORMAT_URL_CONFIG = "http://%s/api/0/config";
    private static final String FORMAT_URL_RESOURCE = "https://%s/clip/v2/resource/";
    private static final String FORMAT_URL_REGISTER = "http://%s/api";
    private static final String FORMAT_URL_EVENTS = "https://%s/eventstream/clip/v2";

    private static final long CLIP2_MINIMUM_VERSION = 1948086000L;

    public static final int TIMEOUT_SECONDS = 10;
    private static final int CHECK_ALIVE_SECONDS = 300;
    private static final int REQUEST_INTERVAL_MILLISECS = 50;
    private static final int MAX_CONCURRENT_STREAMS = 3;
    private static final int RESTART_AFTER_SECONDS = 5;

    private static final ResourceReference BRIDGE = new ResourceReference().setType(ResourceType.BRIDGE);

    /**
     * Static method to attempt to connect to a Hue Bridge, get its software version, and check if it is high enough to
     * support the CLIP 2 API.
     *
     * @param hostName the bridge IP address.
     * @return true if bridge is online and it supports CLIP 2, or false if it is online and does not support CLIP 2.
     * @throws IOException if unable to communicate with the bridge.
     * @throws NumberFormatException if the bridge firmware version is invalid.
     */
    public static boolean isClip2Supported(String hostName) throws IOException {
        String response;
        Properties headers = new Properties();
        headers.put(HttpHeader.ACCEPT, MediaType.APPLICATION_JSON);
        response = HttpUtil.executeUrl("GET", String.format(FORMAT_URL_CONFIG, hostName), headers, null, null,
                TIMEOUT_SECONDS * 1000);
        BridgeConfig config = new Gson().fromJson(response, BridgeConfig.class);
        if (Objects.nonNull(config)) {
            String swVersion = config.swversion;
            if (Objects.nonNull(swVersion)) {
                try {
                    if (Long.parseLong(swVersion) >= CLIP2_MINIMUM_VERSION) {
                        return true;
                    }
                } catch (NumberFormatException e) {
                    LOGGER.debug("isClip2Supported() swVersion '{}' is not a number", swVersion);
                }
            }
        }
        return false;
    }

    private final HttpClient httpClient;
    private final HTTP2Client http2Client;
    private final String hostName;
    private final String baseUrl;
    private final String eventUrl;
    private final String registrationUrl;
    private final String applicationKey;
    private final Clip2BridgeHandler bridgeHandler;
    private final Gson jsonParser = new Gson();
    private final Semaphore streamMutex = new Semaphore(MAX_CONCURRENT_STREAMS, true);

    private boolean closing;
    private boolean internalRestartScheduled;
    private boolean externalRestartScheduled;
    private State onlineState = State.CLOSED;
    private Optional<Instant> lastRequestTime = Optional.empty();
    private Instant sessionExpireTime = Instant.MAX;
    private @Nullable Session http2Session;

    private @Nullable Future<?> checkAliveTask;
    private @Nullable Future<?> internalRestartTask;
    private Map<Integer, Future<?>> fatalErrorTasks = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param httpClientFactory the OH core HttpClientFactory.
     * @param bridgeHandler the bridge handler.
     * @param hostName the host name (ip address) of the Hue bridge
     * @param applicationKey the application key.
     */
    public Clip2Bridge(HttpClientFactory httpClientFactory, Clip2BridgeHandler bridgeHandler, String hostName,
            String applicationKey) {
        LOGGER.debug("Clip2Bridge()");
        httpClient = httpClientFactory.getCommonHttpClient();
        http2Client = httpClientFactory.createHttp2Client("hue-clip2", httpClient.getSslContextFactory());
        http2Client.setConnectTimeout(Clip2Bridge.TIMEOUT_SECONDS * 1000);
        http2Client.setIdleTimeout(-1);
        this.bridgeHandler = bridgeHandler;
        this.hostName = hostName;
        this.applicationKey = applicationKey;
        baseUrl = String.format(FORMAT_URL_RESOURCE, hostName);
        eventUrl = String.format(FORMAT_URL_EVENTS, hostName);
        registrationUrl = String.format(FORMAT_URL_REGISTER, hostName);
    }

    /**
     * Cancel the given task.
     *
     * @param cancelTask the task to be cancelled (may be null)
     * @param mayInterrupt allows cancel() to interrupt the thread.
     */
    private void cancelTask(@Nullable Future<?> cancelTask, boolean mayInterrupt) {
        if (Objects.nonNull(cancelTask)) {
            cancelTask.cancel(mayInterrupt);
        }
    }

    /**
     * Send a ping to the Hue bridge to check that the session is still alive.
     */
    private void checkAlive() {
        if (onlineState == State.CLOSED) {
            return;
        }
        LOGGER.debug("checkAlive()");
        Session session = http2Session;
        if (Objects.nonNull(session)) {
            session.ping(new PingFrame(false), Callback.NOOP);
        }
        if (Instant.now().isAfter(sessionExpireTime)) {
            fatalError(this, new Http2Exception(Http2Error.TIMEOUT));
        }
    }

    /**
     * Connection is ok, so reschedule the session check alive expire time. Called in response to incoming ping frames
     * from the bridge.
     */
    protected void checkAliveOk() {
        LOGGER.debug("checkAliveOk()");
        sessionExpireTime = Instant.now().plusSeconds(CHECK_ALIVE_SECONDS * 2);
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {
        closing = true;
        externalRestartScheduled = false;
        internalRestartScheduled = false;
        close2();
    }

    /**
     * Private method to close the connection.
     */
    private void close2() {
        synchronized (this) {
            LOGGER.debug("close2()");
            boolean notifyHandler = onlineState == State.ACTIVE && !internalRestartScheduled
                    && !externalRestartScheduled && !closing;
            onlineState = State.CLOSED;
            synchronized (fatalErrorTasks) {
                fatalErrorTasks.values().forEach(task -> cancelTask(task, true));
                fatalErrorTasks.clear();
            }
            if (!internalRestartScheduled) {
                // don't close the task if a restart is current
                cancelTask(internalRestartTask, true);
                internalRestartTask = null;
            }
            cancelTask(checkAliveTask, true);
            checkAliveTask = null;
            closeSession();
            try {
                http2Client.stop();
            } catch (Exception e) {
                // ignore
            }
            if (notifyHandler) {
                bridgeHandler.onConnectionOffline();
            }
        }
    }

    /**
     * Close the HTTP 2 session if necessary.
     */
    private void closeSession() {
        LOGGER.debug("closeSession()");
        Session session = http2Session;
        if (Objects.nonNull(session)) {
            session.close(0, null, Callback.NOOP);
        }
        http2Session = null;
    }

    /**
     * Method that is called back in case of fatal stream or session events. Note: under normal operation, the Hue
     * Bridge sends a 'soft' GO_AWAY command every nine or ten hours, so we handle such soft errors by attempting to
     * silently close and re-open the connection without notifying the handler of an actual 'hard' error.
     *
     * @param listener the entity that caused this method to be called.
     * @param cause the exception that caused the error.
     */
    private synchronized void fatalError(Object listener, Http2Exception cause) {
        if (externalRestartScheduled || internalRestartScheduled || onlineState == State.CLOSED || closing) {
            return;
        }
        String causeId = listener.getClass().getSimpleName();
        if (listener instanceof ContentStreamListenerAdapter) {
            // on GET / PUT requests the caller handles errors and closes the stream; the session is still OK
            LOGGER.debug("fatalError() {} {} ignoring", causeId, cause.error);
        } else if (cause.error == Http2Error.GO_AWAY) {
            LOGGER.debug("fatalError() {} {} scheduling reconnect", causeId, cause.error);

            // schedule task to open again
            internalRestartScheduled = true;
            cancelTask(internalRestartTask, false);
            internalRestartTask = bridgeHandler.getScheduler().schedule(
                    () -> internalRestart(onlineState == State.ACTIVE), RESTART_AFTER_SECONDS, TimeUnit.SECONDS);

            // force close immediately to be clean when internalRestart() starts
            close2();
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("fatalError() {} {} closing", causeId, cause.error, cause);
            } else {
                LOGGER.warn("Fatal error {} {} => closing session.", causeId, cause.error);
            }
            close2();
        }
    }

    /**
     * Method that is called back in case of fatal stream or session events. Schedules fatalError() to be called after a
     * delay in order to prevent sequencing issues.
     *
     * @param listener the entity that caused this method to be called.
     * @param cause the exception that caused the error.
     */
    protected void fatalErrorDelayed(Object listener, Http2Exception cause) {
        synchronized (fatalErrorTasks) {
            final int index = fatalErrorTasks.size();
            fatalErrorTasks.put(index, bridgeHandler.getScheduler().schedule(() -> {
                fatalError(listener, cause);
                fatalErrorTasks.remove(index);
            }, 1, TimeUnit.SECONDS));
        }
    }

    /**
     * HTTP GET a Resources object, for a given resource Reference, from the Hue Bridge. The reference is a class
     * comprising a resource type and an id. If the id is a specific resource id then only the one specific resource
     * is returned, whereas if it is null then all resources of the given resource type are returned.
     *
     * It wraps the getResourcesImpl() method in a try/catch block, and transposes any HttpUnAuthorizedException into an
     * ApiException. Such transposition should never be required in reality since by the time this method is called, the
     * connection will surely already have been authorised.
     *
     * @param reference the Reference class to get.
     * @return a Resource object containing either a list of Resources or a list of Errors.
     * @throws ApiException if anything fails.
     * @throws InterruptedException
     */
    public Resources getResources(ResourceReference reference) throws ApiException, InterruptedException {
        sleepDuringRestart();
        if (onlineState == State.CLOSED) {
            throw new ApiException("getResources() offline");
        }
        return getResourcesImpl(reference);
    }

    /**
     * Internal method to send an HTTP 2 GET request to the Hue Bridge and process its response.
     *
     * @param reference the Reference class to get.
     * @return a Resource object containing either a list of Resources or a list of Errors.
     * @throws HttpUnauthorizedException if the request was refused as not authorised or forbidden.
     * @throws ApiException if the communication failed, or an unexpected result occurred.
     * @throws InterruptedException
     */
    private Resources getResourcesImpl(ResourceReference reference)
            throws HttpUnauthorizedException, ApiException, InterruptedException {
        Session session = http2Session;
        if (Objects.isNull(session) || session.isClosed()) {
            throw new ApiException("HTTP 2 session is null or closed");
        }
        throttle();
        String url = getUrl(reference);
        HeadersFrame headers = prepareHeaders(url, MediaType.APPLICATION_JSON);
        LOGGER.trace("GET {} HTTP/2", url);
        try {
            Completable<@Nullable Stream> streamPromise = new Completable<>();
            ContentStreamListenerAdapter contentStreamListener = new ContentStreamListenerAdapter();
            session.newStream(headers, streamPromise, contentStreamListener);
            // wait for stream to be opened
            Objects.requireNonNull(streamPromise.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            // wait for HTTP response contents
            String contentJson = contentStreamListener.awaitResult();
            String contentType = contentStreamListener.getContentType();
            LOGGER.trace("HTTP/2 200 OK (Content-Type: {}) << {}", contentType, contentJson);
            if (!MediaType.APPLICATION_JSON.equals(contentType)) {
                throw new ApiException("Unexpected Content-Type: " + contentType);
            }
            try {
                Resources resources = Objects.requireNonNull(jsonParser.fromJson(contentJson, Resources.class));
                if (LOGGER.isDebugEnabled()) {
                    resources.getErrors().forEach(error -> LOGGER.debug("Resources error:{}", error));
                }
                return resources;
            } catch (JsonParseException e) {
                throw new ApiException("Parsing error", e);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpUnauthorizedException) {
                throw (HttpUnauthorizedException) cause;
            }
            throw new ApiException("Error sending request", e);
        } catch (TimeoutException e) {
            throw new ApiException("Error sending request", e);
        } finally {
            throttleDone();
        }
    }

    /**
     * Build a full path to a server end point, based on a Reference class instance. If the reference contains only
     * a resource type, the method returns the end point url to get all resources of the given resource type. Whereas if
     * it also contains an id, the method returns the end point url to get the specific single resource with that type
     * and id.
     *
     * @param reference a Reference class instance.
     * @return the complete end point url.
     */
    private String getUrl(ResourceReference reference) {
        String url = baseUrl + reference.getType().name().toLowerCase();
        String id = reference.getId();
        return Objects.isNull(id) || id.isEmpty() ? url : url + "/" + id;
    }

    /**
     * Restart the session.
     *
     * @param active boolean that selects whether to restart in active or passive mode.
     */
    private void internalRestart(boolean active) {
        try {
            openPassive();
            if (active) {
                openActive();
            }
            internalRestartScheduled = false;
        } catch (ApiException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("internalRestart() failed", e);
            } else {
                LOGGER.warn("Scheduled reconnection task failed.");
            }
            internalRestartScheduled = false;
            close2();
        } catch (InterruptedException e) {
        }
    }

    /**
     * The event stream calls this method when it has received text data. It parses the text as JSON into a list of
     * Event entries, converts the list of events to a list of resources, and forwards that list to the bridge
     * handler.
     *
     * @param data the incoming (presumed to be JSON) text.
     */
    protected void onEventData(String data) {
        if (onlineState != State.ACTIVE) {
            return;
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("onEventData() data:{}", data);
        } else {
            LOGGER.debug("onEventData() data length:{}", data.length());
        }
        JsonElement jsonElement;
        try {
            jsonElement = JsonParser.parseString(data);
        } catch (JsonSyntaxException e) {
            LOGGER.debug("onEventData() invalid data '{}'", data, e);
            return;
        }
        if (!(jsonElement instanceof JsonArray)) {
            LOGGER.debug("onEventData() data is not a JsonArray {}", data);
            return;
        }
        List<Event> events;
        try {
            events = jsonParser.fromJson(jsonElement, Event.EVENT_LIST_TYPE);
        } catch (JsonParseException e) {
            LOGGER.debug("onEventData() parsing error json:{}", data, e);
            return;
        }
        if (Objects.isNull(events) || events.isEmpty()) {
            LOGGER.debug("onEventData() event list is null or empty");
            return;
        }
        List<Resource> resources = new ArrayList<>();
        events.forEach(event -> resources.addAll(event.getData()));
        if (resources.isEmpty()) {
            LOGGER.debug("onEventData() resource list is empty");
            return;
        }
        resources.forEach(resource -> resource.markAsSparse());
        bridgeHandler.onResourcesEvent(resources);
    }

    /**
     * Open the HTTP 2 session and the event stream.
     *
     * @throws ApiException if there was a communication error.
     * @throws InterruptedException
     */
    public void open() throws ApiException, InterruptedException {
        LOGGER.debug("open()");
        openPassive();
        openActive();
        bridgeHandler.onConnectionOnline();
    }

    /**
     * Make the session active, by opening an HTTP 2 SSE event stream (if necessary).
     *
     * @throws ApiException if an error was encountered.
     * @throws InterruptedException
     */
    private void openActive() throws ApiException, InterruptedException {
        synchronized (this) {
            openEventStream();
            onlineState = State.ACTIVE;
        }
    }

    /**
     * Open the check alive task if necessary.
     */
    private void openCheckAliveTask() {
        Future<?> task = checkAliveTask;
        if (Objects.isNull(task) || task.isCancelled() || task.isDone()) {
            LOGGER.debug("openCheckAliveTask()");
            cancelTask(checkAliveTask, false);
            checkAliveTask = bridgeHandler.getScheduler().scheduleWithFixedDelay(() -> checkAlive(),
                    CHECK_ALIVE_SECONDS, CHECK_ALIVE_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Implementation to open an HTTP 2 SSE event stream if necessary.
     *
     * @throws ApiException if an error was encountered.
     * @throws InterruptedException
     */
    private void openEventStream() throws ApiException, InterruptedException {
        Session session = http2Session;
        if (Objects.isNull(session) || session.isClosed()) {
            throw new ApiException("HTTP 2 session is null or closed");
        }
        if (session.getStreams().stream().anyMatch(stream -> Objects.nonNull(stream.getAttribute(EVENT_STREAM_ID)))) {
            return;
        }
        LOGGER.debug("openEventStream()");
        HeadersFrame headers = prepareHeaders(eventUrl, MediaType.SERVER_SENT_EVENTS);
        LOGGER.trace("GET {} HTTP/2", eventUrl);
        Stream stream = null;
        try {
            Completable<@Nullable Stream> streamPromise = new Completable<>();
            EventStreamListenerAdapter eventStreamListener = new EventStreamListenerAdapter();
            session.newStream(headers, streamPromise, eventStreamListener);
            // wait for stream to be opened
            stream = Objects.requireNonNull(streamPromise.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            stream.setIdleTimeout(0);
            stream.setAttribute(EVENT_STREAM_ID, session);
            // wait for "hi" from the bridge
            eventStreamListener.awaitResult();
        } catch (ExecutionException | TimeoutException e) {
            if (Objects.nonNull(stream)) {
                stream.reset(new ResetFrame(stream.getId(), 0), Callback.NOOP);
            }
            throw new ApiException("Error opening event stream", e);
        }
    }

    /**
     * Private method to open the HTTP 2 session in passive mode.
     *
     * @throws ApiException if there was a communication error.
     * @throws InterruptedException
     */
    private void openPassive() throws ApiException, InterruptedException {
        synchronized (this) {
            LOGGER.debug("openPassive()");
            onlineState = State.CLOSED;
            try {
                http2Client.start();
            } catch (Exception e) {
                throw new ApiException("Error starting HTTP/2 client", e);
            }
            openSession();
            openCheckAliveTask();
            onlineState = State.PASSIVE;
        }
    }

    /**
     * Open the HTTP 2 session if necessary.
     *
     * @throws ApiException if it was not possible to create and connect the session.
     * @throws InterruptedException
     */
    private void openSession() throws ApiException, InterruptedException {
        Session session = http2Session;
        if (Objects.nonNull(session) && !session.isClosed()) {
            return;
        }
        LOGGER.debug("openSession()");
        InetSocketAddress address = new InetSocketAddress(hostName, 443);
        try {
            SessionListenerAdapter sessionListener = new SessionListenerAdapter();
            Completable<@Nullable Session> sessionPromise = new Completable<>();
            http2Client.connect(http2Client.getBean(SslContextFactory.class), address, sessionListener, sessionPromise);
            // wait for the (SSL) session to be opened
            http2Session = Objects.requireNonNull(sessionPromise.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            checkAliveOk(); // initialise the session timeout window
        } catch (ExecutionException | TimeoutException e) {
            throw new ApiException("Error opening HTTP 2 session", e);
        }
    }

    /**
     * Helper class to create a HeadersFrame for a standard HTTP GET request.
     *
     * @param url the server url.
     * @param acceptContentType the accepted content type for the response.
     * @return the HeadersFrame.
     */
    private HeadersFrame prepareHeaders(String url, String acceptContentType) {
        return prepareHeaders(url, acceptContentType, "GET", -1, null);
    }

    /**
     * Helper class to create a HeadersFrame for a more exotic HTTP request.
     *
     * @param url the server url.
     * @param acceptContentType the accepted content type for the response.
     * @param method the HTTP request method.
     * @param contentLength the length of the content e.g. for a PUT call.
     * @param contentType the respective content type.
     * @return the HeadersFrame.
     */
    private HeadersFrame prepareHeaders(String url, String acceptContentType, String method, long contentLength,
            @Nullable String contentType) {
        HttpFields fields = new HttpFields();
        fields.put(HttpHeader.ACCEPT, acceptContentType);
        if (contentType != null) {
            fields.put(HttpHeader.CONTENT_TYPE, contentType);
        }
        if (contentLength >= 0) {
            fields.putLongField(HttpHeader.CONTENT_LENGTH, contentLength);
        }
        fields.put(APPLICATION_KEY, applicationKey);
        return new HeadersFrame(new MetaData.Request(method, new HttpURI(url), HttpVersion.HTTP_2, fields), null,
                contentLength <= 0);
    }

    /**
     * Use an HTTP/2 PUT command to send a resource to the server.
     *
     * @param resource the resource to put.
     * @throws ApiException if something fails.
     * @throws InterruptedException
     */
    public void putResource(Resource resource) throws ApiException, InterruptedException {
        sleepDuringRestart();
        if (onlineState == State.CLOSED) {
            return;
        }
        Session session = http2Session;
        if (Objects.isNull(session) || session.isClosed()) {
            throw new ApiException("HTTP 2 session is null or closed");
        }
        throttle();
        String requestJson = jsonParser.toJson(resource);
        ByteBuffer requestBytes = ByteBuffer.wrap(requestJson.getBytes(StandardCharsets.UTF_8));
        String url = getUrl(new ResourceReference().setId(resource.getId()).setType(resource.getType()));
        HeadersFrame headers = prepareHeaders(url, MediaType.APPLICATION_JSON, "PUT", requestBytes.capacity(),
                MediaType.APPLICATION_JSON);
        LOGGER.trace("PUT {} HTTP/2 >> {}", url, requestJson);
        try {
            Completable<@Nullable Stream> streamPromise = new Completable<>();
            ContentStreamListenerAdapter contentStreamListener = new ContentStreamListenerAdapter();
            session.newStream(headers, streamPromise, contentStreamListener);
            // wait for stream to be opened
            Stream stream = Objects.requireNonNull(streamPromise.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            stream.data(new DataFrame(stream.getId(), requestBytes, true), Callback.NOOP);
            // wait for HTTP response
            String contentJson = contentStreamListener.awaitResult();
            String contentType = contentStreamListener.getContentType();
            LOGGER.trace("HTTP/2 200 OK (Content-Type: {}) << {}", contentType, contentJson);
            if (!MediaType.APPLICATION_JSON.equals(contentType)) {
                throw new ApiException("Unexpected Content-Type: " + contentType);
            }
            try {
                Resources resources = Objects.requireNonNull(jsonParser.fromJson(contentJson, Resources.class));
                if (LOGGER.isDebugEnabled()) {
                    resources.getErrors().forEach(error -> LOGGER.debug("putResource() resources error:{}", error));
                }
            } catch (JsonParseException e) {
                LOGGER.debug("putResource() parsing error json:{}", contentJson, e);
                throw new ApiException("Parsing error", e);
            }
        } catch (ExecutionException | TimeoutException e) {
            throw new ApiException("putResource() error sending request", e);
        } finally {
            throttleDone();
        }
    }

    /**
     * Try to register the application key with the hub. Use the given application key if one is provided; otherwise the
     * hub will create a new one. Note: this requires an HTTP 1.1 client call.
     *
     * @param oldApplicationKey existing application key if any i.e. may be empty.
     * @return the existing or a newly created application key.
     * @throws HttpUnauthorizedException if the registration failed.
     * @throws ApiException if there was a communications error.
     * @throws InterruptedException
     */
    public String registerApplicationKey(@Nullable String oldApplicationKey)
            throws HttpUnauthorizedException, ApiException, InterruptedException {
        LOGGER.debug("registerApplicationKey()");
        String json = jsonParser.toJson((Objects.isNull(oldApplicationKey) || oldApplicationKey.isEmpty())
                ? new CreateUserRequest(APPLICATION_ID)
                : new CreateUserRequest(oldApplicationKey, APPLICATION_ID));
        Request httpRequest = httpClient.newRequest(registrationUrl).method(HttpMethod.POST)
                .timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .content(new StringContentProvider(json), MediaType.APPLICATION_JSON);
        ContentResponse contentResponse;
        try {
            LOGGER.trace("POST {} HTTP/1.1 >> {}", registrationUrl, json);
            contentResponse = httpRequest.send();
        } catch (TimeoutException | ExecutionException e) {
            throw new ApiException("HTTP processing error", e);
        }
        int httpStatus = contentResponse.getStatus();
        json = contentResponse.getContentAsString().trim();
        LOGGER.trace("HTTP/1.1 {} {} << {}", httpStatus, contentResponse.getReason(), json);
        if (httpStatus != HttpStatus.OK_200) {
            throw new ApiException("HTTP bad response");
        }
        try {
            List<SuccessResponse> entries = jsonParser.fromJson(json, SuccessResponse.GSON_TYPE);
            if (Objects.nonNull(entries) && !entries.isEmpty()) {
                SuccessResponse response = entries.get(0);
                Map<String, Object> responseSuccess = response.success;
                if (Objects.nonNull(responseSuccess)) {
                    String newApplicationKey = (String) responseSuccess.get("username");
                    if (Objects.nonNull(newApplicationKey)) {
                        return newApplicationKey;
                    }
                }
            }
        } catch (JsonParseException e) {
            LOGGER.debug("registerApplicationKey() parsing error json:{}", json, e);
        }
        throw new HttpUnauthorizedException("Application key registration failed");
    }

    public void setExternalRestartScheduled() {
        externalRestartScheduled = true;
        internalRestartScheduled = false;
        cancelTask(internalRestartTask, false);
        internalRestartTask = null;
        close2();
    }

    /**
     * Sleep the caller during any period when the connection is restarting.
     *
     * @throws ApiException if anything failed.
     * @throws InterruptedException
     */
    private void sleepDuringRestart() throws ApiException, InterruptedException {
        Future<?> restartTask = this.internalRestartTask;
        if (Objects.nonNull(restartTask)) {
            try {
                restartTask.get(RESTART_AFTER_SECONDS * 2, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new ApiException("sleepDuringRestart() error", e);
            }
        }
        internalRestartScheduled = false;
    }

    /**
     * Test the Hue Bridge connection state by attempting to connect and trying to execute a basic command that requires
     * authentication.
     *
     * @throws HttpUnauthorizedException if it was possible to connect but not to authenticate.
     * @throws ApiException if it was not possible to connect.
     * @throws InterruptedException
     */
    public void testConnectionState() throws HttpUnauthorizedException, ApiException, InterruptedException {
        LOGGER.debug("testConnectionState()");
        try {
            openPassive();
            getResourcesImpl(BRIDGE);
        } catch (ApiException e) {
            close2();
            throw e;
        }
    }

    /**
     * Hue Bridges get confused if they receive too many HTTP requests in a short period of time (e.g. on start up), or
     * if too many HTTP sessions are opened at the same time. So this method throttles the requests to a maximum of one
     * per REQUEST_INTERVAL_MILLISECS, and ensures that no more than MAX_CONCURRENT_SESSIONS sessions are started.
     *
     * @throws InterruptedException
     */
    private synchronized void throttle() throws InterruptedException {
        streamMutex.acquire();
        Instant now = Instant.now();
        if (lastRequestTime.isPresent()) {
            long delay = Duration.between(now, lastRequestTime.get()).toMillis() + REQUEST_INTERVAL_MILLISECS;
            if (delay > 0) {
                Thread.sleep(delay);
            }
        }
        lastRequestTime = Optional.of(now);
    }

    /**
     * Release the mutex.
     */
    private void throttleDone() {
        streamMutex.release();
    }
}
