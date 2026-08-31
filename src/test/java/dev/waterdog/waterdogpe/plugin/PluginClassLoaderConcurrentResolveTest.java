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

package dev.waterdog.waterdogpe.plugin;

import dev.waterdog.waterdogpe.ProxyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deadlock regression test for plugin class loading. {@code PluginClassLoader.findClass()}
 * must not take a lock (neither {@code synchronized} on the loader nor a shared lock):
 * {@code findClass(name, true)} calls {@code getClassFromCache()}, which enters every OTHER
 * loader's {@code findClass(name, false)}. With any findClass-level lock that traversal is
 * an A&lt;-&gt;B monitor deadlock - two plugins resolving classes that are not in the global
 * cache get flagged by the JVM's own deadlock monitor within seconds.
 *
 * <p>Concurrency background: this loader is not parallel-capable, so loadClass() serializes
 * all names on the loader instance monitor and two loadClass() calls on the SAME loader can
 * never overlap. The only concurrent entry into findClass() is {@code getClassFromCache()}
 * calling {@code findClass(name, false)} directly from network threads - exactly the
 * interleaving exercised here.</p>
 */
public class PluginClassLoaderConcurrentResolveTest {

    private static final int ROUNDS = 2000;

    private Path pluginDir;
    private PluginManager pluginManager;
    private PluginClassLoader loaderA;
    private PluginClassLoader loaderB;

    @BeforeEach
    void setUp() throws Exception {
        this.pluginDir = Files.createTempDirectory("wd-plugins");
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPluginPath()).thenReturn(this.pluginDir);
        this.pluginManager = new PluginManager(proxy);
        this.loaderA = new PluginClassLoader(this.pluginManager, null, new File(this.pluginDir.toFile(), "nonexistent-a.jar"));
        this.loaderB = new PluginClassLoader(this.pluginManager, null, new File(this.pluginDir.toFile(), "nonexistent-b.jar"));
        this.pluginManager.pluginClassLoaders.put("plugina", this.loaderA);
        this.pluginManager.pluginClassLoaders.put("pluginb", this.loaderB);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.loaderA.close();
        this.loaderB.close();
    }

    @Test
    @Timeout(60)
    void concurrentClassResolutionDoesNotDeadlock() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicBoolean failed = new AtomicBoolean(false);

        // Both threads resolve classes neither loader defines: each findClass(name, true)
        // misses its own cache and then walks getClassFromCache()'s traversal of ALL loaders,
        // including the one the other thread is inside of.
        Thread resolverA = new Thread(
                () -> this.resolveMissingClasses(this.loaderA, "missing.pkg.Cls", barrier, failed), "resolve-a");
        Thread resolverB = new Thread(
                () -> this.resolveMissingClasses(this.loaderB, "missing.other.Cls", barrier, failed), "resolve-b");
        resolverA.setDaemon(true);
        resolverB.setDaemon(true);
        resolverA.start();
        resolverB.start();

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        while (resolverA.isAlive() || resolverB.isAlive()) {
            long[] deadlocked = threads.findDeadlockedThreads();
            assertTrue(deadlocked == null || deadlocked.length == 0,
                    "concurrent class resolution deadlocked (see stderr for the failing threads)");
            Thread.sleep(10);
        }
        resolverA.join(1000);
        resolverB.join(1000);
        assertFalse(failed.get(), "resolver thread failed (see stderr)");
    }

    /**
     * Regression test for the duplicate-definition recovery in findClass(): the race's loser
     * must adopt the winner's class via findLoadedClass(), which sees the class the moment
     * its defineClass() returns - BEFORE the winner publishes it into the local and global
     * caches. Probing only the cache map misses that window and rethrows a spurious
     * "duplicate class definition" LinkageError on the loadClass() path.
     *
     * <p>The window is simulated deterministically: define the class once, then drop both
     * cache entries, leaving the class defined-but-uncached exactly as mid-race.</p>
     */
    @Test
    @Timeout(60)
    void duplicateDefinitionRecoveryAdoptsWinnerBeforeCachePublication() throws Exception {
        // A real class file from the test classpath (fastutil), packaged under its own
        // package so the dev.waterdog.waterdogpe.* prefix guard does not reject it.
        String className = "it.unimi.dsi.fastutil.ints.IntArrays";
        byte[] classBytes;
        try (InputStream in = PluginClassLoaderConcurrentResolveTest.class
                .getResourceAsStream("/it/unimi/dsi/fastutil/ints/IntArrays.class")) {
            assertNotNull(in, "fastutil IntArrays.class must be on the test classpath");
            classBytes = in.readAllBytes();
        }
        Path jar = this.pluginDir.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
            out.write(classBytes);
        }

        PluginClassLoader loader = new PluginClassLoader(this.pluginManager, null, jar.toFile());
        try {
            Class<?> first = loader.findClass(className, false);
            // Simulate the pre-publication window of the race: the winner's defineClass() has
            // completed, but neither its local nor the global cache entry exists yet.
            dropCacheEntry(PluginClassLoader.class, loader, "classes", className);
            dropCacheEntry(PluginManager.class, this.pluginManager, "cachedClasses", className);

            Class<?> second = loader.findClass(className, true);
            assertSame(first, second, "loser must adopt the winner's already-defined class");
            // The recovery must have republished the class locally, so a third lookup goes
            // through the cache instead of another losing define.
            assertSame(first, loader.findClass(className));
        } finally {
            loader.close();
        }
    }

    private static void dropCacheEntry(Class<?> owner, Object instance, String fieldName, String key) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<?, ?>) field.get(instance)).remove(key);
    }

    private void resolveMissingClasses(PluginClassLoader loader, String classPrefix, CyclicBarrier barrier, AtomicBoolean failed) {
        try {
            for (int i = 0; i < ROUNDS; i++) {
                // Line both threads up so their findClass() calls overlap.
                barrier.await();
                try {
                    loader.findClass(classPrefix + i, true);
                } catch (ClassNotFoundException expected) {
                    // The classes do not exist - the point is exercising the global traversal.
                }
            }
        } catch (Throwable t) {
            failed.set(true);
            t.printStackTrace();
        }
    }
}
