/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.extension;

import com.djrapitops.plan.data.plugin.PluginsConfigSection;
import com.djrapitops.plan.extension.implementation.DataProviderExtractor;
import com.djrapitops.plan.extension.implementation.ExtensionRegister;
import com.djrapitops.plan.extension.implementation.providers.gathering.ProviderValueGatherer;
import com.djrapitops.plan.system.DebugChannels;
import com.djrapitops.plan.system.database.DBSystem;
import com.djrapitops.plan.system.info.server.ServerInfo;
import com.djrapitops.plan.system.settings.config.PlanConfig;
import com.djrapitops.plugin.logging.L;
import com.djrapitops.plugin.logging.console.PluginLogger;
import com.djrapitops.plugin.logging.error.ErrorHandler;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation for {@link ExtensionService}.
 *
 * @author Rsl1122
 */
@Singleton
public class ExtensionServiceImplementation implements ExtensionService {

    private final PlanConfig config;
    private final DBSystem dbSystem;
    private final ServerInfo serverInfo;
    private final ExtensionRegister extensionRegister;
    private final PluginLogger logger;
    private final ErrorHandler errorHandler;

    private final Map<String, ProviderValueGatherer> extensionGatherers;

    @Inject
    public ExtensionServiceImplementation(
            PlanConfig config,
            DBSystem dbSystem,
            ServerInfo serverInfo,
            ExtensionRegister extensionRegister,
            PluginLogger logger,
            ErrorHandler errorHandler
    ) {
        this.config = config;
        this.dbSystem = dbSystem;
        this.serverInfo = serverInfo;
        this.extensionRegister = extensionRegister;
        this.logger = logger;
        this.errorHandler = errorHandler;

        extensionGatherers = new HashMap<>();

        ExtensionService.ExtensionServiceHolder.set(this);
    }

    public void enable() {
        extensionRegister.registerBuiltInExtensions();
    }

    @Override
    public void register(DataExtension extension) {
        DataProviderExtractor extractor = new DataProviderExtractor(extension);
        String pluginName = extractor.getPluginName();

        if (shouldNotAllowRegistration(pluginName)) return;

        for (String warning : extractor.getWarnings()) {
            logger.warn("DataExtension API implementation mistake for " + pluginName + ": " + warning);
        }

        ProviderValueGatherer gatherer = new ProviderValueGatherer(extension, extractor, dbSystem, serverInfo, logger);
        gatherer.storeExtensionInformation();
        extensionGatherers.put(pluginName, gatherer);

        logger.getDebugLogger().logOn(DebugChannels.DATA_EXTENSIONS, pluginName + " extension registered.");
    }

    private boolean shouldNotAllowRegistration(String pluginName) {
        PluginsConfigSection pluginsConfig = config.getPluginsConfigSection();

        if (!pluginsConfig.hasSection(pluginName)) {
            try {
                pluginsConfig.createSection(pluginName);
            } catch (IOException e) {
                errorHandler.log(L.ERROR, this.getClass(), e);
                logger.warn("Could not register DataExtension for " + pluginName + " due to " + e.toString());
                return true;
            }
        }

        if (!pluginsConfig.isEnabled(pluginName)) {
            logger.getDebugLogger().logOn(DebugChannels.DATA_EXTENSIONS, pluginName + " extension disabled in the config.");
            return true;
        }
        return false; // Should register.
    }

    public void updatePlayerValues(UUID playerUUID, String playerName) {
        for (Map.Entry<String, ProviderValueGatherer> gatherer : extensionGatherers.entrySet()) {
            try {
                gatherer.getValue().updateValues(playerUUID, playerName);
            } catch (Exception | NoClassDefFoundError | NoSuchMethodError | NoSuchFieldError e) {
                logger.warn(gatherer.getKey() + " ran into (but failed safely) " + e.getClass().getSimpleName() +
                        " when updating value for '" + playerName +
                        "', (You can disable integration with setting 'Plugins." + gatherer.getKey() + ".Enabled')" +
                        " reason: '" + e.getMessage() +
                        "', stack trace to follow:");
                errorHandler.log(L.WARN, gatherer.getValue().getClass(), e);
            }
        }
    }
}