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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the new {@link EventManager#unsubscribe(ClassLoader)} API used by plugin hot-reload.
 *
 * <p>Handlers are matched by the ClassLoader that defined their owning class. To get a genuinely
 * distinct loader we create a fresh URLClassLoader (its parent is the bootstrap loader, so it
 * cannot resolve application classes by itself — but {@link java.lang.reflect.Proxy} can still
 * synthesise a proxy class *defined by* that loader implementing the bootstrap-resolvable
 * {@link Consumer} interface). That proxy's getClass().getClassLoader() is the plugin loader.</p>
 */
public class EventUnsubscribeTest {

    /** Minimal Event subclass with a no-arg constructor, to avoid event-specific deps. */
    public static class TestEvent extends Event {
    }

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
        // null parent => bootstrap loader. Proxy needs only Runnable/Consumer which are JDK types.
        this.pluginLoader = new URLClassLoader(new URL[0], null);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.eventManager.getThreadedExecutor().shutdownNow();
        this.pluginLoader.close();
    }

    /** Build a Consumer whose defining ClassLoader is {@link #pluginLoader}. */
    @SuppressWarnings("unchecked")
    private Consumer<TestEvent> newPluginHandler(AtomicInteger counter) {
        // Consumer is a JDK interface (bootstrap loader), so the proxy can implement it even when
        // defined by a loader whose parent is the bootstrap loader.
        return (Consumer<TestEvent>) java.lang.reflect.Proxy.newProxyInstance(
                this.pluginLoader,
                new Class[]{Consumer.class},
                (proxy, method, args) -> {
                    counter.incrementAndGet();
                    return null;
                });
    }

    @Test
    void unsubscribeByClassLoaderRemovesOnlyMatchingHandlers() {
        AtomicInteger removedCount = new AtomicInteger();
        AtomicInteger keptCount = new AtomicInteger();

        Consumer<TestEvent> pluginHandler = newPluginHandler(removedCount);
        Consumer<TestEvent> proxyHandler = event -> keptCount.incrementAndGet();

        assertEquals(this.pluginLoader, pluginHandler.getClass().getClassLoader(),
                "plugin handler must be defined by the plugin loader");

        this.eventManager.subscribe(TestEvent.class, pluginHandler);
        this.eventManager.subscribe(TestEvent.class, proxyHandler);

        // Sanity: both fire
        this.eventManager.callEvent(new TestEvent());
        assertEquals(1, removedCount.get());
        assertEquals(1, keptCount.get());

        // Unload the "plugin"
        int removed = this.eventManager.unsubscribe(this.pluginLoader);
        assertEquals(1, removed, "exactly the plugin handler should be removed");

        keptCount.set(0);
        removedCount.set(0);
        this.eventManager.callEvent(new TestEvent());
        assertEquals(0, removedCount.get(), "plugin handler must not fire after unload");
        assertEquals(1, keptCount.get(), "proxy handler must still fire");
    }

    @Test
    void unsubscribeRemovesEmptyEventHandlerEntry() {
        AtomicInteger cnt = new AtomicInteger();
        Consumer<TestEvent> handler = newPluginHandler(cnt);
        this.eventManager.subscribe(TestEvent.class, handler);

        assertNotNull(this.eventManager.getHandlerMap().get(TestEvent.class));

        int removed = this.eventManager.unsubscribe(this.pluginLoader);
        assertEquals(1, removed);
        assertFalse(this.eventManager.getHandlerMap().containsKey(TestEvent.class),
                "empty EventHandler entry must be removed from the map");
    }

    @Test
    void unsubscribeWithUnknownLoaderRemovesNothing() {
        Consumer<TestEvent> handler = event -> {};
        this.eventManager.subscribe(TestEvent.class, handler);
        int removed = this.eventManager.unsubscribe(new URLClassLoader(new URL[0], null));
        assertEquals(0, removed);
        assertTrue(this.eventManager.getHandlerMap().containsKey(TestEvent.class));
    }

    @Test
    void unsubscribeNullPluginIsSafe() {
        assertEquals(0, this.eventManager.unsubscribe((dev.waterdog.waterdogpe.plugin.Plugin) null));
    }
}
