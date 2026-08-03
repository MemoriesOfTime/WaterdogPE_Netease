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

package dev.waterdog.waterdogpe.command.defaults;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.command.Command;
import dev.waterdog.waterdogpe.command.CommandSender;
import dev.waterdog.waterdogpe.command.CommandSettings;
import dev.waterdog.waterdogpe.plugin.Plugin;
import dev.waterdog.waterdogpe.plugin.PluginManager;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Hot-reload one or all plugins without restarting the proxy.
 *
 * <ul>
 *   <li>{@code wdreloadplugin}        — list loaded plugins and show usage</li>
 *   <li>{@code wdreloadplugin <name>} — disable, fully unload, then reload a single plugin</li>
 *   <li>{@code wdreloadplugin all}    — unload every plugin and reload them all from disk</li>
 * </ul>
 *
 * <p>Reload re-reads the jar from disk, so plugins can be hot-updated. Unload releases the
 * ClassLoader, event subscriptions, scheduled tasks, cached classes and Log4j logger configs
 * associated with the plugin. See {@link PluginManager#unloadPlugin(String)} for the full
 * contract and known limitations.</p>
 *
 * <p>The literal argument {@code all} (lowercase) is a reserved keyword that reloads every
 * plugin; plugin name lookup is case-sensitive, so a plugin actually named {@code "all"} cannot
 * be targeted through this command and would have to be reloaded via {@code wdreloadplugin all}.</p>
 */
public class ReloadPluginCommand extends Command {

    public ReloadPluginCommand() {
        super("wdreloadplugin", CommandSettings.builder()
                .setDescription("waterdog.command.reloadplugin.description")
                .setUsageMessage("waterdog.command.reloadplugin.usage")
                .setPermission("waterdog.command.reloadplugin.permission")
                .setAliases("wdrlp")
                .build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        ProxyServer proxy = ProxyServer.getInstance();
        PluginManager pluginManager = proxy.getPluginManager();

        if (args.length == 0) {
            List<String> names = new ArrayList<>();
            for (Plugin plugin : pluginManager.getPlugins()) {
                names.add((plugin.isEnabled() ? "§a" : "§c") + plugin.getName());
            }
            sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.list",
                    names.isEmpty() ? "(none)" : String.join("§r, ", names)));
            return false; // show usage
        }

        String target = args[0];
        if (target.equals("all")) {
            sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.all.start"));
            int loaded = pluginManager.reloadAllPlugins();
            if (loaded < 0) {
                sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.all.failed"));
                return true;
            }
            sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.all.done",
                    String.valueOf(loaded), String.valueOf(loaded)));
            return true;
        }

        // Single-plugin reload. reloadPlugin handles both the "already loaded" and "jar present
        // but never loaded at boot" cases, so try it directly and only classify the failure
        // afterwards — this avoids the contradictory "not found" + "success" message sequence.
        Plugin result = pluginManager.reloadPlugin(target);
        if (result != null) {
            sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.success", target));
            return true;
        }

        // Reload failed. Distinguish "no such jar on disk" from "jar found but load/enable failed"
        // so the operator gets an actionable message.
        if (pluginManager.getPluginByName(target) == null) {
            List<String> loadedNames = new ArrayList<>();
            for (Plugin p : pluginManager.getPlugins()) {
                loadedNames.add(p.getName());
            }
            sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.notfound",
                    target, loadedNames.isEmpty() ? "(none)" : String.join(", ", loadedNames)));
        } else {
            sender.sendMessage(new TranslationContainer("waterdog.command.reloadplugin.failed", target));
        }
        return true;
    }
}
