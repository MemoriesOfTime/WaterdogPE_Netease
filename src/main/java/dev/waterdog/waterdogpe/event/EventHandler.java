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
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.utils.exceptions.EventException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Internal EventHandler Class
 * Manages Event Calling, priorities, execution ordering.
 * Should not be modified if not necessary
 */
public class EventHandler {

    private final EventManager eventManager;
    private final Class<? extends Event> eventClass;

    // Fired (read) from network threads while subscribe/unsubscribe (write) run on plugin or
    // tick threads - CopyOnWriteArrayList keeps the hot fire path lock-free.
    private final ConcurrentMap<EventPriority, CopyOnWriteArrayList<Consumer<Event>>> priority2handlers = new ConcurrentHashMap<>();

    public EventHandler(Class<? extends Event> eventClass, EventManager eventManager) {
        this.eventClass = eventClass;
        this.eventManager = eventManager;
    }

    public <T extends Event>  CompletableFuture<T> handle(T event) {
        if (!this.eventClass.isInstance(event)) {
            throw new EventException("Tried to handle invalid event type!");
        }

        if (!event.isAsync()) {
            return this.handleSync(event);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> {
            for (EventPriority priority : EventPriority.values()) {
                this.handlePriority(priority, event);
            }
            return event;
        }, this.eventManager.getThreadedExecutor()).thenAccept(futureEvent -> futureEvent.completeFuture(future)).whenComplete((ignore, error) -> {
            if (error != null && !future.isDone()) {
                future.completeExceptionally(error);
                ProxyServer.getInstance().getLogger().error("Exception was thrown in event handler", error);
            }
        });
        return future;
    }

    private <T extends Event> CompletableFuture<T> handleSync(T event) {
        if (!event.isCompletable()) {
            for (EventPriority priority : EventPriority.values()) {
                this.handlePriority(priority, event);
            }
            // Non-completable events does not provide future.
            return null;
        }

        try {
            for (EventPriority priority : EventPriority.values()) {
                this.handlePriority(priority, event);
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        if (event.getCompletableFutures().isEmpty()) {
            return CompletableFuture.completedFuture(event);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        event.completeFuture(future);
        return future;
    }

    private void handlePriority(EventPriority priority, Event event) {
        List<Consumer<Event>> handlerList = this.priority2handlers.get(priority);
        if (handlerList != null) {
            for (Consumer<Event> eventHandler : handlerList) {
                try {
                    eventHandler.accept(event);
                } catch (Throwable t) {
                    // One broken plugin handler must not break the caller or the remaining handlers.
                    MainLogger.getLogger().error("Exception was thrown in " + event.getClass().getSimpleName() + " handler", t);
                }
            }
        }
    }

    public void subscribe(Consumer<Event> handler, EventPriority priority) {
        // compute(), not computeIfAbsent + separate addIfAbsent: a concurrent unsubscribe() can
        // empty and remove the entry between the two, stranding this handler on the detached list.
        this.priority2handlers.compute(priority, (p, handlerList) -> {
            if (handlerList == null) {
                handlerList = new CopyOnWriteArrayList<>();
            }
            handlerList.addIfAbsent(handler);
            return handlerList;
        });
    }

    /**
     * Removes all handlers matching the given predicate.
     * Used by plugin reload to drop subscriptions whose lambda/class belongs to an unloaded plugin.
     *
     * @param matcher predicate that returns true for handlers that should be removed.
     *                Runs while the internal per-priority lock is held: it must not
     *                subscribe or unsubscribe handlers of this event and should stay cheap.
     * @return number of handlers removed
     */
    public int unsubscribe(Predicate<Consumer<Event>> matcher) {
        AtomicInteger removed = new AtomicInteger();
        for (EventPriority priority : EventPriority.values()) {
            // computeIfPresent is atomic per priority: an emptied list is never removed while
            // a concurrent subscribe() is adding a handler to it.
            this.priority2handlers.computeIfPresent(priority, (p, handlerList) -> {
                handlerList.removeIf(handler -> {
                    if (matcher.test(handler)) {
                        removed.incrementAndGet();
                        return true;
                    }
                    return false;
                });
                return handlerList.isEmpty() ? null : handlerList;
            });
        }
        return removed.get();
    }

    /**
     * @return true when no handlers remain across any priority
     */
    public boolean isEmpty() {
        return this.priority2handlers.isEmpty();
    }
}
