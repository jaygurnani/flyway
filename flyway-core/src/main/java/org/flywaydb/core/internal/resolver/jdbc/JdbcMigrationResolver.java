/*
 * Copyright 2010-2017 Boxfuse GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.resolver.jdbc;

import org.flywaydb.core.api.configuration.FlywayConfiguration;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.MigrationChecksumProvider;
import org.flywaydb.core.api.migration.MigrationInfoProvider;
import org.flywaydb.core.api.migration.jdbc.JdbcMigration;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.resolver.MigrationInfoHelper;
import org.flywaydb.core.internal.resolver.ResolvedMigrationComparator;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;
import org.flywaydb.core.internal.util.*;
import org.flywaydb.core.internal.util.scanner.Scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migration resolver for Jdbc migrations. The classes must have a name like R__My_description, V1__Description
 * or V1_1_3__Description.
 */
public class JdbcMigrationResolver implements MigrationResolver {
    /**
     * The base package on the classpath where to migrations are located.
     */
    private final Locations locations;

    /**
     * The Scanner to use.
     */
    private Scanner scanner;

    /**
     * The configuration to inject (if necessary) in the migration classes.
     */
    private FlywayConfiguration configuration;

    /**
     * Creates a new instance.
     *
     * @param locations     The base packages on the classpath where to migrations are located.
     * @param scanner       The Scanner for loading migrations on the classpath.
     * @param configuration The configuration to inject (if necessary) in the migration classes.
     */
    public JdbcMigrationResolver(Scanner scanner, Locations locations, FlywayConfiguration configuration) {
        this.locations = locations;
        this.scanner = scanner;
        this.configuration = configuration;
    }

    @Override
    public List<ResolvedMigration> resolveMigrations() {
        List<ResolvedMigration> migrations = new ArrayList<ResolvedMigration>();

        for (Location location : locations.getLocations()) {
            if (!location.isClassPath()) {
                continue;
            }
            resolveMigrationsForSingleLocation(location, migrations);
        }

        Collections.sort(migrations, new ResolvedMigrationComparator());
        return migrations;
    }

    protected void resolveMigrationsForSingleLocation(Location location, List<ResolvedMigration> migrations) {

        try {
            Class<?>[] classes = scanner.scanForClasses(location, JdbcMigration.class);
            for (Class<?> clazz : classes) {
                JdbcMigration jdbcMigration = ClassUtils.instantiate(clazz.getName(), scanner.getClassLoader());
                ConfigurationInjectionUtils.injectFlywayConfiguration(jdbcMigration, configuration);

                ResolvedMigrationImpl migrationInfo = extractMigrationInfo(jdbcMigration);
                migrationInfo.setPhysicalLocation(ClassUtils.getLocationOnDisk(clazz));
                migrationInfo.setExecutor(new JdbcMigrationExecutor(jdbcMigration));

                migrations.add(migrationInfo);
            }
        } catch (Exception e) {
            throw new FlywayException("Unable to resolve Jdbc Java migrations in location: " + location + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Extracts the migration info from this migration.
     *
     * @param jdbcMigration The migration to analyse.
     * @return The migration info.
     */
    /* private -> testing */ ResolvedMigrationImpl extractMigrationInfo(JdbcMigration jdbcMigration) {
        Integer checksum = null;
        if (jdbcMigration instanceof MigrationChecksumProvider) {
            MigrationChecksumProvider checksumProvider = (MigrationChecksumProvider) jdbcMigration;
            checksum = checksumProvider.getChecksum();
        }

        MigrationVersion version;
        String description;
        if (jdbcMigration instanceof MigrationInfoProvider) {
            MigrationInfoProvider infoProvider = (MigrationInfoProvider) jdbcMigration;
            version = infoProvider.getVersion();
            description = infoProvider.getDescription();
            if (!StringUtils.hasText(description)) {
                throw new FlywayException("Missing description for migration " + version);
            }
        } else {
            String shortName = ClassUtils.getShortName(jdbcMigration.getClass());
            String prefix;
            if (shortName.startsWith("V") || shortName.startsWith("R")) {
                prefix = shortName.substring(0, 1);
            } else {
                throw new FlywayException("Invalid Jdbc migration class name: " + jdbcMigration.getClass().getName()
                        + " => ensure it starts with V or R," +
                        " or implement org.flywaydb.core.api.migration.MigrationInfoProvider for non-default naming");
            }
            Pair<MigrationVersion, String> info = MigrationInfoHelper.extractVersionAndDescription(shortName, prefix, "__", "");
            version = info.getLeft();
            description = info.getRight();
        }

        ResolvedMigrationImpl resolvedMigration = new ResolvedMigrationImpl();
        resolvedMigration.setVersion(version);
        resolvedMigration.setDescription(description);
        resolvedMigration.setScript(jdbcMigration.getClass().getName());
        resolvedMigration.setChecksum(checksum);
        resolvedMigration.setType(MigrationType.JDBC);
        return resolvedMigration;
    }
}
