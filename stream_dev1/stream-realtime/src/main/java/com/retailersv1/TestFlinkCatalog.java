package com.retailersv1;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.hive.HiveCatalog;

/**
 * @Package com.sdy.retailersv1.TestFlinkCatalog
 * @Author danyu-shi
 * @Date 2025/5/7 14:50
 * @description:
 */
public class TestFlinkCatalog {
    public static void main(String[] args) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tenv = StreamTableEnvironment.create(env);

        String createHiveCatalogDDL = "create catalog hive_catalog with (\n" +
                "    'type'='hive',                                      \n" +
                "    'default-database'='default',                       \n" +
                "    'hive-conf-dir'='/Users/zhouhan/dev_env/work_project/java/stream-dev/stream-realtime/src/main/resources'\n" +
                ")";

        HiveCatalog hiveCatalog = new HiveCatalog("hive-catalog", "default", "/Users/zhouhan/dev_env/work_project/java/stream-dev/stream-realtime/src/main/resources");
        tenv.registerCatalog("hive-catalog",hiveCatalog);
        tenv.useCatalog("hive-catalog");
        tenv.executeSql(createHiveCatalogDDL).print();
    }

}
