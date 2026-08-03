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

package dev.waterdog.waterdogpe.event;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.plugin.Plugin;
import dev.waterdog.waterdogpe.utils.ThreadFactoryBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Event Manager
 * Enables Plugins to subscribe to Events, either vanilla events already implemented
 * or custom ones which are loaded as part of a plugin.
 */
public class EventManager {

    private final ProxyServer proxy;
    @Getter
    private final ExecutorService threadedExecutor;
    @Getter
    private final Object2ObjectOpenHashMap<Class<? extends Event>, EventHandler> handlerMap = new Object2ObjectOpenHashMap<>();

    public EventManager(ProxyServer proxy) {
        this.proxy = proxy;
        ThreadFactoryBuilder builder = ThreadFactoryBuilder.builder()
                .format("WaterdogEvents Executor - #%d")
                .build();
        int idleThreads = this.proxy.getConfiguration().getIdleThreads();
        this.threadedExecutor = new ThreadPoolExecutor(idleThreads, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(true), builder);
    }

    public <T extends Event> void subscribe(Class<T> event, Consumer<T> handler) {
        this.subscribe(event, handler, EventPriority.NORMAL);
    }

    /**
     * Can be used to subscribe to events. Once subscribed, the handler will be called each time the event is called.
     *
     * @param event    A class reference to the target event you want to subscribe to, for example ProxyPingEvent.class
     * @param handler  A method reference or lambda with one parameter, the event which you want to handle
     * @param priority The Priority of your event handler. Can be used to execute one handler after / before another
     * @param <T>      The class reference to the event you want to subscribe to
     * @see AsyncEvent
     * @see EventPriority
     */
    public <T extends Event> void subscribe(Class<T> event, Consumer<T> handler, EventPriority priority) {
        EventHandler eventHandler = this.handlerMap.computeIfAbsent(event, e -> new EventHandler(event, this));
        eventHandler.subscribe((Consumer<Event>) handler, priority);
    }

    /**
     * Remove every handler whose owning class was loaded by the given ClassLoader.
     * Intended for plugin hot-reload: drops subscriptions that would otherwise keep an
     * unloaded plugin's classes alive (and possibly fire on stale state).
     *
     * @param loader the ClassLoader of the plugin being unloaded, or null to match handlers
     *               whose owning class has no defining ClassLoader (e.g. JDK lambdas from
     *               the bootstrap loader — rarely the target, so null usually removes nothing)
     * @return total number of handlers removed across all events
     */
    public int unsubscribe(ClassLoader loader) {
        int removed = 0;
        // handlerMap is a fastutil Object2ObjectOpenHashMap; iterate a snapshot to allow removal
        for (Iterator<Map.Entry<Class<? extends Event>, EventHandler>> it = new ArrayList<>(this.handlerMap.entrySet()).iterator(); it.hasNext(); ) {
            Map.Entry<Class<? extends Event>, EventHandler> entry = it.next();
            EventHandler handler = entry.getValue();
            removed += handler.unsubscribe(consumer -> {
                ClassLoader consumerLoader = consumer.getClass().getClassLoader();
                return consumerLoader != null && consumerLoader == loader;
            });
            if (handler.isEmpty()) {
                this.handlerMap.remove(entry.getKey());
            }
        }
        return removed;
    }

    /**
     * Convenience overload: unsubscribe every handler defined by the given plugin's ClassLoader.
     */
    public int unsubscribe(Plugin plugin) {
        if (plugin == null) {
            return 0;
        }
        return this.unsubscribe(plugin.getClass().getClassLoader());
    }

    /**
     * Used to call an provided event.
     * If the target event has the annotation AsyncEvent present, the CompletableFuture.whenComplete can be used to
     * execute code once the event has passed the whole event pipeline. If the annotation is not present, you can
     * ignore the return and use the direct variable reference of your event
     *
     * @param event the instance of an event to be called
     * @return CompletableFuture<Event> if event has AsyncEvent annotation present or null in case of non-async event
     */
    public <T extends Event> CompletableFuture<T> callEvent(T event) {
        EventHandler eventHandler = this.handlerMap.computeIfAbsent(event.getClass(), e -> new EventHandler(event.getClass(), this));
        return eventHandler.handle(event);
    }

}
