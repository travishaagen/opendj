/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.core;

import static org.forgerock.util.Utils.*;
import static org.opends.messages.ConfigMessages.*;
import static org.opends.server.replication.plugin.HistoricalCsnOrderingMatchingRuleImpl.*;
import static org.opends.server.schema.AciSyntax.*;
import static org.opends.server.schema.SubtreeSpecificationSyntax.*;
import static org.opends.server.util.StaticUtils.*;

import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.ClassPropertyDefinition;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.Entry;
import org.forgerock.opendj.ldap.schema.Schema;
import org.forgerock.opendj.ldap.schema.SchemaBuilder;
import org.forgerock.opendj.ldif.EntryReader;
import org.forgerock.opendj.ldif.LDIFEntryReader;
import org.forgerock.opendj.server.config.meta.SchemaProviderCfgDefn;
import org.forgerock.opendj.server.config.server.RootCfg;
import org.forgerock.opendj.server.config.server.SchemaProviderCfg;
import org.forgerock.util.Utils;
import org.opends.server.schema.SchemaProvider;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.InitializationException;
import org.opends.server.types.Schema.SchemaUpdater;
import org.opends.server.util.ActivateOnceSDKSchemaIsUsed;

/**
 * Responsible for loading the server schema.
 * <p>
 * The schema is loaded in three steps :
 * <ul>
 *   <li>Start from the core schema.</li>
 *   <li>Load schema elements from the schema providers defined in configuration.</li>
 *   <li>Load all schema files located in the schema directory.</li>
 * </ul>
 */
@ActivateOnceSDKSchemaIsUsed
public final class SchemaHandler
{
  private static final String CORE_SCHEMA_PROVIDER_NAME = "Core Schema";

  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  private ServerContext serverContext;

  private long oldestModificationTime = -1L;

  private long youngestModificationTime = -1L;

  /**
   * Creates a new instance.
   */
  public SchemaHandler()
  {
    // no implementation.
  }

  /**
   * Initialize this schema handler.
   *
   * @param serverContext
   *          The server context.
   * @throws ConfigException
   *           If a configuration problem arises in the process of performing
   *           the initialization.
   * @throws InitializationException
   *           If a problem that is not configuration-related occurs during
   *           initialization.
   */
  public void initialize(final ServerContext serverContext) throws InitializationException, ConfigException
  {
    this.serverContext = serverContext;

    final org.opends.server.types.Schema schema = serverContext.getSchema();

    schema.exclusiveLock();
    try
    {
      // Start from the core schema (TODO: or start with empty schema and add core schema in core schema provider ?)
      final SchemaBuilder schemaBuilder = new SchemaBuilder(Schema.getCoreSchema());

      // Take providers into account.
      loadSchemaFromProviders(serverContext.getRootConfig(), schemaBuilder);

      // Take schema files into account (TODO : or load files using provider mechanism ?)
      completeSchemaFromFiles(schemaBuilder);

      try
      {
        schema.updateSchema(new SchemaUpdater()
        {
          @Override
          public Schema update(SchemaBuilder ignored)
          {
            // see RemoteSchemaLoader.readSchema()
            addAciSyntax(schemaBuilder);
            addSubtreeSpecificationSyntax(schemaBuilder);
            addHistoricalCsnOrderingMatchingRule(schemaBuilder);

            // Uses the builder incrementally updated instead of the default provided by the method.
            // This is why it is necessary to explicitly lock/unlock the schema updater.
            return schemaBuilder.toSchema();
          }
        });
      }
      catch (DirectoryException e)
      {
        throw new ConfigException(e.getMessageObject(), e);
      }
    }
    finally
    {
      schema.exclusiveUnlock();
    }
  }

  /**
   * Load the schema from provided root configuration.
   *
   * @param rootConfiguration
   *          The root to retrieve schema provider configurations.
   * @param schemaBuilder
   *          The schema builder that providers should update.
   * @param schemaUpdater
   *          The updater that providers should use when applying a configuration change.
   */
  private void loadSchemaFromProviders(final RootCfg rootConfiguration, final SchemaBuilder schemaBuilder)
      throws ConfigException, InitializationException {
    for (final String name : rootConfiguration.listSchemaProviders())
    {
      final SchemaProviderCfg config = rootConfiguration.getSchemaProvider(name);
      if (config.isEnabled())
      {
        loadSchemaProvider(config.getJavaClass(), config, schemaBuilder, true);
      }
      else if (name.equals(CORE_SCHEMA_PROVIDER_NAME))
      {
        // TODO : use correct message ERR_CORE_SCHEMA_NOT_ENABLED
        throw new ConfigException(LocalizableMessage.raw("Core Schema can't be disabled"));
      }
    }
  }

  /**
   * Load the schema provider from the provided class name.
   * <p>
   * If {@code} initialize} is {@code true}, then the provider is initialized,
   * and the provided schema builder is updated with schema elements from the provider.
   */
  private <T extends SchemaProviderCfg> SchemaProvider<T> loadSchemaProvider(final String className,
      final T config, final SchemaBuilder schemaBuilder, final boolean initialize)
      throws InitializationException
  {
    try
    {
      final ClassPropertyDefinition propertyDef = SchemaProviderCfgDefn.getInstance().getJavaClassPropertyDefinition();
      final Class<? extends SchemaProvider> providerClass = propertyDef.loadClass(className, SchemaProvider.class);
      final SchemaProvider<T> provider = providerClass.newInstance();

      if (initialize)
      {
        provider.initialize(serverContext, config, schemaBuilder);
      }
      else
      {
        final List<LocalizableMessage> unacceptableReasons = new ArrayList<>();
        if (!provider.isConfigurationAcceptable(config, unacceptableReasons))
        {
          final String reasons = Utils.joinAsString(".  ", unacceptableReasons);
          // TODO : fix message, eg CONFIG SCHEMA PROVIDER CONFIG NOT ACCEPTABLE
          throw new InitializationException(ERR_CONFIG_ALERTHANDLER_CONFIG_NOT_ACCEPTABLE.get(config.dn(), reasons));
        }
      }
      return provider;
    }
    catch (Exception e)
    {
      // TODO : fix message
      throw new InitializationException(ERR_CONFIG_SCHEMA_SYNTAX_CANNOT_INITIALIZE.get(
          className, config.dn(), stackTraceToSingleLineString(e)), e);
    }
  }

  /**
   * Retrieves the path to the directory containing the server schema files.
   *
   * @return The path to the directory containing the server schema files.
   */
  private File getSchemaDirectoryPath() throws InitializationException
  {
    final File dir = serverContext.getEnvironment().getSchemaDirectory();
    if (dir == null)
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_NO_SCHEMA_DIR.get(null));
    }
    if (!dir.exists())
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_NO_SCHEMA_DIR.get(dir.getPath()));
    }
    if (!dir.isDirectory())
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_DIR_NOT_DIRECTORY.get(dir.getPath()));
    }
    return dir;
  }

  /** Returns the LDIF reader on provided LDIF file. The caller must ensure the reader is closed. */
  private EntryReader getLDIFReader(final File ldifFile, final Schema schema)
      throws InitializationException
  {
    try
    {
      final LDIFEntryReader reader = new LDIFEntryReader(new FileReader(ldifFile));
      reader.setSchema(schema);
      return reader;
    }
    catch (Exception e)
    {
      // TODO : fix message
      throw new InitializationException(ERR_CONFIG_FILE_CANNOT_OPEN_FOR_READ.get(ldifFile.getAbsolutePath(), e), e);
    }
  }

  /**
   * Complete the schema with schema files.
   *
   * @param schemaBuilder
   *          The schema builder to update with the content of the schema files.
   * @throws ConfigException
   *           If a configuration problem causes the schema element
   *           initialization to fail.
   * @throws InitializationException
   *           If a problem occurs while initializing the schema elements that
   *           is not related to the server configuration.
   */
  private void completeSchemaFromFiles(final SchemaBuilder schemaBuilder)
      throws ConfigException, InitializationException
  {
    final File schemaDirectory = getSchemaDirectoryPath();
    for (String schemaFile : getSchemaFileNames(schemaDirectory))
    {
      loadSchemaFile(schemaFile, schemaBuilder, Schema.getDefaultSchema());
    }
  }

  /** Returns the list of names of schema files contained in the provided directory. */
  private List<String> getSchemaFileNames(final File schemaDirectory) throws InitializationException {
    try
    {
      final File[] schemaFiles = schemaDirectory.listFiles(new SchemaFileFilter());
      final List<String> schemaFileNames = new ArrayList<>(schemaFiles.length);

      for (final File f : schemaFiles)
      {
        if (f.isFile())
        {
          schemaFileNames.add(f.getName());
        }

        final long modificationTime = f.lastModified();
        if (oldestModificationTime <= 0L
            || modificationTime < oldestModificationTime)
        {
          oldestModificationTime = modificationTime;
        }

        if (youngestModificationTime <= 0
            || modificationTime > youngestModificationTime)
        {
          youngestModificationTime = modificationTime;
        }
      }
      // If the oldest and youngest modification timestamps didn't get set
      // then set them to the current time.
      if (oldestModificationTime <= 0)
      {
        oldestModificationTime = System.currentTimeMillis();
      }

      if (youngestModificationTime <= 0)
      {
        youngestModificationTime = oldestModificationTime;
      }
      Collections.sort(schemaFileNames);
      return schemaFileNames;
    }
    catch (Exception e)
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_CANNOT_LIST_FILES
          .get(schemaDirectory, getExceptionMessage(e)), e);
    }
  }

  /** Returns the schema entry from the provided reader. */
  private Entry readSchemaEntry(final EntryReader reader, final File schemaFile) throws InitializationException {
    try
    {
      Entry entry = null;
      if (reader.hasNext())
      {
        entry = reader.readEntry();
        if (reader.hasNext())
        {
          // TODO : fix message
          logger.warn(WARN_CONFIG_SCHEMA_MULTIPLE_ENTRIES_IN_FILE, schemaFile, "");
        }
        return entry;
      }
      else
      {
        // TODO : fix message - should be SCHEMA NO LDIF ENTRY
        throw new InitializationException(WARN_CONFIG_SCHEMA_CANNOT_READ_LDIF_ENTRY.get(
            schemaFile, "", ""));
      }
    }
    catch (IOException e)
    {
      // TODO : fix message
      throw new InitializationException(WARN_CONFIG_SCHEMA_CANNOT_READ_LDIF_ENTRY.get(
              schemaFile, "", getExceptionMessage(e)), e);
    }
    finally
    {
      closeSilently(reader);
    }
  }

  /**
   * Add the schema from the provided schema file to the provided schema
   * builder.
   *
   * @param schemaFileName
   *          The name of the schema file to be loaded
   * @param schemaBuilder
   *          The schema builder in which the contents of the schema file are to
   *          be loaded.
   * @param readSchema
   *          The schema used to read the file.
   * @throws InitializationException
   *           If a problem occurs while initializing the schema elements.
   */
  private void loadSchemaFile(final String schemaFileName, final SchemaBuilder schemaBuilder, final Schema readSchema)
         throws InitializationException
  {
    EntryReader reader = null;
    try
    {
      File schemaFile = new File(getSchemaDirectoryPath(), schemaFileName);
      reader = getLDIFReader(schemaFile, readSchema);
      final Entry entry = readSchemaEntry(reader, schemaFile);
      // TODO : there is no more file information attached to schema elements - we should add support for this
      // in order to be able to redirect schema elements in the correct file when doing backups
      schemaBuilder.addSchema(entry, true);
    }
    finally {
      Utils.closeSilently(reader);
    }
  }

  /** A file filter implementation that accepts only LDIF files. */
  private static class SchemaFileFilter implements FilenameFilter
  {
    private static final String LDIF_SUFFIX = ".ldif";

    @Override
    public boolean accept(File directory, String filename)
    {
      return filename.endsWith(LDIF_SUFFIX);
    }
  }
}
