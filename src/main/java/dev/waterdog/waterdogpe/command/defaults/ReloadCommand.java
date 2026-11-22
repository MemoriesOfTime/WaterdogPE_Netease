package dev.waterdog.waterdogpe.command.defaults;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.command.Command;
import dev.waterdog.waterdogpe.command.CommandSender;
import dev.waterdog.waterdogpe.command.CommandSettings;
import dev.waterdog.waterdogpe.utils.ConfigurationManager;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import net.cubespace.Yamler.Config.InvalidConfigurationException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

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
            result.getReady().whenComplete((ignored, error) -> {
                try {
                    this.reportOutcome(sender, result, error);
                } catch (Exception e) {
                    sender.getProxy().getLogger().error("Failed to report server reload result", e);
                }
            });
        } catch (InvalidConfigurationException e) {
            sender.sendMessage(new TranslationContainer("waterdog.command.reload.failed", e.getMessage()));
            proxy.getLogger().error("Failed to reload server list from config", e);
        }
        return true;
    }

    private void reportOutcome(CommandSender sender, ConfigurationManager.ReloadResult result, Throwable error) {
        if (error == null) {
            this.sendSuccess(sender, result);
            return;
        }
        Throwable cause = unwrap(error);
        if (cause instanceof CancellationException) {
            // A newer reload superseded this one; it reports its own outcome.
            return;
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        sender.sendMessage(new TranslationContainer("waterdog.command.reload.failed", message));
        sender.getProxy().getLogger().error("Failed to reload server list from config", cause);
    }

    private void sendSuccess(CommandSender sender, ConfigurationManager.ReloadResult result) {
        sender.sendMessage(new TranslationContainer("waterdog.command.reload.success",
                String.valueOf(result.getAdded()),
                String.valueOf(result.getRemoved()),
                String.valueOf(result.getUpdated())));
        if (!result.getPendingUpdates().isEmpty()) {
            sender.sendMessage(new TranslationContainer("waterdog.command.reload.pending.updates",
                    String.join(", ", result.getPendingUpdates())));
        }
        if (!result.getPendingRemovals().isEmpty()) {
            sender.sendMessage(new TranslationContainer("waterdog.command.reload.pending.removals",
                    String.join(", ", result.getPendingRemovals())));
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
