/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.test.fixtures.server.http;

import com.google.common.base.Charsets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.exceptions.DefaultMultiCauseException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class ChainingHttpHandler implements HttpHandler {
    private final AtomicInteger counter;
    private final List<TrackingHttpHandler> handlers = new CopyOnWriteArrayList<TrackingHttpHandler>();
    private final List<RequestOutcome> outcomes = new CopyOnWriteArrayList<RequestOutcome>();
    private final Lock lock;
    private WaitPrecondition last;
    private boolean completed;
    private final Condition condition;
    private int requestCount;

    ChainingHttpHandler(Lock lock, AtomicInteger counter, WaitPrecondition first) {
        this.lock = lock;
        this.condition = lock.newCondition();
        this.counter = counter;
        this.last = first;
    }

    public <T extends TrackingHttpHandler> T addHandler(HandlerFactory<T> factory) {
        lock.lock();
        try {
            T handler = factory.create(last);
            handlers.add(handler);
            last = handler.getWaitPrecondition();
            return handler;
        } finally {
            lock.unlock();
        }
    }

    public void assertComplete() {
        lock.lock();
        try {
            List<Throwable> failures = new ArrayList<Throwable>();
            for (RequestOutcome outcome : outcomes) {
                if (outcome.failure != null) {
                    failures.add(outcome.failure);
                }
            }
            if (!completed) {
                for (TrackingHttpHandler handler : handlers) {
                    try {
                        handler.assertComplete();
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }
                completed = true;
            }
            if (!failures.isEmpty()) {
                throw new DefaultMultiCauseException("Failed to handle all HTTP requests.", failures);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            int id = counter.incrementAndGet();
            RequestOutcome outcome = new RequestOutcome();
            outcomes.add(outcome);
            String requestMethod = httpExchange.getRequestMethod();
            String requestPath = httpExchange.getRequestURI().getPath();

            System.out.println(String.format("[%d] handling %s %s", id, requestMethod, requestPath));

            ResourceHandler resourceHandler = selectHandler(id, httpExchange, outcome);
            if (resourceHandler != null) {
                System.out.println(String.format("[%d] sending response for %s %s", id, requestMethod, requestPath));
                try {
                    resourceHandler.writeTo(id, httpExchange);
                } catch (Throwable t) {
                    System.out.println(String.format("[%d] handling %s %s failed with exception", id, requestMethod, requestPath));
                    outcome.failure = new AssertionError(String.format("Failed to handle %s %s", httpExchange.getRequestMethod(), httpExchange.getRequestURI().getPath()), t);
                }
            } else {
                System.out.println(String.format("[%d] sending error response for unexpected request", id));
                if (requestMethod.equals("HEAD")) {
                    httpExchange.sendResponseHeaders(500, -1);
                } else {
                    byte[] message = String.format("Failed %s request to %s", requestMethod, requestPath).getBytes(Charsets.UTF_8);
                    httpExchange.sendResponseHeaders(500, message.length);
                    httpExchange.getResponseBody().write(message);
                }
            }
        } finally {
            httpExchange.close();
        }
    }

    /**
     * Returns null on failure.
     */
    @Nullable
    private ResourceHandler selectHandler(int id, HttpExchange httpExchange, RequestOutcome outcome) {
        lock.lock();
        try {
            requestCount++;
            condition.signalAll();
            if (completed) {
                System.out.println(String.format("[%d] received request %s %s after HTTP server has stopped.", id, httpExchange.getRequestMethod(), httpExchange.getRequestURI()));
                return null;
            }
            for (TrackingHttpHandler handler : handlers) {
                ResourceHandler resourceHandler = handler.handle(id, httpExchange);
                if (resourceHandler != null) {
                    return resourceHandler;
                }
            }
            System.out.println(String.format("[%d] unexpected request %s %s", id, httpExchange.getRequestMethod(), httpExchange.getRequestURI()));
            outcome.failure = new AssertionError(String.format("Received unexpected request %s %s", httpExchange.getRequestMethod(), httpExchange.getRequestURI().getPath()));
        } catch (Throwable t) {
            System.out.println(String.format("[%d] error during handling of request %s %s", id, httpExchange.getRequestMethod(), httpExchange.getRequestURI()));
            outcome.failure = new AssertionError(String.format("Failed to handle %s %s", httpExchange.getRequestMethod(), httpExchange.getRequestURI().getPath()), t);
        } finally {
            lock.unlock();
        }
        return null;
    }

    void waitForRequests(int requestCount) {
        lock.lock();
        try {
            while (this.requestCount < requestCount) {
                try {
                    condition.await();
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static class RequestOutcome {
        Throwable failure;
    }

    interface HandlerFactory<T extends TrackingHttpHandler> {
        T create(WaitPrecondition previous);
    }
}
