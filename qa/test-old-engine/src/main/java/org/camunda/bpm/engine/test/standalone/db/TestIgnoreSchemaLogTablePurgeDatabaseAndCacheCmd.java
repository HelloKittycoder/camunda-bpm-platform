/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.test.standalone.db;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.cmd.PurgeDatabaseAndCacheCmd;
import org.camunda.bpm.engine.impl.db.DbEntity;
import org.camunda.bpm.engine.impl.db.entitymanager.DbEntityManager;
import org.camunda.bpm.engine.impl.db.entitymanager.operation.DbBulkOperation;
import org.camunda.bpm.engine.impl.db.entitymanager.operation.DbOperationType;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.management.DatabasePurgeReport;
import org.camunda.bpm.engine.impl.management.PurgeReport;
import org.camunda.bpm.engine.impl.persistence.deploy.cache.CachePurgeReport;
import org.camunda.bpm.engine.impl.persistence.deploy.cache.DeploymentCache;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copy of the current (7.11) {@link PurgeDatabaseAndCacheCmd}. Used by
 * {@link TestIgnoreSchemaLogTableOnPurgePlugin} to ensure that the new database
 * table ACT_SCHEMA_LOG is ignored in purge reports. This is necessary to run
 * the test suite on a 7.10 engine with the 7.11 schema.
 * TODO: Remove this after the 7.11 release.
 * 
 * @author Miklas Boskamp
 *
 */
public class TestIgnoreSchemaLogTablePurgeDatabaseAndCacheCmd implements Command<PurgeReport>, Serializable {

  protected static final String DELETE_TABLE_DATA = "deleteTableData";
  protected static final String SELECT_TABLE_COUNT = "selectTableCount";
  protected static final String TABLE_NAME = "tableName";
  protected static final String EMPTY_STRING = "";

  public static final List<String> TABLENAMES_EXCLUDED_FROM_DB_CLEAN_CHECK = Arrays.asList("ACT_GE_PROPERTY", "ACT_GE_SCHEMA_LOG");

  @Override
  public PurgeReport execute(CommandContext commandContext) {
    PurgeReport purgeReport = new PurgeReport();

    // purge the database
    DatabasePurgeReport databasePurgeReport = purgeDatabase(commandContext);
    purgeReport.setDatabasePurgeReport(databasePurgeReport);

    // purge the deployment cache
    DeploymentCache deploymentCache = commandContext.getProcessEngineConfiguration().getDeploymentCache();
    CachePurgeReport cachePurgeReport = deploymentCache.purgeCache();
    purgeReport.setCachePurgeReport(cachePurgeReport);

    return purgeReport;
  }

  private DatabasePurgeReport purgeDatabase(CommandContext commandContext) {
    DbEntityManager dbEntityManager = commandContext.getDbEntityManager();
    // For MySQL and MariaDB we have to disable foreign key check,
    // to delete the table data as bulk operation (execution, incident etc.)
    // The flag will be reset by the DBEntityManager after flush.
    dbEntityManager.setIgnoreForeignKeysForNextFlush(true);
    List<String> tablesNames = dbEntityManager.getTableNamesPresentInDatabase();
    String databaseTablePrefix = commandContext.getProcessEngineConfiguration().getDatabaseTablePrefix().trim();

    // for each table
    DatabasePurgeReport databasePurgeReport = new DatabasePurgeReport();
    for (String tableName : tablesNames) {
      String tableNameWithoutPrefix = tableName.replace(databaseTablePrefix, EMPTY_STRING);
      if (!TABLENAMES_EXCLUDED_FROM_DB_CLEAN_CHECK.contains(tableNameWithoutPrefix)) {

        // Check if table contains data
        Map<String, String> param = new HashMap<String, String>();
        param.put(TABLE_NAME, tableName);
        Long count = (Long) dbEntityManager.selectOne(SELECT_TABLE_COUNT, param);

        if (count > 0) {
          databasePurgeReport.addPurgeInformation(tableName, count);
          // Get corresponding entity classes for the table, which contains data
          List<Class<? extends DbEntity>> entities = commandContext.getTableDataManager().getEntities(tableName);

          if (entities.isEmpty()) {
            throw new ProcessEngineException("No mapped implementation of " + DbEntity.class.getName() + " was found for: " + tableName);
          }

          // Delete the table data as bulk operation with the first entity
          Class<? extends DbEntity> entity = entities.get(0);
          DbBulkOperation deleteBulkOp = new DbBulkOperation(DbOperationType.DELETE_BULK, entity, DELETE_TABLE_DATA, param);
          dbEntityManager.getDbOperationManager().addOperation(deleteBulkOp);
        }
      }
    }
    return databasePurgeReport;
  }
}
