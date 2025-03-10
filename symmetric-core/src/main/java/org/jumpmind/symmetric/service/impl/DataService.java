/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.service.impl;

import static org.apache.commons.lang.StringUtils.isNotBlank;

import java.nio.charset.Charset;
import java.sql.DataTruncation;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.DatabaseInfo;
import org.jumpmind.db.sql.ISqlReadCursor;
import org.jumpmind.db.sql.ISqlRowMapper;
import org.jumpmind.db.sql.ISqlTransaction;
import org.jumpmind.db.sql.InvalidSqlException;
import org.jumpmind.db.sql.Row;
import org.jumpmind.db.sql.SqlException;
import org.jumpmind.db.sql.UniqueKeyException;
import org.jumpmind.db.sql.mapper.NumberMapper;
import org.jumpmind.db.util.TableRow;
import org.jumpmind.exception.IoException;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.SymmetricException;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.common.TableConstants;
import org.jumpmind.symmetric.db.SequenceIdentifier;
import org.jumpmind.symmetric.ext.IHeartbeatListener;
import org.jumpmind.symmetric.io.data.Batch;
import org.jumpmind.symmetric.io.data.CsvData;
import org.jumpmind.symmetric.io.data.CsvUtils;
import org.jumpmind.symmetric.io.data.DataEventType;
import org.jumpmind.symmetric.io.data.transform.TransformPoint;
import org.jumpmind.symmetric.job.OracleNoOrderHeartbeat;
import org.jumpmind.symmetric.job.PushHeartbeatListener;
import org.jumpmind.symmetric.load.IReloadListener;
import org.jumpmind.symmetric.load.IReloadVariableFilter;
import org.jumpmind.symmetric.model.AbstractBatch.Status;
import org.jumpmind.symmetric.model.Channel;
import org.jumpmind.symmetric.model.Data;
import org.jumpmind.symmetric.model.DataEvent;
import org.jumpmind.symmetric.model.DataGap;
import org.jumpmind.symmetric.model.ExtractRequest;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.model.NodeGroupLink;
import org.jumpmind.symmetric.model.NodeGroupLinkAction;
import org.jumpmind.symmetric.model.NodeSecurity;
import org.jumpmind.symmetric.model.OutgoingBatch;
import org.jumpmind.symmetric.model.ProcessInfo;
import org.jumpmind.symmetric.model.Router;
import org.jumpmind.symmetric.model.TableReloadRequest;
import org.jumpmind.symmetric.model.TableReloadRequestKey;
import org.jumpmind.symmetric.model.TableReloadStatus;
import org.jumpmind.symmetric.model.Trigger;
import org.jumpmind.symmetric.model.TriggerHistory;
import org.jumpmind.symmetric.model.TriggerReBuildReason;
import org.jumpmind.symmetric.model.TriggerRouter;
import org.jumpmind.symmetric.service.ClusterConstants;
import org.jumpmind.symmetric.service.IDataService;
import org.jumpmind.symmetric.service.IExtensionService;
import org.jumpmind.symmetric.service.IFileSyncService;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.symmetric.service.ITriggerRouterService;
import org.jumpmind.symmetric.service.impl.TransformService.TransformTableNodeGroupLink;
import org.jumpmind.util.FormatUtils;

/**
 * @see IDataService
 */
public class DataService extends AbstractService implements IDataService {

    private ISymmetricEngine engine;

    private IExtensionService extensionService;

    private DataMapper dataMapper;

    public DataService(ISymmetricEngine engine, IExtensionService extensionService) {
        super(engine.getParameterService(), engine.getSymmetricDialect());
        this.engine = engine;
        this.dataMapper = new DataMapper();
        this.extensionService = extensionService;
        extensionService.addExtensionPoint(new PushHeartbeatListener(engine));
        if (parameterService.is(ParameterConstants.DBDIALECT_ORACLE_SEQUENCE_NOORDER)) {
            extensionService.addExtensionPoint(new OracleNoOrderHeartbeat(engine));
        }
        setSqlMap(new DataServiceSqlMap(symmetricDialect.getPlatform(),
                createSqlReplacementTokens()));
    }

    protected Map<IHeartbeatListener, Long> lastHeartbeatTimestamps = new HashMap<IHeartbeatListener, Long>();

    public boolean insertReloadEvent(TableReloadRequest request, boolean deleteAtClient) {
        boolean successful = false;
        if (request != null) {
            ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
            INodeService nodeService = engine.getNodeService();
            Node targetNode = nodeService.findNode(request.getTargetNodeId());
            if (targetNode != null) {
                TriggerRouter triggerRouter = triggerRouterService.getTriggerRouterForCurrentNode(
                        request.getTriggerId(), request.getRouterId(), false);
                if (triggerRouter != null) {
                    Trigger trigger = triggerRouter.getTrigger();
                    Router router = triggerRouter.getRouter();

                    NodeGroupLink link = router.getNodeGroupLink();
                    Node me = nodeService.findIdentity();
                    if (link.getSourceNodeGroupId().equals(me.getNodeGroupId())) {
                        if (link.getTargetNodeGroupId().equals(targetNode.getNodeGroupId())) {

                            TriggerHistory triggerHistory = lookupTriggerHistory(trigger);

                            ISqlTransaction transaction = null;
                            try {
                                transaction = sqlTemplate.startSqlTransaction();

                                if (parameterService
                                        .is(ParameterConstants.INITIAL_LOAD_DELETE_BEFORE_RELOAD)) {
                                    String overrideDeleteStatement = StringUtils.isNotBlank(request
                                            .getBeforeCustomSql()) ? request.getBeforeCustomSql()
                                            : null;
                                    insertPurgeEvent(transaction, targetNode, triggerRouter,
                                            triggerHistory, false, overrideDeleteStatement, -1,
                                            null);
                                }

                                insertReloadEvent(transaction, targetNode, triggerRouter,
                                        triggerHistory, request.getReloadSelect(), false, -1, null,
                                        Status.NE, -1);

                                if (!targetNode.requires13Compatiblity() && deleteAtClient) {
                                    insertSqlEvent(
                                            transaction,
                                            triggerHistory,
                                            trigger.getChannelId(),
                                            targetNode,
                                            String.format(
                                                    "delete from %s where target_node_id='%s' and source_node_id='%s' and trigger_id='%s' and router_id='%s'",
                                                    TableConstants
                                                            .getTableName(
                                                                    tablePrefix,
                                                                    TableConstants.SYM_TABLE_RELOAD_REQUEST),
                                                    request.getTargetNodeId(), request
                                                            .getSourceNodeId(), request
                                                            .getTriggerId(), request.getRouterId()),
                                            false, -1, null);
                                }

                                deleteTableReloadRequest(transaction, request);

                                transaction.commit();

                            } catch (Error ex) {
                                if (transaction != null) {
                                    transaction.rollback();
                                }
                                throw ex;
                            } catch (RuntimeException ex) {
                                if (transaction != null) {
                                    transaction.rollback();
                                }
                                throw ex;
                            } finally {
                                close(transaction);
                            }

                        } else {
                            log.error(
                                    "Could not reload table {} for node {} because the router {} target node group id {} did not match",
                                    new Object[] { trigger.getSourceTableName(), request.getTargetNodeId(),
                                            request.getRouterId(), link.getTargetNodeGroupId() });
                        }
                    } else {
                        log.error(
                                "Could not reload table {}  for node {} because the router {} source node group id {} did not match",
                                new Object[] { trigger.getSourceTableName(), request.getTargetNodeId(), request.getRouterId(),
                                        link.getSourceNodeGroupId() });
                    }
                } else {
                    log.error(
                            "Could not reload table for node {} because the trigger router ({}, {}) could not be found",
                            new Object[] { request.getTargetNodeId(), request.getTriggerId(),
                                    request.getRouterId() });
                }
            } else {
                log.error("Could not reload table for node {} because the target node could not be found",
                        request.getTargetNodeId());
            }
        }
        return successful;

    }

    protected void deleteTableReloadRequest(ISqlTransaction sqlTransaction,
            TableReloadRequest request) {
        sqlTransaction.prepareAndExecute(
                getSql("deleteTableReloadRequest"),
                new Object[] { request.getSourceNodeId(), request.getTargetNodeId(),
                        request.getTriggerId(), request.getRouterId() }, new int[] { Types.VARCHAR,
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR });
    }
    
    public void insertTableReloadRequest(TableReloadRequest request) {
    	ISqlTransaction transaction = null;
        try {
            transaction = engine.getDatabasePlatform().getSqlTemplate().startSqlTransaction();
            insertTableReloadRequest(transaction, request);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            if (transaction != null) {
                transaction.close();
            }
        }
    }
    
    public void insertTableReloadRequest(ISqlTransaction transaction, TableReloadRequest request) {
        Date time = new Date();
        request.setLastUpdateTime(time);
        if (request.getCreateTime() == null) {
            request.setCreateTime(time);
        }
        request.setCreateTime(new Date((request.getCreateTime().getTime() / 1000) * 1000));

        transaction.prepareAndExecute(
                getSql("insertTableReloadRequest"),
                new Object[] { request.getReloadSelect(), request.getBeforeCustomSql(),
                        request.getCreateTime(), request.getLastUpdateBy(),
                        request.getLastUpdateTime(), request.getSourceNodeId(),
                        request.getTargetNodeId(), request.getTriggerId(),
                        request.getRouterId(), request.isCreateTable() ? 1 : 0, 
                        request.isDeleteFirst() ? 1 : 0, request.getChannelId() });
    }

    public TableReloadRequest getTableReloadRequest(final TableReloadRequestKey key) {
        return sqlTemplate.queryForObject(getSql("selectTableReloadRequest"),
                new ISqlRowMapper<TableReloadRequest>() {
                    public TableReloadRequest mapRow(Row rs) {
                        TableReloadRequest request = new TableReloadRequest(key);
                        request.setReloadSelect(rs.getString("reload_select"));
                        request.setReloadTime(rs.getDateTime("reload_time"));
                        request.setBeforeCustomSql(rs.getString("before_custom_sql"));
                        request.setCreateTime(rs.getDateTime("create_time"));
                        request.setLastUpdateBy(rs.getString("last_update_by"));
                        request.setLastUpdateTime(rs.getDateTime("last_update_time"));
                        request.setLoadId(rs.getInt("load_id"));
                        return request;
                    }
                }, key.getSourceNodeId(), key.getTargetNodeId(), key.getTriggerId(),
                key.getRouterId(), key.getCreateTime());
    }
    
    
    @Override
    public TableReloadRequest getTableReloadRequest(long loadId) {
        List<TableReloadRequest> requests = sqlTemplate.query(getSql("selectTableReloadRequestsByLoadId"), 
                new TableReloadRequestMapper(), loadId);
        
        List<TableReloadRequest> collapsedRequests = collapseTableReloadRequestsByLoadId(requests);
        return collapsedRequests == null || collapsedRequests.size() == 0 ? null : collapsedRequests.get(0);
    }

    @Override
    public TableReloadRequest getTableReloadRequest(long loadId, String triggerId, String routerId) {
        List<TableReloadRequest> requests = sqlTemplate.query(getSql("selectTableReloadRequestsByLoadIdTriggerRouter"), 
                new TableReloadRequestMapper(), loadId, triggerId, routerId);
        if (requests == null || requests.size() == 0) {
            requests = sqlTemplate.query(getSql("selectTableReloadRequestsByLoadIdTriggerRouter"), 
                    new TableReloadRequestMapper(), loadId, ParameterConstants.ALL, ParameterConstants.ALL);
        }
        return requests == null || requests.size() == 0 ? null : requests.get(0);
    }

    public List<TableReloadRequest> getTableReloadRequestToProcess(final String sourceNodeId) {
        return sqlTemplate.query(getSql("selectTableReloadRequestToProcess"),
                new ISqlRowMapper<TableReloadRequest>() {
                    public TableReloadRequest mapRow(Row rs) {
                        TableReloadRequest request = new TableReloadRequest();
                        request.setSourceNodeId(sourceNodeId);
                        request.setTargetNodeId(rs.getString("target_node_id"));
                        request.setCreateTable(rs.getBoolean("create_table"));
                        request.setDeleteFirst(rs.getBoolean("delete_first"));
                        request.setReloadSelect(rs.getString("reload_select"));
                        request.setReloadTime(rs.getDateTime("reload_time"));
                        request.setBeforeCustomSql(rs.getString("before_custom_sql"));
                        request.setChannelId(rs.getString("channel_id"));
                        request.setTriggerId(rs.getString("trigger_id"));
                        request.setRouterId(rs.getString("router_id"));
                        request.setCreateTime(rs.getDateTime("create_time"));
                        request.setLastUpdateBy(rs.getString("last_update_by"));
                        request.setLastUpdateTime(rs.getDateTime("last_update_time"));
                        return request;
                    }
                }, sourceNodeId);
    }
    
    public List<TableReloadRequest> getTableReloadRequests() {
        return sqlTemplateDirty.query(getSql("selectTableReloadRequests"),
                new TableReloadRequestMapper());
    }
    
    public List<TableReloadStatus> getTableReloadStatus() {
        return sqlTemplateDirty.query(getSql("selectTableReloadStatus"),
                new TableReloadStatusMapper());
    }
    
    public List<TableReloadStatus> getActiveTableReloadStatus() {
        return sqlTemplateDirty.query(getSql("selectActiveTableReloadStatus"),
                new TableReloadStatusMapper());
    }
    
    public TableReloadStatus getTableReloadStatusByLoadId(long loadId) {
        return sqlTemplateDirty.queryForObject(getSql("selectTableReloadStatusByLoadId"),
                new TableReloadStatusMapper(), loadId);
    }

    public List<TableReloadStatus> getTableReloadStatusByTarget(String targetNodeId) {
        return sqlTemplateDirty.query(getSql("selectTableReloadStatusByTargetNodeId"),
                new TableReloadStatusMapper(), targetNodeId);
    }
    
    public List<TableReloadRequest> getTableReloadRequestByLoadId() {
        return collapseTableReloadRequestsByLoadId(getTableReloadRequests());
    }

    public List<TableReloadRequest> collapseTableReloadRequestsByLoadId(List<TableReloadRequest> requests) {
        List<TableReloadRequest> collapsedRequests = new ArrayList<TableReloadRequest>();
        
        int previousLoadId = -1;
        
        TableReloadRequest summary = null;
        for (TableReloadRequest request : requests) {
            if (request.getLoadId() != previousLoadId) {
                if (summary != null) {
                    collapsedRequests.add(summary);
                }
                summary = new TableReloadRequest();
                summary.setTargetNodeId(request.getTargetNodeId());
                summary.setSourceNodeId(request.getSourceNodeId());
                summary.setCreateTable(request.isCreateTable());
                summary.setDeleteFirst(request.isDeleteFirst());
                summary.setBeforeCustomSql(request.getBeforeCustomSql());
                summary.setCreateTime(request.getCreateTime());
                summary.setLastUpdateTime(request.getLastUpdateTime());
                summary.setLastUpdateBy(request.getLastUpdateBy());
                summary.setProcessed(request.isProcessed());
                summary.setLoadId(request.getLoadId());
                summary.setProcessed(request.isProcessed());
                summary.setChannelId(request.getChannelId());
              
                if (request.isFullLoadRequest()) {
                    summary.setTriggerId(request.getTriggerId());
                    summary.setRouterId(request.getRouterId());
                }
            }
            previousLoadId = request.getLoadId();
        }
        if (summary != null) {
            collapsedRequests.add(summary);
        }
        return collapsedRequests;
    }
    
    public void updateTableReloadStatusDataLoaded(ISqlTransaction transaction, long loadId, long batchId, int batchCount) {
        int idType = symmetricDialect.getSqlTypeForIds();
        transaction.prepareAndExecute(getSql("updateTableReloadStatusDataLoaded"),
                new Object[] { batchId, batchCount, batchId, batchCount, batchId, batchCount,
                        batchId, batchCount, batchId, batchCount, batchId, batchCount, new Date(),
                        batchId, batchCount, batchId, batchCount, batchId, batchCount, loadId, new Date(), loadId },
                new int[] { idType, Types.NUMERIC, idType, Types.NUMERIC, idType, Types.NUMERIC,
                        idType, Types.NUMERIC, idType, Types.NUMERIC, idType, Types.NUMERIC, Types.TIMESTAMP,
                        idType, Types.NUMERIC, idType, Types.NUMERIC, idType, Types.NUMERIC, idType,
                        Types.TIMESTAMP, idType });
    }
    
    public void updateTableReloadStatusDataCounts(ISqlTransaction transaction, long loadId, long startBatchId, long endBatchId, 
            long dataBatchCount, long rowsCount) {
        
        String sql = getSql("updateTableReloadStatusDataCounts");
        Object[] args = new Object[] {startBatchId, endBatchId, dataBatchCount, rowsCount, new Date(), loadId};
        int idType = symmetricDialect.getSqlTypeForIds();
        int[] types = new int[] { idType, idType, Types.NUMERIC, Types.NUMERIC, Types.TIMESTAMP, idType };
        
        if (transaction == null) {
            try {
                transaction = sqlTemplate.startSqlTransaction();
                transaction.prepareAndExecute(sql, args, types);
                transaction.commit();
            } catch (Error ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } catch (RuntimeException ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } finally {
                close(transaction);
            }
        } else {
            transaction.prepareAndExecute(sql, args, types);
        }
    }
    
    public void updateTableReloadRequestsLoadId(ISqlTransaction transaction, long loadId, TableReloadRequest request) {
        Object[] args = new Object[] { loadId, new Date(), request.getTargetNodeId(), request.getSourceNodeId(), 
                            request.getTriggerId(), request.getRouterId(), request.getCreateTime() };   
        String sql = getSql("updateTableReloadRequestLoadId");
        int[] types = new int[] { symmetricDialect.getSqlTypeForIds(), Types.TIMESTAMP, Types.VARCHAR, Types.VARCHAR,
                Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP};
        
        if (transaction == null) {
            try {
                transaction = sqlTemplate.startSqlTransaction();
                transaction.prepareAndExecute(sql, args, types);
                transaction.commit();
            } catch (Error ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } catch (RuntimeException ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } finally {
                close(transaction);
            }
        } else {
            transaction.prepareAndExecute(sql, args, types);
        }
    }
    
    public void updateTableReloadStatusTableCount(ISqlTransaction transaction, long loadId, int tableCount) {
        Object[] args = new Object[] { tableCount, new Date(), loadId };   
        String sql = getSql("updateTableReloadStatusTableCount");
        int[] types = new int[] { Types.NUMERIC, Types.TIMESTAMP, symmetricDialect.getSqlTypeForIds() };
        
        if (transaction == null) {
            try {
                transaction = sqlTemplate.startSqlTransaction();
                transaction.prepareAndExecute(sql, args, types);
                transaction.commit();
            } catch (Error ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } catch (RuntimeException ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } finally {
                close(transaction);
            }
        } else {
            transaction.prepareAndExecute(sql, args, types);
        }
    }
    
    public void createTableReloadStatus(ISqlTransaction transaction, long loadId, String sourceNodeId, String targetNodeId) {
        Date now = new Date();
        Object[] argsDelete = new Object[] { loadId };   
        String sqlDelete = getSql("deleteTableReloadStatus");
        int[] typesDelete = new int[] { Types.NUMERIC };
        
        
        Object[] args = new Object[] { loadId, targetNodeId, sourceNodeId, now, now };   
        String sql = getSql("insertTableReloadStatus");
        int[] types = new int[] { symmetricDialect.getSqlTypeForIds(), Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP };
        
        if (transaction == null) {
            try {
                transaction = sqlTemplate.startSqlTransaction();
                transaction.prepareAndExecute(sqlDelete, argsDelete, typesDelete);
                transaction.prepareAndExecute(sql, args, types);
                transaction.commit();
            } catch (Error ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } catch (RuntimeException ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } finally {
                close(transaction);
            }
        } else {
            transaction.prepareAndExecute(sqlDelete, argsDelete, typesDelete);
            transaction.prepareAndExecute(sql, args, types);
        }
    }
    
    public void updateTableReloadStatusSetupCount(ISqlTransaction transaction, long loadId, int setupBatchCount) {
        Object[] args = new Object[] { setupBatchCount, new Date(), loadId };   
        String sql = getSql("updateTableReloadStatusSetupCount");
        int[] types = new int[] { Types.NUMERIC, Types.TIMESTAMP, symmetricDialect.getSqlTypeForIds() };
        
        if (transaction == null) {
            try {
                transaction = sqlTemplate.startSqlTransaction();
                transaction.prepareAndExecute(sql, args, types);
                transaction.commit();
            } catch (Error ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } catch (RuntimeException ex) {
                if (transaction != null) {
                    transaction.rollback();
                }
                throw ex;
            } finally {
                close(transaction);
            }
        } else {
            transaction.prepareAndExecute(sql, args, types);
        }
    }
    
    public int updateTableReloadRequestsCancelled(long loadId) {
        ISqlTransaction transaction = null;
        int count = 0;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            Date now = new Date();
            count = transaction.prepareAndExecute(getSql("updateTableReloadStatusCancelled"),
                    new Object[] { now, now, loadId },
                    new int[] { Types.TIMESTAMP, Types.TIMESTAMP, symmetricDialect.getSqlTypeForIds() });
            transaction.prepareAndExecute(getSql("updateProcessedTableReloadRequest"), now, loadId);
            transaction.commit();
            return count;
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }
    
    protected int updateTableReloadRequestsError(long loadId, SqlException e) {
        if (e.getCause() instanceof SQLException) {
            SQLException ex = (SQLException) e.getCause();
            return sqlTemplate.update(getSql("updateTableReloadStatusError"), ex.getErrorCode(), ex.getSQLState(), ex.getMessage(), loadId);
        } else {
            return sqlTemplate.update(getSql("updateTableReloadStatusError"), e.getErrorCode(), null, e.getMessage(), loadId);
        }
    }
    
    protected class TableReloadRequestMapper implements ISqlRowMapper<TableReloadRequest> {

        @Override
        public TableReloadRequest mapRow(Row rs) {
            TableReloadRequest request = new TableReloadRequest();
            
            request.setSourceNodeId(rs.getString("source_node_id"));
            request.setTargetNodeId(rs.getString("target_node_id"));
            request.setCreateTable(rs.getBoolean("create_table"));
            request.setDeleteFirst(rs.getBoolean("delete_first"));
            request.setReloadSelect(rs.getString("reload_select"));
            request.setReloadTime(rs.getDateTime("reload_time"));
            request.setBeforeCustomSql(rs.getString("before_custom_sql"));
            request.setChannelId(rs.getString("channel_id"));
            request.setTriggerId(rs.getString("trigger_id"));
            request.setRouterId(rs.getString("router_id"));
            request.setCreateTime(rs.getDateTime("create_time"));
            request.setLastUpdateBy(rs.getString("last_update_by"));
            request.setLastUpdateTime(rs.getDateTime("last_update_time"));
            request.setLoadId(rs.getInt("load_id"));
            request.setProcessed(rs.getBoolean("processed"));
            
            return request;
        }
    }
    
    protected class TableReloadStatusMapper implements ISqlRowMapper<TableReloadStatus> {

        @Override
        public TableReloadStatus mapRow(Row rs) {
            TableReloadStatus request = new TableReloadStatus();
            
            request.setLoadId(rs.getInt("load_id"));
            request.setSourceNodeId(rs.getString("source_node_id"));
            request.setTargetNodeId(rs.getString("target_node_id"));
            request.setCompleted(rs.getBoolean("completed"));
            request.setCancelled(rs.getBoolean("cancelled"));
            request.setStartDataBatchId(rs.getLong("start_data_batch_id"));
            request.setEndDataBatchId(rs.getLong("end_data_batch_id"));
            request.setSetupBatchCount(rs.getInt("setup_batch_count"));
            request.setDataBatchCount(rs.getInt("data_batch_count"));
            request.setFinalizeBatchCount(rs.getInt("finalize_batch_count"));
            request.setSetupBatchLoaded(rs.getInt("setup_batch_loaded"));
            request.setDataBatchLoaded(rs.getInt("data_batch_loaded"));
            request.setFinalizeBatchLoaded(rs.getInt("finalize_batch_loaded"));
            request.setTableCount(rs.getInt("table_count"));
            request.setRowsLoaded(rs.getLong("rows_loaded"));
            request.setRowsCount(rs.getLong("rows_count"));
            request.setErrorFlag(rs.getBoolean("error_flag"));
            request.setSqlState(rs.getString("sql_state"));
            request.setSqlCode(rs.getInt("sql_code"));
            request.setSqlMessage(rs.getString("sql_message"));
            request.setStartTime(rs.getDateTime("start_time"));
            request.setEndTime(rs.getDateTime("end_time"));
            
            request.setLastUpdateTime(rs.getDateTime("last_update_time"));
            request.setLastUpdatedBy(rs.getString("last_update_by"));
            
            return request;
        }
    }
    
    /**
     * @return If isLoad then return the inserted batch id otherwise return the
     *         data id
     */
    public long insertReloadEvent(ISqlTransaction transaction, Node targetNode,
            TriggerRouter triggerRouter, TriggerHistory triggerHistory,
            String overrideInitialLoadSelect, boolean isLoad, long loadId, String createBy,
            Status status, long estimatedBatchRowCount) {
        String channelId = getReloadChannelIdForTrigger(triggerRouter.getTrigger(), engine
                .getConfigurationService().getChannels(false));
        return insertReloadEvent(transaction, targetNode, triggerRouter, triggerHistory,
                overrideInitialLoadSelect, isLoad, loadId, createBy, status, channelId, estimatedBatchRowCount);
    }

    /**
     * @param estimatedBatchRowCount TODO
     * @return If isLoad then return the inserted batch id otherwise return the
     *         data id
     */
    public long insertReloadEvent(ISqlTransaction transaction, Node targetNode,
            TriggerRouter triggerRouter, TriggerHistory triggerHistory,
            String overrideInitialLoadSelect, boolean isLoad, long loadId, String createBy,
            Status status, String channelId, long estimatedBatchRowCount) {
        return insertReloadEvent(transaction, targetNode, triggerRouter, triggerHistory,
                overrideInitialLoadSelect, isLoad, loadId, createBy,
                status, channelId, estimatedBatchRowCount, isLoad);
    }

    public long insertReloadEventImmediate(ISqlTransaction transaction, Node targetNode,
            TriggerRouter triggerRouter, TriggerHistory triggerHistory,
            String overrideInitialLoadSelect, boolean isLoad, long loadId, String createBy,
            Status status, String channelId, long estimatedBatchRowCount) {
        if (triggerHistory == null) {
            triggerHistory = lookupTriggerHistory(triggerRouter.getTrigger());
        }

        Data data = new Data(triggerHistory.getSourceTableName(), DataEventType.RELOAD,
                overrideInitialLoadSelect != null ? overrideInitialLoadSelect : triggerRouter
                        .getInitialLoadSelect(), null, triggerHistory, channelId,
                null, null);
        data.setNodeList(targetNode.getNodeId());
        return insertDataAndDataEventAndOutgoingBatch(transaction, data,
                targetNode.getNodeId(), triggerRouter.getRouter().getRouterId(), isLoad,
                loadId, createBy, status, channelId, estimatedBatchRowCount);
    }

    protected long insertReloadEvent(ISqlTransaction transaction, Node targetNode,
            TriggerRouter triggerRouter, TriggerHistory triggerHistory,
            String overrideInitialLoadSelect, boolean isLoad, long loadId, String createBy,
            Status status, String channelId, long estimatedBatchRowCount, boolean isImmediate) {
        if (triggerHistory == null) {
            triggerHistory = lookupTriggerHistory(triggerRouter.getTrigger());
        }

        Data data = new Data(triggerHistory.getSourceTableName(), DataEventType.RELOAD,
                overrideInitialLoadSelect != null ? overrideInitialLoadSelect : triggerRouter
                        .getInitialLoadSelect(), null, triggerHistory, channelId,
                null, null);
        data.setNodeList(targetNode.getNodeId());
        if (isImmediate) {
            return insertDataAndDataEventAndOutgoingBatch(transaction, data,
                    targetNode.getNodeId(), triggerRouter.getRouter().getRouterId(), isLoad,
                    loadId, createBy, status, null, estimatedBatchRowCount);
        } else {
            return insertData(transaction, data);
        }
    }

    private String getReloadChannelIdForTrigger(Trigger trigger, Map<String, Channel> channels) {
        String channelId = trigger != null ? trigger.getChannelId() : Constants.CHANNEL_DEFAULT;
        if (parameterService.is(ParameterConstants.INITIAL_LOAD_USE_RELOAD_CHANNEL)) {
            Channel normalChannel = channels.get(channelId);
            Channel reloadChannel = channels.get(trigger != null ? trigger.getReloadChannelId()
                    : Constants.CHANNEL_RELOAD);
            if (normalChannel.isFileSyncFlag()) {
                if (reloadChannel != null && reloadChannel.isFileSyncFlag()) {
                    channelId = reloadChannel.getChannelId();
                }
            } else {
                if (reloadChannel != null && reloadChannel.isReloadFlag()) {
                    channelId = reloadChannel.getChannelId();
                } else {
                    channelId = Constants.CHANNEL_RELOAD;
                }
            }
        }
        return channelId;
    }
    
    @Override
    public void insertReloadEvents(Node targetNode, boolean reverse, ProcessInfo processInfo) {
        ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
        Node sourceNode = engine.getNodeService().findIdentity();
        insertReloadEvents(targetNode, reverse, null, processInfo, triggerRouterService.getActiveTriggerHistories(), 
                triggerRouterService.getAllTriggerRoutersForReloadForCurrentNode(sourceNode.getNodeGroupId(), targetNode.getNodeGroupId()), null);
    }
    
    @Override
    public void insertReloadEvents(Node targetNode, boolean reverse, List<TableReloadRequest> reloadRequests, ProcessInfo processInfo) {
        ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
        Node sourceNode = engine.getNodeService().findIdentity();
        insertReloadEvents(targetNode, reverse, reloadRequests, processInfo, triggerRouterService.getActiveTriggerHistories(), 
                triggerRouterService.getAllTriggerRoutersForReloadForCurrentNode(sourceNode.getNodeGroupId(), targetNode.getNodeGroupId()), null);
    }
    
    @Override
    public void insertReloadEvents(Node targetNode, boolean reverse, ProcessInfo processInfo, List<TriggerHistory> activeHistories, List<TriggerRouter> triggerRouters) {
        insertReloadEvents(targetNode, reverse, null, processInfo, activeHistories, triggerRouters, null);
    }    
    
    
    @Override
    public Map<Integer, ExtractRequest> insertReloadEvents(Node targetNode, boolean reverse, List<TableReloadRequest> reloadRequests, ProcessInfo processInfo, 
            List<TriggerHistory> activeHistories, List<TriggerRouter> triggerRouters, Map<Integer, ExtractRequest> extractRequests) {
        if (engine.getClusterService().lock(ClusterConstants.SYNC_TRIGGERS)) {
            try {
                INodeService nodeService = engine.getNodeService();
                ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
                
                synchronized (triggerRouterService) {

                    boolean isFullLoad = reloadRequests == null 
                            || (reloadRequests.size() == 1 && reloadRequests.get(0).isFullLoadRequest());
                    
                    boolean isChannelLoad = false;
                    String channelId = null;
                    if (reloadRequests != null 
                            && (reloadRequests.size() == 1 && reloadRequests.get(0).isChannelRequest())) {
                        isChannelLoad=true;
                        channelId = reloadRequests.get(0).getChannelId();
                    }

                    if (!reverse) {
                        log.info("Queueing up " + (isFullLoad ? "an initial" : "a") + " load to node " + targetNode.getNodeId() 
                            + (isChannelLoad ? " for channel " + channelId : ""));
                    } else {
                        log.info("Queueing up a reverse " + (isFullLoad ? "initial" : "") + " load to node " + targetNode.getNodeId());
                    }
                    
                    /*
                     * Outgoing data events are pointless because we are
                     * reloading all data
                     */
                    if (isFullLoad) {
                        engine.getOutgoingBatchService().markAllAsSentForNode(targetNode.getNodeId(),
                                false);
                    }                   

                    Node sourceNode = nodeService.findIdentity();

                    boolean transactional = parameterService
                            .is(ParameterConstants.DATA_RELOAD_IS_BATCH_INSERT_TRANSACTIONAL);

                    String nodeIdRecord = reverse ? nodeService.findIdentityNodeId() : targetNode
                            .getNodeId();
                    NodeSecurity nodeSecurity = nodeService.findNodeSecurity(nodeIdRecord);

                    ISqlTransaction transaction = null;
                    long loadId = 0;

                    try {

                        transaction = platform.getSqlTemplate().startSqlTransaction();

                        if (reloadRequests != null && reloadRequests.size() > 0) {
                            loadId = reloadRequests.get(0).getLoadId();
                        }

                        if (loadId == 0) {
                            loadId = engine.getSequenceService().nextVal(transaction, Constants.SEQUENCE_OUTGOING_BATCH_LOAD_ID);
                        }
                        
                        processInfo.setCurrentLoadId(loadId);
                        
                        if (reloadRequests != null && reloadRequests.size() > 0) {
                            createTableReloadStatus(platform.supportsMultiThreadedTransactions() ? null : transaction,
                                    loadId, reloadRequests.get(0).getSourceNodeId(), reloadRequests.get(0).getTargetNodeId()); 
                            for (TableReloadRequest request : reloadRequests) {
                                updateTableReloadRequestsLoadId(
                                    platform.supportsMultiThreadedTransactions() ? null : transaction, 
                                            loadId, request);
                            }
                            // force early commit to get load ID on the reload requests and reload status
                            close(transaction);
                            transaction = platform.getSqlTemplate().startSqlTransaction();
                        }
                        
                        String createBy = reverse ? nodeSecurity.getRevInitialLoadCreateBy()
                                : nodeSecurity.getInitialLoadCreateBy();

                        List<TriggerHistory> triggerHistories = new ArrayList<TriggerHistory>();

                        if (isFullLoad || isChannelLoad) {
                            triggerHistories.addAll(activeHistories);
                            if (reloadRequests != null && reloadRequests.size() == 1) {
                                
                                if (channelId != null) {
                                    List<TriggerHistory> channelTriggerHistories = new ArrayList<TriggerHistory>();
    
                                    for (TriggerHistory history : triggerHistories) {
                                        if (channelId.equals(findChannelFor(history, triggerRouters))) {
                                            channelTriggerHistories.add(history);
                                        }
                                    }
                                    triggerHistories = channelTriggerHistories;
                                }
                            }
                        } else {
                            for (TableReloadRequest reloadRequest : reloadRequests) {
                                triggerHistories.addAll(engine.getTriggerRouterService()
                                        .getActiveTriggerHistories(new Trigger(reloadRequest.getTriggerId(), null)));
                            }
                        }                       
                        
                        Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId = triggerRouterService
                                .fillTriggerRoutersByHistIdAndSortHist(sourceNode.getNodeGroupId(),
                                        targetNode.getNodeGroupId(), triggerHistories, triggerRouters);

                        if (isFullLoad) {
                            callReloadListeners(true, targetNode, transactional, transaction, loadId);

                            if (reloadRequests == null || reloadRequests.size() == 0) {
                                insertCreateSchemaScriptPriorToReload(targetNode, nodeIdRecord, loadId,
                                    createBy, transactional, transaction);
                            }
                        }
                        Map<String, TableReloadRequest> mapReloadRequests = convertReloadListToMap(reloadRequests, triggerRouters, isFullLoad, isChannelLoad);
                        
                        String symNodeSecurityReloadChannel = null;
                        int totalTableCount = 0;
                        try {
                            for (List<TriggerRouter> triggerRouterList : triggerRoutersByHistoryId.values()) {
                                if (triggerRouterList.size() > 0) {
                                    TriggerRouter tr = triggerRouterList.get(0);
                                    symNodeSecurityReloadChannel = tr.getTrigger().getReloadChannelId();
                                }
                                totalTableCount += triggerRouterList.size();
                            }
                        } catch (Exception e) {
                        }
                        processInfo.setTotalDataCount(totalTableCount);
                        
                        if (reloadRequests != null && reloadRequests.size() > 0) {
                            updateTableReloadStatusTableCount(
                                        platform.supportsMultiThreadedTransactions() ? null : transaction, 
                                                loadId, totalTableCount);
                        }
                           
                        int setupBatchCount = 0;
                        int finalizeBatchCount = 0;
                        
                        setupBatchCount += insertSqlEventsPriorToReload(targetNode, nodeIdRecord, loadId, createBy,
                            transactional, transaction, reverse, 
                            triggerHistories, triggerRoutersByHistoryId, 
                            mapReloadRequests, isFullLoad, symNodeSecurityReloadChannel);
                        
                        int createTableBatchCount = insertCreateBatchesForReload(targetNode, loadId, createBy,
                                triggerHistories, triggerRoutersByHistoryId, transactional,
                                transaction, mapReloadRequests);
                        setupBatchCount += createTableBatchCount;
                        if (parameterService.is(ParameterConstants.INITIAL_LOAD_DEFER_CREATE_CONSTRAINTS, false)) {
                            finalizeBatchCount += createTableBatchCount;
                        }

                        setupBatchCount += insertDeleteBatchesForReload(targetNode, loadId, createBy,
                                triggerHistories, triggerRoutersByHistoryId, transactional,
                                transaction, mapReloadRequests);

                        setupBatchCount += insertSQLBatchesForReload(targetNode, loadId, createBy,
                                triggerHistories, triggerRoutersByHistoryId, transactional,
                                transaction, mapReloadRequests);

                        if (reloadRequests != null && reloadRequests.size() > 0) {
                            updateTableReloadStatusSetupCount(platform.supportsMultiThreadedTransactions() ? null : transaction, loadId, setupBatchCount);
                        }
                        
                        extractRequests = insertLoadBatchesForReload(targetNode, loadId, createBy, triggerHistories,
                                triggerRoutersByHistoryId, transactional, transaction, mapReloadRequests, processInfo, null, extractRequests, isFullLoad);
                        
                        finalizeBatchCount += insertSqlEventsAfterReload(targetNode, nodeIdRecord, loadId, createBy,
                                transactional, transaction, reverse, 
                                triggerHistories, triggerRoutersByHistoryId, 
                                mapReloadRequests, isFullLoad, symNodeSecurityReloadChannel);

                        finalizeBatchCount += insertFileSyncBatchForReload(targetNode, loadId, createBy, transactional,
                                transaction, mapReloadRequests, isFullLoad, processInfo);
                                
                        
                        if (isFullLoad) {

                            callReloadListeners(false, targetNode, transactional, transaction, loadId);
                            if (!reverse) {
                                nodeService.setInitialLoadEnabled(transaction, nodeIdRecord, false,
                                    false, loadId, createBy);
                            } else {
                                nodeService.setReverseInitialLoadEnabled(transaction, nodeIdRecord,
                                        false, false, loadId, createBy);
                            }
                        }
                                                
                        if (isFullLoad && !Constants.DEPLOYMENT_TYPE_REST.equals(targetNode.getDeploymentType())) {
                        	insertNodeSecurityUpdate(transaction, nodeIdRecord,
                                    targetNode.getNodeId(), true, loadId, createBy, symNodeSecurityReloadChannel);
                        	    finalizeBatchCount++;
                        }

                        engine.getStatisticManager().incrementNodesLoaded(1);

                        if (reloadRequests != null && reloadRequests.size() > 0) {
                            
                            transaction.prepareAndExecute(getSql("updateTableReloadStatusFinalizeCount"), finalizeBatchCount, new Date(), loadId); 
                            
                            int rowsAffected = transaction.prepareAndExecute(getSql("updateProcessedTableReloadRequest"), new Date(), loadId); 
                            if (rowsAffected == 0) {
                                throw new SymmetricException(String.format("Failed to update a table_reload_request as processed for loadId '%s' ", 
                                        loadId));
                            }
                            log.info("Table reload request(s) for load id " + loadId + " have been processed.");
                        }
                        
                        checkInterrupted();
                        transaction.commit();
                    } catch (Error ex) {
                        if (transaction != null) {
                            transaction.rollback();
                        }
                        throw ex;
                    } catch (Exception ex) {
                        if (transaction != null) {
                            transaction.rollback();
                        }
                        if (ex instanceof InvalidSqlException) {
                            log.warn("Cancelling load " + loadId);
                            if (ex.getCause() instanceof SqlException) {
                                log.error(ex.getCause().getMessage());
                                updateTableReloadRequestsError(loadId, (SqlException) ex.getCause());
                            }
                            updateTableReloadRequestsCancelled(loadId);
                        } else if (ex instanceof RuntimeException) {
                            throw (RuntimeException) ex;
                        } else if (ex instanceof InterruptedException) {
                            log.info("Insert reload events was interrupted");
                        }
                    } finally {
                        close(transaction);
                    }

                    if (!reverse && isFullLoad) {
                        /*
                         * Remove all incoming events for the node that we are
                         * starting a reload for
                         */
                        engine.getPurgeService().purgeAllIncomingEventsForNode(
                                targetNode.getNodeId());
                    }
                    engine.getDataExtractorService().releaseMissedExtractRequests();
                }
            } finally {
                engine.getClusterService().unlock(ClusterConstants.SYNC_TRIGGERS);
            }
        } else {
            log.info("Not attempting to insert reload events because sync trigger is currently running");
        }
        return extractRequests;
    }

    private long getBatchCountFor(Map<Integer, ExtractRequest> extractRequests) {
        long batchCount = 0;
        for (ExtractRequest request : extractRequests.values()) {
            batchCount += ((request.getEndBatchId() - request.getStartBatchId()) + 1);
        }
        return batchCount;
    }

    private String findChannelFor(TriggerHistory history, List<TriggerRouter> triggerRouters) {
        for (TriggerRouter triggerRouter : triggerRouters) {
            if (triggerRouter.getTrigger().getTriggerId().equals(history.getTriggerId())) {
                return triggerRouter.getTrigger().getChannelId();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, TableReloadRequest> convertReloadListToMap(List<TableReloadRequest> reloadRequests, List<TriggerRouter> triggerRouters, boolean isFullLoad, boolean isChannelLoad) {
        if (reloadRequests == null) {
            return null;
        }
        Map<String, TableReloadRequest> reloadMap = new CaseInsensitiveMap();
        for (TableReloadRequest reloadRequest : reloadRequests) {
            if (!isFullLoad && !isChannelLoad) {
                validate(reloadRequest, triggerRouters);
            }
            reloadMap.put(reloadRequest.getIdentifier(), reloadRequest);
        }
        return reloadMap;
    }
    
    protected void validate(TableReloadRequest reloadRequest, List<TriggerRouter> triggerRouters) {
        boolean validMatch = false;
        for (TriggerRouter triggerRouter : triggerRouters) {
            if (ObjectUtils.equals(triggerRouter.getTriggerId(), reloadRequest.getTriggerId())
                    && ObjectUtils.equals(triggerRouter.getRouterId(), reloadRequest.getRouterId())) {
                validMatch = true;
                break;
            }
        }
        if (!validMatch) {
            throw new InvalidSqlException("Invalid SQL", new SqlException("Table reload request submitted which does not have a valid trigger/router "
                    + "combination in sym_trigger_router. Request trigger id: '" + reloadRequest.getTriggerId() + "' router id: '" 
                    + reloadRequest.getRouterId() + "' create time: " + reloadRequest.getCreateTime()));
        }
    }

    private void callReloadListeners(boolean before, Node targetNode, boolean transactional,
            ISqlTransaction transaction, long loadId) {
        for (IReloadListener listener : extensionService.getExtensionPointList(IReloadListener.class)) {
            if (before) {
                listener.beforeReload(transaction, targetNode, loadId);
            } else {
                listener.afterReload(transaction, targetNode, loadId);
            }

            if (!transactional) {
                transaction.commit();
            }
        }
    }
    
    private void insertCreateSchemaScriptPriorToReload(Node targetNode, String nodeIdRecord, long loadId,
            String createBy, boolean transactional, ISqlTransaction transaction) {
        String dumpCommand = parameterService.getString(ParameterConstants.INITIAL_LOAD_SCHEMA_DUMP_COMMAND);
        String loadCommand = parameterService.getString(ParameterConstants.INITIAL_LOAD_SCHEMA_LOAD_COMMAND);
        if (isNotBlank(dumpCommand) && isNotBlank(loadCommand)) {
            try {            
                log.info("Dumping schema using the following dump command: " + dumpCommand);
                
                ProcessBuilder pb = new ProcessBuilder(FormatUtils.splitOnSpacePreserveQuotedStrings(dumpCommand));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                java.io.InputStream is = process.getInputStream();
                java.io.StringWriter ow = new java.io.StringWriter();
                IOUtils.copy(is, ow, Charset.defaultCharset());
                String output = ow.toString();
                output = StringEscapeUtils.escapeJavaScript(output);
                
                String script = IOUtils.toString(getClass().getResourceAsStream("/load-schema-at-target.bsh"), Charset.defaultCharset());
                script = script.replace("${data}", output);
                script = script.replace("${commands}", formatCommandForScript(loadCommand));
                
                if (process.waitFor() != 0) {
                    throw new IoException(output.toString());
                }
                log.info("Inserting script to load dump at client");
                engine.getDataService().insertScriptEvent(transaction, Constants.CHANNEL_RELOAD, targetNode,
                        script, true, loadId, "reload listener");
            } catch (Exception e) {
                throw new IoException(e);
            }
        }
    }
    
    private String formatCommandForScript(String command) {
        String[] tokens = FormatUtils.splitOnSpacePreserveQuotedStrings(command);
        StringBuilder builder = new StringBuilder();
        for (String string : tokens) {
            builder.append("\"" + StringEscapeUtils.escapeJava(string) + "\",");
        }
        return builder.substring(0, builder.length()-1);
    }

    private int insertSqlEventsPriorToReload(Node targetNode, String nodeIdRecord, long loadId,
            String createBy, boolean transactional, ISqlTransaction transaction, boolean reverse,
            List<TriggerHistory> triggerHistories,
            Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId,
            Map<String, TableReloadRequest> reloadRequests, boolean isFullLoad, String channelId) {

        int batchCount = 0;
        if (isFullLoad && !Constants.DEPLOYMENT_TYPE_REST.equals(targetNode.getDeploymentType())) {
            /*
             * Insert node security so the client doing the initial load knows
             * that an initial load is currently happening
             */
            insertNodeSecurityUpdate(transaction, nodeIdRecord, targetNode.getNodeId(), true,
                    loadId, createBy, channelId);
            batchCount++;
            
            /*
             * Mark incoming batches as OK at the target node because we marked
             * outgoing batches as OK at the source
             */
            insertSqlEvent(
                    transaction,
                    targetNode,
                    String.format(
                            "update %s_incoming_batch set status='OK', error_flag=0 where node_id='%s' and status != 'OK'",
                            tablePrefix, engine.getNodeService().findIdentityNodeId()), true,
                    loadId, createBy);
            batchCount++;
        }

        if (isFullLoad) {
            String beforeSql = parameterService.getString(reverse ? ParameterConstants.INITIAL_LOAD_REVERSE_BEFORE_SQL
                    : ParameterConstants.INITIAL_LOAD_BEFORE_SQL);
            if (isNotBlank(beforeSql)) {
                insertSqlEvent(
                        transaction,
                        targetNode,
                        beforeSql, true,
                        loadId, createBy);   
                batchCount++;
            }
        }
        return batchCount;
    }

    private int insertSqlEventsAfterReload(Node targetNode, String nodeIdRecord, long loadId,
            String createBy, boolean transactional, ISqlTransaction transaction, boolean reverse,
            List<TriggerHistory> triggerHistories,
            Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId,
            Map<String, TableReloadRequest> reloadRequests, boolean isFullLoad, String channelId) {

        int totalBatchCount = 0;
        if (isFullLoad) {
            String afterSql = parameterService.getString(reverse ? ParameterConstants.INITIAL_LOAD_REVERSE_AFTER_SQL
                            : ParameterConstants.INITIAL_LOAD_AFTER_SQL);
            if (isNotBlank(afterSql)) {
                insertSqlEvent(transaction, targetNode, afterSql, true, loadId, createBy);
                totalBatchCount++;
            }
        }
        return totalBatchCount;
    }
    
    private int insertCreateBatchesForReload(Node targetNode, long loadId, String createBy,
            List<TriggerHistory> triggerHistories,
            Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId, boolean transactional,
            ISqlTransaction transaction, Map<String, TableReloadRequest> reloadRequests) throws InterruptedException {
        
        int createEventsSent = 0;
        
        if (reloadRequests != null && reloadRequests.size() > 0) {
            for (TriggerHistory triggerHistory : triggerHistories) {
                List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                        .getTriggerHistoryId());
                
                TableReloadRequest currentRequest = reloadRequests.get(ParameterConstants.ALL + ParameterConstants.ALL);
                boolean fullLoad = currentRequest == null ? false : true;
                
                for (TriggerRouter triggerRouter : triggerRouters) {
                    if (!fullLoad) {
                        currentRequest = reloadRequests.get(triggerRouter.getTriggerId() + triggerRouter.getRouterId());
                    }
                    
                    //Check the create flag on the specific table reload request
                    if (currentRequest != null && currentRequest.isCreateTable()
                            && engine.getGroupletService().isTargetEnabled(triggerRouter,
                                    targetNode)) {
                        insertCreateEvent(transaction, targetNode, triggerHistory, triggerRouter.getRouter().getRouterId(), true,
                                loadId, createBy);
                        createEventsSent++;
                        if (!transactional) {
                            transaction.commit();
                        }
                    }
                    checkInterrupted();
                }
            }
            if (createEventsSent > 0) {
                log.info("Before sending load {} to target node {} create table events were sent for {} tables", new Object[] {
                        loadId, targetNode, createEventsSent });
            }
        }
        else {
            if (parameterService.is(ParameterConstants.INITIAL_LOAD_CREATE_SCHEMA_BEFORE_RELOAD)) {
                for (TriggerHistory triggerHistory : triggerHistories) {
                    List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                            .getTriggerHistoryId());
                    for (TriggerRouter triggerRouter : triggerRouters) {
                        if (triggerRouter.getInitialLoadOrder() >= 0
                                && engine.getGroupletService().isTargetEnabled(triggerRouter,
                                        targetNode)) {
                            insertCreateEvent(transaction, targetNode, triggerHistory, triggerRouter.getRouter().getRouterId(), true,
                                    loadId, createBy);
                            createEventsSent++;
                            if (!transactional) {
                                transaction.commit();
                            }
                            checkInterrupted();
                        }
                    }
                }
            }
        }
        return createEventsSent;
    }

    private int insertDeleteBatchesForReload(Node targetNode, long loadId, String createBy,
            List<TriggerHistory> triggerHistories,
            Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId, boolean transactional,
            ISqlTransaction transaction, Map<String, TableReloadRequest> reloadRequests) throws InterruptedException {
        
        int deleteEventsSent = 0;
        
        if (reloadRequests != null && reloadRequests.size() > 0) {
            
            for (ListIterator<TriggerHistory> triggerHistoryIterator = triggerHistories
                    .listIterator(triggerHistories.size()); triggerHistoryIterator.hasPrevious();) {
                TriggerHistory triggerHistory = triggerHistoryIterator.previous();
                List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                        .getTriggerHistoryId());
                TableReloadRequest currentRequest = reloadRequests.get(ParameterConstants.ALL + ParameterConstants.ALL);
                boolean fullLoad = currentRequest == null ? false : true;

                for (ListIterator<TriggerRouter> iterator = triggerRouters
                        .listIterator(triggerRouters.size()); iterator.hasPrevious();) {
                    TriggerRouter triggerRouter = iterator.previous();                
                    if (!fullLoad) {
                        currentRequest = reloadRequests.get(triggerRouter.getTriggerId() + triggerRouter.getRouterId());
                    }
                    
                    //Check the delete flag on the specific table reload request
                    if (currentRequest != null && currentRequest.isDeleteFirst()
                            && engine.getGroupletService().isTargetEnabled(triggerRouter,
                                    targetNode)) {
                        insertPurgeEvent(transaction, targetNode, triggerRouter, triggerHistory,
                                true, currentRequest.getBeforeCustomSql(), loadId, createBy);
                        deleteEventsSent++;
                        if (!transactional) {
                            transaction.commit();
                        }
                    }
                    checkInterrupted();
                }
            }
            if (deleteEventsSent > 0) {
                log.info("Before sending load {} to target node {} delete data events were sent for {} tables", new Object[] {
                        loadId, targetNode, deleteEventsSent });
            }
        }
        else {
            if (parameterService.is(ParameterConstants.INITIAL_LOAD_DELETE_BEFORE_RELOAD)) {
                for (ListIterator<TriggerHistory> triggerHistoryIterator = triggerHistories
                        .listIterator(triggerHistories.size()); triggerHistoryIterator.hasPrevious();) {
                    TriggerHistory triggerHistory = triggerHistoryIterator.previous();
                    List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                            .getTriggerHistoryId());
                    for (ListIterator<TriggerRouter> iterator = triggerRouters
                            .listIterator(triggerRouters.size()); iterator.hasPrevious();) {
                        TriggerRouter triggerRouter = iterator.previous();
                        if (triggerRouter.getInitialLoadOrder() >= 0
                                && engine.getGroupletService().isTargetEnabled(triggerRouter,
                                        targetNode)
                                && (!StringUtils.isBlank(parameterService
                                        .getString(ParameterConstants.INITIAL_LOAD_DELETE_FIRST_SQL)) || !StringUtils
                                        .isEmpty(triggerRouter.getInitialLoadDeleteStmt()))) {
                            insertPurgeEvent(transaction, targetNode, triggerRouter, triggerHistory,
                                    true, null, loadId, createBy);
                            deleteEventsSent++;
                            
                            if (!transactional) {
                                transaction.commit();
                            }
                        }
                        checkInterrupted();
                    }
                }
            }
        }
        return deleteEventsSent;
    }

    private int insertSQLBatchesForReload(Node targetNode, long loadId, String createBy,
            List<TriggerHistory> triggerHistories,
            Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId, boolean transactional,
            ISqlTransaction transaction, Map<String, TableReloadRequest> reloadRequests) throws InterruptedException {
        
        int sqlEventsSent = 0;
        
        if (reloadRequests != null && reloadRequests.size() > 0) {
            
            List<TriggerHistory> copyTriggerHistories = new ArrayList<TriggerHistory>(triggerHistories);
            Collections.reverse(copyTriggerHistories);
            
            for (TriggerHistory triggerHistory : copyTriggerHistories) {
                List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                        .getTriggerHistoryId());
                
                TableReloadRequest currentRequest = reloadRequests.get(ParameterConstants.ALL + ParameterConstants.ALL);
                boolean fullLoad = currentRequest == null ? false : true;
                
                for (TriggerRouter triggerRouter : triggerRouters) {
                    if (!fullLoad) {
                        currentRequest = reloadRequests.get(triggerRouter.getTriggerId() + triggerRouter.getRouterId());
                    }
                    
                    //Check the before custom sql is present on the specific table reload request
                    if (currentRequest != null 
                            && currentRequest.getBeforeCustomSql() != null 
                            && currentRequest.getBeforeCustomSql().length() > 0
                            && engine.getGroupletService().isTargetEnabled(triggerRouter,
                                    targetNode)) {
                        
                        List<String> sqlStatements = resolveTargetTables(currentRequest.getBeforeCustomSql(), 
                                triggerRouter, triggerHistory, targetNode);
                        
                        for (String sql : sqlStatements) {
                            insertSqlEvent(transaction, triggerHistory, triggerRouter.getTrigger().getChannelId(),
                                    targetNode, sql,
                                    true, loadId, createBy);
                            sqlEventsSent++;
                        }
                        if (!transactional) {
                            transaction.commit();
                        }
                    }
                    checkInterrupted();
                }
            }
            if (sqlEventsSent > 0) {
                log.info("Before sending load {} to target node {} SQL data events were sent for {} tables", new Object[] {
                        loadId, targetNode, sqlEventsSent });
            }
        }
        return sqlEventsSent;
    }
    
    private Map<Integer, ExtractRequest> insertLoadBatchesForReload(Node targetNode, long loadId, String createBy,
            List<TriggerHistory> triggerHistories,
            Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId, boolean transactional,
            ISqlTransaction transaction, Map<String, TableReloadRequest> reloadRequests, ProcessInfo processInfo,
            String selectSqlOverride, Map<Integer, ExtractRequest> extractRequests, boolean isFullLoad) throws InterruptedException {

        Map<String, Channel> channels = engine.getConfigurationService().getChannels(false);
        Map<Integer, ExtractRequest> requests = new HashMap<Integer, ExtractRequest>();
        if (extractRequests != null) {
            requests.putAll(extractRequests);
        }

        long firstBatchId = 0;
        
        for (TriggerHistory triggerHistory : triggerHistories) {
            List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                    .getTriggerHistoryId());
            
            processInfo.incrementCurrentDataCount();
            checkInterrupted();
            
            for (TriggerRouter triggerRouter : triggerRouters) {
                if (triggerRouter.getInitialLoadOrder() >= 0
                        && engine.getGroupletService().isTargetEnabled(triggerRouter, targetNode)) {
                    
                    String selectSql = selectSqlOverride;
                    if (StringUtils.isEmpty(selectSql)) {
                        
                        if (reloadRequests != null) {
                            TableReloadRequest reloadRequest = reloadRequests.get(triggerRouter.getTriggerId() + triggerRouter.getRouterId());
                            selectSql = reloadRequest != null ? reloadRequest.getReloadSelect() : null;
                        }
                        if (StringUtils.isBlank(selectSql)) {
                            selectSql = StringUtils.isBlank(triggerRouter.getInitialLoadSelect()) 
                                    ? Constants.ALWAYS_TRUE_CONDITION
                                            : triggerRouter.getInitialLoadSelect();
                        }
                    }
                    
                    if (parameterService.is(ParameterConstants.INITIAL_LOAD_USE_EXTRACT_JOB)) {
                        Trigger trigger = triggerRouter.getTrigger();
                        String reloadChannel = getReloadChannelIdForTrigger(trigger, channels);
                        Channel channel = channels.get(reloadChannel);
                                                   
                        Table table = platform.getTableFromCache(
                                triggerHistory.getSourceCatalogName(), triggerHistory.getSourceSchemaName(),
                                triggerHistory.getSourceTableName(), false);  
                        
                        if (table != null) {
                            processInfo.setCurrentTableName(table.getName());

                            long rowCount = -1;
                            long parentRequestId = 0;
                            ExtractRequest parentRequest = requests.get(triggerHistory.getTriggerHistoryId());

                            if (parentRequest != null) {
                                Router router = engine.getTriggerRouterService().getRouterById(triggerRouter.getRouterId(), false);
                                if (router != null && router.getRouterType().equals("default")) {
                                    parentRequestId = parentRequest.getRequestId();
                                    rowCount = parentRequest.getRows();
                                }                                
                            }

                            if (rowCount == -1) {
                                rowCount = getDataCountForReload(table, targetNode, selectSql);
                            }

                            long transformMultiplier = getTransformMultiplier(table, triggerRouter);
                            
                            // calculate the number of batches needed for table.
                            long numberOfBatches = 1;
                            long lastBatchSize = channel.getMaxBatchSize();

                            if (rowCount > 0) {
                                numberOfBatches = (rowCount * transformMultiplier / channel.getMaxBatchSize()) + 1;
                                lastBatchSize = rowCount % numberOfBatches;
                            }

                            long startBatchId = -1;
                            long endBatchId = -1;
                            for (int i = 0; i < numberOfBatches; i++) {
                                long batchSize = i == numberOfBatches - 1 ? lastBatchSize : channel.getMaxBatchSize();
                                // needs to grab the start and end batch id
                                endBatchId = insertReloadEvent(transaction, targetNode, triggerRouter, triggerHistory, selectSql, true,
                                        loadId, createBy, Status.RQ, null, batchSize);
                                if (startBatchId == -1) {
                                    startBatchId = endBatchId;
                                }
                            }

                            firstBatchId = firstBatchId > 0 ? firstBatchId : startBatchId;
                            
                            updateTableReloadStatusDataCounts(platform.supportsMultiThreadedTransactions() ? null : transaction, 
                                        loadId, firstBatchId, endBatchId, numberOfBatches, rowCount);
                            
                            ExtractRequest request = engine.getDataExtractorService().requestExtractRequest(transaction, targetNode.getNodeId(), channel.getQueue(),
                                    triggerRouter, startBatchId, endBatchId, loadId, table.getName(), rowCount, parentRequestId);
                            if (parentRequestId == 0) {
                                requests.put(triggerHistory.getTriggerHistoryId(), request);
                            }
                        } else {
                            log.warn("The table defined by trigger_hist row %d no longer exists.  A load will not be queue'd up for the table", triggerHistory.getTriggerHistoryId());
                            
                        }
                    } else {
                        insertReloadEvent(transaction, targetNode, triggerRouter, triggerHistory,
                                selectSql, true, loadId, createBy, Status.NE, null, -1);
                    }

                    if (!transactional) {
                        transaction.commit();
                    }
                }
                
            }
        }

        return requests;
    }

    protected long getDataCountForReload(Table table, Node targetNode, String selectSql) throws SqlException {
        long rowCount = -1;
        if (parameterService.is(ParameterConstants.INITIAL_LOAD_USE_ESTIMATED_COUNTS) &&
                (selectSql == null || StringUtils.isBlank(selectSql) || selectSql.replace(" ", "").equals("1=1"))) {
            rowCount = platform.getEstimatedRowCount(table);
        } 
        
        if (rowCount < 0) {
            DatabaseInfo dbInfo = platform.getDatabaseInfo();
            String quote = dbInfo.getDelimiterToken();
            String catalogSeparator = dbInfo.getCatalogSeparator();
            String schemaSeparator = dbInfo.getSchemaSeparator();
                                              
            String sql = String.format("select count(*) from %s t where %s", table
                    .getQualifiedTableName(quote, catalogSeparator, schemaSeparator), selectSql);
            sql = FormatUtils.replace("groupId", targetNode.getNodeGroupId(), sql);
            sql = FormatUtils.replace("externalId", targetNode.getExternalId(), sql);
            sql = FormatUtils.replace("nodeId", targetNode.getNodeId(), sql);
            for (IReloadVariableFilter filter : extensionService.getExtensionPointList(IReloadVariableFilter.class)) {
                sql = filter.filterPurgeSql(sql, targetNode, table);
            }
            
            try {            
                rowCount = sqlTemplateDirty.queryForLong(sql);
            } catch (SqlException ex) {
                log.error("Failed to execute row count SQL while starting reload.  " + ex.getMessage() + ", SQL: \"" + sql + "\"");
                throw new InvalidSqlException(ex);
            }
        }
        return rowCount;
    }

    protected int getTransformMultiplier(Table table, TriggerRouter triggerRouter) {
        int transformMultiplier = 0;
        List<TransformTableNodeGroupLink> transforms = engine.getTransformService()
                .findTransformsFor(triggerRouter.getRouter().getNodeGroupLink(), TransformPoint.EXTRACT);
        if (transforms != null) {
            for (TransformService.TransformTableNodeGroupLink transform : transforms) {
                if (transform.getSourceTableName().equals(table.getName())) {
                    transformMultiplier++;
                }
            }
        }
        transformMultiplier = Math.max(1, transformMultiplier);
        return transformMultiplier;
    }

    private int insertFileSyncBatchForReload(Node targetNode, long loadId, String createBy,
            boolean transactional, ISqlTransaction transaction, Map<String, TableReloadRequest> reloadRequests, boolean isFullLoad, ProcessInfo processInfo) throws InterruptedException {
        
        int totalBatchCount = 0;
        if (parameterService.is(ParameterConstants.FILE_SYNC_ENABLE)
                && !Constants.DEPLOYMENT_TYPE_REST.equals(targetNode.getDeploymentType())) {
            ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
            IFileSyncService fileSyncService = engine.getFileSyncService();
            if (fileSyncService.getFileTriggerRoutersForCurrentNode(false).size() > 0) {
                TriggerHistory fileSyncSnapshotHistory = triggerRouterService.findTriggerHistory(
                        null, null,
                        TableConstants.getTableName(tablePrefix, TableConstants.SYM_FILE_SNAPSHOT));
                String routerid = triggerRouterService.buildSymmetricTableRouterId(
                        fileSyncSnapshotHistory.getTriggerId(), parameterService.getNodeGroupId(),
                        targetNode.getNodeGroupId());
                TriggerRouter fileSyncSnapshotTriggerRouter = triggerRouterService
                        .getTriggerRouterForCurrentNode(fileSyncSnapshotHistory.getTriggerId(),
                                routerid, true);

                if(!isFullLoad && reloadRequests != null 
                        && reloadRequests.get(fileSyncSnapshotTriggerRouter.getTriggerId() + fileSyncSnapshotTriggerRouter.getRouterId()) == null
                        && !isReloadRequestForFileChannel(reloadRequests)){
                    return totalBatchCount;
                }
                
                List<TriggerHistory> triggerHistories = Arrays.asList(fileSyncSnapshotHistory);
                List<TriggerRouter> triggerRouters = Arrays.asList(fileSyncSnapshotTriggerRouter);
                Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId = new HashMap<Integer, List<TriggerRouter>>();
                triggerRoutersByHistoryId.put(fileSyncSnapshotHistory.getTriggerHistoryId(), triggerRouters);
                
                if (parameterService.is(ParameterConstants.INITIAL_LOAD_USE_EXTRACT_JOB)) {      
                    
                    final String FILTER_ENABLED_FILE_SYNC_TRIGGER_ROUTERS = 
                            String.format("1=(select initial_load_enabled from %s tr where t.trigger_id = tr.trigger_id AND t.router_id = tr.router_id)",
                                    TableConstants.getTableName(tablePrefix, TableConstants.SYM_FILE_TRIGGER_ROUTER));
                    totalBatchCount += getBatchCountFor(insertLoadBatchesForReload(targetNode, loadId, createBy, triggerHistories, 
                            triggerRoutersByHistoryId, transactional, transaction, null, processInfo, FILTER_ENABLED_FILE_SYNC_TRIGGER_ROUTERS, null, isFullLoad));
                } else {                    
                    List<Channel> channels = engine.getConfigurationService().getFileSyncChannels();
                    for (Channel channel : channels) {
                        if (channel.isReloadFlag()) {
                            insertReloadEvent(transaction, targetNode, fileSyncSnapshotTriggerRouter,
                                    fileSyncSnapshotHistory,
                                    "reload_channel_id='" + channel.getChannelId() + "'", true, loadId,
                                    createBy, Status.NE, channel.getChannelId(), -1);
                            totalBatchCount++;
                            if (!transactional) {
                                transaction.commit();
                            }
                        }
                    }
                }
            }
        }
        return totalBatchCount;
    }

    private boolean isReloadRequestForFileChannel(Map<String, TableReloadRequest> reloadRequests) {
        for (TableReloadRequest reloadRequest : reloadRequests.values()) {
            if (reloadRequest.getChannelId() != null && engine.getConfigurationService().getChannel(reloadRequest.getChannelId()).isFileSyncFlag()) {
                return true;
            }
        }
        return false;
    }
    private TriggerHistory lookupTriggerHistory(Trigger trigger) {
        TriggerHistory history = engine.getTriggerRouterService()
                .getNewestTriggerHistoryForTrigger(trigger.getTriggerId(),
                        trigger.getSourceCatalogName(), trigger.getSourceSchemaName(),
                        trigger.getSourceTableName());

        if (history == null) {
            engine.getTriggerRouterService().syncTriggers();
            history = engine.getTriggerRouterService().getNewestTriggerHistoryForTrigger(
                    trigger.getTriggerId(), null, null, null);
        }

        if (history == null) {
            throw new RuntimeException("Cannot find history for trigger " + trigger.getTriggerId()
                    + ", " + trigger.getSourceTableName());
        }
        return history;
    }

    protected void insertPurgeEvent(ISqlTransaction transaction, Node targetNode,
            TriggerRouter triggerRouter, TriggerHistory triggerHistory, boolean isLoad,
            String overrideDeleteStatement, long loadId, String createBy) {

        Node sourceNode = engine.getNodeService().findIdentity();
        
        List<TransformTableNodeGroupLink> transforms = 
                this.engine.getTransformService().findTransformsFor(
                        sourceNode.getNodeGroupId(), targetNode.getNodeGroupId(), triggerRouter.getTargetTable(triggerHistory));
        
        if (StringUtils.isNotBlank(overrideDeleteStatement)) {
            List<String> sqlStatements = resolveTargetTables(overrideDeleteStatement, triggerRouter, triggerHistory, targetNode);
            for (String sql : sqlStatements) {
                createPurgeEvent(transaction, sql, targetNode, sourceNode,
                        triggerRouter, triggerHistory, isLoad, loadId, createBy);
            }
            
        } else if (transforms != null && transforms.size() > 0) {
            List<String> sqlStatements = symmetricDialect.createPurgeSqlForMultipleTables(targetNode, triggerRouter, 
                    triggerHistory, transforms, null);
            for (String sql : sqlStatements) {
                createPurgeEvent(transaction, 
                        sql,
                        targetNode, sourceNode,
                        triggerRouter, triggerHistory, isLoad, loadId, createBy);
            }
        } else {
            createPurgeEvent(transaction, 
                symmetricDialect.createPurgeSqlFor(targetNode, triggerRouter, triggerHistory, transforms),
                targetNode, sourceNode,
                triggerRouter, triggerHistory, isLoad, loadId, createBy);
        }
        
    }
    
    public List<String> resolveTargetTables(String sql, TriggerRouter triggerRouter, TriggerHistory triggerHistory, Node targetNode) {
        if (sql == null) { return null; }
        
        List<String> sqlStatements = new ArrayList<String>();                  
        if (sql != null && sql.contains("%s")) {
            Set<String> tableNames = new HashSet<String>();
            Node sourceNode = engine.getNodeService().findIdentity();
            String sourceTableName = triggerRouter.qualifiedTargetTableName(triggerHistory);
            
            List<TransformTableNodeGroupLink> transforms = 
                    this.engine.getTransformService().findTransformsFor(
                            sourceNode.getNodeGroupId(), targetNode.getNodeGroupId(), triggerRouter.getTargetTable(triggerHistory));
            
            if (transforms != null) {
                for (TransformTableNodeGroupLink transform : transforms) {
                    tableNames.add(transform.getFullyQualifiedTargetTableName());
                }
            } else {
                tableNames.add(sourceTableName);
            }
            
            for (String tableName : tableNames) {
                sqlStatements.add(String.format(sql, tableName));
            }
        }
        else {
            sqlStatements.add(sql);
        }
        return sqlStatements;
        
    }

    protected void createPurgeEvent(ISqlTransaction transaction, String sql, Node targetNode, Node sourceNode,
            TriggerRouter triggerRouter, TriggerHistory triggerHistory, boolean isLoad, 
            long loadId, String createBy) {
        
        sql = FormatUtils.replace("groupId", targetNode.getNodeGroupId(), sql);
        sql = FormatUtils.replace("externalId", targetNode.getExternalId(), sql);
        sql = FormatUtils.replace("nodeId", targetNode.getNodeId(), sql);
        sql = FormatUtils.replace("targetGroupId", targetNode.getNodeGroupId(), sql);
        sql = FormatUtils.replace("targetExternalId", targetNode.getExternalId(), sql);
        sql = FormatUtils.replace("targetNodeId", targetNode.getNodeId(), sql);
        sql = FormatUtils.replace("sourceGroupId", sourceNode.getNodeGroupId(), sql);
        sql = FormatUtils.replace("sourceExternalId", sourceNode.getExternalId(), sql);
        sql = FormatUtils.replace("sourceNodeId", sourceNode.getNodeId(), sql);
        Table table = new Table(triggerHistory.getSourceCatalogName(), triggerHistory.getSourceSchemaName(),
                triggerHistory.getSourceTableName(), triggerHistory.getParsedColumnNames(), triggerHistory.getParsedPkColumnNames());
        for (IReloadVariableFilter filter : extensionService.getExtensionPointList(IReloadVariableFilter.class)) {
            sql = filter.filterPurgeSql(sql, targetNode, table);
        }

        String channelId = getReloadChannelIdForTrigger(triggerRouter.getTrigger(), engine
                .getConfigurationService().getChannels(false));
        Data data = new Data(triggerHistory.getSourceTableName(), DataEventType.SQL,
                CsvUtils.escapeCsvData(sql), null, triggerHistory, channelId, null, null);
        data.setNodeList(targetNode.getNodeId());
        if (isLoad) {
            insertDataAndDataEventAndOutgoingBatch(transaction, data, targetNode.getNodeId(),
                    triggerRouter.getRouter().getRouterId(), isLoad, loadId, createBy, Status.NE, null, -1);
        } else {
            insertData(transaction, data);
        }
    }
    
    public void insertSqlEvent(Node targetNode, String sql, boolean isLoad, long loadId,
            String createBy) {
        TriggerHistory history = engine.getTriggerRouterService()
                .findTriggerHistoryForGenericSync();
        Trigger trigger = engine.getTriggerRouterService().getTriggerById(history.getTriggerId(),
                false);
        String reloadChannelId = getReloadChannelIdForTrigger(trigger, engine
                .getConfigurationService().getChannels(false));

        Data data = new Data(history.getSourceTableName(), DataEventType.SQL,
                CsvUtils.escapeCsvData(sql), null, history, isLoad ? reloadChannelId
                        : Constants.CHANNEL_CONFIG, null, null);
        data.setNodeList(targetNode.getNodeId());
        if (isLoad) {
            insertDataAndDataEventAndOutgoingBatch(data, targetNode.getNodeId(),
                    Constants.UNKNOWN_ROUTER_ID, isLoad, loadId, createBy);
        } else {
            insertData(data);
        }
    }

    public void insertSqlEvent(ISqlTransaction transaction, Node targetNode, String sql,
            boolean isLoad, long loadId, String createBy) {
        TriggerHistory history = engine.getTriggerRouterService()
                .findTriggerHistoryForGenericSync();
        insertSqlEvent(transaction, history, Constants.CHANNEL_CONFIG, targetNode, sql, isLoad,
                loadId, createBy);
    }

    public void insertSqlEvent(ISqlTransaction transaction, TriggerHistory history,
            String channelId, Node targetNode, String sql, boolean isLoad, long loadId,
            String createBy) {
        Trigger trigger = engine.getTriggerRouterService().getTriggerById(history.getTriggerId(),
                false);
        String reloadChannelId = getReloadChannelIdForTrigger(trigger, engine
                .getConfigurationService().getChannels(false));
        Data data = new Data(history.getSourceTableName(), DataEventType.SQL,
                CsvUtils.escapeCsvData(sql), null, history, isLoad ? reloadChannelId : channelId,
                null, null);
        data.setNodeList(targetNode.getNodeId());
        if (isLoad) {
            insertDataAndDataEventAndOutgoingBatch(transaction, data, targetNode.getNodeId(),
                    Constants.UNKNOWN_ROUTER_ID, isLoad, loadId, createBy, Status.NE, null, -1);
        } else {
            insertData(transaction, data);
        }
    }

    public void insertScriptEvent(String channelId, Node targetNode, String script, boolean isLoad,
            long loadId, String createBy) {
        ISqlTransaction transaction = null;
        try {
            transaction = platform.getSqlTemplate().startSqlTransaction();
            insertScriptEvent(transaction, channelId, targetNode, script, isLoad, loadId, createBy);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            if (transaction != null) {
                transaction.close();
            }
        }
    }

    public void insertScriptEvent(ISqlTransaction transaction, String channelId,
            Node targetNode, String script, boolean isLoad, long loadId, String createBy) {
        TriggerHistory history = engine.getTriggerRouterService()
                .findTriggerHistoryForGenericSync();
        Trigger trigger = engine.getTriggerRouterService().getTriggerById(history.getTriggerId(),
                false);
        String reloadChannelId = getReloadChannelIdForTrigger(trigger, engine
                .getConfigurationService().getChannels(false));
        Data data = new Data(history.getSourceTableName(), DataEventType.BSH,
                CsvUtils.escapeCsvData(script), null, history,
                isLoad ? reloadChannelId : channelId, null, null);
        data.setNodeList(targetNode.getNodeId());
        if (isLoad) {
            insertDataAndDataEventAndOutgoingBatch(transaction, data, targetNode.getNodeId(),
                    Constants.UNKNOWN_ROUTER_ID, isLoad, loadId, createBy, Status.NE, null, -1);
        } else {
            insertData(transaction, data);
        }
    }

    public int countDataInRange(long firstDataId, long secondDataId) {
        return sqlTemplate.queryForInt(getSql("countDataInRangeSql"), firstDataId, secondDataId);
    }

    @Override
    public void insertCreateEvent(final Node targetNode, TriggerHistory triggerHistory, String routerId,
            boolean isLoad, long loadId, String createBy) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            insertCreateEvent(transaction, targetNode, triggerHistory, routerId, isLoad, loadId,
                    createBy);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    @Override
    public void insertCreateEvent(Node targetNode, TriggerHistory triggerHistory, String routerId, String createBy) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            Trigger trigger = engine.getTriggerRouterService().getTriggerById(triggerHistory.getTriggerId(), false);
            insertCreateEvent(transaction, targetNode, triggerHistory, trigger.getChannelId(), routerId, false, -1, createBy);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    protected void insertCreateEvent(ISqlTransaction transaction, Node targetNode,
            TriggerHistory triggerHistory, String routerId, boolean isLoad, long loadId, String createBy) {
        Trigger trigger = engine.getTriggerRouterService().getTriggerById(
                triggerHistory.getTriggerId(), false);
        String reloadChannelId = getReloadChannelIdForTrigger(trigger, engine
                .getConfigurationService().getChannels(false));
        insertCreateEvent(transaction, targetNode, triggerHistory, isLoad ? reloadChannelId
                : Constants.CHANNEL_CONFIG, routerId, isLoad, loadId, createBy);
    }
    
    @Override
    public void insertCreateEvent(ISqlTransaction transaction, Node targetNode,
            TriggerHistory triggerHistory, String channelId, String routerId, boolean isLoad, long loadId, String createBy) {

        Data data = new Data(triggerHistory.getSourceTableName(), DataEventType.CREATE,
                null, null, triggerHistory, channelId, null, null);
        data.setNodeList(targetNode.getNodeId());
        try {
            if (isLoad) {
                insertDataAndDataEventAndOutgoingBatch(transaction, data, targetNode.getNodeId(),
                        routerId, isLoad, loadId, createBy, Status.NE, null, -1);
            } else {
                insertData(transaction, data);
            }
        } catch (UniqueKeyException e) {
            if (e.getRootCause() != null && e.getRootCause() instanceof DataTruncation) {
                log.error("Table data definition XML was too large and failed.  The feature to send table creates during the initial load may be limited on your platform.  You may need to set the initial.load.create.first parameter to false.");
            }
            throw e;
        }
    }

    public long insertData(Data data) {
        ISqlTransaction transaction = null;
        long dataId = -1;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            dataId = insertData(transaction, data);
            transaction.commit();
            return dataId;
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    protected long insertData(ISqlTransaction transaction, final Data data) {
        long id = transaction.insertWithGeneratedKey(
                getSql("insertIntoDataSql"),
                symmetricDialect.getSequenceKeyName(SequenceIdentifier.DATA),
                symmetricDialect.getSequenceName(SequenceIdentifier.DATA),
                new Object[] {
                        data.getTableName(),
                        data.getDataEventType().getCode(),
                        data.getRowData(),
                        data.getPkData(),
                        data.getOldData(),
                        data.getTriggerHistory() != null ? data.getTriggerHistory()
                                .getTriggerHistoryId() : -1, data.getChannelId(),
                        data.getExternalData(), data.getNodeList(), data.isPreRouted() ? 1 : 0 }, new int[] { Types.VARCHAR,
                        Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.NUMERIC,
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.NUMERIC });
        data.setDataId(id);
        return id;
    }

    protected void insertDataEvent(ISqlTransaction transaction, DataEvent dataEvent) {
        insertDataEvent(transaction, dataEvent.getDataId(), dataEvent.getBatchId(),
                dataEvent.getRouterId());
    }

    protected void insertDataEvent(ISqlTransaction transaction, long dataId, long batchId,
            String routerId) {
        try {
            transaction
                    .prepareAndExecute(getSql("insertIntoDataEventSql"),
                            new Object[] {
                                    dataId,
                                    batchId,
                                    StringUtils.isBlank(routerId) ? Constants.UNKNOWN_ROUTER_ID
                                            : routerId }, new int[] { Types.NUMERIC, Types.NUMERIC,
                                    Types.VARCHAR });
        } catch (RuntimeException ex) {
            throw new RuntimeException(String.format("Could not insert a data event: data_id=%s batch_id=%s router_id=%s",
                    dataId, batchId, routerId ), ex);
        }
    }

    public void insertDataEvents(ISqlTransaction transaction, final List<DataEvent> events) {
        if (events.size() > 0) {
            transaction.prepare(getSql("insertIntoDataEventSql"));
            for (DataEvent dataEvent : events) {
                String routerId = dataEvent.getRouterId();
                transaction.addRow(
                        dataEvent,
                        new Object[] {
                                dataEvent.getDataId(),
                                dataEvent.getBatchId(),
                                StringUtils.isBlank(routerId) ? Constants.UNKNOWN_ROUTER_ID
                                        : routerId }, new int[] { Types.NUMERIC, Types.NUMERIC,
                                Types.VARCHAR });
            }
            transaction.flush();
        }
    }

    public void insertDataAndDataEventAndOutgoingBatch(Data data, String channelId,
            List<Node> nodes, String routerId, boolean isLoad, long loadId, String createBy) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            data.setPreRouted(true);
            long dataId = insertData(transaction, data);
            for (Node node : nodes) {
                insertDataEventAndOutgoingBatch(transaction, dataId, channelId, node.getNodeId(),
                        data.getDataEventType(), routerId, isLoad, loadId, createBy, Status.NE, data.getTableName(), -1);
            }
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    /**
     * @return The inserted batch id
     */
    public long insertDataAndDataEventAndOutgoingBatch(Data data, String nodeId, String routerId,
            boolean isLoad, long loadId, String createBy) {
        long batchId = 0;
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            batchId = insertDataAndDataEventAndOutgoingBatch(transaction, data, nodeId, routerId,
                    isLoad, loadId, createBy, Status.NE, null, -1);
            transaction.commit();
            return batchId;
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    /**
     * @param estimatedBatchRowCount TODO
     * @return The inserted batch id
     */
    public long insertDataAndDataEventAndOutgoingBatch(ISqlTransaction transaction, Data data, String nodeId, String routerId, boolean isLoad,
            long loadId, String createBy, Status status, String overrideChannelId, long estimatedBatchRowCount) {
        data.setPreRouted(true);
        long dataId = insertData(transaction, data);
        String channelId = null;
        if (isLoad) {
            if (overrideChannelId != null) {
                channelId = overrideChannelId;
            } else {
                TriggerHistory history = data.getTriggerHistory();
                if (history != null && channelId == null) {
                    Trigger trigger = engine.getTriggerRouterService().getTriggerById(history.getTriggerId(), false);
                    channelId = getReloadChannelIdForTrigger(trigger, engine.getConfigurationService().getChannels(false));
                }
            }
        } else {
            channelId = data.getChannelId();
        }
        return insertDataEventAndOutgoingBatch(transaction, dataId, channelId, nodeId, data.getDataEventType(), routerId, isLoad, loadId,
                createBy, status, data.getTableName(), estimatedBatchRowCount);
    }
    
    public long insertDataAndDataEventAndOutgoingBatch(ISqlTransaction transaction, Data data,
            String nodeId, String routerId, boolean isLoad, long loadId, String createBy,
            Status status, long estimatedBatchRowCount) {
    	    return insertDataAndDataEventAndOutgoingBatch(transaction, data, nodeId, routerId, isLoad, loadId, createBy, status, null, estimatedBatchRowCount);
    }

    protected long insertDataEventAndOutgoingBatch(ISqlTransaction transaction, long dataId,
            String channelId, String nodeId, DataEventType eventType, String routerId,
            boolean isLoad, long loadId, String createBy, Status status, String tableName, long estimatedBatchRowCount) {
        OutgoingBatch outgoingBatch = new OutgoingBatch(nodeId, channelId, status);
        outgoingBatch.setLoadId(loadId);
        outgoingBatch.setCreateBy(createBy);
        outgoingBatch.setLoadFlag(isLoad);
        outgoingBatch.incrementRowCount(eventType);
        if (estimatedBatchRowCount > 0) {
            outgoingBatch.setDataRowCount(estimatedBatchRowCount);
        } else {
            outgoingBatch.incrementDataRowCount();
        }
        if (tableName != null) {            
            outgoingBatch.incrementTableCount(tableName.toLowerCase());
        }
        if (status == Status.RQ) {
            outgoingBatch.setExtractJobFlag(true);
        }
        engine.getOutgoingBatchService().insertOutgoingBatch(transaction, outgoingBatch);
        insertDataEvent(transaction, new DataEvent(dataId, outgoingBatch.getBatchId(), routerId));
        return outgoingBatch.getBatchId();
    }

    public String reloadNode(String nodeId, boolean reverseLoad, String createBy) {
        INodeService nodeService = engine.getNodeService();
        Node targetNode = engine.getNodeService().findNode(nodeId);
        if (targetNode == null) {
            return String.format("Unknown node %s", nodeId);
        } else if (reverseLoad
                && nodeService.setReverseInitialLoadEnabled(nodeId, true, true, -1, createBy)) {
            return String.format("Successfully enabled reverse initial load for node %s", nodeId);
        } else if (nodeService.setInitialLoadEnabled(nodeId, true, true, -1, createBy)) {
            return String.format("Successfully enabled initial load for node %s", nodeId);
        } else {
            return String.format("Could not enable initial load for %s", nodeId);
        }
    }
    
    private void insertNodeSecurityUpdate(ISqlTransaction transaction, String nodeIdRecord,
            String targetNodeId, boolean isLoad, long loadId, String createBy, String channelId) {
        Data data = createData(transaction, null, null, tablePrefix + "_node_security",
                " t.node_id = '" + nodeIdRecord + "'");
        if (data != null) {
        	insertDataAndDataEventAndOutgoingBatch(transaction, data, targetNodeId,
                    Constants.UNKNOWN_ROUTER_ID, isLoad, loadId, createBy, Status.NE, channelId, -1);
        } else {
            throw new SymmetricException(String.format("Unable to issue an update for %s_node_security. " + 
                    " Check the %s_trigger_hist for %s_node_security.", tablePrefix, tablePrefix,  tablePrefix ));
        }
    }

    public void sendScript(String nodeId, String script, boolean isLoad) {
        Node targetNode = engine.getNodeService().findNode(nodeId, true);
        TriggerHistory history = engine.getTriggerRouterService()
                .findTriggerHistoryForGenericSync();
        Data data = new Data(history.getSourceTableName(), DataEventType.BSH,
                CsvUtils.escapeCsvData(script), null, history, Constants.CHANNEL_CONFIG, null, null);
        data.setNodeList(nodeId);
        if (!isLoad) {
            insertData(data);
        } else {
            insertDataAndDataEventAndOutgoingBatch(data, targetNode.getNodeId(),
                    Constants.UNKNOWN_ROUTER_ID, isLoad, -1, null);
        }
    }

    public boolean sendSchema(String nodeId, String catalogName, String schemaName,
            String tableName, boolean isLoad) {
        Node sourceNode = engine.getNodeService().findIdentity();
        Node targetNode = engine.getNodeService().findNode(nodeId, true);
        if (targetNode == null) {
            log.error("Could not send schema to the node {}.  The target node does not exist", nodeId);
            return false;
        }

        ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
        List<TriggerHistory> triggerHistories = triggerRouterService.findTriggerHistories(
                catalogName, schemaName, tableName);
        Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId = triggerRouterService
                .fillTriggerRoutersByHistIdAndSortHist(sourceNode.getNodeGroupId(),
                        targetNode.getNodeGroupId(), triggerHistories);
        int eventCount = 0;
        for (TriggerHistory triggerHistory : triggerHistories) {
            List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                    .getTriggerHistoryId());
            for (TriggerRouter triggerRouter : triggerRouters) {
                eventCount++;
                insertCreateEvent(targetNode, triggerHistory, triggerRouter.getRouter().getRouterId(), false, -1, null);
            }
        }

        if (eventCount > 0) {
            return true;
        } else {
            return false;
        }
    }

    public String sendSQL(String nodeId, String catalogName, String schemaName, String tableName,
            String sql) {
        Node sourceNode = engine.getNodeService().findIdentity();
        Node targetNode = engine.getNodeService().findNode(nodeId, true);
        if (targetNode == null) {
            return "Unknown node " + nodeId;
        }

        ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
        TriggerHistory triggerHistory = triggerRouterService.findTriggerHistory(catalogName,
                schemaName, tableName);

        if (triggerHistory == null) {
            return "Trigger for table " + tableName + " does not exist from node "
                    + sourceNode.getNodeGroupId();
        } else {
            Trigger trigger = triggerRouterService.getTriggerById(triggerHistory.getTriggerId());
            if (trigger != null) {
                ISqlTransaction transaction = null;
                try {
                    transaction = sqlTemplate.startSqlTransaction();

                    insertSqlEvent(transaction, triggerHistory, trigger.getChannelId(), targetNode,
                            sql, false, -1, null);
                    transaction.commit();
                    return "Successfully create SQL event for node " + targetNode.getNodeId();
                } catch (Error ex) {
                    if (transaction != null) {
                        transaction.rollback();
                    }
                    throw ex;
                } catch (RuntimeException ex) {
                    if (transaction != null) {
                        transaction.rollback();
                    }
                    throw ex;
                } finally {
                    close(transaction);
                }
            } else {
                return "Trigger for table " + tableName + " does not exist from node "
                        + sourceNode.getNodeGroupId();
            }
        }
    }

    public String sendSQL(String nodeId, String sql) {
        String tableName = TableConstants.getTableName(parameterService.getTablePrefix(), TableConstants.SYM_NODE_HOST);
        Node sourceNode = engine.getNodeService().findIdentity();
        Node targetNode = engine.getNodeService().findNode(nodeId, true);
        if (targetNode == null) {
            return "Unknown node " + nodeId;
        }

        ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
        TriggerHistory triggerHistory = triggerRouterService.findTriggerHistory(null, null, tableName);

        if (triggerHistory == null) {
            return "Trigger for table " + tableName + " does not exist from node "
                    + sourceNode.getNodeGroupId();
        } else {
            Trigger trigger = triggerRouterService.getTriggerById(triggerHistory.getTriggerId());
            if (trigger != null) {
                ISqlTransaction transaction = null;
                try {
                    transaction = sqlTemplate.startSqlTransaction();
                    Data data = new Data(triggerHistory.getSourceTableName(), DataEventType.SQL,
                            CsvUtils.escapeCsvData(sql), null, triggerHistory, Constants.CHANNEL_CONFIG,
                            null, null);
                    data.setNodeList(targetNode.getNodeId());
                    insertDataAndDataEventAndOutgoingBatch(transaction, data, targetNode.getNodeId(),
                            Constants.UNKNOWN_ROUTER_ID, false, -1, null, Status.NE, null, -1);
                    transaction.commit();
                    return "Successfully create SQL event for node " + targetNode.getNodeId();
                } catch (Error ex) {
                    if (transaction != null) {
                        transaction.rollback();
                    }
                    throw ex;
                } catch (RuntimeException ex) {
                    if (transaction != null) {
                        transaction.rollback();
                    }
                    throw ex;
                } finally {
                    close(transaction);
                }
            } else {
                return "Trigger for table " + tableName + " does not exist from node "
                        + sourceNode.getNodeGroupId();
            }
        }
    }

    @Override
    public String reloadTable(String nodeId, String catalogName, String schemaName, String tableName) {
        return reloadTable(nodeId, catalogName, schemaName, tableName, null);
    }

    @Override
    public String reloadTable(String nodeId, String catalogName, String schemaName,
            String tableName, String overrideInitialLoadSelect) {
        return reloadTable(nodeId, catalogName, schemaName, tableName, overrideInitialLoadSelect, null, false);
    }

    @Override
    public String reloadTableImmediate(String nodeId, String catalogName, String schemaName, String tableName, 
            String overrideInitialLoadSelect, String overrideChannelId) {
        return reloadTable(nodeId, catalogName, schemaName, tableName, overrideInitialLoadSelect, overrideChannelId, true);
    }

    protected String reloadTable(String nodeId, String catalogName, String schemaName,
            String tableName, String overrideInitialLoadSelect, String overrideChannelId, boolean isImmediate) {
        Node sourceNode = engine.getNodeService().findIdentity();
        Node targetNode = engine.getNodeService().findNode(nodeId);
        if (targetNode == null) {
            return "Unknown node " + nodeId;
        }

        ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
        List<TriggerHistory> triggerHistories = triggerRouterService.findTriggerHistories(
                catalogName, schemaName, tableName);
        Map<Integer, List<TriggerRouter>> triggerRoutersByHistoryId = triggerRouterService
                .fillTriggerRoutersByHistIdAndSortHist(sourceNode.getNodeGroupId(),
                        targetNode.getNodeGroupId(), triggerHistories);
        int eventCount = 0;
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();

            for (TriggerHistory triggerHistory : triggerHistories) {
                List<TriggerRouter> triggerRouters = triggerRoutersByHistoryId.get(triggerHistory
                        .getTriggerHistoryId());
                if (triggerRouters != null && triggerRouters.size() > 0) {
                    for (TriggerRouter triggerRouter : triggerRouters) {
                        eventCount++;
                        String channelId = overrideChannelId;
                        if (channelId == null) {
                            channelId = getReloadChannelIdForTrigger(triggerRouter.getTrigger(), engine
                                .getConfigurationService().getChannels(false));
                        }
                        
                        if (isImmediate) {
                            insertReloadEventImmediate(transaction, targetNode, triggerRouter, triggerHistory,
                                    overrideInitialLoadSelect, false, -1, "reloadTable", Status.NE, channelId, -1);                            
                        } else {
                            insertReloadEvent(transaction, targetNode, triggerRouter, triggerHistory,
                                    overrideInitialLoadSelect, false, -1, "reloadTable", Status.NE, channelId, -1);
                        }
                    }
                }
            }

            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }

        if (eventCount > 0) {
            return "Successfully created " + (eventCount > 1 ? eventCount + " events" : "event")
                    + " to reload table " + tableName + " for node "

                    + targetNode.getNodeId();
        } else {
            return "Trigger for table " + tableName + " does not exist for source node group of "
                    + sourceNode.getNodeGroupId();
        }

    }

    public void reloadMissingForeignKeyRowsReverse(String sourceNodeId, Table table, CsvData data, String channelId, boolean sendCorrectionToPeers) {
        try {
            Map<String, String> dataMap = data.toColumnNameValuePairs(table.getColumnNames(), CsvData.ROW_DATA);
            List<TableRow> tableRows = new ArrayList<TableRow>();
            Row row = new Row(dataMap.size());
            row.putAll(dataMap);
            
            Table localTable = platform.getTableFromCache(table.getCatalog(), table.getSchema(), table.getName(), false);
            if (localTable == null) {
                log.info("Could not find table " + table.getFullyQualifiedTableName());
            }
            tableRows.add(new TableRow(localTable, row, null, null, null));
            List<TableRow> foreignTableRows = platform.getDdlReader().getImportedForeignTableRows(tableRows, new HashSet<TableRow>(), symmetricDialect.getBinaryEncoding());

            if (foreignTableRows.isEmpty()) {
                log.info("Could not determine foreign table rows to fix foreign key violation for "
                        + "nodeId '{}' table '{}'", sourceNodeId, localTable.getName());
            }

            Collections.reverse(foreignTableRows);
            Set<TableRow> visited = new HashSet<TableRow>();
            Node sourceNode = engine.getNodeService().findNode(sourceNodeId);
            Node identity = engine.getNodeService().findIdentity();
            StringBuilder script = new StringBuilder();
            List<Node> targetNodes = new ArrayList<Node>();
            targetNodes.add(identity);
            
            if (sendCorrectionToPeers) {
                targetNodes.addAll(engine.getNodeService().findEnabledNodesFromNodeGroup(sourceNode.getNodeGroupId()));
                targetNodes.remove(sourceNode);
            }

            for (TableRow foreignTableRow : foreignTableRows) {
                if (visited.add(foreignTableRow)) {
                    Table foreignTable = foreignTableRow.getTable();
                    String catalog = foreignTable.getCatalog();
                    String schema = foreignTable.getSchema();
                    if (StringUtils.equals(platform.getDefaultCatalog(), catalog)) {
                        catalog = null;
                    }
                    if (StringUtils.equals(platform.getDefaultSchema(), schema)) {
                        schema = null;
                    }

                    log.info(
                            "Requesting foreign key correction reload "
                                    + "nodeId {} catalog '{}' schema '{}' foreign table name '{}' fk name '{}' where sql '{}' "
                                    + "to correct table '{}' for column '{}'",
                            sourceNodeId, catalog, schema, foreignTable.getName(), foreignTableRow.getFkName(),
                            foreignTableRow.getWhereSql(), localTable.getName(), foreignTableRow.getReferenceColumnName());

                    for (Node targetNode : targetNodes) {
                        script.append("engine.getDataService().reloadTableImmediate(\"" + targetNode.getNodeId() + "\", " +
                                (catalog == null ? catalog : "\"" + catalog + "\"") + ", " +
                                (schema == null ? schema : "\"" + schema + "\"") + ", \"" +
                                foreignTable.getName().replace("\"", "\\\"") + "\", \"" +
                                foreignTableRow.getWhereSql().replace("\"", "\\\"") + "\", " +
                                (channelId == null ? channelId : "\"" + channelId + "\"") + ");\n");
                    }
                }
            }

            if (script.length() > 0) {
                insertScriptEvent("config", sourceNode, script.toString(), false, -1, "fk");
            }
        } catch (Exception e) {
            log.error("Unknown exception while processing foreign key for node id: " + sourceNodeId, e);
        }
    }

    public void reloadMissingForeignKeyRows(String nodeId, long dataId) {
        try {
            Data data = findData(dataId);
            log.debug("reloadMissingForeignKeyRows for nodeId '{}' dataId '{}' table '{}'", nodeId, dataId, data.getTableName());
            TriggerHistory hist = data.getTriggerHistory();
            Table table = platform.getTableFromCache(hist.getSourceCatalogName(), hist.getSourceSchemaName(), hist.getSourceTableName(), false);
            table = table.copyAndFilterColumns(hist.getParsedColumnNames(), hist.getParsedPkColumnNames(), true);
            Map<String, String> dataMap = data.toColumnNameValuePairs(table.getColumnNames(), CsvData.ROW_DATA);
    
            List<TableRow> tableRows = new ArrayList<TableRow>();
            Row row = new Row(dataMap.size());
            row.putAll(dataMap);
            tableRows.add(new TableRow(table, row, null, null, null));
            List<TableRow> foreignTableRows = platform.getDdlReader().getImportedForeignTableRows(tableRows, new HashSet<TableRow>(), symmetricDialect.getBinaryEncoding());
            
            if (foreignTableRows.isEmpty()) {
                log.info("Could not determine foreign table rows to fix foreign key violation for "
                        + "nodeId '{}' dataId '{}' table '{}'", nodeId, dataId, data.getTableName());
            }
            
            Collections.reverse(foreignTableRows);
            Set<TableRow> visited = new HashSet<TableRow>();
            
            for (TableRow foreignTableRow : foreignTableRows) {
                if (visited.add(foreignTableRow)) {
                    Table foreignTable = foreignTableRow.getTable();
                    String catalog = foreignTable.getCatalog();
                    String schema = foreignTable.getSchema();
                    if (StringUtils.equals(platform.getDefaultCatalog(), catalog)) {
                        catalog = null;
                    }
                    if (StringUtils.equals(platform.getDefaultSchema(), schema)) {
                        schema = null;
                    }
                
                    log.info("Issuing foreign key correction reload "
                            + "nodeId {} catalog '{}' schema '{}' foreign table name '{}' fk name '{}' where sql '{}' "
                            + "to correct dataId '{}' table '{}' for column '{}'",
                            nodeId, catalog, schema, foreignTable.getName(), foreignTableRow.getFkName(), foreignTableRow.getWhereSql(), 
                            dataId, data.getTableName(), foreignTableRow.getReferenceColumnName());
                    reloadTableImmediate(nodeId, catalog, schema, foreignTable.getName(), foreignTableRow.getWhereSql(), null);
                }
            }        
        }
        catch (Exception e) {
            log.error("Unknown exception while processing foreign key for node id: " + nodeId + " data id " + dataId, e);
        }
    }

    /**
     * Because we can't add a trigger on the _node table, we are artificially
     * generating heartbeat events.
     * 
     * @param node
     */
    public void insertHeartbeatEvent(Node node, boolean isReload) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            String tableName = TableConstants.getTableName(tablePrefix, TableConstants.SYM_NODE_HOST);
            List<NodeGroupLink> links = engine.getConfigurationService().getNodeGroupLinksFor(
                    parameterService.getNodeGroupId(), false);
            for (NodeGroupLink nodeGroupLink : links) {
                if (nodeGroupLink.getDataEventAction() == NodeGroupLinkAction.P) {
                    Set<TriggerRouter> triggerRouters = engine.getTriggerRouterService()
                            .getTriggerRouterForTableForCurrentNode(nodeGroupLink, null, null,
                                    tableName, false);
                    if (triggerRouters != null && triggerRouters.size() > 0) {
                        Data data = createData(transaction, triggerRouters.iterator().next()
                                .getTrigger(), String.format(" t.node_id = '%s'", node.getNodeId()));
                        if (data != null) {
                            insertData(transaction, data);
                        } else {
                            log.warn("Not generating data/data events for table {} "
                                    + "because a trigger or trigger hist is not created yet.",
                                    tableName);
                        }
                    } else {
                        log.warn("Not generating data/data events for table {} "
                                + "because a trigger or trigger hist is not created yet.",
                                tableName);
                    }
                }
            }
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }

    }

    public Data createData(String catalogName, String schemaName, String tableName) {
        return createData(catalogName, schemaName, tableName, null);
    }

    public Data createData(String catalogName, String schemaName, String tableName,
            String whereClause) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            Data data = createData(transaction, catalogName, schemaName, tableName, whereClause);
            transaction.commit();
            return data;
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    public Data createData(ISqlTransaction transaction, String catalogName, String schemaName,
            String tableName, String whereClause) {
        Data data = null;
        Set<TriggerRouter> triggerRouters = engine.getTriggerRouterService()
                .getTriggerRouterForTableForCurrentNode(catalogName, schemaName, tableName, false);
        if (triggerRouters != null && triggerRouters.size() > 0) {
            data = createData(transaction, triggerRouters.iterator().next().getTrigger(),
                    whereClause);
        }
        return data;
    }

    protected Data createData(ISqlTransaction transaction, Trigger trigger, String whereClause) {
        Data data = null;
        if (trigger != null) {
            TriggerHistory triggerHistory = engine.getTriggerRouterService()
                    .getNewestTriggerHistoryForTrigger(trigger.getTriggerId(),
                            trigger.getSourceCatalogName(), trigger.getSourceSchemaName(),
                            trigger.getSourceTableName());
            if (triggerHistory == null) {
                triggerHistory = engine.getTriggerRouterService().findTriggerHistory(
                        trigger.getSourceCatalogName(), trigger.getSourceSchemaName(),
                        trigger.getSourceTableName());
                if (triggerHistory == null) {
                    triggerHistory = engine.getTriggerRouterService().findTriggerHistory(
                            trigger.getSourceCatalogName(), trigger.getSourceSchemaName(),
                            trigger.getSourceTableName().toUpperCase());
                }
            }
            if (triggerHistory != null) {
                String rowData = null;
                String pkData = null;
                if (whereClause != null) {
                    rowData = getCsvDataFor(transaction, trigger, triggerHistory, whereClause, false);
                    pkData = getCsvDataFor(transaction, trigger, triggerHistory, whereClause, true);                    
                }
                data = new Data(trigger.getSourceTableName(), DataEventType.UPDATE, rowData,
                        pkData, triggerHistory, trigger.getChannelId(), null, null);
            }
        }
        return data;
    }
    
    protected String getCsvDataFor(ISqlTransaction transaction, Trigger trigger, TriggerHistory triggerHistory, String whereClause, boolean pkOnly) {
        String data = null;
        String sql = null;
        try {
            if (pkOnly) {
                sql = symmetricDialect.createCsvPrimaryKeySql(trigger, triggerHistory,
                        engine.getConfigurationService().getChannel(trigger.getChannelId()), whereClause);
            } else {
                sql = symmetricDialect.createCsvDataSql(trigger, triggerHistory,
                        engine.getConfigurationService().getChannel(trigger.getChannelId()), whereClause);
            }
        } catch (NotImplementedException e) {
        }
        
        if (isNotBlank(sql)) {
            data = transaction.queryForObject(sql, String.class);
        } else {
            DatabaseInfo databaseInfo = platform.getDatabaseInfo();
            String quote = databaseInfo.getDelimiterToken() == null || !parameterService.is(ParameterConstants.DB_DELIMITED_IDENTIFIER_MODE)
                    ? "" : databaseInfo.getDelimiterToken();
            sql = "select " + triggerHistory.getColumnNames() + " from "
                    + Table.getFullyQualifiedTableName(triggerHistory.getSourceCatalogName(), triggerHistory.getSourceSchemaName(),
                            triggerHistory.getSourceTableName(), quote, databaseInfo.getCatalogSeparator(),
                            databaseInfo.getSchemaSeparator()) + " t where " + whereClause;
            Row row = transaction.queryForRow(sql);
            if (row != null) {
                data = row.csvValue();
            }
        }
        
        if (data != null) {
            data = data.trim();
        }

        return data;
    }

    public long countDataGaps() {
        return sqlTemplate.queryForLong(getSql("countDataGapsSql"));
    }
    
    public List<DataGap> findDataGapsUnchecked() {
        return sqlTemplate.query(getSql("findDataGapsSql"), new ISqlRowMapper<DataGap>() {
            public DataGap mapRow(Row rs) {
                return new DataGap(rs.getLong("start_id"), rs.getLong("end_id"), rs
                        .getDateTime("create_time"));
            }
        });
    }

    public List<DataGap> findDataGaps() {
        final long maxDataToSelect = parameterService
                .getLong(ParameterConstants.ROUTING_LARGEST_GAP_SIZE);
        List<DataGap> gaps = findDataGapsUnchecked();
        boolean lastGapExists = false;
        long lastGapStartId = 0;
        for (DataGap dataGap : gaps) {
            lastGapExists |= dataGap.gapSize() >= maxDataToSelect - 1;
            lastGapStartId = Math.max(lastGapStartId, dataGap.getEndId());
        }

        if (!lastGapExists) {
            if (lastGapStartId == 0) {
                long maxRoutedDataId = findMaxDataEventDataId();
                long minDataId = findMinDataId() - 1; // -1 to make sure the ++ operation doesn't move past a piece of unrouted data.
                // At this point, determine the startId as the GREATER of the smallest known data id 
                // or the largest known data id that was already routed.
                lastGapStartId = Math.max(minDataId, maxRoutedDataId); 
            }
            if (lastGapStartId > -1) {
                lastGapStartId++;
            }
            DataGap gap = new DataGap(lastGapStartId, lastGapStartId + maxDataToSelect);
            log.info("Inserting missing last data gap: {}", gap);
            insertDataGap(gap);
            gaps = findDataGaps();
        }
        return gaps;

    }

    public long findMaxDataEventDataId() {
        return sqlTemplate.queryForLong(getSql("selectMaxDataEventDataIdSql"));
    }
    
    public void insertDataGap(DataGap gap) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            insertDataGap(transaction, gap);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    public void insertDataGap(ISqlTransaction transaction, DataGap gap) {
        log.debug("Inserting data gap: {}", gap);
        transaction.prepareAndExecute(getSql("insertDataGapSql"),
                new Object[] { engine.getClusterService().getServerId(), gap.getStartId(), gap.getEndId(),
                    gap.getCreateTime() }, new int[] {
                        Types.VARCHAR, Types.NUMERIC, Types.NUMERIC, Types.TIMESTAMP });
    }
    
    @Override
    public void deleteDataGap(DataGap gap) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            deleteDataGap(transaction, gap);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }
    
    @Override
    public void deleteDataGap(ISqlTransaction transaction, DataGap gap) {
        log.debug("Deleting data gap: {}", gap);
        int count = transaction.prepareAndExecute(
                getSql("deleteDataGapSql"),
                new Object[] { gap.getStartId(), gap.getEndId() },
                new int[] { symmetricDialect.getSqlTypeForIds(),
                        symmetricDialect.getSqlTypeForIds() });
        if (count == 0) {
            log.error("Failed to delete data gap: {}", gap);
        }
    }

    public void deleteAllDataGaps(ISqlTransaction transaction) {
        transaction.prepareAndExecute(getSql("deleteAllDataGapsSql"));
    }

    public Date findCreateTimeOfEvent(long dataId) {
        return sqlTemplate.queryForObject(getSql("findDataEventCreateTimeSql"), Date.class, dataId);
    }

    public Date findCreateTimeOfData(long dataId) {
        return sqlTemplate.queryForObject(getSql("findDataCreateTimeSql"), Date.class, dataId);
    }
    
    public Date findNextCreateTimeOfDataStartingAt(long dataId) {
        return findCreateTimeOfData(sqlTemplate.queryForObject(getSql("findMinDataSql"), Long.class, dataId));
    }

    /**
     * Get a list of {@link IHeartbeatListener}s that are ready for a heartbeat
     * according to
     * {@link IHeartbeatListener#getTimeBetweenHeartbeatsInSeconds()}
     * 
     * @param force
     *            if true, then return the entire list of
     *            {@link IHeartbeatListener}s
     */
    protected List<IHeartbeatListener> getHeartbeatListeners(boolean force) {
        if (force) {
            return extensionService.getExtensionPointList(IHeartbeatListener.class);
        } else {
            List<IHeartbeatListener> listeners = new ArrayList<IHeartbeatListener>();
            if (listeners != null) {
                long ts = System.currentTimeMillis();
                for (IHeartbeatListener iHeartbeatListener : extensionService.getExtensionPointList(IHeartbeatListener.class)) {
                    Long lastHeartbeatTimestamp = lastHeartbeatTimestamps.get(iHeartbeatListener);
                    if (lastHeartbeatTimestamp == null
                            || lastHeartbeatTimestamp <= ts
                                    - (iHeartbeatListener.getTimeBetweenHeartbeatsInSeconds() * 1000)) {
                        listeners.add(iHeartbeatListener);
                    }
                }
            }
            return listeners;
        }
    }

    protected void updateLastHeartbeatTime(List<IHeartbeatListener> listeners) {
        if (listeners != null) {
            Long ts = System.currentTimeMillis();
            for (IHeartbeatListener iHeartbeatListener : listeners) {
                lastHeartbeatTimestamps.put(iHeartbeatListener, ts);
            }
        }
    }

    /**
     * @see IDataService#heartbeat()
     */
    public void heartbeat(boolean force) {
        List<IHeartbeatListener> listeners = getHeartbeatListeners(force);
        if (listeners.size() > 0) {
            Node me = engine.getNodeService().findIdentity();
            if (me != null) {
                for (IHeartbeatListener l : listeners) {
                    l.heartbeat(me);
                }
                updateLastHeartbeatTime(listeners);
            } else {
                log.debug("Did not run the heartbeat process because the node has not been configured");
            }
        }
    }

    public List<Number> listDataIds(long batchId, String nodeId) {
        return sqlTemplateDirty.query(getSql("selectEventDataIdsSql", getDataOrderBy()),
                new NumberMapper(), batchId, nodeId);
    }

    public List<Data> listData(long batchId, String nodeId, long startDataId, String channelId,
            final int maxRowsToRetrieve) {
        return sqlTemplateDirty.query(getDataSelectSql(batchId, startDataId, channelId),
                maxRowsToRetrieve, this.dataMapper, new Object[] {batchId, nodeId, startDataId}, 
                new int[] { symmetricDialect.getSqlTypeForIds(), Types.VARCHAR, symmetricDialect.getSqlTypeForIds()});
    }

    public Data findData(long dataId) {
        return sqlTemplateDirty.queryForObject(getSql("selectData"), dataMapper, dataId);       
    }
    
    public Data mapData(Row row) {
        return dataMapper.mapRow(row);
    }

    public ISqlReadCursor<Data> selectDataFor(Batch batch) {
        return selectDataFor(batch.getBatchId(), batch.getTargetNodeId(), engine.getConfigurationService()
                .getNodeChannel(batch.getChannelId(), false).getChannel().isContainsBigLob());
    }

    public ISqlReadCursor<Data> selectDataFor(Long batchId, String targetNodeId, boolean isContainsBigLob) {
        return sqlTemplateDirty.queryForCursor(
                getDataSelectSql(batchId, -1l, isContainsBigLob),
                dataMapper, new Object[] { batchId, targetNodeId },
                new int[] { symmetricDialect.getSqlTypeForIds(), Types.VARCHAR });
    }

    public ISqlReadCursor<Data> selectDataFor(Long batchId, String channelId) {
        return sqlTemplateDirty.queryForCursor(getDataSelectByBatchSql(batchId, -1l, channelId),
                dataMapper, new Object[] { batchId }, new int[] { symmetricDialect.getSqlTypeForIds() });
    }

    protected String getDataSelectByBatchSql(long batchId, long startDataId, String channelId) {
        String startAtDataIdSql = startDataId >= 0l ? " and d.data_id >= ? " : "";
        return symmetricDialect.massageDataExtractionSql(
                getSql("selectEventDataByBatchIdSql", startAtDataIdSql, getDataOrderBy()),
                engine.getConfigurationService().getNodeChannel(channelId, false).getChannel().isContainsBigLob());
    }

    protected String getDataSelectSql(long batchId, long startDataId, String channelId) {
        return getDataSelectSql(batchId, startDataId,
                engine.getConfigurationService().getNodeChannel(channelId, false).getChannel().isContainsBigLob());
    }

    protected String getDataSelectSql(long batchId, long startDataId, boolean isContainsBigLob) {
        String startAtDataIdSql = startDataId >= 0l ? " and d.data_id >= ? " : "";
        return symmetricDialect.massageDataExtractionSql(
                getSql("selectEventDataToExtractSql", startAtDataIdSql, getDataOrderBy()), isContainsBigLob);
    }

    protected String getDataOrderBy() {
        String orderBy = "";
        if (parameterService.is(ParameterConstants.DBDIALECT_ORACLE_SEQUENCE_NOORDER, false)) {
            orderBy = " order by d.create_time asc, d.data_id asc";
        } else if (parameterService.is(ParameterConstants.ROUTING_DATA_READER_ORDER_BY_DATA_ID_ENABLED, true)) {
            orderBy = " order by d.data_id asc";
        }
        return orderBy;
    }

    public long findMaxDataId() {
        return sqlTemplateDirty.queryForLong(getSql("selectMaxDataIdSql"));
    }
    
    public long findMinDataId() {
        return sqlTemplateDirty.queryForLong(getSql("selectMinDataIdSql"));
    }
    
    
    @Override
    public void deleteCapturedConfigChannelData() {
        int count = sqlTemplate.update(getSql("deleteCapturedConfigChannelDataSql"));
        if (count > 0) {
            log.info("Deleted {} data rows that were on the config channel", count);
        }
    }

    @Override
    public Map<String, Date> getLastDataCaptureByChannel() {
        Map<String, Date> captureMap = new HashMap<String, Date>();
        LastCaptureByChannelMapper mapper = new LastCaptureByChannelMapper(captureMap);
        sqlTemplate.query(getSql("findLastCaptureTimeByChannelSql"), mapper);
        return mapper.getCaptureMap();
    }
    
    @Override
    public boolean fixLastDataGap() {
        boolean fixed = false;
        long maxDataId = findMaxDataId();
        List<DataGap> gaps = findDataGaps();
        if (gaps.size() > 0) {
            DataGap lastGap = gaps.get(gaps.size()-1);
            if (lastGap.getEndId() < maxDataId) {
                fixed = true;
                log.warn("The last data id of {} was bigger than the last gap's end_id of {}.  Increasing the gap size", maxDataId, lastGap.getEndId());
                final long maxDataToSelect = parameterService
                        .getLong(ParameterConstants.ROUTING_LARGEST_GAP_SIZE);
                ISqlTransaction transaction = null;
                try {
                    transaction = sqlTemplate.startSqlTransaction();
                    deleteDataGap(transaction, lastGap);
                    insertDataGap(transaction, new DataGap(lastGap.getStartId(), maxDataId+maxDataToSelect));
                    transaction.commit();
                } catch (Error ex) {
                    if (transaction != null) {
                        transaction.rollback();
                    }
                    throw ex;
                } catch (RuntimeException ex) {
                    if (transaction != null) {
                        transaction.rollback();
                    }
                    throw ex;
                } finally {
                    close(transaction);
                }
            }
        }
        return fixed;
    }

    protected void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    public class DataMapper implements ISqlRowMapper<Data> {
        public Data mapRow(Row row) {
            Data data = new Data();
            String rowData = row.getString("ROW_DATA", false);
            data.putCsvData(CsvData.ROW_DATA, isNotBlank(rowData) ? rowData : null);
            String pkData = row.getString("PK_DATA", false);
            data.putCsvData(CsvData.PK_DATA, isNotBlank(pkData) ? pkData : null);
            String oldData = row.getString("OLD_DATA", false);
            data.putCsvData(CsvData.OLD_DATA, isNotBlank(oldData) ? oldData : null);
            data.putAttribute(CsvData.ATTRIBUTE_CHANNEL_ID, row.getString("CHANNEL_ID"));
            data.putAttribute(CsvData.ATTRIBUTE_TX_ID, row.getString("TRANSACTION_ID", false));
            String tableName = row.getString("TABLE_NAME");
            data.putAttribute(CsvData.ATTRIBUTE_TABLE_NAME, tableName);
            data.setDataEventType(DataEventType.getEventType(row.getString("EVENT_TYPE")));
            data.putAttribute(CsvData.ATTRIBUTE_SOURCE_NODE_ID, row.getString("SOURCE_NODE_ID"));
            data.putAttribute(CsvData.ATTRIBUTE_EXTERNAL_DATA, row.getString("EXTERNAL_DATA"));
            data.putAttribute(CsvData.ATTRIBUTE_NODE_LIST, row.getString("NODE_LIST"));
            data.putAttribute(CsvData.ATTRIBUTE_DATA_ID, row.getLong("DATA_ID"));
            data.putAttribute(CsvData.ATTRIBUTE_CREATE_TIME, row.getDateTime("CREATE_TIME"));
            data.putAttribute(CsvData.ATTRIBUTE_ROUTER_ID, row.getString("ROUTER_ID", false));
            int triggerHistId = row.getInt("TRIGGER_HIST_ID");
            data.putAttribute(CsvData.ATTRIBUTE_TABLE_ID, triggerHistId);
            TriggerHistory triggerHistory = engine.getTriggerRouterService().getTriggerHistory(
                    triggerHistId);
            if (triggerHistory == null) {
                Trigger trigger = null;
                Table table = null;
                List<TriggerRouter> triggerRouters = engine.getTriggerRouterService().getAllTriggerRoutersForCurrentNode(engine.getNodeService().findIdentity().getNodeGroupId());
                for (TriggerRouter triggerRouter : triggerRouters) {
                    if (triggerRouter.getTrigger().getSourceTableName().equalsIgnoreCase(tableName)) {
                        trigger = triggerRouter.getTrigger();
                        table = platform.getTableFromCache(trigger.getSourceCatalogName(), trigger.getSourceSchemaName(), tableName, false);
                        break;
                    }
                }
                
                if (table != null && trigger != null) {
                    List<TriggerHistory> activeTriggerHistories = engine.getTriggerRouterService().getActiveTriggerHistories();
                    triggerHistory = new TriggerHistory(table, trigger, engine.getSymmetricDialect().getTriggerTemplate());
                    triggerHistory.setTriggerHistoryId(triggerHistId);
                    triggerHistory.setLastTriggerBuildReason(TriggerReBuildReason.TRIGGER_HIST_MISSIG);
                    triggerHistory.setNameForInsertTrigger(engine.getTriggerRouterService().getTriggerName(DataEventType.INSERT,
                            symmetricDialect.getMaxTriggerNameLength(), trigger, table, activeTriggerHistories, null));
                    triggerHistory.setNameForUpdateTrigger(engine.getTriggerRouterService().getTriggerName(DataEventType.UPDATE,
                            symmetricDialect.getMaxTriggerNameLength(), trigger, table, activeTriggerHistories, null));
                    triggerHistory.setNameForDeleteTrigger(engine.getTriggerRouterService().getTriggerName(DataEventType.DELETE,
                            symmetricDialect.getMaxTriggerNameLength(), trigger, table, activeTriggerHistories, null));
                    engine.getTriggerRouterService().insert(triggerHistory);
                    log.warn("Could not find a trigger history row for the table {} for data_id {}.  \"Attempting\" to generate a new trigger history row", tableName, data.getDataId());
                } else {
                    triggerHistory = new TriggerHistory(-1);
                    log.warn("A captured data row could not be matched with an existing trigger history "
                            + "row and we could not find a matching trigger.  The data_id of {} (table {}) will be ignored", data.getDataId(), data.getTableName());
                }
            } else {
                if (!triggerHistory.getSourceTableName().equals(data.getTableName())) {
                    log.warn("There was a mismatch between the data table name {} and the trigger_hist "
                            + "table name {} for data_id {}.  Attempting to look up a valid trigger_hist row by table name",
                            new Object[] { data.getTableName(),
                                    triggerHistory.getSourceTableName(), data.getDataId() });
                    List<TriggerHistory> list = engine.getTriggerRouterService()
                            .getActiveTriggerHistories(data.getTableName());
                    if (list.size() > 0) {
                        triggerHistory = list.get(0);
                    }
                }
            }
            data.setTriggerHistory(triggerHistory);
            data.setPreRouted(row.getBoolean("IS_PREROUTED"));
            return data;
        }
    }
    
    public static class LastCaptureByChannelMapper implements ISqlRowMapper<String> {
        private Map<String, Date> captureMap;
        
        public LastCaptureByChannelMapper(Map<String, Date> map) {
            captureMap = map;
        }
        
        public Map<String, Date> getCaptureMap() {
            return captureMap;
        }
        
        @Override
        public String mapRow(Row row) {
            captureMap.put(row.getString("CHANNEL_ID"), row.getDateTime("CREATE_TIME"));
            return null;
        }
    }
}
