/*
 * Copyright 2015 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.tests;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.nettosphere.Config;
import org.atmosphere.nettosphere.Nettosphere;
import org.atmosphere.wasync.ClientFactory;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.RequestBuilder;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class LongPollingTest extends StreamingTest {

    @Override
    Request.TRANSPORT transport() {
        return Request.TRANSPORT.LONG_POLLING;
    }

    @Override
    int statusCode() {
        return 200;
    }

    @Override
    int notFoundCode() {
        return 404;
    }

    @Test
    public void BinaryEchoTest() throws Exception {
        Config config = new Config.Builder()
                .port(port)
                .host("127.0.0.1")
                .resource("/suspend", new AtmosphereHandler() {

                    @Override
                    public void onRequest(AtmosphereResource resource) throws IOException {
                        if (resource.getRequest().getMethod().equals("GET")) {
                            resource.suspend(-1);
                        } else {
                            int payloadSize = resource.getRequest().getContentLength();
                            byte[] payload = new byte[payloadSize];
                            try {
                                resource.getRequest().getInputStream().read(payload);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            logger.info("echoing : {}", payload);
                            resource.getBroadcaster().broadcast(payload);
                        }
                    }

                    @Override
                    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                        if (!(event.isResuming()) || event.isResumedOnTimeout() || event.isSuspended()) {
                            // make the GET reply have binary content type
                            event.getResource().getResponse().setContentType("application/octet-stream");
                            // make it use the OutputStream directly in writing, prevent any String conversions.
                            event.getResource().getRequest().setAttribute(ApplicationConfig.PROPERTY_USE_STREAM, true);
                            // do the actual write
                            event.getResource().getResponse().write((byte[]) event.getMessage());
                            event.getResource().resume();
                        }
                    }

                    @Override
                    public void destroy() {

                    }

                }).build();

        Nettosphere server = new Nettosphere.Builder().config(config).build();
        assertNotNull(server);
        server.start();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean hasEchoReplied = new AtomicBoolean(false);
        AtmosphereClient client = ClientFactory.getDefault().newClient(AtmosphereClient.class);

        final byte[] binaryEcho = new byte[]{1, 2, 3, 4};

        RequestBuilder request = client.newRequestBuilder()
                .method(Request.METHOD.GET)
                .uri(targetUrl + "/suspend")
                .header("Content-Type", "application/octet-stream")
                .transport(Request.TRANSPORT.LONG_POLLING);

        final Socket socket = client.create(client.newOptionsBuilder().build());

        final CountDownLatch suspendedLatch = new CountDownLatch(1);

        socket.on(new Function<Integer>() {
            @Override
            public void on(Integer statusCode) {
                suspendedLatch.countDown();
            }
        }).on("message", new Function<byte[]>() {
            @Override
            public void on(byte[] message) {
                logger.info("===Received : {}", message);
                if (Arrays.equals(message, binaryEcho) && !hasEchoReplied.get()) {
                    hasEchoReplied.getAndSet(true);
                    socket.close();
                    latch.countDown();
                }
            }
        }).on(new Function<Throwable>() {
            @Override
            public void on(Throwable t) {
                t.printStackTrace();
            }
        }).open(request.build());

        suspendedLatch.await(5, TimeUnit.SECONDS);

        socket.fire(binaryEcho).get();

        latch.await(10, TimeUnit.SECONDS);

        assertEquals(hasEchoReplied.get(), true);
    }

    @Test
    public void noMessageLostTest() throws Exception {
        Config config = new Config.Builder()
                .port(port)
                .host("127.0.0.1")
                .broadcasterCache(org.atmosphere.cache.UUIDBroadcasterCache.class)
                .resource("/suspend", new AtmosphereHandler() {

                    @Override
                    public void onRequest(AtmosphereResource resource) throws IOException {
                        if (resource.getRequest().getMethod().equals("GET")) {
                            logger.info("Suspending : {}", resource.uuid());
                            resource.suspend(-1);
                        } else {
                            String echo = resource.getRequest().getReader().readLine();
                            logger.info("echoing : {}", echo);
                            try {
                                resource.getBroadcaster().broadcast(echo).get();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            } catch (ExecutionException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                        logger.info("cached : {}", event.getMessage());

                        if (List.class.isAssignableFrom(event.getMessage().getClass())) {
                            List<String> cached = (List<String>) List.class.cast(event.getMessage());
                            for (String m : cached) {
                                event.getResource().getResponse().write(m);
                            }
                        } else {
                            event.getResource().getResponse().write((String) event.getMessage());
                        }
                        // Netty may still write the bytes, so give a chance to those bytes to get written
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        event.getResource().resume();
                    }

                    @Override
                    public void destroy() {

                    }

                }).build();

        Nettosphere server = new Nettosphere.Builder().config(config).build();
        assertNotNull(server);
        server.start();

        final CountDownLatch latch = new CountDownLatch(5);
        final AtomicReference<Set> response = new AtomicReference<Set>(new HashSet());
        AtmosphereClient client = ClientFactory.getDefault().newClient(AtmosphereClient.class);

        RequestBuilder request = client.newRequestBuilder()
                .method(Request.METHOD.GET)
                .uri(targetUrl + "/suspend")
                .transport(Request.TRANSPORT.LONG_POLLING);

        final Socket socket = client.create(client.newOptionsBuilder().build());

        final CountDownLatch suspendedLatch = new CountDownLatch(1);

        socket.on(new Function<Integer>() {
            @Override
            public void on(Integer statusCode) {
                suspendedLatch.countDown();
            }
        }).on("message", new Function<String>() {
            @Override
            public void on(String message) {
                logger.info("received : {}", message);
                response.get().add(message);
                latch.countDown();
            }
        }).on(new Function<Throwable>() {
            @Override
            public void on(Throwable t) {
                t.printStackTrace();
            }
        }).open(request.build());

        suspendedLatch.await(5, TimeUnit.SECONDS);

        socket.fire("ECHO1");
        socket.fire("ECHO2");
        socket.fire("ECHO3");
        socket.fire("ECHO4");
        socket.fire("ECHO5");

        latch.await(10, TimeUnit.SECONDS);

        assertEquals(response.get().size(), 5);
        socket.close();
        server.stop();

    }
}
