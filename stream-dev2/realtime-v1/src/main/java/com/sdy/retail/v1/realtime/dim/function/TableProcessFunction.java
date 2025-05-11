package com.sdy.retail.v1.realtime.dim.function;

import com.alibaba.fastjson.JSONObject;
import com.sdy.common.bean.TableProcessDim;
import com.sdy.common.domain.Constant;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.util.Collector;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
/**
 * @Package com.sdy.retail.v1.realtime.dim.function.TableProcessFunction
 * @Author danyu-shi
 * @Date 2025/4/30 8:46
 * @description: 处理主流业务数据和广播流配置数据关联后的逻辑
 */
public class TableProcessFunction extends BroadcastProcessFunction<JSONObject, TableProcessDim, Tuple2<JSONObject, TableProcessDim>> {
    private Map<String, TableProcessDim> configMap = new HashMap<>();
    private MapStateDescriptor<String, TableProcessDim> mapStateDescriptor;


    public TableProcessFunction(MapStateDescriptor<String, TableProcessDim> mapStateDescriptor) {
        this.mapStateDescriptor = mapStateDescriptor;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        //将配置表中的配置信息预加载到程序configMap中
        Class.forName("com.mysql.cj.jdbc.Driver");
        java.sql.Connection conn = DriverManager.getConnection(Constant.MYSQL_URL, Constant.MYSQL_USER_NAME, Constant.MYSQL_PASSWORD);
        String sql = "select * from realtime_v1_config.table_process_dim";

        PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData metaData = rs.getMetaData();
        while (rs.next()) {
            JSONObject jsonObj = new JSONObject();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String columnName = metaData.getColumnName(i);
                Object columnValue = rs.getObject(i);
                jsonObj.put(columnName, columnValue);
            }
            TableProcessDim tableProcessDim = jsonObj.toJavaObject(TableProcessDim.class);
            configMap.put(tableProcessDim.getSourceTable(), tableProcessDim);
        }

        rs.close();
        ps.close();
        conn.close();
    }

    //处理主流业务数据               根据维度表名到广播状态中读取配置信息，判断是否为维度
    @Override
    public void processElement(JSONObject jsonObj, BroadcastProcessFunction<JSONObject, TableProcessDim, Tuple2<JSONObject, TableProcessDim>>.ReadOnlyContext ctx, Collector<Tuple2<JSONObject, TableProcessDim>> out) throws Exception {

        String table = jsonObj.getJSONObject("source").getString("table");
        ReadOnlyBroadcastState<String, TableProcessDim> broadcastState = ctx.getBroadcastState(mapStateDescriptor);
        TableProcessDim tableProcessDim = broadcastState.get(table);
        if (tableProcessDim != null) {
            JSONObject dataJsonObj = jsonObj.getJSONObject("after");
            //在向下游传递数据前，过滤掉不需要传递的属性
            String sinkColumns = tableProcessDim.getSinkColumns();
            deletenotneetclomns(dataJsonObj, sinkColumns);
            //在向下游传递数据前，补充对维度数据的操作类型属性
            String type = jsonObj.getString("op");
            dataJsonObj.put("op", type);
            out.collect(Tuple2.of(dataJsonObj, tableProcessDim));
        }

    }
    //处理广播流配置信息    将配置数据放到广播状态中或者从广播状态中删除对应的配置  k:维度表名     v:一个配置对象

    @Override
    public void processBroadcastElement(TableProcessDim tp, BroadcastProcessFunction<JSONObject, TableProcessDim, Tuple2<JSONObject, TableProcessDim>>.Context ctx, Collector<Tuple2<JSONObject, TableProcessDim>> out) throws Exception {
        String op = tp.getOp();
        //获取广播状态
        BroadcastState<String, TableProcessDim> broadcastState = ctx.getBroadcastState(mapStateDescriptor);
        //获取维度表名称

        String sourceTable = tp.getSourceTable();
        //从配置表中删除了一条数据，将对应的配置信息也从广播状态中删除
        if ("d".equals(op)) {
            broadcastState.remove(sourceTable);
            configMap.remove(sourceTable);
        } else {
            //对配置表进行了读取、添加或者更新操作，将最新的配置信息放到广播状态中

            broadcastState.put(sourceTable, tp);
            configMap.put(sourceTable, tp);
        }
    }
    //过滤掉不需要传递的字段
    //dataJsonObj  {"tm_name":"Redmi","create_time":"2021-12-14 00:00:00","logo_url":"555","id":1}
    //sinkColumns  id,tm_name
    private static void deletenotneetclomns(JSONObject after, String sinkColumns) {
        List<String> list = Arrays.asList(sinkColumns.split(","));
        Set<Map.Entry<String, Object>> entries = after.entrySet();
        entries.removeIf(e -> !list.contains(e.getKey()));
    }
}
