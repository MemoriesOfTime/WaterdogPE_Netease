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

package dev.waterdog.waterdogpe.scheduler;

import dev.waterdog.waterdogpe.ProxyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link WaterdogScheduler#cancelTasksByClassLoader(ClassLoader)}, the new method used
 * by plugin hot-reload to drop a stale plugin's scheduled tasks.
 *
 * <p>Note: {@link WaterdogScheduler} enforces a singleton in its constructor, so only one test
 * class in the suite may construct a real instance. This is that class.</p>
 */
public class SchedulerCleanupTest {

    private WaterdogScheduler scheduler;

    @BeforeEach
    void setUp() {
        ProxyServer proxy = mock(ProxyServer.class);
        dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig cfg =
                mock(dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(cfg);
        when(cfg.getIdleThreads()).thenReturn(1);
        when(proxy.getCurrentTick()).thenReturn(0);
        // Singleton is initialized once for the whole JVM; reuse it if already present.
        this.scheduler = WaterdogScheduler.getInstance() != null
                ? WaterdogScheduler.getInstance()
                : new WaterdogScheduler(proxy);
    }

    @AfterEach
    void tearDown() {
        // Do NOT shutdown the singleton executor; other tests in the JVM may reuse it.
    }

    @Test
    void cancelTasksByClassLoaderRemovesOnlyMatchingTasks() throws Exception {
        AtomicBoolean pluginRan = new AtomicBoolean(false);
        AtomicBoolean proxyRan = new AtomicBoolean(false);

        // "Plugin" task: a Proxy implementing Runnable, defined by a dedicated loader so it can be
        // targeted separately from the test's own (system) loader.
        ClassLoader pluginLoader = new URLClassLoader(new URL[0], null);
        Runnable pluginTask = (Runnable) java.lang.reflect.Proxy.newProxyInstance(
                pluginLoader,
                new Class[]{Runnable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        pluginRan.set(true);
                    }
                    return null;
                });
        assertEquals(pluginLoader, pluginTask.getClass().getClassLoader(),
                "plugin task must be defined by the plugin loader");

        // "Proxy" task: anonymous, loaded by the system loader.
        Runnable proxyTask = () -> proxyRan.set(true);

        TaskHandler<?> pluginHandler = this.scheduler.scheduleRepeating(pluginTask, 1);
        TaskHandler<?> proxyHandler = this.scheduler.scheduleRepeating(proxyTask, 1);

        int cancelled = this.scheduler.cancelTasksByClassLoader(pluginLoader);
        assertEquals(1, cancelled, "exactly the plugin task should be cancelled");

        assertTrue(pluginHandler.isCancelled(), "plugin handler must be cancelled");
        assertFalse(proxyHandler.isCancelled(), "proxy handler must survive");

        // The cancelled task is removed from taskHandlerMap and skipped via isCancelled() even if
        // it were polled from pendingTasks.
        assertFalse(pluginRan.get(), "cancelled plugin task must not execute");
    }

    @Test
    void cancelTasksByClassLoaderWithNoMatchesReturnsZero() {
        Runnable task = () -> {};
        this.scheduler.scheduleDelayed(task, 100);
        int cancelled = this.scheduler.cancelTasksByClassLoader(new ClassLoader() {});
        assertEquals(0, cancelled);
    }
}
