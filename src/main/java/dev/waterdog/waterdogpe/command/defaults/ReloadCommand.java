package dev.waterdog.waterdogpe.command.defaults;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.command.Command;
import dev.waterdog.waterdogpe.command.CommandSender;
import dev.waterdog.waterdogpe.command.CommandSettings;
import dev.waterdog.waterdogpe.utils.ConfigurationManager;
import net.cubespace.Yamler.Config.InvalidConfigurationException;

public class ReloadCommand extends Command {

    public ReloadCommand() {
        super("wdreload", CommandSettings.builder()
                .setDescription("waterdog.command.reload.description")
                .setUsageMessage("waterdog.command.reload.usage")
                .setPermission("waterdog.command.reload.permission")
                .build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        ProxyServer proxy = ProxyServer.getInstance();
        ConfigurationManager configManager = proxy.getConfigurationManager();

        try {
            ConfigurationManager.ReloadResult result = configManager.reloadServerInfos(proxy.getServerInfoMap());
            sender.sendMessage("§aServer list reloaded! §3Added: §b" + result.getAdded()
                    + "§3, Removed: §b" + result.getRemoved()
                    + "§3, Updated: §b" + result.getUpdated());
            if (!result.getPendingUpdates().isEmpty()) {
                sender.sendMessage("§ePending updates (will apply after players leave): §b" + String.join(", ", result.getPendingUpdates()));
            }
            if (!result.getPendingRemovals().isEmpty()) {
                sender.sendMessage("§ePending removals (will apply after players leave): §b" + String.join(", ", result.getPendingRemovals()));
            }
        } catch (InvalidConfigurationException e) {
            sender.sendMessage("§cFailed to reload config: " + e.getMessage());
            proxy.getLogger().error("Failed to reload server list from config", e);
        }
        return true;
    }
}
