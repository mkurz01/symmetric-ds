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

import java.util.Map;

import org.jumpmind.db.platform.IDatabasePlatform;

public class DataServiceSqlMap extends AbstractSqlMap {

    public DataServiceSqlMap(IDatabasePlatform platform, Map<String, String> replacementTokens) {
        super(platform, replacementTokens);

        putSql("selectTableReloadRequest", "select load_id, reload_select, before_custom_sql, reload_time, create_time, last_update_by, last_update_time from $(table_reload_request) where source_node_id=? and target_node_id=? and trigger_id=? and router_id=? and create_time=?");
        
        putSql("insertTableReloadRequest", "insert into $(table_reload_request) (reload_select, before_custom_sql, create_time, last_update_by, last_update_time, source_node_id, target_node_id, trigger_id, router_id, create_table, delete_first, channel_id) values (?,?,?,?,?,?,?,?,?,?,?,?)");

        putSql("updateTableReloadRequest", "update $(table_reload_request) set reload_select=?, before_custom_sql=?, reload_time=?, create_time=?, last_update_by=?, last_update_time=? where source_node_id=? and target_node_id=? and trigger_id=? and router_id=?");
        
        putSql("deleteTableReloadRequest", "delete from $(table_reload_request) where source_node_id=? and target_node_id=? and trigger_id=? and router_id=?");

        putSql("selectTableReloadRequestToProcess", "select target_node_id, create_table, delete_first, reload_select, before_custom_sql, "
                + " reload_time, channel_id, create_time, last_update_by, "
                + " last_update_time, trigger_id, router_id "
                + " from $(table_reload_request) "
                + " where source_node_id=? and processed = 0 "
                + " order by create_time, target_node_id");
        
        putSql("selectTableReloadRequests", "select source_node_id, target_node_id, load_id, "
                + " trigger_id, router_id, create_time, create_table, delete_first, reload_select, " 
                + " before_custom_sql, reload_time, processed, channel_id, last_update_by, last_update_time "
                + " from $(table_reload_request) "
                + " order by load_id desc, last_update_time desc");

        putSql("selectTableReloadRequestsByLoadId", "select source_node_id, target_node_id, load_id, "
                + " create_table, delete_first, reload_select, channel_id, "
                + " before_custom_sql, processed, "
                + " reload_time, channel_id, create_time, last_update_by, "
                + " last_update_time, trigger_id, router_id "
                + " from $(table_reload_request) "
                + " where load_id = ? "
                + " order by processed, last_update_time desc");

        putSql("selectTableReloadRequestsByLoadIdTriggerRouter", "select source_node_id, target_node_id, load_id, "
                + " create_table, delete_first, reload_select, channel_id, "
                + " before_custom_sql, processed, "
                + " reload_time, channel_id, create_time, last_update_by, "
                + " last_update_time, trigger_id, router_id "
                + " from $(table_reload_request) "
                + " where load_id = ? and trigger_id = ? and router_id = ?"
                + " order by processed, last_update_time desc");

        putSql("selectTableReloadStatus", "select source_node_id, target_node_id, load_id, "
                + " end_data_batch_id, start_data_batch_id, "
                + " setup_batch_count, data_batch_count, finalize_batch_count, " 
                + " setup_batch_loaded, data_batch_loaded, finalize_batch_loaded, " 
                + " table_count, rows_loaded, rows_count, "
                + " completed, cancelled, "
                + " start_time, end_time, last_update_time, last_update_by, "
                + " error_flag, sql_state, sql_code, sql_message "
                + " from $(table_reload_status) "
                + " order by load_id desc, completed, last_update_time desc");
        
        putSql("selectActiveTableReloadStatus", "select source_node_id, target_node_id, load_id, "
                + " end_data_batch_id, start_data_batch_id, "
                + " setup_batch_count, data_batch_count, finalize_batch_count, " 
                + " setup_batch_loaded, data_batch_loaded, finalize_batch_loaded, " 
                + " table_count, rows_loaded, rows_count, "
                + " completed, cancelled, "
                + " start_time, end_time, last_update_time, last_update_by, "
                + " error_flag, sql_state, sql_code, sql_message "
                + " from $(table_reload_status) "
                + " where completed = 0 and cancelled = 0"
                + " order by load_id desc, completed, last_update_time desc");
         
        putSql("selectTableReloadStatusByLoadId", "select source_node_id, target_node_id, load_id, "
                + " end_data_batch_id, start_data_batch_id, "
                + " setup_batch_count, data_batch_count, finalize_batch_count, " 
                + " setup_batch_loaded, data_batch_loaded, finalize_batch_loaded, " 
                + " table_count, rows_loaded, rows_count, "
                + " completed, cancelled, "
                + " start_time, end_time, last_update_time, last_update_by, "
                + " error_flag, sql_state, sql_code, sql_message "
                + " from $(table_reload_status) "
                + " where load_id = ?");

        putSql("selectTableReloadStatusByTargetNodeId", "select source_node_id, target_node_id, load_id, "
                + " end_data_batch_id, start_data_batch_id, "
                + " setup_batch_count, data_batch_count, finalize_batch_count, "
                + " setup_batch_loaded, data_batch_loaded, finalize_batch_loaded, "
                + " table_count, rows_loaded, rows_count, "
                + " completed, cancelled, "
                + " start_time, end_time, last_update_time, last_update_by, "
                + " error_flag, sql_state, sql_code, sql_message "
                + " from $(table_reload_status) "
                + " where target_node_id = ?");
        
        putSql("updateProcessedTableReloadRequest", "update $(table_reload_request) set last_update_time = ?, processed = 1 where load_id = ?");
        
        putSql("updateTableReloadRequestLoadId", "update $(table_reload_request) set load_id = ?, last_update_time = ? where target_node_id = ? and source_node_id = ? and trigger_id = ? and router_id = ? and create_time = ?");
        
        putSql("updateTableReloadStatusTableCount", "update $(table_reload_status) set table_count = ?, last_update_time = ? where load_id = ?");
        
        putSql("updateTableReloadStatusDataCounts", "update $(table_reload_status) set "
                + " start_data_batch_id = ?, end_data_batch_id = ?, "
                + " data_batch_count = data_batch_count + ?, " 
                + " rows_count = rows_count + ?, " 
                + " last_update_time = ?  "
                + " where load_id = ?");
        
        putSql("insertTableReloadStatus", "insert into $(table_reload_status) (load_id, target_node_id, source_node_id, start_time, last_update_time) values (?, ?, ?, ?, ?)");
        
        putSql("deleteTableReloadStatus", "delete from $(table_reload_status) where load_id = ?");
        
        putSql("updateTableReloadStatusSetupCount", "update $(table_reload_status) set "
                + " setup_batch_count = ?, last_update_time = ? "
                + " where load_id = ?");
        
        putSql("updateTableReloadStatusDataLoaded", "update $(table_reload_status) "
                + " set completed = case when ("
                + "    data_batch_count = (case when ? between start_data_batch_id and end_data_batch_id then data_batch_loaded + ? else data_batch_loaded end) and "
                + "    setup_batch_count = (case when ? < start_data_batch_id then setup_batch_loaded + ? else setup_batch_loaded end) and "
                + "    finalize_batch_count = (case when ? > end_data_batch_id then finalize_batch_loaded + ? else finalize_batch_loaded end)) "
                + "    then 1 else 0 end, "
                + " end_time = case when ("
                + "    data_batch_count = (case when ? between start_data_batch_id and end_data_batch_id then data_batch_loaded + ? else data_batch_loaded end) and "
                + "    setup_batch_count = (case when ? < start_data_batch_id then setup_batch_loaded + ? else setup_batch_loaded end) and "
                + "    finalize_batch_loaded = (case when ? > end_data_batch_id then finalize_batch_loaded + ? else finalize_batch_loaded end)) "
                + "    then ? else end_time end, "
                + " data_batch_loaded = case when ? between start_data_batch_id and end_data_batch_id then data_batch_loaded + ? else data_batch_loaded end, "
                + " setup_batch_loaded = case when ? < start_data_batch_id then setup_batch_loaded + ? else setup_batch_loaded end, "
                + " finalize_batch_loaded = case when ? > end_data_batch_id then finalize_batch_loaded + ? else finalize_batch_loaded end, "
                + " rows_loaded = (select case when sum(loaded_rows) is null then 0 else sum(loaded_rows) end from $(extract_request) where load_id = ?), " 
                + " last_update_time = ? "
                + " where load_id = ? and completed = 0");
        
        putSql("updateTableReloadStatusFinalizeCount", "update $(table_reload_status) set "
                + " finalize_batch_count = ?, last_update_time = ? "
                + " where load_id = ?");
        
        putSql("updateTableReloadStatusCancelled", "update $(table_reload_status) set "
                + " cancelled = 1, completed = 1, end_time = ?, last_update_time = ? "
                + " where load_id = ?");
        
        putSql("updateTableReloadStatusError", "update $(table_reload_status) set "
                + " error_flag = 1, sql_code = ?, sql_state = ?, sql_message = ? where load_id = ?");

        // Note that the order by data_id is done appended in code
        putSql("selectEventDataToExtractSql",
                ""
                        + "select d.data_id, d.table_name, d.event_type, d.row_data as row_data, d.pk_data as pk_data, d.old_data as old_data,                                                                          "
                        + "  d.create_time, d.trigger_hist_id, d.channel_id, d.transaction_id, d.source_node_id, d.external_data, d.node_list, d.is_prerouted, e.router_id from $(data) d inner join   "
                        + "  $(data_event) e on d.data_id = e.data_id inner join $(outgoing_batch) o on o.batch_id=e.batch_id                                  "
                        + "  where o.batch_id = ? and o.node_id = ?                                                                                                                                    ");
        
        putSql("selectEventDataByBatchIdSql",
                ""
                        + "select d.data_id, d.table_name, d.event_type, d.row_data as row_data, d.pk_data as pk_data, d.old_data as old_data,                                                                          "
                        + "  d.create_time, d.trigger_hist_id, d.channel_id, d.transaction_id, d.source_node_id, d.external_data, d.node_list, d.is_prerouted, e.router_id from $(data) d inner join   "
                        + "  $(data_event) e on d.data_id = e.data_id inner join $(outgoing_batch) o on o.batch_id=e.batch_id                                  "
                        + "  where o.batch_id = ?    ");
        
        putSql("selectEventDataIdsSql",
                ""
                        + "select d.data_id from $(data) d inner join                                                                 "
                        + "  $(data_event) e on d.data_id = e.data_id inner join $(outgoing_batch) o on o.batch_id=e.batch_id   "
                        + "  where o.batch_id = ? and o.node_id = ?                                                                                           ");

        putSql("selectData",
            "select data_id, table_name, event_type, row_data, pk_data, old_data, " +
            "create_time, trigger_hist_id, channel_id, transaction_id, source_node_id, external_data, node_list, '' as router_id, is_prerouted " +
            "from $(data) where data_id = ?");

        putSql("selectMaxDataEventDataIdSql", ""
                + "select max(data_id) from $(data_event)   ");

        putSql("countDataInRangeSql", ""
                + "select count(*) from $(data) where data_id > ? and data_id < ?   ");

        putSql("insertIntoDataSql",
                "insert into $(data) (data_id, table_name, event_type, row_data, pk_data, " +
                "old_data, trigger_hist_id, channel_id, external_data, node_list, is_prerouted, create_time) values(null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)");

        putSql("insertIntoDataEventSql",
                ""
                        + "insert into $(data_event) (data_id, batch_id, router_id, create_time) values(?, ?, ?, current_timestamp)   ");

        putSql("findDataEventCreateTimeSql", ""
                + "select max(create_time) from $(data_event) where data_id=?   ");        

        putSql("findDataCreateTimeSql", ""
                + "select create_time from $(data) where data_id=?   ");
        
        putSql("findMinDataSql", ""
                + "select min(data_id) from $(data) where data_id >= ?");

        putSql("countDataGapsSql", "select count(*) from $(data_gap)");

        putSql("findDataGapsSql",
                "select start_id, end_id, create_time from $(data_gap) order by start_id asc");

        putSql("insertDataGapSql",
                "insert into $(data_gap) (last_update_hostname, start_id, end_id, create_time) values(?, ?, ?, ?)");

        putSql("deleteDataGapSql",
                        "delete from $(data_gap) where start_id=? and end_id=?   ");

        putSql("deleteAllDataGapsSql", "delete from $(data_gap)");

        putSql("selectMaxDataIdSql", "select max(data_id) from $(data)   ");
        
        putSql("selectMinDataIdSql", "select min(data_id) from $(data)   ");
        
        putSql("deleteCapturedConfigChannelDataSql", "delete from $(data) where channel_id='config'");
        
        putSql("findLastCaptureTimeByChannelSql", 
                "select max(create_time) as create_time, channel_id from $(data) group by channel_id ");        

    }

}