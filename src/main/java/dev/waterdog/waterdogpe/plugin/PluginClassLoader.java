/*
 * Copyright 2022 WaterdogTEAM
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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple class loader which holds classes of plugins.
 * It allows plugins to access each other.
 */
public class PluginClassLoader extends URLClassLoader {

    private final PluginManager pluginManager;
    // Deliberately no lock on findClass. This loader is not parallel-capable, so loadClass()
    // already serializes every name on the loader instance monitor; the only concurrent entry
    // is getClassFromCache() calling findClass(name, false) directly from network threads.
    // Any findClass-level lock - on this or a shared object - deadlocks loaders A<->B when
    // findClass(name, true) traverses getClassFromCache() into the other loader's locked
    // findClass(name, false) (see PluginClassLoaderConcurrentResolveTest). The remaining
    // duplicate-define race is recovered in the LinkageError handler below.
    private final ConcurrentHashMap<String, Class<?>> classes = new ConcurrentHashMap<>();

    public PluginClassLoader(PluginManager pluginManager, ClassLoader parent, File file) throws MalformedURLException {
        super(new URL[]{file.toURI().toURL()}, parent);
        this.pluginManager = pluginManager;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return this.findClass(name, true);
    }

    protected Class<?> findClass(String name, boolean checkGlobal) throws ClassNotFoundException {
        if (name.startsWith("dev.waterdog.waterdogpe.")) { // Proxy classes should be known
            throw new ClassNotFoundException(name);
        }

        Class<?> result = this.classes.get(name);
        if (result != null) {
            return result;
        }

        if (checkGlobal) {
            result = this.pluginManager.getClassFromCache(name);
            if (result != null) {
                this.classes.put(name, result);
                return result;
            }
        }

        try {
            result = super.findClass(name);
        } catch (LinkageError e) {
            // Lost a race into super.findClass() for the same name: the JVM rejects the
            // second definition with a LinkageError, so adopt the winner's class.
            // findLoadedClass() sees the winner as soon as its defineClass() returns, before
            // it is published to this.classes - probing only the map would miss that window
            // and rethrow a spurious LinkageError.
            Class<?> winner = this.findLoadedClass(name);
            if (winner == null) {
                winner = this.classes.get(name);
            }
            if (winner != null) {
                this.classes.putIfAbsent(name, winner);
                return winner;
            }
            if (checkGlobal) {
                throw e; // loadClass() path: surface the real definition error
            }
            throw new ClassNotFoundException(name, e); // probe path: report a plain miss
        }
        this.pluginManager.cacheClass(name, result);
        Class<?> existing = this.classes.putIfAbsent(name, result);
        return existing != null ? existing : result;
    }

}
