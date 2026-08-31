/*
 * Copyright 2026 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.event;

import dev.waterdog.waterdogpe.ProxyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduces the plugin hot-reload crash reported with players online: the network threads
 * fire events ({@code callEvent} writes handlerMap via computeIfAbsent) while the tick thread
 * runs {@code reloadplugin} -> {@code unsubscribe(loader)} which iterates and mutates the same
 * map. The previous fastutil-backed map is not thread-safe, so the iterator's internal state
 * corrupts (NPE inside MapIterator.nextEntry).
 *
 * <p>The event population oscillates: the "network" thread re-inserts entries via callEvent
 * while the "tick" thread removes emptied ones via unsubscribe, forcing repeated rehashes and
 * table restructuring - exactly the conditions that corrupt a non-thread-safe hash map.</p>
 */
public class EventManagerConcurrentUnloadTest {

    // Enough distinct event classes to force several rehash cycles (16 -> 32 -> 64 -> 128).
    public static class Ev0 extends Event {
    }

    public static class Ev1 extends Event {
    }

    public static class Ev2 extends Event {
    }

    public static class Ev3 extends Event {
    }

    public static class Ev4 extends Event {
    }

    public static class Ev5 extends Event {
    }

    public static class Ev6 extends Event {
    }

    public static class Ev7 extends Event {
    }

    public static class Ev8 extends Event {
    }

    public static class Ev9 extends Event {
    }

    public static class Ev10 extends Event {
    }

    public static class Ev11 extends Event {
    }

    public static class Ev12 extends Event {
    }

    public static class Ev13 extends Event {
    }

    public static class Ev14 extends Event {
    }

    public static class Ev15 extends Event {
    }

    public static class Ev16 extends Event {
    }

    public static class Ev17 extends Event {
    }

    public static class Ev18 extends Event {
    }

    public static class Ev19 extends Event {
    }

    public static class Ev20 extends Event {
    }

    public static class Ev21 extends Event {
    }

    public static class Ev22 extends Event {
    }

    public static class Ev23 extends Event {
    }

    public static class Ev24 extends Event {
    }

    public static class Ev25 extends Event {
    }

    private static final List<Class<? extends Event>> EVENT_CLASSES = List.of(
            Ev0.class, Ev1.class, Ev2.class, Ev3.class, Ev4.class, Ev5.class, Ev6.class, Ev7.class,
            Ev8.class, Ev9.class, Ev10.class, Ev11.class, Ev12.class, Ev13.class, Ev14.class, Ev15.class,
            Ev16.class, Ev17.class, Ev18.class, Ev19.class, Ev20.class, Ev21.class, Ev22.class, Ev23.class,
            Ev24.class, Ev25.class);

    private static final int ROUNDS = 300;

    private EventManager eventManager;
    private URLClassLoader pluginLoader;

    @BeforeEach
    void setUp() {
        ProxyServer proxy = mock(ProxyServer.class);
        dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig cfg =
                mock(dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(cfg);
        when(cfg.getIdleThreads()).thenReturn(1);
        this.eventManager = new EventManager(proxy);
        this.pluginLoader = new URLClassLoader(new URL[0], null);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.eventManager.getThreadedExecutor().shutdownNow();
        this.pluginLoader.close();
    }

    /** Build a Consumer whose defining ClassLoader is {@link #pluginLoader}. */
    @SuppressWarnings("unchecked")
    private Consumer<Event> newPluginHandler() {
        return (Consumer<Event>) java.lang.reflect.Proxy.newProxyInstance(
                this.pluginLoader,
                new Class[]{Consumer.class},
                (proxy, method, args) -> {
                    // addIfAbsent() dedupes via equals(); a proxy must answer Object methods
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void doSubscribe(Class<? extends Event> clazz, Consumer<Event> handler, EventPriority priority) {
        this.eventManager.subscribe((Class<T>) clazz, (Consumer<T>) handler, priority);
    }

    @Test
    @Timeout(60)
    void concurrentFireAndUnloadDoesNotCorruptEventManager() throws Exception {
        List<Event> events = new ArrayList<>();
        for (Class<? extends Event> clazz : EVENT_CLASSES) {
            events.add(clazz.getDeclaredConstructor().newInstance());
        }
        Consumer<Event> pluginHandler = newPluginHandler();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger fireCount = new AtomicInteger();
        AtomicInteger unloadCount = new AtomicInteger();

        // Simulates network threads firing player events while the map is being mutated.
        Thread networkThread = new Thread(() -> {
            try {
                for (int i = 0; i < ROUNDS && !failed.get(); i++) {
                    for (Event event : events) {
                        this.eventManager.callEvent(event);
                        fireCount.incrementAndGet();
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
                t.printStackTrace();
            }
        }, "network-sim");

        // Simulates a plugin whose handler keeps being (re-)subscribed - races the fire path
        // on the per-event handler lists too, not just the top-level map.
        Thread pluginThread = new Thread(() -> {
            try {
                for (int i = 0; i < ROUNDS && !failed.get(); i++) {
                    this.doSubscribe(EVENT_CLASSES.get(i % EVENT_CLASSES.size()), pluginHandler, EventPriority.LOWEST);
                }
            } catch (Throwable t) {
                failed.set(true);
                t.printStackTrace();
            }
        }, "plugin-sim");

        // Simulates the tick thread running "reloadplugin": iterate + remove, concurrently
        // with the writers above.
        Thread tickThread = new Thread(() -> {
            try {
                for (int i = 0; i < ROUNDS; i++) {
                    this.eventManager.unsubscribe(this.pluginLoader);
                    unloadCount.incrementAndGet();
                }
            } catch (Throwable t) {
                failed.set(true);
                t.printStackTrace();
            }
        }, "tick-sim");

        pluginThread.start();
        networkThread.start();
        tickThread.start();
        pluginThread.join();
        networkThread.join();
        tickThread.join();

        assertTrue(!failed.get(), "concurrent callEvent/subscribe/unsubscribe must not throw (see stderr)");
        assertTrue(fireCount.get() > 0);
        assertTrue(unloadCount.get() == ROUNDS);
    }

    /**
     * Two hot-reloads racing (e.g. "reloadplugin" while "reloadall" runs) iterate overlapping
     * keySet() snapshots; one thread can drain a key the other snapshot still contains, so
     * unsubscribe() must tolerate its compute() finding no entry instead of NPE-ing.
     */
    @Test
    @Timeout(60)
    void concurrentDoubleUnloadDoesNotThrow() throws Exception {
        List<Event> events = new ArrayList<>();
        for (Class<? extends Event> clazz : EVENT_CLASSES) {
            events.add(clazz.getDeclaredConstructor().newInstance());
        }
        AtomicBoolean failed = new AtomicBoolean(false);

        // Keeps the map populated so the two unloaders actually race on live entries.
        Thread networkThread = new Thread(() -> {
            try {
                for (int i = 0; i < ROUNDS * 3 && !failed.get(); i++) {
                    for (Event event : events) {
                        this.eventManager.callEvent(event);
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
                t.printStackTrace();
            }
        }, "network-sim");

        Runnable unloader = () -> {
            try {
                for (int i = 0; i < ROUNDS; i++) {
                    this.eventManager.unsubscribe(this.pluginLoader);
                }
            } catch (Throwable t) {
                failed.set(true);
                t.printStackTrace();
            }
        };
        Thread unloadThreadA = new Thread(unloader, "unload-a");
        Thread unloadThreadB = new Thread(unloader, "unload-b");

        networkThread.start();
        unloadThreadA.start();
        unloadThreadB.start();
        networkThread.join();
        unloadThreadA.join();
        unloadThreadB.join();

        assertTrue(!failed.get(), "concurrent unsubscribe() calls must not throw (see stderr)");
    }

    /**
     * A subscription from loader X must survive an unrelated unload driven by loader Y:
     * subscribe() has to be atomic with the handlerMap entry lookup, otherwise the entry can
     * be emptied+removed between lookup and list-insert and the subscription is silently lost.
     */
    @Test
    @Timeout(60)
    void subscribeRacingUnrelatedUnloadIsNotLost() throws Exception {
        List<Event> events = new ArrayList<>();
        for (Class<? extends Event> clazz : EVENT_CLASSES) {
            events.add(clazz.getDeclaredConstructor().newInstance());
        }
        // Subscribed handler defined by otherLoader; unsubscribe(pluginLoader) must never match it.
        try (URLClassLoader otherLoader = new URLClassLoader(new URL[0], null)) {
            AtomicInteger fired = new AtomicInteger();
            @SuppressWarnings("unchecked")
            Consumer<Event> survivingHandler = (Consumer<Event>) java.lang.reflect.Proxy.newProxyInstance(
                    otherLoader,
                    new Class[]{Consumer.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        fired.incrementAndGet();
                        return null;
                    });
            AtomicBoolean failed = new AtomicBoolean(false);

            Thread subscribeThread = new Thread(() -> {
                try {
                    for (int i = 0; i < ROUNDS * 4 && !failed.get(); i++) {
                        this.doSubscribe(EVENT_CLASSES.get(i % EVENT_CLASSES.size()), survivingHandler, EventPriority.NORMAL);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                    t.printStackTrace();
                }
            }, "subscribe-sim");

            Thread unloadThread = new Thread(() -> {
                try {
                    for (int i = 0; i < ROUNDS; i++) {
                        this.eventManager.unsubscribe(this.pluginLoader);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                    t.printStackTrace();
                }
            }, "unload-sim");

            subscribeThread.start();
            unloadThread.start();
            subscribeThread.join();
            unloadThread.join();
            assertTrue(!failed.get(), "racing subscribe/unsubscribe must not throw (see stderr)");

            fired.set(0);
            for (Event event : events) {
                this.eventManager.callEvent(event);
            }
            assertTrue(fired.get() >= EVENT_CLASSES.size(),
                    "subscriptions must survive an unrelated loader's unload; got " + fired.get()
                            + " of " + EVENT_CLASSES.size());
        }
    }
}
